/*
 * The mc6809-coco profile's linker script: llvm-mc6809's mc6809-decb.lds with the load address of the program
 * (%address or -address) in place of its fixed $3F00. The compiler replaces {load_address}, which is page aligned.
 * The direct page is the first page of the program; crt0 needs the symbols defined here.
 */

OUTPUT_FORMAT(decb-program)
ENTRY(_start)

__dp_base_addr = {load_address};
__dp_base = __dp_base_addr >> 8;

/* the compiler's imaginary registers at the bottom of the direct page, as in llvm-mc6809's scripts */
PROVIDE(__rc0  = __dp_base_addr + 0x00);
PROVIDE(__rc1  = __dp_base_addr + 0x01);
PROVIDE(__rc2  = __dp_base_addr + 0x02);
PROVIDE(__rc3  = __dp_base_addr + 0x03);
PROVIDE(__rc4  = __dp_base_addr + 0x04);
PROVIDE(__rc5  = __dp_base_addr + 0x05);
PROVIDE(__rc6  = __dp_base_addr + 0x06);
PROVIDE(__rc7  = __dp_base_addr + 0x07);
PROVIDE(__rs0  = __dp_base_addr + 0x08);
PROVIDE(__rs1  = __dp_base_addr + 0x0A);
PROVIDE(__rs2  = __dp_base_addr + 0x0C);
PROVIDE(__rs3  = __dp_base_addr + 0x0E);
PROVIDE(__rs0hi = __rs0); PROVIDE(__rs0lo = __rs0 + 1);
PROVIDE(__rs1hi = __rs1); PROVIDE(__rs1lo = __rs1 + 1);
PROVIDE(__rs2hi = __rs2); PROVIDE(__rs2lo = __rs2 + 1);
PROVIDE(__rs3hi = __rs3); PROVIDE(__rs3lo = __rs3 + 1);
PROVIDE(__rc8  = __dp_base_addr + 0x10);
PROVIDE(__rc9  = __dp_base_addr + 0x11);
PROVIDE(__rc10 = __dp_base_addr + 0x12);
PROVIDE(__rc11 = __dp_base_addr + 0x13);
PROVIDE(__rc12 = __dp_base_addr + 0x14);
PROVIDE(__rc13 = __dp_base_addr + 0x15);
PROVIDE(__rc14 = __dp_base_addr + 0x16);
PROVIDE(__rc15 = __dp_base_addr + 0x17);

SECTIONS {
    .dp __dp_base_addr : {
        . = __dp_base_addr + 0x18;
        *(.dp.data .dp.data.*)
        *(.dp.rodata .dp.rodata.*)
        __dp_bss_start = .;
        *(.dp.bss .dp.bss.*)
        __dp_bss_end = .;
        . = __dp_base_addr + 256;
    }

    .text : {
        *(.text.startup .text.startup.*)
        *(.text .text.*)
        *(.rodata .rodata.*)
    }

    /* LOADM puts the initialised data in place, so nothing copies it */
    .data : ALIGN(2) {
        *(.data .data.*)
    }

    .bss (NOLOAD) : ALIGN(2) {
        __bss_start = .;
        *(.bss .bss.*)
        *(COMMON)
        __bss_end = .;
    }

    PROVIDE(__heap_start = .);

    /DISCARD/ : {
        *(.comment)
        *(.note .note.*)
        *(.eh_frame .eh_frame_hdr)
        *(.ARM.*)
    }
}
