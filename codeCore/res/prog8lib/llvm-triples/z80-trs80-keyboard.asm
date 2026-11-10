; The TRS-80 Model I/III profiles' getk and fgetc_cons, which the link command redirects z88dk's to: they give the ROM's
; key codes, where z88dk's library, built with STANDARDESCAPECHARS, turns ENTER (13) into 10, the code of the down arrow.
; Letters come in lower case unshifted and as capitals shifted, on both models: the Model III ROM's caps lock ($4019) is
; off during the scan (and put back after it), and the Model I's capitals are swapped; trs80.caps_lock(true) swaps them
; again. Also the console output that the link command redirects fputc_cons to, which shows lower case letters as
; capitals after trs80.capitals(true), for a Model I without the lower case kit.

    SECTION code_user
    PUBLIC _prog8_trs80_getk
    PUBLIC _prog8_trs80_fgetc_cons
    PUBLIC _prog8_trs80_console
    PUBLIC _prog8_trs80_set_caps_lock
    PUBLIC _prog8_trs80_set_capitals
    EXTERN fputc_cons_generic

; the key pressed since the last call, or 0 (the ROM's keyboard scan)
_prog8_trs80_getk:
    call prog8_trs80_scan
    ld l,a
    ld h,0
    ret

; waits for a key
_prog8_trs80_fgetc_cons:
    call prog8_trs80_scan
    and a
    jr z,_prog8_trs80_fgetc_cons
    ld l,a
    ld h,0
    ret

; A = the ROM's key, or 0, with the case of the letters as described above
prog8_trs80_scan:
    ld a,($0054)            ; 1 on a Model I, whose ROM gives capitals unshifted
    dec a
    ld a,(prog8_trs80_caps_lock)
    jr nz,prog8_trs80_scan3
    xor 1
    push af
    call $002b
    jr prog8_trs80_case
prog8_trs80_scan3:
    push af
    ld hl,$4019
    ld b,(hl)
    ld (hl),0
    push hl
    push bc
    call $002b
    pop bc
    pop hl
    ld (hl),b
prog8_trs80_case:
    pop bc                  ; B = 1: swap the case of a letter
    ld c,a
    and $df
    cp 'A'
    jr c,prog8_trs80_keep
    cp 'Z'+1
    jr nc,prog8_trs80_keep
    bit 0,b
    jr z,prog8_trs80_keep
    ld a,c
    xor $20
    ret
prog8_trs80_keep:
    ld a,c
    ret

; int fputc_cons(int c): z88dk's generic console, with lower case letters as capitals after trs80.capitals(true)
_prog8_trs80_console:
    ld a,(prog8_trs80_capitals)
    and a
    jp z,fputc_cons_generic
    ld hl,2
    add hl,sp
    ld a,(hl)
    cp 'a'
    jp c,fputc_cons_generic
    cp 'z'+1
    jp nc,fputc_cons_generic
    sub 32
    ld (hl),a
    jp fputc_cons_generic

; void prog8_trs80_set_caps_lock(int on) and prog8_trs80_set_capitals(int on), for the trs80 module
_prog8_trs80_set_caps_lock:
    ld hl,2
    add hl,sp
    ld a,(hl)
    ld (prog8_trs80_caps_lock),a
    ret
_prog8_trs80_set_capitals:
    ld hl,2
    add hl,sp
    ld a,(hl)
    ld (prog8_trs80_capitals),a
    ret

    SECTION bss_user
prog8_trs80_caps_lock:
    defs 1
prog8_trs80_capitals:
    defs 1
