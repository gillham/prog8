%import syslib

psg {
    ; $1F9C0 - $1F9FF 	16 blocks of 4 PSG registers (16 voices)
    ; 00  frequency word LSB
    ; 01  frequency word MSB.    freqword = HERZ / 0.3725290298461914
    ; 02  bit 7 =right, bit 6 = left, bits 5-0 = volume 0-63 levels
    ; 03  bit 7,6 = waveform, bits 5-0 = Pulse width 0-63
    ; waveform: 0=pulse, 1=sawtooth, 2=triangle, 3=noise
    const ubyte PULSE    = %00000000
    const ubyte SAWTOOTH = %01000000
    const ubyte TRIANGLE = %10000000
    const ubyte NOISE    = %11000000
    const ubyte LEFT     = %01000000
    const ubyte RIGHT    = %10000000

    sub voice(ubyte voice_num, ubyte channel, ubyte volume, ubyte waveform, ubyte pulsewidth) {
        cx16.vpoke(1, $f9c2 + voice_num * 4, channel | volume)
        cx16.vpoke(1, $f9c3 + voice_num * 4, waveform | pulsewidth)
    }

;    sub freq_hz(ubyte voice_num, float hertz) {
;        ; this would rely on floating point math to convert hertz to vera frequency
;        ; TODO should be replaced by integer math maybe with a lookup table?
;        uword vera_freq = (hertz / 0.3725290298461914) as uword
;        freq(voice_num, vera_freq)
;    }

    sub freq(ubyte voice_num, uword vera_freq) {
        cx16.vpoke(1, $f9c1 + voice_num*4, msb(vera_freq))
        cx16.vpoke(1, $f9c0 + voice_num*4, lsb(vera_freq))
    }

    sub volume(ubyte voice_num, ubyte vol) {
        uword reg = $f9c2 + voice_num * 4
        cx16.vpoke(1, reg, cx16.vpeek(1, reg) & %11000000 | vol)
    }

    sub pulse_width(ubyte voice_num, ubyte pw) {
        uword reg = $f9c3 + voice_num * 4
        cx16.vpoke(1, reg, cx16.vpeek(1, reg) & %11000000 | pw)
    }

    sub envelope(ubyte voice_num, ubyte attack, ubyte release) {
        envelope_attacks[voice_num] = attack * $0040
        envelope_releases[voice_num] = release * $0040
        if attack
            attack = 0
        else
            attack = 63     ; max volume when no attack is set
        envelope_volumes[voice_num] = mkword(attack, 0)
        envelope_states[voice_num] = 0
    }

    sub silent() {
        for cx16.r15L in 0 to 15 {
            volume(cx16.r15L, 0)
            envelope_volumes[cx16.r15L] = 0
            envelope_states[cx16.r15L] = 255
        }
    }

    sub envelopes_irq() {
        ; cx16.r0 = the volume word (volume scaled by 256)
        ; cx16.r15L = the voice number
        ; the other virtual registers are used to backup vera registers.

        cx16.r13L = cx16.VERA_CTRL
        cx16.r13H = cx16.VERA_ADDR_L
        cx16.r14L = cx16.VERA_ADDR_M
        cx16.r14H = cx16.VERA_ADDR_H

        for cx16.r15L in 0 to 15 {
            when envelope_states[cx16.r15L] {
                0 -> {
                    ; attack
                    cx16.r0 = envelope_volumes[cx16.r15L] + envelope_attacks[cx16.r15L]
                    if msb(cx16.r0) & %11000000 or envelope_attacks[cx16.r15L]==0 {
                        cx16.r0 = mkword(63, 0)
                        envelope_attacks[cx16.r15L] = 0
                        envelope_states[cx16.r15L] = 1  ; start release
                    }
                    envelope_volumes[cx16.r15L] = cx16.r0
                    volume(cx16.r15L, msb(cx16.r0))     ; TODO optimize to not use vpoke and use vera auto increment
                }
                1 -> {
                    ; release
                    cx16.r0 = envelope_volumes[cx16.r15L] - envelope_releases[cx16.r15L]
                    if msb(cx16.r0) & %11000000 {
                        cx16.r0 = 0
                        envelope_releases[cx16.r15L] = 0
                    }
                    envelope_volumes[cx16.r15L] = cx16.r0
                    volume(cx16.r15L, msb(cx16.r0))     ; TODO optimize to not use vpoke and use vera auto increment
                }
            }
        }
        cx16.VERA_CTRL = cx16.r13L
        cx16.VERA_ADDR_L = cx16.r13H
        cx16.VERA_ADDR_M = cx16.r14L
        cx16.VERA_ADDR_H = cx16.r14H
    }

    ubyte[16] envelope_states
    uword[16] envelope_volumes
    uword[16] envelope_attacks
    uword[16] envelope_releases
}