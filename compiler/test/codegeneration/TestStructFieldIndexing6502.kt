package prog8tests.codegeneration

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import prog8.code.target.Cx16Target
import prog8tests.helpers.compileText
import prog8tests.helpers.simulate

class TestStructFieldIndexing6502 : FunSpec({
    val outputDir = tempdir().toPath()

    test("indexing a uword or pointer struct field reads and writes at the address it holds") {
        for(optimize in listOf(false, true)) {
            val machine = compileText(Cx16Target(), optimize, $$"""
                %option no_sysinit
                %launcher none
                %address $1000

                main {
                    &ubyte poweroff = $f203
                    &ubyte[7] results = $0200

                    struct Node {
                        ubyte kind
                        uword data
                        ^^ubyte bptr
                    }
                    ubyte[10] buf = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]

                    sub start() {
                        ^^Node n = ^^Node : [1, &buf, &buf]
                        ubyte @shared i = 4
                        ubyte @shared v = 50
                        results[0] = n.data[2]
                        results[1] = n.data[i]
                        results[2] = n.bptr[3]
                        n.data[5] = v
                        n.bptr[6] = v + 1
                        n.data[i] = 77
                        results[3] = n.kind
                        results[4] = buf[5]
                        results[5] = buf[6]
                        results[6] = buf[4]
                        poweroff = 1
                    }
                }
            """.trimIndent(), outputDir)!!.simulate()
            machine.assertMemory(0x0200, 2)
            machine.assertMemory(0x0201, 4)
            machine.assertMemory(0x0202, 3)
            machine.assertMemory(0x0203, 1)
            machine.assertMemory(0x0204, 50)
            machine.assertMemory(0x0205, 51)
            machine.assertMemory(0x0206, 77)
        }
    }
})
