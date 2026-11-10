/*
 * The z80-zx profile's sys.ticks() (ticks_symbol, ticks_source): the ROM's FRAMES counter (23672, three bytes, which
 * the interrupt advances 50 times a second) in 60ths of a second. Read until two reads agree, as the interrupt can
 * change it between the bytes. It jumps when FRAMES wraps, after 93 hours.
 */
unsigned int prog8_zx_ticks(void)
{
    volatile unsigned char *frames = (volatile unsigned char *)23672;
    unsigned long count;
    unsigned long again;

    do {
        count = frames[0] | ((unsigned long)frames[1] << 8) | ((unsigned long)frames[2] << 16);
        again = frames[0] | ((unsigned long)frames[1] << 8) | ((unsigned long)frames[2] << 16);
    } while (count != again);
    return (unsigned int)(count * 6 / 5);
}
