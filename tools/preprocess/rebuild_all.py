# -*- coding: utf-8 -*-
"""Codify the correct rebuild order for the curriculum content pipeline:
1. Compile initial curriculum notes (including any new/edited hand-authored starter words).
2. Run wiktionary lexicon verification to generate/update verified lists.
3. Compile final assets to filter out unverified words, ensuring clean shipping files.
4. Report reader-text coverage gaps by known-vocabulary band (informational only —
   never fails the build, see reader_gap_report.py for the enforced regression test).

Usage:
    python tools/preprocess/rebuild_all.py
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent


def main() -> int:
    # Ensure commands are run from the repo root
    os.chdir(REPO_ROOT)

    print("=== Step 0/5: Generating GrammarConcepts.kt ===")
    try:
        subprocess.run(
            [sys.executable, "tools/preprocess/generate_concepts.py"],
            check=True,
        )
    except subprocess.CalledProcessError as e:
        print(f"Error during step 0: {e}", file=sys.stderr)
        return 1

    print("\n=== Step 1/5: Compiling curriculum notes (initial build) ===")
    try:
        subprocess.run(
            [sys.executable, "tools/preprocess/build_bootstrap.py"],
            check=True,
        )
    except subprocess.CalledProcessError as e:
        print(f"Error during step 1: {e}", file=sys.stderr)
        return 1

    print("\n=== Step 2/5: Running lexicon verification ===")
    try:
        subprocess.run(
            [sys.executable, "tools/preprocess/verify_lexicon.py"],
            check=True,
        )
    except subprocess.CalledProcessError as e:
        print(f"Error during step 2: {e}", file=sys.stderr)
        return 1

    print("\n=== Step 3/5: Re-compiling curriculum notes (final build) ===")
    try:
        subprocess.run(
            [sys.executable, "tools/preprocess/build_bootstrap.py"],
            check=True,
        )
    except subprocess.CalledProcessError as e:
        print(f"Error during step 3: {e}", file=sys.stderr)
        return 1

    print("\n=== Step 4/5: Reader coverage gap report (informational, non-blocking) ===")
    subprocess.run(
        [sys.executable, "tools/preprocess/reader_gap_report.py"],
        check=False,
    )

    print("\n=== Rebuild All completed successfully! ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
