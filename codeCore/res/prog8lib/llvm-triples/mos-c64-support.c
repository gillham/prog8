/*
 * The mos-c64 profile's usleep, for sys.wait and sys.waitvsync, which the llvm-mos SDK lacks. It counts ticks of the
 * KERNAL's jiffy clock ($A0-$A2), which the interrupt advances 60 times a second on PAL and NTSC machines alike; with
 * interrupts masked the clock stops, and usleep returns at once.
 */
#define JIFFY_LOW (*(volatile unsigned char *)0xA2)

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
