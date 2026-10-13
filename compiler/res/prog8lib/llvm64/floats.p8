; Prog8 definitions for floating point handling on the llvm64 target, where a float is a 64-bit double.
; The square root of a float is the sqrt builtin function, as on the other targets.
; Not available on llvm64 yet: printing, converting to and from strings (print, tostr, parse, txt.print_f), and rnd/rndseed.

%option enable_floats, ignore_unused

sys {
    %option merge       ; add some constants to sys

    const float  MAX_FLOAT = 1.7976931348623157e+308
    const float  MIN_FLOAT = -1.7976931348623157e+308
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
