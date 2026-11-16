package prog8tests.codegen.m68k

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import prog8.code.core.*
import prog8.code.target.Qemu68kTarget
import prog8.codegen.m68k.AsmGen
import prog8.intermediate.*
import prog8tests.helpers.DummyStringEncoder
import kotlin.io.path.readText

class TestM68kDataSection : FunSpec({

    val tempRoot = tempdir().toPath()

    fun dataLines(vararg variables: IRStStaticVariable): List<String> {
        val options = CompilationOptions.builder(Qemu68kTarget())
            .output(OutputType.RAW)
            .zeropage(ZeropageType.DONTUSE)
            .compilerVersion("test")
            .memtopAddress(0xffffu)
            .build()
        val st = IRSymbolTable()
        variables.forEach { st.add(it) }
        val program = IRProgram("test", st, options, DummyStringEncoder)
        program.options.outputDir = tempRoot
        val chunk = IRCodeChunk("test.start", null)
        chunk.instructions.add(IRInstructions.returnVoid())
        val sub = IRSubroutine("test.start", emptyList(), emptyList(), Position.DUMMY)
        sub.chunks.add(chunk)
        val block = IRBlock("test", false, IRBlock.Options(), Position.DUMMY)
        block.children.add(sub)
        program.blocks.add(block)
        program.linkChunks()
        tempRoot.toFile().deleteRecursively()
        tempRoot.toFile().mkdirs()
        AsmGen(program, Qemu68kTarget(), null).generate()
        return tempRoot.resolve("test.asm").readText().lines().map { it.substringBefore(';').trim() }
    }

    fun array(name: String, elementType: BaseDataType, length: Int) =
        IRStStaticVariable(name, DataType.arrayFor(elementType, Qemu68kTarget()), null, length.toUInt(), ZeropageWish.DONTCARE, 0u, false)

    fun lineAfter(lines: List<String>, label: String) = lines[lines.indexOf("$label:") + 1]

    test("an initialized array without values, as read from a .p8ir file with all zeros, gets all its bytes") {
        val lines = dataLines(
            array("test.bytes", BaseDataType.UBYTE, 4),
            array("test.words", BaseDataType.UWORD, 3),
            array("test.longs", BaseDataType.LONG, 2)
        )
        lineAfter(lines, "test.bytes") shouldBe "dcb.b  4,0"
        lineAfter(lines, "test.words") shouldBe "dcb.b  6,0"
        lineAfter(lines, "test.longs") shouldBe "dcb.b  8,0"
    }
})
