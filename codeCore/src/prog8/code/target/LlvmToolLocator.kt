package prog8.code.target

import prog8.code.source.ImportFileSystem
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

    fun find(tool: String): Path? = findIn(searchDirs(), tool)

    /** The executable [tool] in the first of [dirs] that has it, with .exe added on Windows. */
    fun findIn(dirs: List<Path>, tool: String): Path? {
        val fileName = executableName(tool)
        return dirs.firstNotNullOfOrNull { dir ->
            orNull { dir.resolve(fileName).takeIf { Files.isRegularFile(it) && Files.isExecutable(it) } }
        }
    }

    fun isBareName(nameOrPath: String): Boolean = '/' !in nameOrPath && File.separatorChar !in nameOrPath

    /** The clang of the same LLVM installation as llvm-as, which accepts the same IR, rather than an older one earlier on the PATH. */
    fun clang(): Path? {
        val besideLlvmAs = find("llvm-as")?.let { llvmAs ->
            orNull { llvmAs.resolveSibling(executableName("clang")).takeIf { Files.isRegularFile(it) && Files.isExecutable(it) } }
        }
        return besideLlvmAs ?: find("clang")
    }

    /**
     * A bare tool [nameOrPath] is searched for in the [toolPath] directories, then as [find] does; a path, with a leading ~ expanded,
     * is taken as it is. Null when there is no such executable.
     */
    fun locate(nameOrPath: String, toolPath: () -> List<Path> = { emptyList() }): Path? {
        if (isBareName(nameOrPath))
            return findIn(toolPath(), nameOrPath) ?: find(nameOrPath)
        return orNull { ImportFileSystem.expandTilde(Path.of(nameOrPath)).takeIf { Files.isRegularFile(it) && Files.isExecutable(it) } }
    }

    /**
     * The clang to use: [option] (the -clang option), else [profileClang] (the clang of llvm triple profile [profileName], looked up
     * in its [tools] directories first), else [clang]. Null when none is named and none is found; throws an IllegalArgumentException
     * when a named one isn't found.
     */
    fun resolveClang(option: String?, profileClang: String?, profileName: String?, tools: LlvmToolEnvironment? = null): Path? {
        if (option != null)
            return locate(option) ?: throw IllegalArgumentException("the clang '$option' given with -clang was not found")
        if (profileClang != null)
            return locate(profileClang) { tools?.directories().orEmpty() } ?: throw IllegalArgumentException("the clang '$profileClang' of llvm triple profile '$profileName' was not found")
        return clang()
    }

    /** The LLVM tool [tool] of the installation of [clang], else as [find] finds it. */
    fun besideClang(tool: String, clang: Path?): Path? =
        clang?.let { orNull { it.resolveSibling(executableName(tool)).takeIf { file -> Files.isRegularFile(file) && Files.isExecutable(file) } } } ?: find(tool)

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
