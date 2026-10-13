package prog8.codegen.llvm.lower

import prog8.codegen.llvm.LlvmPrinter
import prog8.codegen.llvm.model.BinaryOperator
import prog8.codegen.llvm.model.CastOperator
import prog8.codegen.llvm.model.FcmpCondition
import prog8.codegen.llvm.model.IcmpCondition
import prog8.codegen.llvm.model.LlvmBlock
import prog8.codegen.llvm.model.LlvmFunction
import prog8.codegen.llvm.model.LlvmInstruction
import prog8.codegen.llvm.model.LlvmLinkage
import prog8.codegen.llvm.model.LlvmType
import prog8.codegen.llvm.model.LlvmValue
import prog8.intermediate.CallSite
import prog8.intermediate.CallTarget
import prog8.intermediate.IMSyscall
import prog8.intermediate.IRInstruction
import prog8.intermediate.RegisterOperand
import prog8.intermediate.requireCallSite

// the virtual target's library calls these syscalls of the VM by number
private const val VM_EXIT = 1
private const val VM_PRINT_C = 2
private const val VM_PRINT_S = 3
private const val VM_MEMCOPY = 36
private const val VM_MEMSET = 37
private const val VM_MEMSETW = 38
private const val VM_GET_C = 69

/**
 * Lowers the syscalls that the compiler emits for builtin functions and operators, and the exit, console input and output, memory copy
 * and fill syscalls of the virtual target's library. Searches, string comparisons and string output become internal helper functions.
 */
internal fun FunctionLowering.lowerSyscall(ins: IRInstruction) {
    val site = ins.requireCallSite()
    val number = requireNotNull(site.target as? CallTarget.SystemCall) { "SYSCALL in ${sub.label} needs a syscall number" }.number
    when (number) {
        VM_EXIT -> exit(site, number)
        VM_PRINT_C -> printChar(site, number)
        VM_PRINT_S -> printString(site, number)
        VM_MEMCOPY -> memoryCopy(site, number)
        VM_MEMSET -> memorySet(site, number)
        VM_MEMSETW -> memorySetWords(site, number)
        VM_GET_C -> getChar(site, number)
        else -> when (IMSyscall.entries.firstOrNull { it.number == number }) {
            IMSyscall.COMPARE_STRINGS -> compareStrings(site, number)
            IMSyscall.STRING_CONTAINS -> stringContains(site, number)
            IMSyscall.BYTEARRAY_CONTAINS -> arrayContains(site, number, LlvmType.I8)
            IMSyscall.WORDARRAY_CONTAINS -> arrayContains(site, number, LlvmType.I16)
            IMSyscall.LONGARRAY_CONTAINS -> arrayContains(site, number, LlvmType.I32)
            IMSyscall.QUADARRAY_CONTAINS -> throw IllegalArgumentException("${sub.label} searches a quad array, which the llvm64 target doesn't support yet")
            IMSyscall.FLOATARRAY_CONTAINS -> arrayContains(site, number, LlvmType.Double)
            IMSyscall.CLAMP_UBYTE -> clamp(site, number, LlvmType.I8, signed = false)
            IMSyscall.CLAMP_BYTE -> clamp(site, number, LlvmType.I8, signed = true)
            IMSyscall.CLAMP_UWORD -> clamp(site, number, LlvmType.I16, signed = false)
            IMSyscall.CLAMP_WORD -> clamp(site, number, LlvmType.I16, signed = true)
            IMSyscall.CLAMP_LONG -> clamp(site, number, LlvmType.I32, signed = true)
            IMSyscall.MEMCOPY -> memoryCopy(site, number)
            IMSyscall.CALLFAR, IMSyscall.CALLFAR2 -> throw IllegalArgumentException("${sub.label} calls into another memory bank, which the llvm64 target doesn't have")
            IMSyscall.SPLIT_WORDARRAY_CONTAINS -> throw IllegalArgumentException("${sub.label} searches a split word array, which the llvm64 target doesn't have")
            null -> throw IllegalArgumentException("${sub.label} has syscall $number, which the llvm64 target doesn't have")
        }
    }
}

private fun FunctionLowering.arguments(site: CallSite, number: Int, count: Int): List<RegisterOperand> {
    require(site.arguments.size == count) { "syscall $number in ${sub.label} needs $count arguments, not ${site.arguments.size}" }
    return site.arguments.map { it.source }
}

private fun FunctionLowering.result(site: CallSite, value: LlvmValue) {
    site.results.singleOrNull()?.destination?.let { write(it, value) }
}

private fun FunctionLowering.callHelper(helper: LlvmValue.Global, args: List<LlvmValue>, base: String): LlvmValue =
    derive(base, LlvmType.I8) { LlvmInstruction.Call(it, helper, args, LlvmType.I8) }

/** COMPARE_STRINGS(first, second): the byte -1, 0 or 1. */
private fun FunctionLowering.compareStrings(site: CallSite, number: Int) {
    val (first, second) = arguments(site, number, 2)
    val args = listOf(pointerValue(first), pointerValue(second))
    result(site, callHelper(builder.helperFunction(".compare.strings") { compareStringsHelper() }, args, "order"))
}

/** STRING_CONTAINS(character, string): the byte 1 or 0. */
private fun FunctionLowering.stringContains(site: CallSite, number: Int) {
    val (character, string) = arguments(site, number, 2)
    val args = listOf(convert(read(character), LlvmType.I8), pointerValue(string))
    result(site, callHelper(builder.helperFunction(".contains.string") { stringContainsHelper() }, args, "found"))
}

/** The array contains checks (needle, array, number of elements): the byte 1 or 0. */
private fun FunctionLowering.arrayContains(site: CallSite, number: Int, elementType: LlvmType) {
    val (needle, array, count) = arguments(site, number, 3)
    val args = listOf(convert(read(needle), elementType), pointerValue(array), convert(read(count), LlvmType.I16))
    val name = ".contains.${LlvmPrinter.format(elementType)}"
    result(site, callHelper(builder.helperFunction(name) { containsHelper(name, elementType) }, args, "found"))
}

/** CLAMP(value, minimum, maximum) = min(max(value, minimum), maximum), as in the VM. */
private fun FunctionLowering.clamp(site: CallSite, number: Int, type: LlvmType, signed: Boolean) {
    val (value, minimum, maximum) = arguments(site, number, 3).map { convert(read(it), type) }
    val suffix = LlvmPrinter.format(type)
    val atLeastMinimum = intrinsic(if (signed) "llvm.smax.$suffix" else "llvm.umax.$suffix", type, value, minimum)
    result(site, intrinsic(if (signed) "llvm.smin.$suffix" else "llvm.umin.$suffix", type, atLeastMinimum, maximum))
}

/** EXIT(status byte) ends the program with C's exit(), which llvm64, a hosted target, can rely on. */
private fun FunctionLowering.exit(site: CallSite, number: Int) {
    val (status) = arguments(site, number, 1)
    val code = convert(read(status), LlvmType.I32)
    emit(LlvmInstruction.Call(null, external("exit", LlvmType.Void, listOf(LlvmType.I32)), listOf(code), LlvmType.Void))
}

/** PRINT_C(character) writes the byte to stdout. */
private fun FunctionLowering.printChar(site: CallSite, number: Int) {
    val (character) = arguments(site, number, 1)
    val code = convert(read(character), LlvmType.I32, "ch")
    emit(LlvmInstruction.Call(null, putchar(), listOf(code), LlvmType.I32))
}

/** PRINT_S(string) writes the zero-terminated string to stdout, without its terminating 0. */
private fun FunctionLowering.printString(site: CallSite, number: Int) {
    val (string) = arguments(site, number, 1)
    val helper = builder.helperFunction(".print.string") { printStringHelper(putchar()) }
    emit(LlvmInstruction.Call(null, helper, listOf(pointerValue(string)), LlvmType.Void))
}

// putchar() and not printf() or fputs(): those need a format string, or the platform-specific stdout symbol
private fun FunctionLowering.putchar(): LlvmValue.Global = external("putchar", LlvmType.I32, listOf(LlvmType.I32))

/** GET_C(): the next byte from stdin, read with C's getchar(); its negative EOF becomes 0, as in the VM. */
private fun FunctionLowering.getChar(site: CallSite, number: Int) {
    arguments(site, number, 0)
    val code = derive("ch", LlvmType.I32) { LlvmInstruction.Call(it, external("getchar", LlvmType.I32, emptyList()), emptyList(), LlvmType.I32) }
    result(site, convert(intrinsic("llvm.smax.i32", LlvmType.I32, code, LlvmValue.IntConstant(0, LlvmType.I32)), LlvmType.I8, "ch"))
}

/** MEMCOPY(source, target, number of bytes) becomes memmove, so that overlapping areas don't give undefined behavior. */
private fun FunctionLowering.memoryCopy(site: CallSite, number: Int) {
    val (source, target, count) = arguments(site, number, 3)
    val args = listOf(pointerValue(target), pointerValue(source), convert(read(count), LlvmType.I64), LlvmValue.IntConstant(0, LlvmType.I1))
    emit(LlvmInstruction.Call(null, external("llvm.memmove.p0.p0.i64", LlvmType.Void, args.map { it.type }), args, LlvmType.Void))
}

/** MEMSET(address, number of bytes, byte value). */
private fun FunctionLowering.memorySet(site: CallSite, number: Int) {
    val (address, count, value) = arguments(site, number, 3)
    val args = listOf(pointerValue(address), convert(read(value), LlvmType.I8), convert(read(count), LlvmType.I64), LlvmValue.IntConstant(0, LlvmType.I1))
    emit(LlvmInstruction.Call(null, external("llvm.memset.p0.i64", LlvmType.Void, args.map { it.type }), args, LlvmType.Void))
}

/** MEMSETW(address, number of words, word value). */
private fun FunctionLowering.memorySetWords(site: CallSite, number: Int) {
    val (address, count, value) = arguments(site, number, 3)
    val args = listOf(pointerValue(address), convert(read(count), LlvmType.I16), convert(read(value), LlvmType.I16))
    emit(LlvmInstruction.Call(null, builder.helperFunction(".memsetw") { setWordsHelper() }, args, LlvmType.Void))
}

/** `i8 (needle, ptr array, i16 count)`: 1 when one of the count elements equals the needle, else 0. */
private fun containsHelper(name: String, elementType: LlvmType): LlvmFunction {
    val needle = LlvmValue.Local("needle", elementType)
    val array = LlvmValue.Local("array", LlvmType.Ptr)
    val count = LlvmValue.Local("count", LlvmType.I16)
    val n = LlvmValue.Local("n", LlvmType.I64)
    val i = LlvmValue.Local("i", LlvmType.I64)
    val more = LlvmValue.Local("more", LlvmType.I1)
    val slot = LlvmValue.Local("slot", LlvmType.Ptr)
    val element = LlvmValue.Local("element", elementType)
    val found = LlvmValue.Local("found", LlvmType.I1)
    val next = LlvmValue.Local("next", LlvmType.I64)
    val equal = if (elementType == LlvmType.Double) LlvmInstruction.Fcmp(FcmpCondition.OEQ, found, element, needle)
        else LlvmInstruction.Icmp(IcmpCondition.EQ, found, element, needle)
    return helper(name, LlvmType.I8, listOf(needle, array, count),
        "entry" to listOf(LlvmInstruction.Cast(CastOperator.ZEXT, n, count, LlvmType.I64), LlvmInstruction.Br("loop")),
        "loop" to listOf(
            LlvmInstruction.Phi(i, listOf(i64(0) to "entry", next to "body")),
            LlvmInstruction.Icmp(IcmpCondition.ULT, more, i, n),
            LlvmInstruction.CondBr(more, "body", "absent")),
        "body" to listOf(
            LlvmInstruction.GetElementPtr(slot, elementType, array, listOf(i), inBounds = false),
            LlvmInstruction.Load(element, slot, align = alignment(elementType)),
            equal,
            LlvmInstruction.BinaryOp(BinaryOperator.ADD, next, i, i64(1)),
            LlvmInstruction.CondBr(found, "present", "loop")),
        "present" to listOf(LlvmInstruction.Ret(LlvmValue.IntConstant(1, LlvmType.I8))),
        "absent" to listOf(LlvmInstruction.Ret(LlvmValue.IntConstant(0, LlvmType.I8))))
}

/** `i8 (i8 wanted, ptr string)`: 1 when the zero-terminated string contains the wanted byte, which is never so for 0, else 0. */
private fun stringContainsHelper(): LlvmFunction {
    val wanted = LlvmValue.Local("wanted", LlvmType.I8)
    val string = LlvmValue.Local("string", LlvmType.Ptr)
    val i = LlvmValue.Local("i", LlvmType.I64)
    val slot = LlvmValue.Local("slot", LlvmType.Ptr)
    val char = LlvmValue.Local("char", LlvmType.I8)
    val end = LlvmValue.Local("end", LlvmType.I1)
    val found = LlvmValue.Local("found", LlvmType.I1)
    val next = LlvmValue.Local("next", LlvmType.I64)
    return helper(".contains.string", LlvmType.I8, listOf(wanted, string),
        "entry" to listOf(LlvmInstruction.Br("loop")),
        "loop" to listOf(
            LlvmInstruction.Phi(i, listOf(i64(0) to "entry", next to "more")),
            LlvmInstruction.GetElementPtr(slot, LlvmType.I8, string, listOf(i), inBounds = false),
            LlvmInstruction.Load(char, slot),
            LlvmInstruction.Icmp(IcmpCondition.EQ, end, char, LlvmValue.IntConstant(0, LlvmType.I8)),
            LlvmInstruction.CondBr(end, "absent", "more")),
        "more" to listOf(
            LlvmInstruction.Icmp(IcmpCondition.EQ, found, char, wanted),
            LlvmInstruction.BinaryOp(BinaryOperator.ADD, next, i, i64(1)),
            LlvmInstruction.CondBr(found, "present", "loop")),
        "present" to listOf(LlvmInstruction.Ret(LlvmValue.IntConstant(1, LlvmType.I8))),
        "absent" to listOf(LlvmInstruction.Ret(LlvmValue.IntConstant(0, LlvmType.I8))))
}

/** `i8 (ptr first, ptr second)`: -1, 0 or 1, comparing zero-terminated strings by unsigned bytes like the VM; a prefix comes first. */
private fun compareStringsHelper(): LlvmFunction {
    val first = LlvmValue.Local("first", LlvmType.Ptr)
    val second = LlvmValue.Local("second", LlvmType.Ptr)
    val i = LlvmValue.Local("i", LlvmType.I64)
    val firstSlot = LlvmValue.Local("first.slot", LlvmType.Ptr)
    val firstChar = LlvmValue.Local("first.char", LlvmType.I8)
    val secondSlot = LlvmValue.Local("second.slot", LlvmType.Ptr)
    val secondChar = LlvmValue.Local("second.char", LlvmType.I8)
    val equal = LlvmValue.Local("equal", LlvmType.I1)
    val end = LlvmValue.Local("end", LlvmType.I1)
    val next = LlvmValue.Local("next", LlvmType.I64)
    val less = LlvmValue.Local("less", LlvmType.I1)
    val order = LlvmValue.Local("order", LlvmType.I8)
    return helper(".compare.strings", LlvmType.I8, listOf(first, second),
        "entry" to listOf(LlvmInstruction.Br("loop")),
        "loop" to listOf(
            LlvmInstruction.Phi(i, listOf(i64(0) to "entry", next to "same")),
            LlvmInstruction.GetElementPtr(firstSlot, LlvmType.I8, first, listOf(i), inBounds = false),
            LlvmInstruction.Load(firstChar, firstSlot),
            LlvmInstruction.GetElementPtr(secondSlot, LlvmType.I8, second, listOf(i), inBounds = false),
            LlvmInstruction.Load(secondChar, secondSlot),
            LlvmInstruction.Icmp(IcmpCondition.EQ, equal, firstChar, secondChar),
            LlvmInstruction.CondBr(equal, "same", "differ")),
        "same" to listOf(
            LlvmInstruction.Icmp(IcmpCondition.EQ, end, firstChar, LlvmValue.IntConstant(0, LlvmType.I8)),
            LlvmInstruction.BinaryOp(BinaryOperator.ADD, next, i, i64(1)),
            LlvmInstruction.CondBr(end, "done", "loop")),
        "done" to listOf(LlvmInstruction.Ret(LlvmValue.IntConstant(0, LlvmType.I8))),
        "differ" to listOf(
            LlvmInstruction.Icmp(IcmpCondition.ULT, less, firstChar, secondChar),
            LlvmInstruction.Select(order, less, LlvmValue.IntConstant(-1, LlvmType.I8), LlvmValue.IntConstant(1, LlvmType.I8)),
            LlvmInstruction.Ret(order)))
}

/** `void (ptr str)`: writes the bytes of the zero-terminated string with [putchar], up to its terminating 0. */
private fun printStringHelper(putchar: LlvmValue.Global): LlvmFunction {
    val str = LlvmValue.Local("str", LlvmType.Ptr)
    val currentPointer = LlvmValue.Local("curr_ptr", LlvmType.Ptr)
    val ch = LlvmValue.Local("ch", LlvmType.I8)
    val end = LlvmValue.Local("end", LlvmType.I1)
    val code = LlvmValue.Local("ch_ext", LlvmType.I32)
    val next = LlvmValue.Local("next", LlvmType.Ptr)
    return helper(".print.string", LlvmType.Void, listOf(str),
        "entry" to listOf(LlvmInstruction.Br("loop")),
        "loop" to listOf(
            LlvmInstruction.Phi(currentPointer, listOf(str to "entry", next to "print")),
            LlvmInstruction.Load(ch, currentPointer),
            LlvmInstruction.Icmp(IcmpCondition.EQ, end, ch, LlvmValue.IntConstant(0, LlvmType.I8)),
            LlvmInstruction.CondBr(end, "done", "print")),
        "print" to listOf(
            LlvmInstruction.Cast(CastOperator.ZEXT, code, ch, LlvmType.I32),
            LlvmInstruction.Call(null, putchar, listOf(code), LlvmType.I32),
            LlvmInstruction.GetElementPtr(next, LlvmType.I8, currentPointer, listOf(i64(1)), inBounds = false),
            LlvmInstruction.Br("loop")),
        "done" to listOf(LlvmInstruction.Ret(null)))
}

/** `void (ptr address, i16 count, i16 value)`: stores count copies of the word value from address on. */
private fun setWordsHelper(): LlvmFunction {
    val address = LlvmValue.Local("address", LlvmType.Ptr)
    val count = LlvmValue.Local("count", LlvmType.I16)
    val value = LlvmValue.Local("value", LlvmType.I16)
    val n = LlvmValue.Local("n", LlvmType.I64)
    val i = LlvmValue.Local("i", LlvmType.I64)
    val more = LlvmValue.Local("more", LlvmType.I1)
    val slot = LlvmValue.Local("slot", LlvmType.Ptr)
    val next = LlvmValue.Local("next", LlvmType.I64)
    return helper(".memsetw", LlvmType.Void, listOf(address, count, value),
        "entry" to listOf(LlvmInstruction.Cast(CastOperator.ZEXT, n, count, LlvmType.I64), LlvmInstruction.Br("loop")),
        "loop" to listOf(
            LlvmInstruction.Phi(i, listOf(i64(0) to "entry", next to "body")),
            LlvmInstruction.Icmp(IcmpCondition.ULT, more, i, n),
            LlvmInstruction.CondBr(more, "body", "done")),
        "body" to listOf(
            LlvmInstruction.GetElementPtr(slot, LlvmType.I16, address, listOf(i), inBounds = false),
            LlvmInstruction.Store(value, slot, align = alignment(LlvmType.I16)),
            LlvmInstruction.BinaryOp(BinaryOperator.ADD, next, i, i64(1)),
            LlvmInstruction.Br("loop")),
        "done" to listOf(LlvmInstruction.Ret(null)))
}

private fun helper(name: String, returnType: LlvmType, parameters: List<LlvmValue.Local>, vararg blocks: Pair<String, List<LlvmInstruction>>) =
    LlvmFunction(name, returnType, parameters, blocks.map { (label, code) -> LlvmBlock(label, code.toMutableList()) }.toMutableList(), linkage = LlvmLinkage.INTERNAL)

private fun i64(value: Long) = LlvmValue.IntConstant(value, LlvmType.I64)
