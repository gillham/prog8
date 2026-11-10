/*
 * The file routines of the TRS-80 profiles that z88dk's TRS-80 library lacks or gets wrong:
 * - ftell (the profile's ftell_symbol). For a file being read, from the DOS's file control block; for one being
 *   written, a byte count of our own, kept by the fopen, fwrite and fputc here (c_symbols), as TRSDOS 2.3 and LDOS
 *   keep that position differently in the FCB. -1 for a file open for both.
 * - fseek (fseek_symbol), as z88dk's lseek, which reads its way to the position, only moves forward with SEEK_CUR,
 *   ignores the offset of SEEK_END, and rewinds by closing and opening the file again, which fails under TRSDOS 1.3.
 *   This one rewinds through the DOS and reads its way forward. A seek past the end of a file being read fails; a file
 *   being written only seeks to where it is.
 * - fread (c_symbols), as z88dk's never sets the end of file.
 * - appending (fopen with mode "a"), as z88dk's writes garbage after the old end of the file under TRSDOS 2.3.
 * - remove (c_symbols), as z88dk's opens the file without a sector buffer to kill it, which fails under TRSDOS 2.3.
 * - rename (c_symbols), which z88dk lacks and no DOS call does: it copies the file to the new name and deletes the old
 *   one, so it needs room for the copy, and the copy gets the date of the rename and no password. It fails when the
 *   new name exists.
 * - the drive of the last file opened, for diskio.last_drive() (last_drive_symbol).
 * - fsync, which fflush calls: z88dk's always fails, while byte I/O through the DOS has nothing to flush at this level.
 */
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <trsdos.h>

#define READING(fp) (((fp)->flags & (_IOREAD | _IOWRITE | _IOSYSTEM)) == _IOREAD)
#define WRITING(fp) (((fp)->flags & (_IOREAD | _IOWRITE | _IOSYSTEM)) == _IOWRITE)

/* the position in a file being read */
static long read_position(FILE *fp)
{
    struct TRSDOS_FCB *fcb = &((struct TRSDOS_FILE *)fp->desc.fd)->fcb;
    long position = (long)fcb->NRN * 256 + fcb->offset;
#ifndef PROG8_TRS80_TRSDOS13
    /* bit 5 of the FCB flags is clear while a sector is in the buffer: the record number is then the next one, except
       under TRSDOS 1.3, where it is the one in the buffer */
    if (!(fcb->flags & 0x20))
        position -= 256;
#endif
    return position;
}

/* the position in a file being written, in the three bytes at the end of the FILE that z88dk doesn't use */
static long written(FILE *fp)
{
    return (long)fp->reserved | ((long)fp->reserved1 << 8) | ((long)fp->reserved2 << 16);
}

static void set_written(FILE *fp, long position)
{
    fp->reserved = position;
    fp->reserved1 = position >> 8;
    fp->reserved2 = position >> 16;
}

/* the drive the last fopen found or created its file on (the FCB's drive), or 255 when it failed: diskio.last_drive() */
static unsigned char last_drive = 255;

int prog8_trs80_last_drive(void)
{
    return last_drive;
}

static FILE *opened(FILE *fp)
{
    last_drive = fp == NULL ? 255 : ((struct TRSDOS_FILE *)fp->desc.fd)->fcb.drive & 7;
    return fp;
}

long prog8_trs80_ftell(FILE *fp)
{
    if (READING(fp))
        return read_position(fp);
    if (WRITING(fp))
        return written(fp);
    return -1L;
}

FILE *prog8_trs80_fopen(char *name, char *mode)
{
    FILE *fp;
    /* z88dk's append reads to the end and writes from there, which writes garbage under TRSDOS 2.3: open the file
       for reading instead, read to the end for its size, let the DOS position to the end, and make it a file being written */
    if (*mode == 'a' && (fp = fopen(name, "r")) != NULL) {
        long size;
        fseek(fp, 0L, SEEK_END);
        size = read_position(fp);
        trsdos(DOS_POS_EOF, 0, (char *)&((struct TRSDOS_FILE *)fp->desc.fd)->fcb);
        fp->flags = (fp->flags & ~_IOREAD) | _IOWRITE;
        set_written(fp, size);
        return opened(fp);
    }
    fp = fopen(name, mode);
    if (fp != NULL && WRITING(fp))
        set_written(fp, 0L);
    return opened(fp);
}

size_t prog8_trs80_fwrite(void *ptr, size_t size, size_t nmemb, FILE *fp)
{
    size_t count = fwrite(ptr, size, nmemb, fp);
    if (WRITING(fp))
        set_written(fp, written(fp) + (long)count * size);
    return count;
}

int prog8_trs80_fputc(int c, FILE *fp)
{
    int result = fputc(c, fp);
    if (result != EOF && WRITING(fp))
        set_written(fp, written(fp) + 1);
    return result;
}

/* moves a file being read to target by reading its way there, from the start if target is behind */
static int seek_read(FILE *fp, long target)
{
    struct TRSDOS_FCB *fcb = &((struct TRSDOS_FILE *)fp->desc.fd)->fcb;
    long position = read_position(fp);

    if (target < position) {
        if (trsdos_tst(DOS_REWIND, 0, (char *)fcb))
            return -1;
        position = 0;
    }
    while (position < target && trsdos_get(fcb) != -1)
        position++;
    fp->flags &= ~_IOEOF;
    return position == target ? 0 : -1;
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
    } else if (whence == SEEK_END && READING(fp)) {
        while (trsdos_get(&((struct TRSDOS_FILE *)fp->desc.fd)->fcb) != -1)
            ;
        target = read_position(fp) + offset;
    } else if (whence == SEEK_END && WRITING(fp)) {
        target = written(fp) + offset;
    } else {
        return -1;
    }
    if (target < 0)
        return -1;
    if (WRITING(fp))
        return target == written(fp) ? 0 : -1;
    if (!READING(fp))
        return fseek(fp, target, SEEK_SET) != 0 ? -1 : 0;
    return seek_read(fp, target);
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

/* the FCB and buffer of remove, whose buffer rename copies through before it removes */
static struct TRSDOS_FILE scratch;

int prog8_trs80_remove(char *name)
{
    if (initdcb(name, &scratch.fcb))
        return -1;
    if (trsdos_tst(DOS_OPEN_EX, scratch.buffer, (char *)&scratch.fcb))
        return -1;
    return trsdos_tst(DOS_KILL, 0, (char *)&scratch.fcb) ? -1 : 0;
}

int prog8_trs80_rename(char *oldname, char *newname)
{
    FILE *in, *out;
    int count, failed = 0;

    if ((in = fopen(oldname, "r")) == NULL)
        return -1;
    if ((out = fopen(newname, "r")) != NULL) {
        fclose(out);
        fclose(in);
        return -1;
    }
    if ((out = fopen(newname, "w")) == NULL) {
        fclose(in);
        return -1;
    }
    while ((count = read(in->desc.fd, scratch.buffer, sizeof(scratch.buffer))) > 0) {
        if (write(out->desc.fd, scratch.buffer, count) != count) {
            failed = 1;
            break;
        }
    }
    fclose(in);
    if (fclose(out) != 0)
        failed = 1;
    if (failed) {
        prog8_trs80_remove(newname);
        return -1;
    }
    return prog8_trs80_remove(oldname);
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
