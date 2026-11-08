; The TRS-80 profiles' getk and fgetc_cons, which the link command redirects z88dk's to: they give the ROM's key codes,
; where z88dk's library, built with STANDARDESCAPECHARS, turns ENTER (13) into 10, the code of the down arrow.

    SECTION code_user
    PUBLIC _prog8_trs80_getk
    PUBLIC _prog8_trs80_fgetc_cons

; the key pressed since the last call, or 0 (the ROM's keyboard scan)
_prog8_trs80_getk:
    call $002b
    ld l,a
    ld h,0
    ret

; waits for a key
_prog8_trs80_fgetc_cons:
    call $002b
    and a
    jr z,_prog8_trs80_fgetc_cons
    ld l,a
    ld h,0
    ret
