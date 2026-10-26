package prog8.codegen.llvm.lower

import prog8.intermediate.CallTarget
import prog8.intermediate.CodeReference
import prog8.intermediate.IRInstruction
import prog8.intermediate.IRProgram
import prog8.intermediate.IRStStaticVariable
import prog8.intermediate.IRStSymbolicReference
import prog8.intermediate.IRSubroutine
import prog8.intermediate.IRVariableInitializer
import prog8.intermediate.ImmediateOperand
import prog8.intermediate.MemoryReference
import prog8.intermediate.symbolName

/**
 * The subroutine variables that live in a stack slot of their subroutine instead of a global: scalars that are reset on
 * every entry anyway (not @dirty or @shared), and that only their own subroutine reads and writes, never by address.
 * mem2reg then keeps them in registers, also across calls, where LLVM has to assume a global may change.
 */
internal class StackLocals(private val program: IRProgram) {
    private val subroutines = program.allSubs().associateBy { it.label }

    /** The variables that move to a stack slot, each with the label of its subroutine. */
    val owners: Map<String, String> = promotable()

    /** The variables that a recursive subroutine keeps in static storage, which its recursive calls share, by subroutine. */
    fun sharedByRecursion(): Map<IRSubroutine, List<IRStStaticVariable>> {
        val recursive = recursiveSubroutines()
        if (recursive.isEmpty())
            return emptyMap()
        return program.st.allVariables().filter { it.name !in owners }
            .mapNotNull { variable -> ownerOf(variable.name)?.takeIf { it in recursive }?.let { subroutines.getValue(it) to variable } }
            .groupBy({ it.first }, { it.second })
    }

    private fun promotable(): Map<String, String> {
        val candidates = program.st.allVariables()
            .filter { (it.dt.isNumericOrBool || it.dt.isPointer) && !it.dirty && !it.shared && !it.readonly && it.align == 0u }
            .mapNotNull { variable -> ownerOf(variable.name)?.let { variable.name to it } }
            .toMap(mutableMapOf())
        fun disqualify(name: String?) { if (name != null) candidates.remove(name) }

        for (sub in subroutines.values) {
            sub.forEachInstruction { ins ->
                val memory = ins.memory
                if (memory != null && (memory !is MemoryReference.Direct || candidates[memory.symbolName] != sub.label))
                    disqualify(memory.symbolName)
                disqualify((ins.immediate as? ImmediateOperand.SymbolAddress)?.symbol)
            }
        }
        program.globalInits.forEachChunkRecursive { chunk ->
            chunk.instructions.forEach { ins ->
                disqualify(ins.memory?.symbolName)
                disqualify((ins.immediate as? ImmediateOperand.SymbolAddress)?.symbol)
            }
        }
        symbolsInInitializers().forEach { disqualify(it) }
        return candidates
    }

    /** The subroutine that declares [name]: the one with the longest label that is a scope of it. */
    private fun ownerOf(name: String): String? {
        var scope = name.substringBeforeLast('.', "")
        while (scope.isNotEmpty()) {
            if (scope in subroutines)
                return scope
            scope = scope.substringBeforeLast('.', "")
        }
        return null
    }

    private fun symbolsInInitializers(): List<String> {
        val references = program.st.allVariables().mapNotNull { it.initializationValue as? IRVariableInitializer.Array }.flatMap { it.elements } +
            program.st.allStructInstances().flatMap { instance -> instance.values.map { it.value } }
        return references.mapNotNull { (it as? IRStSymbolicReference.Symbol)?.name }.toList()
    }

    /** The subroutines on a cycle of the call graph, where an indirect call can reach every subroutine whose address is taken. */
    private fun recursiveSubroutines(): Set<String> {
        val addressTaken = mutableSetOf<String>()
        val callees = subroutines.keys.associateWith { mutableSetOf<String>() }
        val indirectCallers = mutableSetOf<String>()
        for (sub in subroutines.values) {
            sub.forEachInstruction { ins ->
                (ins.immediate as? ImmediateOperand.SymbolAddress)?.symbol?.takeIf { it in subroutines }?.let { addressTaken += it }
                for (reference in codeReferences(ins)) {
                    when (reference) {
                        is CodeReference.Label -> if (reference.name in subroutines) callees.getValue(sub.label) += reference.name
                        else -> indirectCallers += sub.label
                    }
                }
            }
        }
        addressTaken += symbolsInInitializers().filter { it in subroutines }
        indirectCallers.forEach { callees.getValue(it) += addressTaken }
        return subroutines.keys.filter { start ->
            val reached = mutableSetOf<String>()
            val pending = ArrayDeque(callees.getValue(start))
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                if (reached.add(next))
                    pending += callees.getValue(next)
            }
            start in reached
        }.toSet()
    }

    private fun codeReferences(ins: IRInstruction): List<CodeReference> = listOfNotNull(
        ins.target,
        when (val target = ins.callSite?.target) {
            is CallTarget.Direct -> target.reference
            is CallTarget.Banked -> target.reference
            is CallTarget.BankedVariable -> target.reference
            else -> null
        })
}
