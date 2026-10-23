package prog8.codegen.llvm

import prog8.code.core.outputStemOf
import prog8.code.target.ILlvmProgramRunner
import prog8.code.target.LlvmTripleProfile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

internal object LlvmToolchain {
    /** Homebrew installs LLVM keg-only, so its tools are not on the PATH by default. */
    val homebrewLlvmBin: Path = Path.of("/opt/homebrew/opt/llvm/bin")

    fun searchDirs(): List<Path> {
        val pathEntries = orNull { System.getenv("PATH") }.orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
        // not `+`: a Path is also an Iterable of its name elements
        return pathEntries.mapNotNull { orNull { Path.of(it) } }.plusElement(homebrewLlvmBin)
    }

    fun find(tool: String): Path? {
        val isWindows = orNull { System.getProperty("os.name") }.orEmpty().startsWith("Windows")
        val fileName = if (isWindows) "$tool.exe" else tool
        return searchDirs().firstNotNullOfOrNull { dir ->
            orNull { dir.resolve(fileName).takeIf { Files.isRegularFile(it) && Files.isExecutable(it) } }
        }
    }

    /** The clang of the same LLVM installation as llvm-as, which accepts the same IR, rather than an older one earlier on the PATH. */
    fun clang(): Path? {
        val besideLlvmAs = find("llvm-as")?.let { llvmAs ->
            orNull { llvmAs.resolveSibling(executableName("clang")).takeIf { Files.isRegularFile(it) && Files.isExecutable(it) } }
        }
        return besideLlvmAs ?: find("clang")
    }

    /**
     * Builds the executable [outputFile] from [llFile] with clang, passing the clang arguments of [profile] first; with a
     * link command in [profile], clang only compiles an object file that the command links. Messages and any failure are reported on System.err.
     */
    fun compileToExecutable(llFile: Path, outputFile: Path, profile: LlvmTripleProfile? = null, optLevel: String = "-O2"): Boolean {
        val clang = clang()
        if (clang == null) {
            System.err.println("Can't build '$outputFile': clang was not found on the PATH or in $homebrewLlvmBin. " +
                "Install LLVM, for example with 'brew install llvm', or put clang on the PATH.")
            return false
        }
        val (profileArgs, linkCommand) = try {
            profile?.clangArgs.orEmpty() to profile?.linkCommand
        } catch (x: IllegalArgumentException) {
            System.err.println("Can't build '$outputFile': ${x.message}")
            return false
        }
        val compileCommand = listOf(clang.toString()) + profileArgs + listOf(optLevel, "-Wno-override-module")
        if (linkCommand == null)
            return runTool(compileCommand + listOf(llFile.toString(), "-o", outputFile.toString()), "clang", outputFile) ||
                failed("clang couldn't build '$outputFile' from '$llFile'")
        val objectFile = outputFile.resolveSibling("${outputFile.fileName}.o")
        try {
            if (!runTool(compileCommand + listOf("-c", llFile.toString(), "-o", objectFile.toString()), "clang", outputFile))
                return failed("clang couldn't compile '$llFile'")
            val link = linkCommand.map { it.replace("{object}", objectFile.toString()).replace("{file}", outputFile.toString()) }
            return runTool(link, link.first(), outputFile) || failed("the link command couldn't build '$outputFile' from '$objectFile'")
        } finally {
            Files.deleteIfExists(objectFile)
        }
    }

    private fun failed(message: String): Boolean {
        System.err.println(message)
        return false
    }

    /** Runs a build step, printing its output; false when it couldn't start, didn't finish in 5 minutes or failed. */
    private fun runTool(command: List<String>, tool: String, outputFile: Path): Boolean {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (x: IOException) {
            System.err.println("Can't build '$outputFile': $tool could not be started: ${x.message}")
            return false
        }
        // read on another thread, so that the timeout also stops a tool that hangs with its output still open
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            System.err.println("$tool did not finish building '$outputFile' within 5 minutes")
            return false
        }
        val messages = output.join()
        if (messages.isNotEmpty())
            System.err.print(messages)
        return process.exitValue() == 0
    }

    /**
     * Runs [executable] on the console of this process until it exits, and returns its exit code; with a [runCommand],
     * that command runs it instead, with `{file}` replaced by its path. Throws an IOException when it can't be started,
     * or when it is still running after [timeoutSeconds]; it is stopped then.
     */
    fun runExecutable(executable: Path, quiet: Boolean, runCommand: List<String>? = null, timeoutSeconds: Int? = null): Int {
        if (!quiet)
            println("Running $executable...")
        System.out.flush()
        // absolute, because a name without a directory would be looked up on the PATH
        val file = executable.toAbsolutePath().toString()
        val command = runCommand?.map { it.replace("{file}", file) } ?: listOf(file)
        val process = ProcessBuilder(command).inheritIO().start()
        if (timeoutSeconds == null)
            return process.waitFor()
        if (!process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly().waitFor()
            throw IOException("it timed out after $timeoutSeconds seconds and was stopped")
        }
        return process.exitValue()
    }

    /**
     * The LLVM IR file of [programFile]: the file itself when it is a .ll file, else `<stem>.ll` next to that native executable.
     * The extension's case doesn't matter, as `x.LL` and `x.ll` are the same file on a case-insensitive file system.
     */
    fun irFileFor(programFile: Path): Path =
        if (programFile.extension.equals("ll", ignoreCase = true)) programFile else programFile.resolveSibling("${outputStemOf(programFile)}.ll")

    /** The executable built from [llFile]: named with [extension] when given, else the native executable name of this host. */
    fun executableFor(llFile: Path, extension: String = ""): Path =
        llFile.resolveSibling(if (extension.isEmpty()) executableName(llFile.nameWithoutExtension) else llFile.nameWithoutExtension + extension)

    private fun executableName(name: String): String =
        if (orNull { System.getProperty("os.name") }.orEmpty().startsWith("Windows")) "$name.exe" else name

    private inline fun <T> orNull(action: () -> T): T? =
        try {
            action()
        } catch (_: InvalidPathException) {
            null
        } catch (_: SecurityException) {
            null
        }
}


/**
 * Runs llvm program files, natively or with the run command of their triple profile.
 * The llvm targets load this class by name, with its no-argument constructor.
 */
class LlvmProgramRunner : ILlvmProgramRunner {
    override fun buildAndRun(programFile: Path, quiet: Boolean, profile: LlvmTripleProfile?): Int? {
        val executable = executableOf(programFile, quiet, profile) ?: return null
        return try {
            LlvmToolchain.runExecutable(executable, quiet, profile?.runCommand, profile?.runTimeoutSeconds)
        } catch (x: IOException) {
            System.err.println("Can't run '$executable': ${x.message}")
            null
        }
    }

    /** An executable named with -o runs as it is; a missing one, or the one of a .ll file, is built from the LLVM IR first. */
    private fun executableOf(programFile: Path, quiet: Boolean, profile: LlvmTripleProfile?): Path? {
        val irFile = LlvmToolchain.irFileFor(programFile)
        if (programFile != irFile && programFile.exists())
            return programFile
        if (!irFile.exists()) {
            System.err.println("No program file found: $programFile")
            return null
        }
        val executable = if (programFile == irFile) LlvmToolchain.executableFor(irFile, profile?.outputExtension.orEmpty()) else programFile
        if (!quiet)
            println("Building $executable from $irFile with clang...")
        return if (LlvmToolchain.compileToExecutable(irFile, executable, profile)) executable else null
    }
}
