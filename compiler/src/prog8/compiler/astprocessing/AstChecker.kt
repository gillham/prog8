package prog8.compiler.astprocessing

import prog8.ast.*
import prog8.ast.FatalAstException
import prog8.ast.SyntaxError
import prog8.ast.expressions.*
import prog8.ast.statements.*
import prog8.ast.walk.IAstVisitor
import prog8.code.core.*
import prog8.code.target.Cx16Target
import prog8.code.target.VMTarget
import prog8.compiler.builtinFunctionReturnType
import java.io.CharConversionException
import java.io.File
import kotlin.io.path.Path
import kotlin.math.floor

/**
 * Semantic analysis and error reporting.
 */
internal class AstChecker(private val program: Program,
                          private val errors: IErrorReporter,
                          private val compilerOptions: CompilationOptions
) : IAstVisitor {

    override fun visit(program: Program) {
        require(program === this.program)
        // there must be a single 'main' block with a 'start' subroutine for the program entry point.
        val mainBlocks = program.modules.flatMap { it.statements }.filter { b -> b is Block && b.name=="main" }.map { it as Block }
        if(mainBlocks.size>1)
            errors.err("more than one 'main' block", mainBlocks[0].position)
        if(mainBlocks.isEmpty())
            errors.err("there is no 'main' block", program.modules.firstOrNull()?.position ?: Position.DUMMY)

        for(mainBlock in mainBlocks) {
            val startSub = mainBlock.subScope("start") as? Subroutine
            if (startSub == null) {
                errors.err("missing program entrypoint ('start' subroutine in 'main' block)", mainBlock.position)
            } else {
                if (startSub.parameters.isNotEmpty() || startSub.returntypes.isNotEmpty())
                    errors.err("program entrypoint subroutine can't have parameters and/or return values", startSub.position)
            }
        }

        if(compilerOptions.floats) {
            if (compilerOptions.zeropage !in arrayOf(ZeropageType.FLOATSAFE, ZeropageType.BASICSAFE, ZeropageType.DONTUSE ))
                errors.err("when floats are enabled, zero page type should be 'floatsafe' or 'basicsafe' or 'dontuse'", program.toplevelModule.position)
        }

        super.visit(program)
    }

    override fun visit(module: Module) {
        super.visit(module)
        if(module.name.startsWith('_'))
            errors.err("identifiers cannot start with an underscore", module.position)
        val directives = module.statements.filterIsInstance<Directive>().groupBy { it.directive }
        directives.filter { it.value.size > 1 }.forEach{ entry ->
            when(entry.key) {
                "%output", "%launcher", "%zeropage", "%address", "%memtop", "%encoding" ->
                    entry.value.forEach { errors.err("directive can just occur once", it.position) }
            }
        }
    }

    override fun visit(identifier: IdentifierReference) {
        if(identifier.nameInSource.any { it.startsWith('_') }) {
            errors.err("identifiers cannot start with an underscore", identifier.position)
        }
        if(identifier.nameInSource.any { it=="void" }) {
            // 'void' as "identifier" is only allowed as part of a multi-assignment expression
            if (!(identifier.nameInSource == listOf("void") && (identifier.parent as? AssignTarget)?.multi?.isNotEmpty() == true
                || identifier.parent is AssignTarget && (identifier.parent.parent as? AssignTarget)?.multi?.isNotEmpty() == true)
            ) {
                errors.err("identifiers cannot contain the 'void' keyword", identifier.position)
            }
        }

        checkLongType(identifier)
        val stmt = identifier.targetStatement(program)
        if(stmt==null)
            errors.undefined(identifier.nameInSource, identifier.position)
        else {
            val target = stmt as? VarDecl
            if (target != null && target.origin == VarDeclOrigin.SUBROUTINEPARAM) {
                if (target.definingSubroutine!!.isAsmSubroutine) {
                    if (target.definingSubroutine!!.parameters.any { it.name == identifier.nameInSource.last() })
                        errors.err("cannot refer to parameter of asmsub by name", identifier.position)
                }
            }
        }

        if(identifier.nameInSource.size>1) {
            val lookupModule = identifier.definingScope.lookup(identifier.nameInSource.take(1))
            if(lookupModule is VarDecl) {
                errors.err("ambiguous symbol name, block name expected but found variable", identifier.position)
            }
        }
    }

    override fun visit(unrollLoop: UnrollLoop) {
        val iterations = unrollLoop.iterations.constValue(program)?.number?.toInt()
        if(iterations==null) {
            errors.err("unroll needs constant number of iterations", unrollLoop.position)
        } else {
            if (iterations < 0 || iterations > 65535)
                errors.err("invalid number of unrolls", unrollLoop.position)
            unrollLoop.body.statements.forEach {
                if (it !is InlineAssembly && it !is Assignment && it !is FunctionCallStatement)
                    errors.err("invalid statement in unroll loop", it.position)
            }
            if (iterations * unrollLoop.body.statements.size > 256) {
                errors.warn("large number of unrolls, potential code size issue", unrollLoop.position)
            }
        }
        super.visit(unrollLoop)
    }

    override fun visit(returnStmt: Return) {
        val expectedReturnValues = returnStmt.definingSubroutine?.returntypes ?: emptyList()
        if(returnStmt.values.size<expectedReturnValues.size) {
            errors.err("too few return values for the subroutine: expected ${expectedReturnValues.size} got ${returnStmt.values.size}", returnStmt.position)
        }
        else if(returnStmt.values.size>expectedReturnValues.size) {
            errors.err("too many return values for the subroutine: expected ${expectedReturnValues.size} got ${returnStmt.values.size}", returnStmt.position)
        }
        for((expectedDt, actual) in expectedReturnValues.zip(returnStmt.values)) {
            val valueDt = actual.inferType(program)
            if(valueDt.isKnown) {
                if (expectedDt != valueDt.getOrUndef()) {
                    if(valueDt.isBool && expectedDt.isUnsignedByte) {
                        // if the return value is a bool and the return type is ubyte, allow this. But give a warning.
                        errors.info("return type of the subroutine should probably be bool instead of ubyte", actual.position)
                    } else if(valueDt.isIterable && expectedDt.isUnsignedWord) {
                        // you can return a string or array when an uword (pointer) is returned
                    } else if(valueDt issimpletype BaseDataType.UWORD && expectedDt.isString) {
                        // you can return an uword pointer when the return type is a string
                    } else {
                        errors.err("return value's type $valueDt doesn't match subroutine's return type ${expectedDt}", actual.position)
                    }
                }
            }
        }

        val statements = (returnStmt.parent as IStatementContainer).statements
        val myIndex = statements.indexOf(returnStmt)
        if(myIndex>=0 && myIndex<statements.size-1) {
            val stmtAfterReturn = statements[myIndex+1]
            if(stmtAfterReturn.position.line==returnStmt.position.line) {
                // this error is not generated by the parser, unfortunately.
                // it parses "return somestatement"  as:  "return"  "somestatement"  (and this then only results in a warning about unreachable code).
                errors.err("a statement is not a return value", stmtAfterReturn.position)
            }
        }

        super.visit(returnStmt)
    }

    override fun visit(ifElse: IfElse) {
        if(!ifElse.condition.inferType(program).isBool) {
            errors.err("condition should be a boolean", ifElse.condition.position)
        }

        val constvalue = ifElse.condition.constValue(program)
        if(constvalue!=null) {
            errors.warn("condition is always ${constvalue.asBooleanValue}", ifElse.condition.position)
        }

        super.visit(ifElse)
    }

    override fun visit(forLoop: ForLoop) {

        fun checkUnsignedLoopDownto0(range: RangeExpression?) {
            if(range==null)
                return
            val step = range.step.constValue(program)?.number ?: 1.0
            if(step < -1.0) {
                val limit = range.to.constValue(program)?.number
                if(limit==0.0 && range.from.constValue(program)==null)
                    errors.err("for unsigned loop variable it's not possible to count down with step != -1 from a non-const value to exactly zero due to value wrapping", forLoop.position)
            }
        }

        val iterableDt = forLoop.iterable.inferType(program).getOrUndef()

        if(iterableDt.isNumeric) TODO("iterable type should not be simple numeric!? "+forLoop.position) // TODO

        if(forLoop.iterable is IFunctionCall) {
            errors.err("can not loop over function call return value", forLoop.position)
        } else if(!(iterableDt.isIterable) && forLoop.iterable !is RangeExpression) {
            errors.err("can only loop over an iterable type", forLoop.position)
        } else {
            val loopvar = forLoop.loopVar.targetVarDecl(program)
            if(loopvar==null || loopvar.type== VarDeclType.CONST) {
                errors.err("for loop requires a variable to loop with", forLoop.position)
            } else {
                require(loopvar.datatype.isNumericOrBool)
                when (loopvar.datatype.base) {
                    BaseDataType.UBYTE -> {
                        if(!iterableDt.isUnsignedByte && !iterableDt.isUnsignedByteArray && !iterableDt.isString)      // TODO remove ubyte check?
                            errors.err("ubyte loop variable can only loop over unsigned bytes or strings", forLoop.position)
                        checkUnsignedLoopDownto0(forLoop.iterable as? RangeExpression)
                    }
                    BaseDataType.BOOL -> {
                        if(!iterableDt.isBoolArray)
                            errors.err("bool loop variable can only loop over boolean array", forLoop.position)
                    }
                    BaseDataType.UWORD -> {
                        if(!iterableDt.isUnsignedByte && !iterableDt.isUnsignedWord && !iterableDt.isString &&      // TODO remove byte and word check?
                                !iterableDt.isUnsignedByteArray && !iterableDt.isUnsignedWordArray &&
                                !iterableDt.isSplitWordArray)
                            errors.err("uword loop variable can only loop over unsigned bytes, words or strings", forLoop.position)

                        checkUnsignedLoopDownto0(forLoop.iterable as? RangeExpression)
                    }
                    BaseDataType.BYTE -> {
                        if(!iterableDt.isSignedByte && !iterableDt.isSignedByteArray)       // TODO remove byte check?
                            errors.err("byte loop variable can only loop over bytes", forLoop.position)
                    }
                    BaseDataType.WORD -> {
                        if(!iterableDt.isSignedByte && !iterableDt.isSignedWord &&
                                !iterableDt.isSignedByteArray && !iterableDt.isUnsignedByteArray &&
                                !iterableDt.isSignedWordArray && !iterableDt.isUnsignedWordArray)
                            errors.err("word loop variable can only loop over bytes or words", forLoop.position)
                    }
                    BaseDataType.FLOAT -> {
                        // Looping over float variables is very inefficient because the loopvar is going to
                        // get copied over with new values all the time. We don't support this for now.
                        // Loop with an integer index variable if you really need to... or write different code.
                        errors.err("for loop only supports integers", forLoop.position)
                    }
                    else -> errors.err("loop variable must be numeric type", forLoop.position)
                }
                if(errors.noErrors()) {
                    // check loop range values
                    val range = forLoop.iterable as? RangeExpression
                    if(range!=null) {
                        val from = range.from as? NumericLiteral
                        val to = range.to as? NumericLiteral
                        if(from != null)
                            checkValueTypeAndRange(loopvar.datatype, from)
                        else if(range.from.inferType(program).isNotAssignableTo(loopvar.datatype))
                            errors.err("range start value is incompatible with loop variable type", range.position)
                        if(to != null)
                            checkValueTypeAndRange(loopvar.datatype, to)
                        else if(!(range.to.inferType(program) istype loopvar.datatype))
                            errors.err("range end value is incompatible with loop variable type", range.position)
                    }
                }
            }
        }

        super.visit(forLoop)
    }

    override fun visit(jump: Jump) {
        val ident = jump.target as? IdentifierReference
        if(ident!=null) {
            val targetStatement = ident.checkFunctionOrLabelExists(program, jump, errors)
            if(targetStatement!=null) {
                if(targetStatement is BuiltinFunctionPlaceholder)
                    errors.err("can't jump to a builtin function", jump.position)
            }
            if(targetStatement is Subroutine && targetStatement.parameters.any()) {
                errors.err("can't jump to a subroutine that takes parameters", jump.position)
            }
        } else {
            val addr = jump.target.constValue(program)?.number
            if (addr!=null && (addr<0 || addr > 65535))
                errors.err("goto address must be uword", jump.position)

            val addressDt = jump.target.inferType(program).getOrUndef()
            if(!(addressDt.isUnsignedByte || addressDt.isUnsignedWord))
                errors.err("goto address must be uword", jump.position)
        }
        super.visit(jump)
    }

    override fun visit(block: Block) {
        if(block.name.startsWith('_'))
            errors.err("identifiers cannot start with an underscore", block.position)

        val addr = block.address
        if (addr!=null) {
            if (addr > 65535u)
                errors.err("block address must be valid integer 0..\$ffff", block.position)
            if(compilerOptions.loadAddress!=0u) {
                val gapsize = compilerOptions.compTarget.STARTUP_CODE_RESERVED_SIZE
                if (addr < compilerOptions.loadAddress + gapsize)
                    errors.err("block address must be at least program load address + $gapsize (to allow for startup logic)", block.position)
            }
        }

        for (statement in block.statements) {
            val ok = when (statement) {
                is Alias,
                is Block,
                is Directive,
                is Label,
                is VarDecl,
                is InlineAssembly,
                is IStatementContainer -> true
                is Assignment -> {
                    val target = statement.target.identifier!!.targetStatement(program)
                    target === statement.previousSibling()      // an initializer assignment is okay
                }
                else -> false
            }
            if (!ok) {
                errors.err("non-declarative statement occurs in block scope, where it will never be executed. Move it to a subroutine instead.", statement.position)
                break
            }
        }

        super.visit(block)
    }

    override fun visit(label: Label) {
        if(label.name.startsWith('_'))
            errors.err("identifiers cannot start with an underscore", label.position)

        // scope check
        if(label.parent !is Block && label.parent !is Subroutine && label.parent !is AnonymousScope) {
            errors.err("Labels can only be defined in the scope of a block, a loop body, or within another subroutine", label.position)
        }
        super.visit(label)
    }

    override fun visit(numLiteral: NumericLiteral) {
        checkLongType(numLiteral)
    }

    private fun hasReturnOrExternalJumpOrRts(scope: IStatementContainer): Boolean {
        class Searcher: IAstVisitor
        {
            var count=0

            override fun visit(returnStmt: Return) {
                count++
            }
            override fun visit(jump: Jump) {
                val jumpTarget = (jump.target as? IdentifierReference)?.targetStatement(program)
                if(jumpTarget!=null) {
                    val sub = jump.definingSubroutine
                    val targetSub = jumpTarget as? Subroutine ?: jumpTarget.definingSubroutine
                    if(sub !== targetSub)
                        count++
                }
                else count++
            }

            override fun visit(inlineAssembly: InlineAssembly) {
                if(inlineAssembly.hasReturnOrRts())
                    count++
            }
        }

        val s=Searcher()
        for(stmt in scope.statements) {
            stmt.accept(s)
            if(s.count>0)
                return true
        }
        return s.count > 0
    }

    override fun visit(subroutine: Subroutine) {
        fun err(msg: String) = errors.err(msg, subroutine.position)

        if(subroutine.name.startsWith('_'))
            errors.err("identifiers cannot start with an underscore", subroutine.position)

        if(subroutine.name in BuiltinFunctions)
            err("cannot redefine a built-in function")

        if(subroutine.parameters.size>6 && !subroutine.isAsmSubroutine && !subroutine.definingBlock.isInLibrary)
            errors.info("subroutine has a large number of parameters, this is slow if called often", subroutine.position)

        val uniqueNames = subroutine.parameters.asSequence().map { it.name }.toSet()
        if(uniqueNames.size!=subroutine.parameters.size)
            err("parameter names must be unique")

        val bank = subroutine.asmAddress?.constbank
        if (bank!=null) {
            if (bank > 255u) err("bank must be 0 to 255")
            if (subroutine.asmAddress?.varbank!=null) throw FatalAstException("need either constant or variable bank")
        }
        val varbank = subroutine.asmAddress?.varbank
        if(varbank!=null) {
            if(varbank.targetVarDecl(program)?.datatype?.isUnsignedByte!=true)
                err("bank variable must be ubyte")
        }
        if(subroutine.inline && subroutine.asmAddress!=null)
            throw FatalAstException("extsub cannot be inline")

        val address = subroutine.asmAddress?.address
        if(address != null && address !is NumericLiteral)
            err("address must be a constant")

        super.visit(subroutine)

        // subroutine must contain at least one 'return' or 'goto'
        // (or if it has an asm block, that must contain a 'rts' or 'jmp' or 'bra')
        if(!hasReturnOrExternalJumpOrRts(subroutine)) {
            if (subroutine.returntypes.isNotEmpty()) {
                // for asm subroutines with an address, no statement check is possible.
                if (subroutine.asmAddress == null && !subroutine.inline)
                    err("non-inline subroutine has result value(s) and thus must have at least one 'return' or external 'goto' in it (or the assembler equivalent in case of %asm)")
            }
        }

        if(subroutine.parent !is Block && subroutine.parent !is Subroutine)
            err("subroutines can only be defined in the scope of a block or within another subroutine")

        if(subroutine.isAsmSubroutine) {
            if(subroutine.asmParameterRegisters.size != subroutine.parameters.size)
                err("number of asm parameter registers is not the isSameAs as number of parameters")
            if(subroutine.asmReturnvaluesRegisters.size != subroutine.returntypes.size)
                err("number of return registers is not the isSameAs as number of return values")
            for(param in subroutine.parameters.zip(subroutine.asmParameterRegisters)) {
                if(param.second.registerOrPair in arrayOf(RegisterOrPair.A, RegisterOrPair.X, RegisterOrPair.Y)) {
                    if (!param.first.type.isByteOrBool)
                        errors.err("parameter '${param.first.name}' should be (u)byte or bool", param.first.position)
                }
                else if(param.second.registerOrPair in arrayOf(RegisterOrPair.AX, RegisterOrPair.AY, RegisterOrPair.XY)) {
                    if (!param.first.type.isWord && !param.first.type.isString && !param.first.type.isArray)
                        err("parameter '${param.first.name}' should be (u)word (an address) or str")
                }
                else if(param.second.statusflag!=null) {
                    if (!param.first.type.isBool)
                        errors.err("parameter '${param.first.name}' should be of type bool", param.first.position)
                }
            }
            subroutine.returntypes.zip(subroutine.asmReturnvaluesRegisters).forEachIndexed { index, pair ->
                if(pair.second.registerOrPair in arrayOf(RegisterOrPair.A, RegisterOrPair.X, RegisterOrPair.Y)) {
                    if (!pair.first.isByteOrBool)
                        err("return type #${index + 1} should be (u)byte")
                }
                else if(pair.second.registerOrPair in arrayOf(RegisterOrPair.AX, RegisterOrPair.AY, RegisterOrPair.XY)) {
                    if (!pair.first.isWord && !pair.first.isString && !pair.first.isArray)
                        err("return type #${index + 1} should be (u)word/address")
                }
                else if(pair.second.statusflag!=null) {
                    if (!pair.first.isBool)
                        err("return type #${index + 1} should be bool")
                }
            }

            val regsUsed = mutableListOf<RegisterOrPair>()
            val statusflagUsed = mutableListOf<Statusflag>()
            fun countRegisters(from: Iterable<RegisterOrStatusflag>) {
                regsUsed.clear()
                statusflagUsed.clear()
                for(p in from) {
                    when(p.registerOrPair) {
                        null -> {
                            if (p.statusflag != null)
                                statusflagUsed += p.statusflag!!
                        }
                        RegisterOrPair.AX -> {
                            regsUsed += RegisterOrPair.A
                            regsUsed += RegisterOrPair.X
                        }
                        RegisterOrPair.AY -> {
                            regsUsed += RegisterOrPair.A
                            regsUsed += RegisterOrPair.Y
                        }
                        RegisterOrPair.XY -> {
                            regsUsed += RegisterOrPair.X
                            regsUsed += RegisterOrPair.Y
                        }
                        else -> regsUsed += p.registerOrPair!!
                    }
                }
            }
            countRegisters(subroutine.asmParameterRegisters)
            if(regsUsed.size != regsUsed.toSet().size)
                err("a register is used multiple times in the parameters")
            if(statusflagUsed.size != statusflagUsed.toSet().size)
                err("a status flag is used multiple times in the parameters")

            countRegisters(subroutine.asmReturnvaluesRegisters)
            if(regsUsed.size != regsUsed.toSet().size)
                err("a register is used multiple times in the return values")
            if(statusflagUsed.size != statusflagUsed.toSet().size)
                err("a status flag is used multiple times in the return values")

            for(reg in subroutine.asmClobbers) {
                if(regsUsed.contains(RegisterOrPair.fromCpuRegister(reg)))
                    err("a return register is also in the clobber list")
            }

            if(subroutine.statements.any{it !is InlineAssembly})
                err("asmsub can only contain inline assembly (%asm)")

            val statusFlagsNoCarry = subroutine.asmParameterRegisters.mapNotNull { it.statusflag }.toSet() - Statusflag.Pc
            if(statusFlagsNoCarry.isNotEmpty())
                err("can only use Carry as status flag parameter")
        } else {
            // normal subroutines only can have R0-R15 as param registers
            val paramsWithRegs = subroutine.parameters.filter { it.registerOrPair!=null }
            val regsUsed = paramsWithRegs.map { it.registerOrPair!! }
            if(regsUsed.size != regsUsed.toSet().size) {
                err("a register is used multiple times in the parameters")
            }
        }

        // Non-string and non-ubytearray Pass-by-reference datatypes can not occur as parameters to a subroutine directly
        // Instead, their reference (address) should be passed (as an UWORD).
        for(p in subroutine.parameters) {
            if (!subroutine.isAsmSubroutine && p.registerOrPair!=null) {
                if (p.registerOrPair !in Cx16VirtualRegisters) errors.err("can only use R0-R15 as register param for normal subroutines", p.position)
                else {
                    if(!compilerOptions.ignoreFootguns)
                        errors.warn("\uD83D\uDCA3 footgun: reusing R0-R15 as parameters risks overwriting due to clobbering or no callstack", subroutine.position)
                    if(!p.type.isWord && !p.type.isByteOrBool) {
                        errors.err("can only use register param when type is boolean, byte or word", p.position)
                    }
                }
            }

            if(p.name.startsWith('_'))
                errors.err("identifiers cannot start with an underscore", p.position)

            if(p.type.isPassByRef && !p.type.isString && !p.type.isUnsignedByteArray) {
                errors.err("this pass-by-reference type can't be used as a parameter type. Instead, use just 'uword' to receive the address, or maybe don't pass the value via a parameter but access it directly.", p.position)
            }
        }
    }

    override fun visit(untilLoop: UntilLoop) {
        if(!untilLoop.condition.inferType(program).isBool) {
            errors.err("condition should be a boolean", untilLoop.condition.position)
        }

        super.visit(untilLoop)
    }

    override fun visit(whileLoop: WhileLoop) {
        if(!whileLoop.condition.inferType(program).isBool) {
            errors.err("condition should be a boolean", whileLoop.condition.position)
        }

        super.visit(whileLoop)
    }

    override fun visit(repeatLoop: RepeatLoop) {
        val iterations = repeatLoop.iterations?.constValue(program)
        if (iterations != null) {
            require(floor(iterations.number)==iterations.number)
            if (iterations.number.toInt() > 65536) errors.err("repeat cannot exceed 65536 iterations", iterations.position)
        }

        val ident = repeatLoop.iterations as? IdentifierReference
        if(ident!=null) {
            val targetVar = ident.targetVarDecl(program)
            if(targetVar==null)
                errors.err("invalid assignment value, maybe forgot '&' (address-of)", ident.position)
        }
        super.visit(repeatLoop)
    }

    override fun visit(assignment: Assignment) {
        if(assignment.target.multi==null) {
            val targetDt = assignment.target.inferType(program)
            val valueDt = assignment.value.inferType(program)
            if(valueDt.isKnown && !(valueDt isAssignableTo targetDt) && !targetDt.isIterable) {
                if(!(valueDt issimpletype  BaseDataType.STR && targetDt issimpletype BaseDataType.UWORD)) {
                    if(targetDt.isUnknown) {
                        if(assignment.target.identifier?.targetStatement(program)!=null)
                            errors.err("target datatype is unknown", assignment.target.position)
                        // otherwise, another error about missing symbol is already reported.
                    }
                }
            }

            if(assignment.value is TypecastExpression) {
                if(assignment.isAugmentable && targetDt issimpletype BaseDataType.FLOAT)
                    errors.err("typecasting a float value in-place makes no sense", assignment.value.position)
            }

            val numvalue = assignment.value.constValue(program)
            if(numvalue!=null && targetDt.isKnown)
                checkValueTypeAndRange(targetDt.getOrUndef(), numvalue)
        }

        if(assignment.target.void && assignment.target.multi?.isNotEmpty()!=true) {
            if(assignment.value is IFunctionCall)
                errors.err("cannot assign to 'void', perhaps a void function call was intended", assignment.position)
            else
                errors.err("cannot assign to 'void'", assignment.position)
            return
        }

        val fcall = assignment.value as? IFunctionCall
        val fcallTarget = fcall?.target?.targetSubroutine(program)
        if(assignment.target.multi!=null) {
            checkMultiAssignment(assignment, fcall, fcallTarget)
        } else if(fcallTarget!=null) {
            if(fcallTarget.returntypes.size!=1) {
                return numberOfReturnValuesError(1, fcallTarget.returntypes, fcall.position)
            }
        }

        super.visit(assignment)
    }

    private fun numberOfReturnValuesError(numAssigns: Int, providedTypes: List<DataType>, position: Position) {
        if(numAssigns<providedTypes.size) {
            val missing = providedTypes.drop(numAssigns).joinToString(", ")
            errors.err("call returns too many values: expected $numAssigns got ${providedTypes.size}, missing assignments for: $missing", position)
        }
        else
            errors.err("call returns too few values: expected $numAssigns got ${providedTypes.size}", position)
    }

    private fun checkMultiAssignment(assignment: Assignment, fcall: IFunctionCall?, fcallTarget: Subroutine?) {
        // multi-assign: check the number of assign targets vs. the number of return values of the subroutine
        // also check the types of the variables vs the types of each return value
        if(fcall==null || fcallTarget==null) {
            errors.err("expected a function call with multiple return values", assignment.value.position)
            return
        }
        val targets = assignment.target.multi!!
        if(fcallTarget.returntypes.size!=targets.size) {
            return numberOfReturnValuesError(targets.size, fcallTarget.returntypes, fcall.position)
        }
        fcallTarget.returntypes.zip(targets).withIndex().forEach { (index, p) ->
            val (returnType, target) = p
            val targetDt = target.inferType(program).getOrUndef()
            if (!target.void && returnType != targetDt)
                errors.err("can't assign returnvalue #${index + 1} to corresponding target; $returnType vs $targetDt", target.position)
        }
    }


    override fun visit(assignTarget: AssignTarget) {
        super.visit(assignTarget)

        val memAddr = assignTarget.memoryAddress?.addressExpression?.constValue(program)?.number?.toInt()
        if (memAddr != null) {
            if (memAddr < 0 || memAddr >= 65536)
                errors.err("address out of range", assignTarget.position)
        }

        if(assignTarget.parent is AssignTarget)
            return      // sub-target of a multi-assign is tested elsewhere

        val assignment = assignTarget.parent as Statement

        for(targetIdentifier in assignTarget.targetIdentifiers()) {
            val targetName = targetIdentifier.nameInSource
            when (val targetSymbol = assignment.definingScope.lookup(targetName)) {
                null -> {
                    errors.undefined(targetIdentifier.nameInSource, targetIdentifier.position)
                    return
                }
                !is VarDecl -> {
                    errors.err("assignment LHS must be register or variable", assignment.position)
                    return
                }
                else -> {
                    if (targetSymbol.type == VarDeclType.CONST) {
                        errors.err("cannot assign new value to a constant", assignment.position)
                        return
                    }
                }
            }
        }

        if (assignment is Assignment) {
            val targetDatatype = assignTarget.inferType(program)
            if (targetDatatype.isKnown) {
                val sourceDatatype = assignment.value.inferType(program)
                if (sourceDatatype.isUnknown) {
                    if (assignment.value !is BinaryExpression && assignment.value !is PrefixExpression && assignment.value !is ContainmentCheck && assignment.value !is IfExpression)
                        errors.err("invalid assignment value, maybe forgot '&' (address-of)", assignment.value.position)
                } else {
                    checkAssignmentCompatible(assignTarget, targetDatatype.getOrUndef(),
                            sourceDatatype.getOrUndef(), assignment.value)
                }
            }
        }
    }

    override fun visit(addressOf: AddressOf) {
        checkLongType(addressOf)
        val variable=addressOf.identifier.targetVarDecl(program)
        if (variable!=null) {
            if (variable.type == VarDeclType.CONST && addressOf.arrayIndex == null)
                errors.err("invalid pointer-of operand type", addressOf.position)
        }

        if(addressOf.msb) {
            if(variable!=null && !variable.datatype.isSplitWordArray)
                errors.err("$> can only be used on split word arrays", addressOf.position)
        }

        super.visit(addressOf)
    }

    override fun visit(ifExpr: IfExpression) {
        if(!ifExpr.condition.inferType(program).isBool)
            errors.err("condition should be a boolean", ifExpr.condition.position)

        val trueDt = ifExpr.truevalue.inferType(program)
        val falseDt = ifExpr.falsevalue.inferType(program)
        if(trueDt.isUnknown || falseDt.isUnknown) {
            errors.err("invalid value type(s)", ifExpr.position)
        } else if(trueDt!=falseDt) {
            errors.err("both values should be the same type", ifExpr.truevalue.position)
        }
        super.visit(ifExpr)
    }

    override fun visit(decl: VarDecl) {
        if(decl.names.size>1)
            throw InternalCompilerException("vardecls with multiple names should have been converted into individual vardecls")

        if(decl.datatype.isLong && decl.type!=VarDeclType.CONST)
            errors.err("cannot use long type for variables; only for constants", decl.position)
        if(decl.type==VarDeclType.MEMORY) {
            if (decl.datatype.isString)
                errors.err("strings cannot be memory-mapped", decl.position)
        }

        fun err(msg: String) = errors.err(msg, decl.position)
        fun valueerr(msg: String) = errors.err(msg, decl.value?.position ?: decl.position)

        // the initializer value can't refer to the variable itself (recursive definition)
        if(decl.value?.referencesIdentifier(listOf(decl.name)) == true || decl.arraysize?.indexExpr?.referencesIdentifier(listOf(decl.name)) == true)
            err("recursive var declaration")

        // CONST can only occur on simple types (byte, word, float)
        if(decl.type== VarDeclType.CONST) {
            if (!decl.datatype.isNumericOrBool)
                err("const can only be used on numeric types or booleans")
        }

        // FLOATS enabled?
        if(!compilerOptions.floats && (decl.datatype.isFloat || decl.datatype.isFloatArray) && decl.type != VarDeclType.MEMORY)
            err("floating point used, but that is not enabled via options")

        // ARRAY without size specifier MUST have an iterable initializer value
        if(decl.isArray && decl.arraysize==null) {
            if(decl.type== VarDeclType.MEMORY)
                err("memory mapped array must have a size specification")
            if(decl.value==null || decl.value is NumericLiteral) {
                err("array variable is missing a size specification")
                return
            }
            if(decl.value is RangeExpression)
                throw InternalCompilerException("range expressions in vardecls should have been converted into array values during constFolding  $decl")
        }

        when(decl.type) {
            VarDeclType.VAR, VarDeclType.CONST -> {
                when(decl.value) {
                    null -> {
                        // a vardecl without an initial value, don't bother with it
                    }
                    is RangeExpression -> throw InternalCompilerException("range expression should have been converted to a true array value")
                    is StringLiteral -> {
                        checkValueTypeAndRangeString(decl.datatype, decl.value as StringLiteral)
                    }
                    is ArrayLiteral -> {
                        val arraySpec = decl.arraysize ?: ArrayIndex.forArray(decl.value as ArrayLiteral)
                        checkValueTypeAndRangeArray(decl.datatype, arraySpec, decl.value as ArrayLiteral)
                    }
                    is NumericLiteral -> {
                        checkValueTypeAndRange(decl.datatype, decl.value as NumericLiteral)
                    }
                    else -> {
                        if(decl.type==VarDeclType.CONST) {
                            valueerr("const declaration needs a compile-time constant initializer value")
                            super.visit(decl)
                            return
                        }
                    }
                }
            }
            VarDeclType.MEMORY -> {
                val arraysize = decl.arraysize
                if(arraysize!=null) {
                    val arraySize = arraysize.constIndex() ?: 1
                    val dt = decl.datatype
                    when {
                        dt.isString || dt.isByteArray || dt.isBoolArray ->
                            if(arraySize > 256)
                                err("byte array length must be 1-256")
                        dt.isSplitWordArray ->
                            if(arraySize > 256)
                                err("split word array length must be 1-256")
                        dt.isWordArray ->
                            if(arraySize > 128)
                                err("regular word array length must be 1-128, use split array to get to 256")
                        dt.isFloatArray ->
                            if(arraySize > 51)
                                err("float array length must be 1-51")
                        else -> {}
                    }
                }
                val numvalue = decl.value as? NumericLiteral
                if(numvalue!=null) {
                    if (!numvalue.type.isInteger || numvalue.number.toInt() < 0 || numvalue.number.toInt() > 65535) {
                        valueerr("memory address must be valid integer 0..\$ffff")
                    }
                } else {
                    valueerr("value of memory mapped variable can only be a constant, maybe use an address pointer type instead?")
                }
            }
        }

        val declValue = decl.value
        if(declValue!=null && decl.type==VarDeclType.VAR) {
            val iDt = declValue.inferType(program)
            if (!(iDt istype decl.datatype)) {
                if(decl.isArray) {
                    val eltDt = decl.datatype.elementType()
                    if(!(iDt istype eltDt))
                        valueerr("initialisation value has incompatible type ($iDt) for the variable (${decl.datatype})")
                } else if(!decl.datatype.isString) {
                    if(!(iDt.isBool && decl.datatype.isUnsignedByte || iDt issimpletype BaseDataType.UBYTE && decl.datatype.isBool))
                        valueerr("initialisation value has incompatible type ($iDt) for the variable (${decl.datatype})")
                }
            }
        }

        if(decl.datatype.isString) {
            if(decl.value==null) {
                // complain about uninitialized str, but only if it's a regular variable
                val parameter = (decl.parent as? Subroutine)?.parameters?.singleOrNull{ it.name==decl.name }
                if(parameter==null)
                    err("string var must be initialized with a string literal")
            }

            if(decl.value !is StringLiteral) {
                if(decl.type==VarDeclType.MEMORY)
                    err("strings can't be memory mapped")
                else
                    valueerr("string var must be initialized with a string literal")
            }
            return
        }

        // array length limits and constant lenghts
        if(decl.isArray) {

            if(decl.type!=VarDeclType.MEMORY) {
                // memory-mapped arrays are initialized with their address, but any other array needs a range or array literal value.

                if (decl.value!=null && decl.value !is ArrayLiteral && decl.value !is RangeExpression) {
                    var suggestion: String? = null
                    val arraysize = decl.arraysize?.constIndex()
                    val numericvalue = decl.value?.constValue(program)
                    if (numericvalue != null && arraysize != null) {
                        when {
                            numericvalue.type.isInteger -> suggestion = "[${numericvalue.number.toInt()}] * $arraysize"
                            numericvalue.type == BaseDataType.FLOAT -> suggestion = "[${numericvalue.number}] * $arraysize"
                            numericvalue.type == BaseDataType.BOOL -> suggestion = "[${numericvalue.asBooleanValue}] * $arraysize"
                            else -> {}
                        }
                    }

                    if (suggestion != null)
                        valueerr("array initialization value must be a range value or an array literal (suggestion: use '$suggestion' here)")
                    else
                        valueerr("array initialization value must be a range value or an array literal")
                }
            }

            val length = decl.arraysize?.constIndex()
            if(length==null)
                err("array length must be known at compile-time")
            else {
                when  {
                    decl.datatype.isString || decl.datatype.isByteArray || decl.datatype.isBoolArray -> {
                        if (length == 0 || length > 256)
                            err("string and byte array length must be 1-256")
                    }
                    decl.datatype.isSplitWordArray -> {
                        if (length == 0 || length > 256)
                            err("split word array length must be 1-256")
                    }
                    decl.datatype.isWordArray -> {
                        if (length == 0 || length > 128)
                            err("regular word array length must be 1-128, use split array to get to 256")
                    }
                    decl.datatype.isFloatArray -> {
                        if (length == 0 || length > 51)
                            err("float array length must be 1-51")
                    }
                    else -> {
                    }
                }
            }

            if(decl.datatype.isSplitWordArray && decl.type==VarDeclType.MEMORY)
                err("memory mapped word arrays cannot be split, should have @nosplit")
        }

        if(decl.datatype.isSplitWordArray) {
            if (!decl.datatype.isWordArray) {
                errors.err("split can only be used on word arrays", decl.position)
            }
        }

        if(decl.alignment>0u) {
            if(decl.alignment !in arrayOf(2u,64u,256u))
                err("variable alignment can only be one of 2 (word), 64 or 256 (page)")
            if(!decl.isArray && !decl.datatype.isString)
                err("only string and array variables can have an alignment option")
            else if(decl.type==VarDeclType.MEMORY)
                err("only normal variables can have an alignment option")
            else if (decl.zeropage == ZeropageWish.REQUIRE_ZEROPAGE || decl.zeropage == ZeropageWish.PREFER_ZEROPAGE) {
                err("zeropage variables can't have alignment")
            } else if(decl.alignment>64u) {
                errors.info("large alignment might waste a lot of memory (check Gaps in assembler output)", decl.position)
            }
        }


        if (decl.dirty) {
            if(decl.datatype.isString)
                errors.err("string variables cannot be @dirty", decl.position)
            else {
                if(decl.value==null) {
                    if(!compilerOptions.ignoreFootguns)
                        errors.warn("\uD83D\uDCA3 footgun: dirty variable, initial value will be undefined", decl.position)
                }
                else
                    errors.err("dirty variable can't have initialization value", decl.position)
            }
        }

        super.visit(decl)
    }

    override fun visit(directive: Directive) {
        fun err(msg: String) {
            errors.err(msg, directive.position)
        }
        when(directive.directive) {
            "%align" -> {
                if(directive.parent !is INameScope || directive.parent is Module)
                    err("this directive can't be used here")
                if(directive.args.size!=1 || directive.args[0].int == null)
                    err("missing correct alignment size")
                if(directive.args[0].int!! >= 64u)
                    errors.info("large alignment might waste a lot of memory (check Gaps in assembler output)", directive.position)
                val prev = directive.previousSibling()
                if (prev !=null && prev !is Block && prev !is Break && prev !is Continue && prev !is Jump && prev !is Return && prev !is Subroutine && prev !is VarDecl)
                    errors.warn("dangerous location for %align, after a regular statement it will likely corrupt the program", directive.position)
            }
            "%output" -> {
                if(directive.parent !is Module)
                    err("this directive may only occur at module level")
                if(directive.args.size!=1 || directive.args[0].string !in OutputType.entries.map {it.name.lowercase()})
                    err("invalid output directive type")
            }
            "%launcher" -> {
                if(directive.parent !is Module)
                    err("this directive may only occur at module level")
                if(directive.args.size!=1 || directive.args[0].string !in CbmPrgLauncherType.entries.map{it.name.lowercase()})
                    err("invalid launcher directive type")
            }
            "%zeropage" -> {
                if(directive.parent !is Module)
                    err("this directive may only occur at module level")
                if(directive.args.size!=1 ||
                        directive.args[0].string != "basicsafe" &&
                        directive.args[0].string != "floatsafe" &&
                        directive.args[0].string != "kernalsafe" &&
                        directive.args[0].string != "dontuse" &&
                        directive.args[0].string != "full")
                    err("invalid zp type, expected basicsafe, floatsafe, kernalsafe, dontuse, or full")
            }
            "%zpreserved", "%zpallowed" -> {
                if(directive.parent !is Module)
                    err("this directive may only occur at module level")
                if(directive.args.size!=2 || directive.args[0].int==null || directive.args[1].int==null)
                    err("requires two addresses (start, end)")
                if(directive.args[0].int!! > 255u || directive.args[1].int!! > 255u)
                    err("start and end addresss must be in Zeropage so 0..255")
            }
            "%address" -> {
                if(directive.parent !is Module)
                    err("this directive may only occur at module level")
                if(directive.args.size!=1 || directive.args[0].int == null)
                    err("invalid address directive, expected numeric address argument")
            }
            "%memtop" -> {
                if(directive.parent !is Module)
                    err("this directive may only occur at module level")
                if(directive.args.size!=1 || directive.args[0].int == null)
                    err("invalid memtop directive, expected numeric address argument")
            }
            "%import" -> {
                if(directive.parent !is Module)
                    err("this directive may only occur at module level")
                if(directive.args.size!=1 || directive.args[0].string==null)
                    err("invalid import directive, expected module name argument")
                if(directive.args[0].string == (directive.parent as? Module)?.name)
                    err("invalid import directive, cannot import itself")
            }
            "%breakpoint" -> {
                if(directive.parent !is INameScope && directive.parent !is AnonymousScope || directive.parent is Module)
                    err("this directive can't be used here")
                if(directive.args.isNotEmpty())
                    err("invalid breakpoint directive, expected no arguments")
            }
            "%asminclude" -> {
                if(directive.parent !is INameScope || directive.parent is Module)
                    err("this directive can't be used here")
                if(directive.args.size!=1 || directive.args[0].string==null)
                    err("invalid asminclude directive, expected argument: \"filename\"")
                checkFileExists(directive, directive.args[0].string!!)
            }
            "%asmbinary" -> {
                if(directive.parent !is INameScope || directive.parent is Module)
                    err("this directive can't be used here")
                val errormsg = "invalid asmbinary directive, expected arguments: \"filename\" [, offset [, length ] ]"
                if(directive.args.isEmpty()) err(errormsg)
                else if(directive.args[0].string==null) err(errormsg)
                else if(directive.args.size>=2 && directive.args[1].int==null) err(errormsg)
                else if(directive.args.size==3 && directive.args[2].int==null) err(errormsg)
                else if(directive.args.size>3) err(errormsg)
                else checkFileExists(directive, directive.args[0].string!!)
            }
            "%option" -> {
                if(directive.parent !is Block && directive.parent !is Module)
                    err("this directive may only occur in a block or at module level")
                if(directive.args.isEmpty())
                    err("missing option directive argument(s)")
                else if(directive.args.map{it.string in arrayOf("enable_floats", "force_output", "no_sysinit", "merge", "verafxmuls", "no_symbol_prefixing", "ignore_unused")}.any { !it })
                    err("invalid option directive argument(s)")
                if(directive.parent is Block) {
                    if(directive.args.any {it.string !in arrayOf("force_output", "merge", "verafxmuls", "no_symbol_prefixing", "ignore_unused")})
                        err("using an option that is not valid for blocks")
                }
                if(directive.parent is Module) {
                    if(directive.args.any {it.string !in arrayOf("enable_floats", "no_sysinit", "no_symbol_prefixing", "ignore_unused")})
                        err("using an option that is not valid for modules")
                }
                if(directive.args.any { it.string=="verafxmuls" } && compilerOptions.compTarget.name != Cx16Target.NAME)
                    err("verafx option is only valid on cx16 target")
            }
            "%encoding" -> {
                if(directive.parent !is Module)
                    err("this directive may only occur at module level")
                val allowedEncodings = Encoding.entries.map {it.prefix}
                if(directive.args.size!=1 || directive.args[0].string !in allowedEncodings)
                    err("invalid encoding directive, expected one of $allowedEncodings")
            }
            "%jmptable" -> {
                if(directive.parent !is Block)
                    err("this directive may only occur in a block")
                for(arg in directive.args) {
                    val target = directive.definingScope.lookup(arg.string!!.split('.'))
                    if(target==null)
                        errors.err("undefined symbol: ${arg.string}", arg.position)
                    else if (target !is Subroutine)
                        errors.err("jmptable entry can only be a subroutine: ${arg.string}", arg.position)
                }
            }
            else -> throw SyntaxError("invalid directive ${directive.directive}", directive.position)
        }
        super.visit(directive)
    }

    private fun checkFileExists(directive: Directive, filename: String) {
        if (File(filename).isFile)
            return

        val definingModule = directive.definingModule
        if (definingModule.isLibrary || !definingModule.source.isFromFilesystem)
            return

        val s = definingModule.source.origin
        val sourceFileCandidate = Path(s).resolveSibling(filename).toFile()
        if (sourceFileCandidate.isFile)
            return
        else
            errors.err("included file not found: $filename", directive.position)
    }

    override fun visit(array: ArrayLiteral) {
        if(array.type.isKnown) {
            if (!compilerOptions.floats && (array.type issimpletype BaseDataType.FLOAT || array.type.isFloatArray)) {
                errors.err("floating point used, but that is not enabled via options", array.position)
            }
            val arrayspec = ArrayIndex.forArray(array)
            checkValueTypeAndRangeArray(array.type.getOrUndef(), arrayspec, array)
        }

        if(array.parent is VarDecl) {
            if (!array.value.all { it is NumericLiteral || it is AddressOf })
                errors.err("array literal for variable initialization contains non-constant elements", array.position)
        } else if(array.parent is ForLoop) {
            if (!array.value.all { it.constValue(program) != null })
                errors.err("array literal for iteration must contain constants. Try using a separate array variable instead?", array.position)
        }

        if(array.parent is Assignment) {
            val arraydt = array.inferType(program)
            val assignTarget = (array.parent as Assignment).target
            val targetDt = assignTarget.inferType(program)
            if(arraydt!=targetDt)
                errors.err("value has incompatible type ($arraydt) for the variable ($targetDt)", array.position)
        }

        super.visit(array)
    }

    override fun visit(char: CharLiteral) {
        try {  // just *try* if it can be encoded, don't actually do it
            compilerOptions.compTarget.encodeString(char.value.toString(), char.encoding)
        } catch (cx: CharConversionException) {
            errors.err(cx.message ?: "can't encode character", char.position)
        }

        super.visit(char)
    }

    override fun visit(string: StringLiteral) {
        checkValueTypeAndRangeString(DataType.forDt(BaseDataType.STR), string)

        try {  // just *try* if it can be encoded, don't actually do it
            val bytes = compilerOptions.compTarget.encodeString(string.value, string.encoding)
            if(0u in bytes)
                errors.info("a character in the string encodes as 0-byte, which will terminate the string prematurely", string.position)
        } catch (cx: CharConversionException) {
            errors.err(cx.message ?: "can't encode string", string.position)
        }

        super.visit(string)
    }

    override fun visit(expr: PrefixExpression) {

        if(expr.expression is IFunctionCall) {
            val targetStatement = (expr.expression as IFunctionCall).target.targetSubroutine(program)
            if(targetStatement?.returntypes?.isEmpty()==true) {
                errors.err("subroutine doesn't return a value", expr.expression.position)
            }
        }

        checkLongType(expr)
        val dt = expr.expression.inferType(program).getOrUndef()
        if(!dt.isUndefined) {
            when (expr.operator) {
                "-" -> {
                    if (!(dt.isSigned && dt.isNumeric)) {
                        errors.err("can only take negative of a signed number type", expr.position)
                    }
                }
                "~" -> {
                    if(!dt.isInteger)
                        errors.err("can only use bitwise invert on integer types", expr.position)
                    else if(dt.isBool)
                        errors.err("bitwise invert is for integer types, use 'not' on booleans", expr.position)
                }
                "not" -> {
                    if(!dt.isBool) {
                        errors.err("logical not is for booleans", expr.position)
                    }
                }
            }
        }
        super.visit(expr)
    }

    override fun visit(defer: Defer) {
        class Searcher: IAstVisitor
        {
            var count=0

            override fun visit(returnStmt: Return) {
                count++
            }
            override fun visit(jump: Jump) {
                val jumpTarget = (jump.target as? IdentifierReference)?.targetStatement(program)
                if(jumpTarget!=null) {
                    val sub = jump.definingSubroutine
                    val targetSub = jumpTarget as? Subroutine ?: jumpTarget.definingSubroutine
                    if(sub !== targetSub)
                        count++
                }
                else count++
            }

            override fun visit(inlineAssembly: InlineAssembly) {
                if(inlineAssembly.hasReturnOrRts())
                    count++
            }
        }
        val s = Searcher()
        defer.scope.accept(s)
        if(s.count>0)
            errors.err("defer cannot contain jumps or returns", defer.position)
    }

    override fun visit(expr: BinaryExpression) {
        super.visit(expr)
        checkLongType(expr)

        val leftIDt = expr.left.inferType(program)
        val rightIDt = expr.right.inferType(program)
        if(!leftIDt.isKnown || !rightIDt.isKnown) {
            // check if maybe one of the operands is a label, this would need a '&'
            if (!leftIDt.isKnown && expr.left !is FunctionCallExpression)
                errors.err("invalid operand, maybe forgot '&' (address-of)", expr.left.position)
            if (!rightIDt.isKnown && expr.right !is FunctionCallExpression)
                errors.err("invalid operand, maybe forgot '&' (address-of)", expr.right.position)

            return     // hopefully this error will be detected elsewhere
        }

        val leftDt = leftIDt.getOrUndef()
        val rightDt = rightIDt.getOrUndef()

        if(expr.operator=="+" || expr.operator=="-") {
            if(leftDt.isString || rightDt.isString || leftDt.isArray || rightDt.isArray) {
                errors.err("missing & (address-of) on the operand", expr.position)
                return
            }
        }

        when(expr.operator){
            "/", "%" -> {
                val constvalRight = expr.right.constValue(program)
                val divisor = constvalRight?.number
                if(divisor==0.0)
                    errors.err("division by zero", expr.right.position)
                if(expr.operator=="%") {
                    if ((!rightDt.isUnsignedByte && !rightDt.isUnsignedWord) || (!leftDt.isUnsignedByte && !leftDt.isUnsignedWord))
                        errors.err("remainder can only be used on unsigned integer operands", expr.right.position)
                }
            }
            "in" -> throw FatalAstException("in expression should have been replaced by containmentcheck")
            "<<", ">>" -> {
                if(rightDt.isWord) {
                    val shift = expr.right.constValue(program)?.number?.toInt()
                    if(shift==null || shift > 255) {
                        errors.err("shift by a word value not supported, max is a byte", expr.position)
                    }
                }
            }
        }

        if(!leftDt.isNumeric && !leftDt.isString && !leftDt.isBool)
            errors.err("left operand is not numeric or str", expr.left.position)
        if(!rightDt.isNumeric && !rightDt.isString && !rightDt.isBool)
            errors.err("right operand is not numeric or str", expr.right.position)
        if(leftDt!=rightDt) {
            if(leftDt.isString && rightDt.isInteger && expr.operator=="*") {
                // exception allowed: str * constvalue
                if(expr.right.constValue(program)==null)
                    errors.err("can only use string repeat with a constant number value", expr.left.position)
            } else if(leftDt.isBool && rightDt.isByte || leftDt.isByte && rightDt.isBool) {
                // expression with one side BOOL other side (U)BYTE is allowed; bool==byte
            } else if((expr.operator == "<<" || expr.operator == ">>") && (leftDt.isWord && rightDt.isByte)) {
                // exception allowed: shifting a word by a byte
            } else if((expr.operator in BitwiseOperators) && (leftDt.isInteger && rightDt.isInteger)) {
                // exception allowed: bitwise operations with any integers
            } else if((leftDt.isUnsignedWord && rightDt.isString) || (leftDt.isString && rightDt.isUnsignedWord)) {
                // exception allowed: comparing uword (pointer) with string
            } else {
                errors.err("left and right operands aren't the same type: $leftDt vs $rightDt", expr.position)
            }
        }

        if(expr.operator !in ComparisonOperators) {
            if (leftDt.isString && rightDt.isString || leftDt.isArray && rightDt.isArray) {
                // str+str  and  str*number have already been const evaluated before we get here.
                errors.err("no computational or logical expressions with strings or arrays are possible", expr.position)
            }
        } else {
            if(expr.left is TypecastExpression && expr.right is NumericLiteral && !(expr.right.inferType(program) issimpletype BaseDataType.FLOAT)) {
                val origLeftDt = (expr.left as TypecastExpression).expression.inferType(program).getOrUndef()
                if(rightDt.largerSizeThan(origLeftDt) && !(expr.right as NumericLiteral).cast(origLeftDt.base, true).isValid)
                    errors.err("operands are not the same type", expr.right.position)
            }
            if(expr.right is TypecastExpression && expr.left is NumericLiteral && !(expr.left.inferType(program) issimpletype BaseDataType.FLOAT)) {
                val origRightDt = (expr.right as TypecastExpression).expression.inferType(program).getOrUndef()
                if(leftDt.largerSizeThan(origRightDt) && !(expr.left as NumericLiteral).cast(origRightDt.base, true).isValid)
                    errors.err("operands are not the same type", expr.right.position)
            }
        }

        if(leftDt.isBool || rightDt.isBool ||
            (expr.left as? TypecastExpression)?.expression?.inferType(program)?.isBool==true ||
            (expr.right as? TypecastExpression)?.expression?.inferType(program)?.isBool==true) {
            if(expr.operator in arrayOf("<", "<=", ">", ">=")) {
                errors.err("can't use boolean operand with this comparison operator", expr.position)
            }
// for now, don't enforce bool type with only logical operators...
//            if(expr.operator in InvalidOperatorsForBoolean && (leftDt.isBool || (expr.left as? TypecastExpression)?.expression?.inferType(program)?.istype(DataType.BOOL)==true)) {
//                errors.err("can't use boolean operand with this operator ${expr.operator}", expr.left.position)
//            }
            if(expr.operator == "==" || expr.operator == "!=") {
                val leftNum = expr.left.constValue(program)?.number ?: 0.0
                val rightNum = expr.right.constValue(program)?.number ?: 0.0
                if(leftNum>1.0 || rightNum>1.0 || leftNum<0.0 || rightNum<0.0) {
                    errors.warn("expression is always false", expr.position)
                }
            }
            if((expr.operator == "/" || expr.operator == "%") && ( rightDt.isBool || (expr.right as? TypecastExpression)?.expression?.inferType(program)?.isBool==true)) {
                errors.err("can't use boolean operand with this operator ${expr.operator}", expr.right.position)
            }
        }


        if(expr.operator in LogicalOperators) {
            if (!leftDt.isBool || !rightDt.isBool) {
                errors.err("logical operator requires boolean operands", expr.right.position)
            }
        }
        else {
            if (leftDt.isBool || rightDt.isBool) {
                if(expr.operator!="==" && expr.operator!="!=")
                    errors.err("operator requires numeric operands", expr.right.position)
            }
        }
    }

    override fun visit(typecast: TypecastExpression) {
        checkLongType(typecast)
        if(typecast.type.isIterable)
            errors.err("cannot type cast to string or array type", typecast.position)

        if(!typecast.expression.inferType(program).isKnown)
            errors.err("this expression doesn't return a value", typecast.expression.position)

        if(typecast.expression is NumericLiteral) {
            val castResult = (typecast.expression as NumericLiteral).cast(typecast.type, typecast.implicit)
            if(castResult.isValid)
                throw FatalAstException("cast should have been performed in const eval already")
            errors.err(castResult.whyFailed!!, typecast.expression.position)
        }

        super.visit(typecast)
    }

    override fun visit(range: RangeExpression) {
        fun err(msg: String) {
            errors.err(msg, range.position)
        }
        super.visit(range)
        val from = range.from.constValue(program)
        val to = range.to.constValue(program)
        val stepLv = range.step.constValue(program)
        if(stepLv==null) {
            err("range step must be a constant integer")
            return
        } else if (!stepLv.type.isInteger || stepLv.number.toInt() == 0) {
            err("range step must be an integer != 0")
            return
        }
        val step = stepLv.number.toInt()
        if(from!=null && to != null) {
            when {
                from.type.isInteger && to.type.isInteger -> {
                    val fromValue = from.number.toInt()
                    val toValue = to.number.toInt()
                    if(fromValue== toValue)
                        errors.warn("range is just a single value, don't use a loop here", range.position)
                    else if(fromValue < toValue && step<=0)
                        err("ascending range requires step > 0")
                    else if(fromValue > toValue && step>=0)
                        err("descending range requires step < 0")
                }
                else -> err("range expression must be over integers or over characters")
            }
        }
    }

    override fun visit(functionCallExpr: FunctionCallExpression) {
        checkLongType(functionCallExpr)
        // this function call is (part of) an expression, which should be in a statement somewhere.
        val stmtOfExpression = findParentNode<Statement>(functionCallExpr)
                ?: throw FatalAstException("cannot determine statement scope of function call expression at ${functionCallExpr.position}")

        val targetStatement = functionCallExpr.target.checkFunctionOrLabelExists(program, stmtOfExpression, errors)
        if(targetStatement!=null)
            checkFunctionCall(targetStatement, functionCallExpr.args, functionCallExpr.position)

        // warn about sgn(unsigned) this is likely a mistake
        if(functionCallExpr.target.nameInSource.last()=="sgn") {
            val sgnArgType = functionCallExpr.args.first().inferType(program)
            if(sgnArgType issimpletype BaseDataType.UBYTE  || sgnArgType issimpletype BaseDataType.UWORD)
                errors.warn("sgn() of unsigned type is always 0 or 1, this is perhaps not what was intended", functionCallExpr.args.first().position)
        }

        val error = VerifyFunctionArgTypes.checkTypes(functionCallExpr, program)
        if(error!=null)
            errors.err(error.first, error.second)

        // functions that don't return a value, can't be used in an expression or assignment
        if(targetStatement is Subroutine) {
            if(targetStatement.returntypes.isEmpty()) {
                if(functionCallExpr.parent is Expression || functionCallExpr.parent is Assignment)
                    errors.err("subroutine doesn't return a value", functionCallExpr.position)
            }
        }
        else if(targetStatement is BuiltinFunctionPlaceholder) {
            if(builtinFunctionReturnType(targetStatement.name).isUnknown) {
                if(functionCallExpr.parent is Expression || functionCallExpr.parent is Assignment)
                    errors.err("function doesn't return a value", functionCallExpr.position)
            }
        }

        if(functionCallExpr.target.nameInSource.singleOrNull() in listOf("peek", "peekw")) {
            val pointervar = functionCallExpr.args[0] as? IdentifierReference
            if(pointervar!=null)
                checkPointer(pointervar)
            val binexpr = functionCallExpr.args[0] as? BinaryExpression
            if(binexpr?.left is IdentifierReference && binexpr.right is NumericLiteral)
                checkPointer(binexpr.left as IdentifierReference)
        }

        super.visit(functionCallExpr)
    }

    override fun visit(functionCallStatement: FunctionCallStatement) {
        // most function calls, even to builtin functions, are still regular FunctionCall nodes here.
        // they get converted to the more specialized node type in BeforeAsmTypecastCleaner
        val targetStatement = functionCallStatement.target.checkFunctionOrLabelExists(program, functionCallStatement, errors)
        if(targetStatement!=null) {
            checkFunctionCall(targetStatement, functionCallStatement.args, functionCallStatement.position)
            checkUnusedReturnValues(functionCallStatement, targetStatement, errors)

            if(functionCallStatement.void) {
                when(targetStatement) {
                    is BuiltinFunctionPlaceholder -> {
                        if(!builtinFunctionReturnType(targetStatement.name).isKnown)
                            errors.info("redundant void", functionCallStatement.position)
                    }
                    is Label -> {
                        errors.info("redundant void", functionCallStatement.position)
                    }
                    is Subroutine -> {
                        if(targetStatement.returntypes.isEmpty())
                            errors.info("redundant void", functionCallStatement.position)
                    }
                    else -> {}
                }
            }
        }

        val funcName = functionCallStatement.target.nameInSource

        if(funcName.size==1) {
            // check some builtin function calls
            if(funcName[0] == "sort") {
                // sort is not supported on float arrays
                val idref = functionCallStatement.args.singleOrNull() as? IdentifierReference
                if(idref!=null && idref.inferType(program).isFloatArray) {
                    errors.err("sorting a floating point array is not supported", functionCallStatement.args.first().position)
                }
            }
            else if(funcName[0].startsWith("divmod")) {
                if(functionCallStatement.args[2] is TypecastExpression || functionCallStatement.args[3] is TypecastExpression) {
                    errors.err("arguments must be all ubyte or all uword", functionCallStatement.position)
                } else {
                    if(functionCallStatement.args[2] !is IdentifierReference || functionCallStatement.args[3] !is IdentifierReference)
                        errors.err("arguments 3 and 4 must be variables to receive the division and remainder", functionCallStatement.position)
                }
            }

            if(funcName[0] in InplaceModifyingBuiltinFunctions) {
                // in-place modification, can be done on specific types of arguments only (variables, array elements)
                if(funcName[0]=="setlsb" || funcName[0]=="setmsb") {
                    val firstArg = functionCallStatement.args[0]
                    if(firstArg !is IdentifierReference && firstArg !is ArrayIndexedExpression)
                        errors.err("this function can only act on an identifier or array element", firstArg.position)
                } else if(funcName[0]=="divmod" || funcName[0].startsWith("divmod__")) {
                    val thirdArg = functionCallStatement.args[2]
                    val fourthArg = functionCallStatement.args[3]
                    if(thirdArg !is IdentifierReference && thirdArg !is ArrayIndexedExpression)
                        errors.err("this function can only act on an identifier or array element", thirdArg.position)
                    if(fourthArg !is IdentifierReference && fourthArg !is ArrayIndexedExpression)
                        errors.err("this function can only act on an identifier or array element", fourthArg.position)
                } else {
                    if(functionCallStatement.args.any { it !is IdentifierReference && it !is ArrayIndexedExpression && it !is DirectMemoryRead })
                        errors.err("invalid argument to a in-place modifying function", functionCallStatement.args.first().position)
                }
            }

        }

        val error = VerifyFunctionArgTypes.checkTypes(functionCallStatement, program)
        if(error!=null)
            errors.err(error.first, error.second)

        if(functionCallStatement.target.nameInSource.singleOrNull() in listOf("poke", "pokew")) {
            val pointervar = functionCallStatement.args[0] as? IdentifierReference
            if(pointervar!=null)
                checkPointer(pointervar)
            val binexpr = functionCallStatement.args[0] as? BinaryExpression
            if(binexpr?.left is IdentifierReference && binexpr.right is NumericLiteral)
                checkPointer(binexpr.left as IdentifierReference)
        }

        super.visit(functionCallStatement)
    }

    private fun checkFunctionCall(target: Statement, args: List<Expression>, position: Position) {
        if(target is BuiltinFunctionPlaceholder) {
            if(target.name=="call") {
                if(args[0] is AddressOf)
                    errors.err("can't call this indirectly, just use normal function call syntax", args[0].position)
                else if(args[0] is IdentifierReference) {
                    val callTarget = (args[0] as IdentifierReference).targetStatement(program)
                    if(callTarget !is VarDecl)
                        errors.err("can't call this indirectly, just use normal function call syntax", args[0].position)
                }
            }
            if(!compilerOptions.floats) {
                if (target.name == "peekf" || target.name == "pokef")
                    errors.err("floating point used, but that is not enabled via options", position)
            }
        }

        if(target is Label && args.isNotEmpty())
            errors.err("cannot use arguments when calling a label", position)

        if(target is Subroutine) {
            if(target.isAsmSubroutine) {
                for (arg in args.zip(target.parameters)) {
                    val argIDt = arg.first.inferType(program)
                    if (!argIDt.isKnown)
                        return
                }

                // check that cx16 virtual registers aren't used as arguments in a conflicting way
                val params = target.asmParameterRegisters.withIndex().toList()
                for(arg in args.withIndex()) {
                    var ident: IdentifierReference? = null
                    if(arg.value is IdentifierReference)
                        ident = arg.value as IdentifierReference
                    else if(arg.value is FunctionCallExpression) {
                        val fcall = arg.value as FunctionCallExpression
                        val name = fcall.target.nameInSource
                        if(name==listOf("lsb") || name==listOf("msb") || name==listOf("msw") || name==listOf("lsw"))
                            ident = fcall.args[0] as? IdentifierReference
                    }
                    if(ident!=null && ident.nameInSource[0] == "cx16" && ident.nameInSource[1].startsWith("r")) {
                        var regname = ident.nameInSource[1].uppercase()
                        val lastLetter = regname.last().lowercaseChar()
                        if(lastLetter in arrayOf('l', 'h', 's')) {
                            regname = regname.substring(0, regname.length - 1)
                            val lastLetter2 = regname.last().lowercaseChar()
                            if(lastLetter2 in arrayOf('l', 'h', 's')) {
                                regname = regname.substring(0, regname.length - 1)
                            }
                        }
                        val reg = RegisterOrPair.valueOf(regname)
                        val same = params.filter { it.value.registerOrPair==reg }
                        for(s in same) {
                            if(s.index!=arg.index) {
                                errors.err("conflicting register $reg used as argument but is also a target register for another parameter", ident.position)
                            }
                        }
                    }
                }
            }

            if(target.returntypes.size>1) {
                if (DataType.forDt(BaseDataType.FLOAT) in target.returntypes) {
                    errors.err("floats cannot be used as part of a multi-value result", target.position)
                }
            }
            if(target.returntypes.size>16) {
                errors.err("cannot have more than 16 return values", target.position)
            }
            if(!target.isAsmSubroutine && target.returntypes.size>3) {
                errors.info("a large number of return values incurs a substantial value copying overhead", target.position)
            }
        }

        args.forEach{
            checkLongType(it)
        }
    }

    private fun checkPointer(pointervar: IdentifierReference) {
        val vardecl = pointervar.targetVarDecl(program)
        if(vardecl?.zeropage == ZeropageWish.NOT_IN_ZEROPAGE)
            errors.info("pointer variable should preferrably be in zeropage but is marked nozp", vardecl.position)
    }

    override fun visit(arrayIndexedExpression: ArrayIndexedExpression) {
        checkLongType(arrayIndexedExpression)
        val target = arrayIndexedExpression.arrayvar.targetStatement(program)
        if(target is VarDecl) {
            if(!target.datatype.isIterable && !target.datatype.isUnsignedWord)
                errors.err("indexing requires an iterable or address uword variable", arrayIndexedExpression.position)
            val indexVariable = arrayIndexedExpression.indexer.indexExpr as? IdentifierReference
            if(indexVariable!=null) {
                if(indexVariable.targetVarDecl(program)?.datatype?.isSigned==true) {
                    errors.err("variable array indexing can't be performed with signed variables", indexVariable.position)
                    return
                }
            }
            val arraysize = target.arraysize?.constIndex()
            val index = arrayIndexedExpression.indexer.constIndex()
            if(arraysize!=null) {
                if(index!=null && (index<0 || index>=arraysize))
                    errors.err("index out of bounds", arrayIndexedExpression.indexer.position)
            } else if(target.datatype.isString) {
                if(target.value is StringLiteral) {
                    // check string lengths for non-memory mapped strings
                    val stringLen = (target.value as StringLiteral).value.length
                    if (index != null && (index < 0 || index >= stringLen))
                        errors.err("index out of bounds", arrayIndexedExpression.indexer.position)
                }
            } else if(index!=null && index<0) {
                errors.err("index out of bounds", arrayIndexedExpression.indexer.position)
            }
        } else
            errors.err("indexing requires a variable to act upon", arrayIndexedExpression.position)

        // check index value 0..255
        val dtxNum = arrayIndexedExpression.indexer.indexExpr.inferType(program)
        if(dtxNum.isKnown && !(dtxNum issimpletype BaseDataType.UBYTE) && !(dtxNum issimpletype BaseDataType.BYTE))
            errors.err("array indexing is limited to byte size 0..255", arrayIndexedExpression.position)

        super.visit(arrayIndexedExpression)
    }

    override fun visit(whenStmt: When) {
        val conditionDt = whenStmt.condition.inferType(program)
        if(conditionDt.isBool)
            errors.err("condition is boolean, use if statement instead", whenStmt.position)
        else if(!conditionDt.isInteger)
            errors.err("when condition must be an integer value", whenStmt.position)
        val tally = mutableSetOf<Int>()
        for((choices, choiceNode) in whenStmt.choiceValues(program)) {
            if(choices!=null) {
                for (c in choices) {
                    if(c in tally)
                        errors.err("choice value already occurs elsewhere", choiceNode.position)
                    else
                        tally.add(c)
                }
            }
        }

        if(whenStmt.choices.isEmpty())
            errors.err("empty when statement", whenStmt.position)

        if(whenStmt.condition.constValue(program)!=null)
            errors.warn("when-value is a constant and will always result in the same choice", whenStmt.condition.position)

        super.visit(whenStmt)
    }

    override fun visit(whenChoice: WhenChoice) {
        val whenStmt = whenChoice.parent as When
        if(whenChoice.values!=null) {
            val conditionType = whenStmt.condition.inferType(program)
            val constvalues = whenChoice.values!!.map { it.constValue(program) to it.position }
            for((constvalue, pos) in constvalues) {
                when {
                    constvalue == null -> errors.err("choice value must be a constant", pos)
                    !constvalue.type.isIntegerOrBool -> errors.err("choice value must be a byte or word", pos)
                    !(conditionType issimpletype constvalue.type) -> {
                        if(conditionType.isKnown) {
                            if(conditionType.isBool) {
                                if(constvalue.number!=0.0 && constvalue.number!=1.0)
                                    errors.err("choice value datatype differs from condition value", pos)
                            } else {
                                errors.err("choice value datatype differs from condition value", pos)
                            }
                        }
                    }
                }
            }
        } else {
            if(whenChoice !== whenStmt.choices.last())
                errors.err("else choice must be the last one", whenChoice.position)
        }
        super.visit(whenChoice)
    }

    override fun visit(containment: ContainmentCheck) {
        val elementDt = containment.element.inferType(program)
        val iterableDt = containment.iterable.inferType(program)

        if (iterableDt.isIterable) {
            if (containment.iterable !is RangeExpression) {
                val iterableEltDt = iterableDt.getOrUndef().elementType()
                val invalidDt = if (elementDt.isBytes) {
                    !iterableEltDt.isByte
                } else if (elementDt.isWords) {
                    !iterableEltDt.isWord
                } else {
                    false
                }
                if (invalidDt)
                    errors.err("element datatype doesn't match iterable datatype", containment.position)
            }
        } else {
            errors.err("iterable must be an array, a string, or a range expression", containment.iterable.position)
        }

        super.visit(containment)
    }

    override fun visit(memread: DirectMemoryRead) {
        if(!(memread.addressExpression.inferType(program) issimpletype BaseDataType.UWORD)) {
            errors.err("address for memory access isn't uword", memread.position)
        }
        val tc = memread.addressExpression as? TypecastExpression
        if(tc!=null && tc.implicit) {
            if(!(tc.expression.inferType(program) issimpletype BaseDataType.UWORD)) {
                errors.err("address for memory access isn't uword", memread.position)
            }
        }

        val pointervar = memread.addressExpression as? IdentifierReference
        if(pointervar!=null)
            checkPointer(pointervar)
        val binexpr = memread.addressExpression as? BinaryExpression
        if(binexpr?.left is IdentifierReference && binexpr.right is NumericLiteral)
            checkPointer(binexpr.left as IdentifierReference)

        super.visit(memread)
    }

    override fun visit(memwrite: DirectMemoryWrite) {
        if(!(memwrite.addressExpression.inferType(program) issimpletype BaseDataType.UWORD)) {
            errors.err("address for memory access isn't uword", memwrite.position)
        }
        val tc = memwrite.addressExpression as? TypecastExpression
        if(tc!=null && tc.implicit) {
            if(!(tc.expression.inferType(program) issimpletype BaseDataType.UWORD)) {
                errors.err("address for memory access isn't uword", memwrite.position)
            }
        }

        val pointervar = memwrite.addressExpression as? IdentifierReference
        if(pointervar!=null)
            checkPointer(pointervar)
        val binexpr = memwrite.addressExpression as? BinaryExpression
        if(binexpr?.left is IdentifierReference && binexpr.right is NumericLiteral)
            checkPointer(binexpr.left as IdentifierReference)

        super.visit(memwrite)
    }

    override fun visit(inlineAssembly: InlineAssembly) {
        if(inlineAssembly.isIR && compilerOptions.compTarget.name != VMTarget.NAME)
            errors.err("%asm containing IR code cannot be translated to 6502 assembly", inlineAssembly.position)
    }

    private fun checkLongType(expression: Expression) {
        if(expression.inferType(program) issimpletype BaseDataType.LONG) {
            if((expression.parent as? VarDecl)?.type!=VarDeclType.CONST) {
                if (expression.parent !is RepeatLoop) {
                    if (errors.noErrorForLine(expression.position))
                        errors.err("integer overflow", expression.position)
                }
            }
        }
    }

    private fun checkValueTypeAndRangeString(targetDt: DataType, value: StringLiteral) : Boolean {
        return if (targetDt.isString) {
            when {
                value.value.length > 255 -> {
                    errors.err("string length must be 0-255", value.position)
                    false
                }
                value.value.isEmpty() -> {
                    val decl = value.parent as? VarDecl
                    if(decl!=null && (decl.zeropage==ZeropageWish.REQUIRE_ZEROPAGE || decl.zeropage==ZeropageWish.PREFER_ZEROPAGE)) {
                        errors.err("string in Zeropage must be non-empty", value.position)
                        false
                    }
                    else true
                }
                else -> true
            }
        }
        else false
    }

    private fun checkValueTypeAndRangeArray(targetDt: DataType, arrayspec: ArrayIndex, value: ArrayLiteral) : Boolean {
        fun err(msg: String) : Boolean {
            errors.err(msg, value.position)
            return false
        }

        if(value.type.isUnknown)
            return false

        when {
            targetDt.isString -> return err("string value expected")
            targetDt.isBoolArray -> {
                // value may be either a single byte, or a byte arraysize (of all constant values)\
                if(value.type istype targetDt) {
                    if(!checkArrayValues(value, targetDt))
                        return false
                    val arraySpecSize = arrayspec.constIndex()
                    val arraySize = value.value.size
                    if(arraySpecSize!=null && arraySpecSize>0) {
                        if(arraySpecSize>256)
                            return err("boolean array length must be 1-256")
                        val expectedSize = arrayspec.constIndex() ?: return err("array size specifier must be constant integer value")
                        if (arraySize != expectedSize)
                            return err("array size mismatch (expecting $expectedSize, got $arraySize)")
                        return true
                    }
                    return err("invalid boolean array size, must be 1-256")
                }
                return err("invalid boolean array initialization value ${value.type}, expected $targetDt")
            }
            targetDt.isByteArray -> {
                // value may be either a single byte, or a byte arraysize (of all constant values), or a range
                if(value.type istype targetDt) {
                    if(!checkArrayValues(value, targetDt))
                        return false
                    val arraySpecSize = arrayspec.constIndex()
                    val arraySize = value.value.size
                    if(arraySpecSize!=null && arraySpecSize>0) {
                        if(arraySpecSize>256)
                            return err("byte array length must be 1-256")
                        val expectedSize = arrayspec.constIndex() ?: return err("array size specifier must be constant integer value")
                        if (arraySize != expectedSize)
                            return err("array size mismatch (expecting $expectedSize, got $arraySize)")
                        return true
                    }
                    return err("invalid byte array size, must be 1-256")
                }
                return err("invalid byte array initialization value ${value.type}, expected $targetDt")
            }
            targetDt.isWordArray -> {
                // value may be either a single word, or a word arraysize, or a range
                if(value.type istype targetDt) {
                    if(!checkArrayValues(value, targetDt))
                        return false
                    val arraySpecSize = arrayspec.constIndex()
                    val arraySize = value.value.size
                    val maxLength = if(targetDt.isSplitWordArray) 256 else 128
                    if(arraySpecSize!=null && arraySpecSize>0) {
                        if(arraySpecSize>maxLength)
                            return err("array length must be 1-$maxLength")
                        val expectedSize = arrayspec.constIndex() ?: return err("array size specifier must be constant integer value")
                        if (arraySize != expectedSize)
                            return err("array size mismatch (expecting $expectedSize, got $arraySize)")
                        return true
                    }
                    return err("invalid array size, must be 1-$maxLength")
                }
                return err("invalid word array initialization value ${value.type}, expected $targetDt")
            }
            targetDt.isFloatArray -> {
                // value may be either a single float, or a float arraysize
                if(value.type istype targetDt) {
                    if(!checkArrayValues(value, targetDt))
                        return false
                    val arraySize = value.value.size
                    val arraySpecSize = arrayspec.constIndex()
                    if(arraySpecSize!=null && arraySpecSize>0) {
                        if(arraySpecSize>51)
                            return err("float array length must be 1-51")
                        val expectedSize = arrayspec.constIndex() ?: return err("array size specifier must be constant integer value")
                        if (arraySize != expectedSize)
                            return err("array size mismatch (expecting $expectedSize, got $arraySize)")
                    } else
                        return err("invalid float array size, must be 1-51")

                    // check if the floating point values are all within range
                    val doubles = value.value.map {it.constValue(program)?.number!!.toDouble()}.toDoubleArray()
                    if(doubles.any { it < compilerOptions.compTarget.FLOAT_MAX_NEGATIVE || it > compilerOptions.compTarget.FLOAT_MAX_POSITIVE })
                        return err("floating point value overflow")
                    return true
                }
                return err("invalid float array initialization value ${value.type}, expected $targetDt")
            }
            else -> return false
        }
    }

    private fun checkValueTypeAndRange(targetDt: DataType, value: NumericLiteral) : Boolean {
        fun err(msg: String) : Boolean {
            errors.err(msg, value.position)
            return false
        }

        when {
            targetDt.isFloat -> {
                val number=value.number
                if (number > compilerOptions.compTarget.FLOAT_MAX_POSITIVE || number < compilerOptions.compTarget.FLOAT_MAX_NEGATIVE)
                    return err("value '$number' out of range")
            }
            targetDt.isUnsignedByte -> {
                if(value.type==BaseDataType.FLOAT)
                    err("unsigned byte value expected instead of float; possible loss of precision")
                val number=value.number
                if (number < 0 || number > 255)
                    return err("value '$number' out of range for unsigned byte")
            }
            targetDt.isSignedByte -> {
                if(value.type==BaseDataType.FLOAT)
                    err("byte value expected instead of float; possible loss of precision")
                val number=value.number
                if (number < -128 || number > 127)
                    return err("value '$number' out of range for byte")
            }
            targetDt.isUnsignedWord -> {
                if(value.type==BaseDataType.FLOAT)
                    err("unsigned word value expected instead of float; possible loss of precision")
                val number=value.number
                if (number < 0 || number > 65535)
                    return err("value '$number' out of range for unsigned word")
            }
            targetDt.isSignedWord -> {
                if(value.type==BaseDataType.FLOAT)
                    err("word value expected instead of float; possible loss of precision")
                val number=value.number
                if (number < -32768 || number > 32767)
                    return err("value '$number' out of range for word")
            }
            targetDt.isLong -> {
                if(value.type==BaseDataType.FLOAT)
                    err("integer value expected instead of float; possible loss of precision")
                val number=value.number
                if (number < -2147483647 || number > 2147483647)
                    return err("value '$number' out of range for long")
            }
            targetDt.isBool -> {
                if (value.type!=BaseDataType.BOOL) {
                    err("type of value ${value.type.toString().lowercase()} doesn't match target $targetDt")
                }
            }
            targetDt.isArray -> {
                return checkValueTypeAndRange(targetDt.elementType(), value)
            }
            else -> return err("type of value ${value.type.toString().lowercase()} doesn't match target $targetDt")
        }
        return true
    }

    private fun checkArrayValues(value: ArrayLiteral, targetDt: DataType): Boolean {
        val array = value.value.map {
            when (it) {
                is NumericLiteral -> it.number.toInt()
                is AddressOf -> it.identifier.nameInSource.hashCode() and 0xffff
                is IdentifierReference -> it.nameInSource.hashCode() and 0xffff
                is TypecastExpression -> {
                    val constVal = it.expression.constValue(program)
                    val cast = constVal?.cast(it.type, true)
                    if(cast==null || !cast.isValid)
                        -9999999
                    else
                        cast.valueOrZero().number
                }
                else -> -9999999
            }
        }
        val correct: Boolean
        when {
            targetDt.isUnsignedByteArray -> {
                correct = array.all { it in 0..255 }
            }
            targetDt.isSignedByteArray -> {
                correct = array.all { it in -128..127 }
            }
            targetDt.isUnsignedWordArray || targetDt.isSplitUnsignedWordArray -> {
                correct = array.all { (it in 0..65535) }
            }
            targetDt.isSignedWordArray || targetDt.isSplitSignedWordArray -> {
                correct = array.all { it in -32768..32767 }
            }
            targetDt.isBoolArray -> {
                correct = array.all { it==0 || it==1 }
            }
            targetDt.isFloatArray -> correct = true
            else -> throw FatalAstException("invalid type $targetDt")
        }
        if (!correct) {
            if (value.parent is VarDecl && !value.value.all { it is NumericLiteral || it is AddressOf })
                errors.err("array literal for variable initialization contains non-constant elements", value.position)
            else
                errors.err("array element out of range for type $targetDt", value.position)
        }
        return correct
    }

    private fun checkAssignmentCompatible(target: AssignTarget,
                                          targetDatatype: DataType,
                                          sourceDatatype: DataType,
                                          sourceValue: Expression) : Boolean {
        val position = sourceValue.position

        if (targetDatatype.isArray) {
            if(sourceValue.inferType(program).isArray)
                errors.err("cannot assign arrays directly. Maybe use sys.memcopy instead.", target.position)
            else
                errors.err("cannot assign value to array. Maybe use sys.memset/memsetw instead.", target.position)
            return false
        }
        if (sourceValue is ArrayLiteral) {
            errors.err("cannot assign array", target.position)
            return false
        }
        if(sourceValue is RangeExpression) {
            errors.err("can't assign a range value to something else", position)
            return false
        }
        if(sourceDatatype.isUndefined) {
            errors.err("assignment right hand side doesn't result in a value", position)
            return false
        }

        val result =  when {
            targetDatatype.isBool -> sourceDatatype.isBool
            targetDatatype.isSignedByte -> sourceDatatype.isSignedByte
            targetDatatype.isUnsignedByte -> sourceDatatype.isUnsignedByte
            targetDatatype.isSignedWord -> sourceDatatype.isSignedWord || sourceDatatype.isByte
            targetDatatype.isUnsignedWord -> sourceDatatype.isUnsignedWord || sourceDatatype.isUnsignedByte
            targetDatatype.isLong -> sourceDatatype.isLong
            targetDatatype.isFloat -> sourceDatatype.isNumeric
            targetDatatype.isString -> sourceDatatype.isString
            else -> false
        }

        if(result)
            return true

        if(sourceDatatype.isWord && targetDatatype.isByte) {
            errors.err("cannot assign word to byte, use msb() or lsb()?", position)
        }
        else if(sourceDatatype.isIterable && targetDatatype.isByte) {
            errors.err("cannot assign string or array to a byte", position)
        }
        else if(sourceDatatype.isFloat&& targetDatatype.isInteger)
            errors.err("cannot assign float to ${targetDatatype}; possible loss of precision. Suggestion: round the value or revert to integer arithmetic", position)
        else if((sourceValue as? BinaryExpression)?.operator in BitwiseOperators && targetDatatype.equalsSize(sourceDatatype)) {
            // this is allowed: bitwise operation between different types as long as they're the same size.
        }
        else if(targetDatatype.isUnsignedWord && sourceDatatype.isPassByRef) {
            // this is allowed: a pass-by-reference datatype into an uword (pointer value).
        }
        else if(sourceDatatype.isArray && targetDatatype.isArray) {
            // this is allowed (assigning array to array)
        }
        else if(sourceDatatype.isBool && !targetDatatype.isBool) {
            errors.err("type of value $sourceDatatype doesn't match target $targetDatatype", position)
        }
        else if(targetDatatype.isBool && !sourceDatatype.isBool) {
            errors.err("type of value $sourceDatatype doesn't match target $targetDatatype", position)
        }
        else if(targetDatatype.isString) {
            if(sourceDatatype.isUnsignedWord)
                errors.err("can't assign UWORD to STR. If the source is a string and you actually want to overwrite the target string, use an explicit strings.copy(src,tgt) instead.", position)
            else
                errors.err("type of value $sourceDatatype doesn't match target $targetDatatype", position)
        }
        else {
            errors.err("type of value $sourceDatatype doesn't match target $targetDatatype", position)
        }

        return false
    }
}

internal fun checkUnusedReturnValues(call: FunctionCallStatement, target: Statement, errors: IErrorReporter) {
    if (!call.void) {
        // check for unused return values
        if (target is Subroutine && target.returntypes.isNotEmpty()) {
            if (target.returntypes.size == 1)
                errors.info("result value of subroutine call is discarded (use void?)", call.position)
            else
                errors.info("result values of subroutine call are discarded (use void?)", call.position)
        } else if (target is BuiltinFunctionPlaceholder) {
            val rt = builtinFunctionReturnType(target.name)
            if (rt.isKnown)
                errors.info("result value of a function call is discarded (use void?)", call.position)
        }
    }
}
