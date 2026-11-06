; The llvm16 Text I/O routines that the z88dk library families (z88dk, z88dk-ansi) share: they use z88dk's conio routines.
; Merged into the txt block of the textio of each of those families, which defines plot().

txt {
%option merge, no_symbol_prefixing, ignore_unused

    ; z88dk's conio routines with the smallc calling convention; its fastcall and callee ones can't be called from Prog8
    extsub wherex() -> word
    extsub wherey() -> word
    extsub cvpeek(word x, word y) -> word
    extsub screensize(^^uword w, ^^uword h)
    extsub getk() -> word

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

sub getkey() -> ubyte {
    ; ---- the key pressed, or 0 when there is none, without waiting (z88dk's getk)
    return lsb(getk())
}

sub waitkey() -> ubyte {
    ; ---- wait for a key and return it: chrin(), which is z88dk's fgetc_cons on these profiles
    return chrin()
}

sub column(ubyte col) {
    ; ---- move the cursor to the column (0 is the leftmost one) of the current line
    plot(col, lsb(wherey()))
}
}
