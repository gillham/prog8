package prog8.intermediate

import prog8.code.*
import prog8.code.ast.PtVariable
import prog8.code.core.*
import prog8.code.target.VMTarget
import prog8.code.target.getCompilationTargetByName
import java.io.StringReader
import java.nio.file.Path
import javax.xml.stream.XMLEventReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamException
import kotlin.io.path.Path
import kotlin.io.path.inputStream


class IRFileReader {

    fun read(irSourceCode: String): IRProgram {
        StringReader(irSourceCode).use { stream ->
            val reader = XMLInputFactory.newInstance().createXMLEventReader(stream)
            try {
                return parseProgram(reader)
            } catch(x: XMLStreamException) {
                System.err.println("Error during parsing, this is not a well-formed P8IR source file")
                throw x
            } finally {
                reader.close()
            }
        }
    }

    fun read(irSourceFile: Path): IRProgram {
        println("Reading intermediate representation from $irSourceFile")

        irSourceFile.inputStream().use { stream ->
            val reader = XMLInputFactory.newInstance().createXMLEventReader(stream)
            try {
                return parseProgram(reader)
            } finally {
                reader.close()
            }
        }
    }

    private fun parseProgram(reader: XMLEventReader): IRProgram {
        require(reader.nextEvent().isStartDocument)
        val start = reader.nextEvent().asStartElement()
        require(start.name.localPart=="PROGRAM") { "missing PROGRAM" }
        val programName = start.attributes.asSequence().single { it.name.localPart == "NAME" }.value
        val options = parseOptions(reader)
        val asmsymbols = parseAsmSymbols(reader)
        val varsWithoutInit = parseVarsWithoutInit(reader)
        val variables = parseVariables(reader)
        val memorymapped = parseMemMapped(reader)
        val slabs = parseSlabs(reader)
        val initGlobals = parseInitGlobals(reader)
        val blocks = parseBlocksUntilProgramEnd(reader)

        val st = IRSymbolTable()
        asmsymbols.forEach { (name, value) -> st.addAsmSymbol(name, value)}
        varsWithoutInit.forEach { st.add(it) }
        variables.forEach { st.add(it) }
        memorymapped.forEach { st.add(it) }
        slabs.forEach { st.add(it) }

        val program = IRProgram(programName, st, options, options.compTarget)
        program.addGlobalInits(initGlobals)
        blocks.forEach{ program.addBlock(it) }

        program.linkChunks()
        program.convertAsmChunks()
        program.validate()

        return program
    }

    private fun parseOptions(reader: XMLEventReader): CompilationOptions {
        skipText(reader)
        val start = reader.nextEvent().asStartElement()
        require(start.name.localPart=="OPTIONS") { "missing OPTIONS" }
        val text = readText(reader).trim()
        require(reader.nextEvent().isEndElement)

        var target: ICompilationTarget = VMTarget()
        var outputType = OutputType.PRG
        var launcher = CbmPrgLauncherType.NONE
        var zeropage = ZeropageType.FULL
        val zpReserved = mutableListOf<UIntRange>()
        val zpAllowed = mutableListOf<UIntRange>()
        var loadAddress = target.machine.PROGRAM_LOAD_ADDRESS
        var optimize = true
        var outputDir = Path("")

        if(text.isNotBlank()) {
            text.lineSequence().forEach { line ->
                val (name, value) = line.split('=', limit=2)
                when(name) {
                    "compTarget" -> target = getCompilationTargetByName(value)
                    "output" -> outputType = OutputType.valueOf(value)
                    "launcher" -> launcher = CbmPrgLauncherType.valueOf(value)
                    "zeropage" -> zeropage = ZeropageType.valueOf(value)
                    "loadAddress" -> loadAddress = parseIRValue(value).toUInt()
                    "zpReserved" -> {
                        val (zpstart, zpend) = value.split(',')
                        zpReserved.add(UIntRange(zpstart.toUInt(), zpend.toUInt()))
                    }
                    "zpAllowed" -> {
                        val (zpstart, zpend) = value.split(',')
                        zpAllowed.add(UIntRange(zpstart.toUInt(), zpend.toUInt()))
                    }
                    "outputDir" -> outputDir = Path(value)
                    "optimize" -> optimize = value.toBoolean()
                    else -> throw IRParseException("illegal OPTION $name")
                }
            }
        }

        return CompilationOptions(
            outputType,
            launcher,
            zeropage,
            zpReserved,
            zpAllowed,
            false,
            false,
            target,
            loadAddress,
            0xffffu,
            outputDir = outputDir,
            optimize = optimize
        )
    }

    private fun parseAsmSymbols(reader: XMLEventReader): Map<String, String> {
        skipText(reader)
        val start = reader.nextEvent().asStartElement()
        require(start.name.localPart=="ASMSYMBOLS") { "missing ASMSYMBOLS" }
        val text = readText(reader).trim()
        require(reader.nextEvent().isEndElement)

        return if(text.isBlank())
            emptyMap()
        else
            text.lineSequence().associate {
                val (name, value) = it.split('=')
                name to value
            }
    }

    private fun parseVarsWithoutInit(reader: XMLEventReader): List<StStaticVariable> {
        skipText(reader)
        val start = reader.nextEvent().asStartElement()
        require(start.name.localPart=="VARIABLESNOINIT") { "missing VARIABLESNOINIT" }
        val text = readText(reader).trim()
        require(reader.nextEvent().isEndElement)

        return if(text.isBlank())
            emptyList()
        else {
            val varPattern = Regex("(.+?)(\\[.+?\\])? (.+) zp=(.+) align=(.+)")
            val variables = mutableListOf<StStaticVariable>()
            text.lineSequence().forEach { line ->
                // example:  uword main.start.qq2 zp=DONTCARE
                val match = varPattern.matchEntire(line) ?: throw IRParseException("invalid VARIABLESNOINIT $line")
                val (type, arrayspec, name, zpwish, alignment) = match.destructured
                if('.' !in name)
                    throw IRParseException("unscoped varname: $name")
                val arraysize = if(arrayspec.isNotBlank()) arrayspec.substring(1, arrayspec.length-1).toInt() else null
                val dt = parseDatatype(type, arraysize!=null)
                val zp = if(zpwish.isBlank()) ZeropageWish.DONTCARE else ZeropageWish.valueOf(zpwish)
                val align = if(alignment.isBlank()) 0u else alignment.toUInt()
                val dummyNode = PtVariable(name, dt, zp, align, null, null, Position.DUMMY)
                val newVar = StStaticVariable(name, dt, null, null, arraysize, zp, align.toInt(), dummyNode)
                variables.add(newVar)
            }
            return variables
        }
    }

    private fun parseVariables(reader: XMLEventReader): List<StStaticVariable> {
        skipText(reader)
        val start = reader.nextEvent().asStartElement()
        require(start.name.localPart=="VARIABLESWITHINIT") { "missing VARIABLESWITHINIT" }
        val text = readText(reader).trim()
        require(reader.nextEvent().isEndElement)

        return if(text.isBlank())
            emptyList()
        else {
            val varPattern = Regex("(.+?)(\\[.+?\\])? (.+)=(.*?) zp=(.+) align=(.+)")
            val variables = mutableListOf<StStaticVariable>()
            text.lineSequence().forEach { line ->
                // examples:
                // uword main.start.qq2=0 zp=REQUIRE_ZP
                // ubyte[6] main.start.namestring=105,114,109,101,110,0
                val match = varPattern.matchEntire(line) ?: throw IRParseException("invalid VARIABLE $line")
                val (type, arrayspec, name, value, zpwish, alignment) = match.destructured
                if('.' !in name)
                    throw IRParseException("unscoped varname: $name")
                val arraysize = if(arrayspec.isNotBlank()) arrayspec.substring(1, arrayspec.length-1).toInt() else null
                val dt: DataType = parseDatatype(type, arraysize!=null)
                val zp = if(zpwish.isBlank()) ZeropageWish.DONTCARE else ZeropageWish.valueOf(zpwish)
                val align = if(alignment.isBlank()) 0u else alignment.toUInt()
                var initNumeric: Double? = null
                var initArray: StArray? = null
                when(dt) {
                    in NumericDatatypesWithBoolean -> initNumeric = parseIRValue(value)
                    DataType.ARRAY_BOOL -> {
                        initArray = value.split(',').map {
                            val boolean = parseIRValue(it) != 0.0
                            StArrayElement(null, null, boolean)
                        }
                    }
                    in ArrayDatatypes -> {
                        initArray = value.split(',').map {
                            if (it.startsWith('@'))
                                StArrayElement(null, it.drop(1), null)
                            else
                                StArrayElement(parseIRValue(it), null, null)
                        }
                    }
                    DataType.STR -> throw IRParseException("STR should have been converted to byte array")
                    else -> throw IRParseException("weird dt")
                }
                val dummyNode = PtVariable(name, dt, zp, align, null, null, Position.DUMMY)
                if(arraysize!=null && initArray!=null && initArray.all { it.number==0.0 }) {
                    initArray=null  // arrays with just zeros can be left uninitialized
                }
                val stVar = StStaticVariable(name, dt, null, initArray, arraysize, zp, align.toInt(), dummyNode)
                if(initNumeric!=null)
                    stVar.setOnetimeInitNumeric(initNumeric)
                variables.add(stVar)
            }
            return variables
        }
    }

    private fun parseMemMapped(reader: XMLEventReader): List<StMemVar> {
        skipText(reader)
        val start = reader.nextEvent().asStartElement()
        require(start.name.localPart=="MEMORYMAPPEDVARIABLES") { "missing MEMORYMAPPEDVARIABLES" }
        val text = readText(reader).trim()
        require(reader.nextEvent().isEndElement)

        return if(text.isBlank())
            emptyList()
        else {
            val memvars = mutableListOf<StMemVar>()
            val mappedPattern = Regex("@(.+?)(\\[.+?\\])? (.+)=(.+)")
            text.lineSequence().forEach { line ->
                // examples:
                // @uword main.start.mapped=49152
                // @ubyte[20] main.start.mappedarray=49408
                val match = mappedPattern.matchEntire(line) ?: throw IRParseException("invalid MEMORYMAPPEDVARIABLES $line")
                val (type, arrayspec, name, address) = match.destructured
                val arraysize = if(arrayspec.isNotBlank()) arrayspec.substring(1, arrayspec.length-1).toInt() else null
                val dt: DataType = parseDatatype(type, arraysize!=null)
                val dummyNode = PtVariable(
                    name,
                    dt,
                    ZeropageWish.NOT_IN_ZEROPAGE,
                    0u,
                    null,
                    null,
                    Position.DUMMY
                )
                memvars.add(StMemVar(name, dt, parseIRValue(address).toUInt(), arraysize, dummyNode))
            }
            memvars
        }
    }

    private fun parseSlabs(reader: XMLEventReader): List<StMemorySlab> {
        skipText(reader)
        val start = reader.nextEvent().asStartElement()
        require(start.name.localPart=="MEMORYSLABS") { "missing MEMORYSLABS" }
        val text = readText(reader).trim()
        require(reader.nextEvent().isEndElement)

        return if(text.isBlank())
            emptyList()
        else {
            val slabs = mutableListOf<StMemorySlab>()
            val slabPattern = Regex("(.+) (.+) (.+)")
            text.lineSequence().forEach { line ->
                // example: "slabname 4096 0"
                val match = slabPattern.matchEntire(line) ?: throw IRParseException("invalid slab $line")
                val (name, size, align) = match.destructured
                val dummyNode = PtVariable(
                    name,
                    DataType.ARRAY_UB,
                    ZeropageWish.NOT_IN_ZEROPAGE,
                    0u,
                    null,
                    null,
                    Position.DUMMY
                )
                slabs.add(StMemorySlab(name, size.toUInt(), align.toUInt(), dummyNode))
            }
            slabs
        }
    }

    private fun parseInitGlobals(reader: XMLEventReader): IRCodeChunk {
        skipText(reader)
        val start = reader.nextEvent().asStartElement()
        require(start.name.localPart=="INITGLOBALS") { "missing INITGLOBALS" }
        skipText(reader)
        val chunk: IRCodeChunk = if(reader.peek().isStartElement)
            parseCodeChunk(reader)
        else
            IRCodeChunk(null, null)
        skipText(reader)
        require(reader.nextEvent().isEndElement)
        return chunk
    }

    private fun parseCodeChunk(reader: XMLEventReader): IRCodeChunk {
        skipText(reader)
        val start = reader.nextEvent().asStartElement()
        require(start.name.localPart=="CODE") { "missing CODE" }
        val next = reader.peek()
        if(next.isStartElement && next.asStartElement().name.localPart=="P8SRC") {
            reader.nextEvent()  // skip the P8SRC node
            while(!reader.nextEvent().isEndElement) { /* skip until end of P8SRC node */ }
        }
        val label = start.attributes.asSequence().singleOrNull { it.name.localPart == "LABEL" }?.value?.ifBlank { null }
        val text = readText(reader).trim()
        val chunk = IRCodeChunk(label, null)
        if(text.isNotBlank()) {
            text.lineSequence().forEach { line ->
                if (line.isNotBlank() && !line.startsWith(';')) {
                    val result = parseIRCodeLine(line)
                    result.fold(
                        ifLeft = {
                            chunk += it
                        },
                        ifRight = {
                            throw IRParseException("code chunk should not contain a separate label line anymore, this should be the proper label of a new separate chunk")
                        }
                    )
                }
            }
        }
        require(reader.nextEvent().isEndElement)
        return chunk
    }

    private fun parseBlocksUntilProgramEnd(reader: XMLEventReader): List<IRBlock> {
        val blocks = mutableListOf<IRBlock>()
        skipText(reader)
        while(reader.peek().isStartElement) {
            blocks.add(parseBlock(reader))
            skipText(reader)
        }
        return blocks
    }

    private fun parseBlock(reader: XMLEventReader): IRBlock {
        skipText(reader)
        val start = reader.nextEvent().asStartElement()
        require(start.name.localPart=="BLOCK") { "missing BLOCK" }
        val attrs = start.attributes.asSequence().associate { it.name.localPart to it.value }
        val block = IRBlock(
            attrs.getValue("NAME"),
            if(attrs.getValue("LIBRARY")=="") false else attrs.getValue("LIBRARY").toBoolean(),
            IRBlock.Options(
                if(attrs.getOrDefault("ADDRESS", "")=="") null else parseIRValue(attrs.getValue("ADDRESS")).toUInt(),
                attrs.getOrDefault("FORCEOUTPUT", "false").toBoolean(),
                attrs.getOrDefault("NOPREFIXING", "false").toBoolean(),
                attrs.getOrDefault("VERAFXMULS", "false").toBoolean(),
                attrs.getOrDefault("IGNOREUNUSED", "false").toBoolean()
            ),
            parsePosition(attrs.getValue("POS")))
        skipText(reader)
        while(reader.peek().isStartElement) {
            when(reader.peek().asStartElement().name.localPart) {
                "SUB" -> block += parseSubroutine(reader)
                "ASMSUB" -> block += parseAsmSubroutine(reader)
                "ASM" -> block += parseInlineAssembly(reader)
                "BYTES" -> block += parseBinaryBytes(reader)
                "CODE" -> {
                    val chunk = parseCodeChunk(reader)
                    if(chunk.isNotEmpty() || chunk.label==null)
                        throw IRParseException("code chunk in block should only contain a label name")
                    block += chunk
                }
                else -> throw IRParseException("invalid line in BLOCK: ${reader.peek()}")
            }
            skipText(reader)
        }
        require(reader.nextEvent().isEndElement)
        return block
    }

    private fun parseSubroutine(reader: XMLEventReader): IRSubroutine {
        skipText(reader)
        val start = reader.nextEvent().asStartElement()
        require(start.name.localPart=="SUB") { "missing SUB" }
        val attrs = start.attributes.asSequence().associate { it.name.localPart to it.value }
        val returntype = attrs.getValue("RETURNTYPE")
        skipText(reader)
        val sub = IRSubroutine(attrs.getValue("NAME"),
            parseParameters(reader),
            if(returntype=="") null else parseDatatype(returntype, false),
            parsePosition(attrs.getValue("POS")))

        skipText(reader)
        while(reader.peek().isStartElement) {
            sub += when(reader.peek().asStartElement().name.localPart) {
                "CODE" -> parseCodeChunk(reader)
                "BYTES" -> parseBinaryBytes(reader)
                "ASM" -> parseInlineAssembly(reader)
                else -> throw IRParseException("invalid line in SUB: ${reader.peek()}")
            }
            skipText(reader)
        }

        require(reader.nextEvent().isEndElement)
        return sub
    }

    private fun parseParameters(reader: XMLEventReader): List<IRSubroutine.IRParam> {
        val start = reader.nextEvent().asStartElement()
        require(start.name.localPart=="PARAMS") { "missing PARAMS" }
        val text = readText(reader).trim()
        require(reader.nextEvent().isEndElement)

        return if(text.isBlank())
            emptyList()
        else {
            text.lines().map { line ->
                val (datatype, name) = line.split(' ')
                val dt = parseDatatype(datatype, datatype.contains('['))
                // val orig = variables.single { it.dt==dt && it.name==name}
                IRSubroutine.IRParam(name, dt)
            }
        }
    }

    private fun parseBinaryBytes(reader: XMLEventReader): IRInlineBinaryChunk {
        skipText(reader)
        val start = reader.nextEvent().asStartElement()
        require(start.name.localPart=="BYTES") { "missing BYTES" }
        val label = start.attributes.asSequence().singleOrNull { it.name.localPart == "LABEL" }?.value?.ifBlank { null }
        val text = readText(reader).replace("\n", "")
        require(reader.nextEvent().isEndElement)

        val bytes = text.windowed(2, step = 2).map { it.toUByte(16) }
        return IRInlineBinaryChunk(label, bytes, null)
    }

    private fun parseAsmSubroutine(reader: XMLEventReader): IRAsmSubroutine {
        skipText(reader)
        val start = reader.nextEvent().asStartElement()
        require(start.name.localPart=="ASMSUB") { "missing ASMSUB" }
        val attrs = start.attributes.asSequence().associate { it.name.localPart to it.value }
        val params = parseAsmParameters(reader)
        val assembly = parseInlineAssembly(reader)
        skipText(reader)
        require(reader.nextEvent().isEndElement)

        val clobbers = attrs.getValue("CLOBBERS")
        val clobberRegs = if(clobbers.isBlank()) emptyList() else clobbers.split(',').map { CpuRegister.valueOf(it) }
        val returnsSpec = attrs.getValue("RETURNS")
        val returns = if(returnsSpec.isNullOrBlank()) emptyList() else returnsSpec.split(',').map { rs ->
            val (regstr, dtstr) = rs.split(':')
            val dt = parseDatatype(dtstr, false)
            val regsf = parseRegisterOrStatusflag(regstr)
            IRAsmSubroutine.IRAsmParam(regsf, dt)
        }
        return IRAsmSubroutine(
            attrs.getValue("NAME"),
            if(attrs.getValue("ADDRESS")=="") null else parseIRValue(attrs.getValue("ADDRESS")).toUInt(),
            clobberRegs.toSet(),
            params,
            returns,
            assembly,
            parsePosition(attrs.getValue("POS"))
        )
    }

    private fun parseAsmParameters(reader: XMLEventReader): List<IRAsmSubroutine.IRAsmParam> {
        skipText(reader)
        val start = reader.nextEvent().asStartElement()
        require(start.name.localPart=="ASMPARAMS") { "missing ASMPARAMS" }
        val text = readText(reader).trim()
        require(reader.nextEvent().isEndElement)

        return if(text.isBlank())
            emptyList()
        else {
            text.lines().map { line ->
                val (datatype, regOrSf) = line.split(' ')
                val dt = parseDatatype(datatype, datatype.contains('['))
                val regsf = parseRegisterOrStatusflag(regOrSf)
                IRAsmSubroutine.IRAsmParam(regsf, dt)
            }
        }
    }

    private fun parseInlineAssembly(reader: XMLEventReader): IRInlineAsmChunk {
        skipText(reader)
        val start = reader.nextEvent().asStartElement()
        require(start.name.localPart=="ASM") { "missing ASM" }
        val label = start.attributes.asSequence().single { it.name.localPart == "LABEL" }.value.ifBlank { null }
        val isIr = start.attributes.asSequence().single { it.name.localPart == "IR" }.value.toBoolean()
        val text = readText(reader).trim()
        require(reader.nextEvent().isEndElement)
        return IRInlineAsmChunk(label, text, isIr, null)
    }

    private fun parseDatatype(type: String, isArray: Boolean): DataType {
        if(isArray) {
            return when(type) {
                "bool" -> DataType.ARRAY_BOOL
                "byte" -> DataType.ARRAY_B
                "ubyte", "str" -> DataType.ARRAY_UB
                "word" -> DataType.ARRAY_W
                "uword" -> DataType.ARRAY_UW
                "float" -> DataType.ARRAY_F
                "uword_split" -> DataType.ARRAY_UW_SPLIT
                "word_split" -> DataType.ARRAY_W_SPLIT
                else -> throw IRParseException("invalid dt  $type")
            }
        } else {
            return when(type) {
                "bool" -> DataType.BOOL
                "byte" -> DataType.BYTE
                "ubyte" -> DataType.UBYTE
                "word" -> DataType.WORD
                "uword" -> DataType.UWORD
                "float" -> DataType.FLOAT
                // note: 'str' should not occur anymore in IR. Should be 'uword'
                else -> throw IRParseException("invalid dt  $type")
            }
        }
    }

    private val posPattern = Regex("\\[(.+): line (.+) col (.+)-(.+)\\]")

    private fun parsePosition(strpos: String): Position {
        // example: "[library:/prog8lib/virtual/textio.p8: line 5 col 2-4]"
        val match = posPattern.matchEntire(strpos) ?: throw IRParseException("invalid Position")
        val (file, line, startCol, endCol) = match.destructured
        return Position(file, line.toInt(), startCol.toInt(), endCol.toInt())
    }

    private fun readText(reader: XMLEventReader): String {
        val sb = StringBuilder()
        while(reader.peek().isCharacters) {
            sb.append(reader.nextEvent().asCharacters().data)
        }
        return sb.toString()
    }

    private fun skipText(reader: XMLEventReader) {
        while(reader.peek().isCharacters)
            reader.nextEvent()
    }
}
