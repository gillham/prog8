package prog8.optimizing

import prog8.ast.*
import prog8.functions.BuiltinFunctionNames
import prog8.functions.BuiltinFunctionsWithoutSideEffects


/*
    todo remove if statements with empty statement blocks
    todo replace if statements with only else block
    todo statement optimization: create augmented assignment from assignment that only refers to its lvalue (A=A+10, A=4*A, ...)
    todo statement optimization: X+=1, X-=1  --> X++/X--  ,
    todo remove statements that have no effect  X=X , X+=0, X-=0, X*=1, X/=1, X//=1, A |= 0, A ^= 0, A<<=0, etc etc
    todo optimize addition with self into shift 1  (A+=A -> A<<=1)
    todo assignment optimization: optimize some simple multiplications into shifts  (A*=8 -> A<<=3)
    todo analyse for unreachable code and remove that (f.i. code after goto or return that has no label so can never be jumped to)
    todo merge sequence of assignments into one (as long as the value is a constant and the target not a MEMORY type!)
    todo report more always true/always false conditions
    todo inline subroutines that are only called once
    todo inline subroutines that are "sufficiently small"
*/

class StatementOptimizer(private val globalNamespace: INameScope) : IAstProcessor {
    var optimizationsDone: Int = 0
        private set

    private var statementsToRemove = mutableListOf<IStatement>()

    override fun process(functionCall: FunctionCall): IExpression {
        val target = globalNamespace.lookup(functionCall.target.nameInSource, functionCall)
        if(target!=null)
            used(target)
        return super.process(functionCall)
    }

    override fun process(functionCall: FunctionCallStatement): IStatement {
        val target = globalNamespace.lookup(functionCall.target.nameInSource, functionCall)
        if(target!=null)
            used(target)

        if(functionCall.target.nameInSource.size==1 && BuiltinFunctionNames.contains(functionCall.target.nameInSource[0])) {
            val functionName = functionCall.target.nameInSource[0]
            if (BuiltinFunctionsWithoutSideEffects.contains(functionName)) {
                printWarning("statement has no effect (function return value is discarded)", functionCall.position)
                statementsToRemove.add(functionCall)
            }
        }

        return super.process(functionCall)
    }

    override fun process(jump: Jump): IStatement {
        if(jump.identifier!=null) {
            val target = globalNamespace.lookup(jump.identifier.nameInSource, jump)
            if (target != null)
                used(target)
        }
        return super.process(jump)
    }

    override fun process(ifStatement: IfStatement): IStatement {
        super.process(ifStatement)
        val constvalue = ifStatement.condition.constValue(globalNamespace)
        if(constvalue!=null) {
            return if(constvalue.asBooleanValue){
                // always true -> keep only if-part
                printWarning("condition is always true", ifStatement.position)
                AnonymousStatementList(ifStatement.parent, ifStatement.statements, ifStatement.position)
            } else {
                // always false -> keep only else-part
                printWarning("condition is always false", ifStatement.position)
                AnonymousStatementList(ifStatement.parent, ifStatement.elsepart, ifStatement.position)
            }
        }
        return ifStatement
    }

    override fun process(forLoop: ForLoop): IStatement {
        super.process(forLoop)
        val range = forLoop.iterable as? RangeExpr
        if(range!=null) {
            if(range.size()==1) {
                // for loop over a (constant) range of just a single value-- optimize the loop away
                // loopvar/reg = range value , follow by block
                val assignment = Assignment(AssignTarget(forLoop.loopRegister, forLoop.loopVar, forLoop.position), null, range.from, forLoop.position)
                forLoop.body.add(0, assignment)
                return AnonymousStatementList(forLoop.parent, forLoop.body, forLoop.position)
            }
        }
        return forLoop
    }

    override fun process(whileLoop: WhileLoop): IStatement {
        super.process(whileLoop)
        val constvalue = whileLoop.condition.constValue(globalNamespace)
        if(constvalue!=null) {
            return if(constvalue.asBooleanValue){
                // always true
                printWarning("condition is always true", whileLoop.position)
                whileLoop
            } else {
                // always false -> ditch whole statement
                printWarning("condition is always false", whileLoop.position)
                AnonymousStatementList(whileLoop.parent, emptyList(), whileLoop.position)
            }
        }
        return whileLoop
    }

    override fun process(repeatLoop: RepeatLoop): IStatement {
        super.process(repeatLoop)
        val constvalue = repeatLoop.untilCondition.constValue(globalNamespace)
        if(constvalue!=null) {
            return if(constvalue.asBooleanValue){
                // always true -> keep only the statement block
                printWarning("condition is always true", repeatLoop.position)
                AnonymousStatementList(repeatLoop.parent, repeatLoop.statements, repeatLoop.position)
            } else {
                // always false
                printWarning("condition is always false", repeatLoop.position)
                repeatLoop
            }
        }
        return repeatLoop
    }

    private fun used(stmt: IStatement) {
        val scopedName = when (stmt) {
            is Label -> stmt.scopedname
            is Subroutine -> stmt.scopedname
            else -> throw AstException("invalid call target node type: ${stmt::class}")
        }
        globalNamespace.registerUsedName(scopedName)
    }

    fun removeUnusedNodes(usedNames: Set<String>, allScopedSymbolDefinitions: MutableMap<String, IStatement>) {
        val symbolsToRemove = mutableListOf<String>()

        for ((name, value) in allScopedSymbolDefinitions) {
            if(!usedNames.contains(name)) {
                val parentScope = value.parent as INameScope
                val localname = name.substringAfterLast(".")
                // printing every possible node that is removed can result in many dozens of warnings.
                // we chose to just print the blocks that aren't used.
                if(value is Block)
                    println("${value.position} Info: block '$localname' is never used")
                if(value is VarDecl) {
                    val scope = value.definingScope() as? Subroutine
                    if(scope!=null && scope.parameters.any { it.name==localname})
                        println("${value.position} Info: parameter '$localname' is never used")
                    else
                        println("${value.position} Info: variable '$localname' is never used")
                }
                parentScope.removeStatement(value)
                symbolsToRemove.add(name)
                optimizationsDone++
            }
        }

        for(name in symbolsToRemove) {
            allScopedSymbolDefinitions.remove(name)
        }

        for(stmt in statementsToRemove) {
            stmt.definingScope().removeStatement(stmt)
        }
    }
}