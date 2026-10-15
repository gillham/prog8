package prog8tests.ast

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import prog8.ast.expressions.NumericLiteral
import prog8.ast.statements.Assignment
import prog8.ast.statements.VarDecl
import prog8.code.core.BaseDataType
import prog8.code.core.DataType
import prog8.code.target.C64Target
import prog8.code.target.Cx16Target
import prog8.code.target.LlvmTarget
import prog8.code.target.Qemu68kTarget
import prog8.code.target.VMTarget
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.compileText


class TestAstChecks: FunSpec({

    val outputDir = tempdir().toPath()
    
    test("conditional expression w/float works") {
        val text = """
            %import floats
            main {
                sub start() {
                    uword xx
                    if xx+99.99 == xx+1.234 {
                        xx++
                    }
                }
            }
        """
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(C64Target(), true, text, outputDir, writeAssembly = true, errors=errors) shouldNotBe null
        errors.errors.size shouldBe 0
        errors.infos.size shouldBe 2
        errors.infos[0] shouldContain "converted to float"
        errors.infos[1] shouldContain "converted to float"
    }

    test("can't assign label or subroutine without using address-of") {
        val text = """
            main {
                sub start() {
            
            label:
                    uword @shared addr
                    addr = label
                    addr = thing
                    addr = &label
                    addr = &thing
                }
            
                sub thing() {
                }
            }
            """
        val errors = ErrorReporterForTests()
        compileText(C64Target(), true, text, outputDir, writeAssembly = true, errors=errors) shouldBe null
        errors.errors.size shouldBe 2
        errors.warnings.size shouldBe 0
        errors.errors[0] shouldContain ":7:28: invalid assignment value"
        errors.errors[1] shouldContain ":8:28: invalid assignment value"
    }

    test("can't do str or array expression without using address-of") {
        val text = """
            main {
                sub start() {
                    ubyte[] array = [1,2,3,4]
                    str s1 = "test"
                    ubyte ff = 1
                    print(s1+ff)
                    print(array+ff)
                    print_uwhex(s1+ff, true)
                    print_uwhex(array+ff, true)
                }
                
                sub print(str s) { cx16.r0++ }
                sub print_uwhex(uword w, bool prefix) { cx16.r0++ }
            }
            """
        val errors = ErrorReporterForTests()
        compileText(C64Target(), false, text, outputDir, writeAssembly = false, errors=errors) shouldBe null
        errors.errors.filter { it.contains("missing &") }.size shouldBe 4
    }

    test("str or array expression with address-of") {
        val text = """
            main {
                sub start() {
                    ubyte[] array = [1,2,3,4]
                    str s1 = "test"
                    bool bb1, bb2
                    ubyte ff = 1
                    print(&s1+ff)
                    print(&array+ff)
                    print_uwhex(&s1+ff, true)
                    print_uwhex(&array+ff, true)
                    ; also good:
                    bb1 = (s1 == "derp")
                    bb2 = (s1 != "derp")
                }

                sub print(str s) { cx16.r0++ }
                sub print_uwhex(uword w, bool prefix) { cx16.r0++ }
            }
            """
        compileText(C64Target(), false, text, outputDir, writeAssembly = false) shouldNotBe null
    }

    test("const is not allowed on arrays") {
        val text = """
            main {
                sub start() {
                    const ubyte[5] a = [1,2,3,4,5]
                    a[2]=42
                }
            }
        """
        compileText(C64Target(), true, text, outputDir, writeAssembly = false) shouldBe null
    }

    test("array indexing is not allowed on a memory mapped variable") {
        val text = """
            main {
                sub start() {
                    &ubyte a = 10000
                    cx16.r0L = a[4]
                    a[4] = cx16.r1L
                }
            }
        """
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(C64Target(), true, text, outputDir, writeAssembly = true, errors=errors)
        errors.errors.size shouldBe 2
        errors.warnings.size shouldBe 0
        errors.errors[0] shouldContain "indexing requires"
        errors.errors[1] shouldContain "indexing requires"
    }

    test("unicode in identifier names is working") {
        val text = """
%import floats

main {
    ubyte приблизительно = 99
    ubyte นี่คือตัวอักษรภาษาไท = 42
    
    sub start() {
        str knäckebröd = "crunchy"  ; with composed form
        prt(knäckebröd)             ; with decomposed form
        printf(2*floats.π)
    }

    sub prt(str message) {
        приблизительно++
    }

    sub printf(float fl) {
        นี่คือตัวอักษรภาษาไท++
    }
}"""
        compileText(C64Target(), false, text, outputDir, writeAssembly = true)  shouldNotBe null
        compileText(Cx16Target(), false, text, outputDir, writeAssembly = true)  shouldNotBe null
        compileText(VMTarget(), false, text, outputDir, writeAssembly = true)  shouldNotBe null
    }

    test("memory-mapped single pointer is allowed") {
        val text = """
            main {
                &^^ubyte p1 = 4000
                sub start() {
                    p1^^ = 10
                }
            }
        """
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), true, text, outputDir, writeAssembly = false, errors=errors) shouldNotBe null
    }

    test("memory-mapped pointer arrays are disallowed") {
        val text = """
            main {
                &^^ubyte[2] arr = $1000
                sub start() {
                    arr[0]^^ = 10
                }
            }
        """
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), true, text, outputDir, writeAssembly = false, errors=errors) shouldBe null
        errors.errors.size shouldBe 1
        errors.errors[0] shouldContain "pointer arrays cannot be memory-mapped"
    }

    test("return with a statement instead of a value is a syntax error") {
        val src="""
main {

    sub invalid() {
        return cx16.r0++
    }

    sub start() {
        invalid()
    }
}"""
        val errors=ErrorReporterForTests()
        compileText(C64Target(), false, src, outputDir, writeAssembly = false, errors=errors)  shouldBe null
        errors.errors.size shouldBe 1
        errors.errors[0] shouldContain "statement"
    }

    test("redefined variable name in single declaration is reported") {
        val src="""
main {
    sub start() {
        const ubyte count=11
        cx16.r0++
        ubyte count = 88        ; redefinition
        cx16.r0 = count
    }
}"""
        val errors=ErrorReporterForTests()
        compileText(C64Target(), false, src, outputDir, writeAssembly = false, errors=errors)  shouldBe null
        errors.errors.size shouldBe 1
        errors.errors[0] shouldContain "name conflict"

        errors.clear()
        compileText(C64Target(), true, src, outputDir, writeAssembly = false, errors=errors)  shouldBe null
        errors.errors.size shouldBe 1
        errors.errors[0] shouldContain "name conflict"
    }

    test("redefined variable name in multi declaration is reported") {
        val src="""
main {
    sub start() {
        ubyte i
        i++
        ubyte i, j              ; redefinition
        i++
        j++
    }
}
"""
        val errors=ErrorReporterForTests()
        compileText(C64Target(), false, src, outputDir, writeAssembly = false, errors=errors)  shouldBe null
        errors.errors.size shouldBe 1
        errors.errors[0] shouldContain "name conflict"

        errors.clear()
        compileText(C64Target(), true, src, outputDir, writeAssembly = false, errors=errors)  shouldBe null
        errors.errors.size shouldBe 1
        errors.errors[0] shouldContain "name conflict"
    }

    test("various range datatype checks allow differences in type") {
        val src="""
main {
    sub func() -> ubyte {
        cx16.r0++
        return cx16.r0L
    }

    sub start() {
        bool[256] @shared cells
        word starw
        byte bb
        uword uw
        ubyte ub

        starw = (240-64 as word) + func()

        for starw in 50 downto 10  {
            cx16.r0++
        }
        for starw in cx16.r0L downto 10  {
            cx16.r0++
        }

        for ub in 0 to len(cells)-1 {
            cx16.r0++
        }
        for ub in cx16.r0L to len(cells)-1 {
            cx16.r0++
        }
        for bb in 50 downto 10  {
            cx16.r0++
        }
        for bb in cx16.r0sL downto 10  {
            cx16.r0++
        }

        for starw in 500 downto 10  {
            cx16.r0++
        }
        for uw in 50 downto 10 {
            cx16.r0++
        }
        for uw in 500 downto 10 {
            cx16.r0++
        }
    }
}"""
        compileText(C64Target(), false, src, outputDir, writeAssembly = false) shouldNotBe null
        compileText(C64Target(), true, src, outputDir, writeAssembly = false) shouldNotBe null
    }

    test("typed for loop with untyped existing variable is ok") {
        val src = """
main {
    sub start() {
        ubyte w
        for w in "derp" {
        }
    }
}
"""
        compileText(C64Target(), false, src, outputDir, writeAssembly = false) shouldNotBe null
    }

    test("typed for loop allows widening iterable elements") {
        val src = """
main {
    sub start() {
        ubyte[] values = [1, 2, 3]
        for word w in values {
        }
        for long l in values {
        }
    }
}
"""
        compileText(C64Target(), false, src, outputDir, writeAssembly = false) shouldNotBe null
    }

    test("reg params cannot be statusflag") {
        val src="""
main {
    sub start() {
        faulty(false)
    }

    sub faulty(bool flag @Pc) {
        cx16.r0++
    }
}"""

        // the syntax error is actually thrown by the parser, so we cannot catch it, but we know that there may not be a compilation result
        compileText(C64Target(), false, src, outputDir, writeAssembly = false) shouldBe null
    }

    test("reg params cannot be cpu register") {
        val src="""
main {
    sub start() {
        faulty(42)
    }

    sub faulty(byte arg @Y) {
        arg++
    }
}"""

        val errors = ErrorReporterForTests()
        compileText(C64Target(), false, src, outputDir, writeAssembly = false, errors = errors) shouldBe null
        errors.errors.size shouldBe 1
        errors.errors[0] shouldContain "can only use R0-R15"
    }

    test("reg params must be all different") {
        val src="""
main {
    sub start() {
        faulty3(9999,55)
    }

    sub faulty3(uword arg @R1, ubyte arg2 @R1) {
        arg += arg2
    }
}"""

        val errors = ErrorReporterForTests()
        compileText(C64Target(), false, src, outputDir, writeAssembly = false, errors = errors) shouldBe null
        errors.errors.size shouldBe 1
        errors.errors[0] shouldContain "register is used multiple times"
    }

    test("reg params R0-R15 are ok") {
        val src="""
main {
    sub start() {
        foo(42)
        bar(9999,55)
        bar2(9999999)
    }

    sub foo(ubyte arg @R2) {
        arg++
    }

    sub bar(uword arg @R0, ubyte arg2 @R1) {
        arg += arg2
    }
    
    sub bar2(long arg @R14R15) {
        arg++
    }
}"""

        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(C64Target(), false, src, outputDir, writeAssembly = true, errors = errors) shouldNotBe null
        errors.errors.size shouldBe 0
        errors.warnings.size shouldBe 3
        errors.warnings[0] shouldContain "footgun"
        errors.warnings[1] shouldContain "footgun"
        errors.warnings[2] shouldContain "footgun"

        errors.clear()
        compileText(VMTarget(), false, src, outputDir, writeAssembly = true, errors = errors) shouldNotBe null
        errors.errors.size shouldBe 0
        errors.warnings.size shouldBe 3
        errors.warnings[0] shouldContain "footgun"
        errors.warnings[1] shouldContain "footgun"
        errors.warnings[2] shouldContain "footgun"
    }

    test("reg params R0-R15 cannot be used for invalid types") {
        val src="""
main {
    sub func(str value @R1) {
        return
    }

    sub start() {
        func(true)
    }
}"""
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(C64Target(), false, src, outputDir, writeAssembly = false, errors = errors) shouldBe null
        errors.errors.size shouldBe 1
        errors.warnings.size shouldBe 0
        errors.errors[0] shouldContain "requires integer or boolean type"
    }

    test("missing address of in expression operand") {
        val src="""
main {
    sub start() {
        str name = "foo"
        cx16.r0 =  name+2
    }
}"""
        val errors = ErrorReporterForTests()
        compileText(C64Target(), false, src, outputDir, writeAssembly = false, errors = errors) shouldBe null
        errors.errors.size shouldBe 1
        errors.warnings.size shouldBe 0
        errors.errors[0] shouldContain "missing &"
    }

    test("can't use uword[] as a parameter type give clear error") {
        val src="""
main {
    sub start() {
        uword[] @nosplit array = [1111,2222]
        funcw1(array)
        funcw1([1111,2222])
        funcw2(array)
        funcw2([1111,2222])
        funcb([11,22])
    }

    sub funcw1(uword[] ptr) {
        ; error
    }

    sub funcw2(uword ptr) {
        ; ok
    }

    sub funcb(ubyte[] ptr) {
        ; ok
    }
}
"""
        val errors = ErrorReporterForTests()
        compileText(C64Target(), false, src, outputDir, writeAssembly = false, errors = errors) shouldBe null
        errors.errors.size shouldBe 3
        errors.warnings.size shouldBe 0
        errors.errors[0] shouldContain  ":5:16: argument 1 type mismatch"
        errors.errors[1] shouldContain  ":6:16: argument 1 type mismatch"
        errors.errors[2] shouldContain  ":12:16: this pass-by-reference type can't be used as a parameter type"
    }

    test("proper type checking for multi-value assigns") {
        val src="""
main {
    sub start() {
        bool bb
        ubyte ub
        uword uw
        uw, void = thing2()
        uw, bb = thing2()
        uw, ub = thing2()
    }

    asmsub thing2() -> ubyte @A, bool @Pc {
        %asm {{
            rts
        }}
    }
}"""
        val errors = ErrorReporterForTests()
        compileText(C64Target(), false, src, outputDir, writeAssembly = false, errors = errors) shouldBe null
        errors.errors.size shouldBe 1
        errors.errors[0] shouldContain  "bool doesn't match target type ubyte"
    }

    test("multi assigns with too few result values from the function") {
        val src="""
main {
    sub start() {
        ubyte @shared x,y,z
        x,y,z = sys.progend()
        ubyte @shared k,l,m = sys.progend()
    }
}"""
        val errors = ErrorReporterForTests()
        compileText(Cx16Target(), optimize=true, src, outputDir, writeAssembly=false, errors = errors) shouldBe null
        errors.errors.size shouldBe 2
        errors.errors[0] shouldContain "too few values: expected 3 got 1"
        errors.errors[1] shouldContain "too few values: expected 3 got 1"
    }

    test("correct errors for wrong string initialization value") {
        val src="""
main {

    sub start() {
        str minString1 = 1234
        str minString2 = func()
    }

    sub func() -> str {
        return "zz"
    }
}"""
        val errors = ErrorReporterForTests()
        compileText(Cx16Target(), optimize=false, src, outputDir, writeAssembly=false, errors = errors) shouldBe null
        errors.errors.size shouldBe 3
        errors.errors[0] shouldContain "value type uword doesn't match target type str"
        errors.errors[1] shouldContain "string var must be initialized with a string literal"
        errors.errors[2] shouldContain "string var must be initialized with a string literal"
    }

    test("cannot assign strings by-value") {
        val src="""
main {

    sub start() {
        str n1 = "irmen"
        str n2 = "de jong 12345"

        n1 = n2
        n1 = "zsdfzsdf"
        n1 = 0
        n2 = 9999

        void foo(n1)
    }

    sub foo(str arg) -> str {
        arg = "bar"
        arg = 0
        arg = 9999
        return arg
    }
}"""

        val errors = ErrorReporterForTests()
        compileText(Cx16Target(), optimize=false, src, outputDir, writeAssembly=false, errors = errors) shouldBe null
        errors.errors.size shouldBe 4
        errors.errors[0] shouldContain ":8:9: cannot assign to string"
        errors.errors[1] shouldContain ":9:9: cannot assign to string"
        errors.errors[2] shouldContain ":10:9: cannot assign to string"
        errors.errors[3] shouldContain ":11:9: cannot assign to string"
    }

    test("if expression with and without then keyword") {
        val src="""
main {
    sub start() {
        cx16.r0L = if cx16.r1L==42 then 1 else 0
        cx16.r1L = if cx16.r2L==42  1 else 0
        cx16.r0L = if_cs then 1 else 0
        cx16.r1L = if_cs   1 else 0
    }
}"""
        compileText(Cx16Target(), optimize=false, src, outputDir, writeAssembly=false).shouldNotBeNull()
    }

    test("float consts without float option enable is ok") {
        val src="""
main {
    const float zz = 3.33
    sub start() {
        uword @shared tmp = (zz+zz) as uword
        cx16.r0++
    }
}"""
        compileText(C64Target(), optimize=false, src, outputDir) shouldNotBe null
    }

    test("empty enum declaration should give error") {
        val src="""
            main {
                enum Color {
                }
                sub start() { }
            }
        """.trimIndent()
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), false, src, outputDir, errors, false)
        errors.errors.size shouldBe 1
        errors.errors[0] shouldContain "enumeration must contain at least one member"
    }

    test("empty struct declaration should give error") {
        val src="""
            main {
                struct Node {
                }
                sub start() { }
            }
        """.trimIndent()
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), false, src, outputDir, errors, false)
        errors.errors.size shouldBe 1
        errors.errors[0] shouldContain "struct must contain at least one field"
    }

    test("empty union declaration should give error") {
        val src = """
%zeropage basicsafe
%option no_sysinit
main {
    union U {
    }
    sub start() { }
}"""
        val errors = ErrorReporterForTests()
        compileText(Cx16Target(), false, src, outputDir, errors, false)
        errors.errors.size shouldBe 1
        errors.errors[0] shouldContain "union must have at least one field"
    }

    test("union initializer with values is rejected") {
        val src = """
%zeropage basicsafe
%option no_sysinit
main {
    union U { ubyte b }
    sub start() {
        ^^U u = [1]
    }
}"""
        val errors = ErrorReporterForTests()
        compileText(Cx16Target(), false, src, outputDir, errors, false)
        errors.errors.any { it.contains("union cannot be statically initialized with values") } shouldBe true
    }

    test("union named-field initialization is rejected") {
        val src = """
%zeropage basicsafe
%option no_sysinit
main {
    union U { ubyte b }
    sub start() {
        ^^U u = ^^U:[b=1]
    }
}"""
        val errors = ErrorReporterForTests()
        compileText(Cx16Target(), false, src, outputDir, errors, false)
        errors.errors.any { it.contains("named-field initialization is not supported for unions") } shouldBe true
    }

    test("unknown field on union gives normal error") {
        val src = """
%zeropage basicsafe
%option no_sysinit
main {
    union U { ubyte b }
    sub start() {
        ^^U u = []
        u.x = 1
    }
}"""
        val errors = ErrorReporterForTests()
        compileText(Cx16Target(), false, src, outputDir, errors, false)
        errors.errors.any { it.contains("unknown field 'x'") } shouldBe true
    }

    test("comma in array index should give friendly error") {
        val src = """
            main {
                sub start() {
                    ubyte[5,20] matrix
                }
            }
        """.trimIndent()
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), false, src, outputDir, errors) shouldBe null
        errors.printedErrors.any { it.contains("[rows][cols]") } shouldBe true
    }

    test("struct initializer with array literals") {
        val src = """
            main {
                struct Node {
                    ubyte a
                    bool flag
                    str name
                }
                
                sub start() {
                    Node n = ^^Node:[1, [1], [42,43,44]]
                }
            }
        """.trimIndent()
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), false, src, outputDir, errors, false)
        errors.errors.size shouldNotBe 0
        errors.errors.any { it.contains("Array literal passed to scalar field 'flag'") } shouldBe true
        errors.errors.any { it.contains("invalid number of arguments; make sure str fields are initialized with a string literal and nothing else") } shouldBe true
    }

    test("struct initializer with array literals for str field") {
        val src = """
            main {
                struct Node {
                    str name
                }
                
                sub start() {
                    Node n = ^^Node:[ [1,2,3] ]
                }
            }
        """.trimIndent()
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), false, src, outputDir, errors, false)
        
        val expectedMessage = "invalid number of arguments; make sure str fields are initialized with a string literal"
        val found = errors.errors.any { it.contains(expectedMessage) }
        
        found shouldBe true
    }

    test("struct field with empty array size should give error") {
        val src = """
            main {
                struct Node {
                    ubyte[] data
                }
                sub start() { }
            }
        """.trimIndent()
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), false, src, outputDir, errors) shouldBe null
        errors.printedErrors.any { it.contains("array field must have a specified size") } shouldBe true
    }

    test("struct field with 2D array should give error") {
        val src = """
            main {
                struct Node {
                    ubyte[4][2] data
                }
                sub start() { }
            }
        """.trimIndent()
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), false, src, outputDir, errors) shouldBe null
        errors.printedErrors.any { it.contains("2D arrays are not allowed as struct fields") } shouldBe true
    }

    test("struct field array size can be const expression") {
        val src = """
            main {
                const ubyte SIZE = 5
                struct Node {
                    ubyte[SIZE] data
                    uword top
                }
                sub start() {
                    ^^Node n = [ [1,2,3,4,5], 9999 ]
                }
            }
        """.trimIndent()
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), false, src, outputDir, errors, false)
        errors.errors shouldBe emptyList()
    }

    test("struct field array size can be folded constant expression") {
        val src = """
            main {
                const ubyte BASE = 3
                struct Node {
                    ubyte[BASE*2] data
                }
                sub start() {
                    ^^Node n = [ [1,2,3,4,5,6] ]
                }
            }
        """.trimIndent()
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), false, src, outputDir, errors, false)
        errors.errors shouldBe emptyList()
    }

    test("struct field array size rejects non-constant expression") {
        val src = """
            main {
                uword dyn
                struct Node {
                    uword[dyn] data
                }
                sub start() {
                    dyn = 5
                }
            }
        """.trimIndent()
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), false, src, outputDir, errors) shouldBe null
        errors.errors.any { it.contains("array length must be known at compile-time") } shouldBe true
    }

    test("struct field array size rejects zero size") {
        val src = """
            main {
                const ubyte SIZE = 0
                struct Node {
                    ubyte[SIZE] data
                }
                sub start() { }
            }
        """.trimIndent()
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), false, src, outputDir, errors) shouldBe null
        errors.errors.any { it.contains("array length must be positive") } shouldBe true
    }

    test("variable declaration followed by statement on same line should give error") {
        val src = """
            main {
                sub start() {
                    ubyte length foo(42)
                }
                sub foo(ubyte x) {
                    x++
                }
            }
        """.trimIndent()
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), false, src, outputDir, errors) shouldBe null
        errors.errors.size shouldBe 1
        errors.errors[0] shouldContain "must be on its own line"
    }

    test("variable declaration followed by statement on next line is fine") {
        val src = """
            main {
                sub start() {
                    ubyte length
                    length = 42
                    foo(length)
                }
                sub foo(ubyte x) {
                    x++
                }
            }
        """.trimIndent()
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), false, src, outputDir, errors, false) shouldNotBe null
        errors.errors.size shouldBe 0
    }

    test("two statements on same line is fine if first one is not a variable declaration") {
        val src = """
            main {
                sub start() {
                    foo(1) foo(2)
                }
                sub foo(ubyte x) {
                    cx16.r0 = x
                }
            }
        """.trimIndent()
        val errors = ErrorReporterForTests()
        compileText(VMTarget(), false, src, outputDir, errors, false) shouldNotBe null
        errors.errors.size shouldBe 0
    }

    test("quad and uquad are only supported on the virtual and llvm64 targets") {
        // a variable, an array, a pointer, a struct field, two casts, two parameters and a return type
        val src = $$"""
            main {
                quad @shared big = 5000000000
                uquad[2] @shared masks = [$ffff_ffff_0000_0000, 1]
                ^^quad @shared bigpointer

                struct Stamp {
                    uquad seconds
                }

                sub start() {
                    ubyte @shared small = 5
                    big = combine(small as quad, masks[0])
                }

                sub combine(quad value, uquad mask) -> quad {
                    return value + (mask as quad)
                }
            }
        """.trimIndent()
        for (target in listOf(C64Target(), Cx16Target(), Qemu68kTarget())) {
            withClue(target.name) {
                val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                compileText(target, false, src, outputDir, errors, false) shouldBe null
                errors.errors.size shouldBe 9
                errors.errors.forEach { it shouldContain "64-bit integer types (quad/uquad) are not supported on target ${target.name}" }
            }
        }
        for (target in listOf(VMTarget(), LlvmTarget())) {
            for (optimize in listOf(false, true)) {
                withClue("${target.name} optimize=$optimize") {
                    compileText(target, optimize, src, outputDir, writeAssembly = false).shouldNotBeNull()
                }
            }
        }
    }

    test("lsb, lsw, msb and msw only take a quad on the virtual and llvm64 targets") {
        val src = $$"""
            main {
                sub start() {
                    uword @shared w = 1234
                    ubyte @shared b = lsb($1_0000_0012)
                    b = msb($1_0000_0012)
                    w = lsw($1_0000_0012)
                    w = msw($1_0000_0012)
                }
            }
        """.trimIndent()
        for (target in listOf(C64Target(), Cx16Target(), Qemu68kTarget())) {
            withClue(target.name) {
                val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                compileText(target, false, src, outputDir, errors, false) shouldBe null
                errors.errors.size shouldBe 4
                errors.errors.forEach { it shouldContain "64-bit integer types (quad/uquad) are not supported on target ${target.name}" }
            }
        }
        for (target in listOf(VMTarget(), LlvmTarget())) {
            for (optimize in listOf(false, true)) {
                withClue("${target.name} optimize=$optimize") {
                    compileText(target, optimize, src, outputDir, writeAssembly = false).shouldNotBeNull()
                }
            }
        }
    }

    test("integer literals wider than 32 bits still work as float values on the targets without quads") {
        val src = $$"""
            %import floats
            %zeropage basicsafe
            %option enable_floats
            main {
                sub start() {
                    float @shared f = 5000000000
                    f = f * 3000000000 + 10000000000
                    long @shared l = $00000001 << 31
                }
            }
        """.trimIndent()
        for (optimize in listOf(false, true)) {
            withClue("optimize=$optimize") {
                val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                compileText(C64Target(), optimize, src, outputDir, errors, true).shouldNotBeNull()
                errors.errors shouldBe emptyList()
            }
        }
    }

    test("quad and uquad expressions, statements and constants pass the checks and optimizations") {
        val src = $$"""
            main {
                quad @shared big = 5000000000
                uquad @shared mask = $ffff_ffff_0000_0000
                quad[] @shared values = [1, -2, 5000000000]
                const quad NEGATIVE = -5000000000

                struct Stamp {
                    quad seconds
                    uquad flags
                }

                sub start() {
                    quad q = big * 3 - NEGATIVE
                    q = -big / 7 + big % 7
                    q <<= 3
                    q >>= 1
                    q++
                    uquad u = mask | $ff
                    u = ~u >> 4
                    word @shared w = -5
                    q = w + big
                    long @shared l = q as long
                    ubyte @shared b = u as ubyte
                    if q > big and u != 0 {
                        q = 0
                    }
                    while q < 10 {
                        q++
                    }
                    when q {
                        1 -> q = 2
                        5000000000 -> q = 3
                        else -> q = 4
                    }
                    bool @shared found = 5000000000 in values
                    ubyte @shared size = sizeof(quad)
                    ^^quad p = &big
                    p^^ = 7000000000
                    ^^Stamp s = ^^Stamp: [5000000000, $ffff_ffff_ffff_ffff]
                    s.seconds = twice(s.seconds)
                }

                sub twice(quad value) -> quad {
                    return value * 2
                }
            }
        """.trimIndent()
        for (target in listOf(VMTarget(), LlvmTarget())) {
            for (optimize in listOf(false, true)) {
                withClue("${target.name} optimize=$optimize") {
                    val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                    compileText(target, optimize, src, outputDir, errors, false).shouldNotBeNull()
                    errors.errors shouldBe emptyList()
                }
            }
        }
    }

    test("integer literals wider than 32 bits are quads, or uquads above the signed range, that keep all 64 bits") {
        val src = $$"""
            main {
                const DECIMAL = 4294967296
                const SIGNED_MAX = 9223372036854775807
                const UNSIGNED = 9223372036854775808
                const UNSIGNED_MAX = 18446744073709551615
                const HEX = $1_0000_0000
                const HEX_MASK = $ffff_ffff_ffff_fff0
                const BINARY = %1_00000000_00000000_00000000_00000001
                const LONG_DECIMAL = 4294967295
                const LONG_HEX = $ffff_ffff

                sub start() {
                }
            }
        """.trimIndent()
        val result = compileText(VMTarget(), false, src, outputDir, writeAssembly = false).shouldNotBeNull()
        val constants = result.compilerAst.allBlocks.single { it.name == "main" }.statements.filterIsInstance<VarDecl>().associateBy { it.name }
        fun check(name: String, type: BaseDataType, bits: Long) = withClue(name) {
            val constant = constants.getValue(name)
            constant.datatype shouldBe DataType.forDt(type)
            val value = constant.value as NumericLiteral
            value.type shouldBe type
            value.integerBits shouldBe bits
        }
        check("DECIMAL", BaseDataType.QUAD, 4294967296L)
        check("SIGNED_MAX", BaseDataType.QUAD, Long.MAX_VALUE)
        check("UNSIGNED", BaseDataType.UQUAD, Long.MIN_VALUE)
        check("UNSIGNED_MAX", BaseDataType.UQUAD, -1L)
        check("HEX", BaseDataType.QUAD, 0x1_0000_0000L)
        check("HEX_MASK", BaseDataType.UQUAD, -16L)
        check("BINARY", BaseDataType.QUAD, 0x1_0000_0001L)
        // 32-bit unsigned values still become signed longs, as before
        check("LONG_DECIMAL", BaseDataType.LONG, -1L)
        check("LONG_HEX", BaseDataType.LONG, -1L)
    }

    test("quad and uquad values must be in range, and integer literals can't be wider than 64 bits") {
        val src = """
            main {
                sub start() {
                    quad @shared signed = 9223372036854775808
                    uquad @shared unsigned = -1
                    long @shared narrow = 5000000000
                }
            }
        """.trimIndent()
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(VMTarget(), false, src, outputDir, errors, false) shouldBe null
        errors.errors.filter { "out of range" in it }.map { it.substringAfter(": ") } shouldBe listOf(
            "value '9223372036854775808' out of range for quad",
            "value '-1.0' out of range for uquad",
            "value '5000000000' out of range for long")

        for ((literal, error) in listOf("18446744073709551616" to "invalid decimal literal", $$"$1_0000_0000_0000_0000" to "invalid hexadecimal literal")) {
            val tooWide = """
                main {
                    sub start() {
                        uquad @shared value = $literal
                    }
                }
            """.trimIndent()
            val wideErrors = ErrorReporterForTests(keepMessagesAfterReporting = true)
            compileText(VMTarget(), false, tooWide, outputDir, wideErrors, false) shouldBe null
            wideErrors.printedErrors.single() shouldContain error
        }
    }

    test("an address only becomes an integer as wide as a pointer without a cast, which is a uquad on llvm64") {
        val narrowing = """
            main {
                ubyte[4] @shared buffer
                sub start() {
                    ^^ubyte p = &&buffer
                    long @shared l = p
                    uword @shared w = p
                    takeslong(p)
                    void aslong()
                }
                sub aslong() -> long {
                    return &&buffer
                }
                sub takeslong(long value) {
                }
            }
        """
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(LlvmTarget(), false, narrowing, outputDir, errors, false) shouldBe null
        errors.errors.map { it.substringAfter(": ") } shouldBe List(4) { index ->
            val type = if(index==1) "uword" else "long"
            "an address doesn't fit in a $type, pointers have 8 bytes here: use uquad, or an explicit cast to truncate it"
        }
        compileText(VMTarget(), false, narrowing, outputDir, writeAssembly = false).shouldNotBeNull()

        val wideEnough = """
            main {
                ubyte[4] @shared buffer
                sub start() {
                    ^^ubyte p = &&buffer
                    long @shared l = p as long
                    uword @shared w = p as uword
                    uquad @shared u = p
                    uquad @shared u2 = &buffer
                    ^^ubyte back = u
                    takesuquad(p)
                }
                sub takesuquad(uquad value) {
                }
            }
        """
        compileText(LlvmTarget(), false, wideEnough, outputDir, writeAssembly = false).shouldNotBeNull()
    }

    test("the integer variable that can be indexed like a pointer is as wide as a pointer") {
        fun holder(type: String) = """
            main {
                ubyte[4] @shared buffer
                sub start() {
                    $type @shared holder = 0
                    holder[3] = 1
                }
            }
        """
        compileText(LlvmTarget(), false, holder("uquad"), outputDir, writeAssembly = false).shouldNotBeNull()
        compileText(VMTarget(), false, holder("long"), outputDir, writeAssembly = false).shouldNotBeNull()
        for ((target, type, expected) in listOf(Triple(LlvmTarget(), "long", "uquad"), Triple(VMTarget(), "uquad", "long"))) {
            val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
            compileText(target, false, holder(type), outputDir, errors, false) shouldBe null
            errors.errors.single() shouldContain "indexing requires an iterable, address $expected, or pointer variable"
        }
    }

    test("a long is compared with a string as an address only where a long holds one") {
        val src = """
            main {
                long @shared l = 5
                sub start() {
                    if l == "abc"
                        l++
                }
            }
        """
        compileText(VMTarget(), false, src, outputDir, writeAssembly = false).shouldNotBeNull()
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(LlvmTarget(), false, src, outputDir, errors, false) shouldBe null
        errors.errors.single() shouldContain "left and right operands aren't the same type: long vs str"
    }

    test("a pointer variable that never changes from its constant value doesn't loop the constant folder") {
        val src = """
            main {
                sub start() {
                    ^^ubyte p = 0
                    bool @shared b = p != 0
                    ^^ubyte @shared q = p
                }
            }
        """
        for (target in listOf(VMTarget(), LlvmTarget(), Cx16Target()))
            withClue(target.name) { compileText(target, false, src, outputDir, writeAssembly = false).shouldNotBeNull() }
    }

    test("constant addresses of typed pointers are limited to 32 bits on llvm64 for now, the untyped pointer is a uquad that keeps all 64 bits") {
        val src = $$"""
            main {
                ^^ubyte @shared global = $1_0000_1234
                sub start() {
                    ^^ubyte @shared p
                    p = $1234_5678_9abc_def0
                    p = $1_0000_0000 as ^^ubyte
                    p = $1234_5678
                }
            }
        """
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(LlvmTarget(), false, src, outputDir, errors, false) shouldBe null
        errors.errors.map { it.substringAfter(": ") } shouldBe listOf("4294971956", "1311768467463790320", "4294967296").map {
            "constant address $it doesn't fit in 32 bits, which is the limit for constant addresses for now"
        }

        val untyped = $$"""
            main {
                pointer @shared global = $1_0000_1234
                sub start() {
                    pointer @shared p
                    p = $1234_5678_9abc_def0
                    p = $1_0000_0000 as pointer
                    p = $1234_5678
                }
            }
        """
        val result = compileText(LlvmTarget(), false, untyped, outputDir, writeAssembly = false).shouldNotBeNull()
        val main = result.compilerAst.allBlocks.first { it.name == "main" }
        main.statements.filterIsInstance<VarDecl>().single { it.name == "global" }.datatype shouldBe DataType.UQUAD
        val values = (main.statements + result.compilerAst.entrypoint.statements).filterIsInstance<Assignment>().map { it.value as NumericLiteral }
        values.map { it.type } shouldBe List(4) { BaseDataType.UQUAD }
        values.map { it.integerBits } shouldBe listOf(0x1_0000_1234L, 0x1234_5678_9abc_def0L, 0x1_0000_0000L, 0x1234_5678L)
    }

    test("@nosplit is redundant on a uquad array as on a long array, so also on the pointer[] and str[] arrays of llvm64") {
        val src = """
            main {
                str[2] @shared @nosplit names = ["a", "b"]
                pointer[2] @shared @nosplit addresses
                uquad[2] @shared @nosplit numbers
                long[2] @shared @nosplit longs
                quad[2] @shared @nosplit signed
                sub start() {
                }
            }
        """
        for (target in listOf(LlvmTarget(), VMTarget())) {
            withClue(target.name) {
                val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                compileText(target, false, src, outputDir, errors, false) shouldBe null
                errors.infos.map { it.substringAfter(".p8:") } shouldBe listOf(3, 4, 5, 6).map { "$it:17: @nosplit is redundant here" }
                errors.errors.map { it.substringAfter(".p8:") } shouldBe listOf("7:17: @nosplit is for word or pointer arrays only")
            }
        }
    }

    test("constant memory addresses are limited to 32 bits on llvm64, also folded ones and those of peek and poke") {
        val src = $$"""
            main {
                sub start() {
                    uquad p = $1_0000_1234
                    ubyte @shared x = @($1_0000_5678)
                    @($1_0000_5678) = 3
                    uword @shared w = peekw($1_0000_0000)
                    pokew($1_0000_0000, 1)
                    x = @(p)
                    w = peekw(p+2)
                }
            }
        """
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(LlvmTarget(), false, src, outputDir, errors, false) shouldBe null
        errors.errors.map { it.substringAfter(": ") } shouldBe listOf("4294989432", "4294989432", "4294967296", "4294967296", "4294971956", "4294971958").map {
            "constant address $it doesn't fit in 32 bits, which is the limit for constant addresses for now"
        }
    }

    test("a constant address for peek and poke is out of range above 32 bits where pointers have 4 bytes, and when negative on llvm64") {
        val quadAddress = $$"""
            main {
                sub start() {
                    uquad p = $1_0000_1234
                    uword @shared w = peekw(p+2)
                    pokew(p+2, 1)
                }
            }
        """
        val negativeAddress = $$"""
            main {
                sub start() {
                    uword @shared w = peekw($ff00_8000)
                    pokew($ff00_8000, 1)
                }
            }
        """
        for ((target, src) in listOf(VMTarget() to quadAddress, LlvmTarget() to negativeAddress)) {
            withClue(target.name) {
                val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                compileText(target, false, src, outputDir, errors, false) shouldBe null
                errors.errors.map { it.substringAfter(": ") } shouldBe List(2) { "address out of range" }
            }
        }
    }

    test("constant memory addresses up to the 32-bit or 16-bit limit of the target still compile") {
        val llvm = $$"""
            main {
                sub start() {
                    ubyte @shared x = @($0_ffff_ffff)
                    @($0_ffff_ffff) = x
                    x = @($7fff_ffff)
                    @($7fff_ffff) = x
                    uword @shared w = peekw($0_ffff_fffe)
                    pokew($0_ffff_fffe, w)
                    uquad p = $0_ffff_fff0
                    x = @(p)
                    w = peekw(p+2)
                }
            }
        """
        val fourBytes = $$"""
            main {
                sub start() {
                    ubyte @shared x = @($ffff_ffff)
                    x = @($8000_0000)
                    @($7fff_ffff) = x
                    uword @shared w = peekw($8000_0000)
                    pokew($ff00_8000, w)
                }
            }
        """
        val virtual = $$"""
            main {
                sub start() {
                    uquad p = $0_ffff_fff0
                    uword @shared w = peekw(p+2)
                    pokew(p+4, w)
                }
            }
        """
        val cx16 = $$"""
            main {
                sub start() {
                    ubyte @shared x = @($ffff)
                    @($ffff) = x
                    uword @shared w = peekw($fffe)
                    pokew($fffe, w)
                }
            }
        """
        for ((target, src) in listOf(LlvmTarget() to llvm, VMTarget() to fourBytes, Qemu68kTarget() to fourBytes, VMTarget() to virtual, Cx16Target() to cx16)) {
            withClue(target.name) {
                val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                val result = compileText(target, false, src, outputDir, errors, false)
                errors.errors shouldBe emptyList()
                result.shouldNotBeNull()
            }
        }
    }

    test("on llvm64 a uquad address takes signed operands, long values, strings and typed addresses") {
        val src = $$"""
            main {
                ubyte @shared a = 7
                ubyte[4] @shared buffer
                long @shared longvar = -2
                word @shared wordvar = -300
                byte @shared bytevar = -5
                quad @shared quadvar = -7
                str text = "abc"
                uquad[3] @shared typed = [&&a, &&buffer, 0]
                uquad[3] @shared mixed = [&a, &&buffer, 0]

                struct Holder {
                    ubyte tag
                    uquad addr
                }

                sub start() {
                    uquad @shared u = &a
                    bool @shared b = u == longvar or wordvar < u or u != bytevar or u >= quadvar
                    b = u < 70000 or 100000 <= u or u == $ff0000 or u == -1 or -5000000000 > u
                    u = u + $1_0000_0000 - quadvar
                    u = quadvar + u
                    u += $1_0000_0000
                    u -= quadvar
                    uquad @shared fromlong = longvar
                    fromlong = longvar
                    u = widened(longvar) + identity(longvar) + identity(buffer) + identity(text)
                    b = u == "abc" or text != u or u < text
                    ^^Holder holder = ^^Holder : [1, &&a]
                }

                sub identity(uquad value) -> uquad {
                    return value
                }

                sub widened(long value) -> uquad {
                    return value
                }
            }
        """
        for (optimize in listOf(false, true)) {
            withClue("optimize=$optimize") {
                val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                compileText(LlvmTarget(), optimize, src, outputDir, errors, false).shouldNotBeNull()
                errors.errors shouldBe emptyList()
            }
        }
    }

    test("a long goes with a uquad on llvm64, but not on the virtual target where pointers have 4 bytes") {
        val src = """
            main {
                long @shared longvar = 5
                sub start() {
                    uquad @shared u = 7
                    u = u + longvar
                    bool @shared same = u == longvar
                    uquad @shared v = longvar
                }
            }
        """
        compileText(LlvmTarget(), false, src, outputDir, writeAssembly = false).shouldNotBeNull()
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(VMTarget(), false, src, outputDir, errors, false) shouldBe null
        errors.errors.map { it.substringAfter(": ") } shouldBe listOf(
            "value type quad doesn't match target type uquad",
            "left and right operands aren't the same type: uquad vs quad",
            "left and right operands aren't the same type: uquad vs quad",
            "value type long doesn't match target type uquad")
    }

    test("on llvm64 words, bytes and negative literals still don't convert to a uquad") {
        val src = """
            main {
                word @shared wordvar = 5
                byte @shared bytevar = 5
                sub start() {
                    uquad @shared u = wordvar
                    uquad @shared v = bytevar
                    uquad @shared w = -1
                    u = wordvar
                    w = -100000
                }
            }
        """
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(LlvmTarget(), false, src, outputDir, errors, false) shouldBe null
        errors.errors.map { it.substringAfter(": ") } shouldBe listOf(
            "value type word doesn't match target type uquad",
            "value type byte doesn't match target type uquad",
            "value '-1.0' out of range for uquad",
            "value type byte doesn't match target type uquad",
            "value type word doesn't match target type uquad",
            "value '-100000.0' out of range for uquad",
            "value type long doesn't match target type uquad")
    }

    test("a quad loop variable only loops over an array of its own type, never over a range") {
        val src = """
            main {
                uquad[3] @shared uarr = [1, 2, 3]
                quad[3] @shared qarr = [1, 2, 3]
                ubyte[3] @shared barr = [1, 2, 3]
                sub start() {
                    uquad u
                    quad q
                    for u in uarr { }
                    for q in qarr { }
                    for u in 0 to 10 { }
                    for q in 1 to 5 step 2 { }
                    for q in barr { }
                    for u in "abc" { }
                }
            }
        """
        for (target in listOf(LlvmTarget(), VMTarget())) {
            withClue(target.name) {
                val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                compileText(target, false, src, outputDir, errors, false) shouldBe null
                errors.errors.map { it.substringAfter(": ") } shouldBe listOf(
                    "uquad loop variable can't loop over a range, only over a uquad array",
                    "quad loop variable can't loop over a range, only over a quad array",
                    "quad loop variable can only loop over a quad array",
                    "uquad loop variable can only loop over a uquad array")
            }
        }
    }

    test("quad loop variables are rejected on the targets without quads, like every quad variable") {
        val src = """
            main {
                uquad[3] @shared uarr = [1, 2, 3]
                quad[3] @shared qarr = [1, 2, 3]
                sub start() {
                    uquad u
                    for u in uarr { }
                    for q in qarr { }
                }
            }
        """
        for (target in listOf(LlvmTarget(), VMTarget()))
            withClue(target.name) { compileText(target, false, src, outputDir, writeAssembly = false).shouldNotBeNull() }
        for (target in listOf(Cx16Target(), C64Target(), Qemu68kTarget())) {
            withClue(target.name) {
                val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                compileText(target, false, src, outputDir, errors, false) shouldBe null
                // the two arrays, u, and the implicitly declared q
                errors.errors.size shouldBe 4
                errors.errors.forEach { it shouldContain "64-bit integer types (quad/uquad) are not supported on target ${target.name}" }
            }
        }
    }

    test("a typed pointer loop variable takes the addresses in a uquad array only on llvm64, where those are uquads") {
        val src = """
            main {
                uquad[2] @shared addrs

                struct Node {
                    ubyte kind
                }

                sub start() {
                    ^^ubyte p
                    ^^Node n
                    for p in addrs { }
                    for n in addrs { }
                }
            }
        """
        compileText(LlvmTarget(), false, src, outputDir, writeAssembly = false).shouldNotBeNull()
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(VMTarget(), false, src, outputDir, errors, false) shouldBe null
        errors.errors.map { it.substringAfter(": ") } shouldBe List(2) { "pointer loop variable can only loop over pointers or unsigned words" }
    }

    test("a struct field indexes the bytes at its address when it has the address integer type of the target") {
        fun src(fieldType: String) = """
            main {
                struct Node {
                    ubyte kind
                    $fieldType data
                }

                sub start() {
                    ^^Node node = ^^Node : [1, 0]
                    ubyte @shared v = node.data[1]
                    node.data[2] = 9
                    node.data[v] += 1
                }
            }
        """
        for ((target, addressType) in listOf(Cx16Target() to "uword", VMTarget() to "long", LlvmTarget() to "uquad"))
            withClue(target.name) { compileText(target, false, src(addressType), outputDir, writeAssembly = false).shouldNotBeNull() }
        for ((target, otherType) in listOf(Cx16Target() to "long", VMTarget() to "uquad", LlvmTarget() to "long")) {
            withClue("${target.name} $otherType") {
                val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
                compileText(target, false, src(otherType), outputDir, errors, false) shouldBe null
                errors.errors.count { it.endsWith("cannot array index on this field type") } shouldBe 3
            }
        }
    }
})
