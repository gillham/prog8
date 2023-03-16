package prog8.code.ast

import prog8.code.core.*
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round


sealed class PtExpression(val type: DataType, position: Position) : PtNode(position) {

    init {
        if(type==DataType.BOOL)
            throw IllegalArgumentException("bool should have become ubyte @$position")
        if(type==DataType.UNDEFINED) {
            @Suppress("LeakingThis")
            when(this) {
                is PtBuiltinFunctionCall -> { /* void function call */ }
                is PtFunctionCall -> { /* void function call */ }
                is PtIdentifier -> { /* non-variable identifier */ }
                else -> throw IllegalArgumentException("type should be known @$position")
            }
        }
    }

    infix fun isSameAs(other: PtExpression): Boolean {
        return when(this) {
            is PtAddressOf -> other is PtAddressOf && other.type==type && other.identifier isSameAs identifier
            is PtArrayIndexer -> other is PtArrayIndexer && other.type==type && other.variable isSameAs variable && other.index isSameAs index
            is PtBinaryExpression -> other is PtBinaryExpression && other.left isSameAs left && other.right isSameAs right
            is PtRpn -> other is PtRpn && this.isSame(other)
            is PtContainmentCheck -> other is PtContainmentCheck && other.type==type && other.element isSameAs element && other.iterable isSameAs iterable
            is PtIdentifier -> other is PtIdentifier && other.type==type && other.name==name
            is PtMachineRegister -> other is PtMachineRegister && other.type==type && other.register==register
            is PtMemoryByte -> other is PtMemoryByte && other.address isSameAs address
            is PtNumber -> other is PtNumber && other.type==type && other.number==number
            is PtPrefix -> other is PtPrefix && other.type==type && other.operator==operator && other.value isSameAs value
            is PtRange -> other is PtRange && other.type==type && other.from==from && other.to==to && other.step==step
            is PtTypeCast -> other is PtTypeCast && other.type==type && other.value isSameAs value
            else -> false
        }
    }

    infix fun isSameAs(target: PtAssignTarget): Boolean {
        return when {
            target.memory != null && this is PtMemoryByte-> {
                target.memory!!.address isSameAs this.address
            }
            target.identifier != null && this is PtIdentifier -> {
                this.name == target.identifier!!.name
            }
            target.array != null && this is PtArrayIndexer -> {
                this.variable.name == target.array!!.variable.name && this.index isSameAs target.array!!.index
            }
            else -> false
        }
    }

    fun asConstInteger(): Int? = (this as? PtNumber)?.number?.toInt()

    fun isSimple(): Boolean {
        return when(this) {
            is PtAddressOf -> true
            is PtArray -> true
            is PtArrayIndexer -> index is PtNumber || index is PtIdentifier
            is PtBinaryExpression -> false
            is PtRpn -> false
            is PtBuiltinFunctionCall -> name in arrayOf("msb", "lsb", "peek", "peekw", "mkword", "set_carry", "set_irqd", "clear_carry", "clear_irqd")
            is PtContainmentCheck -> false
            is PtFunctionCall -> false
            is PtIdentifier -> true
            is PtMachineRegister -> true
            is PtMemoryByte -> address is PtNumber || address is PtIdentifier
            is PtNumber -> true
            is PtPrefix -> value.isSimple()
            is PtRange -> true
            is PtString -> true
            is PtTypeCast -> value.isSimple()
        }
    }

    /*
    fun clone(): PtExpression {
        fun withClonedChildrenFrom(orig: PtExpression, clone: PtExpression): PtExpression {
            orig.children.forEach { clone.add((it as PtExpression).clone()) }
            return clone
        }
        when(this) {
            is PtAddressOf -> return withClonedChildrenFrom(this, PtAddressOf(position))
            is PtArray -> return withClonedChildrenFrom(this, PtArray(type, position))
            is PtArrayIndexer -> return withClonedChildrenFrom(this, PtArrayIndexer(type, position))
            is PtBinaryExpression -> return withClonedChildrenFrom(this, PtBinaryExpression(operator, type, position))
            is PtBuiltinFunctionCall -> return withClonedChildrenFrom(this, PtBuiltinFunctionCall(name, void, hasNoSideEffects, type, position))
            is PtContainmentCheck -> return withClonedChildrenFrom(this, PtContainmentCheck(position))
            is PtFunctionCall -> return withClonedChildrenFrom(this, PtFunctionCall(name, void, type, position))
            is PtIdentifier -> return withClonedChildrenFrom(this, PtIdentifier(name, type, position))
            is PtMachineRegister -> return withClonedChildrenFrom(this, PtMachineRegister(register, type, position))
            is PtMemoryByte -> return withClonedChildrenFrom(this, PtMemoryByte(position))
            is PtNumber -> return withClonedChildrenFrom(this, PtNumber(type, number, position))
            is PtPrefix -> return withClonedChildrenFrom(this, PtPrefix(operator, type, position))
            is PtRange -> return withClonedChildrenFrom(this, PtRange(type, position))
            is PtString -> return withClonedChildrenFrom(this, PtString(value, encoding, position))
            is PtTypeCast -> return withClonedChildrenFrom(this, PtTypeCast(type, position))
        }
    }
    */
}

class PtAddressOf(position: Position) : PtExpression(DataType.UWORD, position) {
    val identifier: PtIdentifier
        get() = children.single() as PtIdentifier
}


class PtArrayIndexer(elementType: DataType, position: Position): PtExpression(elementType, position) {
    val variable: PtIdentifier
        get() = children[0] as PtIdentifier
    val index: PtExpression
        get() = children[1] as PtExpression

    init {
        require(elementType in NumericDatatypes)
    }
}


class PtArray(type: DataType, position: Position): PtExpression(type, position) {
    override fun hashCode(): Int = Objects.hash(children, type)
    override fun equals(other: Any?): Boolean {
        if(other==null || other !is PtArray)
            return false
        return type==other.type && children == other.children
    }

    val size: Int
        get() = children.size
}


class PtBuiltinFunctionCall(val name: String,
                            val void: Boolean,
                            val hasNoSideEffects: Boolean,
                            type: DataType,
                            position: Position) : PtExpression(type, position) {
    init {
        if(!void)
            require(type!=DataType.UNDEFINED)
    }

    val args: List<PtExpression>
        get() = children.map { it as PtExpression }
}


class PtBinaryExpression(val operator: String, type: DataType, position: Position): PtExpression(type, position) {
    // note: "and", "or", "xor" do not occur anymore as operators. They've been replaced int the ast by their bitwise versions &, |, ^.
    val left: PtExpression
        get() = children[0] as PtExpression
    val right: PtExpression
        get() = children[1] as PtExpression
}


class PtRpn(type: DataType, position: Position): PtExpression(type, position) {
    // contains only PtExpression (not PtRpn!) and PtRpnOperator nodes
    // not created directly by the compiler for now, if code generators prefer this over PtBinaryExpression,
    // they have to transform the ast themselves first using the utility routine on PtProgram for it.

    fun addRpnNode(node: PtNode) {
        require(node is PtRpnOperator || node is PtExpression)
        if(node is PtRpn) {
            node.children.forEach {
                children += it
                it.parent = this
            }
        }
        else {
            children += node
            node.parent = this
        }
    }

    fun print() {
        children.forEach {
            when(it) {
                is PtRpnOperator -> println(it.operator)
                is PtExpression -> println("expr $it  ${it.position}")
                else -> {}
            }
        }
    }

    fun isSame(other: PtRpn): Boolean {
        if(other.children.size==children.size) {
            return other.children.zip(this.children).all { (first, second) ->
                when (first) {
                    is PtRpnOperator -> second is PtRpnOperator && first.operator==second.operator
                    is PtExpression -> second is PtExpression && first isSameAs second
                    else -> false
                }
            }
        }
        return false
    }

    fun maxDepth(): Pair<Map<DataType, Int>, Int> {
        val depths = mutableMapOf(
            DataType.UBYTE to 0,
            DataType.UWORD to 0,
            DataType.FLOAT to 0
        )
        val maxDepths = mutableMapOf(
            DataType.UBYTE to 0,
            DataType.UWORD to 0,
            DataType.FLOAT to 0
        )
        var numPushes = 0
        var numPops = 0

        fun push(type: DataType) {
            when (type) {
                in ByteDatatypes -> {
                    val depth = depths.getValue(DataType.UBYTE) + 1
                    depths[DataType.UBYTE] = depth
                    maxDepths[DataType.UBYTE] = max(maxDepths.getValue(DataType.UBYTE), depth)
                }
                in WordDatatypes -> {
                    val depth = depths.getValue(DataType.UWORD) + 1
                    depths[DataType.UWORD] = depth
                    maxDepths[DataType.UWORD] = max(maxDepths.getValue(DataType.UWORD), depth)
                }
                DataType.FLOAT -> {
                    val depth = depths.getValue(DataType.FLOAT) + 1
                    depths[DataType.FLOAT] = depth
                    maxDepths[DataType.FLOAT] = max(maxDepths.getValue(DataType.FLOAT), depth)
                }
                else -> throw IllegalArgumentException("invalid dt")
            }
            numPushes++
        }

        fun pop(type: DataType) {
            when (type) {
                in ByteDatatypes -> depths[DataType.UBYTE]=depths.getValue(DataType.UBYTE) - 1
                in WordDatatypes -> depths[DataType.UWORD]=depths.getValue(DataType.UWORD) - 1
                DataType.FLOAT -> depths[DataType.FLOAT]=depths.getValue(DataType.FLOAT) - 1
                else -> throw IllegalArgumentException("invalid dt")
            }
            numPops++
        }

        children.withIndex().forEach { (index, node) ->
            if (node is PtRpnOperator) {
                pop(node.operand1Type)
                pop(node.operand2Type)
                push(node.type)
            }
            else {
                push((node as PtExpression).type)
            }
        }
        require(numPushes==numPops+1) {
            "RPN not balanced, pushes=$numPushes pops=$numPops"
        }
        return Pair(maxDepths, numPushes)
    }
}

class PtRpnOperator(val operator: String, val type: DataType, val operand1Type: DataType, val operand2Type: DataType, position: Position): PtNode(position) {
//    init {
//        require(operand1Type equalsSize operand2Type) {
//            "operator $operator : operand1 and 2 types aren't equal sizes: $operand1Type $operand2Type"
//        }
//        if(operator !in ComparisonOperators) {
//            require(type equalsSize operand1Type && type equalsSize operand2Type) {
//                "operator $operator : type $type size not equal to operand1 and 2 types: $operand1Type $operand2Type"
//            }
//        }
//    }
}


class PtContainmentCheck(position: Position): PtExpression(DataType.UBYTE, position) {
    val element: PtExpression
        get() = children[0] as PtExpression
    val iterable: PtIdentifier
        get() = children[1] as PtIdentifier
}


class PtFunctionCall(val name: String,
                     val void: Boolean,
                     type: DataType,
                     position: Position) : PtExpression(type, position) {
    init {
        if(!void)
            require(type!=DataType.UNDEFINED)
    }

    val args: List<PtExpression>
        get() = children.map { it as PtExpression }
}


class PtIdentifier(val name: String, type: DataType, position: Position) : PtExpression(type, position)


class PtMemoryByte(position: Position) : PtExpression(DataType.UBYTE, position) {
    val address: PtExpression
        get() = children.single() as PtExpression
}


class PtNumber(type: DataType, val number: Double, position: Position) : PtExpression(type, position) {

    companion object {
        fun fromBoolean(bool: Boolean, position: Position): PtNumber =
            PtNumber(DataType.UBYTE, if(bool) 1.0 else 0.0, position)
    }

    init {
        if(type==DataType.BOOL)
            throw IllegalArgumentException("bool should have become ubyte @$position")
        if(type!=DataType.FLOAT) {
            val rounded = round(number)
            if (rounded != number)
                throw IllegalArgumentException("refused rounding of float to avoid loss of precision @$position")
        }
    }

    override fun hashCode(): Int = Objects.hash(type, number)

    override fun equals(other: Any?): Boolean {
        if(other==null || other !is PtNumber)
            return false
        return number==other.number
    }

    operator fun compareTo(other: PtNumber): Int = number.compareTo(other.number)
}


class PtPrefix(val operator: String, type: DataType, position: Position): PtExpression(type, position) {
    val value: PtExpression
        get() = children.single() as PtExpression

    init {
        // note: the "not" operator may no longer occur in the ast; not x should have been replaced with x==0
        require(operator in setOf("+", "-", "~")) { "invalid prefix operator: $operator" }
    }
}


class PtRange(type: DataType, position: Position) : PtExpression(type, position) {
    val from: PtExpression
        get() = children[0] as PtExpression
    val to: PtExpression
        get() = children[1] as PtExpression
    val step: PtNumber
        get() = children[2] as PtNumber

    fun toConstantIntegerRange(): IntProgression? {
        fun makeRange(fromVal: Int, toVal: Int, stepVal: Int): IntProgression {
            return when {
                fromVal <= toVal -> when {
                    stepVal <= 0 -> IntRange.EMPTY
                    stepVal == 1 -> fromVal..toVal
                    else -> fromVal..toVal step stepVal
                }
                else -> when {
                    stepVal >= 0 -> IntRange.EMPTY
                    stepVal == -1 -> fromVal downTo toVal
                    else -> fromVal downTo toVal step abs(stepVal)
                }
            }
        }

        val fromLv = from as? PtNumber
        val toLv = to as? PtNumber
        val stepLv = step as? PtNumber
        if(fromLv==null || toLv==null || stepLv==null)
            return null
        val fromVal = fromLv.number.toInt()
        val toVal = toLv.number.toInt()
        val stepVal = stepLv.number.toInt()
        return makeRange(fromVal, toVal, stepVal)
    }
}


class PtString(val value: String, val encoding: Encoding, position: Position) : PtExpression(DataType.STR, position) {
    override fun hashCode(): Int = Objects.hash(value, encoding)
    override fun equals(other: Any?): Boolean {
        if(other==null || other !is PtString)
            return false
        return value==other.value && encoding == other.encoding
    }
}


class PtTypeCast(type: DataType, position: Position) : PtExpression(type, position) {
    val value: PtExpression
        get() = children.single() as PtExpression
}


// special node that isn't created from compiling user code, but used internally in the Intermediate Code
class PtMachineRegister(val register: Int, type: DataType, position: Position) : PtExpression(type, position)


fun constValue(expr: PtExpression): Double? = if(expr is PtNumber) expr.number else null
fun constIntValue(expr: PtExpression): Int? = if(expr is PtNumber) expr.number.toInt() else null
