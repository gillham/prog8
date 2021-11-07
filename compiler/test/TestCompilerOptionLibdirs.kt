package prog8tests

import io.kotest.core.spec.style.FunSpec
import prog8.compiler.compileProgram
import prog8.compiler.target.Cx16Target
import prog8tests.ast.helpers.assumeReadableFile
import prog8tests.ast.helpers.fixturesDir
import prog8tests.ast.helpers.outputDir
import prog8tests.ast.helpers.workingDir
import prog8tests.helpers.assertSuccess
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText

/**
 * ATTENTION: this is just kludge!
 * They are not really unit tests, but rather tests of the whole process,
 * from source file loading all the way through to running 64tass.
 */
class TestCompilerOptionSourcedirs: FunSpec({

    lateinit var tempFileInWorkingDir: Path

    beforeSpec {
        tempFileInWorkingDir = createTempFile(directory = workingDir, prefix = "tmp_", suffix = ".p8")
            .also { it.writeText("""
                main {
                    sub start() {
                    }
                }
            """)}
    }

    afterSpec {
        tempFileInWorkingDir.deleteExisting()
    }

    fun compileFile(filePath: Path, sourceDirs: List<String>) =
        compileProgram(
            filepath = filePath,
            optimize = false,
            optimizeFloatExpressions = false,
            writeAssembly = true,
            slowCodegenWarnings = false,
            quietAssembler = true,
            compilationTarget = Cx16Target.name,
            sourceDirs,
            outputDir
        )

    test("testAbsoluteFilePathInWorkingDir") {
        val filepath = assumeReadableFile(tempFileInWorkingDir.absolute())
        compileFile(filepath, listOf())
            .assertSuccess()
    }

    test("testFilePathInWorkingDirRelativeToWorkingDir") {
        val filepath = assumeReadableFile(workingDir.relativize(tempFileInWorkingDir.absolute()))
        compileFile(filepath, listOf())
            .assertSuccess()
    }

    test("testFilePathInWorkingDirRelativeTo1stInSourcedirs") {
        val filepath = assumeReadableFile(tempFileInWorkingDir)
        compileFile(filepath.fileName, listOf(workingDir.toString()))
            .assertSuccess()
    }

    test("testAbsoluteFilePathOutsideWorkingDir") {
        val filepath = assumeReadableFile(fixturesDir, "simple_main.p8")
        compileFile(filepath.absolute(), listOf())
            .assertSuccess()
    }

    test("testFilePathOutsideWorkingDirRelativeToWorkingDir") {
        val filepath = workingDir.relativize(assumeReadableFile(fixturesDir, "simple_main.p8").absolute())
        compileFile(filepath, listOf())
            .assertSuccess()
    }

    test("testFilePathOutsideWorkingDirRelativeTo1stInSourcedirs") {
        val filepath = assumeReadableFile(fixturesDir, "simple_main.p8")
        val sourcedirs = listOf("$fixturesDir")
        compileFile(filepath.fileName, sourcedirs)
            .assertSuccess()
    }

})
