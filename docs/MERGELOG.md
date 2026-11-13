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
   the header of `UPSTREAM-ISSUES.md` names as checked. Remove the worktree afterwards.

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
