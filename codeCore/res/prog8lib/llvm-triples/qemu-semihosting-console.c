/*
 * Console input for newlib programs on qemu's semihosting. newlib reads the console with SYS_READ, which qemu answers
 * with nothing, so with -Wl,--wrap=_read this reads file descriptor 0 a line at a time with SYS_READC instead.
 * A Ctrl-D ends the input. On a terminal, which qemu switches to raw mode, it echoes the typing and handles backspace.
 */
#include <string.h>

#define SYS_OPEN 0x01
#define SYS_CLOSE 0x02
#define SYS_WRITEC 0x03
#define SYS_READC 0x07
#define SYS_ISTTY 0x09
#define END_OF_INPUT 4

int __real__read(int fd, char *buf, int len);

#if defined(__riscv)
int prog8_semihosting(int op, void *arg);
/* the semihosting call is this exact uncompressed sequence, which must not cross a page boundary */
__asm__(
    ".pushsection .text\n"
    ".option push\n"
    ".option norvc\n"
    ".balign 16\n"
    "prog8_semihosting:\n"
    "    slli zero, zero, 0x1f\n"
    "    ebreak\n"
    "    srai zero, zero, 0x7\n"
    "    ret\n"
    ".option pop\n"
    ".popsection\n");
#else
static int prog8_semihosting(int op, void *arg) {
    register int r0 __asm__("r0") = op;
    register void *r1 __asm__("r1") = arg;
#if defined(__thumb__)
    __asm__ volatile("svc 0xab" : "+r"(r0) : "r"(r1) : "memory");
#else
    __asm__ volatile("svc 0x123456" : "+r"(r0) : "r"(r1) : "memory");
#endif
    return r0;
}
#endif

static char line[256];
static int line_length, line_taken;
static int input_ended;
static int terminal = -1;

/* the console handle always claims to be a terminal, so this asks about the host's own stdin */
static int stdin_is_terminal(void) {
    unsigned long open_args[3] = { (unsigned long)"/dev/stdin", 0, 10 };
    int handle = prog8_semihosting(SYS_OPEN, open_args);
    if (handle < 0)
        return 0;
    int result = prog8_semihosting(SYS_ISTTY, &handle) == 1;
    prog8_semihosting(SYS_CLOSE, &handle);
    return result;
}

static void echo(char c) {
    prog8_semihosting(SYS_WRITEC, &c);
}

static void read_line(void) {
    line_length = line_taken = 0;
    while (line_length < (int)sizeof line) {
        char c = (char)prog8_semihosting(SYS_READC, 0);
        if (c == END_OF_INPUT) {
            input_ended = 1;
            return;
        }
        if (terminal) {
            if (c == '\b' || c == 127) {
                if (line_length > 0) {
                    line_length--;
                    echo('\b');
                    echo(' ');
                    echo('\b');
                }
                continue;
            }
            if (c == '\r')
                c = '\n';
            echo(c);
        }
        line[line_length++] = c;
        if (c == '\n')
            return;
    }
}

int __wrap__read(int fd, char *buf, int len) {
    if (fd != 0)
        return __real__read(fd, buf, len);
    if (terminal < 0)
        terminal = stdin_is_terminal();
    if (line_taken == line_length && !input_ended)
        read_line();
    if (line_taken == line_length) {
        input_ended = 0;
        return 0;
    }
    int count = line_length - line_taken;
    if (count > len)
        count = len;
    memcpy(buf, line + line_taken, count);
    line_taken += count;
    return count;
}
