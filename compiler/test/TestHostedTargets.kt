package prog8tests.compiler

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import prog8.code.target.Cx16Target
import prog8.code.target.LlvmTarget
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.compileText


class TestHostedTargets: FunSpec({

    val outputDir = tempdir().toPath()
    val hostedTargets = listOf(LlvmTarget(8), LlvmTarget(4))

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
})
