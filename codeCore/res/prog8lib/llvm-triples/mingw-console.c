// The Windows C runtime translates \n to \r\n in text mode, and mingw-w64 has no roundeven: this gives Prog8 programs
// the byte streams and the routine of the other platforms. mingw-w64 already has usleep and the directory routines.
#include <fcntl.h>
#include <io.h>
#include <math.h>
#include <stdlib.h>

__attribute__((constructor))
static void binary_streams(void) {
    _set_fmode(_O_BINARY);
    _setmode(0, _O_BINARY);
    _setmode(1, _O_BINARY);
    _setmode(2, _O_BINARY);
}

// rint rounds halfway cases to even in the default rounding mode
double roundeven(double value) {
    return rint(value);
}
