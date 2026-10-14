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
import kotlin.io.path.writeText


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

    for ((name, program) in outputPrograms) {
        test("$name: compiles with the llvm64 library, whose console output goes through putchar") {
            for (optimize in listOf(false, true)) {
                withClue("optimize=$optimize") {
                    compiledLlFile(compileText(LlvmTarget(), optimize, program.source, outputDir)).readLines() shouldContain "declare i32 @putchar(i32)"
                }
            }
        }

        test("$name: gives the expected exit code and console output as a native arm64 macOS executable").config(enabledOrReasonIf = requiresNativeLlvm) {
            for (optimize in listOf(false, true)) {
                withClue("optimize=$optimize") {
                    val (exitCode, output) = buildAndRun(compiledLlFile(compileText(LlvmTarget(), optimize, program.source, outputDir)), program.input)
                    withClue(output) { exitCode shouldBe program.exitCode }
                    output shouldBe program.output
                }
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

/**
 * Builds the .ll file into an executable with clang -O2 and runs it with [input] on stdin;
 * returns its exit code and output, or clang's if that failed.
 */
private fun buildAndRun(llFile: Path, input: String = ""): Pair<Int, String> {
    val executable = llFile.resolveSibling(llFile.fileName.toString().removeSuffix(".ll"))
    val compiled = execute(checkNotNull(llvmBin).resolve("clang").toString(), "-O2", "-Wno-override-module", llFile.toString(), "-o", executable.toString())
    if (compiled.first != 0)
        return compiled
    val stdin = executable.resolveSibling("${executable.fileName}.stdin").also { it.writeText(input) }
    return execute(executable.toString(), stdin = stdin)
}

private fun execute(vararg command: String, stdin: Path? = null): Pair<Int, String> {
    val builder = ProcessBuilder(*command).redirectErrorStream(true)
    stdin?.let { builder.redirectInput(it.toFile()) }
    val process = builder.start()
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
}""",
    "jumps into other subroutines" to """
main {
    ubyte @shared counter

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        ; return f() of a subroutine f without parameters becomes a jump to f
        if pick(true) != 11 or pick(false) != 22
            return 1
        if widened() != 22
            return 2
        bump()
        if counter != 11
            return 3
        return 0
    }

    sub pick(bool first) -> ubyte {
        if first
            return eleven()
        return twentytwo()
    }

    sub eleven() -> ubyte {
        return 11
    }

    sub twentytwo() -> ubyte {
        return 22
    }

    sub widened() -> uword {
        return twentytwo()
    }

    sub bump() {
        counter += 10
        goto increment
    }

    sub increment() {
        counter++
    }
}""",
    "short-circuit conditions and the cx16 virtual registers" to $$"""
main {
    ubyte @shared upper = 'A'
    ubyte @shared lower = 'q'
    ubyte @shared zero = 0

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        if islower(upper) or not islower(lower)
            return 1
        if not isedge(zero) or isedge(upper)
            return 2
        cx16.r0 = 1234
        cx16.r1L = 5
        cx16.r1H = 6
        if cx16.r0 != 1234 or cx16.r1 != $0605 or cx16.r0r1sl != $060504d2
            return 3
        return 0
    }

    sub islower(ubyte char) -> bool {
        return char >= 'a' and char <= 'z'
    }

    sub isedge(ubyte char) -> bool {
        return char == 0 or char >= 250
    }
}""",
    "64-bit quad and uquad integers" to $$"""
%option enable_floats

main {
    quad @shared qmax = sys.MAX_QUAD
    quad @shared qmin = sys.MIN_QUAD
    uquad @shared umax = sys.MAX_UQUAD
    quad @shared qzero = 0
    uquad @shared uzero = 0
    quad @shared qminus1 = -1
    quad @shared q5e9 = 5000000000
    quad @shared qneg = -7000000000
    uquad @shared ucross = $1_0000_0001
    uquad @shared pattern = $ffff_ffff_ffff_fff6
    quad @shared qseven = 7
    uquad @shared useven = 7
    ubyte @shared n40 = 40
    uquad @shared ubelow = $7fff_ffff_0000_0005
    uquad @shared uabove = $8000_0000_0000_0005
    byte @shared bm5 = -5
    ubyte @shared ub200 = 200
    word @shared wm300 = -300
    uword @shared uw65535 = 65535
    long @shared lmin = -2147483648
    uquad @shared mixed = $1234_5678_8765_4321
    uquad @shared ubig = $8000_0000_0000_0800
    float @shared fneg = -123456789012.75
    float @shared fbig = 1.0e19
    quad[] @shared qarr = [-1, 5000000000, -9223372036854775808, 1234567890123456789]
    ubyte @shared idx = 2
    quad @shared needle = $2_2a05_f200
    ubyte[16] buffer
    quad @shared beyond53 = 9007199254740993
    uquad @shared highlow = $ffff_ffff_0000_0001

    sub start() {
        sys.exit(check())
    }

    sub check() -> ubyte {
        quad q = qmax + 1
        uquad u = umax + 1
        if q != sys.MIN_QUAD or qmin - 1 != sys.MAX_QUAD or -qmin != sys.MIN_QUAD or u != 0 or uzero - 1 != sys.MAX_UQUAD
            return 1
        if ucross * ucross != ($2_0000_0001 as uquad) or q5e9 * qneg != 1893488147419103232
            return 2
        if (pattern as quad) / qseven != -1 or (pattern as quad) % qseven != -3
            return 3
        if pattern / useven != ($2492_4924_9249_2490 as uquad) or pattern % useven != 6
            return 4
        if q5e9 / qzero != sys.MAX_QUAD or qneg / qzero != sys.MAX_QUAD or qneg % qzero != 0
            return 5
        if umax / uzero != sys.MAX_UQUAD or umax % uzero != 0 or qmin / qminus1 != sys.MIN_QUAD or qmin % qminus1 != 0
            return 6
        if qminus1 << n40 != -1099511627776 or umax << n40 != ($ffff_ff00_0000_0000 as uquad)
            return 7
        if qmin >> n40 != -8388608 or (qmin as uquad) >> n40 != ($80_0000 as uquad) or umax >> n40 != ($ff_ffff as uquad)
            return 8
        if uabove <= ubelow or (uabove as quad) >= (ubelow as quad) or uabove == ubelow or (uabove as long) != (ubelow as long)
            return 9
        q = bm5
        u = ub200
        if q != -5 or q + wm300 != -305 or (bm5 as uquad) != $ffff_ffff_ffff_fffb or u + uw65535 != (65735 as uquad) or (uw65535 as quad) != 65535
            return 10
        q = lmin
        if q != -2147483648 or (lmin as uquad) != $ffff_ffff_8000_0000
            return 11
        if (mixed as long) != -2023406815 or (mixed as word) != $4321 or (mixed as ubyte) != $21
            return 12
        float f = ubig as float
        if f != 9223372036854777856.0 or (f as uquad) != ubig
            return 13
        if (qneg as float) != -7000000000.0 or (fneg as quad) != -123456789012 or (fbig as uquad) != 10000000000000000000
            return 14
        if qarr[idx] != sys.MIN_QUAD or qarr[idx+1] != 1234567890123456789 or q5e9 not in qarr or needle in qarr
            return 15
        qarr[idx] = qneg
        if qarr[2] != -7000000000 or qneg not in qarr
            return 16
        if addq(q5e9, qneg) != -2000000000 or addq(qmax, 1) != sys.MIN_QUAD
            return 17
        q, u = two()
        if q != -14000000000 or u != $ffff_ffff_ffff_fffe
            return 18
        ; little-endian, like the arm64 host
        pokeq(&buffer, qneg)
        pokeq(&buffer + 8, umax - 1)
        if peekq(&buffer) != qneg or buffer[8] != $fe or buffer[15] != $ff
            return 19
        buffer[15] = $7f
        if peekq(&buffer + 8) != sys.MAX_QUAD - 1
            return 20
        if beyond53 % 10 != 3 or beyond53 - 9007199254740000 != 993 or (highlow as long) != 1 or highlow >> 32 != ($0_ffff_ffff as uquad)
            return 21
        return 0
    }

    sub addq(quad x, quad y) -> quad {
        return x + y
    }

    sub two() -> quad, uquad {
        return qneg * 2, umax - 1
    }
}"""
)


/** A complete program that uses the llvm64 library, with the exit code and the console output that it should give for the [input]. */
private class OutputProgram(val source: String, val output: String, val exitCode: Int = 0, val input: String = "")

private val outputPrograms = mapOf(
    "text output" to OutputProgram("""
%import textio
%import syslib
%zeropage basicsafe

main {
    sub start() {
        txt.print("Hello from Prog8 LLVM!\n")
        txt.chrout('X')
        txt.spc()
        txt.chrout('Y')
        txt.nl()
        txt.print("")
        txt.print("multi")
        txt.print("ple\n")
        txt.rvs_on()
        txt.print("reverse")
        txt.rvs_off()
        txt.color(2)
        txt.home()
        txt.plot(4, 2)
        txt.bell()
        txt.nl()
    }
}""", "Hello from Prog8 LLVM!\nX Y\nmultiple\n\u001b[7mreverse\u001b[0m\u001b[32m\u001b[H\u001b[2;4H\u0007\n"),

    "number formatting" to OutputProgram($$"""
%import textio
%zeropage basicsafe

main {
    long @shared minimum = -2147483648

    sub start() {
        txt.print_ub(255)
        txt.spc()
        txt.print_uw(65535)
        txt.spc()
        txt.print_i32(-123456)
        txt.spc()
        txt.print_ubhex($fa, true)
        txt.spc()
        txt.print_ubbin(%10101010, true)
        txt.nl()
        txt.print_ub0(7)
        txt.spc()
        txt.print_uw0(42)
        txt.spc()
        txt.print_b(-128)
        txt.spc()
        txt.print_w(-32768)
        txt.spc()
        txt.print_l(minimum)
        txt.spc()
        txt.print_u32(-1)
        txt.nl()
        txt.print_uwhex($beef, false)
        txt.spc()
        txt.print_uwbin($8001, false)
        txt.spc()
        txt.print_ulhex($12345678, true)
        txt.spc()
        txt.print_bool(true)
        txt.spc()
        txt.print_bool(false)
        txt.spc()
        txt.print(conv.str_uw(1000))
        txt.nl()
    }
}""", $$"255 65535 -123456 $fa %10101010\n007 00042 -128 -32768 -2147483648 4294967295\nbeef 1000000000000001 $12345678 true false 1000\n"),

    "quad number formatting" to OutputProgram($$"""
%import textio
%zeropage basicsafe

main {
    uquad @shared uzero = 0
    uquad @shared uone = 1
    uquad @shared unines = 9999999999999999999 as uquad
    uquad @shared uten19 = 10000000000000000000
    uquad @shared umax = sys.MAX_UQUAD
    quad @shared qzero = 0
    quad @shared qminus1 = -1
    quad @shared qbig = -5000000000
    quad @shared qmin = sys.MIN_QUAD
    quad @shared qmax = sys.MAX_QUAD
    uquad @shared pattern = $fedc_ba98_7654_3210
    uquad @shared topbit = $8000_0000_0000_0001

    sub start() {
        txt.print_u64(uzero)
        txt.spc()
        txt.print_u64(uone)
        txt.spc()
        txt.print_uq(unines)
        txt.spc()
        txt.print_uq(uten19)
        txt.spc()
        txt.print_u64(umax)
        txt.nl()
        txt.print_i64(qzero)
        txt.spc()
        txt.print_i64(qminus1)
        txt.spc()
        txt.print_q(qbig)
        txt.spc()
        txt.print_q(qmin)
        txt.spc()
        txt.print_i64(qmax)
        txt.nl()
        txt.print_uqhex(uone)
        txt.spc()
        txt.print_uqhex(pattern)
        txt.spc()
        txt.print_uqhex(umax)
        txt.nl()
        txt.print_uqbin(uone)
        txt.nl()
        txt.print_uqbin(topbit)
        txt.nl()
        txt.print_uqbin(umax)
        txt.nl()
        txt.print(conv.str_u64(umax))
        txt.spc()
        txt.print(conv.str_uq(uten19))
        txt.spc()
        txt.print(conv.str_i64(qmin))
        txt.spc()
        txt.print(conv.str_q(qbig))
        txt.spc()
        txt.print(conv.str_uqhex(topbit))
        txt.spc()
        txt.print(conv.str_uqbin(pattern))
        txt.nl()
        txt.print_ub(sys.SIZEOF_QUAD)
        txt.spc()
        txt.print_ub(sys.SIZEOF_UQUAD)
        txt.spc()
        txt.print_u64(sys.MIN_UQUAD)
        txt.nl()
    }
}""", "0 1 9999999999999999999 10000000000000000000 18446744073709551615\n" +
        "0 -1 -5000000000 -9223372036854775808 9223372036854775807\n" +
        "0000000000000001 fedcba9876543210 ffffffffffffffff\n" +
        "0".repeat(63) + "1\n" + "1" + "0".repeat(62) + "1\n" + "1".repeat(64) + "\n" +
        "18446744073709551615 10000000000000000000 -9223372036854775808 -5000000000 8000000000000001 " +
        "1111111011011100101110101001100001110110010101000011001000010000\n" +
        "8 8 0\n"),

    "memory routines and sys.exit" to OutputProgram($$"""
%import textio
%import syslib
%zeropage basicsafe

main {
    ubyte[8] source = [1, 2, 3, 4, 5, 6, 7, 8]
    ubyte[8] target
    uword[3] words

    sub start() {
        sys.memset(&target, len(target), $ee)
        print_target()
        sys.memcopy(&source, &target, 5)
        print_target()
        txt.print_b(sys.memcmp(&source, &target, 5))
        txt.spc()
        txt.print_b(sys.memcmp(&source, &target, 6))
        txt.spc()
        txt.print_b(sys.memcmp(&target, &source, 6))
        txt.nl()
        sys.memsetw(&words, len(words), $1234)
        txt.print_uwhex(words[0], false)
        txt.spc()
        txt.print_uwhex(words[2], false)
        txt.nl()
        sys.exit(0)
        txt.print("not reached\n")
    }

    sub print_target() {
        ubyte value
        for value in target {
            txt.print_ubhex(value, false)
            txt.spc()
        }
        txt.nl()
    }
}""", "ee ee ee ee ee ee ee ee \n01 02 03 04 05 ee ee ee \n0 -1 1\n1234 1234\n"),

    "sys.die" to OutputProgram("""
%import textio
%import syslib
%zeropage basicsafe

main {
    sub start() {
        txt.print("about to fail")
        sys.die(1, "error message")
        txt.print("not reached\n")
    }
}""", "about to fail\n\nPROGRAM DIED: error message\n", exitCode = 1),

    "console input" to OutputProgram("""
%import textio
%zeropage basicsafe

main {
    ubyte[81] buffer

    sub start() {
        txt.print_ub(txt.chrin())
        txt.nl()
        ^^ubyte name = txt.prompt("name? ")
        txt.print("hello ")
        txt.print(name)
        txt.print("!\n")
        txt.print_ub(txt.input_chars(buffer))
        txt.chrout(':')
        txt.print(buffer)
        txt.nl()
        txt.print(txt.readln())
        txt.nl()
        txt.print_ub(txt.input_chars(buffer))
        txt.nl()
        txt.print_ub(txt.chrin())
        txt.nl()
    }
}""", "65\nname? hello bc!\n5:World\nline two\n80\n0\n", input = "Abc\nWorld\r\nline two\n" + "x".repeat(90) + "\n"),

    "strings library" to OutputProgram("""
%import textio
%import strings
%zeropage basicsafe

main {
    str hello = "Hello, World"
    str buffer = "?" * 40
    str spaced = "  \t padded text \n "
    str words = "  the quick  brown\tfox "
    str csv = "a,b;c"
    pointer[6] parts

    sub start() {
        txt.print_ub(strings.length(hello))
        txt.spc()
        txt.print_ub(strings.strlen(""))
        txt.nl()
        strings.left(hello, 5, buffer)
        show(buffer)
        strings.right(hello, 5, buffer)
        show(buffer)
        strings.slice(hello, 7, 3, buffer)
        show(buffer)
        strings.substr(hello, 2, 0, buffer)
        show(buffer)
        txt.nl()

        ubyte idx
        bool found
        idx, found = strings.find(hello, 'o')
        txt.print_ub(idx)
        txt.print_bool(found)
        idx, found = strings.find(hello, 'z')
        txt.print_ub(idx)
        txt.print_bool(found)
        idx, found = strings.rfind(hello, 'o')
        txt.print_ub(idx)
        txt.print_bool(found)
        idx, found = strings.find_eol(spaced)
        txt.print_ub(idx)
        txt.print_bool(found)
        txt.print_bool(strings.contains(hello, 'W'))
        txt.print_bool(strings.strcontains(hello, 'w'))
        txt.nl()

        txt.print_ub(strings.copy(hello, buffer))
        show(buffer)
        txt.print_ub(strings.strcpy("abc", buffer))
        show(buffer)
        txt.print_ub(strings.ncopy(hello, buffer, 4))
        show(buffer)
        txt.print_ub(strings.append(buffer, "-tail"))
        show(buffer)
        txt.print_ub(strings.strcat(buffer, "!"))
        show(buffer)
        txt.print_ub(strings.nappend(buffer, "123456", 13))
        show(buffer)
        txt.nl()

        txt.print_b(strings.compare("abc", "abd"))
        txt.print_b(strings.compare("abc", "abc"))
        txt.print_b(strings.compare("abcd", "abc"))
        txt.print_b(strings.strcmp("", "a"))
        txt.print_b(strings.compare("\xff", "a"))
        txt.print_b(strings.compare_nocase("HeLLo", "hello"))
        txt.print_b(strings.compare_nocase("abc", "ABD"))
        txt.nl()

        void strings.copy(hello, buffer)
        txt.print_ub(strings.lower(buffer))
        show(buffer)
        txt.print_ub(strings.strupper(buffer))
        show(buffer)
        void strings.strlower(buffer)
        show(buffer)
        txt.chrout(strings.upperchar('q'))
        txt.chrout(strings.lowerchar('Q'))
        txt.chrout(strings.lowerchar('1'))
        txt.spc()
        txt.print_ub(strings.hash(hello))
        txt.spc()
        txt.print_ub(strings.hash("prog8"))
        txt.nl()

        txt.print_bool(strings.isdigit('5'))
        txt.print_bool(strings.isdigit('a'))
        txt.print_bool(strings.isxdigit('F'))
        txt.print_bool(strings.isxdigit('g'))
        txt.print_bool(strings.isupper('A'))
        txt.print_bool(strings.islower('A'))
        txt.print_bool(strings.isletter('z'))
        txt.print_bool(strings.isspace('\t'))
        txt.print_bool(strings.isprint(7))
        txt.nl()

        void strings.copy(spaced, buffer)
        strings.trim(buffer)
        show(buffer)
        void strings.copy(spaced, buffer)
        strings.strtrim(buffer)
        show(buffer)
        void strings.copy(spaced, buffer)
        strings.strip(buffer)
        show(buffer)
        void strings.copy(spaced, buffer)
        strings.rtrim(buffer)
        show(buffer)
        void strings.copy(spaced, buffer)
        strings.ltrim(buffer)
        show(buffer)
        void strings.copy("   ", buffer)
        strings.rtrim(buffer)
        show(buffer)
        show(strings.ltrimmed("   x "))
        show(strings.lstripped("\x01 y "))
        txt.nl()

        txt.print_bool(strings.startswith(hello, "Hell"))
        txt.print_bool(strings.startswith(hello, "hell"))
        txt.print_bool(strings.endswith(hello, "World"))
        txt.print_bool(strings.endswith("ld", "World"))
        txt.spc()
        txt.print_ub(strings.findstr(hello, "World"))
        txt.spc()
        txt.print_ub(strings.findstr(hello, "world"))
        txt.nl()

        ubyte count = strings.split(words, &parts, len(parts))
        txt.print_ub(count)
        ubyte i
        for i in 0 to count-1
            show(parts[i])
        txt.nl()
        ^^ubyte token = strings.next_token(csv, ",;")
        while token != 0 {
            show(token)
            token = strings.next_token(0, ",;")
        }
        txt.nl()
    }

    sub show(str s) {
        txt.chrout('[')
        txt.print(s)
        txt.chrout(']')
    }
}""", "12 0\n[Hello][World][Wor][]\n4true255false8true16truetruefalse\n12[Hello, World]3[abc]4[Hell]9[Hell-tail]10[Hell-tail!]13[Hell-tail!123]\n-101-110-1\n12[hello, world]12[HELLO, WORLD][hello, world]Qq1 3 165\ntruefalsetruefalsetruefalsetruetruefalse\n[padded text][padded text][padded text][  \t padded text][padded text \n ][][x ][y ]\ntruefalsetruefalse 7 255\n4[the][quick][brown][fox]\n[a][b][c]\n"),

    "math library" to OutputProgram($$"""
%import textio
%import math
%zeropage basicsafe

main {
    str text = "123456789"
    word @shared negative = -1234
    long @shared big = -100000

    sub start() {
        txt.print_ub(math.sin8u(0))
        txt.spc()
        txt.print_ub(math.cos8u(192))
        txt.spc()
        txt.print_b(math.sin8(64))
        txt.spc()
        txt.print_b(math.cos8(128))
        txt.spc()
        txt.print_ub(math.sinr8u(45))
        txt.spc()
        txt.print_b(math.cosr8(90))
        txt.nl()

        repeat 5 {
            txt.print_uw(math.rndw())
            txt.spc()
        }
        txt.nl()
        math.rndseed($1234, $5678)
        repeat 5 {
            txt.print_ub(math.rnd())
            txt.spc()
        }
        repeat 5 {
            txt.print_ub(math.randrange(10))
            txt.spc()
        }
        repeat 5 {
            txt.print_uw(math.randrangew(1000))
            txt.spc()
        }
        txt.nl()
        math.rndseed($c200, $3711)
        txt.print_uw(math.rndw())
        txt.nl()

        txt.print_ub(math.log2(1))
        txt.spc()
        txt.print_ub(math.log2(200))
        txt.spc()
        txt.print_ub(math.log2w(1000))
        txt.spc()
        txt.print_ub(math.log2l(100000))
        txt.spc()
        txt.print_l(math.mul32(60000, 50000))
        txt.spc()
        txt.print_ub(math.diff(3, 10))
        txt.spc()
        txt.print_uw(math.diffw(60000, 100))
        txt.spc()
        txt.print_l(math.diffl(-5, 100000))
        txt.nl()

        txt.print_uwhex(math.crc16(text, 9, 0, 0), true)
        txt.spc()
        txt.print_uwhex(math.crc16(text, 9, $ffff, 0), true)
        txt.spc()
        txt.print_ulhex(math.crc32(text, 9), true)
        txt.spc()
        txt.print_uwhex(math.crc16(text, 0, $ffff, $ffff), true)
        txt.nl()

        txt.print_ub(math.lerp(10, 200, 0))
        txt.spc()
        txt.print_ub(math.lerp(10, 200, 128))
        txt.spc()
        txt.print_ub(math.lerp(200, 10, 255))
        txt.spc()
        txt.print_uw(math.lerpw(1000, 60000, 32768))
        txt.spc()
        txt.print_uw(math.lerpw(60000, 1000, 65535))
        txt.spc()
        txt.print_ub(math.interpolate(50, 0, 100, 0, 200))
        txt.spc()
        txt.print_uw(math.interpolatew(500, 0, 1000, 1000, 3000))
        txt.nl()

        txt.print_uw(math.gcd(48, 180))
        txt.spc()
        txt.print_uw(math.gcd(0, 7))
        txt.spc()
        txt.print_uw(math.gcd(17, 5))
        txt.spc()
        txt.print_uw(math.lcm(4, 6))
        txt.spc()
        txt.print_uw(math.lcm(0, 6))
        txt.spc()
        txt.print_uw(math.lcm(21, 6))
        txt.spc()
        txt.print_uw(math.lcm(1000, 999))
        txt.nl()

        txt.print_uw(abs(negative))
        txt.spc()
        txt.print_w(min(negative, 5))
        txt.spc()
        txt.print_w(max(negative, 5))
        txt.spc()
        txt.print_w(clamp(negative, -1000, 1000))
        txt.spc()
        txt.print_b(sgn(negative))
        txt.spc()
        txt.print_b(sgn(big))
        txt.spc()
        txt.print_l(abs(big))
        txt.nl()
    }
}""", $$"128 127 127 -127 255 -127\n2457 21241 1451 8632 49784 \n24 122 135 121 16 6 7 6 0 3 88 713 31 129 416 \n2457\n0 7 9 16 -1294967296 7 59900 100005\n$31c3 $29b1 $cbf43926 $0000\n10 105 10 30500 1000 100 2000\n12 7 1 12 0 42 15960\n1234 -1234 5 -1000 -1 -1 100000\n"),

    "floats library" to OutputProgram("""
%import textio
%import floats
%import math
%zeropage basicsafe

main {
    float @shared half = 0.5
    float @shared two = 2.0
    float @shared minus = -2.5

    sub start() {
        show(floats.sin(floats.PI / 6))
        show(floats.cos(floats.PI / 3))
        show(floats.tan(floats.PI / 4))
        show(floats.asin(half))
        show(floats.asin(1.0))
        show(floats.asin(-1.0))
        show(floats.asin(7.0))
        show(floats.acos(half))
        show(floats.acos(-1.0))
        show(floats.atan(1.0))
        show(floats.atan2(1.0, 1.0))
        show(floats.atan2(1.0, -1.0))
        show(floats.atan2(-1.0, -1.0))
        show(floats.atan2(-1.0, 1.0))
        show(floats.atan2(1.0, 0.0))
        show(floats.atan2(-1.0, 0.0))
        show(floats.atan2(0.0, 0.0))
        txt.nl()
        show(sqrt(2.25))
        show(sqrt(two))
        show(floats.pow(two, 10.0))
        show(floats.exp(1.0))
        show(floats.exp(0.0))
        show(floats.log(floats.E))
        show(floats.ln(100.0))
        show(floats.log2(1024.0))
        txt.nl()
        show(floats.floor(minus))
        show(floats.ceil(minus))
        show(floats.round(minus))
        show(floats.round(2.5))
        show(floats.round(3.5))
        show(floats.round(2.4))
        show(floats.floor(2.7))
        show(floats.ceil(2.1))
        txt.nl()
        show(floats.rad(180.0))
        show(floats.deg(floats.PI))
        show(floats.secant(0.0))
        show(floats.csc(floats.PI / 2))
        show(floats.cot(floats.PI / 4))
        show(floats.minf(minus, two))
        show(floats.maxf(minus, two))
        show(floats.clampf(minus, -1.0, 1.0))
        show(floats.mod(7.5, 2.0))
        show(floats.lerp(10.0, 20.0, 0.25))
        show(math.lerpf_fast(10.0, 20.0, 0.75))
        show(math.interpolatef(5.0, 0.0, 10.0, 100.0, 200.0))
        show(floats.TWOPI)
        txt.nl()
        txt.print_bool(sys.MAX_FLOAT > 1.0e308)
        txt.print_bool(sys.MIN_FLOAT < -1.0e308)
        txt.print_bool(floats.EPSILON > 0.0)
        txt.nl()
    }

    sub show(float value) {
        ; prints the value in millionths, rounded
        txt.print_l(floats.round(value * 1000000.0) as long)
        txt.spc()
    }
}""", "500000 500000 1000000 523599 1570796 -1570796 1570796 1047198 3141593 785398 785398 2356194 3926991 5497787 1570796 4712389 0 \n1500000 1414214 1024000000 2718282 1000000 1000000 4605170 10000000 \n-3000000 -2000000 -2000000 2000000 4000000 2000000 2000000 3000000 \n3141593 180000000 1000000 1000000 1000000 -2500000 2000000 -1000000 1500000 12500000 17500000 150000000 6283185 \ntruetruetrue\n")
)
