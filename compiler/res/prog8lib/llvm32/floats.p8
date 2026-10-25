; Prog8 definitions for floating point handling on the llvm32 target, where a float is a 64-bit double.
; The square root of a float is the sqrt builtin function, as on the other targets.
; Floats print and convert to strings as on the virtual target; rnd() takes its random bits from math.rndw().

%import math
%option enable_floats, ignore_unused

sys {
    %option merge       ; add some constants to sys

    const float  MAX_FLOAT = 1.7976931348623157e+308
    const float  MIN_FLOAT = -1.7976931348623157e+308
}

txt {
    %option merge       ; add function to txt

    alias print_f = floats.print
}

math {
    %option merge, ignore_unused       ; add functions to math

    alias lerpf = floats.lerp
    alias lerpf_fast = floats.lerp_fast
    alias interpolatef = floats.interpolate
}


floats {

        const float  π         = 3.141592653589793
        const float  PI        = π
        const float  TWOPI     = 2*π
        const float  E         = 2.718281828459045
        const float  EPSILON   = 4.9E-324

        alias log = floats.ln


sub print(float value) {
    ; ---- prints the floating point value (without a newline and no leading spaces), as tostr() gives it
    void tostr(value)
    %ir {{
        load.p r99200.p,#floats.tostr.buffer
        syscall 3 (r99200.p)
        return
    }}
}

sub tostr(float value) -> str {
    ; ---- converts the floating point value to a string (no leading spaces), as the virtual target does:
    ;      with the fewest digits that read back as the same value, and in scientific notation below 0.001 and from 10000000 on
    str @shared buffer = " " * 31
    str @shared digits = " " * 31
    ubyte[17] mantissa
    ubyte length = 0
    ubyte count = 0
    ubyte index = 0
    word exponent = 0

    if value != value {
        add_text("NaN")
        return finish()
    }
    if peek(&value + 7) & $80 != 0 {
        add('-')
        value = -value
    }
    if value == 0.0 {
        add_text("0.0")
        return finish()
    }
    if value > sys.MAX_FLOAT {
        add_text("Infinity")
        return finish()
    }

    ; digits gets d.ddde+xx, with as many digits as needed
    %ir {{
        load.p r99200.p,#floats.tostr.digits
        loadm.f fr99000.f,[floats.tostr.value]
        syscall 81 (r99200.p, fr99000.f)
    }}
    while digits[index] != 'e' {
        if digits[index] != '.' {
            mantissa[count] = digits[index]
            count++
        }
        index++
    }
    bool negative_exponent = digits[index+1] == '-'
    index += 2
    while digits[index] != 0 {
        exponent = exponent * 10 + (digits[index] - '0') as word
        index++
    }
    if negative_exponent
        exponent = -exponent
    while count > 1 and mantissa[count-1] == '0'
        count--

    if exponent >= -3 and exponent < 7 {
        if exponent < 0 {
            add_text("0.")
            repeat (-exponent - 1) as ubyte
                add('0')
            for index in 0 to count-1
                add(mantissa[index])
        } else {
            for index in 0 to lsb(exponent) {
                if index < count
                    add(mantissa[index])
                else
                    add('0')
            }
            add('.')
            if count > lsb(exponent) + 1 {
                for index in lsb(exponent) + 1 to count-1
                    add(mantissa[index])
            } else
                add('0')
        }
    } else {
        add(mantissa[0])
        add('.')
        if count > 1 {
            for index in 1 to count-1
                add(mantissa[index])
        } else
            add('0')
        add('E')
        if exponent < 0 {
            add('-')
            exponent = -exponent
        }
        if exponent >= 100
            add('0' + lsb(exponent / 100))
        if exponent >= 10
            add('0' + lsb(exponent / 10 % 10))
        add('0' + lsb(exponent % 10))
    }
    return finish()

    sub add(ubyte character) {
        buffer[length] = character
        length++
    }

    sub add_text(str text) {
        ubyte i = 0
        while text[i] != 0 {
            add(text[i])
            i++
        }
    }

    sub finish() -> str {
        buffer[length] = 0
        return buffer
    }
}

sub parse(str value) -> float {
    ; -- parse a string value of a number to float, as the virtual target does: spaces are left out, and the number
    ;    ends at the first other character that isn't one of +-0123456789.eE; 0.0 when that is no number
    str @shared buffer = " " * 255
    ubyte length = 0
    ubyte index = 0
    ubyte character
    repeat {
        character = value[index]
        if character == 0 or length == 255
            break
        if character != ' ' {
            if character not in "+-0123456789.eE"
                break
            buffer[length] = character
            length++
        }
        index++
        if index == 0
            break
    }
    buffer[length] = 0
    %ir {{
        load.p r99200.p,#floats.parse.buffer
        syscall 82 (r99200.p): fr99000.f
        returnr.f fr99000.f
    }}
}

sub rnd() -> float {
    ; -- a random float from 0.0 up to but not including 1.0, made of two math.rndw() values
    float high = math.rndw() as float
    float low = math.rndw() as float
    return (high * 65536.0 + low) / 4294967296.0
}

sub rndseed(float seed) {
    ; -- seeds the random generator of rnd(), which is the one of math.rndw() and math.rnd(), with the bits of the seed
    long low = peekl(&seed)
    long high = peekl(&seed + 4)
    math.rndseed(lsw(low) ^ msw(high), msw(low) ^ lsw(high))
}


sub pow(float value, float power) -> float {
    %ir {{
        loadm.f fr0.f,[floats.pow.value]
        loadm.f fr1.f,[floats.pow.power]
        fpow.f fr0.f,fr1.f
        returnr.f fr0.f
    }}
}

sub exp(float value) -> float {
    ; -- e to the power of the value
    return pow(E, value)
}

sub sin(float angle) -> float {
    %ir {{
        loadm.f fr0.f,[floats.sin.angle]
        fsin.f fr1.f,fr0.f
        returnr.f fr1.f
    }}
}

sub cos(float angle) -> float {
    %ir {{
        loadm.f fr0.f,[floats.cos.angle]
        fcos.f fr1.f,fr0.f
        returnr.f fr1.f
    }}
}

sub tan(float value) -> float {
    %ir {{
        loadm.f fr0.f,[floats.tan.value]
        ftan.f fr1.f,fr0.f
        returnr.f fr1.f
    }}
}

sub atan(float value) -> float {
    %ir {{
        loadm.f fr0.f,[floats.atan.value]
        fatan.f fr1.f,fr0.f
        returnr.f fr1.f
    }}
}

sub asin(float value) -> float {
    ; -- arcsine, from -π/2 to π/2. Values outside of -1..1 count as -1 or 1.
    value = clampf(value, -1.0, 1.0)
    ; the half-angle form doesn't divide by 0 for -1 and 1, unlike atan(value / sqrt(1 - value*value))
    return 2.0 * atan(value / (1.0 + sqrt(1.0 - value*value)))
}

sub acos(float value) -> float {
    ; -- arccosine, from 0 to π. Values outside of -1..1 count as -1 or 1.
    return π/2 - asin(value)
}

; two-argument arctangent that returns an angle in the correct quadrant
; for the signs of x and y, normalized to the range [0, 2π]
sub atan2(float y, float x) -> float {
    float atn
    if x == 0 {
        atn = π/2
        if y == 0 return 0
        if y < 0 {
            atn += π
        }
    } else {
        atn = atan(y / x)
    }
    if x < 0 atn += π
    if atn < 0 atn += 2*π
    return atn
}

; reciprocal functions
sub secant(float value) -> float { return 1.0 / cos(value) }
sub csc(float value)    -> float { return 1.0 / sin(value) }
sub cot(float value)    -> float { return 1.0 / tan(value) }

sub ln(float value) -> float {
    %ir {{
        loadm.f fr0.f,[floats.ln.value]
        fln.f fr1.f,fr0.f
        returnr.f fr1.f
    }}
}

sub log2(float value) -> float {
    %ir {{
        loadm.f fr0.f,[floats.log2.value]
        flog.f fr1.f,fr0.f
        returnr.f fr1.f
    }}
}

sub rad(float angle) -> float {
    ; -- convert degrees to radians (d * pi / 180)
    return angle * PI / 180.0
}

sub deg(float angle) -> float {
    ; -- convert radians to degrees (d * (1/ pi * 180))
    return angle * 180.0 / PI
}

sub round(float value) -> float {
    ; -- rounds to the nearest whole number, and halfway values to the even one, like the virtual target
    %ir {{
        loadm.f fr0.f,[floats.round.value]
        fround.f fr1.f,fr0.f
        returnr.f fr1.f
    }}
}

sub floor(float value) -> float {
    %ir {{
        loadm.f fr0.f,[floats.floor.value]
        ffloor.f fr1.f,fr0.f
        returnr.f fr1.f
    }}
}

sub ceil(float value) -> float {
    %ir {{
        loadm.f fr0.f,[floats.ceil.value]
        fceil.f fr1.f,fr0.f
        returnr.f fr1.f
    }}
}


sub minf(float f1, float f2) -> float {
    if f1<f2
        return f1
    return f2
}


sub maxf(float f1, float f2) -> float {
    if f1>f2
        return f1
    return f2
}


sub clampf(float value, float minimum, float maximum) -> float {
    if value<minimum
        value=minimum
    if value<maximum
        return value
    return maximum
}

sub mod(float value, float divisor) -> float {
    return value - floor(value / divisor) * divisor
}

sub normalize(float value) -> float {
    return value
}

sub lerp(float v0, float v1, float t) -> float {
    ; Linear interpolation (LERP)
    ; Precise method, which guarantees v = v1 when t = 1.
    ; returns an interpolation between two inputs (v0, v1) for a parameter t in the closed unit interval [0, 1]
    return (1 - t) * v0 + t * v1
}

sub lerp_fast(float v0, float v1, float t) -> float {
    ; Linear interpolation (LERP)
    ; Imprecise (but slightly faster) method, which does not guarantee v = v1 when t = 1
    ; returns an interpolation between two inputs (v0, v1) for a parameter t in the closed unit interval [0, 1]
    return v0 + t * (v1 - v0)
}

sub interpolate(float v, float inputMin, float inputMax, float outputMin, float outputMax) -> float {
    ; Interpolate a value v in interval [inputMin, inputMax] to output interval [outputMin, outputMax]
    if outputMin==outputMax
        return outputMin
    v = (v - inputMin) / (inputMax - inputMin)
    return v * (outputMax - outputMin) + outputMin
}

}
