%import textio
%import strings
%zeropage basicsafe

; Counts the lines, words and characters on stdin, like the Unix wc command:
;     cat notes.txt | ./wordcount
; Line input normally ends the program at the end of the input; with txt.eof_exit = false it returns
; an empty line instead, and txt.eof() tells that the input has ended.
; Note: this program is for the llvm32 and llvm64 targets. A line longer than 255 characters only counts its start.

main {
    sub start() {
        ubyte[256] line
        long lines = 0
        long words = 0
        long characters = 0

        txt.eof_exit = false
        repeat {
            ubyte length = txt.input_line(line, 255)
            if txt.eof()
                break
            lines++
            characters += length + 1        ; the line end too
            words += count_words(line)
        }

        txt.print_l(lines)
        txt.print(" lines, ")
        txt.print_l(words)
        txt.print(" words, ")
        txt.print_l(characters)
        txt.print(" characters\n")
    }

    sub count_words(str text) -> ubyte {
        ubyte count = 0
        bool in_word = false
        ubyte index = 0
        repeat {
            ubyte char = text[index]
            if char == 0
                return count
            if strings.isspace(char)
                in_word = false
            else if not in_word {
                in_word = true
                count++
            }
            index++
        }
    }
}
