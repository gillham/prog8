package prog8.code.target

import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Finds the LLVM tools on the PATH, or in Homebrew's LLVM installation. */
object LlvmToolLocator {
    /** Homebrew installs LLVM keg-only, so its tools are not on the PATH by default. */
    val homebrewLlvmBin: Path = Path.of("/opt/homebrew/opt/llvm/bin")

    fun searchDirs(): List<Path> {
        val pathEntries = orNull { System.getenv("PATH") }.orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
        // not `+`: a Path is also an Iterable of its name elements
        return pathEntries.mapNotNull { orNull { Path.of(it) } }.plusElement(homebrewLlvmBin)
    }

    fun find(tool: String): Path? {
        val fileName = executableName(tool)
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

    fun executableName(name: String): String =
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
