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

## Later milestones
- Floats on llvm16 (above).
- After milestone 1: dedupe llvm32 and llvm64 (they differ only in comments and `sys.target`) into shared modules. This is separate work.
- z80: a profile that builds an object or assembly file; nothing runs.
- mc6809:
  - lift the `e` check, and audit the little-endian assumptions: the fputc word file format (InstrSyscall.kt:1051-1073), unions as byte arrays, and byte-in-word addressing in codeGenIntermediate (not audited)
  - investigate the collapse
  - USim
- More mos platforms: cx16, mega65 (45GS02 via -mcpu).
- Inline asm and register-passing `extsub` for KERNAL calls.

## Rule for big-endian while building milestone 1
No new llvm-path code that splits a word into bytes by address offset.
