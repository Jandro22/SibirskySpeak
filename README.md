# SibirskySpeak

Native Android reading-proficiency trainer for Russian learners, built around dual-queue FSRS review, explicit grammar drilling, and coverage-aware reading.

## Progressive A1–C1 curriculum

The deck is organised into three **tiers** that are introduced in order, so a true
beginner is never thrown into the formal/political register on day one:

- **Tier 0 — CEFR course (A1 → C1):** a concrete, high-frequency course grouped into
  numbered **units** that span every CEFR level from A1 to C1 (each note is tagged
  with its `cefrLevel`, shown in the review header). Every example sentence is fully
  readable — it ships with a real English translation and uses only vocabulary
  already taught (controlled vocabulary, enforced by tests). Grammar follows a
  **teach-before-test** spine of 32 concepts: A1 (gender → plurals → cases → past →
  aspect), A2 (future, imperative, reflexives, comparison, modals, motion, свой),
  B1 (prefixed motion, conditional, который, superlatives, чтобы, numbers + case),
  B2 (participles, gerunds, passive, reported speech), and C1 (complex syntax,
  nominal style, aspect nuance, register, idiom). Each concept is introduced by a
  **lesson card** shown *before* any drill on it can appear. See
  [`docs/A1_CURRICULUM_REWORK_PLAN.md`](docs/A1_CURRICULUM_REWORK_PLAN.md).
- **Tier 1 — general reading matrix:** function words and common vocabulary that make
  authentic text legible (vocab-only, feeds reader coverage).
- **Tier 2 — formal/political domain:** the original Kremlin/TASS/security register
  with full case and aspect drilling. Its grammar drills stay gated behind the A1
  lessons, so cases are taught before they are tested anywhere in the deck.

## Implemented v2 foundation

- Kotlin + Jetpack Compose Android app, minimum SDK 26.
- Fully local Room database with `Note`, `Card`, `ReviewLog`, `ConfusablePair`, and `ReaderText`.
- One note generates independent vocab and grammar cards, including `CASE_FILL` and `ASPECT_SELECT`.
- Dual queue scheduling: vocab uses raw FSRS; grammar is capped at 10 days until category graduation.
- Rolling accuracy map for case and aspect categories, daily plan generation, triage mode, and category graduation.
- JSON Lines import path for the v2 Note contract.
- Reader with coverage bands, token lookup, simple declension-table parsing, target-source readiness, and lookup logging.
- Dashboard showing due counts, review count, card totals, average coverage, and authentic-transition readiness.
- Desktop preprocessing tools for scraping/copying domain text, ranking a domain frequency corpus, joining lexicon data into app import JSONL, and drafting Aktionsart classifications for human verification.
- Bundled bootstrap JSONL assets for first-run notes and target/graded reader texts.
- Russian answer normalization that ignores stress marks and treats `ё` / `е` as equivalent.
- Android `TextToSpeech` wrapper using `ru-RU`.
- A daily session screen with review, grammar focus, aspect choices, reader coverage, reveal, and rating buttons.

## Command-line build

This repo includes scripts that install a portable JDK, Gradle, and Android command-line SDK under `.tools/`.

```powershell
.\scripts\setup-android.ps1
.\scripts\build-debug.ps1
```

After setup has run once, the Windows Gradle wrapper auto-detects the portable JDK:

```powershell
.\gradlew.bat assembleDebug
```

To run unit tests and build the APK:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

To run preprocessing tests:

```powershell
python -m pytest -q tools\preprocess
```

The debug APK is written to:

```text
app\build\outputs\apk\debug\app-debug.apk
```

To install it on a connected Android phone or emulator:

```powershell
.\scripts\install-debug.ps1
```

## Open in Android Studio

Open this folder as an Android project and let Android Studio sync Gradle. `local.properties` points Android Studio at the portable SDK created by `setup-android.ps1`.

## FSRS reference

The scheduler follows the FSRS-6 formulas and defaults from the open-spaced-repetition project:

- https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm

## Data preprocessing

The Android app stays offline. Build import files on the PC with:

```powershell
.\.venv\Scripts\python tools\preprocess\sibirsky_preprocess.py rank-domain --input data\raw --output data\domain_frequency.tsv
.\.venv\Scripts\python tools\preprocess\sibirsky_preprocess.py build-notes --lexicon data\lexicon.jsonl --domain-frequency data\domain_frequency.tsv --output data\notes.jsonl
.\.venv\Scripts\python tools\preprocess\sibirsky_preprocess.py draft-aktionsart --verbs data\verbs.txt --output data\aktionsart_draft.jsonl
.\.venv\Scripts\python tools\preprocess\sibirsky_preprocess.py generate-mvp --output data\mvp_notes.jsonl --nouns 200 --verbs 100
.\.venv\Scripts\python tools\preprocess\sibirsky_preprocess.py validate-notes --input data\notes.jsonl --require-verified-aktionsart
```

Aktionsart output is intentionally marked low-confidence until human-verified.
