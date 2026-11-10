; The llvm16 Text I/O routines that don't depend on the console: printing numbers through print() and reading input.
; Merged into the txt block of the llvm16 textio and of the textio of each library family, such as z88dk.

%import conv

txt {
%option merge, no_symbol_prefixing, ignore_unused

    alias print_u16 = print_uw
    alias print_i16 = print_w
    alias print_i32 = print_l
    alias print_uq = print_u64
    alias print_q = print_i64

sub size() -> ubyte, ubyte {
    ; -- returns the text screen width and height (number of columns and rows)
    return width(), height()
}

sub cls() {
    clear_screen()
}

sub nl() {
    chrout('\n')
}

sub spc() {
    chrout(' ')
}

sub lowercase() {
    ; not supported
}

sub uppercase() {
    ; not supported
}

sub iso() {
    ; llvm16 target is iso always, this is just for code compatibility
}

sub iso_off() {
    ; llvm16 target is iso always, this is just for code compatibility
}

sub  print_ub0  (ubyte value) {
    ; ---- print the ubyte in decimal form, with left padding 0s (3 positions total)
    print(conv.str_ub0(value))
}

sub  print_ub  (ubyte value)  {
    ; ---- print the ubyte in decimal form, without left padding 0s
    print(conv.str_ub(value))
}

sub  print_b  (byte value)   {
    ; ---- print the byte in decimal form, without left padding 0s
    print(conv.str_b(value))
}

sub print_bool(bool value) {
    print(conv.str_bool(value))
}

sub  print_ubhex  (ubyte value, bool prefix)  {
    ; ---- print the ubyte in hex form
    if prefix
        chrout('$')
    print(conv.str_ubhex(value))
}

sub  print_ubbin  (ubyte value, bool prefix) {
    ; ---- print the ubyte in binary form
    if prefix
        chrout('%')
    print(conv.str_ubbin(value))
}

sub  print_uwbin  (uword value, bool prefix)  {
    ; ---- print the uword in binary form
    if prefix
        chrout('%')
    print(conv.str_uwbin(value))
}

sub  print_uwhex  (uword value, bool prefix) {
    ; ---- print the uword in hexadecimal form (4 digits)
    if prefix
        chrout('$')
    print(conv.str_uwhex(value))
}

sub  print_ulhex  (long value, bool prefix) {
    ; ---- print the long in hexadecimal form (8 digits)
    if prefix
        chrout('$')
    print(conv.str_ulhex(value))
}

sub  print_uqhex  (uquad value) {
    ; ---- print the uquad in hexadecimal form (16 digits)
    print(conv.str_uqhex(value))
}

sub  print_uqbin  (uquad value) {
    ; ---- print the uquad in binary form (64 digits)
    print(conv.str_uqbin(value))
}

sub  print_uw0  (uword value)  {
    ; ---- print the uword value in decimal form, with left padding 0s (5 positions total)
    print(conv.str_uw0(value))
}

sub  print_uw  (uword value)  {
    ; ---- print the uword in decimal form, without left padding 0s
    print(conv.str_uw(value))
}

sub  print_w  (word value) {
    ; ---- print the (signed) word in decimal form, without left padding 0's
    print(conv.str_w(value))
}

sub  print_l  (long value) {
    ; ---- print the (signed) long in decimal form, without left padding 0's
    print(conv.str_l(value))
}

sub  print_u32  (long value) {
    ; ---- print the 32 bits of the long, read as an unsigned number, in decimal form, without left padding 0's
    print(conv.str_u32(value))
}

sub  print_i64  (quad value) {
    ; ---- print the (signed) quad in decimal form, without left padding 0's
    print(conv.str_i64(value))
}

sub  print_u64  (uquad value) {
    ; ---- print the uquad in decimal form, without left padding 0's
    print(conv.str_u64(value))
}

sub chrin() -> ubyte {
    ; ---- read the next character (byte) from stdin, waiting for it if needed; 0 at the end of the input
    %ir {{
        syscall 69 (): r99100.b
        returnr.b r99100.b
    }}
}

bool @shared eof_exit = true      ; line input at the end of the input ends the program with exit status 1; false: it returns 0 and eof() is true
bool eof_flag = false

sub eof() -> bool {
    ; ---- true when the last line input found the end of the input (only when eof_exit is false)
    return eof_flag
}

sub  input_chars  (str buffer) -> ubyte  {
    ; ---- Input a string (max. 80 chars) from stdin. Returns length of input. (string is terminated with a 0 byte as well)
    return input_line(buffer, 80)
}

sub readln() -> str {
    ; ---- read a line (max. 255 chars) from stdin, without the line end; the next readln() reuses the returned string
    ubyte[256] line
    void input_line(line, 255)
    return &line
}

sub prompt(str message) -> str {
    ; ---- print the message, and readln() the answer
    print(message)
    return readln()
}

sub petscii2scr(ubyte petscii_char) -> ubyte {
    ; -- convert petscii character to screencode
    ubyte[8] offsets = [128, 0, 64, 32, 64, 192, 128, 128]
    return petscii_char ^ offsets[petscii_char>>5]
}

sub petscii2scr_str(str petscii_string) {
    ; -- convert petscii string to screencodes string
    pointer temp = petscii_string
    while @(temp)!=0 {
        @(temp) = petscii2scr(@(temp))
        temp++
    }
}

    sub iso2petscii(ubyte iso_char) -> ubyte {
        ; --converts iso 8859-15 character to petscii character (lowercase)
        if iso_char & $7f <= $20
            return petscii:' '  ; whitspace
        if iso_char <= $3f
            return iso_char  ; numbers and symbols
        if iso_char < $80
            return translate40to7F[iso_char-$40]
        return translateA0toFF[iso_char-$a0]

        ubyte[$40] translate40to7F = [
            $40, $c1, $c2, $c3, $c4, $c5, $c6, $c7, $c8, $c9, $ca, $cb, $cc, $cd, $ce, $cf,
            $d0, $d1, $d2, $d3, $d4, $d5, $d6, $d7, $d8, $d9, $da, $5b, $3f, $5d, $5e, $e4,
            $27, $41, $42, $43, $44, $45, $46, $47, $48, $49, $4a, $4b, $4c, $4d, $4e, $4f,
            $50, $51, $52, $53, $54, $55, $56, $57, $58, $59, $5a, $f3, $7d, $eb, $3f, $3f,
        ]
        ubyte[$60] translateA0toFF = [
            $20, $21, $20, $5c, $c5, $d9, $3f, $3f, $3f, $c3, $3f, $28, $3f, $3f, $d2, $e3,
            $3f, $3f, $32, $33, $3f, $3f, $ff, $3f, $3f, $31, $3f, $3e, $3f, $3f, $d9, $3f,
            $c1, $c1, $c1, $c1, $c1, $c1, $c1, $c3, $c5, $c5, $c5, $c5, $c9, $c9, $c9, $c9,
            $c4, $ce, $cf, $cf, $cf, $cf, $cf, $58, $cf, $d5, $d5, $d5, $d5, $d9, $3f, $53,
            $41, $41, $41, $41, $41, $41, $41, $43, $45, $45, $45, $45, $49, $49, $49, $49,
            $4f, $4e, $4f, $4f, $4f, $4f, $4f, $3f, $4f, $55, $55, $55, $55, $59, $3f, $59,
        ]
    }

    sub iso2petscii_str(str iso_string) {
        ; -- convert iso string to petscii (lowercase) string
        pointer temp = iso_string
        while @(temp)!=0 {
            @(temp) = iso2petscii(@(temp))
            temp++
        }
    }
}
