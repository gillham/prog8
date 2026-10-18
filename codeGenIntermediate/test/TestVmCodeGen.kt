import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import prog8.code.SymbolTableMaker
import prog8.code.ast.*
import prog8.code.core.*
import prog8.code.source.SourceCode
import prog8.code.target.Cx16Target
import prog8.code.target.VMTarget
import prog8.codegen.vm.VmAssemblyProgram
import prog8.codegen.vm.VmCodeGen
import io.kotest.matchers.nulls.shouldNotBeNull
import prog8.intermediate.IRAsmSubroutine
import prog8.intermediate.IRDataType
import prog8.intermediate.IRStStaticVariable
import prog8.intermediate.IRSubroutine
import prog8.intermediate.IRVariableInitializer
import prog8.intermediate.Opcode
import prog8.intermediate.codeAddress
import prog8.intermediate.codeLabel
import prog8.intermediate.requireCallSite
import prog8.intermediate.requireImmediateQuad
import prog8.intermediate.symbolName
import prog8.intermediate.toAddress

import prog8tests.helpers.ErrorReporterForTests

class TestVmCodeGen: FunSpec({

    fun getTestOptions(): CompilationOptions {
        val target = VMTarget()
        return CompilationOptions.builder(target)
            .output(OutputType.RAW)
            .zeropage(ZeropageType.DONTUSE)
            .floats(true)
            .compilerVersion("99.99")
            .build()
    }

    test("augmented assigns") {
//main {
//    sub start() {
//        ubyte[] particleX = [1,2,3]
//        ubyte[] particleDX = [1,2,3]
//        particleX[2] += particleDX[2]
//
//        word @shared xx = 1
//        xx = -xx
//        xx += 42
//        xx += cx16.r0
//    }
//}
        val compTarget = VMTarget()
        val codegen = VmCodeGen(false)
        val program = PtProgram("test", VMTarget())
        val block = PtBlock("main", false, SourceCode.Generated("test"), PtBlock.Options(), Position.DUMMY)
        val sub = PtSub("start", emptyList(), emptyList(), Position.DUMMY)
        sub.add(PtVariable(
            "pi",
            DataType.UBYTE,
            false,
            ZeropageWish.DONTCARE,
            0u,
            false,
            null,
            null,
            Position.DUMMY
        ))
        sub.add(PtVariable(
            "particleX",
            DataType.arrayFor(BaseDataType.UBYTE, compTarget),
            false,
            ZeropageWish.DONTCARE,
            0u,
            false,
            null,
            3u,
            Position.DUMMY
        ))
        sub.add(PtVariable(
            "particleDX",
            DataType.arrayFor(BaseDataType.UBYTE, compTarget),
            false,
            ZeropageWish.DONTCARE,
            0u,
            false,
            null,
            3u,
            Position.DUMMY
        ))
        sub.add(PtVariable(
            "xx",
            DataType.WORD,
            false,
            ZeropageWish.DONTCARE,
            0u,
            false,
            null,
            null,
            Position.DUMMY
        ))

        val assign = PtAugmentedAssign("+=", Position.DUMMY)
        val target = PtAssignTarget(false, Position.DUMMY).also {
            val targetIdx = PtArrayIndexer(DataType.UBYTE, false, Position.DUMMY).also { idx ->
                idx.add(PtIdentifier("main.start.particleX",
                    DataType.arrayFor(BaseDataType.UBYTE, compTarget),
                    Position.DUMMY))
                idx.add(PtNumber(BaseDataType.UBYTE, 2.0, Position.DUMMY))
            }
            it.add(targetIdx)
        }
        val value = PtArrayIndexer(DataType.UBYTE, false, Position.DUMMY)
        value.add(PtIdentifier("main.start.particleDX",
            DataType.arrayFor(BaseDataType.UBYTE, compTarget),
            Position.DUMMY))
        value.add(PtNumber(BaseDataType.UBYTE, 2.0, Position.DUMMY))
        assign.add(target)
        assign.add(value)
        sub.add(assign)

        val prefixAssign = PtAugmentedAssign("-", Position.DUMMY)
        val prefixTarget = PtAssignTarget(false, Position.DUMMY).also {
            it.add(PtIdentifier("main.start.xx", DataType.WORD, Position.DUMMY))
        }
        prefixAssign.add(prefixTarget)
        prefixAssign.add(PtIdentifier("main.start.xx", DataType.WORD, Position.DUMMY))
        sub.add(prefixAssign)

        val numberAssign = PtAugmentedAssign("+=", Position.DUMMY)
        val numberAssignTarget = PtAssignTarget(false, Position.DUMMY).also {
            it.add(PtIdentifier("main.start.xx", DataType.WORD, Position.DUMMY))
        }
        numberAssign.add(numberAssignTarget)
        numberAssign.add(PtNumber(BaseDataType.WORD, 42.0, Position.DUMMY))
        sub.add(numberAssign)

        val cxregAssign = PtAugmentedAssign("+=", Position.DUMMY)
        val cxregAssignTarget = PtAssignTarget(false, Position.DUMMY).also {
            it.add(PtIdentifier("main.start.xx", DataType.WORD, Position.DUMMY))
        }
        cxregAssign.add(cxregAssignTarget)
        cxregAssign.add(PtIdentifier("cx16.r0", DataType.UWORD, Position.DUMMY))
        sub.add(cxregAssign)

        block.add(sub)
        program.add(block)

        // define the "cx16.r0" virtual register
        val cx16block = PtBlock("cx16", false, SourceCode.Generated("test"), PtBlock.Options(), Position.DUMMY)
        cx16block.add(PtMemMapped("r0", DataType.UWORD, 100u, null, Position.DUMMY))
        program.add(cx16block)

        val options = getTestOptions()
        val st = SymbolTableMaker(program, options).make()
        val errors = ErrorReporterForTests()
        val result = codegen.generate(program, st, options, errors) as VmAssemblyProgram
        val irChunks = (result.irProgram.blocks.first().children.single() as IRSubroutine).chunks
        irChunks.size shouldBe 1
    }

    test("augmented assigns with PtMemoryByte target - all register opcodes") {
// Tests the fix for using ADDR/SUBR/ANDR/ORR/XORR (register-to-register)
// instead of ADD/SUB/AND/OR/XOR (register+immediate) when the target is a
// PtMemoryByte with a non-constant address and the operand is a variable.
//
// Pattern: @(ptr+offset) = @(ptr+offset) + val   i.e.  ptr[offset] += val
// Non-zero offsets are used to avoid potential optimization of the address expression.
        val codegen = VmCodeGen(false)
        val program = PtProgram("test", VMTarget())
        val block = PtBlock("main", false, SourceCode.Generated("test"), PtBlock.Options(), Position.DUMMY)
        val sub = PtSub("start", emptyList(), emptyList(), Position.DUMMY)
        sub.add(PtVariable("ptr", DataType.pointer(BaseDataType.UBYTE), false, ZeropageWish.DONTCARE, 0u, false, null, null, Position.DUMMY))
        sub.add(PtVariable("val", DataType.UBYTE, false, ZeropageWish.DONTCARE, 0u, false, null, null, Position.DUMMY))

        fun makeMemByteAssign(op: String, offset: Int): PtAugmentedAssign {
            // Use non-zero offset to avoid optimization
            val addrExpr = PtBinaryExpression("+", DataType.UWORD, Position.DUMMY)
            addrExpr.add(PtIdentifier("main.start.ptr", DataType.pointer(BaseDataType.UBYTE), Position.DUMMY))
            addrExpr.add(PtNumber(BaseDataType.UWORD, offset.toDouble(), Position.DUMMY))
            val memByte = PtMemoryByte(Position.DUMMY)
            memByte.add(addrExpr)
            val target = PtAssignTarget(false, Position.DUMMY)
            target.add(memByte)
            val assign = PtAugmentedAssign(op, Position.DUMMY)
            assign.add(target)
            assign.add(PtIdentifier("main.start.val", DataType.UBYTE, Position.DUMMY))
            return assign
        }

        sub.add(makeMemByteAssign("+=", 3))
        sub.add(makeMemByteAssign("-=", 5))
        sub.add(makeMemByteAssign("|=", 7))
        sub.add(makeMemByteAssign("&=", 11))
        sub.add(makeMemByteAssign("^=", 13))

        block.add(sub)
        program.add(block)

        val options = getTestOptions()
        val st = SymbolTableMaker(program, options).make()
        val errors = ErrorReporterForTests()
        val result = codegen.generate(program, st, options, errors) as VmAssemblyProgram
        val irChunks = (result.irProgram.blocks.first().children.single() as IRSubroutine).chunks
        irChunks.size shouldBe 1
        val instructions = irChunks[0].instructions

        // Verify correct register-to-register opcodes are used (not immediate variants)
        val addrCount = instructions.count { it.opcode == Opcode.ADDR }
        val subrCount = instructions.count { it.opcode == Opcode.SUBR }
        val orrCount = instructions.count { it.opcode == Opcode.ORR }
        val andrCount = instructions.count { it.opcode == Opcode.ANDR }
        val xorrCount = instructions.count { it.opcode == Opcode.XORR }

        addrCount shouldBe 1
        subrCount shouldBe 1
        orrCount shouldBe 1
        andrCount shouldBe 1
        xorrCount shouldBe 1

        // Verify that the immediate-only variants are NOT used with a register source
        val wrongAdd = instructions.any { it.opcode == Opcode.ADD && it.srcA != null }
        val wrongSub = instructions.any { it.opcode == Opcode.SUB && it.srcA != null }
        val wrongOr = instructions.any { it.opcode == Opcode.OR && it.srcA != null }
        val wrongAnd = instructions.any { it.opcode == Opcode.AND && it.srcA != null }
        val wrongXor = instructions.any { it.opcode == Opcode.XOR && it.srcA != null }

        (wrongAdd || wrongSub || wrongOr || wrongAnd || wrongXor) shouldBe false
    }

    test("float comparison expressions against zero") {
//main {
//    sub start() {
//        float @shared f1
//
//        if f1==0
//            nop
//        if f1!=0
//            nop
//        if f1>0
//            nop
//        if f1<0
//            nop
//    }
//}
        val codegen = VmCodeGen(false)
        val program = PtProgram("test", VMTarget())
        val block = PtBlock("main", false, SourceCode.Generated("test"), PtBlock.Options(), Position.DUMMY)
        val sub = PtSub("start", emptyList(), emptyList(), Position.DUMMY)
        sub.add(PtVariable(
            "f1",
            DataType.FLOAT,
            false,
            ZeropageWish.DONTCARE,
            0u,
            false,
            null,
            null,
            Position.DUMMY
        ))
        val if1 = PtIfElse(Position.DUMMY)
        val cmp1 = PtBinaryExpression("==", DataType.BOOL, Position.DUMMY)
        cmp1.add(PtIdentifier("main.start.f1", DataType.FLOAT, Position.DUMMY))
        cmp1.add(PtNumber(BaseDataType.FLOAT, 0.0, Position.DUMMY))
        if1.add(cmp1)
        if1.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        if1.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        sub.add(if1)
        val if2 = PtIfElse(Position.DUMMY)
        val cmp2 = PtBinaryExpression("!=", DataType.BOOL, Position.DUMMY)
        cmp2.add(PtIdentifier("main.start.f1", DataType.FLOAT, Position.DUMMY))
        cmp2.add(PtNumber(BaseDataType.FLOAT, 0.0, Position.DUMMY))
        if2.add(cmp2)
        if2.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        if2.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        sub.add(if2)
        val if3 = PtIfElse(Position.DUMMY)
        val cmp3 = PtBinaryExpression("<", DataType.BOOL, Position.DUMMY)
        cmp3.add(PtIdentifier("main.start.f1", DataType.FLOAT, Position.DUMMY))
        cmp3.add(PtNumber(BaseDataType.FLOAT, 0.0, Position.DUMMY))
        if3.add(cmp3)
        if3.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        if3.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        sub.add(if3)
        val if4 = PtIfElse(Position.DUMMY)
        val cmp4 = PtBinaryExpression(">", DataType.BOOL, Position.DUMMY)
        cmp4.add(PtIdentifier("main.start.f1", DataType.FLOAT, Position.DUMMY))
        cmp4.add(PtNumber(BaseDataType.FLOAT, 0.0, Position.DUMMY))
        if4.add(cmp4)
        if4.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        if4.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        sub.add(if4)
        block.add(sub)
        program.add(block)

        val options = getTestOptions()
        val st = SymbolTableMaker(program, options).make()
        val errors = ErrorReporterForTests()
        val result = codegen.generate(program, st, options, errors) as VmAssemblyProgram
        val irChunks = (result.irProgram.blocks.first().children.single() as IRSubroutine).chunks
        irChunks.size shouldBeGreaterThan 4
    }

    test("float comparison expressions against nonzero") {
//main {
//    sub start() {
//        float @shared f1
//
//        if f1==42
//            nop
//        if f1!=42
//            nop
//        if f1>42
//            nop
//        if f1<42
//            nop
//    }
//}
        val codegen = VmCodeGen(false)
        val program = PtProgram("test", VMTarget())
        val block = PtBlock("main", false, SourceCode.Generated("test"), PtBlock.Options(), Position.DUMMY)
        val sub = PtSub("start", emptyList(), emptyList(), Position.DUMMY)
        sub.add(PtVariable(
            "f1",
            DataType.FLOAT,
            false,
            ZeropageWish.DONTCARE,
            0u,
            false,
            null,
            null,
            Position.DUMMY
        ))
        val if1 = PtIfElse(Position.DUMMY)
        val cmp1 = PtBinaryExpression("==", DataType.BOOL, Position.DUMMY)
        cmp1.add(PtIdentifier("main.start.f1", DataType.FLOAT, Position.DUMMY))
        cmp1.add(PtNumber(BaseDataType.FLOAT, 42.0, Position.DUMMY))
        if1.add(cmp1)
        if1.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        if1.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        sub.add(if1)
        val if2 = PtIfElse(Position.DUMMY)
        val cmp2 = PtBinaryExpression("!=", DataType.BOOL, Position.DUMMY)
        cmp2.add(PtIdentifier("main.start.f1", DataType.FLOAT, Position.DUMMY))
        cmp2.add(PtNumber(BaseDataType.FLOAT, 42.0, Position.DUMMY))
        if2.add(cmp2)
        if2.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        if2.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        sub.add(if2)
        val if3 = PtIfElse(Position.DUMMY)
        val cmp3 = PtBinaryExpression("<", DataType.BOOL, Position.DUMMY)
        cmp3.add(PtIdentifier("main.start.f1", DataType.FLOAT, Position.DUMMY))
        cmp3.add(PtNumber(BaseDataType.FLOAT, 42.0, Position.DUMMY))
        if3.add(cmp3)
        if3.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        if3.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        sub.add(if3)
        val if4 = PtIfElse(Position.DUMMY)
        val cmp4 = PtBinaryExpression(">", DataType.BOOL, Position.DUMMY)
        cmp4.add(PtIdentifier("main.start.f1", DataType.FLOAT, Position.DUMMY))
        cmp4.add(PtNumber(BaseDataType.FLOAT, 42.0, Position.DUMMY))
        if4.add(cmp4)
        if4.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        if4.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        sub.add(if4)
        block.add(sub)
        program.add(block)

        val options = getTestOptions()
        val st = SymbolTableMaker(program, options).make()
        val errors = ErrorReporterForTests()
        val result = codegen.generate(program, st, options, errors) as VmAssemblyProgram
        val irChunks = (result.irProgram.blocks.first().children.single() as IRSubroutine).chunks
        irChunks.size shouldBeGreaterThan 4
    }

    test("float conditional jump") {
//main {
//    sub start() {
//        float @shared f1
//
//        if f1==42
//            goto $c000
//        if f1>42
//            goto $c000
//    }
//}
        val codegen = VmCodeGen(false)
        val program = PtProgram("test", VMTarget())
        val block = PtBlock("main", false, SourceCode.Generated("test"), PtBlock.Options(), Position.DUMMY)
        val sub = PtSub("start", emptyList(), emptyList(), Position.DUMMY)
        sub.add(PtVariable(
            "f1",
            DataType.FLOAT,
            false,
            ZeropageWish.DONTCARE,
            0u,
            false,
            null,
            null,
            Position.DUMMY
        ))
        val if1 = PtIfElse(Position.DUMMY)
        val cmp1 = PtBinaryExpression("==", DataType.BOOL, Position.DUMMY)
        cmp1.add(PtIdentifier("main.start.f1", DataType.FLOAT, Position.DUMMY))
        cmp1.add(PtNumber(BaseDataType.FLOAT, 42.0, Position.DUMMY))
        if1.add(cmp1)
        if1.add(PtNodeGroup().also { it.add(PtJump(Position.DUMMY).also { it.add(PtNumber(BaseDataType.UWORD, 0xc000.toDouble(), Position.DUMMY)) }) })
        if1.add(PtNodeGroup())
        sub.add(if1)
        val if2 = PtIfElse(Position.DUMMY)
        val cmp2 = PtBinaryExpression(">", DataType.BOOL, Position.DUMMY)
        cmp2.add(PtIdentifier("main.start.f1", DataType.FLOAT, Position.DUMMY))
        cmp2.add(PtNumber(BaseDataType.FLOAT, 42.0, Position.DUMMY))
        if2.add(cmp2)
        if2.add(PtNodeGroup().also { it.add(PtJump(Position.DUMMY).also { it.add(PtNumber(BaseDataType.UWORD, 0xc000.toDouble(), Position.DUMMY)) }) })
        if2.add(PtNodeGroup())
        sub.add(if2)
        block.add(sub)
        program.add(block)

        val options = getTestOptions()
        val st = SymbolTableMaker(program, options).make()
        val errors = ErrorReporterForTests()
        val result = codegen.generate(program, st, options, errors) as VmAssemblyProgram
        val irChunks = (result.irProgram.blocks.first().children.single() as IRSubroutine).chunks
        irChunks.size shouldBe 1
    }

    test("integer comparison expressions against zero") {
//main {
//    sub start() {
//        byte @shared sb1
//
//        if sb1==0
//            nop
//        if sb1!=0
//            nop
//        if sb1>0
//            nop
//        if sb1<0
//            nop
//    }
//}
        val codegen = VmCodeGen(false)
        val program = PtProgram("test", VMTarget())
        val block = PtBlock("main", false, SourceCode.Generated("test"), PtBlock.Options(), Position.DUMMY)
        val sub = PtSub("start", emptyList(), emptyList(), Position.DUMMY)
        sub.add(PtVariable(
            "sb1",
            DataType.BYTE,
            false,
            ZeropageWish.DONTCARE,
            0u,
            false,
            null,
            null,
            Position.DUMMY
        ))
        val if1 = PtIfElse(Position.DUMMY)
        val cmp1 = PtBinaryExpression("==", DataType.BOOL, Position.DUMMY)
        cmp1.add(PtIdentifier("main.start.sb1", DataType.BYTE, Position.DUMMY))
        cmp1.add(PtNumber(BaseDataType.BYTE, 0.0, Position.DUMMY))
        if1.add(cmp1)
        if1.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        if1.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        sub.add(if1)
        val if2 = PtIfElse(Position.DUMMY)
        val cmp2 = PtBinaryExpression("!=", DataType.BOOL, Position.DUMMY)
        cmp2.add(PtIdentifier("main.start.sb1", DataType.BYTE, Position.DUMMY))
        cmp2.add(PtNumber(BaseDataType.BYTE, 0.0, Position.DUMMY))
        if2.add(cmp2)
        if2.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        if2.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        sub.add(if2)
        val if3 = PtIfElse(Position.DUMMY)
        val cmp3 = PtBinaryExpression("<", DataType.BOOL, Position.DUMMY)
        cmp3.add(PtIdentifier("main.start.sb1", DataType.BYTE, Position.DUMMY))
        cmp3.add(PtNumber(BaseDataType.BYTE, 0.0, Position.DUMMY))
        if3.add(cmp3)
        if3.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        if3.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        sub.add(if3)
        val if4 = PtIfElse(Position.DUMMY)
        val cmp4 = PtBinaryExpression(">", DataType.BOOL, Position.DUMMY)
        cmp4.add(PtIdentifier("main.start.sb1", DataType.BYTE, Position.DUMMY))
        cmp4.add(PtNumber(BaseDataType.BYTE, 0.0, Position.DUMMY))
        if4.add(cmp4)
        if4.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        if4.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        sub.add(if4)
        block.add(sub)
        program.add(block)

        val options = getTestOptions()
        val st = SymbolTableMaker(program, options).make()
        val errors = ErrorReporterForTests()
        val result = codegen.generate(program, st, options, errors) as VmAssemblyProgram
        val irChunks = (result.irProgram.blocks.first().children.single() as IRSubroutine).chunks
        irChunks.size shouldBeGreaterThan 4
    }

    test("integer comparison expressions against nonzero") {
//main {
//    sub start() {
//        byte @shared sb1
//
//        if sb1==42
//            nop
//        if sb1!=42
//            nop
//        if sb1>42
//            nop
//        if sb1<42
//            nop
//    }
//}
        val codegen = VmCodeGen(false)
        val program = PtProgram("test", VMTarget())
        val block = PtBlock("main", false, SourceCode.Generated("test"), PtBlock.Options(), Position.DUMMY)
        val sub = PtSub("start", emptyList(), emptyList(), Position.DUMMY)
        sub.add(PtVariable(
            "sb1",
            DataType.BYTE,
            false,
            ZeropageWish.DONTCARE,
            0u,
            false,
            null,
            null,
            Position.DUMMY
        ))
        val if1 = PtIfElse(Position.DUMMY)
        val cmp1 = PtBinaryExpression("==", DataType.BOOL, Position.DUMMY)
        cmp1.add(PtIdentifier("main.start.sb1", DataType.BYTE, Position.DUMMY))
        cmp1.add(PtNumber(BaseDataType.BYTE, 42.0, Position.DUMMY))
        if1.add(cmp1)
        if1.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        if1.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        sub.add(if1)
        val if2 = PtIfElse(Position.DUMMY)
        val cmp2 = PtBinaryExpression("!=", DataType.BOOL, Position.DUMMY)
        cmp2.add(PtIdentifier("main.start.sb1", DataType.BYTE, Position.DUMMY))
        cmp2.add(PtNumber(BaseDataType.BYTE, 42.0, Position.DUMMY))
        if2.add(cmp2)
        if2.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        if2.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        sub.add(if2)
        val if3 = PtIfElse(Position.DUMMY)
        val cmp3 = PtBinaryExpression("<", DataType.BOOL, Position.DUMMY)
        cmp3.add(PtIdentifier("main.start.sb1", DataType.BYTE, Position.DUMMY))
        cmp3.add(PtNumber(BaseDataType.BYTE, 42.0, Position.DUMMY))
        if3.add(cmp3)
        if3.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        if3.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        sub.add(if3)
        val if4 = PtIfElse(Position.DUMMY)
        val cmp4 = PtBinaryExpression(">", DataType.BOOL, Position.DUMMY)
        cmp4.add(PtIdentifier("main.start.sb1", DataType.BYTE, Position.DUMMY))
        cmp4.add(PtNumber(BaseDataType.BYTE, 42.0, Position.DUMMY))
        if4.add(cmp4)
        if4.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        if4.add(PtNodeGroup().also { it.add(PtNop(Position.DUMMY)) })
        sub.add(if4)
        block.add(sub)
        program.add(block)

        val options = getTestOptions()
        val st = SymbolTableMaker(program, options).make()
        val errors = ErrorReporterForTests()
        val result = codegen.generate(program, st, options, errors) as VmAssemblyProgram
        val irChunks = (result.irProgram.blocks.first().children.single() as IRSubroutine).chunks
        irChunks.size shouldBeGreaterThan 4
    }

    test("integer conditional jump") {
//main {
//    sub start() {
//        ubyte @shared ub1
//
//        if ub1==42
//            goto $c000
//        if ub1>42
//            goto $c000
//    }
//}
        val codegen = VmCodeGen(false)
        val program = PtProgram("test", VMTarget())
        val block = PtBlock("main", false, SourceCode.Generated("test"), PtBlock.Options(), Position.DUMMY)
        val sub = PtSub("start", emptyList(), emptyList(), Position.DUMMY)
        sub.add(PtVariable(
            "ub1",
            DataType.BYTE,
            false,
            ZeropageWish.DONTCARE,
            0u,
            false,
            null,
            null,
            Position.DUMMY
        ))
        val if1 = PtIfElse(Position.DUMMY)
        val cmp1 = PtBinaryExpression("==", DataType.BOOL, Position.DUMMY)
        cmp1.add(PtIdentifier("main.start.ub1", DataType.UBYTE, Position.DUMMY))
        cmp1.add(PtNumber(BaseDataType.UBYTE, 42.0, Position.DUMMY))
        if1.add(cmp1)
        if1.add(PtNodeGroup().also { it.add(PtJump(Position.DUMMY).also { it.add(PtNumber(BaseDataType.UWORD, 0xc000.toDouble(), Position.DUMMY)) }) })
        if1.add(PtNodeGroup())
        sub.add(if1)
        val if2 = PtIfElse(Position.DUMMY)
        val cmp2 = PtBinaryExpression(">", DataType.BOOL, Position.DUMMY)
        cmp2.add(PtIdentifier("main.start.ub1", DataType.UBYTE, Position.DUMMY))
        cmp2.add(PtNumber(BaseDataType.UBYTE, 42.0, Position.DUMMY))
        if2.add(cmp2)
        if2.add(PtNodeGroup().also { it.add(PtJump(Position.DUMMY).also {it.add(PtNumber(BaseDataType.UWORD, 0xc000.toDouble(), Position.DUMMY)) }) })
        if2.add(PtNodeGroup())
        sub.add(if2)
        block.add(sub)
        program.add(block)

        val options = getTestOptions()
        val st = SymbolTableMaker(program, options).make()
        val errors = ErrorReporterForTests()
        val result = codegen.generate(program, st, options, errors) as VmAssemblyProgram
        val irChunks = (result.irProgram.blocks.first().children.single() as IRSubroutine).chunks
        irChunks.size shouldBe 1
    }

    test("extsub allowed in ir-codegen") {
//main {
//    extsub $5000 = routine()
//
//    sub start()  {
//        routine()
//    }
//}
        val codegen = VmCodeGen(false)
        val program = PtProgram("test", VMTarget())
        val block = PtBlock("main", false, SourceCode.Generated("test"), PtBlock.Options(), Position.DUMMY)
        val extsub = PtAsmSub("routine", PtAsmSub.Address(null, null, 0x5000u), setOf(CpuRegister.Y), emptyList(), emptyList(), false, Position.DUMMY)
        block.add(extsub)
        val sub = PtSub("start", emptyList(), emptyList(), Position.DUMMY)
        val call = PtFunctionCall("main.routine", false, false, emptyArray(), Position.DUMMY)
        sub.add(call)
        block.add(sub)
        program.add(block)

        val options = getTestOptions()
        val st = SymbolTableMaker(program, options).make()
        val errors = ErrorReporterForTests()
        val result = codegen.generate(program, st, options, errors) as VmAssemblyProgram
        val irSub = result.irProgram.blocks.first().children.filterIsInstance<IRSubroutine>().single()
        val irChunks = irSub.chunks
        irChunks.size shouldBe 1
        irChunks[0].instructions.size shouldBe 1
        val callInstr = irChunks[0].instructions[0]
        callInstr.opcode shouldBe Opcode.CALL
        callInstr.requireCallSite().codeReference shouldBe codeAddress(0x5000u.toAddress())
    }

    test("an external routine without address becomes an external IRAsmSubroutine that calls link to") {
//main {
//    extsub putchar(ubyte char) -> ubyte
//
//    sub start()  {
//        void putchar(65)
//    }
//}
        val codegen = VmCodeGen(false)
        val program = PtProgram("test", VMTarget())
        val block = PtBlock("main", false, SourceCode.Generated("test"), PtBlock.Options(), Position.DUMMY)
        val none = RegisterOrStatusflag(null, null)
        val param = PtSubroutineParameter("char", DataType.UBYTE, null, Position.DUMMY)
        val extsub = PtAsmSub("putchar", null, emptySet(), listOf(none to param), listOf(none to DataType.UBYTE), false, Position.DUMMY, isExternal = true)
        block.add(extsub)
        val sub = PtSub("start", emptyList(), emptyList(), Position.DUMMY)
        val call = PtFunctionCall("main.putchar", false, false, emptyArray(), Position.DUMMY)
        call.add(PtNumber(BaseDataType.UBYTE, 65.0, Position.DUMMY))
        sub.add(call)
        block.add(sub)
        program.add(block)

        val options = getTestOptions()
        val st = SymbolTableMaker(program, options).make()
        val result = codegen.generate(program, st, options, ErrorReporterForTests()) as VmAssemblyProgram
        val irAsmSub = result.irProgram.blocks.first().children.filterIsInstance<IRAsmSubroutine>().single()
        irAsmSub.label shouldBe "main.putchar"
        irAsmSub.isExternal shouldBe true
        irAsmSub.address shouldBe null
        irAsmSub.isEmpty() shouldBe false
        irAsmSub.parameters.map { it.dt } shouldBe listOf(DataType.UBYTE)
        irAsmSub.returns.map { it.dt } shouldBe listOf(DataType.UBYTE)
        val irSub = result.irProgram.blocks.first().children.filterIsInstance<IRSubroutine>().single()
        val callInstr = irSub.chunks.flatMap { it.instructions }.single { it.opcode == Opcode.CALL }
        callInstr.requireCallSite().codeReference shouldBe codeLabel("main.putchar")
        result.irProgram.resolveCodeTarget(codeLabel("main.putchar")).shouldNotBeNull()
    }

    test("ir codegen for target 'virtual' produces no prefixed names") {
        val codegen = VmCodeGen(false)
        val program = PtProgram("test", VMTarget())
        val block = PtBlock("main", false, SourceCode.Generated("test"), PtBlock.Options(), Position.DUMMY)
        val sub = PtSub("start", emptyList(), emptyList(), Position.DUMMY)
        sub.add(PtVariable("x", DataType.UBYTE, false, ZeropageWish.DONTCARE, 0u, false, null, null, Position.DUMMY))
        block.add(sub)
        program.add(block)

        val options = getTestOptions()
        val st = SymbolTableMaker(program, options).make()
        val errors = ErrorReporterForTests()
        val result = codegen.generate(program, st, options, errors) as VmAssemblyProgram
        val irProg = result.irProgram

        // Block label should not be prefixed
        val irBlock = irProg.blocks.first()
        irBlock.label shouldBe "main"

        // Subroutine label should be scoped but not prefixed
        val irSub = irBlock.children.single() as IRSubroutine
        irSub.label shouldBe "main.start"

        // Variable in IR symbol table should not have prefixed scoped name
        val irVar = irProg.st.allVariables().single()
        irVar.name shouldBe "main.start.x"
    }

    test("ir codegen for target 'cx16' via newcodegen produces prefixed names") {
        val codegen = VmCodeGen(false)
        val program = PtProgram("test", VMTarget())
        val block = PtBlock("main", false, SourceCode.Generated("test"), PtBlock.Options(), Position.DUMMY)
        val sub = PtSub("start", emptyList(), emptyList(), Position.DUMMY)
        sub.add(PtVariable("x", DataType.UBYTE, false, ZeropageWish.DONTCARE, 0u, false, null, null, Position.DUMMY))
        block.add(sub)
        program.add(block)

        val target = Cx16Target()
        val options = CompilationOptions.builder(target)
            .output(OutputType.RAW)
            .zeropage(ZeropageType.DONTUSE)
            .floats(true)
            .compilerVersion("99.99")
            .newCodegen(true)
            .build()
        val st = SymbolTableMaker(program, options).make()
        val errors = ErrorReporterForTests()
        val result = codegen.generate(program, st, options, errors) as VmAssemblyProgram
        val irProg = result.irProgram

        // Block label should be prefixed
        val irBlock = irProg.blocks.first()
        irBlock.label shouldBe "p8b_main"

        // Subroutine label should be fully scoped and prefixed
        val irSub = irBlock.children.single() as IRSubroutine
        irSub.label shouldBe "p8b_main.p8s_start"

        // Variable in IR symbol table should be fully scoped and prefixed
        val irVar = irProg.st.allVariables().single()
        irVar.name shouldBe "p8b_main.p8s_start.p8v_x"
    }    

    test("an in-place operator on an element through a pointer variable reads and writes the element, not the pointer variable") {
        val codegen = VmCodeGen(false)
        val program = PtProgram("test", VMTarget())
        val block = PtBlock("main", false, SourceCode.Generated("test"), PtBlock.Options(), Position.DUMMY)
        val sub = PtSub("start", emptyList(), emptyList(), Position.DUMMY)
        val pointerType = DataType.pointer(BaseDataType.QUAD)
        sub.add(PtVariable("pq", pointerType, false, ZeropageWish.DONTCARE, 0u, false, null, null, Position.DUMMY))
        sub.add(PtVariable("i", DataType.UBYTE, false, ZeropageWish.DONTCARE, 0u, false, null, null, Position.DUMMY))
        for (index in listOf(PtNumber(BaseDataType.UBYTE, 2.0, Position.DUMMY), PtIdentifier("main.start.i", DataType.UBYTE, Position.DUMMY))) {
            sub.add(PtAugmentedAssign("+=", Position.DUMMY).also {
                it.add(PtAssignTarget(false, Position.DUMMY).also { target ->
                    target.add(PtArrayIndexer(DataType.QUAD, false, Position.DUMMY).also { indexer ->
                        indexer.add(PtIdentifier("main.start.pq", pointerType, Position.DUMMY))
                        indexer.add(index)
                    })
                })
                it.add(PtNumber.fromQuadBits(BaseDataType.QUAD, 5L, Position.DUMMY))
            })
        }
        block.add(sub)
        program.add(block)

        val options = getTestOptions()
        val st = SymbolTableMaker(program, options).make()
        val result = codegen.generate(program, st, options, ErrorReporterForTests()) as VmAssemblyProgram
        val instructions = result.irProgram.blocks.first().children.filterIsInstance<IRSubroutine>().single().chunks.flatMap { it.instructions }
        instructions.filter { it.memory?.symbolName == "main.start.pq" }.map { it.opcode to it.type } shouldBe List(4) { Opcode.LOADM to IRDataType.POINTER }
        instructions.count { it.opcode == Opcode.LOADI && it.type == IRDataType.QUAD } shouldBe 2
        instructions.count { it.opcode == Opcode.STOREI && it.type == IRDataType.QUAD } shouldBe 2
    }

    test("quad values keep all 64 bits through the IR code generator") {
        val codegen = VmCodeGen(false)
        val program = PtProgram("test", VMTarget())
        val block = PtBlock("main", false, SourceCode.Generated("test"), PtBlock.Options(), Position.DUMMY)
        val sub = PtSub("start", emptyList(), emptyList(), Position.DUMMY)
        val big = Long.MAX_VALUE - 1
        fun target(name: String, type: DataType) =
            PtAssignTarget(false, Position.DUMMY).also { it.add(PtIdentifier(name, type, Position.DUMMY)) }
        sub.add(PtVariable("q", DataType.QUAD, false, ZeropageWish.DONTCARE, 0u, false, null, null, Position.DUMMY))
        sub.add(PtVariable("l", DataType.LONG, false, ZeropageWish.DONTCARE, 0u, false, null, null, Position.DUMMY))
        sub.add(PtAssignment(Position.DUMMY).also {
            it.add(target("main.start.q", DataType.QUAD))
            it.add(PtNumber.fromQuadBits(BaseDataType.QUAD, big, Position.DUMMY))
        })
        sub.add(PtAugmentedAssign("+=", Position.DUMMY).also {
            it.add(target("main.start.q", DataType.QUAD))
            it.add(PtNumber.fromQuadBits(BaseDataType.QUAD, 0x123456789abcdefL, Position.DUMMY))
        })
        sub.add(PtAssignment(Position.DUMMY).also {
            it.add(target("main.start.l", DataType.LONG))
            it.add(PtTypeCast(DataType.LONG, false, Position.DUMMY).also { cast -> cast.add(PtIdentifier("main.start.q", DataType.QUAD, Position.DUMMY)) })
        })
        block.add(PtVariable("u", DataType.UQUAD, false, ZeropageWish.DONTCARE, 0u, false, null, null, Position.DUMMY))
        block.add(PtAssignment(Position.DUMMY, isVarInitializer = true).also {
            it.add(target("main.u", DataType.UQUAD))
            it.add(PtNumber.fromQuadBits(BaseDataType.UQUAD, -3L, Position.DUMMY))
        })
        block.add(sub)
        program.add(block)

        val options = getTestOptions()
        val st = SymbolTableMaker(program, options).make()
        val result = codegen.generate(program, st, options, ErrorReporterForTests()) as VmAssemblyProgram
        val instructions = result.irProgram.blocks.first().children.filterIsInstance<IRSubroutine>().single().chunks.flatMap { it.instructions }
        instructions.single { it.opcode == Opcode.STOREIM }.requireImmediateQuad() shouldBe big
        instructions.single { it.opcode == Opcode.ADDIM }.requireImmediateQuad() shouldBe 0x123456789abcdefL
        instructions.count { it.opcode == Opcode.LSIGL && it.type == IRDataType.QUAD } shouldBe 1
        val u = result.irProgram.st.lookup("main.u") as IRStStaticVariable
        (u.initializationValue as IRVariableInitializer.Numeric).quadBits shouldBe -3L
    }
})
