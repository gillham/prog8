; Prog8 definitions for the Text I/O console routines for the llvm16 target, on the z88dk platforms with its generic console,
; such as the ZX Spectrum (the TRS-80 Model I and III have the trs80 textio). Text goes to the console through fputc_cons
; (the profile's putchar_symbol); cursor movement, colors and reverse video use the generic console's control codes, and
; work where the platform supports them.
; txt.print_f is not here: floats are not supported yet on llvm16.

%import shared_z88dk_console_textio

txt {
%option no_symbol_prefixing, ignore_unused

    ; generic "terminal" dimensions: the screens of the z88dk machines differ, width() and height() give the real ones
    const ubyte DEFAULT_WIDTH = 80
    const ubyte DEFAULT_HEIGHT = 25

    ; the codes of getkey() and waitkey() for the keys that differ between the machines: those of the ZX Spectrum, which
    ; other z88dk platforms may not share (a key that a machine lacks gets one that no key gives: 248 up, 249 down,
    ; 250 left, 251 right, 252 escape, 253 backspace, 254 break)
    const ubyte KEY_UP = 11
    const ubyte KEY_DOWN = 10
    const ubyte KEY_LEFT = 8
    const ubyte KEY_RIGHT = 9
    const ubyte KEY_ENTER = 13
    const ubyte KEY_ESC = 7             ; EDIT (caps shift 1)
    const ubyte KEY_BACKSPACE = 12      ; DELETE (caps shift 0)
    const ubyte KEY_BREAK = 254
}
