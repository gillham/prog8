package prog8.ast.base

import prog8.ast.Module
import prog8.ast.Program
import prog8.ast.processing.*
import prog8.compiler.CompilationOptions
import prog8.compiler.target.AsmVariableAndReturnsPreparer
import prog8.optimizer.FlattenAnonymousScopesAndNopRemover


internal fun Program.checkValid(compilerOptions: CompilationOptions, errors: ErrorReporter) {
    val checker = AstChecker(this, compilerOptions, errors)
    checker.visit(this)
}

internal fun Program.prepareAsmVariablesAndReturns(errors: ErrorReporter) {
    val fixer = AsmVariableAndReturnsPreparer(this, errors)
    fixer.visit(this)
    fixer.applyModifications()
}

internal fun Program.reorderStatements() {
    val initvalueCreator = AddressOfInserter(this)
    initvalueCreator.visit(this)
    initvalueCreator.applyModifications()

    val reorder = StatementReorderer(this)
    reorder.visit(this)
    reorder.applyModifications()
}

internal fun Program.addTypecasts(errors: ErrorReporter) {
    val caster = TypecastsAdder(this, errors)
    caster.visit(this)
    caster.applyModifications()
}

internal fun Module.checkImportedValid() {
    val imr = ImportedModuleDirectiveRemover()
    imr.visit(this, this.parent)
    imr.applyModifications()
}

internal fun Program.checkRecursion(errors: ErrorReporter) {
    val checker = AstRecursionChecker(namespace, errors)
    checker.visit(this)
    checker.processMessages(name)
}

internal fun Program.checkIdentifiers(errors: ErrorReporter) {
    val checker = AstIdentifiersChecker(this, errors)
    checker.visit(this)

    if (modules.map { it.name }.toSet().size != modules.size) {
        throw FatalAstException("modules should all be unique")
    }
}

internal fun Program.makeForeverLoops() {
    val checker = ForeverLoopsMaker()
    checker.visit(this)
    checker.applyModifications()
}

internal fun Program.removeNopsFlattenAnonScopes() {
    val flattener = FlattenAnonymousScopesAndNopRemover()
    flattener.visit(this)
}
