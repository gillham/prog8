%import textio
%import floats
%zeropage basicsafe

; float arithmetic: the iteration counts of a Mandelbrot set
main {
    sub start() {
        long total = 0
        repeat 160 + lsb(sys.argc()) {
            ubyte py
            for py in 0 to 99 {
                ubyte px
                for px in 0 to 159 {
                    float x0 = (px as float) / 160.0 * 3.5 - 2.5
                    float y0 = (py as float) / 100.0 * 2.0 - 1.0
                    float x = 0.0
                    float y = 0.0
                    ubyte iterations = 0
                    while x*x + y*y <= 4.0 and iterations < 200 {
                        float temp = x*x - y*y + x0
                        y = 2.0*x*y + y0
                        x = temp
                        iterations++
                    }
                    total += iterations
                }
            }
        }
        txt.print_l(total)
        txt.nl()
    }
}
