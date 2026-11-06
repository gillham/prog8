; The llvm16 line input for a console that is a stream, such as a terminal on stdin, which shows what is typed itself.
; Merged into the txt block of the llvm16 textio.

txt {
%option merge, no_symbol_prefixing, ignore_unused

sub  input_line  (str buffer, ubyte maxlength) -> ubyte  {
    ; ---- read a line from stdin into the buffer, which needs room for maxlength chars and the terminating 0 byte.
    ;      Returns the length. The line end is not stored, and the rest of a longer line is skipped.
    ;      When the input has already ended, the program ends with exit status 1, or with eof_exit false, returns 0 and eof() is true.
    ubyte count = 0
    bool started = false
    eof_flag = false
    repeat {
        ubyte char = chrin()
        if char == 0 and not started {
            if eof_exit
                sys.exit(1)
            eof_flag = true
            break
        }
        if char == 0 or char == '\n'
            break
        started = true
        if char != '\r' and count < maxlength {
            buffer[count] = char
            count++
        }
    }
    buffer[count] = 0
    return count
}
}
