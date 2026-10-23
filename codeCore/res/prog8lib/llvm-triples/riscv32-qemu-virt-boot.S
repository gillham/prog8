# Boot code for newlib programs on qemu's RISC-V virt machine, loaded as the firmware with -bios.
# newlib's _start expects a loader to have set up the stack, with argc and argv on it, and to have
# switched on the floating point unit; qemu does neither, so _boot does that, at the top of the 128 MiB of RAM.
# The command line it passes only has the program name, as semihosting's isn't read.
# newlib's _exit uses the plain semihosting exit call, which loses the exit status;
# this _exit uses the extended one instead, so that qemu exits with the program's status.

    .section .text
    .globl _boot
_boot:
    li t0, 0x2000           # mstatus.FS = initial
    csrs mstatus, t0
    li sp, 0x87fffff0
    li t0, 1
    sw t0, 0(sp)            # argc
    la t0, program_name
    sw t0, 4(sp)            # argv[0]
    sw zero, 8(sp)          # argv[1]
    sw zero, 12(sp)         # envp[0]
    j _start

    .globl _exit
_exit:
    addi sp, sp, -8
    li t0, 0x20026          # ADP_Stopped_ApplicationExit
    sw t0, 0(sp)
    sw a0, 4(sp)
    li a0, 0x20             # SYS_EXIT_EXTENDED
    mv a1, sp
    # the semihosting call is this exact uncompressed sequence, which must not cross a page boundary
    .option push
    .option norvc
    .balign 16
    slli zero, zero, 0x1f
    ebreak
    srai zero, zero, 0x7
    .option pop
1:  j 1b

    .section .rodata
program_name:
    .asciz "program"
