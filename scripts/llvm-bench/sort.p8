%import textio
%zeropage basicsafe

; word array indexing and compares: a shell sort of pseudo random words, many times
main {
    uword[8000] numbers
    uword seed

    sub start() {
        long checksum = 0
        seed = 12345 + sys.argc()
        repeat 900 {
            fill()
            shellsort()
            checksum += numbers[0] + numbers[len(numbers) / 2] + numbers[len(numbers) - 1]
            uword i
            for i in 1 to len(numbers) - 1
                if numbers[i - 1] > numbers[i]
                    checksum = -1
        }
        txt.print_l(checksum)
        txt.nl()
    }

    sub fill() {
        uword i
        for i in 0 to len(numbers) - 1 {
            seed = seed * 25173 + 13849
            numbers[i] = seed
        }
    }

    sub shellsort() {
        uword[] gaps = [3280, 1093, 364, 121, 40, 13, 4, 1]
        uword gap
        for gap in gaps {
            uword i
            for i in gap to len(numbers) - 1 {
                uword value = numbers[i]
                uword j = i
                while j >= gap and numbers[j - gap] > value {
                    numbers[j] = numbers[j - gap]
                    j -= gap
                }
                numbers[j] = value
            }
        }
    }
}
