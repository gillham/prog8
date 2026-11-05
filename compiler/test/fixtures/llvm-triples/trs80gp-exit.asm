; For running TRS-80 programs in trs80gp with -ee: the exit status ends trs80gp, through function 128 of its emulator
; extensions; without them nothing happens. Spliced into crt0's exit path, with HL the status.

    SECTION code_crt_exit
    EXTERN _prog8_trs80gp
    push hl
    call _prog8_trs80gp
    ld a,l
    pop hl
    or a
    jr z,prog8_no_exit
    ld b,h
    ld c,l
    ld a,128
    out ($47),a
prog8_no_exit:
