package prog8.code.target

import prog8.code.source.ImportFileSystem
import java.io.InputStream
import java.nio.file.Path
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile

/**
 * The per-triple settings of an llvm target, read from a `.properties` file. [name] is how it was looked up,
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
    val strictIndirectSignatures: Boolean = false
) {
    /** The pointer width in bits of the default address space, which LLVM takes as 64 when the data layout has no `p:` entry. */
    val pointerBits: Int = dataLayout.split('-').firstOrNull { it.startsWith("p:") }
        ?.split(':')?.getOrNull(1)?.toIntOrNull() ?: 64

    /** The clang arguments with `${NAME}` environment variables expanded, done on use so that only a clang build needs them set. */
    val clangArgs: List<String>
        get() = expandEnvironment(clangArgsTemplate) { System.getenv(it) }

    init {
        require(dataLayout.startsWith("e")) { "the llvm triple profile '$name' must have a little-endian data layout (starting with 'e')" }
        require(dataLayout.split('-').none { it.startsWith("p:") && it.split(':').getOrNull(1)?.toIntOrNull() == null }) {
            "the llvm triple profile '$name' has an invalid pointer entry in its data layout"
        }
        require(cLongSize == 4 || cLongSize == 8) { "the llvm triple profile '$name' has c_long_size $cLongSize, it must be 4 or 8" }
        require(runCommand == null || runCommand.any { "{file}" in it }) { "the run_command of llvm triple profile '$name' must contain {file}" }
    }

    companion object {
        const val RESOURCE_DIR = "/prog8lib/llvm-triples"
        private val knownKeys = setOf("triple", "datalayout", "c_long_size", "clang_args", "output_extension", "run_command", "strict_indirect_signatures")
        private val environmentVariable = Regex($$"""\$\{([A-Za-z_][A-Za-z0-9_]*)}""")

        fun load(path: Path): LlvmTripleProfile = path.inputStream().use { parse(path.toString(), it) }

        /** Finds [nameOrPath] as a file, then as `~/.prog8/llvm/<name>.properties`, then as a profile embedded in the compiler. */
        fun resolve(nameOrPath: String): LlvmTripleProfile {
            val file = ImportFileSystem.expandTilde(Path(nameOrPath))
            if(file.isRegularFile())
                return load(file)
            val userFile = ImportFileSystem.userHome.resolve(".prog8/llvm/$nameOrPath.properties")
            if(userFile.isRegularFile())
                return userFile.inputStream().use { parse(nameOrPath, it) }
            val resource = LlvmTripleProfile::class.java.getResourceAsStream("$RESOURCE_DIR/$nameOrPath.properties")
                ?: throw IllegalArgumentException("unknown llvm triple '$nameOrPath': no such file, no $userFile, and no embedded profile")
            return resource.use { parse(nameOrPath, it) }
        }

        internal fun expandEnvironment(args: List<String>, env: (String) -> String?): List<String> = args.map { arg ->
            environmentVariable.replace(arg) {
                val variable = it.groupValues[1]
                env(variable) ?: throw IllegalArgumentException("environment variable $variable used in the llvm clang_args is not set")
            }
        }

        private fun parse(name: String, input: InputStream): LlvmTripleProfile {
            val props = Properties()
            props.load(input)
            val unknown = props.stringPropertyNames() - knownKeys
            require(unknown.isEmpty()) { "unknown keys in llvm triple profile '$name': ${unknown.sorted().joinToString()}" }
            fun required(key: String) = props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("llvm triple profile '$name' is missing '$key'")
            fun words(key: String) = props.getProperty(key)?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
            val cLongSize = required("c_long_size").toIntOrNull()
                ?: throw IllegalArgumentException("llvm triple profile '$name' has a non-numeric c_long_size")
            return LlvmTripleProfile(
                name,
                required("triple"),
                required("datalayout"),
                cLongSize,
                words("clang_args").orEmpty(),
                props.getProperty("output_extension", "").trim(),
                words("run_command")?.takeIf { it.isNotEmpty() },
                props.getProperty("strict_indirect_signatures", "false").trim().toBoolean()
            )
        }
    }
}
