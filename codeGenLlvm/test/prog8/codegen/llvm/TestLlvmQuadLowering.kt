package prog8.codegen.llvm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import prog8.code.core.BaseDataType
import prog8.code.core.CompilationOptions
import prog8.code.core.DataType
import prog8.code.core.ZeropageWish
import prog8.code.target.LlvmTarget
import prog8.codegen.llvm.lower.LlvmGlobalEmitter
import prog8.codegen.llvm.model.LlvmModule
import prog8.intermediate.*
import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.pow
import prog8tests.helpers.hostLlvmTarget


class TestLlvmQuadLowering: FunSpec({

    val focused = TestProgram().apply { quadSubs() }.module()

    test("signed quad division by a register guards the divisors 0 and -1 instead of dividing in a wider type") {
        val lines = focused.function("main.sdivq").lines()
        lines shouldHave "  %zero.1 = icmp eq i64 %r2.2, 0"
        lines shouldHave "  %minusone.1 = icmp eq i64 %r2.2, -1"
        lines shouldHave "  %unsafe.1 = or i1 %zero.1, %minusone.1"
        lines shouldHave "  %divisor.1 = select i1 %unsafe.1, i64 1, i64 %r2.2"
        lines shouldHave "  %r1.4 = sdiv i64 %r1.3, %divisor.1"
        lines shouldHave "  %r1.5 = sub i64 0, %r1.3"
        lines shouldHave "  %r1.6 = select i1 %minusone.1, i64 %r1.5, i64 %r1.4"
        lines shouldHave "  %r1.7 = select i1 %zero.1, i64 ${vmDivs(1, 0)}, i64 %r1.6"
        lines shouldHave "  %divisor.2 = select i1 %unsafe.2, i64 1, i64 %r2.3"
        lines shouldHave "  %r3.2 = srem i64 %r3.1, %divisor.2"
        lines.filter { "i128" in it || " sext " in it } shouldBe emptyList()
    }

    test("unsigned quad division by a register gives all ones for a divisor of 0, and a remainder of 0") {
        val lines = focused.function("main.udivq").lines()
        lines shouldHave "  %divisor.1 = select i1 %zero.1, i64 1, i64 %r2.2"
        lines shouldHave "  %r1.4 = udiv i64 %r1.3, %divisor.1"
        lines shouldHave "  %r1.5 = select i1 %zero.1, i64 ${vmDiv(1, 0)}, i64 %r1.4"
        lines shouldHave "  %r3.2 = urem i64 %r3.1, %divisor.2"
        lines shouldHave "  %r3.3 = select i1 %zero.2, i64 ${vmMod(1, 0)}, i64 %r3.2"
    }

    test("quad division by the constant -1 negates instead of dividing, and constant divisors of 0 give the VM's results right away") {
        val lines = focused.function("main.constdivq").lines()
        lines shouldHave "  %r1.6 = sub i64 0, %r1.5"
        lines shouldHave "  store i64 ${vmMods(Long.MIN_VALUE, -1)}, ptr %r2.slot"
        lines shouldHave "  store i64 ${vmDivs(1, 0)}, ptr %r3.slot"
        lines shouldHave "  store i64 ${vmDiv(1, 0)}, ptr %r4.slot"
        lines.filter { "div " in it || "rem " in it } shouldBe emptyList()
    }

    test("float conversions of quads use the 64-bit intrinsics, FTOUQ converts negative floats like a signed conversion") {
        val lines = focused.function("main.fconvq").lines()
        lines shouldHave "  %fptosi.1 = call i64 @llvm.fptosi.sat.i64.f64(double %fr1.2)"
        lines shouldHave "  %negative.1 = fcmp olt double %fr1.3, 0.0"
        lines shouldHave "  %fptosi.2 = call i64 @llvm.fptosi.sat.i64.f64(double %fr1.3)"
        lines shouldHave "  %fptoui.1 = call i64 @llvm.fptoui.sat.i64.f64(double %fr1.3)"
        lines shouldHave "  %r2.1 = select i1 %negative.1, i64 %fptosi.2, i64 %fptoui.1"
        lines shouldHave "  %float.1 = sitofp i64 %r1.1 to double"
        lines shouldHave "  %float.2 = uitofp i64 %r2.2 to double"
        val module = LlvmPrinter.print(focused).lines()
        module shouldHave "declare i64 @llvm.fptosi.sat.i64.f64(double)"
        module shouldHave "declare i64 @llvm.fptoui.sat.i64.f64(double)"
    }

    test("EXTQ zero extends and EXTQS sign extends to i64, a pointer gives its whole address, LSIGL, LSIGW and LSIGB truncate") {
        val lines = focused.function("main.extendq").lines()
        lines shouldHave "  %r5.1 = zext i8 %r1.2 to i64"
        lines shouldHave "  %r6.1 = sext i8 %r1.3 to i64"
        lines shouldHave "  %r7.1 = zext i16 %r2.2 to i64"
        lines shouldHave "  %r8.1 = sext i16 %r2.3 to i64"
        lines shouldHave "  %r3.4 = trunc i64 %r3.3 to i32"
        lines shouldHave "  %r9.1 = zext i32 %r3.4 to i64"
        lines shouldHave "  %r10.1 = sext i32 %r3.6 to i64"
        lines shouldHave "  %r11.1 = ptrtoint ptr %r4.2 to i64"
        lines shouldHave "  %r12.1 = trunc i64 %r5.14 to i32"
        lines shouldHave "  %r13.1 = trunc i64 %r5.15 to i16"
        lines shouldHave "  %r14.1 = trunc i64 %r5.16 to i8"
    }

    test("quad shift counts outside 0..63 shift out all bits or fill with the sign bit, also immediates that don't fit in 32 bits") {
        val lines = focused.function("main.shiftsq").lines()
        lines shouldHave "  %count.1 = zext i8 %r2.2 to i64"
        lines shouldHave "  %inrange.1 = icmp ult i64 %count.1, 64"
        lines shouldHave "  %r3.3 = select i1 %inrange.1, i64 %r3.2, i64 0"
        lines shouldHave "  %count.3 = select i1 %inrange.2, i64 %count.2, i64 63"
        lines shouldHave "  %r4.2 = ashr i64 %r4.1, %count.3"
        lines shouldHave "  %r5.2 = shl i64 %r5.1, 40"
        lines shouldHave "  store i64 0, ptr %r6.slot"
        lines shouldHave "  store i64 0, ptr %r7.slot"
        lines shouldHave "  %r8.2 = ashr i64 %r8.1, 63"
        lines shouldHave "  %r9.2 = ashr i64 %r9.1, 63"
        lines shouldHave "  store i64 0, ptr %r10.slot"
    }

    test("quad compares and single bit operations work on all 64 bits") {
        val lines = focused.function("main.bitsq").lines()
        lines shouldHave "  %r1.3 = or i64 %r1.2, ${1L shl 63}"
        lines shouldHave "  %bit.1 = and i64 %r1.4, ${1L shl 63}"
        lines shouldHave "  %cond.2 = icmp sgt i64 %r1.5, ${0x1_0000_0005L}"
        lines shouldHave "  %cond.3 = icmp ugt i64 %r1.6, 5"
    }

    test("quad memory access loads and stores i64, indexed with a scale of 8") {
        val lines = focused.function("main.memoryq").lines()
        lines shouldHave "  %offset.1 = mul i64 %index.1, 8"
        lines shouldHave "  %r2.1 = load i64, ptr %addr.1, align 1"
        lines shouldHave "  store i64 %r2.2, ptr %addr.2, align 1"
        lines shouldHave "  store i64 ${0x1_2345_6789L}, ptr @main.qarray, align 1"
        lines shouldHave "  store i64 0, ptr %addr.3, align 1"
    }

    test("PUSH.q and POP.q move all 8 bytes of a quad over the value stack") {
        val lines = focused.function("main.stackq").lines()
        lines shouldHave "  %sp.2 = getelementptr i8, ptr @.stack.data, i64 %sp.1"
        lines shouldHave "  store i64 %r1.2, ptr %sp.2, align 1"
        lines shouldHave "  %sp.3 = add i64 %sp.1, 8"
        lines shouldHave "  %sp.5 = sub i64 %sp.4, 8"
        lines shouldHave "  %sp.6 = getelementptr i8, ptr @.stack.data, i64 %sp.5"
        lines shouldHave "  %r2.1 = load i64, ptr %sp.6, align 1"
        lines shouldHave "  store i64 %r2.1, ptr %r2.slot"
    }

    test("QUADARRAY_CONTAINS searches the 8-byte elements with an i64 helper") {
        focused.function("main.findq").lines() shouldHave "  %found.1 = call i8 @.contains.i64(i64 %r1.2, ptr %r2.3, i16 %r3.1)"
        val helper = focused.function(".contains.i64").lines()
        helper shouldHave "define internal i8 @.contains.i64(i64 %needle, ptr %array, i16 %count) {"
        helper shouldHave "  %slot = getelementptr i64, ptr %array, i64 %i"
        helper shouldHave "  %element = load i64, ptr %slot, align 1"
        helper shouldHave "  %found = icmp eq i64 %element, %needle"
    }

    test("EXTQS of a pointer is rejected, as a pointer is unsigned") {
        val message = shouldThrow<IllegalArgumentException> {
            TestProgram().apply {
                variable("main.p", DataType.pointer(BaseDataType.UBYTE))
                sub("main.f", code = "_main.f:\nloadm.p r1.p,[main.p]\nextqs.p r2.q,r1.p\nreturn")
            }.module()
        }.message.orEmpty()
        message shouldContain "makes no sense"
    }

    test("quad and uquad variables, array elements, struct fields and constants are emitted from their exact bits, not the approximate Double") {
        val module = quadGlobals()
        val uquadMax = 0xFFFF_FFFF_FFFF_FFFFuL.toLong()
        module.printed("main.q") shouldBe "@main.q = internal global i64 ${Long.MAX_VALUE - 1}"
        module.printed("main.u") shouldBe "@main.u = internal global i64 ${uquadMax - 1}"
        module.printed("main.odd") shouldBe "@main.odd = internal global i64 9007199254740993"
        module.printed("main.plain") shouldBe "@main.plain = internal global i64 -5"
        module.printed("main.qs") shouldBe "@main.qs = internal global [3 x i64] [i64 ${Long.MAX_VALUE - 1}, i64 9007199254740993, i64 ${Long.MIN_VALUE}]"
        module.printed("main.us") shouldBe "@main.us = internal global [2 x i64] [i64 ${uquadMax - 1}, i64 ${Long.MIN_VALUE + 1}]"
        module.printed("prog8_struct_instances.big") shouldBe
            "@prog8_struct_instances.big = internal global <{ i64, i64, i8 }> <{ i64 ${Long.MAX_VALUE - 1}, i64 ${uquadMax - 1}, i8 7 }>"
        module.printed("main.BIGQ") shouldBe "@main.BIGQ = internal constant i64 ${Long.MAX_VALUE - 1}"
        module.printed("main.BIGUQ") shouldBe "@main.BIGUQ = internal constant i64 ${uquadMax - 1}"
    }

    test("llvm-as accepts the quad lowering").config(enabledOrReasonIf = requiresLlvmAs) {
        for (module in listOf(focused, TestProgram().apply { quadCheckProgram() }.module(), quadGlobals())) {
            val (exitCode, output) = runLlvmAs(LlvmPrinter.print(module))
            withClue(output) { exitCode shouldBe 0 }
        }
    }

    test("the quad instructions compute what the VM computes, in a native program for the host").config(enabledOrReasonIf = requiresNativeHost) {
        val module = TestProgram(hostLlvmTarget).apply { quadCheckProgram() }.module().withMainCalling("main.check")
        val (exitCode, output) = compileAndRun(LlvmPrinter.print(module))
        withClue("exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
        output shouldBe ""
    }

    test("the llvm32 quad instructions compute what the VM computes, in a wasm32 program under wasmtime").config(enabledOrReasonIf = requiresWasm) {
        val module = TestProgram(LlvmTarget(4)).apply { quadCheckProgram() }.module().withMainCalling("main.check").withMainNamed(wasiMain)
        val (exitCode, output) = compileAndRunWasm(LlvmPrinter.print(module))
        withClue("exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
        output shouldBe ""
    }

    test("the llvm32 quad instructions compute what the VM computes, in a bare metal ARM program on qemu").config(enabledOrReasonIf = requiresArm) {
        val module = TestProgram(armTarget).apply { quadCheckProgram() }.module().withMainCalling("main.check")
        val (exitCode, output) = compileAndRunArm(LlvmPrinter.print(module))
        withClue("exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
        output shouldBe ""
    }

    test("the llvm32 quad instructions compute what the VM computes, in a bare metal RISC-V program on qemu").config(enabledOrReasonIf = requiresRiscv) {
        val module = TestProgram(riscvTarget).apply { quadCheckProgram() }.module().withMainCalling("main.check")
        val (exitCode, output) = compileAndRunRiscv(LlvmPrinter.print(module))
        withClue("exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
        output shouldBe ""
    }
})


// the VM's results (VirtualMachine.kt and VmArithmetic.kt): QUAD wraps around, division by zero and shift counts are defined
private fun vmDivs(a: Long, b: Long): Long = if (b == 0L) Long.MAX_VALUE else BigInteger.valueOf(a).divide(BigInteger.valueOf(b)).toLong()
private fun vmMods(a: Long, b: Long): Long = if (b == 0L) 0L else BigInteger.valueOf(a).rem(BigInteger.valueOf(b)).toLong()
private fun vmDiv(a: Long, b: Long): Long = if (b == 0L) ULong.MAX_VALUE.toLong() else (a.toULong() / b.toULong()).toLong()
private fun vmMod(a: Long, b: Long): Long = if (b == 0L) 0L else (a.toULong() % b.toULong()).toLong()
private fun vmLsl(value: Long, count: Long): Long = if (count in 0..63) value shl count.toInt() else 0L
private fun vmLsr(value: Long, count: Long): Long = if (count in 0..63) value ushr count.toInt() else 0L
private fun vmAsr(value: Long, count: Long): Long = if (count in 0..63) value shr count.toInt() else value shr 63
private fun vmFtosq(value: Double): Long = value.toLong()
private fun vmFtouq(value: Double): Long = if (value < 0.0) value.toLong() else value.toULong().toLong()
private fun vmFfromsq(bits: Long): Double = bits.toDouble()
private fun vmFfromuq(bits: Long): Double = bits.toULong().toDouble()
private fun vmBitClear(value: Long, bit: Int): Boolean = (value ushr bit) and 1L == 0L

/** A quad immediate in IR text. */
private fun q(value: Long) = $$"#$$${value.toULong().toString(16)}.q"

private fun quads(vararg bits: Long) = IRVariableInitializer.Array(bits.map { IRStSymbolicReference.Numeric(it.toDouble(), it) })


private sealed interface QuadExpect
private data class Equals(val register: String, val value: Long) : QuadExpect
private data class FloatEquals(val register: String, val value: Double) : QuadExpect
private data class ZeroFlag(val set: Boolean) : QuadExpect

/**
 * Builds main.check, which returns 0, or the number of the first check whose results differ from the VM's.
 * A check is IR lines separated by "; ", using the registers ra..rh and fra..frh, and the labels FAIL and OK of the check.
 */
private class QuadChecks {
    private val code = mutableListOf<String>()
    private var count = 0
    val sharedVariables = mutableListOf<IRStStaticVariable>()

    /** A @shared variable holding [bits], whose value LLVM can't fold into the code. */
    fun quad(bits: Long, dt: DataType = DataType.QUAD): String {
        val approximate = if (dt.base == BaseDataType.UQUAD) bits.toULong().toDouble() else bits.toDouble()
        return shared(dt, IRVariableInitializer.Numeric(approximate, bits))
    }

    fun float(value: Double): String = shared(DataType.FLOAT, IRVariableInitializer.Numeric(value))

    fun ubyte(value: Int): String = shared(DataType.UBYTE, IRVariableInitializer.Numeric(value.toDouble()))

    private fun shared(dt: DataType, init: IRVariableInitializer.Numeric): String {
        val name = "main.shared${sharedVariables.size}"
        sharedVariables += IRStStaticVariable(name, dt, init, null, ZeropageWish.DONTCARE, 0u, false, shared = true)
        return name
    }

    fun check(ir: String, vararg expected: QuadExpect) {
        val number = ++count
        val fail = "main.check.fail$number"
        fun numbered(text: String) = quadRegisterLetters.replace(text) { "${it.groupValues[1]}r${number * 10 + (it.groupValues[2][0] - 'a')}" }
            .replace("FAIL", fail).replace("OK", "main.check.ok$number")
        code += ir.split("; ").map { numbered(it.trim()) }
        for (expect in expected) {
            when (expect) {
                is Equals -> {
                    val type = expect.register.substringAfterLast('.')
                    code += if (type == "q") "cmpi.q ${numbered(expect.register)},${q(expect.value)}"
                        else "cmpi.$type ${numbered(expect.register)},#${expect.value}.$type"
                    code += "bstne $fail"
                }
                // FCOMP gives 0 for NaN as well, which the comparison with a lower bound catches
                is FloatEquals -> {
                    val register = numbered(expect.register)
                    for ((bound, order) in listOf(expect.value to 0, expect.value - maxOf(1.0, abs(expect.value)) to 1))
                        code += listOf("load.f fr9000.f,#$bound.f", "fcomp.f r9000.b,$register,fr9000.f", "cmpi.b r9000.b,#$order.b", "bstne $fail")
                }
                is ZeroFlag -> code += if (expect.set) "bstne $fail" else "bsteq $fail"
            }
        }
    }

    fun subroutine(): String = buildString {
        appendLine("_main.check:")
        code.forEach { appendLine(it) }
        appendLine("returni.b #0.b")
        for (number in 1..count) {
            appendLine("_main.check.fail$number:")
            appendLine("returni.b #$number.b")
        }
    }
}

private val quadRegisterLetters = Regex("""\b(f?)r([a-h])(?=\.)""")

private val quadArray = longArrayOf(0x0123_4567_89AB_CDEFL, -2L, Long.MIN_VALUE, 0x7FFF_FFFF_0000_0001L)
private val quadMemory = longArrayOf(0x1111_1111_2222_2222L, 0x3333_3333_4444_4444L, 0x5555_5555_6666_6666L)

private fun TestProgram.quadCheckProgram() {
    val checks = quadChecks()
    checks.sharedVariables.forEach { variable(it.name, it.dt, it.initializationValue, shared = true) }
    variable("main.quads", DataType.arrayFor(BaseDataType.QUAD, target), quads(*quadArray), quadArray.size.toUInt(), shared = true)
    variable("main.qmem", DataType.arrayFor(BaseDataType.QUAD, target), length = quadMemory.size.toUInt())
    variable("main.memq", DataType.QUAD)
    sub("main.check", returns = listOf(DataType.UBYTE), code = checks.subroutine())
}

private fun quadChecks() = QuadChecks().apply {
    val min = Long.MIN_VALUE
    val max = Long.MAX_VALUE

    // division by zero and MIN / -1, also with divisors in registers whose values LLVM can't see
    check("load.q ra.q,${q(min)}; loadm.q rb.q,[${quad(-1)}]; loadr.q rc.q,ra.q; divsr.q ra.q,rb.q; modsr.q rc.q,rb.q",
        Equals("ra.q", vmDivs(min, -1)), Equals("rc.q", vmMods(min, -1)))
    check("loadm.q ra.q,[${quad(-7)}]; loadm.q rb.q,[${quad(0)}]; loadr.q rc.q,ra.q; loadr.q rd.q,ra.q; loadr.q re.q,ra.q; " +
            "divsr.q ra.q,rb.q; divr.q rc.q,rb.q; modsr.q rd.q,rb.q; modr.q re.q,rb.q",
        Equals("ra.q", vmDivs(-7, 0)), Equals("rc.q", vmDiv(-7, 0)), Equals("rd.q", vmMods(-7, 0)), Equals("re.q", vmMod(-7, 0)))
    check("loadm.q ra.q,[${quad(-10)}]; loadm.q rb.q,[${quad(3)}]; loadr.q rc.q,ra.q; loadr.q rd.q,ra.q; loadr.q re.q,ra.q; " +
            "divsr.q ra.q,rb.q; divr.q rc.q,rb.q; modsr.q rd.q,rb.q; modr.q re.q,rb.q",
        Equals("ra.q", vmDivs(-10, 3)), Equals("rc.q", vmDiv(-10, 3)), Equals("rd.q", vmMods(-10, 3)), Equals("re.q", vmMod(-10, 3)))
    check("loadm.q ra.q,[${quad(max)}]; loadm.q rb.q,[${quad(-2)}]; loadr.q rc.q,ra.q; divsr.q ra.q,rb.q; modsr.q rc.q,rb.q; " +
            "loadm.q rd.q,[${quad(-2)}]; loadm.q re.q,[${quad(1L shl 32)}]; loadr.q rf.q,rd.q; divr.q rd.q,re.q; modr.q rf.q,re.q",
        Equals("ra.q", vmDivs(max, -2)), Equals("rc.q", vmMods(max, -2)), Equals("rd.q", vmDiv(-2, 1L shl 32)), Equals("rf.q", vmMod(-2, 1L shl 32)))
    check("load.q ra.q,${q(min)}; divs.q ra.q,#-1.q; loadm.q rb.q,[${quad(-7)}]; mods.q rb.q,#-1.q; loadm.q rc.q,[${quad(-7)}]; div.q rc.q,#0.q; " +
            "loadm.q rd.q,[${quad(-7)}]; divs.q rd.q,#0.q; loadm.q re.q,[${quad(-7)}]; mods.q re.q,#0.q",
        Equals("ra.q", vmDivs(min, -1)), Equals("rb.q", vmMods(-7, -1)), Equals("rc.q", vmDiv(-7, 0)), Equals("rd.q", vmDivs(-7, 0)), Equals("re.q", vmMods(-7, 0)))
    check("loadm.q ra.q,[${quad(-7)}]; loadr.q rb.q,ra.q; divs.q ra.q,#2.q; mods.q rb.q,#2.q; loadr.q rc.q,rb.q; div.q rc.q,#2.q",
        Equals("ra.q", vmDivs(-7, 2)), Equals("rb.q", vmMods(-7, 2)), Equals("rc.q", vmDiv(vmMods(-7, 2), 2)))
    check("storeim.q ${q(min)},[main.memq]; loadm.q ra.q,[${quad(-1)}]; divsm.q ra.q,[main.memq]; loadm.q rb.q,[main.memq]; " +
            "storeim.q #5.q,[main.memq]; loadm.q rc.q,[${quad(0)}]; divm.q rc.q,[main.memq]; loadm.q rd.q,[main.memq]",
        Equals("rb.q", vmDivs(min, -1)), Equals("rd.q", vmDiv(5, 0)))

    // shifts by a byte register, and by immediates, of 64 or more shift out all bits
    val c63 = ubyte(63)
    val c64 = ubyte(64)
    val c200 = ubyte(200)
    check("load.q ra.q,#1.q; loadm.b rb.b,[$c63]; lsln.q ra.q,rb.b; load.q rc.q,#1.q; loadm.b rd.b,[$c64]; lsln.q rc.q,rd.b; " +
            "load.q re.q,#-1.q; loadm.b rf.b,[$c200]; lsln.q re.q,rf.b",
        Equals("ra.q", vmLsl(1, 63)), Equals("rc.q", vmLsl(1, 64)), Equals("re.q", vmLsl(-1, 200)))
    check("load.q ra.q,#-1.q; loadm.b rb.b,[$c63]; lsrn.q ra.q,rb.b; load.q rc.q,#-1.q; loadm.b rd.b,[$c64]; lsrn.q rc.q,rd.b; " +
            "load.q re.q,#-1.q; loadm.b rf.b,[$c200]; lsrn.q re.q,rf.b",
        Equals("ra.q", vmLsr(-1, 63)), Equals("rc.q", vmLsr(-1, 64)), Equals("re.q", vmLsr(-1, 200)))
    check("load.q ra.q,${q(min)}; loadm.b rb.b,[$c63]; asrn.q ra.q,rb.b; load.q rc.q,#-8.q; loadm.b rd.b,[$c64]; asrn.q rc.q,rd.b; " +
            "load.q re.q,#-8.q; loadm.b rf.b,[$c200]; asrn.q re.q,rf.b; load.q rg.q,${q(max)}; asrn.q rg.q,rf.b; load.q rh.q,${q(max)}; asrn.q rh.q,rb.b",
        Equals("ra.q", vmAsr(min, 63)), Equals("rc.q", vmAsr(-8, 64)), Equals("re.q", vmAsr(-8, 200)), Equals("rg.q", vmAsr(max, 200)), Equals("rh.q", vmAsr(max, 63)))
    check("loadm.q ra.q,[${quad(1)}]; lsli.q ra.q,#40.q; loadm.q rb.q,[${quad(-1)}]; lsli.q rb.q,#64.q; loadm.q rc.q,[${quad(-1)}]; lsri.q rc.q,${q(1L shl 32)}; " +
            "loadm.q rd.q,[${quad(-2)}]; asri.q rd.q,${q(1L shl 32)}; loadm.q re.q,[${quad(-1)}]; lsri.q re.q,#-1.q; loadm.q rf.q,[${quad(1L shl 62)}]; asri.q rf.q,#64.q; " +
            "loadm.q rg.q,[${quad(-256)}]; asri.q rg.q,#4.q; loadm.q rh.q,[${quad(min)}]; lsri.q rh.q,#63.q",
        Equals("ra.q", vmLsl(1, 40)), Equals("rb.q", vmLsl(-1, 64)), Equals("rc.q", vmLsr(-1, 1L shl 32)), Equals("rd.q", vmAsr(-2, 1L shl 32)),
        Equals("re.q", vmLsr(-1, -1)), Equals("rf.q", vmAsr(1L shl 62, 64)), Equals("rg.q", vmAsr(-256, 4)), Equals("rh.q", vmLsr(min, 63)))

    // extensions to a quad, and the low parts of one
    check("load.b ra.b,#-1.b; extq.b rb.q,ra.b; extqs.b rc.q,ra.b; load.w rd.w,#-1.w; extq.w re.q,rd.w; extqs.w rf.q,rd.w",
        Equals("rb.q", (-1).toUByte().toLong()), Equals("rc.q", (-1).toByte().toLong()), Equals("re.q", (-1).toUShort().toLong()), Equals("rf.q", (-1).toShort().toLong()))
    check("load.l ra.l,#-1.l; extq.l rb.q,ra.l; extqs.l rc.q,ra.l; load.l rd.l,#2147483647.l; extq.l re.q,rd.l; extqs.l rf.q,rd.l",
        Equals("rb.q", (-1).toUInt().toLong()), Equals("rc.q", -1), Equals("re.q", Int.MAX_VALUE.toLong()), Equals("rf.q", Int.MAX_VALUE.toLong()))
    check("load.p ra.p,#main.quads; extq.p rb.q,ra.p; storem.q rb.q,[main.memq]; loadm.p rc.p,[main.memq]; loadi.q rd.q,[rc.p+8]", Equals("rd.q", quadArray[1]))
    val parts = 0x0123_4567_89AB_CDEFL
    check("loadm.q ra.q,[${quad(parts)}]; lsigl.q rb.l,ra.q; lsigw.q rc.w,ra.q; lsigb.q rd.b,ra.q; extq.l re.q,rb.l",
        Equals("rb.l", parts.toInt().toLong()), Equals("rc.w", parts.toUShort().toLong()), Equals("rd.b", parts.toUByte().toLong()), Equals("re.q", parts.toUInt().toLong()))

    // float conversions
    val two63 = 2.0.pow(63)
    check("loadm.f fra.f,[${float(-1.0)}]; ftouq.f rb.q,fra.f; loadm.f frc.f,[${float(two63)}]; ftouq.f rd.q,frc.f; " +
            "loadm.f fre.f,[${float(1e20)}]; ftouq.f rf.q,fre.f; loadm.f frg.f,[${float(Double.NaN)}]; ftouq.f rh.q,frg.f",
        Equals("rb.q", vmFtouq(-1.0)), Equals("rd.q", vmFtouq(two63)), Equals("rf.q", vmFtouq(1e20)), Equals("rh.q", vmFtouq(Double.NaN)))
    check("loadm.f fra.f,[${float(-2.5)}]; ftouq.f rb.q,fra.f; loadm.f frc.f,[${float(1.5 * two63)}]; ftouq.f rd.q,frc.f; " +
            "loadm.f fre.f,[${float(-1e20)}]; ftouq.f rf.q,fre.f; loadm.f frg.f,[${float(2 * two63)}]; ftouq.f rh.q,frg.f",
        Equals("rb.q", vmFtouq(-2.5)), Equals("rd.q", vmFtouq(1.5 * two63)), Equals("rf.q", vmFtouq(-1e20)), Equals("rh.q", vmFtouq(2 * two63)))
    check("loadm.f fra.f,[${float(Double.NaN)}]; ftosq.f rb.q,fra.f; loadm.f frc.f,[${float(1e20)}]; ftosq.f rd.q,frc.f; " +
            "loadm.f fre.f,[${float(-1e20)}]; ftosq.f rf.q,fre.f; loadm.f frg.f,[${float(-2.5)}]; ftosq.f rh.q,frg.f",
        Equals("rb.q", vmFtosq(Double.NaN)), Equals("rd.q", vmFtosq(1e20)), Equals("rf.q", vmFtosq(-1e20)), Equals("rh.q", vmFtosq(-2.5)))
    check("loadm.q ra.q,[${quad(-1)}]; ffromuq.f frb.f,ra.q; ffromsq.f frc.f,ra.q; loadm.q rd.q,[${quad(min)}]; ffromuq.f fre.f,rd.q; ffromsq.f frf.f,rd.q",
        FloatEquals("frb.f", vmFfromuq(-1)), FloatEquals("frc.f", vmFfromsq(-1)), FloatEquals("fre.f", vmFfromuq(min)), FloatEquals("frf.f", vmFfromsq(min)))

    // compares of all 64 bits, also where only the upper 32 bits differ
    for ((left, right) in listOf(0x1_0000_0005L to 5L, 0xFFFF_FFFF_0000_0005uL.toLong() to 5L, 5L to 0x1_0000_0005L, min to min)) {
        val unsignedLeft = left.toULong()
        val unsignedRight = right.toULong()
        val branches = listOf("bgts" to (left > right), "blts" to (left < right), "bges" to (left >= right), "bles" to (left <= right),
            "bgt" to (unsignedLeft > unsignedRight), "blt" to (unsignedLeft < unsignedRight), "bge" to (unsignedLeft >= unsignedRight), "ble" to (unsignedLeft <= unsignedRight))
        for ((branch, taken) in branches)
            check("loadm.q ra.q,[${quad(left)}]; " + if (taken) "$branch.q ra.q,${q(right)},OK; jump FAIL; _OK:" else "$branch.q ra.q,${q(right)},FAIL")
        val registerBranches = listOf("bgtsr" to (left > right), "bgesr" to (left >= right), "bgtr" to (unsignedLeft > unsignedRight), "bger" to (unsignedLeft >= unsignedRight))
        for ((branch, taken) in registerBranches)
            check("loadm.q ra.q,[${quad(left)}]; loadm.q rb.q,[${quad(right)}]; " + if (taken) "$branch.q ra.q,rb.q,OK; jump FAIL; _OK:" else "$branch.q ra.q,rb.q,FAIL")
        check("loadm.q ra.q,[${quad(left)}]; cmpi.q ra.q,${q(right)}", ZeroFlag(left == right))
        check("loadm.q ra.q,[${quad(left)}]; loadm.q rb.q,[${quad(right)}]; cmp.q ra.q,rb.q", ZeroFlag(left == right))
    }

    // single bit operations on bit 63
    check("loadm.q ra.q,[${quad(min)}]; bittst.q ra.q,#63.b", ZeroFlag(vmBitClear(min, 63)))
    check("loadm.q ra.q,[${quad(max)}]; bittst.q ra.q,#63.b", ZeroFlag(vmBitClear(max, 63)))
    check("loadm.q ra.q,[${quad(1)}]; bittst.q ra.q,#32.b", ZeroFlag(vmBitClear(1, 32)))
    check("loadm.q ra.q,[${quad(0)}]; bitset.q ra.q,#63.b; loadm.q rb.q,[${quad(-1)}]; bitclr.q rb.q,#63.b; loadm.q rc.q,[${quad(0)}]; bittog.q rc.q,#40.b; bittog.q rc.q,#0.b",
        Equals("ra.q", 0L or (1L shl 63)), Equals("rb.q", -1L and (1L shl 63).inv()), Equals("rc.q", 0L xor (1L shl 40) xor 1L))

    // memory access moves exactly 8 bytes
    val fill = quadMemory.indices.joinToString("; ") { "storeim.q ${q(quadMemory[it])},[main.qmem+${8 * it}]" }
    val readBack = "loadm.q rf.q,[main.qmem]; loadm.q rg.q,[main.qmem+8]; loadm.q rh.q,[main.qmem+16]"
    val stored = 0xFEDC_BA98_7654_3210uL.toLong()
    check("load.w ra.w,#2.w; loadx.q rb.q,[main.quads+ra.w*8]; load.w rc.w,#0.w; loadx.q rd.q,[main.quads+8+rc.w*8]",
        Equals("rb.q", quadArray[2]), Equals("rd.q", quadArray[1]))
    check("$fill; load.w ra.w,#1.w; loadm.q rb.q,[${quad(stored)}]; storex.q rb.q,[main.qmem+ra.w*8]; $readBack",
        Equals("rf.q", quadMemory[0]), Equals("rg.q", stored), Equals("rh.q", quadMemory[2]))
    check("$fill; load.w ra.w,#2.w; storezx.q [main.qmem+ra.w*8]; storezm.q [main.qmem]; $readBack",
        Equals("rf.q", 0), Equals("rg.q", quadMemory[1]), Equals("rh.q", 0))
    check("$fill; load.p ra.p,#main.qmem; loadi.q rb.q,[ra.p+16]; storei.q rb.q,[ra.p]; storezi.q [ra.p+8]; $readBack",
        Equals("rb.q", quadMemory[2]), Equals("rf.q", quadMemory[2]), Equals("rg.q", 0), Equals("rh.q", quadMemory[2]))
    check("$fill; loadm.q ra.q,[${quad(stored)}]; storem.q ra.q,[main.qmem+8]; $readBack",
        Equals("rf.q", quadMemory[0]), Equals("rg.q", stored), Equals("rh.q", quadMemory[2]))

    // the value stack keeps all 64 bits of every quad, also below a byte
    check("loadm.q ra.q,[${quad(stored)}]; push.q ra.q; loadm.q rb.q,[${quad(-2)}]; push.q rb.q; load.b rc.b,#7.b; push.b rc.b; " +
            "pop.b rd.b; pop.q re.q; pop.q rf.q",
        Equals("rd.b", 7), Equals("re.q", -2), Equals("rf.q", stored))

    // QUADARRAY_CONTAINS compares whole elements, and the initial values of quads keep all 64 bits
    val contains = IMSyscall.QUADARRAY_CONTAINS.number
    fun found(needle: Long, count: Int) = if (needle in quadArray.take(count)) 1L else 0L
    val lowOnly = quadArray[0].toUInt().toLong()
    check("loadm.q ra.q,[${quad(quadArray[2])}]; load.l rb.l,#main.quads; load.w rc.w,#${quadArray.size}.w; syscall $contains(ra.q,rb.l,rc.w):rd.b; " +
            "loadm.q re.q,[${quad(lowOnly)}]; syscall $contains(re.q,rb.l,rc.w):rf.b",
        Equals("rd.b", found(quadArray[2], quadArray.size)), Equals("rf.b", found(lowOnly, quadArray.size)))
    check("loadm.q ra.q,[${quad(quadArray[3])}]; load.l rb.l,#main.quads; load.w rc.w,#3.w; syscall $contains(ra.q,rb.l,rc.w):rd.b; " +
            "load.w re.w,#4.w; syscall $contains(ra.q,rb.l,re.w):rf.b",
        Equals("rd.b", found(quadArray[3], 3)), Equals("rf.b", found(quadArray[3], 4)))
    check("loadm.q ra.q,[${quad(max - 1)}]; loadm.q rb.q,[${quad(-2, DataType.UQUAD)}]; loadm.q rc.q,[${quad(9007199254740993)}]",
        Equals("ra.q", max - 1), Equals("rb.q", -2), Equals("rc.q", 9007199254740993))
}

/** Small subroutines for the tests that check the exact LLVM IR of a quad lowering. */
private fun TestProgram.quadSubs() {
    variable("main.qarray", DataType.arrayFor(BaseDataType.QUAD, target), quads(1, 2), 2u)
    sub("main.sdivq", listOf("a" to DataType.QUAD, "b" to DataType.QUAD), listOf(DataType.QUAD), """
        _main.sdivq:
        loadm.q r1.q,[main.sdivq.a]
        loadm.q r2.q,[main.sdivq.b]
        loadr.q r3.q,r1.q
        divsr.q r1.q,r2.q
        modsr.q r3.q,r2.q
        addr.q r1.q,r3.q
        returnr.q r1.q
        """)
    sub("main.udivq", listOf("a" to DataType.UQUAD, "b" to DataType.UQUAD), listOf(DataType.UQUAD), """
        _main.udivq:
        loadm.q r1.q,[main.udivq.a]
        loadm.q r2.q,[main.udivq.b]
        loadr.q r3.q,r1.q
        divr.q r1.q,r2.q
        modr.q r3.q,r2.q
        addr.q r1.q,r3.q
        returnr.q r1.q
        """)
    sub("main.constdivq", listOf("a" to DataType.QUAD), listOf(DataType.QUAD), """
        _main.constdivq:
        loadm.q r1.q,[main.constdivq.a]
        loadr.q r2.q,r1.q
        loadr.q r3.q,r1.q
        loadr.q r4.q,r1.q
        divs.q r1.q,#-1.q
        mods.q r2.q,#-1.q
        divs.q r3.q,#0.q
        div.q r4.q,#0.q
        addr.q r1.q,r2.q
        addr.q r1.q,r3.q
        addr.q r1.q,r4.q
        returnr.q r1.q
        """)
    sub("main.fconvq", listOf("f" to DataType.FLOAT), listOf(DataType.FLOAT), """
        _main.fconvq:
        loadm.f fr1.f,[main.fconvq.f]
        ftosq.f r1.q,fr1.f
        ftouq.f r2.q,fr1.f
        ffromsq.f fr2.f,r1.q
        ffromuq.f fr3.f,r2.q
        addr.f fr2.f,fr3.f
        returnr.f fr2.f
        """)
    sub("main.extendq", listOf("b" to DataType.BYTE, "w" to DataType.WORD, "l" to DataType.LONG, "p" to DataType.pointer(BaseDataType.UBYTE)),
        listOf(DataType.LONG), """
        _main.extendq:
        loadm.b r1.b,[main.extendq.b]
        loadm.w r2.w,[main.extendq.w]
        loadm.l r3.l,[main.extendq.l]
        loadm.p r4.p,[main.extendq.p]
        extq.b r5.q,r1.b
        extqs.b r6.q,r1.b
        extq.w r7.q,r2.w
        extqs.w r8.q,r2.w
        extq.l r9.q,r3.l
        extqs.l r10.q,r3.l
        extq.p r11.q,r4.p
        addr.q r5.q,r6.q
        addr.q r5.q,r7.q
        addr.q r5.q,r8.q
        addr.q r5.q,r9.q
        addr.q r5.q,r10.q
        addr.q r5.q,r11.q
        lsigl.q r12.l,r5.q
        lsigw.q r13.w,r5.q
        lsigb.q r14.b,r5.q
        returnr.l r12.l
        """)
    sub("main.shiftsq", listOf("v" to DataType.QUAD, "n" to DataType.UBYTE), listOf(DataType.QUAD), $$"""
        _main.shiftsq:
        loadm.q r1.q,[main.shiftsq.v]
        loadm.b r2.b,[main.shiftsq.n]
        loadr.q r3.q,r1.q
        lsln.q r3.q,r2.b
        loadr.q r4.q,r1.q
        asrn.q r4.q,r2.b
        loadr.q r5.q,r1.q
        lsli.q r5.q,#40.q
        loadr.q r6.q,r1.q
        lsri.q r6.q,#64.q
        loadr.q r7.q,r1.q
        lsli.q r7.q,#$100000000.q
        loadr.q r8.q,r1.q
        asri.q r8.q,#$100000000.q
        loadr.q r9.q,r1.q
        asri.q r9.q,#64.q
        loadr.q r10.q,r1.q
        lsri.q r10.q,#-1.q
        returnr.q r3.q
        """)
    sub("main.stackq", listOf("v" to DataType.QUAD), listOf(DataType.QUAD), """
        _main.stackq:
        loadm.q r1.q,[main.stackq.v]
        push.q r1.q
        pop.q r2.q
        returnr.q r2.q
        """)
    sub("main.findq", listOf("q" to DataType.QUAD), listOf(DataType.UBYTE), """
        _main.findq:
        loadm.q r1.q,[main.findq.q]
        load.l r2.l,#main.qarray
        load.w r3.w,#2.w
        syscall ${IMSyscall.QUADARRAY_CONTAINS.number}(r1.q,r2.l,r3.w):r4.b
        returnr.b r4.b
        """)
    sub("main.bitsq", listOf("v" to DataType.QUAD), listOf(DataType.UBYTE), $$"""
        _main.bitsq:
        loadm.q r1.q,[main.bitsq.v]
        bitset.q r1.q,#63.b
        bittst.q r1.q,#63.b
        bstne main.bitsq.set
        bgts.q r1.q,#$100000005.q,main.bitsq.set
        bgt.q r1.q,#5.q,main.bitsq.set
        returni.b #0.b
        _main.bitsq.set:
        returni.b #1.b
        """)
    sub("main.memoryq", listOf("i" to DataType.UWORD), listOf(DataType.QUAD), $$"""
        _main.memoryq:
        loadm.w r1.w,[main.memoryq.i]
        loadx.q r2.q,[main.qarray+r1.w*8]
        storex.q r2.q,[main.qarray+8+r1.w*8]
        storeim.q #$123456789.q,[main.qarray]
        storezx.q [main.qarray+r1.w*8]
        returnr.q r2.q
        """)
}

/** Quad globals whose Double values are inexact; the struct main.Big has a quad, a uquad and a ubyte field. */
private fun quadGlobals(): LlvmModule {
    val target = LlvmTarget()
    val uquadMax = 0xFFFF_FFFF_FFFF_FFFFuL.toLong()
    fun variable(name: String, dt: DataType, init: IRVariableInitializer, length: UInt? = null) =
        IRStStaticVariable(name, dt, init, length, ZeropageWish.DONTCARE, 0u, false)
    fun number(value: Double, bits: Long) = IRStSymbolicReference.Numeric(value, bits)
    val st = IRSymbolTable()
    st.add(variable("main.q", DataType.QUAD, IRVariableInitializer.Numeric(Long.MAX_VALUE.toDouble(), Long.MAX_VALUE - 1)))
    st.add(variable("main.u", DataType.UQUAD, IRVariableInitializer.Numeric(1.8446744073709552E19, uquadMax - 1)))
    st.add(variable("main.odd", DataType.QUAD, IRVariableInitializer.Numeric(9007199254740992.0, 9007199254740993)))
    st.add(variable("main.plain", DataType.QUAD, IRVariableInitializer.Numeric(-5.0)))
    st.add(variable("main.qs", DataType.arrayFor(BaseDataType.QUAD, target), IRVariableInitializer.Array(listOf(
        number(Long.MAX_VALUE.toDouble(), Long.MAX_VALUE - 1), number(9007199254740992.0, 9007199254740993), number(-9.223372036854775808E18, Long.MIN_VALUE))), 3u))
    st.add(variable("main.us", DataType.arrayFor(BaseDataType.UQUAD, target), IRVariableInitializer.Array(listOf(
        number(1.8446744073709552E19, uquadMax - 1), number(9.223372036854775808E18, Long.MIN_VALUE + 1))), 2u))
    st.add(IRStStructDef("main.Big", listOf(IRStStructField(DataType.QUAD, "q"), IRStStructField(DataType.UQUAD, "u"), IRStStructField(DataType.UBYTE, "b")), 17u))
    st.add(IRStStructInstance("prog8_struct_instances.big", "main.Big", listOf(
        IRStructInitValue(BaseDataType.QUAD, number(Long.MAX_VALUE.toDouble(), Long.MAX_VALUE - 1)),
        IRStructInitValue(BaseDataType.UQUAD, number(1.8446744073709552E19, uquadMax - 1)),
        IRStructInitValue(BaseDataType.UBYTE, IRStSymbolicReference.Numeric(7.0))), 17u))
    st.add(IRStConstant("main.BIGQ", DataType.QUAD, Long.MAX_VALUE.toDouble(), quadBits = Long.MAX_VALUE - 1))
    st.add(IRStConstant("main.BIGUQ", DataType.UQUAD, 1.8446744073709552E19, quadBits = uquadMax - 1))
    val module = LlvmModule("quads", target.targetTriple, target.dataLayout)
    LlvmGlobalEmitter(IRProgram("test", st, CompilationOptions.builder(target).floats(true).compilerVersion("99.99").build(), target)).emit(module)
    return module
}

private fun LlvmModule.printed(name: String): String = LlvmPrinter.format(globals.single { it.name == name })
