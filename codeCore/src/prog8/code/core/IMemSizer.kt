package prog8.code.core

interface IMemSizer {
    val FLOAT_MEM_SIZE: UInt
    val POINTER_MEM_SIZE: UInt

    /** The integer type that holds an address: uword, long or uquad for 2, 4 or 8 byte pointers. */
    val pointerType: DataType
        get() = if(POINTER_MEM_SIZE > 4u) DataType.UQUAD else if(POINTER_MEM_SIZE > 2u) DataType.LONG else DataType.UWORD

    val pointerBaseType: BaseDataType
        get() = if(POINTER_MEM_SIZE > 4u) BaseDataType.UQUAD else if(POINTER_MEM_SIZE > 2u) BaseDataType.LONG else BaseDataType.UWORD

    /** The suffix of the peek, poke, push and pop builtins that move a whole pointer: w, l or q for 2, 4 or 8 byte pointers. */
    val pointerPeekPokeSuffix: String
        get() = if(POINTER_MEM_SIZE > 4u) "q" else if(POINTER_MEM_SIZE > 2u) "l" else "w"

    /** Whether a value of type [from] converts implicitly, sign extended, into [to]: a long into the uquad address integer type of 8-byte pointers, as it does into a pointer. */
    fun longConvertsToAddress(from: DataType, to: DataType): Boolean =
        POINTER_MEM_SIZE > 4u && from.isLong && to == pointerType

    fun memorySize(dt: DataType, numElements: Int?): Int

    fun memorySize(dt: BaseDataType): Int {
        if(dt.isPassByRef)
            return POINTER_MEM_SIZE.toInt() 
        try {
            return memorySize(DataType.forDt(dt), null)
        } catch (x: NoSuchElementException) {
            throw IllegalArgumentException(x.message)
        }
    }

    fun isSplitWordArray(dt: DataType): Boolean {
        return dt.isSplitWordArray(this)
    }
}
