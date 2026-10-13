package prog8.codegen.llvm.lower

import prog8.code.INTERNED_STRINGS_MODULENAME
import prog8.code.StMemorySlabBlockName
import prog8.code.core.BaseDataType
import prog8.codegen.llvm.model.CastOperator
import prog8.codegen.llvm.model.LlvmGlobal
import prog8.codegen.llvm.model.LlvmLinkage
import prog8.codegen.llvm.model.LlvmModule
import prog8.codegen.llvm.model.LlvmType
import prog8.codegen.llvm.model.LlvmValue
import prog8.codegen.llvm.types.LlvmTypeMapper
import prog8.intermediate.IRProgram
import prog8.intermediate.IRStConstant
import prog8.intermediate.IRStMemVar
import prog8.intermediate.IRStMemorySlab
import prog8.intermediate.IRStStaticVariable
import prog8.intermediate.IRStStructDef
import prog8.intermediate.IRStStructInstance
import prog8.intermediate.IRStSymbolicReference
import prog8.intermediate.IRVariableInitializer

/**
 * Emits the module-level globals for the data symbols of an IRProgram, and maps each IR symbol name onto
 * the LLVM constant pointer that addresses its storage (used later to lower memory operands).
 */
internal class LlvmGlobalEmitter(private val program: IRProgram) {
    private val st = program.st
    private val target = program.options.compTarget
    private val mapper = LlvmTypeMapper(st)

    private val internedStringNames: Map<String, String> by lazy {
        st.allVariables().filter { it.name.startsWith("$INTERNED_STRINGS_MODULENAME.") }
            .mapIndexed { index, variable -> variable.name to ".str.$index" }.toMap()
    }

    private val memoryMappedClusters: List<MemoryMappedCluster> by lazy {
        val clusters = mutableListOf<MemoryMappedCluster>()
        for (memvar in st.allMemMappedVariables().sortedBy { it.address }) {
            val start = memvar.address.toLong()
            val cluster = clusters.lastOrNull()?.takeIf { start <= it.end } ?: MemoryMappedCluster(start).also { clusters += it }
            cluster.members += memvar
            cluster.end = maxOf(cluster.end, start + target.memorySize(memvar.dt, memvar.length?.toInt()))
        }
        clusters
    }

    // built in one go: initializers may reference symbols whose globals are emitted later
    private val addresses: Map<String, LlvmValue> by lazy {
        buildMap {
            st.allVariables().forEach { put(it.name, LlvmValue.Global(internedStringNames[it.name] ?: it.name)) }
            st.allStructInstances().forEach { put(it.name, LlvmValue.Global(it.name)) }
            st.allMemorySlabs().forEach { put(it.name, LlvmValue.Global(it.name)) }
            memoryMappedClusters.forEach { cluster -> cluster.members.forEach { put(it.name, cluster.addressAt(it.address.toLong())) } }
            st.allConstants().forEach { put(it.name, LlvmValue.Global(it.name)) }
        }
    }

    fun emit(module: LlvmModule): LlvmDataSymbols {
        require(target.POINTER_MEM_SIZE == 8u && target.FLOAT_MEM_SIZE == 8u) { "the LLVM backend needs the llvm64 target's 8-byte pointers and floats" }
        module.globals += buildList {
            st.allVariables().mapTo(this) { variableGlobal(it) }
            st.allStructInstances().mapTo(this) { structInstanceGlobal(it) }
            st.allMemorySlabs().mapTo(this) { memorySlabGlobal(it) }
            memoryMappedClusters.mapTo(this) { memoryMappedGlobal(it) }
            st.allConstants().mapTo(this) { constantGlobal(it) }
        }
        return LlvmDataSymbols(addresses, memoryMappedClusters)
    }

    private fun variableGlobal(variable: IRStStaticVariable): LlvmGlobal {
        val type = mapper.storageType(variable.dt, variable.length)
        check(mapper.byteSize(type) == target.memorySize(variable.dt, variable.length?.toInt())) {
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
            init is IRVariableInitializer.Numeric && type !is LlvmType.Array -> scalarConstant(variable.dt.base, init.value)
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
            val elementBase = variable.dt.elementType().base
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
        val type = mapper.map(constant.dt.base)
        val number = constant.value
        val slab = constant.memorySlabName
        val value = when {
            number != null -> scalarConstant(constant.dt.base, number)
            slab != null -> addressAs(type, memorySlabAddress(slab, constant.name), constant.name)
            else -> throw IllegalArgumentException("constant ${constant.name} has neither a value nor a memory slab")
        }
        return LlvmGlobal(constant.name, type, value, isConstant = true, linkage = LlvmLinkage.INTERNAL)
    }

    private fun memorySlabAddress(slab: String, user: String): LlvmValue {
        val symbol = if (st.lookup(slab) is IRStMemorySlab) slab else "$StMemorySlabBlockName.$slab"
        return requireNotNull(addresses[symbol]) { "memory slab $symbol of $user is not in the symbol table" }
    }

    private fun slotValue(base: BaseDataType, ref: IRStSymbolicReference, user: String): LlvmValue = when (ref) {
        is IRStSymbolicReference.Numeric -> scalarConstant(base, ref.value)
        is IRStSymbolicReference.BoolValue -> LlvmValue.IntConstant(if (ref.value) 1L else 0L, mapper.map(base))
        // names missing from the symbol table are subroutines and labels, whose functions are emitted by a later step
        is IRStSymbolicReference.Symbol -> addressAs(mapper.map(base), addresses[ref.name] ?: LlvmValue.Global(ref.name), user)
    }

    private fun scalarConstant(base: BaseDataType, value: Double): LlvmValue = when (base) {
        BaseDataType.FLOAT -> LlvmValue.FloatConstant(value)
        BaseDataType.POINTER ->
            if (value == 0.0) LlvmValue.NullPtr()
            else LlvmValue.ConstantCast(CastOperator.INTTOPTR, LlvmValue.IntConstant(value.toLong(), LlvmType.I64), LlvmType.Ptr)
        else -> LlvmValue.IntConstant(value.toLong(), mapper.map(base))
    }

    private fun addressAs(type: LlvmType, address: LlvmValue, user: String): LlvmValue = when {
        type == LlvmType.Ptr -> address
        // deliberately truncates 64-bit addresses: the frontend stores some of them (e.g. str[] elements) in 32-bit long slots
        type.isInteger -> LlvmValue.ConstantCast(CastOperator.PTRTOINT, address, type)
        else -> throw IllegalArgumentException("$user can't hold an address in a $type slot")
    }
}

/** Where the data symbols of an IR program live in the LLVM module: [addresses] maps IR symbol names to constant pointers. */
internal class LlvmDataSymbols(val addresses: Map<String, LlvmValue>, private val memoryMapped: List<MemoryMappedCluster>) {
    /** The backing storage of absolute [address] when a memory-mapped variable covers it (the IR accesses those by address), else null. */
    fun memoryMappedAddress(address: Long): LlvmValue? =
        memoryMapped.firstOrNull { address >= it.start && address < it.end }?.addressAt(address)
}

/** Backing storage shared by memory-mapped variables whose byte ranges overlap or touch, preserving their aliasing and layout. */
internal class MemoryMappedCluster(val start: Long) {
    var end = start
    val members = mutableListOf<IRStMemVar>()
    val name: String get() = ".memmapped." + start.toString(16)
    val size: Int get() = (end - start).toInt()
    // the alignment the start address had, so the members keep their natural alignment
    val align: Int get() = if (start == 0L) 8 else minOf(start.takeLowestOneBit(), 8L).toInt()

    fun addressAt(address: Long): LlvmValue {
        val offset = address - start
        val base = LlvmValue.Global(name)
        return if (offset == 0L) base else LlvmValue.ConstantGep(LlvmType.I8, base, listOf(LlvmValue.IntConstant(offset, LlvmType.I64)))
    }
}
