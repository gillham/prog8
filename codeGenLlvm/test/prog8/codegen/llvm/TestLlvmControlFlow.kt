package prog8.codegen.llvm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import prog8.code.core.*
import prog8.code.target.LlvmTarget
import prog8.intermediate.*
import prog8tests.helpers.hostLlvmTarget


class TestLlvmControlFlow: FunSpec({

    test("a subroutine becomes a function that keeps its registers in stack slots and its arguments in the parameter variables") {
        val program = TestProgram().apply { maxSub() }
        program.module().function("main.max").text() shouldBe """
            define internal i8 @main.max(i8 %a, i8 %b) {
            entry:
              %r1.slot = alloca i8
              %r2.slot = alloca i8
              store i8 %a, ptr @main.max.a
              store i8 %b, ptr @main.max.b
              br label %main.max

            main.max:
              %r1.1 = load i8, ptr @main.max.a
              store i8 %r1.1, ptr %r1.slot
              %r2.1 = load i8, ptr @main.max.b
              store i8 %r2.1, ptr %r2.slot
              %r1.2 = load i8, ptr %r1.slot
              %r2.2 = load i8, ptr %r2.slot
              %cond.1 = icmp ugt i8 %r1.2, %r2.2
              br i1 %cond.1, label %main.max.first, label %cont.1

            cont.1:
              %r2.3 = load i8, ptr %r2.slot
              ret i8 %r2.3

            main.max.first:
              %r1.3 = load i8, ptr %r1.slot
              ret i8 %r1.3
            }
            """.trimIndent() + "\n"
    }

    test("a compare sets the flags that a status branch in the same block turns into a single icmp") {
        val lines = TestProgram().apply { isZeroSub() }.module().function("main.iszero").lines()
        lines shouldHave "  %cond.1 = icmp ne i8 %r1.2, 0"
        lines shouldHave "  br i1 %cond.1, label %main.iszero.no, label %cont.1"
        lines shouldHave "  ret i8 1"
        lines shouldHave "  ret i8 0"
        lines.none { "flag." in it } shouldBe true
    }

    test("flags carry over to the rest of the chunk after a branch that isn't taken") {
        val lines = TestProgram().apply { orderSub() }.module().function("main.order").lines()
        lines shouldHave "  %cond.1 = icmp eq i8 %r1.2, %r2.2"
        lines shouldHave "  br i1 %cond.1, label %main.order.same, label %cont.1"
        lines shouldHave "  %cond.2 = icmp ult i8 %r1.2, %r2.2"
        lines shouldHave "  br i1 %cond.2, label %main.order.smaller, label %cont.2"
        lines.none { "flag." in it } shouldBe true
    }

    test("flags that a branch in another block reads are kept in stack slots") {
        val lines = TestProgram().apply { laterSub() }.module().function("main.later").lines()
        lines shouldHave "  %flag.carry = alloca i1"
        lines shouldHave "  %cond.2 = icmp uge i8 %r1.2, 100"
        lines shouldHave "  store i1 %cond.2, ptr %flag.carry"
        lines shouldHave "  br label %main.later.check"
        lines shouldHave "  %flag.1 = load i1, ptr %flag.carry"
        lines shouldHave "  br i1 %flag.1, label %main.later.big, label %cont.1"
    }

    test("a chunk without a branch at its end falls through to the next one, also in a loop") {
        val lines = TestProgram().apply { countSub() }.module().function("main.count").lines()
        lines shouldHave "  store i8 0, ptr %r1.slot"
        lines shouldHave "  br label %main.count.loop"
        lines shouldHave "  %r1.2 = add i8 %r1.1, 1"
        lines shouldHave "  %r2.3 = sub i8 %r2.2, 1"
        lines shouldHave "  br i1 %cond.1, label %main.count.loop, label %cont.1"
    }

    test("an IR loop chunk counts its trip count down in a stack slot") {
        val lines = TestProgram().apply { repeatSub() }.module().function("main.repeat").lines()
        lines shouldHave "  %main.repeat.loop.count = alloca i32"
        lines shouldHave "  store i32 5, ptr %main.repeat.loop.count"
        lines shouldHave "  br label %chunk.1"
        lines shouldHave "  %mem.2 = add i8 %mem.1, 1"
        lines shouldHave "  store i8 %mem.2, ptr @main.counter"
        lines shouldHave "  br label %main.repeat.loop.latch"
        lines shouldHave "  %main.repeat.loop.count.2 = sub i32 %main.repeat.loop.count.1, 1"
        lines shouldHave "  br i1 %again.1, label %chunk.1, label %chunk.2"
        lines shouldHave "  ret void"
    }

    test("calls pass their arguments in the callee's parameter order and keep the result") {
        val lines = TestProgram().apply { maxSub(); callMaxSub() }.module().function("main.callmax").lines()
        lines shouldHave "  %r3.1 = call i8 @main.max(i8 %r1.1, i8 %r2.1)"
        lines shouldHave "  store i8 %r3.1, ptr %r3.slot"
    }

    test("several return values pass through calling convention slots, so such a function returns void") {
        val module = TestProgram().apply { pairSub(); usePairSub() }.module()
        val text = LlvmPrinter.print(module)
        text shouldContain "@.slot.s10 = internal global i64 0"
        text shouldContain "@.slot.s11 = internal global i64 0"
        module.function("main.pair").lines() shouldHave "define internal void @main.pair() {"
        module.function("main.pair").lines() shouldHave "  store i64 %cast.1, ptr @.slot.s10"
        val caller = module.function("main.usepair").lines()
        caller shouldHave "  call void @main.pair()"
        caller shouldHave "  %r3.1 = load i64, ptr @.slot.s10"
        caller shouldHave "  %r3.2 = trunc i64 %r3.1 to i8"
    }

    test("float comparisons give -1, 0 or 1 that an integer branch tests, signed branches use signed predicates") {
        val module = TestProgram().apply { lessSub(); classifySub() }.module()
        val less = module.function("main.less").lines()
        less shouldHave "define internal i8 @main.less(double %x, double %y) {"
        less shouldHave "  %lt.1 = fcmp olt double %fr1.2, %fr2.2"
        less shouldHave "  %gt.1 = fcmp ogt double %fr1.2, %fr2.2"
        less shouldHave "  %sign.3 = sub i8 %sign.2, %sign.1"
        less shouldHave "  %cond.1 = icmp slt i8 %r6.1, 0"
        val classify = module.function("main.classify").lines()
        classify shouldHave "  %r5.1 = load i16, ptr @main.classify.w, align 1"
        classify shouldHave "  %cond.1 = icmp slt i16 %r5.2, 0"
        classify shouldHave "  %cond.2 = icmp sgt i16 %r5.3, 0"
    }

    test("asmsubs and extsubs are declared, a call to an extsub address uses its name, other routines are declared from the call") {
        val program = TestProgram()
        program.asmsub(IRAsmSubroutine("cbm.CHROUT", 0xffd2u, emptySet(),
            listOf(IRAsmSubroutine.IRAsmParam(RegisterOrStatusflag(RegisterOrPair.A, null), DataType.UBYTE)), emptyList(),
            IRInlineAsmChunk("cbm.CHROUT", "", false, null), Position.DUMMY))
        program.string("main.text", "hi")
        program.sub("main.hello", code = $$"""
            _main.hello:
            load.b r1.b,#65.b
            call $ffd2,cbm.CHROUT(r1.b@s10)
            load.p r2.p,#main.text
            call puts(r2.p)
            return
            """)
        val module = program.module()
        val text = LlvmPrinter.print(module)
        text shouldContain "declare void @cbm.CHROUT(i8)"
        text shouldContain "declare void @puts(ptr)"
        module.function("main.hello").lines() shouldHave "  call void @cbm.CHROUT(i8 %r1.1)"
        module.function("main.hello").lines() shouldHave "  call void @puts(ptr %r2.1)"
    }

    test("a jump or branch into another subroutine, which return f() can become, calls it and returns its result") {
        val module = TestProgram().apply { tailCallSubs() }.module()
        val pick = module.function("main.pick").lines()
        pick shouldHave "  br i1 %cond.1, label %goto.1, label %cont.1"
        pick shouldHave "  %goto.2 = call i8 @main.eleven()"
        pick shouldHave "  %cast.1 = zext i8 %goto.2 to i16"
        pick shouldHave "  ret i16 %cast.1"
        val bump = module.function("main.bump").lines()
        bump shouldHave "  br label %goto.1"
        bump shouldHave "  call void @main.increment()"
        bump shouldHave "  ret void"
    }

    test("IR that has no LLVM translation yet is rejected with a clear message") {
        fun failure(code: String) = shouldThrow<IllegalArgumentException> {
            TestProgram().apply { sub("main.f", listOf("a" to DataType.UBYTE), code = code); sub("main.g", code = "_main.g:\n_main.g.there:\nreturn") }.module()
        }.message
        failure("_main.f:\nloadm.b r1.b,[main.f.a]\nsyscall 8(r1.b)\nreturn")!! shouldContain "syscall 8"
        failure("_main.f:\njump main.g.there")!! shouldContain "outside of the subroutine"
        shouldThrow<IllegalArgumentException> {
            TestProgram().apply { sub("main.f", listOf("a" to DataType.UBYTE), code = "_main.f:\nreturn"); sub("main.g", code = "_main.g:\njump main.f") }.module()
        }.message!! shouldContain "main.g jumps to main.f, which has parameters"
        val program = TestProgram()
        program.sub("main.f", code = "_main.f:\nreturn")
        program.subroutine("main.f").chunks += IRInlineAsmChunk(null, "nop", false, null)
        shouldThrow<IllegalArgumentException> { program.module() }.message!! shouldContain "inline assembly"
    }

    test("every block of every function ends with a terminator, and llvm-as accepts the module").config(enabledOrReasonIf = requiresLlvmAs) {
        val module = TestProgram().apply { allSubs() }.module()
        module.functions.filterNot { it.isExternal }.forEach { function ->
            function.blocks.forEach { block -> withClue("${function.name}: ${block.label}") { (block.terminator != null) shouldBe true } }
        }
        val (exitCode, output) = runLlvmAs(LlvmPrinter.print(module))
        withClue(output) { exitCode shouldBe 0 }
    }

    test("the control flow runs correctly as a native program for the host").config(enabledOrReasonIf = requiresNativeHost) {
        val module = TestProgram(hostLlvmTarget).apply { allSubs(); checkSub() }.module().withMainCalling("main.check")
        val (exitCode, output) = compileAndRun(LlvmPrinter.print(module))
        withClue("exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
    }

    test("the llvm32 control flow runs correctly as a wasm32 program under wasmtime").config(enabledOrReasonIf = requiresWasm) {
        val module = TestProgram(LlvmTarget(4)).apply { allSubs(); checkSub() }.module().withMainCalling("main.check").withMainNamed(wasiMain)
        val (exitCode, output) = compileAndRunWasm(LlvmPrinter.print(module))
        withClue("exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
    }

    test("the llvm32 control flow runs correctly as a bare metal ARM program on qemu").config(enabledOrReasonIf = requiresArm) {
        val module = TestProgram(armTarget).apply { allSubs(); checkSub() }.module().withMainCalling("main.check")
        val (exitCode, output) = compileAndRunArm(LlvmPrinter.print(module))
        withClue("exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
    }

    test("the llvm32 control flow runs correctly as a bare metal RISC-V program on qemu").config(enabledOrReasonIf = requiresRiscv) {
        val module = TestProgram(riscvTarget).apply { allSubs(); checkSub() }.module().withMainCalling("main.check")
        val (exitCode, output) = compileAndRunRiscv(LlvmPrinter.print(module))
        withClue("exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
    }
})


private fun TestProgram.maxSub() = sub("main.max", listOf("a" to DataType.UBYTE, "b" to DataType.UBYTE), listOf(DataType.UBYTE), """
    _main.max:
    loadm.b r1.b,[main.max.a]
    loadm.b r2.b,[main.max.b]
    bgtr.b r1.b,r2.b,main.max.first
    returnr.b r2.b
    _main.max.first:
    returnr.b r1.b
    """)

private fun TestProgram.isZeroSub() = sub("main.iszero", listOf("v" to DataType.UBYTE), listOf(DataType.UBYTE), """
    _main.iszero:
    loadm.b r1.b,[main.iszero.v]
    cmpi.b r1.b,#0.b
    bstne main.iszero.no
    returni.b #1.b
    _main.iszero.no:
    returni.b #0.b
    """)

private fun TestProgram.orderSub() = sub("main.order", listOf("a" to DataType.UBYTE, "b" to DataType.UBYTE), listOf(DataType.UBYTE), """
    _main.order:
    loadm.b r1.b,[main.order.a]
    loadm.b r2.b,[main.order.b]
    cmp.b r1.b,r2.b
    bsteq main.order.same
    bstcc main.order.smaller
    returni.b #2.b
    _main.order.same:
    returni.b #1.b
    _main.order.smaller:
    returni.b #0.b
    """)

private fun TestProgram.laterSub() = sub("main.later", listOf("v" to DataType.UBYTE), listOf(DataType.UBYTE), """
    _main.later:
    loadm.b r1.b,[main.later.v]
    cmpi.b r1.b,#100.b
    jump main.later.check
    _main.later.check:
    bstcs main.later.big
    returni.b #0.b
    _main.later.big:
    returni.b #1.b
    """)

private fun TestProgram.countSub() = sub("main.count", listOf("n" to DataType.UBYTE), listOf(DataType.UBYTE), """
    _main.count:
    load.b r1.b,#0.b
    loadm.b r2.b,[main.count.n]
    _main.count.loop:
    inc.b r1.b
    dec.b r2.b
    cmpi.b r2.b,#0.b
    bstne main.count.loop
    returnr.b r1.b
    """)

private fun TestProgram.repeatSub() {
    variable("main.counter", DataType.UBYTE)
    sub("main.repeat", code = """
        _main.repeat:
        loop main.repeat.loop 5
        incm.b [main.counter]
        endloop
        return
        """)
}

private fun TestProgram.callMaxSub() = sub("main.callmax", returns = listOf(DataType.UBYTE), code = """
    _main.callmax:
    load.b r1.b,#5.b
    load.b r2.b,#9.b
    call main.max(b=r2.b,a=r1.b):r3.b
    returnr.b r3.b
    """)

private fun TestProgram.pairSub() = sub("main.pair", returns = listOf(DataType.UBYTE, DataType.UBYTE), code = """
    _main.pair:
    load.b r1.b,#7.b
    storehr.b r1.b,s10.b
    load.b r2.b,#9.b
    storehr.b r2.b,s11.b
    return
    """)

private fun TestProgram.usePairSub() = sub("main.usepair", returns = listOf(DataType.UBYTE), code = """
    _main.usepair:
    call main.pair()
    loadhr.b r3.b,s10.b
    returnr.b r3.b
    """)

private fun TestProgram.lessSub() = sub("main.less", listOf("x" to DataType.FLOAT, "y" to DataType.FLOAT), listOf(DataType.UBYTE), """
    _main.less:
    loadm.f fr1.f,[main.less.x]
    loadm.f fr2.f,[main.less.y]
    fcomp.f r6.b,fr1.f,fr2.f
    blts.b r6.b,#0.b,main.less.yes
    returni.b #0.b
    _main.less.yes:
    returni.b #1.b
    """)

private fun TestProgram.classifySub() = sub("main.classify", listOf("w" to DataType.WORD), listOf(DataType.UBYTE), """
    _main.classify:
    loadm.w r5.w,[main.classify.w]
    blts.w r5.w,#0.w,main.classify.negative
    bgts.w r5.w,#0.w,main.classify.positive
    returni.b #1.b
    _main.classify.negative:
    returni.b #0.b
    _main.classify.positive:
    returni.b #2.b
    """)

/** main.pick returns the byte that main.eleven returns as a word, when its parameter isn't 0; main.bump goes on in main.increment. */
private fun TestProgram.tailCallSubs() {
    variable("main.bumps", DataType.UBYTE)
    sub("main.eleven", returns = listOf(DataType.UBYTE), code = "_main.eleven:\nreturni.b #11.b")
    sub("main.pick", listOf("first" to DataType.UBYTE), listOf(DataType.UWORD), """
        _main.pick:
        loadm.b r1.b,[main.pick.first]
        cmpi.b r1.b,#0.b
        bstne main.eleven
        returni.w #22.w
        """)
    sub("main.increment", code = "_main.increment:\nincm.b [main.bumps]\nreturn")
    sub("main.bump", code = "_main.bump:\njump main.increment")
}

private fun TestProgram.allSubs() {
    maxSub(); isZeroSub(); orderSub(); laterSub(); countSub(); repeatSub(); callMaxSub(); pairSub(); usePairSub(); lessSub(); classifySub(); tailCallSubs()
}

/** Returns 0 when all the other subroutines work as expected, otherwise the number of the failing check. */
private fun TestProgram.checkSub() = sub("main.check", returns = listOf(DataType.UBYTE), code = """
    _main.check:
    load.b r10.b,#5.b
    load.b r11.b,#9.b
    call main.max(a=r10.b,b=r11.b):r12.b
    cmpi.b r12.b,#9.b
    bstne main.check.fail1
    load.b r13.b,#7.b
    call main.count(n=r13.b):r14.b
    cmpi.b r14.b,#7.b
    bstne main.check.fail2
    call main.repeat()
    loadm.b r15.b,[main.counter]
    cmpi.b r15.b,#5.b
    bstne main.check.fail3
    load.w r16.w,#-300.w
    call main.classify(w=r16.w):r17.b
    cmpi.b r17.b,#0.b
    bstne main.check.fail4
    load.w r18.w,#300.w
    call main.classify(w=r18.w):r19.b
    cmpi.b r19.b,#2.b
    bstne main.check.fail4
    load.f fr3.f,#1.5.f
    load.f fr4.f,#2.25.f
    call main.less(x=fr3.f,y=fr4.f):r20.b
    cmpi.b r20.b,#1.b
    bstne main.check.fail5
    call main.pair()
    loadhr.b r21.b,s10.b
    loadhr.b r22.b,s11.b
    cmpi.b r21.b,#7.b
    bstne main.check.fail6
    cmpi.b r22.b,#9.b
    bstne main.check.fail6
    load.b r23.b,#200.b
    call main.later(v=r23.b):r24.b
    cmpi.b r24.b,#1.b
    bstne main.check.fail7
    load.b r25.b,#200.b
    load.b r26.b,#100.b
    call main.order(a=r25.b,b=r26.b):r27.b
    cmpi.b r27.b,#2.b
    bstne main.check.fail8
    cmp.b r25.b,r26.b
    bsteq main.check.fail9
    bstcc main.check.fail9
    bstneg main.check.fail9
    bstvc main.check.fail9
    call main.callmax():r28.b
    cmpi.b r28.b,#9.b
    bstne main.check.fail10
    load.b r29.b,#1.b
    call main.pick(first=r29.b):r30.w
    cmpi.w r30.w,#11.w
    bstne main.check.fail11
    load.b r31.b,#0.b
    call main.pick(first=r31.b):r32.w
    cmpi.w r32.w,#22.w
    bstne main.check.fail11
    call main.bump()
    loadm.b r33.b,[main.bumps]
    cmpi.b r33.b,#1.b
    bstne main.check.fail11
    returni.b #0.b
    _main.check.fail1:
    returni.b #1.b
    _main.check.fail2:
    returni.b #2.b
    _main.check.fail3:
    returni.b #3.b
    _main.check.fail4:
    returni.b #4.b
    _main.check.fail5:
    returni.b #5.b
    _main.check.fail6:
    returni.b #6.b
    _main.check.fail7:
    returni.b #7.b
    _main.check.fail8:
    returni.b #8.b
    _main.check.fail9:
    returni.b #9.b
    _main.check.fail10:
    returni.b #10.b
    _main.check.fail11:
    returni.b #11.b
    """)
