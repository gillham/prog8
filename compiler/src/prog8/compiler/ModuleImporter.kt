package prog8.compiler

import com.github.michaelbull.result.*
import prog8.ast.Module
import prog8.ast.Program
import prog8.ast.SyntaxError
import prog8.ast.statements.Directive
import prog8.ast.statements.DirectiveArg
import prog8.code.core.IErrorReporter
import prog8.code.core.Position
import prog8.code.source.ImportFileSystem
import prog8.code.source.SourceCode
import prog8.parser.Prog8Parser
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists


class ModuleImporter(private val program: Program,
                     private val compilationTargetName: String,
                     val errors: IErrorReporter,
                     sourceDirs: List<String>,
                     libraryDirs: List<String>) {

    private val sourcePaths: List<Path> = sourceDirs.map { Path(it).absolute().normalize() }.toSortedSet().toList()
    private val libraryPaths: List<Path> = libraryDirs.map { Path(it).absolute().normalize() }.toSortedSet().toList()

    fun importMainModule(filePath: Path): Result<Module, NoSuchFileException> {
        val searchIn = (listOf(Path("").absolute()) + sourcePaths).toSortedSet()
        val normalizedFilePath = filePath.normalize()
        for(path in searchIn) {
            val programPath = path.resolve(normalizedFilePath)
            if(programPath.exists()) {
                println("Compiling program ${Path("").absolute().relativize(programPath)}")
                println("Compiler target: $compilationTargetName")
                val source = ImportFileSystem.getFile(programPath, false)
                return Ok(importModule(source))
            }
        }
        return Err(NoSuchFileException(
            file = normalizedFilePath.toFile(),
            reason = "Searched in $searchIn"))
    }

    fun importImplicitLibraryModule(name: String): Module? {
        val import = Directive("%import", listOf(
                DirectiveArg(name, 42u, position = Position("<<<implicit-import>>>", 0, 0, 0))
        ), Position("<<<implicit-import>>>", 0, 0, 0))
        return executeImportDirective(import, null)
    }

    private fun importModule(src: SourceCode) : Module {
        val moduleAst = Prog8Parser.parseModule(src)
        program.addModule(moduleAst)

        // accept additional imports
        try {
            val lines = moduleAst.statements.toMutableList()
            lines.asSequence()
                .mapIndexed { i, it -> i to it }
                .filter { (it.second as? Directive)?.directive == "%import" }
                .forEach { executeImportDirective(it.second as Directive, moduleAst) }
            moduleAst.statements.clear()
            moduleAst.statements.addAll(lines)
            return moduleAst
        } catch (x: Exception) {
            // in case of error, make sure the module we're importing is no longer in the Ast
            program.removeModule(moduleAst)
            throw x
        }
    }

    private fun executeImportDirective(import: Directive, importingModule: Module?): Module? {
        if(import.directive!="%import" || import.args.size!=1)
            throw SyntaxError("invalid import directive", import.position)
        val moduleName = import.args[0].string!!
        if("$moduleName.p8" == import.position.file)
            throw SyntaxError("cannot import self", import.position)

        val existing = program.modules.singleOrNull { it.name == moduleName }
        if (existing!=null)
            return existing

        // try internal library first
        val moduleResourceSrc = getModuleFromResource("$moduleName.p8", compilationTargetName)
        val importedModule =
            moduleResourceSrc.fold(
                success = {
                    importModule(it)
                },
                failure = {
                    // try filesystem next
                    val moduleSrc = getModuleFromFile(moduleName, importingModule)
                    moduleSrc.fold(
                        success = {
                            importModule(it)
                        },
                        failure = {
                            if(moduleName=="string")
                                errors.err("the 'string' module is now named 'strings'", import.position)
                            else
                                errors.err("no module found with name $moduleName. Searched in: $sourcePaths (and internal libraries)", import.position)
                            return null
                        }
                    )
                }
            )

        removeDirectivesFromImportedModule(importedModule)
        return importedModule
    }

    private fun removeDirectivesFromImportedModule(importedModule: Module) {
        // Most global directives don't apply for imported modules, so remove them
        val moduleLevelDirectives = listOf("%output", "%launcher", "%zeropage", "%zpreserved", "%zpallowed", "%address", "%memtop")
        var directives = importedModule.statements.filterIsInstance<Directive>()
        importedModule.statements.removeAll(directives.toSet())
        directives = directives.filter{ it.directive !in moduleLevelDirectives }
        importedModule.statements.addAll(0, directives)
    }

    private fun getModuleFromResource(name: String, compilationTargetName: String): Result<SourceCode, NoSuchFileException> {
        val result =
            runCatching { ImportFileSystem.getResource("/prog8lib/$compilationTargetName/$name") }
            .orElse { runCatching { ImportFileSystem.getResource("/prog8lib/$name") }  }

        return result.mapError { NoSuchFileException(File(name)) }
    }

    private fun getModuleFromFile(name: String, importingModule: Module?): Result<SourceCode, NoSuchFileException> {
        val fileName = "$name.p8"

        val normalLocations =
            if (importingModule == null) {
                sourcePaths
            } else {
                val pathFromImportingModule = (Path(importingModule.position.file).parent ?: Path("")).absolute().normalize()
                listOf(pathFromImportingModule) + sourcePaths
            }

        libraryPaths.forEach {
            try {
                return Ok(ImportFileSystem.getFile(it.resolve(fileName), true))
            } catch (_: NoSuchFileException) {
            }
        }

        normalLocations.forEach {
            try {
                return Ok(ImportFileSystem.getFile(it.resolve(fileName), false))
            } catch (_: NoSuchFileException) {
            }
        }

        return Err(NoSuchFileException(File("name")))
    }
}
