package prog8.codegen.llvm

import io.kotest.core.test.Enabled
import io.kotest.core.test.TestCase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText


// prefer the clang of the same LLVM installation as llvm-as
internal val clang: Path? = llvmAs?.resolveSibling("clang")?.takeIf { Files.isExecutable(it) } ?: LlvmToolchain.find("clang")

internal val requiresNativeArm64Mac: (TestCase) -> Enabled = {
    val arm64Mac = System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") == "aarch64"
    when {
        !arm64Mac -> Enabled.disabled("linking the default llvm64 target triple needs an arm64 macOS host")
        clang == null -> Enabled.disabled("clang not found next to llvm-as, on the PATH or in ${LlvmToolchain.homebrewLlvmBin}")
        else -> Enabled.enabled
    }
}

/**
 * Builds [ir] into a native executable with clang and runs it with [input] on stdin and the command line [args]; returns the exit code
 * and output of the run, or of clang if that failed. Optimizes, because that is when LLVM could fold startup stores back into static data.
 */
internal fun compileAndRun(ir: String, input: String = "", args: List<String> = emptyList()): Pair<Int, String> {
    val dir = createTempDirectory("prog8-llvm-native")
    try {
        val source = dir.resolve("program.ll").also { it.writeText(ir) }
        val executable = dir.resolve("program")
        val stdin = dir.resolve("stdin.txt").also { it.writeText(input) }
        val compiled = execute(checkNotNull(clang).toString(), "-O2", "-Wno-override-module", source.toString(), "-o", executable.toString())
        return if (compiled.first != 0) compiled else execute(executable.toString(), *args.toTypedArray(), stdin = stdin)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

private fun execute(vararg command: String, stdin: Path? = null): Pair<Int, String> {
    val builder = ProcessBuilder(*command).redirectErrorStream(true)
    stdin?.let { builder.redirectInput(it.toFile()) }
    val process = builder.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor(60, TimeUnit.SECONDS)) { "${command.first()} did not finish" }
    return process.exitValue() to output
}
