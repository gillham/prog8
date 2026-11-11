; Prog8 definitions for the Text I/O console routines for the llvm16 target on the mos-c64 profile: the C64's 40x25 text
; screen at $0400, with the lower case character set that the llvm-mos SDK switches on. Text goes through the SDK's
; putchar, which turns ASCII into PETSCII for the KERNAL's CHROUT (128-255 go to CHROUT as they are); control codes go to
; CHROUT directly. Screen characters and colors are read and written in screen and color memory.
; txt.print_f is not here: floats are not supported yet on llvm16.

%import shared_llvm16_textio
%import shared_llvm16_putchar_textio
%import shared_llvm16_input_textio

txt {
%option no_symbol_prefixing, ignore_unused

    const uword SCREEN = $0400
    const uword COLORS = $d800
    const ubyte DEFAULT_WIDTH = 40
    const ubyte DEFAULT_HEIGHT = 25
    const ubyte COLUMNS = DEFAULT_WIDTH
    const ubyte ROWS = DEFAULT_HEIGHT

    ; the codes of getkey() and waitkey() for the keys that differ between the machines (a key that a machine lacks gets
    ; one that no key gives: 248 up, 249 down, 250 left, 251 right, 252 escape, 253 backspace, 254 break)
    const ubyte KEY_UP = 145
    const ubyte KEY_DOWN = 17
    const ubyte KEY_LEFT = 157
    const ubyte KEY_RIGHT = 29
    const ubyte KEY_ENTER = 13          ; RETURN
    const ubyte KEY_ESC = 252
    const ubyte KEY_BACKSPACE = 20      ; DEL
    const ubyte KEY_BREAK = 3           ; RUN/STOP

    extsub cbm_k_getin() -> ubyte
    extsub cbm_k_chrout(ubyte char)
    extsub prog8_c64_plot(ubyte col, ubyte row)     ; the KERNAL's PLOT, in the profile's support source

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

sub width() -> ubyte {
    return COLUMNS
}

sub height() -> ubyte {
    return ROWS
}

sub  clear_screen() {
    cbm_k_chrout(147)
}

sub home() {
    cbm_k_chrout(19)
}

sub column(ubyte col) {
    ; ---- move the cursor to the column (0 is the leftmost one) of the current line
    plot(col, @($d6))
}

sub  plot  (ubyte col, ubyte row) {
    ; ---- move the cursor to the position, 0,0 is the top left; a position off the screen is ignored
    if col < COLUMNS and row < ROWS
        prog8_c64_plot(col, row)
}

sub rvs_on() {
    cbm_k_chrout(18)
}

sub rvs_off() {
    cbm_k_chrout(146)
}

sub color (ubyte txtcol) {
    ; ---- the color (0-15) of the text printed from now on
    @($0286) = txtcol & 15
}

sub bell() {
    ; the C64 has no bell
}

sub screencode(ubyte char) -> ubyte {
    ; ---- the screen code that print() shows the character as: ASCII 32-127 with the lower case character set;
    ;      0-31 and 128-255 are screen codes already (128-255 are reverse video)
    if char < 64 or char >= 128 {
        return char
    }
    if char == 64
        return 0
    if char < 91
        return char
    if char < 96
        return char - 64
    if char == 96
        return 64
    if char < 123
        return char - 96
    return char - 32
}

sub setchr (ubyte col, ubyte row, ubyte char) {
    ; ---- put the character on the screen at the position, as print() would show it (see screencode()).
    ;      The cursor stays where it was.
    if col < COLUMNS and row < ROWS
        @(SCREEN + row * (COLUMNS as uword) + col) = screencode(char)
}

sub getchr (ubyte col, ubyte row) -> ubyte {
    ; ---- the character on the screen at the position: the ASCII character for screen codes 0-95, which setchr()
    ;      puts back as it was, and the screen code itself for the others (graphics and reverse video); 255 off the screen.
    if col >= COLUMNS or row >= ROWS
        return 255
    ubyte code = @(SCREEN + row * (COLUMNS as uword) + col)
    if code >= 96
        return code
    if code == 0
        return 64
    if code < 27
        return code + 96
    if code < 32
        return code + 64
    if code < 64
        return code
    if code == 64
        return 96
    if code < 91
        return code
    return code + 32
}

sub setclr (ubyte col, ubyte row, ubyte color) {
    ; ---- set the color (0-15) of the character at the position
    if col < COLUMNS and row < ROWS
        @(COLORS + row * (COLUMNS as uword) + col) = color
}

sub setcc(ubyte col, ubyte row, ubyte char, ubyte color) {
    ; ---- setchr() and setclr()
    setchr(col, row, char)
    setclr(col, row, color)
}

sub clear_screenchars(ubyte character) {
    ; ---- fill the screen with the character, and put the cursor at the top left
    sys.memset(SCREEN, COLUMNS as uword * ROWS, screencode(character))
    home()
}

sub fill_screen(ubyte character, ubyte color) {
    ; ---- fill the screen with the character in the color, which stays the color of the text printed from then on
    sys.memset(COLORS, COLUMNS as uword * ROWS, color)
    txt.color(color)
    clear_screenchars(character)
}
}
