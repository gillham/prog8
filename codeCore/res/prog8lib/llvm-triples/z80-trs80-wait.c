/*
 * The z80-trs80-cmd profile's sys.wait() and sys.waitvsync() (wait_symbol), which count the ticks of sys.ticks()
 * (z80-trs80-ticks.asm, a ticks_source like this file): without the clock (on a Model I, under TRSDOS 1.3) they
 * return at once, as before.
 */
extern unsigned int prog8_trs80_ticks(void);
extern int prog8_trs80_clock(void);

void prog8_trs80_wait(unsigned int jiffies)
{
    unsigned int start;

    if (!prog8_trs80_clock())
        return;
    start = prog8_trs80_ticks();
    while (prog8_trs80_ticks() - start < jiffies)
        ;
}
