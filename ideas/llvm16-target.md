# llvm16 target plan

## Decisions

Made by the user:
- Milestone 1 is mos only.
- Tool paths: a profile setting plus a `-clang` option.
- Big-endian: designed for now, built later.
- Floats on llvm16 are f32.

- Floats are not part of milestone 1 (see "Floats").

## Facts

- **Toolchains:** all three are 16-bit pointer, 16-bit int, 32-bit long targets.
  - llvm-mos: little-endian. It has SDK platforms, and `mos-sim` exits with the program's status.
  - llvm-z80: little-endian. No libc and no emulator here.
  - llvm-mc6809: BIG-endian. It has a libc, and its USim simulator isn't installed. The benchmark IR collapses under optimization with either byte order; this isn't investigated yet.
- **llvm-mos libc:**
  - It has printf/snprintf without float formatting: `%g` prints literally, and the varargs after it come out wrong.
  - There is no libm (sinf, sqrtf and the rest), no strtod/strtof, no usleep, and no opendir/readdir/mkdir/chdir/getcwd/strdup.
  - It has fopen/fread/fwrite/fseek/ftell/remove/rename, malloc/realloc/free and qsort/strcmp.
  - The soft-float arithmetic (`__addsf3` etc.) is in the compiler runtime.
- **What the hand-made experiment showed:** IR from llvm32, with a 16-bit pointer layout swapped in, builds and runs on mos-sim and on the C64.
  - It worked partly by luck: i16 and i32 arguments share the low registers on mos.
  - The `@(const)` loads and stores had to be made volatile by hand.

## Milestone 1: llvm16 on llvm-mos, in buildable steps

Status: done (steps 1-6).
- Also fixed along the way: on targets with 2-byte pointers the IR used byte-sized array indexes, so arrays over 256 bytes wrapped on llvm16. The fix is `IMemSizer.wordArrayIndexes`, which is true on the llvm targets.
- `%import floats` gives the float error at the import itself, before undefined-symbol errors.
- On mos-sim, 36 of the llvm test programs run; the 35 that don't are listed with their reasons in `notRunOnMos` in TestLlvmCompiler.
- The later milestones are in `docs/source/todo.rst`.

Each step keeps llvm32 and llvm64 unchanged. Check that with llvm-opt-check (0 differences) and byte-identical `.ll` output for the examples, then a full build.

### Step 1: backend refactor, no behaviour change
- `LlvmTypeMapper`:
  - allow `pointerBytes` 2 (`intPtr` and `sizeT` are I16)
  - add `wideInt = max(intPtr, I32)` for the LONG register slots (LlvmFunctionBuilder.kt:651, readWide/write :361-373, POP InstrMemory.kt:78), so a long stays 32 bits with 16-bit pointers
  - add `cInt`: I32 for now, used at every C int site (InstrSyscall.kt:668, exit :215, putchar :234, getchar and smax :239, usleep :703, the snprintf/printf returns :739/:1098, `main(argc)` LlvmFunctionBuilder.kt:101-110)
  - add `floatType`: Double for now
- Add `LlvmType.Float`, and make the model, printer and lowering float-type generic:
  - `FloatConstant`, fneg/fcmp/casts (LlvmAst.kt:77-80, 167-187, 355)
  - hex float printing (LlvmPrinter.kt:43, 61, 160)
  - the FAC/jumpi slots (InstrMemory.kt:62-72, InstrControl.kt:83-87)
  - a suffix-aware intrinsic helper for `llvm.*.f32|f64` and the libm names
  - divide by zero gives the type's own maximum (InstrArithmetic.kt:240)
- Address registers may be WORD: the `w` case in BuiltinFuncGen.kt:329, and tests at TestLlvmInstructionLowering.kt:281/457.

### Step 2: profile keys and tool resolution
- New `LlvmTripleProfile` keys, with parse tests:
  - `c_int_size`: 2 or 4. Without it, `queryClang` reports `sizeof(int)` as it does for long.
  - `clang`: env-expanded, e.g. `clang = mos-sim-clang` searched on the PATH, or `${LLVM_MOS}/bin/mos-sim-clang`.
  - `libc_lacks`: C symbol names matching the `Libc` enum and the `external()` names.
  - `io_ranges`: address ranges, `*` for all constant addresses.
  - `program_args`: true or false, replacing the guess from `{args}`.
- `run_command` gets the same `${VAR}` expansion.
- One tool resolver: `-clang`, then the profile's `clang`, then today's `LlvmToolLocator.clang()`.
  - `llvm-as` is looked for next to that clang.
  - It is used by the build and link (LlvmToolchain), `askClang`, IR validation (AssemblyProgramLlvm) and the TestLlvmCompiler helpers.
  - The build message prints the clang that was used.
- The `-clang <path>` option is only valid with llvm targets, like `-triple`. It goes through:
  - CompilerMain
  - CompilerArguments
  - DaemonRequest (encode, decode and a roundtrip test)
  - CompilationOptions
  - the `.p8ir` file (IRFileWriter.kt:296-299, IRFileReader.kt:143-193, like `llvmCompileArg`)

### Step 3: the llvm16 target
- `CpuType.LLVM16` (in `isLlvm`, and the AstStatements.kt:120 `when`) and `LlvmTarget.NAME16`.
  - Name lookup and CLI in C64Target.kt:105-138.
  - Fix the "llvm32 and llvm64" messages: C64Target.kt:116-120, CompilerMain.kt:108/195, AstChecker.kt:1706, LlvmCodeGenerator.kt:29.
- `LlvmTarget(2, profile)`:
  - floats are 4 bytes everywhere: the `NormalMemSizer` delegate, the `FLOAT_MEM_SIZE` override, the f32 `FLOAT_MAX_*`, and `convertFloatToBytes`
  - the default profile is `mos-sim`
- `hasFixedMemoryAddresses` stays false; that keeps the C-routine `extsub` and rejects `%address`/romable etc.
  - A new `realAddresses` property is read only by the llvm backend.
  - `isIOAddress` is true inside the profile's `io_ranges`. This keeps the front end and the IR optimizer from merging or removing those accesses (UnusedCodeRemover.kt:360, StatementReorderer.kt:282, IRPeepholeOptimizer.kt:978/1346, AstExpressions.kt:131).
- Profiles in `codeCore/res/prog8lib/llvm-triples`:
  - `mos-sim`:
    - triple `mos` and the mos data layout
    - `c_int_size = 2`, `c_long_size = 4`
    - `clang = mos-sim-clang`
    - `run_command = mos-sim {file}`, `program_args = false`
    - `io_ranges = $fff0-$ffff`
    - `libc_lacks` listing the missing functions
  - `mos-c64`: the same but with `clang = mos-c64-clang`, `.prg` output, `io_ranges = 0-1,$d000-$dfff`, and `run_command = x64sc -autostart {file}` (not run by the tests).
- Library `compiler/res/prog8lib/llvm16/`, a copy of llvm32 with changes:
  - `sys.target`, plus a new id
  - floats.p8: the f32 `MAX_FLOAT` and `EPSILON`
  - file and dir routines behave as failing where `libc_lacks` says so
  - diskio's `.l` seek offsets
  - the cx16 virtual registers below
- RAM cost: the 1024-byte `.stack` (InstrMemory.kt:27) and the 4096-byte curdir buffer (InstrSyscall.kt:630) become per-target sizes, or a profile key, on llvm16.

### Step 4: real-address lowering
- With `realAddresses`, `integerConstant` of a Ptr is `inttoptr (iN addr)`. Address 0 isn't turned into `NullPtr` there; for example `@(0)`/`@(1)` is the 6510 port (LlvmFunctionBuilder.kt:421).
- Loads and stores are volatile when `isIOAddress(addr)` is true, or when the access goes through a memory-mapped variable inside `io_ranges`.
- Keep `MemoryMappedCluster` storage for memory-mapped variables outside the 16-bit space or outside `io_ranges`. The cx16 registers at `$ff0000` (syslib.p8:173-260) stay emulated.
- Unit tests for all of these.

### Step 5: libc_lacks lowering
- Syscalls that need a listed function lower to their failure result, as LIST_DIRECTORY does without `dirent_name_offset` (InstrSyscall.kt:456).
- `usleep`: a busy loop or nothing on mos-sim.
- `sys.argc()`/`sys.argv()` with `program_args = false`: argc is 0 and argv empty. The llvm-mos startup calls `main` without arguments, so mos-sim gives garbage (255).
- Integer `sqrt` on llvm16: a pure integer routine. Today it goes through a double `llvm.sqrt.f64` and `fptosi.sat`, which llvm-mos can't lower (textelite).
- RAM: a smaller `.stack` on llvm16 (InstrMemory.kt:27), and no 4096-byte curdir buffer when `getcwd` is in `libc_lacks` (InstrSyscall.kt:630).

### Step 6: tests and docs
- TestLlvmCompiler:
  - a `requiresMosSim` guard
  - `programs` and `outputPrograms` run under mos-sim, with a `notRunOnMos` exclusion set
  - a mos-c64 `.prg` link test
  - the report says how many tests were skipped (CI, the remote hosts and Windows have no llvm-mos)
- Docs:
  - targetsystem.rst: llvm16, pointer sizes, floats, the new profile keys, `io_ranges`, `LLVM_MOS`
  - compiling.rst: `-clang`
  - todo.rst
  - the AGENTS.md llvm section
  - CODEBASE-KNOWLEDGE-GRAPH.md 4.10b
- Validation:
  - Mac full build and llvm-opt-check
  - remote check and Windows suite against the baseline (3286/0/7 Mac, 3286/0/12 Windows, 10/10 remote)
  - bench.p8 through `-target llvm16 -triple mos-c64` in VICE, which checks the volatile I/O path

## Floats
The mos libc can't format, parse or compute floats beyond basic arithmetic. Prog8's float output on llvm uses snprintf/strtod (InstrSyscall.kt:727-767), and the math uses libm.
- Milestone 1 has no floats on llvm16. `%import floats` or a float variable gives a clear "not supported yet on llvm16" error, and llvm16 programs that use floats join the `notRunOnMos` set.
- The f32 plumbing from step 1 and step 3 is still done in milestone 1.
- Float support is the next milestone. It adds a small runtime: formatting, parsing, and sinf/cosf/atan2f/sqrtf/powf/logf/expf/floorf and so on. The runtime is either a C startup source compiled by the profile's clang, or Prog8 code.

## Milestone 2: z80 through llvm-z80 and z88dk

### Decisions
Made by the user, 2026-11-04:
- Use the llvm-z80 clang with `--target=z80-unknown-none-z88dk`.
- Only one profile, for z88dk's `+test` platform, which runs in `z88dk-ticks`. Real machines come later.
- A profile gives its tools an environment through `tool_path` and `tool_env` keys.
- When `qsort` is lacking, the backend uses its own sort.

### Facts (checked by hand)
- **The data layout:** `e-m:o-p:16:8-i16:8-i32:8-i64:8-i128:8-f16:8-f32:8-f64:8-f128:8-ve-a:8-n8:16`. The C int is 2 bytes, long 4, and the C `double` is 4 bytes, the same as `float`.
- **The pipeline:**
  - clang writes z88dk assembly, and `clang -c` assembles it with `z88dk-z80asm`, found only through the PATH.
  - `zcc +test -o out.bin obj.o` links it with z88dk's crt0 and C library. zcc needs `ZCCCFG=<z88dk>/lib/config`.
  - `z88dk-ticks out.bin` runs it and passes the exit status on. Its default limit is 100M cycles; `-counter 0` means 10 cycles, not unlimited, so the profile sets a large `-counter`.
- **What already works:** with only a profile file (`-target llvm16 -triple <file>`, the environment exported by hand), hello, crc, list and a small fib give the same results as llvm64. The full fib and strs benchmarks need more than 4G cycles.
- **What the `+test` C library lacks:** getcwd, mkdir, chdir, rmdir, opendir, readdir, closedir, strdup, realloc, free, getchar, usleep, strtod, and every libm name.
  - `-lm` would bring math later.
  - It has fopen and the other f* functions, strcmp, qsort, printf, snprintf, putchar and exit. z88dk names its standard streams differently, but the backend only prints through putchar.
- **qsort:** z88dk calls the comparison callback with its arguments swapped, so it sorts backwards. Calls from our code into the library are fine (checked with strcmp and snprintf).
  - The backend only uses qsort to sort directory listings, which also need opendir. So on z80 it is enough to list qsort as lacking.
- **Alignment:** the z88dk assembly format ignores it (warning: "alignment of 'prog8_slabs.memory_pool' is ignored"). A `memory(name, size, align)` slab is not aligned on z80.

### Steps
1. **Profile keys `tool_path` and `tool_env`:**
   - `tool_path`: directories, env-expanded, put in front of the PATH with the OS's separator.
   - `tool_env`: `NAME=value` pairs, env-expanded.
   - Both apply to every process the llvm path starts for that profile: clang (build, `askClang`, IR validation), llvm-as, the link command and the run command.
   - Parse tests, plus a test that the environment reaches a started process.
   - llvm32, llvm64 and mos are unchanged.
2. **The `z80-test` profile:**
   - triple, data layout, `c_int_size 2`, `c_long_size 4`
   - `clang = ${LLVM_Z80}/bin/clang`, `clang_args = --target=z80-unknown-none-z88dk`
   - `tool_path = ${Z88DK}/bin`, `tool_env = ZCCCFG=${Z88DK}/lib/config`
   - `link_command = zcc +test -o {file} {object}`
   - `output_extension = .bin`, `run_command = z88dk-ticks -counter <large> {file}`
   - `program_args = false`
   - `libc_lacks` as above plus qsort; no `io_ranges` (the z80 has its own I/O ports)
   - Check that the alignment warning doesn't fail a build, and document that slabs aren't aligned on this profile. Maybe the backend should warn when a slab asks for alignment on such a profile.
3. **Console input:** getchar is lacking, so `txt.input_chars` and the other input routines get the libc_lacks failure result (check what that is today, and that it's sensible). Find out whether z88dk +test has a usable console read (e.g. `fgetc_cons`); if it does, use it later through a profile key rather than a z80 special case.
4. **Tests:**
   - Generalize the mos-sim runner in TestLlvmCompiler into one runner for llvm16 profiles that run programs.
   - A `requiresZ80` guard: LLVM_Z80 and Z88DK are set, and clang, zcc and z88dk-ticks are found.
   - The `programs` and `outputPrograms` maps run on z80, with a `notRunOnZ80` map. Every entry needs a reason that was observed, and unexplained failures are investigated as possible bugs.
   Status: done. 25 programs run on z80 (optimized and -noopt), in about 7 s.
   - Most exclusions come from llvm-z80's runtime helpers, which zcc can't link: `__z80_memset_builtin`, `__z80_memmove_builtin`, `__muldi3`, `__divdi3`, `__udivdi3`, `__moddi3`, `__umoddi3`, `__umulhisi3`. llvm-z80 ships them only as ELF (`z80_rt.a`) and SDCC (`z80_rt.lib`) libraries.
   - The user will provide a z88dk-linkable build; the profile then links it, and those exclusions go.
     - The sources are in llvm-z80's `compiler-rt/lib/builtins/z80/*.asm`, in SDCC syntax. `zcc +test -c` assembles a `.s` copy of each into a z88dk object, and `z88dk-z80asm -x` packs the objects into a library.
     - Leave out the crt0 files and the C library functions z88dk already has (memset, strcmp, ...).
     - Checked on 2026-11-05: the result links with `-l<lib>` and gives the host's results.
     - Decision (user): llvm-z80's build installs it as `lib/z80/z80_rt_z88dk.lib`, and the profile adds `-l${LLVM_Z80}/lib/z80/z80_rt_z88dk` to its link command.
     - zcc fails when an `-l` library is missing, so make that profile change only once the library is installed.
     - Done 2026-11-05: the user installed it, and the profile links it; the z80 test guard requires it.
   - What still doesn't run on z80, apart from the mos-sim reasons:
     - z88dk-ticks' ftell always gives -1 (`ftell` is in libc_lacks), and it can't delete files.
     - z88dk's fread doesn't set the end-of-file flag.
     - The strings library test hits an llvm-z80 bug: LLVM assumes 16-byte alignment for globals over 128 bits (DataLayout::getPreferredAlign), which the z88dk format can't give, and an add becomes an or. Explicit `align 1` on the globals avoids it.
     - Decision (user): fix it in llvm-z80. The repro is strlib.ll / strlib-align1.ll.
     - Fixed in llvm-z80 2026-11-05 (globals without an explicit alignment get align 1 in z88dk mode); the strings test runs on z80 again.
   - Also fixed: a pointer at an odd struct offset no longer needs a global constructor (`.init_array`, which the z88dk format can't hold). Pointers are only deferred when the data layout's pointer alignment (`LlvmTripleProfile.pointerAlignment`) requires it.
   - z88dk-ticks can't remove or rename files, so `remove` and `rename` are in the profile's `libc_lacks`.
   - z88dk-ticks prints a "Ticks: N" line when a program exits, and its `-no-cycles` option doesn't suppress it. The test runner strips that line.
5. **Docs:** a z80 profile section in targetsystem.rst (`LLVM_Z80`, `Z88DK`, what's lacking, the alignment note), the new keys, todo.rst (real z80 machines, -lm for floats), and the AGENTS.md optional tools.
6. **Optional:** an own sort in the backend when qsort is lacking, for directory listings. It only matters for a future platform that has opendir but no usable qsort.

Each step keeps llvm32/llvm64 `.ll` byte-identical and mos-sim results unchanged. At the end: a full build with LLVM_MOS, LLVM_Z80 and Z88DK set, the remote check and the Windows suite.

## Milestone 3: z88dk platforms

### Decisions
Made by the user, 2026-11-05:
- A `base = <profile>` key: a profile inherits every key of its base and overrides some.
- A generator script writes a profile for any z88dk platform and subtype, into `~/.prog8/llvm/`.
- Built-in profiles: TRS-80 (`default` and `cmd` subtypes), CP/M, ZX Spectrum, C128 (the Z80 side).
- The Agon and other eZ80 work are deferred (user, 2026-11-05): the eZ80 may bring other complications. The Agon facts below are kept for then.
- Emulators available: `trs80gp` (Model I/III/4), `tnylpo` and `RunCPM` (CP/M), `agon-cli-emulator` (fab-agon-emulator). The user has C128 and Agon hardware; a friend has a TRS-80 Model 4D.

### Facts (checked by hand, 2026-11-05)
- **z88dk's config:**
  - Platforms: `lib/config/<platform>.cfg`, 115 of them.
  - Variants: `SUBTYPE` lines, 493 in all. A subtype fixes the libraries and the output form.
  - `CLIB` lines select the C library; `default`/`classic` is the one llvm-z80's z88dk mode is known to work with.
  - Not a Z80, so out of scope: 8080/8085, Rabbit (`rcmx000`) and the Game Boy (`gb`).
- **TRS-80 (`+trs80`):**
  - z88dk's crt0 targets the Model I/III memory map: video `$3C00`, keyboard `$3800`, programs at `$5200`, himem read from the Model I or III ROM.
  - Subtypes:
    - `default` (`-lndos`, file calls are stubs)
    - `disk` (`.cmd`, no file library at all)
    - `cmd` (`.cmd`, `-ltrsdos`, Model I/III TRSDOS through `*DO`)
    - `m2` (Model II)
    - `wav`, and two EG2000 subtypes
  - Nothing targets Model 4 native mode (80 columns, video `$F800`, keyboard `$F400`, LS-DOS 6 calls).
- **trs80gp headless runs:**
  - `trs80gp -m<1|3|4> [-ld] -ee -batch -turbo prog.cmd` boots a DOS and runs the `.cmd` from its prompt. A run takes about 2 s.
  - With `-ee`, a program exits the emulator with status BC by writing function 128 to port `$47` (`ld bc,status / ld a,128 / out ($47),a`).
  - With `-batch`, printer output goes to `trs80-printer.txt` in the current directory.
  - The printer is on port `$F8` on the Model III/4: wait while bit 7 of `in ($F8)` is set, or characters get lost. On the Model I it is memory-mapped at `$37E8`.
  - The `+trs80 -subtype=cmd` test program loaded and exited with its status under:
    - `-m1` with TRSDOS and with LDOS
    - `-m3 -ld`
    - `-m4` with TRSDOS 6 and with LDOS
  - Under `-m3` with TRSDOS 1.3 (the default) the `.cmd` never starts: z88dk's startup code overruns TRSDOS 1.3's small stack (see step 6).
  - `-m4` with TRSDOS 6 runs in Model 4 mode. Loading and exiting work; console output doesn't reach the screen (step 6).
  - Other useful options: `-id N` waits N frames, `-it` writes the text VRAM to `trs80-text-N.bin`, `-ix` exits once the command line input has been sent.
- **CP/M (`+cpm`, subtype `default`, `.com`):**
  - `tnylpo -b prog.com` runs it headless, with console output on stdout. File I/O works in the current directory; the files are lower case on the host.
  - tnylpo's exit status is always 0.
  - The other `+cpm` subtypes are disk images for about 150 specific machines; those are for the generator, not built in.
- **Agon (`+agon`, deferred):**
  - z88dk builds an eZ80 **Z80-mode** MOS program at address 0 of its segment, with a "MOS" header (ADL 0), linked with the classic C library from the ez80, z180 and z80 directories.
  - `agon-cli-emulator --sdcard <dir> -u -z` boots MOS 2.3.3 headless, and runs `<dir>/autoexec.txt`. A line naming a program in `<dir>/bin/` runs it.
  - Debug ports: writing `out (0),a` ends the emulator with status A; `out ($30),a` echoes a character to stdout. A run takes about 0.1 s.
  - Console output through MOS/VDP doesn't reach stdout: the fake VDP prints only its own messages.
- **ZX Spectrum (`+zx`):** subtype `default` gives a `.tap`, with `-lndos`. The screen is at `$4000`; the ULA is port `$FE`. No headless emulator.
- **C128 (`+c128`):**
  - The Z80 side, with an ANSI-terminal console and `-lndos`.
  - In Z80 mode the VIC, SID and CIAs are I/O ports, not memory: a Prog8 `@($d020)` writes RAM.
  - No headless emulator.
- **ticks can run eZ80 code:** `z88dk-ticks -mez80_z80` emulates an eZ80 in Z80 mode, so the `z80-test` programs can also run on the Agon's CPU.
- **Agon can't link clang's object:** zcc says "CPU incompatible: ... compiled for z80, incompatible with ez80_z80".
  - `-Wa,-mez80_z80` doesn't help. `-mcpu=ez80` is listed by `-mcpu=help`, but clang rejects it ("unsupported option"; `-target-cpu ez80`: "unknown target CPU").
  - What links: `clang -S` to assembly, which zcc then assembles itself.
- **Link pitfalls found by the review:**
  - `+trs80 -subtype=cmd` fails with "undefined symbol: _heap" (malloc-classic). Its `-DAMALLOC` only works for C that zcc compiles. `-pragma-define:CLIB_MALLOC_HEAP_SIZE=<n>` fixes it.
  - `+zx -create-app -o z.tap` fails ("Input and output file names must be different"); `-o z.bin` gives `z.tap`.
  - `+cpm -create-app -o prog.bin` gives `PROG.COM`, in upper case, which tnylpo rejects. Without `-create-app`, `-o prog.com` is already a valid COM file.
- **Hooking output and exit (checked on +test, +cpm, +agon):**
  - Defining `fputc_cons` in a support `.asm` gives "duplicate definition", because every crt0 defines it as an alias (z88dk `lib/crt/classic/crt_runtime_selection.inc`).
  - What works: `-pragma-redirect:fputc_cons=prog8_fputc`, with the support `.asm` defining `prog8_fputc`, which calls on to `fputc_cons_native`.
  - Code in `SECTION code_crt_exit` is spliced into crt0's exit path with HL holding the status. That covers `exit(n)` and a `return` from main; the TRS-80 crt0 doesn't save HL first.
  - Profile keys for the symbols would miss paths: a normal end returns from main without calling `exit`, and directory listings print with `printf`.
- **Profiles can build support code:** `startup_source` names files next to the profile (or embedded), which the link command gets as `{startup}`. zcc assembles an `.asm` passed this way next to clang's object (also with the dotted names `writeStartupSources` gives them).

### Design
- **`base` key:**
  - `base = <name|file>` is resolved like `-triple`: a file, then `~/.prog8/llvm/`, then embedded. A relative path is relative to the directory of the profile that names it, not the working directory.
  - The `Properties` are merged before they are interpreted: the base's keys first, then the profile's own replace them. That keeps the clang query for a missing `datalayout` working.
  - Chains are allowed; a cycle is an error.
  - A `startup_source` file is relative to the layer that supplied the key, which may be embedded under a file profile. So the profile keeps the source directory per layer, not one `directory`.
  - The profile keeps its own name: the `.p8ir` file records `llvmTriple=<name>`, which must resolve again.
  - Every key is replaced whole: no merging of lists such as `libc_lacks`.
  - `abstract = true` marks a profile that is only a base. Using one directly is an error, since without a `link_command` the backend would quietly link with plain clang.
- **New placeholders:**
  - `{stem}` in `link_command`: the output path without its extension. It handles zcc's own naming: `+zx` writes `<stem>.tap` from `-o <stem>.bin`.
  - `{startup}` may be used without a `startup_source` and then expands to nothing. That way a base link command already has the slot, and a test profile only adds `startup_source`.
  - A placeholder for the directory of the profile, so a `run_command` can name a runner script next to it.
- **Embedded profiles:**
  - `z80-z88dk`: everything the z88dk profiles share (clang, clang_args, data layout, sizes, `tool_path`, `tool_env`, `getchar_symbol`, `program_args`). It has no link command, so it can't be used alone.
  - `z80-test`: rebuilt on it, with no change in what it does.
  - `z80-trs80`, `z80-trs80-cmd`, `z80-cpm`, `z80-zx`, `z80-c128`, each with:
    - a link command: `zcc +<platform> [-subtype=..] [-create-app] -o {file}|{stem}.. {startup} {object} -l..z80_rt_z88dk`, with the per-platform fixes from the facts (the heap size pragma for TRS-80 `cmd`, no `-create-app` for CP/M, `{stem}.bin` for the ZX)
    - its own `output_extension`, `libc_lacks` and `io_ranges` (TRS-80 Model I/III: keyboard `$3800-$3BFF`, video `$3C00-$3FFF`, printer `$37E8`; none for CP/M and C128; ZX: none, since its screen is plain RAM)
    - no `run_command`: running needs a machine or an emulator set up by the user, and the docs say how.
- **Test-only profiles** live with the tests (`compiler/test/res/llvm-triples/` or similar), not in the compiler. Each is a few lines on top of a real one (`base`, `startup_source`, `run_command`, and the redirect pragma, which means repeating the link command):
  - `trs80gp-m1`, `trs80gp-m3` (LDOS) and `trs80gp-m4` on `z80-trs80-cmd`
  - `tnylpo-cpm` on `z80-cpm`
  
  They add a support source and a `run_command` that calls a runner script.
- **Support sources for test runs** (z80 asm, assembled by zcc):
  - Mirror console output to where the emulator can capture it: the TRS-80 printer (port or memory-mapped, by model).
  - Turn the program's exit status into the emulator's exit (trs80gp's `$47` function 128).
  - How they hook in: output through `-pragma-redirect:_putchar=_prog8_putchar` in the link command, exit through code in `SECTION code_crt_exit` (see step 2). This needs no backend change, so the llvm32/llvm64 `.ll` output can't change.
  - On the TRS-80 the exit hook calls `$47` function 128 with BC set from HL.
- **Runner scripts** (`scripts/llvm16-run/*.py`): each turns its emulator's conventions into stdout plus an exit status, so the test runner stays generic.
  - trs80gp: a temporary directory, then the printer file printed to stdout, and trs80gp's status passed on.
  - tnylpo: since its status is always 0, the support source prints a final marker line with the status, which the script strips and turns into its exit status.
  - All of them need a timeout, since a hung program never exits the emulator.
  - They run with `python3`, or `python` on Windows. They are found through the profile-directory placeholder.
- **Generator, `scripts/z88dk-profile.py <platform> [subtype] [-l lib ...] [-o file]`:**
  - Reads the platform's `.cfg` and refuses non-Z80 CLIBs and platforms.
  - Writes a `base = z80-z88dk` profile:
    - `link_command`
    - `output_extension`: found by linking a probe and looking at the files it creates
    - `libc_lacks`: the names from `LlvmTripleProfile.C_FUNCTIONS` that are unresolved when a probe calling all of them is linked
  - Leaves `io_ranges` and `getchar_symbol` as commented placeholders for the user.
  - The built-in profiles are generated with it, then finished by hand, so it gets used and tested.

### Steps
1. **Profile mechanics:**
   - `base`, `abstract`, `{stem}`, an empty `{startup}`, and the profile-directory placeholder, with profile tests.
   - `z80-z88dk` plus `z80-test` on top of it; the z80 test results are unchanged.
   Status: done 2026-11-05.
   - An embedded profile's base can only be embedded.
   - A `base` name is looked up in `~/.prog8/llvm/` and embedded only when it is a plain name.
   - `program_args` stays in `z80-test`, so each platform profile sets it itself.
2. **The hook sources:** one `.asm` per emulator for output and exit, linked with `zcc +test` first and run in ticks (output mirrored, status kept). Also check what an exit from Prog8 does on each platform (back to DOS, MOS or BASIC).
   Status: done 2026-11-05. The sources are in `compiler/test/fixtures/llvm-triples/`. What was found:
   - **Output hook:**
     - Redirecting `fputc_cons` doesn't work for the TRS-80 `cmd` subtype. Its crt0 reopens stdout on TRSDOS's `*DO` device, so putchar never reaches fputc_cons.
     - Redirecting `_putchar` to a hook that calls the library's `___putchar` gives "duplicate definition".
     - What works: `-pragma-redirect:_putchar=_prog8_putchar`, with `trs80gp-output.c` (compiled by zcc) doing `fputc(c, stdout)` itself after mirroring the character to the printer. z88dk's putchar module is then never linked.
   - **trs80gp detection:**
     - Function 255 doesn't visibly set the carry flag.
     - `out ($47),255` followed by `in a,($47)` reads 1 when `-ee` is on, as trs80gp's BASIC example does.
     - Outside trs80gp (ticks, real hardware) the hooks do nothing.
   - **trs80gp results:**
     - With `trs80gp-output.c` and `trs80gp-exit.asm`, the printer file holds the program's output (LF line ends) and trs80gp exits with the status: 3 from `sys.exit(3)`, 0 from a normal return.
     - That works on `-m1` (TRSDOS and LDOS), `-m3 -ld`, and `-m4` (TRSDOS 6 and LDOS). `-m3` with TRSDOS 1.3 still doesn't start the program.
     - In ticks, output and status are unchanged.
   - **CP/M:**
     - The CP/M crt0 has its own `code_crt_exit` code (restoring the default drive through the BDOS), which runs first and changes HL.
     - `cpm-exit-marker.asm` therefore reads the status where crt0 saved it, `(__cpm_base_address+$80)`.
     - tnylpo prints the program's output, then `\nPROG8-EXIT=<hex>\n` (CR LF becomes LF). The runner removes exactly that.
   - **The TRS-80 exit hook relies on HL** still holding the status. The TRS-80 crt0 and libraries add no `code_crt_exit` code of their own; if a program with open files changes that, read the status elsewhere.
   - **Where an exit goes:**
     - TRS-80: back to the DOS prompt ("LDOS Ready").
     - CP/M: back to CP/M (tnylpo exits).
     - ZX and C128: not run, since there is no headless emulator.
   - **Building:** `prog8c -o <dir>/<name>` builds the executable for the llvm targets without running it; `-out` only writes the `.ll`.
3. **Generator script**, with a test that runs it for `+test`. The test compares with the link-only part of `z80-test`'s `libc_lacks`, since `remove`, `rename`, `ftell` and `qsort` are lacking there because they misbehave, not because they don't link (skips without Z88DK).
   Status: done 2026-11-05 (`scripts/z88dk-profile.py`).
   - **The probe:** an asm probe, assembled by zcc, references every C function. zcc reports all undefined symbols in one run. When a library module needs something missing, halving the list finds the culprit.
   - **The heap:** `_heap` is fixed with `-pragma-define:CRT_HEAP_AMALLOC=1` (3/4 of free memory at run time), added only when a link fails without it, as on TRS-80 `cmd`. `CLIB_MALLOC_HEAP_SIZE=-1` means no heap, and a positive size is stored in the program file.
   - **Output naming:** `-create-app -o {stem}.bin` when the app file has exactly the probe's stem. Otherwise the raw binary with `-o {file}`, which is how CP/M ends up with `.com`.
   - **Results:**
     - test `.bin`, trs80 `.cas`, trs80 cmd `.cmd`, cpm `.com`, zx `.tap`, c128 `.T64`.
     - All of them lack the math functions, the directory functions, usleep and strtod. All but trs80 cmd also lack free, realloc and strdup. trs80 cmd also lacks ftell and rename.
     - Refused: agon, `cpm --clib 8080`, gb.
     - A generated trs80 cmd profile with the step 2 hooks ran in trs80gp (`-m3 -ld`) with the right status and printer output.
4. Status: done 2026-11-05.
   - **The profiles:** `z80-trs80` (.cas, no args), `z80-trs80-cmd` (.cmd, args, heap pragma), `z80-cpm` (.com, args), `z80-zx` (.tap) and `z80-c128` (`-subtype=disk`: a PRG without extension at $5000). Each has the generator's libc_lacks plus qsort. The TRS-80 io_ranges are `$37e0-$37ff,$3800-$3bff,$3c00-$3fff`.
   - **Tests:** each profile builds about 50 test programs (link only, ~7 s each). A drift test reruns the generator for every profile.
   - **Excluded:** besides floats and wide addresses, the atan2 program, since every z88dk library lacks atan2f and floorf.
   - **The C128 (user's decision, 2026-11-05):** `z80-c128` uses z88dk's `disk` subtype. It gives a plain PRG at $5000, loaded with `LOAD"NAME",8,1` and started with `BANK 0:SYS 20480`.
     - The `.T64` didn't autostart: VICE loads only its first entry.
     - A single RUN-able PRG works: a BASIC line `10 BANK 0:SYS 20480` at $1C01, padded to $5000, with spaces over the crt0's screen at $2000. It is about 15 KB. In x128, autostart ran it. It was left out to keep things simple; a developer may want their own loader.
     - zcc also leaves a `<stem>.bin` next to the `.cas`, `.tap` and C128 files.

   **The five built-in profiles:** generate them, finish them by hand, and give each a link-only test that compiles a few programs (hello, the strings and math libraries, a file program) and checks that zcc links them. The tests skip without `LLVM_Z80`/`Z88DK`.
5. **The emulator runs:**
   - Runner scripts, support sources and test profiles, run in this order: TRS-80 Model I/III/4, then CP/M with tnylpo.
   - The test profiles go in a new test resource directory (e.g. `compiler/test/res/llvm-triples/`) and are resolved by file path.
   - Each gets an entry in `llvm16ProgramRunners` (TestLlvmCompiler), which is already generic.
   - Each gets a `requires<Emulator>` guard that checks the emulator itself (found on the PATH or through a variable such as `TRS80GP`), since `run_command`'s first word is now python.
   - Each gets its own `notRunOn<...>` map, built from `notRunOnZ80` plus or minus its differences. Every exclusion has an observed reason.
   - CP/M is the first z80 platform where file I/O, `ftell`, `remove` and `rename` should all work, so the diskio and fileio tests that ticks can't run are expected to pass there.
   Status: done 2026-11-05.
   - **Where:** the test profiles and runner scripts are in `compiler/test/fixtures/llvm-triples/`, next to the hook sources, not in `scripts/`: `tnylpo-cpm`, `trs80gp-m3` (LDOS), and `trs80gp-m1` (TRSDOS) and `trs80gp-m4` (TRSDOS 6) on top of `trs80gp-m3`. The run commands are `${PYTHON:-python3} {profile_dir}/run-*.py`; the tests replace the first word with the python they find.
   - **Runner scripts:** `run-trs80gp.py` copies the `.cmd` to `prog.cmd` in a temporary directory and prints the printer file as it is (LF line ends already, no conversion). `run-tnylpo.py` copies the `.com` to `prog.com` in a temporary directory and runs it by path, with a configuration of its own that makes the current directory drive A, so the working directory gets only the program's own files. Both stop the emulator after 50 s (status 124).
   - **Guards:** tnylpo on the PATH; trs80gp only through `TRS80GP`, as it opens a window per run. m3 runs everything, m1 and m4 five programs (`only`).
   - **Program arguments:** the llvm16 runners now pass an output program's arguments when the profile has `program_args`.
   - **Excluded on CP/M** (besides floats, wide addresses, atan2 and the 8-byte arena):
     - console and line input: tnylpo turns LF into CR (a CR LF is two line ends) and gives ^Z forever at the end of the input, which `fgetc_cons` returns as a character, so the line input programs hang.
     - files are whole 128-byte records: sizes round up, and reads go past the written end (diskio writing, saving and loading).
     - fileio copying: z88dk's CP/M `fread` doesn't set the end-of-file flag either; reads give 0 forever.
     - no directories; the CCP upper-cases and splits the arguments; argv[0] is empty (the three argument programs); the console expands tabs (strings library); `$0400` is in the program's code.
   - **Excluded on trs80gp:** console input (no stdin), arguments (no command line; z88dk's TRS-80 crt0 then gives argc 0, while with arguments argv[0] is an empty name), `$0400` is ROM, and **every file program**.
   - **Surprise, TRS-80 files:** z88dk's `-ltrsdos` file routines fail in trs80gp, also from a plain C program linked with zcc: `fopen` for writing and reading (`TEST/TXT`, `TEST/TXT:0`, `SYS0/SYS`) returns NULL on Model I TRSDOS and LDOS, and hangs on Model III LDOS and Model 4. Explained and fixed in step 6 (a z88dk heap bug); Model 4 can't work.
   - **Model 4:** the smoke programs pass under TRSDOS 6 (output through the printer port), so z88dk's Model III code runs in Model 4 mode; the screen output itself wasn't checked.
   - **Results and timings** (each program optimized and not, build included): tnylpo 15 tests in 16 s; trs80gp Model III 16 tests in 157 s; Model I and Model 4 5 tests each in 22 s and 27 s. All pass.
6. **Research items** (the result gets written down whether or not it is fixed):
   - Why TRSDOS 1.3 on the Model III doesn't start a directly loaded `.cmd`. Check whether it's trs80gp or z88dk's `.cmd` format: try a `.cmd` from another source, and loading from a disk image.
   - Whether z88dk's Model I/III console works under TRSDOS 6 in Model 4 mode.
   - Why z88dk's TRS-80 file routines fail in trs80gp.
   Status: done 2026-11-05. Repros and a README for z88dk are in the session scratchpad (`step6/upstream/`).
   - **Files, the cause:** a z88dk heap bug. The `cmd` subtype's `-DAMALLOC` takes the heap's top from SP, as `trs80_crt0.asm` reads HIGH$ only under `CRT_HEAP_ENABLE`, which nothing defines. The cmd crt0 keeps the DOS's stack, below the program (SP $41A2 Model I TRSDOS, $417E Model I LDOS, $4185 Model III LDOS; BSS ends near $62B0), so the size wraps and malloc returns ROM addresses ($08B3-$08D0). `open()` mallocs its FCB and buffer, so @FSPEC fails (errno 19) and every fopen gives NULL. Also in a plain zcc C program.
   - **The fix:** `-pragma-define:CRT_HEAP_ENABLE=1` next to `CRT_HEAP_AMALLOC=1`: the heap then runs from the program's end to HIGH$ (malloc gave $FEDE). Added to `z80-trs80-cmd`, the trs80gp test profile, and the generator (which adds it when the platform's crt0 tests `DEFINED_CRT_HEAP_ENABLE`).
   - **Then:** a C program's fopen "w", fputs, fclose, fopen "r", fgets work on Model III LDOS and on Model I TRSDOS 2.3 once drive 0 isn't write-protected: its boot disk is (DOS COPY says "HIT WRITE ERROR"; `-im wp 0 off` lifts it, so `run-trs80gp.py` now passes that). The Model I LDOS boot disk has 0 K free (a file is created with no data), and blank disks in drive 1 (`:tb1`, `:lb1`) didn't help (error 26), not looked into further.
   - **The Prog8 file programs still fail on Model III LDOS**, each for a known reason, so they stay excluded (with the reasons now in `notRunOnTrs80gp`): `ftell` is lacking (f_tell 0/0, seeks fail, `diskio.load` needs it), `fread` never sets the end of file (the fileio copy loop runs forever reading 0), and `.` in a TRSDOS name starts the password, so `memory.prg` and `memory.bin` are one file (the second save fails; with `memory/prg` both save). Directories don't exist.
   - **Update, the `.` collision:** fixed by the profile key `file_extension_separator = /` on `z80-trs80-cmd`: the generated code copies each file name of fopen, remove and rename to a 64-byte static buffer and replaces its last `.` there, so `name.prg` reaches the DOS as `NAME/PRG`. A program writing, reading and deleting `name.prg` and `name.bin` passes on Model III LDOS (on the Model I the writes fail as before, also with `name/prg` names). `ftell` and the end of file still keep the file programs excluded.
   - **TRSDOS 1.3, the cause:** not trs80gp: a hand-made 14-byte `.cmd` (store to $3C00, exit through $47) runs from TRSDOS 1.3. TRSDOS 1.3 enters with SP=$4089; the crt0 reserves 64 bytes on it for atexit, which moves the stack down to $4049 and below, onto system variables (the clock bytes are around $4041; that the interrupt corrupts it there is the likely, not proven, mechanism). A smaller stack use (no atexit reserve) or a stack elsewhere fixes it, which supports that. Exit probes patched into a z88dk asm-only `.cmd` show it gets past crt0_init and sbrk and hangs in the command line scan, which has no stack use itself. The "0...." line is TRSDOS 1.3's own empty command field ($B0 cursor, then dots), so the screen shows the DOS waiting.
   - **TRSDOS 1.3 workaround:** `-pragma-define:REGISTER_SP=-0x4411` (SP from HIGH$, at $4411 on the Model III, $FFFF there) runs the asm program and the Prog8 hello (output and status 3), without `CRT_HEAP_ENABLE` so the heap ends below the stack. `CLIB_EXIT_STACK_SIZE=0` runs the asm program but not Prog8's hello. Model III only ($4411 is something else on the Model I), so not in the built-in profile. Files still hang on TRSDOS 1.3: direct DOS calls with a static FCB work, OPEN_NEW with the FCB in the heap ($D7xx) hangs.
   - **Model 4 (TRSDOS 6 and LS-DOS 6):** the program's console output doesn't show: the 80-column screen keeps the DOS's boot text, and RAM at $3C00 is unchanged (00 FF pattern), so nothing is corrupted there; only the printer copy appears. $4418-$442F read 00 FF ..., no Model I/III DOS vectors, so fopen hangs. A program waiting in getchar ended in the TRSDOS 6 DEBUG monitor when trs80gp injected a key. Typed input to getchar didn't arrive on Model III LDOS either, so keyboard input wasn't verified anywhere.
7. **Docs:**
   - targetsystem.rst: the `base` key, the generator, each built-in profile with its memory map, I/O and what's lacking, and how to run it on hardware or an emulator.
   - AGENTS.md: the new tools and variables.
   - todo.rst: the Model 4 native mode profile (its own crt0) and port I/O builtins for platforms whose hardware is on I/O ports (C128, ZX).

Each step keeps llvm32/llvm64 `.ll` byte-identical and the mos-sim and z80-test results unchanged.

**Milestone 3 status: done 2026-11-05.**
- **Mac:**
  - Full build without the tool variables: 3444 tests, 0 failures, 98 skipped.
  - With `LLVM_MOS`, `LLVM_Z80` and `Z88DK` set: 3444 tests, 0 failures, 33 skipped (26 of them trs80gp tests, which need `TRS80GP`).
- **Remote check:** all 10 combinations passed, 72 programs.
- **Windows suite:** 3417 tests counted, 0 failures, 106 skipped. The 27 tests of `simpleAst` were not copied back; every other test name matches the Mac run.

## Milestone 4: a z88dk textio

### Decisions
Made by the user, 2026-11-05:
- **Starts after milestone 3** is finished, including the docs, the remote check and the Windows suite.
- **A profile key names a library family:** `library = z88dk` in `z80-z88dk`, so every profile built on it inherits it, generated ones included. The compiler then looks in `prog8lib/llvm16/z88dk/` before `prog8lib/llvm16/` and `prog8lib/`. The directory is named after the family, not the profile.
- **The z88dk `textio.p8`** keeps the `txt.*` interface and uses z88dk's classic console routines (`gotoxy`, `clrscr`, `textcolor`, `fgetc_cons`, ...).
- **Hardware-specific versions are left to the developer,** such as TRS-80 video RAM at `$3C00`. A project's own `textio.p8` already comes first, because the filesystem is searched before the built-in library. The docs should say so, maybe with an example.
- **Maybe later:** a list value (`library = trs80 z88dk`, searched in order) for per-platform layers. Not until a platform needs one.

### The routines to support first
These are the ones the user's program uses:
- `txt.chrout`, `txt.print`, `txt.print_ub`, `txt.print_uw`, `txt.spc`, `txt.nl`
- `txt.cls`, `txt.plot`
- `txt.color`, `txt.setclr`, `txt.rvs_on`, `txt.rvs_off`
- `txt.setchr`, `txt.getchr` (screen character access by position)

### To find out first
- **Which console routines each platform supports:** `+test`, TRS-80, CP/M, ZX and C128 differ. The TRS-80 crt0 uses z88dk's generic console; the C128 uses the ANSI terminal; `+test` and CP/M have only a stream.
- **How `setchr`/`getchr` can work:** they read and write the screen by position. z88dk's generic console may have a character read-back; otherwise these become platform-specific.
- **Calling the C console routines from Prog8** on llvm16, in the way `putchar` and `fgetc_cons` are called.

## Later milestones
- Floats on llvm16 (above).
- TRS-80 Model 4 native mode: 80 columns, video `$F800`, keyboard `$F400`, LS-DOS 6 calls. z88dk has no crt0 for it.
- Port I/O from Prog8 (`in`/`out` builtins, or an `io_ports` profile key), for the C128 Z80 side, the ZX ULA and the Agon.
- Agon and eZ80 (deferred from milestone 3):
  - zcc refuses clang's z80 object for `+agon` (CPU incompatible). Either llvm-z80 marks its objects for ez80_z80, or a profile key hands zcc clang's assembly instead (a second pipeline).
  - Then: a `z80-agon` profile, an `agon-cli-emulator` runner (temporary SD card with `bin/` and `autoexec.txt`, output mirrored to port `$30`, exit through `out (0)`), and the `z80-test` programs on `z88dk-ticks -mez80_z80`.
- After milestone 1: dedupe llvm32 and llvm64 (they differ only in comments and `sys.target`) into shared modules. This is separate work.
- mc6809:
  - lift the `e` check, and audit the little-endian assumptions: the fputc word file format (InstrSyscall.kt:1051-1073), unions as byte arrays, and byte-in-word addressing in codeGenIntermediate (not audited)
  - investigate the collapse
  - USim
- More mos platforms: cx16, mega65 (45GS02 via -mcpu).
- Inline asm and register-passing `extsub` for KERNAL calls.

## Rule for big-endian while building milestone 1
No new llvm-path code that splits a word into bytes by address offset.
