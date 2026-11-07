package prog8tests.compiler

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import prog8.code.INTERNED_STRINGS_MODULENAME
import prog8.code.core.ZeropageType
import prog8.code.target.C128Target
import prog8.code.target.C64Target
import prog8.code.target.Cx16Target
import prog8.code.target.VMTarget
import prog8.compiler.determineCompilationOptions
import prog8.compiler.parseMainModule
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.compileFile
import prog8tests.helpers.compileText
import java.nio.file.Path


class TestImportedModulesOrderAndOptions: FunSpec({

    val outputDir = tempdir().toPath()

    test("testImportedModuleOrderAndMainModuleCorrect") {
        val result = compileText(
            C64Target(), false, """
%import textio
%import floats

main {
    sub start() {
        ; nothing
    }
}
""", outputDir)!!
        result.compilerAst.toplevelModule.name shouldStartWith "on_the_fly_test"

        val moduleNames = result.compilerAst.modules.map { it.name }
        withClue("main module must be second (after container modules)") {
            moduleNames[1] shouldStartWith "on_the_fly_test"
        }
        withClue("module order in parse tree") {
            moduleNames.drop(2) shouldBe listOf(
                "textio",
                "syslib",
                "shared_sys_functions",
                "conv",
                "shared_cbm_textio_functions",
                "floats",
                "shared_floats_functions",
                "prog8_math",
                "prog8_lib"
            )
        }
        result.compilerAst.toplevelModule.name shouldStartWith "on_the_fly_test"
    }

    test("testCompilationOptionsCorrectFromMain") {
        val result = compileText(
            C64Target(), false, """
%import textio
%import floats
%zeropage dontuse
%option no_sysinit

main {
    sub start() {
        ; nothing
    }
}
""", outputDir)!!
        result.compilerAst.toplevelModule.name shouldStartWith "on_the_fly_test"
        val options = determineCompilationOptions(result.compilerAst, C64Target())
        options.floats shouldBe true
        options.zeropage shouldBe ZeropageType.DONTUSE
        options.noSysInit shouldBe true
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
        val filenameBase = "on_the_fly_test_${sourceText.hashCode().toUInt().toString(16)}"

        val filepath = outputDir.resolve("$filenameBase.p8")
        filepath.toFile().writeText(sourceText)
        val (program, options, importedfiles) = parseMainModule(filepath, errors, C64Target(), emptyList(), emptyList(), Path.of("."), false, false)

        program.toplevelModule.name shouldBe filenameBase
        withClue("all imports other than the test source must have been internal resources library files") {
            importedfiles.size shouldBe 1
        }
        withClue("module order in parse tree") {
            program.modules.map { it.name } shouldBe
                listOf(
                    INTERNED_STRINGS_MODULENAME,
                    filenameBase,
                    "textio", "syslib", "shared_sys_functions", "conv", "shared_cbm_textio_functions", "floats", "shared_floats_functions", "prog8_math", "prog8_lib"
                )
        }
        options.floats shouldBe true
        options.noSysInit shouldBe true
        withClue("zeropage option must be correctly taken from main module, not from float module import logic") {
            options.zeropage shouldBe ZeropageType.DONTUSE
        }
    }

    test("merge option works on library modules") {
        val src="""
%zeropage basicsafe
%import textio

txt {
    %option merge
    sub println(uword string) {
        txt.print(string)
        txt.nl()
    }
}

main {
    sub start() {
        txt.lowercase()
        txt.println("Hello, world1")
        txt.println("Hello, world2")
        txt.println("Hello, world3")
    }
}"""
        compileText(VMTarget(), optimize = false, src, outputDir) shouldNotBe null
    }

    test("double merge is invalid") {
        val src="""
main {

    sub start() {
        block1.sub1()
        block1.sub2()
    }
}

block1 {
    %option merge

    sub sub1() {
        cx16.r1++
    }
}


block1 {
    %option merge

    sub sub2() {
        cx16.r2++
    }
}"""
        val errors=ErrorReporterForTests()
        compileText(VMTarget(), optimize = false, src, outputDir, errors=errors) shouldBe null
        errors.errors.size shouldBe 1
        errors.errors[0] shouldContain "all declarations of block 'block1' have %option merge"
    }

    test("merge works") {
        val src = """
%import textio

main {

sub start() {
    blah.test()
}
}

txt {
; merges this block into the txt block coming from the textio library
%option merge

sub schrijf(str arg) {
    print(arg)
}
}

blah {
; merges this block into the other 'blah' one
%option merge

sub test() {
    printit("test merge")
}
}

blah {
sub printit(str arg) {
    txt.schrijf(arg)
}
}"""
        compileText(C64Target(), optimize=false, src, outputDir, writeAssembly=false) shouldNotBe null
    }

    test("merge works with charset encoding") {
        val src = """
main {
    sub start() {
        cx16.r0L = KEY_0
        cx16.r1L = KEY_9
        when cx16.r0L {
            KEY_0 to KEY_9 -> {}
            else -> {}
        }
    }
}

main {
    %option merge

    const ubyte KEY_0 = 'A'
    const ubyte KEY_9 = 'B'
}"""
        compileText(C64Target(), optimize=false, src, outputDir, writeAssembly=false) shouldNotBe null
    }

    test("merge override existing subroutine") {
        val src="""
%import textio

main {

sub start() {
    txt.print("sdfdsf")
}
}

txt {
%option merge

sub print(str text) {
    cx16.r0++
    ; just some dummy implementation to replace existing print
}
}"""

        val result = compileText(VMTarget(), optimize=false, src, outputDir, writeAssembly=false)
        result shouldNotBe null
    }

    test("merge doesn't override existing subroutine if signature differs") {
        val src="""
%import textio

main {

sub start() {
    txt.print("sdfdsf")
}
}

txt {
%option merge

sub print(str anotherparamname) {
    cx16.r0++
    ; just some dummy implementation to replace existing print
}
}"""
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), optimize=false, src, outputDir, writeAssembly=false, errors = errors) shouldBe null
        errors.errors.size shouldBe 1
        errors.errors[0] shouldContain "name conflict"
    }

    test("merge of float stuff into sys and txt - import order 1") {
        val src="""
%import textio
%import floats

main {
sub start() {
    txt.print_b(sys.MIN_BYTE)
    txt.print_b(sys.MAX_BYTE)
    txt.print_ub(sys.MIN_UBYTE)
    txt.print_ub(sys.MAX_UBYTE)
    txt.print_w(sys.MIN_WORD)
    txt.print_w(sys.MAX_WORD)
    txt.print_uw(sys.MIN_UWORD)
    txt.print_uw(sys.MAX_UWORD)

    txt.print_f(floats.EPSILON)
    txt.print_f(sys.MIN_FLOAT)
    txt.print_f(sys.MAX_FLOAT)
    txt.print_f(floats.E)
    txt.print_ub(sys.SIZEOF_FLOAT)
}
}"""

        compileText(VMTarget(), optimize=false, src, outputDir, writeAssembly=false) shouldNotBe null
        compileText(Cx16Target(), optimize=false, src, outputDir, writeAssembly=false) shouldNotBe null
    }

    test("merge of float stuff into sys and txt - import order 2") {
        val src="""
%import floats
%import textio

main {
sub start() {
    txt.print_b(sys.MIN_BYTE)
    txt.print_b(sys.MAX_BYTE)
    txt.print_ub(sys.MIN_UBYTE)
    txt.print_ub(sys.MAX_UBYTE)
    txt.print_w(sys.MIN_WORD)
    txt.print_w(sys.MAX_WORD)
    txt.print_uw(sys.MIN_UWORD)
    txt.print_uw(sys.MAX_UWORD)

    txt.print_f(floats.EPSILON)
    txt.print_f(sys.MIN_FLOAT)
    txt.print_f(sys.MAX_FLOAT)
    txt.print_f(floats.E)
    txt.print_ub(sys.SIZEOF_FLOAT)
}
}"""

        compileText(VMTarget(), optimize=false, src, outputDir, writeAssembly=false) shouldNotBe null
        compileText(Cx16Target(), optimize=false, src, outputDir, writeAssembly=false) shouldNotBe null
    }

    test("force_output keeps a const-only block in a module of its own") {
        val settingsBlock = """
settings {
    %option force_output
    const ubyte LEVEL = 7
    %asm {{
LEVEL = 7
    }}
}
"""
        val mainBlock = """
main {
    sub start() {
        @($0400) = level()
    }

    asmsub level() -> ubyte @A {
        %asm {{
            lda  #p8b_settings.LEVEL
            rts
        }}
    }
}
"""
        val dir = outputDir.resolve("force_output_module")
        dir.toFile().mkdirs()
        dir.resolve("fo_settings_imported.p8").toFile().writeText(settingsBlock)
        dir.resolve("fo_settings_main.p8").toFile().writeText("%import fo_settings_imported\n%zeropage basicsafe\n$mainBlock")
        dir.resolve("fo_main_imported.p8").toFile().writeText(mainBlock)
        dir.resolve("fo_main_main.p8").toFile().writeText("%import fo_main_imported\n%zeropage basicsafe\n$settingsBlock")
        for(file in listOf("fo_settings_main.p8", "fo_main_main.p8")) {
            for(optimize in listOf(false, true)) {
                withClue("$file optimize=$optimize") {
                    compileFile(C64Target(), optimize, dir, file, outputDir) shouldNotBe null
                }
            }
        }
    }

    test("zpreserved and zpallowed in an imported module give a warning") {
        val dir = outputDir.resolve("zp_directives_module")
        dir.toFile().mkdirs()
        dir.resolve("zpd_imported.p8").toFile().writeText($$"""
%zpreserved $92,$a4
%zpallowed $10,$20
platform {
    sub init() {
        @($0400) = 1
    }
}""")
        dir.resolve("zpd_main.p8").toFile().writeText("""
%import zpd_imported
%zeropage basicsafe
main {
    sub start() {
        platform.init()
    }
}""")
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileFile(C128Target(), false, dir, "zpd_main.p8", outputDir, errors = errors, writeAssembly = false) shouldNotBe null
        val warnings = errors.warnings.filter { "only applies in the main module" in it }
        warnings.size shouldBe 2
        warnings.count { "%zpreserved" in it } shouldBe 1
        warnings.count { "%zpallowed" in it } shouldBe 1
    }
})
