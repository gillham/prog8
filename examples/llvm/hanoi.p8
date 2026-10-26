%import textio
%import conv
%zeropage basicsafe

; Solves the Towers of Hanoi recursively, for the number of disks on the command line (default 4):
;     ./hanoi 5
; On the llvm targets the parameters and scalar locals that only their own subroutine uses live in each call,
; so a recursive subroutine like move() works. For one that keeps static variables (such as an array), which
; its recursive calls would share, the compiler gives a warning.
; Note: this program is for the llvm32 and llvm64 targets.

main {
    uword moves = 0

    sub start() {
        ubyte disks = 4
        if sys.argc() > 1
            disks = conv.str2ubyte(sys.argv(1))
        move(disks, 1, 3, 2)
        txt.print_uw(moves)
        txt.print(" moves\n")
    }

    sub move(ubyte count, ubyte source, ubyte target, ubyte spare) {
        if count == 0
            return
        move(count - 1, source, spare, target)
        txt.print("move disk ")
        txt.print_ub(count)
        txt.print(" from ")
        txt.print_ub(source)
        txt.print(" to ")
        txt.print_ub(target)
        txt.nl()
        moves++
        move(count - 1, spare, target, source)
    }
}
