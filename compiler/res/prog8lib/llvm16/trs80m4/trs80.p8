; Prog8 definitions for the TRS-80 Model 4, for the llvm16 target's z80-trs80-m4 profile: the same routines as the
; trs80 module of the Model I and III profiles, so that a program can ask them on every TRS-80.

%option ignore_unused

trs80 {
%option no_symbol_prefixing, ignore_unused

    const ubyte DOS_NONE = 0        ; ROM BASIC, a .cas program
    const ubyte DOS_TRSDOS = 1      ; TRSDOS 2.3 on the Model I, TRSDOS 1.3 on the Model III
    const ubyte DOS_LDOS = 5        ; LDOS 5
    const ubyte DOS_6 = 6           ; TRSDOS 6 or LS-DOS 6 (the Model 4)

    sub model() -> ubyte {
        ; -- 4: the z80-trs80-m4 profile's programs run on a Model 4 (or 4P) in its Model 4 mode
        return 4
    }

    sub dos() -> ubyte {
        ; -- DOS_6: TRSDOS 6 or LS-DOS 6
        return DOS_6
    }

    sub has_lowercase() -> bool {
        ; -- true: the Model 4 shows lower case
        return true
    }

    sub caps_lock(bool on) {
        ; -- does nothing: the DOS's keyboard driver has its own caps lock (shift and 0)
    }

    sub capitals(bool on) {
        ; -- does nothing: the Model 4 shows lower case
    }
}
