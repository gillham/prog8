package prog8tests.vm

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import prog8.ast.expressions.FunctionCallExpression
import prog8.ast.expressions.TypecastExpression
import prog8.ast.statements.Assignment
import prog8.code.target.LlvmTarget
import prog8.code.target.VMTarget
import prog8.intermediate.IRFileReader
import prog8.vm.VmRunner
import prog8.vm.VmVariableAllocator
import prog8tests.helpers.compileText
import kotlin.io.path.readText

class TestPointerOffsetCasts: FunSpec({

    val src = """
%zeropage basicsafe
%option no_sysinit

main {
    struct Thing {
        ubyte a
        uword b
        long c
    }

    long[13] @shared offsets
    uword @shared narrowed
    ubyte @shared narrowedByte

    sub start() {
        uword @shared u = $1000
        ubyte @shared i = 3
        byte @shared sb = -2
        uword @shared wi = 5
        word @shared sw = -4
        long base = u as long

        ^^uword pw = (u as ^^uword) + i
        offsets[0] = (pw as long) - base
        ^^ubyte pb = (u as ^^ubyte) + i
        offsets[1] = (pb as long) - base
        ^^long pl = (u as ^^long) + i
        offsets[2] = (pl as long) - base
        ^^Thing pt = (u as ^^Thing) + i
        offsets[3] = (pt as long) - base
        pw = (u as ^^uword) + sb
        offsets[4] = (pw as long) - base
        pw = (u as ^^uword) + wi
        offsets[5] = (pw as long) - base
        pw = (u as ^^uword) + sw
        offsets[6] = (pw as long) - base
        pw = (u as ^^uword) - i
        offsets[7] = (pw as long) - base
        pw = u as ^^uword
        pw += i
        offsets[8] = (pw as long) - base
        pw -= i
        offsets[9] = (pw as long) - base
        ^^uword pv = u as ^^uword
        pw = pv + i
        offsets[10] = (pw as long) - base
        offsets[11] = ((pv + i) as long) - base
        offsets[12] = (((u as ^^long) + wi) as long) - base
        narrowed = ((u as ^^ubyte) + i) as uword
        narrowedByte = ((u as ^^ubyte) + i) as ubyte
    }
}"""

    test("pointer plus a non-constant offset scales the offset and keeps consistent register types in the VM") {
        val outputDir = tempdir().toPath()
        for(optimize in listOf(false, true)) {
            val result = compileText(VMTarget(), optimize, src, outputDir, writeAssembly = true)!!
            val irSource = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir").readText()
            val irProgram = IRFileReader().read(irSource)
            val allocations = VmVariableAllocator(irProgram.st, irProgram.encoding, irProgram.options.compTarget).allocations
            VmRunner().runAndTestProgram(irSource, quiet = true) { vm ->
                val offsetsAddress = allocations["main.offsets"]!!
                val offsets = List(13) { vm.memory.getUL(offsetsAddress + 4u*it.toUInt()).toInt() }
                offsets shouldBe listOf(6, 3, 12, 21, -4, 10, -8, -6, 6, 0, 6, 6, 20)
                vm.memory.getUW(allocations["main.narrowed"]!!) shouldBe 0x1003u
                vm.memory.getUB(allocations["main.narrowedByte"]!!) shouldBe 0x03u
            }
        }
    }

    test("pointer plus a non-constant offset compiles for the llvm targets") {
        val outputDir = tempdir().toPath()
        for(target in listOf(LlvmTarget(4), LlvmTarget(8))) {
            for(optimize in listOf(false, true))
                compileText(target, optimize, src, outputDir, writeAssembly = true) shouldNotBe null
        }
    }

    val narrowingSrc = """
%zeropage basicsafe
%option no_sysinit

main {
    ubyte @shared viaBytePointer
    ubyte @shared viaLongPointer
    uword @shared narrowed
    long @shared derefed
    bool @shared indexed

    sub start() {
        ^^ubyte @shared bp = $12345
        ^^long @shared lp = $12345
        ^^long @shared hp = $12340
        ^^bool @shared fp = $12350
        uword @shared i = 2
        @($2345) = 11
        @($12345) = 22
        pokel($12340, $11223344)
        pokebool($12352, true)
        viaBytePointer = @(bp as uword)
        viaLongPointer = peek(lp as uword)
        narrowed = lp as uword
        derefed = hp^^
        indexed = fp[i]
    }
}"""

    test("a pointer cast to uword truncates the address in the VM, and implicit address casts keep the whole address") {
        val outputDir = tempdir().toPath()
        for(optimize in listOf(false, true)) {
            val result = compileText(VMTarget(), optimize, narrowingSrc, outputDir, writeAssembly = true)!!
            val irSource = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir").readText()
            val irProgram = IRFileReader().read(irSource)
            val allocations = VmVariableAllocator(irProgram.st, irProgram.encoding, irProgram.options.compTarget).allocations
            VmRunner().runAndTestProgram(irSource, quiet = true) { vm ->
                vm.memory.getUB(allocations["main.viaBytePointer"]!!) shouldBe 11u
                vm.memory.getUB(allocations["main.viaLongPointer"]!!) shouldBe 11u
                vm.memory.getUW(allocations["main.narrowed"]!!) shouldBe 0x2345u
                vm.memory.getUL(allocations["main.derefed"]!!) shouldBe 0x11223344u
                vm.memory.getUB(allocations["main.indexed"]!!) shouldBe 1u
            }
        }
    }

    test("a typed pointer passed to a peek builtin is cast to the address integer type of the target, not to uword") {
        val outputDir = tempdir().toPath()
        for(target in listOf(VMTarget(), LlvmTarget(4), LlvmTarget(8))) {
            val result = compileText(target, false, narrowingSrc, outputDir, writeAssembly = false)!!
            val deref = result.compilerAst.entrypoint.statements.filterIsInstance<Assignment>().single { it.target.identifier?.nameInSource == listOf("derefed") }
            val peek = deref.value.shouldBeInstanceOf<FunctionCallExpression>()
            peek.target.nameInSource shouldBe listOf("peekl")
            peek.args.single().shouldBeInstanceOf<TypecastExpression>().type shouldBe target.pointerType
        }
    }

    test("an 8-byte pointer cast to long truncates the address") {
        val src = """
%zeropage basicsafe
%option no_sysinit

main {
    long @shared narrowed

    sub start() {
        uquad @shared address = $123456789
        ^^ubyte @shared bp = address as ^^ubyte
        narrowed = bp as long
    }
}"""
        val outputDir = tempdir().toPath()
        val result = compileText(LlvmTarget(8), false, src, outputDir, writeAssembly = true, assemble = false)!!
        val irLines = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir").readText().lines()
        irLines.count { it.trim().startsWith("lsigl.q ") } shouldBe 1
    }
})
