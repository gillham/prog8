%import textio
%zeropage basicsafe

; subroutine calls: recursive Fibonacci numbers
main {
    sub start() {
        long total = 0
        ubyte n
        for n in 20 to 35 + lsb(sys.argc())
            total += fib(n)
        txt.print_l(total)
        txt.nl()
    }

    sub fib(ubyte n) -> long {
        if n < 2
            return n
        return fib(n - 1) + fib(n - 2)
    }
}
