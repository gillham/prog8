package prog8.codegen.llvm.model

import prog8.codegen.llvm.model.LlvmValue.Local

internal sealed interface LlvmType {
    data object I1 : LlvmType
    data object I8 : LlvmType
    data object I16 : LlvmType
    data object I32 : LlvmType
    data object I64 : LlvmType
    data object Double : LlvmType
    data object Ptr : LlvmType
    data object Void : LlvmType

    data class Array(val size: Int, val elementType: LlvmType) : LlvmType {
        init {
            require(size >= 0) { "array size must not be negative: $size" }
            require(elementType.isFirstClass) { "array element type must be first-class, not $elementType" }
        }
    }

    /** A [isVarArg] function, such as C's snprintf, takes more arguments after [paramTypes]. */
    data class Function(val returnType: LlvmType, val paramTypes: List<LlvmType>, val isVarArg: Boolean = false) : LlvmType {
        init {
            require(paramTypes.all { it.isFirstClass }) { "function parameter types must be first-class: $paramTypes" }
        }
    }

    /** A literal struct type; [packed] structs have no alignment padding between their fields. */
    data class Struct(val fields: List<LlvmType>, val packed: Boolean) : LlvmType {
        init {
            require(fields.all { it.isFirstClass }) { "struct field types must be first-class: $fields" }
        }
    }

    val bitWidth: Int?
        get() = when (this) {
            I1 -> 1
            I8 -> 8
            I16 -> 16
            I32 -> 32
            I64 -> 64
            Double, Ptr, Void, is Array, is Function, is Struct -> null
        }

    val isInteger: Boolean get() = bitWidth != null

    val isFirstClass: Boolean get() = this != Void && this !is Function
}

internal sealed interface LlvmValue {
    val type: LlvmType

    data class Local(val name: String, override val type: LlvmType) : LlvmValue {
        init {
            require(name.isNotEmpty()) { "local name must not be empty" }
            require(type.isFirstClass) { "local %$name must have a first-class type, not $type" }
        }
    }

    data class Global(val name: String, override val type: LlvmType = LlvmType.Ptr) : LlvmValue {
        init {
            require(name.isNotEmpty()) { "global name must not be empty" }
            require(type == LlvmType.Ptr) { "global @$name must have type ptr, not $type" }
        }
    }

    /** [value] may be given in signed or unsigned form; both denote the same bit pattern of the type's width. */
    data class IntConstant(val value: Long, override val type: LlvmType) : LlvmValue {
        init {
            val bits = type.bitWidth
            require(bits != null) { "integer constant needs an integer type, not $type" }
            require(bits == 64 || value in -(1L shl (bits - 1))..<(1L shl bits)) { "$value does not fit in $type" }
        }
    }

    data class FloatConstant(val value: kotlin.Double, override val type: LlvmType = LlvmType.Double) : LlvmValue {
        init {
            require(type == LlvmType.Double) { "float constant must have type double, not $type" }
        }
    }

    /** An array value of type `[N x i8]`, not a pointer to it; no NUL terminator is appended. */
    data class StringConstant(val bytes: List<UByte>) : LlvmValue {
        constructor(text: String) : this(text.map { char ->
            require(char.code <= 0xFF) { "character '$char' does not fit in a byte" }
            char.code.toUByte()
        })

        override val type: LlvmType.Array get() = LlvmType.Array(bytes.size, LlvmType.I8)
    }

    data class NullPtr(override val type: LlvmType = LlvmType.Ptr) : LlvmValue {
        init {
            require(type == LlvmType.Ptr) { "null must have type ptr, not $type" }
        }
    }

    data class ZeroInitializer(override val type: LlvmType) : LlvmValue {
        init {
            require(type.isFirstClass) { "zeroinitializer needs a first-class type, not $type" }
        }
    }

    data class ArrayConstant(val elementType: LlvmType, val elements: List<LlvmValue>) : LlvmValue {
        init {
            require(elementType.isFirstClass) { "array element type must be first-class, not $elementType" }
            require(elements.all { it.type == elementType }) { "all array elements must have type $elementType" }
        }

        override val type: LlvmType.Array get() = LlvmType.Array(elements.size, elementType)
    }

    data class StructConstant(override val type: LlvmType.Struct, val fields: List<LlvmValue>) : LlvmValue {
        init {
            require(fields.map { it.type } == type.fields) { "struct field values ${fields.map { it.type }} do not match $type" }
        }
    }

    /** A constant cast expression such as `ptrtoint (ptr @x to i32)`; LLVM still accepts only these casts as constants. */
    data class ConstantCast(val op: CastOperator, val value: LlvmValue, override val type: LlvmType) : LlvmValue {
        init {
            require(op in setOf(CastOperator.TRUNC, CastOperator.PTRTOINT, CastOperator.INTTOPTR, CastOperator.BITCAST)) { "${op.keyword} is not a constant expression" }
            require(value !is Local) { "constant ${op.keyword} needs a constant operand, not $value" }
            require(isValidCast(op, value.type, type)) { "invalid ${op.keyword} from ${value.type} to $type" }
        }
    }

    /** A constant address expression such as `getelementptr inbounds (i8, ptr @g, i64 2)`. */
    data class ConstantGep(val baseType: LlvmType, val ptr: LlvmValue, val indices: List<LlvmValue>, val inBounds: Boolean = true) : LlvmValue {
        override val type: LlvmType get() = LlvmType.Ptr

        init {
            require(ptr.type == LlvmType.Ptr && ptr !is Local) { "constant getelementptr needs a constant pointer, not $ptr" }
            require(indices.all { it is IntConstant }) { "constant getelementptr indices must be integer constants: $indices" }
        }
    }
}

internal sealed interface LlvmInstruction {
    val isTerminator: Boolean get() = false

    data class Alloca(val result: Local, val allocatedType: LlvmType) : LlvmInstruction {
        init {
            require(result.type == LlvmType.Ptr) { "alloca result must have type ptr, not ${result.type}" }
            require(allocatedType.isFirstClass) { "alloca needs a first-class type, not $allocatedType" }
        }
    }

    data class Load(val result: Local, val ptr: LlvmValue, val align: Int? = null, val volatile: Boolean = false) : LlvmInstruction {
        init {
            require(ptr.type == LlvmType.Ptr) { "load address must have type ptr, not ${ptr.type}" }
            requireValidAlignment(align)
        }
    }

    data class Store(val value: LlvmValue, val ptr: LlvmValue, val align: Int? = null, val volatile: Boolean = false) : LlvmInstruction {
        init {
            require(ptr.type == LlvmType.Ptr) { "store address must have type ptr, not ${ptr.type}" }
            requireValidAlignment(align)
        }
    }

    data class BinaryOp(val op: BinaryOperator, val result: Local, val lhs: LlvmValue, val rhs: LlvmValue) : LlvmInstruction {
        init {
            require(lhs.type == rhs.type && result.type == lhs.type) { "${op.keyword} operand and result types differ: ${lhs.type}, ${rhs.type}, ${result.type}" }
            require(if (op.isFloat) lhs.type == LlvmType.Double else lhs.type.isInteger) { "${op.keyword} does not accept type ${lhs.type}" }
        }
    }

    data class FNeg(val result: Local, val value: LlvmValue) : LlvmInstruction {
        init {
            require(value.type == LlvmType.Double && result.type == LlvmType.Double) { "fneg needs type double, not ${value.type} -> ${result.type}" }
        }
    }

    data class Icmp(val cond: IcmpCondition, val result: Local, val lhs: LlvmValue, val rhs: LlvmValue) : LlvmInstruction {
        init {
            require(lhs.type == rhs.type) { "icmp operand types differ: ${lhs.type}, ${rhs.type}" }
            require(lhs.type.isInteger || lhs.type == LlvmType.Ptr) { "icmp does not accept type ${lhs.type}" }
            require(result.type == LlvmType.I1) { "icmp result must have type i1, not ${result.type}" }
        }
    }

    data class Fcmp(val cond: FcmpCondition, val result: Local, val lhs: LlvmValue, val rhs: LlvmValue) : LlvmInstruction {
        init {
            require(lhs.type == LlvmType.Double && rhs.type == LlvmType.Double) { "fcmp operands must have type double: ${lhs.type}, ${rhs.type}" }
            require(result.type == LlvmType.I1) { "fcmp result must have type i1, not ${result.type}" }
        }
    }

    data class Cast(val op: CastOperator, val result: Local, val value: LlvmValue, val targetType: LlvmType) : LlvmInstruction {
        init {
            require(result.type == targetType) { "${op.keyword} result type ${result.type} differs from target type $targetType" }
            require(isValidCast(op, value.type, targetType)) { "invalid ${op.keyword} from ${value.type} to $targetType" }
        }
    }

    data class GetElementPtr(
        val result: Local,
        val baseType: LlvmType,
        val ptr: LlvmValue,
        val indices: List<LlvmValue>,
        val inBounds: Boolean = true
    ) : LlvmInstruction {
        init {
            require(result.type == LlvmType.Ptr) { "getelementptr result must have type ptr, not ${result.type}" }
            require(ptr.type == LlvmType.Ptr) { "getelementptr address must have type ptr, not ${ptr.type}" }
            require(indices.all { it.type.isInteger }) { "getelementptr indices must be integers: ${indices.map { it.type }}" }
        }
    }

    /** A call of a variadic function needs its [calleeType], which LLVM can't derive from the arguments. */
    data class Call(val result: Local?, val callee: LlvmValue, val args: List<LlvmValue>, val returnType: LlvmType,
                    val calleeType: LlvmType.Function? = null) : LlvmInstruction {
        constructor(result: Local?, callee: String, args: List<LlvmValue>, returnType: LlvmType) :
                this(result, LlvmValue.Global(callee), args, returnType)

        init {
            require(calleeType == null || calleeType.returnType == returnType) { "callee type $calleeType doesn't return $returnType" }
            require(calleeType == null || args.size >= calleeType.paramTypes.size) { "call of a $calleeType function needs at least ${calleeType?.paramTypes?.size} arguments" }
            require(callee.type == LlvmType.Ptr) { "callee must have type ptr, not ${callee.type}" }
            require(returnType != LlvmType.Void || result == null) { "void call cannot have a result" }
            require(result == null || result.type == returnType) { "call result type ${result?.type} differs from return type $returnType" }
        }
    }

    data class Select(val result: Local, val condition: LlvmValue, val trueValue: LlvmValue, val falseValue: LlvmValue) : LlvmInstruction {
        init {
            require(condition.type == LlvmType.I1) { "select condition must have type i1, not ${condition.type}" }
            require(trueValue.type == result.type && falseValue.type == result.type) { "select values must have result type ${result.type}" }
        }
    }

    /** Each [incoming] entry pairs a value with the label of the predecessor block it comes from. */
    data class Phi(val result: Local, val incoming: List<Pair<LlvmValue, String>>) : LlvmInstruction {
        init {
            require(incoming.isNotEmpty()) { "phi needs at least one incoming value" }
            require(incoming.all { it.first.type == result.type }) { "phi incoming values must have result type ${result.type}" }
        }
    }

    data class Br(val targetBlock: String) : LlvmInstruction {
        override val isTerminator: Boolean get() = true
    }

    data class CondBr(val condition: LlvmValue, val trueBlock: String, val falseBlock: String) : LlvmInstruction {
        override val isTerminator: Boolean get() = true

        init {
            require(condition.type == LlvmType.I1) { "branch condition must have type i1, not ${condition.type}" }
        }
    }

    data class Ret(val value: LlvmValue?) : LlvmInstruction {
        override val isTerminator: Boolean get() = true
    }

    data object Unreachable : LlvmInstruction {
        override val isTerminator: Boolean get() = true
    }
}

internal enum class BinaryOperator(val isFloat: Boolean = false) {
    ADD, SUB, MUL, SDIV, UDIV, SREM, UREM, AND, OR, XOR, SHL, LSHR, ASHR,
    FADD(isFloat = true), FSUB(isFloat = true), FMUL(isFloat = true), FDIV(isFloat = true), FREM(isFloat = true);

    val keyword: String get() = name.lowercase()
}

internal enum class IcmpCondition {
    EQ, NE, UGT, UGE, ULT, ULE, SGT, SGE, SLT, SLE;

    val keyword: String get() = name.lowercase()
}

internal enum class FcmpCondition {
    FALSE, OEQ, OGT, OGE, OLT, OLE, ONE, ORD, UEQ, UGT, UGE, ULT, ULE, UNE, UNO, TRUE;

    val keyword: String get() = name.lowercase()
}

internal enum class CastOperator {
    TRUNC, ZEXT, SEXT, FPTRUNC, FPEXT, FPTOUI, FPTOSI, UITOFP, SITOFP, PTRTOINT, INTTOPTR, BITCAST;

    val keyword: String get() = name.lowercase()
}

internal enum class LlvmLinkage {
    EXTERNAL, PRIVATE, INTERNAL, APPENDING;

    val keyword: String get() = name.lowercase()
}

internal data class LlvmBlock(val label: String, val instructions: MutableList<LlvmInstruction> = mutableListOf()) {
    val terminator: LlvmInstruction? get() = instructions.lastOrNull()?.takeIf { it.isTerminator }
}

/** An [isExternal] function is a body-less `declare`; a definition needs at least one block. */
internal data class LlvmFunction(
    val name: String,
    val returnType: LlvmType,
    val parameters: List<Local>,
    val blocks: MutableList<LlvmBlock> = mutableListOf(),
    val isExternal: Boolean = false,
    val linkage: LlvmLinkage = LlvmLinkage.EXTERNAL,
    val isVarArg: Boolean = false
) {
    init {
        require(!isVarArg || isExternal) { "only declarations can be variadic: @$name" }
        require(!isExternal || linkage == LlvmLinkage.EXTERNAL) { "declaration of @$name must have external linkage, not $linkage" }
        require(linkage != LlvmLinkage.APPENDING) { "function @$name can't have appending linkage" }
    }

    val type: LlvmType.Function get() = LlvmType.Function(returnType, parameters.map { it.type }, isVarArg)
}

/** A null [initializer] makes this an `external` declaration of a global defined elsewhere. */
internal data class LlvmGlobal(
    val name: String,
    val type: LlvmType,
    val initializer: LlvmValue?,
    val isConstant: Boolean,
    val linkage: LlvmLinkage = LlvmLinkage.EXTERNAL,
    val unnamedAddr: Boolean = false,
    val align: Int? = null
) {
    init {
        require(type.isFirstClass) { "global @$name needs a first-class type, not $type" }
        require(initializer != null || linkage == LlvmLinkage.EXTERNAL) { "declaration of @$name must have external linkage, not $linkage" }
        require(initializer == null || initializer.type == type) { "initializer type ${initializer?.type} of @$name differs from $type" }
        require(linkage != LlvmLinkage.APPENDING || type is LlvmType.Array) { "appending global @$name must be an array, not $type" }
        requireValidAlignment(align)
    }
}

internal data class LlvmModule(
    val name: String,
    var targetTriple: String? = null,
    var dataLayout: String? = null,
    val globals: MutableList<LlvmGlobal> = mutableListOf(),
    val functions: MutableList<LlvmFunction> = mutableListOf(),
    val headerComments: MutableList<String> = mutableListOf()
)

private fun requireValidAlignment(align: Int?) =
    require(align == null || align > 0 && align.countOneBits() == 1) { "alignment must be a power of two: $align" }

private fun isValidCast(op: CastOperator, from: LlvmType, to: LlvmType): Boolean {
    val fromBits = from.bitWidth
    val toBits = to.bitWidth
    return when (op) {
        CastOperator.TRUNC -> fromBits != null && toBits != null && toBits < fromBits
        CastOperator.ZEXT, CastOperator.SEXT -> fromBits != null && toBits != null && toBits > fromBits
        CastOperator.UITOFP, CastOperator.SITOFP -> fromBits != null && to == LlvmType.Double
        CastOperator.FPTOUI, CastOperator.FPTOSI -> from == LlvmType.Double && toBits != null
        CastOperator.PTRTOINT -> from == LlvmType.Ptr && toBits != null
        CastOperator.INTTOPTR -> fromBits != null && to == LlvmType.Ptr
        CastOperator.FPTRUNC, CastOperator.FPEXT, CastOperator.BITCAST -> true
    }
}
