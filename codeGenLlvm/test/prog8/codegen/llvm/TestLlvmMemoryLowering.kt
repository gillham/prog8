package prog8.codegen.llvm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import prog8.code.core.BaseDataType
import prog8.code.core.CompilationOptions
import prog8.code.core.DataType
import prog8.code.core.Encoding
import prog8.code.core.ZeropageWish
import prog8.code.target.LlvmTarget
import prog8.codegen.llvm.lower.LlvmGlobalEmitter
import prog8.codegen.llvm.lower.PointerArray
import prog8.codegen.llvm.lower.PointerArrayAccess
import prog8.codegen.llvm.model.*
import prog8.intermediate.*


class TestLlvmMemoryLowering: FunSpec({

    val names = PointerArray("main.names", LlvmValue.Global("main.names"), 3, 4)
    fun formatted(code: List<LlvmInstruction>) = code.map { LlvmPrinter.format(it) }

    test("a constant index into a str[] array loads its ptr element through getelementptr ptr") {
        val code = PointerArrayAccess.load(names, IRDataType.LONG, direct("main.names", 4), null, LlvmValue.Local("r1", LlvmType.Ptr))
        formatted(code) shouldBe listOf(
            "%r1.elem = getelementptr inbounds ptr, ptr @main.names, i64 1",
            "%r1 = load ptr, ptr %r1.elem")
    }

    test("an index register is widened, and the IR's element size is converted to ptr elements") {
        val r3 = LlvmValue.Local("r3", LlvmType.I16)
        formatted(PointerArrayAccess.load(names, IRDataType.LONG, indexed("main.names", scale = 4), r3, LlvmValue.Local("r4", LlvmType.Ptr))) shouldBe listOf(
            "%r4.index = zext i16 %r3 to i64",
            "%r4.elem = getelementptr inbounds ptr, ptr @main.names, i64 %r4.index",
            "%r4 = load ptr, ptr %r4.elem")
        formatted(PointerArrayAccess.load(names, IRDataType.LONG, indexed("main.names", scale = 8, displacement = 8), r3, LlvmValue.Local("r5", LlvmType.Ptr))) shouldBe listOf(
            "%r5.index = zext i16 %r3 to i64",
            "%r5.scaled = mul i64 %r5.index, 2",
            "%r5.slot = add i64 %r5.scaled, 2",
            "%r5.elem = getelementptr inbounds ptr, ptr @main.names, i64 %r5.slot",
            "%r5 = load ptr, ptr %r5.elem")
    }

    test("address tables and real pointer arrays use their own IR element sizes") {
        val jumps = PointerArray("main.jumps", LlvmValue.Global("main.jumps"), 2, 2)
        formatted(PointerArrayAccess.load(jumps, IRDataType.WORD, direct("main.jumps", 2), null, LlvmValue.Local("r6", LlvmType.Ptr))) shouldBe listOf(
            "%r6.elem = getelementptr inbounds ptr, ptr @main.jumps, i64 1",
            "%r6 = load ptr, ptr %r6.elem")
        val nodes = PointerArray("main.nodes", LlvmValue.Global("main.nodes"), 4, 8)
        val r7 = LlvmValue.Local("r7", LlvmType.I64)
        formatted(PointerArrayAccess.load(nodes, IRDataType.POINTER, indexed("main.nodes", scale = 8), r7, LlvmValue.Local("r8", LlvmType.Ptr))) shouldBe listOf(
            "%r8.elem = getelementptr inbounds ptr, ptr @main.nodes, i64 %r7",
            "%r8 = load ptr, ptr %r8.elem")
    }

    test("stores write ptr elements, integer values are converted with inttoptr") {
        formatted(PointerArrayAccess.store(names, IRDataType.LONG, direct("main.names", 0), null, LlvmValue.Local("r5", LlvmType.Ptr), "st1")) shouldBe listOf(
            "%st1.elem = getelementptr inbounds ptr, ptr @main.names, i64 0",
            "store ptr %r5, ptr %st1.elem")
        formatted(PointerArrayAccess.store(names, IRDataType.LONG, direct("main.names", 8), null, LlvmValue.Local("r6", LlvmType.I32), "st2")) shouldBe listOf(
            "%st2.elem = getelementptr inbounds ptr, ptr @main.names, i64 2",
            "%st2.ptr = inttoptr i32 %r6 to ptr",
            "store ptr %st2.ptr, ptr %st2.elem")
    }

    test("accesses that don't address whole elements through the array's own symbol are rejected") {
        val result = LlvmValue.Local("r", LlvmType.Ptr)
        val r1 = LlvmValue.Local("r1", LlvmType.I16)
        shouldThrow<IllegalArgumentException> { PointerArrayAccess.load(names, IRDataType.LONG, direct("main.names", 2), null, result) }
        shouldThrow<IllegalArgumentException> { PointerArrayAccess.load(names, IRDataType.BYTE, direct("main.names", 4), null, result) }
        shouldThrow<IllegalArgumentException> { PointerArrayAccess.load(names, IRDataType.FLOAT, direct("main.names", 0), null, result) }
        shouldThrow<IllegalArgumentException> { PointerArrayAccess.load(names, IRDataType.LONG, direct("main.other", 0), null, result) }
        shouldThrow<IllegalArgumentException> { PointerArrayAccess.load(names, IRDataType.LONG, indexed("main.names", scale = 1), r1, result) }
        shouldThrow<IllegalArgumentException> { PointerArrayAccess.load(names, IRDataType.LONG, indexed("main.names", scale = 4), null, result) }
        shouldThrow<IllegalArgumentException> { PointerArrayAccess.load(names, IRDataType.LONG, direct("main.names", 0), r1, result) }
        shouldThrow<IllegalArgumentException> { PointerArrayAccess.load(names, IRDataType.LONG, direct("main.names", 0), null, LlvmValue.Local("i", LlvmType.I32)) }
        val pointer = RegisterOperand(VirtualRegister.int(9), IRDataType.POINTER, OperandRole.POINTER_BASE, OperandDirection.USE)
        shouldThrow<IllegalArgumentException> { PointerArrayAccess.load(names, IRDataType.LONG, MemoryReference.Indirect(pointer), null, result) }
        shouldThrow<IllegalArgumentException> { PointerArrayAccess.store(names, IRDataType.LONG, direct("main.names", 0), null, LlvmValue.FloatConstant(1.0), "st") }
    }

    test("a str[] array with getelementptr ptr loads and stores is valid LLVM IR").config(enabledOrReasonIf = requiresLlvmAs) {
        val (exitCode, output) = runLlvmAs(LlvmPrinter.print(stringsProgram()))
        withClue(output) { exitCode shouldBe 0 }
    }

    test("a str[] array links into a native arm64 macOS program that prints its elements").config(enabledOrReasonIf = requiresNativeArm64Mac) {
        val ir = LlvmPrinter.print(stringsProgram())
        ir shouldNotContain "ptrtoint"
        val (exitCode, output) = compileAndRun(ir)
        withClue(output) { exitCode shouldBe 0 }
        output shouldBe "bb\ncc\nzz\n"
    }

    test("on llvm32, pointer array elements are indexed with i32, and a wider index register is truncated") {
        val names32 = PointerArray("main.names", LlvmValue.Global("main.names"), 3, 4, LlvmType.I32)
        formatted(PointerArrayAccess.load(names32, IRDataType.LONG, direct("main.names", 4), null, LlvmValue.Local("r1", LlvmType.Ptr))) shouldBe listOf(
            "%r1.elem = getelementptr inbounds ptr, ptr @main.names, i32 1",
            "%r1 = load ptr, ptr %r1.elem")
        formatted(PointerArrayAccess.load(names32, IRDataType.LONG, indexed("main.names", scale = 4), LlvmValue.Local("r3", LlvmType.I16), LlvmValue.Local("r4", LlvmType.Ptr))) shouldBe listOf(
            "%r4.index = zext i16 %r3 to i32",
            "%r4.elem = getelementptr inbounds ptr, ptr @main.names, i32 %r4.index",
            "%r4 = load ptr, ptr %r4.elem")
        formatted(PointerArrayAccess.load(names32, IRDataType.LONG, indexed("main.names", scale = 4), LlvmValue.Local("r5", LlvmType.I64), LlvmValue.Local("r6", LlvmType.Ptr))) shouldBe listOf(
            "%r6.index = trunc i64 %r5 to i32",
            "%r6.elem = getelementptr inbounds ptr, ptr @main.names, i32 %r6.index",
            "%r6 = load ptr, ptr %r6.elem")
        val nodes32 = PointerArray("main.nodes", LlvmValue.Global("main.nodes"), 4, 4, LlvmType.I32)
        formatted(PointerArrayAccess.load(nodes32, IRDataType.POINTER, indexed("main.nodes", scale = 4), LlvmValue.Local("r7", LlvmType.I32), LlvmValue.Local("r8", LlvmType.Ptr))) shouldBe listOf(
            "%r8.elem = getelementptr inbounds ptr, ptr @main.nodes, i32 %r7",
            "%r8 = load ptr, ptr %r8.elem")
        shouldThrow<IllegalArgumentException> { PointerArrayAccess.load(names32, IRDataType.QUAD, direct("main.names", 0), null, LlvmValue.Local("r", LlvmType.Ptr)) }
    }

    test("an llvm32 str[] array with getelementptr ptr loads and stores is valid LLVM IR").config(enabledOrReasonIf = requiresLlvmAs) {
        val (exitCode, output) = runLlvmAs(LlvmPrinter.print(stringsProgram(LlvmTarget(4))))
        withClue(output) { exitCode shouldBe 0 }
    }

    test("an llvm32 str[] array links into a wasm32 program that prints its elements under wasmtime").config(enabledOrReasonIf = requiresWasm) {
        val (exitCode, output) = compileAndRunWasm(LlvmPrinter.print(stringsProgram(LlvmTarget(4)).withMainNamed(wasiMain)))
        withClue(output) { exitCode shouldBe 0 }
        output shouldBe "bb\ncc\nzz\n"
    }

    test("an llvm32 str[] array links into a bare metal ARM program that prints its elements on qemu").config(enabledOrReasonIf = requiresArm) {
        val (exitCode, output) = compileAndRunArm(LlvmPrinter.print(stringsProgram(armTarget)))
        withClue(output) { exitCode shouldBe 0 }
        output shouldBe "bb\ncc\nzz\n"
    }

    test("an llvm32 str[] array links into a bare metal RISC-V program that prints its elements on qemu").config(enabledOrReasonIf = requiresRiscv) {
        val (exitCode, output) = compileAndRunRiscv(LlvmPrinter.print(stringsProgram(riscvTarget)))
        withClue(output) { exitCode shouldBe 0 }
        output shouldBe "bb\ncc\nzz\n"
    }

    test("an llvm32 str[] array compiles into a wasm32 object").config(enabledOrReasonIf = requiresWasmClang) {
        val (exitCode, output) = compileWasmObject(LlvmPrinter.print(stringsProgram(LlvmTarget(4))))
        withClue(output) { exitCode shouldBe 0 }
    }
})


private fun direct(symbol: String, displacement: Int) = MemoryReference.Direct(AddressBase.Symbol(symbol), displacement)

private fun indexed(symbol: String, scale: Int, displacement: Int = 0) = MemoryReference.Indexed(AddressBase.Symbol(symbol),
    RegisterOperand(VirtualRegister.int(1), IRDataType.WORD, OperandRole.INDEX, OperandDirection.USE), scale, displacement)

/**
 * Prints names[1] (constant index) and names[i] with i=2 (index register), then stores "zz" in names[0] and prints that.
 * The IR accesses the str[] elements as the address integer type of the [target]: a uquad, or a long with 4-byte pointers.
 */
private fun stringsProgram(target: LlvmTarget = LlvmTarget()): LlvmModule {
    val size = target.pointerSize
    val type = if (size == 8) IRDataType.QUAD else IRDataType.LONG
    val st = IRSymbolTable()
    listOf("aa", "bb", "cc", "zz").forEachIndexed { index, text ->
        st.add(IRStStaticVariable("prog8_interned_strings.string_$index", DataType.STR, IRVariableInitializer.Str(text, Encoding.ISO), 3u, ZeropageWish.DONTCARE, 0u, false))
    }
    val initialNames = (0..2).map { IRStSymbolicReference.Symbol("prog8_interned_strings.string_$it") }
    st.add(IRStStaticVariable("main.names", DataType.arrayFor(BaseDataType.STR, target), IRVariableInitializer.Array(initialNames), 3u, ZeropageWish.DONTCARE, 0u, false))
    val program = IRProgram("strings", st, CompilationOptions.builder(target).compilerVersion("99.99").build(), target)
    val module = LlvmModule("strings", target.targetTriple, target.dataLayout)
    val symbols = LlvmGlobalEmitter(program).emit(module)
    val names = symbols.pointerArrays.getValue("main.names")

    fun puts(text: LlvmValue) = LlvmInstruction.Call(null, "puts", listOf(text), LlvmType.I32)
    val second = LlvmValue.Local("second", LlvmType.Ptr)
    val third = LlvmValue.Local("third", LlvmType.Ptr)
    val first = LlvmValue.Local("first", LlvmType.Ptr)
    val code = buildList {
        addAll(PointerArrayAccess.load(names, type, direct("main.names", size), null, second))
        add(puts(second))
        addAll(PointerArrayAccess.load(names, type, indexed("main.names", scale = size), LlvmValue.IntConstant(2, LlvmType.I16), third))
        add(puts(third))
        addAll(PointerArrayAccess.store(names, type, direct("main.names", 0), null, symbols.addresses.getValue("prog8_interned_strings.string_3"), "assign"))
        addAll(PointerArrayAccess.load(names, type, direct("main.names", 0), null, first))
        add(puts(first))
        add(LlvmInstruction.Ret(LlvmValue.IntConstant(0, LlvmType.I32)))
    }
    module.functions += LlvmFunction("puts", LlvmType.I32, listOf(LlvmValue.Local("text", LlvmType.Ptr)), isExternal = true)
    module.functions += LlvmFunction("main", LlvmType.I32, emptyList(), mutableListOf(LlvmBlock("entry", code.toMutableList())))
    return module
}
