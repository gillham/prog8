%import textio
%zeropage basicsafe

; long arithmetic, shifts and masks: a bitwise CRC-32 over a buffer, many times
main {
    ubyte[4096] data

    sub start() {
        uword i
        for i in 0 to len(data) - 1
            data[i] = lsb(i * 7 + (i >> 3))
        long total = 0
        repeat 12000 {
            data[0] += lsb(sys.argc())
            total += crc32()
        }
        txt.print_ulhex(total, true)
        txt.nl()
    }

    sub crc32() -> long {
        long crc = -1
        uword i
        for i in 0 to len(data) - 1 {
            crc ^= data[i]
            repeat 8 {
                if crc & 1 != 0
                    crc = ((crc >> 1) & $7fffffff) ^ $EDB88320
                else
                    crc = (crc >> 1) & $7fffffff
            }
        }
        return ~crc
    }
}
