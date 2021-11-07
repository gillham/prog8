package prog8tests

import io.kotest.core.spec.style.FunSpec
import prog8.ast.internedStringsModuleName
import prog8.compiler.determineCompilationOptions
import prog8.compiler.parseImports
import prog8.compiler.target.C64Target
import prog8.compilerinterface.ZeropageType
import prog8tests.ast.helpers.outputDir
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.assertSuccess
import prog8tests.helpers.compileText
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class TestImportedModulesOrderAndOptions: FunSpec({

    test("testImportedModuleOrderAndMainModuleCorrect") {
        val result = compileText(C64Target, false, """
%import textio
%import floats

main {
    sub start() {
        ; nothing
    }
}
""").assertSuccess()
        assertTrue(result.program.toplevelModule.name.startsWith("on_the_fly_test"))

        val moduleNames = result.program.modules.map { it.name }
        assertTrue(moduleNames[0].startsWith("on_the_fly_test"), "main module must be first")
        assertEquals(listOf(
            "prog8_interned_strings",
            "textio",
            "syslib",
            "conv",
            "floats",
            "math",
            "prog8_lib"
        ), moduleNames.drop(1), "module order in parse tree")

        assertTrue(result.program.toplevelModule.name.startsWith("on_the_fly_test"))
    }

    test("testCompilationOptionsCorrectFromMain") {
        val result = compileText(C64Target, false, """
%import textio
%import floats
%zeropage dontuse
%option no_sysinit

main {
    sub start() {
        ; nothing
    }
}
""").assertSuccess()
        assertTrue(result.program.toplevelModule.name.startsWith("on_the_fly_test"))
        val options = determineCompilationOptions(result.program, C64Target)
        assertTrue(options.floats)
        assertEquals(ZeropageType.DONTUSE, options.zeropage)
        assertTrue(options.noSysInit)
    }

    test("testModuleOrderAndCompilationOptionsCorrectWithJustImports") {
        val errors = ErrorReporterForTests()
        val sourceText = """
%import textio
%import floats
%option no_sysinit
%zeropage dontuse            

main {
    sub start() {
        ; nothing
    }
}
"""
        val filenameBase = "on_the_fly_test_" + sourceText.hashCode().toUInt().toString(16)
        val filepath = outputDir.resolve("$filenameBase.p8")
        filepath.toFile().writeText(sourceText)
        val (program, options, importedfiles) = parseImports(filepath, errors, C64Target, emptyList())

        assertEquals(filenameBase, program.toplevelModule.name)
        assertEquals(1, importedfiles.size, "all imports other than the test source must have been internal resources library files")
        assertEquals(listOf(
            internedStringsModuleName,
            filenameBase,
            "textio", "syslib", "conv", "floats", "math", "prog8_lib"
        ), program.modules.map {it.name}, "module order in parse tree")
        assertTrue(options.floats)
        assertEquals(ZeropageType.DONTUSE, options.zeropage, "zeropage option must be correctly taken from main module, not from float module import logic")
        assertTrue(options.noSysInit)
    }

})
