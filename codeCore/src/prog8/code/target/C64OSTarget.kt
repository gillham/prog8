package prog8.code.target

import prog8.code.core.Encoding
import prog8.code.core.ICompilationTarget
import prog8.code.core.IMemSizer
import prog8.code.core.IStringEncoding
import prog8.code.target.c64os.C64OSMachineDefinition
import prog8.code.target.cbm.CbmMemorySizer


class C64OSTarget: ICompilationTarget, IStringEncoding by Encoder, IMemSizer by CbmMemorySizer {
    override val name = NAME
    override val machine = C64OSMachineDefinition()
    override val defaultEncoding = Encoding.C64OS

    companion object {
        const val NAME = "c64os"

        fun viceMonListName(baseFilename: String) = "$baseFilename.vice-mon-list"
    }
}

