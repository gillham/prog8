package prog8.compiler.target.c64.codegen

import prog8.ast.IFunctionCall
import prog8.ast.Program
import prog8.ast.base.*
import prog8.ast.expressions.*
import prog8.ast.statements.DirectMemoryWrite
import prog8.ast.statements.FunctionCallStatement
import prog8.compiler.AssemblyError
import prog8.compiler.target.c64.codegen.assignment.AsmAssignSource
import prog8.compiler.target.c64.codegen.assignment.AsmAssignTarget
import prog8.compiler.target.c64.codegen.assignment.AsmAssignment
import prog8.compiler.target.c64.codegen.assignment.SourceStorageKind
import prog8.compiler.target.c64.codegen.assignment.TargetStorageKind
import prog8.compiler.toHex
import prog8.functions.FSignature

internal class BuiltinFunctionsAsmGen(private val program: Program, private val asmgen: AsmGen) {

    internal fun translateFunctioncallExpression(fcall: FunctionCall, func: FSignature) {
        translateFunctioncall(fcall, func, false)
    }

    internal fun translateFunctioncallStatement(fcall: FunctionCallStatement, func: FSignature) {
        translateFunctioncall(fcall, func, true)
    }

    private fun translateFunctioncall(fcall: IFunctionCall, func: FSignature, discardResult: Boolean) {
        val functionName = fcall.target.nameInSource.last()
        if (discardResult) {
            if (func.pure)
                return  // can just ignore the whole function call altogether
            else if (func.returntype != null)
                throw AssemblyError("discarding result of non-pure function $fcall")
        }

        when (functionName) {
            "msb" -> funcMsb(fcall)
            "lsb" -> funcLsb(fcall)
            "mkword" -> funcMkword(fcall, func)
            "abs" -> funcAbs(fcall, func)
            "swap" -> funcSwap(fcall)
            "strlen" -> funcStrlen(fcall)
            "min", "max", "sum" -> funcMinMaxSum(fcall, functionName)
            "any", "all" -> funcAnyAll(fcall, functionName)
            "sgn" -> funcSgn(fcall, func)
            "sin", "cos", "tan", "atan",
            "ln", "log2", "sqrt", "rad",
            "deg", "round", "floor", "ceil",
            "rdnf" -> funcVariousFloatFuncs(fcall, func, functionName)
            "rol" -> funcRol(fcall)
            "rol2" -> funcRol2(fcall)
            "ror" -> funcRor(fcall)
            "ror2" -> funcRor2(fcall)
            "sort" -> funcSort(fcall)
            "reverse" -> funcReverse(fcall)
            "rsave" -> {
                // save cpu status flag and all registers A, X, Y.
                // see http://6502.org/tutorials/register_preservation.html
                asmgen.out(" php |  sta  P8ZP_SCRATCH_REG | pha  | txa  | pha  | tya  | pha  | lda  P8ZP_SCRATCH_REG")
            }
            "rrestore" -> {
                // restore all registers and cpu status flag
                asmgen.out(" pla |  tay |  pla |  tax |  pla |  plp")
            }
            "clear_carry" -> asmgen.out("  clc")
            "set_carry" -> asmgen.out("  sec")
            "clear_irqd" -> asmgen.out("  cli")
            "set_irqd" -> asmgen.out("  sei")
            else -> {
                translateFunctionArguments(fcall.args, func)
                asmgen.out("  jsr  prog8_lib.func_$functionName")
            }
        }
    }

    private fun funcReverse(fcall: IFunctionCall) {
        val variable = fcall.args.single()
        if (variable is IdentifierReference) {
            val decl = variable.targetVarDecl(program.namespace)!!
            val varName = asmgen.asmVariableName(variable)
            val numElements = decl.arraysize!!.constIndex()
            when (decl.datatype) {
                DataType.ARRAY_UB, DataType.ARRAY_B -> {
                    asmgen.out("""
                                    lda  #<$varName
                                    ldy  #>$varName
                                    sta  P8ZP_SCRATCH_W1
                                    sty  P8ZP_SCRATCH_W1+1
                                    lda  #$numElements
                                    jsr  prog8_lib.reverse_b
                                """)
                }
                DataType.ARRAY_UW, DataType.ARRAY_W -> {
                    asmgen.out("""
                                    lda  #<$varName
                                    ldy  #>$varName
                                    sta  P8ZP_SCRATCH_W1
                                    sty  P8ZP_SCRATCH_W1+1
                                    lda  #$numElements
                                    jsr  prog8_lib.reverse_w
                                """)
                }
                DataType.ARRAY_F -> {
                    asmgen.out("""
                                    lda  #<$varName
                                    ldy  #>$varName
                                    sta  P8ZP_SCRATCH_W1
                                    sty  P8ZP_SCRATCH_W1+1
                                    lda  #$numElements
                                    jsr  prog8_lib.reverse_f
                                """)
                }
                else -> throw AssemblyError("weird type")
            }
        }
    }

    private fun funcSort(fcall: IFunctionCall) {
        val variable = fcall.args.single()
        if (variable is IdentifierReference) {
            val decl = variable.targetVarDecl(program.namespace)!!
            val varName = asmgen.asmVariableName(variable)
            val numElements = decl.arraysize!!.constIndex()
            when (decl.datatype) {
                DataType.ARRAY_UB, DataType.ARRAY_B -> {
                    asmgen.out("""
                                    lda  #<$varName
                                    ldy  #>$varName
                                    sta  P8ZP_SCRATCH_W1
                                    sty  P8ZP_SCRATCH_W1+1
                                    lda  #$numElements
                                    sta  P8ZP_SCRATCH_B1
                                """)
                    asmgen.out(if (decl.datatype == DataType.ARRAY_UB) "  jsr  prog8_lib.sort_ub" else "  jsr  prog8_lib.sort_b")
                }
                DataType.ARRAY_UW, DataType.ARRAY_W -> {
                    asmgen.out("""
                                    lda  #<$varName
                                    ldy  #>$varName
                                    sta  P8ZP_SCRATCH_W1
                                    sty  P8ZP_SCRATCH_W1+1
                                    lda  #$numElements
                                    sta  P8ZP_SCRATCH_B1
                                """)
                    asmgen.out(if (decl.datatype == DataType.ARRAY_UW) "  jsr  prog8_lib.sort_uw" else "  jsr  prog8_lib.sort_w")
                }
                DataType.ARRAY_F -> throw AssemblyError("sorting of floating point array is not supported")
                else -> throw AssemblyError("weird type")
            }
        } else
            throw AssemblyError("weird type")
    }

    private fun funcRor2(fcall: IFunctionCall) {
        val what = fcall.args.single()
        val dt = what.inferType(program)
        when (dt.typeOrElse(DataType.STRUCT)) {
            DataType.UBYTE -> {
                when (what) {
                    is ArrayIndexedExpression -> {
                        asmgen.translateExpression(what.identifier)
                        asmgen.translateExpression(what.arrayspec.index)
                        asmgen.out("  jsr  prog8_lib.ror2_array_ub")
                    }
                    is DirectMemoryRead -> {
                        if (what.addressExpression is NumericLiteralValue) {
                            val number = (what.addressExpression as NumericLiteralValue).number
                            asmgen.out("  lda  ${number.toHex()} |  lsr  a |  bcc  + |  ora  #\$80 |+  |  sta  ${number.toHex()}")
                        } else {
                            asmgen.translateExpression(what.addressExpression)
                            asmgen.out("  jsr  prog8_lib.ror2_mem_ub")
                        }
                    }
                    is IdentifierReference -> {
                        val variable = asmgen.asmVariableName(what)
                        asmgen.out("  lda  $variable |  lsr  a |  bcc  + |  ora  #\$80 |+  |  sta  $variable")
                    }
                    else -> throw AssemblyError("weird type")
                }
            }
            DataType.UWORD -> {
                when (what) {
                    is ArrayIndexedExpression -> {
                        asmgen.translateExpression(what.identifier)
                        asmgen.translateExpression(what.arrayspec.index)
                        asmgen.out("  jsr  prog8_lib.ror2_array_uw")
                    }
                    is IdentifierReference -> {
                        val variable = asmgen.asmVariableName(what)
                        asmgen.out("  lsr  $variable+1 |  ror  $variable |  bcc  + |  lda  $variable+1 |  ora  #\$80 |  sta  $variable+1 |+  ")
                    }
                    else -> throw AssemblyError("weird type")
                }
            }
            else -> throw AssemblyError("weird type")
        }
    }

    private fun funcRor(fcall: IFunctionCall) {
        val what = fcall.args.single()
        val dt = what.inferType(program)
        when (dt.typeOrElse(DataType.STRUCT)) {
            DataType.UBYTE -> {
                when (what) {
                    is ArrayIndexedExpression -> {
                        asmgen.translateExpression(what.identifier)
                        asmgen.translateExpression(what.arrayspec.index)
                        asmgen.out("  jsr  prog8_lib.ror_array_ub")
                    }
                    is DirectMemoryRead -> {
                        if (what.addressExpression is NumericLiteralValue) {
                            val number = (what.addressExpression as NumericLiteralValue).number
                            asmgen.out("  ror  ${number.toHex()}")
                        } else {
                            asmgen.translateExpression(what.addressExpression)
                            asmgen.out("""
                        inx
                        lda  P8ESTACK_LO,x
                        sta  (+) + 1
                        lda  P8ESTACK_HI,x
                        sta  (+) + 2
    +                   ror  ${'$'}ffff            ; modified                    
                                        """)
                        }
                    }
                    is IdentifierReference -> {
                        val variable = asmgen.asmVariableName(what)
                        asmgen.out("  ror  $variable")
                    }
                    else -> throw AssemblyError("weird type")
                }
            }
            DataType.UWORD -> {
                when (what) {
                    is ArrayIndexedExpression -> {
                        asmgen.translateExpression(what.identifier)
                        asmgen.translateExpression(what.arrayspec.index)
                        asmgen.out("  jsr  prog8_lib.ror_array_uw")
                    }
                    is IdentifierReference -> {
                        val variable = asmgen.asmVariableName(what)
                        asmgen.out("  ror  $variable+1 |  ror  $variable")
                    }
                    else -> throw AssemblyError("weird type")
                }
            }
            else -> throw AssemblyError("weird type")
        }
    }

    private fun funcRol2(fcall: IFunctionCall) {
        val what = fcall.args.single()
        val dt = what.inferType(program)
        when (dt.typeOrElse(DataType.STRUCT)) {
            DataType.UBYTE -> {
                when (what) {
                    is ArrayIndexedExpression -> {
                        asmgen.translateExpression(what.identifier)
                        asmgen.translateExpression(what.arrayspec.index)
                        asmgen.out("  jsr  prog8_lib.rol2_array_ub")
                    }
                    is DirectMemoryRead -> {
                        if (what.addressExpression is NumericLiteralValue) {
                            val number = (what.addressExpression as NumericLiteralValue).number
                            asmgen.out("  lda  ${number.toHex()} |  cmp  #\$80 |  rol  a |  sta  ${number.toHex()}")
                        } else {
                            asmgen.translateExpression(what.addressExpression)
                            asmgen.out("  jsr  prog8_lib.rol2_mem_ub")
                        }
                    }
                    is IdentifierReference -> {
                        val variable = asmgen.asmVariableName(what)
                        asmgen.out("  lda  $variable |  cmp  #\$80 |  rol  a |  sta  $variable")
                    }
                    else -> throw AssemblyError("weird type")
                }
            }
            DataType.UWORD -> {
                when (what) {
                    is ArrayIndexedExpression -> {
                        asmgen.translateExpression(what.identifier)
                        asmgen.translateExpression(what.arrayspec.index)
                        asmgen.out("  jsr  prog8_lib.rol2_array_uw")
                    }
                    is IdentifierReference -> {
                        val variable = asmgen.asmVariableName(what)
                        asmgen.out("  asl  $variable |  rol  $variable+1 |  bcc  + |  inc  $variable |+  ")
                    }
                    else -> throw AssemblyError("weird type")
                }
            }
            else -> throw AssemblyError("weird type")
        }
    }

    private fun funcRol(fcall: IFunctionCall) {
        val what = fcall.args.single()
        val dt = what.inferType(program)
        when (dt.typeOrElse(DataType.STRUCT)) {
            DataType.UBYTE -> {
                when (what) {
                    is ArrayIndexedExpression -> {
                        asmgen.translateExpression(what.identifier)
                        asmgen.translateExpression(what.arrayspec.index)
                        asmgen.out("  jsr  prog8_lib.rol_array_ub")
                    }
                    is DirectMemoryRead -> {
                        if (what.addressExpression is NumericLiteralValue) {
                            val number = (what.addressExpression as NumericLiteralValue).number
                            asmgen.out("  rol  ${number.toHex()}")
                        } else {
                            asmgen.translateExpression(what.addressExpression)
                            asmgen.out("""
                        inx
                        lda  P8ESTACK_LO,x
                        sta  (+) + 1
                        lda  P8ESTACK_HI,x
                        sta  (+) + 2
    +                   rol  ${'$'}ffff            ; modified                    
                                        """)
                        }
                    }
                    is IdentifierReference -> {
                        val variable = asmgen.asmVariableName(what)
                        asmgen.out("  rol  $variable")
                    }
                    else -> throw AssemblyError("weird type")
                }
            }
            DataType.UWORD -> {
                when (what) {
                    is ArrayIndexedExpression -> {
                        asmgen.translateExpression(what.identifier)
                        asmgen.translateExpression(what.arrayspec.index)
                        asmgen.out("  jsr  prog8_lib.rol_array_uw")
                    }
                    is IdentifierReference -> {
                        val variable = asmgen.asmVariableName(what)
                        asmgen.out("  rol  $variable |  rol  $variable+1")
                    }
                    else -> throw AssemblyError("weird type")
                }
            }
            else -> throw AssemblyError("weird type")
        }
    }

    private fun funcVariousFloatFuncs(fcall: IFunctionCall, func: FSignature, functionName: String) {
        translateFunctionArguments(fcall.args, func)
        asmgen.out("  jsr  floats.func_$functionName")
    }

    private fun funcSgn(fcall: IFunctionCall, func: FSignature) {
        translateFunctionArguments(fcall.args, func)
        val dt = fcall.args.single().inferType(program)
        when (dt.typeOrElse(DataType.STRUCT)) {
            DataType.UBYTE -> asmgen.out("  jsr  math.sign_ub")
            DataType.BYTE -> asmgen.out("  jsr  math.sign_b")
            DataType.UWORD -> asmgen.out("  jsr  math.sign_uw")
            DataType.WORD -> asmgen.out("  jsr  math.sign_w")
            DataType.FLOAT -> asmgen.out("  jsr  floats.sign_f")
            else -> throw AssemblyError("weird type $dt")
        }
    }

    private fun funcAnyAll(fcall: IFunctionCall, functionName: String) {
        outputPushAddressAndLenghtOfArray(fcall.args[0])
        val dt = fcall.args.single().inferType(program)
        when (dt.typeOrElse(DataType.STRUCT)) {
            DataType.ARRAY_B, DataType.ARRAY_UB, DataType.STR -> asmgen.out("  jsr  prog8_lib.func_${functionName}_b")
            DataType.ARRAY_UW, DataType.ARRAY_W -> asmgen.out("  jsr  prog8_lib.func_${functionName}_w")
            DataType.ARRAY_F -> asmgen.out("  jsr  floats.func_${functionName}_f")
            else -> throw AssemblyError("weird type $dt")
        }
    }

    private fun funcMinMaxSum(fcall: IFunctionCall, functionName: String) {
        outputPushAddressAndLenghtOfArray(fcall.args[0])
        val dt = fcall.args.single().inferType(program)
        when (dt.typeOrElse(DataType.STRUCT)) {
            DataType.ARRAY_UB, DataType.STR -> asmgen.out("  jsr  prog8_lib.func_${functionName}_ub")
            DataType.ARRAY_B -> asmgen.out("  jsr  prog8_lib.func_${functionName}_b")
            DataType.ARRAY_UW -> asmgen.out("  jsr  prog8_lib.func_${functionName}_uw")
            DataType.ARRAY_W -> asmgen.out("  jsr  prog8_lib.func_${functionName}_w")
            DataType.ARRAY_F -> asmgen.out("  jsr  floats.func_${functionName}_f")
            else -> throw AssemblyError("weird type $dt")
        }
    }

    private fun funcStrlen(fcall: IFunctionCall) {
        val name = asmgen.asmVariableName(fcall.args[0] as IdentifierReference)
        val type = fcall.args[0].inferType(program)
        when {
            type.istype(DataType.STR) -> asmgen.out("""
                lda  #<$name
                ldy  #>$name
                jsr  prog8_lib.strlen
                sta  P8ESTACK_LO,x
                dex""")
            type.istype(DataType.UWORD) -> asmgen.out("""
                lda  $name
                ldy  $name+1
                jsr  prog8_lib.strlen
                sta  P8ESTACK_LO,x
                dex""")
            else -> throw AssemblyError("strlen requires str or uword arg")
        }
    }

    private fun funcSwap(fcall: IFunctionCall) {
        val first = fcall.args[0]
        val second = fcall.args[1]

        // optimized simple case: swap two variables
        if(first is IdentifierReference && second is IdentifierReference) {
            val firstName = asmgen.asmVariableName(first)
            val secondName = asmgen.asmVariableName(second)
            val dt = first.inferType(program)
            if(dt.istype(DataType.BYTE) || dt.istype(DataType.UBYTE)) {
                asmgen.out(" ldy  $firstName |  lda  $secondName |  sta  $firstName |  sty  $secondName")
                return
            }
            if(dt.istype(DataType.WORD) || dt.istype(DataType.UWORD)) {
                asmgen.out("""
                    ldy  $firstName
                    lda  $secondName
                    sta  $firstName
                    sty  $secondName
                    ldy  $firstName+1
                    lda  $secondName+1
                    sta  $firstName+1
                    sty  $secondName+1
                """)
                return
            }
            if(dt.istype(DataType.FLOAT)) {
                asmgen.out("""
                    lda  #<$firstName
                    sta  P8ZP_SCRATCH_W1
                    lda  #>$firstName
                    sta  P8ZP_SCRATCH_W1+1
                    lda  #<$secondName
                    sta  P8ZP_SCRATCH_W2
                    lda  #>$secondName
                    sta  P8ZP_SCRATCH_W2+1
                    jsr  floats.swap_floats
                """)
                return
            }
        }

        // optimized simple case: swap two memory locations
        if(first is DirectMemoryRead && second is DirectMemoryRead) {
            val addr1 = (first.addressExpression as? NumericLiteralValue)?.number?.toHex()
            val addr2 = (second.addressExpression as? NumericLiteralValue)?.number?.toHex()
            val name1 = if(first.addressExpression is IdentifierReference) asmgen.asmVariableName(first.addressExpression as IdentifierReference) else null
            val name2 = if(second.addressExpression is IdentifierReference) asmgen.asmVariableName(second.addressExpression as IdentifierReference) else null

            when {
                addr1!=null && addr2!=null -> {
                    asmgen.out("  ldy  $addr1 |  lda  $addr2 |  sta  $addr1 |  sty  $addr2")
                    return
                }
                addr1!=null && name2!=null -> {
                    asmgen.out("  ldy  $addr1 |  lda  $name2 |  sta  $addr1 |  sty  $name2")
                    return
                }
                name1!=null && addr2 != null -> {
                    asmgen.out("  ldy  $name1 |  lda  $addr2 |  sta  $name1 |  sty  $addr2")
                    return
                }
                name1!=null && name2!=null -> {
                    asmgen.out("  ldy  $name1 |  lda  $name2 |  sta  $name1 |  sty  $name2")
                    return
                }
            }
        }

        if(first is ArrayIndexedExpression && second is ArrayIndexedExpression) {
            val indexValue1 = first.arrayspec.index as? NumericLiteralValue
            val indexName1 = first.arrayspec.index as? IdentifierReference
            val indexValue2 = second.arrayspec.index as? NumericLiteralValue
            val indexName2 = second.arrayspec.index as? IdentifierReference
            val arrayVarName1 = asmgen.asmVariableName(first.identifier)
            val arrayVarName2 = asmgen.asmVariableName(second.identifier)
            val elementDt = first.inferType(program).typeOrElse(DataType.STRUCT)

            if(indexValue1!=null && indexValue2!=null) {
                swapArrayValues(elementDt, arrayVarName1, indexValue1, arrayVarName2, indexValue2)
                return
            } else if(indexName1!=null && indexName2!=null) {
                swapArrayValues(elementDt, arrayVarName1, indexName1, arrayVarName2, indexName2)
                return
            }
        }

        // all other types of swap() calls are done via the evaluation stack
        fun targetFromExpr(expr: Expression, datatype: DataType): AsmAssignTarget {
            return when (expr) {
                is IdentifierReference -> AsmAssignTarget(TargetStorageKind.VARIABLE, program, asmgen, datatype, expr.definingSubroutine(), variable=expr)
                is ArrayIndexedExpression -> AsmAssignTarget(TargetStorageKind.ARRAY, program, asmgen, datatype, expr.definingSubroutine(), array = expr)
                is DirectMemoryRead -> AsmAssignTarget(TargetStorageKind.MEMORY, program, asmgen, datatype, expr.definingSubroutine(), memory = DirectMemoryWrite(expr.addressExpression, expr.position))
                else -> throw AssemblyError("invalid expression object $expr")
            }
        }

        asmgen.translateExpression(first)
        asmgen.translateExpression(second)
        val datatype = first.inferType(program).typeOrElse(DataType.STRUCT)
        val assignFirst = AsmAssignment(
                AsmAssignSource(SourceStorageKind.STACK, program, datatype),
                targetFromExpr(first, datatype),
                false, first.position
        )
        val assignSecond = AsmAssignment(
                AsmAssignSource(SourceStorageKind.STACK, program, datatype),
                targetFromExpr(second, datatype),
                false, second.position
        )
        asmgen.translateNormalAssignment(assignFirst)
        asmgen.translateNormalAssignment(assignSecond)
    }

    private fun swapArrayValues(elementDt: DataType, arrayVarName1: String, indexValue1: NumericLiteralValue, arrayVarName2: String, indexValue2: NumericLiteralValue) {
        val index1 = indexValue1.number.toInt() * elementDt.memorySize()
        val index2 = indexValue2.number.toInt() * elementDt.memorySize()
        when(elementDt) {
            DataType.UBYTE, DataType.BYTE -> {
                asmgen.out("""
                    lda  $arrayVarName1+$index1
                    ldy  $arrayVarName2+$index2
                    sta  $arrayVarName2+$index2
                    sty  $arrayVarName1+$index1
                """)
            }
            DataType.UWORD, DataType.WORD -> {
                asmgen.out("""
                    lda  $arrayVarName1+$index1
                    ldy  $arrayVarName2+$index2
                    sta  $arrayVarName2+$index2
                    sty  $arrayVarName1+$index1
                    lda  $arrayVarName1+$index1+1
                    ldy  $arrayVarName2+$index2+1
                    sta  $arrayVarName2+$index2+1
                    sty  $arrayVarName1+$index1+1
                """)
            }
            DataType.FLOAT -> {
                asmgen.out("""
                    lda  #<(${arrayVarName1}+$index1)
                    sta  P8ZP_SCRATCH_W1
                    lda  #>(${arrayVarName1}+$index1)
                    sta  P8ZP_SCRATCH_W1+1
                    lda  #<(${arrayVarName2}+$index2)
                    sta  P8ZP_SCRATCH_W2
                    lda  #>(${arrayVarName2}+$index2)
                    sta  P8ZP_SCRATCH_W2+1
                    jsr  floats.swap_floats
                """)
            }
            else -> throw AssemblyError("invalid aray elt type")
        }
    }

    private fun swapArrayValues(elementDt: DataType, arrayVarName1: String, indexName1: IdentifierReference, arrayVarName2: String, indexName2: IdentifierReference) {
        val idxAsmName1 = asmgen.asmVariableName(indexName1)
        val idxAsmName2 = asmgen.asmVariableName(indexName2)
        when(elementDt) {
            DataType.UBYTE, DataType.BYTE -> {
                asmgen.out("""
                    stx  P8ZP_SCRATCH_REG
                    ldx  $idxAsmName1
                    ldy  $idxAsmName2
                    lda  $arrayVarName1,x
                    pha
                    lda  $arrayVarName2,y
                    sta  $arrayVarName1,x
                    pla
                    sta  $arrayVarName2,y
                    ldx  P8ZP_SCRATCH_REG
                """)
            }
            DataType.UWORD, DataType.WORD -> {
                asmgen.out("""
                    stx  P8ZP_SCRATCH_REG
                    lda  $idxAsmName1
                    asl  a
                    tax
                    lda  $idxAsmName2
                    asl  a
                    tay
                    lda  $arrayVarName1,x
                    pha
                    lda  $arrayVarName2,y
                    sta  $arrayVarName1,x
                    pla
                    sta  $arrayVarName2,y
                    lda  $arrayVarName1+1,x
                    pha
                    lda  $arrayVarName2+1,y
                    sta  $arrayVarName1+1,x
                    pla
                    sta  $arrayVarName2+1,y                    
                    ldx  P8ZP_SCRATCH_REG
                """)
            }
            DataType.FLOAT -> {
                asmgen.out("""
                    lda  #>$arrayVarName1
                    sta  P8ZP_SCRATCH_W1+1
                    lda  $idxAsmName1
                    asl  a
                    asl  a
                    clc
                    adc  $idxAsmName1
                    adc  #<$arrayVarName1
                    sta  P8ZP_SCRATCH_W1
                    bcc  +
                    inc  P8ZP_SCRATCH_W1+1
+                   lda  #>$arrayVarName2
                    sta  P8ZP_SCRATCH_W2+1
                    lda  $idxAsmName2
                    asl  a
                    asl  a
                    clc
                    adc  $idxAsmName2
                    adc  #<$arrayVarName2
                    sta  P8ZP_SCRATCH_W2
                    bcc  +
                    inc  P8ZP_SCRATCH_W2+1
+                   jsr  floats.swap_floats                                   
                """)
            }
            else -> throw AssemblyError("invalid aray elt type")
        }
    }

    private fun funcAbs(fcall: IFunctionCall, func: FSignature) {
        translateFunctionArguments(fcall.args, func)
        val dt = fcall.args.single().inferType(program)
        when (dt.typeOrElse(DataType.STRUCT)) {
            in ByteDatatypes -> asmgen.out("  jsr  prog8_lib.abs_b")
            in WordDatatypes -> asmgen.out("  jsr  prog8_lib.abs_w")
            DataType.FLOAT -> asmgen.out("  jsr  floats.abs_f")
            else -> throw AssemblyError("weird type")
        }
    }

    private fun funcMkword(fcall: IFunctionCall, func: FSignature) {
        // trick: push the args in reverse order (msb first, lsb second) this saves some instructions
        asmgen.translateExpression(fcall.args[1])
        asmgen.translateExpression(fcall.args[0])
        asmgen.out("  inx | lda  P8ESTACK_LO,x  | sta  P8ESTACK_HI+1,x")
    }

    private fun funcMsb(fcall: IFunctionCall) {
        val arg = fcall.args.single()
        if (arg.inferType(program).typeOrElse(DataType.STRUCT) !in WordDatatypes)
            throw AssemblyError("msb required word argument")
        if (arg is NumericLiteralValue)
            throw AssemblyError("msb(const) should have been const-folded away")
        if (arg is IdentifierReference) {
            val sourceName = asmgen.asmVariableName(arg)
            asmgen.out("  lda  $sourceName+1 |  sta  P8ESTACK_LO,x |  dex")
        } else {
            asmgen.translateExpression(arg)
            asmgen.out("  lda  P8ESTACK_HI+1,x |  sta  P8ESTACK_LO+1,x")
        }
    }

    private fun funcLsb(fcall: IFunctionCall) {
        val arg = fcall.args.single()
        if (arg.inferType(program).typeOrElse(DataType.STRUCT) !in WordDatatypes)
            throw AssemblyError("lsb required word argument")
        if (arg is NumericLiteralValue)
            throw AssemblyError("lsb(const) should have been const-folded away")
        if (arg is IdentifierReference) {
            val sourceName = asmgen.asmVariableName(arg)
            asmgen.out("  lda  $sourceName |  sta  P8ESTACK_LO,x |  dex")
        } else {
            asmgen.translateExpression(arg)
            // just ignore any high-byte
        }
    }

    private fun outputPushAddressAndLenghtOfArray(arg: Expression) {
        arg as IdentifierReference
        val identifierName = asmgen.asmVariableName(arg)
        val size = arg.targetVarDecl(program.namespace)!!.arraysize!!.constIndex()!!
        asmgen.out("""
                    lda  #<$identifierName
                    sta  P8ESTACK_LO,x
                    lda  #>$identifierName
                    sta  P8ESTACK_HI,x
                    dex
                    lda  #$size
                    sta  P8ESTACK_LO,x
                    dex
                    """)
    }

    private fun translateFunctionArguments(args: MutableList<Expression>, signature: FSignature) {
        args.forEach {
            asmgen.translateExpression(it)
        }
    }

}
