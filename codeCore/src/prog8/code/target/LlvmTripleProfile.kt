package prog8.code.target

import prog8.code.source.ImportFileSystem
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
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
    private val runCommandTemplate: List<String>? = null,
    val strictIndirectSignatures: Boolean = false,
    val cMainSymbol: String = "main",
    private val linkCommandTemplate: List<String>? = null,
    val runTimeoutSeconds: Int? = null,
    val startupSources: List<String> = emptyList(),
    private val directory: Path? = null,
    private val linkArgsTemplate: List<String> = emptyList(),
    /** Where readdir()'s struct dirent has d_name, or null when the C library has no directory routines. */
    val direntNameOffset: Int? = null,
    val opendirSymbol: String = "opendir",
    val readdirSymbol: String = "readdir",
    /** The C routines that seek and tell the file position, whose offset has [fileOffsetSize] bytes, like _fseeki64 on Windows. */
    val fseekSymbol: String = "fseek",
    val ftellSymbol: String = "ftell",
    /** Other C functions of [C_FUNCTIONS] by another name, such as replacements in a support source for broken ones. */
    val cSymbols: Map<String, String> = emptyMap(),
    /** A C routine `void f(void)` that sys.waitvsync() calls, such as a raster wait; without it, it waits one 60th of a second. */
    val waitvsyncSymbol: String? = null,
    val fileOffsetSize: Int = cLongSize,
    val cIntSize: Int = 4,
    private val clangTemplate: String? = null,
    /** The C functions of [C_FUNCTIONS] that the target's C library doesn't have. */
    val libcLacks: Set<String> = emptySet(),
    /** The memory-mapped I/O addresses; [ioAllAddresses] makes every constant address one. */
    val ioRanges: List<UIntRange> = emptyList(),
    val ioAllAddresses: Boolean = false,
    /** False when the program can't be given command line arguments. */
    val programArgs: Boolean = true,
    val tools: LlvmToolEnvironment = LlvmToolEnvironment(name),
    /** The C routine that reads the next console character as getchar() does, like fgetc_cons in z88dk, where getchar is a macro. */
    val getcharSymbol: String = "getchar",
    /** The C routine that writes a console character as putchar() does, declared as `int f(int)`, like fputc_cons in z88dk. */
    val putcharSymbol: String = "putchar",
    /** The family directory of the target's embedded library that the importer searches before the target's own. */
    val library: String? = null,
    /** The character that replaces the last `.` of a file name the C library gets, like `/` for TRSDOS's NAME/EXT; null keeps the name. */
    val fileExtensionSeparator: Char? = null,
    /** The default load address of a profile that can load a program anywhere (`%address`, `-address`), a multiple of [loadAddressAlign]. */
    val loadAddress: UInt? = null,
    val loadAddressAlign: UInt = 1u,
    /** A linker script template, read from [linkScriptDirectory] or the compiler's resources, written for the link as `{link_script}`. */
    private val linkScript: String? = null,
    private val linkScriptDirectory: Path? = null,
    /** Support sources like [startupSources], but only built along when the program opens files (calls fopen). */
    val fileSources: List<String> = emptyList(),
    private val fileSourceDirectory: Path? = null
) {
    /** The symbol that the C function of [C_FUNCTIONS] named [function] is called by, which the profile can change. */
    fun cSymbol(function: String): String = when (function) {
        "fseek" -> fseekSymbol
        "ftell" -> ftellSymbol
        else -> cSymbols[function] ?: function
    }

    /** True when the data layout starts with `E`. */
    val isBigEndian: Boolean get() = dataLayout.startsWith("E")

    /** The pointer width in bits of the default address space, which LLVM takes as 64 when the data layout has no `p:` entry. */
    val pointerBits: Int = dataLayout.split('-').firstOrNull { it.startsWith("p:") }
        ?.split(':')?.getOrNull(1)?.toIntOrNull() ?: 64

    /** The ABI alignment in bytes of a pointer in the default address space, its size unless the data layout's `p:` entry says less (`p:16:8`). */
    val pointerAlignment: Int = (dataLayout.split('-').firstOrNull { it.startsWith("p:") }
        ?.split(':')?.getOrNull(2)?.toIntOrNull() ?: pointerBits) / 8

    /**
     * The clang arguments with `${NAME}` (or `${NAME:-default}`) environment variables expanded,
     * done on use so that only a clang build needs them set.
     */
    val clangArgs: List<String>
        get() = expandEnvironment(clangArgsTemplate) { System.getenv(it) }

    /** The arguments at the end of the clang command that links, after the program's libraries, such as -lm; expanded as [clangArgs]. */
    val linkArgs: List<String>
        get() = expandEnvironment(linkArgsTemplate) { System.getenv(it) }

    /** The command that links clang's object file `{object}` into the executable `{file}` (`{stem}` without its extension), with environment variables expanded as in [clangArgs]. */
    val linkCommand: List<String>?
        get() = linkCommandTemplate?.let { template -> expandEnvironment(template) { System.getenv(it) } }

    /** The command that runs the program, with environment variables expanded as in [clangArgs]. */
    val runCommand: List<String>?
        get() = runCommandTemplate?.let { template -> expandEnvironment(template) { System.getenv(it) } }

    /** The clang this profile names, a name searched for or a path, with environment variables expanded as in [clangArgs]. */
    val clang: String?
        get() = clangTemplate?.let {
            try {
                expandEnvironment(listOf(it)) { variable -> System.getenv(variable) }.single()
            } catch (x: IllegalArgumentException) {
                throw IllegalArgumentException("can't find the clang of llvm triple profile '$name': ${x.message}")
            }
        }

    /** The clang that builds for this profile: [clangOption] (the -clang option), else [clang], else the default one; see [LlvmToolLocator.resolveClang]. */
    fun resolveClang(clangOption: String?): Path? = LlvmToolLocator.resolveClang(clangOption, clang, name, tools)

    /** A ProcessBuilder for [command] with the [tools] environment of this profile; see [LlvmToolEnvironment.processBuilder]. */
    fun processBuilder(command: List<String>): ProcessBuilder = tools.processBuilder(command)

    /**
     * Writes the [startupSources] files that the link command builds along as `{startup}` next to [outputFile], and the
     * [fileSources] too when [withFileSources], and returns them. They are read from the profile's directory, or else from the compiler's resources.
     */
    fun writeStartupSources(outputFile: Path, withFileSources: Boolean = false): List<Path> {
        val written = mutableListOf<Path>()
        val sources = startupSources.map { it to directory } + (if (withFileSources) fileSources.map { it to fileSourceDirectory } else emptyList())
        try {
            for ((source, sourceDirectory) in sources) {
                val file = outputFile.resolveSibling("${outputFile.fileName}.${Path(source).fileName}")
                (openSupportFile(sourceDirectory, source)
                    ?: throw IllegalArgumentException("the support source '$source' of llvm triple profile '$name' was not found")
                ).use { Files.copy(it, file, StandardCopyOption.REPLACE_EXISTING) }
                written.add(file)
            }
        } catch (x: Exception) {
            written.forEach { Files.deleteIfExists(it) }
            throw x
        }
        return written
    }

    /** A file next to a profile file, or else one built into the compiler, so a profile file can name the built-in ones. */
    private fun openSupportFile(directory: Path?, file: String): InputStream? =
        directory?.resolve(file)?.takeIf { it.isRegularFile() }?.inputStream()
            ?: LlvmTripleProfile::class.java.getResourceAsStream("$RESOURCE_DIR/$file")

    private fun supportFileExists(directory: Path?, file: String): Boolean =
        directory?.resolve(file)?.isRegularFile() == true || LlvmTripleProfile::class.java.getResource("$RESOURCE_DIR/$file") != null

    /** Writes the [linkScript] next to [outputFile] with `{load_address}` replaced by [loadAddress], and returns it; null without one. */
    fun writeLinkScript(outputFile: Path, loadAddress: UInt): Path? {
        val script = linkScript ?: return null
        val template = (openSupportFile(linkScriptDirectory, script)
            ?: throw IllegalArgumentException("the link_script '$script' of llvm triple profile '$name' was not found")
            ).use { it.readBytes().decodeToString() }
        val file = outputFile.resolveSibling("${outputFile.fileName}.${Path(script).fileName}")
        Files.writeString(file, template.replace(LOAD_ADDRESS, "0x%04x".format(loadAddress.toLong())))
        return file
    }

    init {
        require(dataLayout.startsWith("e") || dataLayout.startsWith("E")) {
            "the data layout of llvm triple profile '$name' must start with 'e' (little-endian) or 'E' (big-endian)"
        }
        require(dataLayout.split('-').none { it.startsWith("p:") && it.split(':').getOrNull(1)?.toIntOrNull() == null }) {
            "the llvm triple profile '$name' has an invalid pointer entry in its data layout"
        }
        require(cLongSize == 4 || cLongSize == 8) { "the llvm triple profile '$name' has c_long_size $cLongSize, it must be 4 or 8" }
        require(runCommandTemplate == null || runCommandTemplate.any { "{file}" in it }) { "the run_command of llvm triple profile '$name' must contain {file}" }
        require(linkCommandTemplate == null || (linkCommandTemplate.any { "{object}" in it } && linkCommandTemplate.any { "{file}" in it || "{stem}" in it })) {
            "the link_command of llvm triple profile '$name' must contain {object}, and {file} or {stem}"
        }
        require(startupSources.isEmpty() || linkCommandTemplate?.any { "{startup}" in it } == true) {
            "llvm triple profile '$name' has a startup_source, so it needs {startup} in its link_command"
        }
        require(fileSources.isEmpty() || linkCommandTemplate?.any { "{startup}" in it } == true) {
            "llvm triple profile '$name' has a file_source, so it needs {startup} in its link_command"
        }
        for (source in startupSources)
            require(supportFileExists(directory, source)) {
                "the startup_source '$source' of llvm triple profile '$name' was not found"
            }
        for (source in fileSources)
            require(supportFileExists(fileSourceDirectory, source)) {
                "the file_source '$source' of llvm triple profile '$name' was not found"
            }
        require(linkScript == null || linkCommandTemplate?.any { LINK_SCRIPT in it } == true) {
            "llvm triple profile '$name' has a link_script, so it needs {link_script} in its link_command"
        }
        require(linkScript == null || supportFileExists(linkScriptDirectory, linkScript)) {
            "the link_script '$linkScript' of llvm triple profile '$name' was not found"
        }
        require(loadAddress != null || (linkScript == null && linkCommandTemplate?.none { LOAD_ADDRESS in it } != false)) {
            "llvm triple profile '$name' uses {load_address} or a link_script, so it needs a load_address"
        }
        require(loadAddressAlign > 0u && (loadAddress ?: 0u) % loadAddressAlign == 0u) {
            "the load_address of llvm triple profile '$name' must be a multiple of its load_address_align, which must be positive"
        }
        require(linkArgsTemplate.isEmpty() || linkCommandTemplate == null) {
            "llvm triple profile '$name' has a link_command, which takes the arguments of its link_args itself"
        }
        require(runTimeoutSeconds == null || runTimeoutSeconds > 0) { "the run_timeout_seconds of llvm triple profile '$name' must be a positive number" }
        require(cMainSymbol.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "the c_main_symbol of llvm triple profile '$name' must be a C identifier" }
        require(waitvsyncSymbol == null || waitvsyncSymbol.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "the waitvsync_symbol of llvm triple profile '$name' must be a C identifier" }
        require(direntNameOffset == null || direntNameOffset >= 0) { "the dirent_name_offset of llvm triple profile '$name' must not be negative" }
        require(fileOffsetSize == 4 || fileOffsetSize == 8) { "the llvm triple profile '$name' has file_offset_size $fileOffsetSize, it must be 4 or 8" }
        require(cIntSize == 2 || cIntSize == 4) { "the llvm triple profile '$name' has c_int_size $cIntSize, it must be 2 or 4" }
        require(clangTemplate == null || clangTemplate.isNotBlank()) { "the clang of llvm triple profile '$name' is empty" }
        (libcLacks - C_FUNCTIONS).let { unknown ->
            require(unknown.isEmpty()) { "the libc_lacks of llvm triple profile '$name' has names of C functions that Prog8 doesn't call: ${unknown.sorted().joinToString()}" }
        }
        (cSymbols.keys - C_FUNCTIONS).let { unknown ->
            require(unknown.isEmpty()) { "the c_symbols of llvm triple profile '$name' has names of C functions that Prog8 doesn't call: ${unknown.sorted().joinToString()}" }
        }
        (cSymbols.keys intersect OWN_SYMBOL_KEYS.keys).let { named ->
            require(named.isEmpty()) {
                "the c_symbols of llvm triple profile '$name' can't rename ${named.sorted().joinToString()}: use ${named.sorted().joinToString { OWN_SYMBOL_KEYS.getValue(it) }}"
            }
        }
        require(ioRanges.none { it.isEmpty() }) { "the io_ranges of llvm triple profile '$name' has a range that ends before it starts" }
        require(library == null || library.matches(Regex("[A-Za-z0-9_-]+"))) {
            "the library of llvm triple profile '$name' must be a plain directory name of letters, digits, '_' and '-'"
        }
        require(fileExtensionSeparator == null || (fileExtensionSeparator in ' '..'~' && fileExtensionSeparator != '.')) {
            "the file_extension_separator of llvm triple profile '$name' must be a printable ASCII character other than '.'"
        }
    }

    companion object {
        const val RESOURCE_DIR = "/prog8lib/llvm-triples"
        const val LOAD_ADDRESS = "{load_address}"
        const val LINK_SCRIPT = "{link_script}"
        private val knownKeys = setOf("triple", "datalayout", "c_long_size", "clang_args", "output_extension", "run_command", "strict_indirect_signatures", "c_main_symbol",
            "link_command", "link_args", "run_timeout_seconds", "startup_source", "file_source",
            "dirent_name_offset", "opendir_symbol", "readdir_symbol", "fseek_symbol", "ftell_symbol", "c_symbols", "waitvsync_symbol", "file_offset_size", "getchar_symbol",
            "putchar_symbol", "library", "file_extension_separator", "c_int_size", "clang", "libc_lacks", "io_ranges", "program_args", "tool_path", "tool_env",
            "load_address", "load_address_align", "link_script")

        /** A ProcessBuilder for [command] with the tool environment of [profile], or a plain one without a profile. */
        fun processBuilder(profile: LlvmTripleProfile?, command: List<String>): ProcessBuilder =
            profile?.processBuilder(command) ?: ProcessBuilder(command)

        /** The C library functions that the llvm code generator can call, which libc_lacks can name. */
        /** The C functions that have a profile key of their own for their symbol. */
        private val OWN_SYMBOL_KEYS = mapOf("fseek" to "fseek_symbol", "ftell" to "ftell_symbol", "opendir" to "opendir_symbol",
            "readdir" to "readdir_symbol", "getchar" to "getchar_symbol", "putchar" to "putchar_symbol")

        val C_FUNCTIONS: Set<String> = buildSet {
            addAll(listOf("fopen", "fclose", "fgetc", "fputc", "fread", "fwrite", "fseek", "ftell", "ferror", "feof", "fflush", "remove", "rename",
                "getcwd", "mkdir", "chdir", "rmdir", "opendir", "readdir", "closedir", "strcmp", "strdup", "realloc", "free", "qsort",
                "putchar", "getchar", "exit", "usleep", "strtod", "snprintf", "printf"))
            for (math in listOf("sin", "cos", "tan", "atan", "atan2", "sqrt", "pow", "log", "log2", "fabs", "floor", "ceil", "roundeven")) {
                add(math)
                add(math + "f")
            }
        }
        private val environmentVariable = Regex($$"""\$\{([A-Za-z_][A-Za-z0-9_]*)(:-([^}]*))?}""")
        private val tripleName = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")
        private const val ARGS = "{args}"
        private const val SEMIHOSTING_ARGS = "{semihosting_args}"
        private const val PROFILE_DIR = "{profile_dir}"

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
        private val userProfiles: Path get() = ImportFileSystem.userHome.resolve(".prog8/llvm")
        private val clangAnswers = ConcurrentHashMap<List<Any>, ClangTargetInfo>()

        /** [clangOption] is the -clang option, which answers for a profile that leaves out what clang can supply. */
        fun load(path: Path, clangOption: String? = null): LlvmTripleProfile = load(path, clangOption, userProfiles)

        internal fun load(path: Path, clangOption: String?, userDirectory: Path): LlvmTripleProfile =
            parse(path.toString(), readLayer(path, path.toString()), clangOption, userDirectory)

        /**
         * Finds [nameOrPath] as a file, then as `~/.prog8/llvm/<name>.properties`, then as a profile embedded in the compiler;
         * any other triple name gets the data layout and C long size that clang reports for it.
         */
        fun resolve(nameOrPath: String, clangOption: String? = null): LlvmTripleProfile = resolve(nameOrPath, clangOption, userProfiles)

        /** [userDirectory] takes the place of `~/.prog8/llvm`. */
        internal fun resolve(nameOrPath: String, clangOption: String?, userDirectory: Path): LlvmTripleProfile {
            val file = ImportFileSystem.expandTilde(Path(nameOrPath))
            if(file.isRegularFile())
                return load(file, clangOption, userDirectory)
            val userFile = userDirectory.resolve("$nameOrPath.properties")
            if(userFile.isRegularFile())
                return parse(nameOrPath, readLayer(userFile, nameOrPath), clangOption, userDirectory)
            val embedded = embeddedLayer(nameOrPath)
            if(embedded != null)
                return parse(nameOrPath, embedded, clangOption, userDirectory)
            val notFound = "unknown llvm triple '$nameOrPath': no such file, no $userFile, and no embedded profile"
            if(!nameOrPath.matches(tripleName))
                throw IllegalArgumentException(notFound)
            val info = try {
                askClang(nameOrPath, emptyList(), LlvmToolLocator.resolveClang(clangOption, null, null))
            } catch(x: IllegalArgumentException) {
                throw IllegalArgumentException("$notFound; ${x.message}")
            }
            return LlvmTripleProfile(nameOrPath, info.triple, info.dataLayout, info.cLongSize, listOf("--target=$nameOrPath"), cIntSize = info.cIntSize)
        }

        /**
         * The normalized triple, data layout and C long and int sizes that [clang], or else the default clang, reports for [triple]
         * with [clangArgs], which can change the ABI, run in the [tools] environment. Answers are kept for the life of the process.
         * Throws an IllegalArgumentException when clang is missing or rejects them.
         */
        internal fun askClang(triple: String, clangArgs: List<String>, clang: Path? = null, tools: LlvmToolEnvironment = LlvmToolEnvironment(null)): ClangTargetInfo {
            val found = clang ?: LlvmToolLocator.clang()
                ?: throw IllegalArgumentException("clang, which could supply it, was not found on the PATH or in ${LlvmToolLocator.homebrewLlvmBin}")
            val key = listOf<Any>(found.toString(), triple, tools.withoutName()) + clangArgs
            return clangAnswers[key] ?: queryClang(found, triple, clangArgs, tools).also { clangAnswers[key] = it }
        }

        private fun queryClang(clang: Path, triple: String, clangArgs: List<String>, tools: LlvmToolEnvironment): ClangTargetInfo {
            val command = listOf(clang.toString(), "--target=$triple") + clangArgs + listOf("-S", "-emit-llvm", "-x", "c", "-", "-o", "-")
            val process = try {
                tools.processBuilder(command).start()
            } catch(x: IOException) {
                throw IllegalArgumentException("clang could not be started: ${x.message}")
            }
            val errors = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().use { it.readText() } }
            val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
            try {
                process.outputStream.bufferedWriter().use { it.write("long prog8_c_long_size = sizeof(long);\nint prog8_c_int_size = sizeof(int);\n") }
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
                find("""^@prog8_c_long_size = .*global i\d+ (\d+)""").toInt(),
                find("""^@prog8_c_int_size = .*global i\d+ (\d+)""").toInt()
            )
        }

        internal fun expandEnvironment(args: List<String>, env: (String) -> String?): List<String> = expandEnvironment(args, "the llvm triple profile", env)

        internal fun expandEnvironment(args: List<String>, where: String, env: (String) -> String?): List<String> = args.map { arg ->
            environmentVariable.replace(arg) {
                val variable = it.groupValues[1]
                env(variable) ?: it.groups[3]?.value ?: throw IllegalArgumentException("environment variable $variable used in $where is not set")
            }
        }

        private fun readLayer(file: Path, label: String): ProfileLayer {
            val absolute = file.toAbsolutePath().normalize()
            val props = Properties()
            absolute.inputStream().use { props.load(it) }
            return ProfileLayer(absolute.toString(), label, props, absolute.parent)
        }

        private fun embeddedLayer(name: String): ProfileLayer? =
            LlvmTripleProfile::class.java.getResourceAsStream("$RESOURCE_DIR/$name.properties")?.use { embeddedLayer(name, it) }

        private fun embeddedLayer(name: String, input: InputStream) = ProfileLayer("embedded:$name", name, Properties().apply { load(input) }, null)

        /** Parses [input] as if it were the profile [name] embedded in the compiler. */
        internal fun parseAsEmbedded(name: String, input: InputStream): LlvmTripleProfile = parse(name, embeddedLayer(name, input), null, userProfiles)

        /** A file profile's base is a path relative to it, then a user profile, then an embedded one; an embedded profile's base can only be embedded. */
        private fun findBase(base: String, child: ProfileLayer, userDirectory: Path): ProfileLayer {
            val where = if (child.directory != null) {
                val file = try { child.directory.resolve(ImportFileSystem.expandTilde(Path(base))) } catch (_: InvalidPathException) { null }
                if (file?.isRegularFile() == true)
                    return readLayer(file, file.toString())
                val userFile = userDirectory.resolve("${if (base.matches(tripleName)) base else "<name>"}.properties")
                if (base.matches(tripleName) && userFile.isRegularFile())
                    return readLayer(userFile, base)
                "no such file next to it, no $userFile, and no embedded profile"
            } else "no embedded profile has that name, and a profile built into the compiler can only have an embedded base"
            return (if (base.matches(tripleName)) embeddedLayer(base) else null)
                ?: throw IllegalArgumentException("the base '$base' of llvm triple profile '${child.label}' was not found: $where")
        }

        private fun layersOf(profile: ProfileLayer, userDirectory: Path): List<ProfileLayer> {
            val layers = mutableListOf(profile)
            while (true) {
                val base = layers.last().base ?: return layers
                val next = findBase(base, layers.last(), userDirectory)
                require(layers.none { it.id == next.id }) {
                    "llvm triple profile '${profile.label}' has a cycle of bases: ${(layers + next).joinToString(" -> ") { it.label }}"
                }
                layers.add(next)
            }
        }

        private fun parse(name: String, profile: ProfileLayer, clangOption: String?, userDirectory: Path): LlvmTripleProfile {
            require(!profile.isAbstract) { "llvm triple profile '$name' is abstract: it can only be the base of other profiles" }
            val props = Properties()
            val supplier = mutableMapOf<String, ProfileLayer>()
            for (layer in layersOf(profile, userDirectory).asReversed())
                for (key in layer.properties.stringPropertyNames() - setOf(ProfileLayer.BASE, ProfileLayer.ABSTRACT)) {
                    props.setProperty(key, layer.properties.getProperty(key))
                    supplier[key] = layer
                }
            val unknown = props.stringPropertyNames() - knownKeys
            require(unknown.isEmpty()) { "unknown keys in llvm triple profile '$name': ${unknown.sorted().joinToString()}" }
            fun required(key: String) = props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("llvm triple profile '$name' is missing '$key'")
            fun words(key: String) = props.getProperty(key)?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
            fun optional(key: String) = props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
            val triple = required("triple")
            val clangArgs = words("clang_args").orEmpty()
            val clang = optional("clang")
            val tools = LlvmToolEnvironment(name, words("tool_path").orEmpty(), words("tool_env").orEmpty().map {
                val separator = it.indexOf('=')
                require(separator > 0) { "the tool_env of llvm triple profile '$name' has '$it', which is not NAME=value" }
                it.substring(0, separator) to it.substring(separator + 1)
            })
            val asksClang = optional("datalayout") == null || optional("c_long_size") == null
            val fromClang by lazy {
                try {
                    val profileClang = clang?.let { expandEnvironment(listOf(it)) { variable -> System.getenv(variable) }.single() }
                    askClang(triple, expandEnvironment(clangArgs) { System.getenv(it) }, LlvmToolLocator.resolveClang(clangOption, profileClang, name, tools), tools)
                } catch(x: IllegalArgumentException) {
                    throw IllegalArgumentException("llvm triple profile '$name' leaves out its datalayout or c_long_size, but ${x.message}")
                }
            }
            val cLongSize = optional("c_long_size")?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("llvm triple profile '$name' has a non-numeric c_long_size")
            } ?: fromClang.cLongSize
            val direntNameOffset = optional("dirent_name_offset")?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("llvm triple profile '$name' has a non-numeric dirent_name_offset")
            }
            val fileOffsetSize = optional("file_offset_size")?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("llvm triple profile '$name' has a non-numeric file_offset_size")
            } ?: cLongSize
            val cIntSize = optional("c_int_size")?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("llvm triple profile '$name' has a non-numeric c_int_size")
            } ?: if (asksClang) fromClang.cIntSize else 4
            val ioRanges = optional("io_ranges")
            val programArgs = when (val value = optional("program_args")) {
                null, "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("llvm triple profile '$name' has program_args '$value', it must be true or false")
            }
            val fileExtensionSeparator = optional("file_extension_separator")?.let {
                it.singleOrNull() ?: throw IllegalArgumentException("the file_extension_separator of llvm triple profile '$name' must be a single character, not '$it'")
            }
            val runTimeout = props.getProperty("run_timeout_seconds")?.trim()?.let {
                it.toIntOrNull() ?: throw IllegalArgumentException("llvm triple profile '$name' has a non-numeric run_timeout_seconds")
            }
            fun command(key: String) = words(key)?.takeIf { it.isNotEmpty() }?.map { word ->
                if (PROFILE_DIR !in word) word
                else {
                    val layer = supplier.getValue(key)
                    val dir = layer.directory ?: throw IllegalArgumentException(
                        "the $key of llvm triple profile '${layer.label}' uses {profile_dir}, which can't be used in a profile built into the compiler")
                    word.replace(PROFILE_DIR, dir.toString())
                }
            }
            return LlvmTripleProfile(
                name,
                triple,
                optional("datalayout") ?: fromClang.dataLayout,
                cLongSize,
                clangArgs,
                props.getProperty("output_extension", "").trim(),
                command("run_command"),
                props.getProperty("strict_indirect_signatures", "false").trim().toBoolean(),
                props.getProperty("c_main_symbol", "main").trim(),
                command("link_command"),
                runTimeout,
                words("startup_source").orEmpty(),
                (supplier["startup_source"] ?: profile).directory,
                words("link_args").orEmpty(),
                direntNameOffset,
                optional("opendir_symbol") ?: "opendir",
                optional("readdir_symbol") ?: "readdir",
                optional("fseek_symbol") ?: "fseek",
                optional("ftell_symbol") ?: "ftell",
                words("c_symbols").orEmpty().associate { pair ->
                    val (function, symbol) = pair.split('=', limit = 2).takeIf { it.size == 2 && it.all(String::isNotEmpty) }
                        ?: throw IllegalArgumentException("the c_symbols of llvm triple profile '$name' must be name=symbol pairs: '$pair'")
                    function to symbol
                },
                optional("waitvsync_symbol"),
                fileOffsetSize,
                cIntSize,
                clang,
                words("libc_lacks").orEmpty().toSet(),
                if (ioRanges == null || ioRanges == "*") emptyList() else parseAddressRanges(name, ioRanges),
                ioRanges == "*",
                programArgs,
                tools,
                optional("getchar_symbol") ?: "getchar",
                optional("putchar_symbol") ?: "putchar",
                optional("library"),
                fileExtensionSeparator,
                optional("load_address")?.let { parseAddress(name, "load_address", it) },
                optional("load_address_align")?.let { parseAddress(name, "load_address_align", it) } ?: 1u,
                optional("link_script"),
                supplier["link_script"]?.directory,
                words("file_source").orEmpty(),
                supplier["file_source"]?.directory
            )
        }

        private fun parseAddress(name: String, key: String, text: String): UInt {
            val trimmed = text.trim()
            return when {
                trimmed.startsWith("$") -> trimmed.drop(1).toUIntOrNull(16)
                trimmed.startsWith("0x") -> trimmed.drop(2).toUIntOrNull(16)
                else -> trimmed.toUIntOrNull()
            } ?: throw IllegalArgumentException("the $key of llvm triple profile '$name' has an invalid address '$trimmed'")
        }

        private fun parseAddressRanges(name: String, ranges: String): List<UIntRange> = ranges.split(',').map { range ->
            fun address(text: String): UInt = parseAddress(name, "io_ranges", text)
            val parts = range.split('-')
            when (parts.size) {
                1 -> address(parts[0]).let { it..it }
                2 -> address(parts[0])..address(parts[1])
                else -> throw IllegalArgumentException("the io_ranges of llvm triple profile '$name' has an invalid range '${range.trim()}'")
            }
        }
    }
}


/**
 * The `tool_path` directories and `tool_env` variables of llvm triple profile [profileName], which every process started for it gets.
 * Environment variables in them are expanded on use, as in the profile's clang_args.
 */
data class LlvmToolEnvironment(
    val profileName: String?,
    private val pathTemplate: List<String> = emptyList(),
    private val variableTemplates: List<Pair<String, String>> = emptyList()
) {
    init {
        for ((variable, _) in variableTemplates) {
            require(variable.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "the tool_env of llvm triple profile '$profileName' has an invalid variable name '$variable'" }
            require(!variable.equals("PATH", ignoreCase = true)) { "the tool_env of llvm triple profile '$profileName' can't set PATH, tool_path is for that" }
        }
        val twice = variableTemplates.groupBy { it.first }.filterValues { it.size > 1 }.keys
        require(twice.isEmpty()) { "the tool_env of llvm triple profile '$profileName' sets ${twice.sorted().joinToString()} more than once" }
    }

    /** The tool_path directories, with environment variables and a leading ~ expanded. */
    fun directories(env: (String) -> String? = System::getenv): List<Path> =
        LlvmTripleProfile.expandEnvironment(pathTemplate, "the tool_path of llvm triple profile '$profileName'", env).map {
            try {
                ImportFileSystem.expandTilde(Path(it))
            } catch (_: InvalidPathException) {
                throw IllegalArgumentException("the tool_path of llvm triple profile '$profileName' has an invalid directory '$it'")
            }
        }

    /** The tool_env variables, with environment variables expanded in their values. */
    fun variables(env: (String) -> String? = System::getenv): Map<String, String> =
        variableTemplates.associate { (variable, value) ->
            variable to LlvmTripleProfile.expandEnvironment(listOf(value), "the tool_env of llvm triple profile '$profileName'", env).single()
        }

    /**
     * A ProcessBuilder for [command] with the [variables] set and the [directories] in front of the PATH. A program without
     * a directory is looked up in those directories first, since Java looks it up on its own PATH, not on the process's.
     * Throws an IllegalArgumentException when an environment variable they use is not set.
     */
    fun processBuilder(command: List<String>): ProcessBuilder {
        if (pathTemplate.isEmpty() && variableTemplates.isEmpty())
            return ProcessBuilder(command)
        val directories = directories()
        val variables = variables()
        val program = command.first().let { if (LlvmToolLocator.isBareName(it)) LlvmToolLocator.findIn(directories, it)?.toString() ?: it else it }
        val builder = ProcessBuilder(listOf(program) + command.drop(1))
        val environment = builder.environment()
        environment.putAll(variables)
        if (directories.isNotEmpty()) {
            // Windows names it Path, and the map's keys ignore case there
            val key = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = isWindows) } ?: "PATH"
            environment[key] = (directories.map(Path::toString) + listOfNotNull(environment[key]?.takeIf { it.isNotEmpty() })).joinToString(File.pathSeparator)
        }
        return builder
    }

    internal fun withoutName(): LlvmToolEnvironment = copy(profileName = null)

    private val isWindows get() = System.getProperty("os.name").orEmpty().startsWith("Windows")
}


/** One profile file of a chain of `base` keys, known by [id] for finding cycles; [directory] is null for a profile embedded in the compiler. */
private class ProfileLayer(val id: String, val label: String, val properties: Properties, val directory: Path?) {
    val base: String? = properties.getProperty(BASE)?.trim()?.takeIf { it.isNotEmpty() }
    val isAbstract: Boolean = when (val value = properties.getProperty(ABSTRACT)?.trim()) {
        null, "", "false" -> false
        "true" -> true
        else -> throw IllegalArgumentException("llvm triple profile '$label' has abstract '$value', it must be true or false")
    }

    companion object {
        const val BASE = "base"
        const val ABSTRACT = "abstract"
    }
}


internal data class ClangTargetInfo(val triple: String, val dataLayout: String, val cLongSize: Int, val cIntSize: Int)
