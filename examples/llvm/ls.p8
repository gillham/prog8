%import textio
%import diskio
%zeropage basicsafe

; Lists a directory: the current one, or the one given on the command line:
;     ./ls /tmp
; diskio.directory() prints the size, a tab and the name of each file, and the directories marked as such.
; Note: this program is for the llvm32 and llvm64 targets on an operating system; bare metal has no directories.

main {
    sub start() {
        if sys.argc() > 1
            diskio.chdir(sys.argv(1))
        txt.print(diskio.curdir())
        txt.print(":\n")
        if not diskio.directory() {
            txt.print("can't list the directory\n")
            sys.exit(1)
        }
    }
}
