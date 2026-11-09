; Prog8 definitions for the Text I/O console routines for the llvm16 target on the mos-c64 profile: the stream console
; of the llvm16 textio, with the keyboard read through the KERNAL's GETIN (the llvm-mos SDK's cbm_k_getin).
; txt.print_f is not here: floats are not supported yet on llvm16.

%import shared_llvm16_stream_textio

txt {
%option no_symbol_prefixing, ignore_unused

    extsub cbm_k_getin() -> ubyte

sub getkey() -> ubyte {
    ; ---- the key pressed, or 0 when there is none, without waiting (the KERNAL's GETIN, PETSCII)
    return cbm_k_getin()
}

sub waitkey() -> ubyte {
    ; ---- wait for a key and return it (PETSCII); unlike chrin(), which reads a whole line with the screen editor first
    repeat {
        ubyte key = cbm_k_getin()
        if key != 0
            return key
    }
}
}
