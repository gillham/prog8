; For running CP/M programs in tnylpo, whose exit status is always 0: the program's exit status is printed as a last
; line PROG8-EXIT=<4 hex digits>, which the runner removes and turns into its own exit status.
; The code is spliced into crt0's exit path after crt0's own exit code, which changes HL, so the status is read where
; crt0 saved it.

    SECTION code_crt_exit
    EXTERN __cpm_base_address
    ld hl,(__cpm_base_address+$80)
    push hl
    ld de,prog8_exit_text
    ld c,9
    call 5
    pop hl
    push hl
    ld a,h
    call prog8_hex2
    pop hl
    push hl
    ld a,l
    call prog8_hex2
    ld de,prog8_exit_end
    ld c,9
    call 5
    pop hl

    SECTION code_user
prog8_hex2:
    push af
    rrca
    rrca
    rrca
    rrca
    call prog8_hex1
    pop af
prog8_hex1:
    and 15
    add a,'0'
    cp '9'+1
    jr c,prog8_digit
    add a,7
prog8_digit:
    ld e,a
    ld c,2
    jp 5

    SECTION rodata_user
prog8_exit_text:
    defb 13,10
    defm "PROG8-EXIT=$"
prog8_exit_end:
    defb 13,10,'$'
