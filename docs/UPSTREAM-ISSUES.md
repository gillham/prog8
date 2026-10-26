# Upstream Prog8 issues

Bugs found while working on the `llvm` branch that are also on `master` and have nothing to do with the llvm targets
or quad support. Each repro was checked on a compiler built from `master` at `8bcd17674`, the virtual and cx16 targets.
"Branch" says whether `llvm` already has a fix that could be sent upstream.

Keeping the list: add new items at the end with the next number, and remove an item once its repro passes on `master`.

---

**U1. An unused `%import lists` crashes the compiler**
- Where: `codeGenIntermediate/IRUnusedCodeRemover.kt`, `pruneSymboltable`.
- Repro: `%import lists` and an empty `main.start`, on virtual or cx16 with optimization:
  `NullPointerException ... pruneSymboltable`. The check `variable.dt.subType!!.scopedNameString` runs for every
  pointer variable, and a pointer to a basic type (`sys.die`'s `^^ubyte message`) has no `subType`.
- Branch: fixed, `variable.dt.subType?.scopedNameString`.

**U2. The inliner substitutes raw arguments, so a typed pointer keeps scaled pointer arithmetic**
- Where: `codeOptimizers/Inliner.kt` (parameter substitution).
- Repro (virtual): `sub ident(long a) -> long { return a }` and `ident(np) + 1 - (np as long)` with `np` a `^^Node`
  gives -3997 with optimization and 1 without.
- Branch: fixed, `Inliner.argumentAsParameter` casts the argument to the parameter type when exactly one of them is a
  pointer.

**U3. A valid `(x / C1) / C2` gives a type error**
- Repro: `uword x = 60000` then `x = x / 1000 / 600`: `value type long doesn't match target type uword`, with and
  without optimization. The two divisions appear to be merged into one by 600000, which is a long.
- Branch: not fixed.

**U4. `pp[i] += 2` through a `^^pointer` crashes on cx16**
- Repro: `^^pointer pp = &slots`, `pp[i] = &buf`, `pp[i] += 2`:
  `NotImplementedError: inplace modification of pointer array`.
- Cause: `^^pointer` became a pointer to a bare POINTER (`Antlr2KotlinVisitor.pointerDatatypeFor`), whose element size
  fell back to the pointer's own size.
- Branch: fixed, `^^pointer` is `^^uword`/`^^long` for the target's pointer size, and `DataType.size()` throws for a
  pointer without an element type.

**U5. The optimizer turns `0 - x` on a uword into an invalid negation**
- Repro: `uword low = 5`, `uword x = 0 - low`: `value type word doesn't match target type uword` with optimization;
  `-noopt` compiles it and gives 65531.
- Branch: fixed, `ExpressionSimplifier` only rewrites `0 - x` for signed operands.

**U6. A local pointer initialized with a cast literal crashes the compiler**
- Repro: `^^ubyte @shared p = $1000 as ^^ubyte` in a subroutine: `IllegalArgumentException: numeric literal typecast
  should have been resolved at the compiler Ast level`.
- Branch: fixed.

**U7. 6502: indexed write through a `uword` struct field crashes**
- Repro: `struct Node { ubyte kind  uword data }`, `^^Node n = ^^Node : [1, &buf]`, `ubyte v = 5`, `n.data[2] = v`
  on cx16: `NotImplementedError: asmVarname for array with pointer`. An x16emu run in an earlier session (not
  re-checked) showed that a constant value instead writes into the struct's own `data` field.
- Branch: not fixed.

**U8. A constant address above `$7fff_ffff` reads address 0 on the VM**
- Repro: `ubyte @shared x = @($ffff_ffff)` on virtual compiles to `loadm.b r1.b,[$0]`. The literal is parsed as the
  long -1 and `Double.toUInt()` saturates to 0. Writes give "address out of range"; reads don't.
- Branch: not fixed.

**U9. `x = if c arr1 else arr2` is rejected, while the `return` form is accepted**
- Repro: `uword s = if cx16.r0L==0 arr1 else arr2`: `invalid assignment value`.
- Branch: not fixed.

**U10. Misleading error for an address list in a `^^ubyte[N]`**
- Repro: `^^ubyte[3] arr = [&&a, &&b, 0]` on cx16: `value has incompatible type (ubyte[]) for the variable
  (^^ubyte[]) (the variable is a split word array)`.
- Branch: not fixed.

**U11. `p^^ += 5` followed by `p^^++` on a long crashes the compiler**
- Repro: `long @shared p = $4000`, `p^^ += 5`, `p^^++`: `IllegalArgumentException: numeric literal type should be
  numeric or bool: UNDEFINED` from `StatementReorderer`. One such statement on its own gives a clean error.
- Branch: fixed, a clean error ("invalid assignment value").

**U12. Consecutive increments of a typed pointer crash the compiler**
- Repro: `^^uword @shared p = 1000`, `p++`, `p++` on cx16 or virtual: `FatalAstException: pointer literals should not
  be created` from `StatementReorderer`, which merges the additions into one with a constant of the pointer type.
- Branch: fixed, the constant gets the target's integer address type.

**U13. `buffers` doesn't compile on virtual**
- Repro: `%import buffers`: `value type long doesn't match target type uword` at `uword buffer_ptr = memory(...)`.
- Branch: fixed, `^^ubyte buffer_ptr`; the same code size on c64.

**U14. `wavfile` doesn't compile on virtual**
- Repro: `%import wavfile`: `indexing requires an iterable, address long, or pointer variable`.
- Branch: fixed, `^^ubyte @zp header` and `data_offset = (header - wav_data) as uword + 8`; 4 bytes smaller on cx16.

**U15. virtual `compression` doesn't compile, and `decode_rle` decodes wrongly**
- Repro: `%import compression` on virtual: `return value type long doesn't match subroutine return type uword`
  (`cx16.r0 = target`). `decode_rle` also branches with `if_neg` right after loading a byte, but the IR doesn't set the
  flags on a load, so a repeat run is copied as literal bytes, past the end of the target buffer.
- Branch: fixed, typed locals, `(target - orig) as uword` and `if cx16.r2L >= 128`.

**U16. virtual `strings.contains` and `diskio.f_readline` depend on undefined status flags**
- `contains` reads the carry after `find()` returns. `f_readline` tests `if_z` after `size++` for its length limit,
  and returns its status only in the carry.
- Repro: reading a file with a 300 character line and then a short one: two `f_readline` calls give 255 and 255.
- Branch: fixed, `contains` uses `find`'s bool, and `f_readline` returns `size, status` like the cx16 and c64
  versions.

**U17. The VM crashes at the end of the input**
- Repro: `printf 'ab\n' | prog8c -vm prog.p8ir` for a program calling `txt.input_chars` twice:
  `kotlin.io.ReadAfterEOFException` with a stack trace.
- Branch: fixed, `readlnOrNull()`, ending the program with exit status 1.

**U18. The VM and llvm silently accept status branches whose flags are undefined**
- On the IR only comparisons set the status flags (and shifts, rotates and `set_carry`/`clear_carry` the carry), so
  an `if_xx` after a load or an increment branches on stale flags (U15, U16).
- Branch: `AstChecker` warns about such a branch on the virtual and llvm targets.

**U19. Docs: `popf` says the value was pushed by `pushl`**
- Where: `docs/source/libraries.rst`, the `popf` entry.
- Branch: fixed, it says `pushf`.

**U20. Shifting a long by a word or long variable crashes the IR codegen**
- Repro: `long @shared q = 1`, `uword @shared n = 3`, `q <<= n` (or `q = q << n`, `q >>= n`) on virtual:
  `IllegalArgumentException: register r2 given multiple types! LONG and BYTE`. `TypecastsAdder` widens the count to
  the left operand's type, while the IR shift instructions take a byte count. The 6502 code compiles it and uses the
  count's low byte.
- Branch: fixed, `ExpressionGen.translateShiftCount` narrows a wider count to its low byte (`LSIGB`), for the
  in-place and the expression shifts.
