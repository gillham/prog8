package prog8.codegen.cpu6502.assignment

import prog8.code.StStruct
import prog8.code.ast.*
import prog8.code.core.*
import prog8.codegen.cpu6502.AsmGen6502Internal
import prog8.codegen.cpu6502.VariableAllocator
import kotlin.math.log2


internal class PtrTarget(target: AsmAssignTarget) {
    val dt = target.datatype
    val pointer = target.pointer!!
    val scope = target.scope
    val position = target.position
}

internal class IndexedPtrTarget(target: AsmAssignTarget) {
    val dt = target.datatype                        // TODO unneeded?
    val pointer = target.array!!.pointerderef!!
    val index = target.array!!.index
    val elementDt = target.array!!.type
    val splitwords = target.array!!.splitWords      // TODO unneeded?
    val scope = target.scope
    val position = target.position
}


internal class PointerAssignmentsGen(private val asmgen: AsmGen6502Internal, private val allocator: VariableAllocator) {
    lateinit var augmentableAsmGen: AugmentableAssignmentAsmGen

    internal fun assignAddressOf(target: PtrTarget, varName: String) {
        asmgen.out("  lda  #<$varName |  ldy  #>$varName")
        assignWordReg(target, RegisterOrPair.AY)
    }

    internal fun assignWordVar(target: PtrTarget, varName: String, sourceDt: DataType) {
        val (zpPtrVar, offset) = deref(target.pointer)
        asmgen.storeIndirectWordVar(varName, sourceDt, zpPtrVar, offset)
    }

    internal fun assignFAC1(target: PtrTarget) {
        TODO("assign FAC1 float to pointer deref ${target.position}")
    }

    internal fun assignFloatAY(target: PtrTarget) {
        TODO("assign float from AY to pointer deref ${target.position}")
    }

    internal fun assignFloatVar(target: PtrTarget, varName: String) {
        val (zpPtrVar, offset) = deref(target.pointer)
        asmgen.storeIndirectFloatVar(varName, zpPtrVar, offset)
    }

    internal fun assignByteVar(target: PtrTarget, varName: String, extendToWord: Boolean, signed: Boolean) {
        val (zpPtrVar, offset) = deref(target.pointer)
        if(extendToWord) {
            asmgen.out("  lda  $varName")
            if(signed)
                asmgen.signExtendAXlsb(BaseDataType.BYTE)
            else
                asmgen.signExtendAXlsb(BaseDataType.UBYTE)
            asmgen.storeIndirectWordReg(RegisterOrPair.AX, zpPtrVar, offset)
        } else {
            asmgen.storeIndirectByteVar(varName, zpPtrVar, offset)
        }
    }

    internal fun assignByteReg(target: PtrTarget, register: CpuRegister, signed: Boolean, extendWord: Boolean) {
        asmgen.saveRegisterStack(register, false)
        val (zpPtrVar, offset) = deref(target.pointer)
        asmgen.restoreRegisterStack(register, false)
        asmgen.storeIndirectByteReg(register, zpPtrVar, offset, signed, extendWord && target.dt.isWord)
    }

    internal fun assignByteToWord(target: PtrTarget, value: PtExpression) {
        asmgen.assignExpressionToRegister(value, RegisterOrPair.AX, target.dt.isSigned)
        assignWordReg(target, RegisterOrPair.AX)
    }

    internal fun assignWordReg(target: PtrTarget, regs: RegisterOrPair) {
        saveOnStack(regs)
        val (zpPtrVar, offset) = deref(target.pointer)
        restoreFromStack(regs)
        asmgen.storeIndirectWordReg(regs, zpPtrVar, offset)
    }

    internal fun assignWord(target: PtrTarget, word: Int) {
        val (zpPtrVar, offset) = deref(target.pointer)
        asmgen.storeIndirectWord(word, zpPtrVar, offset)
    }

    internal fun assignByte(target: PtrTarget, byte: Int) {
        val (zpPtrVar, offset) = deref(target.pointer)
        asmgen.storeIndirectByte(byte, zpPtrVar, offset)
    }

    internal fun assignFloat(target: PtrTarget, float: Double) {
        val (zpPtrVar, offset) = deref(target.pointer)
        asmgen.storeIndirectFloat(float, zpPtrVar, offset)
    }

    internal fun assignByteMemory(target: PtrTarget, address: UInt) {
        TODO("assign memory byte to pointer deref ${target.position}")
    }

    internal fun assignByteMemory(target: PtrTarget, identifier: PtIdentifier) {
        TODO("assign memory byte to pointer deref ${target.position}")
    }

    internal fun inplaceByteInvert(target: PtrTarget) {
        TODO("inplace byte invert pointer deref ${target.position}")
    }

    internal fun inplaceWordInvert(target: PtrTarget) {
        TODO("inplace word invert pointer deref ${target.position}")
    }

    internal fun inplaceByteNegate(target: PtrTarget, ignoreDatatype: Boolean, scope: IPtSubroutine?) {
        TODO("inplace byte negate to pointer deref ${target.position}")
    }

    internal fun inplaceWordNegate(target: PtrTarget, ignoreDatatype: Boolean, scope: IPtSubroutine?) {
        TODO("inplace word negate pointer deref ${target.position}")
    }

    internal fun inplaceFloatNegate(target: PtrTarget, scope: IPtSubroutine?) {
        TODO("inplace float negate pointer deref ${target.position}")
    }


    internal fun deref(pointer: PtPointerDeref, addOffsetToPointer: Boolean=false, forceTemporary: Boolean=false): Pair<String, UByte> {
        // walk the pointer deref chain and leaves the final pointer value in a ZP var
        // this will often be the temp var P8ZP_SCRATCH_PTR but can also be the original pointer variable if it is already in zeropage and there is nothing to add to it
        // returns the ZP var to use as a pointer, and a Y register offset (which can be zero)
        // TODO optimize 'forceTemporary' to only use a temporary when the offset is >0?

        fun addFieldOffsetToScratchPointer(fieldoffset: UInt) {
            if(fieldoffset==0u)
                return
            require(fieldoffset<=0xffu)
            asmgen.out("""
                lda  P8ZP_SCRATCH_PTR
                clc
                adc  #$fieldoffset
                sta  P8ZP_SCRATCH_PTR
                bcc  +
                inc  P8ZP_SCRATCH_PTR+1
+""")
        }

        fun updateScratchPointer() {
            asmgen.out("""
                ldy  #0
                lda  (P8ZP_SCRATCH_PTR),y
                tax
                iny
                lda  (P8ZP_SCRATCH_PTR),y
                sta  P8ZP_SCRATCH_PTR+1
                stx  P8ZP_SCRATCH_PTR""")
        }

        var struct: StStruct? = null
        if(pointer.startpointer.type.subType!=null)
            struct = pointer.startpointer.type.subType as StStruct

        if(pointer.chain.isEmpty()) {
            require(pointer.derefLast)
            // just return the pointer itself
            if (!forceTemporary && allocator.isZpVar(pointer.startpointer.name))
                return pointer.startpointer.name to 0u
            else {
                asmgen.assignExpressionToVariable(pointer.startpointer, "P8ZP_SCRATCH_PTR", DataType.UWORD)
                return "P8ZP_SCRATCH_PTR" to 0u
            }
        } else if(pointer.chain.size==1) {
            val field = struct!!.getField(pointer.chain[0], asmgen.program.memsizer)
            if(addOffsetToPointer) {
                asmgen.assignExpressionToVariable(pointer.startpointer, "P8ZP_SCRATCH_PTR", DataType.UWORD)
                addFieldOffsetToScratchPointer(field.second.toUInt())
                if(pointer.derefLast)
                    updateScratchPointer()
                return "P8ZP_SCRATCH_PTR" to 0u
            } else {
                if (!forceTemporary && allocator.isZpVar(pointer.startpointer.name)) {
                    if(pointer.derefLast) {
                        asmgen.assignExpressionToVariable(pointer.startpointer, "P8ZP_SCRATCH_PTR", DataType.UWORD)
                        addFieldOffsetToScratchPointer(field.second.toUInt())
                        return "P8ZP_SCRATCH_PTR" to 0u
                    }
                    else
                        return pointer.startpointer.name to field.second
                }
                else {
                    // have to copy it to temp zp var
                    asmgen.assignExpressionToVariable(pointer.startpointer, "P8ZP_SCRATCH_PTR", DataType.UWORD)
                    if(pointer.derefLast) {
                        addFieldOffsetToScratchPointer(field.second.toUInt())
                        return "P8ZP_SCRATCH_PTR" to 0u
                    }
                    else
                        return "P8ZP_SCRATCH_PTR" to field.second
                }
            }
        }

        // walk pointer chain, calculate pointer address using P8ZP_SCRATCH_PTR
        asmgen.assignExpressionToVariable(pointer.startpointer, "P8ZP_SCRATCH_PTR", DataType.UWORD)

        // traverse deref chain
        for(deref in pointer.chain.dropLast(1)) {
            val fieldinfo = struct!!.getField(deref, asmgen.program.memsizer)
            val fieldoffset = fieldinfo.second
            struct = fieldinfo.first.subType as StStruct
            // get new pointer from field (P8ZP_SCRATCH_PTR += fieldoffset, read pointer from new location)
            addFieldOffsetToScratchPointer(fieldoffset.toUInt())
            updateScratchPointer()
        }

        val field = pointer.chain.last()
        val fieldinfo = struct!!.getField(field, asmgen.program.memsizer)

        if(pointer.derefLast) {
            addFieldOffsetToScratchPointer(fieldinfo.second.toUInt())
            require(fieldinfo.first.isPointer)
            updateScratchPointer()
            return "P8ZP_SCRATCH_PTR" to 0u
        } else {
            if(addOffsetToPointer) {
                addFieldOffsetToScratchPointer(fieldinfo.second.toUInt())
                return "P8ZP_SCRATCH_PTR" to 0u
            } else
                return "P8ZP_SCRATCH_PTR" to fieldinfo.second
        }
    }

    internal fun assignPointerDerefExpression(target: AsmAssignTarget, value: PtPointerDeref) {
        val (zpPtrVar, offset) = deref(value)
        if(value.type.isByteOrBool) {
            asmgen.loadIndirectByte(zpPtrVar, offset)
            asmgen.assignRegister(RegisterOrPair.A, target)
        }
        else if(value.type.isWord || value.type.isPointer) {
            asmgen.loadIndirectWord(zpPtrVar, offset)
            asmgen.assignRegister(RegisterOrPair.AY, target)
        }
        else if(value.type.isFloat) {
            asmgen.loadIndirectFloat(zpPtrVar, offset)
            asmgen.assignRegister(RegisterOrPair.FAC1, target)
        }
        else if(value.type.isLong)
            TODO("load long")
        else
            throw AssemblyError("weird dt ${value.type} in pointer deref assignment ${target.position}")
    }

    internal fun inplaceModification(target: PtrTarget, operator: String, value: AsmAssignSource) {
        when (operator) {
            "+" -> {
                if(target.dt.isByte && value.number?.number==1.0 || value.number?.number==2.0) {
                    val amount = value.number.number.toInt()
                    inplaceByteInc(target, amount)
                } else {
                    if (target.dt.isByte) inplaceByteAdd(target, value)
                    else if (target.dt.isWord) inplaceWordAdd(target, value)
                    else if (target.dt.isFloat) inplaceFloatAddOrMul(target, "FADD", value)
                    else throw AssemblyError("weird dt ${target.dt} ${target.position}")
                }
            }
            "-" -> {
                if(target.dt.isByte && value.number?.number==1.0 || value.number?.number==2.0) {
                    val amount = value.number.number.toInt()
                    inplaceByteDec(target, amount)
                } else {
                    if (target.dt.isByte) inplaceByteSub(target, value)
                    else if (target.dt.isWord) inplaceWordSub(target, value)
                    else if (target.dt.isFloat) inplaceFloatSubOrDiv(target, "FSUB", value)
                    else throw AssemblyError("weird dt ${target.position}")
                }
            }
            "*" -> {
                if(target.dt.isByte) TODO("inplaceByteMul(target, value)  ${target.position}")
                else if(target.dt.isWord) inplaceWordMul(target, value)
                else if(target.dt.isFloat)  inplaceFloatAddOrMul(target, "FMULT", value)
                else throw AssemblyError("weird dt ${target.position}")
            }
            "/" -> {
                if(target.dt.isByte) TODO("inplaceByteDiv(target, value)  ${target.position}")
                else if(target.dt.isWord) inplaceWordDiv(target, value)
                else if(target.dt.isFloat)  inplaceFloatSubOrDiv(target, "FDIV", value)
                else throw AssemblyError("weird dt ${target.position}")
            }
            "%" -> TODO("inplace ptr %")
            "<<" -> {
                if(target.dt.isByte) inplaceByteShiftLeft(target, value)
                else if(target.dt.isWord) inplaceWordShiftLeft(target, value)
                else throw AssemblyError("weird dt ${target.position}")
            }
            ">>" -> {
                if(target.dt.isByte) inplaceByteShiftRight(target, value)
                else if(target.dt.isWord) inplaceWordShiftRight(target, value)
                else throw AssemblyError("weird dt ${target.position}")
            }
            "&", "and" -> {
                // byte targets are handled as direct memory access, not a pointer operation anymore however boolean targets are still to be handled here
                if(target.dt.isByteOrBool) inplaceByteAnd(target, value)
                else if(target.dt.isWord) inplaceWordAnd(target, value)
                else throw AssemblyError("weird dt ${target.dt} ${target.position}")
            }
            "|", "or" -> {
                // byte targets are handled as direct memory access, not a pointer operation anymore however boolean targets are still to be handled here
                if(target.dt.isByteOrBool) inplaceByteOr(target, value)
                else if(target.dt.isWord) inplaceWordOr(target, value)
                else throw AssemblyError("weird dt ${target.dt} ${target.position}")
            }
            "^", "xor" -> {
                // byte targets are handled as direct memory access, not a pointer operation anymore however boolean targets are still to be handled here
                if(target.dt.isByteOrBool) inplaceByteXor(target, value)
                else if(target.dt.isWord) inplaceWordXor(target, value)
                else throw AssemblyError("weird dt ${target.dt} ${target.position}")
            }
            else -> throw AssemblyError("invalid operator for in-place modification $operator")
        }
    }

    internal fun assignByte(target: IndexedPtrTarget, byte: Int) {
        val eltSize = asmgen.program.memsizer.memorySize(target.elementDt, null)
        val constIndex = target.index.asConstInteger()
        if(constIndex!=null) {
            val (zpPtrVar, offset2) = deref(target.pointer)
            val offset = eltSize*constIndex + offset2.toInt()
            if(offset>255) {
                // need to add index to the pointer (but not clobber it) to allow index>255
                if(zpPtrVar.startsWith("P8ZP_SCRATCH_")) {          // TODO not very robust....
                    asmgen.out("""
                        lda  $zpPtrVar+1
                        clc
                        adc  #>$offset
                        sta  $zpPtrVar+1
                        ldy  #<$offset
                        lda  #$byte
                        sta  ($zpPtrVar),y""")
                } else {
                    asmgen.out("""
                        lda  $zpPtrVar
                        sta  P8ZP_SCRATCH_PTR
                        lda  $zpPtrVar+1
                        clc
                        adc  #>$offset
                        sta  P8ZP_SCRATCH_PTR+1
                        ldy  #<$offset
                        lda  #$byte
                        sta  (P8ZP_SCRATCH_PTR),y""")
                }
            } else {
                asmgen.out("""
                    ldy  #<$offset
                    lda  #$byte
                    sta  ($zpPtrVar),y""")
            }
        } else if(target.index is PtIdentifier) {
            val (zpPtrVar, _) = deref(target.pointer, addOffsetToPointer=true)
            val indexVarName = asmgen.asmVariableName(target.index)
            if(eltSize!=1) {
                TODO("multiply index by element size $eltSize ${target.position}")
                // asmgen.loadScaledArrayIndexIntoRegister() ...
            }
            // element size is 1, can immediately add the index value
            if(target.index.type.isWord) {
                asmgen.out("""
                    clc
                    lda  $zpPtrVar+1
                    adc  $indexVarName+1
                    sta  $zpPtrVar+1""")
            }
            asmgen.out("""
                ldy  $indexVarName
                lda  #$byte
                sta  ($zpPtrVar),y""")
        } else {
            if(eltSize!=1) {
                TODO("multiply index by element size $eltSize ${target.position}")
                // asmgen.loadScaledArrayIndexIntoRegister() ...
            }
            if(target.index.type.isByte) {
                asmgen.pushCpuStack(BaseDataType.UBYTE, target.index)
                val (zpPtrVar, _) = deref(target.pointer, addOffsetToPointer = true)
                asmgen.restoreRegisterStack(CpuRegister.Y, false)
                asmgen.out("  lda  #$byte |  sta  ($zpPtrVar),y")
            }
            else {
                asmgen.pushCpuStack(BaseDataType.UWORD, target.index)
                val (zpPtrVar, _) = deref(target.pointer, addOffsetToPointer = true)
                asmgen.out("""
                    pla
                    clc
                    adc  $zpPtrVar+1
                    sta  $zpPtrVar+1""")
                if(asmgen.isTargetCpu(CpuType.CPU65C02)) asmgen.out("  ply") else asmgen.out("  pla |  tay")
                asmgen.out("  lda  #$byte |  sta  ($zpPtrVar),y")
            }
        }
    }

    internal fun assignWord(target: IndexedPtrTarget, word: Int) {
        TODO("array ptr assign const word ${target.position}")
    }

    internal fun assignFloat(target: IndexedPtrTarget, float: Double) {
        TODO("array ptr assign const float ${target.position}")
    }

    internal fun assignFAC1(target: IndexedPtrTarget) {
        TODO("array ptr assign FAC1 ${target.position}")
    }

    internal fun assignFloatAY(target: IndexedPtrTarget) {
        TODO("array ptr assign float AY ${target.position}")
    }

    internal fun assignFloatVar(target: IndexedPtrTarget, varName: String) {
        TODO("array ptr assign float var ${target.position}")
    }

    internal fun assignByteReg(target: IndexedPtrTarget, register: CpuRegister) {
        asmgen.saveRegisterStack(register, false)
        TODO("array ptr assign byte reg ${target.position}")
        asmgen.saveRegisterStack(register, false)
    }

    internal fun assignWordReg(target: IndexedPtrTarget, regs: RegisterOrPair) {
        saveOnStack(regs)
        TODO("array ptr assign word reg ${target.position}")
        restoreFromStack(regs)
    }

    internal fun assignByteVar(target: IndexedPtrTarget, varName: String, extendToWord: Boolean, signed: Boolean) {
        TODO("array ptr assign byte var ${target.position}")
    }

    internal fun assignWordVar(target: IndexedPtrTarget, varName: String) {
        TODO("array ptr assign word var ${target.position}")
    }

    internal fun operatorDereference(binExpr: PtBinaryExpression): Triple<String, UByte, DataType> {
        // the only case we support here is:   a.b.c[i] . value
        // returns the ZP var to use as a pointer, and a Y register offset (which can be zero), and finally the datatype of the field
        val left = binExpr.left as? PtArrayIndexer
        val right = binExpr.right as? PtIdentifier
        require(binExpr.operator=="." && left!=null && right!=null) {"invalid dereference expression ${binExpr.position}"}

        val field: Pair<DataType, UByte>
        var extraFieldOffset = 0

        if(left.type.isStructInstance) {

            // indexing on a pointer directly
            // fetch pointer address, determine struct and field, add index * structsize
            if(left.variable!=null) {
                asmgen.assignExpressionToRegister(left.variable!!, RegisterOrPair.AY)
            } else if(left.pointerderef!=null) {
                TODO("get pointer from deref $left")
            } else {
                throw AssemblyError("weird arrayindexer $left")
            }
            val struct = left.type.subType!! as StStruct
            val constindex = left.index as? PtNumber
            if(constindex!=null) {
                extraFieldOffset = struct.size.toInt() * constindex.number.toInt()
            } else {
                addUnsignedByteOrWordToAY(left.index, struct.size.toInt())
            }
            field = struct.getField(right.name, asmgen.program.memsizer)

        } else {
            // indexing on an array with pointers
            // fetch the pointer from the array, determine the struct & field
            asmgen.assignExpressionToRegister(left, RegisterOrPair.AY)
            val struct = left.type.dereference().subType as StStruct
            field = struct.getField(right.name, asmgen.program.memsizer)
        }

        val offset = extraFieldOffset + field.second.toInt()
        // TODO avoid always using a zp scratch reg below if the only thing we dereferenced above is a pointer variable that is already in zeropage itself
        if(offset>=256) {
            // add field offset to pointer if it doesn't fit in the index register
            asmgen.out("""
                clc
                adc  #<$offset
                sta  P8ZP_SCRATCH_PTR
                tya
                adc  #>$offset
                sta  P8ZP_SCRATCH_PTR+1""")
            return Triple("P8ZP_SCRATCH_PTR", 0u, field.first)
        } else {
            asmgen.out("  sta  P8ZP_SCRATCH_PTR |  sty  P8ZP_SCRATCH_PTR+1")
            return Triple("P8ZP_SCRATCH_PTR", offset.toUByte(), field.first)
        }
    }

    private fun addUnsignedByteOrWordToAY(value: PtExpression, scale: Int?) {
        // TODO generate more optimal code from this, a bit too much stack and temporary variable juggling seems to be done in some easy cases...

        fun complicatedFallback() {
            // slow fallback routine that can deal with any expression for value

            fun restoreAYandAddAsmVariable(varname: String, isByte: Boolean) {
                asmgen.restoreRegisterStack(CpuRegister.Y, false)       // msb
                asmgen.restoreRegisterStack(CpuRegister.A, false)       // lsb
                if(isByte) {
                    asmgen.out("""
                        clc
                        adc  $varname
                        bcc  +
                        iny
+""")
                } else {
                    asmgen.out("""
                        clc
                        adc  $varname
                        pha
                        tya
                        adc  $varname+1
                        tay
                        pla""")
                }
            }

            if (scale == null || scale == 1) {
                asmgen.saveRegisterStack(CpuRegister.A, false)
                asmgen.saveRegisterStack(CpuRegister.Y, false)
                if(value.type.isUnsignedWord) {
                    asmgen.assignExpressionToVariable(value, "P8ZP_SCRATCH_PTR", DataType.UWORD)
                    restoreAYandAddAsmVariable("P8ZP_SCRATCH_PTR", false)
                } else {
                    asmgen.assignExpressionToVariable(value, "P8ZP_SCRATCH_REG", DataType.UBYTE)
                    restoreAYandAddAsmVariable("P8ZP_SCRATCH_REG", true)
                }
            } else {
                // P8ZP_SCRATCH_PTR = value * scale
                if(value is PtBinaryExpression && value.operator=="+" && value.right is PtNumber) {
                    // (x + y) * scale == (x * scale) + (y * scale)
                    addUnsignedByteOrWordToAY(value.left, scale)
                    addUnsignedByteOrWordToAY(value.right, scale)
                } else if(value is PtIdentifier) {
                    // no need to save AY on the stack in this case we can slightly optimize it by storing them in a temp variable instead
                    asmgen.out("  sta  P8ZP_SCRATCH_PTR |  sty  P8ZP_SCRATCH_PTR+1")
                    val mult = PtBinaryExpression("*", DataType.UWORD, value.position)
                    mult.parent = value.parent
                    mult.add(value)
                    mult.add(PtNumber(BaseDataType.UWORD, scale.toDouble(), value.position))
                    val multSrc = AsmAssignSource.fromAstSource(mult, asmgen.program, asmgen)
                    val target = AsmAssignTarget(TargetStorageKind.REGISTER, asmgen, DataType.UWORD, value.definingISub(), value.position, register = RegisterOrPair.AY)
                    val assign= AsmAssignment(multSrc, listOf(target), asmgen.program.memsizer, value.position)
                    asmgen.translateNormalAssignment(assign, value.definingISub())
                    asmgen.out("""
                        clc
                        adc  P8ZP_SCRATCH_PTR
                        pha
                        tya
                        adc  P8ZP_SCRATCH_PTR+1
                        tay
                        pla""")
                } else {
                    asmgen.saveRegisterStack(CpuRegister.A, false)
                    asmgen.saveRegisterStack(CpuRegister.Y, false)
                    val mult = PtBinaryExpression("*", DataType.UWORD, value.position)
                    mult.parent = value.parent
                    mult.add(value)
                    mult.add(PtNumber(BaseDataType.UWORD, scale.toDouble(), value.position))
                    val multSrc = AsmAssignSource.fromAstSource(mult, asmgen.program, asmgen)
                    val target = AsmAssignTarget(TargetStorageKind.VARIABLE, asmgen, DataType.UWORD, value.definingISub(), value.position, variableAsmName = "P8ZP_SCRATCH_PTR")
                    val assign= AsmAssignment(multSrc, listOf(target), asmgen.program.memsizer, value.position)
                    asmgen.translateNormalAssignment(assign, value.definingISub())
                    restoreAYandAddAsmVariable("P8ZP_SCRATCH_PTR", false)
                }
            }
        }

        when(value) {
            is PtNumber -> {
                val num = if(scale!=null) value.number.toInt() * scale else value.number.toInt()
                require(num>=0)
                if(num<1)
                    return
                else if (num<256) {
                    asmgen.out("""
                        clc
                        adc  #$num
                        bcc  +
                        iny
+""")
                } else {
                    asmgen.out("""
                        clc
                        adc  #<$num
                        pha
                        tya
                        adc  #>$num
                        tay
                        pla""")
                }
            }
            is PtIdentifier -> {
                val varname = asmgen.asmVariableName(value)
                if(scale==null || scale==1) {
                    if(value.type.isUnsignedWord)
                        asmgen.out("""
                            clc
                            adc  $varname
                            pha
                            tya
                            adc  $varname+1
                            tay
                            pla""")
                    else if(value.type.isUnsignedByte) {
                        asmgen.out("""
                            clc
                            adc  $varname
                            bcc  +
                            iny
+""")
                    } else {
                        throw AssemblyError("weird index type ${value.type} ${value.position}")
                    }
                } else {
                    // AY += var * scale
                    complicatedFallback()
                }
            }
            else -> {
                // AY += var * scale
                complicatedFallback()
            }
        }
    }

    internal fun assignAddressOfIndexedPointer(target: AsmAssignTarget, arrayVarName: String, arrayDt: DataType, index: PtExpression) {
        // use pointer arithmetic to get the address of the array element
        val constIndex = index.asConstInteger()
        val eltSize = if(arrayDt.sub!=null)
            asmgen.program.memsizer.memorySize(arrayDt.sub!!)
        else
            arrayDt.subType!!.memsize(asmgen.program.memsizer)

        fun addArrayBaseAddressToOffsetInAY() {
            asmgen.out("""
                clc
                adc  $arrayVarName
                pha
                tya
                adc  $arrayVarName+1
                tay
                pla""")
        }

        if(constIndex!=null) {
            val offset = eltSize * constIndex
            if(offset>255) {
                asmgen.out(" lda  #<$offset |  ldy  #>$offset")
                addArrayBaseAddressToOffsetInAY()
            } else if(offset>0) {
                asmgen.out("""
                    lda  $arrayVarName
                    ldy  $arrayVarName+1
                    clc
                    adc  #$offset
                    bcc  +
                    iny
+""")
            }
        } else {
            asmgen.assignExpressionToRegister(index, RegisterOrPair.AY)
            if(eltSize>1) {
                if(eltSize in powersOfTwoInt) {
                    val shift = log2(eltSize.toDouble()).toInt()
                    asmgen.out("  sty  P8ZP_SCRATCH_REG")
                    repeat(shift) {
                        asmgen.out("  asl  a |  rol  P8ZP_SCRATCH_REG")
                    }
                    asmgen.out("  ldy  P8ZP_SCRATCH_REG")
                } else if(eltSize in asmgen.optimizedWordMultiplications) {
                    asmgen.out("  jsr  prog8_math.mul_word_${eltSize}")
                } else {
                    asmgen.out(
                        """
                        sta  prog8_math.multiply_words.multiplier
                        sty  prog8_math.multiply_words.multiplier+1
                        lda  #<$eltSize
                        ldy  #>$eltSize
                        jsr  prog8_math.multiply_words"""
                    )
                }
            }
            addArrayBaseAddressToOffsetInAY()
        }
        asmgen.assignRegister(RegisterOrPair.AY, target)
    }

    private fun inplaceWordShiftRight(target: PtrTarget, value: AsmAssignSource) {
        val (zpPtrVar, offset) = deref(target.pointer)

        if(target.dt.isSigned)
            TODO("signed word shift right ${target.position} $value")

        fun shift1unsigned() {
            asmgen.out("""
                ldy  #${offset+1u}
                lda  ($zpPtrVar),y
                lsr  a
                sta  ($zpPtrVar),y
                dey
                lda  ($zpPtrVar),y
                ror  a
                sta  ($zpPtrVar),y""")
        }

        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val number = value.number!!.number.toInt()
                if(number==1) {
                    shift1unsigned()
                } else if(number>1) {
                    asmgen.out("  ldx  #$number")
                    asmgen.out("-")
                    shift1unsigned()
                    asmgen.out("  dex |  bne  -")
                }
            }
            SourceStorageKind.VARIABLE -> {
                require(value.datatype.isByte)
                val varname = value.asmVarname
                asmgen.out("  ldx  $varname")
                asmgen.out("-")
                shift1unsigned()
                asmgen.out("  dex |  bne  -")
            }
            SourceStorageKind.EXPRESSION -> {
                require(value.datatype.isByte)
                asmgen.assignExpressionToRegister(value.expression!!, RegisterOrPair.X)
                asmgen.out("-")
                shift1unsigned()
                asmgen.out("  dex |  bne  -")
            }
            SourceStorageKind.REGISTER -> {
                require(value.datatype.isByte)
                val register = value.register!!
                asmgen.assignRegister(register, AsmAssignTarget(TargetStorageKind.REGISTER, asmgen, DataType.UBYTE, null, target.position, register = RegisterOrPair.X))
                asmgen.out("-")
                shift1unsigned()
                asmgen.out("  dex |  bne  -")
            }
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceByteShiftRight(target: PtrTarget, value: AsmAssignSource) {
        val (zpPtrVar, offset) = deref(target.pointer)

        if(target.dt.isSigned)
            TODO("signed byte shift right ${target.position} $value")

        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val number = value.number!!.number.toInt()
                if(number==1) {
                    asmgen.out("""
                        ldy  #$offset
                        lda  ($zpPtrVar),y
                        lsr  a
                        sta  ($zpPtrVar),y""")
                } else if(number>1) {
                    asmgen.out("""
                        ldx  #$number
                        ldy  #$offset
                        lda  ($zpPtrVar),y
-                       lsr  a
                        dex
                        bne -
                        sta  ($zpPtrVar),y""")
                }
            }
            SourceStorageKind.VARIABLE -> {
                require(value.datatype.isByte)
                val varname = value.asmVarname
                asmgen.out("""
                    ldx  $varname
                    ldy  #$offset
                    lda  ($zpPtrVar),y
-                   lsr  a
                    dex
                    bne -
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.EXPRESSION -> {
                require(value.datatype.isByte)
                asmgen.assignExpressionToRegister(value.expression!!, RegisterOrPair.X)
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
-                   lsr  a
                    dex
                    bne -
                    sta  ($zpPtrVar),y""")
        }
            SourceStorageKind.REGISTER -> {
                require(value.datatype.isByte)
                val register = value.register!!
                asmgen.assignRegister(register, AsmAssignTarget(TargetStorageKind.REGISTER, asmgen, DataType.UWORD, null, target.position, register = RegisterOrPair.X))
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
-                   lsr  a
                    dex
                    bne -
                    sta  ($zpPtrVar),y""")
            }
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceWordShiftLeft(target: PtrTarget, value: AsmAssignSource) {
        val (zpPtrVar, offset) = deref(target.pointer)

        fun shift1() {
            asmgen.out("""
                ldy  #$offset
                lda  ($zpPtrVar),y
                asl  a
                sta  ($zpPtrVar),y
                iny
                lda  ($zpPtrVar),y
                rol  a
                sta  ($zpPtrVar),y""")
        }

        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val number = value.number!!.number.toInt()
                if(number==1) {
                    shift1()
                } else if(number>1) {
                    asmgen.out("  ldx  #$number")
                    asmgen.out("-")
                    shift1()
                    asmgen.out("  dex |  bne  -")
                }
            }
            SourceStorageKind.VARIABLE -> {
                require(value.datatype.isByte)
                val varname = value.asmVarname
                asmgen.out("  ldx  $varname")
                asmgen.out("-")
                shift1()
                asmgen.out("  dex |  bne  -")
            }
            SourceStorageKind.EXPRESSION -> {
                require(value.datatype.isByte)
                asmgen.assignExpressionToRegister(value.expression!!, RegisterOrPair.X)
                asmgen.out("-")
                shift1()
                asmgen.out("  dex |  bne  -")
            }
            SourceStorageKind.REGISTER -> {
                require(value.datatype.isByte)
                val register = value.register!!
                asmgen.assignRegister(register, AsmAssignTarget(TargetStorageKind.REGISTER, asmgen, DataType.UBYTE, null, target.position, register = RegisterOrPair.X))
                asmgen.out("-")
                shift1()
                asmgen.out("  dex |  bne  -")
            }
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceByteShiftLeft(target: PtrTarget, value: AsmAssignSource) {
        val (zpPtrVar, offset) = deref(target.pointer)

        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val number = value.number!!.number.toInt()
                if(number==1) {
                    asmgen.out("""
                        ldy  #$offset
                        lda  ($zpPtrVar),y
                        asl  a
                        sta  ($zpPtrVar),y""")
                } else if(number>1) {
                    asmgen.out("""
                        ldx  #$number
                        ldy  #$offset
                        lda  ($zpPtrVar),y
-                       asl  a
                        dex
                        bne -
                        sta  ($zpPtrVar),y""")
                }
            }
            SourceStorageKind.VARIABLE -> {
                require(value.datatype.isByte)
                val varname = value.asmVarname
                asmgen.out("""
                    ldx  $varname
                    ldy  #$offset
                    lda  ($zpPtrVar),y
-                   asl  a
                    dex
                    bne -
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.EXPRESSION -> {
                require(value.datatype.isByte)
                asmgen.assignExpressionToRegister(value.expression!!, RegisterOrPair.X)
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
-                   asl  a
                    dex
                    bne -
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.REGISTER -> {
                require(value.datatype.isByte)
                val register = value.register!!
                asmgen.assignRegister(register, AsmAssignTarget(TargetStorageKind.REGISTER, asmgen, DataType.UBYTE, null, target.position, register = RegisterOrPair.X))
                asmgen.out("-")
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
-                   asl  a
                    dex
                    bne -
                    sta  ($zpPtrVar),y""")
            }
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceWordAdd(target: PtrTarget, value: AsmAssignSource) {
        val (zpPtrVar, offset) = deref(target.pointer)
        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val number = value.number!!.number.toInt()
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
                    clc
                    adc  #<$number
                    sta  ($zpPtrVar),y
                    iny
                    lda  ($zpPtrVar),y
                    adc  #>$number
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.VARIABLE -> {
                require(value.datatype.isWord)
                val varname = value.asmVarname
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
                    clc
                    adc  $varname
                    sta  ($zpPtrVar),y
                    iny
                    lda  ($zpPtrVar),y
                    adc  $varname+1
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.EXPRESSION -> {
                require(value.datatype.isWord)
                asmgen.assignExpressionToRegister(value.expression!!, RegisterOrPair.AX)
                asmgen.out("""
                    ldy  #$offset
                    clc
                    adc  ($zpPtrVar),y
                    sta  ($zpPtrVar),y
                    iny
                    txa
                    adc  ($zpPtrVar),y
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.REGISTER -> {
                require(value.datatype.isWord)
                val register = value.register!!
                asmgen.assignRegister(register, AsmAssignTarget(TargetStorageKind.VARIABLE, asmgen, DataType.UWORD, null, target.position, variableAsmName = "P8ZP_SCRATCH_PTR"))
                require(register.isWord())
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
                    clc
                    adc  P8ZP_SCRATCH_PTR
                    sta  ($zpPtrVar),y
                    iny
                    lda  ($zpPtrVar),y
                    adc  P8ZP_SCRATCH_PTR+1
                    sta  ($zpPtrVar),y""")
            }
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceFloatAddOrMul(target: PtrTarget, floatoperation: String, value: AsmAssignSource) {
        require(floatoperation=="FADD" || floatoperation=="FMULT")
        val (zpPtrVar, _) = deref(target.pointer, addOffsetToPointer = true)
        asmgen.out("""
            lda  $zpPtrVar
            ldy  $zpPtrVar+1
            jsr  floats.MOVFM""")
        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val floatConst = allocator.getFloatAsmConst(value.number!!.number)
                asmgen.out("""
                    lda  #<$floatConst
                    ldy  #>$floatConst
                    jsr  floats.$floatoperation
                    ldx  $zpPtrVar
                    ldy  $zpPtrVar+1
                    jsr  floats.MOVMF""")
            }
            SourceStorageKind.VARIABLE -> TODO("variable + * float")
            SourceStorageKind.EXPRESSION -> TODO("expression + * float")
            SourceStorageKind.REGISTER -> TODO("register + * float")
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceWordSub(target: PtrTarget, value: AsmAssignSource) {
        val (zpPtrVar, offset) = deref(target.pointer)
        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val number = value.number!!.number.toInt()
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
                    sec
                    sbc  #<$number
                    sta  ($zpPtrVar),y
                    iny
                    lda  ($zpPtrVar),y
                    sbc  #>$number
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.VARIABLE -> {
                require(value.datatype.isWord)
                val varname = value.asmVarname
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
                    sec
                    sbc  $varname
                    sta  ($zpPtrVar),y
                    iny
                    lda  ($zpPtrVar),y
                    sbc  $varname+1
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.EXPRESSION -> {
                require(value.datatype.isWord)
                asmgen.assignExpressionToVariable(value.expression!!, "P8ZP_SCRATCH_W1", DataType.UWORD)
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
                    sec
                    sbc  P8ZP_SCRATCH_W1
                    sta  ($zpPtrVar),y
                    iny
                    lda  ($zpPtrVar),y  
                    sbc  P8ZP_SCRATCH_W1+1
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.REGISTER -> TODO("register - word")
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceWordMul(target: PtrTarget, value: AsmAssignSource) {

        val (zpPtrVar, _) = deref(target.pointer, addOffsetToPointer = true)

        fun multiply() {
            // on entry here: number placed in routine argument variable
            asmgen.loadIndirectWord(zpPtrVar, 0u)
            asmgen.out("""
                jsr  prog8_math.multiply_words
                tax
                tya
                ldy  #1
                sta  ($zpPtrVar),y
                dey
                txa
                sta  ($zpPtrVar),y""")
        }

        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val number = value.number!!.number.toInt()
                if(number in powersOfTwoInt)
                    throw AssemblyError("multiply by power of two should have been a shift $value.position")
                asmgen.out("""
                    lda  #<$number
                    ldy  #>$number
                    sta  prog8_math.multiply_words.multiplier
                    sty  prog8_math.multiply_words.multiplier+1""")
                multiply()
            }
            SourceStorageKind.VARIABLE -> {
                require(value.datatype.isWord)
                val varname = value.asmVarname
                asmgen.out("""
                    lda  $varname
                    ldy  $varname+1
                    sta  prog8_math.multiply_words.multiplier
                    sty  prog8_math.multiply_words.multiplier+1""")
                multiply()
            }
            SourceStorageKind.REGISTER -> {
                val register = value.register!!
                require(register.isWord())
                val multiplyArg = AsmAssignTarget(TargetStorageKind.VARIABLE, asmgen, DataType.UWORD, null, target.position, variableAsmName = " prog8_math.multiply_words.multiplier")
                asmgen.assignRegister(register, multiplyArg)
                multiply()
            }
            SourceStorageKind.EXPRESSION -> TODO("ptr * expr (word)")
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceWordDiv(target: PtrTarget, value: AsmAssignSource) {
        val (zpPtrVar, _) = deref(target.pointer, addOffsetToPointer = true)

        fun divide(signed: Boolean) {
            // on entry here: number placed in P8ZP_SCRATCH_PTR, divisor placed in AY
            if(signed) asmgen.out("jsr  prog8_math.divmod_w_asm")
            else asmgen.out("jsr  prog8_math.divmod_uw_asm")
            asmgen.out("""
                tax
                tya
                ldy  #1
                sta  ($zpPtrVar),y
                dey
                txa
                sta  ($zpPtrVar),y""")
        }

        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val number = value.number!!.number.toInt()
                if(number in powersOfTwoInt)
                    throw AssemblyError("divide by power of two should have been a shift $value.position")
                asmgen.loadIndirectWord(zpPtrVar, 0u)
                asmgen.out("""
                    sta  P8ZP_SCRATCH_PTR
                    sty  P8ZP_SCRATCH_PTR+1
                    lda  #<$number
                    ldy  #>$number""")
                divide(target.dt.isSigned)
            }
            SourceStorageKind.VARIABLE -> {
                require(value.datatype.isWord)
                val varname = value.asmVarname
                TODO("inplace variable word divide")
            }
            SourceStorageKind.REGISTER -> {
                val register = value.register!!
                require(register.isWord())
                TODO("inplace register word divide")
            }
            SourceStorageKind.EXPRESSION -> TODO("ptr / expr (word)")
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceFloatSubOrDiv(target: PtrTarget, floatoperation: String, value: AsmAssignSource) {
        require(floatoperation=="FSUB" || floatoperation=="FDIV")
        val (zpPtrVar, _) = deref(target.pointer, addOffsetToPointer = true)
        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val floatConst = allocator.getFloatAsmConst(value.number!!.number)
                asmgen.out("""
                    lda  #<$floatConst
                    ldy  #>$floatConst
                    jsr  floats.MOVFM
                    lda  $zpPtrVar
                    ldy  $zpPtrVar+1
                    jsr  floats.$floatoperation
                    ldx  $zpPtrVar
                    ldy  $zpPtrVar+1
                    jsr  floats.MOVMF""")
            }
            SourceStorageKind.VARIABLE -> TODO("variable - / float")
            SourceStorageKind.EXPRESSION -> TODO("expression - / float")
            SourceStorageKind.REGISTER -> TODO("register - / float")
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceByteInc(target: PtrTarget, amount: Int) {
        require(amount==1 || amount==2)
        val (zpPtrVar, offset) = deref(target.pointer)
        if(offset==0.toUByte() && asmgen.isTargetCpu(CpuType.CPU65C02)) {
            asmgen.out("  lda  ($zpPtrVar)")
            repeat(amount) {
                asmgen.out("  inc  a")
            }
            asmgen.out("  sta  ($zpPtrVar)")
        }
        else {
            asmgen.out("""
                ldy  #$offset
                lda  ($zpPtrVar),y
                clc
                adc  #$amount
                sta  ($zpPtrVar),y""")
        }
    }

    private fun inplaceByteDec(target: PtrTarget, amount: Int) {
        require(amount==1 || amount==2)
        val (zpPtrVar, offset) = deref(target.pointer)
        if(offset==0.toUByte() && asmgen.isTargetCpu(CpuType.CPU65C02)) {
            asmgen.out("  lda  ($zpPtrVar)")
            repeat(amount) {
                asmgen.out("  dec  a")
            }
            asmgen.out("  sta  ($zpPtrVar)")
        }
        else {
            asmgen.out("""
                ldy  #$offset
                lda  ($zpPtrVar),y
                sec
                sbc  #$amount
                sta  ($zpPtrVar),y""")
        }
    }

    private fun inplaceByteAdd(target: PtrTarget, value: AsmAssignSource) {
        val (zpPtrVar, offset) = deref(target.pointer)
        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val number = value.number!!.number.toInt()
                if(offset==0.toUByte() && asmgen.isTargetCpu(CpuType.CPU65C02))
                    asmgen.out("""
                        lda  ($zpPtrVar)
                        clc
                        adc  #$number
                        sta  ($zpPtrVar)""")
                else
                    asmgen.out("""
                        ldy  #$offset
                        lda  ($zpPtrVar),y
                        clc
                        adc  #$number
                        sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.VARIABLE -> {
                val varname = value.asmVarname
                if(offset==0.toUByte() && asmgen.isTargetCpu(CpuType.CPU65C02))
                    asmgen.out("""
                        lda  ($zpPtrVar)
                        clc
                        adc $varname
                        sta  ($zpPtrVar)""")
                else
                    asmgen.out("""
                        ldy  #$offset
                        lda  ($zpPtrVar),y
                        clc
                        adc  $varname
                        sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.EXPRESSION -> {
                asmgen.assignExpressionToRegister(value.expression!!, RegisterOrPair.A)
                asmgen.out("""
                    ldy  #$offset
                    clc
                    adc  ($zpPtrVar),y
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.REGISTER -> TODO("register + byte  ${target.position}")
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceByteSub(target: PtrTarget, value: AsmAssignSource) {
        val (zpPtrVar, offset) = deref(target.pointer)
        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val number = value.number!!.number.toInt()
                if(offset==0.toUByte() && asmgen.isTargetCpu(CpuType.CPU65C02))
                    asmgen.out("""
                        lda  ($zpPtrVar)
                        sec
                        sbc  #$number
                        sta  ($zpPtrVar)""")
                else
                    asmgen.out("""
                        ldy  #$offset
                        lda  ($zpPtrVar),y
                        sec
                        sbc  #$number
                        sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.VARIABLE -> {
                val varname = value.asmVarname
                if(offset==0.toUByte() && asmgen.isTargetCpu(CpuType.CPU65C02))
                    asmgen.out("""
                        lda  ($zpPtrVar)
                        sec
                        sbc $varname
                        sta  ($zpPtrVar)""")
                else
                    asmgen.out("""
                        ldy  #$offset
                        lda  ($zpPtrVar),y
                        sec
                        sbc  $varname
                        sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.EXPRESSION -> {
                asmgen.assignExpressionToVariable(value.expression!!, "P8ZP_SCRATCH_REG", DataType.UBYTE)
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
                    sec
                    sbc  P8ZP_SCRATCH_REG
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.REGISTER -> TODO("register - byte  ${target.position}")
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceByteXor(target: PtrTarget, value: AsmAssignSource) {
        val (zpPtrVar, offset) = deref(target.pointer)
        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val number = value.number!!.number.toInt()
                if(offset==0.toUByte() && asmgen.isTargetCpu(CpuType.CPU65C02))
                    asmgen.out("""
                        lda  ($zpPtrVar)
                        eor  #$number
                        sta  ($zpPtrVar)""")
                else
                    asmgen.out("""
                        ldy  #$offset
                        lda  ($zpPtrVar),y
                        eor  #$number
                        sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.VARIABLE -> {
                val varname = value.asmVarname
                if(offset==0.toUByte() && asmgen.isTargetCpu(CpuType.CPU65C02))
                    asmgen.out("""
                        lda  ($zpPtrVar)
                        eor  $varname
                        sta  ($zpPtrVar)""")
                else
                    asmgen.out("""
                        ldy  #$offset
                        lda  ($zpPtrVar),y
                        eor  $varname
                        sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.EXPRESSION -> {
                asmgen.assignExpressionToRegister(value.expression!!, RegisterOrPair.A)
                asmgen.out("""
                    ldy  #$offset
                    eor  ($zpPtrVar),y
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.REGISTER -> TODO("register ^ byte")
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceWordXor(target: PtrTarget, value: AsmAssignSource) {
        val (zpPtrVar, offset) = deref(target.pointer)
        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val number = value.number!!.number.toInt()
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
                    eor  #<$number
                    sta  ($zpPtrVar),y
                    iny
                    lda  ($zpPtrVar),y
                    eor  #>$number
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.VARIABLE -> {
                require(value.datatype.isWord)
                val varname = value.asmVarname
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
                    eor  $varname
                    sta  ($zpPtrVar),y
                    lda  ($zpPtrVar),y
                    eor  $varname+1
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.EXPRESSION -> {
                require(value.datatype.isWord)
                asmgen.assignExpressionToRegister(value.expression!!, RegisterOrPair.AX)
                asmgen.out("""
                    ldy  #$offset
                    eor  ($zpPtrVar),y
                    sta  ($zpPtrVar),y
                    iny
                    txa
                    eor  ($zpPtrVar),y
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.REGISTER -> TODO("register ^ word")
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceByteOr(target: PtrTarget, value: AsmAssignSource) {
        val (zpPtrVar, offset) = deref(target.pointer)
        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val number = value.number!!.number.toInt()
                if(offset==0.toUByte() && asmgen.isTargetCpu(CpuType.CPU65C02))
                    asmgen.out("""
                        lda  ($zpPtrVar)
                        ora  #$number
                        sta  ($zpPtrVar)""")
                else
                    asmgen.out("""
                        ldy  #$offset
                        lda  ($zpPtrVar),y
                        ora  #$number
                        sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.VARIABLE -> {
                val varname = value.asmVarname
                if(offset==0.toUByte() && asmgen.isTargetCpu(CpuType.CPU65C02))
                    asmgen.out("""
                        lda  ($zpPtrVar)
                        ora  $varname
                        sta  ($zpPtrVar)""")
                else
                    asmgen.out("""
                        ldy  #$offset
                        lda  ($zpPtrVar),y
                        ora  $varname
                        sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.EXPRESSION -> {
                asmgen.assignExpressionToRegister(value.expression!!, RegisterOrPair.A)
                asmgen.out("""
                    ldy  #$offset
                    ora  ($zpPtrVar),y
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.REGISTER -> TODO("register | byte")
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceWordOr(target: PtrTarget, value: AsmAssignSource) {
        val (zpPtrVar, offset) = deref(target.pointer)
        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val number = value.number!!.number.toInt()
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
                    ora  #<$number
                    sta  ($zpPtrVar),y
                    iny
                    lda  ($zpPtrVar),y
                    ora  #>$number
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.VARIABLE -> {
                require(value.datatype.isWord)
                val varname = value.asmVarname
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
                    ora  $varname
                    sta  ($zpPtrVar),y
                    lda  ($zpPtrVar),y
                    ora  $varname+1
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.EXPRESSION -> {
                require(value.datatype.isWord)
                asmgen.assignExpressionToRegister(value.expression!!, RegisterOrPair.AX)
                asmgen.out("""
                    ldy  #$offset
                    ora  ($zpPtrVar),y
                    sta  ($zpPtrVar),y
                    iny
                    txa
                    ora  ($zpPtrVar),y
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.REGISTER -> TODO("register | word")
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceByteAnd(target: PtrTarget, value: AsmAssignSource) {
        val (zpPtrVar, offset) = deref(target.pointer)
        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val number = value.number!!.number.toInt()
                if(offset==0.toUByte() && asmgen.isTargetCpu(CpuType.CPU65C02))
                    asmgen.out("""
                        lda  ($zpPtrVar)
                        and  #$number
                        sta  ($zpPtrVar)""")
                else
                    asmgen.out("""
                        ldy  #$offset
                        lda  ($zpPtrVar),y
                        and  #$number
                        sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.VARIABLE -> {
                val varname = value.asmVarname
                if(offset==0.toUByte() && asmgen.isTargetCpu(CpuType.CPU65C02))
                    asmgen.out("""
                        lda  ($zpPtrVar)
                        and  $varname
                        sta  ($zpPtrVar)""")
                else
                    asmgen.out("""
                        ldy  #$offset
                        lda  ($zpPtrVar),y
                        and  $varname
                        sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.EXPRESSION -> {
                asmgen.assignExpressionToRegister(value.expression!!, RegisterOrPair.A)
                asmgen.out("""
                    ldy  #$offset
                    and  ($zpPtrVar),y
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.REGISTER -> TODO("register & byte")
            else -> throw AssemblyError("weird source value $value")
        }
    }

    private fun inplaceWordAnd(target: PtrTarget, value: AsmAssignSource) {
        val (zpPtrVar, offset) = deref(target.pointer)
        when(value.kind) {
            SourceStorageKind.LITERALNUMBER -> {
                val number = value.number!!.number.toInt()
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
                    and  #<$number
                    sta  ($zpPtrVar),y
                    iny
                    lda  ($zpPtrVar),y
                    and  #>$number
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.VARIABLE -> {
                require(value.datatype.isWord)
                val varname = value.asmVarname
                asmgen.out("""
                    ldy  #$offset
                    lda  ($zpPtrVar),y
                    and  $varname
                    sta  ($zpPtrVar),y
                    lda  ($zpPtrVar),y
                    and  $varname+1
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.EXPRESSION -> {
                require(value.datatype.isWord)
                asmgen.assignExpressionToRegister(value.expression!!, RegisterOrPair.AX)
                asmgen.out("""
                    ldy  #$offset
                    and  ($zpPtrVar),y
                    sta  ($zpPtrVar),y
                    iny
                    txa
                    and  ($zpPtrVar),y
                    sta  ($zpPtrVar),y""")
            }
            SourceStorageKind.REGISTER -> TODO("register & word")
            else -> throw AssemblyError("weird source value $value")
        }
    }

    fun assignIndexedPointer(target: AsmAssignTarget, arrayVarName: String, index: PtExpression, arrayDt: DataType) {
        TODO("assign indexed pointer from array $arrayVarName  at ${target.position}")
//        val ptrZp = AsmAssignTarget(TargetStorageKind.VARIABLE, asmgen, DataType.UWORD, target.scope, target.position, variableAsmName="P8ZP_SCRATCH_PTR")
//        assignAddressOfIndexedPointer(ptrZp, arrayVarName, arrayDt, index)
//        when {
//            target.datatype.isByteOrBool -> {
//                asmgen.out("""
//                    ldy  #0
//                    lda  (P8ZP_SCRATCH_PTR),y""")
//                asmgen.assignRegister(RegisterOrPair.A, target)
//            }
//            target.datatype.isWord || target.datatype.isPointer -> {
//                if(asmgen.isTargetCpu(CpuType.CPU65C02))
//                    asmgen.out("""
//                        ldy  #1
//                        lda  (P8ZP_SCRATCH_PTR),y
//                        tax
//                        lda  (P8ZP_SCRATCH_PTR)""")
//                else
//                    asmgen.out("""
//                        ldy  #1
//                        lda  (P8ZP_SCRATCH_PTR),y
//                        tax
//                        dey
//                        lda  (P8ZP_SCRATCH_PTR),y""")
//                asmgen.assignRegister(RegisterOrPair.AX, target)
//            }
//            target.datatype.isLong -> {
//                TODO("assign long from pointer to $target ${target.position}")
//            }
//            target.datatype.isFloat -> {
//                // TODO optimize the float copying to avoid having to go through FAC1
//                asmgen.out("""
//                    lda  P8ZP_SCRATCH_PTR
//                    ldy  P8ZP_SCRATCH_PTR+1
//                    jsr  floats.MOVFM""")
//                asmgen.assignRegister(RegisterOrPair.FAC1, target)
//            }
//            else -> throw AssemblyError("weird dt ${target.datatype}")
//        }
    }

    private fun saveOnStack(regs: RegisterOrPair) {
        when(regs) {
            RegisterOrPair.AX -> {
                if(asmgen.isTargetCpu(CpuType.CPU65C02))
                    asmgen.out("  pha |  phx")
                else
                    asmgen.out("  pha |  txa |  pha")
            }
            RegisterOrPair.AY -> {
                if(asmgen.isTargetCpu(CpuType.CPU65C02))
                    asmgen.out("  pha |  phy")
                else
                    asmgen.out("  pha |  tya |  pha")
            }
            RegisterOrPair.XY -> {
                if(asmgen.isTargetCpu(CpuType.CPU65C02))
                    asmgen.out("  phx |  phy")
                else
                    asmgen.out("  txa |  pha |  tya |  pha")
            }
            in Cx16VirtualRegisters -> {
                val regname = asmgen.asmSymbolName(regs)
                asmgen.out("""
                    lda  $regname
                    pha
                    lda  $regname+1
                    pha""")
            }
            else -> asmgen.saveRegisterStack(regs.asCpuRegister(), false)
        }
    }

    private fun restoreFromStack(regs: RegisterOrPair) {
        when(regs) {
            RegisterOrPair.AX -> {
                if(asmgen.isTargetCpu(CpuType.CPU65C02))
                    asmgen.out("  plx |  pla")
                else
                    asmgen.out("  pla |  tax |  pla")
            }
            RegisterOrPair.AY -> {
                if(asmgen.isTargetCpu(CpuType.CPU65C02))
                    asmgen.out("  ply |  pla")
                else
                    asmgen.out("  pla |  tay |  pla")
            }
            RegisterOrPair.XY -> {
                if(asmgen.isTargetCpu(CpuType.CPU65C02))
                    asmgen.out("  ply |  plx")
                else
                    asmgen.out("  pla |  tay |  pla |  tax")
            }
            in Cx16VirtualRegisters -> {
                val regname = asmgen.asmSymbolName(regs)
                asmgen.out("""
                    pla
                    sta  $regname+1
                    pla
                    sta  $regname""")
            }
            else -> asmgen.restoreRegisterStack(regs.asCpuRegister(), false)
        }
    }

}
