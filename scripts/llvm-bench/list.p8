%import textio
%zeropage basicsafe

; pointers and struct fields: building and walking a linked list in a memory slab
main {
    struct Node {
        uword value
        ^^Node next
    }
    const uword COUNT = 6000

    sub start() {
        ^^Node pool = memory("pool", COUNT * sizeof(Node), 8)
        ^^Node head = 0
        uword i
        for i in 0 to COUNT - 1 {
            ^^Node node = pool + i
            node.value = i * 3 + lsb(sys.argc())
            node.next = head
            head = node
        }
        long total = 0
        repeat 40000
            total += walk(head)
        txt.print_l(total)
        txt.nl()
    }

    sub walk(^^Node node) -> long {
        long sum = 0
        while node != 0 {
            sum += node.value
            node = node.next
        }
        return sum
    }
}
