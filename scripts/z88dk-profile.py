#!/usr/bin/env python3
"""
Writes an llvm16 triple profile for a z88dk platform (zcc +<platform>), built on the z80-z88dk profile.

Usage:
  scripts/z88dk-profile.py cpm -o ~/.prog8/llvm/z80-cpm.properties
  scripts/z88dk-profile.py trs80 cmd
  scripts/z88dk-profile.py zx --clib ansi -l mylib

The platform's configuration in $Z88DK/lib/config tells which subtypes and C libraries it has; configurations
for CPUs other than the Z80, and the new and sdcc C libraries, are refused. Small probe programs, assembled and
linked by zcc itself, then find out:
- libc_lacks: the C functions that Prog8 can call which the platform's C library doesn't link
- output_extension and how zcc names its output: the -create-app file (which can have no extension), or the plain
  binary when that file's name doesn't follow the -o name
- whether the link command needs a malloc heap (classic malloc's _heap), which it then gets with the
  CRT_HEAP_AMALLOC pragma: 3/4 of the free memory, set up at run time; with CRT_HEAP_ENABLE too when the
  platform's crt0 can take the top of memory from the system (TRS-80: HIGH$), as the heap otherwise ends at the stack
What a probe can't find out is left for the user to check: io_ranges, getchar_symbol, program_args, run_command,
library and putchar_symbol (z88dk's generic console), and functions that link but misbehave on the platform
(libc_lacks names those too, as z80-test does).

Needs Z88DK (the z88dk directory).
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE_PROFILE = ROOT / "codeCore/res/prog8lib/llvm-triples/z80-z88dk.properties"

# must equal LlvmTripleProfile.C_FUNCTIONS (a test checks it)
MATH_FUNCTIONS = ["sin", "cos", "tan", "atan", "atan2", "sqrt", "pow", "log", "log2", "fabs", "floor", "ceil", "roundeven"]
C_FUNCTIONS = ["fopen", "fclose", "fgetc", "fputc", "fread", "fwrite", "fseek", "ftell", "ferror", "feof", "fflush", "remove", "rename",
               "getcwd", "mkdir", "chdir", "rmdir", "opendir", "readdir", "closedir", "strcmp", "strdup", "realloc", "free", "qsort",
               "putchar", "getchar", "exit", "usleep", "strtod", "snprintf", "printf"] + [m + s for m in MATH_FUNCTIONS for s in ("", "f")]

# the functions that only need classic malloc's heap to link; lacking them is better than reserving a heap
ALLOCATION_FUNCTIONS = {"free", "realloc", "strdup"}
HEAP_PRAGMA = "-pragma-define:CRT_HEAP_AMALLOC=1"
# the TRS-80 cmd crt0 keeps the DOS's stack, below the program, so without this the heap wraps around into the ROM
HEAP_TOP_PRAGMA = "-pragma-define:CRT_HEAP_ENABLE=1"
NOT_Z80_OPTIONS = {"-m8080", "-m8085", "-mgbz80", "-mr2ka", "-mr3k", "-mr4k", "-mr5k", "-mr6k", "-mkc160", "-mvm1"}
REFUSED_CLIBS = {"new", "sdcc_ix", "sdcc_iy"}
UNDEFINED = re.compile(r"^(\S+?):\d+: error: undefined symbol: (\S+)", re.MULTILINE)


def fail(message):
    sys.exit(f"z88dk-profile: {message}")


def read_config(z88dk, platform):
    configs = Path(z88dk, "lib", "config")
    if not (configs / f"{platform}.cfg").is_file():
        names = sorted(p.stem for p in configs.glob("*.cfg"))
        fail(f"unknown platform '{platform}'; the platforms are: {' '.join(names)}")
    options, clibs, subtypes, crt0 = [], {}, {}, None
    for line in (configs / f"{platform}.cfg").read_text(errors="replace").splitlines():
        words = line.split()
        if not words:
            continue
        if words[0] == "OPTIONS":
            options = words[1:]
        elif words[0] == "CLIB" and len(words) > 1:
            clibs[words[1]] = words[2:]
        elif words[0] == "SUBTYPE" and len(words) > 1:
            subtypes[words[1]] = words[2:]
        elif words[0] == "CRT0" and len(words) > 1:
            crt0 = Path(words[1].replace("DESTDIR", str(z88dk)) + ".asm")
    return options, clibs, subtypes, crt0


def crt0_has_heap_top(crt0):
    return crt0 is not None and crt0.is_file() and "DEFINED_CRT_HEAP_ENABLE" in crt0.read_text(errors="replace")


def option_value(words, name):
    values = [w.split("=", 1)[1] for w in words if w.startswith(f"-{name}=")]
    return values[-1] if values else None


def not_z80_reason(words):
    for word in words:
        if word in NOT_Z80_OPTIONS:
            return f"it builds for another CPU ({word})"
        if word.startswith("-mez80"):
            return f"it builds for the eZ80 ({word}), which llvm16 doesn't support yet"
        if word in ("-clib=8080", "-clib=8085"):
            return f"it selects an 8080 C library ({word})"
    return None


def check_configuration(platform, subtype, clib, options, clibs, subtypes):
    """The subtype and C library that zcc will use, after refusing what llvm-z80 can't link with."""
    if subtype is not None and subtype not in subtypes:
        fail(f"platform '{platform}' has no subtype '{subtype}'; its subtypes are: {' '.join(subtypes) or '(none)'}")
    used_subtype = subtype if subtype is not None else option_value(options, "subtype")
    subtype_words = subtypes.get(used_subtype, [])
    used_clib = clib or option_value(subtype_words, "clib") or option_value(options, "clib")
    if clib is not None and clib not in clibs:
        fail(f"platform '{platform}' has no C library '{clib}'; its C libraries are: {' '.join(clibs) or '(none)'}")
    if used_clib in REFUSED_CLIBS:
        fail(f"the '{used_clib}' C library can't be used: llvm-z80's z88dk output is only known to work with the classic C library")
    for what, words in (("platform", options), (f"C library '{used_clib}'", clibs.get(used_clib, [])), (f"subtype '{used_subtype}'", subtype_words)):
        reason = not_z80_reason(words)
        if reason:
            fail(f"the {what} of '{platform}' can't be used: {reason}")
    return used_subtype, used_clib


class Linker:
    def __init__(self, z88dk, zcc_args, directory):
        self.zcc_args = zcc_args
        self.directory = Path(directory)
        self.environment = dict(os.environ, ZCCCFG=str(Path(z88dk, "lib", "config")))
        self.environment["PATH"] = str(Path(z88dk, "bin")) + os.pathsep + self.environment.get("PATH", "")
        self.zcc = shutil.which("zcc", path=self.environment["PATH"])
        if self.zcc is None:
            fail(f"zcc not found in {Path(z88dk, 'bin')}")
        self.count = 0

    def link(self, symbols, extra_args, output):
        """zcc's output for a program that references [symbols], and its exit status."""
        self.count += 1
        source = self.directory / f"probe{self.count}.asm"
        lines = ["\tSECTION code_user", "\tPUBLIC _main"] + [f"\tEXTERN {s}" for s in symbols]
        lines += ["_main:", "\tld hl,0", "\tret"] + [f"\tdefw {s}" for s in symbols]
        source.write_text("\n".join(lines) + "\n")
        process = subprocess.run([self.zcc] + self.zcc_args + extra_args + ["-o", output, source.name],
                                 cwd=self.directory, env=self.environment, capture_output=True, text=True, errors="replace")
        return process.returncode, process.stdout + process.stderr

    def undefined(self, symbols, extra_args):
        """The undefined symbols that the probe references itself, and those that library modules reference."""
        status, output = self.link(symbols, extra_args, "probe")
        direct, indirect = set(), set()
        for file, symbol in UNDEFINED.findall(output):
            (direct if file.startswith("probe") and file.endswith(".asm") else indirect).add(symbol)
        if status != 0 and not direct and not indirect:
            fail(f"zcc failed:\n{output}")
        return direct, indirect


def c_symbol(name, getchar_symbol):
    return getchar_symbol if name == "getchar" else "_" + name


def failing_functions(linker, functions, symbols, extra_args):
    """The functions whose own modules reference undefined symbols, each with those symbols, found by halving."""
    _, indirect = linker.undefined([symbols[f] for f in functions], extra_args)
    if not indirect:
        return {}
    if len(functions) == 1:
        return {functions[0]: indirect}
    half = len(functions) // 2
    return failing_functions(linker, functions[:half], symbols, extra_args) | failing_functions(linker, functions[half:], symbols, extra_args)


def find_lacking(linker, symbols, pragmas):
    """The functions that don't link, and whether the heap pragma is needed for the others."""
    by_symbol = {s: f for f, s in symbols.items()}
    direct, _ = linker.undefined(list(symbols.values()), pragmas)
    unknown = direct - by_symbol.keys()
    if unknown:
        fail(f"zcc reports undefined symbols that the probe doesn't reference: {' '.join(sorted(unknown))}")
    lacking = {by_symbol[s] for s in direct}
    linkable = [f for f in symbols if f not in lacking]
    failing = failing_functions(linker, linkable, symbols, pragmas)
    heap_users = {f for f, missing in failing.items() if missing == {"_heap"}}
    return lacking | (failing.keys() - heap_users), heap_users


def output_naming(linker, pragmas):
    """The -create-app flag (or none), the -o argument, and the output extension."""
    app_dir = linker.directory / "app"
    app_dir.mkdir()
    status, output = linker.link([], pragmas + ["-create-app"], "app/probe.bin")
    if status != 0:
        fail(f"zcc -create-app failed:\n{output}")
    names = os.listdir(app_dir)
    # c128's disk subtype writes the program as a file without an extension, next to its .ldr BASIC loader
    if "probe" in names:
        return ["-create-app"], "{stem}.bin", ""
    created = sorted(n for n in names if n.lower().startswith("probe.") and n != "probe.bin")
    exact = [n for n in created if n.startswith("probe.")]
    if exact:
        return ["-create-app"], "{stem}.bin", exact[0][len("probe"):]
    raw_extension = ".com" if any(n.lower() == "probe.com" for n in created) else ".bin"
    return [], "{file}", raw_extension


def verify_naming(linker, pragmas, create_app, output_arg, extension):
    check_dir = linker.directory / "check"
    check_dir.mkdir()
    output = "check/" + output_arg.replace("{stem}", "probe").replace("{file}", "probe" + extension)
    status, messages = linker.link([], pragmas + create_app, output)
    if status != 0 or ("probe" + extension) not in os.listdir(check_dir):
        fail(f"linking as the profile will doesn't give probe{extension} (it gives {' '.join(sorted(os.listdir(check_dir)))}):\n{messages}")


def wrapped(key, words, width=120):
    lines, line = [], f"{key} ="
    for word in words:
        if len(line) + 1 + len(word) > width - 2:
            lines.append(line + " \\")
            line = "    " + word
        else:
            line += " " + word
    return "\n".join(lines + [line])


def base_getchar_symbol():
    for line in BASE_PROFILE.read_text().splitlines():
        key, _, value = line.partition("=")
        if key.strip() == "getchar_symbol":
            return value.strip()
    return "getchar"


def main():
    parser = argparse.ArgumentParser(description="Writes an llvm16 triple profile for a z88dk platform, built on z80-z88dk.")
    parser.add_argument("platform", nargs="?", help="the z88dk platform, as in zcc +<platform>")
    parser.add_argument("subtype", nargs="?", help="the platform's subtype (default: the platform's default)")
    parser.add_argument("--clib", help="the C library (default: the platform's default)")
    parser.add_argument("-l", dest="libraries", action="append", default=[], metavar="LIB", help="an extra library to link with")
    parser.add_argument("-o", dest="output", help="the profile file to write (default: standard output)")
    parser.add_argument("--list-functions", action="store_true", help="print the C functions that libc_lacks can name, and stop")
    args = parser.parse_args()

    if args.list_functions:
        print("\n".join(C_FUNCTIONS))
        return
    if not args.platform:
        parser.error("the platform is required")
    z88dk = os.environ.get("Z88DK")
    if not z88dk:
        fail("Z88DK must name the z88dk directory")

    options, clibs, subtypes, crt0 = read_config(z88dk, args.platform)
    subtype, clib = check_configuration(args.platform, args.subtype, args.clib, options, clibs, subtypes)
    selection = ([f"-subtype={args.subtype}"] if args.subtype else []) + ([f"-clib={args.clib}"] if args.clib else [])
    libraries = [f"-l{lib}" for lib in args.libraries]

    getchar_symbol = base_getchar_symbol()
    symbols = {f: c_symbol(f, getchar_symbol) for f in C_FUNCTIONS}
    with tempfile.TemporaryDirectory(prefix="z88dk-profile-") as directory:
        linker = Linker(z88dk, [f"+{args.platform}"] + selection + libraries, directory)
        pragmas = []
        direct, indirect = linker.undefined([], [])
        if indirect - {"_heap"} or direct:
            fail(f"a program with only main doesn't link, undefined: {' '.join(sorted(direct | indirect))}")
        if indirect:
            pragmas = [HEAP_PRAGMA]
        lacking, heap_users = find_lacking(linker, symbols, pragmas)
        if heap_users - ALLOCATION_FUNCTIONS and not pragmas:
            pragmas = [HEAP_PRAGMA]
            lacking, heap_users = find_lacking(linker, symbols, pragmas)
        if heap_users:
            if pragmas:
                fail(f"these functions still lack a heap with {HEAP_PRAGMA}: {' '.join(sorted(heap_users))}")
            lacking |= heap_users
        if pragmas and crt0_has_heap_top(crt0):
            pragmas.append(HEAP_TOP_PRAGMA)
        create_app, output_arg, extension = output_naming(linker, pragmas)
        verify_naming(linker, pragmas, create_app, output_arg, extension)

    link_command = ["zcc", f"+{args.platform}"] + selection + create_app + pragmas + ["-o", output_arg, "{startup}", "{object}",
                                                                                       "-l${LLVM_Z80}/lib/z80/z80_rt_z88dk"] + libraries
    described = f"+{args.platform}, subtype {subtype or '(none)'}, C library {clib or '(none)'}" + (f", with {' '.join(libraries)}" if libraries else "")
    profile = [
        f"# The Z80 for z88dk's {described}, for the llvm16 target.",
        "# Generated by scripts/z88dk-profile.py, which only knows what links: check io_ranges, getchar_symbol, program_args",
        "# and run_command by hand, and add to libc_lacks the functions that link but misbehave on this platform.",
        "base = z80-z88dk",
        "link_command = " + " ".join(link_command),
        f"output_extension = {extension}",
        "# the C functions that this C library doesn't link",
        wrapped("libc_lacks", sorted(lacking)),
        "# the memory-mapped I/O addresses, if any",
        "# io_ranges =",
        f"# getchar_symbol = {getchar_symbol}",
        "# on a platform with z88dk's generic console: its txt module, and all console output through fputc_cons",
        "# library = z88dk",
        "# putchar_symbol = fputc_cons",
        "# program_args = false",
        "# run_command =",
    ]
    text = "\n".join(profile) + "\n"
    if args.output:
        Path(args.output).write_text(text)
    else:
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
