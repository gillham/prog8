package prog8.codegen.llvm.types

import prog8.code.core.BaseDataType
import prog8.code.core.DataType
import prog8.code.core.ICompilationTarget
import prog8.code.target.LlvmTarget
import prog8.codegen.llvm.model.LlvmType
import prog8.intermediate.IRDataType
import prog8.intermediate.IRStStructDef
import prog8.intermediate.IRStStructField
import prog8.intermediate.IRSymbolTable

/**
 * Maps Prog8 IR and Prog8 data types onto LLVM types for the llvm targets ([pointerBytes]-byte pointers, doubles, packed Prog8 struct layout).
 * [cLongBytes] is the size of the C `long` of the target triple, [fileOffsetBytes] that of the file offset of its fseek and ftell routines,
 * and [cIntBytes] that of its C `int`.
 */
internal class LlvmTypeMapper(private val st: IRSymbolTable, val pointerBytes: Int = 8, cLongBytes: Int = 8, fileOffsetBytes: Int = cLongBytes, cIntBytes: Int = 4) {

    constructor(st: IRSymbolTable, target: ICompilationTarget) :
        this(st, target.POINTER_MEM_SIZE.toInt(), (target as? LlvmTarget)?.profile?.cLongSize ?: 8, (target as? LlvmTarget)?.profile?.fileOffsetSize ?: 8)

    init {
        require(pointerBytes == 2 || pointerBytes == 4 || pointerBytes == 8) { "unsupported pointer size: $pointerBytes" }
        require(cLongBytes == 4 || cLongBytes == 8) { "unsupported C long size: $cLongBytes" }
        require(cIntBytes == 2 || cIntBytes == 4) { "unsupported C int size: $cIntBytes" }
    }

    /** The integer type of the size of a pointer, for address arithmetic and offsets. */
    val intPtr: LlvmType = when (pointerBytes) {
        8 -> LlvmType.I64
        4 -> LlvmType.I32
        else -> LlvmType.I16
    }

    /** The integer type of an IR LONG register, which can hold an address as well as a 32-bit long. */
    val wideInt: LlvmType = if (pointerBytes == 8) LlvmType.I64 else LlvmType.I32

    /** C's `size_t`. */
    val sizeT: LlvmType get() = intPtr

    /** C's `long`, which is 32 bits on ILP32 and LLP64 targets. */
    val cLong: LlvmType = if (cLongBytes == 8) LlvmType.I64 else LlvmType.I32

    /** The file offset of the target's fseek and ftell routines, which is C's `long` unless the triple profile names others. */
    val fileOffset: LlvmType = if (fileOffsetBytes == 8) LlvmType.I64 else LlvmType.I32

    /** C's `int`, which is 16 bits on some small targets. */
    val cInt: LlvmType = if (cIntBytes == 2) LlvmType.I16 else LlvmType.I32

    /** The type of Prog8 floats. */
    val floatType: LlvmType = LlvmType.Double

    fun map(type: IRDataType): LlvmType = when (type) {
        IRDataType.BYTE -> LlvmType.I8
        IRDataType.WORD -> LlvmType.I16
        IRDataType.LONG -> LlvmType.I32
        IRDataType.FLOAT -> floatType
        IRDataType.POINTER -> LlvmType.Ptr
        IRDataType.QUAD -> LlvmType.I64
    }

    fun map(type: BaseDataType): LlvmType = when (type) {
        BaseDataType.BOOL, BaseDataType.UBYTE, BaseDataType.BYTE -> LlvmType.I8
        BaseDataType.UWORD, BaseDataType.WORD -> LlvmType.I16
        BaseDataType.LONG -> LlvmType.I32
        BaseDataType.QUAD, BaseDataType.UQUAD -> LlvmType.I64
        BaseDataType.FLOAT -> floatType
        BaseDataType.POINTER -> LlvmType.Ptr
        BaseDataType.STR, BaseDataType.ARRAY, BaseDataType.ARRAY_SPLITW, BaseDataType.STRUCT_INSTANCE, BaseDataType.UNDEFINED ->
            throw IllegalArgumentException("$type is not a scalar type")
    }

    fun storageType(dt: DataType, length: UInt?): LlvmType {
        require(dt.base != BaseDataType.ARRAY_SPLITW) { "split word arrays don't exist on the llvm targets" }
        return when {
            dt.isString -> LlvmType.Array(requireLength(dt, length), LlvmType.I8)
            dt.isArray -> LlvmType.Array(requireLength(dt, length), arrayElementType(dt))
            dt.isStructInstance -> throw IllegalArgumentException("struct instances are separate symbols, there is no variable storage of type $dt")
            else -> map(dt.base)
        }
    }

    /** Unions are byte arrays; structs are packed, with array fields flattened the same way as IRStStructInstance.values. */
    fun structType(structName: String): LlvmType {
        val def = requireNotNull(st.lookup(structName) as? IRStStructDef) { "struct type $structName is not in the symbol table" }
        val type = if (def.isUnion) LlvmType.Array(def.size.toInt(), LlvmType.I8)
            else LlvmType.Struct(def.fields.flatMap { fieldSlots(it, structName) }, packed = true)
        check(byteSize(type) == def.size.toInt()) { "the LLVM layout of struct $structName doesn't match its Prog8 size ${def.size}" }
        return type
    }

    /** The in-memory size without alignment padding, as in Prog8's own memory layout. */
    fun byteSize(type: LlvmType): Int = when (type) {
        LlvmType.I1, LlvmType.I8 -> 1
        LlvmType.I16 -> 2
        LlvmType.I32, LlvmType.Float -> 4
        LlvmType.I64, LlvmType.Double -> 8
        LlvmType.Ptr -> pointerBytes
        is LlvmType.Array -> type.size * byteSize(type.elementType)
        is LlvmType.Struct -> {
            require(type.packed) { "the size of unpacked struct $type depends on the target's alignment rules" }
            type.fields.sumOf { byteSize(it) }
        }
        LlvmType.Void, is LlvmType.Function -> throw IllegalArgumentException("$type has no in-memory size")
    }

    private fun arrayElementType(dt: DataType): LlvmType = when {
        dt.isPointerArray -> LlvmType.Ptr
        dt.sub == BaseDataType.STRUCT_INSTANCE -> structType(requireNotNull(dt.subType) { "struct array type $dt has no resolved struct type" }.scopedNameString)
        else -> map(checkNotNull(dt.sub) { "array type $dt has no element type" })
    }

    private fun fieldSlots(field: IRStStructField, structName: String): List<LlvmType> {
        val arraySize = field.arraySize ?: return listOf(map(field.type.base))
        val element = map(requireNotNull(field.type.sub) { "array field ${field.name} of struct $structName has no element type" })
        return List(arraySize) { element }
    }

    private fun requireLength(dt: DataType, length: UInt?): Int = requireNotNull(length) { "storage of type $dt needs a length" }.toInt()
}

/** The suffix of LLVM's overloaded float intrinsics for [type], such as the `f64` of `llvm.sin.f64`. */
internal fun floatSuffix(type: LlvmType): String = when (type) {
    LlvmType.Float -> "f32"
    LlvmType.Double -> "f64"
    else -> throw IllegalArgumentException("$type is no floating point type")
}

/** The LLVM intrinsic [name] for [type], such as `llvm.sin.f32` for `sin`. */
internal fun floatIntrinsic(name: String, type: LlvmType): String = "llvm.$name.${floatSuffix(type)}"

/** The C math library routine [name] for [type], such as `sinf` for `sin` on floats. */
internal fun libmName(name: String, type: LlvmType): String = if (floatSuffix(type) == "f32") "${name}f" else name
