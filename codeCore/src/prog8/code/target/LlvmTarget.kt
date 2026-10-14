package prog8.code.target

import prog8.code.core.*
import prog8.code.target.encodings.Encoder
import java.nio.file.Path

/**
 * Hosted 64-bit target that emits LLVM IR. [targetTriple] and [dataLayout] go into the module header;
 * when overriding the triple, also pass its matching data layout (as printed by `clang -S -emit-llvm`).
 */
class LlvmTarget(
    val targetTriple: String = DEFAULT_TARGET_TRIPLE,
    val dataLayout: String = DEFAULT_DATA_LAYOUT
): ICompilationTarget,
    IStringEncoding by Encoder(false),
    IMemSizer by NormalMemSizer(8u, 8u) {

    override val name = NAME
    override val supportsBankedCalls = false
    override val supportsStackFrames = false
    override val defaultEncoding = Encoding.ISO
    override val libraryPath = null
    override val customLauncher = emptyList<String>()
    override val additionalAssemblerOptions = emptyList<String>()
    override val defaultOutputType = OutputType.RAW
    override val defaultLauncherType = CbmPrgLauncherType.NONE

    companion object {
        const val NAME = "llvm64"
        const val ALIAS = "llvm"
        const val DEFAULT_TARGET_TRIPLE = "arm64-apple-macosx"
        const val DEFAULT_DATA_LAYOUT = "e-m:o-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-n32:64-S128-Fn32"
    }

    override val cpu = CpuType.LLVM64

    override val FLOAT_MAX_POSITIVE = Double.MAX_VALUE
    override val FLOAT_MAX_NEGATIVE = -Double.MAX_VALUE
    override val FLOAT_MEM_SIZE = 8u
    override val POINTER_MEM_SIZE = 8u
    override val ARRAY_SIZE_LIMIT = 65535u

    override val PROGRAM_LOAD_ADDRESS = 0u
    override val PROGRAM_MEMTOP_ADDRESS = UInt.MAX_VALUE     // hosted: the OS manages memory

    override val BSSHIGHRAM_START = 0u          // not actually used
    override val BSSHIGHRAM_END = 0u            // not actually used
    override val BSSGOLDENRAM_START = 0u        // not actually used
    override val BSSGOLDENRAM_END = 0u          // not actually used
    override lateinit var zeropage: Zeropage    // not actually used

    override fun getFloatAsmBytes(num: Number): String =
        convertFloatToBytes(num.toDouble()).joinToString(", ") { "$%02x".format(it.toInt()) }

    // little-endian IEEE-754 double, matching the default arm64 data layout
    override fun convertFloatToBytes(num: Double): List<UByte> {
        val bits = num.toRawBits()
        return List(8) { (bits ushr (8 * it)).toUByte() }
    }

    override fun convertBytesToFloat(bytes: List<UByte>): Double {
        require(bytes.size==8) { "need 8 bytes" }
        return Double.fromBits(bytes.foldIndexed(0L) { index, bits, byte -> bits or (byte.toLong() shl (8 * index)) })
    }

    override fun launchEmulator(selectedEmulator: Int, programFile: Path, quiet: Boolean) {
        if (selectedEmulator != 1) {
            System.err.println("The $NAME target has no alternative emulator; -emu builds the program into an executable for this host and runs it.")
            return
        }
        // the LLVM code generator module depends on this one, so its runner can only be loaded via reflection
        val runner = Class.forName("prog8.codegen.llvm.LlvmProgramRunner").getDeclaredConstructor().newInstance() as ILlvmProgramRunner
        val status = runner.buildAndRun(programFile, quiet) ?: return
        if (!quiet)
            println("\nProgram exited with status $status")
    }

    override fun isIOAddress(address: UInt): Boolean = false

    override fun initializeMemoryAreas(compilerOptions: CompilationOptions) {
        zeropage = LlvmZeropage(compilerOptions)
    }
}


/**
 * Builds the native executable of an llvm64 program file when needed, and runs it on this host.
 * Returns its exit status, or null when it didn't run; the reason has been printed then.
 */
interface ILlvmProgramRunner {
    fun buildAndRun(programFile: Path, quiet: Boolean): Int?
}

private class LlvmZeropage(options: CompilationOptions): Zeropage(options) {
    override val SCRATCH_B1: UInt get() = noZeropage()
    override val SCRATCH_REG: UInt get() = noZeropage()
    override val SCRATCH_W1: UInt get() = noZeropage()
    override val SCRATCH_W2: UInt get() = noZeropage()
    override val SCRATCH_PTR: UInt get() = noZeropage()

    private fun noZeropage(): Nothing = throw IllegalStateException("the ${LlvmTarget.NAME} target has no zeropage")
}
