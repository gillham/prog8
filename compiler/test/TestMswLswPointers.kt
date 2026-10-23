package prog8tests.compiler

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.instanceOf
import prog8.ast.expressions.Expression
import prog8.ast.expressions.FunctionCallExpression
import prog8.ast.expressions.IdentifierReference
import prog8.ast.expressions.NumericLiteral
import prog8.ast.expressions.TypecastExpression
import prog8.ast.statements.Assignment
import prog8.code.core.BaseDataType
import prog8.code.target.Cx16Target
import prog8.code.target.Qemu68kTarget
import prog8.code.target.VMTarget
import prog8.compiler.CompilationResult
import prog8.intermediate.IRFileReader
import prog8.vm.VmRunner
import prog8.vm.VmVariableAllocator
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.compileText
import kotlin.io.path.readText


class TestMswLswPointers: FunSpec({

    val outputDir = tempdir().toPath()

    val src = $$"""
main {
    ubyte[8] @shared buffer
    str @shared name = "hello"

    sub start() {
        ^^ubyte @shared p = $12345
        uword @shared hi
        uword @shared lo
        uword @shared lonext
        uword @shared arrayhi
        uword @shared arraylo
        uword @shared stringhi
        hi = msw(p)
        lo = lsw(p)
        lonext = lsw(p + 1)
        arrayhi = msw(buffer)
        arraylo = lsw(buffer)
        stringhi = msw(name)
    }
}"""

    fun assignedValue(result: CompilationResult, name: String): Expression =
        result.compilerAst.entrypoint.statements.filterIsInstance<Assignment>()
            .single { it.target.identifier?.nameInSource == listOf(name) }.value

    test("msw and lsw of a pointer above 64 KiB give its high and low word on the virtual machine") {
        for(optimize in listOf(false, true)) {
            withClue("optimize=$optimize") {
                val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                val result = compileText(VMTarget(), optimize, src, outputDir, errors, writeAssembly = true)!!
                val virtfile = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir")
                val irProgram = IRFileReader().read(virtfile)
                val allocations = VmVariableAllocator(irProgram.st, irProgram.encoding, irProgram.options.compTarget).allocations
                VmRunner().runAndTestProgram(virtfile.readText(), true) { vm ->
                    fun word(name: String) = vm.memory.getUW(allocations.getValue("main.start.$name"))
                    word("hi") shouldBe 0x0001u
                    word("lo") shouldBe 0x2345u
                    word("lonext") shouldBe 0x2346u
                    word("arraylo") shouldBe (allocations.getValue("main.buffer") and 0xffffu).toUShort()
                    word("arrayhi") shouldBe (allocations.getValue("main.buffer") shr 16).toUShort()
                    word("stringhi") shouldBe (allocations.getValue("main.name") shr 16).toUShort()
                }
            }
        }
    }

    test("msw and lsw of a pointer take the word from the address as a long on m68k") {
        val result = compileText(Qemu68kTarget(), false, src, outputDir, writeAssembly = false)!!
        for(name in listOf("hi", "lo", "lonext", "arrayhi", "arraylo", "stringhi")) {
            withClue(name) {
                val call = assignedValue(result, name) as FunctionCallExpression
                val arg = call.args.single() as TypecastExpression
                arg.type.base shouldBe BaseDataType.LONG
            }
        }
    }

    test("msw of a pointer folds to 0 and lsw to the pointer itself with 2-byte pointers") {
        val result = compileText(Cx16Target(), false, src.replace($$"$12345", $$"$1234"), outputDir, writeAssembly = false)!!
        (assignedValue(result, "hi") as NumericLiteral).number shouldBe 0.0
        assignedValue(result, "lo") shouldBe instanceOf<IdentifierReference>()
        (assignedValue(result, "arrayhi") as NumericLiteral).number shouldBe 0.0
        (assignedValue(result, "stringhi") as NumericLiteral).number shouldBe 0.0
    }
})
