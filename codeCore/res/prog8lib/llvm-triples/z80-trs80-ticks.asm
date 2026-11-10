; The z80-trs80-cmd profile's sys.ticks() (ticks_symbol, ticks_source): a count of 60ths of a second, kept by an
; interrupt task of LDOS 5.3 on the Model III in a resident module named P8TICKS. The first program that asks for the
; time installs it below HIGH$ (4411H), where it stays until the next reboot, so no task is ever left pointing into a
; program's freed memory. Task slots 8-10 run 30 times a second, and the task adds 2. LDOS 5.3 has no @GTMOD or
; @CKTSK: the task table at 4500H shows which slots are free (their task is a RET) and whether one has the module's
; task (the module's name ends just before its TCB). The count stays 0 on a Model I, under TRSDOS 1.3 (whose @ADTSK
; vector at 403DH jumps into the ROM), and without a free slot.

    SECTION code_crt_init
    ld a,($0054)            ; 1 on a Model I
    dec a
    jp z,prog8_ticks_done
    ld a,($403d)            ; LDOS's @ADTSK is a jump into the DOS, above 4000H
    cp $c3
    jp nz,prog8_ticks_done
    ld a,($403f)
    cp $40
    jp c,prog8_ticks_done
    ; slots 8-10: an earlier program's module, or else the first free slot (C = its number)
    ld c,$ff
    ld b,8
prog8_ticks_slots:
    ld a,b
    add a,a
    ld l,a
    ld h,$45
    ld e,(hl)
    inc hl
    ld d,(hl)               ; DE = the slot's TCB
    ld a,d
    cp $40
    jp c,prog8_ticks_done   ; not a task table after all
    push bc
    ld hl,prog8_ticks_name-prog8_ticks_tcb
    add hl,de               ; where the module's name would be
    ld de,prog8_ticks_name
    ld b,7
prog8_ticks_compare:
    ld a,(de)
    cp (hl)
    jr nz,prog8_ticks_other
    inc de
    inc hl
    djnz prog8_ticks_compare
    pop bc
    ld de,prog8_ticks_count-prog8_ticks_name-7
    add hl,de
    ld (prog8_ticks_counter),hl
    jr prog8_ticks_done
prog8_ticks_other:
    pop bc
    ld a,c
    inc a
    jr nz,prog8_ticks_next  ; a free slot was found already
    ld a,b
    add a,a
    ld l,a
    ld h,$45
    ld e,(hl)
    inc hl
    ld d,(hl)
    ex de,hl
    ld e,(hl)
    inc hl
    ld d,(hl)               ; DE = the slot's task
    ld a,(de)
    cp $c9
    jr nz,prog8_ticks_next
    ld c,b
prog8_ticks_next:
    inc b
    ld a,b
    cp 11
    jr c,prog8_ticks_slots
    ld a,c
    inc a
    jr z,prog8_ticks_done   ; no free slot
    push bc
    ld hl,($4411)           ; HIGH$
    ld (prog8_ticks_module+2),hl    ; the module's last byte
    ld de,prog8_ticks_end-prog8_ticks_module
    or a
    sbc hl,de
    ld ($4411),hl           ; HIGH$ just below the module
    inc hl                  ; the module's first byte
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
    ld a,c
    call $403d              ; @ADTSK: the TCB at DE in slot A
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

    SECTION data_user
prog8_ticks_counter:
    defw 0

; the module that is copied below HIGH$: a module header as LDOS and LS-DOS have them, the task control block (the
; task's address, which the DOS calls with IX at the TCB, then the count and the step), and the task
prog8_ticks_module:
    jr prog8_ticks_entry
    defw 0                  ; the module's last byte
    defb 7
prog8_ticks_name:
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
