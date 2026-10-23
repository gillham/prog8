package prog8tests.vm

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
})
