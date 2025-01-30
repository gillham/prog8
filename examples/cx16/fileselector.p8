%import diskio
%import textio
%import strings
%zeropage basicsafe
%option no_sysinit

; A "TUI" for an interactive file selector, that scrolls the selection list if it doesn't fit on the screen.
; Returns the name of the selected file.  If it is a directory instead, the name will start and end with a slash '/'.
; Functions in PETSCII mode and in ISO mode as well (no case folding in ISO mode!)
; Depends a lot on diskio routines, and uses the drive set in the diskio.drivenumber variable (usually just 8)

; Q: should case folding be done in diskio already? A: no, it doesn't know if you are in iso mode or not.

; TODO use "@$:*=p" instead of filtering manually for only dirs use @$:*=d   , but that needs a change in diskio...
; TODO joystick control? mouse control?
; TODO keyboard typing; jump to the first entry that starts with that character?  (but 'q' for quit stops working then, plus scrolling with pageup/down is already pretty fast)


main {
    sub start() {
        ; some configuration, optional
        fileselector.configure_settings(%00000011, 2)
        fileselector.configure_appearance(10, 10, 20, $b3, $d0)

        ; show all files, using just the * wildcard
        uword chosen = fileselector.select("*")

        txt.nl()
        txt.nl()
        if chosen!=0 {
            txt.print("chosen: ")
            txt.print(chosen)
            txt.nl()
        } else {
            txt.print("nothing chosen or error!\n")
            txt.print(diskio.status())
        }
    }
}

fileselector {
    %option ignore_unused

    const uword filenamesbuffer = $a000      ; use a HIRAM bank
    const uword filenamesbuf_size = $1e00    ; leaves room for a 256 entry string pointer table at $be00-$bfff
    const uword filename_ptrs_start = $be00  ; array of 256 string pointers for each of the names in the buffer. ends with $0000.

    ubyte dialog_topx = 10
    ubyte dialog_topy = 10
    ubyte max_lines = 20
    ubyte colors_normal = $b3
    ubyte colors_selected = $d0
    ubyte buffer_rambank = 1    ; default hiram bank to use for the data buffers
    ubyte show_what = 3         ; dirs and files
    ubyte chr_topleft, chr_topright, chr_botleft, chr_botright, chr_horiz_top, chr_horiz_other, chr_vert, chr_jointleft, chr_jointright

    ubyte num_visible_files
    uword name_ptr


    sub configure_settings(ubyte show_types, ubyte rambank) {
        ; show_types is a bit mask , bit 0 = show files, bit 1 = show dirs
        buffer_rambank = rambank
        show_what = show_types
        set_characters(false)
    }

    sub configure_appearance(ubyte column, ubyte row, ubyte max_entries, ubyte normal, ubyte selected) {
        dialog_topx = column
        dialog_topy = row
        max_lines = max_entries
        colors_normal = normal
        colors_selected = selected
    }

    sub select(str pattern) -> uword {
        ubyte old_bank = cx16.getrambank()
        cx16.rambank(buffer_rambank)
        defer cx16.rambank(old_bank)

        num_visible_files = 0
        diskio.list_filename[0] = 0
        name_ptr = diskio.diskname()
        if name_ptr==0 or cbm.READST()!=0
            return 0

        bool iso_mode = cx16.get_charset()==1
        set_characters(iso_mode)
        txt.color2(colors_normal & 15, colors_normal>>4)
        background(0, 3)

        txt.plot(dialog_topx, dialog_topy)
        txt.chrout(chr_topleft)
        linepart(true)
        txt.chrout(chr_topright)
        txt.nl()
        txt.column(dialog_topx)
        txt.chrout(chr_vert)
        txt.print(" drive ")
        txt.print_ub(diskio.drivenumber)
        txt.print(": '")
        txt.print(name_ptr)
        txt.chrout('\'')
        txt.column(dialog_topx+31)
        txt.chrout(chr_vert)
        txt.nl()
        txt.column(dialog_topx)
        txt.chrout(chr_vert)
        txt.print("   scanning directory...      ")
        txt.chrout(chr_vert)
        txt.nl()
        txt.column(dialog_topx)
        footerline()

        ubyte num_files = get_filenames(pattern, filenamesbuffer, filenamesbuf_size)    ; use Hiram bank to store the files
        ubyte selected_line
        ubyte top_index
        uword filename_ptrs

        construct_name_ptr_array()
        ; sort alphabetically
        sorting.shellsort_pointers(filename_ptrs_start, num_files)
        num_visible_files = min(max_lines, num_files)

        ; initial display
        background(5, 3 + num_visible_files -1)
        txt.plot(dialog_topx+2, dialog_topy+2)
        txt.print("select ")
        if show_what & 1 == 1
            txt.print("file")
        else
            txt.print("directory")
        txt.print(": (")
        txt.print_ub(num_files)
        txt.print(" total)")
        txt.column(dialog_topx+31)
        txt.chrout(chr_vert)
        txt.nl()
        txt.column(dialog_topx)
        txt.chrout(chr_vert)
        txt.print(" esc/stop to abort            ")
        txt.chrout(chr_vert)
        txt.nl()
        txt.column(dialog_topx)
        txt.chrout(chr_jointleft)
        linepart(false)
        txt.chrout(chr_jointright)
        txt.nl()
        print_scroll_indicator(false, true)
        if num_files>0 {
            for selected_line in 0 to num_visible_files-1 {
                txt.column(dialog_topx)
                txt.chrout(chr_vert)
                txt.spc()
                print_filename(peekw(filename_ptrs_start+selected_line*$0002))
                txt.column(dialog_topx+31)
                txt.chrout(chr_vert)
                txt.nl()
            }
        } else {
            txt.column(dialog_topx)
            txt.chrout(chr_vert)
            txt.print(" no matches.")
            txt.column(dialog_topx+31)
            txt.chrout(chr_vert)
            txt.nl()
        }
        print_scroll_indicator(false, false)
        txt.column(dialog_topx)
        footerline()
        selected_line = 0
        select_line(0)
        print_up_and_down()

        repeat {
            if cbm.STOP2()
                return 0

            ubyte key = cbm.GETIN2()
            when key {
                3, 27 -> return 0      ; STOP and ESC  aborts
                '\n',' ' -> {
                    if num_files>0 {
                        void strings.copy(peekw(filename_ptrs_start + (top_index+selected_line)*$0002), &diskio.list_filename)
                        return diskio.list_filename
                    }
                    return 0
                }
                '[',130,157 -> {    ; PAGEUP, cursor left
                    ; previous page of lines
                    unselect_line(selected_line)
                    if selected_line==0
                        repeat max_lines scroll_list_backward()
                    selected_line = 0
                    select_line(0)
                    print_up_and_down()
                }
                ']',2,29 -> {      ; PAGEDOWN, cursor right
                    if num_files>0 {
                        ; next page of lines
                        unselect_line(selected_line)
                        if selected_line == max_lines-1
                            repeat max_lines scroll_list_forward()
                        selected_line = num_visible_files-1
                        select_line(selected_line)
                        print_up_and_down()
                    }
                }
                17 -> {     ; down
                    if num_files>0 {
                        unselect_line(selected_line)
                        if selected_line<num_visible_files-1
                            selected_line++
                        else if num_files>max_lines
                            scroll_list_forward()
                        select_line(selected_line)
                        print_up_and_down()
                    }
                }
                145 -> {    ; up
                    unselect_line(selected_line)
                    if selected_line>0
                        selected_line--
                    else if num_files>max_lines
                        scroll_list_backward()
                    select_line(selected_line)
                    print_up_and_down()
                }
            }
        }

        ubyte x,y

        sub construct_name_ptr_array() {
            filename_ptrs = filename_ptrs_start
            name_ptr = filenamesbuffer
            repeat num_files {
                pokew(filename_ptrs, name_ptr)
                if iso_mode {
                    ; no case folding in iso mode
                    while @(name_ptr)!=0
                        name_ptr++
                } else {
                    ; case-folding to avoid petscii shifted characters coming out as symbols  TODO should diskio do this already?
                    name_ptr += strings.lower(name_ptr)
                }
                name_ptr++
                filename_ptrs+=2
            }
            pokew(filename_ptrs, 0)
        }

        sub print_filename(uword name) {
            repeat 28 {      ; maximum length displayed
                if @(name)==0
                    break
                txt.chrout(128)     ; don't print control characters
                txt.chrout(@(name))
                name++
            }
        }

        sub scroll_list_forward() {
            if top_index+max_lines< num_files {
                top_index++
                ; scroll the displayed list up 1
                scroll_txt_up(dialog_topx+2, dialog_topy+6, 28, max_lines, sc:' ')
                ; print new name at the bottom of the list
                txt.plot(dialog_topx+2, dialog_topy+6+max_lines-1)
                print_filename(peekw(filename_ptrs_start + (top_index+ selected_line)*$0002))
            }
        }

        sub scroll_list_backward() {
            if top_index>0 {
                top_index--
                ; scroll the displayed list down 1
                scroll_txt_down(dialog_topx+2, dialog_topy+6, 28, max_lines, sc:' ')
                ; print new name at the top of the list
                txt.plot(dialog_topx+2, dialog_topy+6)
                print_filename(peekw(filename_ptrs_start + top_index * $0002))
            }
        }

        sub scroll_txt_up(ubyte col, ubyte row, ubyte width, ubyte height, ubyte fillchar) {
            for y in row to row+height-2 {
                for x in col to col+width-1 {
                    txt.setchr(x,y, txt.getchr(x, y+1))
                }
            }
            y = row+height-1
            for x in col to col+width-1 {
                txt.setchr(x,y, fillchar)
            }
        }

        sub scroll_txt_down(ubyte col, ubyte row, ubyte width, ubyte height, ubyte fillchar) {
            for y in row+height-1 downto row+1 {
                for x in col to col+width-1 {
                    txt.setchr(x,y, txt.getchr(x, y-1))
                }
            }
            for x in col to col+width-1 {
                txt.setchr(x,row, fillchar)
            }
        }

        sub print_up_and_down() {
            if num_files<=max_lines
                return
            print_scroll_indicator(top_index>0, true)
            print_scroll_indicator(top_index + num_visible_files < num_files, false)
        }

        sub print_scroll_indicator(bool visible, bool up) {
            txt.plot(dialog_topx, dialog_topy + (if up  5  else  6+num_visible_files))
            txt.chrout(chr_vert)
            txt.column(dialog_topx+24)
            if visible
                if up
                    txt.print("  (up)")
                else
                    txt.print("(down)")
            else
                txt.print("      ")
            txt.spc()
            txt.chrout(chr_vert)
            txt.nl()
        }

        sub footerline() {
            txt.chrout(chr_botleft)
            linepart(false)
            txt.chrout(chr_botright)
        }

        sub linepart(bool top) {
            cx16.r0L = chr_horiz_other
            if top
                cx16.r0L = chr_horiz_top
            repeat 30 txt.chrout(cx16.r0L)
        }

        sub select_line(ubyte line) {
            line_color(line, colors_selected)
        }

        sub unselect_line(ubyte line) {
            line_color(line, colors_normal)
        }

        sub line_color(ubyte line, ubyte colors) {
            cx16.r1L = dialog_topy+6+line
            ubyte charpos
            for charpos in dialog_topx+1 to dialog_topx+30 {
                txt.setclr(charpos, cx16.r1L, colors)
            }
        }
    }

    sub set_characters(bool iso_chars) {
        if iso_chars {
            chr_topleft = iso:'í'
            chr_topright = iso:'ì'
            chr_botleft = iso:'`'
            chr_botright = iso:'\''
            chr_jointleft = chr_jointright = iso:'÷'
            chr_vert = iso:'|'
            chr_horiz_top = iso:'¯'
            chr_horiz_other = iso:'-'
        } else {
            ; PETSCII box symbols
            chr_topleft = '┌'
            chr_topright = '┐'
            chr_botleft = '└'
            chr_botright = '┘'
            chr_horiz_top = '─'
            chr_horiz_other = '─'
            chr_vert = '│'
            chr_jointleft = '├'
            chr_jointright = '┤'
        }
    }

    sub background(ubyte startrow, ubyte numlines) {
        startrow += dialog_topy
        repeat numlines {
            txt.plot(dialog_topx+1, startrow)
            repeat 30  txt.chrout(' ')
            txt.nl()
            startrow++
        }
    }

    sub get_filenames(uword pattern_ptr, uword filenames_buffer, uword filenames_buf_size) -> ubyte {
        uword buffer_start = filenames_buffer
        ubyte files_found = 0
        filenames_buffer[0]=0
        if diskio.lf_start_list(pattern_ptr) {
            while diskio.lf_next_entry() {
                bool is_dir = diskio.list_filetype=="dir"
                if is_dir and show_what & 2 == 0
                    continue
                if not is_dir and show_what & 1 == 0
                    continue
                if is_dir {
                    @(filenames_buffer) = '/'       ; directories start with a slash so they're grouped when sorting
                    filenames_buffer++
                }
                filenames_buffer += strings.copy(diskio.list_filename, filenames_buffer)
                if is_dir {
                    @(filenames_buffer) = '/'       ; directories also end with a slash
                    filenames_buffer++
                    @(filenames_buffer) = 0
                }
                filenames_buffer++
                files_found++
                if filenames_buffer - buffer_start > filenames_buf_size-20 {
                    @(filenames_buffer)=0
                    diskio.lf_end_list()
                    sys.set_carry()
                    return files_found
                }
            }
            diskio.lf_end_list()
        }
        @(filenames_buffer)=0
        sys.clear_carry()
        return files_found
    }

}


sorting {
    ; note: cannot use the sorting library module because that relies on zeropage to be directly available (@requirezp pointer)
    ;       and this code is meant to be able to being used without zeropage as well (except for R0-R15).

    sub shellsort_pointers(uword stringpointers_array, ubyte num_elements) {
        ; Comparefunc must be a routine that accepts 2 pointers in R0 and R1, and must return with Carry=1 if R0<=R1, otherwise Carry=0.
        ; One such function, to compare strings, is provided as 'string_comparator' below.
        cx16.r2 = stringpointers_array      ; need zeropage pointer
        num_elements--
        ubyte gap
        for gap in [132, 57, 23, 10, 4, 1] {
            ubyte i
            for i in gap to num_elements {
                cx16.r1 = peekw(cx16.r2+i*$0002)
                ubyte @zp j = i
                ubyte @zp k = j-gap
                while j>=gap {
                    cx16.r0 = peekw(cx16.r2+k*2)
                    if string_comparator(cx16.r0, cx16.r1)
                        break
                    pokew(cx16.r2+j*2, cx16.r0)
                    j = k
                    k -= gap
                }
                pokew(cx16.r2+j*2, cx16.r1)
            }
        }

        asmsub string_comparator(uword string1 @R0, uword string2 @R1) -> bool @Pc {
            ; R0 and R1 are the two strings, must return Carry=1 when R0<=R1, else Carry=0
            %asm {{
                lda  cx16.r1L
                ldy  cx16.r1H
                sta  P8ZP_SCRATCH_W2
                sty  P8ZP_SCRATCH_W2+1
                lda  cx16.r0L
                ldy  cx16.r0H
                jsr  prog8_lib.strcmp_mem
                cmp  #1
                bne  +
                clc
                rts
+               sec
                rts
            }}
        }
    }
}
