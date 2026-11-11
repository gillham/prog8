; Prog8 definitions for the Text I/O console routines for the llvm16 target.
; Text goes to stdout and input comes from stdin; colors, reverse video and cursor movement use ANSI escape sequences.
; txt.print_f is not here: floats are not supported yet on llvm16.

%import shared_llvm16_stream_textio

txt {
%option no_symbol_prefixing, ignore_unused

    ; the codes of getkey() and waitkey() for the keys that differ between the machines (a key that a machine lacks gets
    ; one that no key gives: 248 up, 249 down, 250 left, 251 right, 252 escape, 253 backspace, 254 break)
    const ubyte KEY_UP = 248
    const ubyte KEY_DOWN = 249
    const ubyte KEY_LEFT = 250
    const ubyte KEY_RIGHT = 251
    const ubyte KEY_ENTER = 10          ; the line end of stdin
    const ubyte KEY_ESC = 27
    const ubyte KEY_BACKSPACE = 8
    const ubyte KEY_BREAK = 254

sub getkey() -> ubyte {
    ; ---- the key pressed, or 0 when there is none: always 0 here, as the C libraries of these profiles can't poll stdin
    return 0
}

sub waitkey() -> ubyte {
    ; ---- wait for the next key and return it: chrin(), so 0 at the end of the input
    return chrin()
}
}
