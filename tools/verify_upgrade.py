"""Install an old APK, upgrade it in place, and smoke the upgraded app.

The release workflow can pass the previous public APK when it is available;
local runs may use any signed APK with the same application id. No learner data
is cleared, so a Room migration failure is observable instead of hidden by a
clean install.
"""

from __future__ import annotations

import argparse
import subprocess
import time
from pathlib import Path


PACKAGE = "com.sibirskyspeak"


def run(*args: str) -> str:
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("old_apk", type=Path)
    parser.add_argument("new_apk", type=Path)
    parser.add_argument("--serial", default=None)
    args = parser.parse_args()
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    run(*adb, "install", "-r", str(args.old_apk))
    run(*adb, "shell", "am", "force-stop", PACKAGE)
    run(*adb, "install", "-r", str(args.new_apk))
    run(*adb, "shell", "monkey", "-p", PACKAGE, "1")
    deadline = time.time() + 20
    while time.time() < deadline:
        activity = run(*adb, "shell", "dumpsys", "activity", "activities")
        if f"{PACKAGE}/" in activity:
            print(f"upgrade smoke passed: {args.old_apk} -> {args.new_apk}")
            return 0
        time.sleep(0.5)
    raise SystemExit("upgraded app did not reach a foreground activity")


if __name__ == "__main__":
    raise SystemExit(main())
