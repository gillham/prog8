%import textio
%zeropage basicsafe

; byte array loops: the sieve of Eratosthenes up to 20000, many times
main {
    bool[20000] composite

    sub start() {
        long total = 0
        repeat 12000
            total += sieve(len(composite) - lsb(sys.argc()))
        txt.print_l(total)
        txt.nl()
    }

    sub sieve(uword limit) -> uword {
        sys.memset(&composite, len(composite), 0)
        uword count = 0
        uword i
        for i in 2 to limit - 1 {
            if not composite[i] {
                count++
                uword j = i + i
                while j < limit {
                    composite[j] = true
                    j += i
                }
            }
        }
        return count
    }
}
