package prog8.codegen.llvm

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import prog8.code.core.*
import prog8.code.target.LlvmTarget
import prog8.codegen.llvm.lower.LlvmFunctionBuilder
import prog8.codegen.llvm.lower.LlvmGlobalEmitter
import prog8.codegen.llvm.model.*
import prog8.intermediate.*
import kotlin.math.abs


/** A test program for the llvm64 target, with subroutines written as IR text. */
internal class TestProgram {
    val target = LlvmTarget()
    private val st = IRSymbolTable()
    private val program = IRProgram("test", st, CompilationOptions.builder(target).floats(true).compilerVersion("99.99").build(), target)
    private val block = IRBlock("main", false, IRBlock.Options(), Position.DUMMY).also { program.addBlock(it) }

    /** A [shared] variable is external, so that LLVM can't fold its value into the code that reads it. */
    fun variable(name: String, dt: DataType, init: IRVariableInitializer? = null, length: UInt? = null, shared: Boolean = false) =
        st.add(IRStStaticVariable(name, dt, init, length, ZeropageWish.DONTCARE, 0u, false, shared = shared))

    fun string(name: String, text: String) =
        st.add(IRStStaticVariable(name, DataType.STR, IRVariableInitializer.Str(text, Encoding.ISO), (text.length + 1).toUInt(), ZeropageWish.DONTCARE, 0u, false))

    /** `_label:` lines start a new chunk; `loop <label> <trip>` and `endloop` put the lines in between into an IR loop chunk. */
    fun sub(name: String, params: List<Pair<String, DataType>> = emptyList(), returns: List<DataType> = emptyList(), code: String) {
        val parameters = params.map { (param, dt) -> IRSubroutine.IRParam("$name.$param", dt).also { variable(it.name, dt) } }
        block += IRSubroutine(name, parameters, returns, Position.DUMMY).apply { parseChunks(code).forEach { this += it } }
    }

    fun asmsub(asmsub: IRAsmSubroutine) {
        block += asmsub
    }

    fun subroutine(name: String): IRSubroutine = program.allSubs().single { it.label == name }

    fun module(): LlvmModule {
        val module = LlvmModule("test", target.targetTriple, target.dataLayout)
        LlvmFunctionBuilder(program, LlvmGlobalEmitter(program).emit(module)).build(module)
        return module
    }

    /** Like [module], with the program's own C entry point, which runs main.start. */
    fun programModule(): LlvmModule {
        val module = LlvmModule("test", target.targetTriple, target.dataLayout)
        LlvmFunctionBuilder(program, LlvmGlobalEmitter(program).emit(module)).buildProgram(module)
        return module
    }
}

/** Adds a C `main` to the module that returns what the subroutine [check] returns: 0, or the number of the check that failed. */
internal fun LlvmModule.withMainCalling(check: String): LlvmModule {
    val result = LlvmValue.Local("result", LlvmType.I8)
    val code = LlvmValue.Local("code", LlvmType.I32)
    functions += LlvmFunction("main", LlvmType.I32, emptyList(), mutableListOf(LlvmBlock("entry", mutableListOf(
        LlvmInstruction.Call(result, check, emptyList(), LlvmType.I8),
        LlvmInstruction.Cast(CastOperator.ZEXT, code, result, LlvmType.I32),
        LlvmInstruction.Ret(code)))))
    return this
}

internal fun LlvmModule.function(name: String): LlvmFunction = functions.single { it.name == name }

internal fun LlvmFunction.text(): String = LlvmPrinter.print(LlvmModule("f", functions = mutableListOf(this))).substringAfter("source_filename = \"f\"\n\n")

internal fun LlvmFunction.lines(): List<String> = text().lines()

internal infix fun List<String>.shouldHave(line: String) = withClue(line) { contains(line) shouldBe true }

internal sealed interface Expect
internal data class Is(val register: String, val value: Long) : Expect
/** Exactly [value] when [tolerance] is 0, and never NaN. */
internal data class IsFloat(val register: String, val value: Double, val tolerance: Double = 0.0) : Expect
internal data class CarryIs(val set: Boolean) : Expect
internal data class ZeroIs(val set: Boolean) : Expect

/**
 * Builds main.check, which returns 0, or the number of the first check whose results differ from the VM's.
 * A check is IR lines separated by "; ", using the registers ra..rh and fra..frh, which get their own numbers in every check.
 */
internal class Checks {
    private val code = mutableListOf<String>()
    private var count = 0

    fun check(ir: String, vararg expected: Expect) {
        val number = ++count
        val fail = "main.check.fail$number"
        fun numbered(text: String) = registerLetters.replace(text) { "${it.groupValues[1]}r${number * 10 + (it.groupValues[2][0] - 'a')}" }
        code += ir.split("; ").map { numbered(it.trim()) }
        for (expect in expected) {
            when (expect) {
                is Is -> {
                    val type = expect.register.substringAfterLast('.')
                    code += "cmpi.$type ${numbered(expect.register)},#${expect.value}.$type"
                    code += "bstne $fail"
                }
                is IsFloat -> {
                    val register = numbered(expect.register)
                    if (expect.tolerance == 0.0) {
                        code += compareFloat(register, expect.value, 0, fail)
                        code += compareFloat(register, expect.value - maxOf(1.0, abs(expect.value)), 1, fail)
                    } else {
                        code += compareFloat(register, expect.value - expect.tolerance, 1, fail)
                        code += compareFloat(register, expect.value + expect.tolerance, -1, fail)
                    }
                }
                is CarryIs -> code += if (expect.set) "bstcc $fail" else "bstcs $fail"
                is ZeroIs -> code += if (expect.set) "bstne $fail" else "bsteq $fail"
            }
        }
    }

    // FCOMP gives 0 for NaN as well, which a comparison with a lower bound catches
    private fun compareFloat(register: String, bound: Double, order: Int, fail: String) =
        listOf("load.f fr9000.f,#$bound.f", "fcomp.f r9000.b,$register,fr9000.f", "cmpi.b r9000.b,#$order.b", "bstne $fail")

    fun subroutine(): String = buildString {
        appendLine("_main.check:")
        code.forEach { appendLine(it) }
        appendLine("returni.b #0.b")
        for (number in 1..count) {
            appendLine("_main.check.fail$number:")
            appendLine("returni.b #$number.b")
        }
    }
}

private val registerLetters = Regex("""\b(f?)r([a-h])(?=\.)""")

private fun parseChunks(code: String): List<IRCodeChunkBase> {
    val chunks = mutableListOf<IRCodeChunkBase>()
    var target = chunks
    var chunk = IRCodeChunk(null, null)
    fun flush() {
        if (chunk.isNotEmpty() || chunk.label != null)
            target += chunk
        chunk = IRCodeChunk(null, null)
    }
    for (line in code.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
        when {
            line.startsWith("loop ") -> {
                flush()
                val (label, trip) = line.removePrefix("loop ").split(' ')
                val loop = IRLoopChunk(label, trip.toInt(), mutableListOf())
                target += loop
                target = loop.body
            }
            line == "endloop" -> {
                flush()
                target = chunks
            }
            else -> when (val parsed = parseIRCodeLine(line)) {
                is ParsedIRLine.Label -> {
                    flush()
                    chunk = IRCodeChunk(parsed.name, null)
                }
                is ParsedIRLine.Instruction -> chunk += parsed.value
            }
        }
    }
    flush()
    return chunks
}
