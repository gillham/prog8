package prog8.codegen.llvm

import io.kotest.core.test.Enabled
import io.kotest.core.test.TestCase
import prog8.code.target.LlvmTarget
import prog8.code.target.LlvmToolLocator
import prog8.code.target.LlvmTripleProfile
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import prog8tests.helpers.hostLlvmProfile
import prog8tests.helpers.noHostLlvmProfileReason


// prefer the clang of the same LLVM installation as llvm-as
internal val clang: Path? = llvmAs?.resolveSibling(LlvmToolLocator.executableName("clang"))?.takeIf { Files.isExecutable(it) } ?: LlvmToolchain.find("clang")

internal val requiresNativeHost: (TestCase) -> Enabled = {
    when {
        noHostLlvmProfileReason != null -> Enabled.disabled(noHostLlvmProfileReason!!)
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

/** An llvm64 target with a 64-bit C long, for checks of LLVM IR that shouldn't depend on the host, whose C long may have 32 bits. */
internal val lp64Target: LlvmTarget get() = LlvmTarget(8, LlvmTripleProfile.resolve("x86_64-unknown-linux-gnu"))

internal val armProfile: LlvmTripleProfile = LlvmTripleProfile.resolve("armv7a-none-eabihf")

/** The llvm32 target for 32-bit ARM bare metal, run on qemu through semihosting. */
internal val armTarget: LlvmTarget get() = LlvmTarget(4, armProfile)

internal val requiresArm: (TestCase) -> Enabled = {
    val linker = try { armProfile.linkCommand?.first()?.let { Path.of(LlvmToolLocator.executableName(it)) } } catch (_: IllegalArgumentException) { null } catch (_: InvalidPathException) { null }
    when {
        clang == null -> Enabled.disabled("clang not found next to llvm-as, on the PATH or in ${LlvmToolchain.homebrewLlvmBin}")
        linker == null || !Files.isExecutable(linker) -> Enabled.disabled("arm-none-eabi-gcc of the Arm GNU Toolchain not found in ARM_GNU_TOOLCHAIN or its default location")
        LlvmToolchain.find("qemu-system-arm") == null -> Enabled.disabled("qemu-system-arm not found on the PATH")
        else -> Enabled.enabled
    }
}

/**
 * Builds [ir] into a bare metal ARM program with the clang arguments, link command and console code of the ARM profile, and runs it
 * on qemu with [input] and a Ctrl-D, which ends it, on stdin, the command line [args] and the working [directory];
 * returns the exit code and output of the run, or of the build.
 */
internal fun compileAndRunArm(ir: String, input: String = "", args: List<String> = emptyList(), directory: Path? = null): Pair<Int, String> {
    val dir = createTempDirectory("prog8-llvm-arm")
    try {
        val source = dir.resolve("program.ll").also { it.writeText(ir) }
        val objectFile = dir.resolve("program.o")
        val program = dir.resolve("program.elf")
        val compiled = execute(checkNotNull(clang).toString(), *armProfile.clangArgs.toTypedArray(), "-O2", "-Wno-override-module", "-c",
            source.toString(), "-o", objectFile.toString())
        if (compiled.first != 0)
            return compiled
        val link = LlvmToolchain.linkCommandFor(checkNotNull(armProfile.linkCommand), objectFile, program, armProfile.writeStartupSources(program), emptyList())
        val linked = execute(*link.toTypedArray())
        if (linked.first != 0)
            return linked
        val stdin = dir.resolve("stdin.txt").also { it.writeText(input + END_OF_CONSOLE_INPUT) }
        val command = LlvmTripleProfile.runCommandFor(armProfile.runCommand, program.toAbsolutePath().toString(), args)
        return execute(*command.toTypedArray(), stdin = stdin, directory = directory)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

/** The Ctrl-D that ends the console input of a program on qemu's semihosting, which otherwise waits for more. */
internal const val END_OF_CONSOLE_INPUT = "\u0004"

internal val riscvProfile: LlvmTripleProfile = LlvmTripleProfile.resolve("riscv32-unknown-elf")

/** The llvm32 target for 32-bit RISC-V bare metal, run on qemu through semihosting. */
internal val riscvTarget: LlvmTarget get() = LlvmTarget(4, riscvProfile)

/** riscv-none-elf-gcc from RISCV_NONE_ELF_GCC, the PATH, or the default install location of the xPack toolchain. */
internal val riscvGcc: Path? = System.getenv("RISCV_NONE_ELF_GCC")?.let { try { Path.of(it) } catch (_: InvalidPathException) { null } }
    ?: LlvmToolchain.find("riscv-none-elf-gcc")
    ?: Path.of(System.getProperty("user.home"), "Library/xPacks/@xpack-dev-tools/riscv-none-elf-gcc").toFile().listFiles().orEmpty()
        .sortedDescending().map { it.toPath().resolve(".content/bin/riscv-none-elf-gcc") }.firstOrNull { Files.isExecutable(it) }

internal val requiresRiscv: (TestCase) -> Enabled = {
    when {
        clang == null -> Enabled.disabled("clang not found next to llvm-as, on the PATH or in ${LlvmToolchain.homebrewLlvmBin}")
        riscvGcc == null || !Files.isExecutable(riscvGcc) -> Enabled.disabled("riscv-none-elf-gcc not found in RISCV_NONE_ELF_GCC, on the PATH or in ~/Library/xPacks")
        LlvmToolchain.find("qemu-system-riscv32") == null -> Enabled.disabled("qemu-system-riscv32 not found on the PATH")
        else -> Enabled.enabled
    }
}

/**
 * Builds [ir] into a bare metal RISC-V program with the clang arguments, link command, boot and console code of the RISC-V profile,
 * and runs it on qemu as [compileAndRunArm] does.
 */
internal fun compileAndRunRiscv(ir: String, input: String = "", args: List<String> = emptyList(), directory: Path? = null): Pair<Int, String> {
    val dir = createTempDirectory("prog8-llvm-riscv")
    try {
        val source = dir.resolve("program.ll").also { it.writeText(ir) }
        val objectFile = dir.resolve("program.o")
        val program = dir.resolve("program.elf")
        val compiled = execute(checkNotNull(clang).toString(), *riscvProfile.clangArgs.toTypedArray(), "-O2", "-Wno-override-module", "-c",
            source.toString(), "-o", objectFile.toString())
        if (compiled.first != 0)
            return compiled
        val link = LlvmToolchain.linkCommandFor(listOf(checkNotNull(riscvGcc).toString()) + checkNotNull(riscvProfile.linkCommand).drop(1),
            objectFile, program, riscvProfile.writeStartupSources(program), emptyList())
        val linked = execute(*link.toTypedArray())
        if (linked.first != 0)
            return linked
        val stdin = dir.resolve("stdin.txt").also { it.writeText(input + END_OF_CONSOLE_INPUT) }
        val command = LlvmTripleProfile.runCommandFor(riscvProfile.runCommand, program.toAbsolutePath().toString(), args)
        return execute(*command.toTypedArray(), stdin = stdin, directory = directory)
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
 * Builds [ir], made for [prog8tests.helpers.hostLlvmTarget], into a native executable with clang and the host's profile, and runs it with [input]
 * on stdin and the command line [args]; returns the exit code and output of the run, or of clang if that failed.
 * Optimizes, because that is when LLVM could fold startup stores back into static data.
 */
internal fun compileAndRun(ir: String, input: String = "", args: List<String> = emptyList()): Pair<Int, String> {
    val dir = createTempDirectory("prog8-llvm-native")
    try {
        val source = dir.resolve("program.ll").also { it.writeText(ir) }
        val stdin = dir.resolve("stdin.txt").also { it.writeText(input) }
        val profile = checkNotNull(hostLlvmProfile)
        // built like prog8c builds it, so a profile's startup sources and link command take part
        val executable = LlvmToolchain.executableFor(source, profile.outputExtension)
        if (!LlvmToolchain.compileToExecutable(source, executable, profile))
            return 1 to "building $executable failed, see the messages on stderr"
        return execute(executable.toString(), *args.toTypedArray(), stdin = stdin)
    } finally {
        dir.toFile().deleteRecursively()
    }
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
