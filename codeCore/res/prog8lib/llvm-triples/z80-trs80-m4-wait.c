/*
 * The usleep of the z80-trs80-m4 profile, which sys.wait and sys.waitvsync use: the DOS's @PAUSE, whose count takes
 * about 17 microseconds (measured on trs80gp's Model 4, which runs at 4 MHz with its wait states).
 */
extern void prog8_trs80m4_pause(unsigned int count) __smallc;

int usleep(unsigned long microseconds)
{
    unsigned long count = microseconds / 17;

    while (count > 65535UL) {
        prog8_trs80m4_pause(65535U);
        count -= 65535UL;
    }
    if (count != 0)
        prog8_trs80m4_pause((unsigned int)count);
    return 0;
}
