package prog8tests.codegeneration

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import prog8.code.target.Cx16Target
import prog8tests.helpers.ErrorReporterForTests
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

    test("the address of a split word array in a struct initializer is that of its lsb part") {
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        val machine = compileText(Cx16Target(), false, $$"""
            %option no_sysinit
            %launcher none
            %address $1000

            main {
                &ubyte poweroff = $f203
                &ubyte[2] results = $0200

                struct Node {
                    ubyte kind
                    uword data
                    ^^uword wptr
                }
                uword[4] words = [100, 200, 300, 400]

                sub start() {
                    ^^Node n = ^^Node : [1, &words, &words]
                    results[0] = if n.data == &words then 1 else 0
                    results[1] = lsb(words[2])
                    poweroff = 1
                }
            }
        """.trimIndent(), outputDir, errors)!!.simulate()
        machine.assertMemory(0x0200, 1)
        machine.assertMemory(0x0201, 44)
        errors.warnings.single() shouldContain "the value is the address of a split word array, but the ^^uword field 'wptr' reads its elements as consecutive words"
    }

    test("a constant index beyond 16-bit pointers through a struct field is an error, not a crash") {
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(Cx16Target(), false, """
            main {
                struct Buf {
                    ^^ubyte data
                }
                sub start() {
                    ^^Buf b = 4000
                    cx16.r0L = b.data[70000]
                }
            }""", outputDir, errors = errors, writeAssembly = false) shouldBe null
        errors.errors.single() shouldContain "index out of range for 16-bit pointers"
    }

    test("field writes through an indexed array of struct instances or an indexed struct pointer, and offsets from an array element's address") {
        for(newCodegen in listOf(false, true)) {
            for(optimize in listOf(false, true)) {
                val machine = compileText(Cx16Target(), optimize, $$"""
                    %option no_sysinit
                    %launcher none
                    %address $1000

                    main {
                        &ubyte poweroff = $f203
                        &ubyte[12] results = $0200

                        struct Point {
                            word x
                            word y
                            ubyte color
                        }
                        Point[4] points
                        ubyte[10] bytes = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]
                        uword[6] @nosplit nwords = [100, 101, 102, 103, 104, 105]

                        sub start() {
                            ubyte @shared j = 3
                            ^^Point p = &points[0]
                            points[j].color = 68
                            points[j].color++
                            results[0] = points[3].color
                            points[2].y = 5
                            p[2].y++
                            results[1] = lsb(points[2].y)
                            p[1].y = 77
                            results[2] = lsb(points[1].y)
                            p[1].color = 9
                            results[3] = points[1].color
                            points[3].color = 20
                            points[3].color++
                            results[4] = points[3].color
                            results[5] = @(&bytes[3] + 4)
                            @(&bytes[2] + 5) = 70
                            results[6] = bytes[7]
                            @(&bytes[2] + 5) += 2
                            results[7] = bytes[7]
                            poke(&bytes[1] + 2, 33)
                            results[8] = bytes[3]
                            results[9] = lsb(peekw(&nwords[2] + 4))
                            pokew(&nwords[1] + 2, 999)
                            results[10] = lsb(nwords[2])
                            results[11] = msb(nwords[2])
                            poweroff = 1
                        }
                    }
                """.trimIndent(), outputDir, newCodegen = newCodegen)!!.simulate()
                listOf(69, 6, 77, 9, 21, 7, 70, 72, 33, 104, 999 and 255, 999 shr 8).forEachIndexed { i, value ->
                    machine.assertMemory(0x0200 + i, value)
                }
            }
        }
    }

    test("sizeof of an array of struct instances is the size of the whole array") {
        val result = compileText(Cx16Target(), false, """
            main {
                struct Enemy {
                    ubyte xpos, ypos
                    uword health
                    bool elite
                }
                Enemy[3] squad
                sub start() {
                    cx16.r0 = sizeof(squad)
                    cx16.r1 = sizeof(Enemy)
                }
            }""", outputDir, writeAssembly = false)!!
        val values = result.compilerAst.entrypoint.statements.filterIsInstance<prog8.ast.statements.Assignment>()
            .map { (it.value as prog8.ast.expressions.NumericLiteral).number.toInt() }
        values shouldBe listOf(15, 5)
    }

    test("-newcodegen scales a variable index into an array of struct instances by a size that isn't a power of two") {
        for(optimize in listOf(false, true)) {
            val machine = compileText(Cx16Target(), optimize, $$"""
                %option no_sysinit
                %launcher none
                %address $1000

                main {
                    &ubyte poweroff = $f203
                    &ubyte[2] results = $0200

                    struct Enemy {
                        ubyte xpos, ypos
                        uword health
                        bool elite
                    }
                    Enemy[50] enemies

                    sub start() {
                        ubyte i
                        for i in 0 to 49
                            enemies[i].xpos = i + 40
                        ubyte @shared k = 45
                        results[0] = enemies[k].xpos
                        results[1] = k
                        poweroff = 1
                    }
                }
            """.trimIndent(), outputDir, newCodegen = true)!!.simulate(maxCycles = 100000)
            machine.assertMemory(0x0200, 85)
            machine.assertMemory(0x0201, 45)
        }
    }

    test("the address of an indexed pointer field, with a variable, constant, long and negative index") {
        for(newCodegen in listOf(false, true)) {
            for(optimize in listOf(false, true)) {
                val machine = compileText(Cx16Target(), optimize, $$"""
                    %option no_sysinit
                    %launcher none
                    %address $1000

                    main {
                        &ubyte poweroff = $f203
                        &ubyte[6] results = $0200

                        struct Buf {
                            ^^ubyte data
                            ^^uword words
                        }
                        ubyte[] storage = [10, 11, 12, 13, 14, 15, 16, 17]
                        uword[] @nosplit wstorage = [1000, 1001, 1002, 1003, 1004]
                        ubyte[16] space

                        sub start() {
                            ^^Buf b = &space
                            b.data = &storage
                            b.words = &wstorage
                            long @shared from = 3
                            ubyte @shared i = 3
                            word @shared w = -1
                            ^^ubyte direct = b.data
                            results[0] = @(&b.data[from])
                            results[1] = @(&direct[from])
                            results[2] = @(&b.data[2])
                            ^^uword q = &b.words[i]
                            results[3] = lsb(q^^)
                            b.words = &wstorage[2]
                            q = &b.words[w]
                            results[4] = lsb(q^^)
                            results[5] = @(&b.data[i])
                            poweroff = 1
                        }
                    }
                """.trimIndent(), outputDir, newCodegen = newCodegen)!!.simulate()
                listOf(13, 13, 12, 1003 and 255, 1001 and 255, 13).forEachIndexed { index, value ->
                    machine.assertMemory(0x0200 + index, value)
                }
            }
        }
    }

    test("@(&p) of a pointer variable in zeropage is the variable's own first byte, not the byte it points to") {
        for(optimize in listOf(false, true)) {
            val machine = compileText(Cx16Target(), optimize, $$"""
                %option no_sysinit
                %launcher none
                %address $1000

                main {
                    &ubyte poweroff = $f203
                    &ubyte[2] results = $0200
                    ubyte[] storage = [10, 11, 12]

                    sub start() {
                        ^^ubyte @zp p = &storage + 1
                        results[0] = @(&p) == lsb(p) as ubyte
                        results[1] = @(&p + 1) == msb(p) as ubyte
                        poweroff = 1
                    }
                }
            """.trimIndent(), outputDir)!!.simulate()
            machine.assertMemory(0x0200, 1)
            machine.assertMemory(0x0201, 1)
        }
    }
})
