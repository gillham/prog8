package prog8.vm

import prog8.intermediate.RegisterNum

/**
 * ~100000 virtual integer registers (64 bits wide).
 * ~100000 virtual float registers (64 bits wide).
 * A,X and Y "physical" 6502 registers (8 bits wide).
 */
class Registers {
    private val registers = LongArray(99999)
    private val floatRegisters = DoubleArray(99999)
    var cpuA: UByte = 0u
    var cpuX: UByte = 0u
    var cpuY: UByte = 0u

    fun reset() {
        registers.fill(0)
        floatRegisters.fill(0.0)
        cpuA = 0u
        cpuX = 0u
        cpuY = 0u
    }

    fun setUB(reg: Int, value: UByte) { registers[reg] = value.toLong() }
    fun setUB(reg: RegisterNum, value: UByte) { registers[reg.value] = value.toLong() }

    fun setSB(reg: Int, value: Byte) { registers[reg] = value.toLong() }
    fun setSB(reg: RegisterNum, value: Byte) { registers[reg.value] = value.toLong() }

    fun setUW(reg: Int, value: UShort) { registers[reg] = value.toLong() }
    fun setUW(reg: RegisterNum, value: UShort) { registers[reg.value] = value.toLong() }

    fun setSW(reg: Int, value: Short) { registers[reg] = value.toLong() }
    fun setSW(reg: RegisterNum, value: Short) { registers[reg.value] = value.toLong() }

    fun setSL(reg: Int, value: Int) { registers[reg] = value.toLong() }
    fun setSL(reg: RegisterNum, value: Int) { registers[reg.value] = value.toLong() }

    fun getUB(reg: Int) = registers[reg].toUByte()
    fun getUB(reg: RegisterNum) = registers[reg.value].toUByte()

    fun getSB(reg: Int) = registers[reg].toByte()
    fun getSB(reg: RegisterNum) = registers[reg.value].toByte()

    fun getUW(reg: Int) = registers[reg].toUShort()
    fun getUW(reg: RegisterNum) = registers[reg.value].toUShort()

    fun getSW(reg: Int) = registers[reg].toShort()
    fun getSW(reg: RegisterNum) = registers[reg.value].toShort()

    fun getSL(reg: Int) = registers[reg].toInt()
    fun getSL(reg: RegisterNum) = registers[reg.value].toInt()

    fun setUL(reg: Int, value: UInt) { registers[reg] = value.toLong() }
    fun setUL(reg: RegisterNum, value: UInt) { registers[reg.value] = value.toLong() }

    fun getUL(reg: Int) = registers[reg].toUInt()
    fun getUL(reg: RegisterNum) = registers[reg.value].toUInt()

    fun setSQ(reg: Int, value: Long) { registers[reg] = value }
    fun setSQ(reg: RegisterNum, value: Long) { registers[reg.value] = value }

    fun getSQ(reg: Int) = registers[reg]
    fun getSQ(reg: RegisterNum) = registers[reg.value]

    fun setUQ(reg: Int, value: ULong) { registers[reg] = value.toLong() }
    fun setUQ(reg: RegisterNum, value: ULong) { registers[reg.value] = value.toLong() }

    fun getUQ(reg: Int) = registers[reg].toULong()
    fun getUQ(reg: RegisterNum) = registers[reg.value].toULong()

    fun getFloat(reg: Int) = floatRegisters[reg]
    fun getFloat(reg: RegisterNum) = floatRegisters[reg.value]

    fun setFloat(reg: Int, value: Double) { floatRegisters[reg] = value }
    fun setFloat(reg: RegisterNum, value: Double) { floatRegisters[reg.value] = value }
}
