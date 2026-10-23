package prog8tests.compiler

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import prog8.code.target.Cx16Target
import prog8.code.target.LlvmTarget
import prog8.code.target.VMTarget
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.compileText


class TestCrashRegressions: FunSpec({

    val outputDir = tempdir().toPath()

    test("consecutive augmented assignments to an untyped uquad dereference report errors instead of crashing") {
        val src = $$"""
main {
    sub start() {
        uquad @shared p = $4000
        p^^ += 5
        p^^++
    }
}"""
        for (target in listOf(VMTarget(), LlvmTarget(4))) {
            val errors = ErrorReporterForTests()
            compileText(target, false, src, outputDir, errors, writeAssembly = false) shouldBe null
            errors.errors.any { "unable to determine type of dereferenced pointer expression" in it } shouldBe true
        }
    }

    test("consecutive augmented assignments to a uquad dereference still merge with 8-byte pointers") {
        val src = $$"""
main {
    sub start() {
        uquad @shared p = $4000
        p^^ += 5
        p^^++
    }
}"""
        compileText(LlvmTarget(8), true, src, outputDir, writeAssembly = false) shouldNotBe null
    }

    test("a block level breakpoint compiles for the virtual target") {
        val src = """
main {
    %breakpoint
    sub start() {
    }
}"""
        val result = compileText(VMTarget(), false, src, outputDir, writeAssembly = true)!!
        val irFile = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir").toFile().readText()
        irFile.lines().map { it.trim() }.count { it == "breakpoint" } shouldBe 1
        compileText(Cx16Target(), false, src, outputDir, writeAssembly = false) shouldNotBe null
    }

    test("a block level breakpoint on the llvm targets is a normal error") {
        val src = """
main {
    %breakpoint
    sub start() {
    }
}"""
        val errors = ErrorReporterForTests()
        compileText(LlvmTarget(8), false, src, outputDir, errors, writeAssembly = true) shouldBe null
        errors.errors.single() shouldContain "code outside of a subroutine"
    }
})
