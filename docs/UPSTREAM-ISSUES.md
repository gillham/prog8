# Upstream Prog8 issues

Bugs found while working on the `llvm` branch that are also on `master` and have nothing to do with the llvm targets
or quad support. Each repro was checked on a compiler built from `master` at `8bcd17674`, the virtual and cx16 targets,
and all of them still fail on `master` at `c530aaf32`.
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
  without optimization. `ConstantFoldingOptimizer` merges the two divisions into one by 600000, which is a long.
- Branch: fixed, the divisions are only merged when the product of the constants fits the operand's type.

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

**U7. 6502: indexing a uword or pointer struct field uses the field's own address**
- Repro: `struct Node { ubyte kind  uword data }`, `^^Node n = ^^Node : [1, &buf]`, `ubyte v = 5`, `n.data[2] = v`
  on cx16: `NotImplementedError: asmVarname for array with pointer`. The other forms compile, but read and write at
  `n + offset(data) + index`, inside the struct, instead of at the address `data` holds plus the index:
  `n.data[2]`, `n.data[i]`, `n.data[3] = 99` and the same through a `^^ubyte` field all give wrong results.
- Branch: fixed in `PointerAssignmentsGen.deref` (a uword field is loaded like a pointer field),
  `AssignmentAsmGen` (constant-index reads), and `PrimitiveAssignmentsGen.assignVariableByte` (checks for the
  pointer target before the constant index); checked with ksim65 and x16emu.

**U8. A constant address above `$7fff_ffff` reads address 0 on the VM**
- Repro: `ubyte @shared x = @($ffff_ffff)` or `peek($ffff_ffff)` on virtual compiles to `loadm.b r1.b,[$0]`. The
  literal is the long -1 (the same 32 bits), and `Double.toUInt()` saturates it to 0. Writes such as
  `@($ffff_ffff) = 5` and `poke($ffff_fff0, 5)` are rejected with "address out of range", and `peekw($ffff_fffe)`
  gives `no implicit cast available from BYTE to UWORD for value -2.0`.
- Branch: fixed, the IR codegen converts constant addresses through `Long` (`PtNumber.addressValue()`), so -1 is
  `$ffffffff`; the writes and `peekw` already compiled here because of the branch's handling of such literals.

**U9. `return if c arr1 else arr2` crashes the 6502 codegen**
- Repro: `sub f(ubyte c) -> uword { return if c==0 arr1 else arr2 }` on cx16:
  `IllegalArgumentException: Failed requirement` at `IfExpressionAsmGen.assignIfExpression`. A returned array stands
  for its address, but the requirement only allows a string or pointer if expression for a uword or pointer target.
  (An assignment `uword s = if c arr1 else arr2` is rejected on purpose, as `uword s = arr1` is; `&arr1` gives the
  address there.)
- Branch: fixed, the requirement also allows an if expression of arrays; on x16emu it returns the right addresses.

**U10. An address list with a 0 in a pointer array is rejected with a misleading message**
- Repro: `^^ubyte[3] arr = [&&a, &&b, 0]` on cx16: `value has incompatible type (ubyte[]) for the variable
  (^^ubyte[]) (the variable is a split word array)`. The 0 (a null pointer) makes `ArrayLiteral.guessDatatype` treat
  the list as numbers, where the ubyte wins. With `@nosplit` the message still says the variable is split.
- Branch: fixed, a 0 among pointers doesn't decide the array type, the pointer array element check accepts it
  (and says "pointer array element" instead of "struct initializer element"), and the split note respects `@nosplit`.

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

**U21. A `@nosplit` pointer array becomes a uword array on targets with wider pointers**
- Repro (virtual): `^^ubyte[2] @nosplit arr = [&&a, &&b]` then `^^ubyte p = arr[0]`:
  `incompatible value type, can only assign long or identical pointer type`. `VariousCleanups` turns a `@nosplit`
  pointer array into a `uword` array, whatever the target's pointer size.
- Branch: fixed, it becomes an array of the target's pointer-sized integer (`pointerBaseType`), so `long` on the VM
  and `uword` on 6502.

**U22. No warning when a struct initializer gives a pointer field a split word array**
- Repro (cx16): `uword[4] words` (split), `^^Node n = ^^Node : [1, &buf, &buf, &words]` with `^^uword` fields:
  no warning that the field then reads the split array as consecutive words. (The undefined `p8v_words` symbol in
  the struct data was fixed on master in 2627c6ff1.)
- Branch: fixed, a pointer field given a split word array gets the same warning as a variable.

**U23. `sgn` of an unsigned value gives -1 on the VM**
- Repro: `uword uw = 40000`, `ubyte ub = 200`, `txt.print_b(sgn(uw))` and `txt.print_b(sgn(ub))` on virtual print -1 -1;
  the compiler's own warning says the result for an unsigned type "is always 0 or 1", and cx16 gives 1 1. The IR's
  `SGN` instruction reads its operand as signed.
- Branch: fixed, `BuiltinFuncGen.funcSgn` compares an unsigned value with 0 instead of using `SGN`.

**U24. `on..goto` inside a loop body crashes the compiler**
- Repro: `for i in 0 to 4 { on i call (a, b, c) }` on any target stops with an internal error, "there are leftover
  vardecls in the nested scope ... they should have been moved/placed in the declaration scope (subroutine) by now".
  `CodeDesugarer` puts the generated label array (and the temporary for a computed index) in the nearest scope, which
  is the loop body.
- Branch: fixed, both declarations go in the subroutine.

**U25. `repeat 1` crashes the IR code generator without optimizations**
- Repro: `repeat 1 { count++ }` (or a variable that is folded to 1) compiled with `-noopt` for virtual stops with an
  internal error, "defer should have been transformed". `IRCodeGen.translate(PtRepeatLoop)` translates all children
  of the loop for a count of 1, which includes the count itself.
- Branch: fixed, it translates only the loop body.

**U26. A signed right shift of an array element at a constant index is a logical shift on the VM**
- Repro: `byte[3] barray`, `barray[1] = -128`, `barray[1] >>= 1` gives 64 instead of -64 on virtual; the same for
  `word` arrays, split or not. `compiler/test/arithmetic/bitshift2.p8` reports it as ">> b array error". With a
  variable index the result is right. `AssignmentGen.operatorShiftRightInplace` always uses `LSRM`/`LSRNM` for a
  constant index.
- Branch: fixed, it uses `ASRM`/`ASRNM` for signed elements.

**U28. A struct pointer cast to `pointer` and passed to a parameter of another struct pointer type crashes the 6502 codegen**
- Repro: `struct A { ubyte x }`, `struct B { uword y }`, `sub show(^^B b)`, `^^A a = 1000`, `show(a as pointer)` on c64 or
  cx16 with `-noopt`: `AssemblyError: argument type incompatible` (with optimization the call is inlined here, but not
  in larger programs). `pointer` is `uword` on 6502, and `BeforeAsmTypecastCleaner` removes every pointer to uword
  cast outside pointer arithmetic, so the codegen gets a `^^A` argument for a `^^B` parameter.
- Branch: fixed, the cast stays on a call argument that isn't assignable to its parameter without it.

**U29. A memory access outside the VM's memory ends with a Java stack trace**
- Repro: `long @shared address = $efbeaddb`, `cx16.r0L = @(address)` on virtual (or following a garbage pointer):
  `java.lang.ArrayIndexOutOfBoundsException` from `Memory.getUB` with a stack trace, which looks like a compiler crash.
- Branch: fixed, `Memory` throws a `MemoryAccessException` with the address, and the VM reports it as a VM error with
  the instruction and the nearest source line, and ends with exit status 1.

**U30. A load through a pointer variable is fused with its increment past a second read of the variable**
- Repro: `sub scan(^^ubyte s)` with `while s^^ != 0 { txt.chrout(s^^)  c = s^^ & $ff  txt.chrout(lsb(c))  s++ }` and
  `scan("abc")` prints `ab bc c` on virtual instead of `aa bb cc` (right with `-noopt`; verified on `master` 77d45b5e1).
  `IRPeepholeOptimizer.fusePointerPostInc` turns the first load and the `incm` into `LOADP_INC`, which moves the
  increment before the second read; `fuseLoadExtend` had made that read a `LOADIZ`, which the pass skips.
- Branch: fixed, the fusion is skipped when an instruction or a call between the load and the `incm` can access the
  pointer variable.

**U31. `min` and `max` of two ubyte expressions on 6502 ignore one operand**
- Repro: `ubyte a = 15`, `ubyte b = 55`, `ubyte r = min(1 + a, b - 2)` gives 53 instead of 16 on c64 and cx16, with and
  without optimization (`master` bcc9ba5ee); virtual is right. `BuiltinFunctionsAsmGen.funcMin` pushes the second
  operand, computes the first into A, and then `pla` overwrites it, so the value is compared with itself. `funcMax` has
  the same fault and always gives the first operand.
- Branch: fixed, both keep A in Y across the `pla`.

**U32. `x*y + x` drops a call with side effects**
- Repro: with `next()` returning 1, 2, 3...: `next()*10 + next()` gives 11 instead of 12 on every target; `-noopt` is
  right (`master` bcc9ba5ee). `n() + n()*10` and `n()*10 - n()` go wrong the same way. The rewrites at the top of
  `ExpressionSimplifier` don't check the common term for side effects. `diskio.status_code()` is affected: its
  `(cbm.CHRIN()-'0')*10 + (cbm.CHRIN()-'0')` returns 66 for status 62.
- Branch: fixed, `determineY` declines the rewrite when the common term or the other factor has side effects.

**U33. Comparing a uword array element with a ubyte variable reads the next byte as the high byte on 6502**
- Repro: `arr[s] == id` with `uword[4] arr` and a ubyte `id` generates `cmp p8v_id` and `cpy p8v_id+1`, also with
  `-noopt` (`master` bcc9ba5ee); a match is only found when the byte after `id` is 0. `arr[s] == id as uword` works.
  `w == id` with a uword variable `w`, `!=`, and the if-expression forms fail the same way: `getWordOperands` in
  `IfElseAsmGen` and `IfExpressionAsmGen` unwraps the cast and uses the byte variable as a word.
- Branch: fixed.

**U34. Comparing two constant-index elements of a split word array doesn't assemble**
- Repro: `uword[2] a = [5, 3]`, `if a[0] < a[1] ...` on c64: `not defined symbol 'p8v_a'` (`master` bcc9ba5ee). It fails
  on cx16, c128 and pet32 too. The right operand is generated as `p8v_a+2`, as if the array weren't split.
  `AsmGen.tryGetStaticAddress` returned a single address for a constant-index element of a split array; it affects
  `<`, `<=`, `>` and `>=` of both signed and unsigned arrays.
- Branch: fixed.

**U35. `sys.memcmp` doesn't assemble with basicsafe on c64**
- Repro: `sys.memcmp(a, b, 4)` with `%zeropage basicsafe` on c64: `not a direct page address bits '$cfe0'`
  (`master` bcc9ba5ee). It also fails to build on c128 and pet32. `shared_sys_functions.p8` uses `(cx16.r0),y`, but
  the virtual registers are outside zero page there; `%zeropage kernalsafe` builds. No other library routine for
  these targets uses a virtual register as an assembly pointer.
- Branch: fixed; memcmp copies the two addresses to `P8ZP_SCRATCH_W2` and `P8ZP_SCRATCH_PTR`.

**U36. `%option force_output` doesn't keep a const-only block in a module of its own**
- Repro: a module holding only `settings { %option force_output  const ubyte LEVEL = 7  %asm {{ ... }} }`, used only
  from the assembler of another module: `not defined symbol 'p8b_settings'` with optimization, on either side of the
  import (`master` bcc9ba5ee); `-noopt` builds. Likely `UnusedCodeRemover.before(module)`, which removes an unused
  module without checking its blocks for `force_output`.
- Branch: fixed; `UnusedCodeRemover.before(module)` keeps a module that has a `force_output` block.

**U37. The VM reads a `uword` pointer variable as 4 bytes after `LOADP_INC`/`STOREP_INC` fusion**
- Repro: `sub mb(uword src, uword dest, uword count)` with `@(dest) = @(src)`, `src++`, `dest++` in a loop prints
  `1 2 3 4 5 6 7 8` instead of `2 3 4 5 6 6 7 8` on virtual; `-noopt` is right, and `pointer` parameters are right
  (`master` bcc9ba5ee). `IRPeepholeOptimizer.fusePointerPostInc` also fuses a word-sized variable, and the VM's
  `InsLOADP_INC` always reads the variable with `getSL`.
- Branch: fixed; the fusion only applies to 4-byte (`.p` or `.l`) pointer variables.

**U38. `diskio.exists()` of a missing file can hang a true-drive 1541**
- Repro: 50 calls of `diskio.exists("nofile")` on VICE x64sc with `-drive8type 1541 -drive8truedrive`: the program hit
  the cycle limit in 3 of 4 runs on `master` bcc9ba5ee. Opening the file and reading the status from the command channel,
  without `CHKIN` on the file's channel, finished in 4 of 4 runs. `f_open` does `CHKIN` on a channel the drive never
  opened. Not checked on real hardware, and the root cause isn't proven. Later runs on the branch: hung 4 of 4; adding
  `CLRCHN` before the failure path's `CLOSE` still hung 3 of 3; reading the status before `CHKIN` finished 3 of 3. That
  last approach can't go into `f_open` as is: closing the command channel makes the drive close all its channels, so
  the file just opened can't be read (and a file open for writing would be closed too).
- Branch: not fixed.

**U40. `@(&w)` reads the low byte of a word variable on the big-endian m68k targets**
- Repro: `uword w = $1234`, then `print_ub(@(&w))` and `print_ub(@(&w+1))`: prints `52 18` on qemu68k instead of
  `18 52` (`master` a4b59f6b3). `CodeDesugarer.after(DirectMemoryRead)` rewrites `@(&w)` to `lsb(w)` and `@(&w+1)` to
  `msb(w)` on every target, also with `-noopt` (`-printast2` shows `lsb()`), but on a big-endian machine the byte at
  the word's address is the high byte.
- Branch: fixed; on a big-endian target the rewrite is `@(&w)` to `msb(w)` and `@(&w+1)` to `lsb(w)`.

**U41. With `-noopt`, no qemu68k program assembles**
- Repro: a program with an empty `main`, built with `-target qemu68k -noopt`: vasm stops with "instruction not
  supported on selected architecture" at `andi.l #-2,a2` in `qemu.bootinfo_ptr` (`master` a4b59f6b3); optimized
  builds and `amiga500 -noopt` assemble. qemu68k is a 68020 target (vasm gets `-m68020`), but no 680x0 has an
  ANDI to an address register: its destination must be a data register or memory, so vasm's message is misleading.
  The unoptimized code rounds the address up to even (`(x + 1) & -2`) in an address register; the AND has to
  go through a data register. Still fails on `master` 2cac14654.
- Branch: fixed; the m68k code generator does `and`, `or`, `eor` and `not` on a value in an address register in d0
  (`move.l a2,d0 / andi.l #-2,d0 / movea.l d0,a2`).

**U42. On c128, a program over about 9 KB never starts, and returning to BASIC crashes**
- Repro: `bugs/u42_c128_startup_under_basic_rom.p8` (16 KB of filler, then `txt.print("hello c128")`) in x128: nothing
  after `RUN` (`master` a4b59f6b3); a program that returns from `main` stops with `?UNRESOLVED REFERENCE ERROR`. The
  entry code after the BASIC line calls `init_system`, `init_system_phase2` and `cleanup_at_exit`, which lie after the
  program code, above $4000, while the KERNAL's bank 15 still has the BASIC ROM at $4000-$bfff. `init_system`,
  `cleanup_at_exit` and `sys.reset_system` also select bank 15 (`$ff00 = 0`) under their own code.
- Branch: fixed; the entry code banks the BASIC ROM out first (`$ff00 = $0e`), the library routines use `$0e`, and
  `cleanup_at_exit` returns through a tail in the entry code that selects bank 15 below $4000.

**U43. On c128, `%zeropage basicsafe` and `kernalsafe` put variables at $92 and $a4, which the KERNAL changes**
- Repro: `bugs/u43_c128_basicsafe_zp92.p8`: variables at `146` and `164` in the .asm (`master` a4b59f6b3).
  `bugs/u43_c128_zeropage_kernal_clobbers.p8`, run in x128 with a disk holding a SEQ file `t.txt`, lists the zero page
  bytes changed by disk I/O: $92 and $a4 are among them (the KERNAL's tape/serial bytes).
- Branch: fixed; `C128Zeropage` leaves them out of the basicsafe, floatsafe and kernalsafe lists.

**U44. `%zpreserved` and `%zpallowed` in an imported module are ignored without a word**
- Repro: `bugs/u44_zpreserved_imported.p8` with `-srcdirs bugs`: the imported module's `%zpreserved $92,$a4` has no
  effect and gives no warning (`master` a4b59f6b3). Only the main module's directives are read (`Compiler.kt`), and
  `ModuleImporter` drops them from imported modules.
- Branch: fixed; a warning says the directive only applies in the main module, and the docs say so.

**U45. On c128, `%zeropage basicsafe` puts variables at $18-$1a, and BASIC prints garbage after the program**
- Repro: `bugs/u45_c128_basicsafe_temppt.p8` in x128, then `print 7` at READY: " 7" is followed by garbage until `RUN`
  or `CLR` (`master` 8bcd17674; the .asm has `p8v_w5 = 24`). $18 is BASIC 7's TEMPPT and $19/$1a its LASTPT, the
  temporary string descriptor stack pointers, which BASIC does not reset when SYS returns.
- Branch: fixed; `C128Zeropage` leaves $18-$1a out of the basicsafe and floatsafe lists.

**U46. An inlined `idx % 32` with a constant 0 argument gives "no implicit cast from boolean to integer allowed"**
- Repro: `bugs/u46_inline_mod_zero_bool_cast.p8`: `check(col(0), 0)` with `col` returning `idx % 32` (or `idx & 31`)
  and `check` comparing its parameter; the error is reported on `return idx % 32` (`master` 8bcd17674, v12.3.4 too).
  Compiles with `-noopt`, and with a non-zero or runtime argument.
- Branch: fixed; `ExpressionSimplifier` rewrites `x & 0` to a 0 of the expression's integer type (it gave the
  boolean `false`, which only the inlined `0 & 31` reached before constant folding).

**U47. `flag = not flag` in another block gives "undefined symbol: flag"**
- Repro: `bugs/u47_not_toggle_other_block.p8`: `other.toggle()` does `flag = not flag` on a block variable of
  `other`, called from `main` (`master` 8bcd17674). Compiles with `-noopt` and prints `true`; also fine with both in
  `main`, or with an if/else instead of `not`. Probably the inliner copying the body into `main` without qualifying
  `flag`, as in U48.
- Branch: fixed; the Inliner fully scopes every name in an inlined one-line assignment, on both sides (it only did
  the side that made the body inlineable).

**U48. A one-line sub doing `@(cells + index()) = s`, called from another block, gives "undefined symbol: index"**
- Repro: `bugs/u48_inline_call_other_block.p8` (`master` 8bcd17674). Compiles with `-noopt` and prints `7`; also
  fine with the offset in a local variable first. The inliner copies the body into `main.start` with `index`
  unqualified.
- Branch: fixed, as U47.

**U49. On pet32, `txt.setchr`, `getchr` and `plot` use 80 column rows after a 40 character line**
- Repro: `bugs/u49_pet32_setchr_linked_line.p8` in `xpet -model 4032`: the second `*` lands on row 20 instead of 10,
  and the result line is misplaced (`master` 8bcd17674, same library code). They double the row index again when
  bit 6 of `$D5` is set, to detect an 80 column PET, but `$D5` is the editor's current logical line length: 79 on a
  40 column PET while the cursor is on a linked line, as after printing exactly 40 characters.
- Branch: fixed; the row index finds the screen width once and keeps it: 40 columns when the 1 KB of video RAM
  shows again at $8400, or when `$D5` is 39; else 80. `txt.width()` uses the same. Checked in xpet 4032 and 8032.

**U50. A void call of a sub whose body is `return other()` ends the caller after that call**
- Where: `codeOptimizers/Inliner.kt` (`possiblyInlineFunctioncallStmt`), with `StatementReorderer`'s tail call.
- Repro: `bugs/u50_inline_tail_call_void.p8`: `void wrap()` in a `repeat 3` loop, `wrap()` being `return inner()`.
  On virtual it prints `i` instead of `ixixixend` (`master` 8bcd17674; correct with `-noopt`), with an "unreachable
  code" warning. `StatementReorderer` turns `return inner()` into `goto inner`, a tail call, and the inliner copies
  that `goto` into the caller, which then leaves the caller when `inner` returns. Found as `void txt.waitkey()` on
  the llvm targets, whose `waitkey()` is `return chrin()`.
- Branch: fixed; the inliner inlines a `goto` to a subroutine as a call of it.

**U51. `sys.memset` and `memsetw` take a `uword` size on the 32-bit targets, and the VM's `memcopy` too**
- Where: `compiler/res/prog8lib/virtual/syslib.p8` (`memcopy`, `memset`, `memsetw`) with the VM's `MEMCOPY`, `MEMSET`
  and `MEMSETW` in `virtualmachine/SysCalls.kt`; `shared_m68k_memory_routines.p8` (`memset`, `memsetw`, `memsetl`).
- On these targets addresses are 32 bits, but a size over 65535 can't be given, and a larger one cast to `uword`
  silently handles only its low 16 bits. The m68k `memcopy` already takes a `long count`. Found with a gap buffer
  of 344 KB on llvm64, where a large move left zeros in the text; it would show the same on the Amiga with a gap over
  64 KB.
- Also: the VM's `memcopy` copies forwards, so a move to a higher overlapping address repeats the first byte
  (`sys.memcopy(&buf, &buf+1, 10)` gives `1 1 1 ...`). The docs say `memcopy` only handles non-overlapping areas, so
  this is documented, but the llvm targets' `memcopy` (`memmove`) handles overlap.
- Branch: llvm32 and llvm64 take a `long` size for all three, and `memcopy` handles overlap there; virtual and the
  m68k targets are unchanged, as on `master`.

**U52. A negative signed constant or initial value comes back positive from a `.p8ir` file**
- Where: `intermediate/IRFileReader.kt` (`parseConstant`, `parseInitValue`).
- Repro: `const long LOWEST = -2147483648` and `long @shared value = LOWEST`; the `.p8ir` file has
  `long main.value=$80000000`, the two's complement bits as hexadecimal, and the reader takes that as +2147483648.
  `IRFileReader().read()` then gives a constant of 2147483648, and the new 6502 code generator, reading the file,
  initializes `value` to `$7fffffff`. The same goes for signed bytes and words, and for array elements.
- Branch: fixed; the reader gives a signed byte, word or long value above its type's maximum the sign back.

**U53. Building a program from a `.p8ir` file**
- Not a bug: a feature request from a tool that writes Prog8 IR, which has no way to run it on the 6502 targets.
- Branch: `prog8c -target cx16 program.p8ir` builds the program with the new 6502 code generator, and with the llvm
  code generator for the llvm targets (`compileIrFile` in `compiler/Compiler.kt`, `generateFromIR` in both back ends;
  see `docs/source/compiling.rst`). The changes to shared code are small: the dispatch in `CompilerMain`, the split
  of `New6502CodeGenerator.generate()`, and `CompilationOptions.floats` became a `var`.

**U54. A constant index above 65535 through a struct pointer field crashes the compiler**
- Where: `compiler/astprocessing/VariousCleanups.kt`, the rewrite `ptr.field[index] --> peek(ptr.field + index)`,
  and `codeGenIntermediate/AssignmentGen.translateRegularAssignPointerIndexed` for a store.
- Repro: `b.data[70000] = 42` with `^^Buf b` and a `^^ubyte data` field: "Failed requirement" in `NumericLiteral`
  (it builds a `UWORD` literal of the index), and for the store "indirect displacement out of range: 70000".
- Branch: fixed; the index gets the target's pointer type, a constant one too big for 16-bit pointers is a normal
  error at its position, and a store with an offset beyond the displacement range adds it to the pointer first.

**U55. An index through a struct pointer field is cut to 16 bits, and a negative one is taken as positive**
- Where: the same rewrite in `VariousCleanups.kt` casts every index to `uword`; for a store, `loadIndexReg` in
  `codeGenIntermediate/IRCodeGen.kt` narrows the index to a word and `elementOffset` zero extends it.
- Repro on virtual or qemu68k: `long i = 70000` and `b.data[i] = 42` writes at `b.data + 4464`; with `word w = -1`,
  `b.data[w] = 77` writes 65535 elements further instead of one element back. Indexing a local copy of the pointer
  (`d[i]`) is correct, as `CodeDesugarer` already uses the pointer type there.
- Branch: fixed; a long index keeps all its bits and a signed index its sign when pointers are wider than 16 bits
  (`pointerIndexOffset` in `IRCodeGen.kt`).

**U56. `repeat` with a constant count above 65536 is an internal error on the IR targets**
- Where: `codeGenIntermediate/IRCodeGen.kt`, `translate(repeat: PtRepeatLoop)`: `require(constRepeats in 1..65536)`.
- Repro: `repeat 70000 count++` on virtual or qemu68k; `-check` passes.
- Branch: fixed; a larger constant count uses the counted loop of a non-constant count.

**U57. A subroutine that returns a float with other values crashes the IR code generator**
- Where: `codeGenIntermediate/IRCodeGen.kt`, `setCpuRegister`: for a calling-convention slot it always stores the
  integer register, which is -1 for a float ("register number out of range: -1"). The m68k back end then also
  emitted `fmove.s fp2,fp0` for the store into the slot, which the assembler rejects (`InstrLoadStore.kt`, STOREHR).
- Repro: `sub f(ubyte x) -> bool, float { return true, 1.5 }` and `ok, v = f(1)` on virtual or qemu68k.
- Branch: fixed in both places.

**U58. A multi-value return loses its later values when the first one calls a subroutine that returns several**
- Where: `codeGenIntermediate/IRCodeGen.kt`, the translation of a `return` with several values: it stores the
  later values in their return slots before it evaluates the first one, whose call overwrites those slots.
- Repro: `return first(), 7`, where `first()` does `r, t = pair()` with `pair()` returning two values: the caller
  gets 99 instead of 7 (virtual, qemu68k and llvm; the 6502 code generator is correct). A recursive call does the same.
- Branch: fixed; all values are evaluated before any of them is stored, and the first is still stored last.

**U59. `-newcodegen`: rol2 and ror2 are plain shifts**
- Where: `codeGenNew6502/InstrBitwise.kt` (`rotateLeft`, `rotateLeftMemory`, `rotateRight`, `rotateRightMemory`)
  start with `clc`, so the bit that leaves one end does not come back in at the other; and
  `codeGenIntermediate/BuiltinFuncGen.kt` (`funcRolRor`) rotates the two halves of an element of a split word
  array separately (`rolm.b` on `_lsb` and on `_msb`), which is only right for rol and ror, through the carry.
- Repro: `compiler/test/arithmetic/bitshift2.p8` with `-target cx16 -newcodegen`; `rol2` of 130 gives 4.
- Branch: fixed; the carry first gets the bit that wraps around, and rol2/ror2 of a split array element load,
  rotate and store the whole word. bitshift2.p8 prints the same with both code generators.

**U60. `-newcodegen`: a for loop over an array of strings is an internal error**
- Where: `codeGenIntermediate/IRCodeGen.kt`, the for loop over an array: `loopvarDt > elementDt` compares the
  `IRDataType` enum order, in which POINTER comes after LONG, so a 16-bit string address is widened for a
  `^^ubyte` loop variable; the widening register was also declared WORD while EXT.w writes a LONG.
- Repro: `for strptr in lines` with `str[] lines` and `^^ubyte strptr`, `-target cx16 -newcodegen`: "Reg type
  mismatch for register r6 type LONG: expected WORD". `examples/cx16/cxlogo.p8` fails the same way.
- Branch: fixed; the widths are compared with POINTER as the target's address type, and the widened register has
  the type EXT writes.

**U61. Reading a `.p8ir` file fails when the XML parser splits the text of a `<REGS>` element**
- Where: `intermediate/IRFileReader.kt`, `parseCodeBlock`: it requires exactly one characters event between
  `<REGS>` and `</REGS>`, but StAX may hand a CDATA section over in several pieces, depending on where it falls
  in the parser's buffer.
- Repro: an unoptimized program of some size, where a chunk's register list happens to cross a buffer boundary:
  `prog8c -target virtual -noopt -emu` then stops with "Failed requirement" in `parseCodeBlock`. Whether a given
  program hits it depends on the file's layout, so a small change elsewhere makes it come and go.
- Branch: fixed; the reader skips all text events there.

**U62. `-newcodegen`: a return, break or goto out of a repeat loop leaves the loop counter on the stack**
- Where: `codeGenNew6502/AsmGen.kt`, `translateLoopChunk` and the copy of it in `emitNestedSubroutine`: a counted
  loop keeps its counter in Y and saves it with `tya`/`pha` around the body, so leaving the body skips the `pla`
  and the next `rts` returns to a wrong address. In a nested subroutine the save was done per body chunk, after the
  chunk's label, so a do-until loop at the top of the body pushed the counter on every pass.
- Repro: `repeat 10 { if next() == 4 return true }` with `-target cx16 -newcodegen` ends in the monitor;
  `examples/maze.p8` stops in the monitor the same way.
- Branch: fixed; a counted loop counts in a byte or word variable of its own in BSS, like the regular 6502 code
  generator does, so nothing is left on the stack; trip counts above 256 now work too, they were cut to a byte.
