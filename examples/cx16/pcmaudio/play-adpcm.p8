%import textio
%import floats
%import wavfile
%import adpcm
%option no_sysinit
%zeropage basicsafe

;
; Simple IMA ADPCM playback example.  (factor 4 lossy compressed pcm audio)
;
; NOTE:  this program requires 16 bits MONO audio, and 256 byte encoded block size!
; HOW TO CREATE SUCH IMA-ADPCM ENCODED AUDIO? Use sox or ffmpeg:
; $ sox --guard source.mp3 -r 8000 -c 1 -e ima-adpcm out.wav trim 01:27.50 00:09
; $ ffmpeg -i source.mp3 -ss 00:01:27.50 -to 00:01:36.50  -ar 8000 -ac 1 -c:a adpcm_ima_wav -block_size 256 -map_metadata -1 -bitexact out.wav
; Or use a tool such as https://github.com/dbry/adpcm-xq   (make sure to set correct block size)
;

main {

    ubyte adpcm_blocks_left
    uword @requirezp nibblesptr
    uword vera_rate_hz
    ubyte vera_rate
    ubyte num_adpcm_blocks
    uword adpcm_size

    sub start() {
        if not wavfile.parse_header(&wavdata.wav_data) {
            txt.print("invalid wav\n")
            sys.exit(1)
        }

        calculate_vera_rate()
        calculate_adpcm_blocks()

        txt.print_ub(num_adpcm_blocks)
        txt.print(" blocks = ")
        txt.print_uw(adpcm_size)
        txt.print(" adpcm bytes\nsamplerate = ")
        txt.print_uw(wavfile.sample_rate)
        txt.print(" vera rate = ")
        txt.print_uw(vera_rate_hz)
        txt.print("\n(b)enchmark or (p)layback? ")

        when cbm.CHRIN() {
            'b' -> benchmark()
            'p' -> playback()
        }
    }

    sub calculate_vera_rate() {
        const float vera_freq_factor = 25e6 / 65536.0
        vera_rate = (wavfile.sample_rate as float / vera_freq_factor) + 1.0 as ubyte
        vera_rate_hz = (vera_rate as float) * vera_freq_factor as uword
    }

    sub calculate_adpcm_blocks() {
        adpcm_size = wavfile.data_size_lo                   ; we assume the data is <64Kb so only low word is enough
        num_adpcm_blocks = (adpcm_size / 256) as ubyte      ; THE ADPCM DATA NEEDS TO BE ENCODED IN 256-byte BLOCKS !
    }

    sub benchmark() {
        nibblesptr = &wavdata.wav_data + wavfile.data_offset

        txt.print("\ndecoding all blocks...\n")
        cbm.SETTIM(0,0,0)
        repeat num_adpcm_blocks {
            adpcm.init(peekw(nibblesptr), @(nibblesptr+2))
            nibblesptr += 4
            repeat 252 {
               ubyte @zp nibble = @(nibblesptr)
               adpcm.decode_nibble(nibble & 15)     ; first word
               adpcm.decode_nibble(nibble>>4)       ; second word
               nibblesptr++
            }
        }
        const float REFRESH_RATE = 25.0e6/(525.0*800)       ; Vera VGA refresh rate is not precisely 60 hz!
        float duration_secs = (cbm.RDTIM16() as float) / REFRESH_RATE
        floats.print_f(duration_secs)
        txt.print(" seconds (approx)\n")
        const float PCM_WORDS_PER_BLOCK = 1 + 252*2
        float words_per_second = PCM_WORDS_PER_BLOCK * (num_adpcm_blocks as float) / duration_secs
        txt.print_uw(words_per_second as uword)
        txt.print(" decoded pcm words/sec\n")
        float src_per_second = adpcm_size as float / duration_secs
        txt.print_uw(src_per_second as uword)
        txt.print(" adpcm data bytes/sec\n")
    }

    sub playback() {
        nibblesptr = &wavdata.wav_data + wavfile.data_offset
        adpcm_blocks_left = num_adpcm_blocks

        cx16.VERA_AUDIO_CTRL = %10101111        ; mono 16 bit
        cx16.VERA_AUDIO_RATE = 0                ; halt playback
        repeat 1024 {
            cx16.VERA_AUDIO_DATA = 0
        }

        sys.set_irqd()
        cx16.CINV = &irq_handler
        cx16.VERA_IEN = %00001000               ; enable AFLOW
        sys.clear_irqd()

        cx16.VERA_AUDIO_RATE = vera_rate        ; start playback

        txt.print("\naudio via irq\n")

        repeat {
            ; audio will play via the IRQ.
        }

        ; not reached:
;        cx16.VERA_AUDIO_CTRL = %00100000
;        cx16.VERA_AUDIO_RATE = 0
;        txt.print("audio off.\n")
    }

    sub irq_handler() {
        if cx16.VERA_ISR & %00001000 {
            ; AFLOW irq.
    	    ;; cx16.vpoke(1,$fa0c, $a0)    ; paint a screen color

            ; refill the fifo buffer with one decoded adpcm block (1010 bytes of pcm data)
            adpcm.init(peekw(nibblesptr), @(nibblesptr+2))
            cx16.VERA_AUDIO_DATA = lsb(adpcm.predict)
            cx16.VERA_AUDIO_DATA = msb(adpcm.predict)
            nibblesptr += 4
            repeat 252 {
               ubyte @zp nibble = @(nibblesptr)
               adpcm.decode_nibble(nibble & 15)     ; first word
               cx16.VERA_AUDIO_DATA = lsb(adpcm.predict)
               cx16.VERA_AUDIO_DATA = msb(adpcm.predict)
               adpcm.decode_nibble(nibble>>4)       ; second word
               cx16.VERA_AUDIO_DATA = lsb(adpcm.predict)
               cx16.VERA_AUDIO_DATA = msb(adpcm.predict)
               nibblesptr++
            }

            adpcm_blocks_left--
            if adpcm_blocks_left==0 {
                ; restart adpcm data from the beginning
                nibblesptr = &wavdata.wav_data + wavfile.data_offset
                adpcm_blocks_left = num_adpcm_blocks
                txt.print("end of data, restarting.\n")
            }

        } else {
            ; it's not AFLOW, handle other IRQ here.
        }

        ;; cx16.vpoke(1,$fa0c, 0)      ; back to other screen color

        %asm {{
    	    ply
	    plx
	    pla
	    rti
        }}
    }

}

wavdata {
    %option align_page
wav_data:
    %asmbinary "small-adpcm-mono.wav"
wav_data_end:

}
