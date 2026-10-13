package prog8.codegen.llvm

import io.kotest.core.test.Enabled
import io.kotest.core.test.TestCase
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText


internal val llvmAs: Path? = LlvmToolchain.find("llvm-as")

internal val requiresLlvmAs: (TestCase) -> Enabled = {
    if (llvmAs != null) Enabled.enabled
    else Enabled.disabled("llvm-as not found on the PATH or in ${LlvmToolchain.homebrewLlvmBin}")
}

/** Runs `llvm-as -disable-output` on [ir] and returns its exit code and combined output. */
internal fun runLlvmAs(ir: String): Pair<Int, String> {
    val file = createTempFile("prog8-llvm-test", ".ll")
    try {
        file.writeText(ir)
        val process = ProcessBuilder(checkNotNull(llvmAs).toString(), "-disable-output", file.toString()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor(30, TimeUnit.SECONDS)) { "llvm-as did not finish" }
        return process.exitValue() to output
    } finally {
        file.deleteIfExists()
    }
}
