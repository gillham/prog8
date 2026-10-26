%import textio
%import strings
%import conv
%zeropage basicsafe

; library calls on strings: number conversion, copying, appending, searching and comparing
main {
    sub start() {
        str buffer = "?" * 80
        long total = 0
        uword i
        repeat 320 + lsb(sys.argc()) {
            for i in 0 to 60000 {
                void strings.copy(conv.str_uw(i), buffer)
                void strings.append(buffer, "-x")
                total += strings.length(buffer)
                total += strings.findstr(buffer, "9-")
                total += conv.str2uword(buffer)
                if strings.compare(buffer, "30000-x") == 0
                    total += 1000
            }
        }
        txt.print_l(total)
        txt.nl()
    }
}
