// The Microsoft C runtime translates \n to \r\n in text mode, and has no usleep, roundeven or directory routines:
// this gives Prog8 programs the byte streams and the routines that the other platforms have.
#define _CRT_SECURE_NO_WARNINGS
#include <fcntl.h>
#include <io.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>

__attribute__((constructor))
static void binary_streams(void) {
    _set_fmode(_O_BINARY);
    _setmode(0, _O_BINARY);
    _setmode(1, _O_BINARY);
    _setmode(2, _O_BINARY);
}

int usleep(unsigned int microseconds) {
    Sleep((microseconds + 999) / 1000);
    return 0;
}

// rint rounds halfway cases to even in the default rounding mode
double roundeven(double value) {
    return rint(value);
}

// the profile's dirent_name_offset is 0: the name is all there is of an entry
struct dirent {
    char d_name[MAX_PATH];
};

typedef struct {
    HANDLE find;
    WIN32_FIND_DATAA data;
    int first;
    struct dirent entry;
} DIR;

DIR *opendir(const char *path) {
    char pattern[MAX_PATH + 3];
    snprintf(pattern, sizeof pattern, "%s\\*", path);
    DIR *dir = malloc(sizeof(DIR));
    if (dir == NULL)
        return NULL;
    dir->find = FindFirstFileA(pattern, &dir->data);
    if (dir->find == INVALID_HANDLE_VALUE) {
        free(dir);
        return NULL;
    }
    dir->first = 1;
    return dir;
}

struct dirent *readdir(DIR *dir) {
    if (!dir->first && !FindNextFileA(dir->find, &dir->data))
        return NULL;
    dir->first = 0;
    strncpy(dir->entry.d_name, dir->data.cFileName, MAX_PATH - 1);
    dir->entry.d_name[MAX_PATH - 1] = 0;
    return &dir->entry;
}

int closedir(DIR *dir) {
    FindClose(dir->find);
    free(dir);
    return 0;
}
