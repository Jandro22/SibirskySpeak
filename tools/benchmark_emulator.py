#!/usr/bin/env python3
"""Small dependency-free cold-start benchmark for the review emulator gate."""
from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import time


def run(adb: str, serial: str, *args: str) -> str:
    return subprocess.check_output([adb, "-s", serial, *args], text=True, stderr=subprocess.STDOUT)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", default="com.sibirskyspeak")
    parser.add_argument("--activity", default="com.sibirskyspeak/.MainActivity")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--iterations", type=int, default=5)
    args = parser.parse_args()
    samples: list[int] = []
    for _ in range(max(1, args.iterations)):
        run(args.adb, args.serial, "shell", "am", "force-stop", args.package)
        started = time.perf_counter()
        run(args.adb, args.serial, "shell", "am", "start", "-W", "-n", f"{args.package}/{args.activity.split('/')[-1]}")
        samples.append(round((time.perf_counter() - started) * 1000))
    result = {
        "package": args.package,
        "iterations": len(samples),
        "samples_ms": samples,
        "median_ms": round(statistics.median(samples)),
        "max_ms": max(samples),
    }
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
