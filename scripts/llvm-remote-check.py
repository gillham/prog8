#!/usr/bin/env python3
"""
Runs the llvm test programs of compiler/test/codegeneration/TestLlvmCompiler.kt on other hosts over ssh.

The compiler's own tests only run the programs natively on the build machine. This script checks the triple profiles of
other platforms: it compiles every program for the triple of each host, builds the executable there with the host's own
clang ("remote" linking) and/or here with a sysroot copied from the host ("local" cross linking), runs it on the host,
and compares the exit status, console output and files left behind with a native run on this machine.

The self-checking programs must exit with status 0. The programs with console output must give the same exit status
and output as the native build. Hosts are given as host=triple, with --host or in the PROG8_REMOTE_HOSTS environment
variable, separated by spaces; ssh and rsync must work without prompts. The commands on the hosts run under sh,
whatever their login shell is.

A triple with 4-byte pointers is compiled for the llvm32 target, without the programs that need 8-byte pointers. A host
can also run the programs of another CPU: --clang names its clang, --clang-arg adds the sysroot and other arguments for
remote linking, and --run-prefix the emulator that runs each program, such as qemu-riscv32 -L <sysroot>. For local
linking, --link-arg adds a clang link argument, such as the --gcc-install-dir that a Buildroot sysroot needs.

Examples:
    export PROG8_REMOTE_HOSTS="linuxbox=x86_64-unknown-linux-gnu pi=aarch64-unknown-linux-gnu oldmac=x86_64-apple-macosx"
    scripts/llvm-remote-check.py                                 # those hosts, remote linking
    scripts/llvm-remote-check.py --fetch-sysroots                # copy the sysroots of the Linux hosts once
    scripts/llvm-remote-check.py --link both --filter quad       # also cross link here, only programs matching 'quad'
    scripts/llvm-remote-check.py --host linuxbox=x86_64-unknown-linux-gnu --noopt-only
    scripts/llvm-remote-check.py --host linuxbox=riscv32-unknown-linux-gnu --clang clang-19 --clang-arg=--sysroot=<dir> \
        --clang-arg=--gcc-install-dir=<dir> --clang-arg=-fuse-ld=lld --run-prefix "qemu-riscv32 -L <dir>"
    scripts/llvm-remote-check.py --host rv32vm=riscv32-unknown-linux-gnu --link local --link-arg=--gcc-install-dir=<dir>

Needs a built compiler: gradle installdist installshadowdist
"""

import argparse
import concurrent.futures
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEST_SOURCE = ROOT / "compiler/test/codegeneration/TestLlvmCompiler.kt"
PROG8C = ROOT / "compiler/build/install/prog8c/bin/prog8c"
PROFILES = ROOT / "codeCore/res/prog8lib/llvm-triples"
WORK = ROOT / "build/llvm-remote-check"
SYSROOTS = WORK / "sysroots"
REMOTE_DIR = "prog8-remote-check"          # relative to the remote home directory
RUN_TIMEOUT = 60
# without a connect timeout, ssh on macOS can fail to connect with "Undefined error: 0"
SSH = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15"]
RSYNC = ["rsync", "-e", " ".join(SSH)]


# the libraries and startup files that linking a Prog8 program for glibc needs, from /usr/lib/<multiarch>
SYSROOT_LIBRARIES = ["*crt*.o", "libc.so*", "libc.a", "libc_nonshared.a", "libm.so*", "libm.a", "libm-*.a", "libmvec*",
                     "ld-linux*", "libpthread*", "libdl*", "librt*", "libgcc_s*", "libutil*", "libresolv*"]


@dataclass
class Program:
    name: str
    source: str
    self_checking: bool                    # exits with 0 when all its checks pass
    input: str = ""
    args: list = field(default_factory=list)


@dataclass
class Result:
    status: int
    output: str
    leftovers: list


# ---- reading the programs from the Kotlin test source ----

KOTLIN_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "\\": "\\", "\"": "\"", "'": "'", "$": "$"}


def kotlin_string(text, pos):
    """Parses the Kotlin string literal at text[pos], plain or triple quoted, with an optional $$ prefix. Returns (value, end)."""
    if text.startswith("$$", pos):
        pos += 2
    if text.startswith('"""', pos):
        end = text.index('"""', pos + 3)
        while text.startswith('"', end + 3):     # a raw string can end with quotes
            end += 1
        return text[pos + 3:end], end + 3
    if text[pos] != '"':
        raise ValueError(f"no string literal at {text[pos:pos + 40]!r}")
    out = []
    i = pos + 1
    while text[i] != '"':
        if text[i] == "\\":
            esc = text[i + 1]
            if esc == "u":
                out.append(chr(int(text[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append(KOTLIN_ESCAPES[esc])
            i += 2
        else:
            out.append(text[i])
            i += 1
    return "".join(out), i + 1


def skip_space(text, pos):
    while text[pos] in " \t\r\n":
        pos += 1
    return pos


def map_entries(text, start_marker):
    """The "name" to <value> entries of a top level mapOf(...), as (name, position of the value)."""
    pos = text.index(start_marker) + len(start_marker)
    while True:
        pos = skip_space(text, pos)
        if text.startswith("//", pos):
            pos = text.index("\n", pos)
            continue
        if text[pos] == ")":
            return
        name, pos = kotlin_string(text, pos)
        pos = skip_space(text, pos)
        if not text.startswith("to ", pos):
            raise ValueError(f"expected 'to' after {name!r}")
        pos = skip_space(text, pos + 3)
        value_pos = pos
        pos = yield name, value_pos
        pos = skip_space(text, pos)
        if text[pos] == ",":
            pos += 1


def output_program(text, pos):
    """Parses OutputProgram(source, output, named arguments...) at pos. Returns (source, input, args, end)."""
    if not text.startswith("OutputProgram(", pos):
        raise ValueError(f"expected OutputProgram at {text[pos:pos + 40]!r}")
    pos += len("OutputProgram(")
    source, pos = kotlin_string(text, skip_space(text, pos))
    inputs, args = "", []
    depth = 1
    while depth > 0:
        pos = skip_space(text, pos)
        c = text[pos]
        if c == '"' or text.startswith('$$"', pos):
            _, pos = kotlin_string(text, pos)
        elif text.startswith("input = ", pos):
            inputs, pos = kotlin_string(text, skip_space(text, pos + len("input = ")))
        elif text.startswith("args = listOf(", pos):
            pos += len("args = listOf(")
            while True:
                pos = skip_space(text, pos)
                if text[pos] == ")":
                    pos += 1
                    break
                arg, pos = kotlin_string(text, pos)
                args.append(arg)
                pos = skip_space(text, pos)
                if text[pos] == ",":
                    pos += 1
        else:
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
            pos += 1
    return source, inputs, args, pos


def kotlin_set(text, marker):
    """The strings of a top level setOf(...), plus those of the sets that it adds with + name."""
    pos = text.index(marker) + len(marker)
    names = set()
    while True:
        pos = skip_space(text, pos)
        if text[pos] == ")":
            break
        name, pos = kotlin_string(text, pos)
        names.add(name)
        pos = skip_space(text, pos)
        if text[pos] == ",":
            pos += 1
    for added in re.findall(r"\+\s*(\w+)", text[pos + 1:text.index("\n", pos)]):
        names |= kotlin_set(text, f"private val {added} = setOf(")
    return names


def needs_64bit_pointers():
    """The programs that only build and run right with 8-byte pointers."""
    text = TEST_SOURCE.read_text()
    return kotlin_set(text, "private val programsWith64BitAddresses = setOf(") | kotlin_set(text, "private val programsWithLp64Extsubs = setOf(")


def read_programs():
    text = TEST_SOURCE.read_text()
    programs = []
    entries = map_entries(text, "private val programs = mapOf(")
    try:
        name, pos = next(entries)
        while True:
            source, end = kotlin_string(text, pos)
            programs.append(Program(name, source, self_checking=True))
            name, pos = entries.send(end)
    except StopIteration:
        pass
    for marker in ["private val outputPrograms = mapOf(", "private val externalPrograms = mapOf("]:
        entries = map_entries(text, marker)
        try:
            name, pos = next(entries)
            while True:
                source, inputs, args, end = output_program(text, pos)
                programs.append(Program(name, source, self_checking=False, input=inputs, args=args))
                name, pos = entries.send(end)
        except StopIteration:
            pass
    return programs


# ---- building ----

def slug(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")[:60]


def prog8c(args, cwd):
    command = [str(PROG8C), "-quiet"] + args
    done = subprocess.run(command, cwd=cwd, capture_output=True, text=True)
    if done.returncode != 0:
        raise RuntimeError(f"prog8c {' '.join(args)} failed:\n{done.stdout}{done.stderr}")


def write_case(case_dir: Path, program: Program):
    if case_dir.exists():
        shutil.rmtree(case_dir)
    case_dir.mkdir(parents=True)
    (case_dir / "prog.p8").write_text(program.source)
    (case_dir / "input.txt").write_text(program.input)


def run_script(program: Program, prefix=""):
    """run.sh, which runs the program from the empty run directory next to it, with the program's arguments, after the prefix."""
    return "exec " + (prefix + " " if prefix else "") + "../prog " + " ".join(shlex.quote(a) for a in program.args) + "\n"


def run_locally(case_dir: Path, executable: Path, program: Program) -> Result:
    run_dir = case_dir / "run"
    if run_dir.exists():
        shutil.rmtree(run_dir)
    run_dir.mkdir()
    try:
        done = subprocess.run([str(executable)] + program.args, cwd=run_dir, input=program.input.encode(),
                              capture_output=True, timeout=RUN_TIMEOUT)
        # a signal gives the status that sh reports for it, as on the hosts
        status = done.returncode if done.returncode >= 0 else 128 - done.returncode
        output = done.stdout.decode(errors="replace")
    except subprocess.TimeoutExpired:
        status, output = -1, "(timed out)"
    return Result(status, output, sorted(os.listdir(run_dir)))


def native_reference(case_dir: Path, program: Program, noopt: bool) -> Result:
    ref = case_dir / "native"
    ref.mkdir()
    prog8c(["-target", "llvm64"] + (["-noopt"] if noopt else []) + ["-o", str(ref / "prog"), str(case_dir / "prog.p8")], ref)
    return run_locally(case_dir, ref / "prog", program)


def sysroot_args(triple):
    sysroot = SYSROOTS / triple
    if "linux" not in triple:
        return []
    if not sysroot.is_dir():
        raise RuntimeError(f"no sysroot for {triple}: run with --fetch-sysroots first")
    return ["-Xcompile", f"--sysroot={sysroot}", "-Xlink", "-fuse-ld=lld"]


# ---- the hosts ----

def ssh_tool(command, retries=3):
    """Runs ssh, scp or rsync, retrying when the connection fails (exit status 255)."""
    for attempt in range(retries):
        done = subprocess.run(command, capture_output=True, text=True)
        if done.returncode != 255:
            return done
        time.sleep(2 * (attempt + 1))
    return done


def fetch_sysroot(host, triple):
    multiarch = {"x86_64-unknown-linux-gnu": "x86_64-linux-gnu", "aarch64-unknown-linux-gnu": "aarch64-linux-gnu",
                 "riscv64-unknown-linux-gnu": "riscv64-linux-gnu", "armv7-unknown-linux-gnueabihf": "arm-linux-gnueabihf"}[triple]
    sysroot = SYSROOTS / triple
    found = ssh_tool(SSH + [host, f"ls -d /usr/lib/gcc/{multiarch}/*/crtbegin.o"])
    if found.returncode != 0:
        raise RuntimeError(f"{host}: no gcc startup files in /usr/lib/gcc/{multiarch}: {found.stderr.strip()}")
    gcc_dir = sorted((Path(p).parent for p in found.stdout.split()), key=lambda p: [int(x) for x in re.findall(r"\d+", p.name)])[-1]
    print(f"{host}: copying the {triple} sysroot ({gcc_dir}) to {sysroot}")
    (sysroot / "usr/lib").mkdir(parents=True, exist_ok=True)
    for link, target in [("lib", "usr/lib"), ("lib64", "usr/lib64")]:
        if not (sysroot / link).is_symlink():
            (sysroot / link).symlink_to(target)
    includes = [f"--include={pattern}" for pattern in SYSROOT_LIBRARIES]
    copies = [
        RSYNC + ["-aL", f"{host}:{gcc_dir}/", str(sysroot / gcc_dir.relative_to("/")) + "/"],
        RSYNC + ["-aL"] + includes + ["--exclude=*", f"{host}:/usr/lib/{multiarch}/", str(sysroot / "usr/lib" / multiarch) + "/"],
        RSYNC + ["-aL", "--include=ld-linux*", "--exclude=*", f"{host}:/usr/lib/", str(sysroot / "usr/lib") + "/"],
    ]
    if triple.startswith("x86_64"):
        copies.append(RSYNC + ["-aL", "--include=ld-linux*", "--exclude=*", f"{host}:/usr/lib64/", str(sysroot / "usr/lib64") + "/"])
    for command in copies:
        Path(command[-1]).mkdir(parents=True, exist_ok=True)
        done = ssh_tool(command)
        if done.returncode != 0:
            raise RuntimeError(f"{' '.join(command)} failed: {done.stderr.strip()}")


DRIVER = r"""#!/bin/sh
# builds (when there's a prog.ll) and runs every case directory, writing status, out.txt and leftovers.txt
cd "$(dirname "$0")" || exit 1
for case in */; do
    case=${case%/}
    if [ -f "$case/prog.ll" ]; then
        if ! __CLANG__ __CLANG_ARGS__ -O2 -Wno-override-module "$case/prog.ll" -o "$case/prog" __LINK_ARGS__ > "$case/build.txt" 2>&1; then
            echo build > "$case/status"
            continue
        fi
    fi
    rm -rf "$case/run" && mkdir "$case/run"
    ( cd "$case/run" && perl -e 'alarm shift; exec @ARGV or die' __TIMEOUT__ sh ../run.sh < ../input.txt > ../out.txt 2> ../err.txt )
    echo $? > "$case/status"
    ls -A "$case/run" > "$case/leftovers.txt"
done
"""


def profile_property(triple, key):
    """A property of the compiler's built-in profile for the triple, as the compiler reads it."""
    for line in (PROFILES / f"{triple}.properties").read_text().splitlines():
        name, _, value = line.partition("=")
        if not line.lstrip().startswith("#") and name.strip() == key:
            return value.strip()
    return ""


def pointer_bytes(triple):
    """4 for a profile whose data layout has 32-bit pointers, else 8."""
    return 4 if re.search(r"(^|-)p:32:", profile_property(triple, "datalayout")) else 8


def run_on_host(host, triple, staged: Path, keep: bool, clang="clang", clang_args=()):
    """Copies the staged case directories to the host, builds and runs them there, and copies the results back."""
    # the same clang command as the compiler's own build: clang_args, the optimization level, the file, then link_args
    args = " ".join([profile_property(triple, "clang_args")] + [shlex.quote(a) for a in clang_args])
    driver = DRIVER.replace("__CLANG__", clang).replace("__CLANG_ARGS__", args)
    driver = driver.replace("__LINK_ARGS__", profile_property(triple, "link_args")).replace("__TIMEOUT__", str(RUN_TIMEOUT))
    (staged / "driver.sh").write_text(driver)
    steps = [
        SSH + [host, f"rm -rf {REMOTE_DIR}"],
        RSYNC + ["-a", str(staged) + "/", f"{host}:{REMOTE_DIR}/"],
        SSH + [host, f"sh {REMOTE_DIR}/driver.sh"],
        RSYNC + ["-a", "--exclude=prog", "--exclude=prog.ll", "--exclude=run", f"{host}:{REMOTE_DIR}/", str(staged) + "/"],
    ] + ([] if keep else [SSH + [host, f"rm -rf {REMOTE_DIR}"]])
    for command in steps:
        done = ssh_tool(command)
        if done.returncode != 0:
            raise RuntimeError(f"{' '.join(command)} failed ({done.returncode}): {done.stdout.strip()} {done.stderr.strip()}")


def remote_result(case_dir: Path) -> Result:
    status_text = (case_dir / "status").read_text().strip() if (case_dir / "status").exists() else "missing"
    if status_text == "build":
        return Result(-2, (case_dir / "build.txt").read_text(), [])
    if not status_text.isdigit():
        return Result(-3, f"(no result: {status_text})", [])
    status = int(status_text)
    if status == 142:              # perl's alarm: SIGALRM
        status = -1
    output = (case_dir / "out.txt").read_text(errors="replace")
    leftovers = (case_dir / "leftovers.txt").read_text().split()
    return Result(status, output, leftovers)


# ---- the check ----

def describe(result: Result):
    if result.status == -1:
        return "timed out"
    if result.status == -2:
        return "build failed:\n" + "\n".join("        " + line for line in result.output.splitlines()[:15])
    if result.status == -3:
        return result.output
    return f"status {result.status}"


def compare(program: Program, reference: Result, actual: Result):
    problems = []
    if actual.status < 0:
        return [describe(actual)]
    expected_status = 0 if program.self_checking else reference.status
    if actual.status != expected_status:
        problems.append(f"exit status {actual.status}, expected {expected_status}" +
                        (" (the number of the failed check)" if program.self_checking else ""))
    if not program.self_checking and actual.output != reference.output:
        problems.append(f"output differs:\n        expected {reference.output[:300]!r}\n        actual   {actual.output[:300]!r}")
    if actual.leftovers != reference.leftovers:
        problems.append(f"files left behind: {actual.leftovers}, natively: {reference.leftovers}")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    parser.add_argument("--host", action="append", help="host=triple to run on (repeatable); default: the PROG8_REMOTE_HOSTS environment variable")
    parser.add_argument("--link", choices=["remote", "local", "both"], default="remote",
                        help="build with clang on the host, here with a copied sysroot, or both (default: remote)")
    parser.add_argument("--fetch-sysroots", action="store_true", help="copy the sysroots of the Linux hosts to " + str(SYSROOTS) + " and stop")
    parser.add_argument("--filter", help="only the programs whose name matches this regular expression")
    parser.add_argument("--noopt-only", action="store_true", help="only compile without optimizations")
    parser.add_argument("--keep", action="store_true", help=f"leave the ~/{REMOTE_DIR} directory on the hosts")
    parser.add_argument("--clang", default="clang", help="the clang command on the hosts, for remote linking (default: clang)")
    parser.add_argument("--clang-arg", action="append", default=[], help="an extra argument for clang on the hosts, such as --sysroot=<dir> (repeatable)")
    parser.add_argument("--link-arg", action="append", default=[], help="an extra clang link argument for local linking (repeatable)")
    parser.add_argument("--run-prefix", default="", help="a command that runs each program on the hosts, such as an emulator")
    parser.add_argument("--jobs", type=int, default=os.cpu_count() or 4, help="parallel compiler runs")
    options = parser.parse_args()

    host_specs = options.host or os.environ.get("PROG8_REMOTE_HOSTS", "").split()
    if not host_specs:
        parser.error("no hosts: give --host host=triple, or set PROG8_REMOTE_HOSTS")
    if any("=" not in spec for spec in host_specs):
        parser.error("hosts are given as host=triple")
    hosts = [spec.split("=", 1) for spec in host_specs]
    if options.fetch_sysroots:
        for host, triple in hosts:
            if "linux" in triple:
                fetch_sysroot(host, triple)
        return 0
    if not PROG8C.exists():
        sys.exit(f"no compiler at {PROG8C}: run gradle installdist installshadowdist first")

    programs = read_programs()
    if options.filter:
        programs = [p for p in programs if re.search(options.filter, p.name)]
    optimizations = [True] if options.noopt_only else [True, False]
    links = ["remote", "local"] if options.link == "both" else [options.link]
    print(f"{len(programs)} programs, {len(hosts)} hosts, linking: {', '.join(links)}")

    if WORK.joinpath("cases").exists():
        shutil.rmtree(WORK / "cases")
    cases = []          # (program, noopt, case directory)
    for program in programs:
        for noopt in optimizations:
            cases.append((program, noopt, WORK / "cases" / (slug(program.name) + ("-noopt" if noopt else "-opt"))))

    def reference(case):
        program, noopt, case_dir = case
        write_case(case_dir, program)
        return native_reference(case_dir, program, noopt)

    with concurrent.futures.ThreadPoolExecutor(options.jobs) as pool:
        references = list(pool.map(reference, cases))
    bad_references = [(c, r) for c, r in zip(cases, references) if c[0].self_checking and r.status != 0]
    for (program, noopt, _), result in bad_references:
        print(f"warning: '{program.name}' {'-noopt ' if noopt else ''}fails natively: {describe(result)}")

    failures = 0
    for host, triple in hosts:
        for link in links:
            if link == "local":
                try:
                    extra = sysroot_args(triple) + [x for a in options.link_arg for x in ("-Xlink", a)]
                except RuntimeError as x:
                    print(f"\n{host} ({triple}), local linking: skipped, {x}")
                    continue
            target_name = "llvm32" if pointer_bytes(triple) == 4 else "llvm64"
            host_cases = [c for c in cases if target_name == "llvm64" or c[0].name not in needs_64bit_pointers()]
            staged = WORK / "hosts" / f"{host}-{link}"
            if staged.exists():
                shutil.rmtree(staged)

            def stage(case):
                program, noopt, case_dir = case
                target = staged / case_dir.name
                target.mkdir(parents=True)
                shutil.copy(case_dir / "input.txt", target)
                (target / "run.sh").write_text(run_script(program, options.run_prefix))
                common = ["-target", target_name, "-triple", triple] + (["-noopt"] if noopt else [])
                if link == "remote":
                    prog8c(common + ["-out", str(target), str(case_dir / "prog.p8")], target)
                else:
                    prog8c(common + extra + ["-o", str(target / "prog"), str(case_dir / "prog.p8")], target)
                    (target / "prog.ll").unlink(missing_ok=True)

            print(f"\n{host} ({triple}), {link} linking: building...", flush=True)
            try:
                with concurrent.futures.ThreadPoolExecutor(options.jobs) as pool:
                    list(pool.map(stage, host_cases))
                print(f"{host} ({triple}), {link} linking: running...", flush=True)
                run_on_host(host, triple, staged, options.keep, options.clang, options.clang_arg)
            except RuntimeError as x:
                print(f"  FAILED: {x}")
                failures += len(host_cases)
                continue

            passed = 0
            for (program, noopt, case_dir), ref in zip(cases, references):
                if (program, noopt, case_dir) not in host_cases:
                    continue
                problems = compare(program, ref, remote_result(staged / case_dir.name))
                if problems:
                    failures += 1
                    print(f"  FAIL {program.name}{' (-noopt)' if noopt else ''}")
                    for problem in problems:
                        print(f"      {problem}")
                else:
                    passed += 1
            print(f"{host} ({triple}), {link} linking: {passed} of {len(host_cases)} passed")

    print(f"\n{'all passed' if failures == 0 else f'{failures} failed'}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
