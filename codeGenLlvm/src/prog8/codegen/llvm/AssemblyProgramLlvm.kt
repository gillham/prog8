package prog8.codegen.llvm

import prog8.code.assembly.IAssemblyProgram
import prog8.code.core.CompilationOptions
import prog8.code.core.IErrorReporter
import prog8.code.core.OutputFiles
import prog8.code.target.LlvmTarget
import prog8.code.target.LlvmToolLocator
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * The LLVM IR of a compiled program, saved as a .ll file; assembling checks it with llvm-as, or else with clang, when one is installed.
 * When -o names a program file without .ll, that is a native executable, which assembling then builds from the IR with clang.
 */
internal class AssemblyProgramLlvm(
    override val name: String,
    private val llvmIr: String,
    override val irInstructionCount: Int,
    override val irChunkCount: Int,
    override val irRegisterCount: Int
) : IAssemblyProgram {

    fun save(options: CompilationOptions): Path = LlvmToolchain.irFileFor(outputFile(options)).also { it.writeText(llvmIr) }

    override fun assemble(options: CompilationOptions, errors: IErrorReporter): Boolean {
        val programFile = outputFile(options)
        val file = LlvmToolchain.irFileFor(programFile)
        if (!validate(file, options))
            return false
        if (programFile == file)
            return true
        val target = options.compTarget as? LlvmTarget
        if (!LlvmToolchain.compileToExecutable(file, programFile, target?.profile,
                extraCompileArgs = target?.extraCompileArgs.orEmpty(), extraLinkArgs = target?.extraLinkArgs.orEmpty(), linkLibraries = options.linkLibraries,
                clangOption = target?.clangOption))
            return false
        if (!options.quiet)
            println("Native executable written to '$programFile'")
        return true
    }

    private fun validate(file: Path, options: CompilationOptions): Boolean {
        val target = options.compTarget as? LlvmTarget
        var profileClangMissing = false
        val namedClang = try {
            target?.takeIf { it.clangOption != null || it.profile.clang != null }?.clang()
        } catch (x: IllegalArgumentException) {
            if (target?.clangOption != null) {
                System.err.println("Can't check the LLVM IR in '$file': ${x.message}")
                return false
            }
            // only a build needs the profile's clang; the default clang may not know the triple, but llvm-as can still check it
            profileClangMissing = true
            null
        }
        val command = validation(file, target?.targetTriple, namedClang, !profileClangMissing)
        if (command == null) {
            if (!options.quiet)
                println("LLVM IR written to '$file', but not checked: " +
                    if (profileClangMissing) "the clang of the triple profile and llvm-as were not found" else "neither llvm-as nor clang was found")
            return true
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            System.err.println("${command.first()} did not finish checking '$file'")
            return false
        }
        if (process.exitValue() != 0) {
            System.err.print(output)
            System.err.println("${command.first()} rejected the LLVM IR in '$file'")
            return false
        }
        if (!options.quiet)
            println("LLVM IR written to '$file'")
        return true
    }

    private fun outputFile(options: CompilationOptions): Path = OutputFiles.of(options, name).programFile

    /**
     * llvm-as only parses the IR; clang compiles it, for [triple] rather than for this host, which may not support that.
     * A [namedClang] (-clang or the profile's) is checked with the llvm-as beside it, or else itself, as it may accept IR that another LLVM doesn't.
     * Without [defaultClang], only llvm-as checks it.
     */
    private fun validation(file: Path, triple: String?, namedClang: Path?, defaultClang: Boolean): List<String>? {
        val llvmAs = if (namedClang != null) LlvmToolLocator.besideClang("llvm-as", namedClang).takeIf { it?.parent == namedClang.parent } else LlvmToolchain.find("llvm-as")
        llvmAs?.let { return listOf(it.toString(), "-disable-output", file.toString()) }
        val nullDevice = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "NUL" else "/dev/null"
        val targetArgs = triple?.let { listOf("--target=$it") }.orEmpty()
        (namedClang ?: LlvmToolchain.find("clang")?.takeIf { defaultClang })?.let { return listOf(it.toString()) + targetArgs + listOf("-Wno-override-module", "-x", "ir", "-c", "-o", nullDevice, file.toString()) }
        return null
    }
}
