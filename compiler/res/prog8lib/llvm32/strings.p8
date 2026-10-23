; 0-terminated string manipulation routines for the llvm32 target.
; Self-contained, without the shared_string_functions module: on llvm32 an address doesn't fit in a uword, so split() takes a pointer[] array.
; Status flags don't carry over from a subroutine to its caller on llvm32, so these routines never report results in the Carry flag.
; The C-style names are aliases that keep the Prog8 argument order, so strcpy(source, target) copies like copy().

strings {
    %option ignore_unused

    alias strlen = strings.length
    alias strcat = strings.append
    alias strcpy = strings.copy
    alias strcmp = strings.compare
    alias strcontains = strings.contains
    alias strtrim = strings.trim
    alias strlower = strings.lower
    alias strupper = strings.upper
    alias substr = strings.slice

    alias lowerchar_iso = strings.lowerchar
    alias upperchar_iso = strings.upperchar
    alias lower_iso = strings.lower
    alias upper_iso = strings.upper
    alias compare_nocase_iso = strings.compare_nocase

    sub length(str st) -> ubyte {
        ; Returns the number of bytes in the string.
        ; This value is determined during runtime and counts upto the first terminating 0 byte in the string,
        ; regardless of the size of the string during compilation time. Don’t confuse this with len and sizeof!
        ubyte count = 0
        while st[count]!=0
            count++
        return count
    }

    sub left(str source, ubyte slen, str target)  {
        ; Copies the left side of the source string of the given length to target string.
        ; It is assumed the target string buffer is large enough to contain the result.
        ; Also, you have to make sure yourself that length is smaller or equal to the length of the source string.
        ; Modifies in-place, doesn’t return a value (so can’t be used in an expression).
        sys.memcopy(source, target, slen)
        target[slen] = 0
    }

    sub right(str source, ubyte slen, str target) {
        ; Copies the right side of the source string of the given length to target string.
        ; It is assumed the target string buffer is large enough to contain the result.
        ; Also, you have to make sure yourself that length is smaller or equal to the length of the source string.
        ; Modifies in-place, doesn’t return a value (so can’t be used in an expression).
        sys.memcopy(source + length(source) - slen, target, slen)
        target[slen] = 0
    }

    sub slice(str source, ubyte start, ubyte slen, str target) {
        ; Copies a segment from the source string, starting at the given index,
        ;  and of the given length to target string.
        ; It is assumed the target string buffer is large enough to contain the result.
        ; Also, you have to make sure yourself that start and length are within bounds of the strings.
        ; Modifies in-place, doesn’t return a value (so can’t be used in an expression).
        sys.memcopy(source + start, target, slen)
        target[slen] = 0
    }

    sub find(str st, ubyte character) -> ubyte, bool {
        ; Locates the first position of the given character in the string,
        ; returns the index, and true; or 255 (an invalid index) and false when the string doesn't contain the character.
        ubyte ix = 0
        repeat {
            ubyte char = st[ix]
            if char==0
                return 255, false
            if char==character
                return ix, true
            ix++
        }
    }

    sub find_eol(str st) -> ubyte, bool {
        ; Locates the position of the first End Of Line character in the string.
        ; This is a convenience function that looks for both a CR or LF (byte 13 or byte 10) as being a possible Line Ending.
        ; returns the index, and true; or 255 (an invalid index) and false when the string has no line ending.
        ubyte ix = 0
        repeat {
            ubyte char = st[ix]
            if char==0
                return 255, false
            if char==10 or char==13
                return ix, true
            ix++
        }
    }

    sub rfind(str stringptr, ubyte character) -> ubyte, bool {
        ; Locates the first position of the given character in the string, starting from the right.
        ; returns the index, and true; or 255 (an invalid index) and false when the string doesn't contain the character.
        ubyte ix = length(stringptr)
        while ix!=0 {
            ix--
            if stringptr[ix]==character
                return ix, true
        }
        return 255, false
    }

    sub contains(str st, ubyte character) -> bool {
        ; Returns whether the string contains the character (never true for the terminating 0 byte).
        bool found
        void, found = find(st, character)
        return found
    }

    sub copy(str source, str target) -> ubyte {
        ; Copy a string to another, overwriting that one.
        ; Returns the length of the string that was copied.
        return ncopy(source, target, 255)
    }

    sub ncopy(str source, str target, ubyte maxlength) -> ubyte {
        ; Copy a string to another, overwriting that one, but limited to the given length.
        ; Returns the length of the string that was copied.
        ubyte ix = 0
        while ix<maxlength {
            ubyte char = source[ix]
            if char==0
                break
            target[ix] = char
            ix++
        }
        target[ix] = 0
        return ix
    }

    sub append(str target, str suffix) -> ubyte {
        ; Append the suffix string to the target. (make sure the buffer is large enough!)
        ; Returns the length of the resulting string.
        ubyte target_len = length(target)
        return copy(suffix, target+target_len) + target_len
    }

    sub nappend(str target, str suffix, ubyte maxlength) -> ubyte {
        ; Append the suffix string to the target, up to the given maximum length of the combined string.
        ; Returns the length of the resulting string.
        ubyte target_len = length(target)
        if maxlength<target_len
            return target_len
        return ncopy(suffix, target+target_len, maxlength-target_len) + target_len
    }

    sub compare(str st1, str st2) -> byte {
        ; Compares two strings for sorting (case sensitive), by their unsigned byte values.
        ; Returns -1 (255), 0 or 1, meaning: string1 sorts before, equal or after string2.
        ; Note that you can also directly compare strings and string values with each other using
        ; comparison operators ==, < etcetera (this will use strcmp automatically).
        repeat {
            ubyte char1 = st1^^
            ubyte char2 = st2^^
            if char1!=char2 {
                if char1<char2
                    return -1
                return 1
            }
            if char1==0
                return 0
            st1++
            st2++
        }
    }

    sub compare_nocase(str st1, str st2) -> byte {
        ; Compares two strings for sorting (case insensitive).
        ; Returns -1 (255), 0 or 1, meaning: string1 sorts before, equal or after string2.
        repeat {
            ubyte char1 = lowerchar(st1^^)
            ubyte char2 = lowerchar(st2^^)
            if char1!=char2 {
                if char1<char2
                    return -1
                return 1
            }
            if char1==0
                return 0
            st1++
            st2++
        }
    }

    sub lower(str st) -> ubyte {
        ; Lowercases the ISO string in-place. Returns length of the string.
        ubyte ix
        repeat {
            ubyte char=st[ix]
            if char==0
                return ix
            if char >= 'A' and char <= 'Z'
                st[ix] = char | %00100000
            ix++
        }
    }

    sub upper(str st) -> ubyte {
        ; Uppercases the ISO string in-place. Returns length of the string.
        ubyte ix
        repeat {
            ubyte char=st[ix]
            if char==0
                return ix
            if char >= 'a' and char <= 'z'
                st[ix] = char & %11011111
            ix++
        }
    }

    sub lowerchar(ubyte char) -> ubyte {
        if char >= 'A' and char <= 'Z'
            char |= %00100000
        return char
    }

    sub upperchar(ubyte char) -> ubyte {
        if char >= 'a' and char <= 'z'
            char &= %11011111
        return char
    }

    sub hash(str st) -> ubyte {
        ; 8 bit hashing function.
        ; hash(-1)=179;  hash(i) = ROL hash(i-1)  XOR  string[i]
        ; On the English word list in /usr/share/dict/words it seems to have a pretty even distribution
        ubyte hashcode = 179
        ubyte ix
        sys.clear_carry()
        repeat {
            if st[ix]!=0 {
                rol(hashcode)
                hashcode ^= st[ix]
                ix++
            } else
                return hashcode
        }
    }

    sub isdigit(ubyte character) -> bool {
        return character>='0' and character<='9'
    }

    sub isxdigit(ubyte character) -> bool {
        character = lowerchar(character)
        return isdigit(character) or character>='a' and character<='f'
    }

    sub isupper(ubyte character) -> bool {
        return character>='A' and character<='Z'
    }

    sub islower(ubyte character) -> bool {
        return character>='a' and character<='z'
    }

    sub isletter(ubyte character) -> bool {
        return islower(character) or isupper(character)
    }

    sub isspace(ubyte character) -> bool {
        return character in [32, 13, 9, 10, 141, 160]
    }

    sub isprint(ubyte character) -> bool {
        return character>=32 and character<=127 or character>=160
    }

    sub split(str s, ^^pointer parts, ubyte maxparts) -> ubyte {
        ; -- split string into parts (splits on whitespace and other non-printable characters).
        ;    The addresses of the parts are stored in the given pointer[] array, which is a long[] array on llvm32.
        ;    Returns number of parts found (up to the given maximum). Modifies the string in place!
        if s==0 or s[0]==0
            return 0

        ubyte index = 0
        ubyte numparts = 0

        while numparts < maxparts {
            skipwhitespace()
            if s[index]==0
                break
            parts^^ = s+index
            numparts++
            parts++
            skipchars()
            if s[index]==0
                break
            if numparts < maxparts
                s[index] = 0
            index++
        }
        if numparts>0 {
            parts--
            strip(parts^^)
        }
        return numparts

        sub skipwhitespace() {
            while s[index]!=0 and (isspace(s[index]) or not isprint(s[index]))
                index++
        }

        sub skipchars() {
            while s[index]!=0 and not isspace(s[index])
                index++
        }
    }

    sub strip(str s) {
        ; -- gets rid of whitespace and other non-visible characters at the edges of the string
        rstrip(s)
        lstrip(s)
    }

    sub rstrip(str s) {
        ; -- gets rid of whitespace and other non-visible characters at the end of the string
        ubyte end = length(s)
        while end!=0 {
            ubyte char = s[end-1]
            if isprint(char) and not isspace(char)
                break
            end--
        }
        s[end] = 0
    }

    sub lstrip(str s) {
        ; -- gets rid of whitespace and other non-visible characters at the start of the string (destructive)
        ^^ubyte result = lstripped(s)
        if result != s
            void copy(result, s)
    }

    sub lstripped(str s) -> str {
        ; -- returns pointer to first non-whitespace and non-visible character at the start of the string (non-destructive lstrip)
        while s^^!=0 and (isspace(s^^) or not isprint(s^^))
            s++
        return s
    }

    sub trim(str s) {
        ; -- gets rid of whitespace characters at the edges of the string
        rtrim(s)
        ltrim(s)
    }

    sub rtrim(str s) {
        ; -- gets rid of whitespace characters at the end of the string
        ubyte end = length(s)
        while end!=0 {
            if not isspace(s[end-1])
                break
            end--
        }
        s[end] = 0
    }

    sub ltrim(str s) {
        ; -- gets rid of whitespace characters at the start of the string (destructive)
        ^^ubyte result = ltrimmed(s)
        if result != s
            void copy(result, s)
    }

    sub ltrimmed(str s) -> str {
        ; -- return pointer to first non-whitespace character at the start of the string (non-destructive ltrim)
        while isspace(s^^)
            s++
        return s
    }

    sub startswith(str st, str prefix) -> bool {
        repeat {
            ubyte char = prefix^^
            if char==0
                return true
            if st^^!=char
                return false
            st++
            prefix++
        }
    }

    sub endswith(str st, str suffix) -> bool {
        ubyte suffix_len = length(suffix)
        ubyte str_len = length(st)
        if suffix_len > str_len
            return false
        return compare(st + str_len - suffix_len, suffix) == 0
    }

    sub findstr(str haystack, str needle) -> ubyte {
        ; searches for needle in haystack.
        ; returns index in haystack where it first occurs, or 255 (an invalid index) when needle doesn't occur in haystack.
        ubyte haystack_len = length(haystack)
        ubyte needle_len = length(needle)
        if needle_len <= haystack_len {
            ubyte idx = 0
            while idx <= haystack_len-needle_len {
                if startswith(haystack+idx, needle)
                    return idx
                idx++
            }
        }
        return 255
    }

    sub next_token(str source, str delimiters) -> str {
        ; -- Tokenize the source string according to the list of delimiter characters. Like C's ``strtok`` function.
        if source == 0
            source = last_token_source
        else
            last_token_source = source

        if last_token_source^^ == 0
            return 0

        while last_token_source^^ != 0 {
            ^^ubyte dptr = delimiters
            while dptr^^ != 0 {
                if last_token_source^^ == dptr^^ {
                    last_token_source^^ = 0
                    last_token_source += 1
                    return source
                }
                dptr += 1
            }
            last_token_source += 1
        }
        return source
    }
    ^^ubyte last_token_source
}
