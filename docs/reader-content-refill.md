# Reader content refill procedure

Use this when `python tools/preprocess/reader_gap_report.py --fail-on-gap` reports a gap.

1. Note the smallest flagged `known words <=` rank and the shortfall. Author at least the shortfall plus two texts, so one later edit cannot reopen the gap.
2. Export the exact authoring vocabulary with `python tools/preprocess/author_reader_refill.py vocab --max-rank RANK > target-vocabulary.txt`. The helper reads tier-0 notes from `bootstrap_notes.jsonl`, orders them by `generalFreqRank`, and uses rank values exactly as the gap report does. Do not commit the scratch export.
3. Add a JSON file under `tools/preprocess/stories/`. Copy the shape of an existing file, but set honest `targetMaxRank`, `format`, and `topic` metadata. Chapters may override `format` and `topic`.
4. Plan diversity before drafting. A batch should use at least three formats, three topics/settings, and two non-romantic casts; no more than half should share a cast or narrative frame. Record named participants in an optional chapter-level `cast` array. Useful beginner formats include dialogue, instructions, lists, diary entries, notices, and Q&A. Vary questions, commands, fragments, coordination, and short/long sentences.
5. Keep each chapter at 40–300 Russian words. Prefer the exported vocabulary. Up to three words outside the full curriculum vocabulary may be glossed by the existing build rule, but a chapter must still reach 90% coverage at its authored `targetMaxRank`.
6. Iterate quickly: `python tools/preprocess/author_reader_refill.py validate tools/preprocess/stories/YOUR_FILE.json`. This directly calls `build_stories.validate_series` and `unknown_words`, then checks every chapter with the gap report's coverage implementation. Fix every failure before rebuilding.
7. Run `python tools/preprocess/build_stories.py` only when appending to an already-built asset during isolated iteration. The canonical path is `python tools/preprocess/rebuild_all.py`; `build_bootstrap.py` recreates the asset and then calls `build_stories.py`, avoiding duplicate titles.
8. Confirm closure with `python tools/preprocess/reader_gap_report.py --fail-on-gap`, then run `python -m pytest -q tools/preprocess`.

Static metadata records authoring intent in `bootstrap_reader_texts.jsonl`; it is deliberately not used by `LearningRepository.coverageFor()`. Live eligibility must remain based on the individual learner's known words. Tooling can compare the declared rank with measured coverage without a database migration.
