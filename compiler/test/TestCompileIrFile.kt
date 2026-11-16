package prog8tests.compiler

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import prog8.code.target.Cx16Target
import prog8.code.target.LlvmTarget
import prog8.code.target.Qemu68kTarget
import prog8.code.target.VMTarget
import prog8.compiler.IrFileArguments
import prog8.compiler.compileIrFile
import prog8.intermediate.IRFileReader
import prog8.intermediate.IRStConstant
import prog8.intermediate.IRStStaticVariable
import prog8.intermediate.IRVariableInitializer
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.compileText
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.io.path.readBytes
import kotlin.io.path.readText


class TestCompileIrFile: FunSpec({

    val outputDir = tempdir().toPath()

    val source = $$"""
%import textio
%zeropage basicsafe
main {
    const long LOWEST = -2147483648
    long @shared value = LOWEST
    sub start() {
        txt.print("hello ")
        txt.print_l(value)
        txt.nl()
    }
}"""

    test("a .p8ir file written by the new 6502 code generator builds to the same size of program") {
        val first = outputDir.resolve("cx16-source").createDirectories()
        val result = compileText(Cx16Target(), false, source, first, newCodegen = true)!!
        val name = result.compilerAst.name
        val second = outputDir.resolve("cx16-ir").createDirectories()
        val errors = ErrorReporterForTests()
        val built = compileIrFile(first.resolve("$name.p8ir"), "cx16", IrFileArguments(second, quiet = true), errors)
        built shouldNotBe null
        errors.errors shouldBe emptyList()
        built!!.outputs.programFile shouldBe second.resolve("$name.prg")
        built.outputs.programFile.readBytes().size shouldBe first.resolve("$name.prg").readBytes().size
    }

    test("a .p8ir file of an llvm target builds to LLVM IR") {
        val first = outputDir.resolve("llvm-source").createDirectories()
        val result = compileText(LlvmTarget(8), false, source, first, assemble = false)!!
        val name = result.compilerAst.name
        val second = outputDir.resolve("llvm-ir").createDirectories()
        val errors = ErrorReporterForTests()
        compileIrFile(first.resolve("$name.p8ir"), "llvm64", IrFileArguments(second, quiet = true, assemble = false), errors) shouldNotBe null
        errors.errors shouldBe emptyList()
        second.resolve("$name.ll").readText() shouldContain "define"
    }

    test("a .p8ir file for another target than -target, or for the virtual target, is an error") {
        val first = outputDir.resolve("vm-source").createDirectories()
        val result = compileText(VMTarget(), false, source, first)!!
        val irFile = first.resolve("${result.compilerAst.name}.p8ir")
        val second = outputDir.resolve("vm-ir").createDirectories()
        val errors = ErrorReporterForTests(throwExceptionAtReportIfErrors = false, keepMessagesAfterReporting = true)
        compileIrFile(irFile, "cx16", IrFileArguments(second), errors) shouldBe null
        errors.errors.single() shouldContain "for the virtual target, not cx16"
        val errors2 = ErrorReporterForTests(throwExceptionAtReportIfErrors = false, keepMessagesAfterReporting = true)
        compileIrFile(irFile, null, IrFileArguments(second), errors2) shouldBe null
        errors2.errors.single() shouldContain "run it with -vm"
        second.resolve("${result.compilerAst.name}.p8ir").exists() shouldBe false
    }

    val m68kSource = """
%import textio
main {
    ubyte[4] zeros = [0, 0, 0, 0]
    sub start() {
        uword total
        ubyte i
        for i in 1 to 10
            total = add(total, i)
        zeros[3] = lsb(total)
        txt.print_uw(fib(10))
        txt.nl()
    }
    sub add(uword a, ubyte b) -> uword {
        uword sum = a + b
        return sum
    }
    sub fib(ubyte n) -> uword {
        if n < 2
            return n
        return fib(n - 1) + fib(n - 2)
    }
}"""

    // a .p8ir file has no initializer for an array of zeros, so it gets dcb.b
    fun asmCode(file: java.nio.file.Path) = file.readLines().filter { !it.trimStart().startsWith(';') && "; p8b_main.p8v_zeros" !in it }
    fun zerosLine(file: java.nio.file.Path) = file.readLines().single { "; p8b_main.p8v_zeros" in it }.substringBefore(';').trim()

    test("a .p8ir file the m68k code generator wrote builds to the same assembly code") {
        val first = outputDir.resolve("m68k-source").createDirectories()
        val result = compileText(Qemu68kTarget(), true, m68kSource, first, assemble = false)!!
        val name = result.compilerAst.name
        first.resolve("$name.p8ir").readText() shouldContain "FRAMESIZE="
        val second = outputDir.resolve("m68k-ir").createDirectories()
        val errors = ErrorReporterForTests()
        compileIrFile(first.resolve("$name.p8ir"), "qemu68k", IrFileArguments(second, quiet = true, assemble = false), errors) shouldNotBe null
        errors.errors shouldBe emptyList()
        asmCode(second.resolve("$name.asm")) shouldBe asmCode(first.resolve("$name.asm"))
        zerosLine(second.resolve("$name.asm")) shouldBe "dcb.b  4,0"
    }

    // as another tool writes it: static locals and parameters, arguments by parameter name, no frames
    val plainM68kIr = $$"""
<?xml version="1.0" encoding="utf-8"?>
<PROGRAM NAME="plain" COMPILERVERSION="test" IRFORMAT="8">
<OPTIONS>
compTarget=qemu68k
output=ELF
launcher=NONE
zeropage=DONTUSE
zpAllowed=0,255
loadAddress=$00010000
memtop=$00100000
optimize=false
romable=false
noSysInit=false
outputDir=.
</OPTIONS>

<ASMSYMBOLS>
</ASMSYMBOLS>
<VARS>
<NOINITCLEAN>
uword main.result zp=DONTCARE
uword main.start.total zp=DONTCARE
ubyte main.start.i zp=DONTCARE
ubyte main.add.b zp=DONTCARE
uword main.add.a zp=DONTCARE
uword main.add.sum zp=DONTCARE
</NOINITCLEAN>
<NOINITDIRTY>
</NOINITDIRTY>
<INIT>
ubyte[4] main.zeros=0,0,0,0 zp=DONTCARE
</INIT>
<STRUCTINSTANCESNOINIT>
</STRUCTINSTANCESNOINIT>
<STRUCTINSTANCES>
</STRUCTINSTANCES>
<STRUCTDEFS>
</STRUCTDEFS>
<CONSTANTS>
</CONSTANTS>
<MEMORYMAPPED>
</MEMORYMAPPED>
<MEMORYSLABS>
</MEMORYSLABS>
</VARS>
<INITGLOBALS>
<CHUNK><REGS><![CDATA[]]></REGS><CODE>
</CODE></CHUNK>
</INITGLOBALS>

<BLOCK NAME="main" ADDRESS="" LIBRARY="false" POS="[plain.p8: line 1 col 1-1]">
<SUB NAME="main.start" RETURNS="" POS="[plain.p8: line 3 col 5-6]">
<PARAMS>
</PARAMS>
<CHUNK LABEL="main.start"><REGS><![CDATA[]]></REGS><CODE>
storezm.w [main.start.total]
storeim.b #1.b,[main.start.i]
</CODE></CHUNK>
<CHUNK LABEL="p8_label_gen_1"><REGS><![CDATA[]]></REGS><CODE>
loadm.w r2.w,[main.start.total]
loadm.b r3.b,[main.start.i]
call main.add(a=r2.w,b=r3.b):r4.w
storem.w r4.w,[main.start.total]
incm.b [main.start.i]
loadm.b r1.b,[main.start.i]
cmpi.b r1.b,#11.b
bstne p8_label_gen_1
</CODE></CHUNK>
<CHUNK><REGS><![CDATA[]]></REGS><CODE>
loadm.w r5.w,[main.start.total]
storem.w r5.w,[main.result]
return
</CODE></CHUNK>
</SUB>

<SUB NAME="main.add" RETURNS="uword" POS="[plain.p8: line 10 col 5-6]">
<PARAMS>
uword main.add.a
ubyte main.add.b
</PARAMS>
<CHUNK LABEL="main.add"><REGS><![CDATA[]]></REGS><CODE>
loadm.w r6.w,[main.add.a]
loadm.b r7.b,[main.add.b]
ext.b r8.w,r7.b
addr.w r6.w,r8.w
storem.w r6.w,[main.add.sum]
loadm.w r9.w,[main.add.sum]
returnr.w r9.w
</CODE></CHUNK>
</SUB>

</BLOCK>

</PROGRAM>"""

    test("a .p8ir file of an m68k target with static locals gets its stack frames laid out") {
        val plainFile = outputDir.resolve("plain.p8ir")
        plainFile.writeText(plainM68kIr.trimStart())
        val second = outputDir.resolve("m68k-plain-ir").createDirectories()
        val errors = ErrorReporterForTests()
        compileIrFile(plainFile, "qemu68k", IrFileArguments(second, quiet = true, assemble = false), errors) shouldNotBe null
        errors.errors shouldBe emptyList()
        val ir = second.resolve("plain.p8ir").readText()
        ir shouldContain "<SUB NAME=\"main.start\" RETURNS=\"\" POS=\"[plain.p8: line 3 col 5-6]\" FRAMESIZE=\"4\">"
        ir shouldContain "call main.add(frame:10=r2.w,frame:8=r3.b):r4.w"
        val asm = second.resolve("plain.asm").readLines().map { it.substringBefore(';').trim() }
        asm.count { it.startsWith("link  a5,") } shouldBe 2
        asm.any { it == "main.start.total:" || it == "main.add.a:" } shouldBe false
        asm[asm.indexOf("main.zeros:") + 1] shouldBe "dcb.b  4,0"
    }

    test("negative signed constants and initial values keep their sign through a .p8ir file") {
        val result = compileText(VMTarget(), false, source, outputDir)!!
        val irProgram = IRFileReader().read(outputDir.resolve("${result.compilerAst.name}.p8ir"))
        (irProgram.st.lookup("main.LOWEST") as IRStConstant).value shouldBe -2147483648.0
        val value = irProgram.st.lookup("main.value") as IRStStaticVariable
        (value.initializationValue as IRVariableInitializer.Numeric).value shouldBe -2147483648.0
    }
})
