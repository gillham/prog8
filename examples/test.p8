%zeropage basicsafe
%import math
%import textio

main {
    sub start() {
        ; put 9 nodes into the buffer sequentially.
        ; each of the first 3 nodes points to the 4th, 5th, 6th.
        ; these in turn point to the 7th, 8th and 9th.

        struct Node {
            bool flag
            ubyte value
            ^^Node next
        }


        ^^Node @shared ptr = 2000
        txt.print_uw(ptr)
        txt.nl()

        cx16.r0 = 0
        ptr = cx16.r0 + ptr - 1
        txt.print_uw(ptr)
        txt.nl()
        ptr = ptr - 1 - cx16.r0
        txt.print_uw(ptr)
        txt.nl()

        cx16.r0=0
        ptr = cx16.r0 + ptr - 10
        txt.print_uw(ptr)
        txt.nl()
        ptr = ptr - 10 - cx16.r0
        txt.print_uw(ptr)
        txt.nl()


        ; static initializer syntax:
        ^^Node @shared node0 = Node()     ; no initialization (will be in BSS and zeroed out at startup)
        ^^Node @shared node1 = Node( false, 11, 0 )
        ^^Node @shared node2 = Node( false, 22, 0 )
        ^^Node @shared node3 = Node( true, 33, 0 )

        ; list of pointers:   (W.I.P.):
;        ^^Node[5] @shared nodes
;        for nptr in nodes {
;            txt.print_uw(nptr)
;            txt.spc()
;        }
;        txt.nl()

        ; link up
        node0.next = node1
        node1.next = node2
        node2.next = node3

        ^^Node nptr = node0
        while nptr {
            txt.print("node at ")
            txt.print_uw(nptr)
            txt.print("\n flag=")
            txt.print_bool(nptr.flag)
            txt.print("\n value=")
            txt.print_ub(nptr.value)
            txt.nl()
            nptr = nptr.next
        }

        ^^Node n0,n1,n2,n3,n4,n5,n6,n7,n8

        uword buf = memory("buffer", 2000, 0)
        sys.memset(buf, 2000, 0)

        n0 = buf + 0
        n1 = buf + sizeof(Node)
        n2 = buf + sizeof(Node)*2
        n3 = buf + sizeof(Node)*3
        n4 = buf + sizeof(Node)*4
        n5 = buf + sizeof(Node)*5
        n6 = buf + sizeof(Node)*6
        n7 = buf + sizeof(Node)*7
        n8 = buf + sizeof(Node)*8

        n0.next = n3
        n1.next = n4
        n2.next = n5
        n3.next = n6
        n4.next = n7
        n5.next = n8

        n0.value = 'a'
        n1.value = 'b'
        n2.value = 'c'
        n3.value = 'd'
        n4.value = 'e'
        n5.value = 'f'
        n6.value = 'g'
        n7.value = 'h'
        n8.value = 'i'

        txt.print("struct size: ")
        txt.print_uw(sizeof(Node))
        txt.nl()

        txt.print("pointer values: ")
        txt.print_uw(n0)
        txt.spc()
        txt.print_uw(n1)
        txt.spc()
        txt.print_uw(n2)
        txt.spc()
        txt.print_uw(n3)
        txt.spc()
        txt.print_uw(n4)
        txt.spc()
        txt.print_uw(n5)
        txt.spc()
        txt.print_uw(n6)
        txt.spc()
        txt.print_uw(n7)
        txt.spc()
        txt.print_uw(n8)
        txt.nl()

        txt.print("field address: ")
        txt.print_uw(&n0.value)
        txt.spc()
        txt.print_uw(&n1.value)
        txt.spc()
        txt.print_uw(&n2.value)
        txt.nl()
        txt.print_uw(&n6.value)
        txt.spc()
        txt.print_uw(&n7.value)
        txt.spc()
        txt.print_uw(&n8.value)
        txt.nl()
        txt.print_uw(&n0.next.next.value)
        txt.spc()
        txt.print_uw(&n1.next.next.value)
        txt.spc()
        txt.print_uw(&n2.next.next.value)
        txt.nl()

        txt.print("node values: ")
        txt.chrout(n0.value)
        txt.chrout(n1.value)
        txt.chrout(n2.value)
        txt.chrout(n3.value)
        txt.chrout(n4.value)
        txt.chrout(n5.value)
        txt.chrout(n6.value)
        txt.chrout(n7.value)
        txt.chrout(n8.value)
        txt.nl()

        txt.print("linked values:\n")
        txt.print("n0: ")
        ptr = n0
        while ptr {
            txt.chrout(ptr.value)
            ptr = ptr.next
        }
        txt.nl()
        txt.print("n1: ")
        ptr = n1
        while ptr {
            txt.chrout(ptr.value)
            ptr = ptr.next
        }
        txt.nl()
        txt.print("n2: ")
        ptr = n2
        while ptr {
            txt.chrout(ptr.value)
            ptr = ptr.next
        }
        txt.nl()

        txt.print("array syntax on nodes: ")
        txt.chrout(n0[0].value)
        txt.chrout(n0[1].value)
        txt.chrout(n0[2].value)
        txt.chrout(n0[3].value)
        txt.chrout(n0[4].value)
        txt.chrout(n0[5].value)
        txt.chrout(n0[6].value)
        txt.chrout(n0[7].value)
        txt.chrout(n0[8].value)
        txt.nl()

        txt.print("array syntax followed by dereference: ")
        txt.chrout(n0[0].next.next.value)
        txt.chrout(n0[1].next.next.value)
        txt.chrout(n0[2].next.next.value)
        txt.nl()

        txt.print("assigning to fields: ")
        n0.value = 'q'
        n1.value = 'w'
        n2.value = 'e'
        n0.next.next.value = 'x'
        n1.next.next.value = 'y'
        n2.next.next.value = 'z'
        txt.chrout(n0.value)
        txt.chrout(n1.value)
        txt.chrout(n2.value)
        txt.spc()
        txt.chrout(n0.next.next.value)
        txt.chrout(n1.next.next.value)
        txt.chrout(n2.next.next.value)
        txt.spc()
        txt.chrout(n6.value)
        txt.chrout(n7.value)
        txt.chrout(n8.value)
        txt.nl()

        txt.print("ptr to simple types: ")
        word w_value = -9999
        txt.print_w(w_value)
        txt.spc()
        ^^word w_ptr = &w_value
        w_ptr^^ = 5555
        txt.print_w(w_value)
        txt.nl()

        word[] @nosplit warray = [1111,2222,3333,4444,5555,6666]
        w_ptr = &warray
        txt.print_w(w_ptr^^)
        txt.spc()
        txt.print_w(w_ptr[4]^^)
        txt.nl()

        txt.print("function call and pointer comparisons: ")
        cx16.r10=9998
        ^^uword uw_ptr = &cx16.r10
        uw_ptr^^ = 4444
        txt.print_uw(cx16.r10)
        txt.spc()
        ^^uword uw_ptr2 = func(uw_ptr, 9000)
        txt.print_uw(cx16.r10)
        txt.spc()
        txt.print_uw(uw_ptr2^^)
        txt.nl()
        uw_ptr2++
        txt.print_bool(uw_ptr2 == uw_ptr)
        txt.spc()
        txt.print_bool(uw_ptr2 != uw_ptr)
        txt.spc()
        txt.print_bool(uw_ptr2 < uw_ptr)
        txt.spc()
        txt.print_bool(uw_ptr2 > uw_ptr)
        txt.spc()
        txt.print_bool(uw_ptr2 <= uw_ptr)
        txt.spc()
        txt.print_bool(uw_ptr2 >= uw_ptr)
        txt.nl()

        ;readbyte(&thing.name)
        ;readbyte(&thing.name[1])
    }

    sub func(^^uword ptr, uword value) -> ^^uword {
        ptr^^ = value * 2
        return ptr
    }

    sub readbyte(uword ptr) {
        @(ptr) = 99
    }
}

;thing {
;    str name = "irmen"
;}
