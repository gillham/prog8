package prog8.codegen.llvm

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import prog8.code.core.BaseDataType
import prog8.code.core.DataType
import prog8.code.target.LlvmTarget
import prog8.code.target.LlvmTripleProfile
import prog8.intermediate.IMSyscall
import prog8.intermediate.IRStSymbolicReference
import prog8.intermediate.IRVariableInitializer
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import prog8tests.helpers.hostLlvmTarget


class TestLlvmSyscallLowering: FunSpec({

    test("syscall 2 writes a character with C's putchar, zero extended to an int") {
        val module = TestProgram().apply { printCharSub() }.module()
        val lines = module.function("main.printchar").lines()
        lines shouldHave "  %r1.1 = load i8, ptr %r1.slot"
        lines shouldHave "  %ch.1 = zext i8 %r1.1 to i32"
        lines shouldHave "  call i32 @putchar(i32 %ch.1)"
        val text = LlvmPrinter.print(module).lines()
        text shouldHave "declare i32 @putchar(i32)"
        text.none { ".print.string" in it } shouldBe true
    }

    test("syscall 3 writes a zero-terminated string through the internal .print.string helper, which calls putchar") {
        val module = TestProgram().apply { printStringSub() }.module()
        val lines = module.function("main.printstring").lines()
        lines shouldHave "  %r1.3 = inttoptr i64 %r1.2 to ptr"
        lines shouldHave "  call void @.print.string(ptr %r1.3)"
        lines shouldHave "  %r2.1 = load ptr, ptr %r2.slot"
        lines shouldHave "  call void @.print.string(ptr %r2.1)"
        module.function(".print.string").text() shouldBe """
            define internal void @.print.string(ptr %str) {
            entry:
              br label %loop

            loop:
              %curr_ptr = phi ptr [ %str, %entry ], [ %next, %print ]
              %ch = load i8, ptr %curr_ptr
              %end = icmp eq i8 %ch, 0
              br i1 %end, label %done, label %print

            print:
              %ch_ext = zext i8 %ch to i32
              call i32 @putchar(i32 %ch_ext)
              %next = getelementptr i8, ptr %curr_ptr, i64 1
              br label %loop

            done:
              ret void
            }
            """.trimIndent() + "\n"
        val text = LlvmPrinter.print(module).lines()
        text.count { it == "declare i32 @putchar(i32)" } shouldBe 1
        text.count { it.startsWith("define internal void @.print.string(") } shouldBe 1
    }

    test("syscall 69 reads a character with C's getchar, whose negative end of input becomes 0") {
        val module = TestProgram().apply { readCharSub() }.module()
        val lines = module.function("main.readchar").lines()
        lines shouldHave "  %ch.1 = call i32 @getchar()"
        lines shouldHave "  %smax.1 = call i32 @llvm.smax.i32(i32 %ch.1, i32 0)"
        lines shouldHave "  %ch.2 = trunc i32 %smax.1 to i8"
        lines shouldHave "  store i8 %ch.2, ptr %r1.slot"
        LlvmPrinter.print(module).lines() shouldHave "declare i32 @getchar()"
    }

    test("the input syscall reads stdin in a native program for the host, and gives 0 at its end").config(enabledOrReasonIf = requiresNativeHost) {
        val module = TestProgram(hostLlvmTarget).apply { readCharSub(); echoSub() }.module().withMainCalling("main.echo")
        val (exitCode, output) = compileAndRun(LlvmPrinter.print(module), input = "hi\n")
        withClue(output) { exitCode shouldBe 0 }
        output shouldBe "hi\n"
    }

    test("a program without output syscalls gets neither the putchar declaration nor the string helper") {
        val text = LlvmPrinter.print(TestProgram().apply { silentSub() }.module())
        withClue("putchar") { ("putchar" in text) shouldBe false }
        withClue(".print.string") { (".print.string" in text) shouldBe false }
    }

    test("llvm-as accepts a module with both output syscalls").config(enabledOrReasonIf = requiresLlvmAs) {
        val (exitCode, output) = runLlvmAs(LlvmPrinter.print(TestProgram().apply { allOutputSubs() }.module()))
        withClue(output) { exitCode shouldBe 0 }
    }

    test("the output syscalls write to stdout in a native program for the host").config(enabledOrReasonIf = requiresNativeHost) {
        val module = TestProgram(hostLlvmTarget).apply { allOutputSubs(); greetingSub() }.module().withMainCalling("main.greeting")
        val (exitCode, output) = compileAndRun(LlvmPrinter.print(module))
        withClue(output) { exitCode shouldBe 0 }
        output shouldBe "A\nhi\nhello, world!\nhi\n"
    }

    test("the C entry point stores argc and argv in their globals, also when the program doesn't ask for the command line arguments") {
        val module = TestProgram().apply { emptyStartSub() }.programModule()
        module.functions.last().name shouldBe "main"
        module.function("main").text() shouldBe """
            define i32 @main(i32 %argc, ptr %argv) {
            entry:
              store i32 %argc, ptr @.sys.argc
              store ptr %argv, ptr @.sys.argv
              call void @main.start()
              ret i32 0
            }
            """.trimIndent() + "\n"
        val text = LlvmPrinter.print(module).lines()
        text shouldHave "@.sys.argc = internal global i32 0"
        text shouldHave "@.sys.argv = internal global ptr null"
    }

    test("syscall 200 gives argc saturated to a word, syscall 201 the argument at an index through the .sys.argv.at helper") {
        val module = TestProgram().apply { argumentLookupSub() }.programModule()
        val lines = module.function("main.start").lines()
        lines shouldHave "  %argc.1 = load i32, ptr @.sys.argc"
        lines shouldHave "  %umin.1 = call i32 @llvm.umin.i32(i32 %argc.1, i32 65535)"
        lines shouldHave "  %argc.2 = trunc i32 %umin.1 to i16"
        lines shouldHave "  store i16 %argc.2, ptr %r1.slot"
        lines shouldHave "  %r1.1 = load i16, ptr %r1.slot"
        lines shouldHave "  %argv.1 = call ptr @.sys.argv.at(i16 %r1.1)"
        lines shouldHave "  store ptr %argv.1, ptr %r2.slot"
        module.function(".sys.argv.at").text() shouldBe """
            define internal ptr @.sys.argv.at(i16 %index) {
            entry:
              %i = zext i16 %index to i64
              %argc = load i32, ptr @.sys.argc
              %n = zext i32 %argc to i64
              %inrange = icmp ult i64 %i, %n
              br i1 %inrange, label %present, label %absent

            present:
              %argv = load ptr, ptr @.sys.argv
              %slot = getelementptr ptr, ptr %argv, i64 %i
              %argument = load ptr, ptr %slot
              ret ptr %argument

            absent:
              ret ptr @.sys.argv.none
            }
            """.trimIndent() + "\n"
        LlvmPrinter.print(module).lines() shouldHave "@.sys.argv.none = internal constant [1 x i8] zeroinitializer"
    }

    test("llvm-as accepts the C entry point and the command line argument syscalls").config(enabledOrReasonIf = requiresLlvmAs) {
        for (module in listOf(TestProgram().apply { emptyStartSub() }.programModule(), TestProgram().apply { argumentsOutputSub() }.programModule())) {
            val (exitCode, output) = runLlvmAs(LlvmPrinter.print(module))
            withClue(output) { exitCode shouldBe 0 }
        }
    }

    test("a native program for the host gets its command line arguments, and an empty string for an index past them").config(enabledOrReasonIf = requiresNativeHost) {
        val module = TestProgram(hostLlvmTarget).apply { argumentsOutputSub() }.programModule()
        val (exitCode, output) = compileAndRun(LlvmPrinter.print(module), args = listOf("first", "second arg"))
        withClue(output) { exitCode shouldBe 3 }
        val lines = output.lines()
        lines.take(4) shouldBe listOf("first", "second arg", "", "")
        withClue("argument 0 is the program: ${lines[4]}") { lines[4].replace('\\', '/').substringAfterLast('/').removeSuffix(".exe") shouldBe "program" }
        lines.drop(5) shouldBe listOf("")
    }

    test("the file syscalls call internal helpers with a stream and an fopen() mode, the filesystem syscalls call the C library directly") {
        val module = TestProgram().apply { allFileSyscallsSub() }.module()
        val lines = module.function("main.files").lines()
        lines shouldHave "  %end.1 = call ptr @.file.load(ptr %r1.1, ptr %r2.1, i1 false)"
        lines shouldHave "  %end.2 = call ptr @.file.load(ptr %r1.2, ptr %r2.2, i1 true)"
        lines shouldHave "  %raw.1 = icmp ne i8 %r5.1, 0"
        lines shouldHave "  %cast.1 = zext i16 %r6.1 to i64"
        lines shouldHave "  %saved.1 = call i8 @.file.save(i1 %raw.1, ptr %r1.3, ptr %r2.3, i64 %cast.1)"
        lines shouldHave "  %status.1 = call i32 @remove(ptr %r1.4)"
        lines shouldHave "  %status.2 = call i32 @rename(ptr %r1.5, ptr %r1.6)"
        lines shouldHave "  %cwd.1 = call ptr @getcwd(ptr @.sys.curdir.buf, i64 4096)"
        lines shouldHave "  %status.3 = call i32 @mkdir(ptr %r1.7, i32 493)"
        lines shouldHave "  %status.4 = call i32 @chdir(ptr %r1.8)"
        lines shouldHave "  %status.5 = call i32 @rmdir(ptr %r1.9)"
        lines shouldHave "  %opened.1 = call i8 @.file.open(ptr %r1.10, ptr @.file.mode.rb, ptr @.file.read_stream)"
        lines shouldHave "  %opened.2 = call i8 @.file.open(ptr %r1.11, ptr @.file.mode.wb, ptr @.file.write_stream)"
        lines shouldHave "  %byte.1 = call i16 @.file.read.byte()"
        lines shouldHave "  %written.1 = call i8 @.file.write.byte(i8 %r5.2)"
        lines shouldHave "  call void @.file.close(ptr @.file.read_stream)"
        lines shouldHave "  call void @.file.close(ptr @.file.write_stream)"
        lines shouldHave "  %r13.2 = trunc i64 %r13.1 to i32"
        lines shouldHave "  %sought.1 = call i8 @.file.seek(i32 %r13.2)"
        lines shouldHave "  %position.1 = call i32 @.file.tell()"
        lines shouldHave "  %size.1 = call i32 @.file.size()"
    }

    test("the C library routines are declared with their 64-bit host signatures, and the streams start out as null pointers") {
        val text = LlvmPrinter.print(TestProgram(lp64Target).apply { allFileSyscallsSub() }.module()).lines()
        for (declaration in listOf("ptr @fopen(ptr, ptr)", "i32 @fclose(ptr)", "i32 @fgetc(ptr)", "i32 @fputc(i32, ptr)",
                "i64 @fread(ptr, i64, i64, ptr)", "i64 @fwrite(ptr, i64, i64, ptr)", "i32 @fseek(ptr, i64, i32)", "i64 @ftell(ptr)",
                "i32 @ferror(ptr)", "i32 @remove(ptr)", "i32 @rename(ptr, ptr)", "ptr @getcwd(ptr, i64)", "i32 @mkdir(ptr, i32)",
                "i32 @chdir(ptr)", "i32 @rmdir(ptr)"))
            text shouldHave "declare $declaration"
        text shouldHave "@.file.read_stream = internal global ptr null"
        text shouldHave "@.file.write_stream = internal global ptr null"
        text shouldHave """@.file.mode.rb = private unnamed_addr constant [3 x i8] c"rb\00""""
        text shouldHave """@.file.mode.wb = private unnamed_addr constant [3 x i8] c"wb\00""""
        text shouldHave "@.sys.curdir.buf = internal global [4096 x i8] zeroinitializer"
        for (helper in listOf("ptr @.file.load(ptr %name, ptr %address, i1 %raw)", "i8 @.file.save(i1 %raw, ptr %name, ptr %start, i64 %size)",
                "i8 @.file.open(ptr %name, ptr %mode, ptr %stream)", "void @.file.close(ptr %stream)", "i16 @.file.read.byte()",
                "i8 @.file.write.byte(i8 %byte)", "i8 @.file.seek(i32 %position)", "i32 @.file.tell()", "i32 @.file.size()"))
            text shouldHave "define internal $helper {"
    }

    test("a program without file syscalls declares none of their C library routines") {
        val text = LlvmPrinter.print(TestProgram().apply { silentSub() }.module())
        for (name in listOf("fopen", "fclose", "remove", "getcwd", ".file."))
            withClue(name) { (name in text) shouldBe false }
    }

    test("opening a file keeps the stream there was when fopen() fails, and closes it when the new one opens") {
        TestProgram().apply { allFileSyscallsSub() }.module().function(".file.open").text() shouldBe """
            define internal i8 @.file.open(ptr %name, ptr %mode, ptr %stream) {
            entry:
              %file = call ptr @fopen(ptr %name, ptr %mode)
              %failed = icmp eq ptr %file, null
              br i1 %failed, label %fail, label %opened

            opened:
              %previous = load ptr, ptr %stream
              %open = icmp ne ptr %previous, null
              br i1 %open, label %close, label %keep

            close:
              call i32 @fclose(ptr %previous)
              br label %keep

            keep:
              store ptr %file, ptr %stream
              ret i8 1

            fail:
              ret i8 0
            }
            """.trimIndent() + "\n"
    }

    test("reading a byte gives ${'$'}0100 plus the byte, like in the VM, or 0 at the end of the file and without a read stream") {
        TestProgram().apply { allFileSyscallsSub() }.module().function(".file.read.byte").text() shouldBe """
            define internal i16 @.file.read.byte() {
            entry:
              %file = load ptr, ptr @.file.read_stream
              %open = icmp ne ptr %file, null
              br i1 %open, label %read, label %none

            read:
              %char = call i32 @fgetc(ptr %file)
              %end = icmp slt i32 %char, 0
              br i1 %end, label %none, label %got

            got:
              %byte = trunc i32 %char to i16
              %value = or i16 %byte, 256
              ret i16 %value

            none:
              ret i16 0
            }
            """.trimIndent() + "\n"
    }

    test("a filesystem syscall gives a status byte only when the call site asks for one") {
        val lines = TestProgram().apply { statusSub() }.module().function("main.status").lines()
        lines shouldHave "  %status.1 = call i32 @remove(ptr %r1.1)"
        lines shouldHave "  %status.2 = call i32 @remove(ptr %r1.2)"
        lines shouldHave "  %ok.1 = icmp eq i32 %status.2, 0"
        lines shouldHave "  %ok.2 = zext i1 %ok.1 to i8"
        lines shouldHave "  store i8 %ok.2, ptr %r2.slot"
        lines.count { "icmp eq i32 %status" in it } shouldBe 1
    }

    test("llvm-as accepts the file syscalls").config(enabledOrReasonIf = requiresLlvmAs) {
        val (exitCode, output) = runLlvmAs(LlvmPrinter.print(TestProgram().apply { allFileSyscallsSub(); statusSub(); allHandleSyscallsSub() }.module()))
        withClue(output) { exitCode shouldBe 0 }
    }

    test("the file handle syscalls call the C library directly, with the handle as the FILE pointer") {
        val lines = TestProgram(lp64Target).apply { allHandleSyscallsSub() }.module().function("main.handles").lines()
        lines shouldHave "  %file.1 = call ptr @fopen(ptr %r1.1, ptr %r2.1)"
        lines shouldHave "  store ptr %file.1, ptr %r3.slot"
        lines shouldHave "  %count.1 = zext i16 %r4.1 to i64"
        lines shouldHave "  %transferred.1 = call i64 @fread(ptr %r1.2, i64 1, i64 %count.1, ptr %r3.1)"
        lines shouldHave "  %transferred.2 = trunc i64 %transferred.1 to i16"
        lines shouldHave "  store i16 %transferred.2, ptr %r5.slot"
        lines shouldHave "  %transferred.3 = call i64 @fwrite(ptr %r1.3, i64 1, i64 %count.2, ptr %r3.2)"
        lines shouldHave "  %origin.1 = zext i8 %r8.1 to i32"
        lines shouldHave "  %status.1 = call i32 @.file.fseek(ptr %r3.3, i64 %r7.1, i32 %origin.1)"
        lines shouldHave "  %ok.1 = icmp eq i32 %status.1, 0"
        lines shouldHave "  %position.1 = call i64 @ftell(ptr %r3.4)"
        lines shouldHave "  store i64 %position.1, ptr %r10.slot"
        lines shouldHave "  %status.2 = call i32 @fflush(ptr %r3.5)"
        lines shouldHave "  %eof.1 = call i32 @feof(ptr %r3.6)"
        lines shouldHave "  %eof.2 = icmp ne i32 %eof.1, 0"
        lines shouldHave "  %eof.3 = zext i1 %eof.2 to i8"
        lines shouldHave "  %status.3 = call i32 @fclose(ptr %r3.7)"
        lines shouldHave "  %ok.6 = zext i1 %ok.5 to i8"
        lines shouldHave "  store i8 %ok.6, ptr %r13.slot"
    }

    test("a long offset to seek to is sign extended") {
        val lines = TestProgram(lp64Target).apply { longSeekSub() }.module().function("main.seek").lines()
        lines shouldHave "  %offset.1 = sext i32 %r2.2 to i64"
        lines shouldHave "  %status.1 = call i32 @.file.fseek(ptr %r1.1, i64 %offset.1, i32 %origin.1)"
    }

    test("the file handle and file syscalls share their C library declarations, which add fflush() and feof()") {
        val text = LlvmPrinter.print(TestProgram(lp64Target).apply { allFileSyscallsSub(); allHandleSyscallsSub() }.module()).lines()
        for (declaration in listOf("ptr @fopen(ptr, ptr)", "i32 @fclose(ptr)", "i64 @fread(ptr, i64, i64, ptr)", "i64 @fwrite(ptr, i64, i64, ptr)",
                "i32 @fseek(ptr, i64, i32)", "i64 @ftell(ptr)", "i32 @fflush(ptr)", "i32 @feof(ptr)"))
            withClue(declaration) { text.count { it == "declare $declaration" } shouldBe 1 }
    }

    test("on Windows, where a C long has 32 bits, the file syscalls seek and tell with the 64-bit _fseeki64 and _ftelli64") {
        val windows = LlvmTarget(8, LlvmTripleProfile.resolve("x86_64-pc-windows-msvc"))
        val text = LlvmPrinter.print(TestProgram(windows).apply { allFileSyscallsSub(); allHandleSyscallsSub() }.module()).lines()
        for (declaration in listOf("i32 @_fseeki64(ptr, i64, i32)", "i64 @_ftelli64(ptr)"))
            withClue(declaration) { text.count { it == "declare $declaration" } shouldBe 1 }
        text.none { it.startsWith("declare i32 @fseek(") || it.startsWith("declare i32 @ftell(") } shouldBe true
    }

    test("the file handle syscalls keep two files open, and seek and tell with 64-bit positions, in a native program for the host").config(enabledOrReasonIf = requiresNativeHost) {
        val directory = createTempDirectory("prog8-file-handles").toRealPath()
        try {
            val module = TestProgram(hostLlvmTarget).apply { handleSyscallChecks(directory) }.module().withMainCalling("main.check")
            val (exitCode, output) = compileAndRun(LlvmPrinter.print(module))
            withClue("exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
            directory.resolve("out.bin").readBytes().toList() shouldBe (1..8).map { it.toByte() }
            directory.resolve("copy.bin").readBytes().toList() shouldBe (1..5).map { it.toByte() }
            directory.listDirectoryEntries().map { it.name }.sorted() shouldBe listOf("copy.bin", "out.bin")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("the file syscalls read, write and manage files like in the VM, in a native program for the host").config(enabledOrReasonIf = requiresNativeHost) {
        val directory = createTempDirectory("prog8-file-syscalls").toRealPath()
        try {
            val module = TestProgram(hostLlvmTarget).apply { fileSyscallChecks(directory) }.module().withMainCalling("main.check")
            val (exitCode, output) = compileAndRun(LlvmPrinter.print(module))
            withClue("exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
            val data = (1..8).map { it.toByte() }
            directory.resolve("data.prg").readBytes().toList().let {
                it.size shouldBe 10
                it.drop(2) shouldBe data
            }
            directory.resolve("data.bin").readBytes().toList() shouldBe data
            directory.resolve("one.bin").readBytes().toList() shouldBe data.take(1)
            directory.listDirectoryEntries().map { it.name }.sorted() shouldBe listOf("data.bin", "data.prg", "one.bin")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    fun llvm32() = TestProgram(LlvmTarget(4))

    test("on llvm32, the C library routines are declared with the 32-bit size_t and long of wasm32") {
        val text = LlvmPrinter.print(llvm32().apply { allFileSyscallsSub(); allHandleSyscallsSub() }.module()).lines()
        for (declaration in listOf("ptr @fopen(ptr, ptr)", "i32 @fread(ptr, i32, i32, ptr)", "i32 @fwrite(ptr, i32, i32, ptr)",
                "i32 @fseek(ptr, i32, i32)", "i32 @ftell(ptr)", "ptr @getcwd(ptr, i32)", "i32 @mkdir(ptr, i32)"))
            withClue(declaration) { text.count { it == "declare $declaration" } shouldBe 1 }
    }

    test("on llvm32, fseek fails for a quad offset that a 32-bit long can't hold, and the position from ftell is sign extended into the quad") {
        val lines = llvm32().apply { allHandleSyscallsSub() }.module().function("main.handles").lines()
        lines shouldHave "  %count.1 = zext i16 %r4.1 to i32"
        lines shouldHave "  %transferred.1 = call i32 @fread(ptr %r1.2, i32 1, i32 %count.1, ptr %r3.1)"
        lines shouldHave "  %transferred.2 = trunc i32 %transferred.1 to i16"
        lines shouldHave "  %offset.1 = trunc i64 %r7.1 to i32"
        lines shouldHave "  %offset.2 = sext i32 %offset.1 to i64"
        lines shouldHave "  %fits.1 = icmp eq i64 %offset.2, %r7.1"
        lines shouldHave "  %origin.2 = select i1 %fits.1, i32 %origin.1, i32 -1"
        lines shouldHave "  %status.1 = call i32 @.file.fseek(ptr %r3.3, i32 %offset.1, i32 %origin.2)"
        lines shouldHave "  %position.1 = call i32 @ftell(ptr %r3.4)"
        lines shouldHave "  %position.2 = sext i32 %position.1 to i64"
        lines shouldHave "  store i64 %position.2, ptr %r10.slot"
        val seek = llvm32().apply { longSeekSub() }.module().function("main.seek").lines()
        seek shouldHave "  %status.1 = call i32 @.file.fseek(ptr %r1.1, i32 %r2.1, i32 %origin.1)"
    }

    test("on llvm32, the file helpers work with 32-bit positions, sizes and addresses, without truncating them") {
        val module = llvm32().apply { allFileSyscallsSub() }.module()
        module.function("main.files").lines() shouldHave "  %cwd.1 = call ptr @getcwd(ptr @.sys.curdir.buf, i32 4096)"
        val tell = module.function(".file.tell").lines()
        tell shouldHave "  %position = call i32 @ftell(ptr %file)"
        tell shouldHave "  %known = select i1 %valid, i32 %position, i32 0"
        tell shouldHave "  ret i32 %known"
        val size = module.function(".file.size").lines()
        size shouldHave "  call i32 @fseek(ptr %file, i32 0, i32 2)"
        size shouldHave "  ret i32 %known"
        module.function(".file.seek").lines() shouldHave "  %status = call i32 @.file.fseek(ptr %file, i32 %position, i32 0)"
        val restoring = module.function(".file.fseek").lines()
        restoring shouldHave "  %before = call i32 @ftell(ptr %file)"
        restoring shouldHave "  call i32 @fseek(ptr %file, i32 %before, i32 0)"
        module.function(".file.load").lines() shouldHave "  %count = call i32 @fread(ptr %address, i32 1, i32 %rest, ptr %file)"
        val save = module.function(".file.save").lines()
        save shouldHave "  %address = ptrtoint ptr %start to i32"
        save shouldHave "  %lsb = and i32 %address, 255"
        save shouldHave "  %written = call i32 @fwrite(ptr %start, i32 1, i32 %size, ptr %file)"
        for (helper in listOf(".file.tell", ".file.size", ".file.seek", ".file.load", ".file.save"))
            withClue(helper) { module.function(helper).lines().none { "i64" in it } shouldBe true }
    }

    test("on llvm32, the .sys.argv.at helper indexes argv with i32") {
        val lines = llvm32().apply { argumentLookupSub() }.programModule().function(".sys.argv.at").lines()
        lines shouldHave "  %i = zext i16 %index to i32"
        lines shouldHave "  %inrange = icmp ult i32 %i, %argc"
        lines shouldHave "  %slot = getelementptr ptr, ptr %argv, i32 %i"
        lines.none { "zext i32" in it } shouldBe true
    }

    fun llvm32Syscalls() = listOf(
        LlvmPrinter.print(llvm32().apply { allFileSyscallsSub(); statusSub(); allHandleSyscallsSub(); allOutputSubs() }.module()),
        LlvmPrinter.print(llvm32().apply { argumentsOutputSub() }.programModule()))

    test("llvm-as accepts the llvm32 syscalls").config(enabledOrReasonIf = requiresLlvmAs) {
        for (ir in llvm32Syscalls())
            runLlvmAs(ir).let { (exitCode, output) -> withClue(output) { exitCode shouldBe 0 } }
    }

    test("the llvm32 console syscalls read stdin and write stdout in a wasm32 program under wasmtime").config(enabledOrReasonIf = requiresWasm) {
        val echo = llvm32().apply { readCharSub(); echoSub() }.module().withMainCalling("main.echo").withMainNamed(wasiMain)
        compileAndRunWasm(LlvmPrinter.print(echo), input = "hi\n").let { (exitCode, output) ->
            withClue(output) { exitCode shouldBe 0 }
            output shouldBe "hi\n"
        }
        val greeting = llvm32().apply { allOutputSubs(); greetingSub() }.module().withMainCalling("main.greeting").withMainNamed(wasiMain)
        compileAndRunWasm(LlvmPrinter.print(greeting)).let { (exitCode, output) ->
            withClue(output) { exitCode shouldBe 0 }
            output shouldBe "A\nhi\nhello, world!\nhi\n"
        }
    }

    test("an llvm32 program gets its command line arguments under wasmtime, through the C main that wasi-libc calls").config(enabledOrReasonIf = requiresWasm) {
        val module = llvm32().apply { argumentsOutputSub() }.programModule()
        module.functions.last().name shouldBe wasiMain
        val (exitCode, output) = compileAndRunWasm(LlvmPrinter.print(module), args = listOf("first", "second arg"))
        withClue(output) { exitCode shouldBe 3 }
        val lines = output.lines()
        lines.take(4) shouldBe listOf("first", "second arg", "", "")
        withClue("argument 0 is the program: ${lines[4]}") { lines[4].endsWith("program.wasm") shouldBe true }
        lines.drop(5) shouldBe listOf("")
    }

    test("the llvm32 file and file handle syscalls work in a wasm32 program under wasmtime, with access to the directory").config(enabledOrReasonIf = requiresWasm) {
        val directory = createTempDirectory("prog8-wasm-files").toRealPath()
        try {
            val files = llvm32().apply { fileSyscallChecks(directory) }.module().withMainCalling("main.check").withMainNamed(wasiMain)
            compileAndRunWasm(LlvmPrinter.print(files), directories = listOf(directory)).let { (exitCode, output) ->
                withClue("exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
            }
            directory.listDirectoryEntries().map { it.name }.sorted() shouldBe listOf("data.bin", "data.prg", "one.bin")
            directory.listDirectoryEntries().forEach { it.toFile().delete() }
            val handles = llvm32().apply { handleSyscallChecks(directory) }.module().withMainCalling("main.check").withMainNamed(wasiMain)
            compileAndRunWasm(LlvmPrinter.print(handles), directories = listOf(directory)).let { (exitCode, output) ->
                withClue("exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
            }
            directory.resolve("out.bin").readBytes().toList() shouldBe (1..8).map { it.toByte() }
            directory.resolve("copy.bin").readBytes().toList() shouldBe (1..5).map { it.toByte() }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    // newlib on semihosting has no mkdir, chdir, rmdir and getcwd, so only those syscalls are left out;
    // and newlib loses the position in the file when a seek fails
    test("the llvm32 console, command line and file handle syscalls work in a bare metal ARM program on qemu").config(enabledOrReasonIf = requiresArm) {
        fun arm() = TestProgram(armTarget)
        val echo = arm().apply { readCharSub(); echoSub() }.module().withMainCalling("main.echo")
        compileAndRunArm(LlvmPrinter.print(echo), input = "hi\nthere\n").let { (exitCode, output) ->
            withClue(output) { exitCode shouldBe 0 }
            output shouldBe "hi\nthere\n"
        }
        val greeting = arm().apply { allOutputSubs(); greetingSub() }.module().withMainCalling("main.greeting")
        compileAndRunArm(LlvmPrinter.print(greeting)).let { (exitCode, output) ->
            withClue(output) { exitCode shouldBe 0 }
            output shouldBe "A\nhi\nhello, world!\nhi\n"
        }
        val arguments = arm().apply { argumentsOutputSub() }.programModule()
        compileAndRunArm(LlvmPrinter.print(arguments), args = listOf("first", "second arg")).let { (exitCode, output) ->
            withClue(output) { exitCode shouldBe 3 }
            val lines = output.lines()
            lines.take(4) shouldBe listOf("first", "second arg", "", "")
            withClue("argument 0 is the program: ${lines[4]}") { lines[4].endsWith("program.elf") shouldBe true }
        }
        val directory = createTempDirectory("prog8-arm-files").toRealPath()
        try {
            val handles = arm().apply { handleSyscallChecks(directory, failedSeekKeepsPosition = false) }.module().withMainCalling("main.check")
            compileAndRunArm(LlvmPrinter.print(handles), directory = directory).let { (exitCode, output) ->
                withClue("exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
            }
            directory.resolve("out.bin").readBytes().toList() shouldBe (1..8).map { it.toByte() }
            directory.resolve("copy.bin").readBytes().toList() shouldBe (1..5).map { it.toByte() }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    // as on ARM, without the directory syscalls; unlike on ARM, newlib keeps the position in the file when a seek fails
    test("the llvm32 console, command line and file handle syscalls work in a bare metal RISC-V program on qemu").config(enabledOrReasonIf = requiresRiscv) {
        fun riscv() = TestProgram(riscvTarget)
        val echo = riscv().apply { readCharSub(); echoSub() }.module().withMainCalling("main.echo")
        compileAndRunRiscv(LlvmPrinter.print(echo), input = "hi\nthere\n").let { (exitCode, output) ->
            withClue(output) { exitCode shouldBe 0 }
            output shouldBe "hi\nthere\n"
        }
        val greeting = riscv().apply { allOutputSubs(); greetingSub() }.module().withMainCalling("main.greeting")
        compileAndRunRiscv(LlvmPrinter.print(greeting)).let { (exitCode, output) ->
            withClue(output) { exitCode shouldBe 0 }
            output shouldBe "A\nhi\nhello, world!\nhi\n"
        }
        val arguments = riscv().apply { argumentsOutputSub() }.programModule()
        compileAndRunRiscv(LlvmPrinter.print(arguments), args = listOf("first", "second arg")).let { (exitCode, output) ->
            withClue(output) { exitCode shouldBe 3 }
            val lines = output.lines()
            lines.take(4) shouldBe listOf("first", "second arg", "", "")
            withClue("argument 0 is the program: ${lines[4]}") { lines[4].endsWith("program.elf") shouldBe true }
        }
        val directory = createTempDirectory("prog8-riscv-files").toRealPath()
        try {
            val handles = riscv().apply { handleSyscallChecks(directory) }.module().withMainCalling("main.check")
            compileAndRunRiscv(LlvmPrinter.print(handles), directory = directory).let { (exitCode, output) ->
                withClue("exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
            }
            directory.resolve("out.bin").readBytes().toList() shouldBe (1..8).map { it.toByte() }
            directory.resolve("copy.bin").readBytes().toList() shouldBe (1..5).map { it.toByte() }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    test("the llvm32 syscalls compile into wasm32 objects").config(enabledOrReasonIf = requiresWasmClang) {
        for (ir in llvm32Syscalls())
            compileWasmObject(ir).let { (exitCode, output) -> withClue(output) { exitCode shouldBe 0 } }
    }
})


private fun TestProgram.printCharSub() = sub("main.printchar", code = """
    _main.printchar:
    load.b r1.b,#65.b
    syscall 2 (r1.b)
    load.b r2.b,#10.b
    syscall 2 (r2.b)
    return
    """)

private fun TestProgram.printStringSub() {
    string("main.text", "hi\n")
    string("main.empty", "")
    sub("main.printstring", code = """
        _main.printstring:
        load.l r1.l,#main.text
        syscall 3 (r1.l)
        load.p r2.p,#main.empty
        syscall 3 (r2.p)
        return
        """)
}

private fun TestProgram.readCharSub() = sub("main.readchar", returns = listOf(DataType.UBYTE), code = """
    _main.readchar:
    syscall 69 ():r1.b
    returnr.b r1.b
    """)

/** Writes the characters of stdin to stdout until main.readchar gives 0; returns 0. */
private fun TestProgram.echoSub() = sub("main.echo", returns = listOf(DataType.UBYTE), code = """
    _main.echo:
    call main.readchar():r1.b
    cmpi.b r1.b,#0.b
    bsteq main.echo.done
    syscall 2 (r1.b)
    jump main.echo
    _main.echo.done:
    returni.b #0.b
    """)

private fun TestProgram.silentSub() = sub("main.silent", returns = listOf(DataType.UBYTE), code = """
    _main.silent:
    returni.b #0.b
    """)

private fun TestProgram.allOutputSubs() {
    printCharSub()
    printStringSub()
}

/** Prints 'A' and a newline, "hi\n", "hello, world!\n", and "hi\n" once more through the string helper; returns 0. */
private fun TestProgram.greetingSub() {
    string("main.hello", "hello, world!\n")
    sub("main.greeting", returns = listOf(DataType.UBYTE), code = """
        _main.greeting:
        call main.printchar()
        call main.printstring()
        load.l r10.l,#main.hello
        syscall 3 (r10.l)
        call main.printstring()
        returni.b #0.b
        """)
}

private fun TestProgram.emptyStartSub() = sub("main.start", code = """
    _main.start:
    return
    """)

private fun TestProgram.argumentLookupSub() = sub("main.start", code = """
    _main.start:
    syscall 200 (): r1.w
    syscall 201 (r1.w): r2.p
    syscall 3 (r2.p)
    return
    """)

/** Prints arguments 1 and 2, those at index argc and 65535, and argument 0, each on a line; exits with argc as the status. */
private fun TestProgram.argumentsOutputSub() = sub("main.start", code = """
    _main.start:
    syscall 200 (): r1.w
    load.b r9.b,#10.b
    load.w r2.w,#1.w
    syscall 201 (r2.w): r3.p
    syscall 3 (r3.p)
    syscall 2 (r9.b)
    load.w r4.w,#2.w
    syscall 201 (r4.w): r5.p
    syscall 3 (r5.p)
    syscall 2 (r9.b)
    syscall 201 (r1.w): r6.p
    syscall 3 (r6.p)
    syscall 2 (r9.b)
    load.w r7.w,#65535.w
    syscall 201 (r7.w): r8.p
    syscall 3 (r8.p)
    syscall 2 (r9.b)
    load.w r10.w,#0.w
    syscall 201 (r10.w): r11.p
    syscall 3 (r11.p)
    syscall 2 (r9.b)
    lsigb.w r12.b,r1.w
    syscall 1 (r12.b)
    return
    """)

/** Every file and filesystem syscall once, with addresses in POINTER registers as the llvm64 library passes them. */
private fun TestProgram.allFileSyscallsSub() {
    string("main.name", "x.bin")
    variable("main.buffer", DataType.arrayFor(BaseDataType.UBYTE, target), length = 8u)
    sub("main.files", code = """
        _main.files:
        load.p r1.p,#main.name
        load.p r2.p,#main.buffer
        syscall 40 (r1.p, r2.p): r3.p
        syscall 41 (r1.p, r2.p): r4.p
        load.b r5.b,#0.b
        load.w r6.w,#8.w
        syscall 42 (r5.b, r1.p, r2.p, r6.w): r7.b
        syscall 43 (r1.p)
        syscall 44 (r1.p, r1.p)
        syscall 48 (): r8.p
        syscall 49 (r1.p)
        syscall 50 (r1.p)
        syscall 51 (r1.p)
        syscall 52 (r1.p): r9.b
        syscall 53 (r1.p): r10.b
        syscall 54 (): r11.w
        syscall 55 (r5.b): r12.b
        syscall 56 ()
        syscall 57 ()
        load.l r13.l,#5.l
        syscall 63 (r13.l): r14.b
        syscall 64 (): r15.l
        syscall 65 (): r16.l
        return
        """)
}

/** Every file handle syscall once, with the quad offset and the byte origin that fileio passes to FSEEK. */
private fun TestProgram.allHandleSyscallsSub() {
    string("main.path", "x.bin")
    string("main.mode", "rb")
    sub("main.handles", code = """
        _main.handles:
        load.p r1.p,#main.path
        load.p r2.p,#main.mode
        syscall 202 (r1.p, r2.p): r3.p
        load.w r4.w,#8.w
        syscall 204 (r3.p, r1.p, r4.w): r5.w
        syscall 205 (r3.p, r1.p, r4.w): r6.w
        load.q r7.q,#-2.q
        load.b r8.b,#2.b
        syscall 206 (r3.p, r7.q, r8.b): r9.b
        syscall 207 (r3.p): r10.q
        syscall 208 (r3.p): r11.b
        syscall 209 (r3.p): r12.b
        syscall 203 (r3.p): r13.b
        return
        """)
}

private fun TestProgram.longSeekSub() = sub("main.seek", code = """
    _main.seek:
    load.p r1.p,#0.p
    load.l r2.l,#-2.l
    load.b r3.b,#0.b
    syscall 206 (r1.p, r2.l, r3.b): r4.b
    return
    """)

private fun TestProgram.statusSub() {
    string("main.gone", "gone.bin")
    sub("main.status", code = """
        _main.status:
        load.p r1.p,#main.gone
        syscall 43 (r1.p)
        syscall 43 (r1.p): r2.b
        return
        """)
}

/**
 * main.check, which returns the number of the first check that fails, or 0. Its results are those of the VM (SysCalls.kt and
 * VmSystemHandler.kt); it changes to the [directory] first, and works on files in there.
 */
private fun TestProgram.fileSyscallChecks(directory: Path) {
    string("main.dir", directory.toString())
    string("main.nodir", "no such directory")
    string("main.unwritable", "no such directory/x.bin")
    string("main.missing", "missing.bin")
    string("main.prg", "data.prg")
    string("main.bin", "data.bin")
    string("main.one", "one.bin")
    string("main.out", "out.txt")
    string("main.renamed", "renamed.txt")
    string("main.sub", "sub")
    variable("main.bytes", DataType.arrayFor(BaseDataType.UBYTE, target),
        IRVariableInitializer.Array((1..8).map { IRStSymbolicReference.Numeric(it.toDouble()) }), 8u)
    variable("main.dst", DataType.arrayFor(BaseDataType.UBYTE, target), length = 12u)
    variable("main.dst2", DataType.arrayFor(BaseDataType.UBYTE, target), length = 12u)
    val compareStrings = IMSyscall.COMPARE_STRINGS.number
    sub("main.check", returns = listOf(DataType.UBYTE), code = Checks().apply {
        check("load.p ra.p,#main.nodir; syscall 50 (ra.p): rb.b", Is("rb.b", 0))
        check("load.p ra.p,#main.dir; syscall 50 (ra.p): rb.b", Is("rb.b", 1))
        check("syscall 48 (): ra.p; load.p rb.p,#main.dir; syscall $compareStrings (ra.p, rb.p): rc.b", Is("rc.b", 0))
        // without streams, the stream syscalls give 0
        check("syscall 54 (): ra.w; syscall 64 (): rb.l; syscall 65 (): rc.l; load.l rd.l,#0.l; syscall 63 (rd.l): re.b; " +
            "load.b rf.b,#65.b; syscall 55 (rf.b): rg.b; syscall 56 (); syscall 57 ()",
            Is("ra.w", 0), Is("rb.l", 0), Is("rc.l", 0), Is("re.b", 0), Is("rg.b", 0))
        check("load.b ra.b,#0.b; load.p rb.p,#main.prg; load.p rc.p,#main.bytes; load.w rd.w,#8.w; syscall 42 (ra.b, rb.p, rc.p, rd.w): re.b", Is("re.b", 1))
        check("load.b ra.b,#1.b; load.p rb.p,#main.bin; load.p rc.p,#main.bytes; load.w rd.w,#8.w; syscall 42 (ra.b, rb.p, rc.p, rd.w): re.b", Is("re.b", 1))
        check("load.b ra.b,#1.b; load.p rb.p,#main.one; load.p rc.p,#main.bytes; load.w rd.w,#1.w; syscall 42 (ra.b, rb.p, rc.p, rd.w): re.b", Is("re.b", 1))
        check("load.b ra.b,#1.b; load.p rb.p,#main.unwritable; load.p rc.p,#main.bytes; load.w rd.w,#1.w; syscall 42 (ra.b, rb.p, rc.p, rd.w): re.b", Is("re.b", 0))
        // load skips the 2-byte header, load_raw doesn't; both give the end address
        check("load.p ra.p,#main.prg; load.p rb.p,#main.dst; syscall 40 (ra.p, rb.p): rc.p; load.p rd.p,#main.dst+8; cmp.p rc.p,rd.p", ZeroIs(true))
        check("loadm.b ra.b,[main.dst]; loadm.b rb.b,[main.dst+7]; loadm.b rc.b,[main.dst+8]", Is("ra.b", 1), Is("rb.b", 8), Is("rc.b", 0))
        check("load.p ra.p,#main.bin; load.p rb.p,#main.dst2; syscall 41 (ra.p, rb.p): rc.p; load.p rd.p,#main.dst2+8; cmp.p rc.p,rd.p", ZeroIs(true))
        check("loadm.b ra.b,[main.dst2]; loadm.b rb.b,[main.dst2+7]; loadm.b rc.b,[main.dst2+8]", Is("ra.b", 1), Is("rb.b", 8), Is("rc.b", 0))
        // a null pointer for an error: no address to load to, a missing file, or not even a complete header
        check("load.p ra.p,#main.prg; load.p rb.p,#0.p; syscall 40 (ra.p, rb.p): rc.p", Is("rc.p", 0))
        check("load.p ra.p,#main.missing; load.p rb.p,#main.dst; syscall 40 (ra.p, rb.p): rc.p", Is("rc.p", 0))
        check("load.p ra.p,#main.one; load.p rb.p,#main.dst; syscall 40 (ra.p, rb.p): rc.p", Is("rc.p", 0))
        check("load.p ra.p,#main.one; load.p rb.p,#main.dst2; syscall 41 (ra.p, rb.p): rc.p; load.p rd.p,#main.dst2+1; cmp.p rc.p,rd.p", ZeroIs(true))
        // reading, seeking and telling; a failed seek leaves the position as it was
        check("load.p ra.p,#main.bin; syscall 52 (ra.p): rb.b; syscall 65 (): rc.l; syscall 64 (): rd.l; syscall 54 (): re.w; syscall 64 (): rf.l",
            Is("rb.b", 1), Is("rc.l", 8), Is("rd.l", 0), Is("re.w", 0x101), Is("rf.l", 1))
        check("load.l ra.l,#5.l; syscall 63 (ra.l): rb.b; syscall 54 (): rc.w; load.l rd.l,#-1.l; syscall 63 (rd.l): re.b; syscall 54 (): rf.w",
            Is("rb.b", 1), Is("rc.w", 0x106), Is("re.b", 0), Is("rf.w", 0x107))
        check("syscall 54 (): ra.w; syscall 54 (): rb.w; syscall 64 (): rc.l; syscall 65 (): rd.l; syscall 64 (): re.l",
            Is("ra.w", 0x108), Is("rb.w", 0), Is("rc.l", 8), Is("rd.l", 8), Is("re.l", 8))
        check("load.l ra.l,#0.l; syscall 63 (ra.l): rb.b; syscall 54 (): rc.w", Is("rb.b", 1), Is("rc.w", 0x101))
        // a failed open keeps the stream there was, a successful one replaces it
        check("load.p ra.p,#main.missing; syscall 52 (ra.p): rb.b; syscall 54 (): rc.w", Is("rb.b", 0), Is("rc.w", 0x102))
        check("load.p ra.p,#main.prg; syscall 52 (ra.p): rb.b; syscall 65 (): rc.l", Is("rb.b", 1), Is("rc.l", 10))
        check("syscall 56 (); syscall 54 (): ra.w; syscall 65 (): rb.l", Is("ra.w", 0), Is("rb.l", 0))
        // writing, also to a file that exists, which gets truncated
        check("load.p ra.p,#main.out; syscall 53 (ra.p): rb.b; load.b rc.b,#104.b; syscall 55 (rc.b): rd.b; load.b re.b,#105.b; " +
            "syscall 55 (re.b): rf.b; syscall 57 (); syscall 55 (rc.b): rg.b",
            Is("rb.b", 1), Is("rd.b", 1), Is("rf.b", 1), Is("rg.b", 0))
        check("load.p ra.p,#main.out; syscall 53 (ra.p): rb.b; load.b rc.b,#88.b; syscall 55 (rc.b): rd.b; syscall 57 (); " +
            "syscall 52 (ra.p): re.b; syscall 54 (): rf.w; syscall 54 (): rg.w; syscall 56 ()",
            Is("rb.b", 1), Is("rd.b", 1), Is("re.b", 1), Is("rf.w", 0x158), Is("rg.w", 0))
        // the filesystem syscalls with a status
        check("load.p ra.p,#main.out; load.p rb.p,#main.renamed; syscall 44 (ra.p, rb.p): rc.b; syscall 52 (ra.p): rd.b", Is("rc.b", 1), Is("rd.b", 0))
        check("load.p ra.p,#main.renamed; syscall 43 (ra.p): rb.b; syscall 43 (ra.p): rc.b", Is("rb.b", 1), Is("rc.b", 0))
        check("load.p ra.p,#main.sub; syscall 49 (ra.p): rb.b; syscall 49 (ra.p): rc.b", Is("rb.b", 1), Is("rc.b", 0))
        check("load.p ra.p,#main.sub; load.p rb.p,#main.dst; syscall 41 (ra.p, rb.p): rc.p", Is("rc.p", 0))
        check("load.p ra.p,#main.sub; syscall 51 (ra.p): rb.b; syscall 51 (ra.p): rc.b", Is("rb.b", 1), Is("rc.b", 0))
    }.subroutine())
}

/**
 * main.check for the file handle syscalls on files in the [directory]; main.files keeps the handles of two open files.
 * Without [failedSeekKeepsPosition], as with newlib, only the status of a failed seek is checked.
 */
private fun TestProgram.handleSyscallChecks(directory: Path, failedSeekKeepsPosition: Boolean = true) {
    string("main.missing", directory.resolve("missing.bin").toString())
    string("main.out", directory.resolve("out.bin").toString())
    string("main.copy", directory.resolve("copy.bin").toString())
    string("main.rb", "rb")
    string("main.wb", "wb")
    variable("main.bytes", DataType.arrayFor(BaseDataType.UBYTE, target),
        IRVariableInitializer.Array((1..8).map { IRStSymbolicReference.Numeric(it.toDouble()) }), 8u)
    variable("main.dst", DataType.arrayFor(BaseDataType.UBYTE, target), length = 8u)
    variable("main.files", DataType.arrayFor(BaseDataType.UBYTE, target), length = 16u)
    sub("main.check", returns = listOf(DataType.UBYTE), code = Checks().apply {
        check("load.p ra.p,#main.missing; load.p rb.p,#main.rb; syscall 202 (ra.p, rb.p): rc.p", Is("rc.p", 0))
        check("load.p ra.p,#main.out; load.p rb.p,#main.wb; syscall 202 (ra.p, rb.p): rc.p; storem.p rc.p,[main.files]; cmpi.p rc.p,#0.p", ZeroIs(false))
        check("loadm.p ra.p,[main.files]; load.p rb.p,#main.bytes; load.w rc.w,#8.w; syscall 205 (ra.p, rb.p, rc.w): rd.w; syscall 207 (ra.p): re.q; " +
            "syscall 208 (ra.p): rf.b; syscall 203 (ra.p): rg.b",
            Is("rd.w", 8), Is("re.q", 8), Is("rf.b", 1), Is("rg.b", 1))
        // a null handle flushes every stream
        check("load.p ra.p,#0.p; syscall 208 (ra.p): rb.b", Is("rb.b", 1))
        // two files open at once, to copy the first 5 bytes of one to the other
        check("load.p ra.p,#main.out; load.p rb.p,#main.rb; syscall 202 (ra.p, rb.p): rc.p; storem.p rc.p,[main.files]; cmpi.p rc.p,#0.p", ZeroIs(false))
        check("load.p ra.p,#main.copy; load.p rb.p,#main.wb; syscall 202 (ra.p, rb.p): rc.p; storem.p rc.p,[main.files+8]; cmpi.p rc.p,#0.p", ZeroIs(false))
        check("loadm.p ra.p,[main.files]; loadm.p rb.p,[main.files+8]; load.p rc.p,#main.dst; load.w rd.w,#5.w; syscall 204 (ra.p, rc.p, rd.w): re.w; " +
            "syscall 209 (ra.p): rf.b; syscall 205 (rb.p, rc.p, re.w): rg.w",
            Is("re.w", 5), Is("rf.b", 0), Is("rg.w", 5))
        // only 3 of the next 5 bytes are there, and reading past them sets the end of the file
        check("loadm.p ra.p,[main.files]; load.p rb.p,#main.dst; load.w rc.w,#5.w; syscall 204 (ra.p, rb.p, rc.w): rd.w; syscall 209 (ra.p): re.b; " +
            "syscall 207 (ra.p): rf.q; loadm.b rg.b,[main.dst]; loadm.b rh.b,[main.dst+2]",
            Is("rd.w", 3), Is("re.b", 1), Is("rf.q", 8), Is("rg.b", 6), Is("rh.b", 8))
        // negative offsets from the end and from the position; seeking clears the end of the file
        check("loadm.p ra.p,[main.files]; load.q rb.q,#-2.q; load.b rc.b,#2.b; syscall 206 (ra.p, rb.q, rc.b): rd.b; syscall 207 (ra.p): re.q; " +
            "load.q rf.q,#-3.q; load.b rg.b,#1.b; syscall 206 (ra.p, rf.q, rg.b): rh.b",
            Is("rd.b", 1), Is("re.q", 6), Is("rh.b", 1))
        check("loadm.p ra.p,[main.files]; syscall 207 (ra.p): rb.q; syscall 209 (ra.p): rc.b; load.p rd.p,#main.dst; load.w re.w,#1.w; " +
            "syscall 204 (ra.p, rd.p, re.w): rf.w; loadm.b rg.b,[main.dst]",
            Is("rb.q", 3), Is("rc.b", 0), Is("rf.w", 1), Is("rg.b", 4))
        // seeking before the start fails and keeps the position; past 4 GB works, with nothing to read there, where the file offset has 64 bits
        check("loadm.p ra.p,[main.files]; load.q rb.q,#-1.q; load.b rc.b,#0.b; syscall 206 (ra.p, rb.q, rc.b): rd.b; syscall 207 (ra.p): re.q",
            *if (failedSeekKeepsPosition) arrayOf(Is("rd.b", 0), Is("re.q", 4)) else arrayOf(Is("rd.b", 0)))
        val past4GB = if (target.profile.fileOffsetSize == 8) arrayOf(Is("rd.b", 1), Is("re.q", 4294967297), Is("rh.w", 0))
            else if (failedSeekKeepsPosition) arrayOf(Is("rd.b", 0), Is("re.q", 4), Is("rh.w", 1)) else arrayOf(Is("rd.b", 0))
        check("loadm.p ra.p,[main.files]; load.q rb.q,#4294967297.q; load.b rc.b,#0.b; syscall 206 (ra.p, rb.q, rc.b): rd.b; syscall 207 (ra.p): re.q; " +
            "load.p rf.p,#main.dst; load.w rg.w,#1.w; syscall 204 (ra.p, rf.p, rg.w): rh.w", *past4GB)
        check("loadm.p ra.p,[main.files]; loadm.p rb.p,[main.files+8]; syscall 203 (ra.p): rc.b; syscall 203 (rb.p): rd.b", Is("rc.b", 1), Is("rd.b", 1))
    }.subroutine())
}
