package prog8.compiler.astprocessing

import prog8.ast.*
import prog8.ast.base.*
import prog8.ast.expressions.*
import prog8.ast.statements.*
import prog8.ast.walk.AstWalker
import prog8.ast.walk.IAstModification
import prog8.ast.walk.IAstVisitor
import prog8.compilerinterface.*
import prog8.optimizer.getTempVarName

internal class BeforeAsmAstChanger(val program: Program, private val options: CompilationOptions,
                                   private val errors: IErrorReporter
) : AstWalker() {

    private val allBlockVars = mutableMapOf<Block, MutableSet<VarDecl>>()
    private val allBlockConsts = mutableMapOf<Block, MutableSet<VarDecl>>()
    private val allBlockMemoryvars = mutableMapOf<Block, MutableSet<VarDecl>>()
    private val allSubroutineVars = mutableMapOf<Subroutine, MutableSet<VarDecl>>()
    private val allSubroutineConsts = mutableMapOf<Subroutine, MutableSet<VarDecl>>()
    private val allSubroutineMemoryvars = mutableMapOf<Subroutine, MutableSet<VarDecl>>()
    internal lateinit var allocation: IVariableAllocation

/* TODO complete the use of VariableAllocation and get rid of it from the AsmGen (and that should no longer use vardecl either):
    override fun after(program: Program): Iterable<IAstModification> {
        allocation = VariableAllocation(options,
            allBlockVars, allBlockConsts, allBlockMemoryvars,
            allSubroutineVars, allSubroutineConsts, allSubroutineMemoryvars)
        allocation.dump(program.memsizer)
        return super.after(program)
    }
*/

    override fun before(breakStmt: Break, parent: Node): Iterable<IAstModification> {
        throw FatalAstException("break should have been replaced by goto $breakStmt")
    }

    override fun before(whileLoop: WhileLoop, parent: Node): Iterable<IAstModification> {
        throw FatalAstException("while should have been converted to jumps")
    }

    override fun before(untilLoop: UntilLoop, parent: Node): Iterable<IAstModification> {
        throw FatalAstException("do..until should have been converted to jumps")
    }

    override fun before(block: Block, parent: Node): Iterable<IAstModification> {
        // move all subroutines to the bottom of the block
        val subs = block.statements.filterIsInstance<Subroutine>()
        block.statements.removeAll(subs)
        block.statements.addAll(subs)

        // adjust global variables initialization
        if(options.dontReinitGlobals) {
            block.statements.asSequence().filterIsInstance<VarDecl>().forEach {
                if(it.type==VarDeclType.VAR) {
                    it.zeropage = ZeropageWish.NOT_IN_ZEROPAGE
                    it.findInitializer(program)?.let { initializer ->
                        it.value = initializer.value     // put the init value back into the vardecl
                    }
                }
            }
        }

        return noModifications
    }

    override fun after(decl: VarDecl, parent: Node): Iterable<IAstModification> {
        if(!options.dontReinitGlobals) {
            if (decl.type == VarDeclType.VAR && decl.value != null && decl.datatype in NumericDatatypes)
                throw FatalAstException("vardecls for variables, with initial numerical value, should have been rewritten as plain vardecl + assignment $decl")
        }

        val scope=decl.definingScope
        when (decl.type) {
            VarDeclType.VAR -> {
                when(scope) {
                    is Block -> {
                        val decls = allBlockVars[scope] ?: mutableSetOf()
                        decls.add(decl)
                        allBlockVars[scope] = decls
                    }
                    is Subroutine -> {
                        val decls = allSubroutineVars[scope] ?: mutableSetOf()
                        decls.add(decl)
                        allSubroutineVars[scope] = decls
                    }
                    else -> {
                        throw FatalAstException("var can only occur in subroutine or block scope")
                    }
                }
            }
            VarDeclType.CONST -> {
                when(scope) {
                    is Block -> {
                        val decls = allBlockConsts[scope] ?: mutableSetOf()
                        decls.add(decl)
                        allBlockConsts[scope] = decls
                    }
                    is Subroutine -> {
                        val decls = allSubroutineConsts[scope] ?: mutableSetOf()
                        decls.add(decl)
                        allSubroutineConsts[scope] = decls
                    }
                    else -> {
                        throw FatalAstException("var can only occur in subroutine or block scope")
                    }
                }
            }
            VarDeclType.MEMORY -> {
                when(scope) {
                    is Block -> {
                        val decls = allBlockMemoryvars[scope] ?: mutableSetOf()
                        decls.add(decl)
                        allBlockMemoryvars[scope] = decls
                    }
                    is Subroutine -> {
                        val decls = allSubroutineMemoryvars[scope] ?: mutableSetOf()
                        decls.add(decl)
                        allSubroutineMemoryvars[scope] = decls
                    }
                    else -> {
                        throw FatalAstException("var can only occur in subroutine or block scope")
                    }
                }
            }
            else -> {
                throw FatalAstException("invalid var type")
            }
        }

        // TODO get rid of the vardecl inside the ast - codegen should depend on the IVariableAllocation object
        // return listOf(IAstModification.Remove(decl, parent as IStatementContainer))
        return noModifications
    }

    override fun after(assignment: Assignment, parent: Node): Iterable<IAstModification> {
        // Try to replace A = B <operator> Something  by A= B, A = A <operator> Something
        // this triggers the more efficent augmented assignment code generation more often.
        // But it can only be done if the target variable IS NOT OCCURRING AS AN OPERAND ITSELF.
        if(!assignment.isAugmentable
            && assignment.target.identifier != null
            && !assignment.target.isIOAddress(options.compTarget.machine)) {
            val binExpr = assignment.value as? BinaryExpression

            if(binExpr!=null && binExpr.inferType(program) istype DataType.FLOAT && !options.optimizeFloatExpressions)
                return noModifications

            if (binExpr != null && binExpr.operator !in ComparisonOperators) {
                if (binExpr.left !is BinaryExpression) {
                    if (binExpr.right.referencesIdentifier(assignment.target.identifier!!.nameInSource)) {
                        // the right part of the expression contains the target variable itself.
                        // we can't 'split' it trivially because the variable will be changed halfway through.
                        if(binExpr.operator in AssociativeOperators) {
                            // A = <something-without-A>  <associativeoperator>  <otherthing-with-A>
                            // use the other part of the expression to split.
                            val sourceDt = binExpr.right.inferType(program).getOrElse { throw AssemblyError("unknown dt") }
                            val (_, right) = binExpr.right.typecastTo(assignment.target.inferType(program).getOrElse { throw AssemblyError(
                                "unknown dt"
                            )
                            }, sourceDt, implicit=true)
                            val assignRight = Assignment(assignment.target, right, AssignmentOrigin.BEFOREASMGEN, assignment.position)
                            return listOf(
                                IAstModification.InsertBefore(assignment, assignRight, parent as IStatementContainer),
                                IAstModification.ReplaceNode(binExpr.right, binExpr.left, binExpr),
                                IAstModification.ReplaceNode(binExpr.left, assignment.target.toExpression(), binExpr)
                            )
                        }
                    } else {
                        val sourceDt = binExpr.left.inferType(program).getOrElse { throw AssemblyError("unknown dt") }
                        val (_, left) = binExpr.left.typecastTo(assignment.target.inferType(program).getOrElse { throw AssemblyError(
                            "unknown dt"
                        )
                        }, sourceDt, implicit=true)
                        val assignLeft = Assignment(assignment.target, left, AssignmentOrigin.BEFOREASMGEN, assignment.position)
                        return listOf(
                            IAstModification.InsertBefore(assignment, assignLeft, parent as IStatementContainer),
                            IAstModification.ReplaceNode(binExpr.left, assignment.target.toExpression(), binExpr)
                        )
                    }
                }
            }
        }
        return noModifications
    }

    override fun after(scope: AnonymousScope, parent: Node): Iterable<IAstModification> {
        if(scope.statements.any { it is VarDecl || it is IStatementContainer })
            throw FatalAstException("anonymousscope may no longer contain any vardecls or subscopes")
        return noModifications
    }

    override fun after(subroutine: Subroutine, parent: Node): Iterable<IAstModification> {
        val mods = mutableListOf<IAstModification>()

        // add the implicit return statement at the end (if it's not there yet), but only if it's not a kernal routine.
        // and if an assembly block doesn't contain a rts/rti, and some other situations.
        if (!subroutine.isAsmSubroutine && !subroutine.inline) {
            if(subroutine.statements.isEmpty() ||
                (subroutine.amountOfRtsInAsm() == 0
                        && subroutine.statements.lastOrNull { it !is VarDecl } !is Return
                        && subroutine.statements.last() !is Subroutine
                        && subroutine.statements.last() !is Return)) {
                val returnStmt = Return(null, subroutine.position)
                mods += IAstModification.InsertLast(returnStmt, subroutine)
            }
        }

        // precede a subroutine with a return to avoid falling through into the subroutine from code above it
        val outerScope = subroutine.definingScope
        val outerStatements = outerScope.statements
        val subroutineStmtIdx = outerStatements.indexOf(subroutine)
        if (subroutineStmtIdx > 0) {
            val prevStmt = outerStatements[subroutineStmtIdx-1]
            if(outerScope !is Block
                && (prevStmt !is Jump)
                && prevStmt !is Subroutine
                && prevStmt !is Return
            ) {
                val returnStmt = Return(null, subroutine.position)
                mods += IAstModification.InsertAfter(outerStatements[subroutineStmtIdx - 1], returnStmt, outerScope)
            }
        }

        if (!subroutine.inline) {
            if (subroutine.isAsmSubroutine && subroutine.asmAddress==null && subroutine.amountOfRtsInAsm() == 0) {
                // make sure the NOT INLINED asm subroutine actually has a rts at the end
                // (non-asm routines get a Return statement as needed, above)
                mods += IAstModification.InsertLast(InlineAssembly("  rts\n", Position.DUMMY), subroutine)
            }
        }

        return mods
    }

    override fun after(ifElse: IfElse, parent: Node): Iterable<IAstModification> {
        val prefixExpr = ifElse.condition as? PrefixExpression
        if(prefixExpr!=null && prefixExpr.operator=="not") {
            // if not x -> if x==0
            val booleanExpr = BinaryExpression(
                prefixExpr.expression,
                "==",
                NumericLiteralValue.optimalInteger(0, ifElse.condition.position),
                ifElse.condition.position
            )
            return listOf(IAstModification.ReplaceNode(ifElse.condition, booleanExpr, ifElse))
        }

        val binExpr = ifElse.condition as? BinaryExpression
        if(binExpr==null || binExpr.operator !in ComparisonOperators) {
            // if x  ->  if x!=0,    if x+5  ->  if x+5 != 0
            val booleanExpr = BinaryExpression(
                ifElse.condition,
                "!=",
                NumericLiteralValue.optimalInteger(0, ifElse.condition.position),
                ifElse.condition.position
            )
            return listOf(IAstModification.ReplaceNode(ifElse.condition, booleanExpr, ifElse))
        }

        if((binExpr.left as? NumericLiteralValue)?.number==0.0 &&
            (binExpr.right as? NumericLiteralValue)?.number!=0.0)
            throw FatalAstException("0==X should have been swapped to if X==0")

        // simplify the conditional expression, introduce simple assignments if required.
        // NOTE: sometimes this increases code size because additional stores/loads are generated for the
        //       intermediate variables. We assume these are optimized away from the resulting assembly code later.
        val simplify = simplifyConditionalExpression(binExpr)
        val modifications = mutableListOf<IAstModification>()
        if(simplify.rightVarAssignment!=null) {
            modifications += IAstModification.ReplaceNode(binExpr.right, simplify.rightOperandReplacement!!, binExpr)
            modifications += IAstModification.InsertBefore(
                ifElse,
                simplify.rightVarAssignment,
                parent as IStatementContainer
            )
        }
        if(simplify.leftVarAssignment!=null) {
            modifications += IAstModification.ReplaceNode(binExpr.left, simplify.leftOperandReplacement!!, binExpr)
            modifications += IAstModification.InsertBefore(
                ifElse,
                simplify.leftVarAssignment,
                parent as IStatementContainer
            )
        }

        return modifications
    }

    private class CondExprSimplificationResult(
        val leftVarAssignment: Assignment?,
        val leftOperandReplacement: Expression?,
        val rightVarAssignment: Assignment?,
        val rightOperandReplacement: Expression?
    )

    private fun simplifyConditionalExpression(expr: BinaryExpression): CondExprSimplificationResult {

        // TODO: somehow figure out if the expr will result in stack-evaluation STILL after being split off,
        //       in that case: do *not* split it off but just keep it as it is (otherwise code size increases)
        // TODO: this should be replaced by a general expression-evaluation optimization step.
        //       the actual conditional expression in the statement should be no more than VARIABLE <COMPARISON-OPERATOR> SIMPLE-EXPRESSION
        // NOTE: do NOT move this to an earler ast transform phase (such as StatementReorderer or StatementOptimizer) - it WILL result in larger code.

        var leftAssignment: Assignment? = null
        var leftOperandReplacement: Expression? = null
        var rightAssignment: Assignment? = null
        var rightOperandReplacement: Expression? = null

        val separateLeftExpr = !expr.left.isSimple && expr.left !is IFunctionCall && expr.left !is ContainmentCheck
        val separateRightExpr = !expr.right.isSimple && expr.right !is IFunctionCall && expr.right !is ContainmentCheck
        val leftDt = expr.left.inferType(program)
        val rightDt = expr.right.inferType(program)

        if(!leftDt.isInteger || !rightDt.isInteger) {
            // we can't reasonably simplify non-integer expressions
            return CondExprSimplificationResult(null, null, null, null)
        }

        if(separateLeftExpr) {
            val name = getTempVarName(leftDt)
            leftOperandReplacement = IdentifierReference(name, expr.position)
            leftAssignment = Assignment(
                AssignTarget(IdentifierReference(name, expr.position), null, null, expr.position),
                expr.left,
                AssignmentOrigin.BEFOREASMGEN, expr.position
            )
        }
        if(separateRightExpr) {
            val name = when {
                rightDt istype DataType.UBYTE -> listOf("prog8_lib","retval_interm_ub")
                rightDt istype DataType.UWORD -> listOf("prog8_lib","retval_interm_uw")
                rightDt istype DataType.BYTE -> listOf("prog8_lib","retval_interm_b2")
                rightDt istype DataType.WORD -> listOf("prog8_lib","retval_interm_w2")
                else -> throw AssemblyError("invalid dt")
            }
            rightOperandReplacement = IdentifierReference(name, expr.position)
            rightAssignment = Assignment(
                AssignTarget(IdentifierReference(name, expr.position), null, null, expr.position),
                expr.right,
                AssignmentOrigin.BEFOREASMGEN, expr.position
            )
        }
        return CondExprSimplificationResult(
            leftAssignment, leftOperandReplacement,
            rightAssignment, rightOperandReplacement
        )
    }

    override fun after(functionCallStatement: FunctionCallStatement, parent: Node): Iterable<IAstModification> {
        if(functionCallStatement.target.nameInSource==listOf("cmp")) {
            // if the datatype of the arguments of cmp() are different, cast the byte one to word.
            val arg1 = functionCallStatement.args[0]
            val arg2 = functionCallStatement.args[1]
            val dt1 = arg1.inferType(program).getOr(DataType.UNDEFINED)
            val dt2 = arg2.inferType(program).getOr(DataType.UNDEFINED)
            if(dt1 in ByteDatatypes) {
                if(dt2 in ByteDatatypes)
                    return noModifications
                val (replaced, cast) = arg1.typecastTo(if(dt1== DataType.UBYTE) DataType.UWORD else DataType.WORD, dt1, true)
                if(replaced)
                    return listOf(IAstModification.ReplaceNode(arg1, cast, functionCallStatement))
            } else {
                if(dt2 in WordDatatypes)
                    return noModifications
                val (replaced, cast) = arg2.typecastTo(if(dt2== DataType.UBYTE) DataType.UWORD else DataType.WORD, dt2, true)
                if(replaced)
                    return listOf(IAstModification.ReplaceNode(arg2, cast, functionCallStatement))
            }
        }
        return noModifications
    }

    override fun after(arrayIndexedExpression: ArrayIndexedExpression, parent: Node): Iterable<IAstModification> {

        val containingStatement = getContainingStatement(arrayIndexedExpression)
        if(getComplexArrayIndexedExpressions(containingStatement).size > 1) {
            errors.err("it's not possible to use more than one complex array indexing expression in a single statement; break it up via a temporary variable for instance", containingStatement.position)
            return noModifications
        }


        val index = arrayIndexedExpression.indexer.indexExpr
        if(index !is NumericLiteralValue && index !is IdentifierReference) {
            // replace complex indexing expression with a temp variable to hold the computed index first
            return getAutoIndexerVarFor(arrayIndexedExpression)
        }

        return noModifications
    }

    private fun getComplexArrayIndexedExpressions(stmt: Statement): List<ArrayIndexedExpression> {

        class Searcher : IAstVisitor {
            val complexArrayIndexedExpressions = mutableListOf<ArrayIndexedExpression>()
            override fun visit(arrayIndexedExpression: ArrayIndexedExpression) {
                val ix = arrayIndexedExpression.indexer.indexExpr
                if(ix !is NumericLiteralValue && ix !is IdentifierReference)
                    complexArrayIndexedExpressions.add(arrayIndexedExpression)
            }

            override fun visit(branch: ConditionalBranch) {}

            override fun visit(forLoop: ForLoop) {}

            override fun visit(ifElse: IfElse) {
                ifElse.condition.accept(this)
            }

            override fun visit(untilLoop: UntilLoop) {
                untilLoop.condition.accept(this)
            }
        }

        val searcher = Searcher()
        stmt.accept(searcher)
        return searcher.complexArrayIndexedExpressions
    }

    private fun getContainingStatement(expression: Expression): Statement {
        var node: Node = expression
        while(node !is Statement)
            node = node.parent

        return node
    }

    private fun getAutoIndexerVarFor(expr: ArrayIndexedExpression): MutableList<IAstModification> {
        val modifications = mutableListOf<IAstModification>()
        val statement = expr.containingStatement
        val dt = expr.indexer.indexExpr.inferType(program)
        val tempvar = if(dt.isBytes) listOf("prog8_lib","retval_interm_ub") else listOf("prog8_lib","retval_interm_b")
        val target = AssignTarget(IdentifierReference(tempvar, expr.indexer.position), null, null, expr.indexer.position)
        val assign = Assignment(target, expr.indexer.indexExpr, AssignmentOrigin.BEFOREASMGEN, expr.indexer.position)
        modifications.add(IAstModification.InsertBefore(statement, assign, statement.parent as IStatementContainer))
        modifications.add(
            IAstModification.ReplaceNode(
                expr.indexer.indexExpr,
                target.identifier!!.copy(),
                expr.indexer
            )
        )
        return modifications
    }

}


internal class VariableAllocation (
    options: CompilationOptions,
    astBlockVars: Map<Block, Set<VarDecl>>,
    astBlockConsts: Map<Block, Set<VarDecl>>,
    astBlockMemvars: Map<Block, Set<VarDecl>>,
    astSubroutineVars: Map<Subroutine, Set<VarDecl>>,
    astSubroutineConsts: Map<Subroutine, Set<VarDecl>>,
    astSubroutineMemvars: Map<Subroutine, Set<VarDecl>>
) : IVariableAllocation
{
    override val zeropageVars: Set<IVariableAllocation.ZeropageVariable>
    override val blockVars: Map<Block, Set<IVariableAllocation.StaticBlockVariable>>
    override val blockConsts: Map<Block, Set<IVariableAllocation.ConstantNumberSymbol>>
    override val blockMemvars: Map<Block, Set<IVariableAllocation.MemoryMappedSymbol>>
    override val subroutineVars: Map<Subroutine, Set<IVariableAllocation.StaticSubroutineVariable>>
    override val subroutineConsts: Map<Subroutine, Set<IVariableAllocation.ConstantNumberSymbol>>
    override val subroutineMemvars: Map<Subroutine, Set<IVariableAllocation.MemoryMappedSymbol>>

    init {
        if(options.zeropage!=ZeropageType.DONTUSE)
            allocateVarsInZeropage(options.compTarget.machine.zeropage)

        val zpv = mutableSetOf<IVariableAllocation.ZeropageVariable>()
        val bv = astBlockVars.keys.associateWith { mutableSetOf<IVariableAllocation.StaticBlockVariable>() }
        val bc = astBlockConsts.keys.associateWith { mutableSetOf<IVariableAllocation.ConstantNumberSymbol>() }
        val bmv = astBlockMemvars.keys.associateWith { mutableSetOf<IVariableAllocation.MemoryMappedSymbol>() }
        val sv = astSubroutineVars.keys.associateWith { mutableSetOf<IVariableAllocation.StaticSubroutineVariable>() }
        val sc = astSubroutineConsts.keys.associateWith { mutableSetOf<IVariableAllocation.ConstantNumberSymbol>() }
        val smv = astSubroutineMemvars.keys.associateWith { mutableSetOf<IVariableAllocation.MemoryMappedSymbol>() }
        astBlockVars.forEach { (block, decls) ->
            val vars = bv.getValue(block)
            vars.addAll(decls.map {
                // TODO make sure the zp-allocated variables are not added here
                IVariableAllocation.StaticBlockVariable(it.datatype, it.name, it.value, it.position, it)
            })
        }
        astBlockConsts.forEach { (block, decls) ->
            bc.getValue(block).addAll(
                decls.map {
                    IVariableAllocation.ConstantNumberSymbol(
                        it.datatype,
                        it.name,
                        (it.value as NumericLiteralValue).number,
                        it.position
                    )
                })
        }
        astBlockMemvars.forEach { (block, decls) ->
            bmv.getValue(block).addAll(
                decls.map {
                    IVariableAllocation.MemoryMappedSymbol(
                        it.datatype,
                        it.name,
                        (it.value as NumericLiteralValue).number.toUInt(),
                        it.position
                    )
                })
        }
        astSubroutineVars.forEach { (sub, decls) ->
            val vars = sv.getValue(sub)
            vars.addAll(decls.map {
                // TODO make sure the zp-allocated variables are not added here
                IVariableAllocation.StaticSubroutineVariable(it.datatype, it.name, it.position, it)
            })
        }
        astSubroutineConsts.forEach { (sub, decls) ->
            sc.getValue(sub).addAll(
                decls.map {
                    IVariableAllocation.ConstantNumberSymbol(
                        it.datatype,
                        it.name,
                        (it.value as NumericLiteralValue).number,
                        it.position
                    )
                })
        }
        astSubroutineMemvars.forEach { (sub, decls) ->
            smv.getValue(sub).addAll(
                decls.map {
                    IVariableAllocation.MemoryMappedSymbol(
                        it.datatype,
                        it.name,
                        (it.value as NumericLiteralValue).number.toUInt(),
                        it.position
                    )
                })
        }
        zeropageVars = zpv
        blockVars = bv
        blockConsts = bc
        blockMemvars = bmv
        subroutineVars = sv
        subroutineConsts = sc
        subroutineMemvars = smv
    }

    private fun allocateVarsInZeropage(zeropage: Zeropage) {
        println("TODO: allocate vars on zeropage")   // TODO
    }

    override fun dump(memsizer: IMemSizer) {
        println("\nALL ZEROPAGE VARS:")
        zeropageVars.forEach {
            println("  ${it.type}  ${it.scopedname}   ${it.position}")
        }
        println("\nALL BLOCK VARS:")
        blockVars.forEach { (block, vars) ->
            val totalsize = vars.sumOf { memsizer.memorySize(it.origVar) }
            println("BLOCK: ${block.name} total size: $totalsize")
            vars.forEach {
                println("  ${it.type}  ${it.name} = ${it.initialValue}  ${it.position}")
            }
        }
        println("\nALL BLOCK CONSTS:")
        blockConsts.forEach { (block, vars) ->
            println("BLOCK: ${block.name}")
            vars.forEach {
                println("  ${it.type}  ${it.name} = ${it.value}  ${it.position}")
            }
        }
        println("\nALL BLOCK MEMORYVARS:")
        blockMemvars.forEach { (block, vars) ->
            println("BLOCK: ${block.name}")
            vars.forEach {
                println("  ${it.type}  ${it.name} = ${it.address.toHex()}  ${it.position}")
            }
        }

        println("\nALL SUBROUTINE VARS:")
        subroutineVars.forEach { (sub, vars) ->
            val totalsize = vars.sumOf { memsizer.memorySize(it.origVar) }
            println("SUBROUTINE: ${sub.name} total size: $totalsize")
            vars.forEach {
                println("  ${it.type}  ${it.name}  ${it.position}")
            }
        }
        println("\nALL SUBROUTINE CONSTS:")
        subroutineConsts.forEach { (sub, vars) ->
            println("SUBROUTINE: ${sub.name}")
            vars.forEach {
                println("  ${it.type}  ${it.name} = ${it.value}  ${it.position}")
            }
        }
        println("\nALL SUBROUTINE MEMORYVARS:")
        subroutineMemvars.forEach { (sub, vars) ->
            println("SUBROUTINE: ${sub.name}")
            vars.forEach {
                println("  ${it.type}  ${it.name} = ${it.address.toHex()}  ${it.position}")
            }
        }
    }
}