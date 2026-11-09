; Prog8 definitions for the Text I/O console routines for the llvm16 target.
; Text goes to stdout and input comes from stdin; colors, reverse video and cursor movement use ANSI escape sequences.
; txt.print_f is not here: floats are not supported yet on llvm16.

%import shared_llvm16_stream_textio

txt {
%option no_symbol_prefixing, ignore_unused

sub getkey() -> ubyte {
    ; ---- the key pressed, or 0 when there is none: always 0 here, as the C libraries of these profiles can't poll stdin
    return 0
}

sub waitkey() -> ubyte {
    ; ---- wait for the next key and return it: chrin(), so 0 at the end of the input
    return chrin()
}
}
