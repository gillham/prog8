#!/usr/bin/env python3
"""
Runs a CP/M .com program in tnylpo for the compiler's tests: run-tnylpo.py <file> [args]. The program must be linked
with cpm-exit-marker.asm, whose last line PROG8-EXIT=<hex> is removed from the output and becomes the exit status,
as tnylpo's own is always 0. The current directory is drive A, and stdin is the console input.
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile

TIMEOUT_SECONDS = 50
MARKER = re.compile(rb"\nPROG8-EXIT=([0-9A-F]{4})\n\Z")


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: run-tnylpo.py <file.com> [args]", file=sys.stderr)
        return 2
    program, args = sys.argv[1], sys.argv[2:]
    tnylpo = shutil.which("tnylpo")
    if not tnylpo:
        print("run-tnylpo.py: tnylpo not found on the PATH", file=sys.stderr)
        return 2
    working_directory = os.getcwd()
    if '"' in working_directory:
        print("run-tnylpo.py: the current directory has a double quote in its path", file=sys.stderr)
        return 2
    # tnylpo only runs a file with a CP/M (8.3) name; the copy stays out of drive A, so the program can't see it
    with tempfile.TemporaryDirectory(prefix="tnylpo-") as directory:
        copy = os.path.join(directory, "prog.com")
        shutil.copyfile(program, copy)
        # a configuration of its own, so that a .tnylpo.conf in the current or home directory doesn't apply
        config = os.path.join(directory, "tnylpo.conf")
        with open(config, "w") as conf:
            conf.write(f'drive a = "{working_directory}"\n')
        try:
            result = subprocess.run([tnylpo, "-f", config, "-b", copy] + args, stdout=subprocess.PIPE, timeout=TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired as timeout:
            sys.stdout.buffer.write(timeout.stdout or b"")
            print(f"run-tnylpo.py: tnylpo did not exit within {TIMEOUT_SECONDS} seconds and was stopped", file=sys.stderr)
            return 124
    output = result.stdout
    if result.returncode != 0:
        sys.stdout.buffer.write(output)
        return result.returncode
    marker = MARKER.search(output)
    if marker is None:
        sys.stdout.buffer.write(output)
        print("run-tnylpo.py: the program's output doesn't end with the PROG8-EXIT line of cpm-exit-marker.asm", file=sys.stderr)
        return 125
    sys.stdout.buffer.write(output[:marker.start()])
    return int(marker.group(1), 16) & 0xFF


if __name__ == "__main__":
    sys.exit(main())
