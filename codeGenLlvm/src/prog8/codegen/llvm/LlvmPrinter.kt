package prog8.codegen.llvm

import prog8.codegen.llvm.model.LlvmFunction
import prog8.codegen.llvm.model.LlvmGlobal
import prog8.codegen.llvm.model.LlvmInstruction
import prog8.codegen.llvm.model.LlvmLinkage
import prog8.codegen.llvm.model.LlvmModule
import prog8.codegen.llvm.model.LlvmType
import prog8.codegen.llvm.model.LlvmValue

/** Writes textual LLVM IR; SSA form and block terminators are not verified, that is left to `llvm-as`. */
internal object LlvmPrinter {
    private val bareIdentifier = Regex($$"[-a-zA-Z$._][-a-zA-Z$._0-9]*")

    fun print(module: LlvmModule): String = buildString { print(module, this) }

    fun print(module: LlvmModule, out: Appendable) {
        // a line break in the name would end the comment early
        out.appendLine("; ModuleID = '${module.name.lines().joinToString(" ")}'")
        for (comment in module.headerComments) {
            for (line in comment.lines())
                out.appendLine("; $line".trimEnd())
        }
        out.appendLine("source_filename = ${quote(module.name)}")
        module.dataLayout?.let { out.appendLine("target datalayout = ${quote(it)}") }
        module.targetTriple?.let { out.appendLine("target triple = ${quote(it)}") }
        if (module.globals.isNotEmpty()) {
            out.appendLine()
            module.globals.forEach { out.appendLine(format(it)) }
        }
        for (function in module.functions) {
            out.appendLine()
            printFunction(function, out)
        }
    }

    fun format(type: LlvmType): String = when (type) {
        LlvmType.I1 -> "i1"
        LlvmType.I8 -> "i8"
        LlvmType.I16 -> "i16"
        LlvmType.I32 -> "i32"
        LlvmType.I64 -> "i64"
        LlvmType.Double -> "double"
        LlvmType.Ptr -> "ptr"
        LlvmType.Void -> "void"
        is LlvmType.Array -> "[${type.size} x ${format(type.elementType)}]"
        is LlvmType.Function -> "${format(type.returnType)} (${type.paramTypes.joinToString { format(it) }})"
    }

    fun format(value: LlvmValue): String = when (value) {
        is LlvmValue.Local -> localName(value.name)
        is LlvmValue.Global -> globalName(value.name)
        is LlvmValue.IntConstant -> formatInteger(value)
        is LlvmValue.FloatConstant -> formatDouble(value.value)
        is LlvmValue.StringConstant -> "c" + quote(value.bytes)
        is LlvmValue.NullPtr -> "null"
        is LlvmValue.ZeroInitializer -> "zeroinitializer"
        is LlvmValue.ArrayConstant -> value.elements.joinToString(", ", "[", "]") { typed(it) }
    }

    fun typed(value: LlvmValue): String = "${format(value.type)} ${format(value)}"

    fun format(instruction: LlvmInstruction): String = when (val insn = instruction) {
        is LlvmInstruction.Alloca -> "${format(insn.result)} = alloca ${format(insn.allocatedType)}"
        is LlvmInstruction.Load -> "${format(insn.result)} = load ${format(insn.result.type)}, ${typed(insn.ptr)}${alignSuffix(insn.align)}"
        is LlvmInstruction.Store -> "store ${typed(insn.value)}, ${typed(insn.ptr)}${alignSuffix(insn.align)}"
        is LlvmInstruction.BinaryOp -> "${format(insn.result)} = ${insn.op.keyword} ${typed(insn.lhs)}, ${format(insn.rhs)}"
        is LlvmInstruction.FNeg -> "${format(insn.result)} = fneg ${typed(insn.value)}"
        is LlvmInstruction.Icmp -> "${format(insn.result)} = icmp ${insn.cond.keyword} ${typed(insn.lhs)}, ${format(insn.rhs)}"
        is LlvmInstruction.Fcmp -> "${format(insn.result)} = fcmp ${insn.cond.keyword} ${typed(insn.lhs)}, ${format(insn.rhs)}"
        is LlvmInstruction.Cast -> "${format(insn.result)} = ${insn.op.keyword} ${typed(insn.value)} to ${format(insn.targetType)}"
        is LlvmInstruction.GetElementPtr -> {
            val inBounds = if (insn.inBounds) "inbounds " else ""
            val indices = insn.indices.joinToString("") { ", ${typed(it)}" }
            "${format(insn.result)} = getelementptr $inBounds${format(insn.baseType)}, ${typed(insn.ptr)}$indices"
        }
        is LlvmInstruction.Call -> {
            val assignment = insn.result?.let { "${format(it)} = " }.orEmpty()
            "${assignment}call ${format(insn.returnType)} ${format(insn.callee)}(${insn.args.joinToString { typed(it) }})"
        }
        is LlvmInstruction.Select -> "${format(insn.result)} = select ${typed(insn.condition)}, ${typed(insn.trueValue)}, ${typed(insn.falseValue)}"
        is LlvmInstruction.Phi -> {
            val incoming = insn.incoming.joinToString { (value, label) -> "[ ${format(value)}, ${localName(label)} ]" }
            "${format(insn.result)} = phi ${format(insn.result.type)} $incoming"
        }
        is LlvmInstruction.Br -> "br label ${localName(insn.targetBlock)}"
        is LlvmInstruction.CondBr -> "br ${typed(insn.condition)}, label ${localName(insn.trueBlock)}, label ${localName(insn.falseBlock)}"
        is LlvmInstruction.Ret -> insn.value?.let { "ret ${typed(it)}" } ?: "ret void"
        LlvmInstruction.Unreachable -> "unreachable"
    }

    fun format(global: LlvmGlobal): String = buildString {
        append(globalName(global.name)).append(" = ")
        append(if (global.initializer == null) "external " else linkagePrefix(global.linkage))
        if (global.unnamedAddr) append("unnamed_addr ")
        append(if (global.isConstant) "constant " else "global ").append(format(global.type))
        global.initializer?.let { append(' ').append(format(it)) }
        global.align?.let { append(", align ").append(it) }
    }

    private fun printFunction(function: LlvmFunction, out: Appendable) {
        val returnType = format(function.returnType)
        val name = globalName(function.name)
        if (function.isExternal) {
            require(function.blocks.isEmpty()) { "declaration of $name must not have blocks" }
            out.appendLine("declare $returnType $name(${function.parameters.joinToString { format(it.type) }})")
            return
        }
        require(function.blocks.isNotEmpty()) { "definition of $name needs at least one block" }
        out.appendLine("define ${linkagePrefix(function.linkage)}$returnType $name(${function.parameters.joinToString { typed(it) }}) {")
        function.blocks.forEachIndexed { index, block ->
            if (index > 0) out.appendLine()
            out.appendLine("${identifier(block.label)}:")
            block.instructions.forEach { out.append("  ").appendLine(format(it)) }
        }
        out.appendLine("}")
    }

    private fun linkagePrefix(linkage: LlvmLinkage): String = when (linkage) {
        LlvmLinkage.EXTERNAL -> ""
        LlvmLinkage.PRIVATE, LlvmLinkage.INTERNAL -> "${linkage.keyword} "
    }

    private fun alignSuffix(align: Int?): String = align?.let { ", align $it" }.orEmpty()

    private fun formatInteger(constant: LlvmValue.IntConstant): String = when (constant.type) {
        LlvmType.I1 -> if (constant.value != 0L) "true" else "false"
        LlvmType.I8 -> constant.value.toByte().toString()
        LlvmType.I16 -> constant.value.toShort().toString()
        LlvmType.I32 -> constant.value.toInt().toString()
        LlvmType.I64 -> constant.value.toString()
        LlvmType.Double, LlvmType.Ptr, LlvmType.Void, is LlvmType.Array, is LlvmType.Function ->
            error("integer constant with non-integer type ${constant.type}")
    }

    private fun formatDouble(value: Double): String {
        val rawBits = value.toRawBits()
        val decimal = value.toString()
        return if (value.isFinite() && decimal.toDouble().toRawBits() == rawBits) decimal
        else "0x" + rawBits.toULong().toString(16).uppercase().padStart(16, '0')
    }

    private fun localName(name: String): String = "%" + identifier(name)

    private fun globalName(name: String): String = "@" + identifier(name)

    private fun identifier(name: String): String = if (bareIdentifier.matches(name)) name else quote(name)

    private fun quote(text: String): String = quote(text.toByteArray(Charsets.UTF_8).map { it.toUByte() })

    private fun quote(bytes: List<UByte>): String = bytes.joinToString("", "\"", "\"") { escape(it.toInt()) }

    private fun escape(byte: Int): String = when (byte) {
        '"'.code -> "\\22"
        '\\'.code -> "\\\\"
        in 0x20..0x7E -> byte.toChar().toString()
        else -> "\\" + byte.toString(16).uppercase().padStart(2, '0')
    }
}
