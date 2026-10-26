#!/usr/bin/env python3
r"""
Measures what the Prog8 optimizer adds on the llvm64 target, where clang -O2 optimizes the program as well.

Each benchmark program in scripts/llvm-bench is built twice for this machine: optimized, and with -noopt so that only
LLVM optimizes it. Both builds must print the same result. Then they run in turn, several times, and the table shows
the fastest run of each, the size of their machine code (the text section) and how long the compiler took.

Each program exercises one kind of code: byte or word arrays, long arithmetic, calls, floats, pointers and structs,
when dispatch, or the string library. Their work depends on the number of command line arguments, so that LLVM can't
compute the results while compiling.

Examples:
    scripts/llvm-bench.py                       # all the benchmarks
    scripts/llvm-bench.py --filter 'sort|fib'   # only these
    scripts/llvm-bench.py --runs 15             # more runs, for less noise

Needs a built compiler (gradle installdist installshadowdist), clang, and llvm-size from the same LLVM installation.
"""

import argparse
import glob
import os
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
BENCHMARKS = REPO / "scripts" / "llvm-bench"
WINDOWS = os.name == "nt"
PROG8C = REPO / "compiler" / "build" / "install" / "prog8c" / "bin" / ("prog8c.bat" if WINDOWS else "prog8c")
VARIANTS = {"prog8+llvm": [], "llvm only": ["-noopt"]}


def find_llvm_size():
    found = shutil.which("llvm-size")
    if found:
        return found
    clang = shutil.which("clang")
    for directory in [Path(clang).parent if clang else None, Path("/opt/homebrew/opt/llvm/bin")]:
        if directory and (directory / "llvm-size").exists():
            return str(directory / "llvm-size")
    return None


def build(prog8c, source, out, flags):
    start = time.perf_counter()
    process = subprocess.run([str(prog8c), "-quiet", "-target", "llvm64", *flags, str(source), "-o", str(out)],
                             capture_output=True, text=True)
    elapsed = time.perf_counter() - start
    if process.returncode != 0:
        sys.exit(f"building {source.name} failed:\n{process.stdout}{process.stderr}")
    return out.with_name(out.name + ".exe") if WINDOWS else out, elapsed


def text_size(llvm_size, executable):
    if not llvm_size:
        return None
    output = subprocess.run([llvm_size, "-A", str(executable)], capture_output=True, text=True).stdout
    for line in output.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[0] in ("__text", ".text"):
            return int(fields[1])
    return None


def run(executable):
    start = time.perf_counter()
    process = subprocess.run([str(executable)], capture_output=True, text=True)
    return time.perf_counter() - start, (process.returncode, process.stdout)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    parser.add_argument("--filter", help="only the benchmarks whose name matches this regular expression")
    parser.add_argument("--runs", type=int, default=7, help="runs of each build; the fastest one counts")
    parser.add_argument("--prog8c", type=Path, default=PROG8C, help=f"the compiler to use (default: {os.path.relpath(PROG8C, REPO)})")
    opts = parser.parse_args()
    if not opts.prog8c.exists():
        sys.exit(f"no compiler at {opts.prog8c}; build it with: gradle installdist installshadowdist")
    sources = [Path(p) for p in sorted(glob.glob(str(BENCHMARKS / "*.p8")))]
    if opts.filter:
        sources = [s for s in sources if re.search(opts.filter, s.stem)]
    llvm_size = find_llvm_size()

    work = Path(tempfile.mkdtemp(prefix="prog8-llvm-bench-"))
    try:
        print(f"{'benchmark':10} {'prog8+llvm':>12} {'llvm only':>12} {'ratio':>6}   {'text bytes':>17}   {'compile seconds':>15}")
        for source in sources:
            builds = {variant: build(opts.prog8c, source, work / f"{source.stem}-{index}", flags)
                      for index, (variant, flags) in enumerate(VARIANTS.items())}
            # the first run of a new executable can be slow, as the operating system checks it
            results = {variant: run(executable)[1] for variant, (executable, _) in builds.items()}
            if len(set(results.values())) != 1:
                print(f"{source.stem:10} the builds give different results: {results}")
                continue
            times = {variant: [] for variant in VARIANTS}
            for _ in range(opts.runs):
                for variant, (executable, _) in builds.items():
                    times[variant].append(run(executable)[0])
            optimized, llvm_only = (min(times[variant]) for variant in VARIANTS)
            spread = max(statistics.pstdev(t) / min(t) for t in times.values())
            sizes = [text_size(llvm_size, executable) for executable, _ in builds.values()]
            size_text = f"{sizes[0]} / {sizes[1]}" if None not in sizes else "no llvm-size"
            compile_text = " / ".join(f"{seconds:.1f}" for _, seconds in builds.values())
            print(f"{source.stem:10} {optimized * 1000:10.1f}ms {llvm_only * 1000:10.1f}ms {optimized / llvm_only:6.2f}   "
                  f"{size_text:>17}   {compile_text:>15}   (spread {spread * 100:.0f}%)")
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
