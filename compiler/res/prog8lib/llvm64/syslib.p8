; Prog8 definitions for the llvm64 target: a hosted 64-bit program, compiled to LLVM IR.
; There is no runtime library for this target yet; this only has the definitions that the compiler and programs rely on.

%option ignore_unused

sys {
    ; ------- lowlevel system routines --------

    const ubyte target = 254         ;  compilation target specifier.  255=virtual, 254=llvm64, 128=C128, 64=C64, 32=PET, 16=CommanderX16, 8=atari800XL, 7=Neo6502

    const ubyte SIZEOF_BOOL  = sizeof(bool)
    const ubyte SIZEOF_BYTE  = sizeof(byte)
    const ubyte SIZEOF_UBYTE = sizeof(ubyte)
    const ubyte SIZEOF_WORD  = sizeof(word)
    const ubyte SIZEOF_UWORD = sizeof(uword)
    const ubyte SIZEOF_LONG  = sizeof(long)
    const ubyte SIZEOF_POINTER = sizeof(pointer)
    const ubyte SIZEOF_FLOAT = sizeof(float)
    const byte  MIN_BYTE     = -128
    const byte  MAX_BYTE     = 127
    const ubyte MIN_UBYTE    = 0
    const ubyte MAX_UBYTE    = 255
    const word  MIN_WORD     = -32768
    const word  MAX_WORD     = 32767
    const uword MIN_UWORD    = 0
    const uword MAX_UWORD    = 65535
    const long  MIN_LONG     = -2147483648
    const long  MAX_LONG     = 2147483647
    ; MIN_FLOAT and MAX_FLOAT are defined in the floats module if imported


    sub exit(ubyte returnvalue) {
        ; -- exit the program with a return code. All active defers in the call chain are unwound program-wide (LIFO) before the program ends.
        %ir {{
            loadm.b r99100.b,[sys.exit.returnvalue]
            syscall 1 (r99100.b)
        }}
    }

    sub set_carry() {
        %ir {{
            sec
        }}
    }

    sub clear_carry() {
        %ir {{
            clc
        }}
    }

    sub set_irqd() {
        %ir {{
            sei
        }}
    }

    sub clear_irqd() {
        %ir {{
            cli
        }}
    }
}

cx16 {

    ; the sixteen virtual 16-bit registers that the Commander X16 has defined in the zeropage
    ; on llvm64 they are memory-mapped variables at the same addresses as on the VirtualMachine
    &uword r0  = $ff0000
    &uword r1  = $ff0002
    &uword r2  = $ff0004
    &uword r3  = $ff0006
    &uword r4  = $ff0008
    &uword r5  = $ff000a
    &uword r6  = $ff000c
    &uword r7  = $ff000e
    &uword r8  = $ff0010
    &uword r9  = $ff0012
    &uword r10 = $ff0014
    &uword r11 = $ff0016
    &uword r12 = $ff0018
    &uword r13 = $ff001a
    &uword r14 = $ff001c
    &uword r15 = $ff001e

    ; signed word versions
    &word r0s  = $ff0000
    &word r1s  = $ff0002
    &word r2s  = $ff0004
    &word r3s  = $ff0006
    &word r4s  = $ff0008
    &word r5s  = $ff000a
    &word r6s  = $ff000c
    &word r7s  = $ff000e
    &word r8s  = $ff0010
    &word r9s  = $ff0012
    &word r10s = $ff0014
    &word r11s = $ff0016
    &word r12s = $ff0018
    &word r13s = $ff001a
    &word r14s = $ff001c
    &word r15s = $ff001e

    ; signed long versions
    &long r0r1sl  = $ff0000
    &long r2r3sl  = $ff0004
    &long r4r5sl  = $ff0008
    &long r6r7sl  = $ff000c
    &long r8r9sl  = $ff0010
    &long r10r11sl = $ff0014
    &long r12r13sl = $ff0018
    &long r14r15sl = $ff001c

    ; ubyte versions (low and high bytes)
    &ubyte r0L  = $ff0000
    &ubyte r1L  = $ff0002
    &ubyte r2L  = $ff0004
    &ubyte r3L  = $ff0006
    &ubyte r4L  = $ff0008
    &ubyte r5L  = $ff000a
    &ubyte r6L  = $ff000c
    &ubyte r7L  = $ff000e
    &ubyte r8L  = $ff0010
    &ubyte r9L  = $ff0012
    &ubyte r10L = $ff0014
    &ubyte r11L = $ff0016
    &ubyte r12L = $ff0018
    &ubyte r13L = $ff001a
    &ubyte r14L = $ff001c
    &ubyte r15L = $ff001e

    &ubyte r0H  = $ff0001
    &ubyte r1H  = $ff0003
    &ubyte r2H  = $ff0005
    &ubyte r3H  = $ff0007
    &ubyte r4H  = $ff0009
    &ubyte r5H  = $ff000b
    &ubyte r6H  = $ff000d
    &ubyte r7H  = $ff000f
    &ubyte r8H  = $ff0011
    &ubyte r9H  = $ff0013
    &ubyte r10H = $ff0015
    &ubyte r11H = $ff0017
    &ubyte r12H = $ff0019
    &ubyte r13H = $ff001b
    &ubyte r14H = $ff001d
    &ubyte r15H = $ff001f

    ; signed byte versions (low and high bytes)
    &byte r0sL  = $ff0000
    &byte r1sL  = $ff0002
    &byte r2sL  = $ff0004
    &byte r3sL  = $ff0006
    &byte r4sL  = $ff0008
    &byte r5sL  = $ff000a
    &byte r6sL  = $ff000c
    &byte r7sL  = $ff000e
    &byte r8sL  = $ff0010
    &byte r9sL  = $ff0012
    &byte r10sL = $ff0014
    &byte r11sL = $ff0016
    &byte r12sL = $ff0018
    &byte r13sL = $ff001a
    &byte r14sL = $ff001c
    &byte r15sL = $ff001e

    &byte r0sH  = $ff0001
    &byte r1sH  = $ff0003
    &byte r2sH  = $ff0005
    &byte r3sH  = $ff0007
    &byte r4sH  = $ff0009
    &byte r5sH  = $ff000b
    &byte r6sH  = $ff000d
    &byte r7sH  = $ff000f
    &byte r8sH  = $ff0011
    &byte r9sH  = $ff0013
    &byte r10sH = $ff0015
    &byte r11sH = $ff0017
    &byte r12sH = $ff0019
    &byte r13sH = $ff001b
    &byte r14sH = $ff001d
    &byte r15sH = $ff001f

    ; boolean versions
    &bool r0bL  = $ff0000
    &bool r1bL  = $ff0002
    &bool r2bL  = $ff0004
    &bool r3bL  = $ff0006
    &bool r4bL  = $ff0008
    &bool r5bL  = $ff000a
    &bool r6bL  = $ff000c
    &bool r7bL  = $ff000e
    &bool r8bL  = $ff0010
    &bool r9bL  = $ff0012
    &bool r10bL = $ff0014
    &bool r11bL = $ff0016
    &bool r12bL = $ff0018
    &bool r13bL = $ff001a
    &bool r14bL = $ff001c
    &bool r15bL = $ff001e

    &bool r0bH  = $ff0001
    &bool r1bH  = $ff0003
    &bool r2bH  = $ff0005
    &bool r3bH  = $ff0007
    &bool r4bH  = $ff0009
    &bool r5bH  = $ff000b
    &bool r6bH  = $ff000d
    &bool r7bH  = $ff000f
    &bool r8bH  = $ff0011
    &bool r9bH  = $ff0013
    &bool r10bH = $ff0015
    &bool r11bH = $ff0017
    &bool r12bH = $ff0019
    &bool r13bH = $ff001b
    &bool r14bH = $ff001d
    &bool r15bH = $ff001f
}
