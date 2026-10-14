package prog8.codegen.llvm

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import prog8.code.core.*
import prog8.code.target.LlvmTarget
import prog8.codegen.llvm.lower.LlvmFunctionBuilder
import prog8.codegen.llvm.lower.LlvmGlobalEmitter
import prog8.codegen.llvm.model.*
import prog8.intermediate.*


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
