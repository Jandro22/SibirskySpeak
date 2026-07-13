#!/usr/bin/env python3
"""Capture deterministic screenshots for the primary bottom-navigation states."""
from __future__ import annotations

import argparse
import re
import subprocess
import time
from pathlib import Path
import xml.etree.ElementTree as ET


def run(adb: str, serial: str, *args: str, binary: bool = False):
    output = subprocess.check_output([adb, "-s", serial, *args], stderr=subprocess.STDOUT)
    return output if binary else output.decode("utf-8", errors="replace")


def bounds_for(xml: str, resource_id: str) -> tuple[int, int] | None:
    root = ET.fromstring(xml)
    for node in root.iter("node"):
        if node.attrib.get("resource-id") != resource_id:
            continue
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
        if match:
            left, top, right, bottom = map(int, match.groups())
            return (left + right) // 2, (top + bottom) // 2
    return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--package", default="com.sibirskyspeak")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    for state in ("nav_practice", "nav_progress", "nav_lab", "nav_settings"):
        run(args.adb, args.serial, "shell", "uiautomator", "dump", "/sdcard/sibirsky-ui.xml")
        xml = run(args.adb, args.serial, "shell", "cat", "/sdcard/sibirsky-ui.xml")
        point = bounds_for(xml, state)
        if point is None:
            raise SystemExit(f"could not locate {state} in UI tree")
        run(args.adb, args.serial, "shell", "input", "tap", str(point[0]), str(point[1]))
        time.sleep(1.0)
        target = args.output / f"{state}.png"
        target.write_bytes(run(args.adb, args.serial, "exec-out", "screencap", "-p", binary=True))
    print(f"captured {4} UI states in {args.output}")


if __name__ == "__main__":
    main()
