package prog8tests.vm

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import prog8.code.target.VMTarget
import prog8.intermediate.IRFileReader
import prog8.vm.VmRunner
import prog8.vm.VmVariableAllocator
import prog8tests.helpers.compileText
import kotlin.io.path.readBytes
import kotlin.io.path.readText

class TestVmLargeData: FunSpec({

    test("library routines return and use buffer addresses above 64 KiB") {
        val outputDir = tempdir().toPath()
        val dataFile = outputDir.resolve("largedata.bin")
        val src = """
%import conv
%import floats
%import diskio
%zeropage basicsafe
%option no_sysinit

main {
    ubyte[60000] @shared big1
    ubyte[60000] @shared big2
    ^^ubyte @shared longstr
    ^^ubyte @shared floatstr
    ^^ubyte @shared highbuffer

    sub start() {
        longstr = conv.str_l(-123456789)
        floatstr = floats.tostr(3.25)
        highbuffer = &big2[59000]
        highbuffer[0] = 'w'
        highbuffer[1] = 'x'
        highbuffer[2] = 'y'
        highbuffer[3] = 'z'
        if diskio.f_open_w("$dataFile") {
            void diskio.f_write(highbuffer, 4)
            diskio.f_close_w()
        }
    }
}"""
        for(optimize in listOf(false, true)) {
            val result = compileText(VMTarget(), optimize, src, outputDir, writeAssembly = true)!!
            val irSource = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir").readText()
            val irProgram = IRFileReader().read(irSource)
            val allocations = VmVariableAllocator(irProgram.st, irProgram.encoding, irProgram.options.compTarget).allocations
            VmRunner().runAndTestProgram(irSource, quiet = true) { vm ->
                val longstr = vm.memory.getUL(allocations["main.longstr"]!!)
                val floatstr = vm.memory.getUL(allocations["main.floatstr"]!!)
                val highbuffer = vm.memory.getUL(allocations["main.highbuffer"]!!)
                longstr.toLong() shouldBeGreaterThan 0xffffL
                floatstr.toLong() shouldBeGreaterThan 0xffffL
                highbuffer.toLong() shouldBeGreaterThan 0xffffL
                vm.memory.getString(longstr) shouldBe "-123456789"
                vm.memory.getString(floatstr) shouldBe "3.25"
            }
            dataFile.readBytes().decodeToString() shouldBe "wxyz"
        }
    }
})
