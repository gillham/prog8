package prog8.codegen.llvm.lower

import prog8.code.core.Position
import prog8.codegen.llvm.LlvmPrinter
import prog8.codegen.llvm.model.BinaryOperator
import prog8.codegen.llvm.model.CastOperator
import prog8.codegen.llvm.model.IcmpCondition
import prog8.codegen.llvm.model.LlvmBlock
import prog8.codegen.llvm.model.LlvmFunction
import prog8.codegen.llvm.model.LlvmGlobal
import prog8.codegen.llvm.model.LlvmInstruction
import prog8.codegen.llvm.model.LlvmLinkage
import prog8.codegen.llvm.model.LlvmModule
import prog8.codegen.llvm.model.LlvmType
import prog8.codegen.llvm.model.LlvmValue
import prog8.codegen.llvm.types.LlvmTypeMapper
import prog8.intermediate.*

private const val PROGRAM_START = "main.start"
// the leading dot keeps it apart from any Prog8 symbol
private const val GLOBAL_INITS = ".init.globals"

/**
 * Translates the subroutines of an IR program into LLVM functions, with one LLVM block per IR chunk.
 * IR virtual registers live in entry-block stack slots, which LLVM's mem2reg pass turns into SSA values.
 */
internal class LlvmFunctionBuilder(val program: IRProgram, val symbols: LlvmDataSymbols) {
    val mapper = LlvmTypeMapper(program.st, program.options.compTarget)
    private val subroutines = program.allSubs().associateBy { it.label }
    private val asmSubroutines = program.allAsmSubs().associateBy { it.label }
    private val externalFunctions = linkedMapOf<String, LlvmFunction>()
    private val externalRoutines = linkedMapOf<String, Pair<IRAsmSubroutine, LlvmFunction>>()
    private val runtimeConflicts = mutableListOf<Pair<String, LlvmLoweringException>>()
    private val helperConflicts = mutableMapOf<String, MutableList<LlvmLoweringException>>()
    private var loweringSub = ""
    private var definingHelper: String? = null
    private val helperFunctions = linkedMapOf<String, LlvmFunction>()
    private val hardwareSlots = sortedMapOf<String, LlvmGlobal>()
    private val runtimeGlobals = linkedMapOf<String, LlvmGlobal>()

    /**
     * Adds a function for every subroutine to [module], and one for the code that initializes global variables,
     * plus the declarations, helper functions and globals that those need.
     */
    fun build(module: LlvmModule) {
        declareExternalRoutines()
        for (block in program.blocks) {
            for (child in block.children) {
                when (child) {
                    is IRSubroutine -> module.functions += function(child)
                    is IRAsmSubroutine -> declaration(child).let { if (!child.isExternal || externalRoutines.getValue(it.name).first === child) module.functions += it }
                    is IRCodeChunkBase -> require(child.isEmpty()) { "block ${block.label} has code outside of a subroutine, which isn't supported" }
                }
            }
        }
        if (program.globalInits.isNotEmpty())
            module.functions += function(IRSubroutine(GLOBAL_INITS, emptyList(), emptyList(), Position.DUMMY).also { it += program.globalInits })
        reportReachableConflict()
        module.functions += helperFunctions.values
        module.functions += externalFunctions.values
        module.globals += hardwareSlots.values
        module.globals += runtimeGlobals.values
    }

    /** Like [build], and adds the program's C entry point `main` as the last function. */
    fun buildProgram(module: LlvmModule) {
        // first, as build() emits the runtime globals that main stores the command line arguments in
        val main = entryPoint()
        build(module)
        module.functions += main
    }

    /**
     * The program's C entry point `main`: stores the command line arguments, initializes the global variables that need code
     * for that, runs main.start, and returns 0.
     */
    private fun entryPoint(): LlvmFunction {
        val start = requireNotNull(subroutine(PROGRAM_START)) { "the program has no $PROGRAM_START subroutine to begin with" }
        require(start.parameters.isEmpty()) { "$PROGRAM_START can't have parameters" }
        val argc = LlvmValue.Local("argc", LlvmType.I32)
        val argv = LlvmValue.Local("argv", LlvmType.Ptr)
        val code = mutableListOf<LlvmInstruction>(LlvmInstruction.Store(argc, argumentCount()), LlvmInstruction.Store(argv, argumentArray()))
        if (program.globalInits.isNotEmpty())
            code += LlvmInstruction.Call(null, GLOBAL_INITS, emptyList(), LlvmType.Void)
        val startType = returnType(start)
        val ignoredResult = if (startType == LlvmType.Void) null else LlvmValue.Local("ignored", startType)
        code += LlvmInstruction.Call(ignoredResult, PROGRAM_START, emptyList(), startType)
        code += LlvmInstruction.Ret(LlvmValue.IntConstant(0, LlvmType.I32))
        return LlvmFunction("main", LlvmType.I32, listOf(argc, argv), mutableListOf(LlvmBlock("entry", code)))
    }

    /** The argc of the C main, which stays 0 in a module without that entry point. */
    fun argumentCount(): LlvmValue.Global = runtimeGlobal(".sys.argc", LlvmType.I32)

    /** The argv of the C main: the addresses of the command line argument strings. */
    fun argumentArray(): LlvmValue.Global = runtimeGlobal(".sys.argv", LlvmType.Ptr)

    fun function(sub: IRSubroutine): LlvmFunction =
        try {
            loweringSub = sub.label
            try {
                FunctionLowering(this, sub, flagSlots = false).lower()
            } catch (_: FlagsFromOtherBlock) {
                FunctionLowering(this, sub, flagSlots = true).lower()
            }
        } catch (x: IllegalArgumentException) {
            throw LlvmLoweringException(x.message ?: "${sub.label} can't be translated to LLVM IR", sub.position, x)
        }

    /**
     * asmsubs and extsubs have assembly bodies for another target, so they become declarations that a runtime has to define.
     * An external routine is a C function: it is declared under its bare name, with the exact widths of its Prog8 types.
     */
    fun declaration(asmsub: IRAsmSubroutine): LlvmFunction {
        require(asmsub.returns.size <= 1) { "asmsub ${asmsub.label} returns several values, which isn't supported yet" }
        val params = asmsub.parameters.mapIndexed { index, param -> LlvmValue.Local("p$index", mapper.map(param.dt.base)) }
        val returnType = asmsub.returns.singleOrNull()?.let { mapper.map(it.dt.base) } ?: LlvmType.Void
        return LlvmFunction(functionName(asmsub), returnType, params, isExternal = true)
    }

    /** The name of the LLVM function of the asmsub or extsub with IR label [label]; external routines are C functions without block scope. */
    fun functionName(label: String): String = asmSubroutines[label]?.let { functionName(it) } ?: label

    private fun functionName(asmsub: IRAsmSubroutine): String = if (asmsub.isExternal) asmsub.label.substringAfterLast('.') else asmsub.label

    private fun declareExternalRoutines() {
        for (sub in program.allAsmSubs().filter { it.isExternal }) {
            val function = declaration(sub)
            val previous = externalRoutines.putIfAbsent(function.name, sub to function) ?: continue
            if (previous.second.type != function.type)
                throw conflict(sub, function, "extsub ${previous.first.label} (at ${previous.first.position.shortText()})", previous.second.type)
        }
    }

    private fun conflict(sub: IRAsmSubroutine, function: LlvmFunction, other: String, otherType: LlvmType): LlvmLoweringException {
        val message = "extsub ${sub.label} declares C function '${function.name}' as ${LlvmPrinter.format(function.type)}, " +
            "which conflicts with $other that declares it as ${LlvmPrinter.format(otherType)}"
        return LlvmLoweringException(message, sub.position, IllegalArgumentException(message))
    }

    private fun Position.shortText() = "$file:$line:$startCol"

    /**
     * The runtime library routines always include code like sys.die that uses C functions such as putchar,
     * so a conflict with an extsub is only an error when the code that uses the runtime's declaration can be reached.
     */
    private fun reportReachableConflict() {
        if (runtimeConflicts.isEmpty())
            return
        val reachable = reachableSubroutines()
        runtimeConflicts.firstOrNull { it.first in reachable }?.let { throw it.second }
    }

    private fun reachableSubroutines(): Set<String> {
        fun references(instructions: List<IRInstruction>): List<String> =
            instructions.flatMap { listOfNotNull(it.labelTarget, (it.immediate as? ImmediateOperand.SymbolAddress)?.symbol) }
        val roots = if (PROGRAM_START in subroutines) listOf(PROGRAM_START, GLOBAL_INITS) else subroutines.keys + GLOBAL_INITS
        val reachable = mutableSetOf<String>()
        val pending = ArrayDeque(roots)
        while (pending.isNotEmpty()) {
            val label = pending.removeLast()
            if (!reachable.add(label))
                continue
            val instructions = mutableListOf<IRInstruction>()
            if (label == GLOBAL_INITS)
                program.globalInits.forEachChunkRecursive { instructions += it.instructions }
            else
                subroutines[label]?.forEachInstruction { instructions += it }
            pending += references(instructions).filter { it in subroutines }
        }
        return reachable
    }

    fun subroutine(name: String): IRSubroutine? = subroutines[name]

    fun asmSubroutine(name: String): IRAsmSubroutine? = asmSubroutines[name]

    fun parameterTypes(sub: IRSubroutine): List<LlvmType> = sub.parameters.map { mapper.map(it.dt.base) }

    /** Several return values are passed back in calling convention slots instead, so such a subroutine returns void. */
    fun returnType(sub: IRSubroutine): LlvmType = sub.returns.singleOrNull()?.let { mapper.map(it.base) } ?: LlvmType.Void

    /** Declares a routine that isn't part of the program, such as a C library function, with the signature of its first call. */
    fun externalFunction(name: String, returnType: LlvmType, paramTypes: List<LlvmType>): LlvmFunction {
        externalRoutines[name]?.let { (sub, declared) ->
            val used = LlvmType.Function(returnType, paramTypes)
            if (declared.type != used) {
                val error = conflict(sub, declared, "the ${program.options.compTarget.name} runtime", used)
                runtimeConflicts += loweringSub to error
                definingHelper?.let { helperConflicts.getOrPut(it) { mutableListOf() } += error }
            }
            return declared
        }
        val function = externalFunctions.getOrPut(name) {
            LlvmFunction(name, returnType, paramTypes.mapIndexed { index, type -> LlvmValue.Local("p$index", type) }, isExternal = true)
        }
        require(function.type == LlvmType.Function(returnType, paramTypes)) { "routine $name is called with different signatures" }
        return function
    }

    /** The global that stands in for calling convention slot [slot]; integer and float registers get separate globals. */
    fun hardwareSlot(slot: CallingConventionSlot, float: Boolean): LlvmValue.Global {
        val name = if (float) ".slot.f${slot.value}" else ".slot.s${slot.value}"
        hardwareSlots.getOrPut(name) {
            val type = if (float) LlvmType.Double else LlvmType.I64
            LlvmGlobal(name, type, LlvmValue.ZeroInitializer(type), isConstant = false, linkage = LlvmLinkage.INTERNAL)
        }
        return LlvmValue.Global(name)
    }

    /** An internal function of the generated code itself, such as the search loop of a syscall; [define] makes it on first use. */
    fun helperFunction(name: String, define: () -> LlvmFunction): LlvmValue.Global {
        if (name !in helperFunctions) {
            val outer = definingHelper
            definingHelper = name
            try {
                helperFunctions[name] = define()
            } finally {
                definingHelper = outer
            }
        }
        helperConflicts[name]?.forEach { runtimeConflicts += loweringSub to it }
        return LlvmValue.Global(name)
    }

    /** A zero-initialized internal global of the generated code itself, such as the value stack of PUSH and POP. */
    fun runtimeGlobal(name: String, type: LlvmType, constant: Boolean = false): LlvmValue.Global {
        runtimeGlobals.getOrPut(name) { LlvmGlobal(name, type, LlvmValue.ZeroInitializer(type), isConstant = constant, linkage = LlvmLinkage.INTERNAL) }
        return LlvmValue.Global(name)
    }

    /** A constant zero-terminated string of the generated code itself, such as an fopen() mode. */
    fun runtimeString(name: String, text: String): LlvmValue.Global {
        runtimeGlobals.getOrPut(name) {
            val bytes = LlvmValue.StringConstant(text + "\u0000")
            LlvmGlobal(name, bytes.type, bytes, isConstant = true, linkage = LlvmLinkage.PRIVATE, unnamedAddr = true)
        }
        return LlvmValue.Global(name)
    }
}


internal enum class Flag { ZERO, CARRY, NEGATIVE, OVERFLOW }

/**
 * Lowers one subroutine. Status flags are tracked per block and only computed where a branch reads them;
 * with [flagSlots] they are also kept in stack slots, for branches that read flags set in another block.
 */
internal class FunctionLowering(val builder: LlvmFunctionBuilder, val sub: IRSubroutine, private val flagSlots: Boolean) {
    val returnType: LlvmType = builder.returnType(sub)
    private val names = LocalNames(chunkLabels(sub.chunks) + sub.label)
    private val registerTypes = registerTypes(sub)
    private val allocas = mutableListOf<LlvmInstruction>()
    private val blocks = mutableListOf<LlvmBlock>()
    private val blockNames = mutableMapOf<IRCodeChunkBase, String>()
    private val labels = mutableMapOf<String, String>()
    private val registerSlots = mutableMapOf<VirtualRegister, LlvmValue.Local>()
    private val flags = mutableMapOf<Flag, FlagValue>()
    private val savedFlags = if (flagSlots) Flag.entries.associateWith { stackSlot("flag.${it.name.lowercase()}", LlvmType.I1) } else emptyMap()
    private lateinit var current: LlvmBlock
    private var endBlock: String? = null
    private val tailCalls = linkedMapOf<String, String>()
    private var notTaken: (() -> String)? = null

    fun lower(): LlvmFunction {
        require(sub.incomingSize == 0 && sub.frameVregSlots.isEmpty()) { "${sub.label} has a stack frame, which the llvm targets don't use" }
        val params = sub.parameters.zip(builder.parameterTypes(sub)) { param, type -> LlvmValue.Local(names.exact(param.name.substringAfterLast('.')), type) }
        val entryName = names.exact("entry")
        nameChunks(sub.chunks)
        val first = sub.chunks.firstOrNull()?.let { blockNames.getValue(it) }
        first?.let { labels.putIfAbsent(sub.label, it) }
        lowerChunks(sub.chunks, after = null)
        tailCalls.forEach { (callee, block) -> lowerTailCall(callee, block) }
        val entryCode = buildList {
            addAll(allocas)
            // PUSHST also reads flags that were never set; cleared slots keep undef out of the status byte
            savedFlags.values.forEach { add(LlvmInstruction.Store(LlvmValue.IntConstant(0, LlvmType.I1), it)) }
            sub.parameters.zip(params).forEach { (param, value) ->
                val variable = requireNotNull(builder.symbols.addresses[param.name]) { "parameter ${param.name} of ${sub.label} has no variable" }
                add(LlvmInstruction.Store(value, variable))
            }
            add(first?.let { LlvmInstruction.Br(it) } ?: endOfFunction())
        }
        endBlock?.let { blocks += LlvmBlock(it, mutableListOf(endOfFunction())) }
        check(blocks.all { it.terminator != null }) { "${sub.label} has a block without terminator" }
        return LlvmFunction(sub.label, returnType, params, (listOf(LlvmBlock(entryName, entryCode.toMutableList())) + blocks).toMutableList(),
            linkage = LlvmLinkage.INTERNAL)
    }

    fun emit(instruction: LlvmInstruction) {
        check(current.terminator == null) { "${sub.label}: block ${current.label} is already terminated" }
        current.instructions += instruction
    }

    fun freshName(base: String): String = names.fresh(base)

    /** Emits the instruction that [make] creates for a new local named after [base], and returns that local. */
    fun derive(base: String, type: LlvmType, make: (LlvmValue.Local) -> LlvmInstruction): LlvmValue.Local =
        LlvmValue.Local(names.fresh(base), type).also { emit(make(it)) }

    fun typeOf(type: IRDataType): LlvmType = builder.mapper.map(type)

    fun read(operand: RegisterOperand): LlvmValue {
        val (slot, slotType) = registerSlot(operand.register)
        val base = operand.register.toString()
        return convert(derive(base, slotType) { LlvmInstruction.Load(it, slot) }, typeOf(operand.type), base)
    }

    /** Like [read], but a LONG register gives its whole slot, which may hold a wider address; for address arithmetic and copies. */
    fun readWide(operand: RegisterOperand): LlvmValue {
        if (operand.type != IRDataType.LONG)
            return read(operand)
        val (slot, slotType) = registerSlot(operand.register)
        return derive(operand.register.toString(), slotType) { LlvmInstruction.Load(it, slot) }
    }

    fun write(operand: RegisterOperand, value: LlvmValue) {
        val (slot, slotType) = registerSlot(operand.register)
        val base = operand.register.toString()
        // sign extended, a 32-bit long still works as an offset in the wider address arithmetic of LONG registers
        val stored = if (operand.type == IRDataType.LONG && value.type == LlvmType.I32) widen(value, slotType, signed = true, base) else convert(value, slotType, base)
        emit(LlvmInstruction.Store(stored, slot))
    }

    /** The address in an integer or pointer register, as a ptr. */
    fun pointerValue(operand: RegisterOperand): LlvmValue = convert(readWide(operand), LlvmType.Ptr, operand.register.toString())

    /** Pointers as pointer-sized integers, for the integer instructions that work on them. */
    fun asInteger(value: LlvmValue): LlvmValue = if (value.type == LlvmType.Ptr) convert(value, builder.mapper.intPtr) else value

    /** Integers are truncated or zero extended, and converted to and from pointers; integer constants fold right away. */
    fun convert(value: LlvmValue, to: LlvmType, base: String = "cast"): LlvmValue {
        val from = value.type
        if (from == to)
            return value
        if (value is LlvmValue.IntConstant && (to.isInteger || to == LlvmType.Ptr))
            return integerConstant(unsigned(value), to)
        val operator = when {
            from.isInteger && to.isInteger -> if (checkNotNull(to.bitWidth) < checkNotNull(from.bitWidth)) CastOperator.TRUNC else CastOperator.ZEXT
            from == LlvmType.Ptr && to.isInteger -> CastOperator.PTRTOINT
            from.isInteger && to == LlvmType.Ptr -> CastOperator.INTTOPTR
            else -> throw IllegalArgumentException("${sub.label}: can't convert a $from value to $to")
        }
        return derive(base, to) { LlvmInstruction.Cast(operator, it, value, to) }
    }

    /** Extends an integer [value] to the wider type [to], with its sign bit when [signed], else like [convert]. */
    fun widen(value: LlvmValue, to: LlvmType, signed: Boolean, base: String = "ext"): LlvmValue {
        if (!signed || value.type == to)
            return convert(value, to, base)
        val bits = checkNotNull(value.type.bitWidth) { "${sub.label}: can't sign extend a ${value.type} value" }
        require(bits < checkNotNull(to.bitWidth) { "$to is no integer type" }) { "${sub.label}: can't sign extend a ${value.type} value to $to" }
        if (value is LlvmValue.IntConstant)
            return integerConstant(value.value shl (64 - bits) shr (64 - bits), to)
        return derive(base, to) { LlvmInstruction.Cast(CastOperator.SEXT, it, value, to) }
    }

    /** The global of a routine outside the program, such as an LLVM intrinsic, which gets declared on first use. */
    fun external(name: String, returnType: LlvmType, paramTypes: List<LlvmType>): LlvmValue.Global =
        LlvmValue.Global(builder.externalFunction(name, returnType, paramTypes).name)

    /** Calls the LLVM intrinsic [name] that returns a [type] value. */
    fun intrinsic(name: String, type: LlvmType, vararg args: LlvmValue): LlvmValue =
        derive(name.removePrefix("llvm.").substringBefore('.'), type) {
            LlvmInstruction.Call(it, external(name, type, args.map { arg -> arg.type }), args.toList(), type)
        }

    fun integerConstant(value: Long, type: LlvmType): LlvmValue = when {
        type != LlvmType.Ptr -> LlvmValue.IntConstant(truncate(value, checkNotNull(type.bitWidth) { "$type is no integer type" }), type)
        value == 0L -> LlvmValue.NullPtr()
        else -> LlvmValue.ConstantCast(CastOperator.INTTOPTR, LlvmValue.IntConstant(value, builder.mapper.intPtr), LlvmType.Ptr)
    }

    /** The immediate operand of [ins] as a value of [type]. */
    fun immediate(ins: IRInstruction, type: LlvmType): LlvmValue = when (val immediate = ins.requireImmediate()) {
        is ImmediateOperand.Integer -> integerConstant(immediate.value.toLong(), type)
        is ImmediateOperand.QuadValue -> integerConstant(immediate.value, type)
        is ImmediateOperand.FloatValue -> LlvmValue.FloatConstant(immediate.value)
        is ImmediateOperand.SymbolAddress -> convert(symbolAddress(immediate.symbol, immediate.offset), type)
    }

    fun symbolAddress(name: String, offset: Int = 0): LlvmValue {
        val base = builder.symbols.addresses[name]
            ?: LlvmValue.Global(builder.functionName(name)).takeIf { builder.subroutine(name) != null || builder.asmSubroutine(name) != null }
            ?: throw IllegalArgumentException("${sub.label} takes the address of unknown symbol $name")
        return if (offset == 0) base else LlvmValue.ConstantGep(LlvmType.I8, base, listOf(LlvmValue.IntConstant(offset.toLong(), builder.mapper.intPtr)))
    }

    /** The block of a label in this subroutine; a branch to another subroutine goes to a block that tail calls it. */
    fun blockOf(target: CodeReference): String {
        require(target is CodeReference.Label && target.offset == 0) { "${sub.label} branches to $target, which can only be a plain label" }
        labels[target.name]?.let { return it }
        require(builder.subroutine(target.name) != null || builder.asmSubroutine(target.name) != null) {
            "${sub.label} branches to ${target.name}, which is outside of the subroutine"
        }
        return tailCalls.getOrPut(target.name) { names.fresh("goto") }
    }

    /** Where execution continues when the conditional branch being lowered isn't taken. */
    fun notTakenTarget(): String = checkNotNull(notTaken) { "not lowering a conditional branch" }()

    fun endOfFunction(): LlvmInstruction = if (returnType == LlvmType.Void) LlvmInstruction.Ret(null) else LlvmInstruction.Unreachable

    fun setComparison(left: LlvmValue, right: LlvmValue) {
        val comparison = FlagValue.Compared(Comparison(left, right))
        Flag.entries.forEach { flags[it] = comparison }
        if (flagSlots)
            Flag.entries.forEach { saveFlag(it) }
    }

    fun setFlag(flag: Flag, value: LlvmValue) {
        flags[flag] = FlagValue.Bit(value)
        if (flagSlots)
            saveFlag(flag)
    }

    fun forgetFlags() = flags.clear()

    /** Branches to [target] when [flag] is set, or when it is clear if not [whenSet]. */
    fun branchOnFlag(flag: Flag, whenSet: Boolean, target: String) {
        val notTaken = notTakenTarget()
        when (val value = flagValue(flag)) {
            is FlagValue.Compared -> emit(LlvmInstruction.CondBr(compare(value.comparison, flag, whenSet), target, notTaken))
            is FlagValue.Bit -> emit(if (whenSet) LlvmInstruction.CondBr(value.value, target, notTaken) else LlvmInstruction.CondBr(value.value, notTaken, target))
        }
    }

    /** The state of [flag] as an i1 value: set or clear. */
    fun flag(flag: Flag): LlvmValue = when (val value = flagValue(flag)) {
        is FlagValue.Compared -> compare(value.comparison, flag, whenSet = true)
        is FlagValue.Bit -> value.value
    }

    private fun flagValue(flag: Flag): FlagValue {
        flags[flag]?.let { return it }
        val saved = savedFlags[flag] ?: throw FlagsFromOtherBlock()
        return FlagValue.Bit(derive("flag", LlvmType.I1) { LlvmInstruction.Load(it, saved) }).also { flags[flag] = it }
    }

    private fun saveFlag(flag: Flag) = emit(LlvmInstruction.Store(flag(flag), savedFlags.getValue(flag)))

    /** The condition that [flag] has the state [whenSet], with the semantics of the VM's CMP: carry means unsigned >=. */
    private fun compare(comparison: Comparison, flag: Flag, whenSet: Boolean): LlvmValue {
        val zero = LlvmValue.IntConstant(0, comparison.left.type)
        val (condition, left, right) = when (flag) {
            Flag.ZERO -> Triple(if (whenSet) IcmpCondition.EQ else IcmpCondition.NE, comparison.left, comparison.right)
            Flag.CARRY -> Triple(if (whenSet) IcmpCondition.UGE else IcmpCondition.ULT, comparison.left, comparison.right)
            Flag.NEGATIVE -> Triple(if (whenSet) IcmpCondition.SLT else IcmpCondition.SGE, difference(comparison), zero)
            Flag.OVERFLOW -> Triple(if (whenSet) IcmpCondition.SLT else IcmpCondition.SGE, overflowSign(comparison), zero)
        }
        return derive("cond", LlvmType.I1) { LlvmInstruction.Icmp(condition, it, left, right) }
    }

    private fun difference(comparison: Comparison): LlvmValue = comparison.difference
        ?: derive("diff", comparison.left.type) { LlvmInstruction.BinaryOp(BinaryOperator.SUB, it, comparison.left, comparison.right) }
            .also { comparison.difference = it }

    // signed overflow of left-right: the operands have different signs, and the result's sign differs from left's
    private fun overflowSign(comparison: Comparison): LlvmValue = comparison.overflowSign ?: run {
        val type = comparison.left.type
        val difference = difference(comparison)
        val operandSigns = derive("ovf", type) { LlvmInstruction.BinaryOp(BinaryOperator.XOR, it, comparison.left, comparison.right) }
        val resultSign = derive("ovf", type) { LlvmInstruction.BinaryOp(BinaryOperator.XOR, it, comparison.left, difference) }
        derive("ovf", type) { LlvmInstruction.BinaryOp(BinaryOperator.AND, it, operandSigns, resultSign) }
    }.also { comparison.overflowSign = it }

    private fun nameChunks(chunks: List<IRCodeChunkBase>) {
        for (chunk in chunks) {
            val name = chunk.label ?: names.fresh("chunk")
            blockNames[chunk] = name
            chunk.label?.let { labels[it] = name }
            if (chunk is IRLoopChunk)
                nameChunks(chunk.body)
        }
    }

    private fun lowerChunks(chunks: List<IRCodeChunkBase>, after: String?) {
        chunks.forEachIndexed { index, chunk ->
            val fallthrough = chunks.getOrNull(index + 1)?.let { blockNames.getValue(it) } ?: after
            // only an unlabeled chunk after the first one is entered from the previous block alone, and inherits its flags
            val inheritsFlags = index > 0 && chunk.label == null
            when (chunk) {
                is IRCodeChunk -> lowerCode(chunk, fallthrough, inheritsFlags)
                is IRLoopChunk -> lowerLoop(chunk, fallthrough)
                is IRInlineAsmChunk -> throw IllegalArgumentException("${sub.label} has inline assembly, which can't be translated to LLVM IR")
                is IRInlineBinaryChunk -> throw IllegalArgumentException("${sub.label} has inline binary data, which can't be translated to LLVM IR")
            }
        }
    }

    private fun lowerCode(chunk: IRCodeChunk, fallthrough: String?, inheritsFlags: Boolean) {
        startBlock(blockNames.getValue(chunk), inheritsFlags)
        chunk.instructions.forEachIndexed { index, ins ->
            if (current.terminator != null)
                startBlock(names.fresh("dead"), inheritsFlags = false)
            var continuation: String? = null
            val last = index == chunk.instructions.lastIndex
            notTaken = { if (last) fallthrough ?: functionEnd() else continuation ?: names.fresh("cont").also { continuation = it } }
            lowerInstruction(ins)
            notTaken = null
            continuation?.let { startBlock(it, inheritsFlags = true) }
        }
        if (current.terminator == null)
            emit(fallthrough?.let { LlvmInstruction.Br(it) } ?: endOfFunction())
    }

    /** The IR loop chunk executes its body [IRLoopChunk.trip] times, counted down in a hidden stack slot. */
    private fun lowerLoop(loop: IRLoopChunk, fallthrough: String?) {
        val counter = stackSlot("${loop.label}.count", LlvmType.I32)
        val latch = names.exact("${loop.label}.latch")
        val body = loop.body.firstOrNull()?.let { blockNames.getValue(it) } ?: latch
        startBlock(blockNames.getValue(loop), inheritsFlags = false)
        emit(LlvmInstruction.Store(LlvmValue.IntConstant(loop.trip.toLong(), LlvmType.I32), counter))
        emit(LlvmInstruction.Br(body))
        lowerChunks(loop.body, after = latch)
        startBlock(latch, inheritsFlags = false)
        val count = derive(counter.name, LlvmType.I32) { LlvmInstruction.Load(it, counter) }
        val remaining = derive(counter.name, LlvmType.I32) { LlvmInstruction.BinaryOp(BinaryOperator.SUB, it, count, LlvmValue.IntConstant(1, LlvmType.I32)) }
        emit(LlvmInstruction.Store(remaining, counter))
        val again = derive("again", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.NE, it, remaining, LlvmValue.IntConstant(0, LlvmType.I32)) }
        emit(LlvmInstruction.CondBr(again, body, fallthrough ?: functionEnd()))
    }

    private fun lowerInstruction(ins: IRInstruction) {
        when (ins.opcode) {
            Opcode.NOP, Opcode.ALIGN -> {}
            Opcode.JUMP, Opcode.JUMPI, Opcode.RETURN, Opcode.RETURNR, Opcode.RETURNI,
            Opcode.BSTCC, Opcode.BSTCS, Opcode.BSTEQ, Opcode.BSTNE, Opcode.BSTNEG, Opcode.BSTPOS, Opcode.BSTVC, Opcode.BSTVS,
            Opcode.BGTR, Opcode.BGT, Opcode.BLT, Opcode.BGTSR, Opcode.BGTS, Opcode.BLTS,
            Opcode.BGER, Opcode.BGE, Opcode.BLE, Opcode.BGESR, Opcode.BGES, Opcode.BLES,
            Opcode.CMP, Opcode.CMPI, Opcode.SGN, Opcode.BITTST, Opcode.SEC, Opcode.CLC, Opcode.CLI, Opcode.SEI,
            Opcode.FCOMP, Opcode.BREAKPOINT -> lowerControl(ins)
            Opcode.CALL, Opcode.CALLI, Opcode.CALLFAR, Opcode.CALLFARVB -> lowerCall(ins)
            Opcode.SYSCALL -> lowerSyscall(ins)
            Opcode.LOAD, Opcode.LOADM, Opcode.LOADX, Opcode.LOADR, Opcode.LOADHR, Opcode.LOADI, Opcode.LOADHFACZERO, Opcode.LOADHFACONE,
            Opcode.STOREM, Opcode.STOREX, Opcode.STOREZM, Opcode.STOREZI, Opcode.STOREIM, Opcode.STOREZX, Opcode.STOREHR, Opcode.STOREI,
            Opcode.STOREHFACZERO, Opcode.STOREHFACONE, Opcode.LOADP_INC, Opcode.STOREP_INC,
            Opcode.PUSH, Opcode.POP, Opcode.PUSHST, Opcode.POPST -> lowerMemory(ins)
            Opcode.INC, Opcode.INCM, Opcode.DEC, Opcode.DECM, Opcode.NEG, Opcode.NEGM,
            Opcode.ADDR, Opcode.ADD, Opcode.ADDM, Opcode.ADDIM, Opcode.SUBR, Opcode.SUB, Opcode.SUBM, Opcode.SUBIM,
            Opcode.MULR, Opcode.MUL, Opcode.MULM, Opcode.MULSR, Opcode.MULS, Opcode.MULSM,
            Opcode.DIVR, Opcode.DIV, Opcode.DIVM, Opcode.DIVSR, Opcode.DIVS, Opcode.DIVSM,
            Opcode.MODR, Opcode.MOD, Opcode.MODSR, Opcode.MODS, Opcode.DIVMODR, Opcode.DIVMOD, Opcode.SDIVMODR, Opcode.SDIVMOD,
            Opcode.SQRT, Opcode.SQUARE,
            Opcode.FFROMUB, Opcode.FFROMSB, Opcode.FFROMUW, Opcode.FFROMSW, Opcode.FFROMSL, Opcode.FFROMSQ, Opcode.FFROMUQ,
            Opcode.FTOUB, Opcode.FTOSB, Opcode.FTOUW, Opcode.FTOSW, Opcode.FTOSL, Opcode.FTOSQ, Opcode.FTOUQ,
            Opcode.FPOW, Opcode.FABS, Opcode.FSIN, Opcode.FCOS, Opcode.FTAN, Opcode.FATAN, Opcode.FLN, Opcode.FLOG,
            Opcode.FROUND, Opcode.FFLOOR, Opcode.FCEIL -> lowerArithmetic(ins)
            Opcode.EXT, Opcode.EXTS, Opcode.EXTL, Opcode.EXTLS, Opcode.EXTQ, Opcode.EXTQS,
            Opcode.ANDR, Opcode.AND, Opcode.ANDM, Opcode.ORR, Opcode.OR, Opcode.ORM, Opcode.XORR, Opcode.XOR, Opcode.XORM, Opcode.INV, Opcode.INVM,
            Opcode.ASRN, Opcode.ASRNM, Opcode.LSRN, Opcode.LSRNM, Opcode.LSLN, Opcode.LSLNM, Opcode.ASRI, Opcode.LSRI, Opcode.LSLI,
            Opcode.ASR, Opcode.ASRM, Opcode.LSR, Opcode.LSRM, Opcode.LSL, Opcode.LSLM,
            Opcode.ROR, Opcode.RORM, Opcode.ROXR, Opcode.ROXRM, Opcode.ROL, Opcode.ROLM, Opcode.ROXL, Opcode.ROXLM,
            Opcode.BITSET, Opcode.BITCLR, Opcode.BITTOG,
            Opcode.LSIGB, Opcode.LSIGW, Opcode.LSIGL, Opcode.MSIGB, Opcode.MSIGW, Opcode.BSIGB, Opcode.MIDB, Opcode.CONCAT -> lowerBitwise(ins)
        }
    }

    private fun startBlock(name: String, inheritsFlags: Boolean) {
        current = LlvmBlock(name).also { blocks += it }
        if (!inheritsFlags)
            flags.clear()
    }

    private fun functionEnd(): String = endBlock ?: names.exact("end").also { endBlock = it }

    /** A jump into another subroutine, which has no parameters (like `return f()` that became `goto f`): calls it and returns what it returns. */
    private fun lowerTailCall(name: String, block: String) {
        startBlock(block, inheritsFlags = false)
        val signature = builder.subroutine(name)?.let { LlvmType.Function(builder.returnType(it), builder.parameterTypes(it)) }
            ?: builder.declaration(checkNotNull(builder.asmSubroutine(name))).type
        require(signature.paramTypes.isEmpty()) { "${sub.label} jumps to $name, which has parameters" }
        val callee = LlvmValue.Global(builder.functionName(name))
        if (signature.returnType == LlvmType.Void || returnType == LlvmType.Void) {
            emit(LlvmInstruction.Call(null, callee, emptyList(), signature.returnType))
            emit(LlvmInstruction.Ret(if (returnType == LlvmType.Void) null else LlvmValue.ZeroInitializer(returnType)))
        } else
            emit(LlvmInstruction.Ret(convert(derive("goto", signature.returnType) { LlvmInstruction.Call(it, callee, emptyList(), signature.returnType) }, returnType)))
    }

    private fun stackSlot(base: String, type: LlvmType): LlvmValue.Local =
        LlvmValue.Local(names.exact(base), LlvmType.Ptr).also { allocas += LlvmInstruction.Alloca(it, type) }

    /**
     * A register used with a single IR type other than LONG gets a slot of that type. LONG registers, which can hold addresses,
     * and registers used with several types get a pointer-sized slot that holds any integer or pointer, or an i64 one for quads.
     */
    private fun registerSlot(register: VirtualRegister): Pair<LlvmValue.Local, LlvmType> {
        val types = registerTypes[register]
        val type = when {
            register is VirtualRegister.FloatReg -> LlvmType.Double
            types == null || IRDataType.QUAD in types -> LlvmType.I64
            else -> types.singleOrNull()?.takeIf { it != IRDataType.LONG }?.let { typeOf(it) } ?: builder.mapper.intPtr
        }
        return registerSlots.getOrPut(register) { stackSlot("$register.slot", type) } to type
    }
}


/** Thrown when a branch reads status flags set in another block; the function is then lowered again, with flag slots. */
internal class FlagsFromOtherBlock : RuntimeException(null, null, false, false)

/** A subroutine that the llvm targets have no translation for; [position] is where it is in the source. */
internal class LlvmLoweringException(message: String, val position: Position, cause: Throwable) : IllegalArgumentException(message, cause)

private sealed interface FlagValue {
    data class Compared(val comparison: Comparison) : FlagValue
    data class Bit(val value: LlvmValue) : FlagValue
}

/** The operands of the last comparison; its flags are only computed once a branch reads them. */
private class Comparison(val left: LlvmValue, val right: LlvmValue) {
    var difference: LlvmValue? = null
    var overflowSign: LlvmValue? = null
}

/** Unique local names within a function; [reserved] names (the IR's own labels) are never handed out. */
private class LocalNames(reserved: Collection<String>) {
    private val used = reserved.toMutableSet()
    private val counters = mutableMapOf<String, Int>()

    /** [name] itself if that's still free, otherwise a numbered variant. */
    fun exact(name: String): String = if (used.add(name)) name else fresh(name)

    fun fresh(base: String): String {
        var number = counters[base] ?: 0
        var name: String
        do {
            name = "$base.${++number}"
        } while (!used.add(name))
        counters[base] = number
        return name
    }
}

private fun chunkLabels(chunks: List<IRCodeChunkBase>): List<String> =
    chunks.flatMap { chunk -> listOfNotNull(chunk.label) + if (chunk is IRLoopChunk) chunkLabels(chunk.body) else emptyList() }

private fun registerTypes(sub: IRSubroutine): Map<VirtualRegister, Set<IRDataType>> {
    val types = mutableMapOf<VirtualRegister, MutableSet<IRDataType>>()
    sub.forEachInstruction { ins -> ins.registerAccesses.forEach { types.getOrPut(it.register) { mutableSetOf() } += it.type } }
    return types
}

private fun unsigned(constant: LlvmValue.IntConstant): Long = truncate(constant.value, checkNotNull(constant.type.bitWidth))

private fun truncate(value: Long, bits: Int): Long = if (bits >= 64) value else value and ((1L shl bits) - 1)
