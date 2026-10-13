package prog8tests.codegeneration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.Enabled
import io.kotest.core.test.TestCase
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import prog8.ast.statements.StructDecl
import prog8.ast.statements.Subroutine
import prog8.ast.statements.VarDecl
import prog8.code.core.BaseDataType
import prog8.code.core.DataType
import prog8.code.core.OutputFiles
import prog8.code.target.Cx16Target
import prog8.code.target.LlvmTarget
import prog8.code.target.Qemu68kTarget
import prog8.code.target.VMTarget
import prog8.code.target.getCompilationTargetByName
import prog8.compiler.CompilationResult
import prog8tests.helpers.ErrorReporterForTests
import prog8tests.helpers.compileText
import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines


class TestLlvmCompiler: FunSpec({

    val outputDir = tempdir().toPath()

    for ((name, source) in programs) {
        test("$name: compiles to a .ll file with a C entry point, with and without optimization, which llvm-as accepts when installed") {
            for (optimize in listOf(false, true)) {
                withClue("optimize=$optimize") {
                    val llFile = compiledLlFile(compileText(LlvmTarget(), optimize, source, outputDir))
                    val lines = llFile.readLines()
                    lines shouldContain "define i32 @main() {"
                    lines shouldContain "define internal void @main.start() {"
                }
            }
        }
    }

    test("the programs compute the right results as native arm64 macOS executables").config(enabledOrReasonIf = requiresNativeLlvm) {
        for ((name, source) in programs) {
            for (optimize in listOf(false, true)) {
                val llFile = compiledLlFile(compileText(LlvmTarget(), optimize, source, outputDir))
                val (exitCode, output) = buildAndRun(llFile)
                withClue("$name optimize=$optimize: exit code = the number of the failed check; $output") { exitCode shouldBe 0 }
            }
        }
    }

    test("llvm is another name for the llvm64 target, whose program file is the LLVM IR text") {
        getCompilationTargetByName("llvm").name shouldBe LlvmTarget.NAME
        getCompilationTargetByName("LLVM64").name shouldBe LlvmTarget.NAME
        compiledLlFile(compileText(LlvmTarget(), false, programs.getValue("arithmetic expressions"), outputDir)).toString() shouldEndWith ".ll"
    }

    test("code that has no LLVM translation is a compilation error") {
        val src = """
main {
    sub start() {
        %asm {{
            nop
        }}
    }
}"""
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(LlvmTarget(), false, src, outputDir, errors = errors) shouldBe null
        errors.errors.single() shouldContain "main.start has inline assembly, which can't be translated to LLVM IR"
    }

    test("an unknown target name is rejected") {
        shouldThrow<IllegalArgumentException> { getCompilationTargetByName("llvm32") }
    }

    test("untyped pointers convert implicitly only on llvm64, where no integer type can hold an address") {
        val bytePointer = DataType.pointer(BaseDataType.UBYTE)
        val wordPointer = DataType.pointer(BaseDataType.UWORD)
        LlvmTarget().untypedPointerIsBytePointer shouldBe true
        LlvmTarget().pointerConvertsImplicitly(bytePointer, wordPointer) shouldBe true
        LlvmTarget().pointerConvertsImplicitly(wordPointer, bytePointer) shouldBe true
        LlvmTarget().pointerConvertsImplicitly(wordPointer, DataType.pointer(BaseDataType.WORD)) shouldBe false
        LlvmTarget().pointerConvertsImplicitly(DataType.LONG, wordPointer) shouldBe false
        for (target in listOf(VMTarget(), Cx16Target(), Qemu68kTarget())) {
            withClue(target.name) {
                target.untypedPointerIsBytePointer shouldBe false
                target.pointerConvertsImplicitly(bytePointer, wordPointer) shouldBe false
            }
        }
    }

    test("the untyped pointer type is a byte pointer on llvm64, and the address integer type elsewhere") {
        val src = """
main {
    pointer @shared global
    pointer[3] @shared table = [&global, 0, 0]

    struct Node {
        pointer data
    }

    sub start() {
        pointer @shared local = &global
        local = identity(local as pointer)
    }

    sub identity(pointer address) -> pointer {
        return address
    }
}"""
        fun types(result: CompilationResult?): List<DataType> {
            val main = result.shouldNotBeNull().compilerAst.allBlocks.first { it.name == "main" }
            val variables = main.statements.filterIsInstance<VarDecl>().associate { it.name to it.datatype }
            val identity = main.statements.filterIsInstance<Subroutine>().first { it.name == "identity" }
            val local = result.compilerAst.entrypoint.statements.filterIsInstance<VarDecl>().first { it.name == "local" }
            val field = main.statements.filterIsInstance<StructDecl>().single().fields.single()
            return listOf(variables.getValue("global"), local.datatype, identity.parameters.single().type, identity.returntypes.single(),
                field.type, variables.getValue("table"))
        }
        val bytePointer = DataType.pointer(BaseDataType.UBYTE)
        types(compileText(LlvmTarget(), false, src, outputDir, writeAssembly = false)) shouldBe
            List(5) { bytePointer } + DataType.arrayOfPointersTo(BaseDataType.UBYTE)
        types(compileText(VMTarget(), false, src, outputDir, writeAssembly = false)) shouldBe
            List(5) { DataType.LONG } + DataType.arrayFor(BaseDataType.LONG, VMTarget())
    }

    test("on llvm64 the untyped pointer converts implicitly to and from typed pointers, as the long type does on the 32-bit targets") {
        val src = """
main {
    struct Node {
        ubyte kind
        pointer data
    }
    ^^Node node
    pointer[2] @shared table = [0, 0]

    sub start() {
        node = ^^Node: [1, 0]
        pointer anything = node
        ^^Node back = anything
        anything = back
        node = anything
        node.data = node
        back = node.data
        table[1] = node
        back = table[1]
        back = kindless(node)
        ubyte @shared kind = kindof(anything)
        pointer routine = &answer
        uword @shared result = call(routine)
    }

    sub kindof(^^Node n) -> ubyte {
        return n.kind
    }

    sub kindless(pointer p) -> pointer {
        return p
    }

    sub answer() -> uword {
        return 42
    }
}"""
        compileText(LlvmTarget(), false, src, outputDir, writeAssembly = false).shouldNotBeNull()
        compileText(VMTarget(), false, src, outputDir, writeAssembly = false).shouldNotBeNull()
    }

    test("on llvm64 two different typed pointers still need an explicit cast") {
        val src = """
main {
    sub start() {
        ^^uword @shared words = 0
        ^^word @shared signedwords = words
    }
}"""
        val errors = ErrorReporterForTests(keepMessagesAfterReporting = true)
        compileText(LlvmTarget(), false, src, outputDir, errors = errors, writeAssembly = false) shouldBe null
        errors.errors.single() shouldContain "cannot assign different pointer type, expected ^^word or uword but got ^^uword"
    }
})


private fun compiledLlFile(result: CompilationResult?): Path {
    val compiled = result.shouldNotBeNull()
    val file = OutputFiles.of(compiled.compilationOptions, compiled.compilerAst.name).programFile
    withClue("$file should exist") { file.exists() shouldBe true }
    return file
}

// llvm-as and clang of one LLVM installation; Homebrew installs LLVM keg-only, outside the PATH
private val llvmBin: Path? = (System.getenv("PATH").orEmpty().split(File.pathSeparator) + "/opt/homebrew/opt/llvm/bin")
    .mapNotNull { try { Path(it) } catch (_: InvalidPathException) { null } }
    .firstOrNull { Files.isExecutable(it.resolve("llvm-as")) && Files.isExecutable(it.resolve("clang")) }

private val requiresNativeLlvm: (TestCase) -> Enabled = {
    val arm64Mac = System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") == "aarch64"
    when {
        !arm64Mac -> Enabled.disabled("linking the default llvm64 target triple needs an arm64 macOS host")
        llvmBin == null -> Enabled.disabled("llvm-as and clang not found on the PATH or in /opt/homebrew/opt/llvm/bin")
        else -> Enabled.enabled
    }
}

/** Builds the .ll file into an executable with clang -O2 and runs it; returns its exit code and output, or clang's if that failed. */
private fun buildAndRun(llFile: Path): Pair<Int, String> {
    val executable = llFile.resolveSibling(llFile.fileName.toString().removeSuffix(".ll"))
    val compiled = execute(checkNotNull(llvmBin).resolve("clang").toString(), "-O2", "-Wno-override-module", llFile.toString(), "-o", executable.toString())
    return if (compiled.first != 0) compiled else execute(executable.toString())
}

private fun execute(vararg command: String): Pair<Int, String> {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor(60, TimeUnit.SECONDS)) { "${command.first()} did not finish" }
    return process.exitValue() to output
}

/** Complete programs that exit with 0, or with the number of the first check that fails. @shared inputs can't be constant folded. */
private val programs = mapOf(
    "arithmetic expressions" to $$"""
main {
    ubyte @shared ub1 = 200
    ubyte @shared ub2 = 100
    byte @shared b1 = -100
    uword @shared uw1 = 60000
    word @shared w1 = -1234
    long @shared l1 = 100000
    long @shared l2 = -3

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ubyte ub = ub1 + ub2
        if ub != 44
            return 1
        byte b = b1 - 100
        if b != 56
            return 2
        uword uw = uw1 + 6000
        if uw != 464
            return 3
        word w = w1 * 3
        if w != -3702
            return 4
        long l = l1 * l2
        if l != -300000
            return 5
        if l1 / 7 != 14285 or l1 % 7 != 5
            return 6
        if w1 / 10 != -123 or uw1 / 1000 != 60
            return 7
        if (ub1 as uword) * 4 != 800
            return 8
        if ub1 >> 3 != 25 or uw1 << 2 != 43392 or b1 >> 2 != -25
            return 9
        if (ub1 & $0f) | $30 != $38 or ub1 ^ $ff != 55 or ~ub2 != 155
            return 10
        if -w1 != 1234 or abs(w1) != 1234
            return 11
        if l2 * l2 * l2 != -27
            return 12
        if sqrt(uw1) != 244
            return 13
        if min(ub1, ub2) != 100 or max(w1, 5) != 5 or clamp(w1, -100, 100) != -100
            return 14
        if msb(uw1) != $ea or lsb(uw1) != $60 or mkword($12, $34) != $1234
            return 15
        if sgn(w1) != -1 or sgn(l1) != 1
            return 16
        return 0
    }
}""",
    "if/else, when and loops" to """
main {
    ubyte @shared count = 10
    word @shared signedstart = -5

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        uword sum = 0
        ubyte i
        for i in 1 to count {
            sum += i
        }
        if sum != 55
            return 1
        sum = 0
        for i in count downto 1 step -3 {
            sum += i
        }
        if sum != 22
            return 2
        word ws = 0
        word wi
        for wi in signedstart to 5 step 2 {
            ws += wi
        }
        if ws != 0
            return 3
        ubyte n = 0
        while n < count {
            n++
            if n == 7
                break
        }
        if n != 7
            return 4
        ubyte odds = 0
        n = 0
        do {
            n++
            if n % 2 == 0
                continue
            odds++
        } until n >= count
        if odds != 5
            return 5
        ubyte reps = 0
        repeat count {
            reps++
        }
        if reps != 10
            return 6
        if classify(-5) != 1 or classify(0) != 2 or classify(9) != 3
            return 7
        if weekday(3) != 30 or weekday(6) != 99 or weekday(1) != 10
            return 8
        n = 0
again:
        n++
        if n < 5
            goto again
        if n != 5
            return 9
        return 0
    }

    sub classify(byte value) -> ubyte {
        if value < 0
            return 1
        else if value == 0
            return 2
        else
            return 3
    }

    sub weekday(ubyte day) -> ubyte {
        when day {
            1 -> return 10
            2, 3 -> return day * 10
            4 to 5 -> return 50
            else -> return 99
        }
    }
}""",
    "subroutines with parameters and return values" to """
%option enable_floats

main {
    ubyte @shared seven = 7
    ubyte counter

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        if add3(1, 2, seven) != 10
            return 1
        if scale(-300, 4) != -1200
            return 2
        ubyte q, r
        q, r = divide(100, seven)
        if q != 14 or r != 2
            return 3
        if fib(15) != 610
            return 4
        if not isodd(seven) or isodd(8)
            return 5
        if twice(2.25) != 4.5
            return 6
        if longsum(100000, 200000) != 300000
            return 7
        repeat 5 {
            bump()
        }
        if counter != 5
            return 8
        return 0
    }

    sub bump() {
        counter++
    }

    sub add3(ubyte a, ubyte b, ubyte c) -> ubyte {
        return a + b + c
    }

    sub scale(word value, byte factor) -> word {
        return value * factor
    }

    sub divide(ubyte dividend, ubyte divisor) -> ubyte, ubyte {
        return dividend / divisor, dividend % divisor
    }

    sub fib(ubyte n) -> uword {
        uword a = 0
        uword b = 1
        repeat n {
            uword t = a + b
            a = b
            b = t
        }
        return a
    }

    sub isodd(ubyte value) -> bool {
        return value & 1 == 1
    }

    sub twice(float value) -> float {
        return value * 2.0
    }

    sub longsum(long a, long b) -> long {
        return a + b
    }
}""",
    "arrays" to """
%option enable_floats

main {
    ubyte @shared idx = 2
    ubyte[] bytes = [10, 20, 30, 40, 50]
    uword[] words = [1000, 2000, 3000, 4000]
    long[] longs = [-1, 100000, 7]
    float[] floats = [1.5, 2.5, 3.5]
    bool[] flags = [true, false, true]
    str[] names = ["alpha", "beta", "gamma"]
    ubyte[8] buffer

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        if bytes[idx] != 30 or bytes[4] != 50
            return 1
        if words[idx] != 3000 or words[idx+1] != 4000
            return 2
        if longs[idx-1] != 100000 or floats[idx] != 3.5
            return 3
        if flags[idx-1] or not flags[idx]
            return 4
        bytes[idx] = 99
        words[idx] += 5
        if bytes[2] != 99 or words[2] != 3005
            return 5
        uword total = 0
        ubyte b
        for b in bytes {
            total += b
        }
        if total != 219
            return 6
        if 40 not in bytes or 41 in bytes
            return 7
        if 4000 not in words or 100000 not in longs or 2.5 not in floats
            return 8
        ubyte i
        for i in 0 to len(buffer)-1 {
            buffer[i] = i * 3
        }
        if buffer[7] != 21
            return 9
        ^^ubyte name = names[idx]
        if name[1] != 'a' or name[4] != 'a' or name[5] != 0
            return 10
        return 0
    }
}""",
    "strings" to """
main {
    str hello = "hello"
    str other = "help!"
    str buffer = "?????????"

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        if hello == other or hello >= other or not (hello < other)
            return 1
        if 'l' not in hello or 'z' in hello
            return 2
        if hello[1] != 'e'
            return 3
        ubyte i
        for i in 0 to 5 {
            buffer[i] = hello[i]
        }
        if buffer != hello or buffer[6] != '?'
            return 4
        ubyte vowels = 0
        ubyte char
        for char in hello {
            if char in "aeiou"
                vowels++
        }
        if vowels != 2
            return 5
        hello[0] = 'j'
        if hello != "jello"
            return 6
        return 0
    }
}""",
    "typed pointers and structs" to """
main {
    ubyte[10] data
    uword[4] words = [100, 200, 300, 400]

    struct Node {
        ubyte kind
        uword value
        ^^Node next
    }

    ^^Node first
    ^^Node second

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ^^ubyte p = &data
        ubyte i
        for i in 0 to 9 {
            p^^ = i * 2
            p++
        }
        if data[9] != 18 or data[3] != 6
            return 1
        ^^uword wp = &words
        wp += 2
        if wp^^ != 300
            return 2
        wp[1] = 999
        if words[3] != 999
            return 3
        first = ^^Node: [1, 1111, 0]
        second = ^^Node: [2, 2222, 0]
        first.next = second
        if first.next.value != 2222 or first.next.kind != 2
            return 4
        second.value += 3
        uword total = 0
        ^^Node n = first
        while n != 0 {
            total += n.value
            n = n.next
        }
        if total != 3336
            return 5
        return 0
    }
}""",
    "global initializers, defer and builtins" to $$"""
main {
    ubyte @shared five = 5
    uword @shared computed = five * 100 + 3
    long @shared biglong = $12345678
    ^^ubyte slab = memory("scratch", 16, 0)
    ubyte deferflag

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ; no widening in Prog8: the ubyte expression wraps around before it is assigned
        if computed != 247
            return 1
        ubyte a = 1
        ubyte b = 2
        swap(a, b)
        if a != 2 or b != 1
            return 2
        if msw(biglong) != $1234 or lsw(biglong) != $5678
            return 3
        uword w = $abcd
        setlsb(w, $11)
        setmsb(w, $22)
        if w != $2211
            return 4
        poke(slab, 42)
        pokew(slab + 2, $beef)
        if peek(slab) != 42 or peekw(slab + 2) != $beef
            return 5
        ubyte carrybits = %10000001
        sys.clear_carry()
        rol(carrybits)
        rol(carrybits)
        if carrybits != %00000101
            return 6
        if defertest() != 1 or deferflag != 2
            return 7
        return 0
    }

    sub defertest() -> ubyte {
        deferflag = 1
        defer deferflag = 2
        return deferflag
    }
}""",
    "untyped pointers" to """
main {
    ubyte[16] buffer
    uword @shared wordoffset = 12
    long @shared longoffset = 13
    word @shared negativeoffset = -5
    str message = "hello"
    pointer[3] ptrs = [&buffer, 0, 0]
    pointer slab = memory("ptrslab", 32, 0)

    struct Node {
        ubyte kind
        uword value
        pointer data
        ^^Node next
    }

    ^^Node first
    ^^Node second

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        pointer p = &buffer
        @(p) = 11
        p[1] = 22
        p += 2
        @(p) = 33
        if buffer[0] != 11 or buffer[1] != 22 or buffer[2] != 33
            return 1
        pointer q = p + 5
        if q - p != 5 or p == 0 or not (q > p)
            return 2
        poke(slab, 7)
        pokew(slab + 4, 1234)
        if peek(slab) != 7 or peekw(slab + 4) != 1234
            return 3
        pointer m = message
        if m[4] != 'o'
            return 4
        first = ^^Node: [1, 1111, 0, 0]
        second = ^^Node: [2, 2222, 0, 0]
        first.next = second
        pointer anynode = first.next
        ^^Node back = anynode
        if back.value != 2222
            return 5
        anynode = first
        back = anynode
        if back.kind != 1
            return 6
        first.data = second
        ^^Node fromdata = first.data
        if fromdata.value != 2222
            return 7
        if kindof(anynode) != 1 or kindof(first.data) != 2
            return 8
        ^^Node viareturn = untyped(second)
        if viareturn.value != 2222
            return 9
        if ptrs[0] != &buffer or ptrs[1] != 0
            return 10
        ptrs[1] = ptrs[0] + 1
        ptrs[2] = second
        if @(ptrs[1]) != 22
            return 11
        ^^Node fromarray = ptrs[2]
        if fromarray.value != 2222
            return 12
        pointer routine = &answer
        if call(routine) != 4242
            return 13
        buffer[12] = 120
        buffer[13] = 130
        buffer[7] = 70
        if @(p + wordoffset - 2) != 120 or @(&buffer + longoffset) != 130
            return 14
        q = p + wordoffset
        q += negativeoffset
        if @(q - 2) != 70
            return 15
        return 0
    }

    sub kindof(^^Node node) -> ubyte {
        return node.kind
    }

    sub untyped(pointer address) -> pointer {
        return address
    }

    sub answer() -> uword {
        return 4242
    }
}"""
)
