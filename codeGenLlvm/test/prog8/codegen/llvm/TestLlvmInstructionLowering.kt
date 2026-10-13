package prog8.codegen.llvm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import prog8.code.core.BaseDataType
import prog8.code.core.DataType
import prog8.intermediate.IMSyscall
import prog8.intermediate.IRStSymbolicReference
import prog8.intermediate.IRVariableInitializer
import kotlin.math.abs


class TestLlvmInstructionLowering: FunSpec({

    val focused = TestProgram().apply { focusedSubs() }.module()

    test("LONG registers hold whole 64-bit addresses, and 32-bit longs sign extended") {
        val lines = focused.function("main.address").lines()
        lines shouldHave "  %r1.slot = alloca i64"
        lines shouldHave "  %r3.slot = alloca i64"
        lines shouldHave "  %r1.1 = ptrtoint ptr @main.buffer to i64"
        lines shouldHave "  %r1.3 = add i64 %r1.2, 3"
        lines shouldHave "  %r1.5 = inttoptr i64 %r1.4 to ptr"
        lines shouldHave "  store i8 %r2.1, ptr %r1.5"
        lines shouldHave "  store i64 -5, ptr %r3.slot"
        lines shouldHave "  store i64 %r3.1, ptr %r4.slot"
        lines shouldHave "  %r5.2 = sext i32 %r5.1 to i64"
        lines shouldHave "  %cast.1 = trunc i64 %r4.1 to i32"
        lines shouldHave "  store i32 %cast.1, ptr @main.long, align 1"
    }

    test("division by zero gives the VM's results, and signed division works one size wider") {
        val divide = focused.function("main.divide").lines()
        divide shouldHave "  %zero.1 = icmp eq i8 %r2.2, 0"
        divide shouldHave "  %divisor.1 = select i1 %zero.1, i8 1, i8 %r2.2"
        divide shouldHave "  %r1.3 = udiv i8 %r1.2, %divisor.1"
        divide shouldHave "  %r1.4 = select i1 %zero.1, i8 -1, i8 %r1.3"
        divide shouldHave "  store i8 -1, ptr %r1.slot"
        val signed = focused.function("main.sdivide").lines()
        signed shouldHave "  %divisor.1 = sext i16 %r2.2 to i32"
        signed shouldHave "  %divisor.2 = select i1 %zero.1, i32 1, i32 %divisor.1"
        signed shouldHave "  %dividend.1 = sext i16 %r1.2 to i32"
        signed shouldHave "  %r1.3 = sdiv i32 %dividend.1, %divisor.2"
        signed shouldHave "  %r1.4 = trunc i32 %r1.3 to i16"
        signed shouldHave "  %r1.5 = select i1 %zero.1, i16 32767, i16 %r1.4"
        val float = focused.function("main.fdivide").lines()
        float shouldHave "  %zero.1 = fcmp oeq double %fr2.2, 0.0"
        float shouldHave "  %fr1.4 = select i1 %zero.1, double 1.7976931348623157E308, double %fr1.3"
    }

    test("counts of the bit width or more shift out all bits, single bit shifts and rotates go through the carry flag") {
        val shifts = focused.function("main.shifts").lines()
        shifts shouldHave "  %count.1 = zext i8 %r2.2 to i16"
        shifts shouldHave "  %inrange.1 = icmp ult i16 %count.1, 16"
        shifts shouldHave "  %r1.3 = shl i16 %r1.2, %count.1"
        shifts shouldHave "  %r1.4 = select i1 %inrange.1, i16 %r1.3, i16 0"
        shifts shouldHave "  %count.3 = select i1 %inrange.2, i16 %count.2, i16 15"
        shifts shouldHave "  %r1.6 = ashr i16 %r1.5, %count.3"
        shifts shouldHave "  store i16 0, ptr %r1.slot"
        val rotate = focused.function("main.rotate").lines()
        rotate shouldHave "  %carry.1 = trunc i8 %r1.2 to i1"
        rotate shouldHave "  %r1.3 = lshr i8 %r1.2, 1"
        rotate shouldHave "  %carry.2 = icmp slt i8 %r1.4, 0"
        rotate shouldHave "  %r1.5 = zext i1 %carry.1 to i8"
        rotate shouldHave "  %r1.7 = or i8 %r1.6, %r1.5"
    }

    test("indexed and indirect memory access offset the address with getelementptr i8, pointer array elements are ptrs") {
        val access = focused.function("main.access").lines()
        access shouldHave "  %index.1 = zext i16 %r1.2 to i64"
        access shouldHave "  %offset.1 = mul i64 %index.1, 2"
        access shouldHave "  %addr.1 = getelementptr i8, ptr getelementptr inbounds (i8, ptr @main.words, i64 2), i64 %offset.1"
        access shouldHave "  %r2.1 = load i16, ptr %addr.1, align 1"
        access shouldHave "  store ptr @main.words, ptr %r3.slot"
        access shouldHave "  %addr.2 = getelementptr i8, ptr %r3.1, i64 4"
        val rename = focused.function("main.rename").lines()
        rename shouldHave "  %store.1.index = zext i16 %r2.1 to i64"
        rename shouldHave "  %store.1.elem = getelementptr inbounds ptr, ptr @main.names, i64 %store.1.index"
        rename shouldHave "  %store.1.ptr = inttoptr i64 %r1.2 to ptr"
        rename shouldHave "  store ptr %store.1.ptr, ptr %store.1.elem"
    }

    test("LOADP_INC and STOREP_INC advance the pointer variable by the access size, also one that is a word variable") {
        val lines = focused.function("main.next").lines()
        lines shouldHave "  %ptr.1 = load ptr, ptr @main.ptr, align 1"
        lines shouldHave "  %r1.1 = load i8, ptr %ptr.1"
        lines shouldHave "  %ptr.2 = getelementptr i8, ptr %ptr.1, i64 1"
        lines shouldHave "  store ptr %ptr.2, ptr @main.ptr, align 1"
        lines shouldHave "  store i16 %r2.1, ptr %ptr.3, align 1"
        lines shouldHave "  %ptr.4 = getelementptr i8, ptr %ptr.3, i64 2"
        lines shouldHave "  %ptr.6 = inttoptr i16 %ptr.5 to ptr"
        lines shouldHave "  %ptr.8 = ptrtoint ptr %ptr.7 to i16"
        lines shouldHave "  store i16 %ptr.8, ptr @main.wptr, align 1"
    }

    test("syscalls call internal helper functions and LLVM intrinsics") {
        val lines = focused.function("main.sys").lines()
        lines shouldHave "  %order.1 = call i8 @.compare.strings(ptr %r1.3, ptr %r2.3)"
        lines shouldHave "  call void @llvm.memmove.p0.p0.i64(ptr %r2.5, ptr %r1.5, i64 %cast.1, i1 false)"
        lines shouldHave "  call void @llvm.memset.p0.i64(ptr %r2.7, i8 %r3.1, i64 %cast.2, i1 false)"
        lines shouldHave "  %smax.1 = call i8 @llvm.smax.i8(i8 %r3.2, i8 %r5.1)"
        lines shouldHave "  %smin.1 = call i8 @llvm.smin.i8(i8 %smax.1, i8 %r6.1)"
        val module = LlvmPrinter.print(focused).lines()
        module shouldHave "define internal i8 @.compare.strings(ptr %first, ptr %second) {"
        module shouldHave "declare void @llvm.memmove.p0.p0.i64(ptr, ptr, i64, i1)"
        module shouldHave "declare i8 @llvm.smax.i8(i8, i8)"
    }

    test("PUSH, POP and the status byte use an internal value stack; flag slots start cleared") {
        val lines = focused.function("main.stack").lines()
        lines shouldHave "  store i1 false, ptr %flag.carry"
        lines shouldHave "  %sp.2 = getelementptr i8, ptr @.stack.data, i64 %sp.1"
        lines shouldHave "  store i16 %r1.1, ptr %sp.2, align 1"
        lines shouldHave "  %sp.3 = add i64 %sp.1, 2"
        lines shouldHave "  %sp.5 = sub i64 %sp.4, 2"
        lines shouldHave "  %r2.1 = load i16, ptr %sp.6, align 1"
        lines shouldHave "  %status.2 = shl i8 %status.1, 7"
        lines shouldHave "  %status.12 = and i8 %status.11, -128"
        lines shouldHave "  store i1 %flag.8, ptr %flag.carry"
        val module = LlvmPrinter.print(focused).lines()
        module shouldHave "@.stack.top = internal global i64 0"
        module shouldHave "@.stack.data = internal global [1024 x i8] zeroinitializer"
    }

    test("CALLI leaves its result in calling convention slot 10, JUMPI calls and returns, BREAKPOINT traps") {
        val lines = focused.function("main.indirect").lines()
        lines shouldHave "  %calli.1 = call i16 %r1.3()"
        lines shouldHave "  %cast.1 = zext i16 %calli.1 to i64"
        lines shouldHave "  store i64 %cast.1, ptr @.slot.s10"
        lines shouldHave "  call void @llvm.debugtrap()"
        lines shouldHave "  %jumpi.1 = call i8 %r1.5()"
        lines shouldHave "  ret i8 %jumpi.1"
    }

    test("IR without a translation for llvm64 is rejected with a clear message") {
        fun failure(code: String) = shouldThrow<IllegalArgumentException> {
            TestProgram().apply {
                variable("main.names", DataType.arrayFor(BaseDataType.STR, target), IRVariableInitializer.Array(listOf(IRStSymbolicReference.Symbol("main.s"))), 1u)
                string("main.s", "s")
                sub("main.f", code = "_main.f:\n$code\nreturn")
            }.module()
        }.message.orEmpty()
        failure("callfar #1,40960()") shouldContain "another memory bank"
        failure("syscall ${IMSyscall.CALLFAR.number}()") shouldContain "another memory bank"
        failure("syscall 2()") shouldContain "has syscall 2, which the llvm64 target doesn't have"
        failure("load.l r1.l,#5.l\nsyscall ${IMSyscall.COMPARE_STRINGS.number}(r1.l):r2.b") shouldContain "needs 2 arguments, not 1"
        failure("load.l r1.l,#5.l\next.l r2.l,r1.l") shouldContain "can only extend a byte or a word"
        failure("load.p r1.p,#main.s\nconcat.p r2.l,r1.p,r1.p") shouldContain "can only join bytes or words"
        failure("loadm.b r1.b,[frame:-4]") shouldContain "stack frame slot"
        failure("loadp_inc.b r1.b,[main.names]") shouldContain "advances an element of pointer array main.names"
    }

    test("llvm-as accepts the lowering of every kind of instruction").config(enabledOrReasonIf = requiresLlvmAs) {
        for (module in listOf(focused, TestProgram().apply { checkProgram() }.module())) {
            val (exitCode, output) = runLlvmAs(LlvmPrinter.print(module))
            withClue(output) { exitCode shouldBe 0 }
        }
    }

    test("the instructions compute what the VM computes, in a native arm64 macOS program").config(enabledOrReasonIf = requiresNativeArm64Mac) {
        val module = TestProgram().apply { checkProgram() }.module().withMainCalling("main.check")
        val (exitCode, output) = compileAndRun(LlvmPrinter.print(module))
        withClue("exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
    }
})


private sealed interface Expect
private data class Is(val register: String, val value: Long) : Expect
/** Exactly [value] when [tolerance] is 0, and never NaN. */
private data class IsFloat(val register: String, val value: Double, val tolerance: Double = 0.0) : Expect
private data class CarryIs(val set: Boolean) : Expect
private data class ZeroIs(val set: Boolean) : Expect

/**
 * Builds main.check, which returns 0, or the number of the first check whose results differ from the VM's.
 * A check is IR lines separated by "; ", using the registers ra..rh and fra..frh, which get their own numbers in every check.
 */
private class Checks {
    private val code = mutableListOf<String>()
    private var count = 0

    fun check(ir: String, vararg expected: Expect) {
        val number = ++count
        val fail = "main.check.fail$number"
        fun numbered(text: String) = registerLetters.replace(text) { "${it.groupValues[1]}r${number * 10 + (it.groupValues[2][0] - 'a')}" }
        code += ir.split("; ").map { numbered(it.trim()) }
        for (expect in expected) {
            when (expect) {
                is Is -> {
                    val type = expect.register.substringAfterLast('.')
                    code += "cmpi.$type ${numbered(expect.register)},#${expect.value}.$type"
                    code += "bstne $fail"
                }
                is IsFloat -> {
                    val register = numbered(expect.register)
                    if (expect.tolerance == 0.0) {
                        code += compareFloat(register, expect.value, 0, fail)
                        code += compareFloat(register, expect.value - maxOf(1.0, abs(expect.value)), 1, fail)
                    } else {
                        code += compareFloat(register, expect.value - expect.tolerance, 1, fail)
                        code += compareFloat(register, expect.value + expect.tolerance, -1, fail)
                    }
                }
                is CarryIs -> code += if (expect.set) "bstcc $fail" else "bstcs $fail"
                is ZeroIs -> code += if (expect.set) "bstne $fail" else "bsteq $fail"
            }
        }
    }

    // FCOMP gives 0 for NaN as well, which a comparison with a lower bound catches
    private fun compareFloat(register: String, bound: Double, order: Int, fail: String) =
        listOf("load.f fr9000.f,#$bound.f", "fcomp.f r9000.b,$register,fr9000.f", "cmpi.b r9000.b,#$order.b", "bstne $fail")

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

private val registerLetters = Regex("""\b(f?)r([a-h])(?=\.)""")

private fun numbers(vararg values: Number) = IRVariableInitializer.Array(values.map { IRStSymbolicReference.Numeric(it.toDouble()) })

private fun TestProgram.checkProgram() {
    // shared, so that LLVM can't fold their values into the code: these checks run on the cpu
    variable("main.zero", DataType.UBYTE, IRVariableInitializer.Numeric(0.0), shared = true)
    variable("main.zerow", DataType.UWORD, IRVariableInitializer.Numeric(0.0), shared = true)
    variable("main.zerol", DataType.LONG, IRVariableInitializer.Numeric(0.0), shared = true)
    variable("main.zerof", DataType.FLOAT, IRVariableInitializer.Numeric(0.0), shared = true)
    variable("main.minusone", DataType.BYTE, IRVariableInitializer.Numeric(-1.0), shared = true)
    variable("main.twenty", DataType.UBYTE, IRVariableInitializer.Numeric(20.0), shared = true)
    variable("main.mem.b", DataType.UBYTE)
    variable("main.mem.w", DataType.UWORD)
    variable("main.mem.l", DataType.LONG)
    variable("main.bytes", DataType.arrayFor(BaseDataType.UBYTE, target), numbers(1, 2, 3, 4, 5, 6, 7, 8), 8u)
    variable("main.words", DataType.arrayFor(BaseDataType.UWORD, target), numbers(10, 20, 30, 40), 4u)
    variable("main.words2", DataType.arrayFor(BaseDataType.UWORD, target), numbers(5, 6, 7, 8), 4u)
    variable("main.longs", DataType.arrayFor(BaseDataType.LONG, target), numbers(-1, 100000), 2u)
    variable("main.floats", DataType.arrayFor(BaseDataType.FLOAT, target), numbers(1.5, 2.5), 2u)
    variable("main.src", DataType.arrayFor(BaseDataType.UBYTE, target), numbers(9, 8, 7, 6), 4u)
    variable("main.dst", DataType.arrayFor(BaseDataType.UBYTE, target), length = 4u)
    variable("main.fill", DataType.arrayFor(BaseDataType.UWORD, target), length = 3u)
    variable("main.ptr", DataType.pointer(BaseDataType.UBYTE))
    string("main.abc", "abc")
    string("main.abd", "abd")
    string("main.ab", "ab")
    listOf("aa", "bb", "cc", "zz").forEachIndexed { index, text -> string("main.s$index", text) }
    variable("main.names", DataType.arrayFor(BaseDataType.STR, target),
        IRVariableInitializer.Array((0..2).map { IRStSymbolicReference.Symbol("main.s$it") }), 3u)
    sub("main.answer", returns = listOf(DataType.UWORD), code = "_main.answer:\nreturni.w #4242.w")
    sub("main.seven", returns = listOf(DataType.UBYTE), code = "_main.seven:\nreturni.b #7.b")
    sub("main.jumper", returns = listOf(DataType.UBYTE), code = "_main.jumper:\nload.l r1.l,#main.seven\njumpi (r1.p)")
    sub("main.peek", listOf("p" to DataType.pointer(BaseDataType.UBYTE)), listOf(DataType.UBYTE),
        "_main.peek:\nloadm.p r1.p,[main.peek.p]\nloadi.b r2.b,[r1.p]\nreturnr.b r2.b")
    sub("main.check", returns = listOf(DataType.UBYTE), code = vmChecks().subroutine())
}

/** The expected results are those of the virtual machine (VirtualMachine.kt, VmArithmetic.kt and SysCalls.kt). */
private fun vmChecks() = Checks().apply {
    // integer arithmetic wraps around; LONG registers compute with 64 bits, but read back as 32-bit longs
    check("load.b ra.b,#200.b; add.b ra.b,#100.b", Is("ra.b", 44))
    check("load.w ra.w,#5.w; sub.w ra.w,#10.w", Is("ra.w", 65531))
    check("load.b ra.b,#20.b; load.b rb.b,#13.b; mulr.b ra.b,rb.b", Is("ra.b", 4))
    check("load.w ra.w,#-300.w; muls.w ra.w,#7.w", Is("ra.w", -2100))
    check("load.l ra.l,#100000.l; add.l ra.l,#-200000.l", Is("ra.l", -100000))
    check("load.l ra.l,#65536.l; muls.l ra.l,#65537.l", Is("ra.l", 65536))
    check("load.b ra.b,#5.b; neg.b ra.b", Is("ra.b", 251))
    check("load.w ra.w,#255.w; inv.w ra.w", Is("ra.w", 65280))
    check("load.l ra.l,#2147483647.l; inc.l ra.l", Is("ra.l", 2147483648))
    check("load.l ra.l,#-1.l; loadm.l rb.l,[main.zerol]; addr.l rb.l,ra.l; dec.l rb.l", Is("rb.l", -2))
    check("load.w ra.w,#300.w; load.w rb.w,#500.w; subr.w ra.w,rb.w", Is("ra.w", 65336))
    check("load.l ra.l,#-5.l; neg.l ra.l; load.w rb.w,#1.w; dec.w rb.w; dec.w rb.w", Is("ra.l", 5), Is("rb.w", 65535))
    check("load.l ra.l,#100000.l; mul.l ra.l,#100000.l; load.l rb.l,#-1000.l; square.l rc.l,rb.l", Is("ra.l", 1410065408), Is("rc.l", 1000000))
    check("load.b ra.b,#200.b; mul.b ra.b,#3.b; load.b rb.b,#-3.b; muls.b rb.b,#50.b", Is("ra.b", 88), Is("rb.b", 106))

    // division by zero, and MIN / -1, give the VM's results
    check("load.b ra.b,#7.b; loadm.b rb.b,[main.zero]; divr.b ra.b,rb.b", Is("ra.b", 255))
    check("load.b ra.b,#7.b; loadm.b rb.b,[main.zero]; modr.b ra.b,rb.b", Is("ra.b", 0))
    check("load.w ra.w,#-7.w; loadm.w rb.w,[main.zerow]; divsr.w ra.w,rb.w", Is("ra.w", 32767))
    check("load.b ra.b,#-128.b; loadm.b rb.b,[main.minusone]; loadr.b rc.b,ra.b; divsr.b ra.b,rb.b; modsr.b rc.b,rb.b", Is("ra.b", 128), Is("rc.b", 0))
    check("load.l ra.l,#-7.l; mods.l ra.l,#3.l; load.l rb.l,#-7.l; divs.l rb.l,#2.l", Is("ra.l", -1), Is("rb.l", -3))
    check("load.w ra.w,#65535.w; div.w ra.w,#256.w", Is("ra.w", 255))
    check("load.w ra.w,#1000.w; load.w rb.w,#7.w; divmodr.w ra.w,rb.w", Is("ra.w", 142), Is("rb.w", 6))
    check("load.b ra.b,#-100.b; sdivmod.b ra.b,rb.b,#7.b", Is("ra.b", -14), Is("rb.b", -2))
    check("load.w ra.w,#1000.w; mod.w ra.w,#7.w; load.w rb.w,#1000.w; divmod.w rb.w,rc.w,#7.w", Is("ra.w", 6), Is("rb.w", 142), Is("rc.w", 6))
    check("load.w ra.w,#-1000.w; load.w rb.w,#7.w; sdivmodr.w ra.w,rb.w", Is("ra.w", -142), Is("rb.w", -6))
    check("storeim.w #-1000.w,[main.mem.w]; load.w ra.w,#-7.w; divsm.w ra.w,[main.mem.w]; loadm.w rb.w,[main.mem.w]", Is("rb.w", 142))
    check("load.l ra.l,#5.l; loadm.l rb.l,[main.zerol]; divsr.l ra.l,rb.l", Is("ra.l", 2147483647))
    check("load.l ra.l,#-2147483648.l; load.l rb.l,#-1.l; loadm.l rc.l,[main.zerol]; addr.l rb.l,rc.l; divsr.l ra.l,rb.l", Is("ra.l", -2147483648))
    check("load.f fra.f,#1.5.f; loadm.f frb.f,[main.zerof]; divsr.f fra.f,frb.f", IsFloat("fra.f", Double.MAX_VALUE))
    check("load.f fra.f,#7.5.f; divs.f fra.f,#2.5.f; load.f frb.f,#7.5.f; div.f frb.f,#0.0.f", IsFloat("fra.f", 3.0), IsFloat("frb.f", Double.MAX_VALUE))

    // floats
    check("load.f fra.f,#1.5.f; add.f fra.f,#2.25.f; mul.f fra.f,#2.0.f; sub.f fra.f,#0.5.f", IsFloat("fra.f", 7.0))
    check("load.f fra.f,#-2.5.f; neg.f fra.f; load.f frb.f,#1.5.f; square.f frc.f,frb.f", IsFloat("fra.f", 2.5), IsFloat("frc.f", 2.25))
    check("load.f fra.f,#16.0.f; sqrt.f frb.f,fra.f", IsFloat("frb.f", 4.0))
    check("load.f fra.f,#2.5.f; fround.f frb.f,fra.f; load.f frc.f,#3.5.f; fround.f frd.f,frc.f; load.f fre.f,#-2.5.f; fround.f frf.f,fre.f",
        IsFloat("frb.f", 2.0), IsFloat("frd.f", 4.0), IsFloat("frf.f", -2.0))
    check("load.f fra.f,#-2.5.f; ffloor.f frb.f,fra.f; load.f frc.f,#2.1.f; fceil.f frd.f,frc.f; fabs.f fre.f,fra.f",
        IsFloat("frb.f", -3.0), IsFloat("frd.f", 3.0), IsFloat("fre.f", 2.5))
    check("load.f fra.f,#2.0.f; load.f frb.f,#10.0.f; fpow.f fra.f,frb.f", IsFloat("fra.f", 1024.0))
    check("load.f fra.f,#8.0.f; flog.f frb.f,fra.f; load.f frc.f,#1.0.f; fln.f frd.f,frc.f", IsFloat("frb.f", 3.0), IsFloat("frd.f", 0.0))
    check("load.f fra.f,#0.5.f; fsin.f frb.f,fra.f; fcos.f frc.f,fra.f; ftan.f frd.f,fra.f; load.f fre.f,#1.0.f; fatan.f frf.f,fre.f",
        IsFloat("frb.f", kotlin.math.sin(0.5), 1e-12), IsFloat("frc.f", kotlin.math.cos(0.5), 1e-12),
        IsFloat("frd.f", kotlin.math.tan(0.5), 1e-12), IsFloat("frf.f", kotlin.math.atan(1.0), 1e-12))
    check("load.f fra.f,#-3.9.f; ftosw.f rb.w,fra.f; load.f frc.f,#300.7.f; ftoub.f rd.b,frc.f; load.f fre.f,#-1.5.f; ftoub.f rf.b,fre.f",
        Is("rb.w", -3), Is("rd.b", 44), Is("rf.b", 255))
    check("load.f fra.f,#-1.0.f; sqrt.f frb.f,fra.f; ftosl.f rc.l,frb.f; load.f frd.f,#1.0E20.f; ftosl.f re.l,frd.f; ftosb.f rf.b,frd.f",
        Is("rc.l", 0), Is("re.l", 2147483647), Is("rf.b", 255))
    check("load.b ra.b,#-5.b; ffromsb.f frb.f,ra.b; load.b rc.b,#250.b; ffromub.f frd.f,rc.b", IsFloat("frb.f", -5.0), IsFloat("frd.f", 250.0))
    check("load.w ra.w,#65535.w; ffromuw.f frb.f,ra.w; ffromsw.f frc.f,ra.w; load.l rd.l,#-100000.l; ffromsl.f fre.f,rd.l",
        IsFloat("frb.f", 65535.0), IsFloat("frc.f", -1.0), IsFloat("fre.f", -100000.0))

    // integer square roots and squares
    check("load.b ra.b,#200.b; sqrt.b rb.b,ra.b; load.w rc.w,#65535.w; sqrt.w rd.b,rc.w; load.l re.l,#1000000.l; sqrt.l rf.w,re.l",
        Is("rb.b", 14), Is("rd.b", 255), Is("rf.w", 1000))
    check("load.b ra.b,#20.b; square.b rb.b,ra.b; load.w rc.w,#300.w; square.w rd.w,rc.w", Is("rb.b", 144), Is("rd.w", 24464))

    // bitwise operations, shifts and rotates
    check("load.b ra.b,#240.b; and.b ra.b,#60.b; load.w rb.w,#4096.w; or.w rb.w,#15.w; load.l rc.l,#-1.l; load.l rd.l,#65535.l; xorr.l rc.l,rd.l",
        Is("ra.b", 48), Is("rb.w", 4111), Is("rc.l", -65536))
    check("load.w ra.w,#61680.w; load.w rb.w,#4080.w; andr.w ra.w,rb.w; load.w rc.w,#4096.w; orr.w rc.w,rb.w; load.b rd.b,#170.b; xor.b rd.b,#255.b",
        Is("ra.w", 240), Is("rc.w", 8176), Is("rd.b", 85))
    check("load.w ra.w,#4.w; bittst.w ra.w,#2.b", ZeroIs(false))
    check("load.w ra.w,#4.w; bittst.w ra.w,#1.b", ZeroIs(true))
    check("load.l ra.l,#-5.l; sgn.l rb.b,ra.l; load.f frc.f,#0.0.f; sgn.f rd.b,frc.f", Is("rb.b", -1), Is("rd.b", 0))
    check("load.b ra.b,#129.b; lsl.b ra.b", CarryIs(true), Is("ra.b", 2))
    check("load.w ra.w,#3.w; lsr.w ra.w", CarryIs(true), Is("ra.w", 1))
    check("load.b ra.b,#128.b; asr.b ra.b", CarryIs(false), Is("ra.b", 192))
    check("load.l ra.l,#-3.l; asr.l ra.l", CarryIs(true), Is("ra.l", -2))
    check("load.w ra.w,#1.w; loadm.b rb.b,[main.twenty]; lsln.w ra.w,rb.b", Is("ra.w", 0))
    check("load.b ra.b,#240.b; load.b rb.b,#3.b; lsrn.b ra.b,rb.b", Is("ra.b", 30))
    check("load.w ra.w,#-1000.w; loadm.b rb.b,[main.twenty]; asrn.w ra.w,rb.b", Is("ra.w", 65535))
    check("load.l ra.l,#-256.l; asri.l ra.l,#4.l; load.b rb.b,#1.b; lsli.b rb.b,#9.b; load.w rc.w,#32768.w; lsri.w rc.w,#15.w",
        Is("ra.l", -16), Is("rb.b", 0), Is("rc.w", 1))
    check("load.b ra.b,#129.b; rol.b ra.b", CarryIs(true), Is("ra.b", 3))
    check("load.w ra.w,#1.w; ror.w ra.w", CarryIs(true), Is("ra.w", 32768))
    check("sec; load.b ra.b,#64.b; roxl.b ra.b", CarryIs(false), Is("ra.b", 129))
    check("clc; load.w ra.w,#1.w; roxr.w ra.w", CarryIs(true), Is("ra.w", 0))
    check("sec; load.l ra.l,#2.l; roxr.l ra.l", CarryIs(false), Is("ra.l", 2147483649))
    check("storeim.b #128.b,[main.mem.b]; clc; roxlm.b [main.mem.b]; loadm.b ra.b,[main.mem.b]", CarryIs(true), Is("ra.b", 0))
    check("storeim.w #32769.w,[main.mem.w]; rorm.w [main.mem.w]; loadm.w ra.w,[main.mem.w]", CarryIs(true), Is("ra.w", 49152))
    check("load.w ra.w,#0.w; bitset.w ra.w,#15.b; bittog.w ra.w,#0.b; bitclr.w ra.w,#15.b", Is("ra.w", 1))
    check("load.b ra.b,#200.b; ext.b rb.w,ra.b; exts.b rc.w,ra.b; extl.b rd.l,ra.b; extls.b re.l,ra.b; load.w rf.w,#-2.w; exts.w rg.l,rf.w; ext.w rh.l,rf.w",
        Is("rb.w", 200), Is("rc.w", 65480), Is("rd.l", 200), Is("re.l", -56), Is("rg.l", -2), Is("rh.l", 65534))
    check("load.w ra.w,#4660.w; lsigb.w rb.b,ra.w; msigb.w rc.b,ra.w", Is("rb.b", 0x34), Is("rc.b", 0x12))
    check("load.l ra.l,#305419896.l; msigb.l rb.b,ra.l; bsigb.l rc.b,ra.l; midb.l rd.b,ra.l; lsigb.l re.b,ra.l; lsigw.l rf.w,ra.l; msigw.l rg.w,ra.l",
        Is("rb.b", 0x12), Is("rc.b", 0x34), Is("rd.b", 0x56), Is("re.b", 0x78), Is("rf.w", 0x5678), Is("rg.w", 0x1234))
    check("load.b ra.b,#18.b; load.b rb.b,#52.b; concat.b rc.w,ra.b,rb.b; load.w rd.w,#4660.w; load.w re.w,#22136.w; concat.w rf.l,rd.w,re.w",
        Is("rc.w", 0x1234), Is("rf.l", 0x12345678))

    // operations on memory
    check("storeim.w #3855.w,[main.mem.w]; load.w ra.w,#255.w; andm.w ra.w,[main.mem.w]; loadm.w rb.w,[main.mem.w]", Is("rb.w", 15))
    check("storeim.l #100.l,[main.mem.l]; addim.l #-150.l,[main.mem.l]; loadm.l ra.l,[main.mem.l]", Is("ra.l", -50))
    check("storeim.b #10.b,[main.mem.b]; incm.b [main.mem.b]; negm.b [main.mem.b]; loadm.b ra.b,[main.mem.b]", Is("ra.b", 245))
    check("storeim.w #1000.w,[main.mem.w]; load.w ra.w,#3.w; divm.w ra.w,[main.mem.w]; loadm.w rb.w,[main.mem.w]", Is("rb.w", 333))
    check("storeim.b #129.b,[main.mem.b]; load.b ra.b,#1.b; asrnm.b ra.b,[main.mem.b]; loadm.b rb.b,[main.mem.b]", Is("rb.b", 192))
    check("storeim.l #7.l,[main.mem.l]; load.l ra.l,#-3.l; mulsm.l ra.l,[main.mem.l]; subim.l #1.l,[main.mem.l]; invm.l [main.mem.l]; loadm.l rb.l,[main.mem.l]",
        Is("rb.l", 21))
    check("storeim.w #5.w,[main.mem.w]; decm.w [main.mem.w]; lslm.w [main.mem.w]; loadm.w ra.w,[main.mem.w]", CarryIs(false), Is("ra.w", 8))
    check("storeim.w #1000.w,[main.mem.w]; load.w ra.w,#24.w; addm.w ra.w,[main.mem.w]; load.w rb.w,#4.w; subm.w rb.w,[main.mem.w]; " +
            "load.w rc.w,#3.w; mulm.w rc.w,[main.mem.w]; loadm.w rd.w,[main.mem.w]", Is("rd.w", 3060))
    check("storeim.b #240.b,[main.mem.b]; load.b ra.b,#15.b; orm.b ra.b,[main.mem.b]; load.b rb.b,#60.b; xorm.b rb.b,[main.mem.b]; loadm.b rc.b,[main.mem.b]",
        Is("rc.b", 195))
    check("storeim.w #4097.w,[main.mem.w]; load.b ra.b,#3.b; lsrnm.w ra.b,[main.mem.w]; lslnm.w ra.b,[main.mem.w]; loadm.w rb.w,[main.mem.w]; " +
            "load.b rc.b,#16.b; lslnm.w rc.b,[main.mem.w]; loadm.w rd.w,[main.mem.w]", Is("rb.w", 4096), Is("rd.w", 0))
    check("storeim.b #131.b,[main.mem.b]; asrm.b [main.mem.b]; loadm.b ra.b,[main.mem.b]", CarryIs(true), Is("ra.b", 193))
    check("storeim.w #2.w,[main.mem.w]; lsrm.w [main.mem.w]; loadm.w ra.w,[main.mem.w]", CarryIs(false), Is("ra.w", 1))
    check("storeim.b #129.b,[main.mem.b]; rolm.b [main.mem.b]; loadm.b ra.b,[main.mem.b]", CarryIs(true), Is("ra.b", 3))
    check("sec; storeim.l #2.l,[main.mem.l]; roxrm.l [main.mem.l]; loadm.l ra.l,[main.mem.l]", CarryIs(false), Is("ra.l", 2147483649))
    check("storeim.l #12345.l,[main.mem.l]; storezm.l [main.mem.l]; loadm.l ra.l,[main.mem.l]", Is("ra.l", 0))

    // indexed and indirect memory access, also with whole 64-bit addresses in LONG registers
    check("load.w ra.w,#1.w; loadx.w rb.w,[main.words+2+ra.w*2]", Is("rb.w", 30))
    check("load.w ra.w,#3.w; load.b rb.b,#77.b; storex.b rb.b,[main.bytes+ra.w]; loadm.b rc.b,[main.bytes+3]", Is("rc.b", 77))
    check("load.w ra.w,#1.w; storezx.w [main.words2+ra.w*2]; loadm.w rb.w,[main.words2+2]", Is("rb.w", 0))
    check("load.l ra.l,#main.bytes; add.l ra.l,#2.l; load.b rb.b,#99.b; storei.b rb.b,[ra.p+1]; loadm.b rc.b,[main.bytes+3]", Is("rc.b", 99))
    check("load.l ra.l,#main.bytes; add.l ra.l,#3.l; load.l rb.l,#-2.l; loadm.l rc.l,[main.zerol]; addr.l rb.l,rc.l; addr.l ra.l,rb.l; loadi.b rd.b,[ra.p]", Is("rd.b", 2))
    check("load.p ra.p,#main.words; loadi.w rb.w,[ra.p+4]", Is("rb.w", 30))
    check("load.p ra.p,#main.bytes; storezi.b [ra.p+5]; loadm.b rb.b,[main.bytes+5]", Is("rb.b", 0))
    check("load.p ra.p,#main.bytes; storem.p ra.p,[main.ptr]; loadp_inc.b rb.b,[main.ptr]; loadp_inc.b rc.b,[main.ptr]; loadm.p rd.p,[main.ptr]; loadi.b re.b,[rd.p]",
        Is("rb.b", 1), Is("rc.b", 2), Is("re.b", 3))
    check("load.p ra.p,#main.words2; storem.p ra.p,[main.ptr]; load.w rb.w,#1111.w; storep_inc.w rb.w,[main.ptr]; load.w rc.w,#2222.w; storep_inc.w rc.w,[main.ptr]; " +
            "loadm.w rd.w,[main.words2+2]; loadm.w re.w,[main.words2]", Is("rd.w", 2222), Is("re.w", 1111))
    check("load.w ra.w,#2.w; loadx.l rb.l,[main.names+ra.w*4]; loadi.b rc.b,[rb.p]", Is("rc.b", 'c'.code.toLong()))
    check("load.l ra.l,#main.s3; load.w rb.w,#0.w; storex.l ra.l,[main.names+rb.w*4]; loadm.l rc.l,[main.names]; loadi.b rd.b,[rc.p+1]", Is("rd.b", 'z'.code.toLong()))
    check("load.l ra.l,#main.bytes; loadr.l rb.l,ra.l; storehr.l rb.l,s12.l; loadhr.l rc.l,s12.l; loadi.b rd.b,[rc.p+1]", Is("rd.b", 2))
    check("load.l ra.l,#main.bytes; add.l ra.l,#1.l; call main.peek(p=ra.l):rb.b", Is("rb.b", 2))

    // the value stack
    check("load.w ra.w,#1234.w; push.w ra.w; load.l rb.l,#main.bytes; push.l rb.l; load.b rc.b,#7.b; push.b rc.b; pop.b rd.b; pop.l re.l; pop.w rf.w; loadi.b rg.b,[re.p]",
        Is("rd.b", 7), Is("rf.w", 1234), Is("rg.b", 1))
    check("load.f fra.f,#2.75.f; push.f fra.f; pop.f frb.f", IsFloat("frb.f", 2.75))
    check("sec; pushst; clc; popst", CarryIs(true))
    check("load.b ra.b,#5.b; cmpi.b ra.b,#5.b; pushst; cmpi.b ra.b,#6.b; popst", ZeroIs(true))

    // syscalls
    check("load.l ra.l,#main.abc; load.l rb.l,#main.abd; syscall ${IMSyscall.COMPARE_STRINGS.number}(ra.l,rb.l):rc.b; " +
            "syscall ${IMSyscall.COMPARE_STRINGS.number}(rb.l,ra.l):rd.b; load.p re.p,#main.ab; syscall ${IMSyscall.COMPARE_STRINGS.number}(ra.l,re.p):rf.b; " +
            "syscall ${IMSyscall.COMPARE_STRINGS.number}(ra.l,ra.l):rg.b", Is("rc.b", -1), Is("rd.b", 1), Is("rf.b", 1), Is("rg.b", 0))
    check("load.b ra.b,#98.b; load.l rb.l,#main.abc; syscall ${IMSyscall.STRING_CONTAINS.number}(ra.b,rb.l):rc.b; " +
            "load.b rd.b,#0.b; syscall ${IMSyscall.STRING_CONTAINS.number}(rd.b,rb.l):re.b; load.b rf.b,#120.b; syscall ${IMSyscall.STRING_CONTAINS.number}(rf.b,rb.l):rg.b",
        Is("rc.b", 1), Is("re.b", 0), Is("rg.b", 0))
    check("load.b ra.b,#3.b; load.l rb.l,#main.bytes; load.w rc.w,#8.w; syscall ${IMSyscall.BYTEARRAY_CONTAINS.number}(ra.b,rb.l,rc.w):rd.b; " +
            "load.b re.b,#200.b; syscall ${IMSyscall.BYTEARRAY_CONTAINS.number}(re.b,rb.l,rc.w):rf.b", Is("rd.b", 1), Is("rf.b", 0))
    check("load.w ra.w,#40.w; load.l rb.l,#main.words; load.w rc.w,#4.w; syscall ${IMSyscall.WORDARRAY_CONTAINS.number}(ra.w,rb.l,rc.w):rd.b; " +
            "load.w re.w,#3.w; syscall ${IMSyscall.WORDARRAY_CONTAINS.number}(ra.w,rb.l,re.w):rf.b", Is("rd.b", 1), Is("rf.b", 0))
    check("load.l ra.l,#100000.l; load.l rb.l,#main.longs; load.w rc.w,#2.w; syscall ${IMSyscall.LONGARRAY_CONTAINS.number}(ra.l,rb.l,rc.w):rd.b", Is("rd.b", 1))
    check("load.f fra.f,#2.5.f; load.l rb.l,#main.floats; load.w rc.w,#2.w; syscall ${IMSyscall.FLOATARRAY_CONTAINS.number}(fra.f,rb.l,rc.w):rd.b; " +
            "load.f fre.f,#3.5.f; syscall ${IMSyscall.FLOATARRAY_CONTAINS.number}(fre.f,rb.l,rc.w):rf.b", Is("rd.b", 1), Is("rf.b", 0))
    check("load.b ra.b,#-100.b; load.b rb.b,#-50.b; load.b rc.b,#50.b; syscall ${IMSyscall.CLAMP_BYTE.number}(ra.b,rb.b,rc.b):rd.b; " +
            "load.b re.b,#200.b; load.b rf.b,#10.b; load.b rg.b,#100.b; syscall ${IMSyscall.CLAMP_UBYTE.number}(re.b,rf.b,rg.b):rh.b", Is("rd.b", -50), Is("rh.b", 100))
    check("load.w ra.w,#-5.w; load.w rb.w,#-10.w; load.w rc.w,#10.w; syscall ${IMSyscall.CLAMP_WORD.number}(ra.w,rb.w,rc.w):rd.w; " +
            "load.w re.w,#500.w; load.w rf.w,#1000.w; load.w rg.w,#65531.w; syscall ${IMSyscall.CLAMP_UWORD.number}(re.w,rf.w,rg.w):rh.w", Is("rd.w", -5), Is("rh.w", 1000))
    check("load.l ra.l,#-7.l; load.l rb.l,#0.l; load.l rc.l,#10.l; syscall ${IMSyscall.CLAMP_LONG.number}(ra.l,rb.l,rc.l):rd.l", Is("rd.l", 0))
    check("load.l ra.l,#main.src; load.l rb.l,#main.dst; load.w rc.w,#3.w; syscall ${IMSyscall.MEMCOPY.number}(ra.l,rb.l,rc.w); " +
            "loadm.b rd.b,[main.dst+2]; loadm.b re.b,[main.dst+3]", Is("rd.b", 7), Is("re.b", 0))
    check("load.l ra.l,#main.dst; load.w rb.w,#2.w; load.b rc.b,#170.b; syscall 37(ra.l,rb.w,rc.b); loadm.b rd.b,[main.dst+1]; loadm.b re.b,[main.dst+2]",
        Is("rd.b", 170), Is("re.b", 7))
    check("load.l ra.l,#main.fill; load.w rb.w,#2.w; load.w rc.w,#4660.w; syscall 38(ra.l,rb.w,rc.w); loadm.w rd.w,[main.fill+2]; loadm.w re.w,[main.fill+4]",
        Is("rd.w", 4660), Is("re.w", 0))
    check("load.l ra.l,#main.src; load.l rb.l,#main.dst; load.w rc.w,#4.w; syscall 36(ra.l,rb.l,rc.w); loadm.b rd.b,[main.dst+3]", Is("rd.b", 6))

    // indirect calls and jumps, FAC registers, and instructions that do nothing on llvm64
    check("load.l ra.l,#main.answer; calli (ra.p)(); loadhr.w rb.w,s10.w", Is("rb.w", 4242))
    check("call main.jumper():ra.b", Is("ra.b", 7))
    check("load.f fra.f,#2.5.f; storehfaczero.f fra.f; loadhfaczero.f frb.f; loadhr.f frc.f,s6.f; load.f frd.f,#-1.5.f; storehfacone.f frd.f; loadhfacone.f fre.f",
        IsFloat("frb.f", 2.5), IsFloat("frc.f", 2.5), IsFloat("fre.f", -1.5))
    check("sei; cli; nop; align #4.l; load.b ra.b,#1.b", Is("ra.b", 1))
}

/** Small subroutines for the tests that check the exact LLVM IR of a lowering. */
private fun TestProgram.focusedSubs() {
    variable("main.buffer", DataType.arrayFor(BaseDataType.UBYTE, target), length = 8u)
    variable("main.long", DataType.LONG)
    variable("main.words", DataType.arrayFor(BaseDataType.UWORD, target), numbers(10, 20, 30, 40), 4u)
    variable("main.ptr", DataType.pointer(BaseDataType.UBYTE))
    variable("main.wptr", DataType.UWORD)
    string("main.abc", "abc")
    string("main.abd", "abd")
    listOf("aa", "bb", "cc", "zz").forEachIndexed { index, text -> string("main.s$index", text) }
    variable("main.names", DataType.arrayFor(BaseDataType.STR, target),
        IRVariableInitializer.Array((0..2).map { IRStSymbolicReference.Symbol("main.s$it") }), 3u)
    sub("main.seven", returns = listOf(DataType.UBYTE), code = "_main.seven:\nreturni.b #7.b")
    sub("main.address", code = """
        _main.address:
        load.l r1.l,#main.buffer
        add.l r1.l,#3.l
        load.b r2.b,#42.b
        storei.b r2.b,[r1.p]
        load.l r3.l,#-5.l
        loadr.l r4.l,r3.l
        loadm.l r5.l,[main.long]
        storem.l r4.l,[main.long]
        return
        """)
    sub("main.divide", listOf("a" to DataType.UBYTE, "b" to DataType.UBYTE), listOf(DataType.UBYTE), """
        _main.divide:
        loadm.b r1.b,[main.divide.a]
        loadm.b r2.b,[main.divide.b]
        divr.b r1.b,r2.b
        div.b r1.b,#0.b
        returnr.b r1.b
        """)
    sub("main.sdivide", listOf("a" to DataType.WORD, "b" to DataType.WORD), listOf(DataType.WORD), """
        _main.sdivide:
        loadm.w r1.w,[main.sdivide.a]
        loadm.w r2.w,[main.sdivide.b]
        divsr.w r1.w,r2.w
        returnr.w r1.w
        """)
    sub("main.fdivide", listOf("a" to DataType.FLOAT, "b" to DataType.FLOAT), listOf(DataType.FLOAT), """
        _main.fdivide:
        loadm.f fr1.f,[main.fdivide.a]
        loadm.f fr2.f,[main.fdivide.b]
        divsr.f fr1.f,fr2.f
        returnr.f fr1.f
        """)
    sub("main.shifts", listOf("v" to DataType.UWORD, "n" to DataType.UBYTE), listOf(DataType.UWORD), """
        _main.shifts:
        loadm.w r1.w,[main.shifts.v]
        loadm.b r2.b,[main.shifts.n]
        lsln.w r1.w,r2.b
        asrn.w r1.w,r2.b
        lsri.w r1.w,#16.w
        returnr.w r1.w
        """)
    sub("main.rotate", listOf("v" to DataType.UBYTE), listOf(DataType.UBYTE), """
        _main.rotate:
        loadm.b r1.b,[main.rotate.v]
        lsr.b r1.b
        roxl.b r1.b
        returnr.b r1.b
        """)
    sub("main.access", listOf("i" to DataType.UWORD), listOf(DataType.UWORD), """
        _main.access:
        loadm.w r1.w,[main.access.i]
        loadx.w r2.w,[main.words+2+r1.w*2]
        load.p r3.p,#main.words
        loadi.w r4.w,[r3.p+4]
        addr.w r2.w,r4.w
        returnr.w r2.w
        """)
    sub("main.rename", code = """
        _main.rename:
        load.l r1.l,#main.s3
        load.w r2.w,#1.w
        storex.l r1.l,[main.names+r2.w*4]
        return
        """)
    sub("main.next", returns = listOf(DataType.UBYTE), code = """
        _main.next:
        loadp_inc.b r1.b,[main.ptr]
        load.w r2.w,#7.w
        storep_inc.w r2.w,[main.ptr]
        loadp_inc.b r3.b,[main.wptr]
        returnr.b r1.b
        """)
    sub("main.sys", returns = listOf(DataType.UBYTE), code = """
        _main.sys:
        load.l r1.l,#main.abc
        load.l r2.l,#main.abd
        syscall ${IMSyscall.COMPARE_STRINGS.number}(r1.l,r2.l):r3.b
        load.w r4.w,#3.w
        syscall ${IMSyscall.MEMCOPY.number}(r1.l,r2.l,r4.w)
        syscall 37(r2.l,r4.w,r3.b)
        load.b r5.b,#1.b
        load.b r6.b,#9.b
        syscall ${IMSyscall.CLAMP_BYTE.number}(r3.b,r5.b,r6.b):r7.b
        returnr.b r7.b
        """)
    sub("main.stack", code = """
        _main.stack:
        load.w r1.w,#7.w
        push.w r1.w
        pop.w r2.w
        pushst
        popst
        return
        """)
    sub("main.indirect", returns = listOf(DataType.UBYTE), code = """
        _main.indirect:
        load.l r1.l,#main.seven
        calli (r1.p)()
        breakpoint
        jumpi (r1.p)
        """)
}
