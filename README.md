# SibirskySpeak

SibirskySpeak is an offline-first Android tutor for learning Russian from beginner foundations through advanced reading. It combines a CEFR curriculum, FSRS spaced repetition, grammar-aware practice, adaptive session pacing, audio practice, and a coverage-aware reader. Learner state stays on the device and can be exported through the full-state backup flow.

## What is included

- **Progressive A1-C1 course:** tiered vocabulary and grammar content, numbered units, controlled examples, and teach-before-test grammar gating.
- **Adaptive review:** FSRS-based scheduling, separate vocabulary and grammar queues, graduation rules, leech handling, due-debt control, and daily session planning.
- **Personal pacing model:** a shared `LearnerSnapshot` feeds capacity, willingness, fatigue, return context, calibration, and per-skill world estimates into session decisions and fluency forecasts.
- **Grammar-aware practice:** case fill-ins, aspect selection, verb forms, agreement, sentence building, cloze, transformations, concept drills, chunks, speaking, listening, dictation, stress, and phonology items.
- **Coverage-aware reader:** bundled and user-added texts, known-word coverage, target reading goals, token lookup, narrow reading, scheduled reading, checkpoints, and mined examples.
- **Audio practice:** Russian text-to-speech plus speech-recognition support for listening and speaking exercises.
- **Learner diagnostics:** dashboards, streaks, review telemetry, confusion pairs, calibration reports, fluency projections, rival/ghost progress, and adaptive-policy tuning.
- **Portable local data:** Room migrations, JSONL full-state backup/restore, content bootstrap validation, and a read-only bundled content database.

Everything runs locally. There is no backend, account system, or network dependency at runtime.

## Curriculum

The deck is organised into three tiers so the learner builds a general foundation before encountering specialised register vocabulary.

### Tier 0 - CEFR course (A1 to C1)

Tier 0 is the main learner-facing course. Notes are grouped into numbered units and tagged with a `cefrLevel`. Lesson cards introduce grammar concepts before related drills become eligible.

- **A1:** gender, plurals, core cases, past tense, and aspect basics.
- **A2:** future tense, imperatives, reflexives, comparison, modals, motion verbs, and `свой`.
- **B1:** prefixed motion, conditionals, `который`, superlatives, `чтобы`, and numbers with case.
- **B2:** participles, gerunds, passive constructions, and reported speech.
- **C1:** complex syntax, nominal style, aspect nuance, register, and idiom.

### Tier 1 - General reading matrix

High-frequency function words and coverage vocabulary support authentic reading without forcing every item into the full grammar-drill sequence.

### Tier 2 - Formal and political domain

The original formal/security-register vocabulary remains available as a specialised domain. Its grammar drills are still gated behind Tier 0 lessons.

See [`docs/A1_CURRICULUM_REWORK_PLAN.md`](docs/A1_CURRICULUM_REWORK_PLAN.md), [`docs/DESIGN_VISION.md`](docs/DESIGN_VISION.md), and [`docs/MASTER_PLAN.md`](docs/MASTER_PLAN.md) for the design history and roadmap.

## Architecture

- **Language/UI:** Kotlin, Jetpack Compose, and a single-activity app with a state-driven review ViewModel.
- **Learner database:** `AppDatabase` (`sibirsky_speak.db`), currently Room schema version 30, stores notes, cards, review logs, reader progress, telemetry, evidence, curriculum state, and adaptive model tables.
- **Content database:** `ContentDatabase` (`content.db`) is read-only and bundled as `tatoeba.db`; it supplies examples, lemma lookup, collocations, semantic neighbours, and frames without entering learner backups.
- **Scheduler:** `FsrsScheduler` handles per-card interval math and can consume fitted learner-specific weights.
- **Learning model:** `PaceController`, `LearnerSnapshot`, `WorldModel`, `CapacityModel`, willingness/return modelling, calibration, bandit selection, and `Rival`/`TrueSkill` coordinate session-level decisions independently of FSRS card scheduling.
- **Repository:** `LearningRepository` is the review-flow orchestrator. It assembles plans, applies gates, reads/writes learner state, and coordinates the scheduler, reader, and learning models.

## Repository layout

```text
app/                         Android application module
app/src/main/java/           Kotlin app, data, scheduler, review, reader, and learning code
app/src/main/assets/         Bundled notes, reader texts, curriculum metadata, and SQLite content
app/src/test/                JVM unit tests and in-memory repository fixtures
app/src/androidTest/         Room migration, backup, and connected-device tests
app/schemas/                 Versioned Room schema exports
docs/                        Curriculum, product, and model-design documentation
scripts/                     Windows setup, build, emulator, and install helpers
tools/preprocess/            Python preprocessing and content-validation pipeline
```

## Prerequisites

### Windows quick start

The repository can install a portable JDK, Gradle, and Android command-line SDK under `.tools/`:

```powershell
.\scripts\setup-android.ps1
.\scripts\build-debug.ps1
```

The debug APK can then be installed to exactly one authorized device or emulator:

```powershell
.\scripts\install-debug.ps1
```

The install helper preserves app data, saves the latest full-state backup before replacing an existing install, and refuses to continue if the recovery snapshot is missing or invalid.

### macOS/Linux or a preconfigured Android SDK

Set `ANDROID_HOME` or provide `local.properties`, then use the Gradle wrapper:

```bash
./gradlew assembleDebug
```

## Common commands

Run the JVM test suite:

```powershell
.\gradlew.bat testDebugUnitTest
```

Build the debug APK:

```powershell
.\scripts\build-debug.ps1
```

Run the isolated QA instrumentation suite:

```powershell
.\gradlew.bat connectedQaAndroidTest
```

The QA variant uses an isolated application id and must not remove the learner's normal install.

Run the content tests:

```bash
python -m pytest -q tools/preprocess
```

After changing preprocessing or curriculum sources, rebuild and validate the shipped assets:

```bash
python tools/preprocess/rebuild_all.py
python -m pytest -q tools/preprocess
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Data safety

Learner state is local and portable. The app maintains versioned Room migrations and a full-state JSONL backup containing notes, cards, review history, reader progress, telemetry, and adaptive model state. The immutable content corpus is deliberately excluded from learner backups because it is shipped separately as an app asset.

When testing on a real device, use the full backup flow before destructive maintenance or installation. The provided install script also checks for a valid recovery snapshot automatically.

## Testing and quality gates

Useful checks before publishing changes:

```bash
./gradlew testDebugUnitTest
python -m pytest -q tools/preprocess
```

The Kotlin suite covers scheduling, adaptive learning, repository behaviour, prompt construction, reader coverage, migrations, backups, and UI-facing state. The Python suite covers curriculum ordering, controlled vocabulary, morphology, reader quality, asset generation, and preprocessing reproducibility.

## Open in Android Studio

Open this folder as an Android project and let Android Studio sync Gradle. If you used `setup-android.ps1`, `local.properties` points Android Studio at the portable SDK.

## FSRS reference

The scheduler follows FSRS-6-style formulas and defaults from the open-spaced-repetition project:

- <https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm>

## Project status

The current public release is **v2.0.0**. It includes the local learner database and migration history, A1-C1 curriculum assets, adaptive pacing and learner modelling, grammar and audio practice, reader coverage workflows, dashboard diagnostics, portable backups, and the desktop content pipeline used to regenerate and validate shipped assets.

Download the latest APK from the [GitHub Releases page](https://github.com/Jandro22/SibirskySpeak/releases).
