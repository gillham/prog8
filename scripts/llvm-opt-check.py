#!/usr/bin/env python3
r"""
Compares what Prog8 programs do with and without the compiler's optimizations, on llvm64 and on the virtual machine.

Each program is compiled four ways: for llvm64 and for the virtual target, each optimized and with -noopt. Every build
runs with the same input, and its exit status and output are compared with the others. A difference between an
optimized and a -noopt build points at a bug in an optimization; one between llvm64 and the virtual machine at a bug
in the llvm code generator or the VM. Programs that don't compile for llvm64, such as those for a particular machine,
are skipped. The exit status is 1 when any program differs.

By default the programs are the examples and the test programs in compiler/test that print their results, except
those that can't give comparable output (see EXCLUDED). A program that only compiles for llvm64, such as one that uses
the command line, only has its two llvm64 builds compared. The input is empty lines, so programs that wait for Enter
continue; --input gives a file instead.

Examples:
    scripts/llvm-opt-check.py                                # the default programs
    scripts/llvm-opt-check.py examples/primes.p8 mytest.p8   # only these
    scripts/llvm-opt-check.py --filter pointers --keep       # the default programs matching 'pointers', keeping the builds

Needs a built compiler: gradle installdist installshadowdist
"""

import argparse
import difflib
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
WINDOWS = os.name == "nt"
PROG8C = REPO / "compiler" / "build" / "install" / "prog8c" / "bin" / ("prog8c.bat" if WINDOWS else "prog8c")

DEFAULT_PROGRAMS = ["examples/*.p8", "examples/llvm/*.p8", "examples/virtual/*.p8", "examples/pointers/*.p8", "examples/qemu68k/*.p8",
                    "compiler/test/arithmetic/*.p8", "compiler/test/comparisons/*.p8"]
EXCLUDED = {
    "compiler/test/arithmetic/bitshift2.p8": "writes to the absolute address $8000, which a hosted program doesn't own",
    "examples/balls.p8": "animates forever",
    "examples/swirl.p8": "animates forever",
    "examples/swirl-float.p8": "animates forever",
    "examples/llvm/clib.p8": "prints the time",
    "examples/llvm/ls.p8": "lists the directory it runs in, which differs per build",
}
VARIANTS = [("llvm64", True), ("llvm64", False), ("virtual", True), ("virtual", False)]


def run(command, cwd, timeout, stdin=b""):
    try:
        process = subprocess.run(command, cwd=cwd, input=stdin, capture_output=True, timeout=timeout)
        return process.returncode, process.stdout.decode(errors="replace"), process.stderr.decode(errors="replace")
    except subprocess.TimeoutExpired as x:
        return "timeout", (x.stdout or b"").decode(errors="replace"), ""


def vm_output(text):
    """The program's own output: without the compiler banner before it and the VM's exit line after it."""
    start = text.find("Starting Virtual Machine...\n")
    if start >= 0:
        text = text[start + len("Starting Virtual Machine...\n"):]
    return re.sub(r"\n?Program exit! Statuscode=\d+ #steps=\d+\n?$", "", text)


def build_and_run(source, target, optimize, work, opts, stdin):
    name = source.stem
    out = work / f"{source.parent.name}-{name}-{target}-{'opt' if optimize else 'noopt'}"
    out.mkdir(parents=True, exist_ok=True)
    output = ["-o", str(out / name)] if target == "llvm64" else ["-out", str(out)]
    compiled = run([str(opts.prog8c), "-quiet", "-target", target, *output, *([] if optimize else ["-noopt"]), str(source)], out, 600)
    if compiled[0] != 0:
        return "compile-fail", (compiled[1] + compiled[2]).strip()
    if target == "llvm64":
        executable = out / (name + ".exe") if WINDOWS else out / name
        status, text, _ = run([str(executable)], out, opts.timeout, stdin)
    else:
        status, text, _ = run([str(opts.prog8c), "-vm", str(out / f"{name}.p8ir")], out, opts.timeout, stdin)
        text = vm_output(text)
    return status, text.replace("\r\n", "\n").rstrip("\n")


def check(source, work, opts, stdin):
    results = {}
    for target, optimize in VARIANTS:
        results[(target, optimize)] = build_and_run(source, target, optimize, work, opts, stdin)
        if target == "llvm64" and results[(target, optimize)][0] == "compile-fail":
            break
    return source, results


def label(target, optimize):
    return f"{target}{'' if optimize else ' -noopt'}"


def report(source, results):
    """Prints the outcome for one program; returns whether its builds differ."""
    name = os.path.relpath(source, REPO) if source.is_relative_to(REPO) else str(source)
    llvm = results.get(("llvm64", True))
    if llvm is None or llvm[0] == "compile-fail" or results.get(("llvm64", False), ("compile-fail",))[0] == "compile-fail":
        print(f"SKIP  {name}: doesn't compile for llvm64")
        return False
    note = ""
    if all(results.get(("virtual", optimize), ("compile-fail",))[0] == "compile-fail" for optimize in (True, False)):
        results = {variant: result for variant, result in results.items() if variant[0] != "virtual"}
        note = ", llvm64 only: doesn't compile for the virtual target"
    groups = {}
    for variant, result in results.items():
        groups.setdefault(json.dumps(result), []).append(variant)
    if len(groups) == 1:
        print(f"SAME  {name}: exit {llvm[0]}{note}")
        return False
    outcomes = [(json.loads(key), variants) for key, variants in groups.items()]
    print(f"DIFF  {name}: " + " | ".join(f"{', '.join(label(*v) for v in variants)}: exit {result[0]}" for result, variants in outcomes))
    (first, first_variants), (second, second_variants) = outcomes[0], outcomes[1]
    diff = difflib.unified_diff(str(first[1]).splitlines(), str(second[1]).splitlines(),
                                label(*first_variants[0]), label(*second_variants[0]), lineterm="", n=1)
    for line in list(diff)[:12]:
        print("      " + line)
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    parser.add_argument("programs", nargs="*", help="the .p8 programs to check (default: the examples and test programs)")
    parser.add_argument("--filter", help="only the programs whose path matches this regular expression")
    parser.add_argument("--input", help="a file with the input for every run (default: empty lines)")
    parser.add_argument("--timeout", type=int, default=20, help="seconds that one run may take")
    parser.add_argument("--keep", action="store_true", help="keep the builds, and say where they are")
    parser.add_argument("--prog8c", type=Path, default=PROG8C, help=f"the compiler to use (default: {os.path.relpath(PROG8C, REPO)})")
    parser.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 4) // 2), help="programs checked at the same time")
    opts = parser.parse_args()
    if not opts.prog8c.exists():
        sys.exit(f"no compiler at {opts.prog8c}; build it with: gradle installdist installshadowdist")

    if opts.programs:
        sources = [Path(p).resolve() for p in opts.programs]
    else:
        sources = sorted(Path(f) for pattern in DEFAULT_PROGRAMS for f in glob.glob(str(REPO / pattern)))
        sources = [s for s in sources if os.path.relpath(s, REPO).replace(os.sep, "/") not in EXCLUDED]
    if opts.filter:
        sources = [s for s in sources if re.search(opts.filter, str(s))]
    if not sources:
        sys.exit("no programs to check")
    stdin = Path(opts.input).read_bytes() if opts.input else b"\n" * 200

    work = Path(tempfile.mkdtemp(prefix="prog8-opt-check-"))
    try:
        with ThreadPoolExecutor(max_workers=opts.jobs) as pool:
            outcomes = list(pool.map(lambda s: check(s, work, opts, stdin), sources))
        differing = sum(report(source, results) for source, results in outcomes)
    finally:
        if opts.keep:
            print(f"the builds are in {work}")
        else:
            shutil.rmtree(work, ignore_errors=True)
    print(f"\n{len(sources)} programs, {differing} with differences")
    sys.exit(1 if differing else 0)


if __name__ == "__main__":
    main()
