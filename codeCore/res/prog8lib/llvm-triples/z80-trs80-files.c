/*
 * The file position routines of the TRS-80 profiles, which z88dk's TRS-80 library lacks or gets wrong:
 * - ftell (the profile's ftell_symbol), from the DOS's file control block. Only for a file open for reading: TRSDOS 2.3
 *   and LDOS keep the position of a file being written differently, so for one of those it gives -1.
 * - fseek (fseek_symbol), as z88dk's lseek, which reads its way to the position, only moves forward with SEEK_CUR and
 *   ignores the offset of SEEK_END. A seek past the end of a file being read fails, as lseek stops at the end.
 * - fread (fread_symbol), as z88dk's never sets the end of file.
 * - fsync, which fflush calls: z88dk's always fails, while byte I/O through the DOS has nothing to flush at this level.
 */
#include <stdio.h>
#include <fcntl.h>
#include <trsdos.h>

long prog8_trs80_ftell(FILE *fp)
{
    struct TRSDOS_FCB *fcb;
    long position;

    if ((fp->flags & (_IOREAD | _IOWRITE | _IOSYSTEM)) != _IOREAD)
        return -1L;
    fcb = &((struct TRSDOS_FILE *)fp->desc.fd)->fcb;
    position = (long)fcb->NRN * 256 + fcb->offset;
    /* bit 5 of the FCB flags is clear while a sector is in the buffer: the record number is then the next one */
    if (!(fcb->flags & 0x20))
        position -= 256;
    return position;
}

int prog8_trs80_fseek(FILE *fp, long offset, int whence)
{
    long target;

    if (whence == SEEK_SET) {
        target = offset;
    } else if (whence == SEEK_CUR) {
        target = prog8_trs80_ftell(fp);
        if (target < 0)
            return -1;
        target += offset;
    } else if (whence == SEEK_END) {
        if (fseek(fp, 0L, SEEK_END) != 0)
            return -1;
        target = prog8_trs80_ftell(fp);
        if (target < 0)
            return -1;
        target += offset;
    } else {
        return -1;
    }
    if (target < 0)
        return -1;
    if (fseek(fp, target, SEEK_SET) != 0)
        return -1;
    if ((fp->flags & _IOREAD) && prog8_trs80_ftell(fp) != target)
        return -1;
    return 0;
}

size_t prog8_trs80_fread(void *ptr, size_t size, size_t nmemb, FILE *fp)
{
    unsigned int length = size * nmemb;
    unsigned int count;

    if ((fp->flags & (_IOREAD | _IOSYSTEM)) != _IOREAD || length == 0)
        return 0;
    count = read(fp->desc.fd, ptr, length);
    if (count < length)
        fp->flags |= _IOEOF;
    return count / size;
}

static void fsync_wrapper(void) __naked
{
#asm
    PUBLIC  fsync
    PUBLIC  _fsync
fsync:
_fsync:
    ld      hl,0
    ret
#endasm
}
