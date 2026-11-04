package prog8.code.target

import prog8.code.core.*
import prog8.code.target.encodings.Encoder
import java.nio.file.Path

/**
 * 16, 32 or 64-bit target that emits LLVM IR. The [profile] of the chosen triple supplies the triple and data layout
 * of the module header, and its pointer width must match [pointerSize]. [extraCompileArgs] and [extraLinkArgs] come from
 * -Xcompile and -Xlink, and go after the profile's own arguments of those build steps. [clangOption] is the -clang option.
 */
class LlvmTarget(
    val pointerSize: Int = 8,
    val profile: LlvmTripleProfile = LlvmTripleProfile.resolve(defaultTriple(pointerSize)),
    val extraCompileArgs: List<String> = emptyList(),
    val extraLinkArgs: List<String> = emptyList(),
    val clangOption: String? = null
): ICompilationTarget,
    IStringEncoding by Encoder(false),
    IMemSizer by NormalMemSizer(floatSize(pointerSize), pointerSize.toUInt()) {

    override val name = when(pointerSize) {
        2 -> NAME16
        4 -> NAME32
        else -> NAME64
    }
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

    /** The clang that builds the program, see [LlvmTripleProfile.resolveClang]. */
    fun clang(): Path? = profile.resolveClang(clangOption)

    /** True when constant addresses are the machine's own memory, which only the llvm backend reads. */
    val realAddresses: Boolean = pointerSize == 2

    /** False on llvm16, whose f32 floats lack the formatting, parsing and math routines yet. */
    val supportsFloats: Boolean = pointerSize != 2

    init {
        require(pointerSize == 2 || pointerSize == 4 || pointerSize == 8) { "the llvm targets have 2, 4 or 8 byte pointers, not $pointerSize" }
        require(profile.pointerBits == pointerSize * 8) {
            "llvm triple '${profile.triple}' has ${profile.pointerBits}-bit pointers, but the $name target needs ${pointerSize * 8}-bit pointers"
        }
    }

    companion object {
        const val NAME64 = "llvm64"
        const val NAME32 = "llvm32"
        const val NAME16 = "llvm16"
        const val ALIAS = "llvm"
        const val FALLBACK_TRIPLE_64 = "arm64-apple-macosx"
        const val DEFAULT_TRIPLE_32 = "wasm32-wasip1"
        const val DEFAULT_TRIPLE_16 = "mos-sim"
        const val FLOATS_UNSUPPORTED = "floats are not supported yet on the $NAME16 target"

        /** The target names, and [ALIAS], that select an llvm target. */
        val NAMES = setOf(NAME16, NAME32, NAME64, ALIAS)

        /** The built-in llvm64 profile of the host the compiler runs on, or [FALLBACK_TRIPLE_64] where there is none. */
        val DEFAULT_TRIPLE_64: String = hostTriple64(System.getProperty("os.name"), System.getProperty("os.arch")) ?: FALLBACK_TRIPLE_64

        /** The built-in llvm64 profile for a host with these Java `os.name` and `os.arch` properties, or null when there is none. */
        fun hostTriple64(osName: String, osArch: String): String? {
            val arch = when (osArch) {
                "aarch64", "arm64" -> "aarch64"
                "amd64", "x86_64" -> "x86_64"
                "riscv64" -> "riscv64"
                else -> return null
            }
            return when {
                osName.startsWith("Mac") -> if (arch == "aarch64") "arm64-apple-macosx" else if (arch == "x86_64") "x86_64-apple-macosx" else null
                osName.startsWith("Linux") -> "$arch-unknown-linux-gnu"
                osName.startsWith("Windows") -> if (arch == "x86_64") "x86_64-pc-windows-msvc" else null
                else -> null
            }
        }

        fun defaultTriple(pointerSize: Int): String = when(pointerSize) {
            2 -> DEFAULT_TRIPLE_16
            4 -> DEFAULT_TRIPLE_32
            else -> DEFAULT_TRIPLE_64
        }

        /** llvm16 has 4-byte floats (f32), the others 8-byte doubles. */
        fun floatSize(pointerSize: Int): UInt = if(pointerSize == 2) 4u else 8u
    }

    override val cpu = when(pointerSize) {
        2 -> CpuType.LLVM16
        4 -> CpuType.LLVM32
        else -> CpuType.LLVM64
    }

    override val FLOAT_MEM_SIZE = floatSize(pointerSize)
    override val FLOAT_MAX_POSITIVE = if(FLOAT_MEM_SIZE == 4u) Float.MAX_VALUE.toDouble() else Double.MAX_VALUE
    override val FLOAT_MAX_NEGATIVE = -FLOAT_MAX_POSITIVE
    override val POINTER_MEM_SIZE = pointerSize.toUInt()
    override val splitPointerArrays = false
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

    // little-endian IEEE-754 single or double; the triple profile guarantees a little-endian data layout
    override fun convertFloatToBytes(num: Double): List<UByte> {
        val size = FLOAT_MEM_SIZE.toInt()
        val bits = if(size == 4) num.toFloat().toRawBits().toLong() else num.toRawBits()
        return List(size) { (bits ushr (8 * it)).toUByte() }
    }

    override fun convertBytesToFloat(bytes: List<UByte>): Double {
        val size = FLOAT_MEM_SIZE.toInt()
        require(bytes.size==size) { "need $size bytes" }
        val bits = bytes.foldIndexed(0L) { index, bits, byte -> bits or (byte.toLong() shl (8 * index)) }
        return if(size == 4) Float.fromBits(bits.toInt()).toDouble() else Double.fromBits(bits)
    }

    override fun launchEmulator(selectedEmulator: Int, programFile: Path, quiet: Boolean) {
        launchEmulator(selectedEmulator, programFile, quiet, emptyList())
    }

    /**
     * As [launchEmulator], giving the program the command line arguments [programArgs]; a build links in the [linkLibraries] of its %link directives.
     * Returns the program's exit status, or null when it didn't run.
     */
    fun launchEmulator(selectedEmulator: Int, programFile: Path, quiet: Boolean, programArgs: List<String>, linkLibraries: List<String> = emptyList()): Int? {
        if (selectedEmulator != 1) {
            System.err.println("The $name target has no alternative emulator; -emu builds the program into an executable for this host and runs it.")
            return null
        }
        if (programArgs.isNotEmpty() && !profile.programArgs) {
            System.err.println(programArgsRejected())
            return null
        }
        // the LLVM code generator module depends on this one, so its runner can only be loaded via reflection
        val runner = Class.forName("prog8.codegen.llvm.LlvmProgramRunner").getDeclaredConstructor().newInstance() as ILlvmProgramRunner
        val status = runner.buildAndRun(programFile, quiet, profile, extraCompileArgs, extraLinkArgs, programArgs, linkLibraries, clangOption) ?: return null
        if (!quiet)
            println("\nProgram exited with status $status")
        return status
    }

    /** The error message for program arguments given to a program of a profile with `program_args = false`. */
    fun programArgsRejected(): String = "the programs of llvm triple profile '${profile.name}' can't be given arguments (it has program_args = false)"

    override fun isIOAddress(address: UInt): Boolean = profile.ioAllAddresses || profile.ioRanges.any { address in it }

    override fun initializeMemoryAreas(compilerOptions: CompilationOptions) {
        zeropage = LlvmZeropage(compilerOptions)
    }
}


/**
 * Builds the executable of an llvm program file when needed, and runs it with the command line arguments [programArgs],
 * with the run command of [profile] when it has one. Returns its exit status, or null when it didn't run; the reason has been printed then.
 * [clangOption] is the -clang option.
 */
interface ILlvmProgramRunner {
    fun buildAndRun(programFile: Path, quiet: Boolean, profile: LlvmTripleProfile? = null,
                    extraCompileArgs: List<String> = emptyList(), extraLinkArgs: List<String> = emptyList(), programArgs: List<String> = emptyList(),
                    linkLibraries: List<String> = emptyList(), clangOption: String? = null): Int?
}

private class LlvmZeropage(options: CompilationOptions): Zeropage(options) {
    override val SCRATCH_B1: UInt get() = noZeropage()
    override val SCRATCH_REG: UInt get() = noZeropage()
    override val SCRATCH_W1: UInt get() = noZeropage()
    override val SCRATCH_W2: UInt get() = noZeropage()
    override val SCRATCH_PTR: UInt get() = noZeropage()

    private fun noZeropage(): Nothing = throw IllegalStateException("the ${options.compTarget.name} target has no zeropage")
}
