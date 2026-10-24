package prog8tests.codecore

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import prog8.code.core.CpuType
import prog8.code.target.LlvmTarget
import prog8.code.target.LlvmToolLocator
import prog8.code.target.LlvmTripleProfile
import prog8.code.target.getCompilationTargetByName
import java.nio.file.Path
import kotlin.io.path.createDirectories
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
        "armv7-unknown-linux-gnueabihf" to Triple("armv7-unknown-linux-gnueabihf", 32, 4),
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

        fun rejected(name: String, properties: String): String =
            shouldThrow<IllegalArgumentException> { LlvmTripleProfile.load(sources.resolve("$name.properties").also { it.writeText(properties) }) }.message!!
        rejected("nostartup", validProfile("link_command = gcc {startup} {object} -o {file}")) shouldContain "startup_source and {startup}"
        rejected("noplaceholder", validProfile("link_command = gcc {object} -o {file}\nstartup_source = boot.S")) shouldContain "startup_source and {startup}"
        rejected("nolink", validProfile("startup_source = boot.S")) shouldContain "startup_source and {startup}"
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
        rejected("linkobject", validProfile("link_command = gcc -o {file}")) shouldContain "{object} and {file}"
        rejected("linkfile", validProfile("link_command = gcc {object}")) shouldContain "{object} and {file}"
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

    test("the data layout and C long size of every embedded profile are what clang reports for it").config(enabled = hasClang) {
        for (name in embedded.keys) {
            val profile = LlvmTripleProfile.resolve(name)
            val reported = LlvmTripleProfile.askClang(profile.triple, profile.clangArgs)
            withClue(name) {
                profile.dataLayout shouldBe reported.dataLayout
                profile.cLongSize shouldBe reported.cLongSize
            }
        }
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
        profile.runCommand shouldBe listOf("qemu", "{file}")
        LlvmTripleProfile.load(profileFile("partiallong", "triple = x86_64-pc-windows-msvc\ndatalayout = $wasmLayout")).let {
            it.dataLayout shouldBe wasmLayout
            it.cLongSize shouldBe 4
        }
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
        llvm64.targetTriple shouldBe "arm64-apple-macosx"
        llvm64.dataLayout shouldBe "e-m:o-p270:32:32-p271:32:32-p272:64:64-i64:64-i128:128-n32:64-S128-Fn32"
    }

    test("a triple whose pointer width doesn't match the target is rejected") {
        shouldThrow<IllegalArgumentException> { LlvmTarget(4, LlvmTripleProfile.resolve("x86_64-unknown-linux-gnu")) }.message!! shouldContain "64-bit pointers"
        shouldThrow<IllegalArgumentException> { LlvmTarget(8, LlvmTripleProfile.resolve("wasm32-wasip1")) }.message!! shouldContain "32-bit pointers"
    }

    test("targets by name: llvm32, llvm64 and llvm take a triple, the other targets don't") {
        getCompilationTargetByName("llvm32").name shouldBe LlvmTarget.NAME32
        (getCompilationTargetByName("llvm32", "armv7-unknown-linux-gnueabihf") as LlvmTarget).targetTriple shouldBe "armv7-unknown-linux-gnueabihf"
        (getCompilationTargetByName("llvm", "x86_64-unknown-linux-gnu") as LlvmTarget).targetTriple shouldBe "x86_64-unknown-linux-gnu"
        shouldThrow<IllegalArgumentException> { getCompilationTargetByName("cx16", "wasm32-wasip1") }
        shouldThrow<IllegalArgumentException> { getCompilationTargetByName("llvm32", "x86_64-unknown-linux-gnu") }
    }
})
