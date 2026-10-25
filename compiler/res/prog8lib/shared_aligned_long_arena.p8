; -- Long-sized arena / bump allocator for the llvm targets.
;    Manages one static memory slab at a time, provided by the caller via memory().
;    Calling init() again replaces the previously tracked slab.
;    Allocations are made by bumping a pointer forward. Individual
;    deallocations are not supported; use reset() to free everything at once.
;    Sizes are long values, and every allocation starts at a multiple of 8 bytes,
;    because the generated code expects floats, quads and pointers to be aligned to their size.

%option ignore_unused

arena {
    private pointer @shared base
    private pointer @shared next
    private pointer @shared end

    sub init(pointer base_addr, long size) {
        ; -- initialize the arena over the range [base_addr, base_addr + size)
        end = base_addr + size
        base = (base_addr + 7) & ~(7 as pointer)
        if base > end
            base = end
        next = base
    }

    sub alloc(long size) -> pointer {
        ; -- allocate size bytes from the arena. Returns 0 if the slab is exhausted.
        ;    Sizes are rounded up to a multiple of 8, so that the next allocation is aligned as well.
        if size <= 0
            return 0
        size = (size + 7) & ~7
        if size > end - next
            return 0
        pointer result = next
        next += size
        return result
    }

    sub reset() {
        ; -- reset the arena so the slab can be reused from the start
        next = base
    }
}
