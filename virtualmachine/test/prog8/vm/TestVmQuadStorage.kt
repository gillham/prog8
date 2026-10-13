package prog8.vm

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import prog8.code.core.*
import prog8.code.target.VMTarget
import prog8.intermediate.*

class TestVmQuadStorage: FunSpec({

    val target = VMTarget()
    val quad = IRDataType.QUAD

    fun options(): CompilationOptions = CompilationOptions.builder(target)
        .output(OutputType.RAW)
        .zeropage(ZeropageType.DONTUSE)
        .floats(true)
        .compilerVersion("99.99")
        .loadAddress(target.PROGRAM_LOAD_ADDRESS)
        .memtopAddress(0xffffu)
        .build()

    fun programOf(st: IRSymbolTable, vararg subroutines: Pair<String, List<IRInstruction>>): IRProgram {
        val program = IRProgram("test", st, options(), target)
        val block = IRBlock("main", false, IRBlock.Options(), Position.DUMMY)
        for ((label, instructions) in subroutines) {
            val sub = IRSubroutine(label, emptyList(), emptyList(), Position.DUMMY)
            val chunk = IRCodeChunk(label, null)
            instructions.forEach { chunk += it }
            sub += chunk
            block += sub
        }
        program.addBlock(block)
        program.linkChunks()
        return program
    }

    // not main.start: the loader prepends an entry jump to that, which would cost an extra step
    val testLabel = "main.test"

    fun vmRunning(vararg instructions: IRInstruction): VirtualMachine {
        val vm = VirtualMachine(programOf(IRSymbolTable(), testLabel to instructions.toList() + IRInstructions.returnVoid()))
        vm.reset(true)
        return vm
    }

    fun Memory.bytesAt(address: UInt, count: Int = 8) = (0 until count).map { getUB(address + it.toUInt()).toInt() }

    fun littleEndian(value: Long) = (0 until 8).map { ((value ushr (8 * it)) and 0xFFL).toInt() }

    test("registers hold 64 bits and the narrow getters return the low bits") {
        val registers = Registers()
        registers.setSQ(1, 0x0123_4567_89AB_CDEFL)
        registers.getSQ(1) shouldBe 0x0123_4567_89AB_CDEFL
        registers.getUQ(1) shouldBe 0x0123_4567_89AB_CDEFuL
        registers.getUL(1) shouldBe 0x89AB_CDEFu
        registers.getSL(1) shouldBe 0x89AB_CDEF.toInt()
        registers.getUW(1) shouldBe 0xCDEFu.toUShort()
        registers.getSW(1) shouldBe 0xCDEF.toShort()
        registers.getUB(1) shouldBe 0xEFu.toUByte()
        registers.getSB(1) shouldBe 0xEF.toByte()

        val r2 = RegisterNum(2)
        registers.setUQ(r2, 0xFEDC_BA98_7654_3210uL)
        registers.getUQ(r2) shouldBe 0xFEDC_BA98_7654_3210uL
        registers.getSQ(r2) shouldBe 0xFEDC_BA98_7654_3210uL.toLong()
        registers.getUQ(2) shouldBe 0xFEDC_BA98_7654_3210uL
        registers.getUL(r2) shouldBe 0x7654_3210u
        registers.setSQ(r2, Long.MIN_VALUE)
        registers.getSQ(2) shouldBe Long.MIN_VALUE
        registers.getUQ(r2) shouldBe 0x8000_0000_0000_0000uL
        registers.getSL(r2) shouldBe 0
    }

    test("narrow register setters zero-extend or sign-extend to 64 bits, reset clears all 64 bits") {
        val registers = Registers()
        fun afterSetting(assign: Registers.() -> Unit): Long {
            registers.setSQ(7, 0x5555_5555_5555_5555L)
            registers.assign()
            return registers.getSQ(7)
        }
        afterSetting { setUB(7, 0xFFu) } shouldBe 0xFFL
        afterSetting { setUW(7, 0xFFFFu) } shouldBe 0xFFFFL
        afterSetting { setUL(7, 0xFFFF_FFFFu) } shouldBe 0xFFFF_FFFFL
        afterSetting { setSB(7, -1) } shouldBe -1L
        afterSetting { setSW(7, -2) } shouldBe -2L
        afterSetting { setSL(7, Int.MIN_VALUE) } shouldBe Int.MIN_VALUE.toLong()
        afterSetting { setUB(RegisterNum(7), 0x80u) } shouldBe 0x80L
        afterSetting { setSB(RegisterNum(7), Byte.MIN_VALUE) } shouldBe Byte.MIN_VALUE.toLong()
        afterSetting { setUL(RegisterNum(7), 0x8000_0000u) } shouldBe 0x8000_0000L
        afterSetting { setSL(RegisterNum(7), -3) } shouldBe -3L

        registers.setSQ(3, Long.MIN_VALUE)
        registers.setUQ(4, 0xFFFF_FFFF_0000_0000uL)
        registers.setSQ(RegisterNum(5), -1L)
        registers.reset()
        registers.getSQ(3) shouldBe 0L
        registers.getSQ(4) shouldBe 0L
        registers.getUQ(5) shouldBe 0uL
    }

    test("memory stores quads as 8 little-endian bytes without touching the neighbours") {
        val memory = Memory()
        val address = 0x2000u
        memory.setUB(address - 1u, 0xA5u)
        memory.setUB(address + 8u, 0x5Au)
        memory.setSQ(address, 0x0807_0605_0403_0201L)
        memory.bytesAt(address - 1u, 10) shouldBe listOf(0xA5, 1, 2, 3, 4, 5, 6, 7, 8, 0x5A)
        memory.getSQ(address) shouldBe 0x0807_0605_0403_0201L
        memory.getUQ(address) shouldBe 0x0807_0605_0403_0201uL

        memory.setUQ(address, 0xF0E0_D0C0_B0A0_9080uL)
        memory.bytesAt(address - 1u, 10) shouldBe listOf(0xA5, 0x80, 0x90, 0xA0, 0xB0, 0xC0, 0xD0, 0xE0, 0xF0, 0x5A)
        memory.getUQ(address) shouldBe 0xF0E0_D0C0_B0A0_9080uL
        memory.getSQ(address) shouldBe 0xF0E0_D0C0_B0A0_9080uL.toLong()

        val bytes = listOf(0xEF, 0xCD, 0xAB, 0x89, 0x67, 0x45, 0x23, 0x81)
        bytes.forEachIndexed { index, byte -> memory.setUB(address + 0x11u + index.toUInt(), byte.toUByte()) }
        memory.getSQ(address + 0x11u) shouldBe 0x8123_4567_89AB_CDEFuL.toLong()
        memory.getUQ(address + 0x11u) shouldBe 0x8123_4567_89AB_CDEFuL
    }

    test("value stack pushes quads lsb first and pops them back, also when mixed with longs") {
        val stack = vmRunning().valueStack
        stack.pushq(0x0807_0605_0403_0201L)
        stack.map { it.toInt() } shouldBe listOf(1, 2, 3, 4, 5, 6, 7, 8)
        stack.popq() shouldBe 0x0807_0605_0403_0201L
        stack.isEmpty() shouldBe true

        stack.pushl(0x1122_3344)
        stack.pushq(0x8000_0000_0000_0055uL.toLong())
        stack.pushl(-2)
        stack.pushq(-0x0102_0304_0506_0708L)
        stack.size shouldBe 24
        stack.popq() shouldBe -0x0102_0304_0506_0708L
        stack.popl() shouldBe -2
        stack.popq() shouldBe 0x8000_0000_0000_0055uL.toLong()
        stack.popl() shouldBe 0x1122_3344
        stack.isEmpty() shouldBe true
    }

    test("hardware D registers keep all 64 bits and STOREHR.l zero-extends") {
        val values = (1..8).map { (0x0101_0101_0101_0101L * it) xor Long.MIN_VALUE }
        val stores = (0..7).map { IRInstructions.hardwareStore(quad, 1 + it, CallingConventionSlot(10 + it)) }
        val loads = (0..7).map { IRInstructions.hardwareLoad(quad, 11 + it, CallingConventionSlot(10 + it)) }
        val vm = vmRunning(*(stores + loads).toTypedArray())
        values.forEachIndexed { index, value -> vm.registers.setSQ(1 + index, value) }
        vm.step(16)
        (11..18).map { vm.registers.getSQ(it) } shouldBe values
        vm.hardwareRegisterD.toList() shouldBe values.map { it.toULong() }

        val vm2 = vmRunning(
            IRInstructions.hardwareStore(quad, 1, CallingConventionSlot(13)),
            IRInstructions.hardwareStore(IRDataType.LONG, 2, CallingConventionSlot(13)),
            IRInstructions.hardwareLoad(quad, 3, CallingConventionSlot(13))
        )
        vm2.registers.setSQ(1, -1L)
        vm2.registers.setSL(2, 0x89AB_CDEF.toInt())
        vm2.registers.setSQ(3, 0x5555_5555_5555_5555L)
        vm2.step(3)
        vm2.hardwareRegisterD[3] shouldBe 0x89AB_CDEFuL
        vm2.registers.getSQ(3) shouldBe 0x89AB_CDEFL
    }

    test("LOAD.q and LOADR.q move all 64 bits") {
        val negative = 0x8123_4567_89AB_CDEFuL.toLong()
        val load = IRInstructions.load(quad, 1, negative)
        load.immediate shouldBe ImmediateOperand.QuadValue(negative)
        val vm = vmRunning(
            load,
            IRInstructions.load(quad, 2, 0x8000_0000L),
            IRInstructions.load(quad, 3, 0x7FFF_FFFF_0000_0000L),
            IRInstructions.move(quad, 4, 1)
        )
        (1..4).forEach { vm.registers.setSQ(it, 0x5555_5555_5555_5555L) }
        vm.step(4)
        vm.registers.getSQ(1) shouldBe negative
        vm.registers.getSQ(2) shouldBe 0x8000_0000L
        vm.registers.getSQ(3) shouldBe 0x7FFF_FFFF_0000_0000L
        vm.registers.getUQ(4) shouldBe 0x8123_4567_89AB_CDEFuL
    }

    test("LOADM.q and STOREM.q move exactly 8 bytes") {
        val source = 0x3000u
        val destination = 0x3100u
        val value = 0x8123_4567_89AB_CDEFuL.toLong()
        val vm = vmRunning(
            IRInstructions.loadMemory(Opcode.LOADM, quad, 1, IRMemory.direct(source)),
            IRInstructions.storeMemory(Opcode.STOREM, quad, 1, IRMemory.direct(destination))
        )
        littleEndian(value).forEachIndexed { index, byte -> vm.memory.setUB(source + index.toUInt(), byte.toUByte()) }
        vm.memory.setUB(destination - 1u, 0xA5u)
        vm.memory.setUB(destination + 8u, 0xA5u)
        vm.step(2)
        vm.registers.getSQ(1) shouldBe value
        vm.memory.bytesAt(destination - 1u, 10) shouldBe listOf(0xA5) + littleEndian(value) + listOf(0xA5)
    }

    test("LOADX.q and STOREX.q scale the index register by 8") {
        val source = 0x3200u
        val destination = 0x3300u
        val elements = listOf(0x1111_1111_1111_1111L, -0x2222_2222_2222_2222L, 0x0807_0605_0403_0201L, Long.MIN_VALUE)
        val vm = vmRunning(
            IRInstructions.loadMemory(Opcode.LOADX, quad, 1, IRMemory.indexed(source, 5, IRDataType.WORD, 8)),
            IRInstructions.storeMemory(Opcode.STOREX, quad, 1, IRMemory.indexed(destination, 6, IRDataType.WORD, 8))
        )
        elements.forEachIndexed { index, element -> vm.memory.setSQ(source + index.toUInt() * 8u, element) }
        for (offset in 0u until 32u) vm.memory.setUB(destination + offset, 0xA5u)
        vm.registers.setUW(5, 2u)
        vm.registers.setUW(6, 1u)
        vm.step(2)
        vm.registers.getSQ(1) shouldBe elements[2]
        vm.memory.bytesAt(destination, 32) shouldBe List(8) { 0xA5 } + littleEndian(elements[2]) + List(16) { 0xA5 }
    }

    test("LOADI.q and STOREI.q add the displacement to the pointer register") {
        val source = 0x3400u
        val destination = 0x3500u
        val value = -0x0102_0304_0506_0708L
        val vm = vmRunning(
            IRInstructions.loadMemory(Opcode.LOADI, quad, 1, IRMemory.indirect(6, 3)),
            IRInstructions.storeMemory(Opcode.STOREI, quad, 1, IRMemory.indirect(7, 5))
        )
        vm.memory.setSQ(source + 3u, value)
        for (offset in 0u until 16u) vm.memory.setUB(destination + offset, 0xA5u)
        vm.registers.setUL(6, source)
        vm.registers.setUL(7, destination)
        vm.step(2)
        vm.registers.getSQ(1) shouldBe value
        vm.memory.bytesAt(destination, 16) shouldBe List(5) { 0xA5 } + littleEndian(value) + List(3) { 0xA5 }
    }

    test("STOREIM.q and STOREZM, STOREZX, STOREZI .q write exactly 8 bytes") {
        val base = 0x3600u
        val immediate = 0x8877_6655_4433_2211uL.toLong()
        val storeImmediate = IRInstructions.storeImmediate(quad, immediate, IRMemory.direct(base + 1u))
        storeImmediate.immediate shouldBe ImmediateOperand.QuadValue(immediate)
        val vm = vmRunning(
            storeImmediate,
            IRInstructions.storeZero(Opcode.STOREZM, quad, IRMemory.direct(base + 0x11u)),
            IRInstructions.storeZero(Opcode.STOREZX, quad, IRMemory.indexed(base + 0x20u, 5, IRDataType.WORD, 8)),
            IRInstructions.storeZero(Opcode.STOREZI, quad, IRMemory.indirect(6, 3))
        )
        for (offset in 0u until 0x50u) vm.memory.setUB(base + offset, 0xFFu)
        vm.registers.setUW(5, 1u)
        vm.registers.setUL(6, base + 0x38u)
        vm.step(4)
        val expected = MutableList(0x50) { 0xFF }
        littleEndian(immediate).forEachIndexed { index, byte -> expected[1 + index] = byte }
        for (start in listOf(0x11, 0x28, 0x3B))
            for (index in 0 until 8) expected[start + index] = 0
        vm.memory.bytesAt(base, 0x50) shouldBe expected
    }

    test("CALL passes a quad argument in parameter memory, RETURNR.q and RETURNI.q return all 64 bits") {
        val parameter = 0x3800u
        val argument = 0x8877_6655_4433_2211uL.toLong()
        val constant = Long.MIN_VALUE + 3
        val callEcho = IRInstructions.call(CallSite(
            CallTarget.Direct(codeLabel("main.echo")),
            arguments = listOf(Calls.argument(1, quad, CallLocation.ParameterMemory("main.echo.value", parameter.toAddress()))),
            results = listOf(Calls.result(2, quad))
        ))
        val callConstant = IRInstructions.call(CallSite(
            CallTarget.Direct(codeLabel("main.constant")),
            results = listOf(Calls.result(3, quad))
        ))
        val program = programOf(IRSymbolTable(),
            "main.start" to listOf(IRInstructions.load(quad, 1, argument), callEcho, callConstant, IRInstructions.returnVoid()),
            "main.echo" to listOf(
                IRInstructions.loadMemory(Opcode.LOADM, quad, 10, IRMemory.direct(parameter)),
                IRInstructions.returnRegister(quad, 10)
            ),
            "main.constant" to listOf(IRInstructions.returnImmediate(quad, constant))
        )
        val vm = VirtualMachine(program)
        vm.reset(true)
        vm.memory.setUB(parameter - 1u, 0xA5u)
        vm.memory.setUB(parameter + 8u, 0xA5u)
        vm.registers.setSQ(2, 0x5555_5555_5555_5555L)
        vm.registers.setSQ(3, 0x5555_5555_5555_5555L)
        vm.run(true)
        vm.memory.bytesAt(parameter - 1u, 10) shouldBe listOf(0xA5) + littleEndian(argument) + listOf(0xA5)
        vm.registers.getSQ(2) shouldBe argument
        vm.registers.getSQ(3) shouldBe constant
    }

    fun staticVar(name: String, dt: DataType, init: IRVariableInitializer?, length: UInt? = null) =
        IRStStaticVariable(name, dt, init, length, ZeropageWish.DONTCARE, 0u, false)

    fun loaded(st: IRSymbolTable, vararg instructions: IRInstruction): Pair<VirtualMachine, Map<String, UInt>> {
        val program = programOf(st, testLabel to instructions.toList() + IRInstructions.returnVoid())
        val vm = VirtualMachine(program)
        return vm to VmVariableAllocator(st, program.encoding, target).allocations
    }

    test("loader writes quad and uquad initializers from their exact bits and zeroes uninitialized quads") {
        val st = IRSymbolTable()
        st.add(staticVar("main.big", DataType.QUAD, IRVariableInitializer.Numeric(9.2e18, Long.MAX_VALUE - 1)))
        st.add(staticVar("main.ubig", DataType.UQUAD, IRVariableInitializer.Numeric(1.8e19, -2L)))
        st.add(staticVar("main.zero", DataType.QUAD, null))
        st.add(staticVar("main.uzero", DataType.UQUAD, null))
        val (vm, addresses) = loaded(st)
        vm.memory.getSQ(addresses.getValue("main.big")) shouldBe Long.MAX_VALUE - 1
        vm.memory.getUQ(addresses.getValue("main.ubig")) shouldBe 0xFFFF_FFFF_FFFF_FFFEuL
        vm.memory.bytesAt(addresses.getValue("main.zero")) shouldBe List(8) { 0 }
        vm.memory.bytesAt(addresses.getValue("main.uzero")) shouldBe List(8) { 0 }
    }

    test("loader zeroes uninitialized quad arrays and writes initialized ones 8 bytes per element") {
        val st = IRSymbolTable()
        st.add(staticVar("main.zeros", DataType.arrayFor(BaseDataType.QUAD, target), null, 3u))
        st.add(staticVar("main.values", DataType.arrayFor(BaseDataType.QUAD, target), IRVariableInitializer.Array(listOf(
            IRStSymbolicReference.Numeric(-1.0, -1L),
            IRStSymbolicReference.Numeric(9.2e18, Long.MAX_VALUE - 1),
            IRStSymbolicReference.Numeric(-9.2e18, Long.MIN_VALUE + 1),
            IRStSymbolicReference.Numeric(5.0e9, 5_000_000_000L)
        )), 4u))
        st.add(staticVar("main.uzeros", DataType.arrayFor(BaseDataType.UQUAD, target), null, 2u))
        st.add(staticVar("main.uvalues", DataType.arrayFor(BaseDataType.UQUAD, target), IRVariableInitializer.Array(listOf(
            IRStSymbolicReference.Numeric(1.8e19, -2L),
            IRStSymbolicReference.Numeric(4.0e9, 0xFFFF_FFFFL)
        )), 2u))
        val (vm, addresses) = loaded(st)
        fun elements(name: String, count: Int) = (0 until count).map { vm.memory.getSQ(addresses.getValue(name) + it.toUInt() * 8u) }
        elements("main.zeros", 3) shouldBe List(3) { 0L }
        elements("main.values", 4) shouldBe listOf(-1L, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1, 5_000_000_000L)
        elements("main.uzeros", 2) shouldBe List(2) { 0L }
        elements("main.uvalues", 2) shouldBe listOf(-2L, 0xFFFF_FFFFL)
    }

    test("loader lays out a struct instance with ubyte, quad and uquad fields at offsets 0, 1 and 9") {
        val st = IRSymbolTable()
        st.add(IRStStructInstance("main.item", "main.Item", listOf(
            IRStructInitValue(BaseDataType.UBYTE, IRStSymbolicReference.Numeric(171.0)),
            IRStructInitValue(BaseDataType.QUAD, IRStSymbolicReference.Numeric(-9.2e18, Long.MIN_VALUE + 5)),
            IRStructInitValue(BaseDataType.UQUAD, IRStSymbolicReference.Numeric(1.8e19, -3L))
        ), 17u))
        val (vm, addresses) = loaded(st)
        vm.memory.bytesAt(addresses.getValue("main.item"), 17) shouldBe listOf(171) + littleEndian(Long.MIN_VALUE + 5) + littleEndian(-3L)
    }

    test("loader resolves LOAD.q of a symbol address into a QuadValue holding that address") {
        val st = IRSymbolTable()
        st.add(staticVar("main.before", DataType.LONG, IRVariableInitializer.Numeric(0.0)))
        st.add(staticVar("main.value", DataType.QUAD, IRVariableInitializer.Numeric(1.0, 1L)))
        val (vm, addresses) = loaded(st,
            IRInstructions.loadAddress(quad, 1, "main.value"),
            IRInstructions.loadAddress(quad, 2, "main.value", 3)
        )
        val address = addresses.getValue("main.value").toLong()
        val chunk = vm.program.single { it.label == testLabel }
        chunk.instructions.take(2).map { it.immediate } shouldBe
                listOf(ImmediateOperand.QuadValue(address), ImmediateOperand.QuadValue(address + 3))
        vm.registers.setSQ(1, -1L)
        vm.registers.setSQ(2, -1L)
        vm.step(2)
        vm.registers.getSQ(1) shouldBe address
        vm.registers.getSQ(2) shouldBe address + 3
    }

    test("QUADARRAY_CONTAINS syscall only matches complete 64-bit elements") {
        Syscall.QUADARRAY_CONTAINS.ordinal shouldBe 70
        val haystack = 0x3A00u
        val vm = vmRunning(IRInstructions.syscall(
            IMSyscall.QUADARRAY_CONTAINS.number,
            listOf(Calls.argument(1, quad), Calls.argument(2, IRDataType.LONG), Calls.argument(3, IRDataType.WORD)),
            listOf(Calls.result(4, IRDataType.BYTE))
        ))
        vm.program.single { it.label == testLabel }.instructions.first().callSite!!.target shouldBe
                CallTarget.SystemCall(Syscall.QUADARRAY_CONTAINS.ordinal)
        listOf(-1L, 5_000_000_000L, Long.MIN_VALUE, 0x1_2345_6789L).forEachIndexed { index, element ->
            vm.memory.setSQ(haystack + index.toUInt() * 8u, element)
        }
        fun contains(needle: Long, length: Int): Int {
            vm.reset(false)
            vm.registers.setSQ(1, needle)
            vm.registers.setSL(2, haystack.toInt())
            vm.registers.setUW(3, length.toUShort())
            vm.registers.setUB(4, 99u)
            vm.step()
            return vm.registers.getUB(4).toInt()
        }
        contains(-1L, 4) shouldBe 1
        contains(5_000_000_000L, 4) shouldBe 1
        contains(Long.MIN_VALUE, 4) shouldBe 1
        contains(0x1_2345_6789L, 4) shouldBe 1
        contains(0x1_2345_6789L, 3) shouldBe 0
        contains(5_000_000_000L and 0xFFFF_FFFFL, 4) shouldBe 0
        contains(0xFFFF_FFFFL, 4) shouldBe 0
        contains(0x2345_6789L, 4) shouldBe 0
        contains(0L, 4) shouldBe 0
    }
})
