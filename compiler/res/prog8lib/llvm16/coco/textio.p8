; Prog8 definitions for the Text I/O console routines for the llvm16 target, on the Color Computer's 32x16 VDG text screen
; at $0400 (the CoCo 1 and 2, and the CoCo 3 in its 32 column mode). Text goes through Color BASIC's CHROUT (the profile's
; putchar_symbol); the cursor is BASIC's CURPOS, and screen characters are read and written in screen memory.
; The VDG has no lower case: like BASIC, it shows lower case letters as inverse upper case. It has no per-character colors.
; txt.print_f is not here: floats are not supported yet on llvm16.

%import shared_llvm16_textio
%import shared_llvm16_echo_input_textio

txt {
%option no_symbol_prefixing, ignore_unused

    const uword SCREEN = $0400
    const uword CURPOS = $0088      ; Color BASIC's cursor, the screen address where CHROUT prints next
    const ubyte DEFAULT_WIDTH = 32
    const ubyte DEFAULT_HEIGHT = 16
    const ubyte COLUMNS = DEFAULT_WIDTH
    const ubyte ROWS = DEFAULT_HEIGHT

    ; the codes of getkey() and waitkey() for the keys that differ between the machines (a key that a machine lacks gets
    ; one that no key gives: 248 up, 249 down, 250 left, 251 right, 252 escape, 253 backspace, 254 break)
    const ubyte KEY_UP = 94
    const ubyte KEY_DOWN = 10
    const ubyte KEY_LEFT = 8            ; also the backspace of line input
    const ubyte KEY_RIGHT = 9
    const ubyte KEY_ENTER = 13
    const ubyte KEY_ESC = 252
    const ubyte KEY_BACKSPACE = 253
    const ubyte KEY_BREAK = 3

    extsub prog8_coco_getkey() -> uword      ; picolibc's __decb_getc, in the profile's startup source

    ; 128 = print inverse characters, 0 = normal
    ubyte inverse_mode = 0

sub chrout(ubyte char) {
    %ir {{
        loadm.b r99100.b,[txt.chrout.char]
        syscall 2 (r99100.b)
    }}
    ; CHROUT has printed it, and scrolled if needed, just before the cursor; VDG inverse is bit 6 clear
    if inverse_mode != 0 and char >= 32 and char < 128
        @(peekw(CURPOS) - 1) &= $bf
}

sub  print (str text) {
    pointer p = text
    while @(p) != 0 {
        chrout(@(p))
        p++
    }
}

sub getkey() -> ubyte {
    ; ---- the key pressed, or 0 when there is none, without waiting; each key press is reported once.
    ;      chrin() reads the keyboard the same way, so a key goes to whichever of them reads first.
    return lsb(prog8_coco_getkey())
}

sub waitkey() -> ubyte {
    ; ---- wait for a key and return it (Enter gives 13, while chrin() gives it as 10)
    repeat {
        ubyte key = lsb(prog8_coco_getkey())
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
    sys.memset(SCREEN, COLUMNS as uword * ROWS, $60)
    home()
}

sub home() {
    pokew(CURPOS, SCREEN)
}

sub column(ubyte col) {
    ; ---- move the cursor to the column (0 is the leftmost one) of the current line
    if col < COLUMNS
        pokew(CURPOS, (peekw(CURPOS) & $ffe0) + col)
}

sub  plot  (ubyte col, ubyte row) {
    ; ---- move the cursor to the position, 0,0 is the top left; a position off the screen is ignored
    if col < COLUMNS and row < ROWS
        pokew(CURPOS, SCREEN + row * (COLUMNS as uword) + col)
}

sub rvs_on() {
    inverse_mode = 128
}

sub rvs_off() {
    inverse_mode = 0
}

sub color (ubyte txtcol) {
    ; the VDG text screen has no per-character colors
}

sub bell() {
    ; Color BASIC's CHROUT has no bell
}

sub setchr (ubyte col, ubyte row, ubyte char) {
    ; ---- put the character on the screen at the position, not inverse (but lower case shows as inverse upper case);
    ;      0-31 show as 64-95, 128-255 are the VDG's graphics blocks. The cursor stays where it was.
    if col >= COLUMNS or row >= ROWS
        return
    if char < 64
        char += 64
    else if char >= 96 and char < 128
        char -= 96
    @(SCREEN + row * (COLUMNS as uword) + col) = char
}

sub getchr (ubyte col, ubyte row) -> ubyte {
    ; ---- the character on the screen at the position, the one setchr() puts there for 32-255; 255 off the screen.
    ;      Inverse letters (and lower case) give lower case, other inverse characters the normal one.
    if col >= COLUMNS or row >= ROWS
        return 255
    ubyte char = @(SCREEN + row * (COLUMNS as uword) + col)
    if char >= 128 or char >= 64 and char < 96
        return char
    if char >= 96
        return char - 64
    if char < 32
        return char + 96
    return char
}

sub setclr (ubyte col, ubyte row, ubyte color) {
    ; the VDG text screen has no per-character colors
}

sub setcc(ubyte col, ubyte row, ubyte char, ubyte color) {
    ; ---- setchr(), as there are no per-character colors
    setchr(col, row, char)
}

sub clear_screenchars(ubyte character) {
    ; ---- fill the screen with the character, and put the cursor at the top left
    setchr(0, 0, character)
    sys.memset(SCREEN, COLUMNS as uword * ROWS, @(SCREEN))
    home()
}

sub fill_screen(ubyte character, ubyte color) {
    ; ---- clear_screenchars(), as there are no per-character colors
    clear_screenchars(character)
}
}
