#!/usr/bin/env python3
"""Install a signed APK on an already booted emulator and verify first UI paint."""
from __future__ import annotations

import argparse
import subprocess
import time
from pathlib import Path


def run(adb: str, serial: str, *args: str) -> str:
    return subprocess.check_output([adb, "-s", serial, *args], text=True, stderr=subprocess.STDOUT)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--package", default="com.sibirskyspeak")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    if not args.apk.is_file() or args.apk.stat().st_size == 0:
        raise SystemExit(f"missing or empty APK: {args.apk}")
    run(args.adb, args.serial, "wait-for-device")
    run(args.adb, args.serial, "install", "-r", str(args.apk))
    run(args.adb, args.serial, "shell", "am", "force-stop", args.package)
    run(args.adb, args.serial, "shell", "monkey", "-p", args.package, "1")
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            run(args.adb, args.serial, "shell", "uiautomator", "dump", "/sdcard/sibirsky-release-ui.xml")
            xml = run(args.adb, args.serial, "shell", "cat", "/sdcard/sibirsky-release-ui.xml")
            if any(marker in xml for marker in ("SibirskySpeak", "Welcome", "Practice", "Settings")):
                print(f"release smoke passed: {args.apk}")
                return
        except subprocess.CalledProcessError:
            pass
        time.sleep(1)
    raise SystemExit("release smoke failed: app launched but no recognizable first screen rendered")


if __name__ == "__main__":
    main()
