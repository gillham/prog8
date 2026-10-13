; Number conversion routines for the llvm64 target, in pure Prog8.

conv {

; ----- number conversions to decimal strings ----

    %option ignore_unused

    str  string_out = "????????????????????????????????"       ; result buffer for the string conversion routines
    str  hex_digits = "0123456789abcdef"

    alias str_u16 = str_uw
    alias str_i16 = str_w
    alias str_i32 = str_l

sub  str_ub0(ubyte value) -> str {
    ; ---- convert the ubyte in decimal string form, with left padding 0s (3 positions total)
    ubyte hundreds = value / 100
    value -= hundreds*100
    ubyte tens = value / 10
    value -= tens*10
    string_out[0] = hundreds+'0'
    string_out[1] = tens+'0'
    string_out[2] = value+'0'
    string_out[3] = 0
    return string_out
}

sub  str_ub(ubyte value) -> str {
    ; ---- convert the ubyte in decimal string form, without left padding 0s
    internal_str_ub(value, string_out)
    return string_out
}

sub  str_b(byte value) -> str {
    ; ---- convert the byte in decimal string form, without left padding 0s
    ^^ubyte out_ptr = &string_out
    if value<0 {
        @(out_ptr) = '-'
        out_ptr++
        value = -value
    }
    internal_str_ub(value as ubyte, out_ptr)
    return string_out
}

private sub internal_str_ub(ubyte value, str out_ptr) {
    ubyte hundreds = value / 100
    value -= hundreds*100
    ubyte tens = value / 10
    value -= tens*10
    if hundreds!=0
        goto output_hundreds
    if tens!=0
        goto output_tens
    goto output_ones
output_hundreds:
    @(out_ptr) = hundreds+'0'
    out_ptr++
output_tens:
    @(out_ptr) = tens+'0'
    out_ptr++
output_ones:
    @(out_ptr) = value+'0'
    out_ptr++
    @(out_ptr) = 0
}

sub  str_ubhex  (ubyte value) -> str {
    ; ---- convert the ubyte in hex string form
    string_out[0] = hex_digits[value>>4]
    string_out[1] = hex_digits[value&15]
    string_out[2] = 0
    return string_out
}

sub  str_ubbin  (ubyte value) -> str {
    ; ---- convert the ubyte in binary string form
    ^^ubyte out_ptr = &string_out
    repeat 8 {
        rol(value)
        if_cc
            @(out_ptr) = '0'
        else
            @(out_ptr) = '1'
        out_ptr++
    }
    @(out_ptr) = 0
    return string_out
}

sub  str_uwbin  (uword value) -> str {
    ; ---- convert the uword in binary string form
    ^^ubyte out_ptr = &string_out
    repeat 16 {
        rol(value)
        if_cc
            @(out_ptr) = '0'
        else
            @(out_ptr) = '1'
        out_ptr++
    }
    @(out_ptr) = 0
    return string_out
}

sub  str_uwhex  (uword value) -> str {
    ; ---- convert the uword in hexadecimal string form (4 digits)
    ubyte bits = msb(value)
    string_out[0] = hex_digits[bits>>4]
    string_out[1] = hex_digits[bits&15]
    bits = lsb(value)
    string_out[2] = hex_digits[bits>>4]
    string_out[3] = hex_digits[bits&15]
    string_out[4] = 0
    return string_out
}

sub  str_ulhex  (long value) -> str {
    ; ---- convert the long in hexadecimal string form (8 digits)
    uword upperw = msw(value)
    uword lowerw = lsw(value)
    ubyte bits = msb(upperw)
    string_out[0] = hex_digits[bits>>4]
    string_out[1] = hex_digits[bits&15]
    bits = lsb(upperw)
    string_out[2] = hex_digits[bits>>4]
    string_out[3] = hex_digits[bits&15]
    bits = msb(lowerw)
    string_out[4] = hex_digits[bits>>4]
    string_out[5] = hex_digits[bits&15]
    bits = lsb(lowerw)
    string_out[6] = hex_digits[bits>>4]
    string_out[7] = hex_digits[bits&15]
    string_out[8] = 0
    return string_out
}

sub  str_uw0  (uword value) -> str {
    ; ---- convert the uword in decimal string form, with left padding 0s (5 positions total)
    uword value2 = value/10
    ubyte digits = value-value2*10 as ubyte
    uword value3 = value2/10
    ubyte tens = value2-value3*10 as ubyte
    uword value4 = value3/10
    ubyte hundreds = value3-value4*10 as ubyte
    uword value5 = value4/10
    ubyte thousands = value4-value5*10 as ubyte
    uword value6 = value5/10
    ubyte tenthousands = value5-value6*10 as ubyte
    string_out[0] = tenthousands+'0'
    string_out[1] = thousands+'0'
    string_out[2] = hundreds+'0'
    string_out[3] = tens+'0'
    string_out[4] = digits+'0'
    string_out[5] = 0
    return string_out
}

sub  str_uw  (uword value) -> str {
    ; ---- convert the uword in decimal string form, without left padding 0s
    internal_str_uw(value, string_out)
    return string_out
}

sub  str_w  (word value) -> str {
    ; ---- convert the (signed) word into decimal string form, without left padding 0's
    ^^ubyte out_ptr = &string_out
    if value<0 {
        @(out_ptr) = '-'
        out_ptr++
        value = -value
    }
    internal_str_uw(value as uword, out_ptr)
    return string_out
}

private sub internal_str_uw(uword value, str out_ptr) {
    uword value2 = value/10
    ubyte digits = value-value2*10 as ubyte
    uword value3 = value2/10
    ubyte tens = value2-value3*10 as ubyte
    uword value4 = value3/10
    ubyte hundreds = value3-value4*10 as ubyte
    uword value5 = value4/10
    ubyte thousands = value4-value5*10 as ubyte
    uword value6 = value5/10
    ubyte tenthousands = value5-value6*10 as ubyte
    if tenthousands!=0
        goto output_tenthousands
    if thousands!=0
        goto output_thousands
    if hundreds!=0
        goto output_hundreds
    if tens!=0
        goto output_tens
    goto output_ones
output_tenthousands:
    @(out_ptr) = tenthousands+'0'
    out_ptr++
output_thousands:
    @(out_ptr) = thousands+'0'
    out_ptr++
output_hundreds:
    @(out_ptr) = hundreds+'0'
    out_ptr++
output_tens:
    @(out_ptr) = tens+'0'
    out_ptr++
output_ones:
    @(out_ptr) = digits+'0'
    out_ptr++
    @(out_ptr) = 0
}

sub  str_l  (long value) -> str {
    ; ---- convert the (signed) long into decimal string form, without left padding 0's
    ^^ubyte out_ptr = &string_out
    if value<0 {
        @(out_ptr) = '-'
        out_ptr++
        value = -value      ; -2147483648 stays the same, but as an unsigned number that is 2147483648
    }
    internal_str_u32(value, out_ptr)
    return string_out
}

sub  str_u32  (long value) -> str {
    ; ---- convert the 32 bits of the long, read as an unsigned number, into decimal string form, without left padding 0's
    internal_str_u32(value, string_out)
    return string_out
}

private sub internal_str_u32(long value, str out_ptr) {
    ubyte[10] digits
    ubyte count = 0
    if value<0 {
        ; 2^31 or more as an unsigned number, which the signed division can't handle: divide by 10 as (value>>>1) / 5
        long unsignedquotient = ((value >> 1) & $7fffffff) / 5
        digits[0] = value - unsignedquotient*10 as ubyte
        count = 1
        value = unsignedquotient
    }
    do {
        long quotient = value / 10
        digits[count] = value - quotient*10 as ubyte
        count++
        value = quotient
    } until value==0
    while count!=0 {
        count--
        @(out_ptr) = digits[count]+'0'
        out_ptr++
    }
    @(out_ptr) = 0
}

sub  str_bool  (bool value) -> str {
    ; ---- convert the boolean into the string "true" or "false"
    if value
        return "true"
    return "false"
}

}
