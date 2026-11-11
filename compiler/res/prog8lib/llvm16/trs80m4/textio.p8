; Prog8 definitions for the Text I/O console routines for the llvm16 target on the z80-trs80-m4 profile: the TRS-80 Model 4's
; 80x24 screen, through the supervisor calls of TRSDOS 6 and LS-DOS 6 (@DSP for the text, @VDCTL for the cursor and the
; characters on the screen), in the profile's support source z80-trs80-m4.asm. The Model 4 has no colors.
; txt.print_f is not here: floats are not supported yet on llvm16.

%import shared_llvm16_textio
%import shared_llvm16_putchar_textio
%import shared_llvm16_echo_input_textio

txt {
%option no_symbol_prefixing, ignore_unused

    const ubyte DEFAULT_WIDTH = 80
    const ubyte DEFAULT_HEIGHT = 24
    const ubyte COLUMNS = DEFAULT_WIDTH
    const ubyte ROWS = DEFAULT_HEIGHT

    ; the codes of getkey() and waitkey() for the keys that differ between the machines (a key that a machine lacks gets
    ; one that no key gives: 248 up, 249 down, 250 left, 251 right, 252 escape, 253 backspace, 254 break)
    const ubyte KEY_UP = 11
    const ubyte KEY_DOWN = 10
    const ubyte KEY_LEFT = 8            ; also the backspace of line input
    const ubyte KEY_RIGHT = 9
    const ubyte KEY_ENTER = 13
    const ubyte KEY_ESC = 27            ; shift and the up arrow
    const ubyte KEY_BACKSPACE = 253
    const ubyte KEY_BREAK = 128

    extsub getk() -> word
    extsub prog8_trs80m4_plot(word col, word row)
    extsub prog8_trs80m4_where() -> uword
    extsub prog8_trs80m4_getchr(word col, word row) -> word
    extsub prog8_trs80m4_setchr(word col, word row, word char)
    extsub prog8_trs80m4_bell()

sub getkey() -> ubyte {
    ; ---- the key pressed, or 0 when there is none, without waiting (@KBD)
    return lsb(getk())
}

sub waitkey() -> ubyte {
    ; ---- wait for a key and return it (@KEY); ENTER is 13
    return chrin()
}

sub width() -> ubyte {
    return COLUMNS
}

sub height() -> ubyte {
    return ROWS
}

sub  clear_screen() {
    chrout(12)
}

sub home() {
    chrout(28)
}

sub column(ubyte col) {
    ; ---- move the cursor to the column (0 is the leftmost one) of the current line
    plot(col, msb(prog8_trs80m4_where()))
}

sub  plot  (ubyte col, ubyte row) {
    ; ---- move the cursor to the position, 0,0 is the top left; a position off the screen is ignored
    if col < COLUMNS and row < ROWS
        prog8_trs80m4_plot(col as word, row as word)
}

sub get_cursor() -> ubyte, ubyte {
    ; ---- the cursor's column and row
    uword position = prog8_trs80m4_where()
    return lsb(position), msb(position)
}

sub cursor_on() {
    ; ---- show the cursor (display code 14)
    chrout(14)
}

sub cursor_off() {
    ; ---- hide the cursor (display code 15)
    chrout(15)
}

sub rvs_on() {
    ; ---- reverse video for the text printed from now on (the characters get bit 7 set, see getchr())
    chrout(16)
}

sub rvs_off() {
    chrout(17)
}

sub color (ubyte txtcol) {
    ; the Model 4 has no colors
}

sub bell() {
    ; ---- a short beep (@SOUND)
    prog8_trs80m4_bell()
}

sub setchr (ubyte col, ubyte row, ubyte char) {
    ; ---- put the character on the screen at the position; the cursor stays where it was. 128-255 show reverse video.
    if col < COLUMNS and row < ROWS
        prog8_trs80m4_setchr(col as word, row as word, char as word)
}

sub getchr (ubyte col, ubyte row) -> ubyte {
    ; ---- the character on the screen at the position, with bit 7 set when it is in reverse video; 255 off the screen
    if col >= COLUMNS or row >= ROWS
        return 255
    return lsb(prog8_trs80m4_getchr(col as word, row as word))
}

sub setclr (ubyte col, ubyte row, ubyte color) {
    ; the Model 4 has no colors
}

sub setcc(ubyte col, ubyte row, ubyte char, ubyte color) {
    ; ---- setchr(), as there are no colors
    setchr(col, row, char)
}

sub clear_screenchars(ubyte character) {
    ; ---- fill the screen with the character, and put the cursor at the top left
    ubyte row
    ubyte col
    for row in 0 to ROWS-1 {
        for col in 0 to COLUMNS-1
            setchr(col, row, character)
    }
    home()
}

sub fill_screen(ubyte character, ubyte color) {
    ; ---- fill the screen with the character (there are no colors)
    clear_screenchars(character)
}
}
