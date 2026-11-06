; The llvm16 Text I/O output routines that send every character to the profile's putchar routine as it is.
; Merged into the txt block of the llvm16 textio and of the textio of each library family that has no chrout() of its own.

txt {
%option merge, no_symbol_prefixing, ignore_unused

sub chrout(ubyte char) {
    %ir {{
        loadm.b r99100.b,[txt.chrout.char]
        syscall 2 (r99100.b)
    }}
}

sub  print (str text) {
    %ir {{
        loadm.p r99200.p,[txt.print.text]
        syscall 3 (r99200.p)
    }}
}
}
