# Boot code for newlib programs on qemu's RISC-V virt machine, loaded as the firmware with -bios.
# newlib's _start expects a loader to have set up the stack, with argc and argv on it, and to have
# switched on the floating point unit; qemu does neither, so _boot does that, at the top of the 128 MiB of RAM.
# The command line comes from semihosting, split at spaces outside double quotes, which are removed, as newlib does on ARM;
# without one, it only has the program name "program".
# newlib's _exit uses the plain semihosting exit call, which loses the exit status;
# this _exit uses the extended one instead, so that qemu exits with the program's status.

    .equ MAX_ARGS, 60
    .equ CMDLINE_SIZE, 1024

    # newlib's _start only sets gp later, so the linker must not turn addresses into gp-relative ones here
    .option norelax
    .section .text
    .globl _boot
_boot:
    li t0, 0x2000           # mstatus.FS = initial
    csrs mstatus, t0
    li sp, 0x87fffef0       # room for argc, MAX_ARGS arguments and the ends of argv and envp
    la a1, cmdline_block
    li a0, 0x15             # SYS_GET_CMDLINE, which fills the buffer the block points to
    call semihosting
    bnez a0, no_cmdline
    la t0, cmdline          # read position
    mv t1, t0               # write position, never past the read position as the quotes are dropped
    addi t2, sp, 4          # next argv entry
    li t3, 0                # argc
    li t4, MAX_ARGS
skip_spaces:
    lbu t5, 0(t0)
    beqz t5, args_done
    li t6, ' '
    bne t5, t6, start_arg
    addi t0, t0, 1
    j skip_spaces
start_arg:
    beq t3, t4, args_done
    sw t1, 0(t2)
    addi t2, t2, 4
    addi t3, t3, 1
    li a2, 0                # inside quotes
arg_char:
    lbu t5, 0(t0)
    beqz t5, end_arg
    li t6, '"'
    bne t5, t6, 1f
    xori a2, a2, 1
    addi t0, t0, 1
    j arg_char
1:  li t6, ' '
    bne t5, t6, 2f
    beqz a2, end_arg
2:  sb t5, 0(t1)
    addi t1, t1, 1
    addi t0, t0, 1
    j arg_char
end_arg:
    sb zero, 0(t1)
    addi t1, t1, 1
    beqz t5, args_done
    addi t0, t0, 1
    j skip_spaces
args_done:
    bnez t3, start
no_cmdline:
    la t0, program_name
    sw t0, 4(sp)            # argv[0]
    li t3, 1
    addi t2, sp, 8
start:
    sw t3, 0(sp)            # argc
    sw zero, 0(t2)          # the end of argv
    sw zero, 4(t2)          # the end of envp
    j _start

    .globl _exit
_exit:
    addi sp, sp, -8
    li t0, 0x20026          # ADP_Stopped_ApplicationExit
    sw t0, 0(sp)
    sw a0, 4(sp)
    li a0, 0x20             # SYS_EXIT_EXTENDED
    mv a1, sp
    call semihosting
1:  j 1b

# the semihosting call is this exact uncompressed sequence, which must not cross a page boundary
semihosting:
    .option push
    .option norvc
    .balign 16
    slli zero, zero, 0x1f
    ebreak
    srai zero, zero, 0x7
    .option pop
    ret

    .section .rodata
program_name:
    .asciz "program"

# not in .bss, which newlib's _start clears after the command line was read into it
    .section .data
    .balign 4
cmdline_block:
    .word cmdline
    .word CMDLINE_SIZE
cmdline:
    .space CMDLINE_SIZE
