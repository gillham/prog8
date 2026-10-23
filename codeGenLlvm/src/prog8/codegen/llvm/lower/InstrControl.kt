package prog8.codegen.llvm.lower

import prog8.codegen.llvm.model.BinaryOperator
import prog8.codegen.llvm.model.CastOperator
import prog8.codegen.llvm.model.FcmpCondition
import prog8.codegen.llvm.model.IcmpCondition
import prog8.codegen.llvm.model.LlvmInstruction
import prog8.codegen.llvm.model.LlvmType
import prog8.codegen.llvm.model.LlvmValue
import prog8.intermediate.CallingConventionSlot
import prog8.intermediate.CodeReference
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
        Opcode.JUMPI -> jumpIndirect(ins)
        Opcode.RETURN -> emit(LlvmInstruction.Ret(if (returnType == LlvmType.Void) null else LlvmValue.ZeroInitializer(returnType)))
        Opcode.RETURNR -> emit(LlvmInstruction.Ret(convert(readWide(ins.requireSrcA()), returnType)))
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
        Opcode.CMP -> setComparison(asInteger(read(ins.requireSrcA())), asInteger(read(ins.requireSrcB())))
        Opcode.CMPI -> {
            val left = asInteger(read(ins.requireSrcA()))
            setComparison(left, immediate(ins, left.type))
        }
        Opcode.SGN -> sign(ins)
        Opcode.BITTST -> bitTest(ins)
        Opcode.SEC -> setFlag(Flag.CARRY, LlvmValue.IntConstant(1, LlvmType.I1))
        Opcode.CLC -> setFlag(Flag.CARRY, LlvmValue.IntConstant(0, LlvmType.I1))
        // a hosted program has no interrupt disable flag
        Opcode.CLI, Opcode.SEI -> {}
        Opcode.FCOMP -> floatCompare(ins)
        Opcode.BREAKPOINT -> emit(LlvmInstruction.Call(null, external("llvm.debugtrap", LlvmType.Void, emptyList()), emptyList(), LlvmType.Void))
        else -> throw IllegalArgumentException("${ins.opcode} is no control flow instruction")
    }
}

/** The branches that compare a register with a register or an immediate themselves, without status flags. */
private fun FunctionLowering.compareAndBranch(ins: IRInstruction, condition: IcmpCondition) {
    val left = asInteger(read(ins.requireSrcA()))
    val right = ins.srcB?.let { asInteger(read(it)) } ?: immediate(ins, left.type)
    val taken = derive("cond", LlvmType.I1) { LlvmInstruction.Icmp(condition, it, left, right) }
    emit(LlvmInstruction.CondBr(taken, blockOf(ins.requireTarget()), notTakenTarget()))
}

/**
 * LLVM IR can't jump to an address, so JUMPI calls the routine there and returns what that returns.
 * This matches a jump to a subroutine without parameters; the llvm targets can't take the address of a label.
 * With strict indirect signatures the routine is called as `void ()`, and a function that returns a value returns
 * what the indirect entry of a value-returning routine left in calling convention slot 10.
 */
private fun FunctionLowering.jumpIndirect(ins: IRInstruction) {
    val reference = requireNotNull(ins.requireTarget() as? CodeReference.Indirect) { "JUMPI in ${sub.label} needs an indirect target" }
    val address = pointerValue(reference.pointer)
    forgetFlags()
    if (returnType == LlvmType.Void) {
        emit(LlvmInstruction.Call(null, address, emptyList(), LlvmType.Void))
        emit(LlvmInstruction.Ret(null))
    } else if (builder.strictIndirectSignatures) {
        val float = returnType == LlvmType.Double
        val slot = builder.hardwareSlot(CallingConventionSlot(10), float)
        emit(LlvmInstruction.Store(LlvmValue.ZeroInitializer(if (float) LlvmType.Double else LlvmType.I64), slot))
        emit(LlvmInstruction.Call(null, address, emptyList(), LlvmType.Void))
        val result = derive("jumpi", if (float) LlvmType.Double else LlvmType.I64) { LlvmInstruction.Load(it, slot) }
        emit(LlvmInstruction.Ret(convert(result, returnType)))
    } else
        emit(LlvmInstruction.Ret(derive("jumpi", returnType) { LlvmInstruction.Call(it, address, emptyList(), returnType) }))
}

/** SGN: the byte -1, 0 or 1 for the sign of the (signed) operand, and the flags of comparing that with 0. */
private fun FunctionLowering.sign(ins: IRInstruction) {
    val value = read(ins.requireSrcA())
    val sign = if (value.type == LlvmType.Double) {
        val zero = LlvmValue.FloatConstant(0.0)
        minusZeroPlus(derive("neg", LlvmType.I1) { LlvmInstruction.Fcmp(FcmpCondition.OLT, it, value, zero) },
            derive("pos", LlvmType.I1) { LlvmInstruction.Fcmp(FcmpCondition.OGT, it, value, zero) })
    } else {
        val number = asInteger(value)
        val zero = LlvmValue.IntConstant(0, number.type)
        minusZeroPlus(derive("neg", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.SLT, it, number, zero) },
            derive("pos", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.SGT, it, number, zero) })
    }
    write(ins.requireIntDest(), sign)
    setComparison(sign, LlvmValue.IntConstant(0, LlvmType.I8))
}

/** BITTST only sets the zero flag: set when the tested bit is 0. */
private fun FunctionLowering.bitTest(ins: IRInstruction) {
    val value = asInteger(read(ins.requireSrcA()))
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
