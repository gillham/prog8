; The llvm16 Text I/O routines that use ANSI escape sequences and fit every ANSI console: the one of a terminal on stdout,
; and z88dk's ANSI console on the C128. Merged into the txt block of the llvm16 textio and of the z88dk-ansi textio.

txt {
%option merge, no_symbol_prefixing, ignore_unused

sub  clear_screen() {
    print("\x1b[2J\x1b[H")
}

sub rvs_on() {
    print("\x1b[7m")
}

sub color (ubyte txtcol) {
    ; ---- set the text color, one of the 8 ANSI colors 0-7
    print("\x1b[3")
    chrout('0' + txtcol)
    chrout('m')
}

sub bell() {
    chrout(7)
}
}
