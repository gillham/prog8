package prog8.compiler

import prog8.ast.Program
import prog8.ast.base.FatalAstException
import prog8.ast.expressions.*
import prog8.ast.statements.*
import prog8.code.ast.*
import prog8.code.core.CompilationOptions
import prog8.code.core.DataType
import kotlin.io.path.Path


class IntermediateAstMaker(val program: Program, val comp: CompilationOptions) {
    fun transform(): PtProgram {
        val options = ProgramOptions(
            comp.output,
            comp.launcher,
            comp.zeropage,
            comp.zpReserved,
            program.definedLoadAddress,
            comp.floats,
            comp.noSysInit,
            comp.dontReinitGlobals,
            comp.optimize
        )

        val ptProgram = PtProgram(
            program.name,
            options,
            program.memsizer,
            program.encoding
        )

        // note: modules are not represented any longer in this Ast. All blocks have been moved into the top scope.
        for (block in program.allBlocks)
            ptProgram.add(transform(block))

        return ptProgram
    }

    private fun transformStatement(statement: Statement): PtNode {
        return when (statement) {
            is AnonymousScope -> throw FatalAstException("AnonymousScopes should have been flattened")
            is Assignment -> transform(statement)
            is Block -> transform(statement)
            is Break -> throw FatalAstException("break should have been replaced by Goto")
            is BuiltinFunctionCallStatement -> transform(statement)
            is BuiltinFunctionPlaceholder -> throw FatalAstException("BuiltinFunctionPlaceholder should not occur in Ast here")
            is ConditionalBranch -> transform(statement)
            is Directive -> transform(statement)
            is ForLoop -> transform(statement)
            is FunctionCallStatement -> transform(statement)
            is GoSub -> transform(statement)
            is IfElse -> transform(statement)
            is InlineAssembly -> transform(statement)
            is Jump -> transform(statement)
            is Label -> transform(statement)
            is Pipe -> transform(statement)
            is PostIncrDecr -> transform(statement)
            is RepeatLoop -> transform(statement)
            is Return -> transform(statement)
            is Subroutine -> {
                if(statement.isAsmSubroutine)
                    transformAsmSub(statement)
                else
                    transformSub(statement)
            }
            is UntilLoop -> throw FatalAstException("until loops must have been converted to jumps")
            is VarDecl -> transform(statement)
            is When -> transform(statement)
            is WhileLoop -> throw FatalAstException("while loops must have been converted to jumps")
        }
    }

    private fun transformExpression(expr: Expression): PtExpression {
        return when(expr) {
            is AddressOf -> transform(expr)
            is ArrayIndexedExpression -> transform(expr)
            is ArrayLiteral -> transform(expr)
            is BinaryExpression -> transform(expr)
            is BuiltinFunctionCall -> transform(expr)
            is CharLiteral -> throw FatalAstException("char literals should have been converted into bytes")
            is ContainmentCheck -> transform(expr)
            is DirectMemoryRead -> transform(expr)
            is FunctionCallExpression -> transform(expr)
            is IdentifierReference -> transform(expr)
            is NumericLiteral -> transform(expr)
            is PipeExpression -> transform(expr)
            is PrefixExpression -> transform(expr)
            is RangeExpression -> transform(expr)
            is StringLiteral -> transform(expr)
            is TypecastExpression -> transform(expr)
        }
    }

    private fun transform(srcAssign: Assignment): PtAssignment {
        val assign = PtAssignment(srcAssign.isAugmentable, srcAssign.position)
        assign.add(transform(srcAssign.target))
        assign.add(transformExpression(srcAssign.value))
        return assign
    }

    private fun transform(srcTarget: AssignTarget): PtAssignTarget {
        val target = PtAssignTarget(srcTarget.position)
        if(srcTarget.identifier!=null)
            target.add(transform(srcTarget.identifier!!))
        else if(srcTarget.arrayindexed!=null)
            target.add(transform(srcTarget.arrayindexed!!))
        else if(srcTarget.memoryAddress!=null)
            target.add(transform(srcTarget.memoryAddress!!))
        else
            throw FatalAstException("invalid AssignTarget")
        return target
    }

    private fun targetOf(identifier: IdentifierReference): Pair<List<String>, DataType> {
        val target=identifier.targetStatement(program)!! as INamedStatement
        val targetname = if(target.name in program.builtinFunctions.names)
            listOf("<builtin>", target.name)
        else
            target.scopedName
        val type = identifier.inferType(program).getOr(DataType.UNDEFINED)
        return Pair(targetname, type)
    }

    private fun transform(identifier: IdentifierReference): PtIdentifier {
        val (target, type) = targetOf(identifier)
        return PtIdentifier(identifier.nameInSource, target, type, identifier.position)
    }

    private fun transform(srcBlock: Block): PtBlock {
        val block = PtBlock(srcBlock.name, srcBlock.address, srcBlock.isInLibrary, srcBlock.position)

        for (stmt in srcBlock.statements)
            block.add(transformStatement(stmt))

        return block
    }

    private fun transform(srcNode: BuiltinFunctionCallStatement): PtBuiltinFunctionCall {
        val type = builtinFunctionReturnType(srcNode.name, srcNode.args, program).getOr(DataType.UNDEFINED)
        val call = PtBuiltinFunctionCall(srcNode.name, true, type, srcNode.position)
        for (arg in srcNode.args)
            call.add(transformExpression(arg))
        return call
    }

    private fun transform(srcBranch: ConditionalBranch): PtConditionalBranch {
        val branch = PtConditionalBranch(srcBranch.condition, srcBranch.position)
        val trueScope = PtNodeGroup()
        val falseScope = PtNodeGroup()
        for (stmt in srcBranch.truepart.statements)
            trueScope.add(transformStatement(stmt))
        for (stmt in srcBranch.elsepart.statements)
            falseScope.add(transformStatement(stmt))
        branch.add(trueScope)
        branch.add(falseScope)
        return branch
    }

    private fun transform(directive: Directive): PtNode {
        return when(directive.directive) {
            "%breakpoint" -> PtBreakpoint(directive.position)
            "%asmbinary" -> {
                val offset: UInt? = if(directive.args.size>=2) directive.args[1].int!! else null
                val length: UInt? = if(directive.args.size>=3) directive.args[2].int!! else null
                val sourcePath = Path(directive.definingModule.source.origin)
                val includedPath = sourcePath.resolveSibling(directive.args[0].str!!)
                PtInlineBinary(includedPath, offset, length, directive.position)
            }
            else -> PtNop(directive.position)
        }
    }

    private fun transform(srcFor: ForLoop): PtForLoop {
        val forloop = PtForLoop(srcFor.position)
        forloop.add(transform(srcFor.loopVar))
        forloop.add(transformExpression(srcFor.iterable))
        val statements = PtNodeGroup()
        for (stmt in srcFor.body.statements)
            statements.add(transformStatement(stmt))
        forloop.add(statements)
        return forloop
    }

    private fun transform(srcCall: FunctionCallStatement): PtFunctionCall {
        val (target, type) = targetOf(srcCall.target)
        val call = PtFunctionCall(target,true, type, srcCall.position)
        for (arg in srcCall.args)
            call.add(transformExpression(arg))
        return call
    }

    private fun transform(srcCall: FunctionCallExpression): PtFunctionCall {
        val (target, _) = targetOf(srcCall.target)
        val type = srcCall.inferType(program).getOrElse { throw FatalAstException("unknown dt") }
        val call = PtFunctionCall(target, false, type, srcCall.position)
        for (arg in srcCall.args)
            call.add(transformExpression(arg))
        return call
    }

    private fun transform(gosub: GoSub): PtGosub {
        val identifier = if(gosub.identifier!=null) transform(gosub.identifier!!) else null
        return PtGosub(identifier,
            gosub.address,
            gosub.generatedLabel,
            gosub.position)
    }

    private fun transform(srcIf: IfElse): PtIfElse {
        val ifelse = PtIfElse(srcIf.position)
        ifelse.add(transformExpression(srcIf.condition))
        val ifScope = PtNodeGroup()
        val elseScope = PtNodeGroup()
        for (stmt in srcIf.truepart.statements)
            ifScope.add(transformStatement(stmt))
        for (stmt in srcIf.elsepart.statements)
            elseScope.add(transformStatement(stmt))
        ifelse.add(ifScope)
        ifelse.add(elseScope)
        return ifelse
    }

    private fun transform(srcNode: InlineAssembly): PtInlineAssembly =
        PtInlineAssembly(srcNode.assembly, srcNode.position)

    private fun transform(srcJump: Jump): PtJump {
        val identifier = if(srcJump.identifier!=null) transform(srcJump.identifier!!) else null
        return PtJump(identifier,
            srcJump.address,
            srcJump.generatedLabel,
            srcJump.position)
    }

    private fun transform(label: Label): PtLabel =
        PtLabel(label.name, label.position)

    private fun transform(srcPipe: Pipe): PtPipe {
        val type = srcPipe.segments.last().inferType(program).getOrElse { throw FatalAstException("unknown dt") }
        val pipe = PtPipe(type, true, srcPipe.position)
        pipe.add(transformExpression(srcPipe.source))
        for (segment in srcPipe.segments)
            pipe.add(transformExpression(segment))
        return pipe
    }

    private fun transform(src: PostIncrDecr): PtPostIncrDecr {
        val post = PtPostIncrDecr(src.operator, src.position)
        post.add(transform(src.target))
        return post
    }

    private fun transform(srcRepeat: RepeatLoop): PtRepeatLoop {
        if(srcRepeat.iterations==null)
            throw FatalAstException("repeat-forever loop should have been replaced with label+jump")
        val repeat = PtRepeatLoop(srcRepeat.position)
        repeat.add(transformExpression(srcRepeat.iterations!!))
        val scope = PtNodeGroup()
        for (statement in srcRepeat.body.statements) {
            scope.add(transformStatement(statement))
        }
        repeat.add(scope)
        return repeat
    }

    private fun transform(srcNode: Return): PtReturn {
        val ret = PtReturn(srcNode.position)
        if(srcNode.value!=null)
            ret.add(transformExpression(srcNode.value!!))
        return ret
    }

    private fun transformAsmSub(srcSub: Subroutine): PtAsmSub {
        val params = srcSub.parameters
            .map { PtSubroutineParameter(it.name, it.type, it.position) }
            .zip(srcSub.asmParameterRegisters)
        val sub = PtAsmSub(srcSub.name,
            srcSub.asmAddress,
            srcSub.asmClobbers,
            params,
            srcSub.asmReturnvaluesRegisters,
            srcSub.inline,
            srcSub.position)
        if(srcSub.asmAddress==null) {
            var combinedAsm = ""
            for (asm in srcSub.statements)
                combinedAsm += (asm as InlineAssembly).assembly + "\n"
            if(combinedAsm.isNotEmpty())
                sub.add(PtInlineAssembly(combinedAsm, srcSub.statements[0].position))
            else
                sub.add(PtInlineAssembly("", srcSub.position))
        }

        return sub
    }

    private fun transformSub(srcSub: Subroutine): PtSub {
        val sub = PtSub(srcSub.name,
            srcSub.parameters.map { PtSubroutineParameter(it.name, it.type, it.position) },
            srcSub.returntypes.singleOrNull(),
            srcSub.inline,
            srcSub.position)

        for (statement in srcSub.statements)
            sub.add(transformStatement(statement))

        return sub
    }

    private fun transform(srcVar: VarDecl): PtNode {
        return when(srcVar.type) {
            VarDeclType.VAR -> {
                val value = if(srcVar.value!=null) transformExpression(srcVar.value!!) else null
                PtVariable(srcVar.name, srcVar.datatype, value, srcVar.position)
            }
            VarDeclType.CONST -> PtConstant(srcVar.name, srcVar.datatype, (srcVar.value as NumericLiteral).number, srcVar.position)
            VarDeclType.MEMORY -> PtMemMapped(srcVar.name, srcVar.datatype, (srcVar.value as NumericLiteral).number.toUInt(), srcVar.position)
        }
    }

    private fun transform(srcWhen: When): PtWhen {
        val w = PtWhen(srcWhen.position)
        w.add(transformExpression(srcWhen.condition))
        val choices = PtNodeGroup()
        for (choice in srcWhen.choices)
            choices.add(transform(choice))
        w.add(choices)
        return w
    }

    private fun transform(srcChoice: WhenChoice): PtWhenChoice {
        val choice = PtWhenChoice(srcChoice.values==null, srcChoice.position)
        val values = PtNodeGroup()
        val statements = PtNodeGroup()
        if(!choice.isElse) {
            for (value in srcChoice.values!!)
                values.add(transformExpression(value))
        }
        for (stmt in srcChoice.statements.statements)
            statements.add(transformStatement(stmt))
        choice.add(values)
        choice.add(statements)
        return choice
    }

    private fun transform(src: AddressOf): PtAddressOf {
        val addr = PtAddressOf(src.position)
        addr.add(transform(src.identifier))
        return addr
    }

    private fun transform(srcArr: ArrayIndexedExpression): PtArrayIndexer {
        val type = srcArr.inferType(program).getOrElse { throw FatalAstException("unknown dt") }
        val array = PtArrayIndexer(type, srcArr.position)
        array.add(transform(srcArr.arrayvar))
        array.add(transformExpression(srcArr.indexer.indexExpr))
        return array
    }

    private fun transform(srcArr: ArrayLiteral): PtArrayLiteral {
        val arr = PtArrayLiteral(srcArr.type.getOrElse { throw FatalAstException("array must know its type") }, srcArr.position)
        for (elt in srcArr.value)
            arr.add(transformExpression(elt))
        return arr
    }

    private fun transform(srcExpr: BinaryExpression): PtBinaryExpression {
        val type = srcExpr.inferType(program).getOrElse { throw FatalAstException("unknown dt") }
        val expr = PtBinaryExpression(srcExpr.operator, type, srcExpr.position)
        expr.add(transformExpression(srcExpr.left))
        expr.add(transformExpression(srcExpr.right))
        return expr
    }

    private fun transform(srcCall: BuiltinFunctionCall): PtBuiltinFunctionCall {
        val type = srcCall.inferType(program).getOrElse { throw FatalAstException("unknown dt") }
        val call = PtBuiltinFunctionCall(srcCall.name, false, type, srcCall.position)
        for (arg in srcCall.args)
            call.add(transformExpression(arg))
        return call
    }

    private fun transform(srcCheck: ContainmentCheck): PtContainmentCheck {
        val check = PtContainmentCheck(srcCheck.position)
        check.add(transformExpression(srcCheck.element))
        check.add(transformExpression(srcCheck.iterable))
        return check
    }

    private fun transform(memory: DirectMemoryWrite): PtMemoryByte {
        val mem = PtMemoryByte(memory.position)
        mem.add(transformExpression(memory.addressExpression))
        return mem
    }

    private fun transform(memory: DirectMemoryRead): PtMemoryByte {
        val mem = PtMemoryByte(memory.position)
        mem.add(transformExpression(memory.addressExpression))
        return mem
    }

    private fun transform(number: NumericLiteral): PtNumber =
        PtNumber(number.type, number.number, number.position)

    private fun transform(srcPipe: PipeExpression): PtPipe {
        val type = srcPipe.inferType(program).getOrElse { throw FatalAstException("unknown dt") }
        val pipe = PtPipe(type, false, srcPipe.position)
        pipe.add(transformExpression(srcPipe.source))
        for (segment in srcPipe.segments)
            pipe.add(transformExpression(segment))
        return pipe
    }

    private fun transform(srcPrefix: PrefixExpression): PtPrefix {
        val type = srcPrefix.inferType(program).getOrElse { throw FatalAstException("unknown dt") }
        val prefix = PtPrefix(srcPrefix.operator, type, srcPrefix.position)
        prefix.add(transformExpression(srcPrefix.expression))
        return prefix
    }

    private fun transform(srcRange: RangeExpression): PtRange {
        val type = srcRange.inferType(program).getOrElse { throw FatalAstException("unknown dt") }
        val range=PtRange(type, srcRange.position)
        range.add(transformExpression(srcRange.from))
        range.add(transformExpression(srcRange.to))
        range.add(transformExpression(srcRange.step))
        return range
    }

    private fun transform(srcString: StringLiteral): PtString =
        PtString(srcString.value, srcString.encoding, srcString.position)

    private fun transform(srcCast: TypecastExpression): PtTypeCast {
        val cast = PtTypeCast(srcCast.type, srcCast.position)
        cast.add(transformExpression(srcCast.expression))
        return cast
    }
}