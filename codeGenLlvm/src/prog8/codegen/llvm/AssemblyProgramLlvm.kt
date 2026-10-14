package prog8.codegen.llvm

import prog8.code.assembly.IAssemblyProgram
import prog8.code.core.CompilationOptions
import prog8.code.core.IErrorReporter
import prog8.code.core.OutputFiles
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
        if (!LlvmToolchain.compileToExecutable(file, programFile))
            return false
        if (!options.quiet)
            println("Native executable written to '$programFile'")
        return true
    }

    private fun validate(file: Path, options: CompilationOptions): Boolean {
        val command = validation(file)
        if (command == null) {
            if (!options.quiet)
                println("LLVM IR written to '$file', but not checked: neither llvm-as nor clang was found")
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

    private fun validation(file: Path): List<String>? {
        LlvmToolchain.find("llvm-as")?.let { return listOf(it.toString(), "-disable-output", file.toString()) }
        val nullDevice = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "NUL" else "/dev/null"
        LlvmToolchain.find("clang")?.let { return listOf(it.toString(), "-Wno-override-module", "-x", "ir", "-c", "-o", nullDevice, file.toString()) }
        return null
    }
}
