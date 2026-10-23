package prog8tests.compiler

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import prog8.code.core.ICompilationTarget
import prog8.code.target.Cx16Target
import prog8.code.target.LlvmTarget
import prog8.code.target.Qemu68kTarget
import prog8.code.target.VMTarget
import prog8.intermediate.IRFileReader
import prog8.vm.VmRunner
import prog8.vm.VmVariableAllocator
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.compileText
import kotlin.io.path.readText


class TestComputedGoto: FunSpec({

    val outputDir = tempdir().toPath()

    fun program(addressType: String) = """
main {
    $addressType @shared global

    sub start() {
        uword @shared local = viaLocal()
        uword @shared viaglobal = viaGlobal()
    }

    sub answer() -> uword {
        return 42
    }

    sub viaLocal() -> uword {
        $addressType @shared address = &answer
        goto address
    }

    sub viaGlobal() -> uword {
        global = &answer
        goto global
    }
}"""

    val targetsWithAddressType = listOf<Pair<ICompilationTarget, String>>(
        Cx16Target() to "uword",
        VMTarget() to "long",
        Qemu68kTarget() to "long",
        LlvmTarget(4) to "long",
        LlvmTarget(8) to "uquad",
    )

    test("a goto through a local or global address variable ends a subroutine that returns a value") {
        for ((target, addressType) in targetsWithAddressType) {
            for (optimize in listOf(false, true)) {
                withClue("${target.name} optimize=$optimize") {
                    compileText(target, optimize, program(addressType), outputDir, writeAssembly = false) shouldNotBe null
                }
            }
        }
    }

    test("a goto through an address variable returns the value of the subroutine it jumps to in the virtual machine") {
        for (optimize in listOf(false, true)) {
            val result = compileText(VMTarget(), optimize, program("long"), outputDir)!!
            val virtfile = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir")
            val irProgram = IRFileReader().read(virtfile)
            val allocations = VmVariableAllocator(irProgram.st, irProgram.encoding, irProgram.options.compTarget).allocations
            VmRunner().runAndTestProgram(virtfile.readText(), true) { vm ->
                withClue("optimize=$optimize") {
                    vm.memory.getUW(allocations.getValue("main.start.local")) shouldBe 42u
                    vm.memory.getUW(allocations.getValue("main.start.viaglobal")) shouldBe 42u
                }
            }
        }
    }

    test("a goto to a label inside the subroutine still doesn't end a subroutine that returns a value") {
        val src = """
main {
    sub start() {
        uword @shared x = loop()
    }

    sub loop() -> uword {
again:
        goto again
    }
}"""
        val errors = ErrorReporterForTests()
        compileText(Cx16Target(), false, src, outputDir, errors, writeAssembly = false) shouldBe null
        errors.errors.any { "must have at least one 'return'" in it } shouldBe true
    }

    test("a goto through a local address variable is a jump out of a defer") {
        val src = $$"""
main {
    sub start() {
        uword @shared address = $4000
        defer {
            goto address
        }
    }
}"""
        val errors = ErrorReporterForTests()
        compileText(Cx16Target(), false, src, outputDir, errors, writeAssembly = false) shouldBe null
        errors.errors.any { "defer cannot contain jumps or returns" in it } shouldBe true
    }

    test("an address variable of the wrong type names the address type of the target") {
        val src = """
main {
    sub start() {
        ubyte @shared address = 42
        goto address
    }
}"""
        for ((target, addressType) in targetsWithAddressType) {
            val errors = ErrorReporterForTests()
            withClue(target.name) {
                compileText(target, false, src, outputDir, errors, writeAssembly = false) shouldBe null
                errors.errors.any { "wrong address variable datatype, expected $addressType" in it } shouldBe true
            }
        }
    }
})
