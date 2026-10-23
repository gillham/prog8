package prog8.codegen.llvm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import prog8.code.target.LlvmTarget
import prog8.code.target.LlvmTripleProfile
import prog8.codegen.llvm.model.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText


class TestLlvmToolchain: FunSpec({

    val dir = tempdir().toPath()

    test("the LLVM IR of a program file is the file itself when that is a .ll file, else <stem>.ll next to the native executable") {
        LlvmToolchain.irFileFor(Path.of("x.ll")) shouldBe Path.of("x.ll")
        LlvmToolchain.irFileFor(Path.of("x.LL")) shouldBe Path.of("x.LL")
        LlvmToolchain.irFileFor(Path.of("x")) shouldBe Path.of("x.ll")
        LlvmToolchain.irFileFor(Path.of("dir", "x.bin")) shouldBe Path.of("dir", "x.ll")
    }

    test("the executable of a .ll file has the output extension of the triple profile, or else the native executable name") {
        LlvmToolchain.executableFor(Path.of("dir", "x.ll"), ".wasm") shouldBe Path.of("dir", "x.wasm")
        val native = LlvmToolchain.executableFor(Path.of("dir", "x.ll"))
        native shouldBe Path.of("dir", if (System.getProperty("os.name").startsWith("Windows")) "x.exe" else "x")
    }

    test("a run command runs the executable, given as {file}, and its exit code is returned").config(enabled = !System.getProperty("os.name").startsWith("Windows")) {
        val runCommand = listOf("sh", "-c", $$"test \"$0\" = \"$${dir.resolve("prog.wasm")}\" && exit 6", "{file}")
        LlvmToolchain.runExecutable(dir.resolve("prog.wasm"), quiet = true, runCommand) shouldBe 6
    }

    test("compileToExecutable reports an unset environment variable in the clang arguments of the profile").config(enabled = LlvmToolchain.clang() != null) {
        val profile = LlvmTripleProfile("test", "wasm32-unknown-wasip1", "e-p:32:32", 4, clangArgsTemplate = listOf($$"--sysroot=${PROG8_NO_SUCH_VARIABLE}"))
        val llFile = dir.resolve("env.ll").also { it.writeText(programExitingWith(0)) }
        val executable = dir.resolve("env.wasm")
        val (built, messages) = capturingStderr { LlvmToolchain.compileToExecutable(llFile, executable, profile) }
        built shouldBe false
        messages shouldContain "PROG8_NO_SUCH_VARIABLE"
        executable.exists() shouldBe false
    }

    test("with a link command, clang compiles an object file that the command links into the executable, and the object file is removed").config(enabled = LlvmToolchain.clang() != null) {
        val target = LlvmTarget()
        val copying = LlvmTripleProfile("link", target.targetTriple, target.dataLayout, 8, linkCommandTemplate = listOf("cp", "{object}", "{file}"))
        val llFile = dir.resolve("linked.ll").also { it.writeText(programExitingWith(0)) }
        val executable = dir.resolve("linked.elf")
        LlvmToolchain.compileToExecutable(llFile, executable, copying) shouldBe true
        executable.exists() shouldBe true
        dir.resolve("linked.elf.o").exists() shouldBe false

        val failing = LlvmTripleProfile("nolink", target.targetTriple, target.dataLayout, 8, linkCommandTemplate = listOf("false", "{object}", "{file}"))
        val unlinked = dir.resolve("unlinked.elf")
        val (built, messages) = capturingStderr { LlvmToolchain.compileToExecutable(llFile, unlinked, failing) }
        built shouldBe false
        messages shouldContain "the link command couldn't build '$unlinked'"
        dir.resolve("unlinked.elf.o").exists() shouldBe false
    }

    test("a run that takes longer than its timeout is stopped, and reported as an IOException").config(enabled = !System.getProperty("os.name").startsWith("Windows")) {
        val started = System.nanoTime()
        shouldThrow<IOException> {
            LlvmToolchain.runExecutable(dir.resolve("prog.elf"), quiet = true, listOf("sh", "-c", "sleep 30", "{file}"), timeoutSeconds = 1)
        }.message!! shouldContain "timed out after 1 seconds"
        (System.nanoTime() - started) / 1_000_000_000 shouldBeLessThan 20L
        LlvmToolchain.runExecutable(dir.resolve("prog.elf"), quiet = true, listOf("sh", "-c", "exit 4", "{file}"), timeoutSeconds = 10) shouldBe 4
    }

    test("the clang that builds executables is the one next to llvm-as, of the same LLVM installation").config(enabledOrReasonIf = requiresLlvmAs) {
        val besideLlvmAs = checkNotNull(llvmAs).resolveSibling("clang")
        LlvmToolchain.clang() shouldBe (if (Files.isExecutable(besideLlvmAs)) besideLlvmAs else LlvmToolchain.find("clang"))
    }

    test("compileToExecutable builds a native executable, whose exit code runExecutable returns").config(enabledOrReasonIf = requiresNativeArm64Mac) {
        val llFile = dir.resolve("seven.ll").also { it.writeText(programExitingWith(7)) }
        val executable = dir.resolve("seven")
        LlvmToolchain.compileToExecutable(llFile, executable) shouldBe true
        Files.isExecutable(executable) shouldBe true
        LlvmToolchain.runExecutable(executable, quiet = true) shouldBe 7
    }

    test("compileToExecutable fails on IR that clang rejects, and says which file it couldn't build").config(enabledOrReasonIf = requiresNativeArm64Mac) {
        val unterminated = programWithMain(LlvmInstruction.Alloca(LlvmValue.Local("p", LlvmType.Ptr), LlvmType.I8))
        val llFile = dir.resolve("broken.ll").also { it.writeText(unterminated) }
        val executable = dir.resolve("broken")
        val (built, messages) = capturingStderr { LlvmToolchain.compileToExecutable(llFile, executable) }
        built shouldBe false
        messages shouldContain "clang couldn't build '$executable' from '$llFile'"
        executable.exists() shouldBe false
    }

    test("the runner reports a missing program file instead of running anything") {
        for (missing in listOf(dir.resolve("missing"), dir.resolve("missing.ll"))) {
            val (status, messages) = capturingStderr { LlvmProgramRunner().buildAndRun(missing, quiet = true) }
            status shouldBe null
            messages shouldContain "No program file found: $missing"
        }
    }

    test("the runner builds the executable of a .ll file next to it, and a missing -o executable from its IR file").config(enabledOrReasonIf = requiresNativeArm64Mac) {
        val llFile = dir.resolve("runner.ll").also { it.writeText(programExitingWith(3)) }
        LlvmProgramRunner().buildAndRun(llFile, quiet = true) shouldBe 3
        Files.isExecutable(dir.resolve("runner")) shouldBe true

        val executable = dir.resolve("named-runner")
        dir.resolve("named-runner.ll").writeText(programExitingWith(5))
        LlvmProgramRunner().buildAndRun(executable, quiet = true) shouldBe 5
        Files.isExecutable(executable) shouldBe true
    }
})


private fun programExitingWith(code: Long): String = programWithMain(LlvmInstruction.Ret(LlvmValue.IntConstant(code, LlvmType.I32)))

/** The LLVM IR of a module for the default llvm64 target, whose C `main` function is the single [instruction]. */
private fun programWithMain(instruction: LlvmInstruction): String {
    val target = LlvmTarget()
    val main = LlvmFunction("main", LlvmType.I32, emptyList(), mutableListOf(LlvmBlock("entry", mutableListOf(instruction))))
    return LlvmPrinter.print(LlvmModule("main", target.targetTriple, target.dataLayout, functions = mutableListOf(main)))
}

/** Other specs may print on System.err at the same time, so only check that the captured text contains something. */
private fun <T> capturingStderr(action: () -> T): Pair<T, String> {
    val original = System.err
    val captured = ByteArrayOutputStream()
    System.setErr(PrintStream(captured, true, Charsets.UTF_8))
    val result = try {
        action()
    } finally {
        System.setErr(original)
    }
    return result to captured.toString(Charsets.UTF_8)
}
