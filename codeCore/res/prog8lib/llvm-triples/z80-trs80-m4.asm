; The z80-trs80-m4 profile's stack, console and keyboard, through the supervisor calls (SVCs, RST 28H with the number in
; A) of TRSDOS 6 and LS-DOS 6 on the TRS-80 Model 4: z88dk has no Model 4 target, and its TRS-80 console writes the
; Model I/III screen at $3c00. The link command redirects z88dk's fputc_cons, getk and fgetc_cons to these, and the
; trs80m4 textio (prog8lib/llvm16/trs80m4/textio.p8) calls the screen routines, which take words (smallc convention).

    ; the DOS starts a program with its stack in low memory: move it to HIGH$, the top of the memory free for programs, but
    ; below $f400, as the display and keyboard routines page the keyboard and screen into $f400-$ffff while they run.
    ; This runs inside crt0_init, before the heap is set up below the stack, so its return address moves along.
    SECTION code_crt_init
    ld b,0
    ld hl,0
    ld a,100                ; @HIGH$, B=0: get it
    rst $28
    ld a,h
    cp $f4
    jr c,prog8_trs80m4_stack
    ld hl,$f3ff
prog8_trs80m4_stack:
    inc hl
    pop de
    ld sp,hl
    push de

    SECTION code_user
    PUBLIC _prog8_trs80m4_fputc
    PUBLIC _prog8_trs80m4_getk
    PUBLIC _prog8_trs80m4_fgetc_cons
    PUBLIC _prog8_trs80m4_plot
    PUBLIC _prog8_trs80m4_where
    PUBLIC _prog8_trs80m4_getchr
    PUBLIC _prog8_trs80m4_setchr

; int fputc_cons(int c): the character through @DSP, which knows 8, 10, 13, 16/17 (reverse video on/off) and 28-31;
; 12, which clears the screen on z88dk's consoles, becomes home (28) and erase to the end of the screen (31)
_prog8_trs80m4_fputc:
    ld hl,2
    add hl,sp
    ld a,(hl)
    cp 12
    jr nz,prog8_trs80m4_dsp
    ld c,28
    ld a,2                  ; @DSP
    rst $28
    ld a,31
prog8_trs80m4_dsp:
    ld c,a
    ld a,2                  ; @DSP
    rst $28
    ld hl,2
    add hl,sp
    ld l,(hl)
    ld h,0
    ret

; the key pressed since the last call, or 0 (@KBD)
_prog8_trs80m4_getk:
    ld a,8                  ; @KBD: Z and the key, or NZ when there is none
    rst $28
    jr z,prog8_trs80m4_key
    xor a
prog8_trs80m4_key:
    ld l,a
    ld h,0
    ret

; waits for a key (@KEY)
_prog8_trs80m4_fgetc_cons:
    ld a,1                  ; @KEY
    rst $28
    ld l,a
    ld h,0
    ret

; void prog8_trs80m4_plot(int col, int row): moves the cursor (@VDCTL 3)
_prog8_trs80m4_plot:
    call prog8_trs80m4_position
    ld b,3
    ld a,15                 ; @VDCTL
    rst $28
    ret

; int prog8_trs80m4_where(void): the cursor position, the row in the high byte and the column in the low one (@VDCTL 4)
_prog8_trs80m4_where:
    ld b,4
    ld a,15                 ; @VDCTL
    rst $28
    ret

; int prog8_trs80m4_getchr(int col, int row): the character on the screen (@VDCTL 1)
_prog8_trs80m4_getchr:
    call prog8_trs80m4_position
    ld b,1
    ld a,15                 ; @VDCTL
    rst $28
    ld l,a
    ld h,0
    ret

; void prog8_trs80m4_setchr(int col, int row, int char): puts the character on the screen, the cursor stays (@VDCTL 2)
_prog8_trs80m4_setchr:
    ld hl,2
    add hl,sp
    ld c,(hl)
    inc hl
    inc hl
    ld e,(hl)               ; row
    inc hl
    inc hl
    ld l,(hl)               ; col
    ld h,e
    ld b,2
    ld a,15                 ; @VDCTL
    rst $28
    ret

; HL = the row and column of a (col, row) call, as @VDCTL wants them
prog8_trs80m4_position:
    ld hl,4
    add hl,sp
    ld e,(hl)               ; row
    inc hl
    inc hl
    ld l,(hl)               ; col
    ld h,e
    ret
