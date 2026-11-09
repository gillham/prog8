; The z80-cpm profile's key poll for txt.getkey(): BDOS function 6 with $FF, which gives the key typed or 0 without
; waiting, as z88dk's fgetc_cons reads keys. z88dk's getk() reads the BIOS until no key is left and gives the last one,
; which throws the other typed-ahead keys away (and never ends when the input is a file, as under tnylpo).

    SECTION code_user
    PUBLIC _prog8_cpm_getkey
    EXTERN __bdos

_prog8_cpm_getkey:
    ld c,6
    ld e,$ff
    call __bdos
    ld l,a
    ld h,0
    ret
