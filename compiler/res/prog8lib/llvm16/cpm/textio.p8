; Prog8 definitions for the Text I/O console routines for the llvm16 target on the z80-cpm profile: the stream console
; of the llvm16 textio, with a key poll through BDOS function 6 (direct console I/O, in the profile's startup source).
; txt.print_f is not here: floats are not supported yet on llvm16.

%import shared_llvm16_stream_textio

txt {
%option no_symbol_prefixing, ignore_unused

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
