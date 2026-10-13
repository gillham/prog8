package prog8.codegen.llvm.lower

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

/**
 * Translates the subroutines of an IR program into LLVM functions, with one LLVM block per IR chunk.
 * IR virtual registers live in entry-block stack slots, which LLVM's mem2reg pass turns into SSA values.
 */
internal class LlvmFunctionBuilder(val program: IRProgram, val symbols: LlvmDataSymbols) {
    val mapper = LlvmTypeMapper(program.st)
    private val subroutines = program.allSubs().associateBy { it.label }
    private val asmSubroutines = program.allAsmSubs().associateBy { it.label }
    private val externalFunctions = linkedMapOf<String, LlvmFunction>()
    private val hardwareSlots = sortedMapOf<String, LlvmGlobal>()

    /** Adds a function for every subroutine to [module], plus the declarations and slot globals that those need. */
    fun build(module: LlvmModule) {
        for (block in program.blocks) {
            for (child in block.children) {
                when (child) {
                    is IRSubroutine -> module.functions += function(child)
                    is IRAsmSubroutine -> module.functions += declaration(child)
                    is IRCodeChunkBase -> require(child.isEmpty()) { "block ${block.label} has code outside of a subroutine, which isn't supported" }
                }
            }
        }
        module.functions += externalFunctions.values
        module.globals += hardwareSlots.values
    }

    fun function(sub: IRSubroutine): LlvmFunction =
        try {
            FunctionLowering(this, sub, flagSlots = false).lower()
        } catch (_: FlagsFromOtherBlock) {
            FunctionLowering(this, sub, flagSlots = true).lower()
        }

    /** asmsubs and extsubs have assembly bodies for another target, so they become declarations that a runtime has to define. */
    fun declaration(asmsub: IRAsmSubroutine): LlvmFunction {
        require(asmsub.returns.size <= 1) { "asmsub ${asmsub.label} returns several values, which isn't supported yet" }
        val params = asmsub.parameters.mapIndexed { index, param -> LlvmValue.Local("p$index", mapper.map(param.dt.base)) }
        val returnType = asmsub.returns.singleOrNull()?.let { mapper.map(it.dt.base) } ?: LlvmType.Void
        return LlvmFunction(asmsub.label, returnType, params, isExternal = true)
    }

    fun subroutine(name: String): IRSubroutine? = subroutines[name]

    fun asmSubroutine(name: String): IRAsmSubroutine? = asmSubroutines[name]

    fun parameterTypes(sub: IRSubroutine): List<LlvmType> = sub.parameters.map { mapper.map(it.dt.base) }

    /** Several return values are passed back in calling convention slots instead, so such a subroutine returns void. */
    fun returnType(sub: IRSubroutine): LlvmType = sub.returns.singleOrNull()?.let { mapper.map(it.base) } ?: LlvmType.Void

    /** Declares a routine that isn't part of the program, such as a C library function, with the signature of its first call. */
    fun externalFunction(name: String, returnType: LlvmType, paramTypes: List<LlvmType>): LlvmFunction {
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
    private var notTaken: (() -> String)? = null

    fun lower(): LlvmFunction {
        require(sub.incomingSize == 0 && sub.frameVregSlots.isEmpty()) { "${sub.label} has a stack frame, which the llvm64 target doesn't use" }
        val params = sub.parameters.zip(builder.parameterTypes(sub)) { param, type -> LlvmValue.Local(names.exact(param.name.substringAfterLast('.')), type) }
        val entryName = names.exact("entry")
        nameChunks(sub.chunks)
        val first = sub.chunks.firstOrNull()?.let { blockNames.getValue(it) }
        first?.let { labels.putIfAbsent(sub.label, it) }
        lowerChunks(sub.chunks, after = null)
        val entryCode = buildList {
            addAll(allocas)
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

    fun write(operand: RegisterOperand, value: LlvmValue) {
        val (slot, slotType) = registerSlot(operand.register)
        emit(LlvmInstruction.Store(convert(value, slotType, operand.register.toString()), slot))
    }

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

    fun integerConstant(value: Long, type: LlvmType): LlvmValue = when {
        type != LlvmType.Ptr -> LlvmValue.IntConstant(truncate(value, checkNotNull(type.bitWidth) { "$type is no integer type" }), type)
        value == 0L -> LlvmValue.NullPtr()
        else -> LlvmValue.ConstantCast(CastOperator.INTTOPTR, LlvmValue.IntConstant(value, LlvmType.I64), LlvmType.Ptr)
    }

    /** The immediate operand of [ins] as a value of [type]. */
    fun immediate(ins: IRInstruction, type: LlvmType): LlvmValue = when (val immediate = ins.requireImmediate()) {
        is ImmediateOperand.Integer -> integerConstant(immediate.value.toLong(), type)
        is ImmediateOperand.FloatValue -> LlvmValue.FloatConstant(immediate.value)
        is ImmediateOperand.SymbolAddress -> convert(symbolAddress(immediate.symbol, immediate.offset), type)
    }

    fun symbolAddress(name: String, offset: Int = 0): LlvmValue {
        val base = builder.symbols.addresses[name]
            ?: LlvmValue.Global(name).takeIf { builder.subroutine(name) != null || builder.asmSubroutine(name) != null }
            ?: throw IllegalArgumentException("${sub.label} takes the address of unknown symbol $name")
        return if (offset == 0) base else LlvmValue.ConstantGep(LlvmType.I8, base, listOf(LlvmValue.IntConstant(offset.toLong(), LlvmType.I64)))
    }

    fun blockOf(target: CodeReference): String {
        require(target is CodeReference.Label && target.offset == 0) { "${sub.label} branches to $target, which can only be a plain label" }
        return labels[target.name] ?: throw IllegalArgumentException("${sub.label} branches to ${target.name}, which is outside of the subroutine")
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

    private fun flagValue(flag: Flag): FlagValue {
        flags[flag]?.let { return it }
        val saved = savedFlags[flag] ?: throw FlagsFromOtherBlock()
        return FlagValue.Bit(derive("flag", LlvmType.I1) { LlvmInstruction.Load(it, saved) }).also { flags[flag] = it }
    }

    private fun saveFlag(flag: Flag) {
        val value = when (val current = flags.getValue(flag)) {
            is FlagValue.Compared -> compare(current.comparison, flag, whenSet = true)
            is FlagValue.Bit -> current.value
        }
        emit(LlvmInstruction.Store(value, savedFlags.getValue(flag)))
    }

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
            Opcode.JUMP, Opcode.RETURN, Opcode.RETURNR, Opcode.RETURNI,
            Opcode.BSTCC, Opcode.BSTCS, Opcode.BSTEQ, Opcode.BSTNE, Opcode.BSTNEG, Opcode.BSTPOS, Opcode.BSTVC, Opcode.BSTVS,
            Opcode.BGTR, Opcode.BGT, Opcode.BLT, Opcode.BGTSR, Opcode.BGTS, Opcode.BLTS,
            Opcode.BGER, Opcode.BGE, Opcode.BLE, Opcode.BGESR, Opcode.BGES, Opcode.BLES,
            Opcode.CMP, Opcode.CMPI, Opcode.SGN, Opcode.BITTST, Opcode.SEC, Opcode.CLC, Opcode.FCOMP -> lowerControl(ins)
            Opcode.CALL -> lowerCall(ins)
            Opcode.LOAD, Opcode.LOADR, Opcode.LOADM, Opcode.STOREM, Opcode.STOREIM, Opcode.STOREZM, Opcode.LOADHR, Opcode.STOREHR -> lowerMemory(ins)
            Opcode.INC, Opcode.DEC, Opcode.INCM, Opcode.DECM -> lowerArithmetic(ins)
            else -> throw IllegalArgumentException("${ins.opcode} in ${sub.label} isn't supported by the LLVM backend yet")
        }
    }

    private fun startBlock(name: String, inheritsFlags: Boolean) {
        current = LlvmBlock(name).also { blocks += it }
        if (!inheritsFlags)
            flags.clear()
    }

    private fun functionEnd(): String = endBlock ?: names.exact("end").also { endBlock = it }

    private fun stackSlot(base: String, type: LlvmType): LlvmValue.Local =
        LlvmValue.Local(names.exact(base), LlvmType.Ptr).also { allocas += LlvmInstruction.Alloca(it, type) }

    /** A register used with a single IR type gets a slot of that type, otherwise an i64 slot that holds any integer or pointer. */
    private fun registerSlot(register: VirtualRegister): Pair<LlvmValue.Local, LlvmType> {
        val type = when {
            register is VirtualRegister.FloatReg -> LlvmType.Double
            else -> registerTypes[register]?.singleOrNull()?.let { typeOf(it) } ?: LlvmType.I64
        }
        return registerSlots.getOrPut(register) { stackSlot("$register.slot", type) } to type
    }
}


/** Thrown when a branch reads status flags set in another block; the function is then lowered again, with flag slots. */
internal class FlagsFromOtherBlock : RuntimeException(null, null, false, false)

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
