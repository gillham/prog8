package prog8.vm

import prog8.intermediate.IRDataType
import prog8.intermediate.IRInstruction
import prog8.intermediate.requireDest
import prog8.intermediate.requireImmediateInt
import prog8.intermediate.requireImmediateQuad
import prog8.intermediate.requireSrcA
import prog8.intermediate.requireSrcB

// Note: statusbitsNZ() was removed as part of the strict status-bits contract cleanup.
// The VM no longer sets Z/N on arithmetic or load operations; only CMP, CMPI, SGN,
// BITTST and the carry-out of shift/rotate operations modify the status flags. The
// IR generator always emits an explicit CMPI before any branch that depends on the
// result, so the VM doesn't need to track Z/N as a side effect of other operations.
// See VirtualMachine.kt (the init require() and the class-level contract comment)
// and CpuType.statusBitsOnMultiByteOps for the rationale.

internal fun VirtualMachine.statusbitsComparison(left: Int, right: Int, type: IRDataType) {
    val comparison = left - right
    statusZero = comparison == 0
    when (type) {
        IRDataType.QUAD -> throw IllegalArgumentException("quad comparisons must use statusbitsComparisonQuad")
        IRDataType.BYTE -> {
            statusNegative = (comparison and 0x80) != 0
            statusCarry = (left and 0xff) >= (right and 0xff)
        }
        IRDataType.WORD -> {
            statusNegative = (comparison and 0x8000) != 0
            statusCarry = (left and 0xffff) >= (right and 0xffff)
        }
        IRDataType.LONG, IRDataType.POINTER -> {
            statusNegative = comparison < 0
            statusCarry = Integer.compareUnsigned(left, right) >= 0
        }
        IRDataType.FLOAT -> { /* floats don't change the status bits */ }
    }
}

internal fun VirtualMachine.statusbitsComparisonWithOverflow(left: Int, right: Int, type: IRDataType) {
    statusbitsComparison(left, right, type)
    val comparison = left - right
    when (type) {
        IRDataType.QUAD -> throw IllegalArgumentException("quad comparisons must use statusbitsComparisonQuad")
        IRDataType.BYTE -> {
            val signBit = 0x80
            val leftSign = left and signBit
            val rightSign = right and signBit
            statusOverflow = ((leftSign xor rightSign) and (leftSign xor (comparison and signBit))) != 0
        }
        IRDataType.WORD -> {
            val signBit = 0x8000
            val leftSign = left and signBit
            val rightSign = right and signBit
            statusOverflow = ((leftSign xor rightSign) and (leftSign xor (comparison and signBit))) != 0
        }
        IRDataType.LONG, IRDataType.POINTER -> {
            val leftSign = left < 0
            val rightSign = right < 0
            val resultSign = comparison < 0
            statusOverflow = leftSign != rightSign && leftSign != resultSign
        }
        IRDataType.FLOAT -> { /* floats don't set overflow */ }
    }
}

internal fun VirtualMachine.statusbitsComparisonQuad(left: Long, right: Long) {
    val comparison = left - right
    statusZero = left == right
    statusNegative = comparison < 0
    statusCarry = left.toULong() >= right.toULong()
    statusOverflow = ((left xor right) and (left xor comparison)) < 0
}

internal fun VirtualMachine.plusMinusMultAnyByte(operator: String, reg1: Int, reg2: Int) {
    val left = registers.getUB(reg1)
    val right = registers.getUB(reg2)
    val result = when(operator) {
        "+" -> left + right
        "-" -> left - right
        "*" -> left * right
        else -> throw IllegalArgumentException("operator byte $operator")
    }
    registers.setUB(reg1, result.toUByte())
}

internal fun VirtualMachine.multiplyAnyByteSigned(reg1: Int, reg2: Int) {
    val left = registers.getSB(reg1)
    val right = registers.getSB(reg2)
    val result = left * right
    registers.setSB(reg1, result.toByte())
}

internal fun VirtualMachine.plusMinusMultConstByte(operator: String, reg1: Int, value: UByte) {
    val left = registers.getUB(reg1)
    val result = when(operator) {
        "+" -> left + value
        "-" -> left - value
        "*" -> left * value
        else -> throw IllegalArgumentException("operator byte $operator")
    }
    registers.setUB(reg1, result.toUByte())
}

internal fun VirtualMachine.multiplyConstByteSigned(reg1: Int, value: Byte) {
    val left = registers.getSB(reg1)
    val result = left * value
    registers.setSB(reg1, result.toByte())
}

internal fun VirtualMachine.plusMinusMultConstByteInplace(operator: String, value: UByte, address: UInt) {
    val memvalue = memory.getUB(address)
    val result = when(operator) {
        "+" -> memvalue + value
        "-" -> memvalue - value
        "*" -> memvalue * value
        else -> throw IllegalArgumentException("operator byte $operator")
    }
    memory.setUB(address, result.toUByte())
}

internal fun VirtualMachine.plusMinusMultConstWordInplace(operator: String, value: UShort, address: UInt) {
    val memvalue = memory.getUW(address)
    val result: UInt = when(operator) {
        "+" -> memvalue + value
        "-" -> memvalue - value
        "*" -> {
            val r = memvalue.toUInt() * value
            mul16LastUpper = r shr 16
            r
        }
        else -> throw IllegalArgumentException("operator word $operator")
    }
    memory.setUW(address, result.toUShort())
}

internal fun VirtualMachine.plusMinusMultConstLongInplace(operator: String, value: Int, address: UInt) {
    val memvalue = memory.getSL(address)
    val result: Int = when(operator) {
        "+" -> memvalue + value
        "-" -> memvalue - value
        "*" -> memvalue * value
        else -> throw IllegalArgumentException("operator long $operator")
    }
    memory.setSL(address, result)
}

internal fun VirtualMachine.plusMinusMultAnyByteInplace(operator: String, reg1: Int, address: UInt) {
    val memvalue = memory.getUB(address)
    val operand = registers.getUB(reg1)
    val result = when(operator) {
        "+" -> memvalue + operand
        "-" -> memvalue - operand
        "*" -> memvalue * operand
        else -> throw IllegalArgumentException("operator byte $operator")
    }
    memory.setUB(address, result.toUByte())
}

internal fun VirtualMachine.multiplyAnyByteSignedInplace(reg1: Int, address: UInt) {
    val memvalue = memory.getSB(address)
    val operand = registers.getSB(reg1)
    val result = memvalue * operand
    memory.setSB(address, result.toByte())
}

internal fun VirtualMachine.divModByteSigned(operator: String, reg1: Int, reg2: Int) {
    val left = registers.getSB(reg1)
    val right = registers.getSB(reg2)
    val result = when(operator) {
        "/" -> {
            if(right==0.toByte()) 127
            else left / right
        }
        "%" -> {
            if(right==0.toByte()) 0
            else left % right
        }
        else -> throw IllegalArgumentException("operator byte $operator")
    }
    registers.setSB(reg1, result.toByte())
}

internal fun VirtualMachine.divModConstByteSigned(operator: String, reg1: Int, value: Byte) {
    val left = registers.getSB(reg1)
    val result = when(operator) {
        "/" -> {
            if(value==0.toByte()) 127
            else left / value
        }
        "%" -> {
            if(value==0.toByte()) 0
            else left % value
        }
        else -> throw IllegalArgumentException("operator byte $operator")
    }
    registers.setSB(reg1, result.toByte())
}

internal fun VirtualMachine.divModByteSignedInplace(operator: String, reg1: Int, address: UInt) {
    val left = memory.getSB(address)
    val right = registers.getSB(reg1)
    val result = when(operator) {
        "/" -> {
            if(right==0.toByte()) 127
            else left / right
        }
        "%" -> {
            if(right==0.toByte()) 0
            else left % right
        }
        else -> throw IllegalArgumentException("operator byte $operator")
    }
    memory.setSB(address, result.toByte())
}

internal fun VirtualMachine.divOrModByteUnsigned(operator: String, reg1: Int, reg2: Int) {
    val left = registers.getUB(reg1)
    val right = registers.getUB(reg2)
    val result = when(operator) {
        "/" -> {
            if(right==0.toUByte()) 0xffu
            else left / right
        }
        "%" -> {
            if(right==0.toUByte()) 0u
            else left % right
        }
        else -> throw IllegalArgumentException("operator byte $operator")
    }
    registers.setUB(reg1, result.toUByte())
}

internal fun VirtualMachine.divOrModConstByteUnsigned(operator: String, reg1: Int, value: UByte) {
    val left = registers.getUB(reg1)
    val result = when(operator) {
        "/" -> {
            if(value==0.toUByte()) 0xffu
            else left / value
        }
        "%" -> {
            if(value==0.toUByte()) 0u
            else left % value
        }
        else -> throw IllegalArgumentException("operator byte $operator")
    }
    registers.setUB(reg1, result.toUByte())
}

internal fun VirtualMachine.divAndModUByte(reg1: Int, reg2: Int) {
    val left = registers.getUB(reg1)
    val right = registers.getUB(reg2)
    val division = if(right==0.toUByte()) 0xffu else left / right
    val remainder = if(right==0.toUByte()) 0u else left % right
    registers.setUB(reg1, division.toUByte())
    registers.setUB(reg2, remainder.toUByte())
}

internal fun VirtualMachine.divAndModConstUByte(reg1: Int, remainderReg: Int, value: UByte) {
    val left = registers.getUB(reg1)
    val division = if(value==0.toUByte()) 0xffu else left / value
    val remainder = if(value==0.toUByte()) 0u else left % value
    registers.setUB(reg1, division.toUByte())
    registers.setUB(remainderReg, remainder.toUByte())
}

internal fun VirtualMachine.divAndModUWord(reg1: Int, reg2: Int) {
    val left = registers.getUW(reg1)
    val right = registers.getUW(reg2)
    val division = if(right==0.toUShort()) 0xffffu else left / right
    val remainder = if(right==0.toUShort()) 0u else left % right
    registers.setUW(reg1, division.toUShort())
    registers.setUW(reg2, remainder.toUShort())
}

internal fun VirtualMachine.divAndModConstUWord(reg1: Int, remainderReg: Int, value: UShort) {
    val left = registers.getUW(reg1)
    val division = if(value==0.toUShort()) 0xffffu else left / value
    val remainder = if(value==0.toUShort()) 0u else left % value
    registers.setUW(reg1, division.toUShort())
    registers.setUW(remainderReg, remainder.toUShort())
}

internal fun VirtualMachine.divAndModSByte(reg1: Int, reg2: Int) {
    val left = registers.getSB(reg1)
    val right = registers.getSB(reg2)
    val division = if(right==0.toByte()) 127 else left / right
    val remainder = if(right==0.toByte()) 0 else left % right
    registers.setUB(reg1, division.toUByte())
    registers.setUB(reg2, remainder.toUByte())
}

internal fun VirtualMachine.divAndModConstSByte(reg1: Int, remainderReg: Int, value: Byte) {
    val left = registers.getSB(reg1)
    val division = if(value==0.toByte()) 127 else left / value
    val remainder = if(value==0.toByte()) 0 else left % value
    registers.setUB(reg1, division.toUByte())
    registers.setUB(remainderReg, remainder.toUByte())
}

internal fun VirtualMachine.divAndModSWord(reg1: Int, reg2: Int) {
    val left = registers.getSW(reg1)
    val right = registers.getSW(reg2)
    val division = if(right==0.toShort()) 32767 else left / right
    val remainder = if(right==0.toShort()) 0 else left % right
    registers.setUW(reg1, division.toUShort())
    registers.setUW(reg2, remainder.toUShort())
}

internal fun VirtualMachine.divAndModConstSWord(reg1: Int, remainderReg: Int, value: Short) {
    val left = registers.getSW(reg1)
    val division = if(value==0.toShort()) 32767 else left / value
    val remainder = if(value==0.toShort()) 0 else left % value
    registers.setUW(reg1, division.toUShort())
    registers.setUW(remainderReg, remainder.toUShort())
}

internal fun VirtualMachine.divModByteUnsignedInplace(operator: String, reg1: Int, address: UInt) {
    val left = memory.getUB(address)
    val right = registers.getUB(reg1)
    val result = when(operator) {
        "/" -> {
            if(right==0.toUByte()) 0xffu
            else left / right
        }
        "%" -> {
            if(right==0.toUByte()) 0u
            else left % right
        }
        else -> throw IllegalArgumentException("operator byte $operator")
    }
    memory.setUB(address, result.toUByte())
}

internal fun VirtualMachine.plusMinusMultAnyWord(operator: String, reg1: Int, reg2: Int) {
    val left = registers.getUW(reg1)
    val right = registers.getUW(reg2)
    val result: UInt
    when(operator) {
        "+" -> result = left + right
        "-" -> result = left - right
        "*" -> {
            result = left.toUInt() * right
            mul16LastUpper = result shr 16
        }
        else -> throw IllegalArgumentException("operator word $operator")
    }
    registers.setUW(reg1, result.toUShort())
}

internal fun VirtualMachine.multiplyAnyWordSigned(reg1: Int, reg2: Int) {
    val left = registers.getSW(reg1)
    val right = registers.getSW(reg2)
    val result = left.toInt() * right
    mul16LastUpper = result.toUInt() shr 16
    registers.setSW(reg1, result.toShort())
}

internal fun VirtualMachine.plusMinusMultConstWord(operator: String, reg1: Int, value: UShort) {
    val left = registers.getUW(reg1)
    val result: UInt
    when(operator) {
        "+" -> result = left + value
        "-" -> result = left - value
        "*" -> {
            result = left.toUInt() * value
            mul16LastUpper = result shr 16
        }
        else -> throw IllegalArgumentException("operator word $operator")
    }
    registers.setUW(reg1, result.toUShort())
}

internal fun VirtualMachine.multiplyConstWordSigned(reg1: Int, value: Short) {
    val left = registers.getSW(reg1)
    val result = left.toInt() * value
    mul16LastUpper = result.toUInt() shr 16
    registers.setSW(reg1, result.toShort())
}

internal fun VirtualMachine.plusMinusMultAnyWordInplace(operator: String, reg1: Int, address: UInt) {
    val memvalue = memory.getUW(address)
    val operand = registers.getUW(reg1)
    val result: UInt
    when(operator) {
        "+" -> result = memvalue + operand
        "-" -> result = memvalue - operand
        "*" -> {
            result = memvalue.toUInt() * operand
            mul16LastUpper = result shr 16
        }
        else -> throw IllegalArgumentException("operator word $operator")
    }
    memory.setUW(address, result.toUShort())
}

internal fun VirtualMachine.multiplyAnyWordSignedInplace(reg1: Int, address: UInt) {
    val memvalue = memory.getSW(address)
    val operand = registers.getSW(reg1)
    val result = memvalue.toInt() * operand
    mul16LastUpper = result.toUInt() shr 16
    memory.setSW(address, result.toShort())
}

internal fun VirtualMachine.divOrModWordUnsigned(operator: String, reg1: Int, reg2: Int) {
    val left = registers.getUW(reg1)
    val right = registers.getUW(reg2)
    val result = when(operator) {
        "/" -> {
            if(right==0.toUShort()) 0xffffu
            else left / right
        }
        "%" -> {
            if(right==0.toUShort()) 0u
            else left % right
        }
        else -> throw IllegalArgumentException("operator word $operator")
    }
    registers.setUW(reg1, result.toUShort())
}

internal fun VirtualMachine.divOrModConstWordUnsigned(operator: String, reg1: Int, value: UShort) {
    val left = registers.getUW(reg1)
    val result = when(operator) {
        "/" -> {
            if(value==0.toUShort()) 0xffffu
            else left / value
        }
        "%" -> {
            if(value==0.toUShort()) 0u
            else left % value
        }
        else -> throw IllegalArgumentException("operator word $operator")
    }
    registers.setUW(reg1, result.toUShort())
}

internal fun VirtualMachine.divModWordUnsignedInplace(operator: String, reg1: Int, address: UInt) {
    val left = memory.getUW(address)
    val right = registers.getUW(reg1)
    val result = when(operator) {
        "/" -> {
            if(right==0.toUShort()) 0xffffu
            else left / right
        }
        "%" -> {
            if(right==0.toUShort()) 0u
            else left % right
        }
        else -> throw IllegalArgumentException("operator word $operator")
    }
    memory.setUW(address, result.toUShort())
}

internal fun VirtualMachine.divModWordSigned(operator: String, reg1: Int, reg2: Int) {
    val left = registers.getSW(reg1)
    val right = registers.getSW(reg2)
    val result = when(operator) {
        "/" -> {
            if(right==0.toShort()) 32767
            else left / right
        }
        "%" -> {
            if(right==0.toShort()) 0
            else left % right
        }
        else -> throw IllegalArgumentException("operator word $operator")
    }
    registers.setSW(reg1, result.toShort())
}

internal fun VirtualMachine.divModConstWordSigned(operator: String, reg1: Int, value: Short) {
    val left = registers.getSW(reg1)
    val result = when(operator) {
        "/" -> {
            if(value==0.toShort()) 32767
            else left / value
        }
        "%" -> {
            if(value==0.toShort()) 0
            else left % value
        }
        else -> throw IllegalArgumentException("operator word $operator")
    }
    registers.setSW(reg1, result.toShort())
}

internal fun VirtualMachine.divModWordSignedInplace(operator: String, reg1: Int, address: UInt) {
    val left = memory.getSW(address)
    val right = registers.getSW(reg1)
    val result = when(operator) {
        "/" -> {
            if(right==0.toShort()) 32767
            else left / right
        }
        "%" -> {
            if(right==0.toShort()) 0
            else left % right
        }
        else -> throw IllegalArgumentException("operator word $operator")
    }
    memory.setSW(address, result.toShort())
}

internal fun arithFloat(left: Double, operator: String, right: Double): Double = when(operator) {
    "+" -> left + right
    "-" -> left - right
    "*" -> left * right
    "/" -> {
        if(right==0.0) Double.MAX_VALUE
        else left / right
    }
    "%" -> {
        if(right==0.0) 0.0
        else left % right
    }
    else -> throw IllegalArgumentException("operator word $operator")
}

internal fun VirtualMachine.getBranchOperands(i: IRInstruction): Pair<Long, Long> {
    val left = i.requireSrcA().registerNumber
    val right = i.requireSrcB().registerNumber
    return when(i.type) {
        IRDataType.QUAD -> Pair(registers.getSQ(left), registers.getSQ(right))
        IRDataType.BYTE -> Pair(registers.getSB(left).toLong(), registers.getSB(right).toLong())
        IRDataType.WORD -> Pair(registers.getSW(left).toLong(), registers.getSW(right).toLong())
        IRDataType.LONG, IRDataType.POINTER -> Pair(registers.getSL(left).toLong(), registers.getSL(right).toLong())
        IRDataType.FLOAT -> {
            throw IllegalArgumentException("can't use float here")
        }
        null -> throw IllegalArgumentException("need type for branch instruction")
    }
}

internal fun VirtualMachine.getBranchOperandsImm(i: IRInstruction): Pair<Long, Long> {
    val left = i.requireSrcA().registerNumber
    val right = if(i.type==IRDataType.QUAD) i.requireImmediateQuad() else i.requireImmediateInt().toLong()
    return when(i.type) {
        IRDataType.QUAD -> Pair(registers.getSQ(left), right)
        IRDataType.BYTE -> Pair(registers.getSB(left).toLong(), right)
        IRDataType.WORD -> Pair(registers.getSW(left).toLong(), right)
        IRDataType.LONG, IRDataType.POINTER -> Pair(registers.getSL(left).toLong(), right)
        IRDataType.FLOAT -> {
            throw IllegalArgumentException("can't use float here")
        }
        null -> throw IllegalArgumentException("need type for branch instruction")
    }
}

internal fun VirtualMachine.getBranchOperandsU(i: IRInstruction): Pair<ULong, ULong> {
    val left = i.requireSrcA().registerNumber
    val right = i.requireSrcB().registerNumber
    return when(i.type) {
        IRDataType.QUAD -> Pair(registers.getUQ(left), registers.getUQ(right))
        IRDataType.BYTE -> Pair(registers.getUB(left).toULong(), registers.getUB(right).toULong())
        IRDataType.WORD -> Pair(registers.getUW(left).toULong(), registers.getUW(right).toULong())
        IRDataType.LONG, IRDataType.POINTER -> Pair(registers.getUL(left).toULong(), registers.getUL(right).toULong())
        IRDataType.FLOAT -> {
            throw IllegalArgumentException("can't use float here")
        }
        null -> throw IllegalArgumentException("need type for branch instruction")
    }
}

internal fun VirtualMachine.getBranchOperandsImmU(i: IRInstruction): Pair<ULong, ULong> {
    val left = i.requireSrcA().registerNumber
    // a narrow immediate is zero-extended from its 32 bits, so a negative one stays below 2^32
    val right = if(i.type==IRDataType.QUAD) i.requireImmediateQuad().toULong() else i.requireImmediateInt().toUInt().toULong()
    return when(i.type) {
        IRDataType.QUAD -> Pair(registers.getUQ(left), right)
        IRDataType.BYTE -> Pair(registers.getUB(left).toULong(), right)
        IRDataType.WORD -> Pair(registers.getUW(left).toULong(), right)
        IRDataType.LONG, IRDataType.POINTER -> Pair(registers.getUL(left).toULong(), right)
        IRDataType.FLOAT -> {
            throw IllegalArgumentException("can't use float here")
        }
        null -> throw IllegalArgumentException("need type for branch instruction")
    }
}

internal fun VirtualMachine.getLogicalOperandsU(i: IRInstruction): Pair<ULong, ULong> {
    val left = i.requireDest().registerNumber
    val right = i.requireSrcA().registerNumber
    return when(i.type) {
        IRDataType.QUAD -> Pair(registers.getUQ(left), registers.getUQ(right))
        IRDataType.BYTE -> Pair(registers.getUB(left).toULong(), registers.getUB(right).toULong())
        IRDataType.WORD -> Pair(registers.getUW(left).toULong(), registers.getUW(right).toULong())
        IRDataType.LONG, IRDataType.POINTER -> Pair(registers.getUL(left).toULong(), registers.getUL(right).toULong())
        IRDataType.FLOAT -> {
            throw IllegalArgumentException("can't use float here")
        }
        null -> throw IllegalArgumentException("need type for logical instruction")
    }
}

internal fun VirtualMachine.getLogicalOperandU(i: IRInstruction): ULong {
    val reg = i.requireDest().registerNumber
    return when(i.type) {
        IRDataType.QUAD -> registers.getUQ(reg)
        IRDataType.BYTE -> registers.getUB(reg).toULong()
        IRDataType.WORD -> registers.getUW(reg).toULong()
        IRDataType.LONG, IRDataType.POINTER -> registers.getUL(reg).toULong()
        IRDataType.FLOAT -> throw IllegalArgumentException("can't use float here")
        null -> throw IllegalArgumentException("need type for logical instruction")
    }
}

internal fun VirtualMachine.getLogicalOperandsS(i: IRInstruction): Pair<Long, Long> {
    val left = i.requireDest().registerNumber
    val right = i.requireSrcA().registerNumber
    return when(i.type) {
        IRDataType.QUAD -> Pair(registers.getSQ(left), registers.getSQ(right))
        IRDataType.BYTE -> Pair(registers.getSB(left).toLong(), registers.getSB(right).toLong())
        IRDataType.WORD -> Pair(registers.getSW(left).toLong(), registers.getSW(right).toLong())
        IRDataType.LONG, IRDataType.POINTER -> Pair(registers.getSL(left).toLong(), registers.getSL(right).toLong())
        IRDataType.FLOAT -> {
            throw IllegalArgumentException("can't use float here")
        }
        null -> throw IllegalArgumentException("need type for logical instruction")
    }
}

internal fun VirtualMachine.getLogicalOperandS(i: IRInstruction): Long {
    val reg = i.requireDest().registerNumber
    return when(i.type) {
        IRDataType.QUAD -> registers.getSQ(reg)
        IRDataType.BYTE -> registers.getSB(reg).toLong()
        IRDataType.WORD -> registers.getSW(reg).toLong()
        IRDataType.LONG, IRDataType.POINTER -> registers.getSL(reg).toLong()
        IRDataType.FLOAT -> throw IllegalArgumentException("can't use float here")
        null -> throw IllegalArgumentException("need type for logical instruction")
    }
}

internal fun VirtualMachine.plusMinusMultAnyLong(operator: String, reg1: Int, reg2: Int) {
    val left = registers.getSL(reg1)
    val right = registers.getSL(reg2)
    val result: Int = when(operator) {
        "+" -> left + right
        "-" -> left - right
        "*" -> left * right
        else -> throw IllegalArgumentException("operator word $operator")
    }
    registers.setSL(reg1, result)
}

internal fun VirtualMachine.plusMinusMultConstLong(operator: String, reg1: Int, value: Int) {
    val left = registers.getSL(reg1)
    val result: Int = when(operator) {
        "+" -> left + value
        "-" -> left - value
        "*" -> left * value
        else -> throw IllegalArgumentException("operator long $operator")
    }
    registers.setSL(reg1, result)
}

internal fun VirtualMachine.plusMinusMultAnyLongInplace(operator: String, reg1: Int, address: UInt) {
    val memvalue = memory.getSL(address)
    val operand = registers.getSL(reg1)
    val result: Int = when(operator) {
        "+" -> memvalue + operand
        "-" -> memvalue - operand
        "*" -> memvalue * operand
        else -> throw IllegalArgumentException("operator word $operator")
    }
    memory.setSL(address, result)
}

internal fun VirtualMachine.multiplyAnyLongSigned(reg1: Int, reg2: Int) {
    val result = registers.getSL(reg1) * registers.getSL(reg2)
    registers.setSL(reg1, result)
}

internal fun VirtualMachine.multiplyConstLongSigned(reg1: Int, value: Int) {
    val result = registers.getSL(reg1) * value
    registers.setSL(reg1, result)
}

internal fun VirtualMachine.multiplyAnyLongSignedInplace(reg1: Int, address: UInt) {
    val result = memory.getSL(address) * registers.getSL(reg1)
    memory.setSL(address, result)
}

internal fun VirtualMachine.divModLongSigned(operator: String, reg1: Int, reg2: Int) {
    val left = registers.getSL(reg1)
    val right = registers.getSL(reg2)
    val result = when(operator) {
        "/" -> {
            if(right==0) Int.MAX_VALUE
            else left / right
        }
        "%" -> {
            if(right==0) 0
            else left % right
        }
        else -> throw IllegalArgumentException("operator long $operator")
    }
    registers.setSL(reg1, result)
}

internal fun VirtualMachine.divModConstLongSigned(operator: String, reg1: Int, immediate: Int) {
    val left = registers.getSL(reg1)
    val result = when(operator) {
        "/" -> {
            if(immediate==0) Int.MAX_VALUE
            else left / immediate
        }
        "%" -> {
            if(immediate==0) 0
            else left % immediate
        }
        else -> throw IllegalArgumentException("operator long $operator")
    }
    registers.setSL(reg1, result)
}

internal fun VirtualMachine.divModLongSignedInplace(operator: String, reg1: Int, address: UInt) {
    val left = memory.getSL(address)
    val right = registers.getSL(reg1)
    val result = when(operator) {
        "/" -> {
            if(right==0) Int.MAX_VALUE
            else left / right
        }
        "%" -> {
            if(right==0) 0
            else left % right
        }
        else -> throw IllegalArgumentException("operator long $operator")
    }
    memory.setSL(address, result)
}

// the low 64 bits of a product are the same for signed and unsigned operands, so these serve both
internal fun VirtualMachine.plusMinusMultAnyQuad(operator: String, reg1: Int, reg2: Int) {
    val left = registers.getSQ(reg1)
    val right = registers.getSQ(reg2)
    val result: Long = when(operator) {
        "+" -> left + right
        "-" -> left - right
        "*" -> left * right
        else -> throw IllegalArgumentException("operator quad $operator")
    }
    registers.setSQ(reg1, result)
}

internal fun VirtualMachine.plusMinusMultConstQuad(operator: String, reg1: Int, value: Long) {
    val left = registers.getSQ(reg1)
    val result: Long = when(operator) {
        "+" -> left + value
        "-" -> left - value
        "*" -> left * value
        else -> throw IllegalArgumentException("operator quad $operator")
    }
    registers.setSQ(reg1, result)
}

internal fun VirtualMachine.plusMinusMultAnyQuadInplace(operator: String, reg1: Int, address: UInt) {
    val memvalue = memory.getSQ(address)
    val operand = registers.getSQ(reg1)
    val result: Long = when(operator) {
        "+" -> memvalue + operand
        "-" -> memvalue - operand
        "*" -> memvalue * operand
        else -> throw IllegalArgumentException("operator quad $operator")
    }
    memory.setSQ(address, result)
}

internal fun VirtualMachine.plusMinusMultConstQuadInplace(operator: String, value: Long, address: UInt) {
    val memvalue = memory.getSQ(address)
    val result: Long = when(operator) {
        "+" -> memvalue + value
        "-" -> memvalue - value
        "*" -> memvalue * value
        else -> throw IllegalArgumentException("operator quad $operator")
    }
    memory.setSQ(address, result)
}

internal fun VirtualMachine.divModQuadSigned(operator: String, reg1: Int, reg2: Int) {
    val left = registers.getSQ(reg1)
    val right = registers.getSQ(reg2)
    val result = when(operator) {
        "/" -> {
            if(right==0L) Long.MAX_VALUE
            else left / right
        }
        "%" -> {
            if(right==0L) 0L
            else left % right
        }
        else -> throw IllegalArgumentException("operator quad $operator")
    }
    registers.setSQ(reg1, result)
}

internal fun VirtualMachine.divModConstQuadSigned(operator: String, reg1: Int, immediate: Long) {
    val left = registers.getSQ(reg1)
    val result = when(operator) {
        "/" -> {
            if(immediate==0L) Long.MAX_VALUE
            else left / immediate
        }
        "%" -> {
            if(immediate==0L) 0L
            else left % immediate
        }
        else -> throw IllegalArgumentException("operator quad $operator")
    }
    registers.setSQ(reg1, result)
}

internal fun VirtualMachine.divModQuadSignedInplace(operator: String, reg1: Int, address: UInt) {
    val left = memory.getSQ(address)
    val right = registers.getSQ(reg1)
    val result = when(operator) {
        "/" -> {
            if(right==0L) Long.MAX_VALUE
            else left / right
        }
        "%" -> {
            if(right==0L) 0L
            else left % right
        }
        else -> throw IllegalArgumentException("operator quad $operator")
    }
    memory.setSQ(address, result)
}

internal fun VirtualMachine.divOrModQuadUnsigned(operator: String, reg1: Int, reg2: Int) {
    val left = registers.getUQ(reg1)
    val right = registers.getUQ(reg2)
    val result = when(operator) {
        "/" -> {
            if(right==0uL) ULong.MAX_VALUE
            else left / right
        }
        "%" -> {
            if(right==0uL) 0uL
            else left % right
        }
        else -> throw IllegalArgumentException("operator quad $operator")
    }
    registers.setUQ(reg1, result)
}

internal fun VirtualMachine.divOrModConstQuadUnsigned(operator: String, reg1: Int, value: ULong) {
    val left = registers.getUQ(reg1)
    val result = when(operator) {
        "/" -> {
            if(value==0uL) ULong.MAX_VALUE
            else left / value
        }
        "%" -> {
            if(value==0uL) 0uL
            else left % value
        }
        else -> throw IllegalArgumentException("operator quad $operator")
    }
    registers.setUQ(reg1, result)
}

internal fun VirtualMachine.divModQuadUnsignedInplace(operator: String, reg1: Int, address: UInt) {
    val left = memory.getUQ(address)
    val right = registers.getUQ(reg1)
    val result = when(operator) {
        "/" -> {
            if(right==0uL) ULong.MAX_VALUE
            else left / right
        }
        "%" -> {
            if(right==0uL) 0uL
            else left % right
        }
        else -> throw IllegalArgumentException("operator quad $operator")
    }
    memory.setUQ(address, result)
}
