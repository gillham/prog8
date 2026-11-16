# Merging master into the llvm branch

How `origin/master` (upstream Prog8) is merged into `llvm`, and the log of those merges. The log tells another
Prog8 compiler that follows the language of `master` what changed in each merge, so it can keep in step without
reading every upstream commit.

## The process

1. **Start clean.** No uncommitted changes in tracked files, `llvm` level with `origin/llvm`. `git fetch origin`,
   then list what comes in: `git log --oneline --reverse llvm..origin/master`. The range of the entry is
   `git merge-base llvm origin/master` .. `origin/master`.

2. **Read the commits before merging.** Go through them with `git show --stat` and sort each one under a heading of
   the entry (see the format below). Where to look:
   - language and grammar: `parser/src/main/antlr/Prog8ANTLR.g4`, `compilerAst`, `compiler/astprocessing`;
   - builtin functions: `compiler/src/prog8/compiler/BuiltinFunctions.kt`;
   - library: `compiler/res/prog8lib/**`, and routines the code generators call by name;
   - IR: `intermediate/` (`IRInstructions.kt`, `IRFileReader.kt`, `IRFileWriter.kt`);
   - errors and warnings: `AstChecker.kt` and the other checkers;
   - command line: `compiler/src/prog8/CompilerMain.kt`;
   - 6502 conventions: `codeGenCpu6502`, zeropage and variable layout.

3. **Merge without committing:** `git merge --no-ff --no-commit origin/master`, so that the conflict resolution,
   the follow-up changes and the log entry go in the merge commit together.

4. **Resolve conflicts.** The aim is everything from both sides:
   - keep upstream's change, and keep the branch's feature next to it; neither side wins by default;
   - when upstream fixed a bug that the branch had already fixed (a U-item), take upstream's fix unless it fails a
     branch test, and say which was kept;
   - lists that both sides extend (`-target` values, module lists, docs tables) get the entries of both;
   - generated files (the symbol dumps in `docs/source/_static/symboldumps/`) are regenerated, not merged by hand;
   - files that upstream renamed or deleted: check that nothing in the branch still refers to them.

5. **Keep the branch's features working.** Changes upstream that compile but don't reach the llvm targets:
   - a new or changed IR instruction needs its lowering in `codeGenLlvm`;
   - a new builtin function needs its llvm lowering, and a new VM syscall the matching one in
     `codeGenLlvm/.../InstrSyscall.kt` (llvm-only syscalls start at 200);
   - a new or changed library routine needs the same in `compiler/res/prog8lib/llvm16`, `llvm32` and `llvm64`
     where those targets have their own module, and in the llvm16 textio variants;
   - quads: new code for long arithmetic or types may need the quad case too.

6. **Check the upstream issues.** Build `origin/master` in a detached worktree
   (`git worktree add --detach <dir> origin/master`) and run the repro of every item in
   `docs/UPSTREAM-ISSUES.md` with that compiler. An item whose repro passes is fixed upstream: remove it there,
   decide between upstream's fix and the branch's (step 4), and list it under "Bugs fixed". Update the commit that
   the header of `UPSTREAM-ISSUES.md` names as checked. Remove the worktree afterwards. When no incoming commit
   changes code that an item names (only docs, examples, or libraries and targets no item is about), say so in the
   entry instead of running the repros.

7. **Test.** The full `gradle build` (not `-Plight`), then `scripts/llvm-opt-check.py`; the Windows suite and
   `scripts/llvm-remote-check.py` before the push when the merge touched code generation.

8. **Write the log entry**, at the top of the entries below, and commit the merge:
   `Merge origin/master into llvm`, with a short list of the conflicts and how each was resolved.

9. **After the commit**, rebuild and install so that `prog8c -version` names the merge commit; push when asked.

## The format of an entry

One entry for each merge, newest first. The headings are fixed and all of them are there every time: "nothing"
under a heading says that it was looked at. One line for each change, with the commit.

```
## master <from commit> .. <to commit>, merged <date>

### Language and grammar
(new syntax, changed meaning of existing syntax, new directives or tags)

### Builtin functions
(added, removed, changed arguments or result types)

### Library
(new modules, changed signatures, routines the code generator calls by name)

### IR
(new or changed instructions, the file format)

### Errors and warnings
(new checks, checks that went, changed wording)

### Command line
(new options, changed defaults)

### Conventions of 6502 programs
(calling conventions, symbol prefixes, how variables are laid out, zero page)

### Bugs fixed
(especially ones that p8p8 reported or works around)

### LLVM branch only
(what the merge changed in the branch's own code, and conflicts worth knowing)

### Nothing for p8p8
(one line: what the rest of the commits were about)
```

## Entries

## master de17e7953 .. 8f50c2ed1, merged 2026-11-16

### Language and grammar
nothing

### Builtin functions
nothing

### Library
- `strings`, edge cases (b9fae1820): `split` stops at trailing whitespace instead of looping forever; `rstrip` and
  `rtrim` of a string of only whitespace give an empty string; `next_token` skips leading and repeated delimiters,
  so it no longer returns empty tokens; `findstr` with an empty needle gives 0, found; on the VM `left`, `right`
  and `slice` with length 0, and `find`, `find_eol` and `rfind` of an empty string, no longer run a loop of 256;
  6502 `nappend` with a maximum below the target's length returns that length, and `ncopy` with a maximum of 0
  gives an empty string. `libraries.rst` now documents `find_eol` and `findstr`
- Amiga `custom`: CIA timer and time-of-day routines `timer_start`, `timer_stop`, `timer_value`, `timer_flag`,
  `tod_read`, `tod_write`, `tod_alarm_set`, `tod_alarm_enable`, with the constants `CIA::A`/`B`, `TIMER::A`/`B` and
  `CIAICRF_ALRM` (93bab5be7)
- Amiga: new module `mobs` with the structs `Mob`, `Bob`, `Sprite` and position, distance and collision helpers
  (7f22c9167, 1c4238d18, 973e18010)
- Amiga `blitter`: new `draw_bob(^^mobs.Bob bob @A0, ^^pointer planes @A1, uword row_bytes @D0)`, and the last
  parameter of `copy_rect` is a `bool descending` instead of a `ubyte` (d9fe4718d); `playfield.flip_copper`
  (d9fe4718d); `waitvsync` fixed (8d89a41d6)

### IR
nothing

### Errors and warnings
- new 6502 warning "this store may rebuild a scratch pointer (many cpu cycles); consider a zero-page uword base
  with a byte index, or a fixed-address page array" for a memory write `@(base + offset)` that isn't one of the
  cheap forms, outside library modules; `-ignorefootguns` turns it off (5ae359154, b75793215)
- a struct field that is itself a struct instance gives "no support for nested structs at 'name'" instead of
  "invalid struct field type" (d9fe4718d)

### Command line
nothing

### Conventions of 6502 programs
- nothing new; 6502 code generation reuses a pointer over adjacent memory stores, keeps loads over indexed stores,
  and needs no stack saves for stores to a constant base plus a byte index (7f743cba1, 5feeadf4c, 80a055508,
  52bde28ed, with a 65C02 fix in b75793215)

### Bugs fixed
- inlining a subroutine that dereferences a pointer parameter (`p.field`) kept the parameter's name in the inlined
  expression, so two calls with different arguments were taken as the same value (656899ab6)
- m68k: the square of a long used `mulu.l`, which the 68000 doesn't have (7f22c9167)
- the `strings` edge cases above
- checked on `master` at 8f50c2ed1: all repros of `bugs/check.py`, and U66, U71, U74 and U76 in x16emu, whose code
  this merge changed; none passes

### LLVM branch only
- `next_token` of the llvm16, llvm32 and llvm64 `strings` modules (which don't use the shared one) gets upstream's
  fix; the other `strings` edge cases already worked there. A program with all of them prints the same on
  virtual, llvm64, llvm32 and llvm16 (mos-sim)
- `math.ctzl`, `reversel` and `popcount` of a long, which the reworded long docs point to, exist on virtual and
  m68k only; the llvm targets lack them, like the 6502 targets
- `variables.rst`, upstream's new note on `pointer` and `long`: llvm32 added to the 32-bit targets, and llvm64's
  8-byte pointer
- conflict in `AstChecker.kt`, struct fields: upstream's nested-struct message, with the branch's
  `checkQuadSupported`
- conflict in the `prog8-coder` skill: the branch's quad types and llvm pointer sizes, with upstream's text on
  long
- conflict in `TestExecution6502.kt` (an import) and in the symbol dumps (version header): dumps regenerated
- conflict in `TestCompilerOnExamples.kt`: the sizes the merged compiler builds; each differs from upstream's by
  the branch's own code, as the asm shows (U32, U76, the VM `txt.eof`, the branch's `memcmp`)

### Nothing for p8p8
- m68k struct sizes and offsets are `rs` blocks in the .asm instead of computed `equ` values (973e18010)
- the long docs and skills, `ideas/` notes, amiga examples (`beachballs`, `hwtimers`, `penrose-triangle-os`
  renamed to `penrose-triangle`), the vasm/vlink version note in `compiling.rst`, a stray binary `amiga2/3d`, and
  expected sizes (28a708996, 93bab5be7, 7f22c9167, d9fe4718d, b75793215, 8f50c2ed1)

## master e13ac65c7 .. de17e7953, merged 2026-11-14

Merge commit `f054e064e`.

### Language and grammar
nothing

### Builtin functions
nothing

### Library
- `lineclip`: the 6502 and generic `frac` (used by `lineclip.clip`) does its 32/16 division with one quotient
  register instead of two; the results are the same (0d0b565d2)
- m68k `lineclip`: `frac` is an inline asmsub with `muls.w`/`divs.w` (0d0b565d2)

### IR
- nothing in the file format; there is a new allocation hint `MUST_SPILL` for the m68k register allocator, which
  isn't written to `.p8ir` files (0d0b565d2)

### Errors and warnings
- the info "a large number of return values incurs a substantial value copying overhead" is no longer given for
  subroutines in library modules (70c4921a3)
- the m68k register allocation statistics and the "(N instructions in M chunks, R registers)" line of the IR writer
  are not printed when 64tass/vasm output is quiet (`asmQuiet`) (70c4921a3)

### Command line
nothing

### Conventions of 6502 programs
nothing

### Bugs fixed
- 6502: an if expression assigned to a byte or bool target whose type differs from the expression's, both of
  them byte or bool types, crashed the code generator (`Failed requirement` in `IfExpressionAsmGen`) (77ad14c13)
- m68k: multi-value returns were corrupted by the caller's moves and the callee's epilogue, and a multi-value
  return with floats crashed the compiler or clobbered fp0; int and float vregs could share a frame slot
  (0d0b565d2, fec84d50b)
- U57 (a subroutine that returns a float with other values crashes the IR code generator) passes on `master` now,
  fixed by fec84d50b; removed from `docs/UPSTREAM-ISSUES.md`
- U17 (the VM crashes at the end of the input) passes on `master` too, fixed by 7a23ccb6d, which an earlier merge
  had already brought in; removed from `docs/UPSTREAM-ISSUES.md`. The branch keeps its own version of that code,
  which ends the program with exit status 1
- checked on `master` at de17e7953: U1-U49 with their repros, and U58, U69 and U73, whose code this merge
  changed; all of them except U17 and U57 still fail. No incoming commit changes code the other items are about

### LLVM branch only
- conflict in `IRCodeGen.kt`, the translation of a multi-value `return`: the branch's fixes of U58 (all values
  evaluated before any is stored, the first stored last) and U69 (no moves when the called routine returns in the
  same registers, otherwise all loaded before any store) are kept, with upstream's float path through
  `loadFromCpuRegister`/`setCpuRegister` and its rule that a float store into fp0 comes after all others; the
  m68k hardware store of `setCpuRegister` is upstream's
- conflict in `IfExpressionAsmGen.kt`: the requirement allows both upstream's byte/bool case and the branch's
  arrays (U9)
- conflict in `examples/textelite.p8`: upstream's `pointer` types in `soup()` taken; it builds for virtual,
  llvm32, llvm64, c64 and cx16
- conflict in `docs/source/todo.rst`: upstream moved "Won't do's or deferred" to the end; the branch's two items
  there (the optimizer on llvm, the static locals of llvm subroutines) moved with it
- conflict in `TestCompilerOnExamples.kt`: the sizes the merged compiler builds (bsieve and the cx16 pcmaudio
  examples keep the branch's, `pointers/sorting` and `sortedlist` changed upstream), plus the branch's virtual
  `textelite` entry

### Nothing for p8p8
- cleanups of the amiga, c64, cx16, pointers and other examples, `libraries.rst` saying syslib is always
  imported, a todo item about faster small divisions, and the kotest and mordant updates (fd1d4378b, 1d746f2ab,
  de17e7953, 72b432040)

## master 31bba5147 .. e13ac65c7, merged 2026-11-13

Merge commit `7325812c1`.

### Language and grammar
nothing

### Builtin functions
nothing

### Library
- cx16: the deprecated `psg` module is gone, and `psg2` is renamed to `psg`; `import psg2` no longer resolves, and
  code that used the old `psg` API has to move to the new one (f82c8f87a)
- c64 `vera_psg` keeps the API of the new `psg` (only its comments changed) (f82c8f87a)
- Amiga `blitter`: `masked_blit` takes a `ubyte shift @D5` as its last argument, and there is a new
  `erase(long mask @A0, long dst @A1, uword width_words @D0, uword height @D1, uword mask_mod @D2,
  uword dst_mod @D3, ubyte shift @D4)` (e13ac65c7)
- m68k `floats.EPSILON` is 1.401298464e-45, the smallest single-precision value (5bfeb173d)
- comments only: c64 and cx16 `cbm.STROUT`, `cbm.RDTIM`, `cx16.mouse_get` and some cx16 `floats` ROM routines
  (1e7d3b26f)

### IR
nothing

### Errors and warnings
- a program that uses floats gets the warning that it needs a 68020 and an FPU on amiga1200 too, not only on
  amiga500 (5bfeb173d)
- the Amiga targets name Copperline instead of FS-UAE as the `-emu` emulator in their message (5bfeb173d)

### Command line
nothing

### Conventions of 6502 programs
nothing

### Bugs fixed
- nothing in `docs/UPSTREAM-ISSUES.md`: no incoming commit changes code that an item is about

### LLVM branch only
- conflicts in the syntax files: the branch's keyword lists (with the quad builtins, and without the module names
  `psg`/`psg2` that it had already taken out of the builtin lists) are kept; the Notepad++ module lists get
  upstream's `psg2` to `psg`
- conflicts in the symbol dumps (the version header only): every dump regenerated with `make symboldumps`; the Amiga
  dumps now show the branch's quad signatures of the builtin functions, where they were upstream's text before

### Nothing for p8p8
- m68k documentation, the `/review-p8` agent command, and the cx16 examples moved to the new `psg` (dd2d1eed5,
  1e7d3b26f, f82c8f87a)

## master d0391a3db .. 31bba5147, merged 2026-11-12

Merge commit `367e051a3`.

### Language and grammar
- nothing new; `variables.rst` now documents that a `str` value can be assigned to a `long` variable without a
  cast, on every target (8562414a4)

### Builtin functions
nothing

### Library
nothing

### IR
nothing

### Errors and warnings
nothing

### Command line
- nothing new; the m68k targets are no longer called experimental in the `-target` help (8562414a4)

### Conventions of 6502 programs
nothing

### Bugs fixed
- m68k: a call with arguments in hardware registers could overwrite an argument before it was moved; the moves
  are now scheduled (37546f8e7)

### LLVM branch only
- conflict in the `-target` list of `compiling.rst`: both sides' entries kept

### Nothing for p8p8
- m68k status docs, the `/review-p8` agent command and skill updates (8562414a4, 31bba5147)
