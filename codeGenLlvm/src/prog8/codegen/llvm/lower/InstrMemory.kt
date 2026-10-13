package prog8.codegen.llvm.lower

import prog8.codegen.llvm.model.BinaryOperator
import prog8.codegen.llvm.model.CastOperator
import prog8.codegen.llvm.model.LlvmInstruction
import prog8.codegen.llvm.model.LlvmType
import prog8.codegen.llvm.model.LlvmValue
import prog8.intermediate.AddressBase
import prog8.intermediate.IRDataType
import prog8.intermediate.IRInstruction
import prog8.intermediate.MemoryReference
import prog8.intermediate.Opcode
import prog8.intermediate.requireDest
import prog8.intermediate.requireHardwareSlot
import prog8.intermediate.requireMemory
import prog8.intermediate.requireSrcA
import prog8.intermediate.symbolName

/** Lowers register loads, memory access through a symbol or a fixed address, and the calling convention slots. */
internal fun FunctionLowering.lowerMemory(ins: IRInstruction) {
    when (ins.opcode) {
        Opcode.LOAD -> ins.requireDest().let { write(it, immediate(ins, typeOf(it.type))) }
        Opcode.LOADR -> write(ins.requireDest(), read(ins.requireSrcA()))
        Opcode.LOADM -> ins.requireDest().let { write(it, loadMemory(ins, it.register.toString())) }
        Opcode.STOREM -> storeMemory(ins, read(ins.requireSrcA()))
        Opcode.STOREIM -> storeMemory(ins, immediate(ins, typeOf(checkNotNull(ins.type))))
        Opcode.STOREZM -> storeMemory(ins, LlvmValue.ZeroInitializer(typeOf(checkNotNull(ins.type))))
        Opcode.LOADHR -> {
            val destination = ins.requireDest()
            val slot = builder.hardwareSlot(ins.requireHardwareSlot().slot, destination.isFloat)
            write(destination, derive(destination.register.toString(), if (destination.isFloat) LlvmType.Double else LlvmType.I64) { LlvmInstruction.Load(it, slot) })
        }
        Opcode.STOREHR -> {
            val source = ins.requireSrcA()
            val slot = builder.hardwareSlot(ins.requireHardwareSlot().slot, source.isFloat)
            emit(LlvmInstruction.Store(convert(read(source), if (source.isFloat) LlvmType.Double else LlvmType.I64), slot))
        }
        else -> throw IllegalArgumentException("${ins.opcode} is no load or store instruction")
    }
}

/** The value in memory that [ins] reads, as the instruction's type; [base] names the new value. */
internal fun FunctionLowering.loadMemory(ins: IRInstruction, base: String): LlvmValue {
    val memory = ins.requireMemory()
    val type = checkNotNull(ins.type)
    pointerArray(memory)?.let { array ->
        val element = LlvmValue.Local(freshName(base), LlvmType.Ptr)
        PointerArrayAccess.load(array, type, memory, null, element).forEach { emit(it) }
        return element
    }
    val address = memoryAddress(memory)
    return derive(base, typeOf(type)) { LlvmInstruction.Load(it, address, align = alignment(typeOf(type))) }
}

internal fun FunctionLowering.storeMemory(ins: IRInstruction, value: LlvmValue) {
    val memory = ins.requireMemory()
    val type = checkNotNull(ins.type)
    pointerArray(memory)?.let { array ->
        PointerArrayAccess.store(array, type, memory, null, value, freshName("store")).forEach { emit(it) }
        return
    }
    emit(LlvmInstruction.Store(convert(value, typeOf(type)), memoryAddress(memory), align = alignment(typeOf(type))))
}

private fun FunctionLowering.pointerArray(memory: MemoryReference): PointerArray? = memory.symbolName?.let { builder.symbols.pointerArrays[it] }

/** Fixed addresses inside memory-mapped variables use their backing storage, all other fixed addresses stay as they are. */
private fun FunctionLowering.memoryAddress(memory: MemoryReference): LlvmValue {
    require(memory is MemoryReference.Direct) { "${sub.label} has ${memory::class.simpleName} memory access, which isn't supported yet" }
    return when (val base = memory.base) {
        is AddressBase.Symbol -> symbolAddress(base.name, memory.displacement)
        is AddressBase.Absolute -> {
            val address = base.address.value.toLong() + memory.displacement
            builder.symbols.memoryMappedAddress(address) ?: integerConstant(address, LlvmType.Ptr)
        }
        is AddressBase.FrameSlot -> throw IllegalArgumentException("${sub.label} uses a stack frame slot, which the llvm64 target doesn't have")
    }
}

// IR memory addresses aren't necessarily aligned, think of packed structs and memory-mapped variables
private fun alignment(type: LlvmType): Int? = if (type == LlvmType.I8) null else 1

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
        require(irSize(type) == array.irElementSize) { "a $type access doesn't match the ${array.irElementSize}-byte elements of ${array.name}" }
        fun derived(suffix: String, type: LlvmType, instruction: (LlvmValue.Local) -> LlvmInstruction) =
            LlvmValue.Local("$temp.$suffix", type).also { code += instruction(it) }

        val element = when (memory) {
            is MemoryReference.Direct -> {
                require(index == null) { "direct access into ${array.name} has no index register" }
                i64(elementOffset(array, memory.displacement))
            }
            is MemoryReference.Indexed -> {
                val register = requireNotNull(index) { "indexed access into ${array.name} needs the index register's value" }
                require(register.type.isInteger) { "index into ${array.name} must be an integer, not ${register.type}" }
                require(memory.scale % array.irElementSize == 0) { "index scale ${memory.scale} doesn't address whole elements of ${array.name}" }
                val factor = (memory.scale / array.irElementSize).toLong()
                val offset = elementOffset(array, memory.displacement)
                val widened = if (register.type == LlvmType.I64) register
                    else derived("index", LlvmType.I64) { LlvmInstruction.Cast(CastOperator.ZEXT, it, register, LlvmType.I64) }
                val scaled = if (factor == 1L) widened
                    else derived("scaled", LlvmType.I64) { LlvmInstruction.BinaryOp(BinaryOperator.MUL, it, widened, i64(factor)) }
                if (offset == 0L) scaled
                else derived("slot", LlvmType.I64) { LlvmInstruction.BinaryOp(BinaryOperator.ADD, it, scaled, i64(offset)) }
            }
            is MemoryReference.Indirect -> error("an indirect reference has no symbol")
        }
        return derived("elem", LlvmType.Ptr) { LlvmInstruction.GetElementPtr(it, LlvmType.Ptr, array.address, listOf(element)) }
    }

    private fun i64(value: Long) = LlvmValue.IntConstant(value, LlvmType.I64)

    private fun elementOffset(array: PointerArray, displacement: Int): Long {
        require(displacement % array.irElementSize == 0) { "byte offset $displacement is not an element boundary of ${array.name}" }
        return (displacement / array.irElementSize).toLong()
    }

    private fun irSize(type: IRDataType): Int? = when (type) {
        IRDataType.BYTE -> 1
        IRDataType.WORD -> 2
        IRDataType.LONG -> 4
        IRDataType.POINTER -> 8
        IRDataType.FLOAT -> null
    }
}
