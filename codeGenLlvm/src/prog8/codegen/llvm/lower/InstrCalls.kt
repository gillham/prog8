package prog8.codegen.llvm.lower

import prog8.code.core.Statusflag
import prog8.codegen.llvm.model.IcmpCondition
import prog8.codegen.llvm.model.LlvmInstruction
import prog8.codegen.llvm.model.LlvmType
import prog8.codegen.llvm.model.LlvmValue
import prog8.intermediate.CallArgument
import prog8.intermediate.CallLocation
import prog8.intermediate.CallSite
import prog8.intermediate.CallTarget
import prog8.intermediate.CodeReference
import prog8.intermediate.IRInstruction
import prog8.intermediate.IRSubroutine
import prog8.intermediate.requireCallSite

/**
 * Lowers a direct call to a subroutine, to an asmsub or extsub (calls to a fixed address use its routine name),
 * or to a routine outside the program, which gets declared with the signature of the call.
 */
internal fun FunctionLowering.lowerCall(ins: IRInstruction) {
    val site = ins.requireCallSite()
    val target = site.target
    require(target is CallTarget.Direct) { "${sub.label} has a ${target::class.simpleName} call, which isn't supported yet" }
    val name = when (val reference = target.reference) {
        is CodeReference.Label -> reference.name.also { require(reference.offset == 0) { "${sub.label} calls into the middle of ${reference.name}" } }
        is CodeReference.Absolute -> requireNotNull(target.externalName) { "${sub.label} calls address ${reference.address} without a routine name" }
        is CodeReference.Indirect -> throw IllegalArgumentException("${sub.label} has an indirect call, which isn't supported yet")
    }
    require(site.results.size <= 1) { "${sub.label} calls $name with several results, which isn't supported yet" }
    val callee = builder.subroutine(name)
    val values = (if (callee != null) argumentsInParameterOrder(callee, site) else site.arguments).map { read(it.source) }
    val signature = when {
        callee != null -> LlvmType.Function(builder.returnType(callee), builder.parameterTypes(callee))
        else -> builder.asmSubroutine(name)?.let { builder.declaration(it).type }
            ?: builder.externalFunction(name, site.results.singleOrNull()?.type?.let { typeOf(it) } ?: LlvmType.Void, values.map { it.type }).type
    }
    require(values.size == signature.paramTypes.size) { "${sub.label} calls $name with ${values.size} arguments instead of ${signature.paramTypes.size}" }
    val arguments = values.zip(signature.paramTypes) { value, type -> convert(value, type) }
    forgetFlags()
    val result = site.results.singleOrNull()
    if (result == null || signature.returnType == LlvmType.Void) {
        require(result == null) { "${sub.label} expects a result from $name, which doesn't return one" }
        emit(LlvmInstruction.Call(null, LlvmValue.Global(name), arguments, signature.returnType))
        return
    }
    val value = derive(result.destination?.register?.toString() ?: "call", signature.returnType) {
        LlvmInstruction.Call(it, LlvmValue.Global(name), arguments, signature.returnType)
    }
    result.destination?.let { write(it, value) }
    (result.location as? CallLocation.StatusFlag)?.let { location ->
        val number = if (value.type == LlvmType.Ptr) convert(value, LlvmType.I64) else value
        setFlag(flagOf(location.flag), derive("flag", LlvmType.I1) { LlvmInstruction.Icmp(IcmpCondition.NE, it, number, LlvmValue.IntConstant(0, number.type)) })
    }
}

/** Arguments name the parameter variable they are passed in; the LLVM function takes them in the subroutine's parameter order. */
private fun FunctionLowering.argumentsInParameterOrder(callee: IRSubroutine, site: CallSite): List<CallArgument> {
    require(site.arguments.size == callee.parameters.size) {
        "${sub.label} calls ${callee.label} with ${site.arguments.size} arguments instead of ${callee.parameters.size}"
    }
    return callee.parameters.mapIndexed { index, param ->
        val shortName = param.name.substringAfterLast('.')
        site.arguments.firstOrNull { (it.location as? CallLocation.ParameterMemory)?.name in setOf(shortName, param.name) } ?: site.arguments[index]
    }
}

private fun flagOf(flag: Statusflag): Flag = when (flag) {
    Statusflag.Pc -> Flag.CARRY
    Statusflag.Pz -> Flag.ZERO
    Statusflag.Pv -> Flag.OVERFLOW
    Statusflag.Pn -> Flag.NEGATIVE
}
