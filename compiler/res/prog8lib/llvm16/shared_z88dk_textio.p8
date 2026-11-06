; The llvm16 Text I/O routines that the z88dk library families (z88dk, z88dk-ansi) share: they use z88dk's conio routines.
; Merged into the txt block of the textio of each of those families, which defines plot().

txt {
%option merge, no_symbol_prefixing, ignore_unused

    ; z88dk's conio routines with the smallc calling convention; its fastcall and callee ones can't be called from Prog8
    extsub wherex() -> word
    extsub wherey() -> word
    extsub cvpeek(word x, word y) -> word
    extsub screensize(^^uword w, ^^uword h)

sub width() -> ubyte {
    uword w, h
    screensize(&w, &h)
    return lsb(w)
}

sub height() -> ubyte {
    uword w, h
    screensize(&w, &h)
    return lsb(h)
}

sub home() {
    plot(0, 0)
}

sub column(ubyte col) {
    ; ---- move the cursor to the column (0 is the leftmost one) of the current line
    plot(col, lsb(wherey()))
}
}
