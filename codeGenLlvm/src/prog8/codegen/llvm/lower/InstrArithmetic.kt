package prog8.codegen.llvm.lower

import prog8.codegen.llvm.model.BinaryOperator
import prog8.codegen.llvm.model.LlvmInstruction
import prog8.codegen.llvm.model.LlvmType
import prog8.codegen.llvm.model.LlvmValue
import prog8.intermediate.IRInstruction
import prog8.intermediate.Opcode
import prog8.intermediate.requireDest

/** Lowers increments and decrements of a register or a memory location. */
internal fun FunctionLowering.lowerArithmetic(ins: IRInstruction) {
    when (ins.opcode) {
        Opcode.INC, Opcode.DEC -> {
            val register = ins.requireDest()
            write(register, step(read(register), up = ins.opcode == Opcode.INC, register.register.toString()))
        }
        Opcode.INCM, Opcode.DECM -> storeMemory(ins, step(loadMemory(ins, "mem"), up = ins.opcode == Opcode.INCM, "mem"))
        else -> throw IllegalArgumentException("${ins.opcode} is no arithmetic instruction")
    }
}

private fun FunctionLowering.step(value: LlvmValue, up: Boolean, base: String): LlvmValue = when (value.type) {
    LlvmType.Double -> derive(base, LlvmType.Double) {
        LlvmInstruction.BinaryOp(if (up) BinaryOperator.FADD else BinaryOperator.FSUB, it, value, LlvmValue.FloatConstant(1.0))
    }
    LlvmType.Ptr -> derive(base, LlvmType.Ptr) {
        LlvmInstruction.GetElementPtr(it, LlvmType.I8, value, listOf(LlvmValue.IntConstant(if (up) 1 else -1, LlvmType.I64)), inBounds = false)
    }
    else -> derive(base, value.type) {
        LlvmInstruction.BinaryOp(if (up) BinaryOperator.ADD else BinaryOperator.SUB, it, value, LlvmValue.IntConstant(1, value.type))
    }
}
