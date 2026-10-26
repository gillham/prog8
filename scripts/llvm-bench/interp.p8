%import textio
%zeropage basicsafe

; when dispatch: a small register machine interpreting a counting loop
main {
    const ubyte OP_LOAD = 0         ; register = value
    const ubyte OP_ADD = 1          ; register += register
    const ubyte OP_XOR = 2          ; register ^= value
    const ubyte OP_MUL = 3          ; register *= value
    const ubyte OP_DEC = 4          ; register--
    const ubyte OP_JNZ = 5          ; if register != 0, go to the instruction
    const ubyte OP_SHR = 6          ; register >>= value
    const ubyte OP_HALT = 7         ; the result is the register

    ; acc = start; for counter = 250 down to 1: acc = ((acc + counter) xor 5) * 3, plus a shift
    ubyte[] program = [OP_LOAD, 1, 250,
                       OP_ADD, 0, 1,
                       OP_XOR, 0, 5,
                       OP_MUL, 0, 3,
                       OP_SHR, 0, 1,
                       OP_DEC, 1, 0,
                       OP_JNZ, 1, 3,
                       OP_HALT, 0, 0]
    long[4] registers

    sub start() {
        long total = 0
        uword round
        repeat 4 {
            for round in 1 to 60000
                total += run(round + sys.argc())
        }
        txt.print_l(total)
        txt.nl()
    }

    sub run(long start) -> long {
        registers[0] = start
        ubyte pc = 0
        repeat {
            ubyte op = program[pc]
            ubyte reg = program[pc + 1]
            ubyte value = program[pc + 2]
            pc += 3
            when op {
                OP_LOAD -> registers[reg] = value
                OP_ADD -> registers[reg] += registers[value]
                OP_XOR -> registers[reg] ^= value
                OP_MUL -> registers[reg] *= value
                OP_SHR -> registers[reg] >>= value
                OP_DEC -> registers[reg]--
                OP_JNZ -> {
                    if registers[reg] != 0
                        pc = value * 3
                }
                OP_HALT -> return registers[reg]
            }
        }
    }
}
