/*
 * The file routines of the z80-trs80-m4 profile, under the names of the no-DOS stubs that its link command links (-lndos),
 * which z88dk's stdio calls: open, close, read, write, readbyte, writebyte, lseek, fdtell, fdgetpos, remove, rename and
 * fsync, through the supervisor calls of TRSDOS 6 and LS-DOS 6. A file is the DOS's file control block (FCB) and its
 * sector buffer, with byte I/O through @GET and @PUT. The position and size come from the FCB, which the DOS keeps up to
 * date in reading and writing; a seek moves to the record with @POSN and reads its way to the byte, so it can't go past
 * the end of the file. Also an fread (c_symbols) that sets the end of the file, which z88dk's never does; opendir, readdir
 * and closedir for the files of the current drive (else drive 0), the directory "." (the DOS has no subdirectories), through
 * @RAMDIR; chdir and getcwd for that current drive, ":0" to ":7", which file names without a drive spec then get; and a qsort
 * (c_symbols) for the sorted directory listing, as z88dk's calls the comparison with the arguments swapped. Linked when
 * the program opens, renames or removes files, lists a directory, or asks for the drive of the last file opened
 * (file_source).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>

#define SVC_GET 3
#define SVC_PUT 4
#define SVC_CKDRV 33
#define SVC_RAMDIR 35
#define SVC_RENAM 56
#define SVC_REMOV 57
#define SVC_INIT 58
#define SVC_OPEN 59
#define SVC_CLOSE 60
#define SVC_PEOF 65
#define SVC_POSN 66
#define SVC_FSPEC 78

struct prog8_trs80m4_file {
    unsigned char fcb[32];
    unsigned char buffer[256];
};

/* A of the last supervisor call, and whether it set the Z flag (success for most) */
static unsigned char svc_result;
static unsigned char svc_zero;

/* the drive the last open found or created its file on (the FCB's byte 6), or 255 when it failed: diskio.last_drive() */
static unsigned char last_drive = 255;

int prog8_trs80_last_drive(void)
{
    return last_drive;
}

/* the file spec as the DOS wants it: upper case, ended by a carriage return */
static char spec[32];

/* the drive that chdir(":d") made current, which file names without a drive spec get; -1 for none: the DOS then searches the
   drives for a file that it opens, and creates a new one on the first drive it can write to */
static signed char current_drive = -1;

/* calls the supervisor call fn with BC, DE and HL; leaves A in svc_result and the Z flag in svc_zero */
static void svc(int fn, int bc, int de, int hl) __smallc __naked
{
#asm
    ld      hl,6
    add     hl,sp
    ld      c,(hl)
    inc     hl
    ld      b,(hl)
    inc     hl
    ld      a,(hl)          ; fn
    push    af
    ld      hl,6
    add     hl,sp
    ld      e,(hl)
    inc     hl
    ld      d,(hl)          ; de
    push    de
    ld      hl,6
    add     hl,sp
    ld      e,(hl)
    inc     hl
    ld      d,(hl)
    ex      de,hl           ; hl
    pop     de
    pop     af
    rst     $28
    ld      (_svc_result),a
    ld      a,0
    jr      nz,svc_not_zero
    inc     a
svc_not_zero:
    ld      (_svc_zero),a
    ret
#endasm
}

/* puts the name in spec, and the FCB gets it from @FSPEC; 0 when the name is a valid file spec. The FCB is cleared first:
   @FSPEC only writes the name, and the DOS's @INIT fails on what an earlier file left in the rest */
/* the name in upper case into the buffer of the given size, with ":d" of the current drive when it has no drive spec of its
   own, ended by a carriage return */
static void dos_name(char *name, char *buffer, int size)
{
    int i;
    int drive = 0;
    for (i = 0; name[i] != 0 && i < size - 3; i++) {
        buffer[i] = name[i] >= 'a' && name[i] <= 'z' ? name[i] - 32 : name[i];
        if (name[i] == ':')
            drive = 1;
    }
    if (!drive && current_drive >= 0) {
        buffer[i++] = ':';
        buffer[i++] = '0' + current_drive;
    }
    buffer[i] = 13;
}

static int file_spec(char *name, unsigned char *fcb)
{
    memset(fcb, 0, 32);
    dos_name(name, spec, sizeof(spec));
    svc(SVC_FSPEC, 0, (int)fcb, (int)spec);
    return svc_zero ? 0 : -1;
}

static long size_of(unsigned char *fcb)
{
    long records = fcb[12] | (fcb[13] << 8);
    if (fcb[8] == 0)
        return records * 256;
    return (records - 1) * 256 + fcb[8];
}

/* the next byte's position: the record number is the next one to read while a sector is in the buffer (bit 5 clear) */
static long position_of(unsigned char *fcb)
{
    long position = (long)(fcb[10] | (fcb[11] << 8)) * 256 + fcb[5];
    if (!(fcb[1] & 0x20))
        position -= 256;
    return position;
}

int open(char *name, int flags, mode_t mode)
{
    struct prog8_trs80m4_file *file = malloc(sizeof(struct prog8_trs80m4_file));
    unsigned char *fcb;

    last_drive = 255;
    if (file == NULL)
        return -1;
    fcb = file->fcb;
    if (file_spec(name, fcb) != 0) {
        free(file);
        return -1;
    }
    if (flags & O_TRUNC) {
        /* the DOS can't shorten a file: remove it, and create it anew */
        svc(SVC_OPEN, 0, (int)fcb, (int)file->buffer);
        if (svc_zero) {
            svc(SVC_REMOV, 0, (int)fcb, 0);
            if (!svc_zero || file_spec(name, fcb) != 0) {
                free(file);
                return -1;
            }
        }
    }
    /* B = 0: records of 256 bytes; @INIT creates a file that doesn't exist */
    svc((flags & O_CREAT) ? SVC_INIT : SVC_OPEN, 0, (int)fcb, (int)file->buffer);
    if (!svc_zero) {
        free(file);
        return -1;
    }
    last_drive = fcb[6] & 7;
    if (flags & O_APPEND)
        svc(SVC_PEOF, 0, (int)fcb, 0);
    return (int)file;
}

int close(int fd)
{
    svc(SVC_CLOSE, 0, fd, 0);
    free((void *)fd);
    return svc_zero ? 0 : -1;
}

/* the next byte, or -1 at the end of the file or for an error */
static int get_byte(int fd)
{
    svc(SVC_GET, 0, fd, 0);
    return svc_zero ? svc_result : -1;
}

ssize_t read(int fd, void *ptr, size_t len)
{
    unsigned char *bytes = ptr;
    size_t count;
    int c;
    for (count = 0; count < len; count++) {
        if ((c = get_byte(fd)) < 0)
            break;
        bytes[count] = c;
    }
    return count;
}

ssize_t write(int fd, void *ptr, size_t len)
{
    unsigned char *bytes = ptr;
    size_t count;
    for (count = 0; count < len; count++) {
        svc(SVC_PUT, bytes[count], fd, 0);
        if (!svc_zero)
            break;
    }
    return count;
}

size_t prog8_trs80m4_fread(void *ptr, size_t size, size_t nmemb, FILE *fp)
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

/* int writebyte(int fd, int byte): 0, or -1 for an error */
int writebyte(int fd, int byte)
{
    svc(SVC_PUT, byte & 255, fd, 0);
    return svc_zero ? 0 : -1;
}

long fdtell(int fd)
{
    return position_of((unsigned char *)fd);
}

int fdgetpos(int fd, fpos_t *position)
{
    *position = position_of((unsigned char *)fd);
    return 0;
}

long lseek(int fd, long offset, int whence)
{
    unsigned char *fcb = (unsigned char *)fd;
    long size = size_of(fcb);
    long target;
    int skip;

    if (whence == SEEK_SET)
        target = offset;
    else if (whence == SEEK_CUR)
        target = position_of(fcb) + offset;
    else if (whence == SEEK_END)
        target = size + offset;
    else
        return -1L;
    if (target < 0 || target > size)
        return -1L;
    if (target == size) {
        /* @PEOF reports the end of the file as its result */
        svc(SVC_PEOF, 0, fd, 0);
        return position_of(fcb) == size ? size : -1L;
    }
    svc(SVC_POSN, (int)(target >> 8), fd, 0);
    if (!svc_zero)
        return -1L;
    for (skip = (int)(target & 255); skip > 0; skip--)
        if (get_byte(fd) < 0)
            return -1L;
    return target;
}

int remove(char *name)
{
    struct prog8_trs80m4_file *file = malloc(sizeof(struct prog8_trs80m4_file));
    int result = -1;

    if (file == NULL)
        return -1;
    if (file_spec(name, file->fcb) == 0) {
        svc(SVC_OPEN, 0, (int)file->fcb, (int)file->buffer);
        if (svc_zero) {
            svc(SVC_REMOV, 0, (int)file->fcb, 0);
            result = svc_zero ? 0 : -1;
        }
    }
    free(file);
    return result;
}

/* @RENAM: the FCB has the old file spec, HL the new one; it fails when the new name exists */
int rename(char *oldname, char *newname)
{
    static unsigned char fcb[32];
    static char newspec[32];

    if (file_spec(oldname, fcb) != 0)
        return -1;
    dos_name(newname, newspec, sizeof(newspec));
    svc(SVC_RENAM, 0, (int)fcb, (int)newspec);
    return svc_zero ? 0 : -1;
}

/* chdir(":d") makes drive d (0-7) the current drive, when it is ready (@CKDRV); "" or ":" goes back to none */
int chdir(char *path)
{
    if (path[0] == 0 || (path[0] == ':' && path[1] == 0)) {
        current_drive = -1;
        return 0;
    }
    if (path[0] != ':' || path[1] < '0' || path[1] > '7' || path[2] != 0)
        return -1;
    svc(SVC_CKDRV, path[1] - '0', 0, 0);
    if (!svc_zero)
        return -1;
    current_drive = path[1] - '0';
    return 0;
}

/* ":d" for the current drive, or "" without one */
char *getcwd(char *buffer, size_t size)
{
    if (size < 3)
        return NULL;
    if (current_drive < 0) {
        buffer[0] = 0;
    } else {
        buffer[0] = ':';
        buffer[1] = '0' + current_drive;
        buffer[2] = 0;
    }
    return buffer;
}

/* the directory of the current drive (else drive 0): @RAMDIR's entries of 22 bytes, the name and drive padded to 15 characters, after a '+' */
#define DIR_ENTRY 22
#define DIR_ENTRIES 256

struct prog8_trs80m4_dirent {
    char d_name[16];
};

struct prog8_trs80m4_dir {
    unsigned char *next;
    struct prog8_trs80m4_dirent entry;
    unsigned char entries[DIR_ENTRY * DIR_ENTRIES + 1];
};

void *opendir(char *path)
{
    struct prog8_trs80m4_dir *dir;

    if (path[0] != '.' || path[1] != 0)
        return NULL;
    dir = malloc(sizeof(struct prog8_trs80m4_dir));
    if (dir == NULL)
        return NULL;
    /* B = the drive, C = 0: all the entries */
    svc(SVC_RAMDIR, (current_drive < 0 ? 0 : current_drive) << 8, 0, (int)dir->entries);
    if (!svc_zero) {
        free(dir);
        return NULL;
    }
    dir->next = dir->entries;
    return dir;
}

struct prog8_trs80m4_dirent *readdir(void *handle)
{
    struct prog8_trs80m4_dir *dir = handle;
    int i;

    if (*dir->next == '+' || dir->next >= dir->entries + DIR_ENTRY * DIR_ENTRIES)
        return NULL;
    /* the name without the drive spec and the padding */
    for (i = 0; i < 15 && dir->next[i] != ':' && dir->next[i] != ' '; i++)
        dir->entry.d_name[i] = dir->next[i];
    dir->entry.d_name[i] = 0;
    dir->next += DIR_ENTRY;
    return &dir->entry;
}

int closedir(void *handle)
{
    free(handle);
    return 0;
}

/* an insertion sort, as the directory listing sorts its names with it */
void prog8_trs80m4_qsort(void *base, size_t count, size_t size, int (*compare)(void *, void *))
{
    unsigned char *array = base;
    unsigned char *a, *b;
    unsigned char swap;
    size_t i, j, k;

    for (i = 1; i < count; i++) {
        for (j = i; j > 0; j--) {
            a = array + (j - 1) * size;
            b = array + j * size;
            if (compare(a, b) <= 0)
                break;
            for (k = 0; k < size; k++) {
                swap = a[k];
                a[k] = b[k];
                b[k] = swap;
            }
        }
    }
}

/* readbyte, which stdio calls with the file in HL and which sets carry at the end of the file, and fsync, which has
   nothing to do, as the DOS writes the buffer when it's full (sccz80 defines the other names of the no-DOS stubs along
   with the C functions, as fcntl.h and stdio.h declare them) */
static void prog8_trs80m4_file_names(void) __naked
{
#asm
    PUBLIC  readbyte, _readbyte, fsync, _fsync
readbyte:
_readbyte:
    ex      de,hl
    ld      a,3             ; @GET
    rst     $28
    jr      nz,readbyte_end
    ld      l,a
    ld      h,0
    and     a
    ret
readbyte_end:
    ld      hl,-1
    scf
    ret
fsync:
_fsync:
    ld      hl,0
    ret
#endasm
}
