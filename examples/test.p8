%import textio
;%import test_stack
%zeropage basicsafe


; NOTE: meant to test to virtual machine output target (use -target vitual)

main {

;    sub ands(ubyte arg, ubyte b1, ubyte b2, ubyte b3, ubyte b4) -> ubyte {
;        return arg>b1 and arg>b2 and arg>b3 and arg>b4
;    }
;
;    sub ors(ubyte arg, ubyte b1, ubyte b2, ubyte b3, ubyte b4) -> ubyte {
;        return arg==b1 or arg==b2 or arg==b3 or arg==b4
;    }

;    sub mcCarthy() {
;        ubyte @shared a
;        ubyte @shared b
;
;        txt.print_ub(ands(10, 2,3,4,5))
;        txt.spc()
;        txt.print_ub(ands(10, 20,3,4,5))
;        txt.spc()
;        txt.print_ub(ors(10, 2,3,40,5))
;        txt.spc()
;        txt.print_ub(ors(10, 1,10,40,5))
;        txt.spc()
;    }

    sub start() {
        ; mcCarthy()
        ;test_stack.test()

        ubyte one = 1
        ubyte two = 2
        uword onew = 1
        uword twow = 2
        ubyte[10] data = [1,2,3,4,5,6,7,8,9,10]
        uword bitmapbuf = &data

;        @(bitmapbuf+onew) = 90+one
;        @(bitmapbuf+twow) = 90+two
        bitmapbuf += 5
;        @(bitmapbuf-1) = 90+one
;        @(bitmapbuf-2) = 90+two
        @(bitmapbuf-onew) = 90+one
        @(bitmapbuf-twow) = 90+two

        ubyte value
        for value in data {
            txt.print_ub(value)     ; 3 2 97 42 5 6 7 8 9 10
            txt.spc()
        }
        txt.nl()

        ;test_stack.test()


;        ; a "pixelshader":
;        sys.gfx_enable(0)       ; enable lo res screen
;        ubyte shifter
;
;        repeat {
;            uword xx
;            uword yy = 0
;            repeat 240 {
;                xx = 0
;                repeat 320 {
;                    sys.gfx_plot(xx, yy, xx*yy + shifter as ubyte)
;                    xx++
;                }
;                yy++
;            }
;            shifter+=4
;        }
    }
}
