package prog8tests.compiler

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import prog8.code.target.Cx16Target
import prog8.code.target.LlvmTarget
import prog8.code.target.VMTarget
import prog8.compiler.IrFileArguments
import prog8.compiler.compileIrFile
import prog8.intermediate.IRFileReader
import prog8.intermediate.IRStConstant
import prog8.intermediate.IRStStaticVariable
import prog8.intermediate.IRVariableInitializer
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.compileText
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText


class TestCompileIrFile: FunSpec({

    val outputDir = tempdir().toPath()

    val source = $$"""
%import textio
%zeropage basicsafe
main {
    const long LOWEST = -2147483648
    long @shared value = LOWEST
    sub start() {
        txt.print("hello ")
        txt.print_l(value)
        txt.nl()
    }
}"""

    test("a .p8ir file written by the new 6502 code generator builds to the same size of program") {
        val first = outputDir.resolve("cx16-source").createDirectories()
        val result = compileText(Cx16Target(), false, source, first, newCodegen = true)!!
        val name = result.compilerAst.name
        val second = outputDir.resolve("cx16-ir").createDirectories()
        val errors = ErrorReporterForTests()
        val built = compileIrFile(first.resolve("$name.p8ir"), "cx16", IrFileArguments(second, quiet = true), errors)
        built shouldNotBe null
        errors.errors shouldBe emptyList()
        built!!.outputs.programFile shouldBe second.resolve("$name.prg")
        built.outputs.programFile.readBytes().size shouldBe first.resolve("$name.prg").readBytes().size
    }

    test("a .p8ir file of an llvm target builds to LLVM IR") {
        val first = outputDir.resolve("llvm-source").createDirectories()
        val result = compileText(LlvmTarget(8), false, source, first, assemble = false)!!
        val name = result.compilerAst.name
        val second = outputDir.resolve("llvm-ir").createDirectories()
        val errors = ErrorReporterForTests()
        compileIrFile(first.resolve("$name.p8ir"), "llvm64", IrFileArguments(second, quiet = true, assemble = false), errors) shouldNotBe null
        errors.errors shouldBe emptyList()
        second.resolve("$name.ll").readText() shouldContain "define"
    }

    test("a .p8ir file for another target than -target, or for the virtual target, is an error") {
        val first = outputDir.resolve("vm-source").createDirectories()
        val result = compileText(VMTarget(), false, source, first)!!
        val irFile = first.resolve("${result.compilerAst.name}.p8ir")
        val second = outputDir.resolve("vm-ir").createDirectories()
        val errors = ErrorReporterForTests(throwExceptionAtReportIfErrors = false, keepMessagesAfterReporting = true)
        compileIrFile(irFile, "cx16", IrFileArguments(second), errors) shouldBe null
        errors.errors.single() shouldContain "for the virtual target, not cx16"
        val errors2 = ErrorReporterForTests(throwExceptionAtReportIfErrors = false, keepMessagesAfterReporting = true)
        compileIrFile(irFile, null, IrFileArguments(second), errors2) shouldBe null
        errors2.errors.single() shouldContain "run it with -vm"
        second.resolve("${result.compilerAst.name}.p8ir").exists() shouldBe false
    }

    test("negative signed constants and initial values keep their sign through a .p8ir file") {
        val result = compileText(VMTarget(), false, source, outputDir)!!
        val irProgram = IRFileReader().read(outputDir.resolve("${result.compilerAst.name}.p8ir"))
        (irProgram.st.lookup("main.LOWEST") as IRStConstant).value shouldBe -2147483648.0
        val value = irProgram.st.lookup("main.value") as IRStStaticVariable
        (value.initializationValue as IRVariableInitializer.Numeric).value shouldBe -2147483648.0
    }
})
