package prog8.codegen.llvm

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import prog8.code.core.CpuType
import prog8.code.target.LlvmTarget
import prog8.codegen.llvm.model.*
import java.nio.file.Path


class TestLlvmAstEmission: FunSpec({

    fun assertAcceptedByLlvmAs(module: LlvmModule) {
        val (exitCode, output) = runLlvmAs(LlvmPrinter.print(module))
        withClue(output) { exitCode shouldBe 0 }
    }

    test("types are formatted in LLVM syntax") {
        LlvmPrinter.format(LlvmType.I1) shouldBe "i1"
        LlvmPrinter.format(LlvmType.I8) shouldBe "i8"
        LlvmPrinter.format(LlvmType.I16) shouldBe "i16"
        LlvmPrinter.format(LlvmType.I32) shouldBe "i32"
        LlvmPrinter.format(LlvmType.I64) shouldBe "i64"
        LlvmPrinter.format(LlvmType.Double) shouldBe "double"
        LlvmPrinter.format(LlvmType.Ptr) shouldBe "ptr"
        LlvmPrinter.format(LlvmType.Void) shouldBe "void"
        LlvmPrinter.format(LlvmType.Array(2, LlvmType.Array(3, LlvmType.I8))) shouldBe "[2 x [3 x i8]]"
        LlvmPrinter.format(LlvmType.Function(LlvmType.I16, listOf(LlvmType.I8, LlvmType.Ptr))) shouldBe "i16 (i8, ptr)"
        LlvmPrinter.format(LlvmType.Function(LlvmType.Void, emptyList())) shouldBe "void ()"
        LlvmPrinter.format(LlvmType.Struct(listOf(LlvmType.I8, LlvmType.Ptr), packed = true)) shouldBe "<{ i8, ptr }>"
        LlvmPrinter.format(LlvmType.Struct(listOf(LlvmType.I8, LlvmType.Ptr), packed = false)) shouldBe "{ i8, ptr }"
        LlvmPrinter.format(LlvmType.Struct(emptyList(), packed = true)) shouldBe "<{}>"
        LlvmFunction("f", LlvmType.I16, listOf(LlvmValue.Local("x", LlvmType.I8))).type shouldBe
            LlvmType.Function(LlvmType.I16, listOf(LlvmType.I8))
        LlvmType.I32.bitWidth shouldBe 32
        LlvmType.Ptr.isInteger shouldBe false
        LlvmType.Array(1, LlvmType.I8).isFirstClass shouldBe true
        LlvmType.Void.isFirstClass shouldBe false
    }

    test("integer constants are printed in canonical signed form") {
        LlvmPrinter.format(i8(255)) shouldBe "-1"
        LlvmPrinter.format(i8(-128)) shouldBe "-128"
        LlvmPrinter.format(i16(65535)) shouldBe "-1"
        LlvmPrinter.format(i32(0x80000000L)) shouldBe "-2147483648"
        LlvmPrinter.format(i64(Long.MIN_VALUE)) shouldBe "-9223372036854775808"
        LlvmPrinter.format(LlvmValue.IntConstant(1, LlvmType.I1)) shouldBe "true"
        LlvmPrinter.format(LlvmValue.IntConstant(0, LlvmType.I1)) shouldBe "false"
        LlvmPrinter.typed(i16(42)) shouldBe "i16 42"
    }

    test("float constants are exact decimals, or hex when no decimal form exists") {
        fun float(value: Double) = LlvmPrinter.format(LlvmValue.FloatConstant(value))
        float(1.5) shouldBe "1.5"
        float(0.1) shouldBe "0.1"
        float(1e10) shouldBe "1.0E10"
        float(-0.0) shouldBe "-0.0"
        float(Double.NaN) shouldBe "0x7FF8000000000000"
        float(Double.POSITIVE_INFINITY) shouldBe "0x7FF0000000000000"
        float(Double.NEGATIVE_INFINITY) shouldBe "0xFFF0000000000000"
        LlvmPrinter.typed(LlvmValue.FloatConstant(2.25)) shouldBe "double 2.25"
    }

    test("string constants are [N x i8] arrays with escaped bytes") {
        val bytes = "hi \"\\".map { it.code.toUByte() } + listOf<UByte>(0x0Au, 0x00u, 0xFFu)
        val constant = LlvmValue.StringConstant(bytes)
        constant.type shouldBe LlvmType.Array(8, LlvmType.I8)
        LlvmPrinter.format(constant) shouldBe """c"hi \22\\\0A\00\FF""""
        LlvmValue.StringConstant("hi \"\\\n\u0000\u00FF") shouldBe constant
        shouldThrow<IllegalArgumentException> { LlvmValue.StringConstant("\u20AC") }
    }

    test("identifiers are quoted only when they are not plain LLVM names") {
        LlvmPrinter.format(LlvmValue.Local("r0", LlvmType.I16)) shouldBe "%r0"
        LlvmPrinter.format(LlvmValue.Global("main.start")) shouldBe "@main.start"
        LlvmPrinter.format(LlvmValue.Global(".str.0")) shouldBe "@.str.0"
        LlvmPrinter.format(LlvmValue.Global($$"p8$sub-x_y")) shouldBe $$"@p8$sub-x_y"
        LlvmPrinter.format(LlvmValue.Local("5", LlvmType.I8)) shouldBe "%\"5\""
        LlvmPrinter.format(LlvmValue.Global("has space")) shouldBe "@\"has space\""
        LlvmPrinter.format(LlvmValue.Global("quo\"te")) shouldBe "@\"quo\\22te\""
        LlvmPrinter.format(LlvmValue.Global("gr\u00FCn")) shouldBe "@\"gr\\C3\\BCn\""
        LlvmPrinter.format(LlvmInstruction.Br("2then")) shouldBe "br label %\"2then\""

        val function = LlvmFunction("f", LlvmType.Void, emptyList(), mutableListOf(
            LlvmBlock("entry", mutableListOf(LlvmInstruction.Br("2then"))),
            LlvmBlock("2then", mutableListOf(LlvmInstruction.Ret(null)))))
        LlvmPrinter.print(LlvmModule("m", functions = mutableListOf(function))).lines() shouldContain "\"2then\":"
    }

    test("every instruction kind is formatted in LLVM syntax") {
        val a8 = LlvmValue.Local("a", LlvmType.I8)
        val a16 = LlvmValue.Local("a", LlvmType.I16)
        val b16 = LlvmValue.Local("b", LlvmType.I16)
        val r16 = LlvmValue.Local("r", LlvmType.I16)
        val fa = LlvmValue.Local("fa", LlvmType.Double)
        val fb = LlvmValue.Local("fb", LlvmType.Double)
        val addr = LlvmValue.Local("addr", LlvmType.I64)
        val p = LlvmValue.Local("p", LlvmType.Ptr)
        val c = LlvmValue.Local("c", LlvmType.I1)
        fun format(instruction: LlvmInstruction) = LlvmPrinter.format(instruction)

        format(LlvmInstruction.Alloca(p, LlvmType.Array(4, LlvmType.I8))) shouldBe "%p = alloca [4 x i8]"
        format(LlvmInstruction.Load(r16, p)) shouldBe "%r = load i16, ptr %p"
        format(LlvmInstruction.Load(r16, p, align = 1)) shouldBe "%r = load i16, ptr %p, align 1"
        format(LlvmInstruction.Load(r16, p, align = 1, volatile = true)) shouldBe "%r = load volatile i16, ptr %p, align 1"
        format(LlvmInstruction.Store(a16, LlvmValue.Global("g"))) shouldBe "store i16 %a, ptr @g"
        format(LlvmInstruction.Store(i16(-1), p, align = 2)) shouldBe "store i16 -1, ptr %p, align 2"
        format(LlvmInstruction.Store(p, LlvmValue.ConstantGep(LlvmType.I8, LlvmValue.Global("g"), listOf(i64(7))), align = 1, volatile = true)) shouldBe
            "store volatile ptr %p, ptr getelementptr inbounds (i8, ptr @g, i64 7), align 1"
        format(LlvmInstruction.BinaryOp(BinaryOperator.ADD, r16, a16, i16(1))) shouldBe "%r = add i16 %a, 1"
        format(LlvmInstruction.BinaryOp(BinaryOperator.ASHR, r16, a16, b16)) shouldBe "%r = ashr i16 %a, %b"
        format(LlvmInstruction.BinaryOp(BinaryOperator.FDIV, fa, fa, fb)) shouldBe "%fa = fdiv double %fa, %fb"
        format(LlvmInstruction.FNeg(fb, fa)) shouldBe "%fb = fneg double %fa"
        format(LlvmInstruction.Icmp(IcmpCondition.ULT, c, a16, b16)) shouldBe "%c = icmp ult i16 %a, %b"
        format(LlvmInstruction.Icmp(IcmpCondition.EQ, c, p, LlvmValue.NullPtr())) shouldBe "%c = icmp eq ptr %p, null"
        format(LlvmInstruction.Fcmp(FcmpCondition.OLT, c, fa, LlvmValue.FloatConstant(0.5))) shouldBe "%c = fcmp olt double %fa, 0.5"
        format(LlvmInstruction.Cast(CastOperator.TRUNC, a8, a16, LlvmType.I8)) shouldBe "%a = trunc i16 %a to i8"
        format(LlvmInstruction.Cast(CastOperator.ZEXT, r16, a8, LlvmType.I16)) shouldBe "%r = zext i8 %a to i16"
        format(LlvmInstruction.Cast(CastOperator.SEXT, addr, a16, LlvmType.I64)) shouldBe "%addr = sext i16 %a to i64"
        format(LlvmInstruction.Cast(CastOperator.SITOFP, fa, a16, LlvmType.Double)) shouldBe "%fa = sitofp i16 %a to double"
        format(LlvmInstruction.Cast(CastOperator.UITOFP, fa, a8, LlvmType.Double)) shouldBe "%fa = uitofp i8 %a to double"
        format(LlvmInstruction.Cast(CastOperator.FPTOSI, r16, fa, LlvmType.I16)) shouldBe "%r = fptosi double %fa to i16"
        format(LlvmInstruction.Cast(CastOperator.FPTOUI, r16, fa, LlvmType.I16)) shouldBe "%r = fptoui double %fa to i16"
        format(LlvmInstruction.Cast(CastOperator.PTRTOINT, addr, p, LlvmType.I64)) shouldBe "%addr = ptrtoint ptr %p to i64"
        format(LlvmInstruction.Cast(CastOperator.INTTOPTR, p, addr, LlvmType.Ptr)) shouldBe "%p = inttoptr i64 %addr to ptr"
        format(LlvmInstruction.Cast(CastOperator.BITCAST, p, p, LlvmType.Ptr)) shouldBe "%p = bitcast ptr %p to ptr"
        format(LlvmInstruction.GetElementPtr(p, LlvmType.Array(3, LlvmType.I16), LlvmValue.Global("arr"), listOf(i64(0), i64(1)))) shouldBe
            "%p = getelementptr inbounds [3 x i16], ptr @arr, i64 0, i64 1"
        format(LlvmInstruction.GetElementPtr(p, LlvmType.I8, p, listOf(a16), inBounds = false)) shouldBe "%p = getelementptr i8, ptr %p, i16 %a"
        format(LlvmInstruction.Call(r16, "f", listOf(a8, LlvmValue.NullPtr()), LlvmType.I16)) shouldBe "%r = call i16 @f(i8 %a, ptr null)"
        format(LlvmInstruction.Call(r16, p, listOf(i8(1)), LlvmType.I16)) shouldBe "%r = call i16 %p(i8 1)"
        format(LlvmInstruction.Call(null, "f", emptyList(), LlvmType.I16)) shouldBe "call i16 @f()"
        format(LlvmInstruction.Call(null, "g", emptyList(), LlvmType.Void)) shouldBe "call void @g()"
        format(LlvmInstruction.Select(r16, c, a16, i16(0))) shouldBe "%r = select i1 %c, i16 %a, i16 0"
        format(LlvmInstruction.Phi(r16, listOf(a16 to "then", i16(0) to "2else"))) shouldBe "%r = phi i16 [ %a, %then ], [ 0, %\"2else\" ]"
        format(LlvmInstruction.Br("next")) shouldBe "br label %next"
        format(LlvmInstruction.CondBr(c, "yes", "no")) shouldBe "br i1 %c, label %yes, label %no"
        format(LlvmInstruction.Ret(null)) shouldBe "ret void"
        format(LlvmInstruction.Ret(r16)) shouldBe "ret i16 %r"
        format(LlvmInstruction.Unreachable) shouldBe "unreachable"
    }

    test("globals are formatted with linkage, unnamed_addr and alignment") {
        val string = LlvmGlobal(".str.0", LlvmType.Array(3, LlvmType.I8), LlvmValue.StringConstant("hi\u0000"), isConstant = true,
            linkage = LlvmLinkage.PRIVATE, unnamedAddr = true, align = 1)
        LlvmPrinter.format(string) shouldBe """@.str.0 = private unnamed_addr constant [3 x i8] c"hi\00", align 1"""
        val words = LlvmValue.ArrayConstant(LlvmType.I16, listOf(i16(1), i16(-1), i16(32767)))
        LlvmPrinter.format(LlvmGlobal("main.values", words.type, words, isConstant = false, linkage = LlvmLinkage.INTERNAL)) shouldBe
            "@main.values = internal global [3 x i16] [i16 1, i16 -1, i16 32767]"
        val buffer = LlvmType.Array(16, LlvmType.I8)
        LlvmPrinter.format(LlvmGlobal("buffer", buffer, LlvmValue.ZeroInitializer(buffer), isConstant = false)) shouldBe
            "@buffer = global [16 x i8] zeroinitializer"
        LlvmPrinter.format(LlvmGlobal("ext", LlvmType.I32, null, isConstant = false)) shouldBe "@ext = external global i32"
        val pointers = LlvmValue.ArrayConstant(LlvmType.Ptr, listOf(LlvmValue.Global(".str.0"), LlvmValue.NullPtr()))
        LlvmPrinter.format(LlvmGlobal("names", pointers.type, pointers, isConstant = true)) shouldBe
            "@names = constant [2 x ptr] [ptr @.str.0, ptr null]"
        LlvmPrinter.format(LlvmGlobal("counter", LlvmType.I16, i16(0), isConstant = false)) shouldBe "@counter = global i16 0"
        val ctors = LlvmValue.ArrayConstant(ctorEntryType, listOf(ctorEntry("init")))
        LlvmPrinter.format(LlvmGlobal("llvm.global_ctors", ctors.type, ctors, isConstant = false, linkage = LlvmLinkage.APPENDING)) shouldBe
            "@llvm.global_ctors = appending global [1 x { i32, ptr, ptr }] [{ i32, ptr, ptr } { i32 65535, ptr @init, ptr null }]"
    }

    test("struct constants, constant expressions and zero values are formatted in LLVM syntax") {
        val packed = LlvmType.Struct(listOf(LlvmType.I8, LlvmType.Ptr), packed = true)
        LlvmPrinter.format(LlvmValue.StructConstant(packed, listOf(i8(1), LlvmValue.NullPtr()))) shouldBe "<{ i8 1, ptr null }>"
        val unpacked = LlvmType.Struct(listOf(LlvmType.I16, LlvmType.Double), packed = false)
        LlvmPrinter.format(LlvmValue.StructConstant(unpacked, listOf(i16(2), LlvmValue.FloatConstant(0.5)))) shouldBe "{ i16 2, double 0.5 }"
        LlvmPrinter.format(LlvmValue.StructConstant(LlvmType.Struct(emptyList(), packed = true), emptyList())) shouldBe "<{}>"
        val x = LlvmValue.Global("x")
        LlvmPrinter.format(LlvmValue.ConstantCast(CastOperator.PTRTOINT, x, LlvmType.I32)) shouldBe "ptrtoint (ptr @x to i32)"
        LlvmPrinter.format(LlvmValue.ConstantCast(CastOperator.INTTOPTR, i64(1024), LlvmType.Ptr)) shouldBe "inttoptr (i64 1024 to ptr)"
        LlvmPrinter.format(LlvmValue.ConstantCast(CastOperator.TRUNC, LlvmValue.ConstantCast(CastOperator.PTRTOINT, x, LlvmType.I64), LlvmType.I16)) shouldBe
            "trunc (i64 ptrtoint (ptr @x to i64) to i16)"
        LlvmPrinter.typed(LlvmValue.ConstantGep(LlvmType.I8, x, listOf(i64(2)))) shouldBe "ptr getelementptr inbounds (i8, ptr @x, i64 2)"
        LlvmPrinter.format(LlvmValue.ConstantGep(LlvmType.Array(4, LlvmType.I16), x, listOf(i64(0), i64(3)), inBounds = false)) shouldBe
            "getelementptr ([4 x i16], ptr @x, i64 0, i64 3)"
        LlvmPrinter.format(LlvmValue.ZeroInitializer(LlvmType.I1)) shouldBe "false"
        LlvmPrinter.format(LlvmValue.ZeroInitializer(LlvmType.I32)) shouldBe "0"
        LlvmPrinter.format(LlvmValue.ZeroInitializer(LlvmType.Double)) shouldBe "0.0"
        LlvmPrinter.format(LlvmValue.ZeroInitializer(LlvmType.Ptr)) shouldBe "null"
        LlvmPrinter.format(LlvmValue.ZeroInitializer(packed)) shouldBe "zeroinitializer"
    }

    test("constructing invalid IR is rejected") {
        val a8 = LlvmValue.Local("a", LlvmType.I8)
        val a16 = LlvmValue.Local("a", LlvmType.I16)
        shouldThrow<IllegalArgumentException> { i8(256) }
        shouldThrow<IllegalArgumentException> { i8(-129) }
        shouldThrow<IllegalArgumentException> { LlvmValue.IntConstant(1, LlvmType.Double) }
        shouldThrow<IllegalArgumentException> { LlvmValue.Local("", LlvmType.I8) }
        shouldThrow<IllegalArgumentException> { LlvmValue.Local("x", LlvmType.Void) }
        shouldThrow<IllegalArgumentException> { LlvmValue.Global("g", LlvmType.I16) }
        shouldThrow<IllegalArgumentException> { LlvmValue.ArrayConstant(LlvmType.I16, listOf(i16(1), i8(1))) }
        shouldThrow<IllegalArgumentException> { LlvmInstruction.BinaryOp(BinaryOperator.ADD, a16, a8, a16) }
        shouldThrow<IllegalArgumentException> { LlvmInstruction.BinaryOp(BinaryOperator.FADD, a16, a16, a16) }
        shouldThrow<IllegalArgumentException> { LlvmInstruction.Icmp(IcmpCondition.EQ, a8, a16, a16) }
        shouldThrow<IllegalArgumentException> { LlvmInstruction.Call(a16, "f", emptyList(), LlvmType.Void) }
        shouldThrow<IllegalArgumentException> { LlvmInstruction.CondBr(a8, "yes", "no") }
        shouldThrow<IllegalArgumentException> { LlvmInstruction.Cast(CastOperator.ZEXT, a8, a16, LlvmType.I8) }
        shouldThrow<IllegalArgumentException> { LlvmInstruction.Load(a16, LlvmValue.Global("g"), align = 3) }
        shouldThrow<IllegalArgumentException> { LlvmGlobal("g", LlvmType.I16, null, isConstant = false, linkage = LlvmLinkage.PRIVATE) }
        shouldThrow<IllegalArgumentException> { LlvmFunction("f", LlvmType.Void, emptyList(), isExternal = true, linkage = LlvmLinkage.INTERNAL) }
        shouldThrow<IllegalArgumentException> { LlvmFunction("f", LlvmType.Void, emptyList(), linkage = LlvmLinkage.APPENDING) }
        shouldThrow<IllegalArgumentException> { LlvmGlobal("g", LlvmType.I16, i16(0), isConstant = false, linkage = LlvmLinkage.APPENDING) }
        shouldThrow<IllegalArgumentException> { LlvmType.Struct(listOf(LlvmType.Void), packed = true) }
        shouldThrow<IllegalArgumentException> { LlvmValue.StructConstant(LlvmType.Struct(listOf(LlvmType.I8), packed = true), listOf(i16(1))) }
        shouldThrow<IllegalArgumentException> { LlvmValue.ConstantCast(CastOperator.ZEXT, i8(1), LlvmType.I16) }
        shouldThrow<IllegalArgumentException> { LlvmValue.ConstantCast(CastOperator.PTRTOINT, i16(1), LlvmType.I32) }
        shouldThrow<IllegalArgumentException> { LlvmValue.ConstantCast(CastOperator.PTRTOINT, LlvmValue.Local("p", LlvmType.Ptr), LlvmType.I64) }
        shouldThrow<IllegalArgumentException> { LlvmValue.ConstantGep(LlvmType.I8, LlvmValue.Global("g"), listOf(a16)) }
        shouldThrow<IllegalArgumentException> { LlvmValue.ConstantGep(LlvmType.I8, LlvmValue.Local("p", LlvmType.Ptr), listOf(i64(1))) }
    }

    test("a block's terminator is its last instruction, if that is a terminator") {
        val store = LlvmInstruction.Store(i8(1), LlvmValue.Global("g"))
        store.isTerminator shouldBe false
        LlvmBlock("empty").terminator shouldBe null
        LlvmBlock("open", mutableListOf(store)).terminator shouldBe null
        val terminators = listOf(LlvmInstruction.Br("x"), LlvmInstruction.CondBr(LlvmValue.Local("c", LlvmType.I1), "x", "y"),
            LlvmInstruction.Ret(null), LlvmInstruction.Unreachable)
        for (terminator in terminators) {
            terminator.isTerminator shouldBe true
            LlvmBlock("closed", mutableListOf(store, terminator)).terminator shouldBe terminator
        }
    }

    test("the printer rejects declarations with a body and definitions without one") {
        val body = mutableListOf(LlvmBlock("entry", mutableListOf(LlvmInstruction.Ret(null))))
        val declarationWithBody = LlvmFunction("f", LlvmType.Void, emptyList(), body, isExternal = true)
        shouldThrow<IllegalArgumentException> { LlvmPrinter.print(LlvmModule("m", functions = mutableListOf(declarationWithBody))) }
        val definitionWithoutBody = LlvmFunction("g", LlvmType.Void, emptyList())
        shouldThrow<IllegalArgumentException> { LlvmPrinter.print(LlvmModule("m", functions = mutableListOf(definitionWithoutBody))) }
    }

    test("a module is printed as a complete .ll file") {
        val expected = """
            ; ModuleID = 'golden'
            ; generated by prog8
            source_filename = "golden"
            target datalayout = "e-m:o-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-n32:64-S128-Fn32"
            target triple = "arm64-apple-macosx"

            @.str.0 = private unnamed_addr constant [3 x i8] c"hi\00", align 1
            @main.counter = global i16 0

            declare i32 @putchar(i32)

            define i16 @main.start(i16 %a) {
            entry:
              %r0 = add i16 %a, 1
              %iszero = icmp eq i16 %r0, 0
              br i1 %iszero, label %zero, label %done

            zero:
              store i16 %r0, ptr @main.counter
              br label %done

            done:
              ret i16 %r0
            }
            """.trimIndent() + "\n"
        LlvmPrinter.print(goldenModule()) shouldBe expected
        val out = StringBuilder()
        LlvmPrinter.print(goldenModule(), out)
        out.toString() shouldBe expected
    }

    test("the llvm64 target defaults to hosted arm64 macOS with 64-bit pointers and doubles") {
        val target = LlvmTarget()
        target.name shouldBe "llvm64"
        target.targetTriple shouldBe "arm64-apple-macosx"
        target.dataLayout shouldBe "e-m:o-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-n32:64-S128-Fn32"
        LlvmTarget(targetTriple = "x86_64-unknown-linux-gnu").targetTriple shouldBe "x86_64-unknown-linux-gnu"
        target.cpu shouldBe CpuType.LLVM64
        target.cpu.isLlvm shouldBe true
        target.cpu.statusBitsOnMultiByteOps shouldBe false
        target.cpu.usesM68kConvention shouldBe true
        target.POINTER_MEM_SIZE shouldBe 8u
        target.FLOAT_MEM_SIZE shouldBe 8u
        target.convertFloatToBytes(1.0) shouldBe listOf<UByte>(0u, 0u, 0u, 0u, 0u, 0u, 0xF0u, 0x3Fu)
        for (value in listOf(0.0, -1.5, Math.PI, Double.MAX_VALUE))
            target.convertBytesToFloat(target.convertFloatToBytes(value)) shouldBe value
    }

    test("llvm tools are searched on the PATH and in Homebrew's keg-only LLVM directory") {
        LlvmToolchain.searchDirs() shouldContain Path.of("/opt/homebrew/opt/llvm/bin")
        LlvmToolchain.find("prog8-no-such-llvm-tool") shouldBe null
    }

    test("IR covering every DOM construct is accepted by llvm-as").config(enabledOrReasonIf = requiresLlvmAs) {
        assertAcceptedByLlvmAs(goldenModule())
        assertAcceptedByLlvmAs(comprehensiveModule())
    }

    test("llvm-as rejects a block without a terminator, so the validation is not vacuous").config(enabledOrReasonIf = requiresLlvmAs) {
        val broken = LlvmFunction("f", LlvmType.Void, emptyList(), mutableListOf(
            LlvmBlock("entry", mutableListOf(LlvmInstruction.Alloca(LlvmValue.Local("p", LlvmType.Ptr), LlvmType.I8)))))
        val (exitCode, _) = runLlvmAs(LlvmPrinter.print(LlvmModule("broken", functions = mutableListOf(broken))))
        exitCode shouldNotBe 0
    }
})


private fun i8(value: Long) = LlvmValue.IntConstant(value, LlvmType.I8)
private fun i16(value: Long) = LlvmValue.IntConstant(value, LlvmType.I16)
private fun i32(value: Long) = LlvmValue.IntConstant(value, LlvmType.I32)
private fun i64(value: Long) = LlvmValue.IntConstant(value, LlvmType.I64)

private val ctorEntryType = LlvmType.Struct(listOf(LlvmType.I32, LlvmType.Ptr, LlvmType.Ptr), packed = false)
private fun ctorEntry(function: String) = LlvmValue.StructConstant(ctorEntryType, listOf(i32(65535), LlvmValue.Global(function), LlvmValue.NullPtr()))

private fun goldenModule(): LlvmModule {
    val target = LlvmTarget()
    val a = LlvmValue.Local("a", LlvmType.I16)
    val sum = LlvmValue.Local("r0", LlvmType.I16)
    val isZero = LlvmValue.Local("iszero", LlvmType.I1)
    val module = LlvmModule("golden", target.targetTriple, target.dataLayout)
    module.headerComments += "generated by prog8"
    module.globals += LlvmGlobal(".str.0", LlvmType.Array(3, LlvmType.I8), LlvmValue.StringConstant("hi\u0000"), isConstant = true,
        linkage = LlvmLinkage.PRIVATE, unnamedAddr = true, align = 1)
    module.globals += LlvmGlobal("main.counter", LlvmType.I16, i16(0), isConstant = false)
    module.functions += LlvmFunction("putchar", LlvmType.I32, listOf(LlvmValue.Local("ch", LlvmType.I32)), isExternal = true)
    module.functions += LlvmFunction("main.start", LlvmType.I16, listOf(a), mutableListOf(
        LlvmBlock("entry", mutableListOf(
            LlvmInstruction.BinaryOp(BinaryOperator.ADD, sum, a, i16(1)),
            LlvmInstruction.Icmp(IcmpCondition.EQ, isZero, sum, i16(0)),
            LlvmInstruction.CondBr(isZero, "zero", "done"))),
        LlvmBlock("zero", mutableListOf(
            LlvmInstruction.Store(sum, LlvmValue.Global("main.counter")),
            LlvmInstruction.Br("done"))),
        LlvmBlock("done", mutableListOf(LlvmInstruction.Ret(sum)))))
    return module
}

private fun comprehensiveModule(): LlvmModule {
    val target = LlvmTarget()
    val module = LlvmModule("comprehensive \"test\"", target.targetTriple, target.dataLayout)
    module.headerComments += "first line\nsecond line"

    val words = LlvmValue.ArrayConstant(LlvmType.I16, listOf(i16(1), i16(65535), i16(-32768)))
    val pair = LlvmType.Array(2, LlvmType.I8)
    val nested = LlvmValue.ArrayConstant(pair, listOf(LlvmValue.ArrayConstant(LlvmType.I8, listOf(i8(1), i8(255))), LlvmValue.ZeroInitializer(pair)))
    val pointers = LlvmValue.ArrayConstant(LlvmType.Ptr, listOf(LlvmValue.Global(".str.0"), LlvmValue.NullPtr()))
    val buffer = LlvmType.Array(8, LlvmType.I8)
    module.globals += listOf(
        LlvmGlobal(".str.0", LlvmType.Array(4, LlvmType.I8), LlvmValue.StringConstant("a\"\\\u0000"), isConstant = true,
            linkage = LlvmLinkage.PRIVATE, unnamedAddr = true, align = 1),
        LlvmGlobal("1 weird name", words.type, words, isConstant = false, linkage = LlvmLinkage.INTERNAL),
        LlvmGlobal("nested", nested.type, nested, isConstant = true),
        LlvmGlobal("empty", LlvmType.Array(0, LlvmType.I16), LlvmValue.ArrayConstant(LlvmType.I16, emptyList()), isConstant = true),
        LlvmGlobal("buffer", buffer, LlvmValue.ZeroInitializer(buffer), isConstant = false, align = 8),
        LlvmGlobal("ext", LlvmType.I32, null, isConstant = false),
        LlvmGlobal("pointers", pointers.type, pointers, isConstant = true),
        LlvmGlobal("fnptr", LlvmType.Ptr, LlvmValue.Global("helper"), isConstant = false),
        LlvmGlobal("flag", LlvmType.I1, LlvmValue.IntConstant(1, LlvmType.I1), isConstant = false),
        LlvmGlobal("small", LlvmType.I8, i8(255), isConstant = false),
        LlvmGlobal("big", LlvmType.I64, i64(Long.MIN_VALUE), isConstant = false),
        LlvmGlobal("nan", LlvmType.Double, LlvmValue.FloatConstant(Double.NaN), isConstant = true),
        LlvmGlobal("tenth", LlvmType.Double, LlvmValue.FloatConstant(0.1), isConstant = true))

    val packed = LlvmType.Struct(listOf(LlvmType.I8, LlvmType.I16, LlvmType.Ptr, LlvmType.Double), packed = true)
    val unpacked = LlvmType.Struct(listOf(LlvmType.I8, LlvmType.I32), packed = false)
    val nodes = LlvmValue.ArrayConstant(packed, listOf(
        LlvmValue.StructConstant(packed, listOf(i8(1), i16(2), LlvmValue.Global("small"), LlvmValue.FloatConstant(1.5))),
        LlvmValue.ZeroInitializer(packed)))
    val nestedRef = LlvmValue.Global("nested")
    module.globals += listOf(
        LlvmGlobal("nodes", nodes.type, nodes, isConstant = false, linkage = LlvmLinkage.INTERNAL),
        LlvmGlobal("pair32", unpacked, LlvmValue.StructConstant(unpacked, listOf(i8(7), i32(-1))), isConstant = true),
        LlvmGlobal("truncated", LlvmType.I32, LlvmValue.ConstantCast(CastOperator.PTRTOINT, LlvmValue.Global("small"), LlvmType.I32), isConstant = false),
        LlvmGlobal("narrowed", LlvmType.I16,
            LlvmValue.ConstantCast(CastOperator.TRUNC, LlvmValue.ConstantCast(CastOperator.PTRTOINT, LlvmValue.Global("small"), LlvmType.I64), LlvmType.I16), isConstant = false),
        LlvmGlobal("absolute", LlvmType.Ptr, LlvmValue.ConstantCast(CastOperator.INTTOPTR, i64(0xd020), LlvmType.Ptr), isConstant = true),
        LlvmGlobal("same", LlvmType.Ptr, LlvmValue.ConstantCast(CastOperator.BITCAST, LlvmValue.Global("small"), LlvmType.Ptr), isConstant = true),
        LlvmGlobal("element", LlvmType.Ptr, LlvmValue.ConstantGep(LlvmType.Array(2, LlvmType.Array(2, LlvmType.I8)), nestedRef, listOf(i64(0), i64(1), i64(1))), isConstant = true),
        LlvmGlobal("byteoffset", LlvmType.Ptr, LlvmValue.ConstantGep(LlvmType.I8, nestedRef, listOf(i64(3)), inBounds = false), isConstant = true),
        LlvmGlobal("zeroword", LlvmType.I16, LlvmValue.ZeroInitializer(LlvmType.I16), isConstant = false),
        LlvmGlobal("zerofloat", LlvmType.Double, LlvmValue.ZeroInitializer(LlvmType.Double), isConstant = false),
        LlvmGlobal("zeroptr", LlvmType.Ptr, LlvmValue.ZeroInitializer(LlvmType.Ptr), isConstant = false),
        LlvmGlobal("zeroflag", LlvmType.I1, LlvmValue.ZeroInitializer(LlvmType.I1), isConstant = false))
    val ctors = LlvmValue.ArrayConstant(ctorEntryType, listOf(ctorEntry("voidfunc")))
    module.globals += LlvmGlobal("llvm.global_ctors", ctors.type, ctors, isConstant = false, linkage = LlvmLinkage.APPENDING)

    val x = LlvmValue.Local("x", LlvmType.I8)
    val widened = LlvmValue.Local("widened", LlvmType.I16)
    module.functions += LlvmFunction("extfunc", LlvmType.I16, listOf(LlvmValue.Local("c", LlvmType.I8), LlvmValue.Local("s", LlvmType.Ptr)), isExternal = true)
    module.functions += LlvmFunction("helper", LlvmType.I16, listOf(x), mutableListOf(
        LlvmBlock("entry", mutableListOf(LlvmInstruction.Cast(CastOperator.ZEXT, widened, x, LlvmType.I16), LlvmInstruction.Ret(widened)))),
        linkage = LlvmLinkage.INTERNAL)
    module.functions += LlvmFunction("voidfunc", LlvmType.Void, emptyList(), mutableListOf(LlvmBlock("entry", mutableListOf(LlvmInstruction.Ret(null)))))
    module.functions += exerciseFunction()
    return module
}

private fun exerciseFunction(): LlvmFunction {
    fun local(name: String, type: LlvmType) = LlvmValue.Local(name, type)
    val a = local("a", LlvmType.I16)
    val b = local("1st", LlvmType.I16)
    val fa = local("fa", LlvmType.Double)
    val p = local("p", LlvmType.Ptr)
    val entry = mutableListOf<LlvmInstruction>()

    val slot = local("slot", LlvmType.Ptr)
    val element = local("element", LlvmType.Ptr)
    val loaded = local("loaded", LlvmType.I16)
    entry += LlvmInstruction.Alloca(slot, LlvmType.Array(4, LlvmType.I16))
    entry += LlvmInstruction.GetElementPtr(element, LlvmType.Array(4, LlvmType.I16), slot, listOf(i64(0), i64(1)))
    entry += LlvmInstruction.Store(a, element, align = 2)
    entry += LlvmInstruction.Load(loaded, element, align = 2)
    entry += LlvmInstruction.Store(i16(7), LlvmValue.Global("1 weird name"))
    entry += LlvmInstruction.Store(i16(8), LlvmValue.ConstantGep(LlvmType.I8, LlvmValue.Global("1 weird name"), listOf(i64(1))), align = 1, volatile = true)
    entry += LlvmInstruction.GetElementPtr(local("raw", LlvmType.Ptr), LlvmType.I8, p, listOf(b), inBounds = false)

    var intChain: LlvmValue = loaded
    for (op in BinaryOperator.entries.filterNot { it.isFloat }) {
        val result = local("int.${op.keyword}", LlvmType.I16)
        entry += LlvmInstruction.BinaryOp(op, result, intChain, b)
        intChain = result
    }
    var floatChain: LlvmValue = fa
    for (op in BinaryOperator.entries.filter { it.isFloat }) {
        val result = local("float.${op.keyword}", LlvmType.Double)
        entry += LlvmInstruction.BinaryOp(op, result, floatChain, LlvmValue.FloatConstant(1.5))
        floatChain = result
    }
    val negated = local("negated", LlvmType.Double)
    entry += LlvmInstruction.FNeg(negated, floatChain)

    val truncated = local("truncated", LlvmType.I8)
    val wide = local("wide", LlvmType.I64)
    val address = local("address", LlvmType.I64)
    val pointer = local("pointer", LlvmType.Ptr)
    entry += LlvmInstruction.Cast(CastOperator.TRUNC, truncated, intChain, LlvmType.I8)
    entry += LlvmInstruction.Cast(CastOperator.ZEXT, local("zext", LlvmType.I32), truncated, LlvmType.I32)
    entry += LlvmInstruction.Cast(CastOperator.SEXT, wide, truncated, LlvmType.I64)
    entry += LlvmInstruction.Cast(CastOperator.SITOFP, local("sitofp", LlvmType.Double), intChain, LlvmType.Double)
    entry += LlvmInstruction.Cast(CastOperator.UITOFP, local("uitofp", LlvmType.Double), wide, LlvmType.Double)
    entry += LlvmInstruction.Cast(CastOperator.FPTOSI, local("fptosi", LlvmType.I32), negated, LlvmType.I32)
    entry += LlvmInstruction.Cast(CastOperator.FPTOUI, local("fptoui", LlvmType.I16), negated, LlvmType.I16)
    entry += LlvmInstruction.Cast(CastOperator.PTRTOINT, address, p, LlvmType.I64)
    entry += LlvmInstruction.Cast(CastOperator.INTTOPTR, pointer, address, LlvmType.Ptr)
    entry += LlvmInstruction.Cast(CastOperator.BITCAST, local("bitcast", LlvmType.Ptr), pointer, LlvmType.Ptr)

    for (cond in IcmpCondition.entries)
        entry += LlvmInstruction.Icmp(cond, local("icmp.${cond.keyword}", LlvmType.I1), a, b)
    entry += LlvmInstruction.Icmp(IcmpCondition.NE, local("icmp.ptr", LlvmType.I1), pointer, LlvmValue.NullPtr())
    for (cond in FcmpCondition.entries)
        entry += LlvmInstruction.Fcmp(cond, local("fcmp.${cond.keyword}", LlvmType.I1), fa, LlvmValue.FloatConstant(Double.POSITIVE_INFINITY))

    val direct = local("direct", LlvmType.I16)
    val callee = local("callee", LlvmType.Ptr)
    val indirect = local("indirect", LlvmType.I16)
    val selected = local("selected", LlvmType.I16)
    entry += LlvmInstruction.Call(direct, "extfunc", listOf(truncated, LlvmValue.Global(".str.0")), LlvmType.I16)
    entry += LlvmInstruction.Load(callee, LlvmValue.Global("fnptr"))
    entry += LlvmInstruction.Call(indirect, callee, listOf(i8(1)), LlvmType.I16)
    entry += LlvmInstruction.Call(null, "extfunc", listOf(i8(0), LlvmValue.NullPtr()), LlvmType.I16)
    entry += LlvmInstruction.Call(null, "voidfunc", emptyList(), LlvmType.Void)
    entry += LlvmInstruction.Select(selected, local("icmp.ult", LlvmType.I1), direct, indirect)
    entry += LlvmInstruction.CondBr(local("icmp.eq", LlvmType.I1), "1then", "else")

    val merged = local("merged", LlvmType.I16)
    return LlvmFunction("0main", LlvmType.I16, listOf(a, b, fa, p), mutableListOf(
        LlvmBlock("entry", entry),
        LlvmBlock("1then", mutableListOf(LlvmInstruction.Br("join"))),
        LlvmBlock("else", mutableListOf(LlvmInstruction.Br("join"))),
        LlvmBlock("join", mutableListOf(LlvmInstruction.Phi(merged, listOf(selected to "1then", i16(0) to "else")), LlvmInstruction.Ret(merged))),
        LlvmBlock("dead", mutableListOf(LlvmInstruction.Unreachable))))
}
