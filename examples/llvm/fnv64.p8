%import textio
%zeropage basicsafe

; Prints the 64-bit FNV-1a hash of each line on stdin, and of all the input together:
;     cat words.txt | ./fnv64
; It computes with uquad, the 64-bit unsigned integer type, whose multiplication wraps around like the hash needs.
; Note: this program is for the llvm32 and llvm64 targets.

main {
    const uquad OFFSET_BASIS = $cbf29ce484222325
    const uquad PRIME = $00000100000001b3

    sub start() {
        ubyte[256] line
        uquad total = OFFSET_BASIS
        txt.eof_exit = false
        repeat {
            ubyte length = txt.input_line(line, 255)
            if txt.eof()
                break
            uquad hash = OFFSET_BASIS
            ubyte index
            for index in 0 to length {
                ; the terminating 0 byte stands in for the line end in the total
                if index < length
                    hash = add_byte(hash, line[index])
                total = add_byte(total, if index < length line[index] else '\n')
            }
            txt.print_uqhex(hash)
            txt.print("  ")
            txt.print(line)
            txt.nl()
        }
        txt.print_uqhex(total)
        txt.print("  (all the input)\n")
    }

    sub add_byte(uquad hash, ubyte value) -> uquad {
        return (hash ^ value) * PRIME
    }
}
