package prog8tests.codecore

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import prog8.code.core.BaseDataType
import prog8.code.core.CpuType
import prog8.code.target.LlvmTarget
import prog8.code.target.LlvmToolLocator
import prog8.code.target.LlvmTripleProfile
import prog8.code.target.CompilationTargets
import prog8.code.target.getCompilationTargetByName
import java.io.File
import java.nio.file.Path
import java.nio.file.NoSuchFileException
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.io.path.writeText

class TestLlvmTripleProfile: FunSpec({

    val directory = tempdir().toPath()

    fun profileFile(name: String, properties: String): Path =
        directory.resolve("$name.properties").also { it.writeText(properties.trimIndent()) }

    val wasmLayout = "e-m:e-p:32:32-p10:8:8-p20:8:8-i64:64-i128:128-n32:64-S128-ni:1:10:20"
    fun validProfile(extra: String = "") = "triple = test-triple\ndatalayout = $wasmLayout\nc_long_size = 4\n$extra"
    val hasClang = LlvmToolLocator.clang() != null

    val embedded = mapOf(
        "wasm32-wasip1" to Triple("wasm32-unknown-wasip1", 32, 4),
        "wasm32-unknown-emscripten" to Triple("wasm32-unknown-emscripten", 32, 4),
        "arm64-apple-macosx" to Triple("arm64-apple-macosx", 64, 8),
        "x86_64-apple-macosx" to Triple("x86_64-apple-macosx", 64, 8),
        "x86_64-unknown-linux-gnu" to Triple("x86_64-unknown-linux-gnu", 64, 8),
        "aarch64-unknown-linux-gnu" to Triple("aarch64-unknown-linux-gnu", 64, 8),
        "riscv64-unknown-linux-gnu" to Triple("riscv64-unknown-linux-gnu", 64, 8),
        "riscv32-unknown-linux-gnu" to Triple("riscv32-unknown-linux-gnu", 32, 4),
        "armv7-unknown-linux-gnueabihf" to Triple("armv7-unknown-linux-gnueabihf", 32, 4),
        "x86_64-pc-windows-msvc" to Triple("x86_64-pc-windows-msvc", 64, 4),
        "x86_64-w64-windows-gnu" to Triple("x86_64-w64-windows-gnu", 64, 4),
        "riscv32-unknown-elf" to Triple("riscv32-unknown-unknown-elf", 32, 4),
        "armv7a-none-eabihf" to Triple("armv7-unknown-none-eabihf", 32, 4),
    )

    test("every embedded profile loads, with the pointer width of its data layout") {
        for ((name, values) in embedded) {
            val profile = LlvmTripleProfile.resolve(name)
            profile.name shouldBe name
            profile.triple shouldBe values.first
            profile.pointerBits shouldBe values.second
            profile.cLongSize shouldBe values.third
        }
    }

    test("the wasm32 profile builds a .wasm file with clang for WASI and runs it with a WASI runtime") {
        val profile = LlvmTripleProfile.resolve("wasm32-wasip1")
        profile.outputExtension shouldBe ".wasm"
        ("-Wl,--fatal-warnings" in profile.clangArgs) shouldBe true
        profile.runCommand shouldBe listOf("wasmtime", "--dir=.", "{file}")
        profile.strictIndirectSignatures shouldBe true
        profile.cMainSymbol shouldBe "__main_argc_argv"
        LlvmTripleProfile.resolve("arm64-apple-macosx").cMainSymbol shouldBe "main"
    }

    test("the default llvm64 triple and the native tests use the built-in llvm64 profile of the host, where there is one") {
        LlvmTarget.hostTriple64("Mac OS X", "aarch64") shouldBe "arm64-apple-macosx"
        LlvmTarget.hostTriple64("Mac OS X", "x86_64") shouldBe "x86_64-apple-macosx"
        LlvmTarget.hostTriple64("Linux", "amd64") shouldBe "x86_64-unknown-linux-gnu"
        LlvmTarget.hostTriple64("Linux", "aarch64") shouldBe "aarch64-unknown-linux-gnu"
        LlvmTarget.hostTriple64("Linux", "riscv64") shouldBe "riscv64-unknown-linux-gnu"
        LlvmTarget.hostTriple64("Linux", "arm") shouldBe null
        LlvmTarget.hostTriple64("Windows 11", "amd64") shouldBe "x86_64-pc-windows-msvc"
        LlvmTarget.hostTriple64("Windows 11", "aarch64") shouldBe null
        for (name in listOfNotNull(LlvmTarget.hostTriple64("Mac OS X", "aarch64"), LlvmTarget.hostTriple64("Mac OS X", "x86_64"), LlvmTarget.hostTriple64("Linux", "amd64"),
                LlvmTarget.hostTriple64("Linux", "aarch64"), LlvmTarget.hostTriple64("Linux", "riscv64"), LlvmTarget.hostTriple64("Windows 11", "amd64")))
            withClue(name) { LlvmTripleProfile.resolve(name).pointerBits shouldBe 64 }
    }

    test("the glibc Linux profiles link the math library after the program, other profiles add no link arguments") {
        for (name in listOf("x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "riscv64-unknown-linux-gnu", "riscv32-unknown-linux-gnu", "armv7-unknown-linux-gnueabihf"))
            withClue(name) { LlvmTripleProfile.resolve(name).linkArgs shouldBe listOf("-lm") }
        for (name in listOf("arm64-apple-macosx", "x86_64-apple-macosx", "wasm32-wasip1", "armv7a-none-eabihf"))
            withClue(name) { LlvmTripleProfile.resolve(name).linkArgs shouldBe emptyList() }
        LlvmTripleProfile.load(profileFile("linkargs", validProfile("link_args = -lm -lfoo"))).linkArgs shouldBe listOf("-lm", "-lfoo")
        shouldThrow<IllegalArgumentException> {
            LlvmTripleProfile.load(profileFile("linkargs-and-command", validProfile("link_command = gcc {object} -o {file}\nlink_args = -lm")))
        }.message!! shouldContain "takes the arguments of its link_args itself"
    }

    test("the bare metal ARM profile links with the Arm GNU Toolchain and runs on qemu with a timeout") {
        val profile = LlvmTripleProfile.resolve("armv7a-none-eabihf")
        profile.outputExtension shouldBe ".elf"
        profile.runTimeoutSeconds shouldBe 60
        profile.cMainSymbol shouldBe "main"
        profile.strictIndirectSignatures shouldBe false
        profile.runCommand!!.first() shouldBe "qemu-system-arm"
        val linkCommand = profile.linkCommand!!
        linkCommand.first() shouldBe (System.getenv("ARM_GNU_TOOLCHAIN") ?: "/Applications/ArmGNUToolchain/15.3.rel2/arm-none-eabi") + "/bin/arm-none-eabi-gcc"
        ("{object}" in linkCommand && "{file}" in linkCommand) shouldBe true
        LlvmTripleProfile.resolve("wasm32-wasip1").let {
            it.linkCommand shouldBe null
            it.runTimeoutSeconds shouldBe null
        }
    }

    test("the bare metal RISC-V and ARM profiles link newlib with their embedded boot and console code and run on qemu with a timeout") {
        val profile = LlvmTripleProfile.resolve("riscv32-unknown-elf")
        profile.outputExtension shouldBe ".elf"
        profile.runTimeoutSeconds shouldBe 60
        profile.runCommand!!.first() shouldBe "qemu-system-riscv32"
        profile.startupSources shouldBe listOf("riscv32-qemu-virt-boot.S", "qemu-semihosting-console.c")
        val linkCommand = profile.linkCommand!!
        linkCommand.first() shouldBe (System.getenv("RISCV_NONE_ELF_GCC") ?: "riscv-none-elf-gcc")
        ("{startup}" in linkCommand && "{object}" in linkCommand && "{file}" in linkCommand) shouldBe true
        val (boot, console) = profile.writeStartupSources(directory.resolve("program.elf"))
        boot.fileName.toString() shouldBe "program.elf.riscv32-qemu-virt-boot.S"
        boot.readText() shouldContain "_boot:"
        console.readText() shouldContain "__wrap__read"
        LlvmTripleProfile.resolve("armv7a-none-eabihf").let {
            it.startupSources shouldBe listOf("qemu-semihosting-console.c")
            it.writeStartupSources(directory.resolve("program.elf")).single().fileName.toString() shouldBe "program.elf.qemu-semihosting-console.c"
        }
    }

    test("a profile file's startup_source files are read from the profile's directory, and need {startup} in the link command") {
        val sources = directory.resolve("withstartup").createDirectories()
        sources.resolve("boot.S").writeText("; boot code")
        sources.resolve("console.c").writeText("/* console code */")
        val profileFile = sources.resolve("withstartup.properties").also {
            it.writeText(validProfile("link_command = gcc {startup} {object} -o {file}\nstartup_source = boot.S  console.c"))
        }
        LlvmTripleProfile.load(profileFile).writeStartupSources(directory.resolve("startup-test.elf")).map { it.readText() } shouldBe
            listOf("; boot code", "/* console code */")
        val loaded = LlvmTripleProfile.load(profileFile)
        sources.resolve("console.c").deleteExisting()
        shouldThrow<NoSuchFileException> { loaded.writeStartupSources(directory.resolve("failed-startup.elf")) }
        withClue("the startup source written before the failing one is removed") { directory.resolve("failed-startup.elf.boot.S").exists() shouldBe false }

        fun rejected(name: String, properties: String): String =
            shouldThrow<IllegalArgumentException> { LlvmTripleProfile.load(sources.resolve("$name.properties").also { it.writeText(properties) }) }.message!!
        rejected("noplaceholder", validProfile("link_command = gcc {object} -o {file}\nstartup_source = boot.S")) shouldContain "needs {startup} in its link_command"
        rejected("nolink", validProfile("startup_source = boot.S")) shouldContain "needs {startup} in its link_command"
        rejected("missing", validProfile("link_command = gcc {startup} {object} -o {file}\nstartup_source = boot.S missing.c")) shouldContain "'missing.c'"
    }

    test("the pointer width is the p: entry of the data layout, ignoring other address spaces, and 64 bits without one") {
        LlvmTripleProfile.load(profileFile("p32", validProfile())).pointerBits shouldBe 32
        val noPointerEntry = profileFile("p64", "triple = t\ndatalayout = e-m:o-p270:32:32-p271:32:32-p272:64:64-i64:64-n32:64-S128\nc_long_size = 8")
        LlvmTripleProfile.load(noPointerEntry).pointerBits shouldBe 64
    }

    test("invalid profiles are rejected with a message about the problem") {
        fun rejected(name: String, properties: String): String =
            shouldThrow<IllegalArgumentException> { LlvmTripleProfile.load(profileFile(name, properties)) }.message!!

        rejected("bigendian", "triple = t\ndatalayout = E-m:e-p:32:32\nc_long_size = 4") shouldContain "little-endian"
        rejected("badpointer", "triple = t\ndatalayout = e-p:x:32\nc_long_size = 4") shouldContain "pointer entry"
        rejected("longsize", "triple = t\ndatalayout = $wasmLayout\nc_long_size = 2") shouldContain "c_long_size 2"
        rejected("longtext", "triple = t\ndatalayout = $wasmLayout\nc_long_size = four") shouldContain "non-numeric c_long_size"
        rejected("notriple", "datalayout = $wasmLayout\nc_long_size = 4") shouldContain "missing 'triple'"
        rejected("nolayout", "triple = t\nc_long_size = 4") shouldContain "leaves out its datalayout or c_long_size"
        rejected("nolong", "triple = t\ndatalayout = $wasmLayout") shouldContain "leaves out its datalayout or c_long_size"
        rejected("unknownkey", validProfile("pointer_size = 4")) shouldContain "unknown keys"
        rejected("runcommand", validProfile("run_command = wasmtime")) shouldContain "{file}"
        rejected("mainsymbol", validProfile("c_main_symbol = not a name")) shouldContain "c_main_symbol"
        rejected("linkobject", validProfile("link_command = gcc -o {file}")) shouldContain "{object}, and {file} or {stem}"
        rejected("linkfile", validProfile("link_command = gcc {object}")) shouldContain "{object}, and {file} or {stem}"
        rejected("timeouttext", validProfile("run_timeout_seconds = soon")) shouldContain "non-numeric run_timeout_seconds"
        rejected("timeoutzero", validProfile("run_timeout_seconds = 0")) shouldContain "positive"
    }

    test("clang_args expand environment variables, with an optional :- default, and an unset one without a default is an error") {
        val env = mapOf("SYSROOT" to "/opt/sysroot")
        LlvmTripleProfile.expandEnvironment(listOf("--target=wasm32", $$"--sysroot=${SYSROOT}/x"), env::get) shouldBe
            listOf("--target=wasm32", "--sysroot=/opt/sysroot/x")
        LlvmTripleProfile.expandEnvironment(listOf($$"--sysroot=${SYSROOT:-/default}", $$"${UNSET:-/default}/y", $$"${UNSET:-}"), env::get) shouldBe
            listOf("--sysroot=/opt/sysroot", "/default/y", "")
        shouldThrow<IllegalArgumentException> {
            LlvmTripleProfile.expandEnvironment(listOf($$"${PROG8_NO_SUCH_VARIABLE}"), env::get)
        }.message!! shouldContain "PROG8_NO_SUCH_VARIABLE"
    }

    test("an unset environment variable in clang_args or link_command only fails when they are used") {
        val profile = LlvmTripleProfile.load(profileFile("envlater", validProfile($$"clang_args = --sysroot=${PROG8_NO_SUCH_VARIABLE}")))
        shouldThrow<IllegalArgumentException> { profile.clangArgs }.message!! shouldContain "PROG8_NO_SUCH_VARIABLE"
        val linking = LlvmTripleProfile.load(profileFile("linklater", validProfile($$"link_command = ${PROG8_NO_SUCH_VARIABLE:-gcc} {object} -o {file} ${PROG8_NO_SUCH_VARIABLE}")))
        shouldThrow<IllegalArgumentException> { linking.linkCommand }.message!! shouldContain "PROG8_NO_SUCH_VARIABLE"
    }

    test("the run command gets the program arguments at {args}, as qemu semihosting arg= entries at {semihosting_args}, or else at the end") {
        LlvmTripleProfile.runCommandFor(null, "/p/x", listOf("a", "b c")) shouldBe listOf("/p/x", "a", "b c")
        LlvmTripleProfile.runCommandFor(listOf("wasmtime", "--dir=.", "{file}"), "/p/x.wasm", listOf("-v", "")) shouldBe
            listOf("wasmtime", "--dir=.", "/p/x.wasm", "-v", "")
        LlvmTripleProfile.runCommandFor(listOf("run", "{file}", "{args}", "--end"), "/p/x", listOf("a")) shouldBe listOf("run", "/p/x", "a", "--end")
        LlvmTripleProfile.runCommandFor(listOf("run", "{file}", "{args}"), "/p/x", emptyList()) shouldBe listOf("run", "/p/x")
        LlvmTripleProfile.runCommandFor(listOf("qemu", "-semihosting-config", "enable=on{semihosting_args}", "-kernel", "{file}"), "/p,q/x.elf",
            listOf("plain", "two words", "", "a,b")) shouldBe
            listOf("qemu", "-semihosting-config", "enable=on,arg=/p,,q/x.elf,arg=plain,arg=\"two words\",arg=\"\",arg=a,,b", "-kernel", "/p,q/x.elf")
        shouldThrow<IllegalArgumentException> {
            LlvmTripleProfile.runCommandFor(listOf("qemu", "enable=on{semihosting_args}"), "/p/x.elf", listOf("say \"hi\""))
        }.message!! shouldContain "double quote"
        LlvmTripleProfile.resolve("armv7a-none-eabihf").runCommand!!.any { "{semihosting_args}" in it } shouldBe true
        LlvmTripleProfile.resolve("riscv32-unknown-elf").runCommand!!.any { "{semihosting_args}" in it } shouldBe true
    }

    test("a triple is looked up as a file path first, then as an embedded profile") {
        val file = profileFile("wasm32-wasip1", validProfile())
        LlvmTripleProfile.resolve(file.toString()).triple shouldBe "test-triple"
        LlvmTripleProfile.resolve("wasm32-wasip1").triple shouldBe "wasm32-unknown-wasip1"
        shouldThrow<IllegalArgumentException> { LlvmTripleProfile.resolve("prog8-no-such-triple") }.message!! shouldContain "unknown llvm triple"
    }

    // LLVM versions differ in entries that Prog8's IR doesn't use: i128 alignment, the layouts of other address spaces
    // than the default one, such as the p270-p272 of x86 and newer AArch64, and function pointer alignment (Fn32), which clang 18 leaves out
    fun usedLayout(layout: String) = layout.split('-').filterNot { it.startsWith("i128:") || it.startsWith("Fn") || it.matches(Regex("p[1-9][0-9]*:.*")) }

    test("the data layout and C long size of every embedded profile are what clang reports for it, apart from entries that Prog8 doesn't use").config(enabled = hasClang) {
        for (name in embedded.keys) {
            val profile = LlvmTripleProfile.resolve(name)
            val reported = LlvmTripleProfile.askClang(profile.triple, profile.clangArgs)
            withClue("$name: profile ${profile.dataLayout}, clang ${reported.dataLayout}") {
                usedLayout(profile.dataLayout) shouldBe usedLayout(reported.dataLayout)
                profile.cLongSize shouldBe reported.cLongSize
                profile.cIntSize shouldBe reported.cIntSize
            }
        }
    }

    test("the data layout comparison only leaves out the entries that differ between LLVM versions") {
        usedLayout("e-m:o-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-n32:64-S128-Fn32") shouldBe usedLayout("e-m:o-i64:64-i128:128-n32:64-S128")
        usedLayout("e-m:e-p:32:32-p10:8:8-p20:8:8-i64:64-i128:128-n32:64-S128-ni:1:10:20") shouldBe usedLayout("e-m:e-p:32:32-p10:8:8-p20:8:8-i64:64-n32:64-S128-ni:1:10:20")
        usedLayout("e-m:e-p:32:32-i64:64-n32-S128") shouldNotBe usedLayout("e-m:e-p:64:64-i64:64-n32-S128")
        usedLayout("e-m:e-i64:64-n32:64-S128") shouldNotBe usedLayout("e-m:e-i64:32-n32:64-S128")
    }

    test("a triple without a profile gets its normalized name, data layout and C long size from clang").config(enabled = hasClang) {
        val musl = LlvmTripleProfile.resolve("aarch64-linux-musl")
        musl.name shouldBe "aarch64-linux-musl"
        musl.triple shouldBe "aarch64-unknown-linux-musl"
        musl.pointerBits shouldBe 64
        musl.cLongSize shouldBe 8
        musl.clangArgs shouldBe listOf("--target=aarch64-linux-musl")
        LlvmTripleProfile.resolve("x86_64-pc-windows-msvc").cLongSize shouldBe 4
        LlvmTripleProfile.resolve("i686-unknown-linux-gnu").pointerBits shouldBe 32
        shouldThrow<IllegalArgumentException> { LlvmTripleProfile.resolve("aarch64_be-linux-gnu") }.message!! shouldContain "little-endian"
        shouldThrow<IllegalArgumentException> { LlvmTripleProfile.resolve("prog8-no-such-triple") }.message!! shouldContain "clang rejects it"
    }

    test("a profile file can leave out its data layout and C long size, which clang then supplies for its triple and clang_args").config(enabled = hasClang) {
        val profile = LlvmTripleProfile.load(profileFile("partial", "triple = riscv32-unknown-elf\nclang_args = -march=rv32imac -mabi=ilp32\nrun_command = qemu {file}"))
        profile.triple shouldBe "riscv32-unknown-elf"
        profile.dataLayout shouldBe "e-m:e-p:32:32-i64:64-n32-S128"
        profile.cLongSize shouldBe 4
        profile.cIntSize shouldBe 4
        profile.runCommand shouldBe listOf("qemu", "{file}")
        LlvmTripleProfile.load(profileFile("partiallong", "triple = x86_64-pc-windows-msvc\ndatalayout = $wasmLayout")).let {
            it.dataLayout shouldBe wasmLayout
            it.cLongSize shouldBe 4
        }
    }

    test("the new keys have defaults that keep the behaviour of profiles without them") {
        for (name in embedded.keys) {
            val profile = LlvmTripleProfile.resolve(name)
            withClue(name) {
                profile.cIntSize shouldBe 4
                profile.clang shouldBe null
                profile.libcLacks shouldBe emptySet()
                profile.ioRanges shouldBe emptyList()
                profile.ioAllAddresses shouldBe false
                profile.programArgs shouldBe true
            }
        }
    }

    test("c_int_size, libc_lacks, io_ranges and program_args are read from a profile file") {
        val profile = LlvmTripleProfile.load(profileFile("newkeys", validProfile(
            $$"c_int_size = 2\nlibc_lacks = opendir  readdir closedir\nio_ranges = $d000-$dfff, 0-1 ,53280, 0x10\nprogram_args = false")))
        profile.cIntSize shouldBe 2
        profile.libcLacks shouldBe setOf("opendir", "readdir", "closedir")
        profile.ioRanges shouldBe listOf(0xd000u..0xdfffu, 0u..1u, 53280u..53280u, 16u..16u)
        profile.ioAllAddresses shouldBe false
        profile.programArgs shouldBe false
        LlvmTripleProfile.load(profileFile("allio", validProfile("io_ranges = *\nc_int_size = 4\nprogram_args = true"))).let {
            it.ioAllAddresses shouldBe true
            it.ioRanges shouldBe emptyList()
            it.cIntSize shouldBe 4
            it.programArgs shouldBe true
        }
        LlvmTripleProfile.C_FUNCTIONS.containsAll(listOf("fopen", "putchar", "usleep", "printf", "tan", "atan2f")) shouldBe true
    }

    test("invalid values of the new keys are rejected") {
        fun rejected(name: String, properties: String): String =
            shouldThrow<IllegalArgumentException> { LlvmTripleProfile.load(profileFile(name, properties)) }.message!!
        rejected("intsize", validProfile("c_int_size = 8")) shouldContain "c_int_size 8"
        rejected("inttext", validProfile("c_int_size = two")) shouldContain "non-numeric c_int_size"
        rejected("lacks", validProfile("libc_lacks = fopen no_such_function")) shouldContain "no_such_function"
        rejected("iohex", validProfile($$"io_ranges = $zz")) shouldContain $$"invalid address '$zz'"
        rejected("iothree", validProfile("io_ranges = 1-2-3")) shouldContain "invalid range '1-2-3'"
        rejected("iobackwards", validProfile("io_ranges = 5-1")) shouldContain "ends before it starts"
        rejected("ioallmixed", validProfile("io_ranges = *, 1")) shouldContain "invalid address '*'"
        rejected("args", validProfile("program_args = yes")) shouldContain "program_args 'yes'"
    }

    test("run_command and clang expand environment variables on use, keeping the {file} and {args} placeholders") {
        val profile = LlvmTripleProfile.load(profileFile("runenv", validProfile(
            $$"run_command = ${PROG8_NO_SUCH_VARIABLE:-qemu} -kernel {file} {args}\nclang = ${PROG8_NO_SUCH_VARIABLE:-/opt/x}/bin/clang")))
        profile.runCommand shouldBe listOf("qemu", "-kernel", "{file}", "{args}")
        LlvmTripleProfile.runCommandFor(profile.runCommand, "/p/x", listOf("a")) shouldBe listOf("qemu", "-kernel", "/p/x", "a")
        profile.clang shouldBe "/opt/x/bin/clang"
        val unset = LlvmTripleProfile.load(profileFile("runenvunset", validProfile($$"run_command = ${PROG8_NO_SUCH_VARIABLE} {file}\nclang = ${PROG8_NO_SUCH_VARIABLE}")))
        shouldThrow<IllegalArgumentException> { unset.runCommand }.message!! shouldContain "PROG8_NO_SUCH_VARIABLE"
        shouldThrow<IllegalArgumentException> { unset.clang }.message!! shouldContain "PROG8_NO_SUCH_VARIABLE"
    }

    test("a profile with program_args = false rejects program arguments") {
        val profile = LlvmTripleProfile.load(profileFile("noargs", "triple = t\ndatalayout = $wasmLayout\nc_long_size = 4\nprogram_args = false"))
        val target = LlvmTarget(4, profile)
        target.launchEmulator(1, directory.resolve("noargs.ll"), quiet = true, programArgs = listOf("a")) shouldBe null
        target.programArgsRejected() shouldContain "program_args = false"
    }

    test("a profile with its datalayout and c_long_size doesn't ask its clang, one without them names the missing clang") {
        val missing = directory.resolve("no such dir").resolve("clang").invariantSeparatorsPathString
        LlvmTripleProfile.load(profileFile("noclangneeded", validProfile("clang = $missing"))).let {
            it.cIntSize shouldBe 4
            it.clang shouldBe missing
            shouldThrow<IllegalArgumentException> { it.resolveClang(null) }.message!! shouldContain "the clang '$missing' of llvm triple profile"
        }
        shouldThrow<IllegalArgumentException> {
            LlvmTripleProfile.load(profileFile("clangneeded", "triple = t\ndatalayout = $wasmLayout\nclang = $missing"))
        }.message!! shouldContain "the clang '$missing' of llvm triple profile"
    }

    test("the clang is the -clang option, then the profile's clang, then the default one, and answers for the profile")
        .config(enabled = !System.getProperty("os.name").startsWith("Windows")) {
        val bin = directory.resolve("fakellvm").createDirectories()
        fun fakeClang(name: String, intType: String, intSize: Int): Path = bin.resolve(name).also {
            it.writeText(listOf("#!/bin/sh", "cat > /dev/null",
                "echo 'target datalayout = \"e-m:e-p:16:8-i16:8-i32:8-i64:8-f32:8-f64:8-a:8-Fi8-n8\"'",
                "echo 'target triple = \"fake\"'",
                "echo '@prog8_c_long_size = dso_local global i32 4, align 1'",
                "echo '@prog8_c_int_size = dso_local global $intType $intSize, align 1'").joinToString("\n", postfix = "\n"))
            it.toFile().setExecutable(true)
        }
        val profileClang = fakeClang("profile-clang", "i16", 2)
        val optionClang = fakeClang("option-clang", "i32", 4)
        val llvmAs = bin.resolve("llvm-as").also { it.writeText("#!/bin/sh\n"); it.toFile().setExecutable(true) }
        LlvmToolLocator.resolveClang(optionClang.toString(), profileClang.toString(), "p") shouldBe optionClang
        LlvmToolLocator.resolveClang(null, profileClang.toString(), "p") shouldBe profileClang
        LlvmToolLocator.resolveClang(null, null, "p") shouldBe LlvmToolLocator.clang()
        LlvmToolLocator.locate("prog8-no-such-clang") shouldBe null
        LlvmToolLocator.besideClang("llvm-as", profileClang) shouldBe llvmAs
        shouldThrow<IllegalArgumentException> { LlvmToolLocator.resolveClang("/prog8/no/such/clang", null, null) }.message!! shouldContain "-clang"
        shouldThrow<IllegalArgumentException> { LlvmToolLocator.resolveClang(null, "/prog8/no/such/clang", "myprofile") }.message!! shouldContain
            "the clang '/prog8/no/such/clang' of llvm triple profile 'myprofile' was not found"

        val file = profileFile("fakeclang", "triple = fake\nclang = $profileClang")
        val profile = LlvmTripleProfile.load(file)
        profile.cIntSize shouldBe 2
        profile.cLongSize shouldBe 4
        profile.pointerBits shouldBe 16
        profile.resolveClang(null) shouldBe profileClang
        profile.resolveClang(optionClang.toString()) shouldBe optionClang
        LlvmTripleProfile.load(file, optionClang.toString()).cIntSize shouldBe 4
        (getCompilationTargetByName("llvm32", "wasm32-wasip1", clang = optionClang.toString()) as LlvmTarget).let {
            it.clangOption shouldBe optionClang.toString()
            it.clang() shouldBe optionClang
        }
    }

    test("-clang is only accepted for the llvm targets") {
        shouldThrow<IllegalArgumentException> { getCompilationTargetByName("cx16", clang = "clang") }.message!! shouldContain "-clang"
        (getCompilationTargetByName("llvm", clang = "/x/clang") as LlvmTarget).clangOption shouldBe "/x/clang"
        LlvmTarget(4).clangOption shouldBe null
    }

    test("the llvm32 target defaults to wasm32 with 4-byte pointers, the llvm64 target to arm64 macOS with 8-byte pointers") {
        val llvm32 = LlvmTarget(4)
        llvm32.name shouldBe LlvmTarget.NAME32
        llvm32.cpu shouldBe CpuType.LLVM32
        llvm32.cpu.isLlvm shouldBe true
        llvm32.POINTER_MEM_SIZE shouldBe 4u
        llvm32.targetTriple shouldBe "wasm32-unknown-wasip1"

        val llvm64 = LlvmTarget()
        llvm64.name shouldBe LlvmTarget.NAME64
        llvm64.cpu shouldBe CpuType.LLVM64
        llvm64.POINTER_MEM_SIZE shouldBe 8u
        llvm64.targetTriple shouldBe (LlvmTarget.hostTriple64(System.getProperty("os.name"), System.getProperty("os.arch")) ?: "arm64-apple-macosx")
        LlvmTarget(8, LlvmTripleProfile.resolve("arm64-apple-macosx")).dataLayout shouldBe "e-m:o-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-n32:64-S128-Fn32"
    }

    val mosLayout = "e-m:e-p:16:8-p1:8:8-i16:8-i32:8-i64:8-f32:8-f64:8-a:8-Fi8-n8"
    val mosProfiles = mapOf("mos-sim" to "mos-sim-clang", "mos-c64" to "mos-c64-clang")

    test("the llvm-mos profiles load without running clang, with 16-bit pointers and C int, and name the clang of LLVM_MOS") {
        for ((name, clang) in mosProfiles) {
            val profile = LlvmTripleProfile.resolve(name)
            withClue(name) {
                profile.triple shouldBe "mos"
                profile.dataLayout shouldBe mosLayout
                profile.pointerBits shouldBe 16
                profile.cIntSize shouldBe 2
                profile.cLongSize shouldBe 4
                profile.programArgs shouldBe false
                if (System.getenv("LLVM_MOS") == null)
                    shouldThrow<IllegalArgumentException> { profile.clang }.message!! shouldContain "LLVM_MOS"
                else
                    profile.clang shouldBe System.getenv("LLVM_MOS") + "/bin/" + clang
                profile.libcLacks.containsAll(listOf("opendir", "readdir", "usleep", "strtod", "sinf", "sqrt")) shouldBe true
                ("putchar" in profile.libcLacks) shouldBe false
            }
        }
        LlvmTripleProfile.resolve("mos-sim").let {
            it.outputExtension shouldBe ".sim"
            it.ioRanges shouldBe listOf(0xfff0u..0xffffu)
            it.libcLacks.containsAll(listOf("fopen", "fclose", "remove")) shouldBe true
        }
        LlvmTripleProfile.resolve("mos-c64").let {
            it.outputExtension shouldBe ".prg"
            it.ioRanges shouldBe listOf(0u..1u, 0xd000u..0xdfffu)
            ("fopen" in it.libcLacks) shouldBe false
        }
    }

    test("the data layout and C int and long sizes of the llvm-mos profiles are what their clang reports").config(enabled = System.getenv("LLVM_MOS") != null) {
        for (name in mosProfiles.keys) {
            val profile = LlvmTripleProfile.resolve(name)
            val clang = profile.resolveClang(null).shouldNotBeNull()
            val reported = LlvmTripleProfile.askClang(profile.triple, profile.clangArgs, clang)
            withClue(name) {
                usedLayout(profile.dataLayout) shouldBe usedLayout(reported.dataLayout)
                profile.cLongSize shouldBe reported.cLongSize
                profile.cIntSize shouldBe reported.cIntSize
            }
        }
    }

    test("the z80-test profile loads without running clang, with 16-bit pointers and C int, and the z88dk tool environment") {
        val profile = LlvmTripleProfile.resolve("z80-test")
        profile.triple shouldBe "z80-unknown-none-z88dk"
        profile.pointerBits shouldBe 16
        profile.cIntSize shouldBe 2
        profile.cLongSize shouldBe 4
        profile.programArgs shouldBe false
        profile.outputExtension shouldBe ".bin"
        profile.clangArgs shouldBe listOf("--target=z80-unknown-none-z88dk")
        profile.libcLacks.containsAll(listOf("qsort", "free", "opendir", "strtod", "sqrtf")) shouldBe true
        (profile.libcLacks intersect setOf("putchar", "getchar", "fopen", "strcmp", "snprintf", "exit")) shouldBe emptySet()
        profile.getcharSymbol shouldBe "fgetc_cons"
        val z88dk = mapOf("Z88DK" to "/opt/z88dk")
        profile.tools.directories { z88dk[it] }.map { it.invariantSeparatorsPathString } shouldBe listOf("/opt/z88dk/bin")
        profile.tools.variables { z88dk[it] } shouldBe mapOf("ZCCCFG" to "/opt/z88dk/lib/config")
        shouldThrow<IllegalArgumentException> { profile.tools.directories { null } }.message!! shouldContain "Z88DK"
    }

    test("the data layout and C int and long sizes of the z80-test profile are what its clang reports")
        .config(enabled = System.getenv("LLVM_Z80") != null && System.getenv("Z88DK") != null) {
        val profile = LlvmTripleProfile.resolve("z80-test")
        val reported = LlvmTripleProfile.askClang(profile.triple, profile.clangArgs, profile.resolveClang(null).shouldNotBeNull(), profile.tools)
        usedLayout(profile.dataLayout) shouldBe usedLayout(reported.dataLayout)
        profile.cLongSize shouldBe reported.cLongSize
        profile.cIntSize shouldBe reported.cIntSize
    }

    test("the llvm16 target has 2-byte pointers, 4-byte floats and real addresses, and defaults to the mos-sim profile") {
        val llvm16 = LlvmTarget(2)
        llvm16.name shouldBe LlvmTarget.NAME16
        llvm16.cpu shouldBe CpuType.LLVM16
        llvm16.cpu.isLlvm shouldBe true
        llvm16.POINTER_MEM_SIZE shouldBe 2u
        llvm16.FLOAT_MEM_SIZE shouldBe 4u
        llvm16.memorySize(BaseDataType.FLOAT) shouldBe 4
        llvm16.FLOAT_MAX_POSITIVE shouldBe Float.MAX_VALUE.toDouble()
        llvm16.FLOAT_MAX_NEGATIVE shouldBe -Float.MAX_VALUE.toDouble()
        llvm16.convertFloatToBytes(1.5) shouldBe listOf<UByte>(0u, 0u, 0xc0u, 0x3fu)
        llvm16.convertBytesToFloat(listOf(0u, 0u, 0xc0u, 0x3fu)) shouldBe 1.5
        llvm16.realAddresses shouldBe true
        llvm16.supportsFloats shouldBe false
        llvm16.hasFixedMemoryAddresses shouldBe false
        llvm16.splitPointerArrays shouldBe false
        llvm16.profile.name shouldBe "mos-sim"
        llvm16.targetTriple shouldBe "mos"
        llvm16.isIOAddress(0xfff0u) shouldBe true
        llvm16.isIOAddress(0xffffu) shouldBe true
        llvm16.isIOAddress(0xd020u) shouldBe false
        val c64 = getCompilationTargetByName("llvm16", "mos-c64") as LlvmTarget
        c64.isIOAddress(1u) shouldBe true
        c64.isIOAddress(0xd020u) shouldBe true
        c64.isIOAddress(2u) shouldBe false
        c64.isIOAddress(0xe000u) shouldBe false
        for (target in listOf(LlvmTarget(4), LlvmTarget(8))) {
            withClue(target.name) {
                target.FLOAT_MEM_SIZE shouldBe 8u
                target.FLOAT_MAX_POSITIVE shouldBe Double.MAX_VALUE
                target.convertFloatToBytes(1.5).size shouldBe 8
                target.realAddresses shouldBe false
                target.supportsFloats shouldBe true
                target.isIOAddress(0xd020u) shouldBe false
            }
        }
        val allIO = LlvmTripleProfile.load(profileFile("allio16", "triple = mos\ndatalayout = $mosLayout\nc_long_size = 4\nio_ranges = *"))
        LlvmTarget(2, allIO).isIOAddress(0x1234u) shouldBe true
    }

    test("a triple whose pointer width doesn't match the target is rejected") {
        shouldThrow<IllegalArgumentException> { LlvmTarget(4, LlvmTripleProfile.resolve("x86_64-unknown-linux-gnu")) }.message!! shouldContain "64-bit pointers"
        shouldThrow<IllegalArgumentException> { LlvmTarget(8, LlvmTripleProfile.resolve("wasm32-wasip1")) }.message!! shouldContain "32-bit pointers"
        shouldThrow<IllegalArgumentException> { LlvmTarget(2, LlvmTripleProfile.resolve("wasm32-wasip1")) }.message!! shouldContain "16-bit pointers"
        shouldThrow<IllegalArgumentException> { LlvmTarget(4, LlvmTripleProfile.resolve("mos-sim")) }.message!! shouldContain "16-bit pointers"
    }

    test("targets by name: llvm32, llvm64 and llvm take a triple, the other targets don't") {
        getCompilationTargetByName("llvm32").name shouldBe LlvmTarget.NAME32
        getCompilationTargetByName("llvm16").name shouldBe LlvmTarget.NAME16
        (LlvmTarget.NAME16 in CompilationTargets) shouldBe true
        (getCompilationTargetByName("LLVM16", "mos-c64") as LlvmTarget).profile.name shouldBe "mos-c64"
        (getCompilationTargetByName("llvm32", "armv7-unknown-linux-gnueabihf") as LlvmTarget).targetTriple shouldBe "armv7-unknown-linux-gnueabihf"
        (getCompilationTargetByName("llvm", "x86_64-unknown-linux-gnu") as LlvmTarget).targetTriple shouldBe "x86_64-unknown-linux-gnu"
        shouldThrow<IllegalArgumentException> { getCompilationTargetByName("cx16", "wasm32-wasip1") }
        shouldThrow<IllegalArgumentException> { getCompilationTargetByName("llvm32", "x86_64-unknown-linux-gnu") }
    }

    test("tool_path and tool_env are parsed, expanded on use, and an unset variable in them names the profile") {
        val profile = LlvmTripleProfile.load(profileFile("tools", validProfile($$"tool_path = ${PROG8_TOOLS}/bin ~/tools\ntool_env = ZCCCFG=${PROG8_TOOLS}/lib/config EMPTY= A_1=x=y")))
        val env = mapOf("PROG8_TOOLS" to "/opt/z88dk")
        val home = System.getProperty("user.home")
        profile.tools.directories(env::get) shouldBe listOf(Path.of("/opt/z88dk/bin"), Path.of("$home/tools"))
        profile.tools.variables(env::get) shouldBe mapOf("ZCCCFG" to "/opt/z88dk/lib/config", "EMPTY" to "", "A_1" to "x=y")
        shouldThrow<IllegalArgumentException> { profile.tools.directories { null } }.message shouldBe
            "environment variable PROG8_TOOLS used in the tool_path of llvm triple profile '${directory.resolve("tools.properties")}' is not set"
        shouldThrow<IllegalArgumentException> { profile.tools.variables { null } }.message!! shouldContain "the tool_env of llvm triple profile"
        val unset = LlvmTripleProfile.load(profileFile("toolsunset", validProfile($$"tool_path = ${PROG8_NO_SUCH_VARIABLE}")))
        shouldThrow<IllegalArgumentException> { unset.processBuilder(listOf("x")) }.message!! shouldContain "PROG8_NO_SUCH_VARIABLE used in the tool_path of llvm triple profile"

        fun rejected(name: String, toolEnv: String) =
            shouldThrow<IllegalArgumentException> { LlvmTripleProfile.load(profileFile(name, validProfile("tool_env = $toolEnv"))) }.message!!
        rejected("envnovalue", "ZCCCFG") shouldContain "which is not NAME=value"
        rejected("envnoname", "=x") shouldContain "which is not NAME=value"
        rejected("envbadname", "1A=x") shouldContain "invalid variable name '1A'"
        rejected("envpath", "PATH=/x") shouldContain "can't set PATH, tool_path is for that"
        rejected("envpathcase", "Path=/x") shouldContain "can't set PATH"
        rejected("envtwice", "A=1 A=2") shouldContain "sets A more than once"
    }

    val noUserProfiles = directory.resolve("no-user-profiles")

    test("a base profile supplies the keys that a profile leaves out, through a chain of bases, and each key is replaced whole") {
        val bases = directory.resolve("bases").createDirectories()
        bases.resolve("first.properties").writeText(validProfile("c_main_symbol = first_main\noutput_extension = .first\nlibc_lacks = fopen fclose\nabstract = true"))
        bases.resolve("second.properties").writeText("base = first.properties\noutput_extension = .second\nprogram_args = false")
        val child = profileFile("chained", "base = bases/second.properties\nlibc_lacks = remove")
        val profile = LlvmTripleProfile.load(child, null, noUserProfiles)
        profile.name shouldBe child.toString()
        profile.tools.profileName shouldBe child.toString()
        profile.triple shouldBe "test-triple"
        profile.dataLayout shouldBe wasmLayout
        profile.cMainSymbol shouldBe "first_main"
        profile.outputExtension shouldBe ".second"
        profile.programArgs shouldBe false
        profile.libcLacks shouldBe setOf("remove")
        LlvmTripleProfile.resolve(child.toString(), null, noUserProfiles).name shouldBe child.toString()
    }

    test("a relative base is found next to the profile that names it, not in the working directory, then in the user profiles, then embedded") {
        val sub = directory.resolve("relative").resolve("sub").createDirectories()
        sub.resolve("parent.properties").writeText(validProfile("output_extension = .parent"))
        val child = sub.resolve("child.properties").also { it.writeText("base = parent.properties") }
        LlvmTripleProfile.load(child, null, noUserProfiles).outputExtension shouldBe ".parent"
        val users = directory.resolve("userprofiles").createDirectories()
        users.resolve("userbase.properties").writeText(validProfile("output_extension = .user"))
        LlvmTripleProfile.load(profileFile("onuser", "base = userbase"), null, users).outputExtension shouldBe ".user"
        users.resolve("z80-z88dk.properties").writeText(validProfile())
        LlvmTripleProfile.load(profileFile("onembedded", "base = z80-z88dk"), null, noUserProfiles).triple shouldBe "z80-unknown-none-z88dk"
        LlvmTripleProfile.load(profileFile("onuserfirst", "base = z80-z88dk"), null, users).triple shouldBe "test-triple"
        withClue("an embedded profile only has embedded bases") {
            LlvmTripleProfile.resolve("z80-test", null, users).triple shouldBe "z80-unknown-none-z88dk"
        }
    }

    test("a base that is not found, or a cycle of bases, is an error that names the profiles") {
        val missing = profileFile("missingbase", "base = prog8-no-such-base")
        shouldThrow<IllegalArgumentException> { LlvmTripleProfile.load(missing, null, noUserProfiles) }.message!! shouldContain
            "the base 'prog8-no-such-base' of llvm triple profile '$missing' was not found"
        profileFile("cycle1", "base = cycle2.properties")
        val cycle2 = profileFile("cycle2", "base = cycle1.properties")
        shouldThrow<IllegalArgumentException> { LlvmTripleProfile.load(cycle2, null, noUserProfiles) }.message!! shouldContain "has a cycle of bases"
        shouldThrow<IllegalArgumentException> { LlvmTripleProfile.parseAsEmbedded("embeddedchild", "base = missingbase".byteInputStream()) }.message!! shouldContain
            "a profile built into the compiler can only have an embedded base"
        val self = profileFile("selfbase", "base = selfbase.properties")
        shouldThrow<IllegalArgumentException> { LlvmTripleProfile.load(self, null, noUserProfiles) }.message!! shouldContain "has a cycle of bases"
        shouldThrow<IllegalArgumentException> { LlvmTripleProfile.load(profileFile("badabstract", validProfile("abstract = yes"))) }.message!! shouldContain
            "abstract 'yes'"
    }

    test("an abstract profile can only be a base") {
        shouldThrow<IllegalArgumentException> { LlvmTripleProfile.resolve("z80-z88dk") }.message!! shouldContain
            "llvm triple profile 'z80-z88dk' is abstract: it can only be the base of other profiles"
        val abstract = profileFile("abstractbase", "abstract = true\ntriple = test-triple")
        shouldThrow<IllegalArgumentException> { LlvmTripleProfile.load(abstract) }.message!! shouldContain "is abstract"
        LlvmTripleProfile.load(profileFile("concrete", "base = abstractbase.properties\ndatalayout = $wasmLayout\nc_long_size = 4"), null, noUserProfiles).triple shouldBe "test-triple"
    }

    test("a startup_source is read from the directory of the profile that supplies it") {
        val lower = directory.resolve("startupbase").createDirectories()
        lower.resolve("boot.S").writeText("; base boot code")
        lower.resolve("base.properties").writeText(validProfile("link_command = gcc {startup} {object} -o {file}\nstartup_source = boot.S"))
        val upper = directory.resolve("startupchild").createDirectories()
        val child = upper.resolve("child.properties").also { it.writeText("base = ../startupbase/base.properties\nrun_command = run {file}") }
        LlvmTripleProfile.load(child, null, noUserProfiles).writeStartupSources(directory.resolve("based.elf")).single().readText() shouldBe "; base boot code"
        upper.resolve("own.S").writeText("; own boot code")
        val own = upper.resolve("own.properties").also { it.writeText("base = ../startupbase/base.properties\nstartup_source = own.S") }
        LlvmTripleProfile.load(own, null, noUserProfiles).writeStartupSources(directory.resolve("own.elf")).single().readText() shouldBe "; own boot code"
    }

    test("{startup} may be in a link command without a startup_source") {
        val profile = LlvmTripleProfile.load(profileFile("emptystartup", validProfile("link_command = zcc {startup} {object} -o x{startup}y {file}")))
        profile.startupSources shouldBe emptyList()
        profile.linkCommand shouldBe listOf("zcc", "{startup}", "{object}", "-o", "x{startup}y", "{file}")
        LlvmTripleProfile.load(profileFile("stemonly", validProfile("link_command = zcc {object} -o {stem}.bin"))).linkCommand shouldBe
            listOf("zcc", "{object}", "-o", "{stem}.bin")
    }

    test("{profile_dir} in link_command and run_command is the directory of the profile that supplies the key") {
        val spaced = directory.resolve("profile dir").createDirectories()
        spaced.resolve("dirbase.properties").writeText(validProfile("run_command = python3 {profile_dir}/run.py {file}\nlink_command = ld {object} -T{profile_dir}/x.ld -o {file}"))
        val other = directory.resolve("otherdir").createDirectories()
        val child = other.resolve("dirchild.properties").also { it.writeText("base = ../profile dir/dirbase.properties\nlink_command = ld {object} -L{profile_dir} -o {file}") }
        val profile = LlvmTripleProfile.load(child, null, noUserProfiles)
        profile.runCommand shouldBe listOf("python3", "${spaced.toAbsolutePath().normalize()}/run.py", "{file}")
        profile.linkCommand shouldBe listOf("ld", "{object}", "-L${other.toAbsolutePath().normalize()}", "-o", "{file}")
        shouldThrow<IllegalArgumentException> {
            LlvmTripleProfile.parseAsEmbedded("embeddeddir", validProfile("run_command = python3 {profile_dir}/run.py {file}").byteInputStream())
        }.message shouldBe "the run_command of llvm triple profile 'embeddeddir' uses {profile_dir}, which can't be used in a profile built into the compiler"
    }

    test("z80-test, built on z80-z88dk, is the profile it was before") {
        val before = profileFile("z80-test-before", $$"""
            triple = z80-unknown-none-z88dk
            datalayout = e-m:o-p:16:8-i16:8-i32:8-i64:8-i128:8-f16:8-f32:8-f64:8-f128:8-ve-a:8-n8:16
            c_int_size = 2
            c_long_size = 4
            clang = ${LLVM_Z80}/bin/clang
            clang_args = --target=z80-unknown-none-z88dk
            tool_path = ${Z88DK}/bin
            tool_env = ZCCCFG=${Z88DK}/lib/config
            link_command = zcc +test -o {file} {object} -l${LLVM_Z80}/lib/z80/z80_rt_z88dk
            output_extension = .bin
            run_command = z88dk-ticks -counter 100000000000 {file}
            program_args = false
            getchar_symbol = fgetc_cons
            libc_lacks = remove rename ftell getcwd mkdir chdir rmdir opendir readdir closedir strdup realloc free usleep strtod qsort \
                sin sinf cos cosf tan tanf atan atanf atan2 atan2f sqrt sqrtf pow powf log logf log2 log2f fabs fabsf floor floorf ceil ceilf roundeven roundevenf
            """)
        val old = LlvmTripleProfile.load(before)
        LlvmTripleProfile.resolve("z80-test") shouldBe old.copy(name = "z80-test", tools = old.tools.copy(profileName = "z80-test"), directory = null)
    }

    test("a profile without tool_path and tool_env starts its processes as they are, one with them gets their environment") {
        val plain = LlvmTripleProfile.load(profileFile("notools", validProfile())).processBuilder(listOf("prog8-tool", "a"))
        plain.command() shouldBe listOf("prog8-tool", "a")
        plain.environment() shouldBe ProcessBuilder().environment()

        val bin = directory.resolve("toolbin").createDirectories()
        val profile = LlvmTripleProfile.load(profileFile("withtools", validProfile("tool_path = ${bin.invariantSeparatorsPathString}\ntool_env = ZZTEST=hello")))
        val builder = profile.processBuilder(listOf("prog8-no-such-tool", "a"))
        builder.command() shouldBe listOf("prog8-no-such-tool", "a")
        builder.environment()["ZZTEST"] shouldBe "hello"
        // Windows names it Path
        fun pathOf(environment: Map<String, String>) = environment.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value
        val path = checkNotNull(pathOf(builder.environment()))
        path shouldBe listOfNotNull(bin.toString(), pathOf(ProcessBuilder().environment())?.takeIf { it.isNotEmpty() }).joinToString(File.pathSeparator)
    }

    test("a bare program name is looked up in tool_path first, which reaches the started process with tool_env, and so is a bare clang")
        .config(enabled = !System.getProperty("os.name").startsWith("Windows")) {
        val bin = directory.resolve("toolpathbin").createDirectories()
        val tool = bin.resolve("zztool").also {
            it.writeText($$"#!/bin/sh\necho \"$ZZTEST ${PATH%%:*} $1\"\n")
            it.toFile().setExecutable(true)
        }
        val clang = bin.resolve("zzclang").also { it.writeText("#!/bin/sh\n"); it.toFile().setExecutable(true) }
        val profile = LlvmTripleProfile.load(profileFile("toolrun", validProfile($$"tool_path = $${bin.invariantSeparatorsPathString}\ntool_env = ZZTEST=${PROG8_NO_SUCH_VARIABLE:-hello}\nclang = zzclang")))
        val builder = profile.processBuilder(listOf("zztool", "arg"))
        builder.command().first() shouldBe tool.toString()
        val process = builder.redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() } shouldBe "hello $bin arg\n"
        process.waitFor() shouldBe 0
        profile.resolveClang(null) shouldBe clang
        shouldThrow<IllegalArgumentException> { LlvmToolLocator.resolveClang(null, "zzclang", "p") }.message!! shouldContain "was not found"
    }
})
