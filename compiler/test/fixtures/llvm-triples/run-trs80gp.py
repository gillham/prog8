#!/usr/bin/env python3
"""
Runs a TRS-80 .cmd program in trs80gp for the compiler's tests: run-trs80gp.py [trs80gp options...] <file> [args].
The program must be linked with trs80gp-output.c and trs80gp-exit.asm. Its console output, which those mirror to the
printer, is printed on stdout, and the exit status is the program's. trs80gp is found through the TRS80GP environment
variable (the path of the binary), else on the PATH. It opens a window for each run.
"""

import os
import shutil
import subprocess
import sys
import tempfile

TIMEOUT_SECONDS = 50


def main() -> int:
    words = sys.argv[1:]
    file_index = next((i for i, word in enumerate(words) if not word.startswith("-") and os.path.isfile(word)), None)
    if file_index is None:
        print("usage: run-trs80gp.py [trs80gp options...] <file.cmd>", file=sys.stderr)
        return 2
    options, program, args = words[:file_index], words[file_index], words[file_index + 1:]
    if args:
        print("run-trs80gp.py: trs80gp can't pass command line arguments to a program it loads", file=sys.stderr)
        return 2
    trs80gp = os.environ.get("TRS80GP") or shutil.which("trs80gp")
    if not trs80gp:
        print("run-trs80gp.py: trs80gp not found: set TRS80GP to its path, or put it on the PATH", file=sys.stderr)
        return 2
    with tempfile.TemporaryDirectory(prefix="trs80gp-") as directory:
        # the DOS gets the program under this name
        shutil.copyfile(program, os.path.join(directory, "prog.cmd"))
        # the Model I TRSDOS boot disk is write-protected; changes to trs80gp's own disk images are never saved
        command = [trs80gp, "-ee", "-batch", "-turbo", "-im", "wp", "0", "off"] + options + ["prog.cmd"]
        try:
            status = subprocess.run(command, cwd=directory, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL, timeout=TIMEOUT_SECONDS).returncode
        except subprocess.TimeoutExpired:
            status = None
        printer = os.path.join(directory, "trs80-printer.txt")
        if os.path.isfile(printer):
            with open(printer, "rb") as output:
                sys.stdout.buffer.write(output.read())
            sys.stdout.flush()
    if status is None:
        print(f"run-trs80gp.py: trs80gp did not exit within {TIMEOUT_SECONDS} seconds and was stopped", file=sys.stderr)
        return 124
    return status


if __name__ == "__main__":
    sys.exit(main())
