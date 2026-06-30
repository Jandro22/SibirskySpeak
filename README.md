# SibirskySpeak

SibirskySpeak is a native Android reading-proficiency trainer for Russian learners. It combines a progressive CEFR curriculum, FSRS-based spaced repetition, explicit grammar drills, and a coverage-aware reader so learners can move from controlled beginner sentences toward authentic Russian text.

## Highlights

- **Progressive A1–C1 course:** tiered content introduces vocabulary and grammar in order, with lesson cards shown before related drills.
- **Dual-queue review:** vocabulary cards use FSRS scheduling while grammar cards stay capped until the learner graduates each category.
- **Grammar-aware practice:** generated cards cover vocabulary, case fill-ins, aspect selection, confusable pairs, and concept-specific drills.
- **Coverage-aware reader:** bundled reader texts track known-word coverage, target-source readiness, token lookup, and reading activity.
- **Offline-first app:** review, reader, dashboard, and bundled bootstrap data all run locally from Room databases.
- **Preprocessing pipeline:** desktop tools build domain frequency lists, validate notes, generate starter content, and prepare reader/deck assets.

## Curriculum model

The deck is organised into three tiers that are introduced in order so a beginner is not exposed to formal or political register content before the core language foundation is in place.

### Tier 0 — CEFR course (A1 → C1)

Tier 0 is the main learner-facing course. Notes are grouped into numbered units and tagged with a `cefrLevel` that appears during review. Example sentences are controlled for readability and paired with English translations.

The grammar spine is teach-before-test:

- **A1:** gender, plurals, cases, past tense, and aspect basics.
- **A2:** future tense, imperatives, reflexives, comparison, modals, motion verbs, and `свой`.
- **B1:** prefixed motion, conditionals, `который`, superlatives, `чтобы`, and numbers with case.
- **B2:** participles, gerunds, passive constructions, and reported speech.
- **C1:** complex syntax, nominal style, aspect nuance, register, and idiom.

See [`docs/A1_CURRICULUM_REWORK_PLAN.md`](docs/A1_CURRICULUM_REWORK_PLAN.md) and [`docs/DESIGN_VISION.md`](docs/DESIGN_VISION.md) for more detail.

### Tier 1 — General reading matrix

Tier 1 contains function words and common vocabulary that improve authentic-text coverage. These cards are vocabulary-focused and feed the reader coverage model.

### Tier 2 — Formal and political domain

Tier 2 contains the original Kremlin/TASS/security-register vocabulary with case and aspect drilling. Grammar drills in this tier remain gated behind Tier 0 lesson concepts, so learners encounter explanations before tests.

## App architecture

- **Language/UI:** Kotlin with Jetpack Compose.
- **Minimum SDK:** Android 8.0 / API 26.
- **Persistence:** Room databases for learner state and bundled content.
- **Core entities:** `Note`, `Card`, `ReviewLog`, `ReaderText`, `ReaderEncounter`, `ConfusablePair`, and telemetry/settings models.
- **Scheduling:** FSRS-6-inspired review scheduling plus grammar category caps and graduation rules.
- **Audio:** Android TextToSpeech wrapper configured for Russian (`ru-RU`) and speech-recognition support.
- **Assets:** bundled JSONL/SQLite bootstrap data for notes, reader texts, lemma lookup, and Tatoeba-derived examples.

## Repository layout

```text
app/                         Android application module
app/src/main/java/           Kotlin app, data, scheduler, review, reader, and learning code
app/src/main/assets/         Bundled bootstrap notes, reader texts, lemma data, and Tatoeba DB
app/src/test/                JVM unit tests
app/src/androidTest/         Instrumented migration tests
docs/                        Curriculum and product-design notes
scripts/                     Windows setup/build/install helper scripts
tools/preprocess/            Python preprocessing and content-validation pipeline
```

## Prerequisites

### Windows quick start

The repository includes PowerShell scripts that install a portable JDK, Gradle, and Android command-line SDK under `.tools/`.

```powershell
.\scripts\setup-android.ps1
.\scripts\build-debug.ps1
```

After setup has run once, the Windows Gradle wrapper auto-detects the portable JDK:

```powershell
.\gradlew.bat assembleDebug
```

### macOS/Linux or preconfigured Android SDK

If you already have a JDK and Android SDK installed, set `ANDROID_HOME` or provide `local.properties`, then use the Gradle wrapper:

```bash
./gradlew assembleDebug
```

## Common commands

Build the debug APK:

```bash
./gradlew assembleDebug
```

Run unit tests and build the debug APK:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Run Android instrumented tests on a connected device or emulator:

```bash
./gradlew connectedDebugAndroidTest
```

Install the debug build from Windows PowerShell:

```powershell
.\scripts\install-debug.ps1
```

Run preprocessing tests:

```bash
python -m pytest -q tools/preprocess
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Open in Android Studio

Open this folder as an Android project and let Android Studio sync Gradle. If you used `setup-android.ps1`, `local.properties` points Android Studio at the portable SDK created by the setup script.

## Data preprocessing

The Android app stays offline. Build import files on a development machine with the Python tools in `tools/preprocess`.

```bash
python tools/preprocess/sibirsky_preprocess.py rank-domain --input data/raw --output data/domain_frequency.tsv
python tools/preprocess/sibirsky_preprocess.py build-notes --lexicon data/lexicon.jsonl --domain-frequency data/domain_frequency.tsv --output data/notes.jsonl
python tools/preprocess/sibirsky_preprocess.py draft-aktionsart --verbs data/verbs.txt --output data/aktionsart_draft.jsonl
python tools/preprocess/sibirsky_preprocess.py generate-mvp --output data/mvp_notes.jsonl --nouns 200 --verbs 100
python tools/preprocess/sibirsky_preprocess.py validate-notes --input data/notes.jsonl --require-verified-aktionsart
```

Aktionsart output is intentionally marked low-confidence until human-verified. See [`tools/preprocess/README.md`](tools/preprocess/README.md) for the full preprocessing workflow.

## Testing and quality gates

Useful checks before opening a pull request:

```bash
./gradlew testDebugUnitTest
python -m pytest -q tools/preprocess
```

Content-specific tests validate controlled vocabulary, curriculum ordering, reader quality, Tatoeba assets, declension parsing, and generated starter content.

## FSRS reference

The scheduler follows FSRS-6 formulas and defaults from the open-spaced-repetition project:

- <https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm>

## Project status

SibirskySpeak currently includes the v2 learning foundation: local scheduling and review, grammar-focused prompts, reader coverage tracking, dashboard metrics, bootstrap course assets, and the desktop preprocessing pipeline needed to regenerate and validate content.
