package prog8.codegen.llvm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import prog8.code.core.*
import prog8.codegen.llvm.lower.LlvmLoweringException
import prog8.intermediate.*


class TestLlvmExternalRoutines: FunSpec({

    test("external routines are declared under the bare C name with the exact Prog8 widths, and called by that name") {
        val program = TestProgram().apply { externalRoutines(); callExternalRoutines() }
        val module = program.module()
        val text = LlvmPrinter.print(module).lines()
        text shouldHave "declare i8 @putchar(i8)"
        text shouldHave "declare i16 @puts(ptr)"
        text shouldHave "declare i8 @getchar()"
        text shouldHave "declare void @srand(i16)"
        text shouldHave "declare void @mixed(i8, i8, i8, i16, i16, i32, i64, i64, double, ptr)"
        text shouldHave "declare i64 @strlen(ptr)"
        text.none { it.startsWith("declare") && ("main.putchar" in it || "main.puts" in it || "zeroext" in it || "signext" in it) } shouldBe true
        val lines = module.function("main.start").lines()
        lines shouldHave "  call i8 @putchar(i8 %r1.1)"
        lines shouldHave "  %r5.1 = call i8 @getchar()"
        lines shouldHave "  call i16 @puts(ptr %r4.1)"
        lines shouldHave "  call void @srand(i16 %r6.1)"
        lines.none { "@main." in it && ("putchar" in it || "puts" in it || "getchar" in it || "srand" in it) } shouldBe true
    }

    test("the result of an external routine is stored in a register or ignored, and the argument has the declared type") {
        val lines = TestProgram().apply { externalRoutines(); callExternalRoutines() }.module().function("main.start").lines()
        lines shouldHave "  %r3.1 = call i8 @putchar(i8 %r2.1)"
        lines shouldHave "  call i8 @putchar(i8 %r1.1)"
        lines.any { it.startsWith("  call double @mixed") } shouldBe false
        lines.any { it.startsWith("  call void @mixed(") && "double %" in it } shouldBe true
    }

    test("external routines in nested scopes use the last part of their label, and the same C function with the same types is declared once") {
        val program = TestProgram()
        program.asmsub(external("main.start.putchar", listOf(DataType.UBYTE), listOf(DataType.UBYTE)))
        program.asmsub(external("other.putchar", listOf(DataType.UBYTE), listOf(DataType.UBYTE)))
        program.sub("main.start", code = "_main.start:\nload.b r1.b,#65.b\ncall main.start.putchar(char=r1.b)\ncall other.putchar(c=r1.b)\nreturn")
        val module = program.module()
        val text = LlvmPrinter.print(module).lines()
        text.count { it == "declare i8 @putchar(i8)" } shouldBe 1
        module.functions.count { it.name == "putchar" } shouldBe 1
        module.function("main.start").lines().count { it.startsWith("  call i8 @putchar(i8 %r1.") } shouldBe 2
    }

    test("an external routine that nothing calls is still declared, and the address of one is its C function") {
        val program = TestProgram()
        program.asmsub(external("main.unused", emptyList(), listOf(DataType.UWORD)))
        program.asmsub(external("main.address", emptyList(), emptyList()))
        program.sub("main.start", code = "_main.start:\nload.p r1.p,#main.address\nreturn")
        val module = program.module()
        LlvmPrinter.print(module).lines() shouldHave "declare i16 @unused()"
        module.function("main.start").lines() shouldHave "  store ptr @address, ptr %r1.slot"
    }

    test("two external routines that declare a C function with different types conflict, which names both of them") {
        val program = TestProgram()
        program.asmsub(external("main.putchar", listOf(DataType.UBYTE), listOf(DataType.UBYTE), Position("main.p8", 3, 5, 20)))
        program.asmsub(external("other.putchar", listOf(DataType.LONG), listOf(DataType.LONG), Position("other.p8", 9, 5, 20)))
        program.sub("main.start", code = "_main.start:\nreturn")
        val error = shouldThrow<LlvmLoweringException> { program.module() }
        error.message shouldBe "extsub other.putchar declares C function 'putchar' as i32 (i32), which conflicts with extsub main.putchar (at main.p8:3:5) that declares it as i8 (i8)"
        error.position shouldBe Position("other.p8", 9, 5, 20)
    }

    test("an external routine conflicts with the runtime's declaration of a C function when reachable code uses it, whichever comes first") {
        for (externalFirst in listOf(true, false)) {
            val program = TestProgram()
            val position = Position("main.p8", 4, 5, 30)
            if (externalFirst)
                program.asmsub(external("main.putchar", listOf(DataType.UBYTE), listOf(DataType.UBYTE), position))
            program.sub("main.start", code = "_main.start:\nload.b r1.b,#65.b\nsyscall 2 (r1.b)\nreturn")
            if (!externalFirst)
                program.asmsub(external("main.putchar", listOf(DataType.UBYTE), listOf(DataType.UBYTE), position))
            withClue("externalFirst=$externalFirst") {
                val error = shouldThrow<LlvmLoweringException> { program.module() }
                error.message shouldBe "extsub main.putchar declares C function 'putchar' as i8 (i8), which conflicts with the llvm64 runtime that declares it as i32 (i32)"
                error.position shouldBe position
            }
        }
    }

    test("the same holds for the C functions that the runtime only uses through its helper functions, and for those it calls with other types") {
        fun failure(label: String, params: List<DataType>, returns: List<DataType>, code: String): String? {
            val program = TestProgram()
            program.string("main.text", "hi")
            program.asmsub(external(label, params, returns))
            program.sub("main.start", code = code)
            return shouldThrow<LlvmLoweringException> { program.module() }.message
        }
        failure("main.putchar", listOf(DataType.UBYTE), listOf(DataType.UBYTE), "_main.start:\nload.l r1.l,#main.text\nsyscall 3 (r1.l)\nreturn") shouldBe
            "extsub main.putchar declares C function 'putchar' as i8 (i8), which conflicts with the llvm64 runtime that declares it as i32 (i32)"
        failure("main.getchar", emptyList(), listOf(DataType.UBYTE), "_main.start:\nsyscall 69 ():r1.b\nreturn")!! shouldContain "extsub main.getchar declares C function 'getchar' as i8 ()"
        failure("main.exit", listOf(DataType.UBYTE), emptyList(), "_main.start:\nload.b r1.b,#3.b\nsyscall 1 (r1.b)\nreturn") shouldBe
            "extsub main.exit declares C function 'exit' as void (i8), which conflicts with the llvm64 runtime that declares it as void (i32)"
    }

    test("a conflict with the runtime is no error when the code that uses it is never reached, or when the types are the same") {
        val unreached = TestProgram()
        unreached.asmsub(external("main.putchar", listOf(DataType.UBYTE), listOf(DataType.UBYTE)))
        unreached.sub("main.start", code = "_main.start:\nreturn")
        unreached.sub("main.die", code = "_main.die:\nload.b r1.b,#65.b\nsyscall 2 (r1.b)\nreturn")
        val module = unreached.module()
        LlvmPrinter.print(module).lines().count { it.startsWith("declare") && "@putchar(" in it } shouldBe 1
        module.function("main.die").lines() shouldHave "  call i32 @putchar(i32 %ch.1)"

        val reached = TestProgram()
        reached.asmsub(external("main.putchar", listOf(DataType.UBYTE), listOf(DataType.UBYTE)))
        reached.sub("main.start", code = "_main.start:\ncall main.die()\nreturn")
        reached.sub("main.die", code = "_main.die:\nload.b r1.b,#65.b\nsyscall 2 (r1.b)\nreturn")
        shouldThrow<LlvmLoweringException> { reached.module() }.message!! shouldContain "conflicts with the llvm64 runtime"

        val same = TestProgram()
        same.asmsub(external("main.putchar", listOf(DataType.LONG), listOf(DataType.LONG)))
        same.sub("main.start", code = "_main.start:\nload.b r1.b,#65.b\nsyscall 2 (r1.b)\nreturn")
        LlvmPrinter.print(same.module()).lines().count { it.startsWith("declare") && "@putchar(" in it } shouldBe 1
    }

    test("a helper that unreachable code used first still conflicts when reachable code uses it too") {
        val program = TestProgram()
        program.string("main.text", "hi")
        program.asmsub(external("main.putchar", listOf(DataType.UBYTE), listOf(DataType.UBYTE)))
        program.sub("main.die", code = "_main.die:\nload.l r1.l,#main.text\nsyscall 3 (r1.l)\nreturn")
        program.sub("main.start", code = "_main.start:\nload.l r1.l,#main.text\nsyscall 3 (r1.l)\nreturn")
        shouldThrow<LlvmLoweringException> { program.module() }.message!! shouldContain "conflicts with the llvm64 runtime"
    }

    test("a jump to an external routine without parameters calls the C function and returns its result") {
        val program = TestProgram()
        program.asmsub(external("main.getchar", emptyList(), listOf(DataType.UBYTE)))
        program.sub("main.next", returns = listOf(DataType.UBYTE), code = "_main.next:\njump main.getchar")
        val lines = program.module().function("main.next").lines()
        lines shouldHave "  %goto.2 = call i8 @getchar()"
        lines.none { "@main.getchar" in it } shouldBe true
    }

    test("asmsubs that are not external keep their scoped name and their register based declaration") {
        val program = TestProgram()
        program.asmsub(IRAsmSubroutine("cbm.CHROUT", null, emptySet(), false,
            listOf(IRAsmSubroutine.IRAsmParam(RegisterOrStatusflag(RegisterOrPair.A, null), DataType.UBYTE)), emptyList(),
            IRInlineAsmChunk("cbm.CHROUT", "rts", false, null), Position.DUMMY))
        program.sub("main.start", code = "_main.start:\nload.b r1.b,#65.b\ncall cbm.CHROUT(r1.b@s10)\nreturn")
        val module = program.module()
        LlvmPrinter.print(module).lines() shouldHave "declare void @cbm.CHROUT(i8)"
        module.function("main.start").lines() shouldHave "  call void @cbm.CHROUT(i8 %r1.1)"
    }

    test("llvm-as accepts a module with external routines").config(enabledOrReasonIf = requiresLlvmAs) {
        val program = TestProgram().apply { externalRoutines(); callExternalRoutines() }
        val (exitCode, output) = runLlvmAs(LlvmPrinter.print(program.module()))
        withClue(output) { exitCode shouldBe 0 }
    }
})


private fun external(label: String, params: List<DataType>, returns: List<DataType>, position: Position = Position.DUMMY): IRAsmSubroutine {
    val none = RegisterOrStatusflag(null, null)
    return IRAsmSubroutine(label, null, emptySet(), false, params.map { IRAsmSubroutine.IRAsmParam(none, it) }, returns.map { IRAsmSubroutine.IRAsmParam(none, it) },
        IRInlineAsmChunk(label, "", false, null), position, isExternal = true)
}

private fun TestProgram.externalRoutines() {
    asmsub(external("main.putchar", listOf(DataType.UBYTE), listOf(DataType.UBYTE)))
    asmsub(external("main.puts", listOf(DataType.pointer(BaseDataType.UBYTE)), listOf(DataType.UWORD)))
    asmsub(external("main.getchar", emptyList(), listOf(DataType.UBYTE)))
    asmsub(external("main.srand", listOf(DataType.UWORD), emptyList()))
    asmsub(external("main.strlen", listOf(DataType.pointer(BaseDataType.UBYTE)), listOf(DataType.UQUAD)))
    asmsub(external("main.mixed", listOf(DataType.BOOL, DataType.BYTE, DataType.UBYTE, DataType.WORD, DataType.UWORD, DataType.LONG, DataType.QUAD, DataType.UQUAD,
        DataType.FLOAT, DataType.pointer(BaseDataType.UBYTE)), emptyList()))
}

private fun TestProgram.callExternalRoutines() {
    string("main.text", "hi")
    variable("main.start.msg", DataType.pointer(BaseDataType.UBYTE))
    sub("main.start", code = """
        _main.start:
        load.b r1.b,#65.b
        call main.putchar(char=r1.b)
        load.b r2.b,#66.b
        call main.putchar(char=r2.b):r3.b
        load.p r4.p,#main.text
        call main.puts(msg=r4.p)
        call main.getchar():r5.b
        load.w r6.w,#1000.w
        call main.srand(seed=r6.w)
        load.b r7.b,#7.b
        call main.mixed(a=r7.b,b=r7.b,c=r7.b,d=r6.w,e=r6.w,f=r8.l,g=r9.q,h=r9.q,i=fr1.f,j=r4.p)
        return
        """)
}
