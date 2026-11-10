; The z80-trs80-m4 profile's sys.ticks() (ticks_symbol, ticks_source): a count of 60ths of a second, kept by an interrupt
; task of TRSDOS 6 and LS-DOS 6 in a resident module named P8TICKS. The first program that asks for the time installs
; it below HIGH$, where it stays until the next reboot, and later programs find it through @GTMOD: so no task is ever
; left pointing into a program's freed memory, however the program ends. It goes below $f400, as the DOS pages the
; keyboard and screen into $f400-$ffff while interrupts can come; when HIGH$ is above that, the memory up to HIGH$ stays
; unused until the reboot. Task slot 11 runs 60 times a second and adds 1; else slot 8 or 9, which run 30 times a
; second, add 2. Without a free slot, or when HIGH$ can't be lowered, the count stays 0. Linked before the other
; startup sources, so that the stack goes below the module.

    SECTION code_crt_init
    ld de,prog8_ticks_name
    ld a,83                 ; @GTMOD: HL = the module's entry, Z when found
    rst $28
    jr nz,prog8_ticks_slot11
    ld de,prog8_ticks_count-prog8_ticks_module
    add hl,de
    ld (prog8_ticks_counter),hl
    jr prog8_ticks_done
prog8_ticks_slot11:
    ld bc,$010b             ; B = the step, C = the slot
    ld a,28                 ; @CKTSK: Z when the slot is free
    rst $28
    jr z,prog8_ticks_install
    ld bc,$0208
    ld a,28
    rst $28
    jr z,prog8_ticks_install
    ld c,9
    ld a,28
    rst $28
    jr nz,prog8_ticks_done
prog8_ticks_install:
    ld a,b
    ld (prog8_ticks_step),a
    push bc
    ld b,0
    ld hl,0
    ld a,100                ; @HIGH$, B=0 and HL=0: get it
    rst $28
    ld (prog8_ticks_module+2),hl    ; the module's last byte: it takes up the memory up to HIGH$
    ld a,h
    cp $f4
    jr c,prog8_ticks_below
    ld hl,$f3ff
prog8_ticks_below:
    ld de,prog8_ticks_end-prog8_ticks_module
    or a
    sbc hl,de
    push hl
    ld b,0
    ld a,100                ; @HIGH$, B=0: set it to just below the module
    rst $28
    pop hl
    pop bc
    jr nz,prog8_ticks_done
    inc hl                  ; the module's first byte
    push bc
    push hl
    ld de,prog8_ticks_task-prog8_ticks_module
    add hl,de
    ld (prog8_ticks_tcb),hl
    pop de
    push de
    ld hl,prog8_ticks_module
    ld bc,prog8_ticks_end-prog8_ticks_module
    ldir
    pop hl
    ld de,prog8_ticks_count-prog8_ticks_module
    add hl,de
    ld (prog8_ticks_counter),hl
    ld de,prog8_ticks_tcb-prog8_ticks_count
    add hl,de
    ex de,hl
    pop bc
    ld a,29                 ; @ADTSK: the TCB at DE in slot C
    rst $28
prog8_ticks_done:

    SECTION code_user
    PUBLIC _prog8_trs80_ticks

; unsigned int prog8_trs80_ticks(void): the count, or 0 without the module
_prog8_trs80_ticks:
    ld hl,(prog8_ticks_counter)
    ld a,h
    or l
    ret z
    di
    ld a,(hl)
    inc hl
    ld h,(hl)
    ld l,a
    ei
    ret

prog8_ticks_name:
    defm "P8TICKS"
    defb 3

    SECTION data_user
prog8_ticks_counter:
    defw 0

; the module that is copied below HIGH$: the DOS's module header, the task control block (the task's address, which
; the DOS calls with IX at the TCB, then the count and the step), and the task
prog8_ticks_module:
    jr prog8_ticks_entry
    defw 0                  ; the module's last byte
    defb 7
    defm "P8TICKS"
    defw 0                  ; no device control block
    defw 0
prog8_ticks_tcb:
    defw 0
prog8_ticks_count:
    defw 0
prog8_ticks_step:
    defb 2
prog8_ticks_task:
    ld a,(ix+4)
    add a,(ix+2)
    ld (ix+2),a
    ret nc
    inc (ix+3)
prog8_ticks_entry:
    ret
prog8_ticks_end:
