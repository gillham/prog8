#!/usr/bin/env python3
"""
Generate Prog8 bindings for a C header, for the llvm64 and llvm32 targets: extsub declarations of its
functions, struct and union definitions with the C field layout, and consts of its enums and #defines.

Usage:
  scripts/c-header-to-prog8.py stdio.h -o stdio.p8
  scripts/c-header-to-prog8.py SDL2/SDL.h --pkg-config sdl2 --block sdl -o sdl.p8
  scripts/c-header-to-prog8.py math.h --triple wasm32-wasip1 --block libm

clang parses the header for the chosen triple profile (default: the llvm64 profile of this machine), with
the profile's clang_args, so the sizes of int, long, size_t and pointers match that target. Sizes, field
offsets and constant values are computed by compiling small probe files with the same clang to LLVM IR.
The result is only valid for that triple. Declarations are taken from the header itself and the headers it
includes from its directory tree that have its name in theirs (such as _stdio.h and bits/stdio_lim.h), or, for
a header named with a directory such as SDL2/SDL.h, from all headers in its directory; --from adds more files,
and --list-files shows which headers are included.
"""

import argparse
import json
import os
import platform
import re
import shlex
import struct
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROFILES = ROOT / "codeCore/res/prog8lib/llvm-triples"

PROG8_KEYWORDS = set("""
    a alias and as asmsub bool break byte call clobbers const continue defer do downto e else enum extsub f false
    float for goto if if_cc if_cs if_eq if_mi if_ne if_neg if_nz if_pl if_pos if_vc if_vs if_z in inline long not
    on or pointer private public quad repeat return sizeof step str struct sub swap then to true ubyte union unroll
    until uquad uword via void when while word xor z
""".split())

# builtin functions can't be redefined, so C functions with their names can't be declared
BUILTINS = set(re.findall(r'^\s*"(\w+)"\s+to\s+FSignature', (ROOT / "codeCore/src/prog8/code/core/BuiltinFunctions.kt").read_text(), re.M))
RESERVED = PROG8_KEYWORDS | BUILTINS

INT_TYPE = {(1, True): "byte", (1, False): "ubyte", (2, True): "word", (2, False): "uword",
            (4, True): "long", (4, False): "long", (8, True): "quad", (8, False): "uquad"}

QUALIFIERS = re.compile(r"\b(const|volatile|restrict|__restrict|_Nonnull|_Nullable|_Null_unspecified|_Nullable_result|__unaligned)\b")
VA_LIST = {"va_list", "__builtin_va_list", "__darwin_va_list", "__gnuc_va_list", "__va_list"}
UNNAMED = re.compile(r"\((?:unnamed|anonymous)(?: struct| union| enum)? at (.+):(\d+):(\d+)\)")


# ---------------------------------------------------------------------------
# target profile and clang
# ---------------------------------------------------------------------------

def host_triple() -> str:
    system, machine = platform.system(), platform.machine().lower()
    arch = {"arm64": "aarch64", "aarch64": "aarch64", "x86_64": "x86_64", "amd64": "x86_64", "riscv64": "riscv64"}.get(machine)
    if system == "Darwin" and arch in ("aarch64", "x86_64"):
        return "arm64-apple-macosx" if arch == "aarch64" else "x86_64-apple-macosx"
    if system == "Linux" and arch:
        return f"{arch}-unknown-linux-gnu"
    return "arm64-apple-macosx"


def expand_env(text: str) -> str:
    def repl(m):
        name, default = m.group(1), m.group(3)
        return os.environ.get(name) or (default or "")
    return re.sub(r"\$\{(\w+)(:-([^}]*))?\}", repl, text)


def read_profile(name: str) -> dict:
    path = Path(name)
    if not path.is_file():
        path = PROFILES / f"{name}.properties"
    if not path.is_file():
        sys.exit(f"no triple profile '{name}': give a built-in name ({', '.join(sorted(p.stem for p in PROFILES.glob('*.properties')))}) or a .properties file")
    props = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


class Clang:
    def __init__(self, clang: str, args: list[str], includes: list[str]):
        self.clang = clang
        self.args = args
        self.includes = includes

    def source(self, body: str) -> str:
        return "".join(f"#include <{h}>\n" for h in self.includes) + "#include <stddef.h>\n" + body

    def run(self, extra: list[str], source: str) -> subprocess.CompletedProcess:
        with tempfile.NamedTemporaryFile("w", suffix=".c", delete=False) as f:
            f.write(source)
            name = f.name
        try:
            return subprocess.run([self.clang, *self.args, *extra, "-x", "c", name], capture_output=True, text=True)
        finally:
            os.unlink(name)

    def evaluate(self, exprs: dict[str, str], ctype: str = "long long") -> dict[str, int | float]:
        """Values of the constant C expressions; those that don't compile or aren't constant are left out."""
        names = list(exprs)
        result = {}
        while names:
            lines = [f"const {ctype} p8probe_{i} = ({exprs[n]});" for i, n in enumerate(names)]
            header = self.source("")
            first_line = header.count("\n") + 1
            proc = self.run(["-S", "-emit-llvm", "-o", "-", "-w", "-ferror-limit=0", "-Wno-error"], header + "\n".join(lines) + "\n")
            if proc.returncode == 0:
                for m in re.finditer(r"^@p8probe_(\d+) = (?:[a-z_]+ )*constant (i\d+|double) ([^,\s]+)", proc.stdout, re.M):
                    name = names[int(m.group(1))]
                    result[name] = parse_ir_constant(m.group(2), m.group(3))
                return result
            bad = {int(m.group(1)) - first_line for m in re.finditer(r"\.c:(\d+):\d+: error", proc.stderr)}
            bad = {i for i in bad if 0 <= i < len(names)}
            if not bad:
                sys.exit(f"clang failed on a probe file:\n{proc.stderr[:3000]}")
            names = [n for i, n in enumerate(names) if i not in bad]
        return result


def parse_ir_constant(irtype: str, text: str):
    if irtype == "double":
        if text.startswith(("0x", "f0x")):
            return struct.unpack(">d", bytes.fromhex(text.split("x", 1)[1].rjust(16, "0")))[0]
        if text.lstrip("+-") in ("qnan", "snan", "inf"):
            return float(text.replace("qnan", "nan").replace("snan", "nan"))
        return float(text)
    if text in ("true", "false"):
        return int(text == "true")
    if text in ("zeroinitializer", "null"):
        return 0
    return int(text)


# ---------------------------------------------------------------------------
# the clang AST
# ---------------------------------------------------------------------------

@dataclass
class Record:
    kind: str                       # struct or union
    tag: str | None
    node: dict
    file: str
    line: int
    col: int
    complete: bool
    typedef: str | None = None      # a typedef name that refers to it


@dataclass
class Function:
    name: str
    node: dict
    file: str


@dataclass
class Unit:
    typedefs: dict = field(default_factory=dict)      # name -> qualType
    sugar: dict = field(default_factory=dict)         # type name without a typedef, such as __size_t -> its desugared type
    records: dict = field(default_factory=dict)       # 'struct tag' or (file, line, col) -> Record
    functions: dict = field(default_factory=dict)
    enum_constants: list = field(default_factory=list)  # (name, enum name, file)


class LocationTracker:
    """clang's JSON dump leaves out the file of a location that is the same as the one printed before it."""

    def __init__(self):
        self.file = None

    def bare(self, loc):
        if loc and "file" in loc:
            self.file = loc["file"]

    def loc(self, loc):
        if not loc:
            return
        if "spellingLoc" in loc:
            self.bare(loc["spellingLoc"])
            self.bare(loc["expansionLoc"])
        else:
            self.bare(loc)

    def node(self, node) -> str | None:
        self.loc(node.get("loc"))
        file = self.file
        rng = node.get("range")
        if rng:
            self.loc(rng.get("begin"))
            self.loc(rng.get("end"))
        return file


def parse_unit(ast: dict) -> Unit:
    unit = Unit()
    tracker = LocationTracker()
    last_line = [0]

    def walk(node, parent_kind=None):
        file = tracker.node(node)
        loc = node.get("loc", {})
        loc = loc.get("expansionLoc", loc)
        if "line" in loc:
            last_line[0] = loc["line"]
        line, col = last_line[0], loc.get("col", 0)
        kind = node.get("kind")
        t = node.get("type")
        if isinstance(t, dict) and "desugaredQualType" in t and re.fullmatch(r"[A-Za-z_]\w*", t.get("qualType", "")):
            unit.sugar.setdefault(t["qualType"], t["desugaredQualType"])
        if kind == "RecordDecl":
            tag = node.get("name")
            rec = Record(node.get("tagUsed", "struct"), tag, node, file, line, col, bool(node.get("completeDefinition")))
            key = f"{rec.kind} {tag}" if tag else (file, line, col)
            if rec.complete or key not in unit.records:
                old = unit.records.get(key)
                if old:
                    rec.typedef = old.typedef
                unit.records[key] = rec
        elif kind == "TypedefDecl" and parent_kind == "TranslationUnitDecl":
            unit.typedefs[node["name"]] = node["type"]["qualType"]
        elif kind == "FunctionDecl" and parent_kind == "TranslationUnitDecl":
            name = node["name"]
            if name not in unit.functions or "inner" in node:
                unit.functions[name] = Function(name, node, file)
        elif kind == "EnumDecl":
            enum_name = node.get("name")
            for c in node.get("inner", []):
                if c.get("kind") == "EnumConstantDecl":
                    unit.enum_constants.append((c["name"], enum_name, file))
        for child in node.get("inner", []):
            walk(child, kind)

    walk(ast)
    return unit


# ---------------------------------------------------------------------------
# C types
# ---------------------------------------------------------------------------

@dataclass
class CType:
    kind: str                       # int, bool, real, void, ptr, array, record, func, unknown
    spelling: str = ""              # a spelling that sizeof() accepts, for int and real
    pointee: "CType | None" = None
    count: int = 0
    record: Record | None = None


def strip_type(qt: str) -> str:
    qt = QUALIFIERS.sub("", qt)
    qt = re.sub(r"__attribute__\(\(.*?\)\)", "", qt)
    return re.sub(r"\s+", " ", qt).replace(" *", "*").replace("* ", "*").strip()


class TypeResolver:
    def __init__(self, unit: Unit, clang: Clang):
        self.unit = unit
        self.clang = clang
        self.builtin_kinds: dict[str, int | None] = {}

    def builtin(self, name: str) -> CType:
        """A type name that clang knows without a typedef in the AST, such as the __size_t of clang 22 and later."""
        if name not in self.builtin_kinds:
            self.builtin_kinds[name] = self.clang.evaluate({name: f"__builtin_classify_type(*({name}*)0)"}, "int").get(name)
        kind = self.builtin_kinds[name]
        if kind in (1, 2, 3):
            return CType("int", spelling=name)
        if kind == 4:
            return CType("bool")
        if kind == 8:
            return CType("real", spelling=name)
        return CType("unknown")

    def record_for(self, text: str) -> Record | None:
        m = UNNAMED.search(text)
        if m:
            return self.unit.records.get((m.group(1), int(m.group(2)), int(m.group(3))))
        return self.unit.records.get(text)

    def resolve(self, qt: str, depth: int = 0) -> CType:
        t = strip_type(qt)
        if depth > 40:
            return CType("unknown")
        if "(*" in t or "(^" in t:
            return CType("ptr", pointee=CType("func"))
        if t.endswith(")"):
            return CType("func")
        m = re.fullmatch(r"(.*?)\[(\d*)\]((?:\[\d*\])*)", t)
        if m:
            inner = m.group(1) + m.group(3)
            return CType("array", pointee=self.resolve(inner, depth + 1), count=int(m.group(2) or 0))
        if t.endswith("*"):
            return CType("ptr", pointee=self.resolve(t[:-1], depth + 1))
        if t in VA_LIST:
            return CType("unknown")
        if t == "void":
            return CType("void")
        if t in ("_Bool", "bool"):
            return CType("bool")
        if t in ("float", "double", "long double"):
            return CType("real", spelling=t)
        if re.fullmatch(r"(signed |unsigned )?(char|short|int|long|long long|short int|long int|long long int|__int128)|signed|unsigned|__int128_t|__uint128_t", t):
            return CType("int", spelling=t)
        if t.startswith("enum "):
            return CType("int", spelling=t if not UNNAMED.search(t) else "int")
        if t.startswith(("struct ", "union ")):
            rec = self.record_for(t)
            return CType("record", record=rec) if rec else CType("unknown")
        if t in self.unit.typedefs:
            target = self.resolve(self.unit.typedefs[t], depth + 1)
            if target.kind in ("int", "real"):
                target.spelling = t
            if target.kind == "record" and target.record and not target.record.typedef:
                target.record.typedef = t
            return target
        if t in self.unit.sugar:
            return self.resolve(self.unit.sugar[t], depth + 1)
        if re.fullmatch(r"[A-Za-z_]\w*", t):
            return self.builtin(t)
        return CType("unknown")


# ---------------------------------------------------------------------------
# generating the Prog8 module
# ---------------------------------------------------------------------------

class Generator:
    def __init__(self, unit: Unit, clang: Clang, in_scope):
        self.unit = unit
        self.clang = clang
        self.in_scope = in_scope
        self.types = TypeResolver(unit, clang)
        self.names: set[str] = set()
        self.int_info: dict[str, tuple[int, bool]] = {}
        self.record_names: dict[int, str] = {}
        self.wanted_records: list[Record] = []
        self.skipped: dict[str, list[str]] = {}

    def skip(self, reason: str, name: str):
        self.skipped.setdefault(reason, []).append(name)

    # -- names

    def claim(self, name: str) -> str | None:
        if name in self.names:
            return None
        self.names.add(name)
        return name

    @staticmethod
    def local_name(name: str | None, index: int, taken: set[str], fallback: str) -> str:
        n = (name or "").lstrip("_")
        if not n or n in RESERVED or n in taken:
            n = f"{n}_" if n and n not in taken and f"{n}_" not in taken else f"{fallback}{index}"
        taken.add(n)
        return n

    # -- integer types

    def probe_int_types(self, spellings: set[str]):
        todo = sorted(s for s in spellings if s not in self.int_info)
        if not todo:
            return
        sizes = self.clang.evaluate({s: f"sizeof({s})" for s in todo})
        signed = self.clang.evaluate({s: f"({s})-1 < 0" for s in todo})
        for s in todo:
            if s in sizes:
                self.int_info[s] = (sizes[s], bool(signed.get(s, 1)))

    def collect_spellings(self, t: CType, out: set[str]):
        if t.kind in ("int", "real"):
            out.add(t.spelling)
        elif t.kind in ("ptr", "array") and t.pointee:
            self.collect_spellings(t.pointee, out)

    # -- mapping

    def scalar(self, t: CType) -> str | None:
        if t.kind == "bool":
            return "bool"
        if t.kind == "int":
            info = self.int_info.get(t.spelling)
            return INT_TYPE.get(info) if info else None
        if t.kind == "real":
            info = self.int_info.get(t.spelling)
            return "float" if info and info[0] == 8 else None
        if t.kind == "ptr":
            return self.pointer(t.pointee)
        return None

    def pointer(self, pointee: CType) -> str:
        if pointee.kind == "int" and pointee.spelling in ("char", "signed char", "unsigned char"):
            return "str" if pointee.spelling == "char" else "^^ubyte"
        if pointee.kind == "record" and pointee.record:
            name = self.want_record(pointee.record)
            if name:
                return f"^^{name}"
        if pointee.kind in ("int", "bool", "real"):
            s = self.scalar(pointee)
            if s:
                return f"^^{s}"
        return "pointer"

    def want_record(self, rec: Record) -> str | None:
        if not rec.complete:
            return None
        key = id(rec.node)
        if key not in self.record_names:
            base = (rec.typedef or rec.tag or "").lstrip("_")
            if not base:
                return None
            name = base if base not in self.names and base not in RESERVED else f"{base}_{rec.kind}"
            if name in self.names:
                return None
            self.names.add(name)
            self.record_names[key] = name
            self.wanted_records.append(rec)
        return self.record_names[key]

    # -- functions

    def function(self, fn: Function) -> str | None:
        node = fn.node
        name = fn.name
        if node.get("storageClass") == "static" or node.get("inline"):
            return self.skip("inline or static", name)
        if name.startswith("_"):
            return self.skip("name starts with an underscore", name)
        if node.get("isImplicit"):
            return None
        if name in RESERVED:
            return self.skip("name is a Prog8 keyword or builtin function", name)
        if node.get("variadic"):
            return self.skip("variadic", name)
        qt = node["type"]["qualType"]
        ret_qt = qt[:qt.index("(")].strip() if "(" in qt else qt
        params = [c for c in node.get("inner", []) if c.get("kind") == "ParmVarDecl"]
        ret = self.types.resolve(ret_qt)
        ptypes = [self.types.resolve(p["type"]["qualType"]) for p in params]
        spellings = set()
        for t in [ret, *ptypes]:
            self.collect_spellings(t, spellings)
        self.probe_int_types(spellings)
        taken: set[str] = set()
        args = []
        for i, (p, t) in enumerate(zip(params, ptypes), 1):
            if t.kind == "array":
                t = CType("ptr", pointee=t.pointee)
            s = self.scalar(t)
            if not s:
                return self.skip(f"parameter type {strip_type(p['type']['qualType'])}", name)
            args.append(f"{s} {self.local_name(p.get('name'), i, taken, 'arg')}")
        result = ""
        if ret.kind != "void":
            s = self.scalar(ret)
            if not s:
                return self.skip(f"return type {strip_type(ret_qt)}", name)
            result = f" -> {s}"
        if not self.claim(name):
            return self.skip("name already used", name)
        asm_label = next((c.get("label") for c in node.get("inner", []) if c.get("kind") == "AsmLabelAttr"), None)
        note = f"    ; C symbol {asm_label}" if asm_label else ""
        return f"extsub {name}({', '.join(args)}){result}{note}"

    # -- records

    def leaf_fields(self, rec: Record, path: str, prefix: str, out: list, skipped: list):
        inner = rec.node.get("inner", [])
        for i, f in enumerate(inner):
            if f.get("kind") != "FieldDecl":
                continue
            fname = f.get("name")
            if f.get("isBitfield"):
                skipped.append(f"{prefix}{fname} (bitfield)")
                continue
            t = self.types.resolve(f["type"]["qualType"])
            if not fname:
                if t.kind == "record" and t.record and t.record.kind == "struct" and rec.kind == "struct":
                    self.leaf_fields(t.record, path, prefix, out, skipped)
                continue
            member = f"{path}{fname}"
            label = f"{prefix}{fname.lstrip('_')}"
            if t.kind == "record" and t.record and t.record.kind == "struct" and rec.kind == "struct":
                self.leaf_fields(t.record, member + ".", label + "_", out, skipped)
            elif t.kind == "record" or (t.kind == "array" and t.pointee and t.pointee.kind not in ("int", "bool", "real", "ptr")) or \
                    (t.kind == "array" and t.pointee and t.pointee.kind == "array"):
                out.append((member, label, "blob", None, t))
            elif t.kind == "array":
                out.append((member, label, "array", t.pointee, t))
            else:
                out.append((member, label, "scalar", t, t))

    def record(self, rec: Record) -> list[str]:
        name = self.record_names[id(rec.node)]
        nameable = f"{rec.kind} {rec.tag}" if rec.tag else rec.typedef
        fields, skipped = [], []
        self.leaf_fields(rec, "", "", fields, skipped)
        spellings = set()
        for f in fields:
            self.collect_spellings(f[4], spellings)
        self.probe_int_types(spellings)
        exprs = {"__size": f"sizeof({nameable})"}
        for member, *_ in fields:
            exprs[member] = f"offsetof({nameable}, {member})"
            exprs["sizeof " + member] = f"sizeof((({nameable}*)0)->{member})"
        values = self.clang.evaluate(exprs)
        size = values.get("__size")
        if size is None:
            self.skip("struct size unknown", name)
            return []
        lines = [f"{rec.kind} {name} {{"]
        taken: set[str] = set()
        pos, pad = 0, 0
        for member, label, how, t, full in sorted((f for f in fields if f[0] in values), key=lambda f: values[f[0]]):
            offset, fsize = values[member], values.get("sizeof " + member, 0)
            if rec.kind == "struct":
                if offset < pos:
                    skipped.append(f"{label} (overlaps)")
                    continue
                if offset > pos:
                    pad += 1
                    lines.append(f"    ubyte[{offset - pos}] {self.local_name(f'pad{pad}', pad, taken, 'pad')}")
            decl = None
            if how == "scalar":
                s = self.scalar(t)
                if s and (s != "float" or fsize == 8):
                    decl = s
            elif how == "array" and full.count:
                s = self.scalar(t) if t.kind != "ptr" else "pointer"
                if t.kind == "int" and t.spelling == "char":
                    s = "ubyte"
                if s and s not in ("str", "float") and not s.startswith("^^"):
                    decl = f"{s}[{full.count}]"
            if decl is None:
                decl = f"ubyte[{fsize}]" if fsize else None
            if decl is None:
                skipped.append(label)
                continue
            lines.append(f"    {decl} {self.local_name(label, len(taken), taken, 'field')}")
            if rec.kind == "struct":
                pos = offset + fsize
        if rec.kind == "struct" and pos < size:
            pad += 1
            lines.append(f"    ubyte[{size - pos}] {self.local_name(f'pad{pad}', pad, taken, 'pad')}")
        if len(lines) == 1:
            lines.append(f"    ubyte[{max(size, 1)}] {'opaque'}")
        if skipped:
            lines.insert(1, f"    ; not mapped, padded instead: {', '.join(skipped)}")
        lines.append("}")
        return lines

    # -- constants

    def constants(self, macros: list[tuple[str, str]]) -> list[str]:
        candidates = {}
        for name, _enum, file in self.unit.enum_constants:
            if self.in_scope(file) and not name.startswith("_"):
                candidates.setdefault(name, name)
        for name, file in macros:
            if self.in_scope(file) and not name.startswith("_"):
                candidates.setdefault(name, name)
        classes = self.clang.evaluate({n: f"__builtin_classify_type({n})" for n in candidates}, "int")
        ints = [n for n in candidates if classes.get(n) in (1, 2, 3, 4)]
        reals = [n for n in candidates if classes.get(n) == 8]
        ivalues = self.clang.evaluate({n: n for n in ints})
        unsigned = self.clang.evaluate({n: f"({n}) > 0 && ({n}) > 0x7fffffffffffffffLL" for n in ints}, "int")
        rvalues = self.clang.evaluate({n: n for n in reals}, "double")
        lines = []
        for n in candidates:
            if n in RESERVED:
                continue
            if n in ivalues:
                v = ivalues[n]
                if unsigned.get(n):
                    v &= 0xFFFF_FFFF_FFFF_FFFF
                if self.claim(n):
                    lines.append(f"const {const_type(v)} {n} = {v}")
            elif n in rvalues:
                v = rvalues[n]
                if v == v and abs(v) != float("inf") and self.claim(n):
                    lines.append(f"const float {n} = {v!r}")
        return lines


def const_type(v: int) -> str:
    if 0 <= v <= 255:
        return "ubyte"
    if -128 <= v < 0:
        return "byte"
    if 0 <= v <= 65535:
        return "uword"
    if -32768 <= v < 0:
        return "word"
    if -2**31 <= v < 2**31:
        return "long"
    if -2**63 <= v < 2**63:
        return "quad"
    return "uquad"


def read_macros(clang: Clang) -> tuple[list[tuple[str, str]], str | None]:
    """The object-like macros with the file that defines them, and the file of the first included header."""
    proc = clang.run(["-E", "-dD"], clang.source(""))
    if proc.returncode != 0:
        sys.exit(f"clang can't preprocess the header:\n{proc.stderr[:3000]}")
    macros, current, main_file, header = [], None, None, None
    for line in proc.stdout.splitlines():
        m = re.match(r'# \d+ "(.*)"( \d+)*', line)
        if m:
            # a line marker escapes the backslashes of a Windows path, which the JSON AST doesn't
            path = re.sub(r"\\(.)", r"\1", m.group(1))
            flags = line[m.end(1) + 1:].split()
            if main_file is None and not path.startswith("<"):
                main_file = path
            elif header is None and "1" in flags and current == main_file and not path.startswith("<"):
                header = path
            current = path
            continue
        m = re.match(r"#define (\w+)(?!\()(?:\s+(.*))?$", line)
        if m and m.group(2):
            macros.append((m.group(1), current))
    return macros, header


def pkg_config(packages: list[str]) -> tuple[list[str], list[str], list[str]]:
    """The compile flags, the libraries and the library search directories of the pkg-config packages."""
    if not packages:
        return [], [], []
    cflags = subprocess.run(["pkg-config", "--cflags", *packages], capture_output=True, text=True)
    if cflags.returncode != 0:
        sys.exit(f"pkg-config failed: {cflags.stderr.strip()}")
    libs = shlex.split(subprocess.run(["pkg-config", "--libs", *packages], capture_output=True, text=True).stdout)
    return shlex.split(cflags.stdout), [a[2:] for a in libs if a.startswith("-l")], [a for a in libs if a.startswith("-L")]


def main():
    ap = argparse.ArgumentParser(description="Generate Prog8 extsub, struct and const bindings from a C header, for the llvm targets.")
    ap.add_argument("header", nargs="+", help="header as it would be included, such as stdio.h or SDL2/SDL.h")
    ap.add_argument("-o", "--output", help="output .p8 file (default: stdout)")
    ap.add_argument("--block", help="block name (default: c_ plus the header name)")
    ap.add_argument("--triple", default=None, help="triple profile name or .properties file (default: the llvm64 profile of this machine)")
    ap.add_argument("--from", dest="from_files", action="append", default=[], metavar="REGEX",
                    help="also take declarations from the headers whose path matches this regex")
    ap.add_argument("--pkg-config", action="append", default=[], metavar="PACKAGE",
                    help="add the compile flags of this pkg-config package, and %%link its libraries")
    ap.add_argument("--link", action="append", default=[], metavar="LIB", help="add a %%link directive for this library")
    ap.add_argument("-I", dest="include_dirs", action="append", default=[], help="extra include directory")
    ap.add_argument("-D", dest="defines", action="append", default=[], help="extra preprocessor define")
    ap.add_argument("--cflag", action="append", default=[], help="extra clang argument")
    ap.add_argument("--clang", default=os.environ.get("CLANG", "clang"), help="the clang to use (default: $CLANG or clang)")
    ap.add_argument("--list-files", action="store_true", help="list the headers that get included, marking those whose declarations are taken (*)")
    ap.add_argument("--no-consts", action="store_true", help="leave out the enum and #define constants")
    ap.add_argument("--no-structs", action="store_true", help="leave out struct and union definitions, using untyped pointers")
    opts = ap.parse_args()

    triple = opts.triple or host_triple()
    profile = read_profile(triple)
    pkg_cflags, pkg_libs, pkg_libdirs = pkg_config(opts.pkg_config)
    # expanded after splitting, so that the backslashes of a Windows path in a variable stay
    clang_args = [a for a in map(expand_env, shlex.split(profile.get("clang_args", ""))) if not a.startswith(("-Wl,", "-l", "-L", "-fuse-ld"))]
    args = clang_args + pkg_cflags + [f"-I{d}" for d in opts.include_dirs] + [f"-D{d}" for d in opts.defines] + opts.cflag
    clang = Clang(opts.clang, args, opts.header)

    macros, header_file = read_macros(clang)
    if header_file is None:
        sys.exit("can't tell which file the header is")
    header_dir = os.path.dirname(header_file)
    stem = Path(header_file).stem.lower()
    whole_dir = any("/" in h for h in opts.header)
    from_res = [re.compile(r) for r in opts.from_files]

    def in_scope(file: str | None) -> bool:
        if not file:
            return False
        if file == header_file or any(r.search(file) for r in from_res):
            return True
        if whole_dir:
            return os.path.dirname(file) == header_dir
        return file.startswith(os.path.join(header_dir, "")) and stem in Path(file).name.lower()

    proc = clang.run(["-Xclang", "-ast-dump=json", "-fsyntax-only", "-w"], clang.source(""))
    if proc.returncode != 0:
        sys.exit(f"clang can't parse the header:\n{proc.stderr[:3000]}")
    unit = parse_unit(json.loads(proc.stdout))
    if opts.list_files:
        counts = {}
        for fn in unit.functions.values():
            counts[fn.file] = counts.get(fn.file, 0) + 1
        for _name, file in macros:
            counts.setdefault(file, 0)
        for file in sorted(f for f in counts if f and not f.startswith("<")):
            print(f"{'*' if in_scope(file) else ' '} {counts[file]:5} functions  {file}")
        return

    gen = Generator(unit, clang, in_scope)
    if opts.no_structs:
        gen.want_record = lambda rec: None
    consts = [] if opts.no_consts else gen.constants(macros)
    extsubs = [line for fn in unit.functions.values() if in_scope(fn.file) and (line := gen.function(fn))]
    structs = []
    done = 0
    while done < len(gen.wanted_records):
        structs.append(gen.record(gen.wanted_records[done]))
        done += 1

    block = opts.block or "c_" + re.sub(r"\W", "_", Path(opts.header[0]).stem.lower())
    version = subprocess.run([opts.clang, "--version"], capture_output=True, text=True).stdout.splitlines()[0]
    out = [f"; Prog8 bindings for {', '.join(opts.header)} on the {profile.get('triple', triple)} triple, generated by scripts/c-header-to-prog8.py",
           f"; with {version}. The types and sizes only apply to that triple.",
           f"; {' '.join(shlex.quote(a) for a in ['c-header-to-prog8.py', *sys.argv[1:]])}"]
    if pkg_libdirs:
        out.append(f"; build with {' '.join('-Xlink ' + shlex.quote(d) for d in pkg_libdirs)} to find the libraries")
    out.append("")
    for lib in [*pkg_libs, *opts.link]:
        out.append(f'%link "{lib}"')
    if pkg_libs or opts.link:
        out.append("")
    out.append(f"{block} {{")
    for c in consts:
        out.append(f"    {c}")
    for s in structs:
        out.append("")
        out.extend(f"    {line}" for line in s)
    if extsubs:
        out.append("")
    for e in extsubs:
        out.append(f"    {e}")
    if gen.skipped:
        out.append("")
        for reason, names in sorted(gen.skipped.items()):
            line = f"    ; skipped, {reason}:"
            for n in names:
                if len(line) + len(n) > 118:
                    out.append(line)
                    line = "    ;    "
                line += f" {n},"
            out.append(line.rstrip(","))
    out.append("}")
    text = "\n".join(out) + "\n"
    if opts.output:
        Path(opts.output).write_text(text)
        print(f"{opts.output}: {len(consts)} consts, {len(structs)} structs, {len(extsubs)} extsubs, "
              f"{sum(len(v) for v in gen.skipped.values())} skipped", file=sys.stderr)
    else:
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
