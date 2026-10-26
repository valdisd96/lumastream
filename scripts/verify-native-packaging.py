#!/usr/bin/env python3
"""Verify universal ARM APK contents and 16 KB native library alignment."""

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from zipfile import BadZipFile, ZipFile


SUPPORTED_ABIS = {"armeabi-v7a", "arm64-v8a"}
REQUIRED_LIBRARIES = {"libtorrent4j.so", "liblumastream_storage.so"}
PAGE_SIZE = 16 * 1024


def verify(apk, readelf, zipalign):
    with ZipFile(apk) as archive:
        entries = [
            entry for entry in archive.infolist()
            if entry.filename.startswith("lib/") and not entry.is_dir()
        ]
        abis = {entry.filename.split("/")[1] for entry in entries}
        if abis != SUPPORTED_ABIS:
            raise ValueError(
                f"Expected ABIs {sorted(SUPPORTED_ABIS)}, found {sorted(abis)}"
            )
        names = {entry.filename for entry in entries}
        for abi in sorted(SUPPORTED_ABIS):
            missing = {
                f"lib/{abi}/{library}" for library in REQUIRED_LIBRARIES
            } - names
            if missing:
                raise ValueError(f"Missing native libraries: {', '.join(sorted(missing))}")

        scratch = Path(__file__).resolve().parent.parent / ".local"
        scratch.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="native-packaging-", dir=scratch) as directory:
            library = Path(directory) / "library.so"
            for entry in entries:
                if not entry.filename.startswith("lib/arm64-v8a/") or not entry.filename.endswith(".so"):
                    continue
                with archive.open(entry) as source, library.open("wb") as target:
                    shutil.copyfileobj(source, target)
                result = subprocess.run(
                    [str(readelf), "--program-headers", "--wide", str(library)],
                    check=True, capture_output=True, text=True,
                )
                alignments = [
                    int(fields[-1], 0)
                    for line in result.stdout.splitlines()
                    if (fields := line.split()) and fields[0] == "LOAD"
                ]
                if not alignments:
                    raise ValueError(f"{entry.filename}: no ELF LOAD segments found")
                if any(alignment < PAGE_SIZE for alignment in alignments):
                    raise ValueError(
                        f"{entry.filename}: ELF LOAD alignment below 16 KB: "
                        f"{', '.join(hex(alignment) for alignment in alignments)}"
                    )
                print(f"{entry.filename}: all {len(alignments)} ELF LOAD segments align >=16 KB")

    subprocess.run([str(zipalign), "-c", "-P", "16", "4", str(apk)], check=True)
    print(f"Verified {apk}: ARMv7 + ARM64 libraries, 16 KB ELF and APK alignment")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="APK to verify")
    parser.add_argument("--readelf", required=True, type=Path, help="SDK NDK llvm-readelf executable")
    parser.add_argument("--zipalign", required=True, type=Path, help="SDK build-tools zipalign executable")
    args = parser.parse_args()
    try:
        verify(args.apk, args.readelf, args.zipalign)
    except (OSError, BadZipFile, ValueError, subprocess.CalledProcessError) as error:
        print(f"Native packaging verification failed: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            print(error.stderr, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
