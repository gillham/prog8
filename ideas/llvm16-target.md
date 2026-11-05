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
   - Also fixed: a pointer at an odd struct offset no longer needs a global constructor (`.init_array`, which the z88dk format can't hold). Pointers are only deferred when the data layout's pointer alignment (`LlvmTripleProfile.pointerAlignment`) requires it.
   - z88dk-ticks can't remove or rename files, so `remove` and `rename` are in the profile's `libc_lacks`.
   - z88dk-ticks prints a "Ticks: N" line when a program exits, and its `-no-cycles` option doesn't suppress it. The test runner strips that line.
5. **Docs:** a z80 profile section in targetsystem.rst (`LLVM_Z80`, `Z88DK`, what's lacking, the alignment note), the new keys, todo.rst (real z80 machines, -lm for floats), and the AGENTS.md optional tools.
6. **Optional:** an own sort in the backend when qsort is lacking, for directory listings. It only matters for a future platform that has opendir but no usable qsort.

Each step keeps llvm32/llvm64 `.ll` byte-identical and mos-sim results unchanged. At the end: a full build with LLVM_MOS, LLVM_Z80 and Z88DK set, the remote check and the Windows suite.

## Later milestones
- Floats on llvm16 (above).
- After milestone 1: dedupe llvm32 and llvm64 (they differ only in comments and `sys.target`) into shared modules. This is separate work.
- z80: real machines through other z88dk targets (ZX Spectrum, CP/M, MSX ...).
- mc6809:
  - lift the `e` check, and audit the little-endian assumptions: the fputc word file format (InstrSyscall.kt:1051-1073), unions as byte arrays, and byte-in-word addressing in codeGenIntermediate (not audited)
  - investigate the collapse
  - USim
- More mos platforms: cx16, mega65 (45GS02 via -mcpu).
- Inline asm and register-passing `extsub` for KERNAL calls.

## Rule for big-endian while building milestone 1
No new llvm-path code that splits a word into bytes by address offset.
