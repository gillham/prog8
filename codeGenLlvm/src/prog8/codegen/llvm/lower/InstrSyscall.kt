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
private const val VM_WAIT = 13
private const val VM_WAITVSYNC = 14
private const val VM_ATAN = 31
internal const val VM_MUL16_LAST_UPPER = 33
private const val VM_MEMCOPY = 36
private const val VM_MEMSET = 37
private const val VM_MEMSETW = 38
private const val VM_LOAD = 40
private const val VM_LOAD_RAW = 41
private const val VM_SAVE = 42
private const val VM_DELETE = 43
private const val VM_RENAME = 44
private const val VM_CURDIR = 48
private const val VM_MKDIR = 49
private const val VM_CHDIR = 50
private const val VM_RMDIR = 51
private const val VM_OPEN_FILE = 52
private const val VM_OPEN_FILE_WRITE = 53
private const val VM_READ_FILE_BYTE = 54
private const val VM_WRITE_FILE_BYTE = 55
private const val VM_CLOSE_FILE = 56
private const val VM_CLOSE_FILE_WRITE = 57
private const val VM_SEEK_FILE = 63
private const val VM_TELL_FILE_POS = 64
private const val VM_TELL_FILE_SIZE = 65
private const val VM_GET_C = 69
// the llvm libraries' own syscalls start at 200, which leaves the numbers below it to the VM
private const val VM_GET_ARGC = 200
private const val VM_GET_ARGV = 201
private const val VM_FOPEN = 202
private const val VM_FCLOSE = 203
private const val VM_FREAD = 204
private const val VM_FWRITE = 205
private const val VM_FSEEK = 206
private const val VM_FTELL = 207
private const val VM_FFLUSH = 208
private const val VM_FEOF = 209
private const val VM_FLOAT_DIGITS = 210
private const val VM_PARSE_FLOAT = 211
private const val VM_LIST_DIRECTORY = 212

/**
 * Lowers the syscalls that the compiler emits for builtin functions and operators, the exit, console, memory, file and filesystem
 * syscalls of the virtual target's library, and the command line argument, file handle, float conversion and directory listing syscalls of the llvm libraries.
 * Searches, string comparisons, string output, argument lookups and the file stream routines become internal helper functions.
 */
internal fun FunctionLowering.lowerSyscall(ins: IRInstruction) {
    val site = ins.requireCallSite()
    val number = requireNotNull(site.target as? CallTarget.SystemCall) { "SYSCALL in ${sub.label} needs a syscall number" }.number
    when (number) {
        VM_EXIT -> exit(site, number)
        VM_PRINT_C -> printChar(site, number)
        VM_PRINT_S -> printString(site, number)
        VM_WAIT -> waitJiffies(site, number)
        VM_WAITVSYNC -> {
            arguments(site, number, 0)
            emit(LlvmInstruction.Call(null, builder.helperFunction(".sys.wait") { builder.waitHelper() }, listOf(LlvmValue.IntConstant(1, LlvmType.I16)), LlvmType.Void))
        }
        VM_ATAN -> angle(site, number)
        VM_MUL16_LAST_UPPER -> mul16LastUpper(site, number)
        VM_MEMCOPY -> memoryCopy(site, number)
        VM_MEMSET -> memorySet(site, number)
        VM_MEMSETW -> memorySetWords(site, number)
        VM_LOAD -> load(site, number, raw = false)
        VM_LOAD_RAW -> load(site, number, raw = true)
        VM_SAVE -> save(site, number)
        VM_DELETE -> fileSystem(site, number, Libc.REMOVE)
        VM_RENAME -> fileSystem(site, number, Libc.RENAME)
        VM_CURDIR -> currentDirectory(site, number)
        VM_MKDIR -> fileSystem(site, number, Libc.MKDIR)
        VM_CHDIR -> fileSystem(site, number, Libc.CHDIR)
        VM_RMDIR -> fileSystem(site, number, Libc.RMDIR)
        VM_OPEN_FILE -> openFile(site, number, write = false)
        VM_OPEN_FILE_WRITE -> openFile(site, number, write = true)
        VM_READ_FILE_BYTE -> readFileByte(site, number)
        VM_WRITE_FILE_BYTE -> writeFileByte(site, number)
        VM_CLOSE_FILE -> closeFile(site, number, write = false)
        VM_CLOSE_FILE_WRITE -> closeFile(site, number, write = true)
        VM_SEEK_FILE -> seekFile(site, number)
        VM_TELL_FILE_POS -> tellFile(site, number, size = false)
        VM_TELL_FILE_SIZE -> tellFile(site, number, size = true)
        VM_GET_C -> getChar(site, number)
        VM_GET_ARGC -> argumentCount(site, number)
        VM_GET_ARGV -> argument(site, number)
        VM_FOPEN -> openHandle(site, number)
        VM_FCLOSE -> handleStatus(site, number, Libc.FCLOSE)
        VM_FREAD -> transfer(site, number, Libc.FREAD)
        VM_FWRITE -> transfer(site, number, Libc.FWRITE)
        VM_FSEEK -> seekHandle(site, number)
        VM_FTELL -> tellHandle(site, number)
        VM_FFLUSH -> handleStatus(site, number, Libc.FFLUSH)
        VM_FEOF -> endOfFile(site, number)
        VM_FLOAT_DIGITS -> floatDigits(site, number)
        VM_PARSE_FLOAT -> parseFloat(site, number)
        VM_LIST_DIRECTORY -> listDirectory(site, number)
        else -> when (IMSyscall.entries.firstOrNull { it.number == number }) {
            IMSyscall.COMPARE_STRINGS -> compareStrings(site, number)
            IMSyscall.STRING_CONTAINS -> stringContains(site, number)
            IMSyscall.BYTEARRAY_CONTAINS -> arrayContains(site, number, LlvmType.I8)
            IMSyscall.WORDARRAY_CONTAINS -> arrayContains(site, number, LlvmType.I16)
            IMSyscall.LONGARRAY_CONTAINS -> arrayContains(site, number, LlvmType.I32)
            IMSyscall.QUADARRAY_CONTAINS -> arrayContains(site, number, LlvmType.I64)
            IMSyscall.FLOATARRAY_CONTAINS -> arrayContains(site, number, LlvmType.Double)
            IMSyscall.CLAMP_UBYTE -> clamp(site, number, LlvmType.I8, signed = false)
            IMSyscall.CLAMP_BYTE -> clamp(site, number, LlvmType.I8, signed = true)
            IMSyscall.CLAMP_UWORD -> clamp(site, number, LlvmType.I16, signed = false)
            IMSyscall.CLAMP_WORD -> clamp(site, number, LlvmType.I16, signed = true)
            IMSyscall.CLAMP_LONG -> clamp(site, number, LlvmType.I32, signed = true)
            IMSyscall.MEMCOPY -> memoryCopy(site, number)
            IMSyscall.CALLFAR, IMSyscall.CALLFAR2 -> throw IllegalArgumentException("${sub.label} calls into another memory bank, which the llvm targets don't have")
            IMSyscall.SPLIT_WORDARRAY_CONTAINS -> throw IllegalArgumentException("${sub.label} searches a split word array, which the llvm targets don't have")
            null -> throw IllegalArgumentException("${sub.label} has syscall $number, which the llvm targets don't have")
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
    result(site, callHelper(builder.helperFunction(".compare.strings") { compareStringsHelper(builder.mapper.intPtr) }, args, "order"))
}

/** STRING_CONTAINS(character, string): the byte 1 or 0. */
private fun FunctionLowering.stringContains(site: CallSite, number: Int) {
    val (character, string) = arguments(site, number, 2)
    val args = listOf(convert(read(character), LlvmType.I8), pointerValue(string))
    result(site, callHelper(builder.helperFunction(".contains.string") { stringContainsHelper(builder.mapper.intPtr) }, args, "found"))
}

/** The array contains checks (needle, array, number of elements): the byte 1 or 0. */
private fun FunctionLowering.arrayContains(site: CallSite, number: Int, elementType: LlvmType) {
    val (needle, array, count) = arguments(site, number, 3)
    val args = listOf(convert(read(needle), elementType), pointerValue(array), convert(read(count), LlvmType.I16))
    val name = ".contains.${LlvmPrinter.format(elementType)}"
    result(site, callHelper(builder.helperFunction(name) { containsHelper(name, elementType, builder.mapper.intPtr) }, args, "found"))
}

/** CLAMP(value, minimum, maximum) = min(max(value, minimum), maximum), as in the VM. */
private fun FunctionLowering.clamp(site: CallSite, number: Int, type: LlvmType, signed: Boolean) {
    val (value, minimum, maximum) = arguments(site, number, 3).map { convert(read(it), type) }
    val suffix = LlvmPrinter.format(type)
    val atLeastMinimum = intrinsic(if (signed) "llvm.smax.$suffix" else "llvm.umax.$suffix", type, value, minimum)
    result(site, intrinsic(if (signed) "llvm.smin.$suffix" else "llvm.umin.$suffix", type, atLeastMinimum, maximum))
}

/** ATAN(x1, y1, x2, y2): the angle from the first point to the second, in 256 steps for a full circle, as in the VM. */
private fun FunctionLowering.angle(site: CallSite, number: Int) {
    val (x1, y1, x2, y2) = arguments(site, number, 4).map { derive("coord", LlvmType.Double) { value -> LlvmInstruction.Cast(CastOperator.UITOFP, value, convert(read(it), LlvmType.I8), LlvmType.Double) } }
    val dy = derive("dy", LlvmType.Double) { LlvmInstruction.BinaryOp(BinaryOperator.FSUB, it, y2, y1) }
    val dx = derive("dx", LlvmType.Double) { LlvmInstruction.BinaryOp(BinaryOperator.FSUB, it, x2, x1) }
    val radians = derive("radians", LlvmType.Double) { LlvmInstruction.Call(it, external("atan2", LlvmType.Double, listOf(LlvmType.Double, LlvmType.Double)), listOf(dy, dx), LlvmType.Double) }
    val negative = derive("negative", LlvmType.I1) { LlvmInstruction.Fcmp(FcmpCondition.OLT, it, radians, LlvmValue.FloatConstant(0.0)) }
    val turned = derive("radians", LlvmType.Double) { LlvmInstruction.BinaryOp(BinaryOperator.FADD, it, radians, LlvmValue.FloatConstant(2 * Math.PI)) }
    val full = derive("radians", LlvmType.Double) { LlvmInstruction.Select(it, negative, turned, radians) }
    // the VM's radians/2.0/PI*256.0, in that order, so that values on a step boundary round the same
    val halved = derive("steps", LlvmType.Double) { LlvmInstruction.BinaryOp(BinaryOperator.FDIV, it, full, LlvmValue.FloatConstant(2.0)) }
    val turns = derive("steps", LlvmType.Double) { LlvmInstruction.BinaryOp(BinaryOperator.FDIV, it, halved, LlvmValue.FloatConstant(Math.PI)) }
    val scaled = derive("steps", LlvmType.Double) { LlvmInstruction.BinaryOp(BinaryOperator.FMUL, it, turns, LlvmValue.FloatConstant(256.0)) }
    val steps = derive("steps", LlvmType.I32) { LlvmInstruction.Cast(CastOperator.FPTOSI, it, intrinsic("llvm.floor.f64", LlvmType.Double, scaled), LlvmType.I32) }
    result(site, convert(steps, LlvmType.I8, "angle"))
}

/** MUL16_LAST_UPPER(): the upper 16 bits of the product of the last word multiplication. */
private fun FunctionLowering.mul16LastUpper(site: CallSite, number: Int) {
    arguments(site, number, 0)
    result(site, derive("upper", LlvmType.I16) { LlvmInstruction.Load(it, builder.mul16Upper()) })
}

/** WAIT(jiffies word) sleeps for that many 1/60 seconds; WAITVSYNC() sleeps for one, as there is no vertical blank to wait for. */
private fun FunctionLowering.waitJiffies(site: CallSite, number: Int) {
    val (jiffies) = arguments(site, number, 1)
    emit(LlvmInstruction.Call(null, builder.helperFunction(".sys.wait") { builder.waitHelper() }, listOf(convert(read(jiffies), LlvmType.I16)), LlvmType.Void))
}

/** EXIT(status byte) ends the program with C's exit(), which the hosted llvm targets can rely on. */
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
    val helper = builder.helperFunction(".print.string") { printStringHelper(putchar(), builder.mapper.intPtr) }
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

/** GET_ARGC(): the number of command line arguments as a word, including the program name; it can't count past 65535. */
private fun FunctionLowering.argumentCount(site: CallSite, number: Int) {
    arguments(site, number, 0)
    val count = derive("argc", LlvmType.I32) { LlvmInstruction.Load(it, builder.argumentCount()) }
    // saturated rather than truncated, so that every index below the count still gives an argument
    result(site, convert(intrinsic("llvm.umin.i32", LlvmType.I32, count, LlvmValue.IntConstant(0xffff, LlvmType.I32)), LlvmType.I16, "argc"))
}

/** GET_ARGV(index word): the command line argument at the index, 0 being the program name, or an empty string when there is none. */
private fun FunctionLowering.argument(site: CallSite, number: Int) {
    val (index) = arguments(site, number, 1)
    val helper = builder.helperFunction(".sys.argv.at") {
        argumentHelper(builder.argumentCount(), builder.argumentArray(), builder.runtimeGlobal(".sys.argv.none", LlvmType.Array(1, LlvmType.I8), constant = true), builder.mapper.intPtr)
    }
    result(site, derive("argv", LlvmType.Ptr) { LlvmInstruction.Call(it, helper, listOf(convert(read(index), LlvmType.I16)), LlvmType.Ptr) })
}

/** MEMCOPY(source, target, number of bytes) becomes memmove, so that overlapping areas don't give undefined behavior. */
private fun FunctionLowering.memoryCopy(site: CallSite, number: Int) {
    val (source, target, count) = arguments(site, number, 3)
    val sizeT = builder.mapper.sizeT
    val args = listOf(pointerValue(target), pointerValue(source), convert(read(count), sizeT), LlvmValue.IntConstant(0, LlvmType.I1))
    emit(LlvmInstruction.Call(null, external("llvm.memmove.p0.p0.${LlvmPrinter.format(sizeT)}", LlvmType.Void, args.map { it.type }), args, LlvmType.Void))
}

/** MEMSET(address, number of bytes, byte value). */
private fun FunctionLowering.memorySet(site: CallSite, number: Int) {
    val (address, count, value) = arguments(site, number, 3)
    val sizeT = builder.mapper.sizeT
    val args = listOf(pointerValue(address), convert(read(value), LlvmType.I8), convert(read(count), sizeT), LlvmValue.IntConstant(0, LlvmType.I1))
    emit(LlvmInstruction.Call(null, external("llvm.memset.p0.${LlvmPrinter.format(sizeT)}", LlvmType.Void, args.map { it.type }), args, LlvmType.Void))
}

/** MEMSETW(address, number of words, word value). */
private fun FunctionLowering.memorySetWords(site: CallSite, number: Int) {
    val (address, count, value) = arguments(site, number, 3)
    val args = listOf(pointerValue(address), convert(read(count), LlvmType.I16), convert(read(value), LlvmType.I16))
    emit(LlvmInstruction.Call(null, builder.helperFunction(".memsetw") { setWordsHelper(builder.mapper.intPtr) }, args, LlvmType.Void))
}

/** LOAD(name, address) and LOAD_RAW(name, address): the end address of the file's data at the address, or null for an error. */
private fun FunctionLowering.load(site: CallSite, number: Int, raw: Boolean) {
    val (name, address) = arguments(site, number, 2)
    val helper = builder.helperFunction(".file.load") { builder.loadHelper() }
    val args = listOf(pointerValue(name), pointerValue(address), LlvmValue.IntConstant(if (raw) 1L else 0L, LlvmType.I1))
    result(site, derive("end", LlvmType.Ptr) { LlvmInstruction.Call(it, helper, args, LlvmType.Ptr) })
}

/** SAVE(raw byte, name, start, size): 1 when the file is written, which gets a 2-byte header first unless raw, else 0. */
private fun FunctionLowering.save(site: CallSite, number: Int) {
    val (raw, name, start, size) = arguments(site, number, 4)
    val helper = builder.helperFunction(".file.save") { builder.saveHelper() }
    val headerless = derive("raw", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.NE, it, convert(read(raw), LlvmType.I8), LlvmValue.IntConstant(0, LlvmType.I8)) }
    val args = listOf(headerless, pointerValue(name), pointerValue(start), convert(readWide(size), builder.mapper.sizeT))
    result(site, derive("saved", LlvmType.I8) { LlvmInstruction.Call(it, helper, args, LlvmType.I8) })
}

/**
 * DELETE(name), RENAME(name, new name), MKDIR(path), CHDIR(path) and RMDIR(path). The VM's give no result,
 * but here a call site that asks for one gets 1 when the C routine succeeded, else 0.
 */
private fun FunctionLowering.fileSystem(site: CallSite, number: Int, routine: Libc) {
    val paths = arguments(site, number, if (routine == Libc.RENAME) 2 else 1).map { pointerValue(it) }
    // rwxr-xr-x, before the umask
    val args = if (routine == Libc.MKDIR) paths + LlvmValue.IntConstant(0b111_101_101, LlvmType.I32) else paths
    val status = derive("status", LlvmType.I32) { LlvmInstruction.Call(it, builder.libc(routine), args, LlvmType.I32) }
    if (site.results.isNotEmpty())
        result(site, succeeded(status))
}

/** The byte 1 when the int [status] of a C routine is 0, its value for success, else 0. */
private fun FunctionLowering.succeeded(status: LlvmValue): LlvmValue {
    val ok = derive("ok", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.EQ, it, status, i32(0)) }
    return derive("ok", LlvmType.I8) { LlvmInstruction.Cast(CastOperator.ZEXT, it, ok, LlvmType.I8) }
}

/** CURDIR(): the current directory in a static buffer, or null when it doesn't fit or getcwd() fails otherwise. */
private fun FunctionLowering.currentDirectory(site: CallSite, number: Int) {
    arguments(site, number, 0)
    val buffer = builder.runtimeGlobal(".sys.curdir.buf", LlvmType.Array(CURDIR_BUFFER_SIZE, LlvmType.I8))
    val args = listOf(buffer, LlvmValue.IntConstant(CURDIR_BUFFER_SIZE.toLong(), builder.mapper.sizeT))
    result(site, derive("cwd", LlvmType.Ptr) { LlvmInstruction.Call(it, builder.libc(Libc.GETCWD), args, LlvmType.Ptr) })
}

/** OPEN_FILE(name) and OPEN_FILE_WRITE(name): 1 when the file becomes the read or write stream, else 0, keeping the stream there was. */
private fun FunctionLowering.openFile(site: CallSite, number: Int, write: Boolean) {
    val (name) = arguments(site, number, 1)
    val helper = builder.helperFunction(".file.open") { builder.openHelper() }
    val mode = if (write) builder.runtimeString(".file.mode.wb", "wb") else builder.runtimeString(".file.mode.rb", "rb")
    val args = listOf(pointerValue(name), mode, if (write) builder.writeStream() else builder.readStream())
    result(site, derive("opened", LlvmType.I8) { LlvmInstruction.Call(it, helper, args, LlvmType.I8) })
}

/** CLOSE_FILE() and CLOSE_FILE_WRITE() close the read or write stream, if there is one. */
private fun FunctionLowering.closeFile(site: CallSite, number: Int, write: Boolean) {
    arguments(site, number, 0)
    val helper = builder.helperFunction(".file.close") { builder.closeHelper() }
    emit(LlvmInstruction.Call(null, helper, listOf(if (write) builder.writeStream() else builder.readStream()), LlvmType.Void))
}

/** READ_FILE_BYTE(): $0100 plus the next byte of the read stream, as in the VM, or 0 at its end, for an error or without a stream. */
private fun FunctionLowering.readFileByte(site: CallSite, number: Int) {
    arguments(site, number, 0)
    val helper = builder.helperFunction(".file.read.byte") { builder.readByteHelper() }
    result(site, derive("byte", LlvmType.I16) { LlvmInstruction.Call(it, helper, emptyList(), LlvmType.I16) })
}

/** WRITE_FILE_BYTE(byte): 1 when the byte is written to the write stream, else 0. */
private fun FunctionLowering.writeFileByte(site: CallSite, number: Int) {
    val (byte) = arguments(site, number, 1)
    val helper = builder.helperFunction(".file.write.byte") { builder.writeByteHelper() }
    val args = listOf(convert(read(byte), LlvmType.I8))
    result(site, derive("written", LlvmType.I8) { LlvmInstruction.Call(it, helper, args, LlvmType.I8) })
}

/** SEEK_FILE(position long): 1 when the read stream moved to the position, else 0. */
private fun FunctionLowering.seekFile(site: CallSite, number: Int) {
    val (position) = arguments(site, number, 1)
    val helper = builder.helperFunction(".file.seek") { builder.seekHelper() }
    val args = listOf(convert(read(position), LlvmType.I32))
    result(site, derive("sought", LlvmType.I8) { LlvmInstruction.Call(it, helper, args, LlvmType.I8) })
}

/** TELL_FILE_POS() and TELL_FILE_SIZE(): the position in the read stream, or the size of its file, as a long; 0 without a stream. */
private fun FunctionLowering.tellFile(site: CallSite, number: Int, size: Boolean) {
    arguments(site, number, 0)
    val helper = if (size) builder.helperFunction(".file.size") { builder.sizeHelper() } else builder.helperFunction(".file.tell") { builder.tellHelper() }
    result(site, derive(if (size) "size" else "position", LlvmType.I32) { LlvmInstruction.Call(it, helper, emptyList(), LlvmType.I32) })
}

// The file handle syscalls of the llvm fileio module, whose handle is the FILE pointer from FOPEN. As in C, only FFLUSH
// accepts a null one, which flushes every output stream; fileio checks the handle for the others.

/** FOPEN(name, mode): the FILE pointer of the file that fopen() opens with the C mode string, or null when it can't. */
private fun FunctionLowering.openHandle(site: CallSite, number: Int) {
    val (name, mode) = arguments(site, number, 2)
    val args = listOf(pointerValue(name), pointerValue(mode))
    result(site, derive("file", LlvmType.Ptr) { LlvmInstruction.Call(it, builder.libc(Libc.FOPEN), args, LlvmType.Ptr) })
}

/** FCLOSE(file) and FFLUSH(file): 1 when fclose() or fflush() succeeds, else 0. */
private fun FunctionLowering.handleStatus(site: CallSite, number: Int, routine: Libc) {
    val (file) = arguments(site, number, 1)
    val status = derive("status", LlvmType.I32) { LlvmInstruction.Call(it, builder.libc(routine), listOf(pointerValue(file)), LlvmType.I32) }
    result(site, succeeded(status))
}

/** FREAD(file, buffer, count word) and FWRITE(file, buffer, count word): the number of bytes that fread() or fwrite() transfers. */
private fun FunctionLowering.transfer(site: CallSite, number: Int, routine: Libc) {
    val (file, buffer, count) = arguments(site, number, 3)
    val sizeT = builder.mapper.sizeT
    val args = listOf(pointerValue(buffer), LlvmValue.IntConstant(1, sizeT), convert(read(count), sizeT, "count"), pointerValue(file))
    val transferred = derive("transferred", sizeT) { LlvmInstruction.Call(it, builder.libc(routine), args, sizeT) }
    // never more than the count, so it fits in a word
    result(site, convert(transferred, LlvmType.I16, "transferred"))
}

/** FSEEK(file, offset quad, origin byte): 1 when fseek() moves to the offset from the origin, SEEK_SET 0, SEEK_CUR 1 or SEEK_END 2, else 0. */
private fun FunctionLowering.seekHandle(site: CallSite, number: Int) {
    val (file, offset, origin) = arguments(site, number, 3)
    val cLong = builder.mapper.cLong
    val quadOffset = read(offset)
    val narrowed = checkNotNull(quadOffset.type.bitWidth) > checkNotNull(cLong.bitWidth)
    val longOffset = if (narrowed) convert(quadOffset, cLong, "offset") else widen(quadOffset, cLong, signed = true, "offset")
    var whence = convert(read(origin), LlvmType.I32, "origin")
    if (narrowed) {
        // an offset that doesn't fit in the C long fails the seek, with an origin that fseek rejects, instead of going to its low bits
        val restored = widen(longOffset, quadOffset.type, signed = true, "offset")
        val fits = derive("fits", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.EQ, it, restored, quadOffset) }
        val checked = whence
        whence = derive("origin", LlvmType.I32) { LlvmInstruction.Select(it, fits, checked, i32(-1)) }
    }
    val args = listOf(pointerValue(file), longOffset, whence)
    val status = derive("status", LlvmType.I32) { LlvmInstruction.Call(it, builder.libc(Libc.FSEEK), args, LlvmType.I32) }
    result(site, succeeded(status))
}

/** FTELL(file): the position in the file as a quad, which is -1 for an error. */
private fun FunctionLowering.tellHandle(site: CallSite, number: Int) {
    val (file) = arguments(site, number, 1)
    val cLong = builder.mapper.cLong
    val position = derive("position", cLong) { LlvmInstruction.Call(it, builder.libc(Libc.FTELL), listOf(pointerValue(file)), cLong) }
    result(site, widen(position, LlvmType.I64, signed = true, "position"))
}

/** FEOF(file): 1 when a read went past the end of the file, else 0. */
private fun FunctionLowering.endOfFile(site: CallSite, number: Int) {
    val (file) = arguments(site, number, 1)
    val flag = derive("eof", LlvmType.I32) { LlvmInstruction.Call(it, builder.libc(Libc.FEOF), listOf(pointerValue(file)), LlvmType.I32) }
    val set = derive("eof", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.NE, it, flag, i32(0)) }
    result(site, derive("eof", LlvmType.I8) { LlvmInstruction.Cast(CastOperator.ZEXT, it, set, LlvmType.I8) })
}

/** FLOAT_DIGITS(buffer of 32 bytes, float): the shortest "%.*e" text of the float, of at least two digits, that reads back as the same float. */
private fun FunctionLowering.floatDigits(site: CallSite, number: Int) {
    val (buffer, value) = arguments(site, number, 2)
    val helper = builder.helperFunction(".float.digits") { builder.floatDigitsHelper() }
    emit(LlvmInstruction.Call(null, helper, listOf(pointerValue(buffer), read(value)), LlvmType.Void))
}

/** PARSE_FLOAT(string): the float that strtod() reads from the whole string, or 0.0 when the string isn't one number. */
private fun FunctionLowering.parseFloat(site: CallSite, number: Int) {
    val (string) = arguments(site, number, 1)
    val helper = builder.helperFunction(".float.parse") { builder.parseFloatHelper() }
    result(site, derive("float", LlvmType.Double) { LlvmInstruction.Call(it, helper, listOf(pointerValue(string)), LlvmType.Double) })
}

/**
 * LIST_DIRECTORY(kind byte): prints the entries of the current directory sorted by name, all of them for kind 0, the subdirectories
 * for 1 and the files for 2. 1 when the directory could be read, else 0, which it always is without the triple's dirent layout.
 */
private fun FunctionLowering.listDirectory(site: CallSite, number: Int) {
    val (kind) = arguments(site, number, 1)
    val nameOffset = builder.profile?.direntNameOffset
    if (nameOffset == null) {
        result(site, LlvmValue.IntConstant(0, LlvmType.I8))
        return
    }
    val helper = builder.helperFunction(".diskio.list") { builder.listDirectoryHelper(nameOffset) }
    val args = listOf(convert(read(kind), LlvmType.I8))
    result(site, derive("listed", LlvmType.I8) { LlvmInstruction.Call(it, helper, args, LlvmType.I8) })
}

/** `i8 (needle, ptr array, i16 count)`: 1 when one of the count elements equals the needle, else 0. */
private fun containsHelper(name: String, elementType: LlvmType, intPtr: LlvmType): LlvmFunction {
    val needle = LlvmValue.Local("needle", elementType)
    val array = LlvmValue.Local("array", LlvmType.Ptr)
    val count = LlvmValue.Local("count", LlvmType.I16)
    val n = LlvmValue.Local("n", intPtr)
    val i = LlvmValue.Local("i", intPtr)
    val more = LlvmValue.Local("more", LlvmType.I1)
    val slot = LlvmValue.Local("slot", LlvmType.Ptr)
    val element = LlvmValue.Local("element", elementType)
    val found = LlvmValue.Local("found", LlvmType.I1)
    val next = LlvmValue.Local("next", intPtr)
    val equal = if (elementType == LlvmType.Double) LlvmInstruction.Fcmp(FcmpCondition.OEQ, found, element, needle)
        else LlvmInstruction.Icmp(IcmpCondition.EQ, found, element, needle)
    return helper(name, LlvmType.I8, listOf(needle, array, count),
        "entry" to listOf(LlvmInstruction.Cast(CastOperator.ZEXT, n, count, intPtr), LlvmInstruction.Br("loop")),
        "loop" to listOf(
            LlvmInstruction.Phi(i, listOf(LlvmValue.IntConstant(0, intPtr) to "entry", next to "body")),
            LlvmInstruction.Icmp(IcmpCondition.ULT, more, i, n),
            LlvmInstruction.CondBr(more, "body", "absent")),
        "body" to listOf(
            LlvmInstruction.GetElementPtr(slot, elementType, array, listOf(i), inBounds = false),
            LlvmInstruction.Load(element, slot, align = alignment(elementType)),
            equal,
            LlvmInstruction.BinaryOp(BinaryOperator.ADD, next, i, LlvmValue.IntConstant(1, intPtr)),
            LlvmInstruction.CondBr(found, "present", "loop")),
        "present" to listOf(LlvmInstruction.Ret(LlvmValue.IntConstant(1, LlvmType.I8))),
        "absent" to listOf(LlvmInstruction.Ret(LlvmValue.IntConstant(0, LlvmType.I8))))
}

/** `i8 (i8 wanted, ptr string)`: 1 when the zero-terminated string contains the wanted byte, which is never so for 0, else 0. */
private fun stringContainsHelper(intPtr: LlvmType): LlvmFunction {
    val wanted = LlvmValue.Local("wanted", LlvmType.I8)
    val string = LlvmValue.Local("string", LlvmType.Ptr)
    val i = LlvmValue.Local("i", intPtr)
    val slot = LlvmValue.Local("slot", LlvmType.Ptr)
    val char = LlvmValue.Local("char", LlvmType.I8)
    val end = LlvmValue.Local("end", LlvmType.I1)
    val found = LlvmValue.Local("found", LlvmType.I1)
    val next = LlvmValue.Local("next", intPtr)
    return helper(".contains.string", LlvmType.I8, listOf(wanted, string),
        "entry" to listOf(LlvmInstruction.Br("loop")),
        "loop" to listOf(
            LlvmInstruction.Phi(i, listOf(LlvmValue.IntConstant(0, intPtr) to "entry", next to "more")),
            LlvmInstruction.GetElementPtr(slot, LlvmType.I8, string, listOf(i), inBounds = false),
            LlvmInstruction.Load(char, slot),
            LlvmInstruction.Icmp(IcmpCondition.EQ, end, char, LlvmValue.IntConstant(0, LlvmType.I8)),
            LlvmInstruction.CondBr(end, "absent", "more")),
        "more" to listOf(
            LlvmInstruction.Icmp(IcmpCondition.EQ, found, char, wanted),
            LlvmInstruction.BinaryOp(BinaryOperator.ADD, next, i, LlvmValue.IntConstant(1, intPtr)),
            LlvmInstruction.CondBr(found, "present", "loop")),
        "present" to listOf(LlvmInstruction.Ret(LlvmValue.IntConstant(1, LlvmType.I8))),
        "absent" to listOf(LlvmInstruction.Ret(LlvmValue.IntConstant(0, LlvmType.I8))))
}

/** `i8 (ptr first, ptr second)`: -1, 0 or 1, comparing zero-terminated strings by unsigned bytes like the VM; a prefix comes first. */
private fun compareStringsHelper(intPtr: LlvmType): LlvmFunction {
    val first = LlvmValue.Local("first", LlvmType.Ptr)
    val second = LlvmValue.Local("second", LlvmType.Ptr)
    val i = LlvmValue.Local("i", intPtr)
    val firstSlot = LlvmValue.Local("first.slot", LlvmType.Ptr)
    val firstChar = LlvmValue.Local("first.char", LlvmType.I8)
    val secondSlot = LlvmValue.Local("second.slot", LlvmType.Ptr)
    val secondChar = LlvmValue.Local("second.char", LlvmType.I8)
    val equal = LlvmValue.Local("equal", LlvmType.I1)
    val end = LlvmValue.Local("end", LlvmType.I1)
    val next = LlvmValue.Local("next", intPtr)
    val less = LlvmValue.Local("less", LlvmType.I1)
    val order = LlvmValue.Local("order", LlvmType.I8)
    return helper(".compare.strings", LlvmType.I8, listOf(first, second),
        "entry" to listOf(LlvmInstruction.Br("loop")),
        "loop" to listOf(
            LlvmInstruction.Phi(i, listOf(LlvmValue.IntConstant(0, intPtr) to "entry", next to "same")),
            LlvmInstruction.GetElementPtr(firstSlot, LlvmType.I8, first, listOf(i), inBounds = false),
            LlvmInstruction.Load(firstChar, firstSlot),
            LlvmInstruction.GetElementPtr(secondSlot, LlvmType.I8, second, listOf(i), inBounds = false),
            LlvmInstruction.Load(secondChar, secondSlot),
            LlvmInstruction.Icmp(IcmpCondition.EQ, equal, firstChar, secondChar),
            LlvmInstruction.CondBr(equal, "same", "differ")),
        "same" to listOf(
            LlvmInstruction.Icmp(IcmpCondition.EQ, end, firstChar, LlvmValue.IntConstant(0, LlvmType.I8)),
            LlvmInstruction.BinaryOp(BinaryOperator.ADD, next, i, LlvmValue.IntConstant(1, intPtr)),
            LlvmInstruction.CondBr(end, "done", "loop")),
        "done" to listOf(LlvmInstruction.Ret(LlvmValue.IntConstant(0, LlvmType.I8))),
        "differ" to listOf(
            LlvmInstruction.Icmp(IcmpCondition.ULT, less, firstChar, secondChar),
            LlvmInstruction.Select(order, less, LlvmValue.IntConstant(-1, LlvmType.I8), LlvmValue.IntConstant(1, LlvmType.I8)),
            LlvmInstruction.Ret(order)))
}

/** `void (ptr str)`: writes the bytes of the zero-terminated string with [putchar], up to its terminating 0. */
private fun printStringHelper(putchar: LlvmValue.Global, intPtr: LlvmType): LlvmFunction {
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
            LlvmInstruction.GetElementPtr(next, LlvmType.I8, currentPointer, listOf(LlvmValue.IntConstant(1, intPtr)), inBounds = false),
            LlvmInstruction.Br("loop")),
        "done" to listOf(LlvmInstruction.Ret(null)))
}

/** `void (ptr address, i16 count, i16 value)`: stores count copies of the word value from address on. */
private fun setWordsHelper(intPtr: LlvmType): LlvmFunction {
    val address = LlvmValue.Local("address", LlvmType.Ptr)
    val count = LlvmValue.Local("count", LlvmType.I16)
    val value = LlvmValue.Local("value", LlvmType.I16)
    val n = LlvmValue.Local("n", intPtr)
    val i = LlvmValue.Local("i", intPtr)
    val more = LlvmValue.Local("more", LlvmType.I1)
    val slot = LlvmValue.Local("slot", LlvmType.Ptr)
    val next = LlvmValue.Local("next", intPtr)
    return helper(".memsetw", LlvmType.Void, listOf(address, count, value),
        "entry" to listOf(LlvmInstruction.Cast(CastOperator.ZEXT, n, count, intPtr), LlvmInstruction.Br("loop")),
        "loop" to listOf(
            LlvmInstruction.Phi(i, listOf(LlvmValue.IntConstant(0, intPtr) to "entry", next to "body")),
            LlvmInstruction.Icmp(IcmpCondition.ULT, more, i, n),
            LlvmInstruction.CondBr(more, "body", "done")),
        "body" to listOf(
            LlvmInstruction.GetElementPtr(slot, LlvmType.I16, address, listOf(i), inBounds = false),
            LlvmInstruction.Store(value, slot, align = alignment(LlvmType.I16)),
            LlvmInstruction.BinaryOp(BinaryOperator.ADD, next, i, LlvmValue.IntConstant(1, intPtr)),
            LlvmInstruction.Br("loop")),
        "done" to listOf(LlvmInstruction.Ret(null)))
}

/** `ptr (i16 index)`: the [arguments] entry at the index when that is below the [count], else the [empty] string. */
private fun argumentHelper(count: LlvmValue.Global, arguments: LlvmValue.Global, empty: LlvmValue.Global, intPtr: LlvmType): LlvmFunction {
    val index = LlvmValue.Local("index", LlvmType.I16)
    val i = LlvmValue.Local("i", intPtr)
    val argc = LlvmValue.Local("argc", LlvmType.I32)
    val n = if (intPtr == LlvmType.I32) argc else LlvmValue.Local("n", intPtr)
    val inRange = LlvmValue.Local("inrange", LlvmType.I1)
    val argv = LlvmValue.Local("argv", LlvmType.Ptr)
    val slot = LlvmValue.Local("slot", LlvmType.Ptr)
    val argument = LlvmValue.Local("argument", LlvmType.Ptr)
    return helper(".sys.argv.at", LlvmType.Ptr, listOf(index),
        "entry" to listOfNotNull(
            LlvmInstruction.Cast(CastOperator.ZEXT, i, index, intPtr),
            LlvmInstruction.Load(argc, count),
            if (n === argc) null else LlvmInstruction.Cast(CastOperator.ZEXT, n, argc, intPtr),
            LlvmInstruction.Icmp(IcmpCondition.ULT, inRange, i, n),
            LlvmInstruction.CondBr(inRange, "present", "absent")),
        "present" to listOf(
            LlvmInstruction.Load(argv, arguments),
            LlvmInstruction.GetElementPtr(slot, LlvmType.Ptr, argv, listOf(i), inBounds = false),
            LlvmInstruction.Load(argument, slot),
            LlvmInstruction.Ret(argument)),
        "absent" to listOf(LlvmInstruction.Ret(empty)))
}

// PATH_MAX of Linux; that of macOS is 1024
private const val CURDIR_BUFFER_SIZE = 4096
private const val LOAD_CHUNK_SIZE = 4096L
private const val SEEK_SET = 0L
private const val SEEK_END = 2L

private enum class CType { VOID, INT, PTR, SIZE_T, LONG }

/** The C library routines of the file syscalls; the widths of size_t and long come from the target triple. */
private enum class Libc(val returnType: CType, vararg parameters: CType) {
    FOPEN(CType.PTR, CType.PTR, CType.PTR),
    FCLOSE(CType.INT, CType.PTR),
    FGETC(CType.INT, CType.PTR),
    FPUTC(CType.INT, CType.INT, CType.PTR),
    FREAD(CType.SIZE_T, CType.PTR, CType.SIZE_T, CType.SIZE_T, CType.PTR),
    FWRITE(CType.SIZE_T, CType.PTR, CType.SIZE_T, CType.SIZE_T, CType.PTR),
    FSEEK(CType.INT, CType.PTR, CType.LONG, CType.INT),
    FTELL(CType.LONG, CType.PTR),
    FERROR(CType.INT, CType.PTR),
    FEOF(CType.INT, CType.PTR),
    FFLUSH(CType.INT, CType.PTR),
    REMOVE(CType.INT, CType.PTR),
    RENAME(CType.INT, CType.PTR, CType.PTR),
    GETCWD(CType.PTR, CType.PTR, CType.SIZE_T),
    // mode_t has 16 bits on macOS, 32 on Linux; both take it extended to 32 bits
    MKDIR(CType.INT, CType.PTR, CType.INT),
    CHDIR(CType.INT, CType.PTR),
    RMDIR(CType.INT, CType.PTR),
    CLOSEDIR(CType.INT, CType.PTR),
    STRCMP(CType.INT, CType.PTR, CType.PTR),
    STRDUP(CType.PTR, CType.PTR),
    REALLOC(CType.PTR, CType.PTR, CType.SIZE_T),
    FREE(CType.VOID, CType.PTR),
    QSORT(CType.VOID, CType.PTR, CType.SIZE_T, CType.SIZE_T, CType.PTR);

    val parameterTypes = parameters.toList()
}

private fun LlvmFunctionBuilder.cType(type: CType): LlvmType = when (type) {
    CType.VOID -> LlvmType.Void
    CType.INT -> LlvmType.I32
    CType.PTR -> LlvmType.Ptr
    CType.SIZE_T -> mapper.sizeT
    CType.LONG -> mapper.cLong
}

private fun LlvmFunctionBuilder.libc(routine: Libc): LlvmValue.Global =
    LlvmValue.Global(externalFunction(routine.name.lowercase(), cType(routine.returnType), routine.parameterTypes.map { cType(it) }).name)

/** The FILE pointer of the stream that the file syscalls read from, which is null when there is none. */
private fun LlvmFunctionBuilder.readStream(): LlvmValue.Global = runtimeGlobal(".file.read_stream", LlvmType.Ptr)

/** The FILE pointer of the stream that the file syscalls write to, which is null when there is none. */
private fun LlvmFunctionBuilder.writeStream(): LlvmValue.Global = runtimeGlobal(".file.write_stream", LlvmType.Ptr)

private fun i32(value: Long) = LlvmValue.IntConstant(value, LlvmType.I32)

/**
 * `void (i16 jiffies)`: usleep() for that many 1/60 seconds, in parts of at most half a second, because POSIX lets usleep() fail
 * from a million microseconds on. Fewer calls also add up less of the time that each one oversleeps.
 */
private fun LlvmFunctionBuilder.waitHelper(): LlvmFunction {
    val jiffies = LlvmValue.Local("jiffies", LlvmType.I16)
    val count = LlvmValue.Local("count", LlvmType.I32)
    val total = LlvmValue.Local("total", LlvmType.I32)
    val left = LlvmValue.Local("left", LlvmType.I32)
    val over = LlvmValue.Local("over", LlvmType.I1)
    val part = LlvmValue.Local("part", LlvmType.I32)
    val next = LlvmValue.Local("next", LlvmType.I32)
    val usleep = LlvmValue.Global(externalFunction("usleep", LlvmType.I32, listOf(LlvmType.I32)).name)
    val umin = LlvmValue.Global(externalFunction("llvm.umin.i32", LlvmType.I32, listOf(LlvmType.I32, LlvmType.I32)).name)
    return helper(".sys.wait", LlvmType.Void, listOf(jiffies),
        "entry" to listOf(
            LlvmInstruction.Cast(CastOperator.ZEXT, count, jiffies, LlvmType.I32),
            // at most 65535 * 16667 microseconds, which fits in 31 bits
            LlvmInstruction.BinaryOp(BinaryOperator.MUL, total, count, i32(16667)),
            LlvmInstruction.Br("loop")),
        "loop" to listOf(
            LlvmInstruction.Phi(left, listOf(total to "entry", next to "sleep")),
            LlvmInstruction.Icmp(IcmpCondition.EQ, over, left, i32(0)),
            LlvmInstruction.CondBr(over, "finished", "sleep")),
        "sleep" to listOf(
            LlvmInstruction.Call(part, umin, listOf(left, i32(500000)), LlvmType.I32),
            LlvmInstruction.Call(null, usleep, listOf(part), LlvmType.I32),
            LlvmInstruction.BinaryOp(BinaryOperator.SUB, next, left, part),
            LlvmInstruction.Br("loop")),
        "finished" to listOf(LlvmInstruction.Ret(null)))
}

private fun LlvmFunctionBuilder.strtod(): LlvmValue.Global =
    LlvmValue.Global(externalFunction("strtod", LlvmType.Double, listOf(LlvmType.Ptr, LlvmType.Ptr)).name)

/**
 * `void (ptr buffer, double value)`: snprintf's "%.*e" of the value with the precisions 1 to 16, until strtod() reads it back as the value.
 * It starts at two digits, because that is what Java's Double.toString gives when one would do, such as 4.9E-324 instead of 5.0E-324.
 */
private fun LlvmFunctionBuilder.floatDigitsHelper(): LlvmFunction {
    val buffer = LlvmValue.Local("buffer", LlvmType.Ptr)
    val value = LlvmValue.Local("value", LlvmType.Double)
    val precision = LlvmValue.Local("precision", LlvmType.I32)
    val next = LlvmValue.Local("next", LlvmType.I32)
    val parsed = LlvmValue.Local("parsed", LlvmType.Double)
    val same = LlvmValue.Local("same", LlvmType.I1)
    val last = LlvmValue.Local("last", LlvmType.I1)
    val found = LlvmValue.Local("found", LlvmType.I1)
    val snprintf = externalFunction("snprintf", LlvmType.I32, listOf(LlvmType.Ptr, mapper.sizeT, LlvmType.Ptr), isVarArg = true)
    val args = listOf(buffer, LlvmValue.IntConstant(32, mapper.sizeT), runtimeString(".float.digits.format", "%.*e"), precision, value)
    return helper(".float.digits", LlvmType.Void, listOf(buffer, value),
        "entry" to listOf(LlvmInstruction.Br("loop")),
        "loop" to listOf(
            LlvmInstruction.Phi(precision, listOf(i32(1) to "entry", next to "loop")),
            LlvmInstruction.Call(null, LlvmValue.Global(snprintf.name), args, LlvmType.I32, snprintf.type),
            LlvmInstruction.Call(parsed, strtod(), listOf(buffer, LlvmValue.NullPtr()), LlvmType.Double),
            LlvmInstruction.Fcmp(FcmpCondition.OEQ, same, parsed, value),
            LlvmInstruction.BinaryOp(BinaryOperator.ADD, next, precision, i32(1)),
            LlvmInstruction.Icmp(IcmpCondition.SGE, last, next, i32(17)),
            LlvmInstruction.BinaryOp(BinaryOperator.OR, found, same, last),
            LlvmInstruction.CondBr(found, "done", "loop")),
        "done" to listOf(LlvmInstruction.Ret(null)))
}

/** `double (ptr string)`: strtod() of the string when it reads all of it, else 0.0. */
private fun LlvmFunctionBuilder.parseFloatHelper(): LlvmFunction {
    val string = LlvmValue.Local("string", LlvmType.Ptr)
    val endSlot = LlvmValue.Local("end_slot", LlvmType.Ptr)
    val parsed = LlvmValue.Local("parsed", LlvmType.Double)
    val end = LlvmValue.Local("end", LlvmType.Ptr)
    val rest = LlvmValue.Local("rest", LlvmType.I8)
    val whole = LlvmValue.Local("whole", LlvmType.I1)
    val result = LlvmValue.Local("result", LlvmType.Double)
    return helper(".float.parse", LlvmType.Double, listOf(string),
        "entry" to listOf(
            LlvmInstruction.Alloca(endSlot, LlvmType.Ptr),
            LlvmInstruction.Call(parsed, strtod(), listOf(string, endSlot), LlvmType.Double),
            LlvmInstruction.Load(end, endSlot),
            LlvmInstruction.Load(rest, end),
            LlvmInstruction.Icmp(IcmpCondition.EQ, whole, rest, LlvmValue.IntConstant(0, LlvmType.I8)),
            LlvmInstruction.Select(result, whole, parsed, LlvmValue.FloatConstant(0.0)),
            LlvmInstruction.Ret(result)))
}

/** `i8 (ptr name, ptr mode, ptr stream)`: 1 when fopen() opens the file into the stream variable, closing the one it held, else 0. */
private fun LlvmFunctionBuilder.openHelper(): LlvmFunction {
    val name = LlvmValue.Local("name", LlvmType.Ptr)
    val mode = LlvmValue.Local("mode", LlvmType.Ptr)
    val stream = LlvmValue.Local("stream", LlvmType.Ptr)
    val file = LlvmValue.Local("file", LlvmType.Ptr)
    val failed = LlvmValue.Local("failed", LlvmType.I1)
    val previous = LlvmValue.Local("previous", LlvmType.Ptr)
    val open = LlvmValue.Local("open", LlvmType.I1)
    return helper(".file.open", LlvmType.I8, listOf(name, mode, stream),
        "entry" to listOf(
            LlvmInstruction.Call(file, libc(Libc.FOPEN), listOf(name, mode), LlvmType.Ptr),
            LlvmInstruction.Icmp(IcmpCondition.EQ, failed, file, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(failed, "fail", "opened")),
        "opened" to listOf(
            LlvmInstruction.Load(previous, stream),
            LlvmInstruction.Icmp(IcmpCondition.NE, open, previous, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(open, "close", "keep")),
        "close" to listOf(
            LlvmInstruction.Call(null, libc(Libc.FCLOSE), listOf(previous), LlvmType.I32),
            LlvmInstruction.Br("keep")),
        "keep" to listOf(
            LlvmInstruction.Store(file, stream),
            LlvmInstruction.Ret(LlvmValue.IntConstant(1, LlvmType.I8))),
        "fail" to listOf(LlvmInstruction.Ret(LlvmValue.IntConstant(0, LlvmType.I8))))
}

/** `void (ptr stream)`: fclose()s the file of the stream variable, if it holds one, and clears it. */
private fun LlvmFunctionBuilder.closeHelper(): LlvmFunction {
    val stream = LlvmValue.Local("stream", LlvmType.Ptr)
    val file = LlvmValue.Local("file", LlvmType.Ptr)
    val open = LlvmValue.Local("open", LlvmType.I1)
    return helper(".file.close", LlvmType.Void, listOf(stream),
        "entry" to listOf(
            LlvmInstruction.Load(file, stream),
            LlvmInstruction.Icmp(IcmpCondition.NE, open, file, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(open, "close", "done")),
        "close" to listOf(
            LlvmInstruction.Call(null, libc(Libc.FCLOSE), listOf(file), LlvmType.I32),
            LlvmInstruction.Store(LlvmValue.NullPtr(), stream),
            LlvmInstruction.Br("done")),
        "done" to listOf(LlvmInstruction.Ret(null)))
}

/** `i16 ()`: $0100 plus the next byte of the read stream, or 0 at its end, for an error, or without a read stream. */
private fun LlvmFunctionBuilder.readByteHelper(): LlvmFunction {
    val file = LlvmValue.Local("file", LlvmType.Ptr)
    val open = LlvmValue.Local("open", LlvmType.I1)
    val char = LlvmValue.Local("char", LlvmType.I32)
    val end = LlvmValue.Local("end", LlvmType.I1)
    val byte = LlvmValue.Local("byte", LlvmType.I16)
    val value = LlvmValue.Local("value", LlvmType.I16)
    return helper(".file.read.byte", LlvmType.I16, emptyList(),
        "entry" to listOf(
            LlvmInstruction.Load(file, readStream()),
            LlvmInstruction.Icmp(IcmpCondition.NE, open, file, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(open, "read", "none")),
        "read" to listOf(
            LlvmInstruction.Call(char, libc(Libc.FGETC), listOf(file), LlvmType.I32),
            LlvmInstruction.Icmp(IcmpCondition.SLT, end, char, i32(0)),
            LlvmInstruction.CondBr(end, "none", "got")),
        "got" to listOf(
            LlvmInstruction.Cast(CastOperator.TRUNC, byte, char, LlvmType.I16),
            LlvmInstruction.BinaryOp(BinaryOperator.OR, value, byte, LlvmValue.IntConstant(0x100, LlvmType.I16)),
            LlvmInstruction.Ret(value)),
        "none" to listOf(LlvmInstruction.Ret(LlvmValue.IntConstant(0, LlvmType.I16))))
}

/** `i8 (i8 byte)`: 1 when fputc() writes the byte to the write stream, else 0. */
private fun LlvmFunctionBuilder.writeByteHelper(): LlvmFunction {
    val byte = LlvmValue.Local("byte", LlvmType.I8)
    val file = LlvmValue.Local("file", LlvmType.Ptr)
    val open = LlvmValue.Local("open", LlvmType.I1)
    val char = LlvmValue.Local("char", LlvmType.I32)
    val written = LlvmValue.Local("written", LlvmType.I32)
    val ok = LlvmValue.Local("ok", LlvmType.I1)
    val result = LlvmValue.Local("result", LlvmType.I8)
    return helper(".file.write.byte", LlvmType.I8, listOf(byte),
        "entry" to listOf(
            LlvmInstruction.Load(file, writeStream()),
            LlvmInstruction.Icmp(IcmpCondition.NE, open, file, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(open, "write", "fail")),
        "write" to listOf(
            LlvmInstruction.Cast(CastOperator.ZEXT, char, byte, LlvmType.I32),
            LlvmInstruction.Call(written, libc(Libc.FPUTC), listOf(char, file), LlvmType.I32),
            LlvmInstruction.Icmp(IcmpCondition.SGE, ok, written, i32(0)),
            LlvmInstruction.Cast(CastOperator.ZEXT, result, ok, LlvmType.I8),
            LlvmInstruction.Ret(result)),
        "fail" to listOf(LlvmInstruction.Ret(LlvmValue.IntConstant(0, LlvmType.I8))))
}

/** `i8 (i32 position)`: 1 when fseek() moves the read stream to the position, else 0; as in the VM, a negative one fails. */
private fun LlvmFunctionBuilder.seekHelper(): LlvmFunction {
    val position = LlvmValue.Local("position", LlvmType.I32)
    val file = LlvmValue.Local("file", LlvmType.Ptr)
    val open = LlvmValue.Local("open", LlvmType.I1)
    val cLong = mapper.cLong
    val offset = if (cLong == LlvmType.I32) position else LlvmValue.Local("offset", cLong)
    val status = LlvmValue.Local("status", LlvmType.I32)
    val ok = LlvmValue.Local("ok", LlvmType.I1)
    val result = LlvmValue.Local("result", LlvmType.I8)
    return helper(".file.seek", LlvmType.I8, listOf(position),
        "entry" to listOf(
            LlvmInstruction.Load(file, readStream()),
            LlvmInstruction.Icmp(IcmpCondition.NE, open, file, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(open, "seek", "fail")),
        "seek" to listOfNotNull(
            if (offset === position) null else LlvmInstruction.Cast(CastOperator.SEXT, offset, position, cLong),
            LlvmInstruction.Call(status, libc(Libc.FSEEK), listOf(file, offset, i32(SEEK_SET)), LlvmType.I32),
            LlvmInstruction.Icmp(IcmpCondition.EQ, ok, status, i32(0)),
            LlvmInstruction.Cast(CastOperator.ZEXT, result, ok, LlvmType.I8),
            LlvmInstruction.Ret(result)),
        "fail" to listOf(LlvmInstruction.Ret(LlvmValue.IntConstant(0, LlvmType.I8))))
}

/** `i32 ()`: the position in the read stream, truncated like the VM's, or 0 for an error or without a read stream. */
private fun LlvmFunctionBuilder.tellHelper(): LlvmFunction {
    val cLong = mapper.cLong
    val file = LlvmValue.Local("file", LlvmType.Ptr)
    val open = LlvmValue.Local("open", LlvmType.I1)
    val position = LlvmValue.Local("position", cLong)
    val valid = LlvmValue.Local("valid", LlvmType.I1)
    val known = LlvmValue.Local("known", cLong)
    val result = if (cLong == LlvmType.I32) known else LlvmValue.Local("result", LlvmType.I32)
    return helper(".file.tell", LlvmType.I32, emptyList(),
        "entry" to listOf(
            LlvmInstruction.Load(file, readStream()),
            LlvmInstruction.Icmp(IcmpCondition.NE, open, file, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(open, "tell", "none")),
        "tell" to listOfNotNull(
            LlvmInstruction.Call(position, libc(Libc.FTELL), listOf(file), cLong),
            LlvmInstruction.Icmp(IcmpCondition.SGE, valid, position, LlvmValue.IntConstant(0, cLong)),
            LlvmInstruction.Select(known, valid, position, LlvmValue.IntConstant(0, cLong)),
            if (result === known) null else LlvmInstruction.Cast(CastOperator.TRUNC, result, known, LlvmType.I32),
            LlvmInstruction.Ret(result)),
        "none" to listOf(LlvmInstruction.Ret(i32(0))))
}

/** `i32 ()`: the size of the read stream's file, from seeking to its end and back, or 0 for an error or without a read stream. */
private fun LlvmFunctionBuilder.sizeHelper(): LlvmFunction {
    val cLong = mapper.cLong
    val file = LlvmValue.Local("file", LlvmType.Ptr)
    val open = LlvmValue.Local("open", LlvmType.I1)
    val position = LlvmValue.Local("position", cLong)
    val seekable = LlvmValue.Local("seekable", LlvmType.I1)
    val size = LlvmValue.Local("size", cLong)
    val valid = LlvmValue.Local("valid", LlvmType.I1)
    val known = LlvmValue.Local("known", cLong)
    val result = if (cLong == LlvmType.I32) known else LlvmValue.Local("result", LlvmType.I32)
    return helper(".file.size", LlvmType.I32, emptyList(),
        "entry" to listOf(
            LlvmInstruction.Load(file, readStream()),
            LlvmInstruction.Icmp(IcmpCondition.NE, open, file, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(open, "measure", "none")),
        "measure" to listOf(
            LlvmInstruction.Call(position, libc(Libc.FTELL), listOf(file), cLong),
            LlvmInstruction.Icmp(IcmpCondition.SGE, seekable, position, LlvmValue.IntConstant(0, cLong)),
            LlvmInstruction.CondBr(seekable, "end", "none")),
        "end" to listOfNotNull(
            LlvmInstruction.Call(null, libc(Libc.FSEEK), listOf(file, LlvmValue.IntConstant(0, cLong), i32(SEEK_END)), LlvmType.I32),
            LlvmInstruction.Call(size, libc(Libc.FTELL), listOf(file), cLong),
            LlvmInstruction.Call(null, libc(Libc.FSEEK), listOf(file, position, i32(SEEK_SET)), LlvmType.I32),
            LlvmInstruction.Icmp(IcmpCondition.SGE, valid, size, LlvmValue.IntConstant(0, cLong)),
            LlvmInstruction.Select(known, valid, size, LlvmValue.IntConstant(0, cLong)),
            if (result === known) null else LlvmInstruction.Cast(CastOperator.TRUNC, result, known, LlvmType.I32),
            LlvmInstruction.Ret(result)),
        "none" to listOf(LlvmInstruction.Ret(i32(0))))
}

/**
 * `ptr (ptr name, ptr address, i1 raw)`: the end address of the file's data, read to the address after skipping a 2-byte header
 * unless raw, or null for an error. A hosted program can't use the address in a header, so a null address is an error too.
 */
private fun LlvmFunctionBuilder.loadHelper(): LlvmFunction {
    val name = LlvmValue.Local("name", LlvmType.Ptr)
    val address = LlvmValue.Local("address", LlvmType.Ptr)
    val raw = LlvmValue.Local("raw", LlvmType.I1)
    val nowhere = LlvmValue.Local("nowhere", LlvmType.I1)
    val file = LlvmValue.Local("file", LlvmType.Ptr)
    val failed = LlvmValue.Local("failed", LlvmType.I1)
    val second = LlvmValue.Local("second", LlvmType.I32)
    val truncated = LlvmValue.Local("truncated", LlvmType.I1)
    val at = LlvmValue.Local("at", LlvmType.Ptr)
    val sizeT = mapper.sizeT
    val count = LlvmValue.Local("count", sizeT)
    val next = LlvmValue.Local("next", LlvmType.Ptr)
    val more = LlvmValue.Local("more", LlvmType.I1)
    val error = LlvmValue.Local("error", LlvmType.I32)
    val broken = LlvmValue.Local("broken", LlvmType.I1)
    return helper(".file.load", LlvmType.Ptr, listOf(name, address, raw),
        "entry" to listOf(
            LlvmInstruction.Icmp(IcmpCondition.EQ, nowhere, address, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(nowhere, "fail", "open")),
        "open" to listOf(
            LlvmInstruction.Call(file, libc(Libc.FOPEN), listOf(name, runtimeString(".file.mode.rb", "rb")), LlvmType.Ptr),
            LlvmInstruction.Icmp(IcmpCondition.EQ, failed, file, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(failed, "fail", "header")),
        "header" to listOf(LlvmInstruction.CondBr(raw, "read", "skip")),
        // the end of the file stays the end, so the second byte tells whether there were two
        "skip" to listOf(
            LlvmInstruction.Call(null, libc(Libc.FGETC), listOf(file), LlvmType.I32),
            LlvmInstruction.Call(second, libc(Libc.FGETC), listOf(file), LlvmType.I32),
            LlvmInstruction.Icmp(IcmpCondition.SLT, truncated, second, i32(0)),
            LlvmInstruction.CondBr(truncated, "unloaded", "read")),
        "read" to listOf(
            LlvmInstruction.Phi(at, listOf(address to "header", address to "skip", next to "read")),
            LlvmInstruction.Call(count, libc(Libc.FREAD), listOf(at, LlvmValue.IntConstant(1, sizeT), LlvmValue.IntConstant(LOAD_CHUNK_SIZE, sizeT), file), sizeT),
            LlvmInstruction.GetElementPtr(next, LlvmType.I8, at, listOf(count), inBounds = false),
            LlvmInstruction.Icmp(IcmpCondition.EQ, more, count, LlvmValue.IntConstant(LOAD_CHUNK_SIZE, sizeT)),
            LlvmInstruction.CondBr(more, "read", "done")),
        "done" to listOf(
            LlvmInstruction.Call(error, libc(Libc.FERROR), listOf(file), LlvmType.I32),
            LlvmInstruction.Call(null, libc(Libc.FCLOSE), listOf(file), LlvmType.I32),
            LlvmInstruction.Icmp(IcmpCondition.NE, broken, error, i32(0)),
            LlvmInstruction.CondBr(broken, "fail", "loaded")),
        "loaded" to listOf(LlvmInstruction.Ret(next)),
        "unloaded" to listOf(
            LlvmInstruction.Call(null, libc(Libc.FCLOSE), listOf(file), LlvmType.I32),
            LlvmInstruction.Br("fail")),
        "fail" to listOf(LlvmInstruction.Ret(LlvmValue.NullPtr())))
}

/**
 * `i8 (i1 raw, ptr name, ptr start, size_t size)`: 1 when the size bytes from the start address are written to the file, else 0.
 * Unless raw, they come after a 2-byte header with the low 16 bits of the start address, as in the VM.
 */
private fun LlvmFunctionBuilder.saveHelper(): LlvmFunction {
    val raw = LlvmValue.Local("raw", LlvmType.I1)
    val name = LlvmValue.Local("name", LlvmType.Ptr)
    val start = LlvmValue.Local("start", LlvmType.Ptr)
    val sizeT = mapper.sizeT
    val size = LlvmValue.Local("size", sizeT)
    val file = LlvmValue.Local("file", LlvmType.Ptr)
    val failed = LlvmValue.Local("failed", LlvmType.I1)
    val address = LlvmValue.Local("address", mapper.intPtr)
    val low = if (mapper.intPtr == LlvmType.I32) address else LlvmValue.Local("low", LlvmType.I32)
    val lsb = LlvmValue.Local("lsb", LlvmType.I32)
    val shifted = LlvmValue.Local("shifted", LlvmType.I32)
    val msb = LlvmValue.Local("msb", LlvmType.I32)
    val written = LlvmValue.Local("written", sizeT)
    val complete = LlvmValue.Local("complete", LlvmType.I1)
    val closed = LlvmValue.Local("closed", LlvmType.I32)
    val flushed = LlvmValue.Local("flushed", LlvmType.I1)
    val ok = LlvmValue.Local("ok", LlvmType.I1)
    val result = LlvmValue.Local("result", LlvmType.I8)
    return helper(".file.save", LlvmType.I8, listOf(raw, name, start, size),
        "entry" to listOf(
            LlvmInstruction.Call(file, libc(Libc.FOPEN), listOf(name, runtimeString(".file.mode.wb", "wb")), LlvmType.Ptr),
            LlvmInstruction.Icmp(IcmpCondition.EQ, failed, file, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(failed, "fail", "opened")),
        "opened" to listOf(LlvmInstruction.CondBr(raw, "write", "header")),
        "header" to listOfNotNull(
            LlvmInstruction.Cast(CastOperator.PTRTOINT, address, start, mapper.intPtr),
            if (low === address) null else LlvmInstruction.Cast(CastOperator.TRUNC, low, address, LlvmType.I32),
            LlvmInstruction.BinaryOp(BinaryOperator.AND, lsb, low, i32(0xff)),
            LlvmInstruction.Call(null, libc(Libc.FPUTC), listOf(lsb, file), LlvmType.I32),
            LlvmInstruction.BinaryOp(BinaryOperator.LSHR, shifted, low, i32(8)),
            LlvmInstruction.BinaryOp(BinaryOperator.AND, msb, shifted, i32(0xff)),
            LlvmInstruction.Call(null, libc(Libc.FPUTC), listOf(msb, file), LlvmType.I32),
            LlvmInstruction.Br("write")),
        // fclose() reports the errors of writing what was still buffered
        "write" to listOf(
            LlvmInstruction.Call(written, libc(Libc.FWRITE), listOf(start, LlvmValue.IntConstant(1, sizeT), size, file), sizeT),
            LlvmInstruction.Icmp(IcmpCondition.EQ, complete, written, size),
            LlvmInstruction.Call(closed, libc(Libc.FCLOSE), listOf(file), LlvmType.I32),
            LlvmInstruction.Icmp(IcmpCondition.EQ, flushed, closed, i32(0)),
            LlvmInstruction.BinaryOp(BinaryOperator.AND, ok, complete, flushed),
            LlvmInstruction.Cast(CastOperator.ZEXT, result, ok, LlvmType.I8),
            LlvmInstruction.Ret(result)),
        "fail" to listOf(LlvmInstruction.Ret(LlvmValue.IntConstant(0, LlvmType.I8))))
}

/**
 * `i8 (i8 kind)`: the LIST_DIRECTORY syscall, with the d_name of struct dirent at [nameOffset]. It prints "size<tab>name" for a file,
 * and "dir<tab>name" for an entry that opendir() opens; the names are copied and sorted first, as readdir() has no order.
 */
private fun LlvmFunctionBuilder.listDirectoryHelper(nameOffset: Int): LlvmFunction {
    val profile = checkNotNull(profile)
    val sizeT = mapper.sizeT
    val cLong = mapper.cLong
    val pointerSize = LlvmValue.IntConstant((profile.pointerBits / 8).toLong(), sizeT)
    val opendir = LlvmValue.Global(externalFunction(profile.opendirSymbol, LlvmType.Ptr, listOf(LlvmType.Ptr)).name)
    val readdir = LlvmValue.Global(externalFunction(profile.readdirSymbol, LlvmType.Ptr, listOf(LlvmType.Ptr)).name)
    val printf = externalFunction("printf", LlvmType.I32, listOf(LlvmType.Ptr), isVarArg = true)
    val compare = helperFunction(".diskio.compare") { compareNamesHelper() }
    fun local(name: String, type: LlvmType) = LlvmValue.Local(name, type)
    val kind = local("kind", LlvmType.I8)
    val namesSlot = local("names_slot", LlvmType.Ptr)
    val countSlot = local("count_slot", LlvmType.Ptr)
    val dir = local("dir", LlvmType.Ptr)
    val failed = local("failed", LlvmType.I1)
    val entry = local("dirent", LlvmType.Ptr)
    val end = local("end", LlvmType.I1)
    val name = local("entry_name", LlvmType.Ptr)
    val dot = local("dot", LlvmType.I32)
    val isDot = local("is_dot", LlvmType.I1)
    val dotDot = local("dotdot", LlvmType.I32)
    val isDotDot = local("is_dotdot", LlvmType.I1)
    val skip = local("skip", LlvmType.I1)
    val count = local("count", sizeT)
    val names = local("names", LlvmType.Ptr)
    val grownCount = local("grown_count", sizeT)
    val bytes = local("bytes", sizeT)
    val grown = local("grown", LlvmType.Ptr)
    val full = local("full", LlvmType.I1)
    val copy = local("name_copy", LlvmType.Ptr)
    val lost = local("lost", LlvmType.I1)
    val slot = local("slot", LlvmType.Ptr)
    val total = local("total", sizeT)
    val list = local("list", LlvmType.Ptr)
    val index = local("index", sizeT)
    val done = local("done", LlvmType.I1)
    val at = local("at", LlvmType.Ptr)
    val file = local("file", LlvmType.Ptr)
    val sub = local("sub", LlvmType.Ptr)
    val isDir = local("is_dir", LlvmType.I1)
    val wantDirs = local("want_dirs", LlvmType.I1)
    val wantFiles = local("want_files", LlvmType.I1)
    val stream = local("stream", LlvmType.Ptr)
    val opened = local("opened", LlvmType.I1)
    val length = local("length", cLong)
    val size = local("file_size", cLong)
    val next = local("next_index", sizeT)
    val zero = LlvmValue.IntConstant(0, sizeT)
    fun print(format: String, text: String, vararg values: LlvmValue) =
        LlvmInstruction.Call(null, LlvmValue.Global(printf.name), listOf(runtimeString(format, text)) + values, LlvmType.I32, printf.type)
    return helper(".diskio.list", LlvmType.I8, listOf(kind),
        "entry" to listOf(
            LlvmInstruction.Alloca(namesSlot, LlvmType.Ptr),
            LlvmInstruction.Alloca(countSlot, sizeT),
            LlvmInstruction.Store(LlvmValue.NullPtr(), namesSlot),
            LlvmInstruction.Store(zero, countSlot),
            LlvmInstruction.Call(dir, opendir, listOf(runtimeString(".diskio.dot", ".")), LlvmType.Ptr),
            LlvmInstruction.Icmp(IcmpCondition.EQ, failed, dir, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(failed, "fail", "read")),
        "read" to listOf(
            LlvmInstruction.Call(entry, readdir, listOf(dir), LlvmType.Ptr),
            LlvmInstruction.Icmp(IcmpCondition.EQ, end, entry, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(end, "sort", "name")),
        "name" to listOf(
            LlvmInstruction.GetElementPtr(name, LlvmType.I8, entry, listOf(i32(nameOffset.toLong())), inBounds = true),
            LlvmInstruction.Call(dot, libc(Libc.STRCMP), listOf(name, runtimeString(".diskio.dot", ".")), LlvmType.I32),
            LlvmInstruction.Icmp(IcmpCondition.EQ, isDot, dot, i32(0)),
            LlvmInstruction.Call(dotDot, libc(Libc.STRCMP), listOf(name, runtimeString(".diskio.dotdot", "..")), LlvmType.I32),
            LlvmInstruction.Icmp(IcmpCondition.EQ, isDotDot, dotDot, i32(0)),
            LlvmInstruction.BinaryOp(BinaryOperator.OR, skip, isDot, isDotDot),
            LlvmInstruction.CondBr(skip, "read", "keep")),
        // without the memory for another name, the listing has the names so far
        "keep" to listOf(
            LlvmInstruction.Load(count, countSlot),
            LlvmInstruction.Load(names, namesSlot),
            LlvmInstruction.BinaryOp(BinaryOperator.ADD, grownCount, count, LlvmValue.IntConstant(1, sizeT)),
            LlvmInstruction.BinaryOp(BinaryOperator.MUL, bytes, grownCount, pointerSize),
            LlvmInstruction.Call(grown, libc(Libc.REALLOC), listOf(names, bytes), LlvmType.Ptr),
            LlvmInstruction.Icmp(IcmpCondition.EQ, full, grown, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(full, "sort", "copy")),
        "copy" to listOf(
            LlvmInstruction.Store(grown, namesSlot),
            LlvmInstruction.Call(copy, libc(Libc.STRDUP), listOf(name), LlvmType.Ptr),
            LlvmInstruction.Icmp(IcmpCondition.EQ, lost, copy, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(lost, "sort", "add")),
        "add" to listOf(
            LlvmInstruction.GetElementPtr(slot, LlvmType.Ptr, grown, listOf(count), inBounds = true),
            LlvmInstruction.Store(copy, slot),
            LlvmInstruction.Store(grownCount, countSlot),
            LlvmInstruction.Br("read")),
        "sort" to listOf(
            LlvmInstruction.Call(null, libc(Libc.CLOSEDIR), listOf(dir), LlvmType.I32),
            LlvmInstruction.Load(total, countSlot),
            LlvmInstruction.Load(list, namesSlot),
            LlvmInstruction.Call(null, libc(Libc.QSORT), listOf(list, total, pointerSize, compare), LlvmType.Void),
            LlvmInstruction.Br("loop")),
        "loop" to listOf(
            LlvmInstruction.Phi(index, listOf(zero to "sort", next to "next")),
            LlvmInstruction.Icmp(IcmpCondition.EQ, done, index, total),
            LlvmInstruction.CondBr(done, "finish", "show")),
        "show" to listOf(
            LlvmInstruction.GetElementPtr(at, LlvmType.Ptr, list, listOf(index), inBounds = true),
            LlvmInstruction.Load(file, at),
            LlvmInstruction.Call(sub, opendir, listOf(file), LlvmType.Ptr),
            LlvmInstruction.Icmp(IcmpCondition.NE, isDir, sub, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(isDir, "directory", "other")),
        "directory" to listOf(
            LlvmInstruction.Call(null, libc(Libc.CLOSEDIR), listOf(sub), LlvmType.I32),
            LlvmInstruction.Icmp(IcmpCondition.NE, wantDirs, kind, LlvmValue.IntConstant(2, LlvmType.I8)),
            LlvmInstruction.CondBr(wantDirs, "print_dir", "next")),
        "print_dir" to listOf(
            print(".diskio.format.dir", "dir\t%s\n", file),
            LlvmInstruction.Br("next")),
        "other" to listOf(
            LlvmInstruction.Icmp(IcmpCondition.NE, wantFiles, kind, LlvmValue.IntConstant(1, LlvmType.I8)),
            LlvmInstruction.CondBr(wantFiles, "measure", "next")),
        "measure" to listOf(
            LlvmInstruction.Call(stream, libc(Libc.FOPEN), listOf(file, runtimeString(".file.mode.rb", "rb")), LlvmType.Ptr),
            LlvmInstruction.Icmp(IcmpCondition.NE, opened, stream, LlvmValue.NullPtr()),
            LlvmInstruction.CondBr(opened, "size", "print_file")),
        "size" to listOf(
            LlvmInstruction.Call(null, libc(Libc.FSEEK), listOf(stream, LlvmValue.IntConstant(0, cLong), i32(SEEK_END)), LlvmType.I32),
            LlvmInstruction.Call(length, libc(Libc.FTELL), listOf(stream), cLong),
            LlvmInstruction.Call(null, libc(Libc.FCLOSE), listOf(stream), LlvmType.I32),
            LlvmInstruction.Br("print_file")),
        // an unreadable file shows size 0
        "print_file" to listOf(
            LlvmInstruction.Phi(size, listOf(LlvmValue.IntConstant(0, cLong) to "measure", length to "size")),
            print(".diskio.format.file", "%ld\t%s\n", size, file),
            LlvmInstruction.Br("next")),
        "next" to listOf(
            LlvmInstruction.Call(null, libc(Libc.FREE), listOf(file), LlvmType.Void),
            LlvmInstruction.BinaryOp(BinaryOperator.ADD, next, index, LlvmValue.IntConstant(1, sizeT)),
            LlvmInstruction.Br("loop")),
        "finish" to listOf(
            LlvmInstruction.Call(null, libc(Libc.FREE), listOf(list), LlvmType.Void),
            LlvmInstruction.Ret(LlvmValue.IntConstant(1, LlvmType.I8))),
        "fail" to listOf(LlvmInstruction.Ret(LlvmValue.IntConstant(0, LlvmType.I8))))
}

/** `i32 (ptr, ptr)`: the qsort() comparison of two string pointers, by strcmp() of the strings. */
private fun LlvmFunctionBuilder.compareNamesHelper(): LlvmFunction {
    val first = LlvmValue.Local("first", LlvmType.Ptr)
    val second = LlvmValue.Local("second", LlvmType.Ptr)
    val a = LlvmValue.Local("a", LlvmType.Ptr)
    val b = LlvmValue.Local("b", LlvmType.Ptr)
    val order = LlvmValue.Local("order", LlvmType.I32)
    return helper(".diskio.compare", LlvmType.I32, listOf(first, second),
        "entry" to listOf(
            LlvmInstruction.Load(a, first),
            LlvmInstruction.Load(b, second),
            LlvmInstruction.Call(order, libc(Libc.STRCMP), listOf(a, b), LlvmType.I32),
            LlvmInstruction.Ret(order)))
}

private fun helper(name: String, returnType: LlvmType, parameters: List<LlvmValue.Local>, vararg blocks: Pair<String, List<LlvmInstruction>>) =
    LlvmFunction(name, returnType, parameters, blocks.map { (label, code) -> LlvmBlock(label, code.toMutableList()) }.toMutableList(), linkage = LlvmLinkage.INTERNAL)
