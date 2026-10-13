package prog8.vm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import prog8.code.core.*
import prog8.code.target.VMTarget
import prog8.intermediate.*
import java.math.BigDecimal
import java.math.BigInteger

private enum class Form { REG, IMM, MEM, MEM_IMM }

private data class BinaryOp(val opcode: Opcode, val form: Form, val reference: (Long, Long) -> Long)

private data class UnaryOp(val opcode: Opcode, val inMemory: Boolean, val reference: (Long) -> Long)

private data class Flags(val zero: Boolean, val negative: Boolean, val carry: Boolean, val overflow: Boolean)

private val mask64: BigInteger = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE

private fun signed(value: Long): BigInteger = BigInteger.valueOf(value)

private fun unsigned(value: Long): BigInteger = BigInteger.valueOf(value).and(mask64)

private fun hex(value: Long) = "0x" + value.toULong().toString(16)

private fun MutableList<String>.verify(description: String, actual: Any?, expected: Any?) {
    if (actual != expected) add("$description: expected $expected, got $actual")
}

private val edgeValues = listOf(
    0L, 1L, -1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE + 1,
    0x7FFF_FFFFL, 0x8000_0000L, 0xFFFF_FFFFL, 0x1_0000_0000L, 0x1_0000_0001L, -0x1_0000_0000L,
    0x0123_4567_89AB_CDEFL, 0xFEDC_BA98_7654_3210uL.toLong()
)

class TestVmQuadInstructions: FunSpec({

    val target = VMTarget()
    val quad = IRDataType.QUAD
    // not main.start: the loader prepends an entry jump to that
    val testLabel = "main.test"

    fun options(): CompilationOptions = CompilationOptions.builder(target)
        .output(OutputType.RAW)
        .zeropage(ZeropageType.DONTUSE)
        .floats(true)
        .compilerVersion("99.99")
        .loadAddress(target.PROGRAM_LOAD_ADDRESS)
        .memtopAddress(0xffffu)
        .build()

    fun vmOf(chunks: List<Pair<String, List<IRInstruction>>>): VirtualMachine {
        val program = IRProgram("test", IRSymbolTable(), options(), target)
        val block = IRBlock("main", false, IRBlock.Options(), Position.DUMMY)
        val sub = IRSubroutine(testLabel, emptyList(), emptyList(), Position.DUMMY)
        for ((label, instructions) in chunks) {
            val chunk = IRCodeChunk(label, null)
            instructions.forEach { chunk += it }
            sub += chunk
        }
        block += sub
        program.addBlock(block)
        program.linkChunks()
        return VirtualMachine(program)
    }

    fun vmRunning(instructions: List<IRInstruction>) = vmOf(listOf(testLabel to instructions + IRInstructions.returnVoid()))

    fun VirtualMachine.runWith(setup: VirtualMachine.() -> Unit) {
        reset(false)
        setup()
        run(true)
    }

    fun reg(group: Int, index: Int) = group * 1000 + index
    fun mem(group: Int, index: Int) = 0x2000u + (group * 128 + index).toUInt() * 8u
    fun direct(group: Int, index: Int) = IRMemory.direct(mem(group, index))

    // every (op, right) pair gets its own instruction and left operand location; one run per left value
    fun checkBinaryOps(ops: List<BinaryOp>, lefts: List<Long>, rights: List<Long>): List<String> {
        val instructions = ops.flatMapIndexed { g, op ->
            rights.mapIndexed { j, right ->
                when (op.form) {
                    Form.REG -> IRInstructions.binary(op.opcode, quad, reg(g + 2, j), reg(1, j))
                    Form.IMM -> IRInstructions.binaryImmediate(op.opcode, quad, reg(g + 2, j), right)
                    Form.MEM -> IRInstructions.memoryOp(op.opcode, quad, direct(g + 1, j), reg(1, j))
                    Form.MEM_IMM -> IRInstructions.memoryOpImmediate(op.opcode, quad, direct(g + 1, j), right)
                }
            }
        }
        val vm = vmRunning(instructions)
        val failures = mutableListOf<String>()
        for (left in lefts) {
            vm.runWith {
                rights.forEachIndexed { j, right ->
                    registers.setSQ(reg(1, j), right)
                    ops.indices.forEach { g ->
                        registers.setSQ(reg(g + 2, j), left)
                        memory.setSQ(mem(g + 1, j), left)
                    }
                }
            }
            ops.forEachIndexed { g, op ->
                rights.forEachIndexed { j, right ->
                    val inMemory = op.form == Form.MEM || op.form == Form.MEM_IMM
                    val actual = if (inMemory) vm.memory.getSQ(mem(g + 1, j)) else vm.registers.getSQ(reg(g + 2, j))
                    failures.verify("${op.opcode}.q ${hex(left)}, ${hex(right)}", hex(actual), hex(op.reference(left, right)))
                }
            }
        }
        return failures
    }

    fun checkUnaryOps(ops: List<UnaryOp>, values: List<Long>): List<String> {
        val instructions = ops.mapIndexed { g, op ->
            if (op.inMemory) IRInstructions.memoryOp(op.opcode, quad, direct(1, g))
            else IRInstructions.unary(op.opcode, quad, reg(1, g))
        }
        val vm = vmRunning(instructions)
        val failures = mutableListOf<String>()
        for (value in values) {
            vm.runWith {
                ops.indices.forEach { g ->
                    registers.setSQ(reg(1, g), value)
                    memory.setSQ(mem(1, g), value)
                }
            }
            ops.forEachIndexed { g, op ->
                val actual = if (op.inMemory) vm.memory.getSQ(mem(1, g)) else vm.registers.getSQ(reg(1, g))
                failures.verify("${op.opcode}.q ${hex(value)}", hex(actual), hex(op.reference(value)))
            }
        }
        return failures
    }

    test("ADDR ADD ADDM ADDIM and SUBR SUB SUBM SUBIM .q wrap around at 64 bits") {
        val add = { l: Long, r: Long -> (signed(l) + signed(r)).toLong() }
        val sub = { l: Long, r: Long -> (signed(l) - signed(r)).toLong() }
        add(Long.MAX_VALUE, 1L) shouldBe Long.MIN_VALUE
        sub(Long.MIN_VALUE, 1L) shouldBe Long.MAX_VALUE
        val ops = listOf(
            BinaryOp(Opcode.ADDR, Form.REG, add),
            BinaryOp(Opcode.ADD, Form.IMM, add),
            BinaryOp(Opcode.ADDM, Form.MEM, add),
            BinaryOp(Opcode.ADDIM, Form.MEM_IMM, add),
            BinaryOp(Opcode.SUBR, Form.REG, sub),
            BinaryOp(Opcode.SUB, Form.IMM, sub),
            BinaryOp(Opcode.SUBM, Form.MEM, sub),
            BinaryOp(Opcode.SUBIM, Form.MEM_IMM, sub)
        )
        checkBinaryOps(ops, edgeValues, edgeValues) shouldBe emptyList()
    }

    test("INC INCM DEC DECM NEG NEGM INV INVM .q work on all 64 bits and wrap") {
        val ops = listOf(
            UnaryOp(Opcode.INC, false) { (signed(it) + BigInteger.ONE).toLong() },
            UnaryOp(Opcode.INCM, true) { (signed(it) + BigInteger.ONE).toLong() },
            UnaryOp(Opcode.DEC, false) { (signed(it) - BigInteger.ONE).toLong() },
            UnaryOp(Opcode.DECM, true) { (signed(it) - BigInteger.ONE).toLong() },
            UnaryOp(Opcode.NEG, false) { (-signed(it)).toLong() },
            UnaryOp(Opcode.NEGM, true) { (-signed(it)).toLong() },
            UnaryOp(Opcode.INV, false) { it.inv() },
            UnaryOp(Opcode.INVM, true) { it.inv() }
        )
        checkUnaryOps(ops, edgeValues + listOf(Long.MAX_VALUE - 1, -0x1_0000_0001L)) shouldBe emptyList()

        val incNeg = vmRunning(listOf(IRInstructions.unary(Opcode.INC, quad, 1), IRInstructions.unary(Opcode.NEG, quad, 2),
            IRInstructions.unary(Opcode.INC, quad, 3), IRInstructions.unary(Opcode.DEC, quad, 4)))
        incNeg.runWith {
            registers.setSQ(1, Long.MAX_VALUE)
            registers.setSQ(2, Long.MIN_VALUE)
            registers.setSQ(3, 0xFFFF_FFFFL)
            registers.setSQ(4, 0x1_0000_0000L)
        }
        incNeg.registers.getSQ(1) shouldBe Long.MIN_VALUE
        incNeg.registers.getSQ(2) shouldBe Long.MIN_VALUE
        incNeg.registers.getSQ(3) shouldBe 0x1_0000_0000L
        incNeg.registers.getSQ(4) shouldBe 0xFFFF_FFFFL
    }

    test("MULR MUL MULM (unsigned) and MULSR MULS MULSM (signed) .q keep the low 64 bits of the product") {
        val mulUnsigned = { l: Long, r: Long -> (unsigned(l) * unsigned(r)).toLong() }
        val mulSigned = { l: Long, r: Long -> (signed(l) * signed(r)).toLong() }
        mulUnsigned(0xFFFF_FFFFL, 0xFFFF_FFFFL) shouldBe 0xFFFF_FFFE_0000_0001uL.toLong()
        mulUnsigned(0x1_0000_0000L, 0x1_0000_0000L) shouldBe 0L
        val ops = listOf(
            BinaryOp(Opcode.MULR, Form.REG, mulUnsigned),
            BinaryOp(Opcode.MUL, Form.IMM, mulUnsigned),
            BinaryOp(Opcode.MULM, Form.MEM, mulUnsigned),
            BinaryOp(Opcode.MULSR, Form.REG, mulSigned),
            BinaryOp(Opcode.MULS, Form.IMM, mulSigned),
            BinaryOp(Opcode.MULSM, Form.MEM, mulSigned)
        )
        val values = edgeValues + listOf(3L, -3L, 0x1_0000_0003L, 1_000_000_007L)
        checkBinaryOps(ops, values, values) shouldBe emptyList()
    }

    test("DIVR DIV DIVM (unsigned) and DIVSR DIVS DIVSM (signed) .q, including division by zero and MIN/-1") {
        val divUnsigned = { l: Long, r: Long -> if (r == 0L) ULong.MAX_VALUE.toLong() else unsigned(l).divide(unsigned(r)).toLong() }
        val divSigned = { l: Long, r: Long -> if (r == 0L) Long.MAX_VALUE else signed(l).divide(signed(r)).toLong() }
        divSigned(Long.MIN_VALUE, -1L) shouldBe Long.MIN_VALUE
        val ops = listOf(
            BinaryOp(Opcode.DIVR, Form.REG, divUnsigned),
            BinaryOp(Opcode.DIV, Form.IMM, divUnsigned),
            BinaryOp(Opcode.DIVM, Form.MEM, divUnsigned),
            BinaryOp(Opcode.DIVSR, Form.REG, divSigned),
            BinaryOp(Opcode.DIVS, Form.IMM, divSigned),
            BinaryOp(Opcode.DIVSM, Form.MEM, divSigned)
        )
        val values = edgeValues + listOf(3L, -3L, -10L, 10L, 0x1_0000_0003L, -7_000_000_000L)
        checkBinaryOps(ops, values, values) shouldBe emptyList()
    }

    test("MODR MOD (unsigned) and MODSR MODS (signed) .q: the remainder sign follows the dividend, x%0 is 0") {
        val modUnsigned = { l: Long, r: Long -> if (r == 0L) 0L else unsigned(l).rem(unsigned(r)).toLong() }
        val modSigned = { l: Long, r: Long -> if (r == 0L) 0L else signed(l).rem(signed(r)).toLong() }
        modSigned(-7L, 3L) shouldBe -1L
        modSigned(7L, -3L) shouldBe 1L
        val ops = listOf(
            BinaryOp(Opcode.MODR, Form.REG, modUnsigned),
            BinaryOp(Opcode.MOD, Form.IMM, modUnsigned),
            BinaryOp(Opcode.MODSR, Form.REG, modSigned),
            BinaryOp(Opcode.MODS, Form.IMM, modSigned)
        )
        val values = edgeValues + listOf(3L, -3L, -8L, 7L, -7L, 0x1_0000_0003L, -7_000_000_000L)
        checkBinaryOps(ops, values, values) shouldBe emptyList()
    }

    test("the same bit pattern divides differently signed and unsigned: -10 / 3 and -8 % 3") {
        val vm = vmRunning(listOf(
            IRInstructions.binary(Opcode.DIVR, quad, 1, 9),
            IRInstructions.binary(Opcode.DIVSR, quad, 2, 9),
            IRInstructions.binaryImmediate(Opcode.DIV, quad, 3, 3L),
            IRInstructions.binaryImmediate(Opcode.DIVS, quad, 4, 3L),
            IRInstructions.binary(Opcode.MODR, quad, 5, 9),
            IRInstructions.binary(Opcode.MODSR, quad, 6, 9)
        ))
        vm.runWith {
            registers.setSQ(9, 3L)
            (1..4).forEach { registers.setSQ(it, -10L) }
            (5..6).forEach { registers.setSQ(it, -8L) }
        }
        vm.registers.getUQ(1) shouldBe 0x5555_5555_5555_5552uL
        vm.registers.getSQ(2) shouldBe -3L
        vm.registers.getUQ(3) shouldBe 0x5555_5555_5555_5552uL
        vm.registers.getSQ(4) shouldBe -3L
        vm.registers.getSQ(5) shouldBe 2L
        vm.registers.getSQ(6) shouldBe -2L
    }

    test("AND OR XOR with quad immediates, ANDR ORR XORR and ANDM ORM XORM .q use all 64 bits") {
        val and = { l: Long, r: Long -> l and r }
        val or = { l: Long, r: Long -> l or r }
        val xor = { l: Long, r: Long -> l xor r }
        val ops = listOf(
            BinaryOp(Opcode.AND, Form.IMM, and),
            BinaryOp(Opcode.ANDR, Form.REG, and),
            BinaryOp(Opcode.ANDM, Form.MEM, and),
            BinaryOp(Opcode.OR, Form.IMM, or),
            BinaryOp(Opcode.ORR, Form.REG, or),
            BinaryOp(Opcode.ORM, Form.MEM, or),
            BinaryOp(Opcode.XOR, Form.IMM, xor),
            BinaryOp(Opcode.XORR, Form.REG, xor),
            BinaryOp(Opcode.XORM, Form.MEM, xor)
        )
        val values = edgeValues + listOf(0x5555_5555_AAAA_AAAAuL.toLong(), 0xAAAA_AAAA_0000_0000uL.toLong(), 0x0000_FFFF_FFFF_0000L)
        checkBinaryOps(ops, values, values) shouldBe emptyList()
    }

    val asr = { value: Long, count: Int -> signed(value).shiftRight(count).toLong() }
    val lsr = { value: Long, count: Int -> unsigned(value).shiftRight(count).toLong() }
    val lsl = { value: Long, count: Int -> signed(value).shiftLeft(count).toLong() }
    val shiftValues = edgeValues + listOf(0xC000_0000_0000_0001uL.toLong(), 0x8000_0000_8000_0001uL.toLong(), 0x4000_0000_0000_0000L)

    test("ASRN LSRN LSLN, ASRNM LSRNM LSLNM and ASRI LSRI LSLI .q shift by 0..63, larger counts saturate") {
        asr(Long.MIN_VALUE, 200) shouldBe -1L
        asr(Long.MAX_VALUE, 64) shouldBe 0L
        lsr(-1L, 64) shouldBe 0L
        lsl(1L, 63) shouldBe Long.MIN_VALUE
        lsl(-1L, 64) shouldBe 0L
        fun byCount(shift: (Long, Int) -> Long) = { value: Long, count: Long -> shift(value, count.toInt()) }
        val ops = listOf(
            BinaryOp(Opcode.ASRN, Form.REG, byCount(asr)),
            BinaryOp(Opcode.LSRN, Form.REG, byCount(lsr)),
            BinaryOp(Opcode.LSLN, Form.REG, byCount(lsl)),
            BinaryOp(Opcode.ASRNM, Form.MEM, byCount(asr)),
            BinaryOp(Opcode.LSRNM, Form.MEM, byCount(lsr)),
            BinaryOp(Opcode.LSLNM, Form.MEM, byCount(lsl)),
            BinaryOp(Opcode.ASRI, Form.IMM, byCount(asr)),
            BinaryOp(Opcode.LSRI, Form.IMM, byCount(lsr)),
            BinaryOp(Opcode.LSLI, Form.IMM, byCount(lsl))
        )
        val counts = listOf(0L, 1L, 2L, 31L, 32L, 33L, 63L, 64L, 65L, 200L, 255L)
        checkBinaryOps(ops, shiftValues, counts) shouldBe emptyList()
    }

    test("ASRI LSRI LSLI .q treat any immediate count outside 0..63 like 64") {
        IRInstructions.binaryImmediate(Opcode.ASRI, quad, 1, 0x1_0000_0000L).immediate shouldBe ImmediateOperand.QuadValue(0x1_0000_0000L)
        fun saturating(shift: (Long, Int) -> Long) = { value: Long, count: Long -> shift(value, if (count in 0L..63L) count.toInt() else 64) }
        val ops = listOf(
            BinaryOp(Opcode.ASRI, Form.IMM, saturating(asr)),
            BinaryOp(Opcode.LSRI, Form.IMM, saturating(lsr)),
            BinaryOp(Opcode.LSLI, Form.IMM, saturating(lsl))
        )
        val counts = listOf(5L, 64L, 256L, 257L, 0x1_0000_0000L, 0x1_0000_0001L, 0x1_0000_0020L, -1L, -63L, Long.MIN_VALUE, Long.MAX_VALUE)
        checkBinaryOps(ops, shiftValues, counts) shouldBe emptyList()
    }

    test("ASR LSR LSL and ASRM LSRM LSLM .q shift by one and put the shifted-out bit in the carry") {
        asr(Long.MIN_VALUE, 1) shouldBe 0xC000_0000_0000_0000uL.toLong()
        lsr(Long.MIN_VALUE, 1) shouldBe 0x4000_0000_0000_0000L
        data class ShiftOne(val opcode: Opcode, val inMemory: Boolean, val result: (Long) -> Long, val carry: (Long) -> Boolean)
        val ops = listOf(
            ShiftOne(Opcode.ASR, false, { asr(it, 1) }, { unsigned(it).testBit(0) }),
            ShiftOne(Opcode.LSR, false, { lsr(it, 1) }, { unsigned(it).testBit(0) }),
            ShiftOne(Opcode.LSL, false, { lsl(it, 1) }, { unsigned(it).testBit(63) }),
            ShiftOne(Opcode.ASRM, true, { asr(it, 1) }, { unsigned(it).testBit(0) }),
            ShiftOne(Opcode.LSRM, true, { lsr(it, 1) }, { unsigned(it).testBit(0) }),
            ShiftOne(Opcode.LSLM, true, { lsl(it, 1) }, { unsigned(it).testBit(63) })
        )
        val vm = vmRunning(ops.mapIndexed { g, op ->
            if (op.inMemory) IRInstructions.memoryOp(op.opcode, quad, direct(1, g)) else IRInstructions.unary(op.opcode, quad, reg(1, g))
        })
        val failures = mutableListOf<String>()
        for (value in shiftValues + listOf(3L, 0x8000_0000_0000_0002uL.toLong())) {
            vm.reset(false)
            ops.indices.forEach { g ->
                vm.registers.setSQ(reg(1, g), value)
                vm.memory.setSQ(mem(1, g), value)
            }
            ops.forEachIndexed { g, op ->
                val expectedCarry = op.carry(value)
                vm.statusCarry = !expectedCarry
                vm.step()
                val actual = if (op.inMemory) vm.memory.getSQ(mem(1, g)) else vm.registers.getSQ(reg(1, g))
                failures.verify("${op.opcode}.q ${hex(value)}", hex(actual), hex(op.result(value)))
                failures.verify("${op.opcode}.q ${hex(value)} carry", vm.statusCarry, expectedCarry)
            }
        }
        failures shouldBe emptyList()
    }

    test("BITTST.q sets Z exactly when the tested bit is clear") {
        val bits = listOf(0, 1, 7, 8, 31, 32, 33, 62, 63)
        val vm = vmRunning(bits.map { bit ->
            IRInstruction(Opcode.BITTST, quad,
                srcA = IRInstructions.operandFor(Opcode.BITTST, quad, InstructionSlot.SRC_A, 1),
                immediate = IRInstructions.immediateFor(Opcode.BITTST, quad, bit))
        })
        val values = edgeValues + bits.map { 1L shl it } + bits.map { (1L shl it).inv() }
        val failures = mutableListOf<String>()
        for (value in values) {
            vm.reset(false)
            vm.registers.setSQ(1, value)
            for (bit in bits) {
                val bitClear = !unsigned(value).testBit(bit)
                vm.statusZero = !bitClear
                vm.step()
                failures.verify("BITTST.q ${hex(value)}, $bit", vm.statusZero, bitClear)
            }
        }
        failures shouldBe emptyList()
    }

    test("BITSET BITCLR BITTOG .q change exactly the given bit 0..63") {
        val ops = listOf(
            BinaryOp(Opcode.BITSET, Form.IMM) { value, bit -> signed(value).setBit(bit.toInt()).toLong() },
            BinaryOp(Opcode.BITCLR, Form.IMM) { value, bit -> signed(value).clearBit(bit.toInt()).toLong() },
            BinaryOp(Opcode.BITTOG, Form.IMM) { value, bit -> signed(value).flipBit(bit.toInt()).toLong() }
        )
        val values = listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x0123_4567_89AB_CDEFL, 0x5555_5555_5555_5555L)
        checkBinaryOps(ops, values, (0L..63L).toList()) shouldBe emptyList()
    }

    fun VirtualMachine.flags() = Flags(statusZero, statusNegative, statusCarry, statusOverflow)
    fun VirtualMachine.setFlags(flags: Flags) {
        statusZero = flags.zero
        statusNegative = flags.negative
        statusCarry = flags.carry
        statusOverflow = flags.overflow
    }
    fun compareFlags(left: Long, right: Long): Flags {
        val exact = signed(left) - signed(right)
        return Flags(left == right, exact.toLong() < 0, unsigned(left) >= unsigned(right), exact.bitLength() > 63)
    }

    test("CMP.q and CMPI.q set Z N C V from the 64-bit subtraction") {
        compareFlags(Long.MIN_VALUE, 1L) shouldBe Flags(zero = false, negative = false, carry = true, overflow = true)
        compareFlags(Long.MAX_VALUE, -1L) shouldBe Flags(zero = false, negative = true, carry = false, overflow = true)
        compareFlags(1L, 0x1_0000_0000L) shouldBe Flags(zero = false, negative = true, carry = false, overflow = false)
        compareFlags(5_000_000_000L, 705_032_704L) shouldBe Flags(zero = false, negative = false, carry = true, overflow = false)
        compareFlags(-1L, -1L) shouldBe Flags(zero = true, negative = false, carry = true, overflow = false)

        val values = edgeValues + listOf(-2L, Long.MAX_VALUE - 1, 5_000_000_000L, 705_032_704L, 0x2_0000_0000L)
        val vm = vmRunning(values.indices.flatMap { j ->
            listOf(IRInstructions.compare(quad, 1, reg(1, j)), IRInstructions.compareImmediate(quad, 1, values[j]))
        })
        val failures = mutableListOf<String>()
        for (left in values) {
            vm.reset(false)
            vm.registers.setSQ(1, left)
            values.forEachIndexed { j, right -> vm.registers.setSQ(reg(1, j), right) }
            for (right in values) {
                val expected = compareFlags(left, right)
                for (name in listOf("CMP", "CMPI")) {
                    vm.setFlags(Flags(!expected.zero, !expected.negative, !expected.carry, !expected.overflow))
                    vm.step()
                    failures.verify("$name.q ${hex(left)}, ${hex(right)}", vm.flags(), expected)
                }
            }
        }
        failures shouldBe emptyList()
    }

    test("quad compare-and-branch instructions compare all 64 bits, unsigned or signed") {
        data class Branch(val opcode: Opcode, val immediate: Boolean, val taken: (Int) -> Boolean, val isSigned: Boolean)
        val branches = listOf(
            Branch(Opcode.BGTR, false, { it > 0 }, false),
            Branch(Opcode.BGER, false, { it >= 0 }, false),
            Branch(Opcode.BGTSR, false, { it > 0 }, true),
            Branch(Opcode.BGESR, false, { it >= 0 }, true),
            Branch(Opcode.BGT, true, { it > 0 }, false),
            Branch(Opcode.BLT, true, { it < 0 }, false),
            Branch(Opcode.BGE, true, { it >= 0 }, false),
            Branch(Opcode.BLE, true, { it <= 0 }, false),
            Branch(Opcode.BGTS, true, { it > 0 }, true),
            Branch(Opcode.BLTS, true, { it < 0 }, true),
            Branch(Opcode.BGES, true, { it >= 0 }, true),
            Branch(Opcode.BLES, true, { it <= 0 }, true)
        )
        val pairs = listOf(
            0x1_0000_0000L to 1L, 1L to 0x1_0000_0000L,
            -1L to 1L, 1L to -1L,
            Long.MIN_VALUE to Long.MAX_VALUE, Long.MAX_VALUE to Long.MIN_VALUE,
            5_000_000_000L to 705_032_704L, 705_032_704L to 5_000_000_000L,
            0xFFFF_FFFFL to 0x1_0000_0000L, 0x1_0000_0000L to 0x2_0000_0000L, -0x1_0000_0000L to 0xFFFF_FFFFL,
            0L to 0L, -1L to -1L, 0x1_0000_0000L to 0x1_0000_0000L, Long.MIN_VALUE to Long.MIN_VALUE, 5_000_000_000L to 5_000_000_000L
        )
        data class Case(val branch: Branch, val left: Long, val right: Long)
        val cases = pairs.flatMap { (left, right) -> branches.map { Case(it, left, right) } }
        fun caseLabel(k: Int) = if (k == 0) testLabel else "$testLabel.case$k"
        val chunks = cases.flatMapIndexed { k, case ->
            val taken = "$testLabel.taken$k"
            val branch = if (case.branch.immediate)
                IRInstructions.branchImmediate(case.branch.opcode, quad, reg(1, k), case.right, codeLabel(taken))
            else
                IRInstructions.branchRegister(case.branch.opcode, quad, reg(1, k), reg(2, k), codeLabel(taken))
            listOf(
                caseLabel(k) to listOf(branch),
                "$testLabel.fallthrough$k" to listOf(IRInstructions.load(IRDataType.BYTE, reg(3, k), 1), IRInstructions.jump(codeLabel(caseLabel(k + 1)))),
                taken to listOf(IRInstructions.load(IRDataType.BYTE, reg(3, k), 2))
            )
        } + listOf(caseLabel(cases.size) to listOf(IRInstructions.returnVoid()))
        val vm = vmOf(chunks)
        vm.runWith {
            cases.forEachIndexed { k, case ->
                registers.setSQ(reg(1, k), case.left)
                registers.setSQ(reg(2, k), case.right)
            }
        }
        val failures = mutableListOf<String>()
        cases.forEachIndexed { k, case ->
            val comparison = if (case.branch.isSigned) signed(case.left).compareTo(signed(case.right))
                else unsigned(case.left).compareTo(unsigned(case.right))
            val expected = if (case.branch.taken(comparison)) "taken" else "not taken"
            val actual = when (vm.registers.getUB(reg(3, k)).toInt()) { 2 -> "taken"; 1 -> "not taken"; else -> "not reached" }
            failures.verify("${case.branch.opcode}.q ${hex(case.left)}, ${hex(case.right)}", actual, expected)
        }
        failures shouldBe emptyList()
    }

    test("EXTQ zero-extends and EXTQS sign-extends the low byte, word, long or pointer of the source") {
        data class Extension(val opcode: Opcode, val type: IRDataType, val reference: (Long) -> Long)
        val extensions = listOf(
            Extension(Opcode.EXTQ, IRDataType.BYTE) { it and 0xFFL },
            Extension(Opcode.EXTQ, IRDataType.WORD) { it and 0xFFFFL },
            Extension(Opcode.EXTQ, IRDataType.LONG) { it and 0xFFFF_FFFFL },
            Extension(Opcode.EXTQ, IRDataType.POINTER) { it and 0xFFFF_FFFFL },
            Extension(Opcode.EXTQS, IRDataType.BYTE) { it.toByte().toLong() },
            Extension(Opcode.EXTQS, IRDataType.WORD) { it.toShort().toLong() },
            Extension(Opcode.EXTQS, IRDataType.LONG) { it.toInt().toLong() }
        )
        val vm = vmRunning(extensions.mapIndexed { g, ext -> IRInstructions.binary(ext.opcode, ext.type, reg(2, g), reg(1, g)) })
        val sources = listOf(0L, 0x7FL, 0x80L, 0xFFL, 0x7FFFL, 0x8000L, 0xFFFFL, 0x7FFF_FFFFL, 0x8000_0000L, 0xFFFF_FFFFL, -1L,
            Long.MIN_VALUE, 0x5555_5555_89AB_CDEFL, 0xAAAA_AAAA_7654_3210uL.toLong(), 0x1_0000_0000L)
        val failures = mutableListOf<String>()
        for (source in sources) {
            vm.runWith {
                extensions.indices.forEach { g ->
                    registers.setSQ(reg(1, g), source)
                    registers.setSQ(reg(2, g), 0x5A5A_5A5A_5A5A_5A5AL)
                }
            }
            extensions.forEachIndexed { g, ext ->
                failures.verify("${ext.opcode}.${ext.type} ${hex(source)}", hex(vm.registers.getSQ(reg(2, g))), hex(ext.reference(source)))
            }
        }
        failures shouldBe emptyList()

        val extqsPointer = IRInstructions.binary(Opcode.EXTQS, IRDataType.POINTER, 2, 1)
        shouldThrow<IllegalArgumentException> {
            vmRunning(listOf(extqsPointer)).runWith { registers.setSQ(1, 0x8000_0000L) }
        }
    }

    test("LSIGB LSIGW LSIGL .q take the low byte, word and long of a quad") {
        val vm = vmRunning(listOf(
            IRInstructions.binary(Opcode.LSIGB, quad, 11, 1),
            IRInstructions.binary(Opcode.LSIGW, quad, 12, 1),
            IRInstructions.binary(Opcode.LSIGL, quad, 13, 1)
        ))
        val failures = mutableListOf<String>()
        for (value in edgeValues + listOf(0x8000_0000_0000_00FFuL.toLong(), 0xFFFF_FFFF_0000_8000uL.toLong())) {
            vm.runWith {
                registers.setSQ(1, value)
                (11..13).forEach { registers.setSQ(it, value.inv()) }
            }
            failures.verify("LSIGB.q ${hex(value)}", vm.registers.getUB(11), (value and 0xFFL).toUByte())
            failures.verify("LSIGW.q ${hex(value)}", vm.registers.getUW(12), (value and 0xFFFFL).toUShort())
            failures.verify("LSIGL.q ${hex(value)}", vm.registers.getUL(13), (value and 0xFFFF_FFFFL).toUInt())
        }
        failures shouldBe emptyList()
    }

    test("FFROMSQ and FFROMUQ convert signed and unsigned quads to the nearest float") {
        val vm = vmRunning(listOf(
            IRInstructions.binary(Opcode.FFROMSQ, IRDataType.FLOAT, 11, 1),
            IRInstructions.binary(Opcode.FFROMUQ, IRDataType.FLOAT, 12, 1)
        ))
        fun convert(value: Long): Pair<Double, Double> {
            vm.runWith {
                registers.setSQ(1, value)
                registers.setFloat(11, 12345.0)
                registers.setFloat(12, 12345.0)
            }
            return vm.registers.getFloat(11) to vm.registers.getFloat(12)
        }
        convert(-1L) shouldBe (-1.0 to 1.8446744073709552E19)
        convert(0xFFFF_FFFF_FFFF_FFFFuL.toLong()).second shouldBe 1.8446744073709552E19
        convert(Long.MIN_VALUE) shouldBe (-9.223372036854775808E18 to 9.223372036854775808E18)

        val values = edgeValues + listOf(5_000_000_000L, -5_000_000_000L, (1L shl 53) + 1, (1L shl 53) + 3,
            0x8000_0000_0000_0401uL.toLong(), 0x8000_0000_0000_0C01uL.toLong(), 0xFFFF_FFFF_FFFF_F800uL.toLong(), 0xFFFF_FFFF_FFFF_FBFFuL.toLong())
        val failures = mutableListOf<String>()
        for (value in values) {
            val (fromSigned, fromUnsigned) = convert(value)
            failures.verify("FFROMSQ ${hex(value)}", fromSigned, signed(value).toDouble())
            failures.verify("FFROMUQ ${hex(value)}", fromUnsigned, unsigned(value).toDouble())
        }
        failures shouldBe emptyList()
    }

    val twoTo63 = 9.223372036854775808E18
    val twoTo64 = 1.8446744073709551616E19
    fun truncated(value: Double): BigInteger = BigDecimal(value).toBigInteger()
    fun ftosqReference(value: Double): Long = when {
        value.isNaN() -> 0L
        value == Double.POSITIVE_INFINITY -> Long.MAX_VALUE
        value == Double.NEGATIVE_INFINITY -> Long.MIN_VALUE
        else -> truncated(value).max(signed(Long.MIN_VALUE)).min(signed(Long.MAX_VALUE)).toLong()
    }
    fun ftouqReference(value: Double): ULong = when {
        value.isNaN() -> 0uL
        value < 0.0 -> ftosqReference(value).toULong()
        value == Double.POSITIVE_INFINITY -> ULong.MAX_VALUE
        else -> truncated(value).min(mask64).toLong().toULong()
    }
    val floatValues = listOf(0.0, -0.0, 0.5, -0.5, 0.99, -0.99, 1.0, -1.0, 1.5, -1.5, 2.5, -2.5, 255.9, 5.0e9, -5.0e9,
        4503599627370497.0, -4503599627370497.0, 9.2e18, -9.2e18, twoTo63 - 1024.0, twoTo63, -twoTo63, -twoTo63 - 2048.0,
        1.5 * twoTo63, twoTo64 - 2048.0, twoTo64, 1.0e19, -1.0e19, 1.0e20, -1.0e20, 1.0e300, -1.0e300,
        Double.MIN_VALUE, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN)

    fun floatToQuadVm(opcode: Opcode) = vmRunning(listOf(IRInstructions.binary(opcode, IRDataType.FLOAT, 11, 1)))

    test("FTOSQ truncates like Double.toLong(): saturating at Long.MIN/MAX, NaN gives 0") {
        ftosqReference(1.0e19) shouldBe Long.MAX_VALUE
        ftosqReference(twoTo63) shouldBe Long.MAX_VALUE
        ftosqReference(-1.0e19) shouldBe Long.MIN_VALUE
        ftosqReference(-2.5) shouldBe -2L
        val vm = floatToQuadVm(Opcode.FTOSQ)
        val failures = mutableListOf<String>()
        for (value in floatValues) {
            val expected = ftosqReference(value)
            vm.runWith {
                registers.setFloat(1, value)
                registers.setSQ(11, expected.inv())
            }
            failures.verify("FTOSQ $value", vm.registers.getSQ(11), expected)
            failures.verify("FTOSQ $value vs Double.toLong()", expected, value.toLong())
        }
        failures shouldBe emptyList()
    }

    test("FTOUQ converts the whole uquad range exactly, wraps negative values like a signed conversion, saturates above") {
        ftouqReference(-1.0) shouldBe 0xFFFF_FFFF_FFFF_FFFFuL
        ftouqReference(-2.5) shouldBe (-2L).toULong()
        ftouqReference(twoTo63) shouldBe 0x8000_0000_0000_0000uL
        ftouqReference(1.5 * twoTo63) shouldBe 0xC000_0000_0000_0000uL
        ftouqReference(twoTo64 - 2048.0) shouldBe 0xFFFF_FFFF_FFFF_F800uL
        ftouqReference(1.0e20) shouldBe ULong.MAX_VALUE
        val vm = floatToQuadVm(Opcode.FTOUQ)
        val failures = mutableListOf<String>()
        for (value in floatValues) {
            val expected = ftouqReference(value)
            vm.runWith {
                registers.setFloat(1, value)
                registers.setUQ(11, expected.inv())
            }
            failures.verify("FTOUQ $value", vm.registers.getUQ(11), expected)
        }
        failures shouldBe emptyList()
    }
})
