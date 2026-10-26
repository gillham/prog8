; Handle-based file I/O for the llvm32 target, with the C library of the host: any number of files can be open at the same time.
; A handle is the C library's FILE pointer, and 0 means no file; the routines accept a 0 handle, as described with each of them.
; Note: quads are in r99300, as the registers that the IR reserves for syscalls (r99000-r99299) have no quad range.

fileio {
    %option no_symbol_prefixing, ignore_unused

    ; where seek() counts the offset from
    const ubyte SEEK_SET = 0        ; the start of the file
    const ubyte SEEK_CUR = 1        ; the position in the file
    const ubyte SEEK_END = 2        ; the end of the file

    sub open(str path, str mode) -> pointer {
        ; -- opens the file in a mode of C's fopen(), such as "rb", "wb", "ab" or "r+b": returns its handle, or 0 when that fails
        %ir {{
            loadm.p r99200.p,[fileio.open.path]
            loadm.p r99201.p,[fileio.open.mode]
            syscall 202 (r99200.p, r99201.p): r99202.p
            returnr.p r99202.p
        }}
    }

    sub close(pointer handle) -> bool {
        ; -- writes what is still buffered and closes the file: returns false when that fails, or for a 0 handle
        if handle == 0
            return false
        %ir {{
            loadm.p r99200.p,[fileio.close.handle]
            syscall 203 (r99200.p): r99100.b
            returnr.b r99100.b
        }}
    }

    sub read(pointer handle, pointer buf, uword count) -> uword {
        ; -- reads up to count bytes into the buffer: returns how many, which is less at the end of the file or for an error
        if handle == 0
            return 0
        %ir {{
            loadm.p r99200.p,[fileio.read.handle]
            loadm.p r99201.p,[fileio.read.buf]
            loadm.w r99000.w,[fileio.read.count]
            syscall 204 (r99200.p, r99201.p, r99000.w): r99001.w
            returnr.w r99001.w
        }}
    }

    sub write(pointer handle, pointer buf, uword count) -> uword {
        ; -- writes count bytes from the buffer: returns how many, which is less for an error
        if handle == 0
            return 0
        %ir {{
            loadm.p r99200.p,[fileio.write.handle]
            loadm.p r99201.p,[fileio.write.buf]
            loadm.w r99000.w,[fileio.write.count]
            syscall 205 (r99200.p, r99201.p, r99000.w): r99001.w
            returnr.w r99001.w
        }}
    }

    sub seek(pointer handle, quad offset, ubyte whence) -> bool {
        ; -- moves to the offset from where whence says (SEEK_SET, SEEK_CUR or SEEK_END): returns false when that fails, or for a 0 handle
        if handle == 0
            return false
        %ir {{
            loadm.p r99200.p,[fileio.seek.handle]
            loadm.q r99300.q,[fileio.seek.offset]
            loadm.b r99100.b,[fileio.seek.whence]
            syscall 206 (r99200.p, r99300.q, r99100.b): r99101.b
            returnr.b r99101.b
        }}
    }

    sub tell(pointer handle) -> quad {
        ; -- returns the position in the file, or -1 for an error and for a 0 handle
        if handle == 0
            return -1
        %ir {{
            loadm.p r99200.p,[fileio.tell.handle]
            syscall 207 (r99200.p): r99300.q
            returnr.q r99300.q
        }}
    }

    sub flush(pointer handle) -> bool {
        ; -- writes what is still buffered for the file, or for every file for a 0 handle: returns false when that fails
        %ir {{
            loadm.p r99200.p,[fileio.flush.handle]
            syscall 208 (r99200.p): r99100.b
            returnr.b r99100.b
        }}
    }

    sub eof(pointer handle) -> bool {
        ; -- returns true when a read went past the end of the file (until the next seek), and for a 0 handle
        if handle == 0
            return true
        %ir {{
            loadm.p r99200.p,[fileio.eof.handle]
            syscall 209 (r99200.p): r99100.b
            returnr.b r99100.b
        }}
    }
}
