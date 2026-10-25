package prog8.codegen.llvm

import prog8.code.core.outputStemOf
import prog8.code.target.ILlvmProgramRunner
import prog8.code.target.LlvmToolLocator
import prog8.code.target.LlvmTripleProfile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

internal object LlvmToolchain {
    val homebrewLlvmBin: Path get() = LlvmToolLocator.homebrewLlvmBin

    fun searchDirs(): List<Path> = LlvmToolLocator.searchDirs()

    fun find(tool: String): Path? = LlvmToolLocator.find(tool)

    fun clang(): Path? = LlvmToolLocator.clang()

    /**
     * Builds the executable [outputFile] from [llFile] with clang, passing the clang arguments of [profile] first; with a
     * link command in [profile], clang only compiles an object file that the command links, along with the profile's startup sources if it has any.
     * [extraCompileArgs] go last on the clang command, [extraLinkArgs] and then [linkLibraries] last on the command that links, so libraries
     * come after the object file, and the profile's link arguments after those; a link command can place the [linkLibraries] elsewhere with a `{libs}` word.
     * Messages and any failure are reported on System.err.
     */
    fun compileToExecutable(llFile: Path, outputFile: Path, profile: LlvmTripleProfile? = null, optLevel: String = "-O2",
                            extraCompileArgs: List<String> = emptyList(), extraLinkArgs: List<String> = emptyList(),
                            linkLibraries: List<String> = emptyList()): Boolean {
        val clang = clang()
        if (clang == null) {
            System.err.println("Can't build '$outputFile': clang was not found on the PATH or in $homebrewLlvmBin. " +
                "Install LLVM, for example with 'brew install llvm', or put clang on the PATH.")
            return false
        }
        val (profileArgs, linkCommand, profileLinkArgs) = try {
            Triple(profile?.clangArgs.orEmpty(), profile?.linkCommand, profile?.linkArgs.orEmpty())
        } catch (x: IllegalArgumentException) {
            System.err.println("Can't build '$outputFile': ${x.message}")
            return false
        }
        val compileCommand = compileCommand(clang.toString(), profileArgs, optLevel, extraCompileArgs)
        if (linkCommand == null)
            return runTool(clangLinkCommand(compileCommand, llFile, outputFile, extraLinkArgs + linkLibraries + profileLinkArgs), "clang", outputFile) ||
                failed("clang couldn't build '$outputFile' from '$llFile'")
        val objectFile = outputFile.resolveSibling("${outputFile.fileName}.o")
        var startupFiles = emptyList<Path>()
        try {
            if (!runTool(compileCommand + listOf("-c", llFile.toString(), "-o", objectFile.toString()), "clang", outputFile))
                return failed("clang couldn't compile '$llFile'")
            startupFiles = try {
                profile?.writeStartupSources(outputFile).orEmpty()
            } catch (x: IOException) {
                return failed("Can't build '$outputFile': a startup source of the llvm triple profile couldn't be written: ${x.message}")
            }
            val link = linkCommandFor(linkCommand, objectFile, outputFile, startupFiles, extraLinkArgs, linkLibraries)
            return runTool(link, link.first(), outputFile) || failed("the link command couldn't build '$outputFile' from '$objectFile'")
        } finally {
            Files.deleteIfExists(objectFile)
            startupFiles.forEach { Files.deleteIfExists(it) }
        }
    }

    internal fun compileCommand(clang: String, profileArgs: List<String>, optLevel: String, extraCompileArgs: List<String>): List<String> =
        listOf(clang) + profileArgs + listOf(optLevel, "-Wno-override-module") + extraCompileArgs

    internal fun clangLinkCommand(compileCommand: List<String>, llFile: Path, outputFile: Path, extraLinkArgs: List<String>): List<String> =
        compileCommand + listOf(llFile.toString(), "-o", outputFile.toString()) + extraLinkArgs

    internal fun linkCommandFor(template: List<String>, objectFile: Path, outputFile: Path, startupFiles: List<Path>, extraLinkArgs: List<String>,
                                linkLibraries: List<String> = emptyList()): List<String> {
        val command = template.flatMap {
            when (it) {
                LIBS -> linkLibraries
                STARTUP -> startupFiles.map(Path::toString)
                else -> listOf(it.replace("{object}", objectFile.toString()).replace("{file}", outputFile.toString())
                    .replace(STARTUP, startupFiles.joinToString(" ")))
            }
        } + extraLinkArgs
        return if (LIBS in template) command else command + linkLibraries
    }

    private const val LIBS = "{libs}"
    private const val STARTUP = "{startup}"

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
     * Runs [executable] with the program arguments [args] on the console of this process until it exits, and returns its exit code;
     * with a [runCommand], that command runs it instead (see [LlvmTripleProfile.runCommandFor]). Throws an IOException when it
     * can't be started, or when it is still running after [timeoutSeconds]; it is stopped then.
     */
    fun runExecutable(executable: Path, quiet: Boolean, runCommand: List<String>? = null, timeoutSeconds: Int? = null, args: List<String> = emptyList()): Int {
        if (!quiet)
            println("Running $executable...")
        System.out.flush()
        // absolute, because a name without a directory would be looked up on the PATH
        val file = executable.toAbsolutePath().toString()
        val command = LlvmTripleProfile.runCommandFor(runCommand, file, args)
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
        llFile.resolveSibling(if (extension.isEmpty()) LlvmToolLocator.executableName(llFile.nameWithoutExtension) else llFile.nameWithoutExtension + extension)
}


/**
 * Runs llvm program files, natively or with the run command of their triple profile.
 * The llvm targets load this class by name, with its no-argument constructor.
 */
class LlvmProgramRunner : ILlvmProgramRunner {
    override fun buildAndRun(programFile: Path, quiet: Boolean, profile: LlvmTripleProfile?,
                             extraCompileArgs: List<String>, extraLinkArgs: List<String>, programArgs: List<String>, linkLibraries: List<String>): Int? {
        val executable = executableOf(programFile, quiet, profile, extraCompileArgs, extraLinkArgs, linkLibraries) ?: return null
        return try {
            LlvmToolchain.runExecutable(executable, quiet, profile?.runCommand, profile?.runTimeoutSeconds, programArgs)
        } catch (x: IOException) {
            System.err.println("Can't run '$executable': ${x.message}")
            null
        } catch (x: IllegalArgumentException) {
            System.err.println("Can't run '$executable': ${x.message}")
            null
        }
    }

    /** An executable named with -o runs as it is; a missing one, or the one of a .ll file, is built from the LLVM IR first. */
    private fun executableOf(programFile: Path, quiet: Boolean, profile: LlvmTripleProfile?,
                             extraCompileArgs: List<String>, extraLinkArgs: List<String>, linkLibraries: List<String>): Path? {
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
        return if (LlvmToolchain.compileToExecutable(irFile, executable, profile, extraCompileArgs = extraCompileArgs, extraLinkArgs = extraLinkArgs, linkLibraries = linkLibraries)) executable else null
    }
}
