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


def run_step(step: int, total: int, description: str, script: str) -> int | None:
    """Run one pipeline script, printing its banner. Returns an exit code on
    failure, or None on success, so main() can just `return` a non-None result."""
    print(f"\n=== Step {step}/{total}: {description} ===")
    try:
        subprocess.run([sys.executable, f"tools/preprocess/{script}"], check=True)
    except subprocess.CalledProcessError as e:
        print(f"Error during step {step}: {e}", file=sys.stderr)
        return 1
    return None


def main() -> int:
    # Ensure commands are run from the repo root
    os.chdir(REPO_ROOT)

    steps = [
        ("Generating GrammarConcepts.kt", "generate_concepts.py"),
        ("Compiling curriculum notes (initial build)", "build_bootstrap.py"),
        ("Running lexicon verification", "verify_lexicon.py"),
        ("Re-compiling curriculum notes (final build)", "build_bootstrap.py"),
        ("Rebuilding the graded sentence bank", "build_sentence_bank.py"),
        ("Refreshing curriculum metadata from the graded sentence bank", "build_curriculum_metadata.py"),
        ("Rebuilding the complete unit-dialogue database", "build_dialogues.py"),
    ]
    total_steps = len(steps) + 1  # + the informational reader-gap report below
    for step, (description, script) in enumerate(steps):
        failure = run_step(step, total_steps, description, script)
        if failure is not None:
            return failure

    print(f"\n=== Step {len(steps)}/{total_steps}: Reader coverage gap report (informational, non-blocking) ===")
    subprocess.run(
        [sys.executable, "tools/preprocess/reader_gap_report.py"],
        check=False,
    )

    print("\n=== Rebuild All completed successfully! ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
