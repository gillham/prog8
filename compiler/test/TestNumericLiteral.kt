package prog8tests.compiler

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import prog8.ast.ExpressionError
import prog8.ast.expressions.ArrayLiteral
import prog8.ast.expressions.InferredTypes
import prog8.ast.expressions.NumericLiteral
import prog8.ast.expressions.StringLiteral
import prog8.ast.statements.AnonymousScope
import prog8.code.core.BaseDataType
import prog8.code.core.DataType
import prog8.code.core.Encoding
import prog8.code.core.Position
import prog8.code.target.C64Target
import prog8.code.target.Cx16Target
import prog8.code.target.VMTarget


class TestNumericLiteral: FunSpec({

    fun sameValueAndType(lv1: NumericLiteral, lv2: NumericLiteral): Boolean {
        return lv1.type==lv2.type && lv1==lv2
    }

    test("testIdentity") {
        val v = NumericLiteral(BaseDataType.UWORD, 12345.0, Position.DUMMY)
        (v==v) shouldBe true
        (v != v) shouldBe false
        (v <= v) shouldBe true
        (v >= v) shouldBe true
        (v < v ) shouldBe false
        (v > v ) shouldBe false

        sameValueAndType(NumericLiteral(BaseDataType.UWORD, 12345.0, Position.DUMMY), NumericLiteral(BaseDataType.UWORD, 12345.0, Position.DUMMY)) shouldBe true
    }

    test("test truncating avoidance") {
        shouldThrow<ExpressionError> {
            NumericLiteral(BaseDataType.BYTE, -2.345, Position.DUMMY)
        }.message shouldContain "float value given for integer"
        shouldThrow<ExpressionError> {
            NumericLiteral(BaseDataType.BYTE, -2.6, Position.DUMMY)
        }.message shouldContain "float value given for integer"
        shouldThrow<ExpressionError> {
            NumericLiteral(BaseDataType.UWORD, 2222.345, Position.DUMMY)
        }.message shouldContain "float value given for integer"
        NumericLiteral(BaseDataType.UBYTE, 2.0, Position.DUMMY).number shouldBe 2.0
        NumericLiteral(BaseDataType.BYTE, -2.0, Position.DUMMY).number shouldBe -2.0
        NumericLiteral(BaseDataType.UWORD, 2222.0, Position.DUMMY).number shouldBe 2222.0
        NumericLiteral(BaseDataType.FLOAT, 123.456, Position.DUMMY)
    }

    test("testEqualsAndNotEquals") {
        (NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY) == NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY) == NumericLiteral(BaseDataType.UWORD, 100.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY) == NumericLiteral(BaseDataType.FLOAT, 100.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.UWORD, 254.0, Position.DUMMY) == NumericLiteral(BaseDataType.UBYTE, 254.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.UWORD, 12345.0, Position.DUMMY) == NumericLiteral(BaseDataType.UWORD, 12345.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.UWORD, 12345.0, Position.DUMMY) == NumericLiteral(BaseDataType.FLOAT, 12345.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.FLOAT, 100.0, Position.DUMMY) == NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.FLOAT, 22239.0, Position.DUMMY) == NumericLiteral(BaseDataType.UWORD, 22239.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.FLOAT, 9.99, Position.DUMMY) == NumericLiteral(BaseDataType.FLOAT, 9.99, Position.DUMMY)) shouldBe true

        sameValueAndType(NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY), NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY)) shouldBe true
        sameValueAndType(NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY), NumericLiteral(BaseDataType.UWORD, 100.0, Position.DUMMY)) shouldBe false
        sameValueAndType(NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY), NumericLiteral(BaseDataType.FLOAT, 100.0, Position.DUMMY)) shouldBe false
        sameValueAndType(NumericLiteral(BaseDataType.UWORD, 254.0, Position.DUMMY), NumericLiteral(BaseDataType.UBYTE, 254.0, Position.DUMMY)) shouldBe false
        sameValueAndType(NumericLiteral(BaseDataType.UWORD, 12345.0, Position.DUMMY), NumericLiteral(BaseDataType.UWORD, 12345.0, Position.DUMMY)) shouldBe true
        sameValueAndType(NumericLiteral(BaseDataType.UWORD, 12345.0, Position.DUMMY), NumericLiteral(BaseDataType.FLOAT, 12345.0, Position.DUMMY)) shouldBe false
        sameValueAndType(NumericLiteral(BaseDataType.FLOAT, 100.0, Position.DUMMY), NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY)) shouldBe false
        sameValueAndType(NumericLiteral(BaseDataType.FLOAT, 22239.0, Position.DUMMY), NumericLiteral(BaseDataType.UWORD, 22239.0, Position.DUMMY)) shouldBe false
        sameValueAndType(NumericLiteral(BaseDataType.FLOAT, 9.99, Position.DUMMY), NumericLiteral(BaseDataType.FLOAT, 9.99, Position.DUMMY)) shouldBe true

        (NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY) != NumericLiteral(BaseDataType.UBYTE, 101.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY) != NumericLiteral(BaseDataType.UWORD, 101.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY) != NumericLiteral(BaseDataType.FLOAT, 101.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.UWORD, 245.0, Position.DUMMY) != NumericLiteral(BaseDataType.UBYTE, 246.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.UWORD, 12345.0, Position.DUMMY) != NumericLiteral(BaseDataType.UWORD, 12346.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.UWORD, 12345.0, Position.DUMMY) != NumericLiteral(BaseDataType.FLOAT, 12346.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.FLOAT, 9.99, Position.DUMMY) != NumericLiteral(BaseDataType.UBYTE, 9.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.FLOAT, 9.99, Position.DUMMY) != NumericLiteral(BaseDataType.UWORD, 9.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.FLOAT, 9.99, Position.DUMMY) != NumericLiteral(BaseDataType.FLOAT, 9.0, Position.DUMMY)) shouldBe true

        sameValueAndType(NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY), NumericLiteral(BaseDataType.UBYTE, 101.0, Position.DUMMY)) shouldBe false
        sameValueAndType(NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY), NumericLiteral(BaseDataType.UWORD, 101.0, Position.DUMMY)) shouldBe false
        sameValueAndType(NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY), NumericLiteral(BaseDataType.FLOAT, 101.0, Position.DUMMY)) shouldBe false
        sameValueAndType(NumericLiteral(BaseDataType.UWORD, 245.0, Position.DUMMY), NumericLiteral(BaseDataType.UBYTE, 246.0, Position.DUMMY)) shouldBe false
        sameValueAndType(NumericLiteral(BaseDataType.UWORD, 12345.0, Position.DUMMY), NumericLiteral(BaseDataType.UWORD, 12346.0, Position.DUMMY)) shouldBe false
        sameValueAndType(NumericLiteral(BaseDataType.UWORD, 12345.0, Position.DUMMY), NumericLiteral(BaseDataType.FLOAT, 12346.0, Position.DUMMY)) shouldBe false
        sameValueAndType(NumericLiteral(BaseDataType.FLOAT, 9.99, Position.DUMMY), NumericLiteral(BaseDataType.UBYTE, 9.0, Position.DUMMY)) shouldBe false
        sameValueAndType(NumericLiteral(BaseDataType.FLOAT, 9.99, Position.DUMMY), NumericLiteral(BaseDataType.UWORD, 9.0, Position.DUMMY)) shouldBe false
        sameValueAndType(NumericLiteral(BaseDataType.FLOAT, 9.99, Position.DUMMY), NumericLiteral(BaseDataType.FLOAT, 9.0, Position.DUMMY)) shouldBe false


    }
    

    test("testEqualsRef") {
        val c64target = C64Target()
        (StringLiteral.create("hello", Encoding.PETSCII, Position.DUMMY) == StringLiteral.create("hello", Encoding.PETSCII, Position.DUMMY)) shouldBe true
        (StringLiteral.create("hello", Encoding.PETSCII, Position.DUMMY) != StringLiteral.create("bye", Encoding.PETSCII, Position.DUMMY)) shouldBe true
        (StringLiteral.create("hello", Encoding.SCREENCODES, Position.DUMMY) == StringLiteral.create("hello", Encoding.SCREENCODES, Position.DUMMY)) shouldBe true
        (StringLiteral.create("hello", Encoding.SCREENCODES, Position.DUMMY) != StringLiteral.create("bye", Encoding.SCREENCODES, Position.DUMMY)) shouldBe true
        (StringLiteral.create("hello", Encoding.SCREENCODES, Position.DUMMY) != StringLiteral.create("hello", Encoding.PETSCII, Position.DUMMY)) shouldBe true

        val lvOne = NumericLiteral(BaseDataType.UBYTE, 1.0, Position.DUMMY)
        val lvTwo = NumericLiteral(BaseDataType.UBYTE, 2.0, Position.DUMMY)
        val lvThree = NumericLiteral(BaseDataType.UBYTE, 3.0, Position.DUMMY)
        val lvOneR = NumericLiteral(BaseDataType.UBYTE, 1.0, Position.DUMMY)
        val lvTwoR = NumericLiteral(BaseDataType.UBYTE, 2.0, Position.DUMMY)
        val lvThreeR = NumericLiteral(BaseDataType.UBYTE, 3.0, Position.DUMMY)
        val lvFour= NumericLiteral(BaseDataType.UBYTE, 4.0, Position.DUMMY)
        val lv1 = ArrayLiteral(InferredTypes.InferredType.known(DataType.arrayFor(BaseDataType.UBYTE, c64target)), arrayOf(lvOne, lvTwo, lvThree), Position.DUMMY)
        val lv2 = ArrayLiteral(InferredTypes.InferredType.known(DataType.arrayFor(BaseDataType.UBYTE, c64target)), arrayOf(lvOneR, lvTwoR, lvThreeR), Position.DUMMY)
        val lv3 = ArrayLiteral(InferredTypes.InferredType.known(DataType.arrayFor(BaseDataType.UBYTE, c64target)), arrayOf(lvOneR, lvTwoR, lvFour), Position.DUMMY)
        lv1 shouldBe lv2
        lv1 shouldNotBe lv3
    }

    test("testGreaterThan") {
        (NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY) > NumericLiteral(BaseDataType.UBYTE, 99.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.UWORD, 254.0, Position.DUMMY) > NumericLiteral(BaseDataType.UWORD, 253.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.FLOAT, 100.0, Position.DUMMY) > NumericLiteral(BaseDataType.FLOAT, 99.9, Position.DUMMY)) shouldBe true

        (NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY) >= NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.UWORD, 254.0, Position.DUMMY) >= NumericLiteral(BaseDataType.UWORD, 254.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.FLOAT, 100.0, Position.DUMMY) >= NumericLiteral(BaseDataType.FLOAT, 100.0, Position.DUMMY)) shouldBe true

        (NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY) > NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY)) shouldBe false
        (NumericLiteral(BaseDataType.UWORD, 254.0, Position.DUMMY) > NumericLiteral(BaseDataType.UWORD, 254.0, Position.DUMMY)) shouldBe false
        (NumericLiteral(BaseDataType.FLOAT, 100.0, Position.DUMMY) > NumericLiteral(BaseDataType.FLOAT, 100.0, Position.DUMMY)) shouldBe false

        (NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY) >= NumericLiteral(BaseDataType.UBYTE, 101.0, Position.DUMMY)) shouldBe false
        (NumericLiteral(BaseDataType.UWORD, 254.0, Position.DUMMY) >= NumericLiteral(BaseDataType.UWORD, 255.0, Position.DUMMY)) shouldBe false
        (NumericLiteral(BaseDataType.FLOAT, 100.0, Position.DUMMY) >= NumericLiteral(BaseDataType.FLOAT, 100.1, Position.DUMMY)) shouldBe false
    }

    test("testLessThan") {
        (NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY) < NumericLiteral(BaseDataType.UBYTE, 101.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.UWORD, 254.0, Position.DUMMY) < NumericLiteral(BaseDataType.UWORD, 255.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.FLOAT, 100.0, Position.DUMMY) < NumericLiteral(BaseDataType.FLOAT, 100.1, Position.DUMMY)) shouldBe true

        (NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY) <= NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.UWORD, 254.0, Position.DUMMY) <= NumericLiteral(BaseDataType.UWORD, 254.0, Position.DUMMY)) shouldBe true
        (NumericLiteral(BaseDataType.FLOAT, 100.0, Position.DUMMY) <= NumericLiteral(BaseDataType.FLOAT, 100.0, Position.DUMMY)) shouldBe true

        (NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY) < NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY)) shouldBe false
        (NumericLiteral(BaseDataType.UWORD, 254.0, Position.DUMMY) < NumericLiteral(BaseDataType.UWORD, 254.0, Position.DUMMY)) shouldBe false
        (NumericLiteral(BaseDataType.FLOAT, 100.0, Position.DUMMY) < NumericLiteral(BaseDataType.FLOAT, 100.0, Position.DUMMY)) shouldBe false

        (NumericLiteral(BaseDataType.UBYTE, 100.0, Position.DUMMY) <= NumericLiteral(BaseDataType.UBYTE, 99.0, Position.DUMMY)) shouldBe false
        (NumericLiteral(BaseDataType.UWORD, 254.0, Position.DUMMY) <= NumericLiteral(BaseDataType.UWORD, 253.0, Position.DUMMY)) shouldBe false
        (NumericLiteral(BaseDataType.FLOAT, 100.0, Position.DUMMY) <= NumericLiteral(BaseDataType.FLOAT, 99.9, Position.DUMMY)) shouldBe false
    }

    test("optimalInteger") {
        NumericLiteral.optimalInteger(10, Position.DUMMY).type shouldBe BaseDataType.UBYTE
        NumericLiteral.optimalInteger(10, Position.DUMMY).number shouldBe 10.0
        NumericLiteral.optimalInteger(-10, Position.DUMMY).type shouldBe BaseDataType.BYTE
        NumericLiteral.optimalInteger(-10, Position.DUMMY).number shouldBe -10.0
        NumericLiteral.optimalInteger(1000, Position.DUMMY).type shouldBe BaseDataType.UWORD
        NumericLiteral.optimalInteger(-1000, Position.DUMMY).number shouldBe -1000.0
        NumericLiteral.optimalInteger(1000u, Position.DUMMY).type shouldBe BaseDataType.UWORD
        NumericLiteral.optimalInteger(1000u, Position.DUMMY).number shouldBe 1000.0

        NumericLiteral.optimalInteger(BaseDataType.UBYTE, BaseDataType.UWORD, 1, Position.DUMMY).type shouldBe BaseDataType.UWORD
        NumericLiteral.optimalInteger(BaseDataType.UWORD, BaseDataType.UBYTE, 1, Position.DUMMY).type shouldBe BaseDataType.UWORD
        NumericLiteral.optimalInteger(BaseDataType.UWORD, null, 1, Position.DUMMY).type shouldBe BaseDataType.UWORD
        NumericLiteral.optimalInteger(BaseDataType.UBYTE, BaseDataType.UBYTE, -1, Position.DUMMY).type shouldBe BaseDataType.BYTE
        NumericLiteral.optimalInteger(BaseDataType.UBYTE, null, -1, Position.DUMMY).type shouldBe BaseDataType.BYTE
        NumericLiteral.optimalInteger(BaseDataType.UWORD, BaseDataType.UWORD, -1, Position.DUMMY).type shouldBe BaseDataType.WORD
        NumericLiteral.optimalInteger(BaseDataType.UWORD, null, -1, Position.DUMMY).type shouldBe BaseDataType.WORD
    }

    test("optimalNumeric") {
        NumericLiteral.optimalNumeric(10, Position.DUMMY).type shouldBe BaseDataType.UBYTE
        NumericLiteral.optimalNumeric(10, Position.DUMMY).number shouldBe 10.0
        NumericLiteral.optimalNumeric(-10, Position.DUMMY).type shouldBe BaseDataType.BYTE
        NumericLiteral.optimalNumeric(-10, Position.DUMMY).number shouldBe -10.0
        NumericLiteral.optimalNumeric(1000, Position.DUMMY).type shouldBe BaseDataType.UWORD
        NumericLiteral.optimalNumeric(1000, Position.DUMMY).number shouldBe 1000.0
        NumericLiteral.optimalNumeric(-1000, Position.DUMMY).type shouldBe BaseDataType.WORD
        NumericLiteral.optimalNumeric(-1000, Position.DUMMY).number shouldBe -1000.0
        NumericLiteral.optimalNumeric(1.123, Position.DUMMY).type shouldBe BaseDataType.FLOAT
        NumericLiteral.optimalNumeric(1.123, Position.DUMMY).number shouldBe 1.123
        NumericLiteral.optimalNumeric(1.0, Position.DUMMY).type shouldBe BaseDataType.UBYTE
        NumericLiteral.optimalNumeric(1.0, Position.DUMMY).number shouldBe 1.0
        NumericLiteral.optimalNumeric(-1.0, Position.DUMMY).type shouldBe BaseDataType.BYTE
        NumericLiteral.optimalNumeric(-1.0, Position.DUMMY).number shouldBe -1.0
        NumericLiteral.optimalNumeric(1234.0, Position.DUMMY).type shouldBe BaseDataType.UWORD
        NumericLiteral.optimalNumeric(1234.0, Position.DUMMY).number shouldBe 1234.0
        NumericLiteral.optimalNumeric(-1234.0, Position.DUMMY).type shouldBe BaseDataType.WORD
        NumericLiteral.optimalNumeric(-1234.0, Position.DUMMY).number shouldBe -1234.0

        NumericLiteral.optimalNumeric(BaseDataType.UBYTE, BaseDataType.UWORD, 1.0, Position.DUMMY).type shouldBe BaseDataType.UWORD
        NumericLiteral.optimalNumeric(BaseDataType.UWORD, BaseDataType.UBYTE, 1.0, Position.DUMMY).type shouldBe BaseDataType.UWORD
        NumericLiteral.optimalNumeric(BaseDataType.UWORD, null, 1.0, Position.DUMMY).type shouldBe BaseDataType.UWORD
        NumericLiteral.optimalNumeric(BaseDataType.UBYTE, BaseDataType.UBYTE, -1.0, Position.DUMMY).type shouldBe BaseDataType.BYTE
        NumericLiteral.optimalNumeric(BaseDataType.UBYTE, null, -1.0, Position.DUMMY).type shouldBe BaseDataType.BYTE
        NumericLiteral.optimalNumeric(BaseDataType.UWORD, BaseDataType.UWORD, -1.0, Position.DUMMY).type shouldBe BaseDataType.WORD
        NumericLiteral.optimalNumeric(BaseDataType.UWORD, null, -1.0, Position.DUMMY).type shouldBe BaseDataType.WORD

        NumericLiteral.optimalNumeric(BaseDataType.UBYTE, BaseDataType.UWORD, 1.234, Position.DUMMY).type shouldBe BaseDataType.FLOAT
        NumericLiteral.optimalNumeric(BaseDataType.UWORD, BaseDataType.UBYTE, 1.234, Position.DUMMY).type shouldBe BaseDataType.FLOAT
        NumericLiteral.optimalNumeric(BaseDataType.UWORD, null, 1.234, Position.DUMMY).type shouldBe BaseDataType.FLOAT
        NumericLiteral.optimalNumeric(BaseDataType.UBYTE, BaseDataType.UBYTE, -1.234, Position.DUMMY).type shouldBe BaseDataType.FLOAT
        NumericLiteral.optimalNumeric(BaseDataType.UBYTE, null, -1.234, Position.DUMMY).type shouldBe BaseDataType.FLOAT
        NumericLiteral.optimalNumeric(BaseDataType.UWORD, BaseDataType.UWORD, -1.234, Position.DUMMY).type shouldBe BaseDataType.FLOAT
        NumericLiteral.optimalNumeric(BaseDataType.UWORD, null, -1.234, Position.DUMMY).type shouldBe BaseDataType.FLOAT
    }

    test("cast can change value") {
        fun num(dt: BaseDataType, num: Double): NumericLiteral {
            val n = NumericLiteral(dt, num, Position.DUMMY)
            n.linkParents(AnonymousScope.empty())
            return n
        }
        val target = Cx16Target()
        val cast1 = num(BaseDataType.UBYTE, 200.0).cast(BaseDataType.BYTE, false, target)
        cast1.isValid shouldBe true
        cast1.valueOrZero().number shouldBe -56.0
        val cast2 = num(BaseDataType.BYTE, -50.0).cast(BaseDataType.UBYTE, false, target)
        cast2.isValid shouldBe true
        cast2.valueOrZero().number shouldBe 206.0
        val cast3 = num(BaseDataType.UWORD, 55555.0).cast(BaseDataType.WORD, false, target)
        cast3.isValid shouldBe true
        cast3.valueOrZero().number shouldBe -9981.0
        val cast4 = num(BaseDataType.WORD, -3333.0).cast(BaseDataType.UWORD, false, target)
        cast4.isValid shouldBe true
        cast4.valueOrZero().number shouldBe 62203.0
    }

    test("cat without changing value at all") {
        fun num(dt: BaseDataType, num: Double): NumericLiteral {
            val n = NumericLiteral(dt, num, Position.DUMMY)
            n.linkParents(AnonymousScope.empty())
            return n
        }
        val target = Cx16Target()
        num(BaseDataType.UBYTE, 200.0).castTypeKeepValue(BaseDataType.BYTE, target).isValid shouldBe false
        num(BaseDataType.BYTE, -50.0).castTypeKeepValue(BaseDataType.UBYTE, target).isValid shouldBe false
        num(BaseDataType.UWORD, 55555.0).castTypeKeepValue(BaseDataType.WORD, target).isValid shouldBe false
        num(BaseDataType.WORD, -3333.0).castTypeKeepValue(BaseDataType.UWORD, target).isValid shouldBe false

        num(BaseDataType.UBYTE, 42.0).castTypeKeepValue(BaseDataType.BYTE, target).isValid shouldBe true
        num(BaseDataType.BYTE, 42.0).castTypeKeepValue(BaseDataType.UBYTE, target).isValid shouldBe true
        num(BaseDataType.UWORD, 12345.0).castTypeKeepValue(BaseDataType.WORD, target).isValid shouldBe true
        num(BaseDataType.WORD, 12345.0).castTypeKeepValue(BaseDataType.UWORD, target).isValid shouldBe true
    }

    // ============================================================================
    // 64-bit quad and uquad literals
    // ============================================================================

    fun quad(type: BaseDataType, bits: Long): NumericLiteral =
        NumericLiteral.fromQuadBits(type, bits, Position.DUMMY).also { it.linkParents(AnonymousScope.empty()) }

    fun number(type: BaseDataType, value: Double): NumericLiteral =
        NumericLiteral(type, value, Position.DUMMY).also { it.linkParents(AnonymousScope.empty()) }

    test("a quad or uquad literal keeps all 64 bits, while its number is only the nearest double") {
        val mask = quad(BaseDataType.UQUAD, -16L)
        mask.number shouldBe 1.8446744073709552E19
        mask.integerBits shouldBe -16L
        mask.valueText shouldBe "18446744073709551600"
        mask.copy().integerBits shouldBe -16L
        val odd = quad(BaseDataType.QUAD, 0x0123_4567_89ab_cdefL)
        odd.integerBits shouldBe 0x0123_4567_89ab_cdefL
        odd.number shouldBe 0x0123_4567_89ab_cdefL.toDouble()
        odd.toString() shouldBe "NumericLiteral(QUAD:81985529216486895)"
        number(BaseDataType.QUAD, 5000000000.0).integerBits shouldBe 5000000000L
        number(BaseDataType.UWORD, 1234.0).integerBits shouldBe 1234L
        number(BaseDataType.BYTE, -5.0).integerBits shouldBe -5L
        shouldThrow<IllegalArgumentException> { number(BaseDataType.FLOAT, 1.5).integerBits }
        shouldThrow<IllegalArgumentException> { NumericLiteral.fromQuadBits(BaseDataType.LONG, 1L, Position.DUMMY) }
    }

    test("quad literals equal and compare by their exact values") {
        val max = quad(BaseDataType.UQUAD, -1L)
        val belowMax = quad(BaseDataType.UQUAD, -2L)
        max.number shouldBe belowMax.number
        (max == belowMax) shouldBe false
        (max > belowMax) shouldBe true
        (belowMax < max) shouldBe true
        (quad(BaseDataType.QUAD, -1L) == max) shouldBe false
        (quad(BaseDataType.QUAD, -1L) < max) shouldBe true
        (quad(BaseDataType.QUAD, 5L) == number(BaseDataType.UBYTE, 5.0)) shouldBe true
        (quad(BaseDataType.QUAD, 5L) == NumericLiteral.fromBoolean(true, Position.DUMMY)) shouldBe false
        (quad(BaseDataType.QUAD, Long.MAX_VALUE) == number(BaseDataType.FLOAT, Long.MAX_VALUE.toDouble())) shouldBe false
        (quad(BaseDataType.QUAD, Long.MAX_VALUE) < number(BaseDataType.FLOAT, Long.MAX_VALUE.toDouble())) shouldBe true
        (quad(BaseDataType.QUAD, 1L shl 62) == number(BaseDataType.FLOAT, (1L shl 62).toDouble())) shouldBe true
    }

    test("optimalInteger gives a quad for a value that doesn't fit in 32 bits") {
        NumericLiteral.optimalInteger(5000000000L, Position.DUMMY).type shouldBe BaseDataType.QUAD
        NumericLiteral.optimalInteger(-5000000000L, Position.DUMMY).integerBits shouldBe -5000000000L
        NumericLiteral.optimalInteger(200L, Position.DUMMY).type shouldBe BaseDataType.UBYTE
        NumericLiteral.optimalInteger(-2147483648L, Position.DUMMY).type shouldBe BaseDataType.LONG
        NumericLiteral.optimalNumeric(5000000000L, Position.DUMMY).type shouldBe BaseDataType.QUAD
        NumericLiteral.optimalNumeric(5000000000.0, Position.DUMMY).type shouldBe BaseDataType.FLOAT
        NumericLiteral.optimalInteger(BaseDataType.UQUAD, null, 5L, Position.DUMMY).type shouldBe BaseDataType.UQUAD
        NumericLiteral.optimalInteger(BaseDataType.UQUAD, BaseDataType.UBYTE, -5L, Position.DUMMY).type shouldBe BaseDataType.QUAD
    }

    test("smaller numbers widen to quad, and to uquad when not negative unless the cast is explicit") {
        val target = VMTarget()
        number(BaseDataType.WORD, -5.0).cast(BaseDataType.QUAD, true, target).valueOrZero().integerBits shouldBe -5L
        number(BaseDataType.UWORD, 65535.0).cast(BaseDataType.UQUAD, true, target).valueOrZero().integerBits shouldBe 65535L
        number(BaseDataType.LONG, -5.0).cast(BaseDataType.UQUAD, true, target).isValid shouldBe false
        number(BaseDataType.LONG, -5.0).cast(BaseDataType.UQUAD, false, target).valueOrZero().valueText shouldBe "18446744073709551611"
        number(BaseDataType.FLOAT, 1.5e15).cast(BaseDataType.QUAD, true, target).valueOrZero().integerBits shouldBe 1500000000000000L
        number(BaseDataType.FLOAT, 1.5).cast(BaseDataType.QUAD, true, target).isValid shouldBe false
        number(BaseDataType.FLOAT, -1.5).cast(BaseDataType.QUAD, false, target).valueOrZero().integerBits shouldBe -1L
        number(BaseDataType.FLOAT, 1e19).cast(BaseDataType.QUAD, false, target).isValid shouldBe false
        number(BaseDataType.FLOAT, 1e19).cast(BaseDataType.UQUAD, false, target).valueOrZero().valueText shouldBe "10000000000000000000"
        number(BaseDataType.FLOAT, -1.0).cast(BaseDataType.UQUAD, false, target).isValid shouldBe false
    }

    test("quads cast to the other quad type, to float and pointers, and explicitly truncate to smaller integer types") {
        val target = VMTarget()
        val big = quad(BaseDataType.UQUAD, Long.MIN_VALUE or 0xff)
        big.cast(BaseDataType.QUAD, true, target).isValid shouldBe false
        big.cast(BaseDataType.QUAD, false, target).valueOrZero().integerBits shouldBe (Long.MIN_VALUE or 0xff)
        quad(BaseDataType.QUAD, -1L).cast(BaseDataType.UQUAD, true, target).isValid shouldBe false
        quad(BaseDataType.QUAD, 42L).cast(BaseDataType.UQUAD, true, target).valueOrZero().integerBits shouldBe 42L
        big.cast(BaseDataType.UBYTE, true, target).isValid shouldBe false
        big.cast(BaseDataType.UBYTE, false, target).valueOrZero().number shouldBe 255.0
        big.cast(BaseDataType.BYTE, false, target).valueOrZero().number shouldBe -1.0
        quad(BaseDataType.QUAD, 0x1_2345_6789L).cast(BaseDataType.LONG, true, target).isValid shouldBe false
        quad(BaseDataType.QUAD, 0x1_2345_6789L).cast(BaseDataType.LONG, false, target).valueOrZero().number shouldBe 0x2345_6789.toDouble()
        quad(BaseDataType.QUAD, 70000L).cast(BaseDataType.UWORD, false, target).valueOrZero().number shouldBe 4464.0
        quad(BaseDataType.QUAD, -2L).cast(BaseDataType.WORD, true, target).valueOrZero().number shouldBe -2.0
        big.cast(BaseDataType.FLOAT, true, target).valueOrZero().number shouldBe big.number
        quad(BaseDataType.QUAD, 4096L).cast(BaseDataType.POINTER, true, target).valueOrZero().type shouldBe BaseDataType.LONG
        quad(BaseDataType.QUAD, 5000000000L).cast(BaseDataType.POINTER, true, target).isValid shouldBe false
        quad(BaseDataType.QUAD, 0L).cast(BaseDataType.BOOL, false, target).valueOrZero().number shouldBe 0.0
        quad(BaseDataType.QUAD, 0L).cast(BaseDataType.BOOL, true, target).isValid shouldBe false
    }

    test("a cast to or from a quad type that keeps the value") {
        val target = VMTarget()
        quad(BaseDataType.UQUAD, -1L).castTypeKeepValue(BaseDataType.QUAD, target).isValid shouldBe false
        quad(BaseDataType.QUAD, 255L).castTypeKeepValue(BaseDataType.UBYTE, target).isValid shouldBe true
        quad(BaseDataType.QUAD, 256L).castTypeKeepValue(BaseDataType.UBYTE, target).isValid shouldBe false
        number(BaseDataType.LONG, -1.0).castTypeKeepValue(BaseDataType.UQUAD, target).isValid shouldBe false
        number(BaseDataType.WORD, -1.0).castTypeKeepValue(BaseDataType.QUAD, target).isValid shouldBe true
        number(BaseDataType.BYTE, -1.0).castTypeKeepValue(BaseDataType.UQUAD, target).isValid shouldBe false
        number(BaseDataType.FLOAT, 2.5).castTypeKeepValue(BaseDataType.QUAD, target).isValid shouldBe false
    }
})
