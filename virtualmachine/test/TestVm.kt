import io.kotest.assertions.throwables.shouldThrowWithMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import prog8.code.core.*
import prog8.code.target.VMTarget
import prog8.code.target.c64.C64MachineDefinition
import prog8.code.target.cx16.CX16MachineDefinition
import prog8.code.target.virtual.VirtualMachineDefinition
import prog8.intermediate.*
import prog8.vm.VirtualMachine
import prog8.vm.VmRunner

class TestVm: FunSpec( {

    fun getTestOptions(): CompilationOptions {
        val target = VMTarget()
        return CompilationOptions(
            OutputType.RAW,
            CbmPrgLauncherType.NONE,
            ZeropageType.DONTUSE,
            zpReserved = emptyList(),
            floats = true,
            noSysInit = false,
            compTarget = target,
            loadAddress = target.machine.PROGRAM_LOAD_ADDRESS
        )
    }

    test("vm execution: empty program") {
        val program = IRProgram("test", IRSymbolTable(null), getTestOptions(), VMTarget())
        val vm = VirtualMachine(program)
        vm.callStack.shouldBeEmpty()
        vm.valueStack.shouldBeEmpty()
        vm.pcIndex shouldBe 0
        vm.stepCount shouldBe 0
        vm.run()
        vm.callStack.shouldBeEmpty()
        vm.valueStack.shouldBeEmpty()
        vm.pcIndex shouldBe 0
        vm.stepCount shouldBe 0
    }

    test("vm execution: modify memory") {
        val program = IRProgram("test", IRSymbolTable(null), getTestOptions(), VMTarget())
        val block = IRBlock("testmain", null, IRBlock.BlockAlignment.NONE, Position.DUMMY)
        val startSub = IRSubroutine("testmain.testsub", emptyList(), null, Position.DUMMY)
        val code = IRCodeChunk(startSub.label, null)
        code += IRInstruction(Opcode.NOP)
        code += IRInstruction(Opcode.LOAD, IRDataType.WORD, reg1=1, value=12345)
        code += IRInstruction(Opcode.STOREM, IRDataType.WORD, reg1=1, value=1000)
        code += IRInstruction(Opcode.RETURN)
        startSub += code
        block += startSub
        program.addBlock(block)
        val vm = VirtualMachine(program)
        vm.memory.setUW(1000, 0u)

        vm.memory.getUW(1000) shouldBe 0u
        vm.callStack.shouldBeEmpty()
        vm.valueStack.shouldBeEmpty()
        vm.pcIndex shouldBe 0
        vm.stepCount shouldBe 0
        vm.run()
        vm.stepCount shouldBe 4
        vm.memory.getUW(1000) shouldBe 12345u
        vm.callStack.shouldBeEmpty()
        vm.valueStack.shouldBeEmpty()
        vm.pcIndex shouldBe code.instructions.size-1
        vm.stepCount shouldBe code.instructions.size
    }

    test("vm asmbinary not supported") {
        val program = IRProgram("test", IRSymbolTable(null), getTestOptions(), VMTarget())
        val block = IRBlock("testmain", null, IRBlock.BlockAlignment.NONE, Position.DUMMY)
        val startSub = IRSubroutine("testmain.testsub", emptyList(), null, Position.DUMMY)
        val code = IRCodeChunk(startSub.label, null)
        code += IRInstruction(Opcode.BINARYDATA, binaryData = listOf(1u,2u,3u))
        code += IRInstruction(Opcode.RETURN)
        startSub += code
        block += startSub
        program.addBlock(block)
        val vm = VirtualMachine(program)
        shouldThrowWithMessage<NotImplementedError>("An operation is not implemented: BINARYDATA not yet supported in VM") {
            vm.run()
        }
    }

    test("asmsub not supported in vm even with IR") {
        val program = IRProgram("test", IRSymbolTable(null), getTestOptions(), VMTarget())
        val block = IRBlock("main", null, IRBlock.BlockAlignment.NONE, Position.DUMMY)
        val startSub = IRAsmSubroutine(
            "main.asmstart",
            0x2000u,
            emptySet(),
            emptyList(),
            emptyList(),
            IRInlineAsmChunk("main.asmstart", "return", false, null),
            Position.DUMMY
        )
        block += startSub
        program.addBlock(block)
        shouldThrowWithMessage<IRParseException>("vm does not support asmsubs (use normal sub): main.asmstart") {
            VirtualMachine(program)
        }
    }

    test("vmrunner") {
        val runner = VmRunner()
        val irSource="""<?xml version="1.0" encoding="utf-8"?>
<PROGRAM NAME="test">
<OPTIONS>
</OPTIONS>

<ASMSYMBOLS>
</ASMSYMBOLS>

<VARIABLESNOINIT>
</VARIABLESNOINIT>
<VARIABLESWITHINIT>
</VARIABLESWITHINIT>

<MEMORYMAPPEDVARIABLES>
</MEMORYMAPPEDVARIABLES>

<MEMORYSLABS>
</MEMORYSLABS>

<INITGLOBALS>
</INITGLOBALS>

<BLOCK NAME="main" ADDRESS="null" ALIGN="NONE" POS="[unittest: line 42 col 1-9]">
</BLOCK>
</PROGRAM>
"""
        runner.runProgram(irSource)
    }

    test("vm machine float bits") {
        val cx16machine = CX16MachineDefinition()
        cx16machine.getFloatAsmBytes(Math.PI) shouldBe "\$82, \$49, \$0f, \$da, \$a2"
        val c64machine = C64MachineDefinition()
        c64machine.getFloatAsmBytes(Math.PI) shouldBe "\$82, \$49, \$0f, \$da, \$a2"

        val vm = VirtualMachineDefinition()
        vm.getFloatAsmBytes(Math.PI) shouldBe "\$40, \$49, \$0f, \$db"
    }
})
