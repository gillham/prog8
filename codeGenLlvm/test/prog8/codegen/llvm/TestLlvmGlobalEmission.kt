package prog8.codegen.llvm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import prog8.code.core.*
import prog8.code.target.LlvmTarget
import prog8.code.target.VMTarget
import prog8.codegen.llvm.lower.LlvmDataSymbols
import prog8.codegen.llvm.lower.LlvmGlobalEmitter
import prog8.codegen.llvm.model.*
import prog8.codegen.llvm.types.LlvmTypeMapper
import prog8.intermediate.*


class TestLlvmGlobalEmission: FunSpec({

    val target = LlvmTarget()

    test("IR and Prog8 scalar types map onto LLVM types") {
        val mapper = LlvmTypeMapper(IRSymbolTable())
        mapper.map(IRDataType.BYTE) shouldBe LlvmType.I8
        mapper.map(IRDataType.WORD) shouldBe LlvmType.I16
        mapper.map(IRDataType.LONG) shouldBe LlvmType.I32
        mapper.map(IRDataType.FLOAT) shouldBe LlvmType.Double
        mapper.map(IRDataType.POINTER) shouldBe LlvmType.Ptr
        mapper.map(BaseDataType.BOOL) shouldBe LlvmType.I8
        mapper.map(BaseDataType.BYTE) shouldBe LlvmType.I8
        mapper.map(BaseDataType.UWORD) shouldBe LlvmType.I16
        mapper.map(BaseDataType.LONG) shouldBe LlvmType.I32
        mapper.map(BaseDataType.FLOAT) shouldBe LlvmType.Double
        mapper.map(BaseDataType.POINTER) shouldBe LlvmType.Ptr
        shouldThrow<IllegalArgumentException> { mapper.map(BaseDataType.STR) }
    }

    test("variable storage types follow the Prog8 memory layout") {
        val mapper = LlvmTypeMapper(IRSymbolTable().apply { add(nodeStruct(target)) })
        mapper.storageType(DataType.STR, 6u) shouldBe LlvmType.Array(6, LlvmType.I8)
        mapper.storageType(DataType.arrayFor(BaseDataType.UBYTE, target), 3u) shouldBe LlvmType.Array(3, LlvmType.I8)
        mapper.storageType(DataType.arrayFor(BaseDataType.WORD, target), 4u) shouldBe LlvmType.Array(4, LlvmType.I16)
        mapper.storageType(DataType.arrayFor(BaseDataType.STR, target), 2u) shouldBe LlvmType.Array(2, LlvmType.I32)
        mapper.storageType(DataType.arrayFor(BaseDataType.FLOAT, target), 2u) shouldBe LlvmType.Array(2, LlvmType.Double)
        mapper.storageType(DataType.arrayOfPointersTo(BaseDataType.UBYTE), 2u) shouldBe LlvmType.Array(2, LlvmType.Ptr)
        mapper.storageType(DataType.arrayOfStructs(NodeType), 2u) shouldBe LlvmType.Array(2, nodeLlvmType)
        mapper.storageType(DataType.pointer(BaseDataType.UBYTE), null) shouldBe LlvmType.Ptr
        mapper.storageType(DataType.BOOL, null) shouldBe LlvmType.I8
        shouldThrow<IllegalArgumentException> { mapper.storageType(DataType.splitWordArrayFor(BaseDataType.UWORD), 4u) }
        shouldThrow<IllegalArgumentException> { mapper.storageType(DataType.structInstance(NodeType), null) }
        shouldThrow<IllegalArgumentException> { mapper.storageType(DataType.STR, null) }
    }

    test("structs are packed with flattened array fields, unions are byte arrays") {
        val st = IRSymbolTable()
        st.add(nodeStruct(target))
        st.add(IRStStructDef("main.Either", listOf(IRStStructField(DataType.UWORD, "w"), IRStStructField(DataType.FLOAT, "f")), 8u, isUnion = true))
        st.add(IRStStructDef("main.Bad", listOf(IRStStructField(DataType.UBYTE, "x")), 2u))
        val mapper = LlvmTypeMapper(st)
        mapper.structType("main.Node") shouldBe nodeLlvmType
        mapper.structType("main.Either") shouldBe LlvmType.Array(8, LlvmType.I8)
        shouldThrow<IllegalStateException> { mapper.structType("main.Bad") }
        shouldThrow<IllegalArgumentException> { mapper.structType("main.Missing") }
        mapper.byteSize(nodeLlvmType) shouldBe 15
        mapper.byteSize(LlvmType.Array(3, LlvmType.Struct(listOf(LlvmType.I8, LlvmType.Ptr), packed = true))) shouldBe 27
        mapper.byteSize(LlvmType.Double) shouldBe 8
        shouldThrow<IllegalArgumentException> { mapper.byteSize(LlvmType.Struct(listOf(LlvmType.I8), packed = false)) }
    }

    test("scalar variables get their initial value, or zero") {
        val st = IRSymbolTable()
        st.add(variable("main.flag", DataType.BOOL, numeric(1.0)))
        st.add(variable("main.b", DataType.BYTE, numeric(-5.0)))
        st.add(variable("main.ub", DataType.UBYTE, numeric(200.0)))
        st.add(variable("main.counter", DataType.UWORD))
        st.add(variable("main.w", DataType.WORD, numeric(-1000.0)))
        st.add(variable("main.big", DataType.LONG, numeric(4294967295.0)))
        st.add(variable("main.fl", DataType.FLOAT, numeric(1.5)))
        st.add(variable("main.nothing", DataType.pointer(BaseDataType.UBYTE)))
        st.add(variable("main.screen", DataType.pointer(BaseDataType.UBYTE), numeric(1024.0)))
        val (module, _) = emit(st)
        module.printed("main.flag") shouldBe "@main.flag = internal global i8 1"
        module.printed("main.b") shouldBe "@main.b = internal global i8 -5"
        module.printed("main.ub") shouldBe "@main.ub = internal global i8 -56"
        module.printed("main.counter") shouldBe "@main.counter = internal global i16 0"
        module.printed("main.w") shouldBe "@main.w = internal global i16 -1000"
        module.printed("main.big") shouldBe "@main.big = internal global i32 -1"
        module.printed("main.fl") shouldBe "@main.fl = internal global double 1.5"
        module.printed("main.nothing") shouldBe "@main.nothing = internal global ptr null"
        module.printed("main.screen") shouldBe "@main.screen = internal global ptr inttoptr (i64 1024 to ptr)"
    }

    test("string variables are mutable, interned string literals are private constants") {
        val st = IRSymbolTable()
        st.add(variable("main.name", DataType.STR, IRVariableInitializer.Str("hello", Encoding.ISO), length = 6u))
        st.add(variable("prog8_interned_strings.string_1", DataType.STR, IRVariableInitializer.Str("aa", Encoding.ISO), length = 3u))
        st.add(variable("prog8_interned_strings.string_2", DataType.arrayFor(BaseDataType.UBYTE, target), numbers(98.0, 98.0, 0.0), length = 3u))
        val (module, symbols) = emit(st)
        module.printed("main.name") shouldBe """@main.name = internal global [6 x i8] c"hello\00""""
        module.printed(".str.0") shouldBe """@.str.0 = private unnamed_addr constant [3 x i8] c"aa\00""""
        module.printed(".str.1") shouldBe """@.str.1 = private unnamed_addr constant [3 x i8] c"bb\00""""
        symbols.addresses["prog8_interned_strings.string_1"] shouldBe LlvmValue.Global(".str.0")
        symbols.addresses["main.name"] shouldBe LlvmValue.Global("main.name")

        val wrongLength = IRSymbolTable().apply { add(variable("main.bad", DataType.STR, IRVariableInitializer.Str("hi", Encoding.ISO), length = 5u)) }
        shouldThrow<IllegalArgumentException> { emit(wrongLength) }.message shouldContain "main.bad"
    }

    test("array initializers hold numbers, booleans and addresses") {
        val st = IRSymbolTable()
        st.add(variable("main.name", DataType.STR, IRVariableInitializer.Str("hi", Encoding.ISO), length = 3u))
        st.add(variable("prog8_interned_strings.string_1", DataType.STR, IRVariableInitializer.Str("aa", Encoding.ISO), length = 3u))
        st.add(variable("main.bytes", DataType.arrayFor(BaseDataType.UBYTE, target), numbers(1.0, 2.0, 255.0), length = 3u))
        st.add(variable("main.words", DataType.arrayFor(BaseDataType.UWORD, target), refs(num(1000.0), sym("main.name")), length = 2u))
        st.add(variable("main.names", DataType.arrayFor(BaseDataType.STR, target), refs(sym("prog8_interned_strings.string_1"), sym("main.name")), length = 2u))
        st.add(variable("main.ptrs", DataType.arrayOfPointersTo(BaseDataType.UBYTE), refs(sym("main.name"), num(0.0)), length = 2u))
        st.add(variable("main.floats", DataType.arrayFor(BaseDataType.FLOAT, target), numbers(1.5, -2.0), length = 2u))
        st.add(variable("main.flags", DataType.arrayFor(BaseDataType.BOOL, target), refs(IRStSymbolicReference.BoolValue(true), IRStSymbolicReference.BoolValue(false)), length = 2u))
        st.add(variable("main.handlers", DataType.arrayOfPointersTo(BaseDataType.UBYTE), refs(sym("main.start")), length = 1u))
        st.add(variable("main.zeros", DataType.arrayFor(BaseDataType.WORD, target), length = 4u))
        val (module, _) = emit(st)
        module.printed("main.bytes") shouldBe "@main.bytes = internal global [3 x i8] [i8 1, i8 2, i8 -1]"
        module.printed("main.words") shouldBe "@main.words = internal global [2 x i16] [i16 1000, i16 ptrtoint (ptr @main.name to i16)]"
        module.printed("main.names") shouldBe "@main.names = internal global [2 x i32] [i32 ptrtoint (ptr @.str.0 to i32), i32 ptrtoint (ptr @main.name to i32)]"
        module.printed("main.ptrs") shouldBe "@main.ptrs = internal global [2 x ptr] [ptr @main.name, ptr null]"
        module.printed("main.floats") shouldBe "@main.floats = internal global [2 x double] [double 1.5, double -2.0]"
        module.printed("main.flags") shouldBe "@main.flags = internal global [2 x i8] [i8 1, i8 0]"
        module.printed("main.handlers") shouldBe "@main.handlers = internal global [1 x ptr] [ptr @main.start]"
        module.printed("main.zeros") shouldBe "@main.zeros = internal global [4 x i16] zeroinitializer"

        val wrongCount = IRSymbolTable().apply { add(variable("main.short", DataType.arrayFor(BaseDataType.UBYTE, target), numbers(1.0), length = 2u)) }
        shouldThrow<IllegalArgumentException> { emit(wrongCount) }.message shouldContain "main.short"
        val floatAddress = IRSymbolTable().apply {
            add(variable("main.x", DataType.UBYTE))
            add(variable("main.fs", DataType.arrayFor(BaseDataType.FLOAT, target), refs(sym("main.x")), length = 1u))
        }
        shouldThrow<IllegalArgumentException> { emit(floatAddress) }.message shouldContain "main.fs"
    }

    test("@shared variables stay external, read-only data is constant, alignment is kept") {
        val st = IRSymbolTable()
        st.add(variable("txt.sequence", DataType.STR, IRVariableInitializer.Str("ab", Encoding.ISO), length = 3u, shared = true))
        st.add(variable("main.table", DataType.arrayFor(BaseDataType.UBYTE, target), numbers(1.0, 2.0), length = 2u, readonly = true))
        st.add(variable("main.page", DataType.arrayFor(BaseDataType.UBYTE, target), length = 256u, align = 256u))
        val (module, _) = emit(st)
        module.printed("txt.sequence") shouldBe """@txt.sequence = global [3 x i8] c"ab\00""""
        module.printed("main.table") shouldBe "@main.table = internal constant [2 x i8] [i8 1, i8 2]"
        module.printed("main.page") shouldBe "@main.page = internal global [256 x i8] zeroinitializer, align 256"
    }

    test("struct instances are packed structs, struct arrays hold the instances inline") {
        val (module, symbols) = emit(structSymbols(target))
        val node = "<{ i8, i16, i16, i16, ptr }>"
        val node1 = "<{ i8 1, i16 10, i16 20, i16 30, ptr @main.name }>"
        module.printed("prog8_struct_instances.node1") shouldBe "@prog8_struct_instances.node1 = internal global $node $node1"
        module.printed("prog8_struct_instances.node2") shouldBe "@prog8_struct_instances.node2 = internal global [15 x i8] zeroinitializer"
        module.printed("prog8_struct_instances.either") shouldBe "@prog8_struct_instances.either = internal global [8 x i8] zeroinitializer"
        module.printed("main.nodes") shouldBe "@main.nodes = internal global [2 x $node] [$node $node1, $node zeroinitializer]"
        symbols.addresses["prog8_struct_instances.node1"] shouldBe LlvmValue.Global("prog8_struct_instances.node1")
    }

    test("memory slabs are zeroed byte arrays with their alignment") {
        val st = IRSymbolTable()
        st.add(IRStMemorySlab("prog8_slabs.memory_buffer", 100u, 64u))
        st.add(IRStMemorySlab("prog8_slabs.memory_plain", 10u, 0u))
        val (module, _) = emit(st)
        module.printed("prog8_slabs.memory_buffer") shouldBe "@prog8_slabs.memory_buffer = internal global [100 x i8] zeroinitializer, align 64"
        module.printed("prog8_slabs.memory_plain") shouldBe "@prog8_slabs.memory_plain = internal global [10 x i8] zeroinitializer"

        val odd = IRSymbolTable().apply { add(IRStMemorySlab("prog8_slabs.memory_odd", 10u, 3u)) }
        shouldThrow<IllegalArgumentException> { emit(odd) }.message shouldContain "prog8_slabs.memory_odd"
    }

    test("overlapping and adjacent memory-mapped variables share backing storage") {
        val (module, symbols) = emit(memoryMappedSymbols(target))
        module.printed(".memmapped.d021") shouldBe "@.memmapped.d021 = internal global [1 x i8] zeroinitializer, align 1"
        module.printed(".memmapped.ff0000") shouldBe "@.memmapped.ff0000 = internal global [4 x i8] zeroinitializer, align 8"
        module.printed(".memmapped.ff0100") shouldBe "@.memmapped.ff0100 = internal global [4 x i8] zeroinitializer, align 8"
        fun address(name: String) = LlvmPrinter.format(symbols.addresses.getValue(name))
        address("cx16.r0") shouldBe "@.memmapped.ff0000"
        address("cx16.r0L") shouldBe "@.memmapped.ff0000"
        address("cx16.r0H") shouldBe "getelementptr inbounds (i8, ptr @.memmapped.ff0000, i64 1)"
        address("cx16.r1") shouldBe "getelementptr inbounds (i8, ptr @.memmapped.ff0000, i64 2)"
        address("main.hwregs") shouldBe "@.memmapped.ff0100"
        symbols.memoryMappedAddress(0xff0003)?.let { LlvmPrinter.format(it) } shouldBe "getelementptr inbounds (i8, ptr @.memmapped.ff0000, i64 3)"
        symbols.memoryMappedAddress(0xff0102)?.let { LlvmPrinter.format(it) } shouldBe "getelementptr inbounds (i8, ptr @.memmapped.ff0100, i64 2)"
        symbols.memoryMappedAddress(0xd021) shouldBe LlvmValue.Global(".memmapped.d021")
        symbols.memoryMappedAddress(0xff0004) shouldBe null
        symbols.memoryMappedAddress(0xd020) shouldBe null
    }

    test("constants become internal constant globals, memory slab constants hold the slab address") {
        val st = IRSymbolTable()
        st.add(IRStConstant("main.LIMIT", DataType.UBYTE, 42.0))
        st.add(IRStConstant("main.PI", DataType.FLOAT, 3.25))
        st.add(IRStConstant("main.MINUS", DataType.WORD, -2.0))
        st.add(IRStMemorySlab("prog8_slabs.memory_buf", 16u, 0u))
        st.add(IRStConstant("main.BUF", DataType.UWORD, null, "memory_buf"))
        st.add(IRStConstant("main.BUFPTR", DataType.pointer(BaseDataType.UBYTE), null, "memory_buf"))
        val (module, symbols) = emit(st)
        module.printed("main.LIMIT") shouldBe "@main.LIMIT = internal constant i8 42"
        module.printed("main.PI") shouldBe "@main.PI = internal constant double 3.25"
        module.printed("main.MINUS") shouldBe "@main.MINUS = internal constant i16 -2"
        module.printed("main.BUF") shouldBe "@main.BUF = internal constant i16 ptrtoint (ptr @prog8_slabs.memory_buf to i16)"
        module.printed("main.BUFPTR") shouldBe "@main.BUFPTR = internal constant ptr @prog8_slabs.memory_buf"
        symbols.addresses["main.LIMIT"] shouldBe LlvmValue.Global("main.LIMIT")

        val empty = IRSymbolTable().apply { add(IRStConstant("main.NOTHING", DataType.UBYTE, null)) }
        shouldThrow<IllegalArgumentException> { emit(empty) }.message shouldContain "main.NOTHING"
    }

    test("globals are grouped by kind, independent of the symbol table order") {
        val st = IRSymbolTable()
        st.add(IRStConstant("main.LIMIT", DataType.UBYTE, 42.0))
        st.add(IRStMemVar("main.reg", DataType.UBYTE, 0xd020u, null))
        st.add(IRStMemorySlab("prog8_slabs.memory_buf", 16u, 0u))
        st.add(nodeStruct(target))
        st.add(IRStStructInstance("prog8_struct_instances.node", "main.Node", emptyList(), 15u))
        st.add(variable("main.counter", DataType.UWORD))
        emit(st).first.globals.map { it.name } shouldBe
            listOf("main.counter", "prog8_struct_instances.node", "prog8_slabs.memory_buf", ".memmapped.d020", "main.LIMIT")
    }

    test("the emitter only accepts the llvm64 data layout") {
        val st = IRSymbolTable().apply { add(variable("main.counter", DataType.UWORD)) }
        shouldThrow<IllegalArgumentException> { emit(st, VMTarget()) }.message shouldContain "8-byte pointers"
    }

    test("a module with every kind of global is accepted by llvm-as").config(enabledOrReasonIf = requiresLlvmAs) {
        val st = structSymbols(target)
        memoryMappedSymbols(target).let { memvars -> memvars.allMemMappedVariables().forEach { st.add(it) } }
        st.add(variable("prog8_interned_strings.string_1", DataType.STR, IRVariableInitializer.Str("aa", Encoding.ISO), length = 3u))
        st.add(variable("main.words", DataType.arrayFor(BaseDataType.UWORD, target), refs(num(1000.0), sym("main.name")), length = 2u))
        st.add(variable("main.names", DataType.arrayFor(BaseDataType.STR, target), refs(sym("prog8_interned_strings.string_1"), sym("main.name")), length = 2u))
        st.add(variable("main.regs", DataType.arrayOfPointersTo(BaseDataType.UWORD), refs(sym("cx16.r1"), sym("cx16.r0"), num(0.0)), length = 3u))
        st.add(variable("main.handlers", DataType.arrayOfPointersTo(BaseDataType.UBYTE), refs(sym("main.start")), length = 1u))
        st.add(variable("main.floats", DataType.arrayFor(BaseDataType.FLOAT, target), numbers(1.5, -2.0), length = 2u))
        st.add(variable("main.screen", DataType.pointer(BaseDataType.UBYTE), numeric(1024.0)))
        st.add(variable("main.counter", DataType.UWORD))
        st.add(variable("txt.sequence", DataType.STR, IRVariableInitializer.Str("ab", Encoding.ISO), length = 3u, shared = true))
        st.add(variable("main.page", DataType.arrayFor(BaseDataType.UBYTE, target), length = 256u, align = 256u, readonly = true))
        st.add(IRStMemorySlab("prog8_slabs.memory_buf", 16u, 64u))
        st.add(IRStConstant("main.BUF", DataType.UWORD, null, "memory_buf"))
        st.add(IRStConstant("main.PI", DataType.FLOAT, 3.25))

        val module = LlvmModule("globals", target.targetTriple, target.dataLayout)
        LlvmGlobalEmitter(program(st, target)).emit(module)
        module.functions += LlvmFunction("main.start", LlvmType.Void, emptyList(), isExternal = true)
        val (exitCode, output) = runLlvmAs(LlvmPrinter.print(module))
        withClue(output) { exitCode shouldBe 0 }
    }
})


private object NodeType : ISubType {
    override val scopedNameString = "main.Node"
    override fun memsize(sizer: IMemSizer) = 15
    override fun sameas(other: ISubType) = other === this
    override fun getFieldType(name: String): DataType? = null
}

private val nodeLlvmType = LlvmType.Struct(listOf(LlvmType.I8, LlvmType.I16, LlvmType.I16, LlvmType.I16, LlvmType.Ptr), packed = true)

private fun nodeStruct(target: ICompilationTarget) = IRStStructDef("main.Node", listOf(
    IRStStructField(DataType.UBYTE, "id"),
    IRStStructField(DataType.arrayFor(BaseDataType.UWORD, target), "values", 3),
    IRStStructField(DataType.pointer(BaseDataType.UBYTE), "next")), 15u)

private fun structSymbols(target: ICompilationTarget) = IRSymbolTable().apply {
    add(nodeStruct(target))
    add(IRStStructDef("main.Either", listOf(IRStStructField(DataType.UWORD, "w"), IRStStructField(DataType.FLOAT, "f")), 8u, isUnion = true))
    add(variable("main.name", DataType.STR, IRVariableInitializer.Str("hi", Encoding.ISO), length = 3u))
    add(IRStStructInstance("prog8_struct_instances.node1", "main.Node", listOf(
        IRStructInitValue(BaseDataType.UBYTE, num(1.0)),
        IRStructInitValue(BaseDataType.UWORD, num(10.0)),
        IRStructInitValue(BaseDataType.UWORD, num(20.0)),
        IRStructInitValue(BaseDataType.UWORD, num(30.0)),
        IRStructInitValue(BaseDataType.POINTER, sym("main.name"))), 15u))
    add(IRStStructInstance("prog8_struct_instances.node2", "main.Node", emptyList(), 15u))
    add(IRStStructInstance("prog8_struct_instances.either", "main.Either", listOf(IRStructInitValue(BaseDataType.UWORD, num(5.0))), 8u))
    add(variable("main.nodes", DataType.arrayOfStructs(NodeType), refs(sym("prog8_struct_instances.node1"), sym("prog8_struct_instances.node2")), length = 2u))
}

private fun memoryMappedSymbols(target: ICompilationTarget) = IRSymbolTable().apply {
    add(IRStMemVar("cx16.r0", DataType.UWORD, 0xff0000u, null))
    add(IRStMemVar("cx16.r0L", DataType.UBYTE, 0xff0000u, null))
    add(IRStMemVar("cx16.r0H", DataType.UBYTE, 0xff0001u, null))
    add(IRStMemVar("cx16.r1", DataType.UWORD, 0xff0002u, null))
    add(IRStMemVar("main.hwregs", DataType.arrayFor(BaseDataType.UBYTE, target), 0xff0100u, 4u))
    add(IRStMemVar("main.oddreg", DataType.UBYTE, 0xd021u, null))
}

private fun variable(name: String, dt: DataType, init: IRVariableInitializer? = null, length: UInt? = null,
                     align: UInt = 0u, readonly: Boolean = false, shared: Boolean = false) =
    IRStStaticVariable(name, dt, init, length, ZeropageWish.DONTCARE, align, dirty = false, inBss = init == null, readonly = readonly, shared = shared)

private fun numeric(value: Double) = IRVariableInitializer.Numeric(value)
private fun num(value: Double) = IRStSymbolicReference.Numeric(value)
private fun sym(name: String) = IRStSymbolicReference.Symbol(name)
private fun refs(vararg elements: IRStSymbolicReference) = IRVariableInitializer.Array(elements.toList())
private fun numbers(vararg values: Double) = IRVariableInitializer.Array(values.map { num(it) })

private fun program(st: IRSymbolTable, target: ICompilationTarget) =
    IRProgram("test", st, CompilationOptions.builder(target).floats(true).compilerVersion("99.99").build(), target)

private fun emit(st: IRSymbolTable, target: ICompilationTarget = LlvmTarget()): Pair<LlvmModule, LlvmDataSymbols> {
    val module = LlvmModule("test")
    return module to LlvmGlobalEmitter(program(st, target)).emit(module)
}

private fun LlvmModule.printed(name: String): String = LlvmPrinter.format(globals.single { it.name == name })
