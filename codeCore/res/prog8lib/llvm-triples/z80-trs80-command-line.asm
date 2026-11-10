; The TRS-80 profiles' command line, for z88dk's crt0, which takes the one the DOS points HL to at the start: each profile
; has it among its startup sources.

    SECTION code_crt_init
    EXTERN __head
    ; crt0 keeps HL at the start, the command line, in the operand of an ld hl,nn, and its first instruction (at __head)
    ; is ld (nn),hl with that operand's address. crt0 counts the carriage return as part of the line, so it parses a byte or
    ; two before the line: from the DOS, the end of the program name, which became argument 1. The line is copied after
    ; zeros, with one blank before it as the DOS has it. trs80gp starts a program that it loads with HL at a 13 of the
    ; DOS's, which crt0 would overwrite with 0 (ENTER then no longer works): anything but printable characters up to a
    ; carriage return within 80 is no command line.
    ld a,(__head)
    cp $22
    jr nz,prog8_trs80_args_done
    ld hl,(__head+1)
    push hl
    ld a,(hl)
    inc hl
    ld h,(hl)
    ld l,a
    ld de,prog8_trs80_line+3
    ld b,80
prog8_trs80_blanks:
    ld a,(hl)
    cp ' '
    jr nz,prog8_trs80_copy
    inc hl
    djnz prog8_trs80_blanks
    jr prog8_trs80_bad_line
prog8_trs80_copy:
    ld a,(hl)
    cp 13
    jr z,prog8_trs80_copied
    cp ' '
    jr c,prog8_trs80_bad_line
    cp 127
    jr nc,prog8_trs80_bad_line
    ld (de),a
    inc hl
    inc de
    djnz prog8_trs80_copy
prog8_trs80_bad_line:
    ld de,prog8_trs80_line+3
prog8_trs80_copied:
    ld a,13
    ld (de),a
    pop hl
    ld de,prog8_trs80_line+2
    ld a,(prog8_trs80_line+3)
    cp 13
    jr nz,prog8_trs80_store
    inc de
prog8_trs80_store:
    ld (hl),e
    inc hl
    ld (hl),d
prog8_trs80_args_done:

    SECTION data_user
prog8_trs80_line:
    defb 0,0,' '
    defs 81
