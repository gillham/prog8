package prog8.codegen.llvm

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import prog8.code.core.DataType


class TestLlvmSyscallLowering: FunSpec({

    test("syscall 2 writes a character with C's putchar, zero extended to an int") {
        val module = TestProgram().apply { printCharSub() }.module()
        val lines = module.function("main.printchar").lines()
        lines shouldHave "  %r1.1 = load i8, ptr %r1.slot"
        lines shouldHave "  %ch.1 = zext i8 %r1.1 to i32"
        lines shouldHave "  call i32 @putchar(i32 %ch.1)"
        val text = LlvmPrinter.print(module).lines()
        text shouldHave "declare i32 @putchar(i32)"
        text.none { ".print.string" in it } shouldBe true
    }

    test("syscall 3 writes a zero-terminated string through the internal .print.string helper, which calls putchar") {
        val module = TestProgram().apply { printStringSub() }.module()
        val lines = module.function("main.printstring").lines()
        lines shouldHave "  %r1.3 = inttoptr i64 %r1.2 to ptr"
        lines shouldHave "  call void @.print.string(ptr %r1.3)"
        lines shouldHave "  %r2.1 = load ptr, ptr %r2.slot"
        lines shouldHave "  call void @.print.string(ptr %r2.1)"
        module.function(".print.string").text() shouldBe """
            define internal void @.print.string(ptr %str) {
            entry:
              br label %loop

            loop:
              %curr_ptr = phi ptr [ %str, %entry ], [ %next, %print ]
              %ch = load i8, ptr %curr_ptr
              %end = icmp eq i8 %ch, 0
              br i1 %end, label %done, label %print

            print:
              %ch_ext = zext i8 %ch to i32
              call i32 @putchar(i32 %ch_ext)
              %next = getelementptr i8, ptr %curr_ptr, i64 1
              br label %loop

            done:
              ret void
            }
            """.trimIndent() + "\n"
        val text = LlvmPrinter.print(module).lines()
        text.count { it == "declare i32 @putchar(i32)" } shouldBe 1
        text.count { it.startsWith("define internal void @.print.string(") } shouldBe 1
    }

    test("syscall 69 reads a character with C's getchar, whose negative end of input becomes 0") {
        val module = TestProgram().apply { readCharSub() }.module()
        val lines = module.function("main.readchar").lines()
        lines shouldHave "  %ch.1 = call i32 @getchar()"
        lines shouldHave "  %smax.1 = call i32 @llvm.smax.i32(i32 %ch.1, i32 0)"
        lines shouldHave "  %ch.2 = trunc i32 %smax.1 to i8"
        lines shouldHave "  store i8 %ch.2, ptr %r1.slot"
        LlvmPrinter.print(module).lines() shouldHave "declare i32 @getchar()"
    }

    test("the input syscall reads stdin in a native arm64 macOS program, and gives 0 at its end").config(enabledOrReasonIf = requiresNativeArm64Mac) {
        val module = TestProgram().apply { readCharSub(); echoSub() }.module().withMainCalling("main.echo")
        val (exitCode, output) = compileAndRun(LlvmPrinter.print(module), input = "hi\n")
        withClue(output) { exitCode shouldBe 0 }
        output shouldBe "hi\n"
    }

    test("a program without output syscalls gets neither the putchar declaration nor the string helper") {
        val text = LlvmPrinter.print(TestProgram().apply { silentSub() }.module())
        withClue("putchar") { ("putchar" in text) shouldBe false }
        withClue(".print.string") { (".print.string" in text) shouldBe false }
    }

    test("llvm-as accepts a module with both output syscalls").config(enabledOrReasonIf = requiresLlvmAs) {
        val (exitCode, output) = runLlvmAs(LlvmPrinter.print(TestProgram().apply { allOutputSubs() }.module()))
        withClue(output) { exitCode shouldBe 0 }
    }

    test("the output syscalls write to stdout in a native arm64 macOS program").config(enabledOrReasonIf = requiresNativeArm64Mac) {
        val module = TestProgram().apply { allOutputSubs(); greetingSub() }.module().withMainCalling("main.greeting")
        val (exitCode, output) = compileAndRun(LlvmPrinter.print(module))
        withClue(output) { exitCode shouldBe 0 }
        output shouldBe "A\nhi\nhello, world!\nhi\n"
    }
})


private fun TestProgram.printCharSub() = sub("main.printchar", code = """
    _main.printchar:
    load.b r1.b,#65.b
    syscall 2 (r1.b)
    load.b r2.b,#10.b
    syscall 2 (r2.b)
    return
    """)

private fun TestProgram.printStringSub() {
    string("main.text", "hi\n")
    string("main.empty", "")
    sub("main.printstring", code = """
        _main.printstring:
        load.l r1.l,#main.text
        syscall 3 (r1.l)
        load.p r2.p,#main.empty
        syscall 3 (r2.p)
        return
        """)
}

private fun TestProgram.readCharSub() = sub("main.readchar", returns = listOf(DataType.UBYTE), code = """
    _main.readchar:
    syscall 69 ():r1.b
    returnr.b r1.b
    """)

/** Writes the characters of stdin to stdout until main.readchar gives 0; returns 0. */
private fun TestProgram.echoSub() = sub("main.echo", returns = listOf(DataType.UBYTE), code = """
    _main.echo:
    call main.readchar():r1.b
    cmpi.b r1.b,#0.b
    bsteq main.echo.done
    syscall 2 (r1.b)
    jump main.echo
    _main.echo.done:
    returni.b #0.b
    """)

private fun TestProgram.silentSub() = sub("main.silent", returns = listOf(DataType.UBYTE), code = """
    _main.silent:
    returni.b #0.b
    """)

private fun TestProgram.allOutputSubs() {
    printCharSub()
    printStringSub()
}

/** Prints 'A' and a newline, "hi\n", "hello, world!\n", and "hi\n" once more through the string helper; returns 0. */
private fun TestProgram.greetingSub() {
    string("main.hello", "hello, world!\n")
    sub("main.greeting", returns = listOf(DataType.UBYTE), code = """
        _main.greeting:
        call main.printchar()
        call main.printstring()
        load.l r10.l,#main.hello
        syscall 3 (r10.l)
        call main.printstring()
        returni.b #0.b
        """)
}
