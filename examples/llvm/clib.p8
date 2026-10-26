%import textio
%import floats
%zeropage basicsafe

; Calls functions of the C library directly, as extsubs without an address: the date and time, an environment
; variable, a math function, and qsort() with a Prog8 subroutine that compares two elements.
; An extsub must use the Prog8 types with the exact widths of the C ones: a C int is a long, and a size_t is an uquad
; on llvm64 but a long on llvm32, which is why this program is for llvm64 only.

main {
    extsub time(pointer result) -> quad
    extsub ctime(^^quad time) -> str
    extsub getenv(str name) -> ^^ubyte
    extsub cbrt(float value) -> float
    extsub qsort(pointer base, uquad count, uquad size, pointer compare)

    uword[8] @nosplit numbers = [512, 3, 4096, 77, 1, 900, 42, 65535]

    sub start() {
        quad now = time(0)
        txt.print("the time is ")
        txt.print(ctime(&now))          ; ctime's text ends with a line end

        ; a C string that may be NULL is a ^^ubyte, a str is never 0
        ^^ubyte home = getenv("HOME")
        txt.print("HOME is ")
        if home != 0
            txt.print(home)
        else
            txt.print("not set")
        txt.nl()

        txt.print("the cube root of 2 is ")
        txt.print_f(cbrt(2.0))
        txt.nl()

        ; a split word array keeps its bytes apart, which C can't sort, hence @nosplit
        qsort(&numbers, len(numbers), sizeof(uword), &by_value)
        uword number
        for number in numbers {
            txt.print_uw(number)
            txt.spc()
        }
        txt.nl()
    }

    sub by_value(^^uword first, ^^uword second) -> long {
        return (first^^ as long) - (second^^ as long)
    }
}
