%import textio
%import strings
%zeropage basicsafe

; Prints the lines on stdin that contain the text given on the command line, like a very simple grep:
;     cat notes.txt | ./find todo
;     cat notes.txt | ./find -c todo        (only prints how many lines contain it)
; The exit status is 0 when a line matched, 1 when none did, and 2 when the command line is wrong.
; Note: this program is for the llvm32 and llvm64 targets.

main {
    sub start() {
        bool count_only = false
        uword first = 1
        if sys.argc() == 3 and strings.compare(sys.argv(1), "-c") == 0 {
            count_only = true
            first = 2
        }
        if sys.argc() != first + 1 {
            txt.print("usage: find [-c] <text>\n")
            sys.exit(2)
        }
        ^^ubyte text = sys.argv(first)

        ubyte[256] line
        uword matches = 0
        txt.eof_exit = false
        repeat {
            void txt.input_line(line, 255)
            if txt.eof()
                break
            if strings.findstr(line, text) != 255 {
                matches++
                if not count_only {
                    txt.print(line)
                    txt.nl()
                }
            }
        }

        if count_only {
            txt.print_uw(matches)
            txt.nl()
        }
        if matches == 0
            sys.exit(1)
    }
}
