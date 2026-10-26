package prog8tests.vm

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import prog8.code.target.VMTarget
import prog8.intermediate.IRFileReader
import prog8.vm.VirtualMachine
import prog8.vm.VmRunner
import prog8.vm.VmVariableAllocator
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.compileText
import kotlin.io.path.readText
import kotlin.math.pow

/** The Kotlin type of an expected value selects the memory width and signedness that is read back. */
private class QuadVmResult(private val vm: VirtualMachine, private val allocations: Map<String, UInt>, val freeMemory: UInt) {
    private fun read(address: UInt, like: Any): Any = when(like) {
        is Long -> vm.memory.getSQ(address)
        is ULong -> vm.memory.getUQ(address)
        is Int -> vm.memory.getSL(address)
        is Short -> vm.memory.getSW(address)
        is UShort -> vm.memory.getUW(address)
        is Byte -> vm.memory.getSB(address)
        is UByte -> vm.memory.getUB(address)
        is Boolean -> when(val raw = vm.memory.getUB(address).toInt()) {
            0 -> false
            1 -> true
            else -> raw
        }
        is Double -> vm.memory.getFloat(address)
        else -> throw IllegalArgumentException("unsupported expected value type ${like::class}")
    }

    private fun readVariable(name: String, like: Any): Any {
        val address = allocations["main.$name"] ?: fail("no variable main.$name")
        if(like !is List<*>)
            return read(address, like)
        val element = like.first()!!
        val elementSize = when(element) {
            is Long, is ULong -> 8u
            else -> throw IllegalArgumentException("unsupported array element type ${element::class}")
        }
        return like.indices.map { read(address + elementSize * it.toUInt(), element) }
    }

    fun shouldHave(vararg expected: Pair<String, Any>) {
        expected.associate { (name, value) -> name to readVariable(name, value) } shouldBe expected.toMap()
    }

    fun memoryShouldHave(vararg expected: Pair<UInt, Any>) {
        expected.associate { (address, value) -> address to read(address, value) } shouldBe expected.toMap()
    }
}

class TestQuadVirtualMachine: FunSpec({
    val outputDir = tempdir().toPath()

    fun runOptimizedAndUnoptimized(src: String, check: QuadVmResult.() -> Unit) {
        // soft assertions, so that a failure without optimizations doesn't hide the result of the optimized run
        assertSoftly {
            for(optimize in listOf(false, true)) {
                withClue("optimize=$optimize") {
                    val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                    val result = compileText(VMTarget(), optimize, src, outputDir, errors, writeAssembly = true)
                        ?: fail("compilation failed: ${errors.errors}")
                    val virtfile = result.compilationOptions.outputDir.resolve(result.compilerAst.name + ".p8ir")
                    val irProgram = IRFileReader().read(virtfile)
                    val allocator = VmVariableAllocator(irProgram.st, irProgram.encoding, irProgram.options.compTarget)
                    VmRunner().runAndTestProgram(virtfile.readText(), true) { vm ->
                        QuadVmResult(vm, allocator.allocations, allocator.freeMem).check()
                    }
                }
            }
        }
    }

    // values between 2^31 and 2^32 are written as 9-digit hex ($0_ffff_ffff): shorter literals still parse as a wrapped long

    test("arithmetic on quad and uquad variables uses all 64 bits") {
        val src = $$"""
main {
    quad @shared a = 5000000000
    quad @shared b = -30000000007
    uquad @shared ua = $ffff_ffff_0000_0010
    uquad @shared ub = $1_0000_0003
    quad @shared maxq = 9223372036854775807
    quad @shared minq = -9223372036854775808
    uquad @shared maxu = $ffff_ffff_ffff_ffff
    quad @shared sneg = -10
    uquad @shared upat = $ffff_ffff_ffff_fff6
    quad @shared three = 3
    uquad @shared uthree = 3
    quad @shared incq = $0_ffff_ffff
    uquad @shared decu = $1_0000_0000

    quad @shared r_add
    quad @shared r_sub
    quad @shared r_mul
    quad @shared r_div
    quad @shared r_mod
    uquad @shared ur_add
    uquad @shared ur_sub
    uquad @shared ur_mul
    uquad @shared ur_div
    uquad @shared ur_mod
    quad @shared wrap_max
    quad @shared wrap_min
    quad @shared neg_min
    quad @shared neg_a
    uquad @shared wrap_umax
    uquad @shared wrap_under
    quad @shared sdiv_var
    quad @shared smod_var
    uquad @shared udiv_var
    uquad @shared umod_var
    quad @shared sdiv_const
    quad @shared smod_const
    uquad @shared udiv_const
    uquad @shared umod_const
    quad @shared sdiv_pow2
    quad @shared smod_pow2
    uquad @shared udiv_pow2
    uquad @shared umod_pow2
    quad @shared sdiv_big
    quad @shared smod_big
    uquad @shared udiv_big
    uquad @shared umod_big

    sub start() {
        r_add = a + b
        r_sub = b - a
        r_mul = a * b
        r_div = b / a
        r_mod = b % a
        ur_add = ua + ub
        ur_sub = ub - ua
        ur_mul = ua * ub
        ur_div = ua / ub
        ur_mod = ua % ub
        wrap_max = maxq + 1
        wrap_min = minq - 1
        neg_min = -minq
        neg_a = -a
        wrap_umax = maxu + 1
        wrap_under = wrap_umax - 1
        sdiv_var = sneg / three
        smod_var = sneg % three
        udiv_var = upat / uthree
        umod_var = upat % uthree
        sdiv_const = sneg / 3
        smod_const = sneg % 7
        udiv_const = upat / 3
        umod_const = upat % 7
        sdiv_pow2 = sneg / 4
        smod_pow2 = sneg % 4
        udiv_pow2 = upat / 4
        umod_pow2 = upat % 4
        sdiv_big = b / 7000000000
        smod_big = b % 7000000000
        udiv_big = upat / ($1_0000_0000 as uquad)
        umod_big = upat % ($1_0000_0000 as uquad)
        incq++
        decu--
    }
}"""
        val a = 5_000_000_000L
        val b = -30_000_000_007L
        val ua = 0xffff_ffff_0000_0010uL
        val ub = 0x1_0000_0003uL
        val upat = 0xffff_ffff_ffff_fff6uL
        runOptimizedAndUnoptimized(src) {
            shouldHave(
                "r_add" to a + b,
                "r_sub" to b - a,
                "r_mul" to a * b,
                "r_div" to -6L,
                "r_mod" to -7L,
                "ur_add" to 0x13uL,
                "ur_sub" to ub - ua,
                "ur_mul" to ua * ub,
                "ur_div" to ua / ub,
                "ur_mod" to ua % ub,
                "wrap_max" to Long.MIN_VALUE,
                "wrap_min" to Long.MAX_VALUE,
                "neg_min" to Long.MIN_VALUE,
                "neg_a" to -5_000_000_000L,
                "wrap_umax" to 0uL,
                "wrap_under" to ULong.MAX_VALUE,
                "sdiv_var" to -3L,
                "smod_var" to -1L,
                "udiv_var" to 0x5555_5555_5555_5552uL,
                "umod_var" to 0uL,
                "sdiv_const" to -3L,
                "smod_const" to -3L,
                "udiv_const" to 0x5555_5555_5555_5552uL,
                "umod_const" to 6uL,
                "sdiv_pow2" to -2L,
                "smod_pow2" to -2L,
                "udiv_pow2" to upat / 4uL,
                "umod_pow2" to 2uL,
                "sdiv_big" to -4L,
                "smod_big" to -2_000_000_007L,
                "udiv_big" to 0xffff_ffffuL,
                "umod_big" to 0xffff_fff6uL,
                "incq" to 0x1_0000_0000L,
                "decu" to 0xffff_ffffuL,
            )
        }
    }

    test("compound assignments on quad variables and array elements") {
        val src = $$"""
main {
    quad @shared q1 = 5000000000
    quad @shared q2 = -5000000000
    quad @shared q3 = 9000000000
    quad @shared q4 = -9000000001
    quad @shared q5 = -90000000001
    uquad @shared u1 = $ffff_ffff_0000_0000
    uquad @shared u2 = $1_0000_0000
    uquad @shared u3 = 3
    uquad @shared u4 = $ffff_ffff_ffff_fff6
    uquad @shared u5 = $ffff_ffff_ffff_fff6
    quad @shared qv = 30000000000
    uquad @shared uv = 7
    quad[] @shared qa = [1, -5000000000, 7000000000, 3, -90000000001]
    uquad[] @shared ua = [1, $ffff_ffff_ffff_fff0, $8000_0000_0000_0000, 100]
    uquad[] @shared ub = [$ffff_ffff_ffff_ffff, $1234_5678_9abc_def0, $8000_0000_0000_0001, 1]
    quad[] @shared qb = [-8000000000, 3]
    ubyte @shared i = 1
    ubyte @shared j = 2
    ubyte @shared k = 4
    ubyte @shared n = 36

    sub start() {
        q1 += qv
        q2 -= qv
        q3 *= -3
        q4 /= 4
        q5 %= qv
        u1 += $2_0000_0001 as uquad
        u2 -= 1
        u3 *= $5555_5555_5555_5555 as uquad
        u4 /= uv
        u5 %= 7

        qa[i] += qv
        qa[j] -= 9000000000
        qa[3] *= qv
        qa[0] /= -1
        qa[k] %= qv
        qa[i] %= 1000000007
        ua[i] /= 3
        ua[j] %= uv
        ua[3] *= $1_0000_0000 as uquad
        ua[0] -= 2

        ub[0] &= $ffff_0000_ffff_0000
        ub[i] |= $0f0f_0000_0000_0000
        ub[j] ^= ub[0]
        ub[3] <<= 63
        qb[0] >>= n
        qb[i] <<= n
    }
}"""
        val qv = 30_000_000_000L
        runOptimizedAndUnoptimized(src) {
            shouldHave(
                "q1" to 35_000_000_000L,
                "q2" to -35_000_000_000L,
                "q3" to -27_000_000_000L,
                "q4" to -2_250_000_000L,
                "q5" to -1L,
                "u1" to 0x1_0000_0001uL,
                "u2" to 0xffff_ffffuL,
                "u3" to ULong.MAX_VALUE,
                "u4" to 0xffff_ffff_ffff_fff6uL / 7uL,
                "u5" to 6uL,
                "qa" to listOf(-1L, (qv - 5_000_000_000L) % 1_000_000_007L, -2_000_000_000L, 3 * qv, -1L),
                "ua" to listOf(ULong.MAX_VALUE, 0x5555_5555_5555_5550uL, 1uL, 100uL shl 32),
                "ub" to listOf(0xffff_0000_ffff_0000uL, 0x1f3f_5678_9abc_def0uL, 0x7fff_0000_ffff_0001uL, 0x8000_0000_0000_0000uL),
                "qb" to listOf(-1L, 3L shl 36),
            )
        }
    }

    test("bitwise operators and shifts on 64-bit values") {
        val src = $$"""
main {
    uquad @shared m = $ffff_ffff_0000_0000
    uquad @shared v = $1234_5678_9abc_def0
    quad @shared neg = -8000000000
    quad @shared pos = $4000_0000_8000_0001
    ubyte @shared n1 = 1
    ubyte @shared n31 = 31
    ubyte @shared n32 = 32
    ubyte @shared n40 = 40
    ubyte @shared n63 = 63

    uquad @shared r_and
    uquad @shared r_or
    uquad @shared r_xor
    uquad @shared r_inv
    quad @shared q_inv
    quad @shared q_and
    uquad @shared r_andc
    uquad @shared r_orc
    uquad @shared r_xorc

    uquad @shared ushl_c1
    uquad @shared ushl_c31
    uquad @shared ushl_c32
    uquad @shared ushl_c40
    uquad @shared ushl_c63
    uquad @shared ushr_c1
    uquad @shared ushr_c31
    uquad @shared ushr_c32
    uquad @shared ushr_c40
    uquad @shared ushr_c63
    quad @shared sshr_c1
    quad @shared sshr_c31
    quad @shared sshr_c32
    quad @shared sshr_c40
    quad @shared sshr_c63
    quad @shared sshl_c1
    quad @shared sshl_c40

    uquad @shared ushl_v1
    uquad @shared ushl_v31
    uquad @shared ushl_v32
    uquad @shared ushl_v40
    uquad @shared ushl_v63
    uquad @shared ushr_v1
    uquad @shared ushr_v31
    uquad @shared ushr_v32
    uquad @shared ushr_v40
    uquad @shared ushr_v63
    quad @shared sshr_v1
    quad @shared sshr_v31
    quad @shared sshr_v32
    quad @shared sshr_v40
    quad @shared sshr_v63
    quad @shared pshr_v40
    quad @shared sshl_v32

    uquad @shared ca = $f0f0_f0f0_f0f0_f0f0
    uquad @shared co = $0000_0001_0000_0000
    uquad @shared cx = $ffff_ffff_ffff_ffff
    uquad @shared cl = 1
    uquad @shared cr = $8000_0000_0000_0000
    quad @shared csr = -4294967296

    sub start() {
        r_and = v & m
        r_or = v | m
        r_xor = v ^ m
        r_inv = ~v
        q_inv = ~neg as quad
        q_and = neg & pos
        r_andc = v & $0000_ffff_ffff_0000
        r_orc = v | $8000_0000_0000_0001
        r_xorc = v ^ $ffff_ffff_ffff_ffff

        ushl_c1 = v << 1
        ushl_c31 = v << 31
        ushl_c32 = v << 32
        ushl_c40 = v << 40
        ushl_c63 = v << 63
        ushr_c1 = m >> 1
        ushr_c31 = m >> 31
        ushr_c32 = m >> 32
        ushr_c40 = m >> 40
        ushr_c63 = m >> 63
        sshr_c1 = neg >> 1
        sshr_c31 = neg >> 31
        sshr_c32 = neg >> 32
        sshr_c40 = neg >> 40
        sshr_c63 = neg >> 63
        sshl_c1 = neg << 1
        sshl_c40 = pos << 40

        ushl_v1 = v << n1
        ushl_v31 = v << n31
        ushl_v32 = v << n32
        ushl_v40 = v << n40
        ushl_v63 = v << n63
        ushr_v1 = m >> n1
        ushr_v31 = m >> n31
        ushr_v32 = m >> n32
        ushr_v40 = m >> n40
        ushr_v63 = m >> n63
        sshr_v1 = neg >> n1
        sshr_v31 = neg >> n31
        sshr_v32 = neg >> n32
        sshr_v40 = neg >> n40
        sshr_v63 = neg >> n63
        pshr_v40 = pos >> n40
        sshl_v32 = pos << n32

        ca &= $0f0f_0f0f_ffff_0000
        co |= m
        cx ^= v
        cl <<= n63
        cr >>= n40
        csr >>= n1
    }
}"""
        val m = 0xffff_ffff_0000_0000uL
        val v = 0x1234_5678_9abc_def0uL
        val neg = -8_000_000_000L
        val pos = 0x4000_0000_8000_0001L
        val shifts = listOf(1, 31, 32, 40, 63).flatMap { n ->
            listOf("c", "v").flatMap { kind ->
                listOf("ushl_$kind$n" to (v shl n), "ushr_$kind$n" to (m shr n), "sshr_$kind$n" to (neg shr n))
            }
        }
        runOptimizedAndUnoptimized(src) {
            shouldHave(
                "r_and" to 0x1234_5678_0000_0000uL,
                "r_or" to 0xffff_ffff_9abc_def0uL,
                "r_xor" to 0xedcb_a987_9abc_def0uL,
                "r_inv" to 0xedcb_a987_6543_210fuL,
                "q_inv" to 7_999_999_999L,
                "q_and" to (neg and pos),
                "r_andc" to 0x0000_5678_9abc_0000uL,
                "r_orc" to 0x9234_5678_9abc_def1uL,
                "r_xorc" to 0xedcb_a987_6543_210fuL,
                "sshl_c1" to -16_000_000_000L,
                "sshl_c40" to (pos shl 40),
                "pshr_v40" to (pos shr 40),
                "sshl_v32" to (pos shl 32),
                "ca" to 0x0000_0000_f0f0_0000uL,
                "co" to 0xffff_ffff_0000_0000uL,
                "cx" to 0xedcb_a987_6543_210fuL,
                "cl" to 0x8000_0000_0000_0000uL,
                "cr" to 0x80_0000uL,
                "csr" to -2_147_483_648L,
                *shifts.toTypedArray(),
            )
        }
    }

    test("smaller integer types widen to quad and uquad with the right sign") {
        val src = $$"""
main {
    byte @shared bm1 = -1
    byte @shared bm100 = -100
    ubyte @shared ub255 = 255
    word @shared wm2 = -2
    uword @shared uw = 65535
    long @shared lm7 = -7
    long @shared lmin = -2147483647
    long @shared lmax = 2147483647
    long @shared lwrap

    quad @shared q_b
    quad @shared q_ub
    quad @shared q_w
    quad @shared q_uw
    quad @shared q_l
    quad @shared q_lmin
    quad @shared q_lmax
    quad @shared q_lwrap
    quad @shared qc_b
    quad @shared qc_ub
    quad @shared qc_w
    quad @shared qc_uw
    quad @shared qc_l
    uquad @shared u_ub
    uquad @shared u_uw
    uquad @shared uc_b
    uquad @shared uc_ub
    uquad @shared uc_w
    uquad @shared uc_uw
    uquad @shared uc_l
    uquad @shared uc_lmax
    quad @shared mix_b = 5000000000
    quad @shared mix_w = 5000000000
    quad @shared mix_l = -5000000000
    quad @shared mix_ub = 5000000000
    uquad @shared mixu_ub = $ffff_ffff_ffff_ff00
    uquad @shared mixu_uw = $0000_0001_ffff_ffff
    quad @shared mixq_bin
    quad @shared sumq
    uquad @shared u_from_q
    quad @shared q_from_u

    sub start() {
        q_b = bm1
        q_ub = ub255
        q_w = wm2
        q_uw = uw
        q_l = lm7
        q_lmin = lmin
        q_lmax = lmax
        lwrap = lmax + 1
        q_lwrap = lwrap
        qc_b = bm100 as quad
        qc_ub = ub255 as quad
        qc_w = wm2 as quad
        qc_uw = uw as quad
        qc_l = lm7 as quad
        u_ub = ub255
        u_uw = uw
        uc_b = bm1 as uquad
        uc_ub = ub255 as uquad
        uc_w = wm2 as uquad
        uc_uw = uw as uquad
        uc_l = lm7 as uquad
        uc_lmax = lmax as uquad
        mix_b = mix_b + bm1
        mix_w = mix_w + wm2
        mix_l = mix_l - lm7
        mix_ub = mix_ub + ub255
        mixu_ub = mixu_ub + ub255
        mixu_uw = mixu_uw + uw
        mixq_bin = q_b * bm100
        sumq = (lmax as quad) + lmax
        u_from_q = q_b as uquad
        q_from_u = uc_l as quad
    }
}"""
        runOptimizedAndUnoptimized(src) {
            shouldHave(
                "q_b" to -1L,
                "q_ub" to 255L,
                "q_w" to -2L,
                "q_uw" to 65535L,
                "q_l" to -7L,
                "q_lmin" to -2_147_483_647L,
                "q_lmax" to 2_147_483_647L,
                "q_lwrap" to -2_147_483_648L,
                "qc_b" to -100L,
                "qc_ub" to 255L,
                "qc_w" to -2L,
                "qc_uw" to 65535L,
                "qc_l" to -7L,
                "u_ub" to 255uL,
                "u_uw" to 65535uL,
                "uc_b" to ULong.MAX_VALUE,
                "uc_ub" to 255uL,
                "uc_w" to 0xffff_ffff_ffff_fffeuL,
                "uc_uw" to 65535uL,
                "uc_l" to 0xffff_ffff_ffff_fff9uL,
                "uc_lmax" to 2_147_483_647uL,
                "mix_b" to 4_999_999_999L,
                "mix_w" to 4_999_999_998L,
                "mix_l" to -4_999_999_993L,
                "mix_ub" to 5_000_000_255L,
                "mixu_ub" to ULong.MAX_VALUE,
                "mixu_uw" to 0x2_0000_fffeuL,
                "mixq_bin" to 100L,
                "sumq" to 4_294_967_294L,
                "u_from_q" to ULong.MAX_VALUE,
                "q_from_u" to -7L,
            )
        }
    }

    test("narrowing casts from quad and uquad keep the low bits") {
        val src = $$"""
main {
    quad @shared q = $1234_5678_9abc_def1
    quad @shared qn = -5000000001
    uquad @shared u = $ffff_ffff_8000_0080
    uquad @shared u2 = $fedc_ba98_7654_3210

    long @shared q_l
    word @shared q_w
    uword @shared q_uw
    byte @shared q_b
    ubyte @shared q_ub
    long @shared qn_l
    word @shared qn_w
    uword @shared qn_uw
    byte @shared qn_b
    ubyte @shared qn_ub
    long @shared u_l
    word @shared u_w
    uword @shared u_uw
    byte @shared u_b
    ubyte @shared u_ub
    long @shared u2_l
    uword @shared u2_uw
    ubyte @shared u2_ub
    long @shared expr_l
    uword @shared expr_uw

    sub start() {
        q_l = q as long
        q_w = q as word
        q_uw = q as uword
        q_b = q as byte
        q_ub = q as ubyte
        qn_l = qn as long
        qn_w = qn as word
        qn_uw = qn as uword
        qn_b = qn as byte
        qn_ub = qn as ubyte
        u_l = u as long
        u_w = u as word
        u_uw = u as uword
        u_b = u as byte
        u_ub = u as ubyte
        u2_l = u2 as long
        u2_uw = u2 as uword
        u2_ub = u2 as ubyte
        expr_l = (q + qn) as long
        expr_uw = (u2 >> 16) as uword
    }
}"""
        // -5000000001 is $ffff_fffe_d5fa_0dff
        runOptimizedAndUnoptimized(src) {
            shouldHave(
                "q_l" to 0x9abc_def1u.toInt(),
                "q_w" to 0xdef1.toShort(),
                "q_uw" to 0xdef1.toUShort(),
                "q_b" to 0xf1.toByte(),
                "q_ub" to 0xf1.toUByte(),
                "qn_l" to 0xd5fa_0dffu.toInt(),
                "qn_w" to 0x0dff.toShort(),
                "qn_uw" to 0x0dff.toUShort(),
                "qn_b" to (-1).toByte(),
                "qn_ub" to 0xff.toUByte(),
                "u_l" to 0x8000_0080u.toInt(),
                "u_w" to 0x0080.toShort(),
                "u_uw" to 0x0080.toUShort(),
                "u_b" to (-128).toByte(),
                "u_ub" to 0x80.toUByte(),
                "u2_l" to 0x7654_3210,
                "u2_uw" to 0x3210.toUShort(),
                "u2_ub" to 0x10.toUByte(),
                "expr_l" to (0x1234_5678_9abc_def1L - 5_000_000_001L).toInt(),
                "expr_uw" to 0x7654.toUShort(),
            )
        }
    }

    test("quad and uquad convert to float and back") {
        val src = $$"""
%option enable_floats
main {
    quad @shared q = -5000000000
    quad @shared qodd = 9007199254740993
    uquad @shared ubig = $8000_0000_0000_0800
    uquad @shared utop = $ffff_ffff_ffff_f800
    uquad @shared usmall = 12345678901
    float @shared fsrc = -123456789012.75
    float @shared fpos = 98765432109.99
    float @shared fbig = 1.0e19

    float @shared f_q
    float @shared f_qodd
    float @shared f_ubig
    float @shared f_utop
    float @shared f_usmall
    quad @shared q_back
    uquad @shared ubig_back
    uquad @shared utop_back
    uquad @shared usmall_back
    quad @shared q_fsrc
    quad @shared q_fpos
    uquad @shared u_fpos
    uquad @shared u_fbig
    float @shared f_expr

    sub start() {
        f_q = q as float
        f_qodd = qodd as float
        f_ubig = ubig as float
        f_utop = utop as float
        f_usmall = usmall as float
        q_back = f_q as quad
        ubig_back = f_ubig as uquad
        utop_back = f_utop as uquad
        usmall_back = f_usmall as uquad
        q_fsrc = fsrc as quad
        q_fpos = fpos as quad
        u_fpos = fpos as uquad
        u_fbig = fbig as uquad
        f_expr = (q as float) / 4.0
    }
}"""
        runOptimizedAndUnoptimized(src) {
            shouldHave(
                "f_q" to -5.0e9,
                "f_qodd" to 2.0.pow(53),
                "f_ubig" to 2.0.pow(63) + 2048.0,
                "f_utop" to 2.0.pow(64) - 2048.0,
                "f_usmall" to 12_345_678_901.0,
                "q_back" to -5_000_000_000L,
                "ubig_back" to 0x8000_0000_0000_0800uL,
                "utop_back" to 0xffff_ffff_ffff_f800uL,
                "usmall_back" to 12_345_678_901uL,
                "q_fsrc" to -123_456_789_012L,
                "q_fpos" to 98_765_432_109L,
                "u_fpos" to 98_765_432_109uL,
                "u_fbig" to 10_000_000_000_000_000_000uL,
                "f_expr" to -1.25e9,
            )
        }
    }

    test("comparisons are signed for quad and unsigned for uquad over all 64 bits") {
        val src = $$"""
main {
    quad @shared qm1 = -1
    quad @shared q1 = 1
    uquad @shared um1 = $ffff_ffff_ffff_ffff
    uquad @shared u1 = 1
    quad @shared qa = $1_0000_0000
    quad @shared qb = $2_0000_0000
    uquad @shared ua = $1_0000_0005
    uquad @shared ub = $2_0000_0005
    quad @shared qlo1 = $1_8000_0000
    quad @shared qlo2 = $1_0000_0001
    quad @shared qneg = -5000000000
    quad @shared qpos = 5000000000
    uquad @shared uneg = $ffff_fffe_d5fa_0e00
    uquad @shared upos = 5000000000
    quad @shared qmin = -9223372036854775808
    quad @shared qmax = 9223372036854775807
    uquad @shared utop = $8000_0000_0000_0000
    uquad @shared ubelow = $7fff_ffff_ffff_ffff
    quad @shared qzero = 0

    bool @shared c_q_lt
    bool @shared c_q_le
    bool @shared c_q_gt
    bool @shared c_q_ge
    bool @shared c_q_eq
    bool @shared c_q_ne
    bool @shared c_u_lt
    bool @shared c_u_le
    bool @shared c_u_gt
    bool @shared c_u_ge
    bool @shared c_u_eq
    bool @shared c_u_ne
    bool @shared c_hi_lt
    bool @shared c_hi_gt
    bool @shared c_hi_eq
    bool @shared c_hi_ne
    bool @shared c_uhi_lt
    bool @shared c_uhi_ge
    bool @shared c_uhi_eq
    bool @shared c_uhi_ne
    bool @shared c_lo_gt
    bool @shared c_lo_lt
    bool @shared c_sign_q
    bool @shared c_sign_u
    bool @shared c_top_q
    bool @shared c_top_u
    bool @shared c_const_gt
    bool @shared c_const_lt
    bool @shared c_const_eq
    bool @shared c_const_ne
    bool @shared c_uconst_gt
    bool @shared c_uconst_eq
    bool @shared c_qneg_lt0
    bool @shared c_eq_sum
    ubyte @shared if1
    ubyte @shared if2
    ubyte @shared if3
    ubyte @shared if4
    ubyte @shared if5
    ubyte @shared if6

    sub start() {
        c_q_lt = qm1 < q1
        c_q_le = qm1 <= q1
        c_q_gt = qm1 > q1
        c_q_ge = qm1 >= q1
        c_q_eq = qm1 == q1
        c_q_ne = qm1 != q1
        c_u_lt = um1 < u1
        c_u_le = um1 <= u1
        c_u_gt = um1 > u1
        c_u_ge = um1 >= u1
        c_u_eq = um1 == u1
        c_u_ne = um1 != u1
        c_hi_lt = qa < qb
        c_hi_gt = qa > qb
        c_hi_eq = qa == qb
        c_hi_ne = qa != qb
        c_uhi_lt = ua < ub
        c_uhi_ge = ua >= ub
        c_uhi_eq = ua == ub
        c_uhi_ne = ua != ub
        c_lo_gt = qlo1 > qlo2
        c_lo_lt = qlo1 < qlo2
        c_sign_q = qneg < qpos
        c_sign_u = uneg < upos
        c_top_q = qmin > qmax
        c_top_u = utop > ubelow
        c_const_gt = qb > 4294967297
        c_const_lt = qb < 8589934592
        c_const_eq = qb == 8589934592
        c_const_ne = qa != 8589934592
        c_uconst_gt = um1 > $ffff_ffff_ffff_fffe
        c_uconst_eq = ua == $1_0000_0005
        c_qneg_lt0 = qneg < 0
        c_eq_sum = (qa + qa) == qb

        if qa < qb
            if1 = 1
        else
            if1 = 2
        if um1 > u1
            if2 = 1
        else
            if2 = 2
        if qm1 > q1
            if3 = 1
        else
            if3 = 2
        if ua >= ub
            if4 = 1
        else
            if4 = 2
        if qneg <= qneg
            if5 = 1
        else
            if5 = 2
        if qa == qzero
            if6 = 1
        else
            if6 = 2
    }
}"""
        runOptimizedAndUnoptimized(src) {
            shouldHave(
                "c_q_lt" to true,
                "c_q_le" to true,
                "c_q_gt" to false,
                "c_q_ge" to false,
                "c_q_eq" to false,
                "c_q_ne" to true,
                "c_u_lt" to false,
                "c_u_le" to false,
                "c_u_gt" to true,
                "c_u_ge" to true,
                "c_u_eq" to false,
                "c_u_ne" to true,
                "c_hi_lt" to true,
                "c_hi_gt" to false,
                "c_hi_eq" to false,
                "c_hi_ne" to true,
                "c_uhi_lt" to true,
                "c_uhi_ge" to false,
                "c_uhi_eq" to false,
                "c_uhi_ne" to true,
                "c_lo_gt" to true,
                "c_lo_lt" to false,
                "c_sign_q" to true,
                "c_sign_u" to false,
                "c_top_q" to false,
                "c_top_u" to true,
                "c_const_gt" to true,
                "c_const_lt" to false,
                "c_const_eq" to true,
                "c_const_ne" to true,
                "c_uconst_gt" to true,
                "c_uconst_eq" to true,
                "c_qneg_lt0" to true,
                "c_eq_sum" to true,
                "if1" to 1.toUByte(),
                "if2" to 1.toUByte(),
                "if3" to 2.toUByte(),
                "if4" to 2.toUByte(),
                "if5" to 1.toUByte(),
                "if6" to 2.toUByte(),
            )
        }
    }

    test("loops, when and bool conversion with quad values beyond 32 bits") {
        val src = $$"""
main {
    quad @shared counter = $0_ffff_fffa
    uquad @shared ucounter = $1_0000_0003
    ubyte @shared iterations
    ubyte @shared uiterations
    quad @shared repeatcount = 3
    ubyte @shared repeats
    ubyte @shared w1
    ubyte @shared w2
    ubyte @shared w3
    ubyte @shared w4
    ubyte @shared w5
    ubyte @shared uw1
    ubyte @shared uw2
    ubyte @shared uw3
    quad @shared big = $1_0000_0000
    uquad @shared ubig = $8000_0000_0000_0000
    quad @shared zero = 0
    bool @shared b_big
    bool @shared b_ubig
    bool @shared b_zero
    ubyte @shared if_big
    ubyte @shared if_zero
    ubyte @shared if_ubig
    ubyte @shared if_not

    sub start() {
        while counter < 4294967300 and iterations < 50 {
            counter++
            iterations++
        }
        do {
            ucounter--
            uiterations++
        } until ucounter <= $0_ffff_fffe or uiterations >= 50
        repeat repeatcount {
            repeats++
        }

        w1 = classify($2_0000_0000)
        w2 = classify(4294967296)
        w3 = classify(-8589934592)
        w4 = classify(0)
        w5 = classify(-4294967296)
        uw1 = uclassify($ffff_ffff_0000_0000)
        uw2 = uclassify($1_0000_0000)
        uw3 = uclassify(0)

        b_big = big as bool
        b_ubig = ubig as bool
        b_zero = zero as bool
        if big as bool
            if_big = 1
        else
            if_big = 2
        if zero as bool
            if_zero = 1
        else
            if_zero = 2
        if ubig as bool
            if_ubig = 1
        else
            if_ubig = 2
        if not (big as bool)
            if_not = 1
        else
            if_not = 2
    }

    sub classify(quad v) -> ubyte {
        when v {
            4294967296 -> return 1
            0 -> return 2
            -8589934592 -> return 3
            else -> return 4
        }
    }

    sub uclassify(uquad v) -> ubyte {
        when v {
            $ffff_ffff_0000_0000 -> return 1
            0 -> return 2
            else -> return 3
        }
    }
}"""
        runOptimizedAndUnoptimized(src) {
            shouldHave(
                "counter" to 4_294_967_300L,
                "iterations" to 10.toUByte(),
                "ucounter" to 4_294_967_294uL,
                "uiterations" to 5.toUByte(),
                "repeats" to 3.toUByte(),
                "w1" to 4.toUByte(),
                "w2" to 1.toUByte(),
                "w3" to 3.toUByte(),
                "w4" to 2.toUByte(),
                "w5" to 4.toUByte(),
                "uw1" to 1.toUByte(),
                "uw2" to 3.toUByte(),
                "uw3" to 2.toUByte(),
                "b_big" to true,
                "b_ubig" to true,
                "b_zero" to false,
                "if_big" to 1.toUByte(),
                "if_zero" to 2.toUByte(),
                "if_ubig" to 1.toUByte(),
                "if_not" to 2.toUByte(),
            )
        }
    }

    test("quad and uquad arrays: initializers, indexing, element updates and containment") {
        val src = $$"""
main {
    quad[] @shared qarr = [-1, 5000000000, -9223372036854775808, 7]
    uquad[] @shared uarr = [$ffff_ffff_ffff_ffff, $1_0000_0000, 3]
    quad[4] @shared qzero
    uquad[3] @shared uzero
    ubyte @shared i = 1
    ubyte @shared j = 2
    quad @shared needle_present = 5000000000
    quad @shared needle_absent = 6000000000
    quad @shared needle_low = $1_0000_0007
    uquad @shared uneedle_present = $ffff_ffff_ffff_ffff
    uquad @shared uneedle_low = $0000_0000_ffff_ffff
    uquad @shared uneedle_absent = 4

    quad @shared r_read1
    quad @shared r_read2
    uquad @shared r_uread
    bool @shared f_present
    bool @shared f_absent
    bool @shared f_low
    bool @shared f_upresent
    bool @shared f_ulow
    bool @shared f_uabsent
    bool @shared f_literal_yes
    bool @shared f_literal_no
    ubyte @shared n_q
    ubyte @shared n_u
    quad @shared sum

    sub start() {
        f_present = needle_present in qarr
        f_absent = needle_absent in qarr
        f_low = needle_low in qarr
        f_upresent = uneedle_present in uarr
        f_ulow = uneedle_low in uarr
        f_uabsent = uneedle_absent in uarr
        f_literal_yes = needle_present in [9, 5000000000]
        f_literal_no = needle_low in [7, 6000000000]
        n_q = len(qarr)
        n_u = len(uarr)

        r_read1 = qarr[i]
        r_read2 = qarr[j]
        r_uread = uarr[i]
        qarr[j] = 6000000000
        qzero[i] = -2
        uzero[j] = $ffff_0000_ffff_0000
        qarr[i] += 30000000000
        qarr[3] *= 1000000000000
        uarr[i] -= 1
        uarr[0] >>= 4
        qzero[j] = qarr[i] - qarr[3]

        ubyte k
        for k in 0 to len(qarr)-1 {
            sum += qarr[k]
        }
    }
}"""
        runOptimizedAndUnoptimized(src) {
            shouldHave(
                "f_present" to true,
                "f_absent" to false,
                "f_low" to false,
                "f_upresent" to true,
                "f_ulow" to false,
                "f_uabsent" to false,
                "f_literal_yes" to true,
                "f_literal_no" to false,
                "n_q" to 4.toUByte(),
                "n_u" to 3.toUByte(),
                "r_read1" to 5_000_000_000L,
                "r_read2" to Long.MIN_VALUE,
                "r_uread" to 0x1_0000_0000uL,
                "qarr" to listOf(-1L, 35_000_000_000L, 6_000_000_000L, 7_000_000_000_000L),
                "uarr" to listOf(0x0fff_ffff_ffff_ffffuL, 0xffff_ffffuL, 3uL),
                "qzero" to listOf(0L, -2L, -6_965_000_000_000L, 0L),
                "uzero" to listOf(0uL, 0uL, 0xffff_0000_ffff_0000uL),
                "sum" to 7_040_999_999_999L,
            )
        }
    }

    test("subroutines take and return quad and uquad values") {
        val src = $$"""
main {
    quad @shared r_add
    uquad @shared r_mul
    quad @shared r_two_q
    uquad @shared r_two_u
    quad @shared r_nested
    quad @shared r_mixed
    ubyte @shared r_three_b
    quad @shared r_three_q
    uquad @shared r_three_u
    bool @shared r_isbig1
    bool @shared r_isbig2
    bool @shared r_cond
    quad @shared r_widen_args
    quad @shared r_ret_widen
    quad @shared arg = -7000000000
    uquad @shared uarg = 3
    byte @shared bm = -1
    word @shared wm = -1000

    sub start() {
        r_add = addq(5000000000, arg)
        r_mul = mulu($1_0000_0001, uarg)
        r_two_q, r_two_u = two()
        r_nested = addq(addq(arg, arg), 1)
        r_mixed = mixed(200, -5000000000, 60000)
        r_three_b, r_three_q, r_three_u = three(arg)
        r_isbig1 = isbig($1_0000_0000)
        r_isbig2 = isbig($0_ffff_ffff)
        r_cond = addq(arg, 1) > -7000000000
        r_widen_args = addq(bm, wm)
        r_ret_widen = retw(-1234)
    }

    sub retw(word w) -> quad {
        return w
    }

    sub addq(quad x, quad y) -> quad {
        return x + y
    }

    sub mulu(uquad x, uquad y) -> uquad {
        return x * y
    }

    sub two() -> quad, uquad {
        return -5000000000, $ffff_ffff_0000_0001
    }

    sub mixed(ubyte a, quad b, uword c) -> quad {
        return b - a + c
    }

    sub three(quad v) -> ubyte, quad, uquad {
        return 42, v * 2, (v as uquad) >> 4
    }

    sub isbig(uquad v) -> bool {
        return v > ($0_ffff_ffff as uquad)
    }
}"""
        val arg = -7_000_000_000L
        runOptimizedAndUnoptimized(src) {
            shouldHave(
                "r_add" to -2_000_000_000L,
                "r_mul" to 0x3_0000_0003uL,
                "r_two_q" to -5_000_000_000L,
                "r_two_u" to 0xffff_ffff_0000_0001uL,
                "r_nested" to -13_999_999_999L,
                "r_mixed" to -4_999_940_200L,
                "r_three_b" to 42.toUByte(),
                "r_three_q" to -14_000_000_000L,
                "r_three_u" to (arg.toULong() shr 4),
                "r_isbig1" to true,
                "r_isbig2" to false,
                "r_cond" to true,
                "r_widen_args" to -1001L,
                "r_ret_widen" to -1234L,
            )
        }
    }

    test("globals, consts, struct fields through a pointer, peekq and pokeq") {
        val src = $$"""
main {
    quad @shared g1 = -5000000000
    uquad @shared g2 = $ffff_ffff_0000_0000
    quad @shared g3
    uquad @shared g4
    quad @shared g5
    uquad @shared g6
    const quad CQ = 1234567890123456789
    const uquad CU = $fedc_ba98_7654_3210
    const quad CNEG = -1234567890123456789

    struct Item {
        ubyte tag
        quad amount
        uquad mask
    }

    quad @shared r_cq
    uquad @shared r_cu
    quad @shared r_cneg
    quad @shared r_cmod
    quad @shared r_cplus
    quad @shared r_amount
    uquad @shared r_mask
    ubyte @shared r_tag
    quad @shared r_amount1
    quad @shared r_peek
    uquad @shared r_peeku
    ubyte @shared sz_q
    ubyte @shared sz_u
    ubyte @shared sz_item

    sub start() {
        r_cq = CQ
        r_cu = CU
        r_cneg = CNEG
        r_cmod = CQ % 1000
        r_cplus = CQ + 3
        g3 = g1 * 2
        g4 = g2 | 1

        ^^Item item = $1000
        item.tag = 99
        item.amount = -5000000000
        item.mask = $8000_0000_0000_0001
        item.mask |= 2
        item.amount -= 1
        r_amount = item.amount
        r_mask = item.mask
        r_tag = item.tag

        ubyte @shared idx = 1
        item[idx].tag = 7
        item[idx].amount = 6000000000
        item[idx].mask = ~item.mask
        r_amount1 = item[idx].amount + item[0].amount

        pokeq($10100, 7000000000)
        pokeq($10108, $ffff_ffff_ffff_fffe)
        r_peek = peekq($10100)
        r_peeku = peekq($10108) as uquad

        sz_q = sizeof(quad)
        sz_u = sizeof(uquad)
        sz_item = sizeof(Item)
    }
}"""
        runOptimizedAndUnoptimized(src) {
            withClue("variables must not overlap the fixed struct address") {
                (freeMemory <= 0x1000u) shouldBe true
            }
            shouldHave(
                "g1" to -5_000_000_000L,
                "g2" to 0xffff_ffff_0000_0000uL,
                "g3" to -10_000_000_000L,
                "g4" to 0xffff_ffff_0000_0001uL,
                "g5" to 0L,
                "g6" to 0uL,
                "r_cq" to 1_234_567_890_123_456_789L,
                "r_cu" to 0xfedc_ba98_7654_3210uL,
                "r_cneg" to -1_234_567_890_123_456_789L,
                "r_cmod" to 789L,
                "r_cplus" to 1_234_567_890_123_456_792L,
                "r_amount" to -5_000_000_001L,
                "r_mask" to 0x8000_0000_0000_0003uL,
                "r_tag" to 99.toUByte(),
                "r_amount1" to 999_999_999L,
                "r_peek" to 7_000_000_000L,
                "r_peeku" to 0xffff_ffff_ffff_fffeuL,
                "sz_q" to 8.toUByte(),
                "sz_u" to 8.toUByte(),
                "sz_item" to 17.toUByte(),
            )
            // the struct is packed: tag at offset 0, amount at 1, mask at 9, 17 bytes per element
            memoryShouldHave(
                0x1000u to 99.toUByte(),
                0x1001u to -5_000_000_001L,
                0x1009u to 0x8000_0000_0000_0003uL,
                0x1011u to 7.toUByte(),
                0x1012u to 6_000_000_000L,
                0x101au to 0x7fff_ffff_ffff_fffcuL,
                0x10100u to 7_000_000_000L,
                0x10108u to 0xffff_ffff_ffff_fffeuL,
            )
        }
    }

    test("typed quad pointers: dereference and indexed element updates") {
        val src = $$"""
main {
    quad @shared g7 = 5
    uquad @shared g8 = 1
    quad[] @shared qa = [1, 2, 3, 4]
    ubyte @shared i = 3

    sub start() {
        ^^quad qp = &&g7
        qp^^ = 5000000000
        qp^^ *= 3
        ^^uquad up = &&g8
        up^^ |= $8000_0000_0000_0000
        ^^quad ap = &&qa[0]
        ap[1] = -6000000000
        ap[2] += 7
        ap[i] -= 10000000000
        ap[0] += ap[1]
    }
}"""
        runOptimizedAndUnoptimized(src) {
            shouldHave(
                "g7" to 15_000_000_000L,
                "g8" to 0x8000_0000_0000_0001uL,
                "qa" to listOf(1L - 6_000_000_000L, -6_000_000_000L, 10L, 4L - 10_000_000_000L),
            )
        }
    }

    test("struct with quad fields through a pointer above 64K") {
        val src = $$"""
main {
    struct Item {
        ubyte tag
        quad amount
        uquad mask
    }

    sub start() {
        ^^Item @shared far = $20000
        far.tag = 42
        far.amount = -5000000000
        far.mask = $8000_0000_0000_0001
    }
}"""
        runOptimizedAndUnoptimized(src) {
            shouldHave("start.far" to 0x20000)
            memoryShouldHave(
                0x20000u to 42.toUByte(),
                0x20001u to -5_000_000_000L,
                0x20009u to 0x8000_0000_0000_0001uL,
            )
        }
    }

    test("long -2147483648 widens to quad -2147483648") {
        val src = """
main {
    long @shared lmin = -2147483648
    long[] @shared larr = [5, -2147483648]
    quad @shared q
    uquad @shared u
    quad @shared qa

    sub start() {
        q = lmin
        u = lmin as uquad
        qa = larr[1]
    }
}"""
        runOptimizedAndUnoptimized(src) {
            shouldHave(
                "lmin" to Int.MIN_VALUE,
                "q" to -2_147_483_648L,
                "u" to 0xffff_ffff_8000_0000uL,
                "qa" to -2_147_483_648L,
            )
        }
    }

    test("quad literals between 2^31 and 2^32 keep their value") {
        val src = $$"""
main {
    quad @shared q1 = 4294967295
    quad @shared q2 = 2147483648
    quad @shared q3 = -3000000000
    quad @shared q4 = $ffff_ffff
    quad[] @shared arr = [4294967295, 3000000000, -2147483648]
    const quad C = 4294967295
    quad @shared q5 = 1
    quad @shared q6 = 1
    quad @shared q7

    sub start() {
        q5 = q5 + 3000000000
        q6 += $ffff_ffff
        q7 = C
    }
}"""
        runOptimizedAndUnoptimized(src) {
            shouldHave(
                "q1" to 4_294_967_295L,
                "q2" to 2_147_483_648L,
                "q3" to -3_000_000_000L,
                "q4" to 4_294_967_295L,
                "arr" to listOf(4_294_967_295L, 3_000_000_000L, -2_147_483_648L),
                "q5" to 3_000_000_001L,
                "q6" to 4_294_967_296L,
                "q7" to 4_294_967_295L,
            )
        }
    }

    test("uquad compared with a large positive literal compares unsigned") {
        val src = $$"""
main {
    uquad @shared um1 = $ffff_ffff_ffff_ffff
    uquad @shared uhigh = $8000_0000_0000_0000
    bool @shared b_gt
    bool @shared b_lt
    bool @shared b_ge
    bool @shared b_le
    ubyte @shared r_if

    sub start() {
        b_gt = um1 > 5000000000
        b_lt = um1 < 5000000000
        b_ge = uhigh >= $1_0000_0000
        b_le = uhigh <= $1_0000_0000
        if um1 > 5000000000
            r_if = 1
        else
            r_if = 2
    }
}"""
        runOptimizedAndUnoptimized(src) {
            shouldHave(
                "b_gt" to true,
                "b_lt" to false,
                "b_ge" to true,
                "b_le" to false,
                "r_if" to 1.toUByte(),
            )
        }
    }

    test("abs, min, max, clamp and sgn take quads and uquads, and sgn of an unsigned value is 0 or 1") {
        val src = $$"""
main {
    quad @shared q = -5000000000
    quad @shared r = 3
    quad @shared z = 0
    uquad @shared uq = $9000_0000_0000_0000
    uquad @shared ur = 4
    long @shared l = -7
    uword @shared uw = 40000
    quad @shared absq
    uquad @shared absuq
    quad @shared minq
    quad @shared maxq
    uquad @shared minuq
    uquad @shared maxuq
    quad @shared minmixed
    quad @shared clampq1
    quad @shared clampq2
    uquad @shared clampuq
    byte @shared sgnq
    byte @shared sgnz
    byte @shared sgnuq
    byte @shared sgnuw

    sub start() {
        absq = abs(q)
        absuq = abs(uq)
        minq = min(q, r)
        maxq = max(q, r)
        minuq = min(uq, ur)
        maxuq = max(uq, ur)
        minmixed = min(q, l)
        clampq1 = clamp(q, r, 10)
        clampq2 = clamp(r * 5, 0, 10)
        clampuq = clamp(uq, ur, 100)
        sgnq = sgn(q)
        sgnz = sgn(z)
        sgnuq = sgn(uq)
        sgnuw = sgn(uw)
    }
}"""
        runOptimizedAndUnoptimized(src) {
            shouldHave(
                "absq" to 5_000_000_000L,
                "absuq" to 0x9000_0000_0000_0000uL,
                "minq" to -5_000_000_000L,
                "maxq" to 3L,
                "minuq" to 4uL,
                "maxuq" to 0x9000_0000_0000_0000uL,
                "minmixed" to -5_000_000_000L,
                "clampq1" to 3L,
                "clampq2" to 10L,
                "clampuq" to 100uL,
                "sgnq" to (-1).toByte(),
                "sgnz" to 0.toByte(),
                "sgnuq" to 1.toByte(),
                "sgnuw" to 1.toByte(),
            )
        }
    }

    test("sqrt of a uquad is the exact integer square root, also near 2^64") {
        val src = $$"""
main {
    uquad @shared v1 = 17
    uquad @shared v2 = $ffff_fffe_0000_0001
    uquad @shared v3 = $ffff_fffe_0000_0000
    uquad @shared v4 = $ffff_ffff_ffff_ffff
    uquad @shared v5 = 0
    uquad @shared r1
    uquad @shared r2
    uquad @shared r3
    uquad @shared r4
    uquad @shared r5

    sub start() {
        r1 = sqrt(v1)
        r2 = sqrt(v2)
        r3 = sqrt(v3)
        r4 = sqrt(v4)
        r5 = sqrt(v5)
    }
}"""
        runOptimizedAndUnoptimized(src) {
            shouldHave(
                "r1" to 4uL,
                "r2" to 4_294_967_295uL,
                "r3" to 4_294_967_294uL,
                "r4" to 4_294_967_295uL,
                "r5" to 0uL,
            )
        }
    }
})
