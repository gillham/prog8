package prog8.codegen.llvm

import io.kotest.core.test.Enabled
import io.kotest.core.test.TestCase
import prog8.code.target.LlvmTarget
import prog8.code.target.LlvmTripleProfile
import java.nio.file.Files
import java.nio.file.InvalidPathException
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

private val wasiSysroot: Path? = (System.getenv("WASI_SYSROOT") ?: "/opt/homebrew/opt/wasi-libc/share/wasi-sysroot")
    .let { try { Path.of(it) } catch (_: InvalidPathException) { null } }?.takeIf { Files.isDirectory(it) }

internal val requiresWasmClang: (TestCase) -> Enabled = {
    when {
        clang == null -> Enabled.disabled("clang not found next to llvm-as, on the PATH or in ${LlvmToolchain.homebrewLlvmBin}")
        wasiSysroot == null -> Enabled.disabled("no WASI sysroot in WASI_SYSROOT or /opt/homebrew/opt/wasi-libc/share/wasi-sysroot")
        else -> Enabled.enabled
    }
}

private val wasmtime: Path? = LlvmToolchain.find("wasmtime")

internal val requiresWasm: (TestCase) -> Enabled = {
    when {
        clang == null -> Enabled.disabled("clang not found next to llvm-as, on the PATH or in ${LlvmToolchain.homebrewLlvmBin}")
        wasiSysroot == null -> Enabled.disabled("no WASI sysroot in WASI_SYSROOT or /opt/homebrew/opt/wasi-libc/share/wasi-sysroot")
        LlvmToolchain.find("wasm-ld") == null -> Enabled.disabled("wasm-ld (from lld) not found on the PATH")
        wasmtime == null -> Enabled.disabled("wasmtime not found on the PATH")
        else -> Enabled.enabled
    }
}

/** The name wasi-libc's startup code calls the C main by. */
internal val wasiMain: String = LlvmTripleProfile.resolve(LlvmTarget.DEFAULT_TRIPLE_32).cMainSymbol

/**
 * Builds [ir] into a wasm32-wasip1 program and runs it with wasmtime, giving it [input] on stdin, the command line [args]
 * and access to [directories]; returns the exit code and output of the run, or of clang if that failed.
 * Uses the clang arguments of the llvm32 target's profile, which make wasm-ld warnings fatal.
 */
internal fun compileAndRunWasm(ir: String, input: String = "", args: List<String> = emptyList(), directories: List<Path> = emptyList()): Pair<Int, String> {
    val dir = createTempDirectory("prog8-llvm-wasm")
    try {
        val source = dir.resolve("program.ll").also { it.writeText(ir) }
        val program = dir.resolve("program.wasm")
        val stdin = dir.resolve("stdin.txt").also { it.writeText(input) }
        val clangArgs = LlvmTripleProfile.resolve(LlvmTarget.DEFAULT_TRIPLE_32).clangArgs
        val compiled = execute(checkNotNull(clang).toString(), *clangArgs.toTypedArray(), "-O2", "-Wno-override-module", source.toString(), "-o", program.toString())
        if (compiled.first != 0)
            return compiled
        val preopens = directories.map { "--dir=$it" }
        return execute(checkNotNull(wasmtime).toString(), *preopens.toTypedArray(), program.toString(), *args.toTypedArray(), stdin = stdin)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

/** Compiles [ir] into a wasm32-wasip1 object file with clang, without linking; returns clang's exit code and output. */
internal fun compileWasmObject(ir: String): Pair<Int, String> {
    val dir = createTempDirectory("prog8-llvm-wasm")
    try {
        val source = dir.resolve("program.ll").also { it.writeText(ir) }
        return execute(checkNotNull(clang).toString(), "--target=wasm32-wasip1", "--sysroot=${checkNotNull(wasiSysroot)}", "-c", "-O2",
            "-Wno-override-module", source.toString(), "-o", dir.resolve("program.o").toString())
    } finally {
        dir.toFile().deleteRecursively()
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
