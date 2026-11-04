package prog8.codegen.llvm

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import prog8.code.target.LlvmTripleProfile
import prog8.codegen.llvm.lower.Libc
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.walk


class TestLlvmCFunctionNames: FunSpec({

    test("every C function that the backend declares is a name that libc_lacks accepts") {
        val sources = listOf(Path("src"), Path("codeGenLlvm", "src")).first { it.resolve("prog8/codegen/llvm").exists() }
        val text = sources.walk().filter { it.toString().endsWith(".kt") }.joinToString("\n") { it.readText() }
        val literal = Regex("""\b(external|externalFunction)\("([A-Za-z_][A-Za-z0-9_.]*)"""")
        val libm = Regex("""\b(libmName|floatIntrinsic)\("([A-Za-z_][A-Za-z0-9_]*)"""")
        val declared = literal.findAll(text).map { it.groupValues[2] }.filterNot { it.startsWith("llvm.") }.toSet() +
            libm.findAll(text).flatMap { listOf(it.groupValues[2], it.groupValues[2] + "f") } +
            Libc.entries.map { it.name.lowercase() } + listOf("opendir", "readdir")
        declared shouldContainAll listOf("putchar", "getchar", "exit", "usleep", "strtod", "snprintf", "printf", "fopen", "tan", "atan2f")
        (declared - LlvmTripleProfile.C_FUNCTIONS).shouldBeEmpty()
    }
})
