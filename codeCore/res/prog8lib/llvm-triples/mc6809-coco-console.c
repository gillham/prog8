/*
 * Prog8's console output on DECB: each character goes straight to Color BASIC's CHROUT, without picolibc's line-buffered
 * stdout, so it stays in order with what the coco textio writes to the screen itself. CHROUT ignores LF, so '\n' becomes CR.
 * And txt.getkey's key poll, as a Prog8 extsub name can't start with an underscore.
 */
void __decb_putc(char c);
char __decb_getc(void);

int prog8_coco_putchar(int c) {
    __decb_putc(c == '\n' ? '\r' : (char)c);
    return c;
}

/* Color BASIC's POLCAT: the key pressed, or 0 at once */
int prog8_coco_getkey(void) {
    return (unsigned char)__decb_getc();
}
