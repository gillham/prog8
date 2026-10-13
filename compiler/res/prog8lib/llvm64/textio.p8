; Prog8 definitions for the Text I/O console routines for the llvm64 target.
; Text goes to stdout; colors, reverse video and cursor movement use ANSI escape sequences.
; txt.print_f is not here: as on the other targets, it belongs in the floats module (which llvm64 doesn't have yet).

%import conv
%option ignore_unused

txt {

    alias print_u16 = print_uw
    alias print_i16 = print_w
    alias print_i32 = print_l

sub  clear_screen() {
    print("\x1b[2J\x1b[H")
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

sub home() {
    print("\x1b[H")
}

sub lowercase() {
    ; not supported
}

sub uppercase() {
    ; not supported
}

sub iso() {
    ; llvm64 target is iso always, this is just for code compatibility
}

sub iso_off() {
    ; llvm64 target is iso always, this is just for code compatibility
}

sub rvs_on() {
    print("\x1b[7m")
}

sub rvs_off() {
    print("\x1b[0m")
}

sub color (ubyte txtcol) {
    ; ---- set the text color, one of the 8 ANSI colors 0-7
    print("\x1b[3")
    chrout('0' + txtcol)
    chrout('m')
}

sub chrout(ubyte char) {
    %ir {{
        loadm.b r99100.b,[txt.chrout.char]
        syscall 2 (r99100.b)
    }}
}

sub bell() {
    chrout(7)
}

sub  print (str text) {
    %ir {{
        loadm.p r99200.p,[txt.print.text]
        syscall 3 (r99200.p)
    }}
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
}
