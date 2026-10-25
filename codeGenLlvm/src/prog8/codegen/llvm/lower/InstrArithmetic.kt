package prog8.codegen.llvm.lower

import prog8.codegen.llvm.model.BinaryOperator
import prog8.codegen.llvm.model.CastOperator
import prog8.codegen.llvm.model.FcmpCondition
import prog8.codegen.llvm.model.IcmpCondition
import prog8.codegen.llvm.model.LlvmInstruction
import prog8.codegen.llvm.model.LlvmType
import prog8.codegen.llvm.model.LlvmValue
import prog8.intermediate.IRDataType
import prog8.intermediate.IRInstruction
import prog8.intermediate.Opcode
import prog8.intermediate.requireDest
import prog8.intermediate.requireDestB
import prog8.intermediate.requireSrcA

/** Lowers integer and float arithmetic on registers and memory, and the float functions and conversions. */
internal fun FunctionLowering.lowerArithmetic(ins: IRInstruction) {
    when (ins.opcode) {
        Opcode.INC, Opcode.INCM -> modify(ins, wide = true) { value, base -> step(value, up = true, base) }
        Opcode.DEC, Opcode.DECM -> modify(ins, wide = true) { value, base -> step(value, up = false, base) }
        Opcode.NEG, Opcode.NEGM -> modify(ins, wide = true) { value, base -> negate(value, base) }
        Opcode.ADD, Opcode.ADDR, Opcode.ADDM, Opcode.ADDIM -> combine(ins, BinaryOperator.ADD)
        Opcode.SUB, Opcode.SUBR, Opcode.SUBM, Opcode.SUBIM -> combine(ins, BinaryOperator.SUB)
        Opcode.MUL, Opcode.MULR, Opcode.MULM -> multiply(ins, signed = false)
        Opcode.MULS, Opcode.MULSR, Opcode.MULSM -> multiply(ins, signed = true)
        Opcode.DIV, Opcode.DIVR, Opcode.DIVM -> divide(ins, signed = false, remainder = false)
        Opcode.DIVS, Opcode.DIVSR, Opcode.DIVSM -> divide(ins, signed = true, remainder = false)
        Opcode.MOD, Opcode.MODR -> divide(ins, signed = false, remainder = true)
        Opcode.MODS, Opcode.MODSR -> divide(ins, signed = true, remainder = true)
        Opcode.DIVMOD, Opcode.DIVMODR -> divideWithRemainder(ins, signed = false)
        Opcode.SDIVMOD, Opcode.SDIVMODR -> divideWithRemainder(ins, signed = true)
        Opcode.SQUARE -> {
            val dest = ins.requireDest()
            val value = read(ins.requireSrcA())
            write(dest, operation(BinaryOperator.MUL, value, value, dest.register.toString()))
        }
        Opcode.SQRT -> squareRoot(ins)
        Opcode.FFROMUB, Opcode.FFROMUW, Opcode.FFROMUQ -> toFloat(ins, CastOperator.UITOFP)
        Opcode.FFROMSB, Opcode.FFROMSW, Opcode.FFROMSL, Opcode.FFROMSQ -> toFloat(ins, CastOperator.SITOFP)
        Opcode.FTOUB, Opcode.FTOSB, Opcode.FTOUW, Opcode.FTOSW, Opcode.FTOSL, Opcode.FTOSQ ->
            ins.requireDest().let { write(it, floatToInteger(read(ins.requireSrcA()), typeOf(it.type))) }
        Opcode.FTOUQ -> ins.requireDest().let { write(it, floatToUnsignedQuad(read(ins.requireSrcA()), it.register.toString())) }
        Opcode.FPOW -> ins.requireDest().let { write(it, intrinsic("llvm.pow.f64", LlvmType.Double, read(it), read(ins.requireSrcA()))) }
        Opcode.FABS -> floatFunction(ins, "llvm.fabs.f64")
        Opcode.FSIN -> floatFunction(ins, "llvm.sin.f64")
        Opcode.FCOS -> floatFunction(ins, "llvm.cos.f64")
        // the C library's, as LLVM only has the llvm.tan and llvm.atan intrinsics from version 19 on
        Opcode.FTAN -> floatFunction(ins, "tan")
        Opcode.FATAN -> floatFunction(ins, "atan")
        Opcode.FLN -> floatFunction(ins, "llvm.log.f64")
        Opcode.FLOG -> floatFunction(ins, "llvm.log2.f64")
        // the VM rounds with Kotlin's round(), which rounds half to even
        Opcode.FROUND -> floatFunction(ins, "llvm.roundeven.f64")
        Opcode.FFLOOR -> floatFunction(ins, "llvm.floor.f64")
        Opcode.FCEIL -> floatFunction(ins, "llvm.ceil.f64")
        else -> throw IllegalArgumentException("${ins.opcode} is no arithmetic instruction")
    }
}

/**
 * Applies [operation] to the register or the memory that [ins] modifies in place.
 * With [wide], a LONG register is operated on with its whole slot, so that an address in it stays whole.
 */
internal fun FunctionLowering.modify(ins: IRInstruction, wide: Boolean = false, operation: (LlvmValue, String) -> LlvmValue) {
    if (ins.memory != null)
        storeMemory(ins, operation(loadMemory(ins, "mem"), "mem"))
    else {
        val dest = ins.requireDest()
        write(dest, operation(if (wide) readWide(dest) else read(dest), dest.register.toString()))
    }
}

/** The binary operations that combine a register or memory, in place, with a register or an immediate. */
internal fun FunctionLowering.combine(ins: IRInstruction, operator: BinaryOperator) =
    modify(ins, wide = true) { left, base ->
        val right = ins.srcA?.let { readWide(it) } ?: immediate(ins, if (left.type == LlvmType.Ptr) builder.mapper.intPtr else left.type)
        operation(operator, left, right, base)
    }

/** A multiplication; one of words keeps the upper 16 bits of its 32-bit product for math.mul16_last_upper when the program calls that. */
private fun FunctionLowering.multiply(ins: IRInstruction, signed: Boolean) {
    if (ins.type != IRDataType.WORD || !builder.keepsMul16Upper)
        return combine(ins, BinaryOperator.MUL)
    modify(ins, wide = true) { left, base ->
        val right = ins.srcA?.let { convert(read(it), LlvmType.I16, base) } ?: immediate(ins, LlvmType.I16)
        val product = operation(BinaryOperator.MUL, widen(left, LlvmType.I32, signed, base), widen(right, LlvmType.I32, signed, base), base)
        val upper = derive(base, LlvmType.I32) { LlvmInstruction.BinaryOp(BinaryOperator.LSHR, it, product, LlvmValue.IntConstant(16, LlvmType.I32)) }
        emit(LlvmInstruction.Store(convert(upper, LlvmType.I16, base), builder.mul16Upper()))
        convert(product, LlvmType.I16, base)
    }
}

/** [left] [operator] [right] on integers or floats; a pointer gets an integer added or subtracted as a byte offset. */
internal fun FunctionLowering.operation(operator: BinaryOperator, left: LlvmValue, right: LlvmValue, base: String): LlvmValue {
    if (left.type == LlvmType.Double) {
        val floatOperator = when (operator) {
            BinaryOperator.ADD -> BinaryOperator.FADD
            BinaryOperator.SUB -> BinaryOperator.FSUB
            BinaryOperator.MUL -> BinaryOperator.FMUL
            else -> throw IllegalArgumentException("${sub.label}: ${operator.keyword} doesn't work on floats")
        }
        return derive(base, LlvmType.Double) { LlvmInstruction.BinaryOp(floatOperator, it, left, right) }
    }
    if (left.type == LlvmType.Ptr && right.type != LlvmType.Ptr && (operator == BinaryOperator.ADD || operator == BinaryOperator.SUB)) {
        val intPtr = builder.mapper.intPtr
        val offset = if (checkNotNull(right.type.bitWidth) > checkNotNull(intPtr.bitWidth)) convert(right, intPtr, base) else widen(right, intPtr, signed = true, base)
        val distance = if (operator == BinaryOperator.ADD) offset else negate(offset, base)
        return derive(base, LlvmType.Ptr) { LlvmInstruction.GetElementPtr(it, LlvmType.I8, left, listOf(distance), inBounds = false) }
    }
    val number = asInteger(left)
    val operand = asInteger(right).let {
        if (checkNotNull(it.type.bitWidth) < checkNotNull(number.type.bitWidth)) widen(it, number.type, signed = true, base) else convert(it, number.type, base)
    }
    return derive(base, number.type) { LlvmInstruction.BinaryOp(operator, it, number, operand) }
}

private fun FunctionLowering.step(value: LlvmValue, up: Boolean, base: String): LlvmValue = when (value.type) {
    LlvmType.Double -> derive(base, LlvmType.Double) {
        LlvmInstruction.BinaryOp(if (up) BinaryOperator.FADD else BinaryOperator.FSUB, it, value, LlvmValue.FloatConstant(1.0))
    }
    LlvmType.Ptr -> derive(base, LlvmType.Ptr) {
        LlvmInstruction.GetElementPtr(it, LlvmType.I8, value, listOf(LlvmValue.IntConstant(if (up) 1 else -1, builder.mapper.intPtr)), inBounds = false)
    }
    else -> derive(base, value.type) {
        LlvmInstruction.BinaryOp(if (up) BinaryOperator.ADD else BinaryOperator.SUB, it, value, LlvmValue.IntConstant(1, value.type))
    }
}

private fun FunctionLowering.negate(value: LlvmValue, base: String): LlvmValue {
    if (value.type == LlvmType.Double)
        return derive(base, LlvmType.Double) { LlvmInstruction.FNeg(it, value) }
    val number = asInteger(value)
    if (number is LlvmValue.IntConstant)
        return integerConstant(-number.value, number.type)
    return derive(base, number.type) { LlvmInstruction.BinaryOp(BinaryOperator.SUB, it, LlvmValue.IntConstant(0, number.type), number) }
}

/** DIV, DIVS, MOD and MODS of a register or memory, by a register or an immediate. */
private fun FunctionLowering.divide(ins: IRInstruction, signed: Boolean, remainder: Boolean) =
    modify(ins) { left, base ->
        if (left.type == LlvmType.Double)
            floatQuotient(left, ins.srcA?.let { read(it) } ?: immediate(ins, LlvmType.Double), base)
        else {
            val dividend = divisible(left)
            val divisor = ins.srcA?.let { divisible(read(it)) } ?: immediate(ins, dividend.type)
            integerQuotient(dividend, divisor, signed, remainder, base)
        }
    }

/** DIVMOD and SDIVMOD put the quotient in their first register and the remainder in the second; DIVMODR's second register also holds the divisor. */
private fun FunctionLowering.divideWithRemainder(ins: IRInstruction, signed: Boolean) {
    val quotientRegister = ins.requireDest()
    val remainderRegister = ins.requireDestB()
    val dividend = divisible(read(quotientRegister))
    val divisor = if (ins.immediate != null) immediate(ins, dividend.type) else divisible(read(remainderRegister))
    val quotient = integerQuotient(dividend, divisor, signed, remainder = false, quotientRegister.register.toString())
    val rest = integerQuotient(dividend, divisor, signed, remainder = true, remainderRegister.register.toString())
    write(quotientRegister, quotient)
    write(remainderRegister, rest)
}

// the VM divides pointers as 32-bit longs
private fun FunctionLowering.divisible(value: LlvmValue): LlvmValue = if (value.type == LlvmType.Ptr) convert(value, LlvmType.I32) else value

/**
 * Division by zero gives the results of the VM instead of undefined behavior: the largest (signed) value, or a remainder of 0.
 * Signed operations work one size wider, where MIN / -1 doesn't overflow; quads have no wider type, see [signedQuadQuotient].
 */
private fun FunctionLowering.integerQuotient(dividend: LlvmValue, divisor: LlvmValue, signed: Boolean, remainder: Boolean, base: String): LlvmValue {
    val type = dividend.type
    val bits = checkNotNull(type.bitWidth) { "${sub.label}: can't divide a $type value" }
    val byZero = when {
        remainder -> 0L
        signed -> (1L shl (bits - 1)) - 1
        else -> -1L
    }
    if (divisor is LlvmValue.IntConstant && divisor.value == 0L)
        return integerConstant(byZero, type)
    if (signed && type == LlvmType.I64)
        return signedQuadQuotient(dividend, divisor, remainder, byZero, base)
    val computeType = if (signed) widerThan(type) else type
    val isZero = if (divisor is LlvmValue.IntConstant) null
        else derive("zero", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.EQ, it, divisor, LlvmValue.IntConstant(0, type)) }
    val widenedDivisor = widen(divisor, computeType, signed, "divisor")
    val safeDivisor = isZero?.let { zero ->
        derive("divisor", computeType) { LlvmInstruction.Select(it, zero, LlvmValue.IntConstant(1, computeType), widenedDivisor) }
    } ?: widenedDivisor
    val widenedDividend = widen(dividend, computeType, signed, "dividend")
    val operator = when {
        signed && remainder -> BinaryOperator.SREM
        signed -> BinaryOperator.SDIV
        remainder -> BinaryOperator.UREM
        else -> BinaryOperator.UDIV
    }
    val result = convert(derive(base, computeType) { LlvmInstruction.BinaryOp(operator, it, widenedDividend, safeDivisor) }, type, base)
    return isZero?.let { zero -> derive(base, type) { LlvmInstruction.Select(it, zero, integerConstant(byZero, type), result) } } ?: result
}

/**
 * Signed 64-bit division by a [divisor] other than the constant 0. Where LLVM's sdiv and srem are undefined, it gives the VM's results:
 * [byZero] for a divisor of 0, and for -1 the wrapped negation (MIN / -1 is MIN) and a remainder of 0.
 */
private fun FunctionLowering.signedQuadQuotient(dividend: LlvmValue, divisor: LlvmValue, remainder: Boolean, byZero: Long, base: String): LlvmValue {
    val type = LlvmType.I64
    val operator = if (remainder) BinaryOperator.SREM else BinaryOperator.SDIV
    if (divisor is LlvmValue.IntConstant) {
        if (divisor.value == -1L)
            return if (remainder) LlvmValue.IntConstant(0, type) else negate(dividend, base)
        return derive(base, type) { LlvmInstruction.BinaryOp(operator, it, dividend, divisor) }
    }
    val isZero = derive("zero", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.EQ, it, divisor, LlvmValue.IntConstant(0, type)) }
    val isMinusOne = derive("minusone", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.EQ, it, divisor, LlvmValue.IntConstant(-1, type)) }
    val unsafe = derive("unsafe", LlvmType.I1) { LlvmInstruction.BinaryOp(BinaryOperator.OR, it, isZero, isMinusOne) }
    val safeDivisor = derive("divisor", type) { LlvmInstruction.Select(it, unsafe, LlvmValue.IntConstant(1, type), divisor) }
    val result = derive(base, type) { LlvmInstruction.BinaryOp(operator, it, dividend, safeDivisor) }
    // the remainder of dividing by 1 is already the 0 that both cases need
    if (remainder)
        return result
    val negated = negate(dividend, base)
    val quotient = derive(base, type) { LlvmInstruction.Select(it, isMinusOne, negated, result) }
    return derive(base, type) { LlvmInstruction.Select(it, isZero, LlvmValue.IntConstant(byZero, type), quotient) }
}

private fun widerThan(type: LlvmType): LlvmType = when (type) {
    LlvmType.I8 -> LlvmType.I16
    LlvmType.I16 -> LlvmType.I32
    LlvmType.I32 -> LlvmType.I64
    else -> throw IllegalArgumentException("there is no integer type wider than $type")
}

/** The VM gives the largest float for a division by zero. */
private fun FunctionLowering.floatQuotient(dividend: LlvmValue, divisor: LlvmValue, base: String): LlvmValue {
    if (divisor is LlvmValue.FloatConstant && divisor.value == 0.0)
        return LlvmValue.FloatConstant(Double.MAX_VALUE)
    val quotient = derive(base, LlvmType.Double) { LlvmInstruction.BinaryOp(BinaryOperator.FDIV, it, dividend, divisor) }
    if (divisor is LlvmValue.FloatConstant)
        return quotient
    val isZero = derive("zero", LlvmType.I1) { LlvmInstruction.Fcmp(FcmpCondition.OEQ, it, divisor, LlvmValue.FloatConstant(0.0)) }
    return derive(base, LlvmType.Double) { LlvmInstruction.Select(it, isZero, LlvmValue.FloatConstant(Double.MAX_VALUE), quotient) }
}

/** The square root of an integer is an integer, truncated like the VM does; the VM takes a long as signed. */
private fun FunctionLowering.squareRoot(ins: IRInstruction) {
    val dest = ins.requireDest()
    val value = read(ins.requireSrcA())
    if (value.type == LlvmType.Double)
        write(dest, intrinsic("llvm.sqrt.f64", LlvmType.Double, value))
    else {
        val operator = if (ins.type == IRDataType.LONG) CastOperator.SITOFP else CastOperator.UITOFP
        val number = asInteger(value)
        val float = derive("float", LlvmType.Double) { LlvmInstruction.Cast(operator, it, number, LlvmType.Double) }
        write(dest, floatToInteger(intrinsic("llvm.sqrt.f64", LlvmType.Double, float), typeOf(dest.type)))
    }
}

/** Like the VM's Kotlin toInt() (toLong() for a quad), which saturates and turns NaN into 0, followed by truncation to [type]. */
private fun FunctionLowering.floatToInteger(value: LlvmValue, type: LlvmType): LlvmValue =
    if (type == LlvmType.I64) intrinsic("llvm.fptosi.sat.i64.f64", LlvmType.I64, value)
    else convert(intrinsic("llvm.fptosi.sat.i32.f64", LlvmType.I32, value), type)

/** Like the VM, a negative float wraps around as with a signed conversion, others saturate to the uquad range; NaN gives 0. */
private fun FunctionLowering.floatToUnsignedQuad(value: LlvmValue, base: String): LlvmValue {
    val negative = derive("negative", LlvmType.I1) { LlvmInstruction.Fcmp(FcmpCondition.OLT, it, value, LlvmValue.FloatConstant(0.0)) }
    val signed = intrinsic("llvm.fptosi.sat.i64.f64", LlvmType.I64, value)
    val unsigned = intrinsic("llvm.fptoui.sat.i64.f64", LlvmType.I64, value)
    return derive(base, LlvmType.I64) { LlvmInstruction.Select(it, negative, signed, unsigned) }
}

private fun FunctionLowering.toFloat(ins: IRInstruction, operator: CastOperator) {
    val value = read(ins.requireSrcA())
    write(ins.requireDest(), derive("float", LlvmType.Double) { LlvmInstruction.Cast(operator, it, value, LlvmType.Double) })
}

private fun FunctionLowering.floatFunction(ins: IRInstruction, name: String) =
    write(ins.requireDest(), intrinsic(name, LlvmType.Double, read(ins.requireSrcA())))
