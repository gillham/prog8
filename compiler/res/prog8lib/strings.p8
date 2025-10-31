; 0-terminated string manipulation routines.

%import shared_string_functions

strings {
    %option no_symbol_prefixing, ignore_unused

    asmsub length(str string @AY) clobbers(A) -> ubyte @Y {
        ; Returns the number of bytes in the string.
        ; This value is determined during runtime and counts upto the first terminating 0 byte in the string,
        ; regardless of the size of the string during compilation time. Don’t confuse this with len and sizeof!

        ; uses P8ZP_SCRATCH_W1 to store the string address, do not change this, other routines here may depend on it (as optimization implementation detail)
        %asm {{
		sta  P8ZP_SCRATCH_W1
		sty  P8ZP_SCRATCH_W1+1
		ldy  #0
-		lda  (P8ZP_SCRATCH_W1),y
		beq  +
		iny
		bne  -
+		rts
        }}
    }

    asmsub left(str source @AX, ubyte length @Y, str target @R1) clobbers(A, Y) {
        ; Copies the left side of the source string of the given length to target string.
        ; It is assumed the target string buffer is large enough to contain the result.
        ; Also, you have to make sure yourself that length is smaller or equal to the length of the source string.
        ; Modifies in-place, doesn’t return a value (so can’t be used in an expression).
        %asm {{
		; need to copy the the cx16 virtual registers to zeropage to be compatible with C64...
		sta  P8ZP_SCRATCH_W1
		stx  P8ZP_SCRATCH_W1+1
		lda  cx16.r1
		sta  P8ZP_SCRATCH_W2
		lda  cx16.r1+1
		sta  P8ZP_SCRATCH_W2+1
		lda  #0
		sta  (P8ZP_SCRATCH_W2),y
		cpy  #0
		bne  _loop
		rts
_loop		dey
		lda  (P8ZP_SCRATCH_W1),y
		sta  (P8ZP_SCRATCH_W2),y
		cpy  #0
		bne  _loop
+		rts
        }}
    }

    asmsub right(str source @AY, ubyte length @X, str target @R1) clobbers(A,Y) {
        ; Copies the right side of the source string of the given length to target string.
        ; It is assumed the target string buffer is large enough to contain the result.
        ; Also, you have to make sure yourself that length is smaller or equal to the length of the source string.
        ; Modifies in-place, doesn’t return a value (so can’t be used in an expression).
        %asm {{
		; need to copy the the cx16 virtual registers to zeropage to be compatible with C64...
		stx  P8ZP_SCRATCH_B1
		sta  cx16.r0
		sty  cx16.r0+1
		jsr  length
		tya
		sec
		sbc  P8ZP_SCRATCH_B1
		clc
		adc  cx16.r0
		sta  P8ZP_SCRATCH_W1
		lda  cx16.r0+1
		adc  #0
		sta  P8ZP_SCRATCH_W1+1
		ldy  cx16.r1
		sty  P8ZP_SCRATCH_W2
		ldy  cx16.r1+1
		sty  P8ZP_SCRATCH_W2+1
		ldy  P8ZP_SCRATCH_B1
		lda  #0
		sta  (P8ZP_SCRATCH_W2),y
		cpy  #0
		bne  _loop
		rts
_loop		dey
		lda  (P8ZP_SCRATCH_W1),y
		sta  (P8ZP_SCRATCH_W2),y
		cpy  #0
		bne  _loop
+		rts
        }}
    }

    asmsub slice(str source @R0, ubyte start @A, ubyte length @Y, str target @R1) clobbers(A, Y) {
        ; Copies a segment from the source string, starting at the given index,
        ;  and of the given length to target string.
        ; It is assumed the target string buffer is large enough to contain the result.
        ; Also, you have to make sure yourself that start and length are within bounds of the strings.
        ; Modifies in-place, doesn’t return a value (so can’t be used in an expression).
        %asm {{
		; need to copy the the cx16 virtual registers to zeropage to be compatible with C64...
		; substr(source, target, start, length)
		sta  P8ZP_SCRATCH_B1
		lda  cx16.r0
		sta  P8ZP_SCRATCH_W1
		lda  cx16.r0+1
		sta  P8ZP_SCRATCH_W1+1
		lda  cx16.r1
		sta  P8ZP_SCRATCH_W2
		lda  cx16.r1+1
		sta  P8ZP_SCRATCH_W2+1

		; adjust src location
		clc
		lda  P8ZP_SCRATCH_W1
		adc  P8ZP_SCRATCH_B1
		sta  P8ZP_SCRATCH_W1
		bcc  +
		inc  P8ZP_SCRATCH_W1+1
+		lda  #0
		sta  (P8ZP_SCRATCH_W2),y
		beq  _startloop
-		lda  (P8ZP_SCRATCH_W1),y
		sta  (P8ZP_SCRATCH_W2),y
_startloop	dey
		cpy  #$ff
		bne  -
		rts
        }}
    }

    asmsub find(str string @AY, ubyte character @X) -> ubyte @A, bool @Pc {
        ; Locates the first position of the given character in the string,
        ; returns Carry set if found + index in A, or Carry clear if not found (and A will be 255, an invalid index).
        %asm {{
		; need to copy the the cx16 virtual registers to zeropage to make this run on C64...
		sta  P8ZP_SCRATCH_W1
		sty  P8ZP_SCRATCH_W1+1
		stx  P8ZP_SCRATCH_B1
		ldy  #0
-		lda  (P8ZP_SCRATCH_W1),y
		beq  _notfound
		cmp  P8ZP_SCRATCH_B1
		beq  _found
		iny
		bne  -
_notfound	lda  #255
        clc
		rts
_found	tya
        sec
        rts
        }}
    }


    asmsub find_eol(str string @AY) -> ubyte @A, bool @Pc {
        ; Locates the position of the first End Of Line character in the string.
        ; This is a convenience function that looks for both a CR or LF (byte 13 or byte 10) as being a possible Line Ending.
        ; returns Carry set if found + index in A, or Carry clear if not found (and A will be 255, an invalid index).
        %asm {{
		; need to copy the the cx16 virtual registers to zeropage to make this run on C64...
		sta  P8ZP_SCRATCH_W1
		sty  P8ZP_SCRATCH_W1+1
		ldy  #0
-		lda  (P8ZP_SCRATCH_W1),y
		beq  _notfound
		cmp  #13
		beq  _found
		cmp  #10
		beq  _found
		iny
		bne  -
_notfound	lda  #255
        clc
		rts
_found	tya
        sec
        rts
        }}
    }

    asmsub rfind(str string @AY, ubyte character @X) -> ubyte @A, bool @Pc {
        ; Locates the first position of the given character in the string, starting from the right.
        ; returns Carry set if found + index in A, or Carry clear if not found (and A will be 255, an invalid index).
        %asm {{
            stx  P8ZP_SCRATCH_B1
            ; note: we make use of the fact that length() stores the string address AY in P8ZP_SCRATCH_W1 for us! we need that later
            jsr  length
            cpy  #0
            beq  _notfound
            dey
-           lda  (P8ZP_SCRATCH_W1),y
            cmp  P8ZP_SCRATCH_B1
            beq  _found
            dey
            cpy  #255
            bne  -
_notfound   lda  #255
            clc
            rts
_found      tya
            sec
            rts
        }}
    }

    asmsub contains(str string @AY, ubyte character @X) -> bool @Pc {
        ; Just return true/false if the character is in the given string or not.
        %asm {{
            jmp  find
        }}
    }

    asmsub copy(str source @R0, str target @AY) clobbers(A) -> ubyte @Y {
        ; Copy a string to another, overwriting that one.
        ; Returns the length of the string that was copied.
        %asm {{
		sta  P8ZP_SCRATCH_W1
		sty  P8ZP_SCRATCH_W1+1
		lda  cx16.r0
		ldy  cx16.r0+1
		jmp  prog8_lib.strcpy
        }}
    }

    asmsub ncopy(str source @R0, str target @AY, ubyte maxlength @X) clobbers(A, X) -> ubyte @Y {
        ; Copy a string to another, overwriting that one.
        ; Returns the length of the string that was copied.
        %asm {{
		sta  P8ZP_SCRATCH_W1
		sty  P8ZP_SCRATCH_W1+1
		lda  cx16.r0
		ldy  cx16.r0+1
		jmp  prog8_lib.strncpy
        }}
    }

    asmsub append(str target @R0, str suffix @R1) clobbers(Y) -> ubyte @A {
        ; Append the suffix string to the target. (make sure the buffer is large enough!)
        ; Returns the length of the resulting string.
        %asm {{
            lda  cx16.r0
            ldy  cx16.r0+1
            jsr  length
            sty  P8ZP_SCRATCH_B1
            tya
            clc
            adc  cx16.r0
            sta  P8ZP_SCRATCH_W1
            lda  cx16.r0+1
            adc  #0
            sta  P8ZP_SCRATCH_W1+1
            lda  cx16.r1
            ldy  cx16.r1+1
            jsr  prog8_lib.strcpy
            tya
            clc
            adc  P8ZP_SCRATCH_B1
            rts
        }}
    }

    asmsub nappend(str target @R0, str suffix @R1, ubyte maxlength @X) clobbers(Y) -> ubyte @A {
        ; Append the suffix string to the target. (make sure the buffer is large enough!)
        ; Returns the length of the resulting string.
        %asm {{
            lda  cx16.r0
            ldy  cx16.r0+1
            jsr  length
            sty  P8ZP_SCRATCH_B1
            cpx  P8ZP_SCRATCH_B1
            beq  _max_too_small
            bmi  _max_too_small
            txa
            sec
            sbc  P8ZP_SCRATCH_B1
            sta  P8ZP_SCRATCH_B1
            tya
            clc
            adc  cx16.r0
            sta  P8ZP_SCRATCH_W1
            lda  cx16.r0+1
            adc  #0
            sta  P8ZP_SCRATCH_W1+1
            lda  cx16.r1
            ldy  cx16.r1+1
            ldx  P8ZP_SCRATCH_B1
            jsr  prog8_lib.strncpy
            tya
            clc
            adc  P8ZP_SCRATCH_B1
            rts
_max_too_small
            rts
        }}
    }

    asmsub compare(str string1 @R0, str string2 @AY) clobbers(Y) -> byte @A {
        ; Compares two strings for sorting.
        ; Returns -1 (255), 0 or 1, meaning: string1 sorts before, equal or after string2.
        ; Note that you can also directly compare strings and string values with eachother using
        ; comparison operators ==, < etcetera (this will use strcmp automatically).
        %asm {{
		sta  P8ZP_SCRATCH_W2
		sty  P8ZP_SCRATCH_W2+1
		lda  cx16.r0
		ldy  cx16.r0+1
		jmp  prog8_lib.strcmp_mem
        }}
    }

    asmsub ncompare(str string1 @R0, str string2 @AY, ubyte length @X) clobbers(X, Y) -> byte @A {
        ; Compares two strings for sorting.
        ; Returns -1 (255), 0 or 1, meaning: string1 sorts before, equal or after string2.
        ; Only compares the strings from index 0 up to the length argument.
        %asm {{
		sta  P8ZP_SCRATCH_W2
		sty  P8ZP_SCRATCH_W2+1
		lda  cx16.r0
		ldy  cx16.r0+1
		jmp  prog8_lib.strncmp_mem
        }}
    }

    asmsub lower(str st @AY) -> ubyte @Y {
        ; Lowercases the petscii string in-place. Returns length of the string.
        ; (for efficiency, non-letter characters > 128 will also not be left intact,
        ;  but regular text doesn't usually contain those characters anyway.)
        %asm {{
            sta  P8ZP_SCRATCH_W1
            sty  P8ZP_SCRATCH_W1+1
            ldy  #0
-           lda  (P8ZP_SCRATCH_W1),y
            beq  _done
            and  #$7f
            cmp  #97
            bcc  +
            cmp  #123
            bcs  +
            and  #%11011111
+           sta  (P8ZP_SCRATCH_W1),y
            iny
            bne  -
_done       rts
        }}
    }

    asmsub upper(str st @AY) -> ubyte @Y {
        ; Uppercases the petscii string in-place. Returns length of the string.
        %asm {{
            sta  P8ZP_SCRATCH_W1
            sty  P8ZP_SCRATCH_W1+1
            ldy  #0
-           lda  (P8ZP_SCRATCH_W1),y
            beq  _done
            cmp  #65
            bcc  +
            cmp  #91
            bcs  +
            ora  #%00100000
+           sta  (P8ZP_SCRATCH_W1),y
            iny
            bne  -
_done       rts
        }}
    }

    asmsub lowerchar(ubyte character @A) -> ubyte @A {
        %asm {{
            and  #$7f
            cmp  #97
            bcc  +
            cmp  #123
            bcs  +
            and  #%11011111
+           rts
        }}
    }

    asmsub upperchar(ubyte character @A) -> ubyte @A {
        %asm {{
            cmp  #65
            bcc  +
            cmp  #91
            bcs  +
            ora  #%00100000
+           rts
        }}
    }

    asmsub pattern_match(str string @AY, str pattern @R0) clobbers(Y) -> bool @A {
        ; This routine matches a string against a pattern and returns with the carry bit set if they match, or clear if they don't.
        ; The two characters ? and * have a special meaning when they appear in the pattern. All other characters match themselves.
        ; ? matches any one character. For example, F?? matches FOO but not FU, and ?? matches all two-character strings.
        ; * matches any string, including the empty string.
        ; For example, F* matches all strings starting with F. *O*O* matches all strings with at least two Os. Finally, ?* matches all non-empty strings.
        ; Both the pattern and the string must be NUL-terminated (that it, followed with a 00 byte) and at most 255 characters long (excluding the NUL).
        ; Code taken from http://6502.org/source/strings/patmatch.htm
        ;
        ; Input:  cx16.r0:  A NUL-terminated, <255-length pattern
        ;              AY:  A NUL-terminated, <255-length string
        ;
        ; Output: A = 1 if the string matches the pattern, A = 0 if not.
        ;
        ; Notes:  Clobbers A, X, Y. Each * in the pattern uses 4 bytes of stack.
        ;         Does not work in ROM due to self-modifying code.

		%asm {{

strptr = P8ZP_SCRATCH_W1

	sta  strptr
	sty  strptr+1
	lda  cx16.r0
	sta  modify_pattern1+1
	sta  modify_pattern2+1
	lda  cx16.r0+1
	sta  modify_pattern1+2
	sta  modify_pattern2+2
	jsr  _match
	lda  #0
	rol  a
	rts


_match
	ldx #$00        ; x is an index in the pattern
	ldy #$ff        ; y is an index in the string
modify_pattern1
next    lda $ffff,x   ; look at next pattern character    MODIFIED
	cmp #'*'     ; is it a star?
	beq star        ; yes, do the complicated stuff
	iny             ; no, let's look at the string
	cmp #'?'     ; is the pattern caracter a ques?
	bne reg         ; no, it's a regular character
	lda (strptr),y     ; yes, so it will match anything
	beq fail        ;  except the end of string
reg     cmp (strptr),y     ; are both characters the same?
	bne fail        ; no, so no match
	inx             ; yes, keep checking
	cmp #0          ; are we at end of string?
	bne next        ; not yet, loop
found   rts             ; success, return with c=1

star    inx             ; skip star in pattern
modify_pattern2
	cmp $ffff,x   	; string of stars equals one star	MODIFIED
	beq star        ;  so skip them also
stloop  txa             ; we first try to match with * = ""
	pha             ;  and grow it by 1 character every
	tya             ;  time we loop
	pha             ; save x and y on stack
	jsr next        ; recursive call
	pla             ; restore x and y
	tay
	pla
	tax
	bcs found       ; we found a match, return with c=1
	iny             ; no match yet, try to grow * string
	lda (strptr),y     ; are we at the end of string?
	bne stloop      ; not yet, add a character
fail    clc             ; yes, no match found, return with c=0
	rts
		}}
	}

    asmsub hash(str string @AY) -> ubyte @A {
        ; experimental 8 bit hashing function.
        ; hash(-1)=179;  clear carry;  hash(i) = ROL hash(i-1)  XOR  string[i]
        ; On the English word list in /usr/share/dict/words it seems to have a pretty even distribution
        %asm {{
            sta  P8ZP_SCRATCH_W1
            sty  P8ZP_SCRATCH_W1+1
            lda  #179
            sta  P8ZP_SCRATCH_REG
            ldy  #0
            clc
-           lda  (P8ZP_SCRATCH_W1),y
            beq  +
            rol  P8ZP_SCRATCH_REG
            eor  P8ZP_SCRATCH_REG
            sta  P8ZP_SCRATCH_REG
            iny
            bne  -
+           lda  P8ZP_SCRATCH_REG
            rts
        }}
    }

    asmsub isdigit(ubyte petsciichar @A) -> bool @Pc {
        %asm {{
            cmp  #'0'
            bcs  +
            rts
+           cmp  #'9'+1
            bcc  +
            clc
            rts
+           sec
            rts
        }}
    }

    asmsub isupper(ubyte petsciichar @A) -> bool @Pc {
        ; shifted petscii has 2 ranges that contain the upper case letters... 97-122 and 193-218
        %asm {{
            cmp  #97
            bcs  +
            rts
+           cmp  #122+1
            bcc  _yes
            cmp  #193
            bcs  +
            rts
+           cmp  #218+1
            bcc  _yes
            clc
            rts
_yes        sec
            rts
        }}
    }

    asmsub islower(ubyte petsciichar @A) -> bool @Pc {
        %asm {{
            cmp  #'a'
            bcs  +
            rts
+           cmp  #'z'+1
            bcc  +
            clc
            rts
+           sec
            rts
        }}
    }

    asmsub isletter(ubyte petsciichar @A) -> bool @Pc {
        %asm {{
            jsr  islower
            bcs  +
            jmp  isupper
+           rts
        }}
    }

    asmsub isspace(ubyte petsciichar @A) -> bool @Pc {
        %asm {{
            cmp  #32
            beq  +
            cmp  #13
            beq  +
            cmp  #9
            beq  +
            cmp  #10
            beq  +
            cmp  #141
            beq  +
            cmp  #160
            beq  +
            clc
            rts
+           sec
            rts
        }}
    }

    asmsub isprint(ubyte petsciichar @A) -> bool @Pc {
        %asm {{
            cmp  #160
            bcc  +
            rts
+           cmp  #32
            bcs  +
            rts
+           cmp  #128
            bcc  +
            clc
            rts
+           sec
            rts
        }}
    }
}
