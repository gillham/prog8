; Prog8 definitions for the TRS-80 Model I and III, for the llvm16 target's z80-trs80, z80-trs80-cmd and
; z80-trs80-m3-trsdos profiles: which machine and DOS the program runs on, whether the screen shows lower case, and the
; case of the letters typed and shown.

%option ignore_unused

trs80 {
%option no_symbol_prefixing, ignore_unused

    const ubyte DOS_NONE = 0        ; ROM BASIC, a .cas program
    const ubyte DOS_TRSDOS = 1      ; TRSDOS 2.3 on the Model I, TRSDOS 1.3 on the Model III
    const ubyte DOS_LDOS = 5        ; LDOS 5
    const ubyte DOS_6 = 6           ; TRSDOS 6 or LS-DOS 6 (the Model 4)

    extsub prog8_trs80_set_caps_lock(word on)
    extsub prog8_trs80_set_capitals(word on)

    sub model() -> ubyte {
        ; -- 1 on a Model I, 3 on a Model III (also a Model 4 in its Model III mode), from the ROM ($0054 is 1 on a Model I)
        if @($0054) == 1
            return 1
        return 3
    }

    sub dos() -> ubyte {
        ; -- the DOS the program runs under: DOS_NONE, DOS_TRSDOS or DOS_LDOS
        if model() == 1 {
            if @($4410) != $c3
                return DOS_NONE
            ; LDOS keeps its name in its resident part ("LDOS Ready")
            uword address
            for address in $4200 to $43fc {
                if @(address) == 'L' and @(address + 1) == 'D' and @(address + 2) == 'O' and @(address + 3) == 'S'
                    return DOS_LDOS
            }
            return DOS_TRSDOS
        }
        ; the Model III: LDOS's @ADTSK vector at $403d jumps into the DOS, TRSDOS 1.3's into the ROM; without a DOS,
        ; HIGH$ ($4411) is 0
        if @($403d) == $c3 and @($403f) >= $40
            return DOS_LDOS
        if peekw($4411) != 0
            return DOS_TRSDOS
        return DOS_NONE
    }

    sub has_lowercase() -> bool {
        ; -- true when the screen shows lower case letters: a Model III, or a Model I with the lower case kit. Writes a
        ;    letter to the top left of the video RAM, reads it back, and puts the character there back.
        ubyte saved = @($3c00)
        @($3c00) = 'a'
        bool lower = @($3c00) == 'a'
        @($3c00) = saved
        return lower
    }

    sub caps_lock(bool on) {
        ; -- true: letters typed come as capitals unshifted (and lower case shifted), as with caps lock;
        ;    false (the default): lower case unshifted and capitals shifted, on both models
        prog8_trs80_set_caps_lock(on as word)
    }

    sub capitals(bool on) {
        ; -- true: txt.chrout and txt.print show lower case letters as capitals, for a Model I without the lower case
        ;    kit (see has_lowercase()); false (the default): letters are shown as they are
        prog8_trs80_set_capitals(on as word)
    }
}
