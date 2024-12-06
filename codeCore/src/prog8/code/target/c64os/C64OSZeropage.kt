package prog8.code.target.c64os

import prog8.code.core.*


class C64OSZeropage(options: CompilationOptions) : Zeropage(options) {

    // From C64 OS docs/memory.t:
    // $57 - $5b FAC1 unpack area (used by EXP,TAN)
    // $5c - $60 FAC1 unpack area (used by EXP)
    override val SCRATCH_B1 = 0x5fu      // temp storage for a single byte
    override val SCRATCH_REG = 0x60u     // temp storage for a register, must be B1+1
    override val SCRATCH_W1 = 0xfbu      // temp storage 1 for a word  $fb+$fc
    override val SCRATCH_W2 = 0xfdu      // temp storage 2 for a word  $fd+$fe


    init {
        if (options.floats && options.zeropage !in arrayOf(
                ZeropageType.FLOATSAFE,
                ZeropageType.BASICSAFE,
                ZeropageType.DONTUSE
            ))
            throw InternalCompilerException("when floats are enabled, zero page type should be 'floatsafe' or 'basicsafe' or 'dontuse'")

        if (options.zeropage == ZeropageType.FULL) {
            // From C64 OS docs/memory.t:
            // $57 - $5b FAC1 unpack area (used by EXP,TAN)
            // $5c - $60 FAC1 unpack area (used by EXP)
            free.addAll(arrayOf(
                0x57, 0x58, 0x59, 0x5a, 0x5b, 0x5c, 0x5d, 0x5e,
            ).map{it.toUInt()})
        } else {
            if (options.zeropage == ZeropageType.KERNALSAFE) {
                // From C64 OS docs/memory.t:
                // $57 - $5b FAC1 unpack area (used by EXP,TAN)
                // $5c - $60 FAC1 unpack area (used by EXP)
                free.addAll(arrayOf(
                    0x57, 0x58, 0x59, 0x5a, 0x5b, 0x5c, 0x5d, 0x5e,
                ).map{it.toUInt()})
            }

            if (options.zeropage == ZeropageType.FLOATSAFE) {
                // Floats aren't likely to work as we are using $5f/$60 above
                // don't use the zeropage at all
                free.clear()
            }

            if(options.zeropage == ZeropageType.DONTUSE) {
                // don't use the zeropage at all
                free.clear()
            }
        }

        val distinctFree = free.distinct()
        free.clear()
        free.addAll(distinctFree)

        removeReservedFromFreePool()
        retainAllowed()
    }

    override fun allocateCx16VirtualRegisters() {
        // Note: the 16 virtual registers R0-R15 are not regular allocated variables, they're *memory mapped* elsewhere to fixed addresses.
        // However, to be able for the compiler to "see" them as zero page variables, we have to register them here as well.
        // This is important because the compiler sometimes treats ZP variables more efficiently (for example if it's a pointer)
        // The base addres is $04.  Unfortunately it cannot be the same as on the Commander X16 ($02).
        for(reg in 0..15) {
            allocatedVariables["cx16.r${reg}"]   = VarAllocation((4+reg*2).toUInt(), DataType.forDt(BaseDataType.UWORD), 2)       // cx16.r0 .. cx16.r15
            allocatedVariables["cx16.r${reg}s"]  = VarAllocation((4+reg*2).toUInt(), DataType.forDt(BaseDataType.WORD), 2)        // cx16.r0s .. cx16.r15s
            allocatedVariables["cx16.r${reg}L"]  = VarAllocation((4+reg*2).toUInt(), DataType.forDt(BaseDataType.UBYTE), 1)       // cx16.r0L .. cx16.r15L
            allocatedVariables["cx16.r${reg}H"]  = VarAllocation((5+reg*2).toUInt(), DataType.forDt(BaseDataType.UBYTE), 1)       // cx16.r0H .. cx16.r15H
            allocatedVariables["cx16.r${reg}sL"] = VarAllocation((4+reg*2).toUInt(), DataType.forDt(BaseDataType.BYTE), 1)        // cx16.r0sL .. cx16.r15sL
            allocatedVariables["cx16.r${reg}sH"] = VarAllocation((5+reg*2).toUInt(), DataType.forDt(BaseDataType.BYTE), 1)        // cx16.r0sH .. cx16.r15sH
            free.remove((4+reg*2).toUInt())
            free.remove((5+reg*2).toUInt())
        }
    }
}
