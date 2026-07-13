"""Safe device resilience gate for an installable APK.

It never fills or deletes device storage. Instead it checks available space,
verifies APK asset CRCs, launches the package, and reports the exact device
state so a release job can refuse an unsafe low-storage run.
"""

from __future__ import annotations

import argparse
import subprocess
import time
import zipfile
from pathlib import Path


PACKAGE = "com.sibirskyspeak"
REQUIRED_ASSETS = ("assets/bootstrap_notes.jsonl", "assets/curriculum_contract.json")


def adb(serial: str | None, *args: str) -> str:
    command = ["adb"] + (["-s", serial] if serial else []) + list(args)
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--serial")
    parser.add_argument("--min-free-mb", type=int, default=256)
    args = parser.parse_args()
    if not args.apk.is_file():
        raise SystemExit(f"missing APK: {args.apk}")
    with zipfile.ZipFile(args.apk) as archive:
        bad = archive.testzip()
        if bad:
            raise SystemExit(f"APK CRC failure: {bad}")
        missing = [asset for asset in REQUIRED_ASSETS if asset not in archive.namelist()]
        if missing:
            raise SystemExit(f"APK missing required assets: {missing}")
    df = adb(args.serial, "shell", "df", "/data")
    rows = [line.split() for line in df.splitlines() if "/data" in line]
    if not rows or len(rows[-1]) < 4:
        raise SystemExit(f"could not parse device storage: {df}")
    free_kb = int(rows[-1][3])
    if free_kb < args.min_free_mb * 1024:
        raise SystemExit(f"device has only {free_kb // 1024} MiB free; need {args.min_free_mb} MiB")
    adb(args.serial, "install", "-r", str(args.apk))
    adb(args.serial, "shell", "monkey", "-p", PACKAGE, "1")
    deadline = time.time() + 20
    while time.time() < deadline:
        activities = adb(args.serial, "shell", "dumpsys", "activity", "activities")
        if f"{PACKAGE}/" in activities:
            print(f"device resilience passed: {free_kb // 1024} MiB free, APK assets CRC-valid")
            return 0
        time.sleep(0.5)
    raise SystemExit("package did not reach a foreground activity")


if __name__ == "__main__":
    raise SystemExit(main())
