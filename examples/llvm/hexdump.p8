%import textio
%import fileio
%zeropage basicsafe

; Shows a file in hex and as text, 16 bytes per line, with the offset of each line:
;     ./hexdump picture.png
; fileio reads and writes files through C's FILE streams; a pointer holds the stream on both llvm targets.
; Note: this program is for the llvm32 and llvm64 targets.

main {
    sub start() {
        if sys.argc() != 2 {
            txt.print("usage: hexdump <file>\n")
            sys.exit(2)
        }
        ^^ubyte name = sys.argv(1)
        pointer file = fileio.open(name, "rb")
        if file == 0 {
            txt.print("can't open ")
            txt.print(name)
            txt.nl()
            sys.exit(1)
        }

        ubyte[16] buffer
        long offset = 0
        repeat {
            uword count = fileio.read(file, &buffer, len(buffer))
            if count == 0
                break
            show(offset, buffer, lsb(count))
            offset += count
        }
        void fileio.close(file)
    }

    sub show(long offset, ^^ubyte bytes, ubyte count) {
        txt.print_ulhex(offset, false)
        txt.print("  ")
        ubyte index
        for index in 0 to 15 {
            if index < count {
                txt.print_ubhex(bytes[index], false)
                txt.spc()
            } else
                txt.print("   ")
            if index == 7
                txt.spc()
        }
        txt.print(" |")
        for index in 0 to count - 1 {
            ubyte char = bytes[index]
            txt.chrout(if char >= 32 and char < 127 char else '.')
        }
        txt.print("|\n")
    }
}
