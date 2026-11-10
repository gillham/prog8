/*
 * The float routines of the z80 profiles that z88dk's math32 library lacks or gets wrong, built along when the program uses
 * floats (float_source), with --math32 (float_link_args), where sccz80's double is the same 32-bit float as LLVM's float:
 * - prog8_z80_roundeven (c_symbols roundevenf), as math32 has no roundeven and its roundevenf gives 0.
 * - prog8_z80_ftoa (float_digits_symbol): the shortest digits that read back as the float, as math32's printf only gets about
 *   7 digits right.
 * - prog8_z80_strtod (c_symbols strtod): the nearest float to a decimal number, as math32's strtod is off in the last place.
 * Both conversions are exact, with big integers of 16-bit limbs (Burger and Dybvig's free-format algorithm for the digits).
 */
#include <math.h>
#include <string.h>

double prog8_z80_roundeven(double x)
{
    double whole = floor(x);
    double rest = x - whole;
    /* half way: to the even neighbour */
    if (rest > 0.5 || (rest == 0.5 && floor(whole / 2.0) * 2.0 != whole))
        whole += 1.0;
    return whole;
}

/* the bits of a float: sccz80's double is LLVM's float here */
union prog8_z80_bits {
    double value;
    unsigned long bits;
};

/* a big unsigned integer of 16-bit limbs, the least significant first; len limbs are in use */
#define LIMBS 26
struct big {
    unsigned int len;
    unsigned int limb[LIMBS];
};

static void big_set(struct big *a, unsigned long value)
{
    memset(a, 0, sizeof(struct big));
    a->limb[0] = (unsigned int)value;
    a->limb[1] = (unsigned int)(value >> 16);
    a->len = a->limb[1] ? 2 : (a->limb[0] ? 1 : 0);
}

static void big_mul(struct big *a, unsigned int factor)
{
    unsigned long carry = 0;
    unsigned int i;
    for (i = 0; i < a->len; i++) {
        carry += (unsigned long)a->limb[i] * factor;
        a->limb[i] = (unsigned int)carry;
        carry >>= 16;
    }
    if (carry && a->len < LIMBS)
        a->limb[a->len++] = (unsigned int)carry;
}

/* times 10 to the power, in steps of 10000 */
static void big_mul_pow10(struct big *a, int power)
{
    while (power >= 4) {
        big_mul(a, 10000);
        power -= 4;
    }
    while (power-- > 0)
        big_mul(a, 10);
}

static void big_shl(struct big *a, int bits)
{
    unsigned int whole = bits >> 4, i;
    bits &= 15;
    if (a->len == 0)
        return;
    if (whole) {
        for (i = a->len; i-- > 0;)
            if (i + whole < LIMBS)
                a->limb[i + whole] = a->limb[i];
        for (i = 0; i < whole; i++)
            a->limb[i] = 0;
        a->len = a->len + whole > LIMBS ? LIMBS : a->len + whole;
    }
    if (bits) {
        unsigned int carry = 0, next;
        for (i = 0; i < a->len; i++) {
            next = a->limb[i] >> (16 - bits);
            a->limb[i] = (a->limb[i] << bits) | carry;
            carry = next;
        }
        if (carry && a->len < LIMBS)
            a->limb[a->len++] = carry;
    }
}

static void big_shr1(struct big *a)
{
    unsigned int i;
    for (i = 0; i < a->len; i++)
        a->limb[i] = (a->limb[i] >> 1) | (i + 1 < a->len ? (a->limb[i + 1] << 15) : 0);
    while (a->len && a->limb[a->len - 1] == 0)
        a->len--;
}

static void big_add(struct big *a, struct big *b)
{
    unsigned long sum = 0;
    unsigned int i, len = a->len > b->len ? a->len : b->len;
    for (i = 0; i < len; i++) {
        sum += (unsigned long)a->limb[i] + b->limb[i];
        a->limb[i] = (unsigned int)sum;
        sum >>= 16;
    }
    a->len = len;
    if (sum && a->len < LIMBS)
        a->limb[a->len++] = (unsigned int)sum;
}

/* a - b, with a not smaller than b */
static void big_sub(struct big *a, struct big *b)
{
    unsigned int i, borrow = 0;
    long difference;
    for (i = 0; i < a->len; i++) {
        difference = (long)a->limb[i] - (i < b->len ? b->limb[i] : 0) - borrow;
        borrow = difference < 0;
        a->limb[i] = (unsigned int)(difference + (borrow ? 65536L : 0));
    }
    while (a->len && a->limb[a->len - 1] == 0)
        a->len--;
}

static int big_cmp(struct big *a, struct big *b)
{
    unsigned int i;
    if (a->len != b->len)
        return a->len < b->len ? -1 : 1;
    for (i = a->len; i-- > 0;)
        if (a->limb[i] != b->limb[i])
            return a->limb[i] < b->limb[i] ? -1 : 1;
    return 0;
}

/* compares a + b with c */
static int big_cmp_sum(struct big *a, struct big *b, struct big *c)
{
    static struct big sum;
    memcpy(&sum, a, sizeof(struct big));
    big_add(&sum, b);
    return big_cmp(&sum, c);
}

static int big_bits(struct big *a)
{
    unsigned int top;
    int bits;
    if (a->len == 0)
        return 0;
    top = a->limb[a->len - 1];
    bits = (a->len - 1) * 16;
    while (top) {
        bits++;
        top >>= 1;
    }
    return bits;
}

/* the quotient digit of r / s (below 10), leaving the remainder in r */
static int big_digit(struct big *r, struct big *s)
{
    int digit = 0;
    while (big_cmp(r, s) >= 0) {
        big_sub(r, s);
        digit++;
    }
    return digit;
}

/* a comparison result past the bound: above 0, or 0 too when the bound is [inclusive]; sccz80 gets `c ? x >= 0 : x > 0` wrong */
static int past(int comparison, int inclusive)
{
    if (comparison > 0)
        return 1;
    if (comparison == 0 && inclusive)
        return 1;
    return 0;
}

static struct big r, s, mplus, mminus, saved_r;

/*
 * Writes the positive, finite value in buffer as "%.*e" would with the fewest digits that read back as the value (but at
 * least two, as Java's Double.toString and the other targets do), such as "1.5e+00".
 */
void prog8_z80_ftoa(char *buffer, double value)
{
    union prog8_z80_bits bits;
    unsigned long f;
    int e, k, digit, count = 0, even, low, high, exponent;
    char digits[12];
    char *out = buffer;

    bits.value = value;
    f = bits.bits & 0x7FFFFFUL;
    e = (int)((bits.bits >> 23) & 0xFF);
    if (e) {
        f |= 0x800000UL;
        e -= 150;
    } else {
        e = -149;
    }
    even = 1;
    if (f & 1)
        even = 0;
    /* value = r / s, the gaps to the neighbours mplus / s and mminus / s */
    if (e >= 0) {
        big_set(&r, f);
        big_shl(&r, e + 1);
        big_set(&s, 2);
        big_set(&mplus, 1);
        big_shl(&mplus, e);
        big_set(&mminus, 1);
        big_shl(&mminus, e);
        if (f == 0x800000UL) {
            big_shl(&mplus, 1);
            big_shl(&r, 1);
            big_shl(&s, 1);
        }
    } else {
        big_set(&r, f);
        big_shl(&r, 1);
        big_set(&s, 1);
        big_shl(&s, 1 - e);
        big_set(&mplus, 1);
        big_set(&mminus, 1);
        if (f == 0x800000UL && e > -149) {
            big_shl(&mplus, 1);
            big_shl(&r, 1);
            big_shl(&s, 1);
        }
    }
    /* k: the decimal exponent, with value / 10^k in [0.1, 1); estimated from the binary one, then corrected */
    k = (int)(((long)(e + 23) * 1233) >> 12);
    if (k >= 0)
        big_mul_pow10(&s, k);
    else {
        big_mul_pow10(&r, -k);
        big_mul_pow10(&mplus, -k);
        big_mul_pow10(&mminus, -k);
    }
    while (past(big_cmp_sum(&r, &mplus, &s), even)) {
        big_mul(&s, 10);
        k++;
    }
    for (;;) {
        static struct big tenfold;
        memcpy(&tenfold, &r, sizeof(struct big));
        big_add(&tenfold, &mplus);
        big_mul(&tenfold, 10);
        if (past(big_cmp(&tenfold, &s), even))
            break;
        big_mul(&r, 10);
        big_mul(&mplus, 10);
        big_mul(&mminus, 10);
        k--;
    }
    memcpy(&saved_r, &r, sizeof(struct big));
    for (;;) {
        big_mul(&r, 10);
        big_mul(&mplus, 10);
        big_mul(&mminus, 10);
        digit = big_digit(&r, &s);
        low = past(big_cmp(&mminus, &r), even);
        high = past(big_cmp_sum(&r, &mplus, &s), even);
        if (!low && !high) {
            digits[count++] = (char)digit;
            continue;
        }
        if (low && high) {
            /* the nearer of the two, or the even one half way */
            int nearer;
            big_shl(&r, 1);
            nearer = big_cmp(&r, &s);
            if (nearer > 0 || (nearer == 0 && (digit & 1)))
                digit++;
        } else if (high)
            digit++;
        digits[count++] = (char)digit;
        break;
    }
    if (count == 1) {
        /* two digits, rounded half to even */
        int both;
        big_mul(&saved_r, 10);
        digits[0] = (char)big_digit(&saved_r, &s);
        big_mul(&saved_r, 10);
        digits[1] = (char)big_digit(&saved_r, &s);
        big_shl(&saved_r, 1);
        both = digits[0] * 10 + digits[1];
        digit = big_cmp(&saved_r, &s);
        if (digit > 0 || (digit == 0 && (both & 1)))
            both++;
        if (both == 100) {
            both = 10;
            k++;
        }
        digits[0] = (char)(both / 10);
        digits[1] = (char)(both % 10);
        count = 2;
    }
    *out++ = (char)('0' + digits[0]);
    *out++ = '.';
    for (digit = 1; digit < count; digit++)
        *out++ = (char)('0' + digits[digit]);
    *out++ = 'e';
    exponent = k - 1;
    if (exponent < 0) {
        *out++ = '-';
        exponent = -exponent;
    } else
        *out++ = '+';
    if (exponent >= 10)
        *out++ = (char)('0' + exponent / 10);
    else
        *out++ = '0';
    *out++ = (char)('0' + exponent % 10);
    *out = 0;
}

/* the most significant decimal digits that the parse keeps; a nonzero rest becomes one more digit 1, so it still rounds right */
#define KEPT_DIGITS 40

static struct big number, divisor;

/*
 * The float nearest to the decimal number at text (rounded half to even): [+-]digits[.digits][(e|E)[+-]digits], with *end
 * after it. Infinity when it is too large, 0 when too small; 0 with *end at text when there is no number.
 */
double prog8_z80_strtod(char *text, char **end)
{
    union prog8_z80_bits result;
    char *p = text;
    int negative = 0, digits = 0, seen = 0, exponent = 0, rest = 0, shift, bitcount, binary;
    unsigned long mantissa;

    if (*p == '+' || *p == '-')
        negative = *p++ == '-';
    big_set(&number, 0);
    for (; *p >= '0' && *p <= '9'; p++) {
        seen = 1;
        if (digits < KEPT_DIGITS) {
            if (digits || *p != '0') {
                big_mul(&number, 10);
                big_set(&divisor, (unsigned long)(*p - '0'));
                big_add(&number, &divisor);
                digits++;
            }
        } else {
            exponent++;
            if (*p != '0')
                rest = 1;
        }
    }
    if (*p == '.') {
        p++;
        for (; *p >= '0' && *p <= '9'; p++) {
            seen = 1;
            if (digits < KEPT_DIGITS) {
                if (digits || *p != '0') {
                    big_mul(&number, 10);
                    big_set(&divisor, (unsigned long)(*p - '0'));
                    big_add(&number, &divisor);
                    digits++;
                }
                exponent--;
            } else if (*p != '0')
                rest = 1;
        }
    }
    if (!seen) {
        if (end)
            *end = text;
        return 0.0;
    }
    if (*p == 'e' || *p == 'E') {
        char *mark = p++;
        int sign = 1, power = 0, any = 0;
        if (*p == '+' || *p == '-')
            sign = *p++ == '-' ? -1 : 1;
        for (; *p >= '0' && *p <= '9'; p++) {
            any = 1;
            if (power < 1000)
                power = power * 10 + (*p - '0');
        }
        if (any)
            exponent += sign * power;
        else
            p = mark;
    }
    if (end)
        *end = p;
    if (rest) {
        big_mul(&number, 10);
        big_set(&divisor, 1);
        big_add(&number, &divisor);
        exponent--;
    }
    result.bits = 0;
    if (number.len == 0 || digits + exponent < -46) {
        /* zero, or below half the smallest float */
    } else if (digits + exponent > 39) {
        result.bits = 0x7F800000UL;
    } else {
        /* number * 10^exponent = quotient * 2^-shift, with a quotient of 26 bits and a remainder: the sticky bit */
        if (exponent >= 0) {
            big_mul_pow10(&number, exponent);
            big_set(&divisor, 1);
        } else {
            big_set(&divisor, 1);
            big_mul_pow10(&divisor, -exponent);
        }
        shift = big_bits(&divisor) - big_bits(&number) + 25;
        if (shift > 0)
            big_shl(&number, shift);
        else
            big_shl(&divisor, -shift);
        /* the quotient bit by bit */
        big_shl(&divisor, 26);
        mantissa = 0;
        for (bitcount = 26; bitcount >= 0; bitcount--) {
            mantissa <<= 1;
            if (big_cmp(&number, &divisor) >= 0) {
                big_sub(&number, &divisor);
                mantissa |= 1;
            }
            big_shr1(&divisor);
        }
        rest = number.len != 0;
        /* mantissa has 26 or 27 bits; value = mantissa * 2^(-shift) */
        binary = -shift;
        while (mantissa >= 0x2000000UL) {
            rest |= (int)(mantissa & 1);
            mantissa >>= 1;
            binary++;
        }
        /* mantissa has 25 bits now, 24 and a rounding bit, so value = 1.f * 2^(binary + 24): the exponent field, biased by 127 */
        bitcount = binary + 24 + 127;
        if (bitcount <= 0) {
            /* a subnormal: fewer bits */
            while (bitcount <= 0 && mantissa) {
                rest |= (int)(mantissa & 1);
                mantissa >>= 1;
                bitcount++;
            }
            bitcount = 0;
        }
        if ((mantissa & 1) && (rest || (mantissa & 2)))
            mantissa += 2;
        mantissa >>= 1;
        if (mantissa >= 0x1000000UL) {
            mantissa >>= 1;
            bitcount++;
        }
        if (bitcount == 0 && mantissa >= 0x800000UL)
            bitcount = 1;
        if (bitcount >= 255)
            result.bits = 0x7F800000UL;
        else
            result.bits = ((unsigned long)bitcount << 23) | (mantissa & 0x7FFFFFUL);
    }
    if (negative)
        result.bits |= 0x80000000UL;
    return result.value;
}
