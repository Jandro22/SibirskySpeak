# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

SibirskySpeak is a native Android (Kotlin + Jetpack Compose) offline app that teaches Russian: a progressive A1→C1 curriculum, FSRS-based spaced repetition, generated grammar drills, and a coverage-aware reader. Everything runs locally against bundled Room databases — there is no backend.

## Commands

### Windows (primary dev environment)

First-time setup installs a portable JDK, Gradle, and Android SDK under `.tools/`:

```powershell
.\scripts\setup-android.ps1
```

Build the debug APK (uses the portable toolchain automatically):

```powershell
.\scripts\build-debug.ps1
```

Install the freshly built debug APK to a connected device/emulator:

```powershell
.\scripts\install-debug.ps1
```

If invoking Gradle directly instead of the wrapper scripts, the portable JDK must be on `JAVA_HOME`:

```powershell
$env:JAVA_HOME = "$PWD\.tools\jdk"
.\gradlew.bat assembleDebug
```

### Emulator-driven UI/QA scripts (`scripts/`)

- `setup-emulator.ps1` — creates the `Sibirsky_Pixel4a_API35` AVD (Google APIs image) under `.tools/android-avd`.
- `start-emulator.ps1` — boots that AVD (headless by default; `-Visible` for a window), waits for `sys.boot_completed`, and leaves animations enabled so Compose transitions are visible in a screenshot.
- `review-emulator-ui.ps1` — boots the emulator, installs the current debug APK (`-Install`, `-ResetApp` to clear state), waits for the app body to actually render (not just the splash), and captures a settled `screen.png` + `ui.xml` (uiautomator dump) under `build/emulator-review/<timestamp>/`.

These are the fastest way to visually verify a Compose UI change end-to-end. For scripted interaction beyond a single screenshot, drive the same emulator with `adb -s emulator-5554 shell input tap/swipe/text` and re-dump `uiautomator dump` to find element bounds — `Modifier.testTag` values are exposed as `resource-id` in that dump (see Testability below), so prefer selecting by tag over scraping visible text.

### Tests

```powershell
.\gradlew.bat testDebugUnitTest                          # JVM unit tests (app/src/test)
.\gradlew.bat testDebugUnitTest --tests "com.sibirskyspeak.review.ReviewPromptTest"   # single class
.\gradlew.bat testDebugUnitTest --tests "*.ReviewPromptTest.lessonCardBuildsTeachingContentFromConcept"  # single test
.\gradlew.bat connectedDebugAndroidTest                   # instrumented tests, needs a device/emulator
.\gradlew.bat lint                                        # Android Lint
```

Content/curriculum tests are Python (`tools/preprocess`), separate from the Kotlin test suite:

```bash
python -m pytest -q tools/preprocess              # all content tests
python -m pytest -q tools/preprocess/test_curriculum.py -k some_test_name  # single test
python tools/preprocess/audit_curriculum.py       # batch-prints controlled-vocab/stress/gloss violations before running pytest
```

### Content pipeline

The app is offline-only; content changes are authored/generated on a dev machine and shipped as a bundled asset, not fetched at runtime:

```bash
python tools/preprocess/build_bootstrap.py   # regenerates app/src/main/assets/bootstrap_notes.jsonl
```

Always re-run this and `python -m pytest -q tools/preprocess` after editing anything under `tools/preprocess/` (curriculum modules `a1_starter.py`…`c1_starter.py`, `general_layer.py`, etc.) — the Kotlin app never validates this content itself, it just loads whatever JSONL ships in the assets.

## Architecture

### Two Room databases, different lifecycles

- **`AppDatabase`** (`sibirsky_speak.db`, currently schema v19, `data/AppDatabase.kt`) — the learner's mutable state: `Note`, `Card`, `ReviewLog`, reader progress, telemetry, and the adaptive-learning model tables (`SkillRating`, `RivalState`, `PaceLog`, etc.). Has real `Migration` objects (12+ so far) — adding/changing a Room entity field requires bumping `version` and writing a migration, not just editing the entity.
- **`ContentDatabase`** (`content.db`, `data/ContentDatabase.kt`) — read-only, `createFromAsset("tatoeba.db")`. Holds Tatoeba example sentences, lemma index, collocations, and semantic neighbors used to enrich lesson cards (word family, "useful chunks", cognate detection). Never migrated in place — schema changes here mean regenerating and reshipping the asset.

### Note → Card is one-to-many

A `Note` is one vocabulary/grammar item (its Russian form, translation, examples, declension JSON, etc.). Each `Note` can have several `Card` rows, one per `CardType` (`RU_TO_MEANING`, `CASE_FILL`, `ADJ_AGREE`, `CLOZE`, `SPEAK`, `LESSON`, …), each independently FSRS-scheduled. `ReviewPrompt.kt`'s `buildPrompt(card, note, ...)` is the single place that turns a `(Card, Note)` pair into what the review screen actually renders — the `when (card.cardType)` there is the map of every drill type to its prompt-construction logic.

### Teach-before-test grammar gating is derived state, not a table

Concept progression is **not** a separate Room table. A grammar concept counts as "introduced" once its `CardType.LESSON` card has been reviewed (`state != NEW`) — a LESSON card graduates immediately on first review (special-cased in `LearningRepository.review`). `LearningRepository.lockedConceptIds()` / `isConceptLocked()` derive which drills are still gated from card state on every query. Grammar concepts are authored in `data/GrammarConcepts.kt` (Kotlin, field `GrammarConcept.id`) and must stay in sync with the `"concept"` values used in `tools/preprocess/a1_starter.py` and friends (Python, e.g. `"concept": "GENDER"`), which flow into `Card.gramConcept` / `Note.conceptId` — there's no compile-time link between the Kotlin and Python sides.

### Curriculum tiers (`Note.tier`)

0 = hand-authored A1→C1 spine + promoted high-frequency band (~5k notes, teach-before-test, controlled vocabulary); 1 = general reading-matrix vocabulary (function words / coverage fuel, vocab-only, no generated morphology drills — gated by the `"matrix"` tag, not tier); 2 = the original formal/political-register domain, with its grammar drills still gated behind tier-0 lesson concepts. New cards are introduced in tier order via `CardDao.getNewCardsOrdered`.

### Three cooperating "brains"

- **`scheduler/`** — `FsrsScheduler` is pure per-card interval math (FSRS-6-style). Takes a `weightsProvider: () -> DoubleArray` (not a fixed array) so an on-device weight refit applies without reconstructing the scheduler. `FsrsWeightFitter` re-estimates initial-stability and decay weights from the learner's own `ReviewLog` history.
- **`learning/`** — session/pace-level intelligence: `PaceController`/`Doctrine` (RECOVERY…SPRINT presets tuning new-card caps and production bias), `WorldModel` (a per-skill Bayesian ability estimate used to pick session difficulty), and `Rival`/`TrueSkill` (a simulated opponent + match rating shown on session-complete, independent of FSRS scheduling).
- **`data/LearningRepository.kt`** (~4k lines) — the orchestrator. Builds the daily session plan, decides which cards are due/blocked/new, wires the scheduler and pace/world models together, and is the only place that talks to the DAOs for review-flow purposes. Most feature work touches this file.

### UI: single-Activity Compose, one big ViewModel

`MainActivity.kt` hosts one `ReviewScreen` composable; screens (`DashboardScreens.kt`, `StudyScreens.kt`, `PracticeScreens.kt`, `ReaderScreens.kt`, `SettingsScreens.kt`) are all driven by one `ReviewViewModel` (~2.2k lines) exposing a single `StateFlow<ReviewUiState>`. There's no navigation library — screen switching is `AnimatedContent` keyed on a `SessionStep` enum plus a local `studyActive` boolean in `MainActivity`. Below that, there are two top-level layout branches: the open-text reader (its own bounded-height `Column`, since the reader screen virtualizes tokens in a `LazyColumn` that can't live inside the other branch's `verticalScroll`), and everything else, which shares one scrollable `Column` whose `rememberScrollState()` is scoped with `key(pageKey)` (derived from the active tab or the current card's id) so switching cards/tabs can't leak a stale scroll offset into the next screen. Both branches invoke the same hoisted `achievementOverlay` lambda at their top so the achievement toast pushes content down in either layout instead of floating over it.

Session-mutating ViewModel actions (rate, suspend, mark-known, the debug card-type jump, …) follow the same shape: `viewModelScope.launch { runCatching { repository.xxx(...) }.onSuccess { ... }.onFailure { mutableState.value = mutableState.value.copy(statusMessage = it.message ?: "...") } }`. Match this pattern for new one-off actions instead of inventing a new error-handling style.

### Testability

Interactive Compose controls carry `Modifier.testTag(...)` (constants in `TestTags.kt`), and the app root wraps content in `Modifier.semantics { testTagsAsResourceId = true }` (`MainActivity.kt`), so `adb shell uiautomator dump` exposes tags as `resource-id` — prefer adding a tag over relying on visible text or `contentDescription` when a control needs to be driven from a test or a QA script.

### Debug-only card-type jump

`ReviewViewModel.debugStartSessionWithCardType` (wired from Settings → Data, `BuildConfig.DEBUG`-gated in both the UI and the ViewModel) opens any existing card of a chosen `CardType` as an unscored `practiceOnly` preview, bypassing the adaptive queue. Useful for reaching rare card types without dozens of turns through the real adaptive session — extend `resetSessionTrackingState()` (shared with `startStudySession`) rather than hand-duplicating session-reset logic if you touch this.
