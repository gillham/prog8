package prog8.code.target

import prog8.code.source.ImportFileSystem
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile

/**
 * The per-triple settings of an llvm target, read from a `.properties` file or asked from clang. [name] is how it was looked up,
 * so that [LlvmTripleProfile.resolve] finds it again: a triple name, or the path of the file.
 */
data class LlvmTripleProfile(
    val name: String,
    val triple: String,
    val dataLayout: String,
    val cLongSize: Int,
    private val clangArgsTemplate: List<String> = emptyList(),
    val outputExtension: String = "",
    val runCommand: List<String>? = null,
    val strictIndirectSignatures: Boolean = false,
    val cMainSymbol: String = "main",
    private val linkCommandTemplate: List<String>? = null,
    val runTimeoutSeconds: Int? = null,
    val startupSource: String? = null,
    private val directory: Path? = null
) {
    /** The pointer width in bits of the default address space, which LLVM takes as 64 when the data layout has no `p:` entry. */
    val pointerBits: Int = dataLayout.split('-').firstOrNull { it.startsWith("p:") }
        ?.split(':')?.getOrNull(1)?.toIntOrNull() ?: 64

    /**
     * The clang arguments with `${NAME}` (or `${NAME:-default}`) environment variables expanded,
     * done on use so that only a clang build needs them set.
     */
    val clangArgs: List<String>
        get() = expandEnvironment(clangArgsTemplate) { System.getenv(it) }

    /** The command that links clang's object file `{object}` into the executable `{file}`, with environment variables expanded as in [clangArgs]. */
    val linkCommand: List<String>?
        get() = linkCommandTemplate?.let { template -> expandEnvironment(template) { System.getenv(it) } }

    /**
     * Writes the [startupSource] file that the link command builds along as `{startup}` next to [outputFile], and returns it;
     * null without one. It is read from the profile's directory, or from the compiler's resources for an embedded profile.
     */
    fun writeStartupSource(outputFile: Path): Path? {
        val source = startupSource ?: return null
        val file = outputFile.resolveSibling("${outputFile.fileName}.${Path(source).fileName}")
        startupSourceStream(source).use { Files.copy(it, file, StandardCopyOption.REPLACE_EXISTING) }
        return file
    }

    private fun startupSourceStream(source: String): InputStream =
        if (directory != null) directory.resolve(source).inputStream()
        else LlvmTripleProfile::class.java.getResourceAsStream("$RESOURCE_DIR/$source")
            ?: throw IllegalArgumentException("the startup_source '$source' of llvm triple profile '$name' is not embedded in the compiler")

    init {
        require(dataLayout.startsWith("e")) { "the llvm triple profile '$name' must have a little-endian data layout (starting with 'e')" }
        require(dataLayout.split('-').none { it.startsWith("p:") && it.split(':').getOrNull(1)?.toIntOrNull() == null }) {
            "the llvm triple profile '$name' has an invalid pointer entry in its data layout"
        }
        require(cLongSize == 4 || cLongSize == 8) { "the llvm triple profile '$name' has c_long_size $cLongSize, it must be 4 or 8" }
        require(runCommand == null || runCommand.any { "{file}" in it }) { "the run_command of llvm triple profile '$name' must contain {file}" }
        require(linkCommandTemplate == null || (linkCommandTemplate.any { "{object}" in it } && linkCommandTemplate.any { "{file}" in it })) {
            "the link_command of llvm triple profile '$name' must contain {object} and {file}"
        }
        require((startupSource != null) == (linkCommandTemplate?.any { "{startup}" in it } == true)) {
            "llvm triple profile '$name' needs both a startup_source and {startup} in its link_command, or neither"
        }
        require(startupSource == null || (if (directory != null) directory.resolve(startupSource).isRegularFile()
            else LlvmTripleProfile::class.java.getResource("$RESOURCE_DIR/$startupSource") != null)) {
            "the startup_source '$startupSource' of llvm triple profile '$name' was not found"
        }
        require(runTimeoutSeconds == null || runTimeoutSeconds > 0) { "the run_timeout_seconds of llvm triple profile '$name' must be a positive number" }
        require(cMainSymbol.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "the c_main_symbol of llvm triple profile '$name' must be a C identifier" }
    }

    companion object {
        const val RESOURCE_DIR = "/prog8lib/llvm-triples"
        private val knownKeys = setOf("triple", "datalayout", "c_long_size", "clang_args", "output_extension", "run_command", "strict_indirect_signatures", "c_main_symbol",
            "link_command", "run_timeout_seconds", "startup_source")
        private val environmentVariable = Regex($$"""\$\{([A-Za-z_][A-Za-z0-9_]*)(:-([^}]*))?}""")
        private val tripleName = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")
        private const val ARGS = "{args}"
        private const val SEMIHOSTING_ARGS = "{semihosting_args}"

        /**
         * The command that runs [file] with the program arguments [args]: [runCommand] with `{file}` replaced, or else the file itself.
         * A word `{args}` becomes the arguments, and `{semihosting_args}` in a word becomes the `arg=` entries of qemu's
         * `-semihosting-config` for the file and the arguments; without either, the arguments go at the end.
         */
        fun runCommandFor(runCommand: List<String>?, file: String, args: List<String>): List<String> {
            val command = runCommand ?: return listOf(file) + args
            val placed = command.any { it == ARGS || SEMIHOSTING_ARGS in it }
            return command.flatMap { word ->
                when {
                    word == ARGS -> args
                    SEMIHOSTING_ARGS in word -> listOf(word.replace("{file}", file).replace(SEMIHOSTING_ARGS, semihostingArgs(listOf(file) + args)))
                    else -> listOf(word.replace("{file}", file))
                }
            } + if (placed) emptyList() else args
        }

        // the C library splits the semihosting command line at spaces outside double quotes, which it removes
        private fun semihostingArgs(args: List<String>): String = args.joinToString("") {
            require('"' !in it) { "the program argument '$it' has a double quote, which the semihosting command line can't pass on" }
            ",arg=" + (if (it.isEmpty() || it.any(Char::isWhitespace)) "\"$it\"" else it).replace(",", ",,")
        }
        private val clangAnswers = ConcurrentHashMap<List<String>, ClangTargetInfo>()

        fun load(path: Path): LlvmTripleProfile = path.inputStream().use { parse(path.toString(), it, path.toAbsolutePath().parent) }

        /**
         * Finds [nameOrPath] as a file, then as `~/.prog8/llvm/<name>.properties`, then as a profile embedded in the compiler;
         * any other triple name gets the data layout and C long size that clang reports for it.
         */
        fun resolve(nameOrPath: String): LlvmTripleProfile {
            val file = ImportFileSystem.expandTilde(Path(nameOrPath))
            if(file.isRegularFile())
                return load(file)
            val userFile = ImportFileSystem.userHome.resolve(".prog8/llvm/$nameOrPath.properties")
            if(userFile.isRegularFile())
                return userFile.inputStream().use { parse(nameOrPath, it, userFile.parent) }
            val resource = LlvmTripleProfile::class.java.getResourceAsStream("$RESOURCE_DIR/$nameOrPath.properties")
            if(resource != null)
                return resource.use { parse(nameOrPath, it) }
            val notFound = "unknown llvm triple '$nameOrPath': no such file, no $userFile, and no embedded profile"
            if(!nameOrPath.matches(tripleName))
                throw IllegalArgumentException(notFound)
            val info = try {
                askClang(nameOrPath, emptyList())
            } catch(x: IllegalArgumentException) {
                throw IllegalArgumentException("$notFound; ${x.message}")
            }
            return LlvmTripleProfile(nameOrPath, info.triple, info.dataLayout, info.cLongSize, listOf("--target=$nameOrPath"))
        }

        /**
         * The normalized triple, data layout and C long size that clang reports for [triple] with [clangArgs], which can change the ABI.
         * Answers are kept for the life of the process. Throws an IllegalArgumentException when clang is missing or rejects them.
         */
        internal fun askClang(triple: String, clangArgs: List<String>): ClangTargetInfo =
            clangAnswers[listOf(triple) + clangArgs] ?: queryClang(triple, clangArgs).also { clangAnswers[listOf(triple) + clangArgs] = it }

        private fun queryClang(triple: String, clangArgs: List<String>): ClangTargetInfo {
            val clang = LlvmToolLocator.clang()
                ?: throw IllegalArgumentException("clang, which could supply it, was not found on the PATH or in ${LlvmToolLocator.homebrewLlvmBin}")
            val command = listOf(clang.toString(), "--target=$triple") + clangArgs + listOf("-S", "-emit-llvm", "-x", "c", "-", "-o", "-")
            val process = try {
                ProcessBuilder(command).start()
            } catch(x: IOException) {
                throw IllegalArgumentException("clang could not be started: ${x.message}")
            }
            val errors = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().use { it.readText() } }
            val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
            try {
                process.outputStream.bufferedWriter().use { it.write("long prog8_c_long_size = sizeof(long);\n") }
            } catch(_: IOException) {
                // clang already exited, rejecting its arguments; its exit status below says why
            }
            if(!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalArgumentException("clang did not answer within 30 seconds")
            }
            if(process.exitValue() != 0) {
                val reason = errors.join().lines().firstOrNull { "error:" in it }?.substringAfter("error:")?.trim() ?: "it exited with status ${process.exitValue()}"
                throw IllegalArgumentException("clang rejects it: $reason")
            }
            val ir = output.join()
            fun find(regex: String) = Regex(regex, RegexOption.MULTILINE).find(ir)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("clang's output for it has no match for $regex")
            return ClangTargetInfo(
                find("""^target triple = "([^"]*)""""),
                find("""^target datalayout = "([^"]*)""""),
                find("""^@prog8_c_long_size = .*global i\d+ (\d+)""").toInt()
            )
        }

        internal fun expandEnvironment(args: List<String>, env: (String) -> String?): List<String> = args.map { arg ->
            environmentVariable.replace(arg) {
                val variable = it.groupValues[1]
                env(variable) ?: it.groups[3]?.value ?: throw IllegalArgumentException("environment variable $variable used in the llvm triple profile is not set")
            }
        }

        /** [directory] holds the profile file, which a startup_source is relative to; null for an embedded profile. */
        private fun parse(name: String, input: InputStream, directory: Path? = null): LlvmTripleProfile {
            val props = Properties()
            props.load(input)
            val unknown = props.stringPropertyNames() - knownKeys
            require(unknown.isEmpty()) { "unknown keys in llvm triple profile '$name': ${unknown.sorted().joinToString()}" }
            fun required(key: String) = props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("llvm triple profile '$name' is missing '$key'")
            fun words(key: String) = props.getProperty(key)?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
            fun optional(key: String) = props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
            val triple = required("triple")
            val clangArgs = words("clang_args").orEmpty()
            val fromClang by lazy {
                try {
                    askClang(triple, expandEnvironment(clangArgs) { System.getenv(it) })
                } catch(x: IllegalArgumentException) {
                    throw IllegalArgumentException("llvm triple profile '$name' leaves out its datalayout or c_long_size, but ${x.message}")
                }
            }
            val cLongSize = optional("c_long_size")?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("llvm triple profile '$name' has a non-numeric c_long_size")
            } ?: fromClang.cLongSize
            val runTimeout = props.getProperty("run_timeout_seconds")?.trim()?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("llvm triple profile '$name' has a non-numeric run_timeout_seconds")
            }
            return LlvmTripleProfile(
                name,
                triple,
                optional("datalayout") ?: fromClang.dataLayout,
                cLongSize,
                clangArgs,
                props.getProperty("output_extension", "").trim(),
                words("run_command")?.takeIf { it.isNotEmpty() },
                props.getProperty("strict_indirect_signatures", "false").trim().toBoolean(),
                props.getProperty("c_main_symbol", "main").trim(),
                words("link_command")?.takeIf { it.isNotEmpty() },
                runTimeout,
                props.getProperty("startup_source")?.trim()?.takeIf { it.isNotEmpty() },
                directory
            )
        }
    }
}


internal data class ClangTargetInfo(val triple: String, val dataLayout: String, val cLongSize: Int)
