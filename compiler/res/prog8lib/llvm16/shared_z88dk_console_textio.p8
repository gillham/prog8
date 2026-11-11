; The llvm16 Text I/O console routines of the z88dk platforms with its generic console, such as the TRS-80 and the ZX
; Spectrum, which the z88dk and trs80 textios share. Text goes to the console through fputc_cons (the profile's
; putchar_symbol); cursor movement, colors and reverse video use the generic console's control codes, and work where the
; platform supports them. Merged into the txt block of the textio of each of those families, which has the constants.

%import shared_llvm16_textio
%import shared_llvm16_putchar_textio
%import shared_llvm16_echo_input_textio
%import shared_z88dk_textio

txt {
%option merge, no_symbol_prefixing, ignore_unused

    ubyte ink_color = 255       ; the one of color(), which setclr() and setcc() put back; 255 before color() is called

sub  clear_screen() {
    chrout(12)
}

sub rvs_on() {
    chrout(27)
    chrout('p')
}

sub rvs_off() {
    chrout(27)
    chrout('q')
}

sub color (ubyte txtcol) {
    ; ---- set the ink color, where the platform has colors (the ZX Spectrum: 0-7)
    ink_color = txtcol
    chrout(16)
    chrout(32 + txtcol)
}

sub bell() {
    ; the generic console has no bell
}

sub  plot  (ubyte col, ubyte row) {
    ; ---- move the cursor to the position, 0,0 is the top left; a position off the screen is ignored
    chrout(22)
    chrout(row + 32)
    chrout(col + 32)
}

sub setchr (ubyte col, ubyte row, ubyte char) {
    ; ---- put the character on the screen at the position; the cursor stays where it was
    ubyte x = lsb(wherex())
    ubyte y = lsb(wherey())
    plot(col, row)
    if char < 32 {
        chrout(27)
        chrout('r')     ; prints a control code as a character
    }
    chrout(char)
    plot(x, y)
}

sub getchr (ubyte col, ubyte row) -> ubyte {
    ; ---- the character on the screen at the position (z88dk's cvpeek)
    return lsb(cvpeek(col as word, row as word))
}

sub setclr (ubyte col, ubyte row, ubyte color) {
    ; ---- color the character at the position: rewrites it, read back with getchr(), in that ink.
    ;      The cursor and the ink of color() stay as they were.
    ubyte x = lsb(wherex())
    ubyte y = lsb(wherey())
    ubyte char = getchr(col, row)
    plot(col, row)
    chrout(16)
    chrout(32 + color)
    if char < 32 {
        chrout(27)
        chrout('r')     ; prints a control code as a character
    }
    chrout(char)
    if ink_color != 255 {
        chrout(16)
        chrout(32 + ink_color)
    }
    plot(x, y)
}

sub setcc(ubyte col, ubyte row, ubyte char, ubyte color) {
    ; ---- put the character on the screen at the position, in that ink; the cursor and the ink of color() stay as they were
    ubyte x = lsb(wherex())
    ubyte y = lsb(wherey())
    plot(col, row)
    chrout(16)
    chrout(32 + color)
    if char < 32 {
        chrout(27)
        chrout('r')     ; prints a control code as a character
    }
    chrout(char)
    if ink_color != 255 {
        chrout(16)
        chrout(32 + ink_color)
    }
    plot(x, y)
}

sub clear_screenchars(ubyte character) {
    ; ---- fill the screen with the character, and put the cursor at the top left
    plot(0, 0)
    uword count = width() as uword * height()
    repeat count {
        if character < 32 {
            chrout(27)
            chrout('r')
        }
        chrout(character)
    }
    plot(0, 0)
}

sub fill_screen(ubyte character, ubyte color) {
    ; ---- fill the screen with the character in that ink, which stays set, and put the cursor at the top left
    txt.color(color)
    clear_screenchars(character)
}
}
