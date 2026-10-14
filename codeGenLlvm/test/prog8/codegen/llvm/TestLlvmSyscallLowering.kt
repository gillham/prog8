package prog8.codegen.llvm

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import prog8.code.core.DataType


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

    test("the input syscall reads stdin in a native arm64 macOS program, and gives 0 at its end").config(enabledOrReasonIf = requiresNativeArm64Mac) {
        val module = TestProgram().apply { readCharSub(); echoSub() }.module().withMainCalling("main.echo")
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

    test("the output syscalls write to stdout in a native arm64 macOS program").config(enabledOrReasonIf = requiresNativeArm64Mac) {
        val module = TestProgram().apply { allOutputSubs(); greetingSub() }.module().withMainCalling("main.greeting")
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

    test("syscall 71 gives argc saturated to a word, syscall 72 the argument at an index through the .sys.argv.at helper") {
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

    test("a native arm64 macOS program gets its command line arguments, and an empty string for an index past them").config(enabledOrReasonIf = requiresNativeArm64Mac) {
        val module = TestProgram().apply { argumentsOutputSub() }.programModule()
        val (exitCode, output) = compileAndRun(LlvmPrinter.print(module), args = listOf("first", "second arg"))
        withClue(output) { exitCode shouldBe 3 }
        val lines = output.lines()
        lines.take(4) shouldBe listOf("first", "second arg", "", "")
        withClue("argument 0 is the program: ${lines[4]}") { lines[4].endsWith("/program") shouldBe true }
        lines.drop(5) shouldBe listOf("")
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
    syscall 71 (): r1.w
    syscall 72 (r1.w): r2.p
    syscall 3 (r2.p)
    return
    """)

/** Prints arguments 1 and 2, those at index argc and 65535, and argument 0, each on a line; exits with argc as the status. */
private fun TestProgram.argumentsOutputSub() = sub("main.start", code = """
    _main.start:
    syscall 71 (): r1.w
    load.b r9.b,#10.b
    load.w r2.w,#1.w
    syscall 72 (r2.w): r3.p
    syscall 3 (r3.p)
    syscall 2 (r9.b)
    load.w r4.w,#2.w
    syscall 72 (r4.w): r5.p
    syscall 3 (r5.p)
    syscall 2 (r9.b)
    syscall 72 (r1.w): r6.p
    syscall 3 (r6.p)
    syscall 2 (r9.b)
    load.w r7.w,#65535.w
    syscall 72 (r7.w): r8.p
    syscall 3 (r8.p)
    syscall 2 (r9.b)
    load.w r10.w,#0.w
    syscall 72 (r10.w): r11.p
    syscall 3 (r11.p)
    syscall 2 (r9.b)
    lsigb.w r12.b,r1.w
    syscall 1 (r12.b)
    return
    """)
