; File I/O routines for the llvm64 target, on the filesystem of the host.
;
; NOTE: some calls differ from the "official" diskio library, because a hosted 64-bit program has no carry flag to return
;       and no 16-bit addresses: f_readline() returns a status instead, and load() and load_raw() return a pointer.
; NOTE: there are no directory listings. The fileio module has routines for several open files at the same time.

%import textio
%import syslib
%import fileio

diskio {
    %option no_symbol_prefixing, ignore_unused

    sub diskname() -> str {
        ; -- Returns the name of the "disk", which is the filesystem of the host.
        return "host-filesystem"
    }


    ; ----- iterative file loader functions -----

    sub f_open(str filenameptr) -> bool {
        ; -- open a file for iterative reading with f_read, f_read_all and f_readline
        ;    note: only a single file can be open for reading at a time; opening the next one closes it.
        ;    Returns true if the file is successfully opened and readable; else the file that was open stays open.
        %ir {{
            loadm.p r99200.p,[diskio.f_open.filenameptr]
            syscall 52 (r99200.p): r99100.b
            returnr.b r99100.b
        }}
    }

    sub f_read(pointer bufferpointer, uword num_bytes) -> uword {
        ; -- read from the currently open file, up to the given number of bytes.
        ;    returns the actual number of bytes read.  (checks for End-of-file and error conditions)
        uword actual
        uword data
        repeat num_bytes {
            data = internal_read_byte()
            if data == 0
                return actual
            @(bufferpointer) = lsb(data)
            bufferpointer++
            actual++
        }
        return actual
    }

    sub f_read_all(pointer bufferpointer) -> uword {
        ; -- read the full rest of the file, returns number of bytes read.
        ;    It is assumed the file size is less than 64 K.
        ;    Usually you will just be using load() / load_raw() to read entire files!
        uword actual
        uword data
        repeat {
            data = internal_read_byte()
            if data == 0
                return actual
            @(bufferpointer) = lsb(data)
            bufferpointer++
            actual++
        }
    }

    sub f_readline(pointer bufptr) -> ubyte, ubyte {
        ; Routine to read text lines from a text file.
        ; Reads characters from the input file UNTIL a newline or return character, or 0 byte, or the end of the file.
        ; The line read will be 0-terminated in the buffer (and not contain the end of line character).
        ; A line longer than 255 characters continues in the next call, so the buffer needs room for 256 bytes.
        ; Returns the length of the line (an empty line is okay and is length 0!), and a status byte:
        ; 0 when the line ended, $40 when the file ended instead, or there was an error, or no file was open.
        ubyte size
        uword data
        repeat {
            data = internal_read_byte()
            if data == 0 {
                @(bufptr) = 0
                return size, $40
            }
            if lsb(data) in ['\n', '\r', 0] {
                @(bufptr) = 0
                return size, 0
            }
            @(bufptr) = lsb(data)
            bufptr++
            size++
            if size == 255 {
                @(bufptr) = 0
                return size, 0
            }
        }
    }

    sub f_close() {
        ; -- end an iterative file loading session (close channels).
        ;    it is safe to call this multiple times, or when no file is open for reading.
        %ir {{
            syscall 56 ()
        }}
    }

    sub f_seek(long position) -> bool {
        ; -- seek in the reading file opened with f_open, to the given 32-bits position
        ;    Returns true if successful, false if the position is invalid or file not open.
        %ir {{
            loadm.l r99200.l,[diskio.f_seek.position]
            syscall 63 (r99200.l): r99100.b
            returnr.b r99100.b
        }}
    }

    sub f_tell() -> long, long {
        ; -- Returns the current read position of the opened read file, and the file size.
        long @shared pos, size
        %ir {{
            syscall 64 (): r99200.l
            storem.l r99200.l,[diskio.f_tell.pos]
            syscall 65 (): r99200.l
            storem.l r99200.l,[diskio.f_tell.size]
        }}
        return pos, size
    }

    sub f_size() -> long {
        ; -- Returns the size of the opened read file.
        %ir {{
            syscall 65 (): r99200.l
            returnr.l r99200.l
        }}
    }


    ; ----- iterative file writing functions -----

    sub f_open_w(str filenameptr) -> bool {
        ; -- open a file for iterative writing with f_write.
        ;    An existing file is overwritten. Returns true if the file is successfully opened.
        %ir {{
            loadm.p r99200.p,[diskio.f_open_w.filenameptr]
            syscall 53 (r99200.p): r99100.b
            returnr.b r99100.b
        }}
    }

    sub f_write(pointer bufferpointer, uword num_bytes) -> bool {
        ; -- write the given number of bytes to the currently open file
        ;    you can call this multiple times to append more data
        repeat num_bytes {
            if not internal_write_byte(@(bufferpointer))
                return false
            bufferpointer++
        }
        return true
    }

    sub f_close_w() {
        ; -- end an iterative file writing session (close channels).
        ;    it is safe to call this multiple times, or when no file is open for writing.
        %ir {{
            syscall 57 ()
        }}
    }


    ; ---- other functions ----

    sub chdir(str path) {
        ; -- change current directory.
        %ir {{
            loadm.p r99200.p,[diskio.chdir.path]
            syscall 50 (r99200.p)
        }}
    }

    sub mkdir(str name) {
        ; -- make a new subdirectory.
        %ir {{
            loadm.p r99200.p,[diskio.mkdir.name]
            syscall 49 (r99200.p)
        }}
    }

    sub rmdir(str name) {
        ; -- remove a subdirectory.
        %ir {{
            loadm.p r99200.p,[diskio.rmdir.name]
            syscall 51 (r99200.p)
        }}
    }

    sub curdir() -> str {
        ; return current directory name or 0 if error
        ; the name is in a buffer that the next call overwrites.
        %ir {{
            syscall 48 (): r99200.p
            returnr.p r99200.p
        }}
    }

    sub status() -> str {
        ; -- retrieve the disk drive's current status message
        return "unknown"
    }

    sub status_code() -> ubyte {
        ; -- return status code instead of whole CBM-DOS status string. (in this case always 255, which means 'unable to return sensible value')
        return 255
    }


    sub save(str filenameptr, pointer start_address, uword savesize) -> bool {
        ; -- save the memory block in a file, after a 2-byte prg header with the lower 16 bits of the start address.
        ;    An existing file is overwritten. Returns true if successful.
        %ir {{
            load.b r99100.b,#0.b
            loadm.p r99200.p,[diskio.save.filenameptr]
            loadm.p r99201.p,[diskio.save.start_address]
            loadm.w r99000.w,[diskio.save.savesize]
            syscall 42 (r99100.b, r99200.p, r99201.p, r99000.w): r99101.b
            returnr.b r99101.b
        }}
    }

    ; like save() but omits the 2 byte prg header.
    sub save_raw(str filenameptr, pointer start_address, uword savesize) -> bool {
        %ir {{
            load.b r99100.b,#1.b
            loadm.p r99200.p,[diskio.save_raw.filenameptr]
            loadm.p r99201.p,[diskio.save_raw.start_address]
            loadm.w r99000.w,[diskio.save_raw.savesize]
            syscall 42 (r99100.b, r99200.p, r99201.p, r99000.w): r99101.b
            returnr.b r99101.b
        }}
    }

    ; Load the given file in memory at the address, skipping the 2-byte prg header in the file.
    ; The address can't be 0: a hosted program can't load to the address in the header.
    ; Returns the end load address+1 if successful or 0 if a load error occurred.
    sub load(str filenameptr, pointer address_override) -> pointer {
        %ir {{
            loadm.p r99200.p,[diskio.load.filenameptr]
            loadm.p r99201.p,[diskio.load.address_override]
            syscall 40 (r99200.p, r99201.p): r99202.p
            returnr.p r99202.p
        }}
    }

    ; Identical to load(), but DOES INCLUDE the first 2 bytes in the file.
    ; No program header is assumed in the file. Everything is loaded.
    ; See comments on load() for more details.
    sub load_raw(str filenameptr, pointer start_address) -> pointer {
        %ir {{
            loadm.p r99200.p,[diskio.load_raw.filenameptr]
            loadm.p r99201.p,[diskio.load_raw.start_address]
            syscall 41 (r99200.p, r99201.p): r99202.p
            returnr.p r99202.p
        }}
    }

    sub delete(str filenameptr) {
        ; -- delete a file on the drive
        %ir {{
            loadm.p r99200.p,[diskio.delete.filenameptr]
            syscall 43 (r99200.p)
        }}
    }

    sub rename(str oldfileptr, str newfileptr) {
        ; -- rename a file on the drive
        %ir {{
            loadm.p r99200.p,[diskio.rename.oldfileptr]
            loadm.p r99201.p,[diskio.rename.newfileptr]
            syscall 44 (r99200.p, r99201.p)
        }}
    }

    sub exists(str filename) -> bool {
        ; -- returns true if the given file exists (and can be read), otherwise false.
        ;    Unlike on the other targets, the file that f_open opened stays open.
        pointer file = fileio.open(filename, "rb")
        if file == 0
            return false
        void fileio.close(file)
        return true
    }

    sub get_loadaddress(str filename) -> uword {
        ; get the load address from the header of a PRG file, or 0 if it can't be read.
        ; Like exists(), this leaves the file that f_open opened alone.
        uword address
        pointer file = fileio.open(filename, "rb")
        if file == 0
            return 0
        if fileio.read(file, &address, 2) != 2
            address = 0
        void fileio.close(file)
        return address
    }


    private sub internal_read_byte() -> uword {
        ; $0100 plus the next byte of the file opened with f_open, or 0 at its end
        %ir {{
            syscall 54 (): r99000.w
            returnr.w r99000.w
        }}
    }

    private sub internal_write_byte(ubyte value) -> bool {
        %ir {{
            loadm.b r99100.b,[diskio.internal_write_byte.value]
            syscall 55 (r99100.b): r99101.b
            returnr.b r99101.b
        }}
    }
}
