// txt.getkey() and txt.waitkey() of the llvm32 and llvm64 targets on Unix and Windows: one key, without waiting for it or
// waiting, not echoed. Unix terminals go to a raw mode at the first call, and back when the program ends or reads a
// character or line with txt.chrin() and txt.input_line(), which call prog8_getchar(). The cursor keys' escape sequences
// become the PETSCII codes of the Commodore targets: 145 up, 17 down, 29 right, 157 left; DEL (127) becomes backspace (8),
// as txt.KEY_BACKSPACE. Input that isn't a terminal is read as it comes.
#include <stdio.h>

#define KEY_UP 145
#define KEY_DOWN 17
#define KEY_RIGHT 29
#define KEY_LEFT 157
#define KEY_BACKSPACE 8

#ifdef _WIN32
#include <conio.h>
#include <io.h>

static int console(void) {
    static int known = 0, result = 0;
    if (!known) {
        result = _isatty(0);
        known = 1;
    }
    return result;
}

// the next byte, waiting for it when wait_ms isn't 0, or -1: none yet, which only a console has, or the end of the input
static int next_byte(int wait_ms) {
    if (!console()) {
        int c = getchar();
        return c == EOF ? -1 : c;
    }
    return wait_ms != 0 || _kbhit() ? _getch() : -1;
}

static void cooked(void) {}

// _getch gives the cursor keys as 0 or 224 and a scan code
static int special_key(int c) {
    if ((c == 0 || c == 224) && console()) {
        switch (_getch()) {
            case 'H': return KEY_UP;
            case 'P': return KEY_DOWN;
            case 'M': return KEY_RIGHT;
            case 'K': return KEY_LEFT;
            default: return 0;
        }
    }
    return -1;
}
#else
#include <poll.h>
#include <stdlib.h>
#include <termios.h>
#include <unistd.h>

static struct termios saved;
static int terminal = -1;   // -1 not known yet, 0 no terminal, 1 a terminal
static int raw = 0;

static void cooked(void) {
    if (raw) {
        tcsetattr(0, TCSANOW, &saved);
        raw = 0;
    }
}

// keeps the output processing, so that "\n" still starts a new line
static void make_raw(void) {
    if (terminal < 0) {
        terminal = isatty(0) && tcgetattr(0, &saved) == 0;
        if (terminal)
            atexit(cooked);
    }
    if (terminal && !raw) {
        struct termios mode = saved;
        mode.c_lflag &= ~(ICANON | ECHO | ISIG | IEXTEN);
        mode.c_iflag &= ~(IXON | ICRNL | INLCR | IGNCR | ISTRIP);
        mode.c_cc[VMIN] = 0;
        mode.c_cc[VTIME] = 0;
        tcsetattr(0, TCSANOW, &mode);
        raw = 1;
    }
}

// the next byte, waiting up to wait_ms for it on a terminal (-1: as long as it takes), or -1
static int next_byte(int wait_ms) {
    unsigned char c;
    if (!terminal) {
        int ch = getchar();
        return ch == EOF ? -1 : ch;
    }
    struct pollfd input = {0, POLLIN, 0};
    if (poll(&input, 1, wait_ms) <= 0)
        return -1;
    return read(0, &c, 1) == 1 ? c : -1;
}

static int special_key(int c) {
    (void)c;
    return -1;
}
#endif

// the bytes of an escape sequence that isn't a cursor key, given one by one
static unsigned char pending[16];
static int pending_count = 0, pending_next = 0;

static int cursor_key(int final) {
    switch (final) {
        case 'A': return KEY_UP;
        case 'B': return KEY_DOWN;
        case 'C': return KEY_RIGHT;
        case 'D': return KEY_LEFT;
        default: return 0;
    }
}

// ESC [ or ESC O, parameters, and a final byte: a cursor key, or else the sequence as it came
static int escape_sequence(void) {
    int c = next_byte(30);
    if (c != '[' && c != 'O') {
        if (c >= 0)
            pending[pending_count++] = (unsigned char)c;
        return 27;
    }
    pending[pending_count++] = (unsigned char)c;
    while ((c = next_byte(30)) >= 0) {
        if (pending_count < (int)sizeof pending)
            pending[pending_count++] = (unsigned char)c;
        if (c >= 0x40 && c <= 0x7e) {
            int key = cursor_key(c);
            if (key) {
                pending_count = 0;
                return key;
            }
            break;
        }
    }
    return 27;
}

// a key, 0 for a key that has no code (a Windows function key), or -1: none, or the end of the input when waiting
static int read_key(int wait) {
    if (pending_next < pending_count) {
        int c = pending[pending_next++];
        if (pending_next == pending_count)
            pending_count = pending_next = 0;
        return c == 127 ? KEY_BACKSPACE : c;
    }
#ifndef _WIN32
    make_raw();
#endif
    int c = next_byte(wait ? -1 : 0);
    if (c < 0)
        return -1;
    int key = special_key(c);
    if (key >= 0)
        return key;
    if (c == 127)
        return KEY_BACKSPACE;
    return c == 27 ? escape_sequence() : c;
}

int prog8_getkey(void) {
    int key = read_key(0);
    return key < 0 ? 0 : key;
}

// 0 only at the end of the input
int prog8_waitkey(void) {
    int key;
    while ((key = read_key(1)) == 0) {}
    return key < 0 ? 0 : key;
}

int prog8_getchar(void) {
    cooked();
    return getchar();
}
