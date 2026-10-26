package prog8tests.vm

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import prog8.code.target.VMTarget
import prog8.intermediate.IRFileReader
import prog8.vm.VmRunner
import prog8.vm.VmVariableAllocator
import prog8tests.helpers.compileText
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.io.path.writeText

class TestVmLibraryRoutines: FunSpec({

    test("library routines that don't depend on status flags the IR leaves undefined") {
        val outputDir = tempdir().toPath()
        val textFile = outputDir.resolve("lines.txt")
        textFile.writeText("x".repeat(300) + "\nlast")
        val src = """
%import diskio
%import math
%import strings
%zeropage basicsafe
%option no_sysinit

main {
    ubyte[400] line
    ubyte[5] @shared lengths
    ubyte[5] @shared statuses
    bool @shared found
    bool @shared notfound
    uword @shared crc

    sub start() {
        found = strings.contains("hello", 'l')
        notfound = strings.contains("hello", 'z')
        crc = math.crc16("123456789", 9, 0, 0)
        if diskio.f_open("${textFile.invariantSeparatorsPathString}") {
            ubyte i
            for i in 0 to 3
                lengths[i], statuses[i] = diskio.f_readline(&line)
            diskio.f_close()
        }
    }
}"""
        for(optimize in listOf(false, true)) {
            val result = compileText(VMTarget(), optimize, src, outputDir, writeAssembly = true)!!
            val irSource = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir").readText()
            val irProgram = IRFileReader().read(irSource)
            val allocations = VmVariableAllocator(irProgram.st, irProgram.encoding, irProgram.options.compTarget).allocations
            VmRunner().runAndTestProgram(irSource, quiet = true) { vm ->
                vm.memory.getUB(allocations["main.found"]!!) shouldBe 1u
                vm.memory.getUB(allocations["main.notfound"]!!) shouldBe 0u
                vm.memory.getUW(allocations["main.crc"]!!) shouldBe 0x31c3u
                val lengths = allocations["main.lengths"]!!
                val statuses = allocations["main.statuses"]!!
                (0..3).map { vm.memory.getUB(lengths + it.toUInt()).toInt() } shouldBe listOf(255, 45, 4, 0)
                (0..3).map { vm.memory.getUB(statuses + it.toUInt()).toInt() } shouldBe listOf(0, 0, 0x40, 0x40)
            }
        }
    }
})
