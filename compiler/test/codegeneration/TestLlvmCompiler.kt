package prog8tests.codegeneration

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.Enabled
import io.kotest.core.test.TestCase
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import prog8.ast.expressions.NumericLiteral
import prog8.ast.statements.Assignment
import prog8.ast.statements.StructDecl
import prog8.ast.statements.Subroutine
import prog8.ast.statements.VarDecl
import prog8.code.core.BaseDataType
import prog8.code.core.DataType
import prog8.code.core.ICompilationTarget
import prog8.code.core.OutputFiles
import prog8.code.target.Amiga1200Target
import prog8.code.target.Amiga500Target
import prog8.code.target.C128Target
import prog8.code.target.C64Target
import prog8.code.target.Cx16Target
import prog8.code.target.LlvmTarget
import prog8.code.target.LlvmTripleProfile
import prog8.code.target.PETTarget
import prog8.code.target.Qemu68kTarget
import prog8.code.target.VMTarget
import prog8.code.target.getCompilationTargetByName
import prog8.compiler.CompilationResult
import prog8.intermediate.IRFileReader
import prog8.vm.ProgramExitException
import prog8.vm.VirtualMachine
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.compileText
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines
import kotlin.io.path.writeText


class TestLlvmCompiler: FunSpec({

    val outputDir = tempdir().toPath()

    for ((name, source) in programs) {
        test("$name: compiles to a .ll file with a C entry point, with and without optimization, which llvm-as accepts when installed") {
            for (optimize in listOf(false, true)) {
                withClue("optimize=$optimize") {
                    val llFile = compiledLlFile(compileText(LlvmTarget(), optimize, source, outputDir))
                    val lines = llFile.readLines()
                    lines shouldContain "define i32 @main(i32 %argc, ptr %argv) {"
                    lines shouldContain "define internal void @main.start() {"
                }
            }
        }
    }

    test("the programs compute the right results as native arm64 macOS executables").config(enabledOrReasonIf = requiresNativeLlvm) {
        for ((name, source) in programs) {
            for (optimize in listOf(false, true)) {
                val llFile = compiledLlFile(compileText(LlvmTarget(), optimize, source, outputDir))
                val (exitCode, output) = buildAndRun(llFile)
                withClue("$name optimize=$optimize: exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
            }
        }
    }

    for ((name, program) in outputPrograms) {
        test("$name: compiles with the llvm64 library, whose console output goes through putchar") {
            for (optimize in listOf(false, true)) {
                withClue("optimize=$optimize") {
                    compiledLlFile(compileText(LlvmTarget(), optimize, program.source, outputDir)).readLines() shouldContain "declare i32 @putchar(i32)"
                }
            }
        }

        test("$name: gives the expected exit code and console output as a native arm64 macOS executable, which leaves no files behind").config(enabledOrReasonIf = requiresNativeLlvm) {
            for (optimize in listOf(false, true)) {
                withClue("optimize=$optimize") {
                    val workingDirectory = outputDir.resolve("run")
                    workingDirectory.toFile().deleteRecursively()
                    workingDirectory.createDirectories()
                    val llFile = compiledLlFile(compileText(LlvmTarget(), optimize, program.source, outputDir))
                    val (exitCode, output) = buildAndRun(llFile, program.input, program.args, workingDirectory)
                    withClue(output) { exitCode shouldBe program.exitCode }
                    output shouldBe program.output
                    withClue("the files left in the working directory") { workingDirectory.listDirectoryEntries() shouldBe emptyList() }
                }
            }
        }
    }

    test("llvm is another name for the llvm64 target, whose program file is the LLVM IR text") {
        getCompilationTargetByName("llvm").name shouldBe LlvmTarget.NAME64
        getCompilationTargetByName("LLVM64").name shouldBe LlvmTarget.NAME64
        compiledLlFile(compileText(LlvmTarget(), false, programs.getValue("arithmetic expressions"), outputDir)).toString() shouldEndWith ".ll"
    }

    test("a -triple profile sets the triple and data layout of the LLVM module, and is kept in the .p8ir file") {
        val dir = outputDir.resolve("x86_64").createDirectories()
        val target = getCompilationTargetByName("llvm64", "x86_64-unknown-linux-gnu")
        val result = compileText(target, false, programs.getValue("arithmetic expressions"), dir, assemble = false).shouldNotBeNull()
        val lines = compiledLlFile(result).readLines()
        lines.any { it == "target triple = \"x86_64-unknown-linux-gnu\"" } shouldBe true
        lines.any { it == "target datalayout = \"e-m:e-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-f80:128-n8:16:32:64-S128\"" } shouldBe true

        val irFile = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir")
        val irTarget = IRFileReader().read(irFile).options.compTarget as LlvmTarget
        irTarget.name shouldBe LlvmTarget.NAME64
        irTarget.profile.name shouldBe "x86_64-unknown-linux-gnu"
    }

    test("the -Xcompile and -Xlink arguments are kept in the .p8ir file unchanged and in order") {
        val dir = outputDir.resolve("extra-args").createDirectories()
        val compileArgs = listOf("-g", "-mllvm", "-x=1")
        val linkArgs = listOf("-L/a b", "-lfoo", "back\\slash", "two\nlines", " spaced ")
        val target = getCompilationTargetByName("llvm32", "riscv32-unknown-elf", compileArgs, linkArgs)
        val result = compileText(target, false, programs.getValue("arithmetic expressions"), dir, assemble = false).shouldNotBeNull()
        val irFile = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir")
        val irTarget = IRFileReader().read(irFile).options.compTarget as LlvmTarget
        irTarget.profile.name shouldBe "riscv32-unknown-elf"
        irTarget.extraCompileArgs shouldBe compileArgs
        irTarget.extraLinkArgs shouldBe linkArgs
    }

    test("-Xcompile and -Xlink arguments are only accepted for the llvm targets") {
        shouldThrow<IllegalArgumentException> { getCompilationTargetByName("cx16", extraLinkArgs = listOf("-lfoo")) }
        shouldThrow<IllegalArgumentException> { getCompilationTargetByName("virtual", extraCompileArgs = listOf("-g")) }
        (getCompilationTargetByName("llvm", extraLinkArgs = listOf("-lfoo")) as LlvmTarget).extraLinkArgs shouldBe listOf("-lfoo")
    }

    test("-Xlink links an extra static library into a native executable").config(enabledOrReasonIf = requiresNativeLlvmAndAr) {
        val ar = checkNotNull(llvmBin).resolve("llvm-ar")
        linksExtraLibrary(outputDir, LlvmTarget(), "native-library", ar) { source, objectFile ->
            listOf(checkNotNull(llvmBin).resolve("clang").toString(), "-c", source.toString(), "-o", objectFile.toString())
        }
    }

    test("-Xlink links an extra static library into an llvm32 wasm32 program").config(enabledOrReasonIf = requiresWasmAndAr) {
        val ar = checkNotNull(llvmBin).resolve("llvm-ar")
        val sysroot = System.getenv("WASI_SYSROOT") ?: "/opt/homebrew/opt/wasi-libc/share/wasi-sysroot"
        linksExtraLibrary(outputDir, LlvmTarget(4), "wasm-library", ar) { source, objectFile ->
            listOf(checkNotNull(llvmBin).resolve("clang").toString(), "--target=wasm32-wasip1", "--sysroot=$sysroot", "-c", source.toString(), "-o", objectFile.toString())
        }
    }

    test("-Xlink links an extra static library into an llvm32 bare metal ARM program").config(enabledOrReasonIf = requiresArm) {
        val gcc = checkNotNull(armProfile.linkCommand).first()
        linksExtraLibrary(outputDir, armTarget(), "arm-library", Path(gcc.removeSuffix("gcc") + "ar")) { source, objectFile ->
            listOf(gcc, "-mcpu=cortex-a15", "-mfloat-abi=hard", "-mfpu=neon-vfpv4", "-c", source.toString(), "-o", objectFile.toString())
        }
    }

    test("-Xlink links an extra static library into an llvm32 bare metal RISC-V program").config(enabledOrReasonIf = requiresRiscvOnThePath) {
        val gcc = checkNotNull(riscvGcc).toString()
        linksExtraLibrary(outputDir, riscvTarget(), "riscv-library", Path(gcc.removeSuffix("gcc") + "ar")) { source, objectFile ->
            listOf(gcc, "-march=rv32imafdc_zicsr", "-mabi=ilp32d", "-c", source.toString(), "-o", objectFile.toString())
        }
    }

    test("the programs without 64-bit addresses compile for the llvm32 target, with 4-byte pointers, which llvm-as accepts when installed") {
        val dir = outputDir.resolve("llvm32").createDirectories()
        val sources = programs.filterKeys { it !in programsWith64BitAddresses } + outputPrograms.mapValues { it.value.source }
        for ((name, source) in sources) {
            for (optimize in listOf(false, true)) {
                withClue("$name optimize=$optimize") {
                    val lines = compiledLlFile(compileText(LlvmTarget(4), optimize, source, dir)).readLines()
                    lines shouldContain "target triple = \"wasm32-unknown-wasip1\""
                    lines shouldContain "define i32 @__main_argc_argv(i32 %argc, ptr %argv) {"
                    lines.none { it.startsWith("declare void @llvm.mem") && "i64" in it } shouldBe true
                }
            }
        }
    }

    test("the llvm32 programs without 64-bit addresses compute the right results as wasm32 programs under wasmtime").config(enabledOrReasonIf = requiresWasm) {
        val dir = outputDir.resolve("wasm").createDirectories()
        for ((name, source) in programs.filterKeys { it !in programsWith64BitAddresses && it !in notRunUnderWasmtime }) {
            for (optimize in listOf(false, true)) {
                val llFile = compiledLlFile(compileText(LlvmTarget(4), optimize, source, dir))
                val (exitCode, output) = buildAndRunWasm(llFile)
                withClue("$name optimize=$optimize: exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
            }
        }
    }

    for ((name, program) in outputPrograms.filterKeys { it !in notRunUnderWasmtime }) {
        test("$name: gives the expected exit code and console output as an llvm32 wasm32 program under wasmtime, which leaves no files behind").config(enabledOrReasonIf = requiresWasm) {
            for (optimize in listOf(false, true)) {
                withClue("optimize=$optimize") {
                    val workingDirectory = outputDir.resolve("wasm-run")
                    workingDirectory.toFile().deleteRecursively()
                    workingDirectory.createDirectories()
                    val llFile = compiledLlFile(compileText(LlvmTarget(4), optimize, program.source, outputDir.resolve("wasm").createDirectories()))
                    val (exitCode, output) = buildAndRunWasm(llFile, program.input, program.args, workingDirectory)
                    withClue(output) { exitCode shouldBe program.exitCode }
                    output shouldBe program.output
                    withClue("the files left in the working directory") { workingDirectory.listDirectoryEntries() shouldBe emptyList() }
                }
            }
        }
    }

    test("on llvm32, call() and goto reach subroutines through void () entries under wasmtime, also those that return a value").config(enabledOrReasonIf = requiresWasm) {
        val dir = outputDir.resolve("wasm-indirect").createDirectories()
        for (optimize in listOf(false, true)) {
            withClue("optimize=$optimize") {
                val llFile = compiledLlFile(compileText(LlvmTarget(4), optimize, indirectCalls32.source, dir))
                val lines = llFile.readLines()
                lines.any { it.startsWith("define internal void @.indirect.main.answer()") } shouldBe true
                lines.none { it.trim().startsWith("%calli") } shouldBe true
                val (exitCode, output) = buildAndRunWasm(llFile)
                withClue(output) { exitCode shouldBe indirectCalls32.exitCode }
                output shouldBe indirectCalls32.output
            }
        }
    }

    test("launchEmulator builds an llvm32 program into a .wasm file next to the .ll file and runs it with wasmtime").config(enabledOrReasonIf = requiresWasm) {
        val dir = outputDir.resolve("wasm-launched").createDirectories()
        val llFile = compiledLlFile(compileText(LlvmTarget(4), false, nativeHello.source, dir))
        val wasm = llFile.resolveSibling(llFile.fileName.toString().removeSuffix(".ll") + ".wasm")
        withClue("a compile without -o only writes the LLVM IR") { wasm.exists() shouldBe false }
        val printed = printedOnStdout { LlvmTarget(4).launchEmulator(1, llFile, quiet = false) }
        printed shouldContain "Building $wasm from $llFile with clang..."
        printed shouldContain "Running $wasm..."
        printed shouldContain "Program exited with status 42"
        wasm.exists() shouldBe true
    }

    test("an llvm32 extsub whose signature doesn't match the C function fails to link, the matching one runs").config(enabledOrReasonIf = requiresWasm) {
        fun strlenProgram(returnType: String) = """
            %zeropage basicsafe
            main {
                extsub strlen(str s) -> $returnType
                sub start() {
                    sys.exit(lsb(strlen("hello")))
                }
            }"""
        val dir = outputDir.resolve("wasm-signatures").createDirectories()
        // size_t is 32 bits on wasm32, so a uquad result is the wrong signature
        val mismatched = compiledLlFile(compileText(LlvmTarget(4), false, strlenProgram("uquad"), dir))
        val (status, output) = buildAndRunWasm(mismatched)
        status shouldNotBe 0
        output shouldContain "signature mismatch"
        val wasm = mismatched.resolveSibling(mismatched.fileName.toString().removeSuffix(".ll") + ".wasm")
        shouldNotThrowAny { LlvmTarget(4).launchEmulator(1, mismatched, quiet = true) }
        wasm.exists() shouldBe false
        val matching = compiledLlFile(compileText(LlvmTarget(4), false, strlenProgram("long"), dir))
        buildAndRunWasm(matching).first shouldBe 5
    }

    test("the llvm32 programs without 64-bit addresses compute the right results as Emscripten programs under node").config(enabledOrReasonIf = requiresEmscripten) {
        val dir = outputDir.resolve("emscripten").createDirectories()
        for ((name, source) in programs.filterKeys { it !in programsWith64BitAddresses && it !in notRunUnderEmscripten }) {
            for (optimize in listOf(false, true)) {
                val llFile = compiledLlFile(compileText(emscriptenTarget(), optimize, source, dir))
                val (exitCode, output) = buildAndRunEmscripten(llFile)
                withClue("$name optimize=$optimize: exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
            }
        }
    }

    for ((name, program) in outputPrograms.filterKeys { it !in notRunUnderEmscripten }) {
        test("$name: gives the expected exit code and console output as an llvm32 Emscripten program under node, which leaves no files behind").config(enabledOrReasonIf = requiresEmscripten) {
            for (optimize in listOf(false, true)) {
                withClue("optimize=$optimize") {
                    // a program prints the name of its working directory
                    val workingDirectory = outputDir.resolve("emscripten-run").resolve("run")
                    workingDirectory.toFile().deleteRecursively()
                    workingDirectory.createDirectories()
                    val llFile = compiledLlFile(compileText(emscriptenTarget(), optimize, program.source, outputDir.resolve("emscripten").createDirectories()))
                    val (exitCode, output) = buildAndRunEmscripten(llFile, program.input, program.args, workingDirectory)
                    withClue(output) { exitCode shouldBe program.exitCode }
                    output shouldBe program.output
                    withClue("the files left in the working directory") { workingDirectory.listDirectoryEntries() shouldBe emptyList() }
                }
            }
        }
    }

    test("on llvm32 with Emscripten, call() and goto reach subroutines through void () entries under node, also those that return a value").config(enabledOrReasonIf = requiresEmscripten) {
        val dir = outputDir.resolve("emscripten-indirect").createDirectories()
        for (optimize in listOf(false, true)) {
            withClue("optimize=$optimize") {
                val llFile = compiledLlFile(compileText(emscriptenTarget(), optimize, indirectCalls32.source, dir))
                llFile.readLines().any { it.startsWith("define internal void @.indirect.main.answer()") } shouldBe true
                val (exitCode, output) = buildAndRunEmscripten(llFile)
                withClue(output) { exitCode shouldBe indirectCalls32.exitCode }
                output shouldBe indirectCalls32.output
            }
        }
    }

    test("launchEmulator links an llvm32 Emscripten program into a single .js file next to the .ll file and runs it with node").config(enabledOrReasonIf = requiresEmscriptenOnThePath) {
        val dir = outputDir.resolve("emscripten-launched").createDirectories()
        val llFile = compiledLlFile(compileText(emscriptenTarget(), false, nativeHello.source, dir))
        val js = llFile.resolveSibling(llFile.fileName.toString().removeSuffix(".ll") + ".js")
        val printed = printedOnStdout { emscriptenTarget().launchEmulator(1, llFile, quiet = false) }
        printed shouldContain "Running $js..."
        printed shouldContain "Program exited with status 42"
        js.exists() shouldBe true
        llFile.resolveSibling("${js.fileName}.o").exists() shouldBe false
        llFile.resolveSibling(llFile.fileName.toString().removeSuffix(".ll") + ".wasm").exists() shouldBe false
    }

    test("an llvm32 Emscripten extsub whose signature doesn't match the C function fails to link, the matching one runs").config(enabledOrReasonIf = requiresEmscripten) {
        fun strlenProgram(returnType: String) = """
            %zeropage basicsafe
            main {
                extsub strlen(str s) -> $returnType
                sub start() {
                    sys.exit(lsb(strlen("hello")))
                }
            }"""
        val dir = outputDir.resolve("emscripten-signatures").createDirectories()
        val mismatched = compiledLlFile(compileText(emscriptenTarget(), false, strlenProgram("uquad"), dir))
        val (status, output) = buildAndRunEmscripten(mismatched)
        status shouldNotBe 0
        output shouldContain "signature mismatch"
        val matching = compiledLlFile(compileText(emscriptenTarget(), false, strlenProgram("long"), dir))
        buildAndRunEmscripten(matching).first shouldBe 5
    }

    test("the llvm32 programs without 64-bit addresses compute the right results as bare metal ARM programs on qemu").config(enabledOrReasonIf = requiresArm) {
        val dir = outputDir.resolve("arm").createDirectories()
        for ((name, source) in programs.filterKeys { it !in programsWith64BitAddresses && it !in notRunOnArm }) {
            for (optimize in listOf(false, true)) {
                val llFile = compiledLlFile(compileText(armTarget(), optimize, source, dir))
                val (exitCode, output) = buildAndRunArm(llFile)
                withClue("$name optimize=$optimize: exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
            }
        }
    }

    for ((name, program) in outputPrograms.filterKeys { it !in notRunOnArm }) {
        test("$name: gives the expected exit code and console output as an llvm32 bare metal ARM program on qemu, which leaves no files behind").config(enabledOrReasonIf = requiresArm) {
            for (optimize in listOf(false, true)) {
                withClue("optimize=$optimize") {
                    val workingDirectory = outputDir.resolve("arm-run")
                    workingDirectory.toFile().deleteRecursively()
                    workingDirectory.createDirectories()
                    val llFile = compiledLlFile(compileText(armTarget(), optimize, program.source, outputDir.resolve("arm").createDirectories()))
                    val (exitCode, output) = buildAndRunArm(llFile, program.input, program.args, workingDirectory)
                    withClue(output) { exitCode shouldBe program.exitCode }
                    output shouldBe program.output
                    withClue("the files left in the working directory") { workingDirectory.listDirectoryEntries() shouldBe emptyList() }
                }
            }
        }
    }

    test("launchEmulator links an llvm32 program for bare metal ARM into a .elf file next to the .ll file and runs it on qemu").config(enabledOrReasonIf = requiresArm) {
        val dir = outputDir.resolve("arm-launched").createDirectories()
        val llFile = compiledLlFile(compileText(armTarget(), false, nativeHello.source, dir))
        val elf = llFile.resolveSibling(llFile.fileName.toString().removeSuffix(".ll") + ".elf")
        val printed = printedOnStdout { armTarget().launchEmulator(1, llFile, quiet = false) }
        printed shouldContain "Building $elf from $llFile with clang..."
        printed shouldContain "Running $elf..."
        printed shouldContain "Program exited with status 42"
        elf.exists() shouldBe true
        llFile.resolveSibling("${elf.fileName}.o").exists() shouldBe false
        llFile.resolveSibling("${elf.fileName}.qemu-semihosting-console.c").exists() shouldBe false
    }

    test("the llvm32 programs without 64-bit addresses compute the right results as bare metal RISC-V programs on qemu").config(enabledOrReasonIf = requiresRiscv) {
        val dir = outputDir.resolve("riscv").createDirectories()
        for ((name, source) in programs.filterKeys { it !in programsWith64BitAddresses && it !in notRunOnRiscv }) {
            for (optimize in listOf(false, true)) {
                val llFile = compiledLlFile(compileText(riscvTarget(), optimize, source, dir))
                val (exitCode, output) = buildAndRunRiscv(llFile)
                withClue("$name optimize=$optimize: exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
            }
        }
    }

    for ((name, program) in outputPrograms.filterKeys { it !in notRunOnRiscv }) {
        test("$name: gives the expected exit code and console output as an llvm32 bare metal RISC-V program on qemu, which leaves no files behind").config(enabledOrReasonIf = requiresRiscv) {
            for (optimize in listOf(false, true)) {
                withClue("optimize=$optimize") {
                    val workingDirectory = outputDir.resolve("riscv-run")
                    workingDirectory.toFile().deleteRecursively()
                    workingDirectory.createDirectories()
                    val llFile = compiledLlFile(compileText(riscvTarget(), optimize, program.source, outputDir.resolve("riscv").createDirectories()))
                    val (exitCode, output) = buildAndRunRiscv(llFile, program.input, program.args, workingDirectory)
                    withClue(output) { exitCode shouldBe program.exitCode }
                    output shouldBe program.output
                    withClue("the files left in the working directory") { workingDirectory.listDirectoryEntries() shouldBe emptyList() }
                }
            }
        }
    }

    test("launchEmulator links an llvm32 program for bare metal RISC-V with its boot code into a .elf file and runs it on qemu, leaving no build files behind")
        .config(enabledOrReasonIf = requiresRiscvOnThePath) {
        val dir = outputDir.resolve("riscv-launched").createDirectories()
        val llFile = compiledLlFile(compileText(riscvTarget(), false, nativeHello.source, dir))
        val elf = llFile.resolveSibling(llFile.fileName.toString().removeSuffix(".ll") + ".elf")
        val printed = printedOnStdout { riscvTarget().launchEmulator(1, llFile, quiet = false) }
        printed shouldContain "Running $elf..."
        printed shouldContain "Program exited with status 42"
        elf.exists() shouldBe true
        llFile.resolveSibling("${elf.fileName}.o").exists() shouldBe false
        llFile.resolveSibling("${elf.fileName}.riscv32-qemu-virt-boot.S").exists() shouldBe false
        llFile.resolveSibling("${elf.fileName}.qemu-semihosting-console.c").exists() shouldBe false
    }

    test("launchEmulator builds a native executable next to the .ll file and runs it").config(enabledOrReasonIf = requiresNativeLlvm) {
        val dir = outputDir.resolve("launched").createDirectories()
        val llFile = compiledLlFile(compileText(LlvmTarget(), false, nativeHello.source, dir))
        val executable = llFile.resolveSibling(llFile.fileName.toString().removeSuffix(".ll"))
        withClue("a compile without -o only writes the LLVM IR") { executable.exists() shouldBe false }
        val printed = printedOnStdout { LlvmTarget().launchEmulator(1, llFile, quiet = false) }
        printed shouldContain "Building $executable from $llFile with clang..."
        printed shouldContain "Running $executable..."
        printed shouldContain "Program exited with status 42"
        withClue("$executable should be executable") { Files.isExecutable(executable) shouldBe true }
        execute(executable.toString()) shouldBe (nativeHello.exitCode to nativeHello.output)

        val selfChecking = compiledLlFile(compileText(LlvmTarget(), true, programs.getValue("64-bit quad and uquad integers"), dir))
        printedOnStdout { LlvmTarget().launchEmulator(1, selfChecking, quiet = false) } shouldContain "Program exited with status 0"
    }

    test("launchEmulator gives the program its arguments natively").config(enabledOrReasonIf = requiresNativeLlvm) {
        launchesWithArguments(LlvmTarget(), outputDir.resolve("arguments-native"))
    }

    test("launchEmulator gives the program its arguments after the file in the run command, under wasmtime").config(enabledOrReasonIf = requiresWasm) {
        launchesWithArguments(LlvmTarget(4), outputDir.resolve("arguments-wasm"))
    }

    test("launchEmulator gives the program its arguments under node").config(enabledOrReasonIf = requiresEmscriptenOnThePath) {
        launchesWithArguments(emscriptenTarget(), outputDir.resolve("arguments-emscripten"))
    }

    test("launchEmulator gives the program its arguments through the semihosting command line on bare metal ARM").config(enabledOrReasonIf = requiresArm) {
        launchesWithArguments(armTarget(), outputDir.resolve("arguments-arm"))
    }

    test("launchEmulator gives the program its arguments through the semihosting command line, which the boot code splits, on bare metal RISC-V")
        .config(enabledOrReasonIf = requiresRiscvOnThePath) {
        launchesWithArguments(riscvTarget(), outputDir.resolve("arguments-riscv"))
    }

    test("%link libraries of all modules reach the link of -o and of launchEmulator, once each, with files relative to their module")
        .config(enabledOrReasonIf = requiresNativeLlvm) {
        val dir = outputDir.resolve("link-directive").createDirectories()
        val libraryDir = dir.resolve("lib dir").createDirectories()
        val source = libraryDir.resolve("tripler.c").also { it.writeText("int p8test_triple(int x) { return x * 3; }\n") }
        val objectFile = libraryDir.resolve("tripler.o")
        withClue("compiling the library") {
            execute(checkNotNull(llvmBin).resolve("clang").toString(), "-c", source.toString(), "-o", objectFile.toString()).first shouldBe 0
        }
        dir.resolve("tripler.p8").writeText("""
            %link "lib dir/tripler.o", "m"
            tripler {
                extsub p8test_triple(word x) -> word
            }""")
        val program = """
            %import tripler
            %zeropage basicsafe
            %link "m"
            main {
                sub start() {
                    word @shared x = 14
                    sys.exit(lsb(tripler.p8test_triple(x)))
                }
            }"""
        val result = compileText(LlvmTarget(), false, program, dir).shouldNotBeNull()
        result.compilationOptions.linkLibraries shouldBe listOf(objectFile.toRealPath().toString(), "-lm")
        val irFile = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir")
        withClue("kept in the .p8ir file") { IRFileReader().read(irFile).options.linkLibraries shouldBe result.compilationOptions.linkLibraries }
        val llFile = compiledLlFile(result)
        withClue("the -emu build gets the libraries from the compilation") {
            printedOnStdout { LlvmTarget().launchEmulator(1, llFile, quiet = false, emptyList(), result.compilationOptions.linkLibraries) } shouldContain
                "Program exited with status 42"
        }
        val executable = dir.resolve("linked")
        compileText(LlvmTarget(), false, program, dir, outputFile = executable).shouldNotBeNull()
        execute(executable.toString()).first shouldBe 42
    }

    test("%link takes library names and existing files, at module level, on the llvm targets only") {
        val dir = outputDir.resolve("link-errors").createDirectories()
        val src = """
            %link "nosuchfile.a", "-Wl,-x", "a b", ""
            %link
            main {
                %link "m"
                sub start() {
                }
            }"""
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(LlvmTarget(), false, src, dir, errors = errors, writeAssembly = false) shouldBe null
        errors.errors.map { it.substringAfter(": ") } shouldBe listOf(
            "library file not found: nosuchfile.a",
            "a library name can't start with '-'; pass linker options with -Xlink instead",
            "a library name can't contain whitespace",
            "a library name can't be empty",
            "invalid link directive, expected one or more library names or files: \"name\" [, \"name\"...]",
            "this directive may only occur at module level")

        val vmErrors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(VMTarget(), false, "%link \"m\"\nmain {\n sub start() {\n }\n}", dir, errors = vmErrors, writeAssembly = false) shouldBe null
        vmErrors.errors.single() shouldContain "%link is only available on the llvm32 and llvm64 targets"
    }

    test("-o without .ll builds a native executable with the LLVM IR next to it").config(enabledOrReasonIf = requiresNativeLlvm) {
        val dir = outputDir.resolve("named").createDirectories()
        val executable = dir.resolve("hello-native")
        compileText(LlvmTarget(), false, nativeHello.source, dir, outputFile = executable).shouldNotBeNull()
        withClue("the LLVM IR next to the executable") { dir.resolve("hello-native.ll").exists() shouldBe true }
        withClue("$executable should be executable") { Files.isExecutable(executable) shouldBe true }
        execute(executable.toString()) shouldBe (nativeHello.exitCode to nativeHello.output)

        val irFile = dir.resolve("hello-ir.ll")
        compileText(LlvmTarget(), false, nativeHello.source, dir, outputFile = irFile).shouldNotBeNull()
        irFile.exists() shouldBe true
        withClue("an -o name ending in .ll only gets the LLVM IR") { dir.resolve("hello-ir").exists() shouldBe false }

        val built = Files.getLastModifiedTime(executable)
        printedOnStdout { LlvmTarget().launchEmulator(1, executable, quiet = false) } shouldContain "Program exited with status 42"
        withClue("an existing -o executable runs as it is") { Files.getLastModifiedTime(executable) shouldBe built }
        withClue("prog8c -emu exits with the status that launchEmulator returns") { LlvmTarget().launchEmulator(1, executable, quiet = true, emptyList()) shouldBe 42 }
    }

    test("code that has no LLVM translation is a compilation error") {
        val src = """
main {
    sub start() {
        %asm {{
            nop
        }}
    }
}"""
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(LlvmTarget(), false, src, outputDir, errors = errors) shouldBe null
        errors.errors.single() shouldContain "main.start has inline assembly, which can't be translated to LLVM IR"
    }

    test("quad and uquad are rejected on every retro target, and go through code generation on llvm64 and virtual") {
        val src = $$"""
main {
    quad @shared big = 5000000000
    uquad[2] @shared masks = [$ffff_ffff_0000_0000, 1]

    struct Stamp {
        uquad seconds
    }

    sub start() {
        ^^Stamp @shared stamp = ^^Stamp : [$1_0000_0000]
        big = scaled(big, masks[0]) + (stamp.seconds as quad)
    }

    sub scaled(quad value, uquad mask) -> quad {
        return value * 3 + (mask as quad)
    }
}"""
        for (target in listOf(C64Target(), C128Target(), Cx16Target(), PETTarget(), Qemu68kTarget(), Amiga500Target(), Amiga1200Target())) {
            withClue(target.name) {
                val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                compileText(target, false, src, outputDir, errors = errors, writeAssembly = false) shouldBe null
                errors.errors.shouldNotBeEmpty()
                errors.errors.forEach { it shouldContain "64-bit integer types (quad/uquad) are not supported on target ${target.name}" }
            }
        }
        for (target in listOf(LlvmTarget(), VMTarget())) {
            for (optimize in listOf(false, true)) {
                withClue("${target.name} optimize=$optimize") {
                    compileText(target, optimize, src, outputDir).shouldNotBeNull()
                }
            }
        }
    }

    test("the quad programs give the same results on the virtual machine") {
        for (name in quadProgramsForTheVm) {
            for (optimize in listOf(false, true)) {
                val result = compileText(VMTarget(), optimize, programs.getValue(name), outputDir).shouldNotBeNull()
                val irFile = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir")
                withClue("$name optimize=$optimize: exit status = the number of the failed check") { exitStatusOnVirtualMachine(irFile) shouldBe 0 }
            }
        }
    }

    test("an unknown target name is rejected") {
        shouldThrow<IllegalArgumentException> { getCompilationTargetByName("llvm16") }
    }

    test("a byte pointer or str parameter needs a cast to become another typed pointer, and call() an untyped address, on llvm64 as on the virtual machine") {
        val src = """
main {
    struct Node {
        ubyte kind
    }
    ubyte[4] @shared buffer
    ^^Node @shared node

    sub start() {
        ^^ubyte @shared bytes = &buffer
        ^^Node @shared other = bytes
        node = bytes
        bytes = node
        setkind(bytes)
        fromtext("abc")
        uword @shared result = call(node)
    }

    sub setkind(^^Node n) {
        n.kind = 1
    }

    sub fromtext(str s) {
        node = s
    }
}"""
        fun errorsOn(target: ICompilationTarget): List<String> {
            val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
            compileText(target, false, src, outputDir, errors = errors, writeAssembly = false) shouldBe null
            return errors.errors.map { it.substringAfter(".p8:") }
        }
        val llvmErrors = errorsOn(LlvmTarget())
        llvmErrors shouldBe listOf(
            "11:32: cannot assign different pointer type, expected ^^main.Node or uword but got ^^ubyte",
            "12:16: cannot assign different pointer type, expected ^^main.Node or uword but got ^^ubyte",
            "13:17: cannot assign different pointer type, expected ^^ubyte or uword but got ^^main.Node",
            "14:17: argument 1 type mismatch, was: ^^ubyte expected: ^^main.Node",
            "16:37: argument 1 type mismatch, was: ^^main.Node expected: POINTER",
            "24:16: cannot assign different pointer type, expected ^^main.Node or uword but got ^^ubyte")
        errorsOn(VMTarget()) shouldBe llvmErrors

        val casted = """
main {
    struct Node {
        ubyte kind
    }
    ubyte[4] @shared buffer
    ^^Node @shared node

    sub start() {
        ^^ubyte @shared bytes = &buffer
        ^^Node @shared other = bytes as ^^Node
        node = bytes as ^^Node
        bytes = node as ^^ubyte
        setkind(bytes as ^^Node)
        fromtext("abc")
        uword @shared result = call(node as pointer)
    }

    sub setkind(^^Node n) {
        n.kind = 1
    }

    sub fromtext(str s) {
        node = s as ^^Node
    }
}"""
        for (target in listOf(LlvmTarget(), VMTarget()))
            withClue(target.name) { compileText(target, false, casted, outputDir, writeAssembly = false).shouldNotBeNull() }
    }

    test("the untyped pointer type is the address integer type on every target, uquad on llvm64, and has the size of a pointer") {
        val src = """
main {
    pointer @shared global
    pointer[3] @shared table = [&global, 0, 0]
    ubyte @shared size = sizeof(pointer)

    struct Node {
        pointer data
    }

    sub start() {
        pointer @shared local = &global
        local = identity(local as pointer)
    }

    sub identity(pointer address) -> pointer {
        return address
    }
}"""
        fun types(result: CompilationResult?): List<DataType> {
            val main = result.shouldNotBeNull().compilerAst.allBlocks.first { it.name == "main" }
            val variables = main.statements.filterIsInstance<VarDecl>().associate { it.name to it.datatype }
            val identity = main.statements.filterIsInstance<Subroutine>().first { it.name == "identity" }
            val local = result.compilerAst.entrypoint.statements.filterIsInstance<VarDecl>().first { it.name == "local" }
            val field = main.statements.filterIsInstance<StructDecl>().single().fields.single()
            return listOf(variables.getValue("global"), local.datatype, identity.parameters.single().type, identity.returntypes.single(),
                field.type, variables.getValue("table"))
        }
        fun size(result: CompilationResult?): Double {
            val main = result.shouldNotBeNull().compilerAst.allBlocks.first { it.name == "main" }
            return (main.statements.filterIsInstance<Assignment>().single { it.target.identifier?.nameInSource == listOf("size") }.value as NumericLiteral).number
        }
        for (target in listOf(LlvmTarget(), VMTarget(), Qemu68kTarget(), Cx16Target())) {
            withClue(target.name) {
                val result = compileText(target, false, src, outputDir, writeAssembly = false)
                val table = if (target.POINTER_MEM_SIZE == 2u) DataType.splitWordArrayFor(BaseDataType.UWORD) else DataType.arrayFor(target.pointerBaseType, target)
                types(result) shouldBe List(5) { target.pointerType } + table
                size(result) shouldBe target.POINTER_MEM_SIZE.toDouble()
            }
        }
        LlvmTarget().pointerType shouldBe DataType.UQUAD
    }

    test("the untyped pointer converts implicitly to and from typed pointers on every target, as the address integer type that it is") {
        val src = """
main {
    struct Node {
        ubyte kind
        pointer data
    }
    ^^Node node
    pointer[2] @shared table = [0, 0]

    sub start() {
        node = ^^Node: [1, 0]
        pointer anything = node
        ^^Node back = anything
        anything = back
        node = anything
        node.data = node
        back = node.data
        table[1] = node
        back = table[1]
        back = kindless(node)
        ubyte @shared kind = kindof(anything)
        pointer routine = &answer
        uword @shared result = call(routine)
    }

    sub kindof(^^Node n) -> ubyte {
        return n.kind
    }

    sub kindless(pointer p) -> pointer {
        return p
    }

    sub answer() -> uword {
        return 42
    }
}"""
        for (target in listOf(LlvmTarget(), VMTarget(), Cx16Target()))
            withClue(target.name) { compileText(target, false, src, outputDir, writeAssembly = false).shouldNotBeNull() }
    }

    test("on llvm64 two different typed pointers still need an explicit cast") {
        val src = """
main {
    sub start() {
        ^^uword @shared words = 0
        ^^word @shared signedwords = words
    }
}"""
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(LlvmTarget(), false, src, outputDir, errors = errors, writeAssembly = false) shouldBe null
        errors.errors.single() shouldContain "cannot assign different pointer type, expected ^^word or uword but got ^^uword"
    }

    for ((name, program) in externalPrograms) {
        test("$name: extsubs are declared and called by their bare C name, with the Prog8 widths and no extension attributes, with and without optimization") {
            for (optimize in listOf(false, true)) {
                withClue("optimize=$optimize") {
                    val lines = compiledLlFile(compileText(LlvmTarget(), optimize, program.source, outputDir)).readLines()
                    lines.filter { it.startsWith("declare") && "putchar" in it } shouldBe listOf("declare i8 @putchar(i8)")
                    lines.none { ("declare" in it || "call" in it) && ("@main." in it && ("putchar" in it || "puts" in it || "getchar" in it)) } shouldBe true
                    lines.none { it.startsWith("declare") && ("zeroext" in it || "signext" in it) } shouldBe true
                    lines.any { it.trim().startsWith("call i8 @putchar(i8 ") || it.trim().contains("= call i8 @putchar(i8 ") } shouldBe true
                }
            }
        }

        test("$name: gives the expected exit code and output as a native arm64 macOS executable").config(enabledOrReasonIf = requiresNativeLlvm) {
            for (optimize in listOf(false, true)) {
                withClue("optimize=$optimize") {
                    val llFile = compiledLlFile(compileText(LlvmTarget(), optimize, program.source, outputDir))
                    val (exitCode, output) = buildAndRun(llFile, program.input)
                    withClue(output) { exitCode shouldBe program.exitCode }
                    output shouldBe program.output
                }
            }
        }
    }

    test("the declarations and calls of extsubs for putchar, puts and getchar, with their Prog8 types") {
        val src = """
%zeropage basicsafe
%option no_sysinit

main {
    extsub putchar(ubyte char) -> ubyte
    extsub puts(str msg) -> uword
    extsub getchar() -> ubyte

    sub start() {
        putchar('A')
        void puts("Hello world\n")
        ubyte @shared x = getchar() + 1
        void putchar(x)
    }
}"""
        for (optimize in listOf(false, true)) {
            withClue("optimize=$optimize") {
                val lines = compiledLlFile(compileText(LlvmTarget(), optimize, src, outputDir)).readLines().map { it.trim() }
                lines shouldContain "declare i8 @putchar(i8)"
                lines shouldContain "declare i16 @puts(ptr)"
                lines shouldContain "declare i8 @getchar()"
                lines.count { it.startsWith("call i8 @putchar(i8 ") } shouldBe 2
                lines.count { it.startsWith("call i16 @puts(ptr ") } shouldBe 1
                lines.count { it.contains("= call i8 @getchar()") } shouldBe 1
            }
        }
    }

    test("extsub parameter and return types keep their exact LLVM widths") {
        val src = """
%zeropage basicsafe
%option no_sysinit
%option enable_floats

main {
    struct Point { ubyte x, y }
    extsub all(bool a, byte b, ubyte c, word d, uword e, long f, quad g, uquad h, float i, ^^Point j, ^^ubyte k, str l) -> float
    extsub nothing(long value)
    extsub flag() -> bool
    extsub quads() -> uquad
    extsub node() -> ^^Point

    sub start() {
        ^^Point @shared p = 0
        ubyte @shared bytes = 5
        float @shared r = all(true, -1, 2, -3, 4, 5, 6, 7, 8.5, p, &bytes, "text")
        nothing(9)
        bool @shared flagged = flag()
        uquad @shared big = quads()
        p = node()
    }
}"""
        for (optimize in listOf(false, true)) {
            withClue("optimize=$optimize") {
                val lines = compiledLlFile(compileText(LlvmTarget(), optimize, src, outputDir)).readLines().map { it.trim() }
                lines shouldContain "declare double @all(i8, i8, i8, i16, i16, i32, i64, i64, double, ptr, ptr, ptr)"
                lines shouldContain "declare void @nothing(i32)"
                lines shouldContain "declare i8 @flag()"
                lines shouldContain "declare i64 @quads()"
                lines shouldContain "declare ptr @node()"
                lines.any { it.startsWith("call void @nothing(i32 ") } shouldBe true
                lines.any { it.contains("= call double @all(i8 ") } shouldBe true
            }
        }
    }

    test("the .p8ir file has the external routines with their flag, and reading it back keeps them") {
        val src = """
%zeropage basicsafe
%option no_sysinit

main {
    extsub putchar(ubyte char) -> ubyte
    extsub puts(str msg) -> uword

    sub start() {
        void putchar('A')
        void puts("Hello")
    }
}"""
        for (optimize in listOf(false, true)) {
            withClue("optimize=$optimize") {
                val result = compileText(LlvmTarget(), optimize, src, outputDir).shouldNotBeNull()
                val irFile = compiledLlFile(result).resolveSibling("${result.compilerAst.name}.p8ir")
                val lines = irFile.readLines()
                lines.count { it.startsWith("<ASMSUB NAME=\"main.putchar\"") && it.endsWith("EXTERNAL=\"true\">") } shouldBe 1
                lines.count { it.startsWith("<ASMSUB NAME=\"main.puts\"") && it.endsWith("EXTERNAL=\"true\">") } shouldBe 1
                lines.any { it.trim().startsWith("call main.putchar(char=") } shouldBe true
                val subs = IRFileReader().read(irFile).allAsmSubs().filter { it.isExternal }.associateBy { it.label }
                subs.keys shouldBe setOf("main.putchar", "main.puts")
                subs.getValue("main.putchar").returns.single().dt shouldBe DataType.UBYTE
                subs.getValue("main.puts").parameters.single().dt.base shouldBe BaseDataType.POINTER
            }
        }
    }

    test("an extsub that nothing calls is dropped by the optimizer, and declared without it") {
        val src = """
%zeropage basicsafe
%option no_sysinit

main {
    extsub getchar() -> ubyte
    sub start() {
        cx16.r0L = 1
    }
}"""
        compiledLlFile(compileText(LlvmTarget(), false, src, outputDir)).readLines() shouldContain "declare i8 @getchar()"
        compiledLlFile(compileText(LlvmTarget(), true, src, outputDir)).readLines().none { "@getchar" in it } shouldBe true
    }

    test("extsubs whose C function the llvm64 runtime declares differently are a compilation error that names the extsub") {
        fun errorsOf(src: String, optimize: Boolean): List<String> {
            val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
            compileText(LlvmTarget(), optimize, src, outputDir, errors = errors) shouldBe null
            return errors.errors
        }
        val textio = """
%import textio
%zeropage basicsafe

main {
    extsub putchar(ubyte char) -> ubyte
    sub start() {
        txt.print("hi")
        void putchar('A')
    }
}"""
        val getchar = """
%import textio
%zeropage basicsafe

main {
    extsub getchar() -> ubyte
    sub start() {
        void txt.chrin()
        void getchar()
    }
}"""
        val die = """
%zeropage basicsafe
%option no_sysinit

main {
    extsub putchar(ubyte char) -> ubyte
    sub start() {
        void putchar('A')
        sys.die(1, "x")
    }
}"""
        for (optimize in listOf(false, true)) {
            withClue("optimize=$optimize") {
                errorsOf(textio, optimize).single() shouldContain "extsub main.putchar declares C function 'putchar' as i8 (i8), which conflicts with the llvm64 runtime that declares it as i32 (i32)"
                errorsOf(getchar, optimize).single() shouldContain "extsub main.getchar declares C function 'getchar' as i8 (), which conflicts with the llvm64 runtime that declares it as i32 ()"
                errorsOf(die, optimize).single() shouldContain "extsub main.putchar declares C function 'putchar' as i8 (i8), which conflicts with the llvm64 runtime"
            }
        }
    }

    test("two extsubs that declare a C function with different types are a compilation error that names both, with the same types it is fine") {
        fun src(otherType: String) = """
%zeropage basicsafe
%option no_sysinit

main {
    extsub putchar(ubyte char) -> ubyte
    sub start() {
        void putchar('A')
        other.hello()
    }
}

other {
    extsub putchar($otherType
    sub hello() {
        void putchar('B')
    }
}"""
        for (optimize in listOf(false, true)) {
            withClue("optimize=$optimize") {
                val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                compileText(LlvmTarget(), optimize, src("long char) -> long"), outputDir, errors = errors) shouldBe null
                val message = errors.errors.single()
                message shouldContain "extsub other.putchar declares C function 'putchar' as i32 (i32), which conflicts with extsub main.putchar (at "
                message shouldContain ") that declares it as i8 (i8)"
                compiledLlFile(compileText(LlvmTarget(), optimize, src("ubyte c) -> ubyte"), outputDir)).readLines().count { it == "declare i8 @putchar(i8)" } shouldBe 1
            }
        }
    }
})


/**
 * Builds a static library with p8test_answer() returning 42 in a directory with a space in its name, links it into a program
 * for [target] with -Xlink, and checks that launchEmulator builds and runs the program, which exits with that answer.
 */
private fun linksExtraLibrary(outputDir: Path, target: LlvmTarget, dirName: String, ar: Path, compileLibrary: (Path, Path) -> List<String>) {
    val dir = outputDir.resolve(dirName).createDirectories()
    val libraryDir = dir.resolve("lib dir").createDirectories()
    val source = libraryDir.resolve("p8test.c").also { it.writeText("int p8test_answer(void) { return 42; }\n") }
    val objectFile = libraryDir.resolve("p8test.o")
    withClue("compiling the library") { execute(*compileLibrary(source, objectFile).toTypedArray()).first shouldBe 0 }
    withClue("archiving the library") { execute(ar.toString(), "rcs", libraryDir.resolve("libp8test.a").toString(), objectFile.toString()).first shouldBe 0 }
    val linking = LlvmTarget(target.pointerSize, target.profile, extraLinkArgs = listOf("-L$libraryDir", "-lp8test"))
    val program = """
        %zeropage basicsafe
        main {
            extsub p8test_answer() -> long
            sub start() {
                sys.exit(p8test_answer() as ubyte)
            }
        }"""
    val llFile = compiledLlFile(compileText(linking, false, program, dir))
    printedOnStdout { linking.launchEmulator(1, llFile, quiet = false) } shouldContain "Program exited with status 42"
}

private fun compiledLlFile(result: CompilationResult?): Path {
    val compiled = result.shouldNotBeNull()
    val file = OutputFiles.of(compiled.compilationOptions, compiled.compilerAst.name).programFile
    withClue("$file should exist") { file.exists() shouldBe true }
    return file
}

// llvm-as and clang of one LLVM installation; Homebrew installs LLVM keg-only, outside the PATH
private val llvmBin: Path? = (System.getenv("PATH").orEmpty().split(File.pathSeparator) + "/opt/homebrew/opt/llvm/bin")
    .mapNotNull { try { Path(it) } catch (_: InvalidPathException) { null } }
    .firstOrNull { Files.isExecutable(it.resolve("llvm-as")) && Files.isExecutable(it.resolve("clang")) }

private val requiresNativeLlvm: (TestCase) -> Enabled = {
    val arm64Mac = System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") == "aarch64"
    when {
        !arm64Mac -> Enabled.disabled("linking the default llvm64 target triple needs an arm64 macOS host")
        llvmBin == null -> Enabled.disabled("llvm-as and clang not found on the PATH or in /opt/homebrew/opt/llvm/bin")
        else -> Enabled.enabled
    }
}

private val requiresNativeLlvmAndAr: (TestCase) -> Enabled = {
    if (llvmBin?.resolve("llvm-ar")?.let { Files.isExecutable(it) } != true) Enabled.disabled("llvm-ar not found next to llvm-as") else requiresNativeLlvm(it)
}

/**
 * Builds the .ll file into an executable with clang -O2 and runs it with [input] on stdin and the command line [args],
 * in the working [directory] if there is one; returns its exit code and output, or clang's if that failed.
 */
private fun buildAndRun(llFile: Path, input: String = "", args: List<String> = emptyList(), directory: Path? = null): Pair<Int, String> {
    val executable = llFile.resolveSibling(llFile.fileName.toString().removeSuffix(".ll"))
    val compiled = execute(checkNotNull(llvmBin).resolve("clang").toString(), "-O2", "-Wno-override-module", llFile.toString(), "-o", executable.toString())
    if (compiled.first != 0)
        return compiled
    val stdin = executable.resolveSibling("${executable.fileName}.stdin").also { it.writeText(input) }
    return execute(executable.toString(), *args.toTypedArray(), stdin = stdin, directory = directory)
}

private val requiresWasm: (TestCase) -> Enabled = {
    val sysroot = System.getenv("WASI_SYSROOT") ?: "/opt/homebrew/opt/wasi-libc/share/wasi-sysroot"
    fun onPath(tool: String) = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .any { try { Files.isExecutable(Path(it).resolve(tool)) } catch (_: InvalidPathException) { false } }
    when {
        llvmBin == null -> Enabled.disabled("llvm-as and clang not found on the PATH or in /opt/homebrew/opt/llvm/bin")
        !Files.isDirectory(Path(sysroot)) -> Enabled.disabled("no WASI sysroot in WASI_SYSROOT or $sysroot")
        !onPath("wasm-ld") -> Enabled.disabled("wasm-ld (from lld) not found on the PATH")
        !onPath("wasmtime") -> Enabled.disabled("wasmtime not found on the PATH")
        else -> Enabled.enabled
    }
}

/**
 * Builds the .ll file into a .wasm file with clang and the clang arguments of the llvm32 target's profile, and runs it
 * with the profile's run command, in the working [directory] (default: next to the .ll file). The profile makes wasm-ld
 * warnings fatal, because a function signature mismatch only warns, and traps when called.
 */
private fun buildAndRunWasm(llFile: Path, input: String = "", args: List<String> = emptyList(), directory: Path? = null): Pair<Int, String> {
    val profile = LlvmTarget(4).profile
    val wasm = llFile.resolveSibling(llFile.fileName.toString().removeSuffix(".ll") + profile.outputExtension)
    val compiled = execute(checkNotNull(llvmBin).resolve("clang").toString(), *profile.clangArgs.toTypedArray(), "-O2", "-Wno-override-module",
        llFile.toString(), "-o", wasm.toString())
    if (compiled.first != 0)
        return compiled
    val stdin = wasm.resolveSibling("${wasm.fileName}.stdin").also { it.writeText(input) }
    val command = LlvmTripleProfile.runCommandFor(profile.runCommand, wasm.toAbsolutePath().toString(), args)
    return execute(*command.toTypedArray(), stdin = stdin, directory = directory ?: llFile.parent)
}

private val requiresWasmAndAr: (TestCase) -> Enabled = {
    if (llvmBin?.resolve("llvm-ar")?.let { Files.isExecutable(it) } != true) Enabled.disabled("llvm-ar not found next to llvm-as") else requiresWasm(it)
}

private val armProfile = LlvmTripleProfile.resolve("armv7a-none-eabihf")

private fun armTarget() = LlvmTarget(4, armProfile)

private val requiresArm: (TestCase) -> Enabled = {
    val linker = try { armProfile.linkCommand?.first()?.let { Path(it) } } catch (_: IllegalArgumentException) { null } catch (_: InvalidPathException) { null }
    fun onPath(tool: String) = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .any { try { Files.isExecutable(Path(it).resolve(tool)) } catch (_: InvalidPathException) { false } }
    when {
        llvmBin == null -> Enabled.disabled("llvm-as and clang not found on the PATH or in /opt/homebrew/opt/llvm/bin")
        linker == null || !Files.isExecutable(linker) -> Enabled.disabled("arm-none-eabi-gcc of the Arm GNU Toolchain not found in ARM_GNU_TOOLCHAIN or its default location")
        !onPath("qemu-system-arm") -> Enabled.disabled("qemu-system-arm not found on the PATH")
        else -> Enabled.enabled
    }
}

/**
 * Builds the .ll file into a .elf file with clang and the link command and console code of the bare metal ARM profile, and runs it on qemu
 * with [input] and a Ctrl-D, which ends it, on stdin, the command line [args] and the working [directory] (default: next to the .ll file).
 */
private fun buildAndRunArm(llFile: Path, input: String = "", args: List<String> = emptyList(), directory: Path? = null): Pair<Int, String> {
    val elf = llFile.resolveSibling(llFile.fileName.toString().removeSuffix(".ll") + armProfile.outputExtension)
    val objectFile = elf.resolveSibling("${elf.fileName}.o")
    val compiled = execute(checkNotNull(llvmBin).resolve("clang").toString(), *armProfile.clangArgs.toTypedArray(), "-O2", "-Wno-override-module", "-c",
        llFile.toString(), "-o", objectFile.toString())
    if (compiled.first != 0)
        return compiled
    return linkAndRunOnQemu(armProfile, checkNotNull(armProfile.linkCommand), objectFile, elf, input, args, directory ?: llFile.parent)
}

private val riscvProfile = LlvmTripleProfile.resolve("riscv32-unknown-elf")

private fun riscvTarget() = LlvmTarget(4, riscvProfile)

// riscv-none-elf-gcc from RISCV_NONE_ELF_GCC, the PATH, or the default install location of the xPack toolchain
private val riscvGcc: Path? = System.getenv("RISCV_NONE_ELF_GCC")?.let { try { Path(it) } catch (_: InvalidPathException) { null } }
    ?: System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .mapNotNull { try { Path(it).resolve("riscv-none-elf-gcc") } catch (_: InvalidPathException) { null } }.firstOrNull { Files.isExecutable(it) }
    ?: Path(System.getProperty("user.home"), "Library/xPacks/@xpack-dev-tools/riscv-none-elf-gcc").toFile().listFiles().orEmpty()
        .sortedDescending().map { it.toPath().resolve(".content/bin/riscv-none-elf-gcc") }.firstOrNull { Files.isExecutable(it) }

private val requiresRiscv: (TestCase) -> Enabled = {
    fun onPath(tool: String) = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .any { try { Files.isExecutable(Path(it).resolve(tool)) } catch (_: InvalidPathException) { false } }
    when {
        llvmBin == null -> Enabled.disabled("llvm-as and clang not found on the PATH or in /opt/homebrew/opt/llvm/bin")
        riscvGcc == null -> Enabled.disabled("riscv-none-elf-gcc not found in RISCV_NONE_ELF_GCC, on the PATH or in ~/Library/xPacks")
        !onPath("qemu-system-riscv32") -> Enabled.disabled("qemu-system-riscv32 not found on the PATH")
        else -> Enabled.enabled
    }
}

// launchEmulator links with the profile's own link command, which finds riscv-none-elf-gcc only through RISCV_NONE_ELF_GCC or the PATH
private val requiresRiscvOnThePath: (TestCase) -> Enabled = {
    val linker = riscvProfile.linkCommand?.first()
    val found = linker != null && System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .any { try { Files.isExecutable(Path(it).resolve(linker)) } catch (_: InvalidPathException) { false } } || System.getenv("RISCV_NONE_ELF_GCC") != null
    if (!found) Enabled.disabled("riscv-none-elf-gcc is not in RISCV_NONE_ELF_GCC or on the PATH") else requiresRiscv(it)
}

/** Builds the .ll file with the link command, boot and console code of the bare metal RISC-V profile, and runs it as [buildAndRunArm] does. */
private fun buildAndRunRiscv(llFile: Path, input: String = "", args: List<String> = emptyList(), directory: Path? = null): Pair<Int, String> {
    val elf = llFile.resolveSibling(llFile.fileName.toString().removeSuffix(".ll") + riscvProfile.outputExtension)
    val objectFile = elf.resolveSibling("${elf.fileName}.o")
    val compiled = execute(checkNotNull(llvmBin).resolve("clang").toString(), *riscvProfile.clangArgs.toTypedArray(), "-O2", "-Wno-override-module", "-c",
        llFile.toString(), "-o", objectFile.toString())
    if (compiled.first != 0)
        return compiled
    val linkCommand = listOf(checkNotNull(riscvGcc).toString()) + checkNotNull(riscvProfile.linkCommand).drop(1)
    return linkAndRunOnQemu(riscvProfile, linkCommand, objectFile, elf, input, args, directory ?: llFile.parent)
}

/** Links [objectFile] and the startup sources of [profile] into [elf] with [linkCommand], and runs it on qemu with [input] and a Ctrl-D on stdin. */
private fun linkAndRunOnQemu(profile: LlvmTripleProfile, linkCommand: List<String>, objectFile: Path, elf: Path, input: String, args: List<String>,
                             directory: Path): Pair<Int, String> {
    val startup = profile.writeStartupSources(elf)
    val link = linkCommand.flatMap { word ->
        if (word == "{startup}") startup.map { it.toString() }
        else listOf(word.replace("{object}", objectFile.toString()).replace("{file}", elf.toString()))
    }
    val linked = execute(*link.toTypedArray())
    Files.deleteIfExists(objectFile)
    startup.forEach { Files.deleteIfExists(it) }
    if (linked.first != 0)
        return linked
    val stdin = elf.resolveSibling("${elf.fileName}.stdin").also { it.writeText(input + "\u0004") }
    val command = LlvmTripleProfile.runCommandFor(profile.runCommand, elf.toAbsolutePath().toString(), args)
    return execute(*command.toTypedArray(), stdin = stdin, directory = directory).also { Files.deleteIfExists(stdin) }
}

private val emscriptenProfile = LlvmTripleProfile.resolve("wasm32-unknown-emscripten")

private fun emscriptenTarget() = LlvmTarget(4, emscriptenProfile)

private val emcc: Path? = System.getenv("EMCC")?.let { try { Path(it) } catch (_: InvalidPathException) { null } }
    ?: System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .mapNotNull { try { Path(it).resolve("emcc") } catch (_: InvalidPathException) { null } }.firstOrNull { Files.isExecutable(it) }

private val requiresEmscripten: (TestCase) -> Enabled = {
    fun onPath(tool: String) = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .any { try { Files.isExecutable(Path(it).resolve(tool)) } catch (_: InvalidPathException) { false } }
    when {
        llvmBin == null -> Enabled.disabled("llvm-as and clang not found on the PATH or in /opt/homebrew/opt/llvm/bin")
        emcc == null -> Enabled.disabled("emcc not found in EMCC or on the PATH")
        !onPath("node") -> Enabled.disabled("node not found on the PATH")
        else -> Enabled.enabled
    }
}

// launchEmulator runs the profile's own link command, which finds emcc only through EMCC or the PATH
private val requiresEmscriptenOnThePath: (TestCase) -> Enabled = {
    val found = System.getenv("EMCC") != null || System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .any { try { Files.isExecutable(Path(it).resolve("emcc")) } catch (_: InvalidPathException) { false } }
    if (!found) Enabled.disabled("emcc is not in EMCC or on the PATH") else requiresEmscripten(it)
}

/**
 * Builds the .ll file into a single .js file with clang and the link command of the Emscripten profile, and runs it with node,
 * giving it [input] on stdin and the command line [args], in the working [directory] (default: next to the .ll file).
 */
private fun buildAndRunEmscripten(llFile: Path, input: String = "", args: List<String> = emptyList(), directory: Path? = null): Pair<Int, String> {
    val js = llFile.resolveSibling(llFile.fileName.toString().removeSuffix(".ll") + emscriptenProfile.outputExtension)
    val objectFile = js.resolveSibling("${js.fileName}.o")
    val compiled = execute(checkNotNull(llvmBin).resolve("clang").toString(), *emscriptenProfile.clangArgs.toTypedArray(), "-O2", "-Wno-override-module", "-c",
        llFile.toString(), "-o", objectFile.toString())
    if (compiled.first != 0)
        return compiled
    val link = listOf(checkNotNull(emcc).toString()) + checkNotNull(emscriptenProfile.linkCommand).drop(1)
        .map { it.replace("{object}", objectFile.toString()).replace("{file}", js.toString()) }
    val linked = execute(*link.toTypedArray())
    Files.deleteIfExists(objectFile)
    if (linked.first != 0)
        return linked
    val stdin = js.resolveSibling("${js.fileName}.stdin").also { it.writeText(input) }
    val command = LlvmTripleProfile.runCommandFor(emscriptenProfile.runCommand, js.toAbsolutePath().toString(), args)
    return execute(*command.toTypedArray(), stdin = stdin, directory = directory ?: llFile.parent)
}

/** The program exits with 20 times its argument count, plus the length of its first argument, plus 1 if the second is "-v"; WASI only allows a status below 126. */
private fun launchesWithArguments(target: LlvmTarget, directory: Path) {
    val source = """
        %import strings
        %zeropage basicsafe
        main {
            sub start() {
                ubyte status = lsb(sys.argc()) * 20 + strings.length(sys.argv(1))
                if strings.compare(sys.argv(2), "-v") == 0
                    status++
                sys.exit(status)
            }
        }"""
    val llFile = compiledLlFile(compileText(target, false, source, directory.createDirectories()))
    printedOnStdout { target.launchEmulator(1, llFile, quiet = false, listOf("a, b c", "-v", "")) } shouldContain "Program exited with status ${4 * 20 + 6 + 1}"
    printedOnStdout { target.launchEmulator(1, llFile, quiet = false) } shouldContain "Program exited with status 20"
}

private fun execute(vararg command: String, stdin: Path? = null, directory: Path? = null): Pair<Int, String> {
    val builder = ProcessBuilder(*command).redirectErrorStream(true)
    stdin?.let { builder.redirectInput(it.toFile()) }
    directory?.let { builder.directory(it.toFile()) }
    val process = builder.start()
    if (stdin == null)
        process.outputStream.close()
    // read on another thread, so that a program that hangs, like qemu after a crash, is stopped after the timeout
    val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly().waitFor()
        error("${command.first()} did not finish within 60 seconds and was stopped")
    }
    return process.exitValue() to output.join()
}

/** Other specs may print on System.out at the same time, so only check that the captured text contains something. */
private fun printedOnStdout(action: () -> Unit): String {
    val original = System.out
    val captured = ByteArrayOutputStream()
    System.setOut(PrintStream(captured, true, Charsets.UTF_8))
    try {
        action()
    } finally {
        System.setOut(original)
    }
    return captured.toString(Charsets.UTF_8)
}

private fun exitStatusOnVirtualMachine(irFile: Path): Int {
    val vm = VirtualMachine(IRFileReader().read(irFile))
    try {
        while (true)
            vm.step(1000)
    } catch (exit: ProgramExitException) {
        return exit.status
    }
}

// these use uquad as the address type, which only holds addresses where pointers have 8 bytes
private val programsWith64BitAddresses = setOf(
    "64-bit addresses",
    "64-bit addresses beyond 4 GiB through pushp, popp, pokep and peekp, and defer with computed untyped addresses",
    "uquad addresses with signed operands, long values, strings and typed address initializers",
    "uquad and typed pointer loop variables over uquad address arrays",
    "uquad struct fields indexed like the address they hold",
    "a uquad address dereferenced with ^^ reads and writes the byte at that address",
    "inlined subroutines compute with pointer arguments as their integer parameters, and with integer arguments as their pointer parameters",
    "uquad constants folded with signed operands as at runtime, and typed pointers compared with negative numbers",
    "the untyped pointer is a uquad in variables, constants, parameters, return values, struct fields and arrays",
    "a goto through a uquad address variable ends a subroutine that returns a value"
)

// programs that don't run as they are under wasmtime: WASI's working directory is "/" rather than a host path;
// and extsubs declared with the 64-bit size_t of LP64 hosts don't link against wasi-libc
private val notRunUnderWasmtime = setOf(
    "diskio directories, renaming and deleting",
    "extsubs call C library functions with long, quad, uquad, float, bool, string and pointer types"
)

// programs that don't run as they are with Emscripten: extsubs declared with the 64-bit size_t of LP64 hosts don't link
private val notRunUnderEmscripten = setOf(
    "extsubs call C library functions with long, quad, uquad, float, bool, string and pointer types"
)

// programs that don't run as they are on bare metal ARM: newlib on semihosting has no mkdir, chdir, rmdir and getcwd,
// its rename fails, and it loses the position in a file when a seek fails; extsubs declared with the 64-bit size_t of LP64 hosts get wrong results
private val notRunOnArm = setOf(
    "diskio directories, renaming and deleting",
    "diskio writing, reading lines, seeking in and deleting a text file",
    "fileio copying between two open files, seeking and the end of the file",
    "extsubs call C library functions with long, quad, uquad, float, bool, string and pointer types"
)

// the same on bare metal RISC-V, except that its newlib keeps the position in a file when a seek fails
private val notRunOnRiscv = setOf(
    "diskio directories, renaming and deleting",
    "extsubs call C library functions with long, quad, uquad, float, bool, string and pointer types"
)

// the 64-bit programs that don't use the constants of the llvm64 library, so they also run on the virtual machine
private val quadProgramsForTheVm = listOf(
    "quad and uquad arithmetic, bitwise logic and shifts",
    "64-bit literals and constant folding",
    "quad and uquad conversions",
    "quad and uquad arrays, structs and memory",
    "pointer element updates and offsets beyond 64 KiB",
    "lsb, msb, lsw and msw of quads and addresses, and signed offsets to addresses",
    "arithmetic on the elements of a pointer to untyped pointers",
    "pushq, popq, pushp, popp, pokep and peekp, and defer with quad, long and pointer return values",
    "quad and uquad loop variables over quad and uquad arrays",
    "never written quad and uquad variables become constants of their own type"
)

/** Complete programs that exit with 0, or with the number of the first check that fails. @shared inputs can't be constant folded. */
private val programs = mapOf(
    "arithmetic expressions" to $$"""
main {
    ubyte @shared ub1 = 200
    ubyte @shared ub2 = 100
    byte @shared b1 = -100
    uword @shared uw1 = 60000
    word @shared w1 = -1234
    long @shared l1 = 100000
    long @shared l2 = -3

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ubyte ub = ub1 + ub2
        if ub != 44
            return 1
        byte b = b1 - 100
        if b != 56
            return 2
        uword uw = uw1 + 6000
        if uw != 464
            return 3
        word w = w1 * 3
        if w != -3702
            return 4
        long l = l1 * l2
        if l != -300000
            return 5
        if l1 / 7 != 14285 or l1 % 7 != 5
            return 6
        if w1 / 10 != -123 or uw1 / 1000 != 60
            return 7
        if (ub1 as uword) * 4 != 800
            return 8
        if ub1 >> 3 != 25 or uw1 << 2 != 43392 or b1 >> 2 != -25
            return 9
        if (ub1 & $0f) | $30 != $38 or ub1 ^ $ff != 55 or ~ub2 != 155
            return 10
        if -w1 != 1234 or abs(w1) != 1234
            return 11
        if l2 * l2 * l2 != -27
            return 12
        if sqrt(uw1) != 244
            return 13
        if min(ub1, ub2) != 100 or max(w1, 5) != 5 or clamp(w1, -100, 100) != -100
            return 14
        if msb(uw1) != $ea or lsb(uw1) != $60 or mkword($12, $34) != $1234
            return 15
        if sgn(w1) != -1 or sgn(l1) != 1
            return 16
        return 0
    }
}""",
    "pointers to memory-mapped variables use the same storage as the variables" to $$"""
main {
    &ubyte[4] regs = $0400
    &uword wreg = $0410
    ^^ubyte @shared globalPointer = &&regs

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        regs[1] = 5
        regs[3] = 7
        wreg = $1234
        ^^ubyte p = &&regs
        if p[1] != 5
            return 1
        ^^ubyte @shared q
        q = &&regs[3]
        if q^^ != 7
            return 2
        if globalPointer[1] != 5
            return 3
        ^^uword @shared wp = &&wreg
        if wp^^ != $1234
            return 4
        p[2] = 9
        if regs[2] != 9
            return 5
        return 0
    }
}""",
    $$"32-bit literals above $7fff_ffff keep their written value as a uquad, quad or pointer" to $$"""
main {
    uquad @shared globalQuad = $9000_0000

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        uquad @shared q = $ffff_ffff
        quad @shared sq = $8000_0000
        long @shared l = $ffff_ffff
        uquad @shared q2
        q2 = 4294967295
        pointer @shared p = $9000_0000
        if q != 4294967295 or (q >> 31) != 1
            return 1
        if sq <= 0
            return 2
        if l != -1 or l != $ffff_ffff
            return 3
        if q2 != q
            return 4
        if (globalQuad >> 28) != 9 or globalQuad != $9000_0000
            return 5
        if q + 1 != $1_0000_0000
            return 6
        if (globalQuad | $ffff_ffff) != $ffff_ffff or (q & $8000_0000) != $8000_0000
            return 7
        if p != $9000_0000
            return 8
        return 0
    }
}""",
    "if/else, when and loops" to """
main {
    ubyte @shared count = 10
    word @shared signedstart = -5

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        uword sum = 0
        ubyte i
        for i in 1 to count {
            sum += i
        }
        if sum != 55
            return 1
        sum = 0
        for i in count downto 1 step -3 {
            sum += i
        }
        if sum != 22
            return 2
        word ws = 0
        word wi
        for wi in signedstart to 5 step 2 {
            ws += wi
        }
        if ws != 0
            return 3
        ubyte n = 0
        while n < count {
            n++
            if n == 7
                break
        }
        if n != 7
            return 4
        ubyte odds = 0
        n = 0
        do {
            n++
            if n % 2 == 0
                continue
            odds++
        } until n >= count
        if odds != 5
            return 5
        ubyte reps = 0
        repeat count {
            reps++
        }
        if reps != 10
            return 6
        if classify(-5) != 1 or classify(0) != 2 or classify(9) != 3
            return 7
        if weekday(3) != 30 or weekday(6) != 99 or weekday(1) != 10
            return 8
        n = 0
again:
        n++
        if n < 5
            goto again
        if n != 5
            return 9
        return 0
    }

    sub classify(byte value) -> ubyte {
        if value < 0
            return 1
        else if value == 0
            return 2
        else
            return 3
    }

    sub weekday(ubyte day) -> ubyte {
        when day {
            1 -> return 10
            2, 3 -> return day * 10
            4 to 5 -> return 50
            else -> return 99
        }
    }
}""",
    "subroutines with parameters and return values" to """
%option enable_floats

main {
    ubyte @shared seven = 7
    ubyte counter

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        if add3(1, 2, seven) != 10
            return 1
        if scale(-300, 4) != -1200
            return 2
        ubyte q, r
        q, r = divide(100, seven)
        if q != 14 or r != 2
            return 3
        if fib(15) != 610
            return 4
        if not isodd(seven) or isodd(8)
            return 5
        if twice(2.25) != 4.5
            return 6
        if longsum(100000, 200000) != 300000
            return 7
        repeat 5 {
            bump()
        }
        if counter != 5
            return 8
        return 0
    }

    sub bump() {
        counter++
    }

    sub add3(ubyte a, ubyte b, ubyte c) -> ubyte {
        return a + b + c
    }

    sub scale(word value, byte factor) -> word {
        return value * factor
    }

    sub divide(ubyte dividend, ubyte divisor) -> ubyte, ubyte {
        return dividend / divisor, dividend % divisor
    }

    sub fib(ubyte n) -> uword {
        uword a = 0
        uword b = 1
        repeat n {
            uword t = a + b
            a = b
            b = t
        }
        return a
    }

    sub isodd(ubyte value) -> bool {
        return value & 1 == 1
    }

    sub twice(float value) -> float {
        return value * 2.0
    }

    sub longsum(long a, long b) -> long {
        return a + b
    }
}""",
    "arrays" to """
%option enable_floats

main {
    ubyte @shared idx = 2
    ubyte[] bytes = [10, 20, 30, 40, 50]
    uword[] words = [1000, 2000, 3000, 4000]
    long[] longs = [-1, 100000, 7]
    float[] floats = [1.5, 2.5, 3.5]
    bool[] flags = [true, false, true]
    str[] names = ["alpha", "beta", "gamma"]
    ubyte[8] buffer

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        if bytes[idx] != 30 or bytes[4] != 50
            return 1
        if words[idx] != 3000 or words[idx+1] != 4000
            return 2
        if longs[idx-1] != 100000 or floats[idx] != 3.5
            return 3
        if flags[idx-1] or not flags[idx]
            return 4
        bytes[idx] = 99
        words[idx] += 5
        if bytes[2] != 99 or words[2] != 3005
            return 5
        uword total = 0
        ubyte b
        for b in bytes {
            total += b
        }
        if total != 219
            return 6
        if 40 not in bytes or 41 in bytes
            return 7
        if 4000 not in words or 100000 not in longs or 2.5 not in floats
            return 8
        ubyte i
        for i in 0 to len(buffer)-1 {
            buffer[i] = i * 3
        }
        if buffer[7] != 21
            return 9
        ^^ubyte name = names[idx]
        if name[1] != 'a' or name[4] != 'a' or name[5] != 0
            return 10
        return 0
    }
}""",
    "strings" to """
main {
    str hello = "hello"
    str other = "help!"
    str buffer = "?????????"

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        if hello == other or hello >= other or not (hello < other)
            return 1
        if 'l' not in hello or 'z' in hello
            return 2
        if hello[1] != 'e'
            return 3
        ubyte i
        for i in 0 to 5 {
            buffer[i] = hello[i]
        }
        if buffer != hello or buffer[6] != '?'
            return 4
        ubyte vowels = 0
        ubyte char
        for char in hello {
            if char in "aeiou"
                vowels++
        }
        if vowels != 2
            return 5
        hello[0] = 'j'
        if hello != "jello"
            return 6
        return 0
    }
}""",
    "typed pointers and structs" to """
main {
    ubyte[10] data
    uword[4] words = [100, 200, 300, 400]

    struct Node {
        ubyte kind
        uword value
        ^^Node next
    }

    ^^Node first
    ^^Node second

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ^^ubyte p = &data
        ubyte i
        for i in 0 to 9 {
            p^^ = i * 2
            p++
        }
        if data[9] != 18 or data[3] != 6
            return 1
        ^^uword wp = &words
        wp += 2
        if wp^^ != 300
            return 2
        wp[1] = 999
        if words[3] != 999
            return 3
        first = ^^Node: [1, 1111, 0]
        second = ^^Node: [2, 2222, 0]
        first.next = second
        if first.next.value != 2222 or first.next.kind != 2
            return 4
        second.value += 3
        uword total = 0
        ^^Node n = first
        while n != 0 {
            total += n.value
            n = n.next
        }
        if total != 3336
            return 5
        return 0
    }
}""",
    "global initializers, defer and builtins" to $$"""
main {
    ubyte @shared five = 5
    uword @shared computed = five * 100 + 3
    long @shared biglong = $12345678
    ^^ubyte slab = memory("scratch", 16, 0)
    ubyte deferflag

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ; no widening in Prog8: the ubyte expression wraps around before it is assigned
        if computed != 247
            return 1
        ubyte a = 1
        ubyte b = 2
        swap(a, b)
        if a != 2 or b != 1
            return 2
        if msw(biglong) != $1234 or lsw(biglong) != $5678
            return 3
        uword w = $abcd
        setlsb(w, $11)
        setmsb(w, $22)
        if w != $2211
            return 4
        poke(slab, 42)
        pokew(slab + 2, $beef)
        if peek(slab) != 42 or peekw(slab + 2) != $beef
            return 5
        ubyte carrybits = %10000001
        sys.clear_carry()
        rol(carrybits)
        rol(carrybits)
        if carrybits != %00000101
            return 6
        if defertest() != 1 or deferflag != 2
            return 7
        return 0
    }

    sub defertest() -> ubyte {
        deferflag = 1
        defer deferflag = 2
        return deferflag
    }
}""",
    "untyped pointers" to """
main {
    ubyte[16] buffer
    uword @shared wordoffset = 12
    long @shared longoffset = 13
    word @shared negativeoffset = -5
    str message = "hello"
    pointer[3] ptrs = [&buffer, 0, 0]
    pointer slab = memory("ptrslab", 32, 0)

    struct Node {
        ubyte kind
        uword value
        pointer data
        ^^Node next
    }

    ^^Node first
    ^^Node second

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        pointer p = &buffer
        @(p) = 11
        p[1] = 22
        p += 2
        @(p) = 33
        if buffer[0] != 11 or buffer[1] != 22 or buffer[2] != 33
            return 1
        pointer q = p + 5
        if q - p != 5 or p == 0 or not (q > p)
            return 2
        poke(slab, 7)
        pokew(slab + 4, 1234)
        if peek(slab) != 7 or peekw(slab + 4) != 1234
            return 3
        pointer m = message
        if m[4] != 'o'
            return 4
        first = ^^Node: [1, 1111, 0, 0]
        second = ^^Node: [2, 2222, 0, 0]
        first.next = second
        pointer anynode = first.next
        ^^Node back = anynode
        if back.value != 2222
            return 5
        anynode = first
        back = anynode
        if back.kind != 1
            return 6
        first.data = second
        ^^Node fromdata = first.data
        if fromdata.value != 2222
            return 7
        if kindof(anynode) != 1 or kindof(first.data) != 2
            return 8
        ^^Node viareturn = untyped(second)
        if viareturn.value != 2222
            return 9
        if ptrs[0] != &buffer or ptrs[1] != 0
            return 10
        ptrs[1] = ptrs[0] + 1
        ptrs[2] = second
        if @(ptrs[1]) != 22
            return 11
        ^^Node fromarray = ptrs[2]
        if fromarray.value != 2222
            return 12
        pointer routine = &answer
        if call(routine) != 4242
            return 13
        buffer[12] = 120
        buffer[13] = 130
        buffer[7] = 70
        if @(p + wordoffset - 2) != 120 or @(&buffer + longoffset) != 130
            return 14
        q = p + wordoffset
        q += negativeoffset
        if @(q - 2) != 70
            return 15
        return 0
    }

    sub kindof(^^Node node) -> ubyte {
        return node.kind
    }

    sub untyped(pointer address) -> pointer {
        return address
    }

    sub answer() -> uword {
        return 4242
    }
}""",
    "jumps into other subroutines" to """
main {
    ubyte @shared counter

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ; return f() of a subroutine f without parameters becomes a jump to f
        if pick(true) != 11 or pick(false) != 22
            return 1
        if widened() != 22
            return 2
        bump()
        if counter != 11
            return 3
        return 0
    }

    sub pick(bool first) -> ubyte {
        if first
            return eleven()
        return twentytwo()
    }

    sub eleven() -> ubyte {
        return 11
    }

    sub twentytwo() -> ubyte {
        return 22
    }

    sub widened() -> uword {
        return twentytwo()
    }

    sub bump() {
        counter += 10
        goto increment
    }

    sub increment() {
        counter++
    }
}""",
    "short-circuit conditions and the cx16 virtual registers" to $$"""
main {
    ubyte @shared upper = 'A'
    ubyte @shared lower = 'q'
    ubyte @shared zero = 0

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        if islower(upper) or not islower(lower)
            return 1
        if not isedge(zero) or isedge(upper)
            return 2
        cx16.r0 = 1234
        cx16.r1L = 5
        cx16.r1H = 6
        if cx16.r0 != 1234 or cx16.r1 != $0605 or cx16.r0r1sl != $060504d2
            return 3
        return 0
    }

    sub islower(ubyte char) -> bool {
        return char >= 'a' and char <= 'z'
    }

    sub isedge(ubyte char) -> bool {
        return char == 0 or char >= 250
    }
}""",
    "64-bit quad and uquad integers" to $$"""
%option enable_floats

main {
    quad @shared qmax = sys.MAX_QUAD
    quad @shared qmin = sys.MIN_QUAD
    uquad @shared umax = sys.MAX_UQUAD
    quad @shared qzero = 0
    uquad @shared uzero = 0
    quad @shared qminus1 = -1
    quad @shared q5e9 = 5000000000
    quad @shared qneg = -7000000000
    uquad @shared ucross = $1_0000_0001
    uquad @shared pattern = $ffff_ffff_ffff_fff6
    quad @shared qseven = 7
    uquad @shared useven = 7
    ubyte @shared n40 = 40
    uquad @shared ubelow = $7fff_ffff_0000_0005
    uquad @shared uabove = $8000_0000_0000_0005
    byte @shared bm5 = -5
    ubyte @shared ub200 = 200
    word @shared wm300 = -300
    uword @shared uw65535 = 65535
    long @shared lmin = -2147483648
    uquad @shared mixed = $1234_5678_8765_4321
    uquad @shared ubig = $8000_0000_0000_0800
    float @shared fneg = -123456789012.75
    float @shared fbig = 1.0e19
    quad[] @shared qarr = [-1, 5000000000, -9223372036854775808, 1234567890123456789]
    ubyte @shared idx = 2
    quad @shared needle = $2_2a05_f200
    ubyte[16] buffer
    quad @shared beyond53 = 9007199254740993
    uquad @shared highlow = $ffff_ffff_0000_0001

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        quad q = qmax + 1
        uquad u = umax + 1
        if q != sys.MIN_QUAD or qmin - 1 != sys.MAX_QUAD or -qmin != sys.MIN_QUAD or u != 0 or uzero - 1 != sys.MAX_UQUAD
            return 1
        if ucross * ucross != ($2_0000_0001 as uquad) or q5e9 * qneg != 1893488147419103232
            return 2
        if (pattern as quad) / qseven != -1 or (pattern as quad) % qseven != -3
            return 3
        if pattern / useven != ($2492_4924_9249_2490 as uquad) or pattern % useven != 6
            return 4
        if q5e9 / qzero != sys.MAX_QUAD or qneg / qzero != sys.MAX_QUAD or qneg % qzero != 0
            return 5
        if umax / uzero != sys.MAX_UQUAD or umax % uzero != 0 or qmin / qminus1 != sys.MIN_QUAD or qmin % qminus1 != 0
            return 6
        if qminus1 << n40 != -1099511627776 or umax << n40 != ($ffff_ff00_0000_0000 as uquad)
            return 7
        if qmin >> n40 != -8388608 or (qmin as uquad) >> n40 != ($80_0000 as uquad) or umax >> n40 != ($ff_ffff as uquad)
            return 8
        if uabove <= ubelow or (uabove as quad) >= (ubelow as quad) or uabove == ubelow or (uabove as long) != (ubelow as long)
            return 9
        q = bm5
        u = ub200
        if q != -5 or q + wm300 != -305 or (bm5 as uquad) != $ffff_ffff_ffff_fffb or u + uw65535 != (65735 as uquad) or (uw65535 as quad) != 65535
            return 10
        q = lmin
        if q != -2147483648 or (lmin as uquad) != $ffff_ffff_8000_0000
            return 11
        if (mixed as long) != -2023406815 or (mixed as word) != $4321 or (mixed as ubyte) != $21
            return 12
        float f = ubig as float
        if f != 9223372036854777856.0 or (f as uquad) != ubig
            return 13
        if (qneg as float) != -7000000000.0 or (fneg as quad) != -123456789012 or (fbig as uquad) != 10000000000000000000
            return 14
        if qarr[idx] != sys.MIN_QUAD or qarr[idx+1] != 1234567890123456789 or q5e9 not in qarr or needle in qarr
            return 15
        qarr[idx] = qneg
        if qarr[2] != -7000000000 or qneg not in qarr
            return 16
        if addq(q5e9, qneg) != -2000000000 or addq(qmax, 1) != sys.MIN_QUAD
            return 17
        q, u = two()
        if q != -14000000000 or u != $ffff_ffff_ffff_fffe
            return 18
        ; little-endian, like the arm64 host
        pokeq(&buffer, qneg)
        pokeq(&buffer + 8, umax - 1)
        if peekq(&buffer) != qneg or buffer[8] != $fe or buffer[15] != $ff
            return 19
        buffer[15] = $7f
        if peekq(&buffer + 8) != sys.MAX_QUAD - 1
            return 20
        if beyond53 % 10 != 3 or beyond53 - 9007199254740000 != 993 or (highlow as long) != 1 or highlow >> 32 != ($0_ffff_ffff as uquad)
            return 21
        return 0
    }

    sub addq(quad x, quad y) -> quad {
        return x + y
    }

    sub two() -> quad, uquad {
        return qneg * 2, umax - 1
    }
}""",
    "quad and uquad arithmetic, bitwise logic and shifts" to $$"""
main {
    quad @shared qmax = 9223372036854775807
    quad @shared qmin = -9223372036854775808
    uquad @shared umax = $ffff_ffff_ffff_ffff
    uquad @shared utop = $8000_0000_0000_0000
    quad @shared qzero = 0
    uquad @shared uzero = 0
    quad @shared qone = 1
    uquad @shared uone = 1
    quad @shared qminus1 = -1
    quad @shared q31 = $7fff_ffff
    quad @shared q32 = $0_ffff_ffff
    uquad @shared u32 = $0_ffff_ffff
    quad @shared big = 123456789012345
    quad @shared nbig = -98765432109876
    quad @shared q7e9 = 7000000001
    quad @shared qn7e9 = -7000000001
    quad @shared q1e9 = 1000000000
    quad @shared qn1e9 = -1000000000
    uquad @shared upat = $9234_5678_9abc_def1
    uquad @shared hi = $ffff_ffff_0000_0000
    uquad @shared magic = $1234_5678_9abc_def0
    uquad @shared nibbles = $0f0f_0f0f_0f0f_0f0f
    ; the same low 32 bits $8000_0001
    quad @shared qa = $1_8000_0001
    quad @shared qb = -6442450943
    quad @shared qc = $2_8000_0001
    ubyte @shared n33 = 33
    ubyte @shared n36 = 36

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        quad qpat = (upat as quad)
        if big + nbig != 24691356902469 or nbig - big != -222222221122221 or qzero - big != -123456789012345 or nbig + nbig != -197530864219752
            return 1
        if u32 + u32 != ($1_ffff_fffe as uquad) or hi + u32 != umax or hi - u32 != $ffff_fffe_0000_0001
            return 2
        if upat - magic != $8000_0000_0000_0001 or magic - upat != ($7fff_ffff_ffff_ffff as uquad)
            return 3
        if q1e9 * qn7e9 != -7000000001000000000 or qn1e9 * qn1e9 != 1000000000000000000 or nbig * -3 != 296296296329628
            return 4
        ; wraparound
        if qmax + qmax != -2 or qmin + qmin != 0 or qmin + qminus1 != qmax or qmax - qminus1 != qmin
            return 5
        if uzero - uone != umax or umax + umax != $ffff_ffff_ffff_fffe or umax * umax != 1 or hi * hi != 0
            return 6
        if qmax * qmax != 1 or qmin * qmin != 0 or qmin * qminus1 != qmin or q7e9 * q7e9 != -6340232207128654847
            return 7
        if u32 * u32 != $ffff_fffe_0000_0001 or upat * 3 != $b69d_0369_d036_9cd3 or magic * 16 != ($2345_6789_abcd_ef00 as uquad)
            return 8
        ; signed division truncates toward zero, the remainder has the sign of the dividend
        if qn7e9 / q1e9 != -7 or qn7e9 % q1e9 != -1 or q7e9 / qn1e9 != -7 or q7e9 % qn1e9 != 1 or qn7e9 / qn1e9 != 7 or qn7e9 % qn1e9 != -1
            return 9
        if nbig / 10000000000 != -9876 or nbig % 10000000000 != -5432109876 or big / -10000000000 != -12345 or big % -10000000000 != 6789012345
            return 10
        if umax / u32 != ($1_0000_0001 as uquad) or umax % u32 != 0
            return 11
        if upat / (u32 + uone) != ($0_9234_5678 as uquad) or upat % (u32 + uone) != ($0_9abc_def1 as uquad)
            return 12
        ; hi is -2^32 as a quad
        if hi / u32 != ($1_0000_0000 as uquad) or hi % u32 != 0 or (hi as quad) / q32 != -1 or (hi as quad) % q32 != -1
            return 13
        if hi / ($1_0000 as uquad) != ($ffff_ffff_0000 as uquad) or (hi as quad) / $1_0000 != -65536 or hi % 7 != 5 or (hi as quad) % 7 != -4
            return 14
        ; like the VM: dividing by 0 gives the largest value, and a remainder of 0
        if qmin / qzero != qmax or qzero / qzero != qmax or qmin % qzero != 0 or uone / uzero != umax or uzero / uzero != umax or utop % uzero != 0
            return 15
        if qmax / qminus1 != qmin + 1 or (qmin + 1) / qminus1 != qmax or qmin / -1 != qmin or qmin % -1 != 0 or qmin / qmax != -1 or qmin % qmax != -1
            return 16
        quad q = qmin
        q = -q
        if q != qmin or -nbig != 98765432109876 or -qmax != qmin + 1 or (-(q32 + qone) as uquad) != hi
            return 17
        if ~qzero != umax or ~uzero != umax or ~hi != u32 or ~big != -123456789012346 or ~qmin != qmax or ~magic != $edcb_a987_6543_210f
            return 18
        if magic & nibbles != ($0204_0608_0a0c_0e00 as uquad) or magic | nibbles != ($1f3f_5f7f_9fbf_dfff as uquad)
            return 19
        if magic ^ nibbles != ($1d3b_5977_95b3_d1ff as uquad) or magic ^ $ffff_ffff_0000_0000 != $edcb_a987_9abc_def0
            return 20
        if magic & $ffff_ffff_0000_0000 != ($1234_5678_0000_0000 as uquad) or magic | $ffff_ffff_0000_0000 != $ffff_ffff_9abc_def0
            return 21
        if qmin | qmax != umax or qmin & qmax != 0 or qpat ^ (magic as quad) != qmin + 1
            return 22
        if qpat & $0f0f_0f0f_0f0f_0f0f != $0204_0608_0a0c_0e01 or ((qpat | $0f0f_0f0f_0f0f_0f0f) as uquad) != $9f3f_5f7f_9fbf_dfff
            return 23

        ; compound assignments on a quad
        q = q32
        q += qone
        if q != $1_0000_0000
            return 24
        q -= 4294967297
        q *= nbig
        if q != 98765432109876
            return 25
        q /= -1000
        q %= 1000000
        if q != -432109
            return 26
        q = qpat
        q &= -4294967296
        q |= $0f0f_0f0f
        q ^= qminus1
        if q != $6dcb_a987_f0f0_f0f0
            return 27
        q = qpat
        q >>= n33
        q <<= 4
        if (q as uquad) != $ffff_fffc_91a2_b3c0
            return 28
        q >>= 2
        if (q as uquad) != $ffff_ffff_2468_acf0
            return 29

        ; compound assignments on a uquad
        uquad u = u32
        u += uone
        u -= ($1_0000_0001 as uquad)
        if u != umax
            return 30
        u *= u32
        if u != $ffff_ffff_0000_0001
            return 31
        u /= ($1_0000 as uquad)
        u %= 1000
        if u != 120
            return 32
        u = magic
        u &= hi
        u |= nibbles
        u ^= $ffff_ffff_ffff_ffff
        if u != $e0c0_a080_f0f0_f0f0
            return 33
        u = upat
        u <<= 4
        u >>= n33
        if u != ($11a2_b3c4 as uquad)
            return 34
        q = nbig
        q /= qzero
        u = upat
        u /= uzero
        if q != qmax or u != umax
            return 35
        q = qmin
        q %= qzero
        u = upat
        u %= uzero
        if q != 0 or u != 0
            return 36
        q = qmin
        q /= qminus1
        if q != qmin
            return 37

        ; ++ and -- carry across bit 31 of a quad and bit 32 of a uquad
        q = q31
        q++
        u = u32
        u++
        if q != $0_8000_0000 or u != ($1_0000_0000 as uquad)
            return 38
        q--
        u--
        u--
        if q != $7fff_ffff or u != ($0_ffff_fffe as uquad)
            return 39
        q = qmax
        q++
        u = uzero
        u--
        if q != qmin or u != umax
            return 40
        q--
        u++
        if q != qmax or u != 0
            return 41

        ; shifts by every count in a variable
        uquad up = 1
        uquad down
        ubyte n
        for n in 1 to 63 {
            up += up
            down = utop / up
            if uone << n != up or qminus1 << n != -(up as quad) or utop >> n != down or qmin >> n != -(down as quad)
                return 42
        }
        if up != utop
            return 43

        ; shifts by constant counts
        if upat << 1 != ($2468_acf1_3579_bde2 as uquad) or upat << 31 != ($4d5e_6f78_8000_0000 as uquad) or upat << 32 != $9abc_def1_0000_0000
            return 44
        if upat << 33 != ($3579_bde2_0000_0000 as uquad) or upat << 63 != utop
            return 45
        if upat >> 1 != ($491a_2b3c_4d5e_6f78 as uquad) or upat >> 31 != ($1_2468_acf1 as uquad) or upat >> 32 != ($0_9234_5678 as uquad)
            return 46
        if upat >> 33 != ($491a_2b3c as uquad) or upat >> 63 != 1
            return 47
        if ((qpat >> 1) as uquad) != $c91a_2b3c_4d5e_6f78 or ((qpat >> 31) as uquad) != $ffff_ffff_2468_acf1
            return 48
        if ((qpat >> 32) as uquad) != $ffff_ffff_9234_5678 or ((qpat >> 33) as uquad) != $ffff_ffff_c91a_2b3c or qpat >> 63 != -1
            return 49
        if q32 << 1 != 8589934590 or q32 << 31 != 9223372034707292160 or q32 << 32 != -4294967296 or q32 << 33 != -8589934592 or q32 << 63 != qmin
            return 50
        ; shifted left and back right: logical clears, arithmetic sign extends the upper bits
        if (upat << 32) >> 32 != ($0_9abc_def1 as uquad) or (((qpat << 32) >> 32) as uquad) != $ffff_ffff_9abc_def1 or (upat >> 32) << 32 != upat & hi
            return 51
        if (upat << n36) >> n36 != ($0abc_def1 as uquad) or (((qpat << n36) >> n36) as uquad) != $ffff_ffff_fabc_def1
            return 52

        ; comparisons of values that differ only above bit 31
        if qa == qc or qa >= qc or qc <= qa or qb >= qa or qa <= qb or qb == qa
            return 53
        if not (qa != qc and qa < qc and qc > qa and qb < qa and qa > qb and qb <= qa)
            return 54
        if (qb as uquad) <= (qa as uquad) or (qa as uquad) >= (qc as uquad) or (qc as uquad) > (qb as uquad)
            return 55
        if qa >= $2_8000_0001 or qb >= $0_8000_0001 or qc <= $1_8000_0001 or (qb as uquad) <= ($2_8000_0001 as uquad)
            return 56
        if umax <= u32 or u32 >= ($1_ffff_ffff as uquad) or umax == u32
            return 57
        return 0
    }
}""",
    "64-bit literals and constant folding" to $$"""
main {
    quad @shared q1 = 1
    uquad @shared u1 = 1
    uquad @shared uzero = 0
    quad @shared q1e6 = 1_000_000
    quad @shared q1e7 = 10_000_000
    quad @shared q1e10 = 10_000_000_000
    quad @shared q922337 = 922_337
    quad @shared q7 = 7
    quad @shared qmax = 9223372036854775807
    quad @shared qmin = -9223372036854775808
    quad @shared qabove = 9007199254740993
    quad @shared qbig = 100_000_000_000_003
    quad @shared qodd = -1_000_000_000_000_000_001
    uquad @shared uall = $ffff_ffff_ffff_ffff
    uquad @shared utop = $8000_0000_0000_0000
    uquad @shared ubase = 1234567890123456789
    uquad @shared ufff6 = $ffff_ffff_ffff_fff6
    uquad @shared u3 = 3
    uquad @shared u7 = 7
    uquad @shared ulow32 = $0000_0000_ffff_ffff
    uquad @shared upattern = $1234_5678_9abc_def0
    uquad @shared uhigh = $1234_5678_0000_0000
    ubyte @shared n8 = 8
    ubyte @shared n32 = 32
    ubyte @shared n40 = 40
    ubyte @shared n53 = 53
    ubyte @shared n62 = 62
    ubyte @shared n63 = 63
    ubyte @shared idx = 2

    const quad TEN_T = 10_000_000_000_000
    const quad PRODUCT = TEN_T * 922_337
    const quad ABOVE53 = 9007199254740993 + 2
    const quad BIT62 = (1 as quad) << 62
    const uquad TOPBIT = $8000_0000_0000_0000 >> 63
    const quad SIGNBITS = -9223372036854775808 >> 63
    const uquad HIGH32 = ~$0000_0000_ffff_ffff
    const quad MINQ = -(9223372036854775807) - 1
    const quad QDIV = -100_000_000_000_003 / 7
    const quad QREM = -100_000_000_000_003 % 7
    const quad QDIVN = 100_000_000_000_003 / -7
    const quad QREMN = 100_000_000_000_003 % -7
    const uquad UDIV = $ffff_ffff_ffff_fff6 / 3
    const uquad UREM = $ffff_ffff_ffff_fff6 % 7
    const quad WRAP = 9223372036854775807 + 1
    const uquad UWRAP = $ffff_ffff_ffff_ffff + 1
    const quad MULWRAP = 10_000_000_000 * 10_000_000_000
    const uquad ANDED = $1234_5678_9abc_def0 & $ffff_0000_ffff_0000
    const uquad ORED = $1234_5678_0000_0000 | $0_9abc_def0
    const uquad XORED = $ffff_ffff_ffff_ffff ^ $0123_4567_89ab_cdef
    const quad SAR8 = -1_000_000_000_000_000_001 >> 8

    quad[] @shared qtab = [PRODUCT, -TEN_T * 3, WRAP, (1 as quad) << 40, QDIV]
    uquad[] @shared utab = [$ffff_ffff_ffff_ffff / 3, HIGH32, TOPBIT, 12345678901234567891 % 1000]

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ; literals beyond 32 bits become quads, or uquads above the signed range, keeping all 64 bits
        quad q = 10_000_000_000_000
        if q != q1e6 * q1e7 or q >> n32 != 2328 or (q as long) != 1316134912
            return 1
        if q != $918_4e72_a000 or qmax != $7fff_ffff_ffff_ffff or 18446744073709551615 != $ffff_ffff_ffff_ffff
            return 2
        q = 9223372036854775807
        if q != ((q1 << n62) - 1) * 2 + 1 or q >> n32 != $7fff_ffff
            return 3
        uquad u = $8000_0000_0000_0000
        if u != u1 << n63 or u / 2 != ($4000_0000_0000_0000 as uquad) or u <= u1
            return 4
        u = $ffff_ffff_ffff_ffff
        if u != uzero - u1 or u != 18446744073709551615 or u % 10 != 5
            return 5
        q = -9223372036854775808
        if q != q1 << n63 or q >= q1 or -9223372036854775808 != -$8000_0000_0000_0000
            return 6
        q = $1_0000_0000
        if q != q1 << n32 or q != 4294967296 or $1_0000_0000 - 1 != $0_ffff_ffff
            return 7
        q = %1_0000000000_0000000000_0000000000_0000000000
        if q != q1 << n40 or q != $100_0000_0000 or q != 1099511627776
            return 8
        ; above 2^53 a Double can't hold every value anymore
        q = 9007199254740993
        if q != (q1 << n53) + 1 or q % 10 != 3 or 9007199254740993 == 9007199254740992
            return 9
        u = 12345678901234567891
        if u != ubase * 10 + 1 or u % 1000 != 891 or u != $ab54_a98c_eb1f_0ad3 or 18446744073709551615 % 10 != 5
            return 10

        ; each folded constant equals its hand-derived value and the same expression computed at runtime
        if PRODUCT != 9223370000000000000 or 10_000_000_000_000 * 922_337 != PRODUCT or q1e6 * q1e7 * q922337 != PRODUCT
            return 11
        if ABOVE53 != 9007199254740995 or qabove + 2 != ABOVE53
            return 12
        if BIT62 != 4611686018427387904 or q1 << n62 != BIT62
            return 13
        if TOPBIT != 1 or utop >> n63 != TOPBIT or SIGNBITS != -1 or qmin >> n63 != SIGNBITS
            return 14
        if HIGH32 != $ffff_ffff_0000_0000 or ~ulow32 != HIGH32
            return 15
        if MINQ != -9223372036854775808 or -qmax - 1 != MINQ
            return 16
        if QDIV != -14285714285714 or QREM != -5 or -qbig / q7 != QDIV or -qbig % q7 != QREM
            return 17
        if QDIVN != -14285714285714 or QREMN != 5 or qbig / -q7 != QDIVN or qbig % -q7 != QREMN
            return 18
        if UDIV != $5555_5555_5555_5552 or UREM != 6 or ufff6 / u3 != UDIV or ufff6 % u7 != UREM
            return 19
        if WRAP != -9223372036854775808 or UWRAP != 0 or qmax + 1 != WRAP or uall + 1 != UWRAP
            return 20
        if MULWRAP != 7766279631452241920 or q1e10 * q1e10 != MULWRAP
            return 21
        if ANDED != $1234_0000_9abc_0000 or upattern & $ffff_0000_ffff_0000 != ANDED
            return 22
        if ORED != $1234_5678_9abc_def0 or uhigh | ($0_9abc_def0 as uquad) != ORED
            return 23
        if XORED != $fedc_ba98_7654_3210 or uall ^ ($0123_4567_89ab_cdef as uquad) != XORED
            return 24
        if SAR8 != -3_906_250_000_000_001 or qodd >> n8 != SAR8
            return 25

        ; constants in array initializers, and constants compared with variables
        if qtab[0] != 9223370000000000000 or qtab[idx - 1] != -30_000_000_000_000 or qtab[idx] != WRAP
            return 26
        if qtab[idx + 1] != 1099511627776 or qtab[idx + 2] != -14285714285714
            return 27
        if utab[0] != ($5555_5555_5555_5555 as uquad) or utab[idx - 1] != HIGH32 or utab[idx] != 1 or utab[idx + 1] != 891
            return 28
        if PRODUCT <= qabove or not (qmin < SIGNBITS) or MINQ != qmin or HIGH32 >= uall or not (TOPBIT < utop)
            return 29
        return 0
    }
}""",
    "quad and uquad conversions" to $$"""
%option enable_floats

main {
    ubyte @shared ubmin = 0
    ubyte @shared ubmid = 128
    ubyte @shared ubmax = 255
    byte @shared bmin = -128
    byte @shared bmid = -1
    byte @shared bmax = 127
    uword @shared uwmin = 0
    uword @shared uwmid = 32768
    uword @shared uwmax = 65535
    word @shared wmin = -32768
    word @shared wmid = -2
    word @shared wmax = 32767
    long @shared lmin = -2147483648
    long @shared lmid = -1
    long @shared lmax = 2147483647
    quad @shared qzero = 0
    quad @shared qone = 1
    quad @shared qminus1 = -1
    quad @shared qminus2 = -2
    quad @shared qmin = -9223372036854775808
    quad @shared qmax = 9223372036854775807
    quad @shared mixed = $1234_5678_9abc_def0
    quad @shared qneg = -4886718345
    quad @shared two31 = $0_8000_0000
    quad @shared two32 = $1_0000_0000
    quad @shared two53 = 9007199254740992
    quad @shared two53plus1 = 9007199254740993
    quad @shared two53plus3 = 9007199254740995
    quad @shared negtwo53plus3 = -9007199254740995
    uquad @shared uzero = 0
    uquad @shared umax = $ffff_ffff_ffff_ffff
    uquad @shared utop = $8000_0000_0000_0000
    uquad @shared uhigh = $ffff_ffff_0000_0000
    uquad @shared upattern = $fedc_ba98_7654_3210
    uquad @shared two63plus1024 = $8000_0000_0000_0400
    uquad @shared two63plus1025 = $8000_0000_0000_0401
    uquad @shared two63plus2048 = $8000_0000_0000_0800
    float @shared fnegfrac = -123456789012.75
    float @shared fposfrac = 98765.99
    float @shared fminus1 = -1.0
    float @shared f1e18 = 1.0e18
    float @shared ftwo63 = 9223372036854775808.0
    float @shared f1e19 = 1.0e19
    float @shared fbelow2pow64 = 18446744073709549568.0
    float @shared f1e20 = 1.0e20
    bool @shared btrue = true

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ; implicit widening by assignment: unsigned sources zero extend, signed sources sign extend
        quad qa = ubmin
        quad qb = ubmid
        quad qc = ubmax
        quad qd = uwmin
        quad qe = uwmid
        quad qf = uwmax
        if qa != 0 or qb != 128 or qc != 255 or qd != 0 or qe != 32768 or qf != 65535
            return 1
        qa = bmin
        qb = bmid
        qc = bmax
        qd = wmin
        qe = wmid
        qf = wmax
        if qa != -128 or qb != -1 or qc != 127 or qd != -32768 or qe != -2 or qf != 32767
            return 2
        qa = lmin
        qb = lmid
        qc = lmax
        if qa != -2147483648 or qb != -1 or qc != 2147483647
            return 3
        uquad ua = ubmid
        uquad ub = ubmax
        uquad uc = uwmid
        uquad ud = uwmax
        if ua != 128 or ub != 255 or uc != 32768 or ud != 65535
            return 4

        ; implicit widening of the other operand of a binary operation
        if qzero + ubmax != 255 or ubmid - qone != 127 or qminus1 * uwmax != -65535 or uwmid + qzero != 32768
            return 5
        if qzero + bmin != -128 or bmid * qone != -1 or qzero - bmax != -127 or wmin + qzero != -32768 or qone + wmid != -1 or wmax - qone != 32766
            return 6
        if lmin + qzero != -2147483648 or qminus1 * lmid != 1 or lmax + qone != $0_8000_0000
            return 7
        if (utop | ubmax) != $8000_0000_0000_00ff or (utop | uwmid) != $8000_0000_0000_8000 or (umax & uwmax) != 65535 or uzero + ubmid != 128
            return 8
        if qminus1 != bmid or qminus1 != lmid or qminus1 == ubmax or qminus1 == uwmax or umax == uwmax or bmin >= qzero or wmin > qminus1
            return 9
        quad acc = qzero
        acc += bmin
        acc -= ubmax
        acc += wmin
        acc -= uwmax
        acc += lmax
        uquad uacc = uzero
        uacc += ubmax
        uacc -= uwmax
        if acc != 2147384961 or uacc != $ffff_ffff_ffff_0100
            return 10

        ; implicit widening of arguments and return values
        if not argq(ubmax, 255) or not argq(bmin, -128) or not argq(uwmid, 32768) or not argq(wmid, -2) or not argq(lmin, -2147483648) or not argq(lmax, 2147483647)
            return 11
        if not argu(ubmid, 128) or not argu(uwmax, 65535) or not argu(ubmin, 0)
            return 12
        if retq(1) != 255 or retq(2) != -1 or retq(3) != 65535 or retq(4) != -32768 or retq(5) != -1 or retq(6) != 2147483647
            return 13
        if retu(true) != 128 or retu(false) != 32768
            return 14

        ; explicit widening casts: signed sources sign extend, also to uquad
        if (bmin as uquad) != $ffff_ffff_ffff_ff80 or (bmid as uquad) != $ffff_ffff_ffff_ffff or (bmax as uquad) != 127
            return 15
        if (wmin as uquad) != $ffff_ffff_ffff_8000 or (wmid as uquad) != $ffff_ffff_ffff_fffe or (wmax as uquad) != 32767
            return 16
        if (lmin as uquad) != $ffff_ffff_8000_0000 or (lmid as uquad) != $ffff_ffff_ffff_ffff or (lmax as uquad) != ($7fff_ffff as uquad)
            return 17
        if (ubmax as quad) != 255 or (uwmax as quad) != 65535 or (ubmid as uquad) != 128 or (uwmid as uquad) != 32768
            return 18
        if (bmin as quad) != -128 or (wmid as quad) != -2 or (lmin as quad) != -2147483648
            return 19

        ; truncation keeps the low bits: $9abc_def0, $def0 and $f0 as signed numbers
        if (mixed as long) != -1698898192 or (mixed as word) != -8464 or (mixed as uword) != $def0 or (mixed as byte) != -16 or (mixed as ubyte) != $f0
            return 20
        ; qneg is $ffff_fffe_dcba_9877
        if (qneg as long) != -591751049 or (qneg as word) != -26505 or (qneg as uword) != $9877 or (qneg as byte) != $77 or (qneg as ubyte) != $77
            return 21
        if (qminus2 as long) != -2 or (qminus2 as word) != -2 or (qminus2 as uword) != 65534 or (qminus2 as byte) != -2 or (qminus2 as ubyte) != 254
            return 22
        if (upattern as long) != $7654_3210 or (upattern as word) != $3210 or (upattern as uword) != $3210 or (upattern as byte) != $10 or (upattern as ubyte) != $10
            return 23
        if (umax as long) != -1 or (umax as word) != -1 or (umax as uword) != 65535 or (umax as byte) != -1 or (umax as ubyte) != 255
            return 24
        if (two31 as long) != -2147483648 or (two31 as word) != 0 or (two32 as long) != 0 or (two32 as ubyte) != 0
            return 25
        if ((mixed as word) as uquad) != $ffff_ffff_ffff_def0 or ((mixed as uword) as quad) != $def0 or ((qneg as long) as uquad) != $ffff_ffff_dcba_9877
            return 26

        ; quad <-> uquad keeps all 64 bits
        if (qminus1 as uquad) != $ffff_ffff_ffff_ffff or (utop as quad) != -9223372036854775808 or (qmin as uquad) != utop or (umax as quad) != -1
            return 27
        if (umax as quad) >= qzero or (utop as quad) >= qzero or (qminus1 as uquad) < (qmax as uquad) or ((mixed as uquad) as quad) != mixed
            return 28

        ; integer to float rounds to nearest, ties to even, beyond 2^53
        if (two53 as float) != 9007199254740992.0 or (two53plus1 as float) != 9007199254740992.0 or (two53plus3 as float) != 9007199254740996.0 or (negtwo53plus3 as float) != -9007199254740996.0
            return 29
        if (qmin as float) != -9223372036854775808.0 or (qmax as float) != 9223372036854775808.0 or (qneg as float) != -4886718345.0
            return 30
        if (two63plus2048 as float) != 9223372036854777856.0 or (two63plus1024 as float) != 9223372036854775808.0 or (two63plus1025 as float) != 9223372036854777856.0 or (umax as float) != 18446744073709551616.0
            return 31
        float f = two53plus3
        if f != 9007199254740996.0 or fminus1 * umax != -18446744073709551616.0 or fminus1 + two53plus1 != 9007199254740991.0
            return 32

        ; float to integer truncates toward zero
        if (fnegfrac as quad) != -123456789012 or (fposfrac as quad) != 98765 or (fposfrac as uquad) != (98765 as uquad)
            return 33
        if (f1e18 as quad) != 1000000000000000000 or ((-f1e18) as quad) != -1000000000000000000
            return 34
        if (ftwo63 as uquad) != utop or (f1e19 as uquad) != 10000000000000000000 or (fbelow2pow64 as uquad) != $ffff_ffff_ffff_f800
            return 35
        ; out of range: quad saturates, a negative float wraps to uquad like a signed conversion, NaN gives 0
        float fnan = sqrt(fminus1)
        if (f1e19 as quad) != 9223372036854775807 or ((-f1e19) as quad) != -9223372036854775808 or (fnan as quad) != 0
            return 36
        if (fminus1 as uquad) != umax or (f1e20 as uquad) != umax or (fnan as uquad) != 0
            return 37

        ; bool conversions test all 64 bits
        if (qzero as bool) or not (qone as bool) or not (two32 as bool) or not (qmin as bool) or not (utop as bool) or not (uhigh as bool) or (uzero as bool)
            return 38
        if (btrue as quad) != 1 or (btrue as uquad) != 1 or ((not btrue) as quad) != 0
            return 39
        return 0
    }

    sub argq(quad value, quad expected) -> bool {
        return value == expected
    }

    sub argu(uquad value, uquad expected) -> bool {
        return value == expected
    }

    sub retq(ubyte which) -> quad {
        when which {
            1 -> return ubmax
            2 -> return bmid
            3 -> return uwmax
            4 -> return wmin
            5 -> return lmid
            else -> return lmax
        }
    }

    sub retu(bool small) -> uquad {
        if small
            return ubmid
        return uwmid
    }
}""",
    "quad and uquad arrays, structs and memory" to $$"""
main {
    struct Item {
        ubyte tag
        quad q
        uquad u
        quad[2] hist
    }

    quad[] @shared qvals = [-9223372036854775808, -1, 9007199254740993, 5000000000, -9007199254740995, 9223372036854775807]
    uquad[] @shared uvals = [$ffff_ffff_ffff_ffff, $1_0000_0005, $8000_0000_0000_0001, 7]
    quad[5] @shared qzero
    uquad[3] @shared uzero
    quad[10] @shared qops = [$0_ffff_ffff, -5000000000, 5000000000, -9000000000000000001, -10000000007,
                             $1234_5678_9abc_def0, 1, -1, 3, -9223372036854775808]
    uquad[10] @shared uops = [$1_0000_0000, $ffff_ffff_ffff_ffff, $ffff_ffff_ffff_fffe, $0_ffff_ffff, $0123_4567_89ab_cdef,
                              $ffff_ffff_ffff_ffff, $0123_4567_89ab_cdef, $8000_0000_0000_0000, $8000_0000_0000_0000, $0_ffff_ffff]
    ubyte @shared i0 = 0
    ubyte @shared i1 = 1
    ubyte @shared i3 = 3
    ubyte @shared i5 = 5
    ubyte @shared i7 = 7
    ubyte @shared i9 = 9
    quad @shared q5e9 = 5000000000
    quad @shared low32 = $2a05_f200
    quad @shared qnull = 0
    uquad @shared ulow = 5
    ubyte[24] @shared membuf
    ubyte[40] @shared itembuf
    ^^Item @shared stat = ^^Item : [7, -5000000000, $fedc_ba98_7654_3210, [-1, 9007199254740993]]
    ^^Item @shared blank = ^^Item : []
    Item[2] @shared items = [[1, -5000000000, $ffff_ffff_ffff_ffff, [-1, 1]],
                             [2, 9223372036854775807, 3, [-3, 9007199254740993]]]

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ; the subtractions would expose values above 2^53 that went through a double
        if qvals[0] != -9223372036854775808 or qvals[5] != 9223372036854775807 or qvals[2] - 9007199254740000 != 993 or qvals[4] + 9007199254740000 != -995
            return 1
        if uvals[0] != $ffff_ffff_ffff_ffff or uvals[2] - $8000_0000_0000_0000 != 1 or uvals[3] != 7
            return 2
        if qvals[i0] != -9223372036854775808 or qvals[i5] != 9223372036854775807 or qvals[i1] != -1 or qvals[i3] != 5000000000
            return 3
        if uvals[i0] != $ffff_ffff_ffff_ffff or uvals[i1] != ($1_0000_0005 as uquad) or uvals[i3] != 7
            return 4
        if qzero[0] != 0 or qzero[4] != 0 or qzero[i3] != 0 or len(qzero) != 5 or sizeof(qzero) != 40
            return 5
        if uzero[0] != 0 or uzero[i1] != 0 or uzero[2] != 0 or len(uvals) != 4 or sizeof(qvals) != 48
            return 6

        qzero[0] = -9223372036854775808
        qzero[i3] = q5e9
        uzero[2] = $ffff_ffff_ffff_fffe
        uzero[i0] = ulow
        if qzero[0] != -9223372036854775808 or qzero[3] != 5000000000 or qzero[1] != 0 or qzero[2] != 0 or qzero[4] != 0
            return 7
        if uzero[2] != $ffff_ffff_ffff_fffe or uzero[0] != 5 or uzero[1] != 0
            return 8
        qzero[1] = qvals[2]
        qzero[i3+1] = qvals[i5]
        qzero[2] = qzero[i3]
        uzero[i1] = uvals[2]
        if qzero[1] != 9007199254740993 or qzero[4] != 9223372036854775807 or qzero[2] != 5000000000 or uzero[1] != $8000_0000_0000_0001
            return 9
        swap(qzero[0], qzero[i3])
        swap(uzero[i0], uzero[2])
        if qzero[0] != 5000000000 or qzero[3] != -9223372036854775808 or uzero[0] != $ffff_ffff_ffff_fffe or uzero[2] != 5
            return 10

        qops[0] += 1
        qops[i1] -= q5e9
        qops[2] *= q5e9
        if qops[0] != $1_0000_0000 or qops[1] != -10000000000 or qops[2] != 6553255926290448384
            return 11
        qops[i3] /= 1000
        qops[4] %= 10
        qops[i5] &= $00ff_00ff_00ff_00ff
        if qops[3] != -9000000000000000 or qops[4] != -7 or qops[5] != $0034_0078_00bc_00f0
            return 12
        qops[6] |= -9223372036854775808
        qops[i7] ^= $0_ffff_ffff
        qops[8] <<= 62
        qops[i9] >>= 60
        if qops[6] != -9223372036854775807 or qops[7] != -4294967296 or qops[8] != -4611686018427387904 or qops[9] != -8
            return 13
        uops[0] -= 1
        uops[i1] += 1
        uops[2] /= 2
        uops[i3] *= ($0_ffff_ffff as uquad)
        if uops[0] != ($0_ffff_ffff as uquad) or uops[1] != 0 or uops[2] != ($7fff_ffff_ffff_ffff as uquad) or uops[3] != $ffff_fffe_0000_0001
            return 14
        uops[4] &= $ff00_0000_0000_00ff
        uops[i5] %= 10
        uops[6] ^= $ffff_ffff_ffff_ffff
        if uops[4] != ($0100_0000_0000_00ef as uquad) or uops[5] != 5 or uops[6] != $fedc_ba98_7654_3210
            return 15
        uops[i7] |= 1
        uops[8] >>= 63
        uops[i9] <<= 32
        if uops[7] != $8000_0000_0000_0001 or uops[8] != 1 or uops[9] != $ffff_ffff_0000_0000
            return 16

        ubyte i
        uquad usum = 0
        ubyte ubest = 0
        for i in 0 to len(uvals)-1 {
            usum += uvals[i]
            if uvals[i] > uvals[ubest]
                ubest = i
        }
        if usum != $8000_0001_0000_000c or ubest != 0
            return 17
        ubyte qbest = 0
        ubyte found = 255
        for i in 0 to len(qvals)-1 {
            if qvals[i] > qvals[qbest]
                qbest = i
            if qvals[i] == q5e9
                found = i
        }
        if qbest != 5 or found != 3
            return 18
        if q5e9 not in qvals or uvals[i1] not in uvals or (q5e9 + 1) in qvals or (uvals[i3] + 1) in uvals
            return 19
        ; each of these equals an element in its low 32 bits only
        if low32 in qvals or qnull in qvals or ulow in uvals
            return 20
        if q5e9 not in [-1, 5000000000, 9223372036854775807] or low32 in [5000000000, -9223372036854775808]
            return 21
        if uvals[2] not in [7, $8000_0000_0000_0001] or ulow in [($1_0000_0005 as uquad), 7]
            return 22

        if sumq(qvals, len(qvals)) != 4999999996 or sumq(&&qvals[1], 3) != 9007204254740992
            return 23
        reverseu(uvals, len(uvals))
        if uvals[0] != 7 or uvals[i1] != $8000_0000_0000_0001 or uvals[2] != ($1_0000_0005 as uquad) or uvals[i3] != $ffff_ffff_ffff_ffff
            return 24
        fillq(qzero, len(qzero), -5000000000)
        if qzero[0] != -5000000000 or qzero[i3] != 40000000000 or qzero[4] != -80000000000 or sumq(qzero, 5) != -55000000000
            return 25

        quad[3] lq = [-9007199254740993, $7fff_ffff_0000_0000, -2]
        uquad[4] lu
        if lu[0] != 0 or lu[i3] != 0 or lq[0] + 9007199254740000 != -993 or lq[i1] != $7fff_ffff_0000_0000 or lq[2] != -2
            return 26
        for i in 0 to 2 {
            lu[i] = (lq[i] as uquad)
        }
        lu[i3] = lu[0] + lu[2]
        if lu[0] != $ffdf_ffff_ffff_ffff or lu[1] != ($7fff_ffff_0000_0000 as uquad) or lu[3] != $ffdf_ffff_ffff_fffd
            return 27

        if sizeof(Item) != 33 or offsetof(Item.u) != 9 or offsetof(Item.hist) != 17 or stat.tag != 7 or stat.q != -5000000000
            return 28
        if stat.u != $fedc_ba98_7654_3210 or stat.hist[0] != -1 or stat.hist[i1] - 9007199254740000 != 993
            return 29
        if blank.tag != 0 or blank.q != 0 or blank.u != 0 or blank.hist[i1] != 0
            return 30
        stat.q -= q5e9
        stat.u >>= 4
        stat.hist[i1] = stat.q
        stat.hist[0] *= q5e9
        if stat.q != -10000000000 or stat.u != ($0fed_cba9_8765_4321 as uquad) or stat.hist[1] != -10000000000 or stat.hist[0] != -5000000000
            return 31
        ^^Item owned = &itembuf
        owned.tag = $a5
        owned.q = -2
        owned.u = $0123_4567_89ab_cdef
        owned.hist[i1] = -9223372036854775808
        if itembuf[0] != $a5 or itembuf[1] != $fe or itembuf[8] != $ff or itembuf[9] != $ef or itembuf[16] != $01 or itembuf[25] != 0
            return 32
        if itembuf[32] != $80 or itembuf[33] != 0 or peekq(&itembuf + 25) != -9223372036854775808 or peekq(&itembuf + 1) != -2
            return 33
        owned.q *= -3
        owned.q <<= 40
        owned.u ^= $ffff_ffff_ffff_ffff
        owned.u >>= 8
        if owned.q != 6597069766656 or owned.u != ($00fe_dcba_9876_5432 as uquad) or itembuf[15] != $fe or itembuf[16] != 0
            return 34
        owned^^ = stat^^
        if owned.q != -10000000000 or owned.hist[i1] != -10000000000 or itembuf[0] != 7 or itembuf[16] != $0f
            return 35

        items[i1].q += 1
        items[0].u >>= 4
        items[i1].u = (items[0].q as uquad)
        if items[1].q != -9223372036854775808 or items[0].u != ($0fff_ffff_ffff_ffff as uquad) or items[i1].u != $ffff_fffe_d5fa_0e00
            return 36
        if peek(&items + 33) != 2 or peekq(&items + 34) != -9223372036854775808 or peekq(&items + 58) - 9007199254740000 != 993
            return 37

        ; little-endian, like the arm64 host
        pokeq(&membuf + 3, $0123_4567_89ab_cdef)
        if membuf[2] != 0 or membuf[3] != $ef or membuf[4] != $cd or membuf[10] != $01 or membuf[11] != 0
            return 38
        for i in 0 to 7 {
            membuf[16 + i] = i * $11
        }
        if peekq(&membuf + 16) != $7766_5544_3322_1100
            return 39
        membuf[23] = $f7
        if (peekq(&membuf + 16) as uquad) != $f766_5544_3322_1100 or peekq(&membuf + 16) != -619714147312856832
            return 40
        if peekq(&qvals + 16) != 9007199254740993 or peek(&uvals + 15) != $80 or peek(&uvals + 16) != 5 or peek(&uvals + 20) != 1
            return 41
        ^^uquad up = &&uvals[1]
        up++
        if up^^ != ($1_0000_0005 as uquad) or up[1] != $ffff_ffff_ffff_ffff or up - &&uvals[0] != 2 or peek(up as pointer) != 5
            return 42
        pokeq(&uzero + 8, -2)
        if uzero[1] != $ffff_ffff_ffff_fffe or uzero[0] != $ffff_ffff_ffff_fffe or uzero[2] != 5
            return 43
        return 0
    }

    sub sumq(^^quad p, ubyte n) -> quad {
        quad total = 0
        ubyte i
        for i in 0 to n-1 {
            total += p[i]
        }
        return total
    }

    sub reverseu(^^uquad p, ubyte n) {
        ubyte i = 0
        ubyte j = n - 1
        while i < j {
            uquad tmp = p[i]
            p[i] = p[j]
            p[j] = tmp
            i++
            j--
        }
    }

    sub fillq(^^quad p, ubyte n, quad value) {
        ubyte i
        for i in 0 to n-1 {
            p[i] = value
            value *= -2
        }
    }
}""",
    "command line arguments when there are none" to """
main {
    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        if sys.argc() != 1
            return 1
        if sys.argv(0) == ""
            return 2
        if sys.argv(1) != "" or sys.argv(65535) != ""
            return 3
        return 0
    }
}""",
    "pointer element updates and offsets beyond 64 KiB" to $$"""
main {
    struct Node {
        uword a
        uword b
        long c
        quad d
    }
    quad[4] @shared values = [10, 20, 30, 40]
    uquad[3] @shared uvalues = [1, $1_0000_0000, 3]
    uquad @shared expected = $1_0000_0001
    quad[10000] @shared big
    ubyte[65535] @shared slab
    long @shared longvalue = $1234_5678

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ^^quad pq = &&values
        ^^quad original = pq
        ubyte @shared i = 3
        pq[i] += 5
        pq[0] += 5
        if pq != original or values[3] != 45 or values[0] != 15
            return 1
        ^^uquad pu = &&uvalues
        pu[1] += 1
        if uvalues[1] != expected
            return 2
        ^^quad qp = &&big
        uword @shared index = 8500
        qp[index] = 77
        if big[8500] != 77 or big[308] == 77
            return 3
        ^^Node np = &big
        index = 4500
        np[index].a = $1234
        if big[9000] != $1234 or big[808] == $1234
            return 4
        ^^ubyte @shared typed
        typed = $1_2345
        if typed != $1_2345
            return 5
        long @shared allbits = -1 & longvalue
        if allbits != $1234_5678
            return 6
        ^^ubyte nothing = 0
        if nothing != 0
            return 7
        ; a pointer whose lowest 16 bits are 0
        uword low = &&slab as uword
        ^^ubyte aligned = &&slab + (~low + 1)
        if aligned == 0 or not (aligned as bool)
            return 8
        return 0
    }
}""",
    "a goto through a uquad address variable ends a subroutine that returns a value" to """
main {
    uquad @shared global

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        if viaLocal() != 42
            return 1
        if viaGlobal() != 42
            return 2
        return 0
    }

    sub answer() -> uword {
        return 42
    }

    sub viaLocal() -> uword {
        uquad @shared address = &answer
        goto address
    }

    sub viaGlobal() -> uword {
        global = &answer
        goto global
    }
}""",

    "64-bit addresses" to $$"""
main {
    ubyte[8] @shared buffer = [1, 2, 3, 4, 5, 6, 7, 8]
    ubyte[8] @shared slot
    uquad @shared nearvalue = $1234_5678

    struct Node {
        ubyte value
        ^^Node next
    }

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        uquad holder = &buffer
        ubyte @shared i = 3
        if holder[i] != 4
            return 1
        holder[i] = 40
        if buffer[3] != 40
            return 2
        ^^ubyte p = &&buffer
        if (p as uquad) != holder
            return 3
        uquad @shared offset = $1_0000_0000
        ^^ubyte far = p + offset
        if (far as uquad) - holder != offset
            return 4
        pokep(&&slot, p)
        if peekp(&&slot) != p
            return 5
        pointer @shared q
        q = $1234_5678
        if (q as uquad) != nearvalue
            return 6
        far = p + $1_0000_0000
        if (far as uquad) - holder != offset
            return 7
        far -= $1_0000_0000
        if far != p or (distant() as uquad) != offset
            return 11
        ^^Node second = ^^Node : [2, 0]
        ^^Node first = ^^Node : [1, 0]
        first.next = second
        pointer anything = first
        if (anything as ^^Node).next != second
            return 8
        (anything as ^^Node).value = 5
        if first.value != 5
            return 9
        long truncated = p as long
        if truncated != (holder as long)
            return 10
        long @shared longoffset = 3
        long @shared minus = -2
        holder = &buffer
        holder += longoffset
        if @(holder) != 40
            return 12
        holder -= minus
        if @(holder) != 6 or @(&buffer + minus + 3) != 2
            return 13
        if msb(p) != (((p as uquad) >> 56) as ubyte) or msw(p) != (((p as uquad) >> 48) as uword)
            return 14
        if msb(&buffer) != msb(p) or msw(&buffer) != msw(p) or msb(buffer) != msb(p) or msw(buffer) != msw(p)
            return 15
        return 0
    }

    sub distant() -> ^^ubyte {
        return $1_0000_0000
    }
}""",
    "lsb, msb, lsw and msw of quads and addresses, and signed offsets to addresses" to $$"""
main {
    ubyte[8] @shared buffer = [1, 2, 3, 4, 5, 6, 7, 8]

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        uquad @shared uq = $1234_5678_9abc_def0
        quad @shared q = -2
        if lsb(uq) != $f0 or msb(uq) != $12
            return 1
        if lsw(uq) != $def0 or msw(uq) != $1234
            return 2
        if lsb(q) != $fe or msb(q) != $ff or lsw(q) != $fffe or msw(q) != $ffff
            return 3
        if lsb($1234_5678_9abc_def0) != $f0 or msb($1234_5678_9abc_def0) != $12
            return 4
        if lsw($1234_5678_9abc_def0) != $def0 or msw($1234_5678_9abc_def0) != $1234
            return 5
        if msb(uq + $0100_0000_0000_0000) != $13 or msw(uq >> 8) != $0012
            return 6
        ubyte @shared b = msb(uq)
        uword @shared w = msw(uq)
        if b != $12 or w != $1234 or uq != $1234_5678_9abc_def0
            return 7
        ^^ubyte p = &&buffer
        if lsb(&buffer) != lsb(&buffer as uword) or lsw(&buffer) != (&buffer as uword)
            return 8
        if lsb(p) != lsb(p as uword) or lsw(p + 2) != (p as uword) + 2
            return 9
        if lsb(&&buffer[2] - &&buffer) != 2 or lsb(buffer) != lsb(&buffer as uword) or lsw(buffer) != (&buffer as uword)
            return 10
        long @shared longoffset = 3
        long @shared minus = -2
        word @shared wordoffset = 4
        byte @shared byteoffset = -1
        if @(&buffer + longoffset) != 4 or @(longoffset + &buffer) != 4 or @(&buffer - minus) != 3
            return 11
        if @(&buffer + longoffset + minus) != 2 or @(&buffer + wordoffset + byteoffset) != 4
            return 12
        if @(&buffer + 100000 - 99999) != 2 or @(&buffer + -1 + longoffset) != 3
            return 13
        return 0
    }
}""",
    "arithmetic on the elements of a pointer to untyped pointers" to """
main {
    ubyte[16] @shared buf
    pointer[4] @shared slots

    struct Holder {
        ubyte tag
        ^^pointer items
    }

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ^^pointer pp = &slots
        ubyte @shared i = 1
        ubyte @shared k = 2
        pp[i] = &buf
        pp[i] += 2
        if pp[i] != &buf + 2
            return 1
        pp[0] = &buf
        pp[0] += 2
        pp[0] = pp[0] + 2
        if pp[0] != &buf + 4
            return 2
        pp[i] -= 1
        pp[i]++
        pp[i] += k
        if pp[i] != &buf + 4
            return 3
        pp^^ += 1
        if pp^^ != &buf + 5
            return 4
        pp++
        if pp != &slots + sizeof(pointer) or pp^^ != &buf + 4
            return 5
        ^^Holder holder = ^^Holder : [7, &slots]
        holder.items[1] += 2
        if pp^^ != &buf + 6
            return 6
        return 0
    }
}""",
    "pushq, popq, pushp, popp, pokep and peekp, and defer with quad, long and pointer return values" to $$"""
main {
    ubyte[16] @shared buf
    ubyte[8] @shared slot
    ubyte @shared counter
    uquad @shared pattern = $1234_5678_9abc_def0
    quad @shared negative = -5000000000
    long @shared longvalue = -123456789

    struct Node {
        ubyte value
        ^^Node next
    }

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        pushq(pattern)
        pushq(negative)
        pushq(-2)
        push(7)
        if pop() != 7
            return 1
        if popq() != -2
            return 2
        if popq() != negative
            return 3
        uquad unsigned = popq() as uquad
        if unsigned != pattern
            return 4
        pushq($1_0000_0001)
        if (popq() as uquad) != $1_0000_0001
            return 5
        ^^ubyte p = &&buf[3]
        ^^Node node = ^^Node : [5, 0]
        pushp(p)
        pushp(node)
        ^^Node node2 = popp()
        ^^ubyte p2 = popp()
        if p2 != p or node2 != node or node2.value != 5
            return 6
        pokep(&&slot, p)
        if peekp(&&slot) != p
            return 7
        if bigger(1) != pattern + 1 or counter != 1
            return 8
        if smaller(2) != negative - 2 or counter != 2
            return 9
        if longer(3) != longvalue + 3 or counter != 3
            return 10
        if address(4) != &&buf[4] + 1 or counter != 4
            return 11
        return 0
    }

    sub bigger(ubyte i) -> uquad {
        defer counter++
        return pattern + i
    }

    sub smaller(ubyte i) -> quad {
        defer counter++
        return negative - i
    }

    sub longer(ubyte i) -> long {
        defer counter++
        return longvalue + i
    }

    sub address(ubyte i) -> ^^ubyte {
        defer counter++
        return &&buf[i] + 1
    }
}""",
    "64-bit addresses beyond 4 GiB through pushp, popp, pokep and peekp, and defer with computed untyped addresses" to $$"""
main {
    ubyte[16] @shared buf
    ubyte[8] @shared slot
    ubyte @shared counter

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ^^ubyte p = &&buf
        uquad @shared offset = $1_0000_0000
        ^^ubyte far = p + offset
        pushp(far)
        ^^ubyte back = popp()
        if back != far
            return 1
        pushp(far)
        uquad address = popp()
        if address != (far as uquad) or address - (p as uquad) != offset
            return 2
        pokep(&&slot, far)
        uquad peeked = peekp(&&slot)
        if peeked != address
            return 3
        ^^ubyte peekedpointer = peekp(&&slot)
        if peekedpointer != far
            return 4
        uquad @shared q = $1234_5678_9abc_def0
        pushp(q)
        uquad r = popp()
        if r != q
            return 5
        pushp(&buf + 3)
        if popp() != &buf + 3
            return 6
        ^^ubyte fromstr = untypedstr(2)
        if fromstr != &&buf[2] or counter != 1
            return 7
        ^^ubyte frompointer = untypedpointer(5)
        if frompointer != &&buf[5] or counter != 2
            return 8
        if fromarray(1) != &buf or fromarray(2) != &slot or counter != 4
            return 9
        return 0
    }

    sub untypedstr(ubyte i) -> str {
        defer counter++
        return &buf + i
    }

    sub untypedpointer(ubyte i) -> ^^ubyte {
        defer counter++
        return &buf + i
    }

    sub fromarray(ubyte i) -> uquad {
        defer counter++
        return if i==1 buf else slot
    }
}""",
    "uquad addresses with signed operands, long values, strings and typed address initializers" to $$"""
main {
    ubyte[8] @shared buffer = [1, 2, 3, 4, 5, 6, 7, 8]
    str text1 = "abc"
    str text2 = "abd"
    ubyte @shared first = 11
    ubyte @shared second = 22
    long @shared minus = -2
    long @shared positive = 123456
    word @shared wminus = -300
    byte @shared bminus = -5
    quad @shared qminus = -7
    quad @shared qbig = $1_0000_0000
    uquad[3] @shared typed = [&&first, &&second, 0]
    uquad[3] @shared mixed = [&first, &&second, 0]

    struct Holder {
        ubyte tag
        uquad addr
    }

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ; a signed operand is sign extended to a uquad, so a negative one is above every address
        uquad @shared u = &buffer
        if u == minus or minus == u or u > minus or not (minus > u)
            return 1
        if u >= wminus or bminus <= u or u >= qminus or u == minus + 1
            return 2
        if u < 70000 or 100000 >= u or u == $ff0000 or u >= -5000000000
            return 3
        uquad @shared all = $ffff_ffff_ffff_ffff
        if all != -1 or -1 != all or all != minus + 1 or all - 1 != minus or all < qminus
            return 4
        uquad @shared small = 123456
        if small != positive or positive != small or small < positive or all == bminus
            return 5
        ; quad operands keep the address arithmetic in uquads
        uquad far = u + $1_0000_0000
        if far - u != $1_0000_0000 or far - qbig != u
            return 6
        far -= qbig
        if far != u
            return 7
        far = qbig + u
        far = far - qbig
        far += $1_0000_0000
        far -= $1_0000_0000
        if far != u or @(u + 7 + qminus) != 1 or @(qminus + u + 9) != 3
            return 8
        ; a long converts to a uquad, sign extended
        uquad fromlong = minus
        if fromlong != $ffff_ffff_ffff_fffe
            return 9
        fromlong = positive
        if fromlong != 123456 or widened(minus) != $ffff_ffff_ffff_fffe or identity(minus) != $ffff_ffff_ffff_fffe
            return 10
        ; a uquad holds the address of a string, which it is compared with
        uquad text = text1
        if text != "abc" or "abc" != text or text == "abd" or text >= "abd" or text != text1 or text == text2 or text2 <= text
            return 11
        if typed[0] != &first or @(typed[1]) != 22 or typed[2] != 0 or mixed[0] != &first or mixed[1] != &second
            return 12
        ^^Holder holder = ^^Holder : [5, &&second]
        if holder.addr != &second or @(holder.addr) != 22
            return 13
        ; arrays, strings and subroutines are passed to a uquad as their address, also when the call is inlined
        if identity(buffer) != &buffer or identity(text1) != &text1 or identity(widened) != &widened
            return 14
        if identity("abc") != "abc" or identity("abc") == 0
            return 15
        return 0
    }

    sub identity(uquad a) -> uquad {
        return a
    }

    sub widened(long l) -> uquad {
        return l
    }
}""",
    "quad and uquad loop variables over quad and uquad arrays" to $$"""
main {
    uquad[3] @shared uarr = [1, $ffff_ffff_ffff_ff00, $1_0000_0000]
    quad[4] @shared qarr = [-1, 5, -5000000000, 9223372036854775807]
    uquad[1] @shared usingle = [$1234_5678_9abc_def0]
    quad[1] @shared qsingle = [-5000000000]
    quad looper

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ; every element once and in order: the elements are 8 bytes apart
        uquad e
        uquad usum = 0
        ubyte count = 0
        for e in uarr {
            usum += e
            count++
            if count == 2 and e != $ffff_ffff_ffff_ff00
                return 1
        }
        if usum != $0_ffff_ff01 or count != 3 or e != $1_0000_0000
            return 2
        quad qsum = 0
        count = 0
        for looper in qarr {
            qsum += looper
            count++
        }
        if qsum != 9223372031854775811 or count != 4 or looper != 9223372036854775807
            return 3
        uquad[4] localu = [10, 20, $10_0000_0000, 40]
        quad[2] localq = [-7, -8]
        usum = 0
        for e in localu
            usum += e
        quad q
        qsum = 0
        for q in localq
            qsum += q
        if usum != $10_0000_0046 or qsum != -15 or q != -8
            return 4
        ; a single element is assigned without a loop, with all of its 64 bits
        usum = 0
        for e in usingle
            usum += e
        qsum = 0
        for q in qsingle
            qsum += q
        if usum != $1234_5678_9abc_def0 or qsum != -5000000000
            return 5
        usum = 0
        for x in uarr
            usum ^= x
        if usum != $ffff_fffe_ffff_ff01
            return 6
        count = 0
        for e in uarr {
            if e == 1
                continue
            count++
            if e == $ffff_ffff_ffff_ff00
                break
        }
        if count != 1
            return 7
        count = 0
        for q in qarr step -1 {
            if (count == 0 and q != 9223372036854775807) or (count == 3 and q != -1)
                return 8
            count++
        }
        if count != 4
            return 9
        count = 0
        for e in uarr {
            for q in localq
                count++
        }
        if count != 6
            return 10
        return 0
    }
}""",
    "uquad and typed pointer loop variables over uquad address arrays" to $$"""
main {
    ubyte @shared a = 11
    ubyte @shared b = 22
    ubyte @shared c = 33
    uquad[3] @shared addrs = [&a, &b, &c]
    uquad[2] @shared nodes

    struct Node {
        ubyte kind
        uquad data
    }

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ; the address array has native ptr elements, which a uquad loop variable gets as integers
        uquad u
        ubyte count = 0
        for u in addrs {
            if (count == 0 and u != &a) or (count == 1 and u != &b) or (count == 2 and u != &c)
                return 1
            count++
        }
        if count != 3 or u != &c
            return 2
        ; a uquad converts to a pointer, so a typed pointer loop variable can take the addresses
        ^^ubyte p
        ubyte sum = 0
        for p in addrs
            sum += p^^
        if sum != 66 or p != &&c
            return 3
        ^^Node n1 = ^^Node : [1, 100]
        ^^Node n2 = ^^Node : [2, $1_0000_0000]
        nodes[0] = n1
        nodes[1] = n2
        ^^Node n
        ubyte kinds = 0
        uquad datas = 0
        for n in nodes {
            kinds = kinds * 10 + n.kind
            datas += n.data
        }
        if kinds != 12 or datas != $1_0000_0064 or n != n2
            return 4
        uquad[2] localaddrs = [&b, &c]
        sum = 0
        for ^^ubyte lp in localaddrs
            sum += lp^^
        if sum != 55
            return 5
        uquad[2] localnodes
        localnodes[0] = n2
        localnodes[1] = n1
        kinds = 0
        for ^^Node ln in localnodes
            kinds = kinds * 10 + ln.kind
        if kinds != 21
            return 6
        return 0
    }
}""",
    "uquad struct fields indexed like the address they hold" to $$"""
main {
    ubyte[8] @shared buf = [1, 2, 3, 4, 5, 6, 7, 8]
    ubyte[8] @shared buf2 = [10, 20, 30, 40, 50, 60, 70, 80]

    struct Node {
        ubyte kind
        uquad data
        uquad[2] items
    }

    struct Wrapper {
        bool flag
        ^^Node node
    }

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ^^Node node = ^^Node : [1, &buf, [5, $1_0000_0000]]
        ^^Wrapper w = ^^Wrapper : [false, 0]
        w.node = node
        ubyte @shared i = 2
        uword @shared wi = 3
        ; a uquad field holds an address whose bytes it indexes, like a uquad variable
        ubyte v = node.data[1]
        if v != 2 or node.data[i] != 3 or node.data[wi] != 4
            return 1
        node.data[1] = 9
        if buf[1] != 9
            return 2
        node.data[i] += 10
        node.data[i]++
        if buf[2] != 14
            return 3
        node.data[0] |= $80
        if buf[0] != $81
            return 4
        node.data[5] = node.data[6] + node.data[7]
        if buf[5] != 15
            return 5
        if w.node.data[3] != 4
            return 6
        w.node.data[4] = 77
        if buf[4] != 77
            return 7
        node.data += 1
        if node.data[0] != 9 or node.data != &buf + 1
            return 8
        node.data = &buf2
        if node.data[i] != 30 or w.node.data[i + 1] != 40
            return 9
        ; an array field still indexes its uquad elements
        if node.items[1] != $1_0000_0000 or node.items[0] != 5
            return 10
        node.items[i - 1] = 99
        if node.items[1] != 99 or node.items[0] != 5
            return 11
        return 0
    }
}""",
    "never written quad and uquad variables become constants of their own type" to $$"""
main {
    uquad gu = 70000
    quad gq = -70000

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ; never written, so these become constants, of their own type and with their exact value
        uquad u1 = 70000
        uquad u2 = 1234567
        uquad u3 = $12345
        uquad u4 = $ff0000
        uquad u5 = $1_0000_0000
        uquad u6 = 255
        quad q1 = -5
        quad q2 = 70000
        quad q3 = -70000
        if gu != 70000 or u1 != 70000 or u2 != 1234567 or u3 != 74565 or u4 != 16711680 or u5 != 4294967296 or u6 != 255
            return 1
        if gq != -70000 or q1 != -5 or q2 != 70000 or q3 != -70000
            return 2
        uquad @shared ubig = 100000
        quad @shared qbig = 100000
        if u1 * ubig != 7000000000 or gu * ubig != 7000000000 or u2 << 32 != $12_d687_0000_0000 or u4 + u5 != $1_00ff_0000
            return 3
        if q2 * qbig != 7000000000 or q3 * qbig != -7000000000 or gq * qbig != -7000000000 or q1 * qbig * qbig != -50000000000
            return 4
        if q2 * q2 != 4900000000 or q3 * q2 != -4900000000 or u1 * u1 != 4900000000
            return 5
        return 0
    }
}""",
    "uquad constants folded with signed operands as at runtime, and typed pointers compared with negative numbers" to $$"""
main {
    const uquad ALL = $ffff_ffff_ffff_ffff
    const uquad FIVE = 5
    const quad QMINUS = -7
    const long LMINUS = -2
    uquad @shared all = $ffff_ffff_ffff_ffff
    uquad @shared five = 5
    ubyte[4] @shared buf

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ; a signed operand is sign extended to 64 bits and compared unsigned, when folded as at runtime
        if ALL != -1 or -1 != ALL or not (ALL == -1) or all != -1
            return 1
        if not (FIVE < -1) or FIVE >= -1 or -1 <= FIVE or not (five < -1)
            return 2
        if not (ALL > -2) or ALL < -2 or not (all > -2)
            return 3
        if ALL != QMINUS + 6 or not (ALL == LMINUS + 1) or ALL < QMINUS or not (FIVE < QMINUS)
            return 4
        ; and added or subtracted with 64-bit wraparound, giving a uquad
        uquad x = ALL + -1
        if x != $ffff_ffff_ffff_fffe or x != all + -1
            return 5
        uquad y = ALL - -1
        if y != 0 or y != all - -1
            return 6
        uquad z = -1 + FIVE
        if z != 4 or z != -1 + five
            return 7
        uquad w = FIVE + QMINUS
        if w != $ffff_ffff_ffff_fffe or w != five + QMINUS or FIVE + LMINUS != 3 or FIVE - LMINUS != 7
            return 8
        const uquad SUM = ALL + -1
        uquad[2] folded = [ALL + -2, FIVE + LMINUS]
        if SUM != $ffff_ffff_ffff_fffe or folded[0] != $ffff_ffff_ffff_fffd or folded[1] != 3
            return 9
        ; a typed pointer compares with the 64-bit two's complement of a negative number
        ^^ubyte p = &&buf
        if p == -1 or -1 == p or p > -1 or -2 < p or not (p < -1)
            return 10
        p = all
        if p != -1 or -1 != p or p == -2 or p != -5000000000 + 4999999999
            return 11
        ^^uword wp = all - 1
        if wp != -2 or wp == -1 or wp >= -1 or not (wp > -3)
            return 12
        return 0
    }
}""",
    "a uquad address dereferenced with ^^ reads and writes the byte at that address" to """
main {
    ubyte[4] @shared buf = [1, 2, 3, 4]

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        uquad @shared p = &buf + 1
        ubyte @shared i = 1
        if p^^ != 2
            return 1
        p^^ = 20
        if buf[1] != 20
            return 2
        p^^ += 5
        p^^++
        if buf[1] != 26 or p^^ != 26
            return 3
        p++
        p^^ -= i
        if buf[2] != 2
            return 4
        ^^ubyte @shared bp = &&buf
        bp^^ = 9
        bp^^ += 1
        bp^^++
        if buf[0] != 11 or bp^^ != 11
            return 5
        return 0
    }
}""",
    "inlined subroutines compute with pointer arguments as their integer parameters, and with integer arguments as their pointer parameters" to """
main {
    uword[4] @shared wordarray = [1, 2, 3, 4]

    struct Node {
        ubyte a
        uword b
        ^^Node next
    }

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ^^Node np = ^^Node : [1, 2, 0]
        uquad @shared address = np
        if ident(np) + 1 - (np as uquad) != 1
            return 1
        if plus1(np) - (np as uquad) != 1
            return 2
        if ident(&&wordarray) + 1 - &wordarray != 1
            return 3
        if plus1(&&wordarray) - &wordarray != 1
            return 4
        if ident(wordarray) + 1 - &wordarray != 1
            return 5
        if (nextnode(address) as uquad) - address != sizeof(Node)
            return 6
        return 0
    }

    sub ident(uquad a) -> uquad {
        return a
    }

    sub plus1(uquad a) -> uquad {
        return a + 1
    }

    sub nextnode(^^Node n) -> ^^Node {
        return n + 1
    }
}""",
    "the untyped pointer is a uquad in variables, constants, parameters, return values, struct fields and arrays" to $$"""
main {
    ubyte[16] @shared buffer = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15]
    uword[2] @shared @nosplit words = [1111, 2222]
    long @shared minus = -2
    pointer @shared far = $1_0000_1234
    uquad @shared expected = $1_0000_1234
    pointer[3] @shared table = [&buffer, &&words, 0]
    pointer[2] @shared @nosplit unsplit = [&buffer, 0]
    pointer[3] @shared later

    struct Node {
        ubyte kind
        pointer data
        ^^Node next
    }

    struct Slots {
        ubyte tag
        pointer[2] slots
    }

    ^^Node @shared first = ^^Node : [1, &buffer, 0]

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        if sizeof(pointer) != 8 or sizeof(far) != 8 or far != expected or far >> 32 != 1
            return 1
        pointer big = $1234_5678_9abc_def0
        if big >> 32 != $1234_5678 or lsw(big) != $def0
            return 2
        pointer p = &buffer + 5
        uquad u = p
        if u != p or @(p) != 5 or p[2] != 7 or p^^ != 5
            return 3
        if p & $ff != lsb(p) or p >> 4 != u / 16 or (p >> 4) << 4 != p & $ffff_ffff_ffff_fff0 or p ^ p != 0 or p | 0 != p
            return 4
        if table[0] != &buffer or table[1] != &words or table[2] != 0 or peekw(table[1] + 2) != 2222
            return 5
        if unsplit[0] != &buffer or @(unsplit[0] + 3) != 3
            return 6
        unsplit[1] = &words
        if peekw(unsplit[1]) != 1111
            return 7
        later[1] = &buffer + 2
        later[2] = first
        if @(later[1]) != 2 or later[2] != first or later[0] != 0
            return 8
        if first.data != &buffer or @(first.data + 9) != 9
            return 9
        first.data = &words
        ^^uword wp = first.data
        if wp^^ != 1111 or wp[1] != 2222
            return 10
        pointer anything = first
        ^^Node back = anything
        if back != first or back.kind != 1
            return 11
        if identity(p) != p or identity(first) != first or nodeof(anything).kind != 1
            return 12
        pointer signext = minus
        if signext != $ffff_ffff_ffff_fffe or identity(minus) != $ffff_ffff_ffff_fffe or @(&buffer + 4 + minus) != 2
            return 13
        pushp(p)
        pushp(far)
        pointer popped = popp()
        if popped != expected or popp() != p
            return 14
        pointer routine = &answer
        ^^ubyte code = routine
        if call(routine) != 4242 or call(code as pointer) != 4242
            return 15
        ^^Slots holder = ^^Slots : [7, [&buffer, 0]]
        holder.slots[1] = &words
        if @(holder.slots[0] + 1) != 1 or peekw(holder.slots[1]) != 1111
            return 16
        return 0
    }

    sub identity(pointer address) -> pointer {
        return address
    }

    sub nodeof(pointer address) -> ^^Node {
        return address
    }

    sub answer() -> uword {
        return 4242
    }
}""",
    "when with goto choices to routines and with return choices, as jump tables on llvm64 only where the table holds routines" to """
main {
    ubyte @shared total

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        total = 0
        ubyte @shared sel
        for sel in 0 to 5 {
            dispatch(sel)
        }
        dispatch(9)
        if total != 115
            return 1
        if weekday(1) + weekday(3) + weekday(5) != 90 or weekday(9) != 99
            return 2
        return 0
    }

    sub dispatch(ubyte s) {
        when s {
            0 -> goto r0
            1 -> goto r1
            2 -> goto r2
            3 -> goto r3
            4 -> goto r4
            5 -> goto r5
            else -> total += 100
        }
    }

    sub r0() { total += 0 }
    sub r1() { total += 1 }
    sub r2() { total += 2 }
    sub r3() { total += 3 }
    sub r4() { total += 4 }
    sub r5() { total += 5 }

    sub weekday(ubyte day) -> ubyte {
        when day {
            1 -> return 10
            2, 3 -> return day * 10
            4 to 5 -> return 50
            else -> return 99
        }
    }
}""",
    "extsubs call C library functions with long, quad, uquad, float, bool, string and pointer types" to """
%option enable_floats

main {
    extsub llabs(quad value) -> quad
    extsub labs(quad value) -> quad
    extsub fabs(float x) -> float
    extsub atoi(str s) -> long
    extsub atol(str s) -> quad
    extsub strlen(str s) -> uquad
    extsub toupper(ubyte c) -> ubyte
    extsub isdigit(ubyte c) -> bool
    extsub cbrt(float x) -> float
    extsub srand(uword seed)

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        quad @shared q = -5000000000
        if llabs(q) != 5000000000
            return 1
        if labs(-7) != 7
            return 2
        float @shared f = -2.5
        if fabs(f) != 2.5
            return 3
        if atoi("1234") + 1 != 1235
            return 4
        if atol("-5000000000") != -5000000000
            return 5
        if strlen("hello") != 5
            return 6
        str @shared text = "abcdef"
        if strlen(text) != 6
            return 7
        if toupper('a') != 'A'
            return 8
        ubyte @shared digit = '7'
        if not isdigit(digit)
            return 9
        if isdigit('x')
            return 10
        if cbrt(27.0) != 3.0
            return 11
        ^^ubyte @shared chars = text
        if strlen(chars) != 6
            return 12
        srand(5)
        return 0
    }
}"""
)


/**
 * A complete program that uses the llvm64 library, with the exit code and the console output that it should give
 * for the [input] and the command line [args]. It runs in an empty directory named run, which it has to leave empty.
 */
private class OutputProgram(val source: String, val output: String, val exitCode: Int = 0, val input: String = "", val args: List<String> = emptyList())

/** Programs with extsubs that call the C library: they can't import textio, which uses the runtime's own declarations of putchar and getchar. */
private val externalPrograms = mapOf(
    "putchar and puts" to OutputProgram("""
%zeropage basicsafe
%option no_sysinit

main {
    extsub putchar(ubyte char) -> ubyte
    extsub puts(str msg) -> uword

    sub start() {
        putchar('A')
        putchar('B')
        putchar('\n')
        void puts("Hello world")
        ubyte @shared echoed = putchar('Z')
        putchar('\n')
        sys.exit(echoed - 'Z' + 3)
    }
}""", "AB\nHello world\nZ\n", exitCode = 3),

    "getchar in an expression, until the end of the input" to OutputProgram("""
%zeropage basicsafe
%option no_sysinit

main {
    extsub putchar(ubyte char) -> ubyte
    extsub getchar() -> ubyte

    sub start() {
        ubyte @shared next = getchar() + 1
        putchar(next)
        next = getchar()
        putchar(next)
        putchar('\n')
        next = getchar()
        sys.exit(next)
    }
}""", "bb\n", exitCode = 255, input = "ab"),

    "the same C function in several scopes with the same types" to OutputProgram("""
%zeropage basicsafe
%option no_sysinit

main {
    extsub putchar(ubyte char) -> ubyte

    sub start() {
        extsub puts(str msg) -> uword
        putchar('M')
        other.hello()
        putchar('\n')
        void puts("done")
        sys.exit(other.counter)
    }
}

other {
    extsub putchar(ubyte c) -> ubyte
    ubyte @shared counter = 5

    sub hello() {
        void putchar('O')
        counter++
    }
}""", "MO\ndone\n", exitCode = 6)
)

// call() takes the result that a subroutine's indirect entry leaves; jump() returns what the routine it goes to returns
private val indirectCalls32 = OutputProgram("""
%import textio
%zeropage basicsafe

main {
    uword @shared counter = 0

    sub start() {
        long @shared target = &bump
        long @shared target2 = &answer
        void call(target)
        void call(target)
        txt.print_uw(counter)
        txt.nl()
        txt.print_uw(call(target2))
        txt.nl()
        txt.print_uw(jump(target2))
        txt.nl()
        sys.exit(lsb(counter))
    }

    sub bump() {
        counter++
    }

    sub answer() -> uword {
        counter++
        return 42
    }

    sub jump(long address) -> uword {
        goto address
        return 0
    }
}""", "2\n42\n42\n", exitCode = 4)

private val nativeHello = OutputProgram("""
%import textio
%zeropage basicsafe

main {
    sub start() {
        txt.print("Hello from a native executable\n")
        sys.exit(42)
    }
}""", "Hello from a native executable\n", exitCode = 42)

private val outputPrograms = mapOf(
    "text output" to OutputProgram("""
%import textio
%import syslib
%zeropage basicsafe

main {
    sub start() {
        txt.print("Hello from Prog8 LLVM!\n")
        txt.chrout('X')
        txt.spc()
        txt.chrout('Y')
        txt.nl()
        txt.print("")
        txt.print("multi")
        txt.print("ple\n")
        txt.rvs_on()
        txt.print("reverse")
        txt.rvs_off()
        txt.color(2)
        txt.home()
        txt.plot(4, 2)
        txt.bell()
        txt.nl()
    }
}""", "Hello from Prog8 LLVM!\nX Y\nmultiple\n\u001b[7mreverse\u001b[0m\u001b[32m\u001b[H\u001b[2;4H\u0007\n"),

    "number formatting" to OutputProgram($$"""
%import textio
%zeropage basicsafe

main {
    long @shared minimum = -2147483648

    sub start() {
        txt.print_ub(255)
        txt.spc()
        txt.print_uw(65535)
        txt.spc()
        txt.print_i32(-123456)
        txt.spc()
        txt.print_ubhex($fa, true)
        txt.spc()
        txt.print_ubbin(%10101010, true)
        txt.nl()
        txt.print_ub0(7)
        txt.spc()
        txt.print_uw0(42)
        txt.spc()
        txt.print_b(-128)
        txt.spc()
        txt.print_w(-32768)
        txt.spc()
        txt.print_l(minimum)
        txt.spc()
        txt.print_u32(-1)
        txt.nl()
        txt.print_uwhex($beef, false)
        txt.spc()
        txt.print_uwbin($8001, false)
        txt.spc()
        txt.print_ulhex($12345678, true)
        txt.spc()
        txt.print_bool(true)
        txt.spc()
        txt.print_bool(false)
        txt.spc()
        txt.print(conv.str_uw(1000))
        txt.nl()
    }
}""", $$"255 65535 -123456 $fa %10101010\n007 00042 -128 -32768 -2147483648 4294967295\nbeef 1000000000000001 $12345678 true false 1000\n"),

    "quad number formatting" to OutputProgram($$"""
%import textio
%zeropage basicsafe

main {
    uquad @shared uzero = 0
    uquad @shared uone = 1
    uquad @shared unines = 9999999999999999999 as uquad
    uquad @shared uten19 = 10000000000000000000
    uquad @shared umax = sys.MAX_UQUAD
    quad @shared qzero = 0
    quad @shared qminus1 = -1
    quad @shared qbig = -5000000000
    quad @shared qmin = sys.MIN_QUAD
    quad @shared qmax = sys.MAX_QUAD
    uquad @shared pattern = $fedc_ba98_7654_3210
    uquad @shared topbit = $8000_0000_0000_0001

    sub start() {
        txt.print_u64(uzero)
        txt.spc()
        txt.print_u64(uone)
        txt.spc()
        txt.print_uq(unines)
        txt.spc()
        txt.print_uq(uten19)
        txt.spc()
        txt.print_u64(umax)
        txt.nl()
        txt.print_i64(qzero)
        txt.spc()
        txt.print_i64(qminus1)
        txt.spc()
        txt.print_q(qbig)
        txt.spc()
        txt.print_q(qmin)
        txt.spc()
        txt.print_i64(qmax)
        txt.nl()
        txt.print_uqhex(uone)
        txt.spc()
        txt.print_uqhex(pattern)
        txt.spc()
        txt.print_uqhex(umax)
        txt.nl()
        txt.print_uqbin(uone)
        txt.nl()
        txt.print_uqbin(topbit)
        txt.nl()
        txt.print_uqbin(umax)
        txt.nl()
        txt.print(conv.str_u64(umax))
        txt.spc()
        txt.print(conv.str_uq(uten19))
        txt.spc()
        txt.print(conv.str_i64(qmin))
        txt.spc()
        txt.print(conv.str_q(qbig))
        txt.spc()
        txt.print(conv.str_uqhex(topbit))
        txt.spc()
        txt.print(conv.str_uqbin(pattern))
        txt.nl()
        txt.print_ub(sys.SIZEOF_QUAD)
        txt.spc()
        txt.print_ub(sys.SIZEOF_UQUAD)
        txt.spc()
        txt.print_u64(sys.MIN_UQUAD)
        txt.nl()
    }
}""", "0 1 9999999999999999999 10000000000000000000 18446744073709551615\n" +
        "0 -1 -5000000000 -9223372036854775808 9223372036854775807\n" +
        "0000000000000001 fedcba9876543210 ffffffffffffffff\n" +
        "0".repeat(63) + "1\n" + "1" + "0".repeat(62) + "1\n" + "1".repeat(64) + "\n" +
        "18446744073709551615 10000000000000000000 -9223372036854775808 -5000000000 8000000000000001 " +
        "1111111011011100101110101001100001110110010101000011001000010000\n" +
        "8 8 0\n"),

    "quad values computed and printed with textio, syslib and conv" to OutputProgram($$"""
%import textio
%import syslib
%import conv
%zeropage basicsafe

main {
    ubyte @shared firstfib = 90
    uquad @shared one = 1
    uquad @shared umax = sys.MAX_UQUAD
    uquad @shared pattern = $1234_5678_9abc_def0
    quad @shared seven = 7
    quad @shared factor = -1000
    quad @shared small = 1234567
    quad @shared big = 5001234567
    quad @shared step = 2000000000
    quad @shared qmin = sys.MIN_QUAD
    quad @shared qmax = sys.MAX_QUAD
    ubyte @shared ubig = 200
    uword @shared wbig = 65000
    byte @shared bneg = -56
    long @shared lmin = -2147483648
    ubyte @shared halfbits = 32
    ubyte[7] @shared exponents = [0, 1, 31, 32, 33, 62, 63]

    sub start() {
        factorials()
        fibonacci()
        signed_values()
        mixed_types()
        powers_of_two()
        bit_patterns()
        conversions()
    }

    sub factorials() {
        uquad product = one
        ubyte n = 0
        repeat {
            txt.print_ub(n)
            txt.print("! = ")
            txt.print_u64(product)
            txt.nl()
            n++
            if product > umax / n
                break
            product *= n
        }
    }

    sub fibonacci() {
        uquad previous = 0
        uquad current = one
        uquad following
        ubyte n = 1
        while previous <= umax - current {
            following = previous + current
            previous = current
            current = following
            n++
            if n >= firstfib {
                txt.print("F(")
                txt.print_ub(n)
                txt.print(") = ")
                txt.print_uq(current)
                txt.nl()
            }
        }
        txt.print_q((current as quad))
        txt.spc()
        txt.print_u64(previous + current)
        txt.nl()
    }

    sub signed_values() {
        quad value = seven
        txt.print_q(value)
        repeat 6 {
            value *= factor
            txt.spc()
            txt.print_q(value)
        }
        txt.nl()

        value = big
        repeat {
            txt.print_i64(value)
            if value < -step
                break
            txt.spc()
            value -= step
        }
        txt.nl()

        value = small - big
        txt.print_i64(value)
        txt.spc()
        txt.print_i64(value / seven)
        txt.spc()
        txt.print_i64(value % seven)
        txt.spc()
        txt.print_i64(-value)
        txt.nl()

        txt.print_q(qmin)
        txt.spc()
        txt.print_q(qmax)
        txt.spc()
        txt.print_q(qmin + 1)
        txt.spc()
        txt.print_q(qmin + qmax)
        txt.spc()
        txt.print_q(qmin + qmax + 1)
        txt.nl()

        txt.print_i64(qmax + 1)
        txt.spc()
        txt.print_i64(qmin - 1)
        txt.spc()
        txt.print_i64(-qmin)
        txt.spc()
        txt.print_i64(qmin >> 1)
        txt.spc()
        txt.print_i64(qmin >> 63)
        txt.nl()
    }

    sub mixed_types() {
        uquad unsignedwide = ubig
        quad signedwide = bneg
        txt.print_u64(unsignedwide)
        txt.spc()
        txt.print_u64(wbig)
        txt.spc()
        txt.print_q(signedwide)
        txt.spc()
        txt.print_q(lmin)
        txt.spc()
        txt.print_l((big as long))
        txt.spc()
        txt.print_q(big + lmin)
        txt.spc()
        txt.print_q(big * bneg)
        txt.nl()
    }

    sub powers_of_two() {
        ubyte exponent
        uquad power
        for exponent in exponents {
            power = one << exponent
            txt.print("2^")
            txt.print_ub(exponent)
            txt.print(" = ")
            txt.print_u64(power)
            txt.spc()
            txt.print_uqhex(power)
            txt.spc()
            txt.print_uqhex(power - 1)
            txt.nl()
        }
    }

    sub bit_patterns() {
        uquad highmask = umax << halfbits
        show(highmask)
        show(pattern)
        show(~pattern)
        show(one << (sys.SIZEOF_UQUAD * 8 - 1))
        show(umax + 1)
        show(umax)
        txt.print_uqhex(pattern & highmask)
        txt.spc()
        txt.print_uqhex(pattern | ~highmask)
        txt.spc()
        txt.print_uqhex(pattern ^ highmask)
        txt.spc()
        txt.print_uqhex(pattern >> 4)
        txt.spc()
        txt.print_uqhex((pattern << halfbits) | (pattern >> halfbits))
        txt.nl()
    }

    sub show(uquad value) {
        txt.print_uqhex(value)
        txt.spc()
        txt.print_uqbin(value)
        txt.nl()
    }

    sub conversions() {
        uquad lowhalf = pattern & (umax >> halfbits)
        uquad highhalf = pattern >> halfbits
        txt.print(conv.str_u64(umax / 10))
        txt.spc()
        txt.print(conv.str_uq(umax % 10))
        txt.spc()
        txt.print(conv.str_i64(qmin / 10))
        txt.spc()
        txt.print(conv.str_q(qmin % 10))
        txt.nl()
        txt.print(conv.str_uqhex(highhalf * lowhalf))
        txt.spc()
        txt.print(conv.str_u64(highhalf * lowhalf))
        txt.nl()
        txt.print(conv.str_uqbin(pattern + pattern))
        txt.nl()
        txt.print_ub(length(conv.str_u64(umax)))
        txt.spc()
        txt.print_ub(length(conv.str_i64(qmin)))
        txt.spc()
        txt.print_ub(length(conv.str_uqhex(one)))
        txt.spc()
        txt.print_ub(length(conv.str_uqbin(one)))
        txt.nl()
    }

    sub length(str text) -> ubyte {
        ubyte count = 0
        while text[count] != 0
            count++
        return count
    }
}""", """0! = 1
1! = 1
2! = 2
3! = 6
4! = 24
5! = 120
6! = 720
7! = 5040
8! = 40320
9! = 362880
10! = 3628800
11! = 39916800
12! = 479001600
13! = 6227020800
14! = 87178291200
15! = 1307674368000
16! = 20922789888000
17! = 355687428096000
18! = 6402373705728000
19! = 121645100408832000
20! = 2432902008176640000
F(90) = 2880067194370816120
F(91) = 4660046610375530309
F(92) = 7540113804746346429
F(93) = 12200160415121876738
-6246583658587674878 1293530146158671551
7 -7000 7000000 -7000000000 7000000000000 -7000000000000000 7000000000000000000
5001234567 3001234567 1001234567 -998765433 -2998765433
-5000000000 -714285714 -2 5000000000
-9223372036854775808 9223372036854775807 -9223372036854775807 -1 0
-9223372036854775808 9223372036854775807 -9223372036854775808 -4611686018427387904 -1
200 65000 -56 -2147483648 706267271 2853750919 -280069135752
2^0 = 1 0000000000000001 0000000000000000
2^1 = 2 0000000000000002 0000000000000001
2^31 = 2147483648 0000000080000000 000000007fffffff
2^32 = 4294967296 0000000100000000 00000000ffffffff
2^33 = 8589934592 0000000200000000 00000001ffffffff
2^62 = 4611686018427387904 4000000000000000 3fffffffffffffff
2^63 = 9223372036854775808 8000000000000000 7fffffffffffffff
ffffffff00000000 1111111111111111111111111111111100000000000000000000000000000000
123456789abcdef0 0001001000110100010101100111100010011010101111001101111011110000
edcba9876543210f 1110110111001011101010011000011101100101010000110010000100001111
8000000000000000 1000000000000000000000000000000000000000000000000000000000000000
0000000000000000 0000000000000000000000000000000000000000000000000000000000000000
ffffffffffffffff 1111111111111111111111111111111111111111111111111111111111111111
1234567800000000 12345678ffffffff edcba9879abcdef0 0123456789abcdef 9abcdef012345678
1844674407370955161 5 -922337203685477580 -8
0b00ea4e242d2080 792891155752493184
0010010001101000101011001111000100110101011110011011110111100000
20 20 16 64
"""),

    "memory routines and sys.exit" to OutputProgram($$"""
%import textio
%import syslib
%zeropage basicsafe

main {
    ubyte[8] source = [1, 2, 3, 4, 5, 6, 7, 8]
    ubyte[8] target
    uword[3] words

    sub start() {
        sys.memset(&target, len(target), $ee)
        print_target()
        sys.memcopy(&source, &target, 5)
        print_target()
        txt.print_b(sys.memcmp(&source, &target, 5))
        txt.spc()
        txt.print_b(sys.memcmp(&source, &target, 6))
        txt.spc()
        txt.print_b(sys.memcmp(&target, &source, 6))
        txt.nl()
        sys.memsetw(&words, len(words), $1234)
        txt.print_uwhex(words[0], false)
        txt.spc()
        txt.print_uwhex(words[2], false)
        txt.nl()
        sys.exit(0)
        txt.print("not reached\n")
    }

    sub print_target() {
        ubyte value
        for value in target {
            txt.print_ubhex(value, false)
            txt.spc()
        }
        txt.nl()
    }
}""", "ee ee ee ee ee ee ee ee \n01 02 03 04 05 ee ee ee \n0 -1 1\n1234 1234\n"),

    "sys.die" to OutputProgram("""
%import textio
%import syslib
%zeropage basicsafe

main {
    sub start() {
        txt.print("about to fail")
        sys.die(1, "error message")
        txt.print("not reached\n")
    }
}""", "about to fail\n\nPROGRAM DIED: error message\n", exitCode = 1),

    "console input" to OutputProgram("""
%import textio
%zeropage basicsafe

main {
    ubyte[81] buffer

    sub start() {
        txt.print_ub(txt.chrin())
        txt.nl()
        ^^ubyte name = txt.prompt("name? ")
        txt.print("hello ")
        txt.print(name)
        txt.print("!\n")
        txt.print_ub(txt.input_chars(buffer))
        txt.chrout(':')
        txt.print(buffer)
        txt.nl()
        txt.print(txt.readln())
        txt.nl()
        txt.print_ub(txt.input_chars(buffer))
        txt.nl()
        txt.print_ub(txt.chrin())
        txt.nl()
    }
}""", "65\nname? hello bc!\n5:World\nline two\n80\n0\n", input = "Abc\nWorld\r\nline two\n" + "x".repeat(90) + "\n"),

    "command line arguments" to OutputProgram("""
%import textio
%zeropage basicsafe

main {
    sub start() {
        uword count = sys.argc()
        txt.print_uw(count)
        txt.print(" arguments\n")
        uword index
        for index in 1 to count-1 {
            txt.print_uw(index)
            txt.print(": [")
            txt.print(sys.argv(index))
            txt.print("]\n")
        }
        if sys.argv(0) != ""
            txt.print("0: the program\n")
        txt.print("past the last one: [")
        txt.print(sys.argv(count))
        txt.print(sys.argv(65535))
        txt.print("]\n")
        sys.exit(lsb(count))
    }
}""", "4 arguments\n1: [--level=3]\n2: [two words]\n3: []\n0: the program\npast the last one: []\n", exitCode = 4,
        args = listOf("--level=3", "two words", "")),

    "sys.argc and sys.argv with a single argument" to OutputProgram("""
%import textio
%import syslib
%zeropage basicsafe

main {
    sub start() {
        uword count = sys.argc()
        if count < 1 {
            txt.print("no program name\n")
            sys.exit(1)
        }
        if sys.argv(0) == "" {
            txt.print("empty program name\n")
            sys.exit(2)
        }
        txt.print("argc=")
        txt.print_uw(count)
        ; the last two indices are past the arguments
        uword index
        for index in 1 to count + 1 {
            txt.print(" [")
            txt.print(sys.argv(index))
            txt.chrout(']')
        }
        txt.nl()
        if sys.argv(65535) != "" {
            txt.print("an argument past the end\n")
            sys.exit(3)
        }
    }
}""", "argc=2 [single] [] []\n", args = listOf("single")),

    "diskio writing, reading lines, seeking in and deleting a text file" to OutputProgram("""
%import textio
%import diskio
%import strings
%zeropage basicsafe

main {
    str filename = "lines.txt"
    ubyte[256] line
    ubyte[301] long_line

    sub start() {
        if not diskio.f_open_w(filename)
            sys.exit(1)
        write_line("first line")
        write_line("")
        write_line("third, after an empty one")
        if not diskio.f_write("no line end", 11)
            sys.exit(2)
        diskio.f_close_w()

        if not diskio.f_open(filename)
            sys.exit(3)
        ubyte length, status
        repeat {
            length, status = diskio.f_readline(&line)
            txt.print_ub(length)
            txt.spc()
            txt.print_ubhex(status, true)
            txt.print(" [")
            txt.print(&line)
            txt.print("]\n")
            if status != 0
                break
        }
        show_position()

        txt.print_bool(diskio.f_seek(12))
        txt.spc()
        length, status = diskio.f_readline(&line)
        txt.print(&line)
        txt.nl()
        show_position()
        txt.print_bool(diskio.f_seek(-1))
        txt.spc()
        show_position()
        txt.print_bool(diskio.f_seek(38))
        read_part(5)
        read_part(100)
        read_part(100)
        txt.spc()
        void diskio.f_seek(0)
        txt.print_l(diskio.f_read_all(&line) as long)
        txt.nl()
        diskio.f_close()

        ; a line of more than 255 characters continues in the next call
        sys.memset(&long_line, 300, 'x')
        long_line[300] = '\n'
        if not diskio.f_open_w("long.txt") or not diskio.f_write(&long_line, len(long_line))
            sys.exit(5)
        diskio.f_close_w()
        if not diskio.f_open("long.txt")
            sys.exit(6)
        txt.print("long line:")
        repeat 3 {
            length, status = diskio.f_readline(&line)
            txt.spc()
            txt.print_ub(length)
            txt.spc()
            txt.print_ubhex(status, true)
        }
        txt.nl()
        diskio.f_close()

        diskio.delete("long.txt")
        diskio.delete(filename)
        txt.print_bool(diskio.exists(filename))
        txt.spc()
        txt.print_bool(diskio.f_open(filename))
        txt.nl()
    }

    sub write_line(str text) {
        if not diskio.f_write(text, strings.length(text)) or not diskio.f_write("\n", 1)
            sys.exit(4)
    }

    sub read_part(uword size) {
        pointer count = diskio.f_read(&line, size)
        line[lsb(count)] = 0
        txt.spc()
        txt.print_l(count as long)
        txt.print(" [")
        txt.print(&line)
        txt.chrout(']')
    }

    sub show_position() {
        long position, size
        position, size = diskio.f_tell()
        txt.print_l(position)
        txt.chrout('/')
        txt.print_l(size)
        txt.nl()
    }
}""", $$"""10 $00 [first line]
0 $00 []
25 $00 [third, after an empty one]
11 $40 [no line end]
49/49
true third, after an empty one
38/49
false 38/49
true 5 [no li] 6 [ne end] 0 [] 49
long line: 255 $00 45 $00 0 $40
false false
"""),

    "diskio directories, renaming and deleting" to OutputProgram("""
%import textio
%import diskio
%zeropage basicsafe

main {
    sub start() {
        txt.print(last_name(diskio.curdir()))
        txt.spc()
        diskio.mkdir("subdir")
        diskio.chdir("subdir")
        txt.print(last_name(diskio.curdir()))
        txt.nl()
        void diskio.save_raw("first.txt", "data", 4)
        diskio.rename("first.txt", "second.txt")
        txt.print_bool(diskio.exists("first.txt"))
        txt.spc()
        txt.print_bool(diskio.exists("second.txt"))
        txt.nl()
        diskio.chdir("..")
        txt.print(last_name(diskio.curdir()))
        txt.spc()
        txt.print_bool(diskio.exists("subdir/second.txt"))
        txt.spc()
        ; a directory that isn't empty stays
        diskio.rmdir("subdir")
        txt.print_bool(diskio.exists("subdir/second.txt"))
        txt.spc()
        diskio.delete("subdir/second.txt")
        diskio.rmdir("subdir")
        txt.print_bool(diskio.exists("subdir/second.txt"))
        txt.spc()
        txt.print_bool(diskio.exists("subdir"))
        txt.nl()
    }

    sub last_name(pointer path) -> pointer {
        ; the part of the path after its last '/'
        pointer name = path
        while @(path) != 0 {
            if @(path) == '/'
                name = path + 1
            path++
        }
        return name
    }
}""", "run subdir\nfalse true\nrun true true false false\n"),

    "fileio copying between two open files, seeking and the end of the file" to OutputProgram("""
%import textio
%import fileio
%import diskio
%zeropage basicsafe

main {
    ubyte[50] data
    ubyte[16] chunk
    ubyte[60] copied

    sub start() {
        ubyte i
        for i in 0 to len(data)-1
            data[i] = i * 3
        pointer output = fileio.open("input.bin", "wb")
        txt.print_uw(fileio.write(output, &data, len(data)))
        txt.spc()
        txt.print_bool(fileio.close(output))
        txt.nl()

        pointer input = fileio.open("input.bin", "rb")
        output = fileio.open("copy.bin", "wb")
        if input == 0 or output == 0
            sys.exit(1)
        uword count
        repeat {
            count = fileio.read(input, &chunk, len(chunk))
            txt.print_uw(count)
            txt.spc()
            if fileio.write(output, &chunk, count) != count
                sys.exit(2)
            if fileio.eof(input)
                break
        }
        txt.print_i64(fileio.tell(input))
        txt.spc()
        txt.print_i64(fileio.tell(output))
        txt.spc()
        txt.print_bool(fileio.flush(output))
        txt.spc()
        txt.print_bool(fileio.close(input))
        txt.spc()
        txt.print_bool(fileio.close(output))
        txt.nl()

        input = fileio.open("copy.bin", "rb")
        txt.print_uw(fileio.read(input, &copied, len(copied)))
        txt.spc()
        txt.print_bool(fileio.eof(input))
        txt.spc()
        txt.print_b(sys.memcmp(&data, &copied, len(data)))
        txt.nl()
        txt.print_bool(fileio.seek(input, -10, fileio.SEEK_END))
        show(input)
        txt.print_bool(fileio.seek(input, 5, fileio.SEEK_CUR))
        show(input)
        txt.print_bool(fileio.seek(input, 3, fileio.SEEK_SET))
        show(input)
        txt.print_bool(fileio.seek(input, -1, fileio.SEEK_SET))
        show(input)
        txt.print_bool(fileio.close(input))
        txt.nl()

        ; a file that can't be opened gives handle 0, which the routines accept
        pointer missing = fileio.open("missing.bin", "rb")
        txt.print_bool(missing == 0)
        txt.spc()
        txt.print_uw(fileio.read(missing, &chunk, 1))
        txt.spc()
        txt.print_uw(fileio.write(missing, &chunk, 1))
        txt.spc()
        txt.print_bool(fileio.seek(missing, 0, fileio.SEEK_SET))
        txt.spc()
        txt.print_i64(fileio.tell(missing))
        txt.spc()
        txt.print_bool(fileio.eof(missing))
        txt.spc()
        txt.print_bool(fileio.close(missing))
        txt.nl()
        diskio.delete("input.bin")
        diskio.delete("copy.bin")
    }

    sub show(pointer file) {
        ; prints the position, whether a read went past the end, and the byte that is read there
        txt.spc()
        txt.print_i64(fileio.tell(file))
        txt.spc()
        txt.print_bool(fileio.eof(file))
        txt.spc()
        ubyte value
        txt.print_uw(fileio.read(file, &value, 1))
        txt.chrout(':')
        txt.print_ub(value)
        txt.nl()
    }
}""", """50 true
16 16 16 2 50 50 true true true
50 true 0
true 40 false 1:120
true 46 false 1:138
true 3 false 1:9
false 4 false 1:12
true
true 0 0 false -1 true false
"""),

    "diskio saving and loading memory" to OutputProgram("""
%import textio
%import diskio
%zeropage basicsafe

main {
    ubyte[1000] original
    ubyte[1000] loaded
    ubyte[1002] withheader

    sub start() {
        uword i
        for i in 0 to len(original)-1
            original[i] = lsb(i * 7 + msb(i))
        txt.print_bool(diskio.save("memory.prg", &original, len(original)))
        txt.spc()
        txt.print_bool(diskio.save_raw("memory.bin", &original, len(original)))
        txt.nl()

        pointer end = diskio.load("memory.prg", &loaded)
        txt.print_bool(end == &loaded + len(loaded))
        txt.spc()
        txt.print_b(sys.memcmp(&original, &loaded, len(loaded)))
        txt.nl()
        sys.memset(&loaded, len(loaded), 0)
        end = diskio.load_raw("memory.bin", &loaded)
        txt.print_bool(end == &loaded + len(loaded))
        txt.spc()
        txt.print_b(sys.memcmp(&original, &loaded, len(loaded)))
        txt.nl()

        ; the header that save() writes holds the lower 16 bits of the address
        end = diskio.load_raw("memory.prg", &withheader)
        txt.print_bool(end == &withheader + len(withheader))
        txt.spc()
        uword address = &original as uword
        txt.print_bool(withheader[0] == lsb(address) and withheader[1] == msb(address))
        txt.spc()
        txt.print_bool(diskio.get_loadaddress("memory.prg") == address)
        txt.spc()
        txt.print_b(sys.memcmp(&original, &withheader + 2, len(original)))
        txt.nl()
        ; the first two bytes of any file, here 0 and 7
        txt.print_uw(diskio.get_loadaddress("memory.bin"))
        txt.spc()
        txt.print_uw(diskio.get_loadaddress("missing.prg"))
        txt.nl()

        txt.print_bool(diskio.load("missing.prg", &loaded) == 0)
        txt.spc()
        txt.print_bool(diskio.load("memory.prg", 0) == 0)
        txt.nl()
        diskio.delete("memory.prg")
        diskio.delete("memory.bin")
    }
}""", "true true\ntrue 0\ntrue 0\ntrue true true 0\n1792 0\ntrue true\n"),

    "strings library" to OutputProgram("""
%import textio
%import strings
%zeropage basicsafe

main {
    str hello = "Hello, World"
    str buffer = "?" * 40
    str spaced = "  \t padded text \n "
    str words = "  the quick  brown\tfox "
    str csv = "a,b;c"
    pointer[6] parts

    sub start() {
        txt.print_ub(strings.length(hello))
        txt.spc()
        txt.print_ub(strings.strlen(""))
        txt.nl()
        strings.left(hello, 5, buffer)
        show(buffer)
        strings.right(hello, 5, buffer)
        show(buffer)
        strings.slice(hello, 7, 3, buffer)
        show(buffer)
        strings.substr(hello, 2, 0, buffer)
        show(buffer)
        txt.nl()

        ubyte idx
        bool found
        idx, found = strings.find(hello, 'o')
        txt.print_ub(idx)
        txt.print_bool(found)
        idx, found = strings.find(hello, 'z')
        txt.print_ub(idx)
        txt.print_bool(found)
        idx, found = strings.rfind(hello, 'o')
        txt.print_ub(idx)
        txt.print_bool(found)
        idx, found = strings.find_eol(spaced)
        txt.print_ub(idx)
        txt.print_bool(found)
        txt.print_bool(strings.contains(hello, 'W'))
        txt.print_bool(strings.strcontains(hello, 'w'))
        txt.nl()

        txt.print_ub(strings.copy(hello, buffer))
        show(buffer)
        txt.print_ub(strings.strcpy("abc", buffer))
        show(buffer)
        txt.print_ub(strings.ncopy(hello, buffer, 4))
        show(buffer)
        txt.print_ub(strings.append(buffer, "-tail"))
        show(buffer)
        txt.print_ub(strings.strcat(buffer, "!"))
        show(buffer)
        txt.print_ub(strings.nappend(buffer, "123456", 13))
        show(buffer)
        txt.nl()

        txt.print_b(strings.compare("abc", "abd"))
        txt.print_b(strings.compare("abc", "abc"))
        txt.print_b(strings.compare("abcd", "abc"))
        txt.print_b(strings.strcmp("", "a"))
        txt.print_b(strings.compare("\xff", "a"))
        txt.print_b(strings.compare_nocase("HeLLo", "hello"))
        txt.print_b(strings.compare_nocase("abc", "ABD"))
        txt.nl()

        void strings.copy(hello, buffer)
        txt.print_ub(strings.lower(buffer))
        show(buffer)
        txt.print_ub(strings.strupper(buffer))
        show(buffer)
        void strings.strlower(buffer)
        show(buffer)
        txt.chrout(strings.upperchar('q'))
        txt.chrout(strings.lowerchar('Q'))
        txt.chrout(strings.lowerchar('1'))
        txt.spc()
        txt.print_ub(strings.hash(hello))
        txt.spc()
        txt.print_ub(strings.hash("prog8"))
        txt.nl()

        txt.print_bool(strings.isdigit('5'))
        txt.print_bool(strings.isdigit('a'))
        txt.print_bool(strings.isxdigit('F'))
        txt.print_bool(strings.isxdigit('g'))
        txt.print_bool(strings.isupper('A'))
        txt.print_bool(strings.islower('A'))
        txt.print_bool(strings.isletter('z'))
        txt.print_bool(strings.isspace('\t'))
        txt.print_bool(strings.isprint(7))
        txt.nl()

        void strings.copy(spaced, buffer)
        strings.trim(buffer)
        show(buffer)
        void strings.copy(spaced, buffer)
        strings.strtrim(buffer)
        show(buffer)
        void strings.copy(spaced, buffer)
        strings.strip(buffer)
        show(buffer)
        void strings.copy(spaced, buffer)
        strings.rtrim(buffer)
        show(buffer)
        void strings.copy(spaced, buffer)
        strings.ltrim(buffer)
        show(buffer)
        void strings.copy("   ", buffer)
        strings.rtrim(buffer)
        show(buffer)
        show(strings.ltrimmed("   x "))
        show(strings.lstripped("\x01 y "))
        txt.nl()

        txt.print_bool(strings.startswith(hello, "Hell"))
        txt.print_bool(strings.startswith(hello, "hell"))
        txt.print_bool(strings.endswith(hello, "World"))
        txt.print_bool(strings.endswith("ld", "World"))
        txt.spc()
        txt.print_ub(strings.findstr(hello, "World"))
        txt.spc()
        txt.print_ub(strings.findstr(hello, "world"))
        txt.nl()

        ubyte count = strings.split(words, &parts, len(parts))
        txt.print_ub(count)
        ubyte i
        for i in 0 to count-1
            show(parts[i])
        txt.nl()
        ^^ubyte token = strings.next_token(csv, ",;")
        while token != 0 {
            show(token)
            token = strings.next_token(0, ",;")
        }
        txt.nl()
    }

    sub show(str s) {
        txt.chrout('[')
        txt.print(s)
        txt.chrout(']')
    }
}""", "12 0\n[Hello][World][Wor][]\n4true255false8true16truetruefalse\n12[Hello, World]3[abc]4[Hell]9[Hell-tail]10[Hell-tail!]13[Hell-tail!123]\n-101-110-1\n12[hello, world]12[HELLO, WORLD][hello, world]Qq1 3 165\ntruefalsetruefalsetruefalsetruetruefalse\n[padded text][padded text][padded text][  \t padded text][padded text \n ][][x ][y ]\ntruefalsetruefalse 7 255\n4[the][quick][brown][fox]\n[a][b][c]\n"),

    "math library" to OutputProgram($$"""
%import textio
%import math
%zeropage basicsafe

main {
    str text = "123456789"
    word @shared negative = -1234
    long @shared big = -100000

    sub start() {
        txt.print_ub(math.sin8u(0))
        txt.spc()
        txt.print_ub(math.cos8u(192))
        txt.spc()
        txt.print_b(math.sin8(64))
        txt.spc()
        txt.print_b(math.cos8(128))
        txt.spc()
        txt.print_ub(math.sinr8u(45))
        txt.spc()
        txt.print_b(math.cosr8(90))
        txt.nl()

        repeat 5 {
            txt.print_uw(math.rndw())
            txt.spc()
        }
        txt.nl()
        math.rndseed($1234, $5678)
        repeat 5 {
            txt.print_ub(math.rnd())
            txt.spc()
        }
        repeat 5 {
            txt.print_ub(math.randrange(10))
            txt.spc()
        }
        repeat 5 {
            txt.print_uw(math.randrangew(1000))
            txt.spc()
        }
        txt.nl()
        math.rndseed($c200, $3711)
        txt.print_uw(math.rndw())
        txt.nl()

        txt.print_ub(math.log2(1))
        txt.spc()
        txt.print_ub(math.log2(200))
        txt.spc()
        txt.print_ub(math.log2w(1000))
        txt.spc()
        txt.print_ub(math.log2l(100000))
        txt.spc()
        txt.print_l(math.mul32(60000, 50000))
        txt.spc()
        txt.print_ub(math.diff(3, 10))
        txt.spc()
        txt.print_uw(math.diffw(60000, 100))
        txt.spc()
        txt.print_l(math.diffl(-5, 100000))
        txt.nl()

        txt.print_uwhex(math.crc16(text, 9, 0, 0), true)
        txt.spc()
        txt.print_uwhex(math.crc16(text, 9, $ffff, 0), true)
        txt.spc()
        txt.print_ulhex(math.crc32(text, 9), true)
        txt.spc()
        txt.print_uwhex(math.crc16(text, 0, $ffff, $ffff), true)
        txt.nl()

        txt.print_ub(math.lerp(10, 200, 0))
        txt.spc()
        txt.print_ub(math.lerp(10, 200, 128))
        txt.spc()
        txt.print_ub(math.lerp(200, 10, 255))
        txt.spc()
        txt.print_uw(math.lerpw(1000, 60000, 32768))
        txt.spc()
        txt.print_uw(math.lerpw(60000, 1000, 65535))
        txt.spc()
        txt.print_ub(math.interpolate(50, 0, 100, 0, 200))
        txt.spc()
        txt.print_uw(math.interpolatew(500, 0, 1000, 1000, 3000))
        txt.nl()

        txt.print_uw(math.gcd(48, 180))
        txt.spc()
        txt.print_uw(math.gcd(0, 7))
        txt.spc()
        txt.print_uw(math.gcd(17, 5))
        txt.spc()
        txt.print_uw(math.lcm(4, 6))
        txt.spc()
        txt.print_uw(math.lcm(0, 6))
        txt.spc()
        txt.print_uw(math.lcm(21, 6))
        txt.spc()
        txt.print_uw(math.lcm(1000, 999))
        txt.nl()

        txt.print_uw(abs(negative))
        txt.spc()
        txt.print_w(min(negative, 5))
        txt.spc()
        txt.print_w(max(negative, 5))
        txt.spc()
        txt.print_w(clamp(negative, -1000, 1000))
        txt.spc()
        txt.print_b(sgn(negative))
        txt.spc()
        txt.print_b(sgn(big))
        txt.spc()
        txt.print_l(abs(big))
        txt.nl()
    }
}""", $$"128 127 127 -127 255 -127\n2457 21241 1451 8632 49784 \n24 122 135 121 16 6 7 6 0 3 88 713 31 129 416 \n2457\n0 7 9 16 -1294967296 7 59900 100005\n$31c3 $29b1 $cbf43926 $0000\n10 105 10 30500 1000 100 2000\n12 7 1 12 0 42 15960\n1234 -1234 5 -1000 -1 -1 100000\n"),

    "floats library" to OutputProgram("""
%import textio
%import floats
%import math
%zeropage basicsafe

main {
    float @shared half = 0.5
    float @shared two = 2.0
    float @shared minus = -2.5

    sub start() {
        show(floats.sin(floats.PI / 6))
        show(floats.cos(floats.PI / 3))
        show(floats.tan(floats.PI / 4))
        show(floats.asin(half))
        show(floats.asin(1.0))
        show(floats.asin(-1.0))
        show(floats.asin(7.0))
        show(floats.acos(half))
        show(floats.acos(-1.0))
        show(floats.atan(1.0))
        show(floats.atan2(1.0, 1.0))
        show(floats.atan2(1.0, -1.0))
        show(floats.atan2(-1.0, -1.0))
        show(floats.atan2(-1.0, 1.0))
        show(floats.atan2(1.0, 0.0))
        show(floats.atan2(-1.0, 0.0))
        show(floats.atan2(0.0, 0.0))
        txt.nl()
        show(sqrt(2.25))
        show(sqrt(two))
        show(floats.pow(two, 10.0))
        show(floats.exp(1.0))
        show(floats.exp(0.0))
        show(floats.log(floats.E))
        show(floats.ln(100.0))
        show(floats.log2(1024.0))
        txt.nl()
        show(floats.floor(minus))
        show(floats.ceil(minus))
        show(floats.round(minus))
        show(floats.round(2.5))
        show(floats.round(3.5))
        show(floats.round(2.4))
        show(floats.floor(2.7))
        show(floats.ceil(2.1))
        txt.nl()
        show(floats.rad(180.0))
        show(floats.deg(floats.PI))
        show(floats.secant(0.0))
        show(floats.csc(floats.PI / 2))
        show(floats.cot(floats.PI / 4))
        show(floats.minf(minus, two))
        show(floats.maxf(minus, two))
        show(floats.clampf(minus, -1.0, 1.0))
        show(floats.mod(7.5, 2.0))
        show(floats.lerp(10.0, 20.0, 0.25))
        show(math.lerpf_fast(10.0, 20.0, 0.75))
        show(math.interpolatef(5.0, 0.0, 10.0, 100.0, 200.0))
        show(floats.TWOPI)
        txt.nl()
        txt.print_bool(sys.MAX_FLOAT > 1.0e308)
        txt.print_bool(sys.MIN_FLOAT < -1.0e308)
        txt.print_bool(floats.EPSILON > 0.0)
        txt.nl()
    }

    sub show(float value) {
        ; prints the value in millionths, rounded
        txt.print_l(floats.round(value * 1000000.0) as long)
        txt.spc()
    }
}""", "500000 500000 1000000 523599 1570796 -1570796 1570796 1047198 3141593 785398 785398 2356194 3926991 5497787 1570796 4712389 0 \n1500000 1414214 1024000000 2718282 1000000 1000000 4605170 10000000 \n-3000000 -2000000 -2000000 2000000 4000000 2000000 2000000 3000000 \n3141593 180000000 1000000 1000000 1000000 -2500000 2000000 -1000000 1500000 12500000 17500000 150000000 6283185 \ntruetruetrue\n")
)
