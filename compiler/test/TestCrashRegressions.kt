package prog8tests.compiler

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import prog8.code.target.Cx16Target
import prog8.code.target.LlvmTarget
import prog8.code.target.VMTarget
import prog8.intermediate.IRFileReader
import prog8.vm.VmRunner
import prog8.vm.VmVariableAllocator
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.compileText
import kotlin.io.path.readText


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

    val pointerCastsSrc = $$"""
main {
    struct S { ubyte a  uword b }
    ^^ubyte @shared gp = $1234 as ^^ubyte
    sub f(^^uword q) -> uword { return q as uword }
    sub g() -> ^^ubyte { return $1236 as ^^ubyte }
    sub start() {
        ^^ubyte @shared p = $1234 as ^^ubyte
        ^^S @shared ps = $1234 as ^^S
        ^^uword @shared pw
        uword @shared declared = p as uword
        uword @shared global = gp as uword
        uword @shared structptr = ps as uword
        p = $1235 as ^^ubyte
        uword @shared assigned = p as uword
        uword @shared argument = f($1234 as ^^uword)
        uword @shared returned = g() as uword
        pw = ($1234 as ^^uword) + 3
        uword @shared scaled = pw as uword
        ps = ($1234 as ^^S) + 1
        uword @shared structscaled = ps as uword
        p = ($1230 + 4) as ^^ubyte
        uword @shared folded = p as uword
    }
}"""

    test("a numeric literal cast to a pointer type compiles in every value position") {
        for(optimize in listOf(false, true)) {
            for(target in listOf(Cx16Target(), VMTarget(), LlvmTarget(4), LlvmTarget(8))) {
                withClue("${target.name} optimize=$optimize") {
                    compileText(target, optimize, pointerCastsSrc, outputDir, writeAssembly = true) shouldNotBe null
                }
            }
        }
    }

    test("a numeric literal cast to a pointer type gives the right addresses on the virtual machine") {
        for(optimize in listOf(false, true)) {
            withClue("optimize=$optimize") {
                val result = compileText(VMTarget(), optimize, pointerCastsSrc, outputDir, writeAssembly = true)!!
                val virtfile = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir")
                val irProgram = IRFileReader().read(virtfile)
                val allocations = VmVariableAllocator(irProgram.st, irProgram.encoding, irProgram.options.compTarget).allocations
                VmRunner().runAndTestProgram(virtfile.readText(), true) { vm ->
                    fun word(name: String) = vm.memory.getUW(allocations.getValue("main.start.$name"))
                    word("declared") shouldBe 0x1234u
                    word("global") shouldBe 0x1234u
                    word("structptr") shouldBe 0x1234u
                    word("assigned") shouldBe 0x1235u
                    word("argument") shouldBe 0x1234u
                    word("returned") shouldBe 0x1236u
                    word("scaled") shouldBe 0x123au
                    word("structscaled") shouldBe 0x1237u
                    word("folded") shouldBe 0x1234u
                }
            }
        }
    }

    test("a long or quad shifted by a word or long variable uses the low byte of the count") {
        val src = """
main {
    sub start() {
        long @shared l1 = 1
        long @shared l2 = 64
        long @shared l3 = 1
        quad @shared q1 = 1
        quad @shared q2 = 64
        uword @shared count = 3
        long @shared lcount = 259
        l1 <<= count
        l2 = l2 >> lcount
        l3 = l3 << lcount
        q1 <<= count
        q2 >>= lcount
    }
}"""
        compileText(LlvmTarget(8), false, src, outputDir, writeAssembly = true) shouldNotBe null
        compileText(LlvmTarget(4), false, src.lines().filterNot { "quad" in it || "q1" in it || "q2" in it }.joinToString("\n"),
            outputDir, writeAssembly = true) shouldNotBe null
        for(optimize in listOf(false, true)) {
            withClue("optimize=$optimize") {
                val result = compileText(VMTarget(), optimize, src, outputDir, writeAssembly = true)!!
                val virtfile = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir")
                val irProgram = IRFileReader().read(virtfile)
                val allocations = VmVariableAllocator(irProgram.st, irProgram.encoding, irProgram.options.compTarget).allocations
                VmRunner().runAndTestProgram(virtfile.readText(), true) { vm ->
                    fun long(name: String) = vm.memory.getSL(allocations.getValue("main.start.$name"))
                    fun quad(name: String) = vm.memory.getSQ(allocations.getValue("main.start.$name"))
                    long("l1") shouldBe 8
                    long("l2") shouldBe 8
                    long("l3") shouldBe 8
                    quad("q1") shouldBe 8L
                    quad("q2") shouldBe 8L
                }
            }
        }
    }

    test("a constant address that doesn't fit a 2-byte pointer is an error, with or without a cast") {
        for(value in listOf($$"$12345 as ^^ubyte", $$"$12345")) {
            val src = """
main {
    sub start() {
        ^^ubyte @shared p = $value
        p = $value
    }
}"""
            withClue(value) {
                val errors = ErrorReporterForTests()
                compileText(Cx16Target(), false, src, outputDir, errors, writeAssembly = false) shouldBe null
                errors.errors.size shouldBe 2
                errors.errors.all { "address out of range" in it } shouldBe true
                compileText(VMTarget(), false, src, outputDir, writeAssembly = true) shouldNotBe null
            }
        }
    }

    test("an unused module with struct types compiles next to pointers to primitive types") {
        val src = """
%import lists
main {
    sub start() {
    }
}"""
        for (target in listOf(VMTarget(), LlvmTarget(8), LlvmTarget(4))) {
            withClue(target.name) {
                compileText(target, true, src, outputDir, writeAssembly = true) shouldNotBe null
            }
        }
    }

    test("consecutive increments of a typed pointer merge into one addition") {
        val src = """
main {
    sub start() {
        ^^uword @shared p = 1000
        p++
        p++
        p += 3
    }
}"""
        for (target in listOf(Cx16Target(), VMTarget())) {
            withClue(target.name) {
                compileText(target, false, src, outputDir, writeAssembly = false) shouldNotBe null
            }
        }
    }

    test("a block level breakpoint on the llvm targets compiles with a warning that it has no effect") {
        val src = """
main {
    %breakpoint
    sub start() {
    }
}"""
        for (target in listOf(LlvmTarget(8), LlvmTarget(4))) {
            withClue(target.name) {
                val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                val result = compileText(target, false, src, outputDir, errors, writeAssembly = true)!!
                errors.warnings.single() shouldContain "a block-level %breakpoint has no effect on the ${target.name} target"
                val ll = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".ll").toFile().readText()
                ll.lines().count { "llvm.debugtrap" in it } shouldBe 0
            }
        }
    }
})
