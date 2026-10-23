package prog8tests.vm

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import prog8.code.target.VMTarget
import prog8.intermediate.IRFileReader
import prog8.vm.VmRunner
import prog8.vm.VmVariableAllocator
import prog8tests.helpers.compileText
import kotlin.io.path.readText

class TestVmIndirectCall: FunSpec({

    test("call() through an address runs the routine and returns its value") {
        val outputDir = tempdir().toPath()
        val src = """
%zeropage basicsafe
%option no_sysinit

main {
    ubyte @shared counter
    uword @shared wordresult
    uword @shared byteresult
    uword @shared voidresult

    sub start() {
        long @shared a1 = &answer
        long @shared a2 = &bump
        long @shared a3 = &byteval
        wordresult = call(a1)
        void call(a2)
        void call(a2)
        voidresult = call(a2)
        byteresult = call(a3)
    }

    sub answer() -> uword {
        return 1234
    }

    sub bump() {
        counter++
    }

    sub byteval() -> ubyte {
        return 77
    }
}"""
        for(optimize in listOf(false, true)) {
            val result = compileText(VMTarget(), optimize, src, outputDir, writeAssembly = true)!!
            val irSource = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir").readText()
            val irProgram = IRFileReader().read(irSource)
            val allocations = VmVariableAllocator(irProgram.st, irProgram.encoding, irProgram.options.compTarget).allocations
            VmRunner().runAndTestProgram(irSource, quiet = true) { vm ->
                vm.memory.getUB(allocations["main.counter"]!!) shouldBe 3u
                vm.memory.getUW(allocations["main.wordresult"]!!) shouldBe 1234u
                vm.memory.getUW(allocations["main.byteresult"]!!) shouldBe 77u
                vm.memory.getUW(allocations["main.voidresult"]!!) shouldBe 0u
            }
        }
    }
})
