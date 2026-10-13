package prog8.codegen.llvm.lower

import prog8.codegen.llvm.model.BinaryOperator
import prog8.codegen.llvm.model.CastOperator
import prog8.codegen.llvm.model.IcmpCondition
import prog8.codegen.llvm.model.LlvmInstruction
import prog8.codegen.llvm.model.LlvmType
import prog8.codegen.llvm.model.LlvmValue
import prog8.intermediate.IRDataType
import prog8.intermediate.IRInstruction
import prog8.intermediate.Opcode
import prog8.intermediate.requireDest
import prog8.intermediate.requireImmediateInt
import prog8.intermediate.requireSrcA
import prog8.intermediate.requireSrcB

/** Lowers bitwise logic, shifts and rotates, single bit operations, and the extensions and extractions of bytes and words. */
internal fun FunctionLowering.lowerBitwise(ins: IRInstruction) {
    when (ins.opcode) {
        Opcode.AND, Opcode.ANDR, Opcode.ANDM -> combine(ins, BinaryOperator.AND)
        Opcode.OR, Opcode.ORR, Opcode.ORM -> combine(ins, BinaryOperator.OR)
        Opcode.XOR, Opcode.XORR, Opcode.XORM -> combine(ins, BinaryOperator.XOR)
        Opcode.INV, Opcode.INVM -> modify(ins, wide = true) { value, base ->
            asInteger(value).let { operation(BinaryOperator.XOR, it, integerConstant(-1, it.type), base) }
        }
        Opcode.LSL, Opcode.LSLM -> modify(ins) { value, base -> shiftOnce(value, BinaryOperator.SHL, base) }
        Opcode.LSR, Opcode.LSRM -> modify(ins) { value, base -> shiftOnce(value, BinaryOperator.LSHR, base) }
        Opcode.ASR, Opcode.ASRM -> modify(ins) { value, base -> shiftOnce(value, BinaryOperator.ASHR, base) }
        Opcode.LSLN, Opcode.LSLNM, Opcode.LSLI -> modify(ins) { value, base -> shift(value, shiftCount(ins), BinaryOperator.SHL, base) }
        Opcode.LSRN, Opcode.LSRNM, Opcode.LSRI -> modify(ins) { value, base -> shift(value, shiftCount(ins), BinaryOperator.LSHR, base) }
        Opcode.ASRN, Opcode.ASRNM, Opcode.ASRI -> modify(ins) { value, base -> shift(value, shiftCount(ins), BinaryOperator.ASHR, base) }
        Opcode.ROL, Opcode.ROLM -> modify(ins) { value, base -> rotate(value, left = true, throughCarry = false, base) }
        Opcode.ROR, Opcode.RORM -> modify(ins) { value, base -> rotate(value, left = false, throughCarry = false, base) }
        Opcode.ROXL, Opcode.ROXLM -> modify(ins) { value, base -> rotate(value, left = true, throughCarry = true, base) }
        Opcode.ROXR, Opcode.ROXRM -> modify(ins) { value, base -> rotate(value, left = false, throughCarry = true, base) }
        Opcode.BITSET, Opcode.BITCLR, Opcode.BITTOG -> modify(ins, wide = true) { value, base ->
            val number = asInteger(value)
            val mask = 1L shl ins.requireImmediateInt()
            when (ins.opcode) {
                Opcode.BITSET -> operation(BinaryOperator.OR, number, integerConstant(mask, number.type), base)
                Opcode.BITCLR -> operation(BinaryOperator.AND, number, integerConstant(mask.inv(), number.type), base)
                else -> operation(BinaryOperator.XOR, number, integerConstant(mask, number.type), base)
            }
        }
        Opcode.EXT, Opcode.EXTS, Opcode.EXTL, Opcode.EXTLS -> extend(ins)
        Opcode.LSIGB, Opcode.LSIGW -> extract(ins, shift = 0)
        Opcode.MSIGB -> extract(ins, shift = if (ins.type == IRDataType.WORD) 8 else 24)
        Opcode.MSIGW, Opcode.BSIGB -> extract(ins, shift = 16)
        Opcode.MIDB -> extract(ins, shift = 8)
        Opcode.CONCAT -> concat(ins)
        else -> throw IllegalArgumentException("${ins.opcode} is no bitwise instruction")
    }
}

/** Shifts one bit, which goes into the carry flag. */
private fun FunctionLowering.shiftOnce(value: LlvmValue, operator: BinaryOperator, base: String): LlvmValue {
    val number = asInteger(value)
    setFlag(Flag.CARRY, if (operator == BinaryOperator.SHL) topBit(number) else bottomBit(number))
    return derive(base, number.type) { LlvmInstruction.BinaryOp(operator, it, number, LlvmValue.IntConstant(1, number.type)) }
}

private fun FunctionLowering.shiftCount(ins: IRInstruction): LlvmValue =
    ins.srcA?.let { read(it) } ?: LlvmValue.IntConstant(ins.requireImmediateInt().toLong(), LlvmType.I64)

/**
 * Shifts by a count, without changing the carry flag. Like in the VM, a count of the bit width or more
 * shifts out all bits (or fills with the sign bit), where LLVM's shifts would give poison.
 */
private fun FunctionLowering.shift(value: LlvmValue, count: LlvmValue, operator: BinaryOperator, base: String): LlvmValue {
    val number = asInteger(value)
    val type = number.type
    val bits = checkNotNull(type.bitWidth).toLong()
    if (count is LlvmValue.IntConstant) {
        val amount = when {
            count.value in 0..<bits -> count.value
            operator == BinaryOperator.ASHR -> bits - 1
            else -> return LlvmValue.IntConstant(0, type)
        }
        return derive(base, type) { LlvmInstruction.BinaryOp(operator, it, number, LlvmValue.IntConstant(amount, type)) }
    }
    val amount = convert(count, type, "count")
    val inRange = derive("inrange", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.ULT, it, amount, LlvmValue.IntConstant(bits, type)) }
    if (operator == BinaryOperator.ASHR) {
        val limited = derive("count", type) { LlvmInstruction.Select(it, inRange, amount, LlvmValue.IntConstant(bits - 1, type)) }
        return derive(base, type) { LlvmInstruction.BinaryOp(operator, it, number, limited) }
    }
    val shifted = derive(base, type) { LlvmInstruction.BinaryOp(operator, it, number, amount) }
    // select doesn't pass on the poison of the operand that it doesn't choose
    return derive(base, type) { LlvmInstruction.Select(it, inRange, shifted, LlvmValue.IntConstant(0, type)) }
}

/** Rotates one bit, which goes into the carry flag. The bit coming in is the old carry when rotating through it, else the bit going out. */
private fun FunctionLowering.rotate(value: LlvmValue, left: Boolean, throughCarry: Boolean, base: String): LlvmValue {
    val number = asInteger(value)
    val type = number.type
    val oldCarry = if (throughCarry) flag(Flag.CARRY) else null
    val out = if (left) topBit(number) else bottomBit(number)
    val incoming = convert(oldCarry ?: out, type, base)
    val shifted = derive(base, type) {
        LlvmInstruction.BinaryOp(if (left) BinaryOperator.SHL else BinaryOperator.LSHR, it, number, LlvmValue.IntConstant(1, type))
    }
    val topBitShift = checkNotNull(type.bitWidth) - 1
    val placed = when {
        left -> incoming
        incoming is LlvmValue.IntConstant -> integerConstant(incoming.value shl topBitShift, type)
        else -> derive(base, type) { LlvmInstruction.BinaryOp(BinaryOperator.SHL, it, incoming, LlvmValue.IntConstant(topBitShift.toLong(), type)) }
    }
    setFlag(Flag.CARRY, out)
    return derive(base, type) { LlvmInstruction.BinaryOp(BinaryOperator.OR, it, shifted, placed) }
}

private fun FunctionLowering.topBit(number: LlvmValue): LlvmValue =
    derive("carry", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.SLT, it, number, LlvmValue.IntConstant(0, number.type)) }

private fun FunctionLowering.bottomBit(number: LlvmValue): LlvmValue =
    derive("carry", LlvmType.I1) { LlvmInstruction.Cast(CastOperator.TRUNC, it, number, LlvmType.I1) }

/** EXT and EXTS widen a byte to a word or a word to a long, EXTL and EXTLS a byte to a long. */
private fun FunctionLowering.extend(ins: IRInstruction) {
    require(ins.type == IRDataType.BYTE || ins.type == IRDataType.WORD) { "${ins.opcode} in ${sub.label} can only extend a byte or a word, not a ${ins.type}" }
    val dest = ins.requireDest()
    write(dest, widen(read(ins.requireSrcA()), typeOf(dest.type), signed = ins.opcode == Opcode.EXTS || ins.opcode == Opcode.EXTLS, dest.register.toString()))
}

/** The byte or word at bit [shift] of a word or long; the destination register has the type of the part. */
private fun FunctionLowering.extract(ins: IRInstruction, shift: Int) {
    val dest = ins.requireDest()
    val number = asInteger(read(ins.requireSrcA()))
    val shifted = if (shift == 0) number
        else derive(dest.register.toString(), number.type) { LlvmInstruction.BinaryOp(BinaryOperator.LSHR, it, number, LlvmValue.IntConstant(shift.toLong(), number.type)) }
    write(dest, convert(shifted, typeOf(dest.type), dest.register.toString()))
}

/** CONCAT joins two bytes into a word or two words into a long, the first register becoming the upper half. */
private fun FunctionLowering.concat(ins: IRInstruction) {
    require(ins.type == IRDataType.BYTE || ins.type == IRDataType.WORD) { "CONCAT in ${sub.label} can only join bytes or words, not ${ins.type}" }
    val dest = ins.requireDest()
    val base = dest.register.toString()
    val type = typeOf(dest.type)
    val upper = convert(read(ins.requireSrcA()), type, base)
    val lower = convert(read(ins.requireSrcB()), type, base)
    val half = checkNotNull(typeOf(checkNotNull(ins.type)).bitWidth).toLong()
    val shifted = derive(base, type) { LlvmInstruction.BinaryOp(BinaryOperator.SHL, it, upper, LlvmValue.IntConstant(half, type)) }
    write(dest, derive(base, type) { LlvmInstruction.BinaryOp(BinaryOperator.OR, it, shifted, lower) })
}
