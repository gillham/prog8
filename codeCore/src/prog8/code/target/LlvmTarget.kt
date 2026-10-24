package prog8.code.target

import prog8.code.core.*
import prog8.code.target.encodings.Encoder
import java.nio.file.Path

/**
 * Hosted 32 or 64-bit target that emits LLVM IR. The [profile] of the chosen triple supplies the triple and data layout
 * of the module header, and its pointer width must match [pointerSize]. [extraCompileArgs] and [extraLinkArgs] come from
 * -Xcompile and -Xlink, and go after the profile's own arguments of those build steps.
 */
class LlvmTarget(
    val pointerSize: Int = 8,
    val profile: LlvmTripleProfile = LlvmTripleProfile.resolve(defaultTriple(pointerSize)),
    val extraCompileArgs: List<String> = emptyList(),
    val extraLinkArgs: List<String> = emptyList()
): ICompilationTarget,
    IStringEncoding by Encoder(false),
    IMemSizer by NormalMemSizer(8u, pointerSize.toUInt()) {

    override val name = if(pointerSize == 4) NAME32 else NAME64
    override val supportsBankedCalls = false
    override val supportsStackFrames = false
    override val hasFixedMemoryAddresses = false
    override val defaultEncoding = Encoding.ISO
    override val libraryPath = null
    override val customLauncher = emptyList<String>()
    override val additionalAssemblerOptions = emptyList<String>()
    override val defaultOutputType = OutputType.RAW
    override val defaultLauncherType = CbmPrgLauncherType.NONE

    val targetTriple: String get() = profile.triple
    val dataLayout: String get() = profile.dataLayout

    init {
        require(pointerSize == 4 || pointerSize == 8) { "the llvm targets have 4 or 8 byte pointers, not $pointerSize" }
        require(profile.pointerBits == pointerSize * 8) {
            "llvm triple '${profile.triple}' has ${profile.pointerBits}-bit pointers, but the $name target needs ${pointerSize * 8}-bit pointers"
        }
    }

    companion object {
        const val NAME64 = "llvm64"
        const val NAME32 = "llvm32"
        const val ALIAS = "llvm"
        const val DEFAULT_TRIPLE_64 = "arm64-apple-macosx"
        const val DEFAULT_TRIPLE_32 = "wasm32-wasip1"

        fun defaultTriple(pointerSize: Int): String = if(pointerSize == 4) DEFAULT_TRIPLE_32 else DEFAULT_TRIPLE_64
    }

    override val cpu = if(pointerSize == 4) CpuType.LLVM32 else CpuType.LLVM64

    override val FLOAT_MAX_POSITIVE = Double.MAX_VALUE
    override val FLOAT_MAX_NEGATIVE = -Double.MAX_VALUE
    override val FLOAT_MEM_SIZE = 8u
    override val POINTER_MEM_SIZE = pointerSize.toUInt()
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

    // little-endian IEEE-754 double; the triple profile guarantees a little-endian data layout
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
            System.err.println("The $name target has no alternative emulator; -emu builds the program into an executable for this host and runs it.")
            return
        }
        // the LLVM code generator module depends on this one, so its runner can only be loaded via reflection
        val runner = Class.forName("prog8.codegen.llvm.LlvmProgramRunner").getDeclaredConstructor().newInstance() as ILlvmProgramRunner
        val status = runner.buildAndRun(programFile, quiet, profile, extraCompileArgs, extraLinkArgs) ?: return
        if (!quiet)
            println("\nProgram exited with status $status")
    }

    override fun isIOAddress(address: UInt): Boolean = false

    override fun initializeMemoryAreas(compilerOptions: CompilationOptions) {
        zeropage = LlvmZeropage(compilerOptions)
    }
}


/**
 * Builds the executable of an llvm program file when needed, and runs it, with the run command of [profile] when it has one.
 * Returns its exit status, or null when it didn't run; the reason has been printed then.
 */
interface ILlvmProgramRunner {
    fun buildAndRun(programFile: Path, quiet: Boolean, profile: LlvmTripleProfile? = null,
                    extraCompileArgs: List<String> = emptyList(), extraLinkArgs: List<String> = emptyList()): Int?
}

private class LlvmZeropage(options: CompilationOptions): Zeropage(options) {
    override val SCRATCH_B1: UInt get() = noZeropage()
    override val SCRATCH_REG: UInt get() = noZeropage()
    override val SCRATCH_W1: UInt get() = noZeropage()
    override val SCRATCH_W2: UInt get() = noZeropage()
    override val SCRATCH_PTR: UInt get() = noZeropage()

    private fun noZeropage(): Nothing = throw IllegalStateException("the ${options.compTarget.name} target has no zeropage")
}
