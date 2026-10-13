package prog8tests.codecore

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import prog8.code.core.*
import prog8.code.target.Amiga500Target
import prog8.code.target.Cx16Target
import prog8.code.target.LlvmTarget
import prog8.code.target.VMTarget


/**
 * Unit tests for DataType and BaseDataType classes in codeCore.
 * Focus on type predicates, relationships, and key operations.
 */
class TestDataType: FunSpec({
    val tgt = Cx16Target()

    val dummyMemSizer = object : IMemSizer {
        override val FLOAT_MEM_SIZE: UInt = 4u
        override val POINTER_MEM_SIZE: UInt = 2u

        override fun memorySize(dt: DataType, numElements: Int?): Int {
            if (dt.isPointerArray) return 2 * numElements!!
            if (dt.isArray || dt.isSplitWordArray(this)) {
                require(numElements != null)
                return when (dt.sub) {
                    BaseDataType.BOOL, BaseDataType.BYTE, BaseDataType.UBYTE -> numElements
                    BaseDataType.UWORD, BaseDataType.WORD -> numElements * 2
                    BaseDataType.LONG -> numElements * 4
                    BaseDataType.FLOAT -> numElements * 5
                    BaseDataType.POINTER -> numElements * 2
                    BaseDataType.STRUCT_INSTANCE -> numElements * (dt.subType?.memsize(this) ?: throw IllegalArgumentException("struct instance array has unresolved subtype"))
                    else -> throw IllegalArgumentException("invalid sub type")
                }
            }
            return when {
                dt.isByteOrBool -> 1 * (numElements ?: 1)
                dt.isLong -> 4 * (numElements ?: 1)
                dt.isFloat -> 5 * (numElements ?: 1)
                else -> 2 * (numElements ?: 1)
            }
        }

        override fun memorySize(dt: BaseDataType): Int {
            return memorySize(DataType.forDt(dt), null)
        }
    }

    // ============================================================================
    // BaseDataType Extension Property Tests
    // ============================================================================

    test("isByte extension") {
        BaseDataType.UBYTE.isByte shouldBe true
        BaseDataType.BYTE.isByte shouldBe true
        BaseDataType.UWORD.isByte shouldBe false
        BaseDataType.WORD.isByte shouldBe false
    }

    test("isByteOrBool extension") {
        BaseDataType.UBYTE.isByteOrBool shouldBe true
        BaseDataType.BYTE.isByteOrBool shouldBe true
        BaseDataType.BOOL.isByteOrBool shouldBe true
        BaseDataType.UWORD.isByteOrBool shouldBe false
    }

    test("isWord extension") {
        BaseDataType.UWORD.isWord shouldBe true
        BaseDataType.WORD.isWord shouldBe true
        BaseDataType.UBYTE.isWord shouldBe false
    }

    test("isInteger extension") {
        BaseDataType.UBYTE.isInteger shouldBe true
        BaseDataType.WORD.isInteger shouldBe true
        BaseDataType.LONG.isInteger shouldBe true
        BaseDataType.FLOAT.isInteger shouldBe false
    }

    test("isNumeric extension") {
        BaseDataType.UBYTE.isNumeric shouldBe true
        BaseDataType.FLOAT.isNumeric shouldBe true
        BaseDataType.BOOL.isNumeric shouldBe false
    }

    test("isSigned extension") {
        BaseDataType.BYTE.isSigned shouldBe true
        BaseDataType.WORD.isSigned shouldBe true
        BaseDataType.LONG.isSigned shouldBe true
        BaseDataType.UBYTE.isSigned shouldBe false
    }

    test("isArray extension") {
        BaseDataType.ARRAY.isArray shouldBe true
        BaseDataType.ARRAY_SPLITW.isArray shouldBe true
        BaseDataType.UBYTE.isArray shouldBe false
    }

    test("isPointerArray property") {
        DataType.arrayOfPointersTo(BaseDataType.UBYTE).isPointerArray shouldBe true
        DataType.arrayOfPointersTo(BaseDataType.WORD).isPointerArray shouldBe true
        DataType.arrayFor(BaseDataType.UBYTE, tgt).isPointerArray shouldBe false
    }

    test("isPointer extension") {
        BaseDataType.POINTER.isPointer shouldBe true
        BaseDataType.UBYTE.isPointer shouldBe false
    }

    test("isSplitWordArray extension") {
        DataType.splitWordArrayFor(BaseDataType.UWORD).isSplitWordArray(Cx16Target()) shouldBe true
        DataType.splitWordArrayFor(BaseDataType.UWORD).isSplitWordArray(VMTarget()) shouldBe true
        DataType.splitWordArrayFor(BaseDataType.UWORD).isSplitWordArray(Amiga500Target()) shouldBe true

        DataType.arrayOfPointersTo(BaseDataType.UWORD).isSplitWordArray(Cx16Target()) shouldBe true
        DataType.arrayOfPointersTo(BaseDataType.UWORD).isSplitWordArray(VMTarget()) shouldBe false
        DataType.arrayOfPointersTo(BaseDataType.UWORD).isSplitWordArray(Amiga500Target()) shouldBe false

        DataType.arrayFor(BaseDataType.UWORD, Cx16Target()).isSplitWordArray(Cx16Target()) shouldBe false
        DataType.arrayFor(BaseDataType.UWORD, VMTarget()).isSplitWordArray(VMTarget()) shouldBe false
        DataType.arrayFor(BaseDataType.UWORD, Amiga500Target()).isSplitWordArray(Amiga500Target()) shouldBe false
    }

    // ============================================================================
    // DataType Companion Object Tests
    // ============================================================================

    test("DataType.forDt creates simple types") {
        DataType.forDt(BaseDataType.UBYTE) shouldBe DataType.UBYTE
        DataType.forDt(BaseDataType.WORD) shouldBe DataType.WORD
        DataType.forDt(BaseDataType.LONG) shouldBe DataType.LONG
        DataType.forDt(BaseDataType.FLOAT) shouldBe DataType.FLOAT
    }

    test("DataType.forDt supports struct instance") {
        val dt = DataType.forDt(BaseDataType.STRUCT_INSTANCE)
        dt.base shouldBe BaseDataType.STRUCT_INSTANCE
        dt.isStructInstance shouldBe true
    }

    test("DataType.arrayFor creates array types") {
        val ubyteArray = DataType.arrayFor(BaseDataType.UBYTE, tgt)
        ubyteArray.base shouldBe BaseDataType.ARRAY
        ubyteArray.sub shouldBe BaseDataType.UBYTE

        val wordArray = DataType.arrayFor(BaseDataType.WORD, tgt)
        wordArray.base shouldBe BaseDataType.ARRAY
        wordArray.sub shouldBe BaseDataType.WORD
    }

    test("DataType.pointer creates pointer types") {
        val ptrByte = DataType.pointer(BaseDataType.UBYTE)
        ptrByte.base shouldBe BaseDataType.POINTER
        ptrByte.sub shouldBe BaseDataType.UBYTE
    }

    // ============================================================================
    // DataType Property Tests
    // ============================================================================

    test("DataType.isBasic property") {
        DataType.UBYTE.isBasic shouldBe true
        DataType.WORD.isBasic shouldBe true
        DataType.STR.isBasic shouldBe false
        DataType.arrayFor(BaseDataType.UBYTE, tgt).isBasic shouldBe false
    }

    test("DataType.isByte property") {
        DataType.UBYTE.isByte shouldBe true
        DataType.BYTE.isByte shouldBe true
        DataType.WORD.isByte shouldBe false
    }

    test("DataType.isNumeric property") {
        DataType.UBYTE.isNumeric shouldBe true
        DataType.FLOAT.isNumeric shouldBe true
        DataType.BOOL.isNumeric shouldBe false
        DataType.STR.isNumeric shouldBe false
    }

    test("DataType.isArray property") {
        DataType.arrayFor(BaseDataType.UBYTE, tgt).isArray shouldBe true
        DataType.UBYTE.isArray shouldBe false
    }

    test("DataType.isPointer property") {
        DataType.pointer(BaseDataType.UBYTE).isPointer shouldBe true
        DataType.UBYTE.isPointer shouldBe false
    }

    test("DataType.isString property") {
        DataType.STR.isString shouldBe true
        DataType.UBYTE.isString shouldBe false
    }

    test("DataType.isPassByRef property") {
        DataType.STR.isPassByRef shouldBe true
        DataType.arrayFor(BaseDataType.UBYTE, tgt).isPassByRef shouldBe true
        DataType.UBYTE.isPassByRef shouldBe false
        DataType.pointer(BaseDataType.UBYTE).isPassByRef shouldBe false
    }

    // ============================================================================
    // DataType Method Tests
    // ============================================================================

    test("DataType.elementType returns element type for arrays") {
        DataType.arrayFor(BaseDataType.UBYTE, tgt).elementType() shouldBe DataType.UBYTE
        DataType.arrayFor(BaseDataType.WORD, tgt).elementType() shouldBe DataType.WORD
        DataType.STR.elementType() shouldBe DataType.UBYTE
    }

    test("DataType.elementType throws for non-arrays") {
        shouldThrow<IllegalArgumentException> {
            DataType.UBYTE.elementType()
        }
    }

    test("DataType.dereference for pointers") {
        DataType.pointer(BaseDataType.UBYTE).dereference() shouldBe DataType.UBYTE
        DataType.pointer(BaseDataType.WORD).dereference() shouldBe DataType.WORD
        DataType.UWORD.dereference() shouldBe DataType.UBYTE
    }

    test("DataType.size with memsizer") {
        DataType.UBYTE.size(dummyMemSizer) shouldBe 1
        DataType.WORD.size(dummyMemSizer) shouldBe 2
        DataType.LONG.size(dummyMemSizer) shouldBe 4
        DataType.FLOAT.size(dummyMemSizer) shouldBe 5
    }

    // ============================================================================
    // DataType Comparison Tests
    // ============================================================================

    test("DataType equals for same types") {
        DataType.UBYTE shouldBe DataType.UBYTE
        DataType.WORD shouldBe DataType.WORD
        DataType.STR shouldBe DataType.STR
    }

    test("DataType equals for different types") {
        DataType.UBYTE shouldNotBe DataType.BYTE
        DataType.UBYTE shouldNotBe DataType.WORD
    }

    test("BaseDataType.largerSizeThan") {
        BaseDataType.WORD.largerSizeThan(BaseDataType.UBYTE) shouldBe true
        BaseDataType.LONG.largerSizeThan(BaseDataType.WORD) shouldBe true
        BaseDataType.UBYTE.largerSizeThan(BaseDataType.WORD) shouldBe false
        BaseDataType.STR.largerSizeThan(BaseDataType.UBYTE) shouldBe true
        BaseDataType.STR.largerSizeThan(BaseDataType.BOOL) shouldBe true
        BaseDataType.STR.largerSizeThan(BaseDataType.UWORD) shouldBe false
        BaseDataType.STR.largerSizeThan(BaseDataType.WORD) shouldBe false
        BaseDataType.STR.largerSizeThan(BaseDataType.POINTER) shouldBe false
        BaseDataType.STR.largerSizeThan(BaseDataType.LONG) shouldBe false
        BaseDataType.POINTER.largerSizeThan(BaseDataType.UBYTE) shouldBe true
        BaseDataType.POINTER.largerSizeThan(BaseDataType.BOOL) shouldBe true
        BaseDataType.POINTER.largerSizeThan(BaseDataType.UWORD) shouldBe false
        BaseDataType.POINTER.largerSizeThan(BaseDataType.STR) shouldBe false
    }

    test("BaseDataType.equalsSize") {
        BaseDataType.UBYTE.equalsSize(BaseDataType.UBYTE) shouldBe true
        BaseDataType.UBYTE.equalsSize(BaseDataType.BYTE) shouldBe true
        BaseDataType.WORD.equalsSize(BaseDataType.UWORD) shouldBe true
        BaseDataType.UBYTE.equalsSize(BaseDataType.WORD) shouldBe false
        BaseDataType.STR.equalsSize(BaseDataType.STR) shouldBe true
        BaseDataType.STR.equalsSize(BaseDataType.UWORD) shouldBe true
        BaseDataType.STR.equalsSize(BaseDataType.BYTE) shouldBe false
        BaseDataType.POINTER.equalsSize(BaseDataType.UWORD) shouldBe true
        BaseDataType.POINTER.equalsSize(BaseDataType.STR) shouldBe false
    }

    // ============================================================================
    // DataType.isAssignableTo Tests
    // ============================================================================

    test("bool assignability") {
        DataType.BOOL.isAssignableTo(DataType.BOOL) shouldBe true
        DataType.BOOL.isAssignableTo(DataType.UBYTE) shouldBe false
    }

    test("ubyte assignability") {
        DataType.UBYTE.isAssignableTo(DataType.UBYTE) shouldBe true
        DataType.UBYTE.isAssignableTo(DataType.UWORD) shouldBe true
        DataType.UBYTE.isAssignableTo(DataType.LONG) shouldBe true
        DataType.UBYTE.isAssignableTo(DataType.FLOAT) shouldBe true
    }

    test("byte assignability") {
        DataType.BYTE.isAssignableTo(DataType.BYTE) shouldBe true
        DataType.BYTE.isAssignableTo(DataType.WORD) shouldBe true
        DataType.BYTE.isAssignableTo(DataType.LONG) shouldBe true
    }

    test("uword assignability") {
        DataType.UWORD.isAssignableTo(DataType.UWORD) shouldBe true
        DataType.UWORD.isAssignableTo(DataType.LONG) shouldBe true
        DataType.UWORD.isAssignableTo(DataType.FLOAT) shouldBe true
    }

    test("string assignability") {
        DataType.STR.isAssignableTo(DataType.STR) shouldBe true
        DataType.STR.isAssignableTo(DataType.UWORD) shouldBe true
    }

    test("pointer assignability") {
        val ptr = DataType.pointer(BaseDataType.UBYTE)
        ptr.isAssignableTo(DataType.UWORD) shouldBe true
        ptr.isAssignableTo(DataType.LONG) shouldBe true
        ptr.isAssignableTo(DataType.pointer(BaseDataType.UBYTE)) shouldBe true
    }

    // ============================================================================
    // DataType.toString Tests
    // ============================================================================

    test("DataType.toString for basic types") {
        DataType.UBYTE.toString() shouldBe "ubyte"
        DataType.WORD.toString() shouldBe "word"
        DataType.LONG.toString() shouldBe "long"
        DataType.FLOAT.toString() shouldBe "float"
    }

    test("DataType.toString for arrays") {
        DataType.arrayFor(BaseDataType.UBYTE, tgt).toString() shouldBe "ubyte[]"
        DataType.arrayFor(BaseDataType.WORD, tgt).toString() shouldBe "word[]"
    }

    // ============================================================================
    // 64-bit quad and uquad Tests
    // ============================================================================

    test("quad is the signed and uquad the unsigned 64-bit integer type") {
        for (quad in listOf(BaseDataType.QUAD, BaseDataType.UQUAD)) {
            quad.isQuad shouldBe true
            quad.isInteger shouldBe true
            quad.isIntegerOrBool shouldBe true
            quad.isNumeric shouldBe true
            quad.isNumericOrBool shouldBe true
            quad.isLong shouldBe false
            quad.isWordOrByteOrBool shouldBe false
            quad.isPassByValue shouldBe true
        }
        BaseDataType.QUAD.isSignedQuad shouldBe true
        BaseDataType.QUAD.isUnsignedQuad shouldBe false
        BaseDataType.QUAD.isSigned shouldBe true
        BaseDataType.QUAD.isSignedInteger shouldBe true
        BaseDataType.UQUAD.isUnsignedQuad shouldBe true
        BaseDataType.UQUAD.isSignedQuad shouldBe false
        BaseDataType.UQUAD.isSigned shouldBe false
        BaseDataType.UQUAD.isUnsignedInteger shouldBe true
        BaseDataType.LONG.isQuad shouldBe false
        BaseDataType.FLOAT.isQuad shouldBe false
    }

    test("the maximum values of quad and uquad, as the nearest doubles") {
        BaseDataType.QUAD.maxUnsignedValue shouldBe 9.223372036854775807E18
        BaseDataType.UQUAD.maxUnsignedValue shouldBe 1.8446744073709551615E19
        BaseDataType.LONG.maxUnsignedValue shouldBe 2147483647.0
    }

    test("quad and uquad are larger than the byte, word and long types, and only equal in size to each other") {
        for (quad in listOf(BaseDataType.QUAD, BaseDataType.UQUAD)) {
            for (smaller in listOf(BaseDataType.BOOL, BaseDataType.UBYTE, BaseDataType.BYTE, BaseDataType.UWORD, BaseDataType.WORD, BaseDataType.LONG)) {
                quad.largerSizeThan(smaller) shouldBe true
                smaller.largerSizeThan(quad) shouldBe false
                quad.equalsSize(smaller) shouldBe false
                smaller.equalsSize(quad) shouldBe false
            }
            quad.largerSizeThan(BaseDataType.FLOAT) shouldBe false
            BaseDataType.FLOAT.largerSizeThan(quad) shouldBe true
        }
        BaseDataType.QUAD.largerSizeThan(BaseDataType.UQUAD) shouldBe false
        BaseDataType.UQUAD.largerSizeThan(BaseDataType.QUAD) shouldBe false
        BaseDataType.QUAD.equalsSize(BaseDataType.UQUAD) shouldBe true
        BaseDataType.UQUAD.equalsSize(BaseDataType.QUAD) shouldBe true
    }

    test("quad and uquad only equal pointers in size when those are 8 bytes") {
        for (quad in listOf(BaseDataType.QUAD, BaseDataType.UQUAD)) {
            quad.equalsSize(BaseDataType.POINTER, LlvmTarget()) shouldBe true
            BaseDataType.POINTER.equalsSize(quad, LlvmTarget()) shouldBe true
            quad.equalsSize(BaseDataType.POINTER, VMTarget()) shouldBe false
            BaseDataType.POINTER.equalsSize(quad, VMTarget()) shouldBe false
            quad.equalsSize(BaseDataType.POINTER) shouldBe false
            BaseDataType.POINTER.equalsSize(quad) shouldBe false
        }
        BaseDataType.POINTER.equalsSize(BaseDataType.LONG, LlvmTarget()) shouldBe true
    }

    test("quad and uquad DataTypes") {
        DataType.forDt(BaseDataType.QUAD) shouldBe DataType.QUAD
        DataType.forDt(BaseDataType.UQUAD) shouldBe DataType.UQUAD
        DataType.QUAD shouldNotBe DataType.UQUAD
        DataType.QUAD.isBasic shouldBe true
        DataType.QUAD.isQuad shouldBe true
        DataType.QUAD.isSignedQuad shouldBe true
        DataType.QUAD.isUnsignedQuad shouldBe false
        DataType.QUAD.isSignedInteger shouldBe true
        DataType.UQUAD.isQuad shouldBe true
        DataType.UQUAD.isUnsignedQuad shouldBe true
        DataType.UQUAD.isSignedQuad shouldBe false
        DataType.UQUAD.isUnsignedInteger shouldBe true
        DataType.LONG.isQuad shouldBe false
    }

    test("quad and uquad arrays") {
        val quads = DataType.arrayFor(BaseDataType.QUAD, VMTarget())
        val uquads = DataType.arrayFor(BaseDataType.UQUAD, VMTarget())
        quads.isQuadArray shouldBe true
        quads.isSignedQuadArray shouldBe true
        quads.isUnsignedQuadArray shouldBe false
        uquads.isQuadArray shouldBe true
        uquads.isUnsignedQuadArray shouldBe true
        uquads.isSignedQuadArray shouldBe false
        DataType.arrayFor(BaseDataType.LONG, VMTarget()).isQuadArray shouldBe false
        DataType.QUAD.isQuadArray shouldBe false
        quads.elementType() shouldBe DataType.QUAD
        uquads.elementType() shouldBe DataType.UQUAD
        DataType.UQUAD.elementToArray(tgt) shouldBe DataType.arrayFor(BaseDataType.UQUAD, tgt)
    }

    test("quad and uquad take 8 bytes, whatever the pointer size") {
        for (target in listOf(Cx16Target(), VMTarget(), LlvmTarget())) {
            target.memorySize(BaseDataType.QUAD) shouldBe 8
            target.memorySize(BaseDataType.UQUAD) shouldBe 8
            DataType.UQUAD.size(target) shouldBe 8
            target.memorySize(DataType.arrayFor(BaseDataType.QUAD, target), 3) shouldBe 24
        }
    }

    test("smaller integers widen to quad without loss, and only the unsigned ones to uquad") {
        for (dt in listOf(DataType.UBYTE, DataType.BYTE, DataType.UWORD, DataType.WORD, DataType.LONG))
            dt.isAssignableTo(DataType.QUAD) shouldBe true
        DataType.UBYTE.isAssignableTo(DataType.UQUAD) shouldBe true
        DataType.UWORD.isAssignableTo(DataType.UQUAD) shouldBe true
        DataType.BYTE.isAssignableTo(DataType.UQUAD) shouldBe false
        DataType.WORD.isAssignableTo(DataType.UQUAD) shouldBe false
        DataType.LONG.isAssignableTo(DataType.UQUAD) shouldBe false
        DataType.BOOL.isAssignableTo(DataType.QUAD) shouldBe false
        DataType.FLOAT.isAssignableTo(DataType.QUAD) shouldBe false
        DataType.pointer(BaseDataType.UBYTE).isAssignableTo(DataType.UQUAD) shouldBe false
    }

    test("quad and uquad are assignable to themselves, float and pointers, not to each other or smaller types") {
        for (quad in listOf(DataType.QUAD, DataType.UQUAD)) {
            quad.isAssignableTo(quad) shouldBe true
            quad.isAssignableTo(DataType.FLOAT) shouldBe true
            quad.isAssignableTo(DataType.pointer(BaseDataType.UBYTE)) shouldBe true
            for (smaller in listOf(DataType.BOOL, DataType.UBYTE, DataType.BYTE, DataType.UWORD, DataType.WORD, DataType.LONG))
                quad.isAssignableTo(smaller) shouldBe false
        }
        DataType.QUAD.isAssignableTo(DataType.UQUAD) shouldBe false
        DataType.UQUAD.isAssignableTo(DataType.QUAD) shouldBe false
    }

    test("quad and uquad toString and sourceString") {
        DataType.QUAD.toString() shouldBe "quad"
        DataType.UQUAD.toString() shouldBe "uquad"
        DataType.arrayFor(BaseDataType.QUAD, tgt).toString() shouldBe "quad[]"
        DataType.arrayFor(BaseDataType.UQUAD, tgt).toString() shouldBe "uquad[]"
        DataType.pointer(BaseDataType.UQUAD).toString() shouldBe "^^uquad"
        DataType.QUAD.sourceString() shouldBe "quad"
        DataType.UQUAD.sourceString() shouldBe "uquad"
        DataType.arrayFor(BaseDataType.QUAD, tgt).sourceString() shouldBe "quad["
        DataType.arrayFor(BaseDataType.UQUAD, tgt).sourceString() shouldBe "uquad["
        DataType.pointer(BaseDataType.QUAD).sourceString() shouldBe "^^quad"
    }
})
