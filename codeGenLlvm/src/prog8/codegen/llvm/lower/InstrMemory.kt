package prog8.codegen.llvm.lower

import prog8.code.core.BaseDataType
import prog8.codegen.llvm.model.BinaryOperator
import prog8.codegen.llvm.model.CastOperator
import prog8.codegen.llvm.model.IcmpCondition
import prog8.codegen.llvm.model.LlvmInstruction
import prog8.codegen.llvm.model.LlvmType
import prog8.codegen.llvm.model.LlvmValue
import prog8.intermediate.AddressBase
import prog8.intermediate.CallingConventionSlot
import prog8.intermediate.IRDataType
import prog8.intermediate.IRInstruction
import prog8.intermediate.IRStMemVar
import prog8.intermediate.IRStStaticVariable
import prog8.intermediate.ImmediateOperand
import prog8.intermediate.MemoryReference
import prog8.intermediate.Opcode
import prog8.intermediate.requireDest
import prog8.intermediate.requireHardwareSlot
import prog8.intermediate.requireImmediate
import prog8.intermediate.requireMemory
import prog8.intermediate.requireSrcA
import prog8.intermediate.symbolName

// the value stack of PUSH and POP, where a LONG takes the size of its register slot because it may hold an address
private const val VALUE_STACK_SIZE = 1024

/**
 * Lowers register loads, memory access through a symbol, a fixed address, an index register or a pointer register,
 * the calling convention slots, and the value stack.
 */
internal fun FunctionLowering.lowerMemory(ins: IRInstruction) {
    when (ins.opcode) {
        Opcode.LOAD -> {
            val dest = ins.requireDest()
            val immediate = ins.requireImmediate()
            // the whole address, which a LONG register can hold
            write(dest, if (immediate is ImmediateOperand.SymbolAddress) symbolAddress(immediate.symbol, immediate.offset) else immediate(ins, typeOf(dest.type)))
        }
        Opcode.LOADR -> write(ins.requireDest(), readWide(ins.requireSrcA()))
        Opcode.LOADM, Opcode.LOADX, Opcode.LOADI -> ins.requireDest().let { write(it, loadMemory(ins, it.register.toString())) }
        Opcode.STOREM, Opcode.STOREX, Opcode.STOREI -> storeMemory(ins, readWide(ins.requireSrcA()))
        Opcode.STOREIM -> storeMemory(ins, immediate(ins, typeOf(checkNotNull(ins.type))))
        Opcode.STOREZM, Opcode.STOREZX, Opcode.STOREZI -> storeMemory(ins, LlvmValue.ZeroInitializer(typeOf(checkNotNull(ins.type))))
        Opcode.LOADP_INC -> {
            val dest = ins.requireDest()
            postIncrement(ins) { pointer, type -> write(dest, derive(dest.register.toString(), type) { LlvmInstruction.Load(it, pointer, align = alignment(type)) }) }
        }
        Opcode.STOREP_INC -> {
            val value = readWide(ins.requireSrcA())
            postIncrement(ins) { pointer, type -> emit(LlvmInstruction.Store(convert(value, type), pointer, align = alignment(type))) }
        }
        Opcode.LOADHR -> {
            val destination = ins.requireDest()
            val slot = builder.hardwareSlot(ins.requireHardwareSlot().slot, destination.isFloat)
            write(destination, derive(destination.register.toString(), if (destination.isFloat) LlvmType.Double else LlvmType.I64) { LlvmInstruction.Load(it, slot) })
        }
        Opcode.STOREHR -> {
            val source = ins.requireSrcA()
            val slot = builder.hardwareSlot(ins.requireHardwareSlot().slot, source.isFloat)
            emit(LlvmInstruction.Store(convert(readWide(source), if (source.isFloat) LlvmType.Double else LlvmType.I64), slot))
        }
        Opcode.LOADHFACZERO, Opcode.LOADHFACONE -> {
            val dest = ins.requireDest()
            val fac = facSlot(one = ins.opcode == Opcode.LOADHFACONE)
            write(dest, derive(dest.register.toString(), LlvmType.Double) { LlvmInstruction.Load(it, fac) })
        }
        Opcode.STOREHFACZERO, Opcode.STOREHFACONE -> emit(LlvmInstruction.Store(read(ins.requireSrcA()), facSlot(one = ins.opcode == Opcode.STOREHFACONE)))
        Opcode.PUSH -> push(readWide(ins.requireSrcA()))
        Opcode.POP -> {
            val dest = ins.requireDest()
            write(dest, pop(if (dest.type == IRDataType.LONG) builder.mapper.intPtr else typeOf(dest.type), dest.register.toString()))
        }
        Opcode.PUSHST -> pushStatus()
        Opcode.POPST -> popStatus()
        else -> throw IllegalArgumentException("${ins.opcode} is no load or store instruction")
    }
}

/** The value in memory that [ins] reads, as the instruction's type; [base] names the new value. */
internal fun FunctionLowering.loadMemory(ins: IRInstruction, base: String): LlvmValue {
    val memory = ins.requireMemory()
    val type = checkNotNull(ins.type)
    pointerArray(memory)?.let { array ->
        val element = LlvmValue.Local(freshName(base), LlvmType.Ptr)
        PointerArrayAccess.load(array, type, memory, indexValue(memory), element).forEach { emit(it) }
        return element
    }
    val address = memoryAddress(memory)
    return derive(base, typeOf(type)) { LlvmInstruction.Load(it, address, align = alignment(typeOf(type))) }
}

internal fun FunctionLowering.storeMemory(ins: IRInstruction, value: LlvmValue) {
    val memory = ins.requireMemory()
    val type = checkNotNull(ins.type)
    pointerArray(memory)?.let { array ->
        PointerArrayAccess.store(array, type, memory, indexValue(memory), value, freshName("store")).forEach { emit(it) }
        return
    }
    emit(LlvmInstruction.Store(convert(value, typeOf(type)), memoryAddress(memory), align = alignment(typeOf(type))))
}

// IR memory addresses aren't necessarily aligned, think of packed structs and memory-mapped variables
internal fun alignment(type: LlvmType): Int? = if (type == LlvmType.I8) null else 1

private fun FunctionLowering.pointerArray(memory: MemoryReference): PointerArray? = memory.symbolName?.let { builder.symbols.pointerArrays[it] }

private fun FunctionLowering.indexValue(memory: MemoryReference): LlvmValue? = (memory as? MemoryReference.Indexed)?.let { asInteger(read(it.index)) }

private fun FunctionLowering.memoryAddress(memory: MemoryReference): LlvmValue = when (memory) {
    is MemoryReference.Direct -> baseAddress(memory.base, memory.displacement)
    is MemoryReference.Indexed -> offsetAddress(baseAddress(memory.base, memory.displacement), scaledIndex(memory))
    is MemoryReference.Indirect -> offsetAddress(pointerValue(memory.pointer), LlvmValue.IntConstant(memory.displacement.toLong(), builder.mapper.intPtr))
}

/** Fixed addresses inside memory-mapped variables use their backing storage, all other fixed addresses stay as they are. */
private fun FunctionLowering.baseAddress(base: AddressBase, displacement: Int): LlvmValue = when (base) {
    is AddressBase.Symbol -> symbolAddress(base.name, displacement)
    is AddressBase.Absolute -> {
        integerConstant(base.address.value.toLong() + displacement, LlvmType.Ptr)
    }
    is AddressBase.FrameSlot -> throw IllegalArgumentException("${sub.label} uses a stack frame slot, which the llvm targets don't have")
}

// like the VM, which reads an index register as unsigned
private fun FunctionLowering.scaledIndex(memory: MemoryReference.Indexed): LlvmValue {
    val intPtr = builder.mapper.intPtr
    val index = convert(asInteger(read(memory.index)), intPtr, "index")
    if (memory.scale == 1)
        return index
    return derive("offset", intPtr) { LlvmInstruction.BinaryOp(BinaryOperator.MUL, it, index, LlvmValue.IntConstant(memory.scale.toLong(), intPtr)) }
}

private fun FunctionLowering.offsetAddress(address: LlvmValue, offset: LlvmValue): LlvmValue =
    if (offset is LlvmValue.IntConstant && offset.value == 0L) address
    else derive("addr", LlvmType.Ptr) { LlvmInstruction.GetElementPtr(it, LlvmType.I8, address, listOf(offset), inBounds = false) }

/** LOADP_INC and STOREP_INC access memory through the pointer variable of their memory operand, then advance that by the access size. */
private fun FunctionLowering.postIncrement(ins: IRInstruction, access: (pointer: LlvmValue, type: LlvmType) -> Unit) {
    val memory = ins.requireMemory()
    require(pointerArray(memory) == null) { "${ins.opcode} in ${sub.label} advances an element of pointer array ${memory.symbolName}, which isn't supported" }
    val variable = memoryAddress(memory)
    val storage = pointerVariableType(memory)
    val pointer = convert(derive("ptr", storage) { LlvmInstruction.Load(it, variable, align = alignment(storage)) }, LlvmType.Ptr, "ptr")
    val type = typeOf(checkNotNull(ins.type))
    access(pointer, type)
    val size = LlvmValue.IntConstant(builder.mapper.byteSize(type).toLong(), builder.mapper.intPtr)
    val next = derive("ptr", LlvmType.Ptr) { LlvmInstruction.GetElementPtr(it, LlvmType.I8, pointer, listOf(size), inBounds = false) }
    emit(LlvmInstruction.Store(convert(next, storage, "ptr"), variable, align = alignment(storage)))
}

/** Normally a pointer variable, but IR code can also keep an address in a word or long variable. */
private fun FunctionLowering.pointerVariableType(memory: MemoryReference): LlvmType {
    val dt = when (val symbol = memory.symbolName?.let { builder.program.st.lookup(it) }) {
        is IRStStaticVariable -> symbol.dt
        is IRStMemVar -> symbol.dt
        else -> null
    }
    return when (dt?.base) {
        BaseDataType.UWORD, BaseDataType.WORD -> LlvmType.I16
        BaseDataType.LONG -> LlvmType.I32
        else -> LlvmType.Ptr
    }
}

// the VM's FAC0 and FAC1 are the float registers of calling convention slots 6 and 7
private fun FunctionLowering.facSlot(one: Boolean): LlvmValue = builder.hardwareSlot(CallingConventionSlot(if (one) 7 else 6), float = true)

private fun FunctionLowering.push(value: LlvmValue) {
    val intPtr = builder.mapper.intPtr
    val top = builder.runtimeGlobal(".stack.top", intPtr)
    val offset = derive("sp", intPtr) { LlvmInstruction.Load(it, top) }
    emit(LlvmInstruction.Store(value, valueStackEntry(offset), align = alignment(value.type)))
    val size = LlvmValue.IntConstant(builder.mapper.byteSize(value.type).toLong(), intPtr)
    emit(LlvmInstruction.Store(derive("sp", intPtr) { LlvmInstruction.BinaryOp(BinaryOperator.ADD, it, offset, size) }, top))
}

private fun FunctionLowering.pop(type: LlvmType, base: String): LlvmValue {
    val intPtr = builder.mapper.intPtr
    val top = builder.runtimeGlobal(".stack.top", intPtr)
    val offset = derive("sp", intPtr) { LlvmInstruction.Load(it, top) }
    val size = LlvmValue.IntConstant(builder.mapper.byteSize(type).toLong(), intPtr)
    val newTop = derive("sp", intPtr) { LlvmInstruction.BinaryOp(BinaryOperator.SUB, it, offset, size) }
    emit(LlvmInstruction.Store(newTop, top))
    val entry = valueStackEntry(newTop)
    return derive(base, type) { LlvmInstruction.Load(it, entry, align = alignment(type)) }
}

private fun FunctionLowering.valueStackEntry(offset: LlvmValue): LlvmValue {
    val data = builder.runtimeGlobal(".stack.data", LlvmType.Array(VALUE_STACK_SIZE, LlvmType.I8))
    return derive("sp", LlvmType.Ptr) { LlvmInstruction.GetElementPtr(it, LlvmType.I8, data, listOf(offset), inBounds = false) }
}

// the bit of each flag in the status byte of PUSHST and POPST, as in the VM
private val statusBits = listOf(Flag.NEGATIVE to 7, Flag.OVERFLOW to 6, Flag.ZERO to 1, Flag.CARRY to 0)

private fun FunctionLowering.pushStatus() {
    val status = statusBits.map { (statusFlag, bit) ->
        val value = convert(flag(statusFlag), LlvmType.I8, "status")
        if (bit == 0) value else derive("status", LlvmType.I8) { LlvmInstruction.BinaryOp(BinaryOperator.SHL, it, value, LlvmValue.IntConstant(bit.toLong(), LlvmType.I8)) }
    }.reduce { bits, value -> derive("status", LlvmType.I8) { LlvmInstruction.BinaryOp(BinaryOperator.OR, it, bits, value) } }
    push(status)
}

private fun FunctionLowering.popStatus() {
    val status = pop(LlvmType.I8, "status")
    for ((statusFlag, bit) in statusBits) {
        val masked = derive("status", LlvmType.I8) { LlvmInstruction.BinaryOp(BinaryOperator.AND, it, status, LlvmValue.IntConstant(1L shl bit, LlvmType.I8)) }
        setFlag(statusFlag, derive("flag", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.NE, it, masked, LlvmValue.IntConstant(0, LlvmType.I8)) })
    }
}

/**
 * Lowers IR loads and stores of [PointerArray] elements to `getelementptr ptr` plus `load ptr` or `store ptr`.
 * The IR's byte offsets assume its own element size; they are converted into indices of the native `ptr` elements.
 */
internal object PointerArrayAccess {

    /** [index] is the value of the index register when [memory] is an indexed reference, else null. */
    fun load(array: PointerArray, type: IRDataType, memory: MemoryReference, index: LlvmValue?, result: LlvmValue.Local): List<LlvmInstruction> {
        require(result.type == LlvmType.Ptr) { "an element of pointer array ${array.name} loads as ptr, not ${result.type}" }
        val code = mutableListOf<LlvmInstruction>()
        code += LlvmInstruction.Load(result, elementAddress(array, type, memory, index, result.name, code))
        return code
    }

    /** Integer [value]s are converted with `inttoptr`; [temp] prefixes the names of the intermediate values. */
    fun store(array: PointerArray, type: IRDataType, memory: MemoryReference, index: LlvmValue?, value: LlvmValue, temp: String): List<LlvmInstruction> {
        val code = mutableListOf<LlvmInstruction>()
        val address = elementAddress(array, type, memory, index, temp, code)
        val pointer = when {
            value.type == LlvmType.Ptr -> value
            value.type.isInteger -> LlvmValue.Local("$temp.ptr", LlvmType.Ptr).also { code += LlvmInstruction.Cast(CastOperator.INTTOPTR, it, value, LlvmType.Ptr) }
            else -> throw IllegalArgumentException("can't store a ${value.type} into pointer array ${array.name}")
        }
        code += LlvmInstruction.Store(pointer, address)
        return code
    }

    private fun elementAddress(array: PointerArray, type: IRDataType, memory: MemoryReference, index: LlvmValue?, temp: String,
                               code: MutableList<LlvmInstruction>): LlvmValue {
        require(memory.symbolName == array.name) { "pointer array ${array.name} must be accessed through its own symbol, not via $memory" }
        require(irSize(type, array.indexType) == array.irElementSize) { "a $type access doesn't match the ${array.irElementSize}-byte elements of ${array.name}" }
        fun derived(suffix: String, type: LlvmType, instruction: (LlvmValue.Local) -> LlvmInstruction) =
            LlvmValue.Local("$temp.$suffix", type).also { code += instruction(it) }

        val indexType = array.indexType
        fun constant(value: Long) = LlvmValue.IntConstant(value, indexType)
        val element = when (memory) {
            is MemoryReference.Direct -> {
                require(index == null) { "direct access into ${array.name} has no index register" }
                constant(elementOffset(array, memory.displacement))
            }
            is MemoryReference.Indexed -> {
                val register = requireNotNull(index) { "indexed access into ${array.name} needs the index register's value" }
                require(register.type.isInteger) { "index into ${array.name} must be an integer, not ${register.type}" }
                require(memory.scale % array.irElementSize == 0) { "index scale ${memory.scale} doesn't address whole elements of ${array.name}" }
                val factor = (memory.scale / array.irElementSize).toLong()
                val offset = elementOffset(array, memory.displacement)
                val widened = when {
                    register.type == indexType -> register
                    checkNotNull(register.type.bitWidth) > checkNotNull(indexType.bitWidth) ->
                        derived("index", indexType) { LlvmInstruction.Cast(CastOperator.TRUNC, it, register, indexType) }
                    else -> derived("index", indexType) { LlvmInstruction.Cast(CastOperator.ZEXT, it, register, indexType) }
                }
                val scaled = if (factor == 1L) widened
                    else derived("scaled", indexType) { LlvmInstruction.BinaryOp(BinaryOperator.MUL, it, widened, constant(factor)) }
                if (offset == 0L) scaled
                else derived("slot", indexType) { LlvmInstruction.BinaryOp(BinaryOperator.ADD, it, scaled, constant(offset)) }
            }
            is MemoryReference.Indirect -> error("an indirect reference has no symbol")
        }
        return derived("elem", LlvmType.Ptr) { LlvmInstruction.GetElementPtr(it, LlvmType.Ptr, array.address, listOf(element)) }
    }

    private fun elementOffset(array: PointerArray, displacement: Int): Long {
        require(displacement % array.irElementSize == 0) { "byte offset $displacement is not an element boundary of ${array.name}" }
        return (displacement / array.irElementSize).toLong()
    }

    private fun irSize(type: IRDataType, indexType: LlvmType): Int? = when (type) {
        IRDataType.BYTE -> 1
        IRDataType.WORD -> 2
        IRDataType.LONG -> 4
        IRDataType.POINTER -> checkNotNull(indexType.bitWidth) / 8
        IRDataType.QUAD -> 8
        IRDataType.FLOAT -> null
    }
}
