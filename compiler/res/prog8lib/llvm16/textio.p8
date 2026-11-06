; Prog8 definitions for the Text I/O console routines for the llvm16 target.
; Text goes to stdout and input comes from stdin; colors, reverse video and cursor movement use ANSI escape sequences.
; txt.print_f is not here: floats are not supported yet on llvm16.

%import shared_llvm16_textio
%import shared_llvm16_putchar_textio
%import shared_llvm16_input_textio
%import shared_llvm16_ansi_textio

txt {
%option no_symbol_prefixing, ignore_unused

sub width() -> ubyte {
    return DEFAULT_WIDTH
}

sub height() -> ubyte {
    return DEFAULT_HEIGHT
}

sub home() {
    print("\x1b[H")
}

sub getkey() -> ubyte {
    ; ---- the key pressed, or 0 when there is none: always 0 here, as the C libraries of these profiles can't poll stdin
    return 0
}

sub waitkey() -> ubyte {
    ; ---- wait for the next key and return it: chrin(), so 0 at the end of the input
    return chrin()
}

sub rvs_off() {
    print("\x1b[0m")
}

sub column(ubyte col) {
    ; ---- move the cursor to the column (0 is the leftmost one) of the current line
    chrout(27)
    chrout('[')
    print_ub(col+1)
    chrout(';')
    chrout('G')
}

sub  plot  (ubyte col, ubyte row) {
    ; ---- move the cursor to the position, with an ANSI escape sequence (as on the virtual target, the numbers are not adjusted for its 1-based positions)
    chrout(27)
    chrout('[')
    print_ub(row)
    chrout(';')
    print_ub(col)
    chrout('H')
}

sub setchr (ubyte col, ubyte row, ubyte char) {
    plot(col, row)
    chrout(char)
}

sub setcc(ubyte col, ubyte row, ubyte char, ubyte color) {
    ; move to col/row
    ; don't call plot() as it will be tracking vcol/vrow soon
    chrout(27)
    chrout('[')
    print_ub(row)
    chrout(';')
    print_ub(col)
    chrout('H')
    ; ---- set the text color, one of the 8 ANSI colors 0-7
    print("\x1b[3")
    chrout('0' + color)
    chrout('m')
    chrout(char)
}

sub clear_screenchars(ubyte character) {
    plot(0,0)
    ubyte w,h
    for h in 0 to DEFAULT_HEIGHT-1 {
        for h in 0 to DEFAULT_WIDTH-1 {
            chrout(character)
        }
    }
}

sub fill_screen(ubyte character, ubyte color) {
    ; ---- set the text color, one of the 8 ANSI colors 0-7
    print("\x1b[3")
    chrout('0' + color)
    chrout('m')
    ; fill screen
    plot(0,0)
    ubyte w,h
    for h in 0 to DEFAULT_HEIGHT-1 {
        for h in 0 to DEFAULT_WIDTH-1 {
            chrout(character)
        }
    }
}

}
