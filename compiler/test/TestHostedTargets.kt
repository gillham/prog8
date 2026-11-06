package prog8tests.compiler

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import prog8.ast.Program
import prog8.ast.expressions.NumericLiteral
import prog8.ast.statements.Block
import prog8.ast.statements.VarDecl
import prog8.code.core.CompilationOptions
import prog8.code.core.ICompilationTarget
import prog8.code.source.SourceCode
import prog8.code.target.Cx16Target
import prog8.code.target.LlvmTarget
import prog8.code.target.LlvmTripleProfile
import prog8.compiler.astprocessing.cx16VirtualRegisterAddresses
import prog8.compiler.astprocessing.preprocessAst
import prog8.parser.Prog8Parser.parseModule
import prog8tests.helpers.DummyFunctions
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.compileText


class TestHostedTargets: FunSpec({

    val outputDir = tempdir().toPath()
    val hostedTargets = listOf(LlvmTarget(8), LlvmTarget(4), LlvmTarget(2))

    fun program(header: String, blockAddress: String = "") = """
$header
main $blockAddress{
    sub start() {
    }
}"""

    val fixedAddressPrograms = mapOf(
        "%address" to program($$"%launcher none\n%address $4000"),
        "%memtop" to program($$"%memtop $c000"),
        "%varsaddress" to program($$"%varsaddress $a000"),
        "a block address" to program("", $$"$4000 "),
    )

    test("hosted targets have no fixed memory addresses") {
        hostedTargets.forEach { it.hasFixedMemoryAddresses shouldBe false }
        Cx16Target().hasFixedMemoryAddresses shouldBe true
    }

    for ((what, src) in fixedAddressPrograms) {
        test("$what is an error on the hosted targets, and accepted on cx16") {
            for (target in hostedTargets) {
                withClue(target.name) {
                    val errors = ErrorReporterForTests()
                    compileText(target, false, src, outputDir, errors = errors, writeAssembly = false) shouldBe null
                    errors.errors.size shouldBe 1
                    errors.errors[0] shouldContain "not available on the hosted ${target.name} target"
                }
            }
            compileText(Cx16Target(), false, src, outputDir, writeAssembly = false) shouldNotBe null
        }
    }

    test("%option romable is an error on the hosted targets") {
        val src = program("%option romable")
        for (target in hostedTargets) {
            withClue(target.name) {
                val errors = ErrorReporterForTests()
                compileText(target, false, src, outputDir, errors = errors, writeAssembly = false) shouldBe null
                errors.errors.single() shouldContain "romable option is not available on the hosted ${target.name} target"
            }
        }
        compileText(Cx16Target(), false, program($$"%option romable\n%varsaddress $a000"), outputDir, writeAssembly = false) shouldNotBe null
    }

    test("%jmptable is an error on the hosted targets") {
        val src = """
main {
    %jmptable ( routine )

    sub start() {
    }

    sub routine() {
    }
}"""
        for (target in hostedTargets) {
            withClue(target.name) {
                val errors = ErrorReporterForTests()
                compileText(target, false, src, outputDir, errors = errors, writeAssembly = false) shouldBe null
                errors.errors.single() shouldContain "%jmptable is not supported on the hosted ${target.name} target"
            }
        }
    }

    test("the vars address options are an error on the hosted targets") {
        for (target in hostedTargets) {
            withClue(target.name) {
                compileText(target, false, program(""), outputDir, writeAssembly = false, varsAddress = 0xa000u) shouldBe null
                compileText(target, false, program(""), outputDir, writeAssembly = false, varshigh = 1) shouldBe null
            }
        }
    }

    test("extsub without an address is only accepted on the hosted targets, extsub with an address only on the others") {
        val withoutAddress = """
main {
    extsub putchar(ubyte char) -> ubyte

    sub start() {
        void putchar(65)
    }
}"""
        val withAddress = $$"""
main {
    extsub $ffd2 = chrout(ubyte char @A)

    sub start() {
        chrout(65)
    }
}"""
        for (target in hostedTargets) {
            withClue(target.name) {
                compileText(target, false, withoutAddress, outputDir, writeAssembly = false) shouldNotBe null
                val errors = ErrorReporterForTests()
                compileText(target, false, withAddress, outputDir, errors = errors, writeAssembly = false) shouldBe null
                errors.errors.single() shouldContain "extsub with an address is not supported on target ${target.name}"
            }
        }
        val errors = ErrorReporterForTests()
        compileText(Cx16Target(), false, withoutAddress, outputDir, errors = errors, writeAssembly = false) shouldBe null
        errors.errors.single() shouldContain "address-free extsub (external C routine) is not supported on target cx16"
        compileText(Cx16Target(), false, withAddress, outputDir, writeAssembly = false) shouldNotBe null
    }


    val noEffectPrograms = mapOf(
        "%output" to program("%output raw\n%launcher none"),
        "%zeropage full" to program("%zeropage full"),
        "%zeropage kernalsafe" to program("%zeropage kernalsafe"),
        "%zeropage floatsafe" to program("%zeropage floatsafe"),
        "%zpreserved" to program("%zpreserved 10,20"),
        "%zpallowed" to program("%zpallowed 10,20"),
    )

    for ((what, src) in noEffectPrograms) {
        test("$what gives a no-effect warning on the hosted targets, and none on cx16") {
            for (target in hostedTargets) {
                withClue(target.name) {
                    val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                    compileText(target, false, src, outputDir, errors = errors, writeAssembly = false) shouldNotBe null
                    errors.warnings.single() shouldContain "has no effect on the hosted ${target.name} target"
                }
            }
            val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
            compileText(Cx16Target(), false, src, outputDir, errors = errors, writeAssembly = false) shouldNotBe null
            errors.warnings.none { "has no effect" in it } shouldBe true
        }
    }

    test("%zeropage basicsafe and dontuse are silent on the hosted targets") {
        for (mode in listOf("basicsafe", "dontuse")) {
            for (target in hostedTargets) {
                withClue("${target.name} $mode") {
                    val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                    compileText(target, false, program("%zeropage $mode"), outputDir, errors = errors, writeAssembly = false) shouldNotBe null
                    errors.warnings.none { "has no effect" in it } shouldBe true
                }
            }
        }
    }

    test("the cx16 virtual registers keep their little-endian layout, and get the big-endian one on a big-endian target") {
        val little = cx16VirtualRegisterAddresses(0x1000u, false)
        little["r0"] shouldBe 0x1000u
        little["r0L"] shouldBe 0x1000u
        little["r0H"] shouldBe 0x1001u
        little["r1"] shouldBe 0x1002u
        little["r15bH"] shouldBe 0x101fu
        little["r14r15sl"] shouldBe 0x101cu

        val big = cx16VirtualRegisterAddresses(0x1000u, true)
        for (pair in 0u..7u) {
            val long = 0x1000u + 4u * pair
            val low = "r${2u * pair}"
            val high = "r${2u * pair + 1u}"
            big["${low}${high}sl"] shouldBe long
            big[high] shouldBe long
            big[low] shouldBe long + 2u
            for (reg in listOf(low, high)) {
                big["${reg}H"] shouldBe big[reg]
                big["${reg}L"] shouldBe big[reg]!! + 1u
                big["${reg}s"] shouldBe big[reg]
                big["${reg}sH"] shouldBe big[reg]
                big["${reg}sL"] shouldBe big[reg]!! + 1u
                big["${reg}bH"] shouldBe big[reg]
                big["${reg}bL"] shouldBe big[reg]!! + 1u
            }
        }
        big.keys shouldBe little.keys
    }

    test("the llvm16 syslib declares the little-endian layout, which a big-endian target relocates") {
        fun cx16Addresses(program: Program) = program.allBlocks.single { it.name == "cx16" }.statements
            .filterIsInstance<VarDecl>().filter { it.name.matches(Regex("r[0-9].*")) }
            .associate { it.name to (it.value as NumericLiteral).number.toUInt() }
        fun preprocessedCx16Block(target: ICompilationTarget): Program {
            val syslib = parseModule(SourceCode.Text(javaClass.getResource("/prog8lib/llvm16/syslib.p8")!!.readText()), target)
            syslib.statements.retainAll { it is Block && it.name == "cx16" }
            val program = Program("test", DummyFunctions, target).addModule(syslib)
            val errors = ErrorReporterForTests()
            program.preprocessAst(errors, CompilationOptions.builder(target).build())
            errors.errors shouldBe emptyList()
            return program
        }
        val llvm16 = LlvmTarget(2)
        val bigEndian = LlvmTarget(2, LlvmTripleProfile("bigendian", "mc6809",
            "E-p:16:8-p1:8:8-S8-m:e-i1:8-i8:8-i16:8-i32:8-i64:8-f32:8-f64:8-a:0-n8:16", 4, cIntSize = 2))
        bigEndian.isBigEndian shouldBe true
        cx16Addresses(preprocessedCx16Block(llvm16)) shouldBe cx16VirtualRegisterAddresses(0xff0000u, false)
        cx16Addresses(preprocessedCx16Block(bigEndian)) shouldBe cx16VirtualRegisterAddresses(0xff0000u, true)
    }
})
