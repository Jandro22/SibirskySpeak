# SibirskySpeak Preprocessing

One-time desktop tools for producing the JSON Lines files imported by the Android app. The app stays offline; these scripts run on a PC when you want to refresh the corpus or card data.

## Pipeline

```powershell
python -m venv .venv
.\.venv\Scripts\pip install -r tools\preprocess\requirements.txt
.\.venv\Scripts\python tools\preprocess\sibirsky_preprocess.py rank-domain --input data\raw --output data\domain_frequency.tsv
.\.venv\Scripts\python tools\preprocess\sibirsky_preprocess.py build-notes --lexicon data\lexicon.jsonl --domain-frequency data\domain_frequency.tsv --output data\notes.jsonl
.\.venv\Scripts\python tools\preprocess\sibirsky_preprocess.py draft-aktionsart --verbs data\verbs.txt --output data\aktionsart_draft.jsonl
.\.venv\Scripts\python tools\preprocess\sibirsky_preprocess.py generate-mvp --output data\mvp_notes.jsonl --nouns 200 --verbs 100
.\.venv\Scripts\python tools\preprocess\sibirsky_preprocess.py validate-notes --input data\notes.jsonl --require-verified-aktionsart
```

## Inputs

- `data/raw/*.txt`: scraped or copied MFA/Kremlin/TASS/domain texts.
- `lexicon.jsonl`: one object per lemma with fields matching the Android import contract where available.
- `verbs.txt`: one verb lemma per line for Aktionsart drafting.

The `scrape` command can fetch public pages, but manual text files are safer and easier to inspect before ranking.

`generate-mvp` creates a *structurally* complete corpus of placeholder words — useful only for exercising code paths, never for study.

**For real, studyable content use `build_bootstrap.py` instead.** It declines a curated formal/political/security-register wordlist (`domain_wordlist.py`) through the rule-based engine in `russian_morph.py`, merges a general reading-matrix layer from an Anki deck, and writes the app assets directly:

```powershell
python tools\preprocess\build_bootstrap.py
```

This emits ~10,300 real notes in two layers:

- **Domain layer (~779 notes):** the curated `domain_wordlist.py` plus the `extended_*` modules — 514 nouns/adjectives with full case tables and 265 verbs (224 aspect-ready with manually-verified Aktionsart). Domain frequency ranks come from `domain_freq_list.tsv`; authentic target-source reader passages from `reader_texts.py`. These get the full grammar treatment (CASE_FILL + ASPECT_SELECT drills).
- **General layer (~9,500 notes):** the reading matrix (function words + common vocab) from the *Alex's 10k Russian* Anki deck. Nouns carry full declension tables (rule-engine, validated against the deck's real forms so irregulars like человек→люди keep correct forms); adjectives get the full paradigm; verbs derive regular past forms. These feed the reader coverage index but are tagged `general` so they stay vocab-only — grammar drilling stays focused on the domain. Sequencing is unified via the domain frequency list so function words surface first.

Regenerate the general source once from the deck with `extract_general.py` (writes the repo-local `general_source.jsonl` artifact so later builds don't need the original `.apkg`). Re-run `build_bootstrap.py` after editing any wordlist module.

`validate-notes` checks that an import file has at least 200 ready noun/adjective rows, 100 aspect-ready verb rows, valid aspect-partner lemmas, domain ranks, examples, and optionally high/manual Aktionsart verification.
