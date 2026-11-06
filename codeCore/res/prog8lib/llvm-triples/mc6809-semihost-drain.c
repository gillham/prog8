/*
 * USim's MC6850 console ACIA ($FFC4 status, $FFC5 data) sends a written byte only some cycles later, and picolibc's _exit
 * halts the simulator at once, which loses the last byte. This waits until the transmit register is empty again;
 * as a destructor it runs both when main returns and on exit().
 */
__attribute__((destructor)) static void prog8_drain_console(void) {
    while (!(*(volatile unsigned char *)0xffc4 & 2))
        ;
}
