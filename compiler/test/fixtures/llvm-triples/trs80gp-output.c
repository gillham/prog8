/*
 * For running TRS-80 programs in trs80gp with -ee -batch: console output also goes to the printer, which trs80gp
 * writes to trs80-printer.txt. Linked with -pragma-redirect:fputc_cons=_prog8_fputc, as the z80-trs80 profiles send all
 * console output through fputc_cons; prog8_fputc then calls the generic console driver that fputc_cons would be.
 * The printer gets the text, and each generic console control sequence (12, 4 n, 16 n, 17 n, 20 n, 22 y x, ESC x, ESC Y
 * y x, ESC b/c/s/r n) as its codes in decimal between braces, such as {22,34,36}: the tests then see the cursor and
 * color changes too, without a screen dump.
 * With -DPROG8_CONSOLE=name (the Model 4), name is the console routine instead, and every control code but 9, 10 and 13
 * is printed as {code}, as the Model 4's take no parameters. With -DPROG8_GENERIC_CONSOLE=name (the Model I/III), name is
 * the profile's own fputc_cons, which calls the generic console driver itself.
 */
#include <stdio.h>
#include <stdlib.h>

#ifdef PROG8_CONSOLE
extern int PROG8_CONSOLE(int c) __smallc;
#elif defined(PROG8_GENERIC_CONSOLE)
extern int PROG8_GENERIC_CONSOLE(int c) __smallc;
#else
extern int fputc_cons_generic(int c);
#endif

static unsigned char extensions;

/* trs80gp's emulator extensions are on when port $47 reads 1 after their function 255 */
int prog8_trs80gp(void)
{
    if (!extensions) {
        outp(0x47, 255);
        extensions = inp(0x47) == 1 ? 2 : 1;
    }
    return extensions == 2;
}

/* the Model I printer is memory-mapped at $37E8 (ROM byte $0054 is 1 there), the Model III and 4 one is port $F8 */
static void lprint(int c)
{
    if (*(unsigned char *)0x54 == 1) {
        while (*(volatile unsigned char *)0x37E8 & 0x80)
            ;
        *(unsigned char *)0x37E8 = c;
    } else {
        while (inp(0xF8) & 0x80)
            ;
        outp(0xF8, c);
    }
}

static void lprint_number(int n)
{
    if (n >= 10)
        lprint_number(n / 10);
    lprint('0' + n % 10);
}

/* the parameter bytes still to come of the control sequence being printed */
static unsigned char parameters;
static unsigned char escape;

static void mirror(int c)
{
    c &= 255;
#ifdef PROG8_CONSOLE
    if (c < 32 && c != 9 && c != 10 && c != 13) {
        lprint('{');
        lprint_number(c);
        lprint('}');
        return;
    }
#endif
    if (parameters) {
        lprint(',');
        lprint_number(c);
        if (escape) {
            escape = 0;
            parameters = c == 'Y' ? 2 : (c == 'b' || c == 'c' || c == 's' || c == 'r') ? 1 : 0;
        } else {
            parameters--;
        }
        if (!parameters)
            lprint('}');
        return;
    }
    if (c == 12 || c == 4 || c == 16 || c == 17 || c == 20 || c == 22 || c == 27) {
        lprint('{');
        lprint_number(c);
        escape = c == 27;
        parameters = c == 12 ? 0 : c == 22 ? 2 : 1;
        if (!parameters)
            lprint('}');
        return;
    }
    lprint(c);
}

int prog8_fputc(int c)
{
    if (prog8_trs80gp())
        mirror(c);
#ifdef PROG8_CONSOLE
    return PROG8_CONSOLE(c);
#elif defined(PROG8_GENERIC_CONSOLE)
    return PROG8_GENERIC_CONSOLE(c);
#else
    return fputc_cons_generic(c);
#endif
}
