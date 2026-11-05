/*
 * For running TRS-80 programs in trs80gp with -ee -batch: console output also goes to the printer, which trs80gp
 * writes to trs80-printer.txt. Linked with -pragma-redirect:_putchar=_prog8_putchar; it writes through stdout as putchar
 * does, which the cmd subtype sends to TRSDOS's *DO device rather than to fputc_cons.
 */
#include <stdio.h>
#include <stdlib.h>

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

int prog8_putchar(int c)
{
    if (prog8_trs80gp())
        lprint(c);
    return fputc(c, stdout);
}
