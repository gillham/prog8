package prog8.code.target.c64os

import prog8.code.core.*
import prog8.code.target.C64OSTarget
import prog8.code.target.cbm.Mflpt5
import java.io.IOException
import java.nio.file.Path


class C64OSMachineDefinition: IMachineDefinition {

    override val cpu = CpuType.CPU6502

    override val FLOAT_MAX_POSITIVE = Mflpt5.FLOAT_MAX_POSITIVE
    override val FLOAT_MAX_NEGATIVE = Mflpt5.FLOAT_MAX_NEGATIVE
    override val FLOAT_MEM_SIZE = Mflpt5.FLOAT_MEM_SIZE
    override val STARTUP_CODE_RESERVED_SIZE = 0u
    override val PROGRAM_LOAD_ADDRESS = 0x0900u
    override val PROGRAM_MEMTOP_ADDRESS = 0xb000u

    override val BSSHIGHRAM_START = 0x0u
    override val BSSHIGHRAM_END = 0x0u
    override val BSSGOLDENRAM_START = 0u
    override val BSSGOLDENRAM_END = 0u

    override lateinit var zeropage: Zeropage
    override lateinit var golden: GoldenRam

    override fun getFloatAsmBytes(num: Number) = Mflpt5.fromNumber(num).makeFloatFillAsm()

    override fun convertFloatToBytes(num: Double): List<UByte> {
        val m5 = Mflpt5.fromNumber(num)
        return listOf(m5.b0, m5.b1, m5.b2, m5.b3, m5.b4)
    }

    override fun convertBytesToFloat(bytes: List<UByte>): Double {
        require(bytes.size==5) { "need 5 bytes" }
        val m5 = Mflpt5(bytes[0], bytes[1], bytes[2], bytes[3], bytes[4])
        return m5.toDouble()
    }

    override fun launchEmulator(selectedEmulator: Int, programNameWithPath: Path) {
        if(selectedEmulator!=1) {
            System.err.println("The c64os target only supports the main emulator (Vice).")
            return
        }

        for(emulator in listOf("x64sc", "x64")) {
            println("\nStarting C-64 emulator $emulator...")
            val viceMonlist = C64OSTarget.viceMonListName(programNameWithPath.toString())
            val cmdline = listOf(emulator, "-silent", "-moncommands", viceMonlist)
            val processb = ProcessBuilder(cmdline).inheritIO()
            val process: Process
            try {
                process=processb.start()
            } catch(x: IOException) {
                continue  // try the next emulator executable
            }
            process.waitFor()
            break
        }
    }

    override fun isIOAddress(address: UInt): Boolean = address==0u || address==1u || address in 0xd000u..0xdfffu

    override fun initializeMemoryAreas(compilerOptions: CompilationOptions) {
        zeropage = C64OSZeropage(compilerOptions)
    }

}