/*
 * The mos-c64 profile's support routines:
 * - usleep, for sys.wait, which the llvm-mos SDK lacks. It counts ticks of the KERNAL's jiffy clock ($A0-$A2), which the
 *   interrupt advances 60 times a second on PAL and NTSC machines alike; with interrupts masked the clock stops, and
 *   usleep returns at once.
 * - prog8_c64_waitvsync (the profile's waitvsync_symbol), for sys.waitvsync: waits until the raster wraps around to
 *   line 0, as the c64 target's sys.waitvsync does.
 * - prog8_c64_plot, the KERNAL's PLOT for txt.plot, which the SDK has no routine for.
 */
#define JIFFY_LOW (*(volatile unsigned char *)0xA2)
#define SCROLY (*(volatile unsigned char *)0xD011)

int usleep(unsigned long microseconds)
{
    unsigned char status;
    unsigned int ticks;

    __asm__ volatile ("php\n\tpla" : "=a"(status));
    if (status & 0x04)
        return 0;
    for (ticks = (unsigned int)((microseconds + 8333) / 16667); ticks != 0; ticks--) {
        unsigned char start = JIFFY_LOW;
        while (JIFFY_LOW == start)
            ;
    }
    return 0;
}

void prog8_c64_waitvsync(void)
{
    /* bit 7 of SCROLY is bit 8 of the raster line: wait until it is set, then until it is clear again */
    while (!(SCROLY & 0x80))
        ;
    while (SCROLY & 0x80)
        ;
}

void prog8_c64_plot(unsigned char col, unsigned char row)
{
    /* PLOT ($FFF0) with the carry clear moves the cursor to row X, column Y */
    __asm__ volatile ("clc\n\tjsr 65520" : : "x"(row), "y"(col) : "a", "p", "memory");
}
