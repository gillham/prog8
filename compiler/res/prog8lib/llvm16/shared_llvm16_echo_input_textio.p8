; The llvm16 line input for a machine's own keyboard and screen, whose key reading doesn't show what is typed.
; Merged into the txt block of the textio of the library families coco, z88dk and z88dk-ansi.

txt {
%option merge, no_symbol_prefixing, ignore_unused

sub  input_line  (str buffer, ubyte maxlength) -> ubyte  {
    ; ---- read a line from the keyboard into the buffer, which needs room for maxlength chars and the terminating 0 byte.
    ;      Returns the length. Typed characters are echoed through chrout(), Enter as a new line, which is not stored;
    ;      backspace (8, or 12 or 127, other machines' delete keys) removes the last one. Other control codes and the characters
    ;      past maxlength are ignored. When chrin() gives 0 first, the program ends with exit status 1, or with eof_exit false, returns 0 and eof() is true.
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
        started = true
        if char == 0
            break
        if char == '\n' or char == '\r' {
            chrout('\n')
            break
        }
        if char == 8 or char == 12 or char == 127 {
            if count > 0 {
                count--
                chrout(8)
                chrout(' ')
                chrout(8)
            }
        } else if char >= 32 and count < maxlength {
            buffer[count] = char
            count++
            chrout(char)
        }
    }
    buffer[count] = 0
    return count
}
}
