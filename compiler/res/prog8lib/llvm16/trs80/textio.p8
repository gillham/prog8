; Prog8 definitions for the Text I/O console routines for the llvm16 target on the TRS-80 Model I and III profiles
; (z80-trs80, z80-trs80-cmd, z80-trs80-m3-trsdos), with z88dk's generic console: the 64x16 screen at $3c00. Text goes to
; the console through fputc_cons (the profile's putchar_symbol); cursor movement and reverse video use the generic
; console's control codes. The keys are the ROM's, from the profiles' z80-trs80-keyboard.asm.
; txt.print_f is not here: floats are not supported yet on llvm16.

%import shared_z88dk_console_textio

txt {
%option no_symbol_prefixing, ignore_unused

    const ubyte DEFAULT_WIDTH = 64
    const ubyte DEFAULT_HEIGHT = 16

    ; the codes of getkey() and waitkey() for the keys that differ between the machines (a key that a machine lacks gets
    ; one that no key gives: 248 up, 249 down, 250 left, 251 right, 252 escape, 253 backspace, 254 break)
    const ubyte KEY_UP = 91
    const ubyte KEY_DOWN = 10
    const ubyte KEY_LEFT = 8            ; also the backspace of line input
    const ubyte KEY_RIGHT = 9
    const ubyte KEY_ENTER = 13
    const ubyte KEY_ESC = 27            ; shift and the up arrow
    const ubyte KEY_BACKSPACE = 253
    const ubyte KEY_BREAK = 1
}
