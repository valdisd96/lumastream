#!/usr/bin/env python3
"""Capture a projector PNG without firmware diagnostics preceding its signature."""

import argparse
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit authorized ADB device")
    parser.add_argument("--output", required=True, type=Path, help="Private PNG destination")
    args = parser.parse_args()
    result = subprocess.run(
        ["adb", "-s", args.serial, "exec-out", "screencap", "-p"],
        capture_output=True,
        check=True,
        timeout=30,
    )
    start = result.stdout.find(b"\x89PNG\r\n\x1a\n")
    if start < 0:
        raise RuntimeError("ADB returned no PNG; destination was not modified")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(result.stdout[start:])
    print(f"Saved {args.output} (removed {start} firmware prefix bytes)")


if __name__ == "__main__":
    main()
