; Prog8 definitions for the Text I/O console routines for the llvm16 target, on the z88dk platforms with its ANSI console:
; the Commodore 128 in Z80 mode, 40x25 characters at $2000 with the colors at $1000. Text goes to the console through
; fputc_cons (the profile's putchar_symbol); colors and reverse video use ANSI escape sequences, as in the llvm16 textio.
; txt.print_f is not here: floats are not supported yet on llvm16.

%import shared_llvm16_textio
%import shared_llvm16_putchar_textio
%import shared_llvm16_echo_input_textio
%import shared_llvm16_ansi_textio
%import shared_z88dk_textio

txt {
%option no_symbol_prefixing, ignore_unused

    extsub gotoxy(uword x, uword y)

    const uword SCREEN = $2000
    const uword COLORS = $1000
    const ubyte DEFAULT_WIDTH = 40
    const ubyte DEFAULT_HEIGHT = 25
    const ubyte COLUMNS = DEFAULT_WIDTH
    const ubyte ROWS = DEFAULT_HEIGHT

    ; the codes of getkey() and waitkey() for the keys that differ between the machines (a key that a machine lacks gets
    ; one that no key gives: 248 up, 249 down, 250 left, 251 right, 252 escape, 253 backspace, 254 break)
    const ubyte KEY_UP = 248
    const ubyte KEY_DOWN = 249
    const ubyte KEY_LEFT = 250
    const ubyte KEY_RIGHT = 251
    const ubyte KEY_ENTER = 13          ; RETURN
    const ubyte KEY_ESC = 252
    const ubyte KEY_BACKSPACE = 12      ; DEL
    const ubyte KEY_BREAK = 3           ; RUN/STOP

    ubyte[8] c128_colors = [0, 2, 5, 7, 6, 4, 3, 1]     ; those that z88dk's ANSI console shows for the ANSI colors 0-7

sub rvs_off() {
    print("\x1b[27m")       ; 0m would also reset the color
}

sub  plot  (ubyte col, ubyte row) {
    ; ---- move the cursor to the position, 0,0 is the top left
    gotoxy(col, row)
}

sub setchr (ubyte col, ubyte row, ubyte char) {
    ; ---- put the character on the screen at the position, in the current color; the cursor stays where it was
    ubyte x = lsb(wherex())
    ubyte y = lsb(wherey())
    gotoxy(col, row)
    chrout(char)
    gotoxy(x, y)
}

sub getchr (ubyte col, ubyte row) -> ubyte {
    ; ---- the character on the screen at the position, without its reverse video; 255 off the screen.
    ;      Not z88dk's cvpeek: linking it replaces the C128's font with one that lacks the lower case.
    if col >= COLUMNS or row >= ROWS
        return 255
    ubyte char = @(SCREEN + row * (COLUMNS as uword) + col) & 127
    if char < 32
        char += 96      ; the console stores the characters from 96 on as screen codes 0-31
    return char
}

sub setclr (ubyte col, ubyte row, ubyte color) {
    ; ---- color the character at the position, one of the ANSI colors 0-7 as in color(); the cursor and color() stay as they were
    if col < COLUMNS and row < ROWS and color < 8
        @(COLORS + row * (COLUMNS as uword) + col) = c128_colors[color]
}

sub setcc(ubyte col, ubyte row, ubyte char, ubyte color) {
    ; ---- put the character on the screen at the position, in that color; the cursor and color() stay as they were
    setchr(col, row, char)
    setclr(col, row, color)
}

sub clear_screenchars(ubyte character) {
    ; ---- fill the screen with the character, keeping the colors, and put the cursor at the top left
    if character >= 96
        character -= 96
    sys.memset(SCREEN, COLUMNS as uword * ROWS, character)
    plot(0, 0)
}

sub fill_screen(ubyte character, ubyte color) {
    ; ---- fill the screen with the character in that color, which stays set, and put the cursor at the top left
    txt.color(color)
    clear_screenchars(character)
    if color < 8
        sys.memset(COLORS, COLUMNS as uword * ROWS, c128_colors[color])
}
}
