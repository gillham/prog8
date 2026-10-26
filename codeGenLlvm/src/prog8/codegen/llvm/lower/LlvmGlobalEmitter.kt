package prog8.codegen.llvm.lower

import prog8.code.INTERNED_STRINGS_MODULENAME
import prog8.code.StMemorySlabBlockName
import prog8.code.core.BaseDataType
import prog8.code.target.LlvmTarget
import prog8.codegen.llvm.model.CastOperator
import prog8.codegen.llvm.model.LlvmBlock
import prog8.codegen.llvm.model.LlvmFunction
import prog8.codegen.llvm.model.LlvmGlobal
import prog8.codegen.llvm.model.LlvmInstruction
import prog8.codegen.llvm.model.LlvmLinkage
import prog8.codegen.llvm.model.LlvmModule
import prog8.codegen.llvm.model.LlvmType
import prog8.codegen.llvm.model.LlvmValue
import prog8.codegen.llvm.types.LlvmTypeMapper
import prog8.intermediate.IRDataType
import prog8.intermediate.IRProgram
import prog8.intermediate.IRStConstant
import prog8.intermediate.IRStMemVar
import prog8.intermediate.IRStMemorySlab
import prog8.intermediate.IRStStaticVariable
import prog8.intermediate.IRStStructDef
import prog8.intermediate.IRStStructInstance
import prog8.intermediate.IRStSymbolicReference
import prog8.intermediate.IRVariableInitializer
import prog8.intermediate.Opcode
import prog8.intermediate.OperandDirection
import prog8.intermediate.VirtualRegister
import prog8.intermediate.symbolName

// the leading dot keeps it apart from any Prog8 symbol
private const val STARTUP_INITIALIZER = ".init.addresses"

/**
 * Emits the module-level globals for the data symbols of an IRProgram, and maps each IR symbol name onto
 * the LLVM constant pointer that addresses its storage (used later to lower memory operands).
 */
internal class LlvmGlobalEmitter(private val program: IRProgram, private val stackLocals: Map<String, String> = emptyMap()) {
    private val st = program.st
    private val target = program.options.compTarget
    private val mapper = LlvmTypeMapper(st, target)
    private val subroutinesWithIndirectEntry: Set<String> =
        if ((target as? LlvmTarget)?.profile?.strictIndirectSignatures == true) program.allSubs().filter { needsIndirectEntry(it) }.map { it.label }.toSet()
        else emptySet()
    private val indirectEntries = linkedSetOf<String>()

    private val internedStringNames: Map<String, String> by lazy {
        st.allVariables().filter { it.name.startsWith("$INTERNED_STRINGS_MODULENAME.") }
            .mapIndexed { index, variable -> variable.name to ".str.$index" }.toMap()
    }

    private val memoryMappedClusters: List<MemoryMappedCluster> by lazy {
        val clusters = mutableListOf<MemoryMappedCluster>()
        for (memvar in st.allMemMappedVariables().sortedBy { it.address }) {
            val start = memvar.address.toLong()
            val cluster = clusters.lastOrNull()?.takeIf { start <= it.end } ?: MemoryMappedCluster(start, mapper.intPtr).also { clusters += it }
            cluster.members += memvar
            cluster.end = maxOf(cluster.end, start + target.memorySize(memvar.dt, memvar.length?.toInt()))
        }
        clusters
    }

    // built in one go: initializers may reference symbols whose globals are emitted later
    private val addresses: Map<String, LlvmValue> by lazy {
        buildMap {
            st.allVariables().filter { it.name !in stackLocals }.forEach { put(it.name, LlvmValue.Global(internedStringNames[it.name] ?: it.name)) }
            st.allStructInstances().forEach { put(it.name, LlvmValue.Global(it.name)) }
            st.allMemorySlabs().forEach { put(it.name, LlvmValue.Global(it.name)) }
            memoryMappedClusters.forEach { cluster -> cluster.members.forEach { put(it.name, cluster.addressAt(it.address.toLong())) } }
            st.allConstants().forEach { put(it.name, LlvmValue.Global(it.name)) }
        }
    }

    // word, long and uquad arrays holding addresses (str[] is an array of the address integer type in the IR) get native ptr elements, so no address is truncated
    private val pointerArrays: Map<String, PointerArray> by lazy {
        val storedWithPointers = symbolsStoredWithPointers()
        st.allVariables().mapNotNull { variable ->
            val irElementSize = when {
                variable.dt.isPointerArray -> target.POINTER_MEM_SIZE.toInt()
                holdsAddresses(variable, storedWithPointers) -> target.memorySize(checkNotNull(variable.dt.sub))
                else -> null
            }
            irElementSize?.let {
                val length = requireNotNull(variable.length) { "array ${variable.name} has no length" }.toInt()
                variable.name to PointerArray(variable.name, addresses.getValue(variable.name), length, it, mapper.intPtr)
            }
        }.toMap()
    }

    fun emit(module: LlvmModule): LlvmDataSymbols {
        require(target.FLOAT_MEM_SIZE == 8u) { "the LLVM backend needs 8-byte floats" }
        val globals = buildList {
            st.allVariables().filter { it.name !in stackLocals }.mapTo(this) { variableGlobal(it) }
            st.allStructInstances().mapTo(this) { structInstanceGlobal(it) }
            st.allMemorySlabs().mapTo(this) { memorySlabGlobal(it) }
            memoryMappedClusters.mapTo(this) { memoryMappedGlobal(it) }
            st.allConstants().mapTo(this) { constantGlobal(it) }
        }
        val startupStores = mutableListOf<LlvmInstruction>()
        module.globals += globals.map { withStartupAddresses(it, startupStores) }
        if (startupStores.isNotEmpty())
            addStartupInitializer(module, startupStores)
        val locals = st.allVariables().filter { it.name in stackLocals }.map { variable ->
            val type = mapper.storageType(variable.dt, variable.length)
            StackLocal(variable.name, stackLocals.getValue(variable.name), type, initializer(variable, type))
        }
        return LlvmDataSymbols(addresses, memoryMappedClusters, pointerArrays, indirectEntries, locals.groupBy { it.subroutine })
    }

    /**
     * Static data can only hold a symbol address as a pointer-aligned `ptr`, arm64 macOS rejects other relocations.
     * Other address slots, like unaligned pointer fields of packed structs, are zeroed and get stored at startup instead.
     */
    private fun withStartupAddresses(global: LlvmGlobal, startupStores: MutableList<LlvmInstruction>): LlvmGlobal {
        val initializer = global.initializer ?: return global
        val deferred = mutableListOf<Pair<Long, LlvmValue>>()
        val staticInitializer = deferAddresses(initializer, 0L, deferred)
        for ((offset, value) in deferred) {
            val base = LlvmValue.Global(global.name)
            val slot = if (offset == 0L) base else LlvmValue.ConstantGep(LlvmType.I8, base, listOf(LlvmValue.IntConstant(offset, mapper.intPtr)))
            // volatile, so that LLVM can't fold the store back into the static initializer
            startupStores += LlvmInstruction.Store(value, slot, align = 1, volatile = true)
        }
        val alignment = global.align ?: naturalAlignment(global.type)
        val align = if (containsAddress(staticInitializer) && alignment < mapper.pointerBytes) mapper.pointerBytes else global.align
        return global.copy(initializer = staticInitializer, isConstant = global.isConstant && deferred.isEmpty(), align = align)
    }

    private fun deferAddresses(value: LlvmValue, offset: Long, deferred: MutableList<Pair<Long, LlvmValue>>): LlvmValue = when (value) {
        is LlvmValue.StructConstant -> {
            var fieldOffset = offset
            LlvmValue.StructConstant(value.type, value.fields.map { field ->
                deferAddresses(field, fieldOffset, deferred).also { fieldOffset += mapper.byteSize(field.type) }
            })
        }
        is LlvmValue.ArrayConstant -> {
            val elementSize = mapper.byteSize(value.elementType)
            LlvmValue.ArrayConstant(value.elementType, value.elements.mapIndexed { index, element ->
                deferAddresses(element, offset + index * elementSize, deferred)
            })
        }
        else ->
            if (isAddress(value) && (value.type != LlvmType.Ptr || offset % mapper.pointerBytes != 0L)) {
                deferred += offset to value
                LlvmValue.ZeroInitializer(value.type)
            } else value
    }

    private fun isAddress(value: LlvmValue): Boolean = when (value) {
        is LlvmValue.Global, is LlvmValue.ConstantGep -> true
        is LlvmValue.ConstantCast -> isAddress(value.value)
        else -> false
    }

    private fun containsAddress(value: LlvmValue): Boolean = when (value) {
        is LlvmValue.StructConstant -> value.fields.any { containsAddress(it) }
        is LlvmValue.ArrayConstant -> value.elements.any { containsAddress(it) }
        else -> isAddress(value)
    }

    private fun naturalAlignment(type: LlvmType): Int = when (type) {
        is LlvmType.Array -> naturalAlignment(type.elementType)
        is LlvmType.Struct -> if (type.packed) 1 else type.fields.maxOfOrNull { naturalAlignment(it) } ?: 1
        else -> mapper.byteSize(type)
    }

    private fun addStartupInitializer(module: LlvmModule, stores: List<LlvmInstruction>) {
        val body = LlvmBlock("entry", (stores + LlvmInstruction.Ret(null)).toMutableList())
        module.functions += LlvmFunction(STARTUP_INITIALIZER, LlvmType.Void, emptyList(), mutableListOf(body), linkage = LlvmLinkage.INTERNAL)
        val entryType = LlvmType.Struct(listOf(LlvmType.I32, LlvmType.Ptr, LlvmType.Ptr), packed = false)
        // 65535 is the default constructor priority
        val entry = LlvmValue.StructConstant(entryType, listOf(LlvmValue.IntConstant(65535, LlvmType.I32), LlvmValue.Global(STARTUP_INITIALIZER), LlvmValue.NullPtr()))
        val constructors = LlvmValue.ArrayConstant(entryType, listOf(entry))
        module.globals += LlvmGlobal("llvm.global_ctors", constructors.type, constructors, isConstant = false, linkage = LlvmLinkage.APPENDING)
    }

    private fun holdsAddresses(variable: IRStStaticVariable, storedWithPointers: Set<String>): Boolean {
        val dt = variable.dt
        if (dt.base != BaseDataType.ARRAY || dt.sub !in setOf(BaseDataType.UWORD, BaseDataType.WORD, BaseDataType.LONG, BaseDataType.UQUAD))
            return false
        val initialValues = (variable.initializationValue as? IRVariableInitializer.Array)?.elements.orEmpty()
        return variable.name in storedWithPointers || initialValues.any { it is IRStSymbolicReference.Symbol }
    }

    /** The symbols that the IR code stores pointer-typed registers into, such as string addresses assigned to str[] elements. */
    private fun symbolsStoredWithPointers(): Set<String> {
        val pointerRegisters = mutableSetOf<VirtualRegister>()
        program.forEachInstruction { ins ->
            ins.registerAccesses.filter { it.direction != OperandDirection.USE && it.type == IRDataType.POINTER }.mapTo(pointerRegisters) { it.register }
        }
        val symbols = mutableSetOf<String>()
        program.forEachInstruction { ins ->
            if ((ins.opcode == Opcode.STOREM || ins.opcode == Opcode.STOREX) && ins.srcA?.register in pointerRegisters)
                ins.memory?.symbolName?.let { symbols += it }
        }
        return symbols
    }

    private fun variableGlobal(variable: IRStStaticVariable): LlvmGlobal {
        val pointerArray = pointerArrays[variable.name]
        val type = pointerArray?.let { LlvmType.Array(it.length, LlvmType.Ptr) } ?: mapper.storageType(variable.dt, variable.length)
        // a pointer array is bigger than the IR thinks, but the element size the IR uses must still agree with Prog8
        val sizeSeenByIr = pointerArray?.let { it.length * it.irElementSize } ?: mapper.byteSize(type)
        check(sizeSeenByIr == target.memorySize(variable.dt, variable.length?.toInt())) {
            "the LLVM type $type of ${variable.name} doesn't match its Prog8 size"
        }
        val internedName = internedStringNames[variable.name]
        val interned = internedName != null
        val linkage = when {
            interned -> LlvmLinkage.PRIVATE
            variable.shared -> LlvmLinkage.EXTERNAL
            else -> LlvmLinkage.INTERNAL
        }
        return LlvmGlobal(internedName ?: variable.name, type, initializer(variable, type), isConstant = interned || variable.readonly,
            linkage = linkage, unnamedAddr = interned, align = variable.align.toInt().takeIf { it > 1 })
    }

    private fun initializer(variable: IRStStaticVariable, type: LlvmType): LlvmValue {
        val init = variable.initializationValue
        return when {
            init == null -> LlvmValue.ZeroInitializer(type)
            init is IRVariableInitializer.Numeric && type !is LlvmType.Array -> scalarConstant(variable.dt.base, init.value, init.quadBits)
            init is IRVariableInitializer.Str && variable.dt.isString -> stringConstant(variable, init)
            init is IRVariableInitializer.Array && variable.dt.isArray && type is LlvmType.Array -> arrayConstant(variable, type, init)
            else -> throw IllegalArgumentException("${variable.name} of type ${variable.dt} can't have a ${init::class.simpleName} initial value")
        }
    }

    private fun stringConstant(variable: IRStStaticVariable, init: IRVariableInitializer.Str): LlvmValue {
        val bytes = program.encoding.encodeString(init.text, init.encoding) + 0u
        require(bytes.size.toUInt() == variable.length) {
            "string ${variable.name} encodes to ${bytes.size} bytes including its terminator, but its length is ${variable.length}"
        }
        return LlvmValue.StringConstant(bytes)
    }

    private fun arrayConstant(variable: IRStStaticVariable, type: LlvmType.Array, init: IRVariableInitializer.Array): LlvmValue {
        require(init.elements.size.toUInt() == variable.length) {
            "array ${variable.name} has ${init.elements.size} initial values, but its length is ${variable.length}"
        }
        val elements = if (variable.dt.sub == BaseDataType.STRUCT_INSTANCE) {
            init.elements.map { structArrayElement(variable, type.elementType, it) }
        } else {
            val elementBase = if (variable.name in pointerArrays) BaseDataType.POINTER else variable.dt.elementType().base
            init.elements.map { slotValue(elementBase, it, variable.name) }
        }
        // an IR file stores string literals as byte arrays; still emit them as c"..." strings
        if (variable.name in internedStringNames && type.elementType == LlvmType.I8 && elements.all { it is LlvmValue.IntConstant })
            return LlvmValue.StringConstant(elements.map { (it as LlvmValue.IntConstant).value.toUByte() })
        return LlvmValue.ArrayConstant(type.elementType, elements)
    }

    private fun structArrayElement(variable: IRStStaticVariable, elementType: LlvmType, element: IRStSymbolicReference): LlvmValue {
        val instance = (element as? IRStSymbolicReference.Symbol)?.let { st.lookup(it.name) as? IRStStructInstance }
        requireNotNull(instance) { "struct array ${variable.name} contains $element, which is not a struct instance" }
        return structValue(instance) ?: LlvmValue.ZeroInitializer(elementType)
    }

    private fun structInstanceGlobal(instance: IRStStructInstance): LlvmGlobal {
        val value = structValue(instance)
        val type = value?.type ?: LlvmType.Array(instance.size.toInt(), LlvmType.I8)
        return LlvmGlobal(instance.name, type, value ?: LlvmValue.ZeroInitializer(type), isConstant = false, linkage = LlvmLinkage.INTERNAL)
    }

    /** Null means zero-filled: [instance] has no initial values, or it is a union. */
    private fun structValue(instance: IRStStructInstance): LlvmValue.StructConstant? {
        val def = requireNotNull(st.lookup(instance.structName) as? IRStStructDef) {
            "struct type ${instance.structName} of ${instance.name} is not in the symbol table"
        }
        if (instance.values.isEmpty() || def.isUnion)
            return null
        val type = mapper.structType(instance.structName) as LlvmType.Struct
        require(instance.values.size == type.fields.size) {
            "struct instance ${instance.name} has ${instance.values.size} initial values for the ${type.fields.size} fields of ${instance.structName}"
        }
        return LlvmValue.StructConstant(type, instance.values.map { slotValue(it.dt, it.value, instance.name) })
    }

    private fun memorySlabGlobal(slab: IRStMemorySlab): LlvmGlobal {
        val align = slab.align.toInt()
        require(align <= 1 || align.countOneBits() == 1) { "memory slab ${slab.name} has alignment $align, but LLVM only supports powers of two" }
        val type = LlvmType.Array(slab.size.toInt(), LlvmType.I8)
        return LlvmGlobal(slab.name, type, LlvmValue.ZeroInitializer(type), isConstant = false, linkage = LlvmLinkage.INTERNAL,
            align = align.takeIf { it > 1 })
    }

    private fun memoryMappedGlobal(cluster: MemoryMappedCluster): LlvmGlobal {
        val type = LlvmType.Array(cluster.size, LlvmType.I8)
        return LlvmGlobal(cluster.name, type, LlvmValue.ZeroInitializer(type), isConstant = false, linkage = LlvmLinkage.INTERNAL,
            align = cluster.align)
    }

    private fun constantGlobal(constant: IRStConstant): LlvmGlobal {
        val number = constant.value
        val slab = constant.memorySlabName
        val value = when {
            number != null -> scalarConstant(constant.dt.base, number, constant.quadBits)
            // an address, so a ptr whatever its Prog8 type; the IR code uses the slab symbol itself, never this global
            slab != null -> memorySlabAddress(slab, constant.name)
            else -> throw IllegalArgumentException("constant ${constant.name} has neither a value nor a memory slab")
        }
        return LlvmGlobal(constant.name, value.type, value, isConstant = true, linkage = LlvmLinkage.INTERNAL)
    }

    private fun memorySlabAddress(slab: String, user: String): LlvmValue {
        val symbol = if (st.lookup(slab) is IRStMemorySlab) slab else "$StMemorySlabBlockName.$slab"
        return requireNotNull(addresses[symbol]) { "memory slab $symbol of $user is not in the symbol table" }
    }

    private fun slotValue(base: BaseDataType, ref: IRStSymbolicReference, user: String): LlvmValue = when (ref) {
        is IRStSymbolicReference.Numeric -> scalarConstant(base, ref.value, ref.quadBits)
        is IRStSymbolicReference.BoolValue -> LlvmValue.IntConstant(if (ref.value) 1L else 0L, mapper.map(base))
        // names missing from the symbol table are subroutines and labels, whose functions are emitted by a later step
        is IRStSymbolicReference.Symbol -> addressAs(mapper.map(base), addresses[ref.name] ?: subroutineAddress(ref.name), user)
    }

    private fun subroutineAddress(name: String): LlvmValue =
        if (name in subroutinesWithIndirectEntry) LlvmValue.Global(indirectEntryName(name)).also { indirectEntries += name }
        else LlvmValue.Global(name)

    /** The Double [value] of a quad is only approximate, so its exact [quadBits] are used when it has those. */
    private fun scalarConstant(base: BaseDataType, value: Double, quadBits: Long?): LlvmValue = when (base) {
        BaseDataType.FLOAT -> LlvmValue.FloatConstant(value)
        BaseDataType.POINTER ->
            if (value == 0.0) LlvmValue.NullPtr()
            else memoryMappedClusters.addressOf(value.toLong())
                ?: LlvmValue.ConstantCast(CastOperator.INTTOPTR, LlvmValue.IntConstant(value.toLong(), mapper.intPtr), LlvmType.Ptr)
        BaseDataType.QUAD -> LlvmValue.IntConstant(quadBits ?: value.toLong(), LlvmType.I64)
        BaseDataType.UQUAD -> LlvmValue.IntConstant(quadBits ?: value.toULong().toLong(), LlvmType.I64)
        else -> LlvmValue.IntConstant(value.toLong(), mapper.map(base))
    }

    private fun addressAs(type: LlvmType, address: LlvmValue, user: String): LlvmValue = when {
        type == LlvmType.Ptr -> address
        // truncates the address when the slot is narrower; static data can't hold that, so withStartupAddresses stores it at startup
        type.isInteger -> LlvmValue.ConstantCast(CastOperator.PTRTOINT, address, type)
        else -> throw IllegalArgumentException("$user can't hold an address in a $type slot")
    }
}

/**
 * Where the data symbols of an IR program live in the LLVM module: [addresses] maps IR symbol names to constant pointers.
 * [indirectEntries] are the subroutines whose indirect call entry the data references.
 */
internal class LlvmDataSymbols(
    val addresses: Map<String, LlvmValue>,
    private val memoryMapped: List<MemoryMappedCluster>,
    val pointerArrays: Map<String, PointerArray>,
    val indirectEntries: Set<String> = emptySet(),
    /** The variables that live in a stack slot of their subroutine, by subroutine label. */
    val stackLocals: Map<String, List<StackLocal>> = emptyMap()
) {
    /** The backing storage of absolute [address] when a memory-mapped variable covers it (the IR accesses those by address), else null. */
    fun memoryMappedAddress(address: Long): LlvmValue? = memoryMapped.addressOf(address)
}

/** A variable of [subroutine] that lives in a stack slot of type [type], which gets [initialValue] on every entry. */
internal data class StackLocal(val name: String, val subroutine: String, val type: LlvmType, val initialValue: LlvmValue)

/** The backing storage of absolute [address] when one of these memory-mapped clusters covers it, else null. */
internal fun List<MemoryMappedCluster>.addressOf(address: Long): LlvmValue? =
    firstOrNull { address >= it.start && address < it.end }?.addressAt(address)

/**
 * A global array of native `ptr` elements that the IR addresses as [irElementSize]-byte elements, such as those of the address integer type for str[].
 * [indexType] is the pointer-sized integer type of the target.
 */
internal data class PointerArray(val name: String, val address: LlvmValue, val length: Int, val irElementSize: Int, val indexType: LlvmType = LlvmType.I64)

/** Backing storage shared by memory-mapped variables whose byte ranges overlap or touch, preserving their aliasing and layout. */
internal class MemoryMappedCluster(val start: Long, private val indexType: LlvmType) {
    var end = start
    val members = mutableListOf<IRStMemVar>()
    val name: String get() = ".memmapped." + start.toString(16)
    val size: Int get() = (end - start).toInt()
    // the alignment the start address had, so the members keep their natural alignment
    val align: Int get() = if (start == 0L) 8 else minOf(start.takeLowestOneBit(), 8L).toInt()

    fun addressAt(address: Long): LlvmValue {
        val offset = address - start
        val base = LlvmValue.Global(name)
        return if (offset == 0L) base else LlvmValue.ConstantGep(LlvmType.I8, base, listOf(LlvmValue.IntConstant(offset, indexType)))
    }
}
