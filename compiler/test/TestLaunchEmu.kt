package prog8tests.compiler

import io.kotest.core.spec.style.FunSpec
import prog8.code.target.VMTarget
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText


class TestLaunchEmu: FunSpec({

    test("test launch virtualmachine via target") {
        val target = VMTarget()
        val tmpfile = kotlin.io.path.createTempFile(suffix=".p8ir")
        tmpfile.writeText("""<?xml version="1.0" encoding="utf-8"?>
<PROGRAM NAME="test" COMPILERVERSION="99.99">
<OPTIONS>
</OPTIONS>

<ASMSYMBOLS>
</ASMSYMBOLS>

<VARIABLESNOINIT>
</VARIABLESNOINIT>
<VARIABLESNOINITDIRTY>
</VARIABLESNOINITDIRTY>
<VARIABLESWITHINIT>
</VARIABLESWITHINIT>
<STRUCTINSTANCESNOINIT>
</STRUCTINSTANCESNOINIT>
<STRUCTINSTANCES>
</STRUCTINSTANCES>

<CONSTANTS>
</CONSTANTS>

<MEMORYMAPPEDVARIABLES>
</MEMORYMAPPEDVARIABLES>

<MEMORYSLABS>
</MEMORYSLABS>

<INITGLOBALS>
</INITGLOBALS>

<BLOCK NAME="main" ADDRESS="" LIBRARY="false" FORCEOUTPUT="false" ALIGN="NONE" POS="[unittest: line 42 col 1-9]">
</BLOCK>
</PROGRAM>
""")
        target.launchEmulator(0, tmpfile, true)
        tmpfile.deleteExisting()
    }
})
