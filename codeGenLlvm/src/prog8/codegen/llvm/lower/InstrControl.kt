package prog8.codegen.llvm.lower

import prog8.codegen.llvm.model.BinaryOperator
import prog8.codegen.llvm.model.CastOperator
import prog8.codegen.llvm.model.FcmpCondition
import prog8.codegen.llvm.model.IcmpCondition
import prog8.codegen.llvm.model.LlvmInstruction
import prog8.codegen.llvm.model.LlvmType
import prog8.codegen.llvm.model.LlvmValue
import prog8.intermediate.IRInstruction
import prog8.intermediate.Opcode
import prog8.intermediate.requireImmediateInt
import prog8.intermediate.requireIntDest
import prog8.intermediate.requireSrcA
import prog8.intermediate.requireSrcB
import prog8.intermediate.requireTarget

/** Lowers jumps, branches and returns, and the instructions that set the status flags those branches read. */
internal fun FunctionLowering.lowerControl(ins: IRInstruction) {
    when (ins.opcode) {
        Opcode.JUMP -> emit(LlvmInstruction.Br(blockOf(ins.requireTarget())))
        Opcode.RETURN -> emit(LlvmInstruction.Ret(if (returnType == LlvmType.Void) null else LlvmValue.ZeroInitializer(returnType)))
        Opcode.RETURNR -> emit(LlvmInstruction.Ret(convert(read(ins.requireSrcA()), returnType)))
        Opcode.RETURNI -> emit(LlvmInstruction.Ret(immediate(ins, returnType)))
        Opcode.BSTCC -> branchOnFlag(Flag.CARRY, whenSet = false, blockOf(ins.requireTarget()))
        Opcode.BSTCS -> branchOnFlag(Flag.CARRY, whenSet = true, blockOf(ins.requireTarget()))
        Opcode.BSTNE -> branchOnFlag(Flag.ZERO, whenSet = false, blockOf(ins.requireTarget()))
        Opcode.BSTEQ -> branchOnFlag(Flag.ZERO, whenSet = true, blockOf(ins.requireTarget()))
        Opcode.BSTPOS -> branchOnFlag(Flag.NEGATIVE, whenSet = false, blockOf(ins.requireTarget()))
        Opcode.BSTNEG -> branchOnFlag(Flag.NEGATIVE, whenSet = true, blockOf(ins.requireTarget()))
        Opcode.BSTVC -> branchOnFlag(Flag.OVERFLOW, whenSet = false, blockOf(ins.requireTarget()))
        Opcode.BSTVS -> branchOnFlag(Flag.OVERFLOW, whenSet = true, blockOf(ins.requireTarget()))
        Opcode.BGTR, Opcode.BGT -> compareAndBranch(ins, IcmpCondition.UGT)
        Opcode.BLT -> compareAndBranch(ins, IcmpCondition.ULT)
        Opcode.BGER, Opcode.BGE -> compareAndBranch(ins, IcmpCondition.UGE)
        Opcode.BLE -> compareAndBranch(ins, IcmpCondition.ULE)
        Opcode.BGTSR, Opcode.BGTS -> compareAndBranch(ins, IcmpCondition.SGT)
        Opcode.BLTS -> compareAndBranch(ins, IcmpCondition.SLT)
        Opcode.BGESR, Opcode.BGES -> compareAndBranch(ins, IcmpCondition.SGE)
        Opcode.BLES -> compareAndBranch(ins, IcmpCondition.SLE)
        Opcode.CMP -> setComparison(comparable(read(ins.requireSrcA())), comparable(read(ins.requireSrcB())))
        Opcode.CMPI -> {
            val left = comparable(read(ins.requireSrcA()))
            setComparison(left, immediate(ins, left.type))
        }
        Opcode.SGN -> sign(ins)
        Opcode.BITTST -> bitTest(ins)
        Opcode.SEC -> setFlag(Flag.CARRY, LlvmValue.IntConstant(1, LlvmType.I1))
        Opcode.CLC -> setFlag(Flag.CARRY, LlvmValue.IntConstant(0, LlvmType.I1))
        Opcode.FCOMP -> floatCompare(ins)
        else -> throw IllegalArgumentException("${ins.opcode} is no control flow instruction")
    }
}

/** The branches that compare a register with a register or an immediate themselves, without status flags. */
private fun FunctionLowering.compareAndBranch(ins: IRInstruction, condition: IcmpCondition) {
    val left = comparable(read(ins.requireSrcA()))
    val right = ins.srcB?.let { comparable(read(it)) } ?: immediate(ins, left.type)
    val taken = derive("cond", LlvmType.I1) { LlvmInstruction.Icmp(condition, it, left, right) }
    emit(LlvmInstruction.CondBr(taken, blockOf(ins.requireTarget()), notTakenTarget()))
}

/** SGN: the byte -1, 0 or 1 for the sign of the (signed) operand, and the flags of comparing that with 0. */
private fun FunctionLowering.sign(ins: IRInstruction) {
    val value = read(ins.requireSrcA())
    val sign = if (value.type == LlvmType.Double) {
        val zero = LlvmValue.FloatConstant(0.0)
        minusZeroPlus(derive("neg", LlvmType.I1) { LlvmInstruction.Fcmp(FcmpCondition.OLT, it, value, zero) },
            derive("pos", LlvmType.I1) { LlvmInstruction.Fcmp(FcmpCondition.OGT, it, value, zero) })
    } else {
        val number = comparable(value)
        val zero = LlvmValue.IntConstant(0, number.type)
        minusZeroPlus(derive("neg", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.SLT, it, number, zero) },
            derive("pos", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.SGT, it, number, zero) })
    }
    write(ins.requireIntDest(), sign)
    setComparison(sign, LlvmValue.IntConstant(0, LlvmType.I8))
}

/** BITTST only sets the zero flag: set when the tested bit is 0. */
private fun FunctionLowering.bitTest(ins: IRInstruction) {
    val value = comparable(read(ins.requireSrcA()))
    val bit = derive("bit", value.type) { LlvmInstruction.BinaryOp(BinaryOperator.AND, it, value, integerConstant(1L shl ins.requireImmediateInt(), value.type)) }
    setFlag(Flag.ZERO, derive("cond", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.EQ, it, bit, LlvmValue.IntConstant(0, value.type)) })
}

/** FCOMP doesn't set flags: it gives the byte -1, 0 or 1 when the left float is smaller, equal (or NaN), or larger. */
private fun FunctionLowering.floatCompare(ins: IRInstruction) {
    val left = read(ins.requireSrcA())
    val right = read(ins.requireSrcB())
    val smaller = derive("lt", LlvmType.I1) { LlvmInstruction.Fcmp(FcmpCondition.OLT, it, left, right) }
    val larger = derive("gt", LlvmType.I1) { LlvmInstruction.Fcmp(FcmpCondition.OGT, it, left, right) }
    write(ins.requireIntDest(), minusZeroPlus(smaller, larger))
}

private fun FunctionLowering.minusZeroPlus(negative: LlvmValue, positive: LlvmValue): LlvmValue {
    val minus = derive("sign", LlvmType.I8) { LlvmInstruction.Cast(CastOperator.ZEXT, it, negative, LlvmType.I8) }
    val plus = derive("sign", LlvmType.I8) { LlvmInstruction.Cast(CastOperator.ZEXT, it, positive, LlvmType.I8) }
    return derive("sign", LlvmType.I8) { LlvmInstruction.BinaryOp(BinaryOperator.SUB, it, plus, minus) }
}

/** Pointers are compared as 64-bit integers, so they can also be subtracted for the negative and overflow flags. */
private fun FunctionLowering.comparable(value: LlvmValue): LlvmValue =
    if (value.type == LlvmType.Ptr) convert(value, LlvmType.I64) else value
