; Prog8 definitions for the Text I/O console routines for the llvm16 target on the z80-cpm profile: the stream console
; of the llvm16 textio, with a key poll through BDOS function 6 (direct console I/O, in the profile's startup source).
; txt.print_f is not here: floats are not supported yet on llvm16.

%import shared_llvm16_stream_textio

txt {
%option no_symbol_prefixing, ignore_unused

    ; the codes of getkey() and waitkey() for the keys that differ between the machines (a key that a machine lacks gets
    ; one that no key gives: 248 up, 249 down, 250 left, 251 right, 252 escape, 253 backspace, 254 break)
    const ubyte KEY_UP = 5              ; Ctrl-E, as in WordStar
    const ubyte KEY_DOWN = 24           ; Ctrl-X
    const ubyte KEY_LEFT = 19           ; Ctrl-S
    const ubyte KEY_RIGHT = 4           ; Ctrl-D
    const ubyte KEY_ENTER = 13
    const ubyte KEY_ESC = 27
    const ubyte KEY_BACKSPACE = 8
    const ubyte KEY_BREAK = 3           ; Ctrl-C

    extsub prog8_cpm_getkey() -> uword

sub getkey() -> ubyte {
    ; ---- the key pressed, or 0 when there is none, without waiting (BDOS function 6 with $FF)
    return lsb(prog8_cpm_getkey())
}

sub waitkey() -> ubyte {
    ; ---- wait for the next key and return it: chrin(), z88dk's fgetc_cons, which waits for one key
    return chrin()
}
}
