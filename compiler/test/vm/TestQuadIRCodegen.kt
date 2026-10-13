package prog8tests.vm

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import prog8.code.target.Cx16Target
import prog8.code.target.VMTarget
import prog8.intermediate.*
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.compileText

class TestQuadIRCodegen: FunSpec({
    val outputDir = tempdir().toPath()

    fun irFor(src: String, optimize: Boolean = false): IRProgram {
        val result = compileText(VMTarget(), optimize, src, outputDir, writeAssembly = true)!!
        return IRFileReader().read(result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir"))
    }

    fun IRProgram.instructionsOf(subName: String): List<IRInstruction> {
        val result = mutableListOf<IRInstruction>()
        allSubs().single { it.label == subName }.forEachChunk { result += it.instructions }
        return result
    }

    fun List<IRInstruction>.withOpcode(opcode: Opcode, type: IRDataType?) = filter { it.opcode == opcode && it.type == type }

    fun List<IRInstruction>.quadImmediates(opcode: Opcode) = withOpcode(opcode, IRDataType.QUAD).map { it.requireImmediateQuad() }

    test("quad initializers and constants keep all 64 bits") {
        val src = $$"""
main {
    quad @shared big = 9223372036854775807
    uquad @shared mask = $ffff_ffff_ffff_fffe
    quad[] @shared values = [-1, 5000000000, -9223372036854775808]
    const quad CQ = 1234567890123456789

    sub start() {
        quad @shared q = CQ
        q = -5000000000
        uquad @shared u = mask & $00ff_00ff_00ff_00ff
    }
}"""
        val ir = irFor(src)
        val big = ir.st.lookup("main.big") as IRStStaticVariable
        (big.initializationValue as IRVariableInitializer.Numeric).quadBits shouldBe Long.MAX_VALUE
        val mask = ir.st.lookup("main.mask") as IRStStaticVariable
        (mask.initializationValue as IRVariableInitializer.Numeric).quadBits shouldBe -2L
        val values = ir.st.lookup("main.values") as IRStStaticVariable
        (values.initializationValue as IRVariableInitializer.Array).elements.map { (it as IRStSymbolicReference.Numeric).quadBits } shouldBe
                listOf(-1L, 5000000000L, Long.MIN_VALUE)
        val start = ir.instructionsOf("main.start")
        start.quadImmediates(Opcode.STOREIM) shouldBe listOf(1234567890123456789L, -5000000000L)
        start.quadImmediates(Opcode.AND) shouldBe listOf(0x00ff00ff00ff00ffL)
    }

    test("quad operators follow the signedness of quad and uquad") {
        val src = """
main {
    sub start() {
        quad @shared q = 5000000000
        uquad @shared u = 7
        quad @shared q2 = 3
        uquad @shared u2 = 3
        ubyte @shared n = 3
        q2 = q / 7
        u2 = u / 7
        q2 = q % 10
        u2 = u % 10
        q2 = q >> n
        u2 = u >> n
        q2 = q * q
        q2 = -q
        u2 = ~u
        bool @shared b = q < 0
        b = u > 5
        b = q2 <= q
        b = u2 >= u
    }
}"""
        val start = irFor(src).instructionsOf("main.start")
        start.quadImmediates(Opcode.DIVS) shouldBe listOf(7L)
        start.quadImmediates(Opcode.DIV) shouldBe listOf(7L)
        start.quadImmediates(Opcode.MODS) shouldBe listOf(10L)
        start.quadImmediates(Opcode.MOD) shouldBe listOf(10L)
        start.withOpcode(Opcode.ASRN, IRDataType.QUAD).size shouldBe 1
        start.withOpcode(Opcode.LSRN, IRDataType.QUAD).size shouldBe 1
        start.withOpcode(Opcode.MULSR, IRDataType.QUAD).size shouldBe 1
        start.withOpcode(Opcode.NEG, IRDataType.QUAD).size shouldBe 1
        start.withOpcode(Opcode.INV, IRDataType.QUAD).size shouldBe 1
        start.quadImmediates(Opcode.BLTS) shouldBe listOf(0L)
        start.quadImmediates(Opcode.BGT) shouldBe listOf(5L)
        start.withOpcode(Opcode.BGESR, IRDataType.QUAD).size shouldBe 1
        start.withOpcode(Opcode.BGER, IRDataType.QUAD).size shouldBe 1
        start.none { it.opcode == Opcode.CALL } shouldBe true
    }

    test("quad conversions widen, narrow and convert floats") {
        val src = """
%option enable_floats
main {
    sub start() {
        byte @shared b = -1
        ubyte @shared ub = 200
        word @shared w = -1
        uword @shared uw = 60000
        long @shared l = -1
        float @shared f = 1.5
        quad @shared q
        uquad @shared u
        q = b
        q = ub
        q = w
        u = uw
        q = l
        q = f as quad
        u = f as uquad
        f = q as float
        f = u as float
        l = q as long
        w = q as word
        ub = u as ubyte
        bool @shared flag = q as bool
    }
}"""
        val start = irFor(src).instructionsOf("main.start")
        start.withOpcode(Opcode.EXTQS, IRDataType.BYTE).size shouldBe 1
        start.withOpcode(Opcode.EXTQ, IRDataType.BYTE).size shouldBe 1
        start.withOpcode(Opcode.EXTQS, IRDataType.WORD).size shouldBe 1
        start.withOpcode(Opcode.EXTQ, IRDataType.WORD).size shouldBe 1
        start.withOpcode(Opcode.EXTQS, IRDataType.LONG).size shouldBe 1
        start.withOpcode(Opcode.FTOSQ, IRDataType.FLOAT).size shouldBe 1
        start.withOpcode(Opcode.FTOUQ, IRDataType.FLOAT).size shouldBe 1
        start.withOpcode(Opcode.FFROMSQ, IRDataType.FLOAT).size shouldBe 1
        start.withOpcode(Opcode.FFROMUQ, IRDataType.FLOAT).size shouldBe 1
        start.withOpcode(Opcode.LSIGL, IRDataType.QUAD).size shouldBe 1
        start.withOpcode(Opcode.LSIGW, IRDataType.QUAD).size shouldBe 1
        start.withOpcode(Opcode.LSIGB, IRDataType.QUAD).size shouldBe 1
        start.quadImmediates(Opcode.CMPI) shouldBe listOf(0L)
    }

    test("quad pointers, struct fields and multiple return values") {
        val src = """
main {
    struct Item {
        ubyte tag
        quad amount
        uquad mask
    }

    sub start() {
        ubyte @shared i = 1
        quad @shared q = 5000000000
        uquad @shared u
        ^^ubyte @shared bp = q as ^^ubyte
        u = bp as uquad
        ^^Item items = 4000
        items[i].amount = 5000000000
        items[i].mask |= 2
        q = items[i].amount
        pokeq(4000, 6000000000)
        q = peekq(4000)
        q, u = two()
    }

    sub two() -> quad, uquad {
        return 5000000000, 1
    }
}"""
        val ir = irFor(src)
        val start = ir.instructionsOf("main.start")
        start.withOpcode(Opcode.LSIGL, IRDataType.QUAD).size shouldBe 1
        start.withOpcode(Opcode.EXTQ, IRDataType.POINTER).size shouldBe 1
        start.withOpcode(Opcode.STOREI, IRDataType.QUAD).map { (it.requireMemory() as MemoryReference.Indirect).displacement } shouldBe listOf(1, 9)
        start.withOpcode(Opcode.LOADI, IRDataType.QUAD).size shouldBe 2
        start.quadImmediates(Opcode.LOAD).contains(6000000000L) shouldBe true
        start.withOpcode(Opcode.STOREM, IRDataType.QUAD).any { it.requireMemory().absoluteAddress?.value == 4000u } shouldBe true
        start.withOpcode(Opcode.LOADM, IRDataType.QUAD).any { it.requireMemory().absoluteAddress?.value == 4000u } shouldBe true
        start.withOpcode(Opcode.LOADHR, IRDataType.QUAD).size shouldBe 2
        ir.instructionsOf("main.two").withOpcode(Opcode.STOREHR, IRDataType.QUAD).size shouldBe 2
    }

    test("comparisons with constants beyond the int range are exact") {
        val src = $$"""
main {
    sub start() {
        quad @shared q = 7
        uquad @shared u = 9
        if q > 5000000000
            q = 1
        when q {
            5000000000 -> q = 2
            -5000000000 -> q = 3
        }
        bool @shared b = u == $ffff_ffff_0000_0000
        b = q < 9223372036854775807
        b = q > -5000000000
    }
}"""
        val start = irFor(src).instructionsOf("main.start")
        start.quadImmediates(Opcode.BLES) shouldBe listOf(5000000000L)
        start.quadImmediates(Opcode.CMPI) shouldBe listOf(5000000000L, -5000000000L, -0x100000000L)
        start.quadImmediates(Opcode.BLTS) shouldBe listOf(Long.MAX_VALUE)
        start.quadImmediates(Opcode.BGTS) shouldBe listOf(-5000000000L)
    }

    test("consecutive quad updates are combined without losing bits") {
        val src = """
main {
    sub start() {
        quad @shared q = 7
        q += 9007199254740993
        q += 1
        q *= 3
        q *= 3000000000000000001
    }
}"""
        val start = irFor(src).instructionsOf("main.start")
        start.quadImmediates(Opcode.ADDIM) shouldBe listOf(9007199254740994L)
        start.quadImmediates(Opcode.LOAD) shouldBe listOf(9000000000000000003L)
        start.withOpcode(Opcode.MULSM, IRDataType.QUAD).size shouldBe 1
    }

    test("containment in a quad array uses the quad array search") {
        val src = """
main {
    quad[] @shared values = [1, 5000000000, 3]
    sub start() {
        quad @shared q = 7
        bool @shared found = q in values
        found = q in [9, 6000000000]
    }
}"""
        val start = irFor(src).instructionsOf("main.start")
        start.count { (it.callSite?.target as? CallTarget.SystemCall)?.number == IMSyscall.QUADARRAY_CONTAINS.number } shouldBe 2
    }

    test("optimized quad code keeps its exact immediates") {
        val src = $$"""
main {
    sub start() {
        quad @shared q = 5000000000
        q += 6000000000
        q = getq(q)
        uquad @shared u = 5
        uquad @shared u2 = u | ~u
        uquad @shared u3 = u & $ffff_ffff_ffff_ffff
        uquad @shared u4 = u ^ $ffff_ffff_ffff_ffff
    }

    sub getq(quad v) -> quad {
        if v > 7000000000
            return 7000000000
        return v * 4294967298
    }
}"""
        val ir = irFor(src, optimize = true)
        val start = ir.instructionsOf("main.start")
        start.quadImmediates(Opcode.ADDIM) shouldBe listOf(6000000000L)
        start.quadImmediates(Opcode.STOREIM) shouldBe listOf(5000000000L, 5L, -1L)
        start.withOpcode(Opcode.AND, IRDataType.QUAD).size + start.withOpcode(Opcode.XOR, IRDataType.QUAD).size shouldBe 0
        start.withOpcode(Opcode.INV, IRDataType.QUAD).size shouldBe 1
        val getq = ir.instructionsOf("main.getq")
        getq.quadImmediates(Opcode.BLES) shouldBe listOf(7000000000L)
        getq.quadImmediates(Opcode.RETURNI) shouldBe listOf(7000000000L)
        getq.withOpcode(Opcode.LSL, IRDataType.QUAD).size + getq.withOpcode(Opcode.LSLI, IRDataType.QUAD).size shouldBe 0
        getq.withOpcode(Opcode.MULS, IRDataType.QUAD).map { it.requireImmediateQuad() } shouldBe listOf(4294967298L)
    }

    test("peekq and pokeq are rejected where quads aren't supported") {
        val src = $$"""
main {
    sub start() {
        pokeq($1000, 5)
        uword @shared w = peekq($1000) as uword
    }
}"""
        val errors = ErrorReporterForTests()
        compileText(Cx16Target(), false, src, outputDir, errors = errors, writeAssembly = false) shouldBe null
        errors.errors.size shouldBe 2
        errors.errors.forEach { it shouldContain "64-bit integer types (quad/uquad) are not supported on target cx16" }
        compileText(VMTarget(), false, src, outputDir, writeAssembly = false) shouldNotBe null
    }
})
