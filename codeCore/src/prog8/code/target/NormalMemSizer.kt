package prog8.code.target

import prog8.code.core.BaseDataType
import prog8.code.core.DataType
import prog8.code.core.IMemSizer

internal class NormalMemSizer(val floatsize: Int): IMemSizer {

    override fun memorySize(dt: DataType, numElements: Int?): Int {
        if(dt.isArray) {
            if(numElements==null) return 2      // treat it as a pointer size
            return when(dt.sub) {
                BaseDataType.BOOL, BaseDataType.UBYTE, BaseDataType.BYTE -> numElements
                BaseDataType.UWORD, BaseDataType.WORD, BaseDataType.STR -> numElements * 2
                BaseDataType.FLOAT-> numElements * floatsize
                BaseDataType.UNDEFINED -> throw IllegalArgumentException("undefined has no memory size")
                else -> throw IllegalArgumentException("invalid sub type")
            }
        }
        else if (dt.isString) {
            return numElements        // treat it as the size of the given string with the length
                ?: 2    // treat it as the size to store a string pointer
        }

        return when {
            dt.isByteOrBool -> 1 * (numElements ?: 1)
            dt.isFloat -> floatsize * (numElements ?: 1)
            dt.isLong -> 4 * (numElements ?: 1)
            dt.isUndefined -> throw IllegalArgumentException("undefined has no memory size")
            else -> 2 * (numElements ?: 1)
        }
    }

}