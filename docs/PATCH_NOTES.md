# SibirskySpeak 2.0.0 — Full patch notes

Release date: 2026-07-12  
Package: `com.sibirskyspeak`  
Room schema: 32

This release is the result of a full application audit and implementation pass.
It focuses on learning quality, reader usefulness, data safety, resilience, and
operational verification while preserving the app's offline-first design.

## Highlights

- A more explainable adaptive study plan with visible reasons for card selection.
- Reader difficulty signals beyond vocabulary coverage, including syntax,
  morphology, idioms, and combined difficulty.
- Durable reader bookmarks, completion history, and editable source/provenance.
- Stronger backup/import safety with encrypted external mirrors and corruption
  recovery.
- Placement correction, recovery sessions, focus sessions, and bounded micro-review.
- A tested Room migration path through schema 32.
- Physical-device installation and launch verification tooling.

## Learning and study experience

- Added queue explanations such as why a card is due, blocked, new, or being
  prioritized.
- Added dashboard plan explanations and a clear “next best step” action.
- Added bounded three-card micro-review sessions for low-time study moments.
- Added temporary session modes for Balanced, Reviews-only, Recovery, Reader-only,
  and Focus sessions without mutating the learner's long-term adaptive settings.
- Added session completion, streak, achievement, rest, and recovery feedback.
- Added placement-session correction so learners can move to a lower starting
  level when the placement result is too aggressive.
- Placement correction now explains the recognition confidence and warns that
  production review should confirm the transfer.
- Preserved teach-before-test grammar gating from lesson-card state.
- Added safer known-card handling so marking a card known produces a meaningful
  scheduled state instead of an unusable zeroed interval.
- Improved card-family prompting and explanations across vocabulary, grammar,
  cloze, case, agreement, speaking, and lesson cards.
- Added a pronunciation self-check fallback when speech recognition is unavailable.
  Learners can explicitly confirm that they said the answer aloud without the
  app pretending that recognition succeeded.
- Improved unavailable speech-service handling and persisted speech-recognition
  preference behavior.
- Added adaptive session-state recovery after process death and prompt restoration.
- Added long-horizon state-machine coverage for session counters, wall-clock
  corrections, and repeated review flows.

## Reader

- Added syntax, morphology novelty, idiom, and combined difficulty metrics to
  reader recommendations.
- Added reader focus mode that collapses chrome while retaining an obvious exit.
- Added durable word bookmarks from the reader's word-detail card.
- Bookmarked words are visibly marked in the reader text.
- Added per-text completion history, including last reading activity and progress.
- Added bookshelf and text-view source/license editing for imported texts.
- Source/provenance edits emit durable `reader_source_updated` telemetry and are
  included in exported learner history.
- Reader bookmarks and history are included in full-state backup and restore.
- Improved reader word lookup and selected-word detail behavior.
- Kept reader rendering in its own bounded layout so virtualized text does not
  conflict with the rest of the screen's scroll container.
- Added provenance/license metadata to bundled curriculum content and exposed
  bundled credits in Settings.

## Dashboard, navigation, and UI polish

- Added a single ordered back-navigation policy across the app's screen states.
- Added deferred maintenance work so the first interaction is not blocked by
  background cleanup.
- Improved dashboard cards for narrow windows and large text.
- Added stable scroll-state scoping when switching tabs or cards.
- Added achievement overlays that push content down instead of obscuring it.
- Improved onboarding so a clean install can proceed directly into the first
  Study lesson.
- Refined bottom navigation, settings/data controls, reader bookshelf cards,
  study feedback, and reference screens.
- Added Compose test tags for important interactive controls and exposed them as
  resource IDs in UI automation dumps.
- Added dark-theme, reduced-motion, and large-font review tooling foundations.
- Preserved existing app semantics and testability; broad localization and
  accessibility expansion are intentionally deferred for this release.

## Data safety and backups

- Increased the learner database to Room schema 32.
- Added migrations through schema 32, including durable reader bookmarks.
- Added migration tests for 30→31, 31→32, and the complete migration chain.
- Added process-safe session checkpoints and prompt restoration.
- Added atomic two-generation backups with SHA-256 and format validation.
- Added fallback selection when the newest backup is incomplete or corrupt.
- Added optional automatic public Downloads backups while retaining local/custom
  backup destinations.
- Added password-based AES-GCM encryption for external backup mirrors.
- Added Keystore-wrapped backup credentials and recovery-key UX.
- Added backup/import previews and full-state restoration coverage.
- Added interrupted-write, corrupt-state, malformed-manifest, and low-storage
  resilience tests.
- Full-state export now carries reader bookmarks, reader history, and provenance
  telemetry.

## Offline content and curriculum

- Rebuilt bundled curriculum and reader assets from the verified preprocessing
  pipeline.
- Added source attribution and license metadata to the curriculum manifest.
- Improved lexical verification, mined examples, paradigms, reader coverage, and
  textbook ingestion handling.
- Preserved NFC normalization after stress-mark stripping so Russian words such
  as `й` remain compatible with morphology dictionaries and database keys.
- Added content-budget and packaged-asset validation.
- Release builds remain offline-only and reject the `INTERNET` permission.

## Architecture and maintainability

- Extracted curriculum-manifest parsing behind `CurriculumManifestService`.
- Extracted reader persistence, provenance updates, and bookmark mutations behind
  `ReaderTextService`.
- Extracted reader difficulty scoring behind a pure, unit-tested analyzer.
- Extracted placement progression behind a pure, unit-tested reducer.
- Extracted review-session counters, queue state, origin IDs, and session state
  behind `SessionTrackingStateHolder`.
- Preserved existing repository, review-transaction, backup, and scheduler seams.
- Added explicit telemetry for reader provenance changes.
- Added safer installation tooling with serial targeting, device snapshots, and an
  explicit empty-device opt-in.

## Verification completed

- `testDebugUnitTest` — passed.
- `lintDebug` — passed.
- `connectedQaAndroidTest` — 44 tests passed.
- `:benchmark:connectedBenchmarkAndroidTest` — build and connected gate passed;
  physical-only benchmark cases are skipped on the emulator by design.
- APK asset CRC and low-storage resilience gate — passed.
- Release launch smoke gate — passed on the emulator and Pixel 8.
- Debug upgrade smoke gate — passed.
- Emulator UI review and UI-tree capture — passed.
- Final APK installed on Pixel 8 serial `37201FDJH00177`.
- Physical-device resilience check — passed with approximately 40 GB free.

## Migration and upgrade notes

- Existing learner data migrates forward through Room schema 32.
- Reader bookmarks are additive and default to empty for existing texts.
- Existing reader texts remain usable; imported texts can be assigned or corrected
  with source/license information after migration.
- Do not delete the app or clear app data when upgrading if learner history and
  backups should be preserved.

## Known limitations and deferred work

The following items are intentionally not part of this implementation pass:

- App-wide localization and plural/locale formatting.
- Full TalkBack, switch-access, 200%-font, reduced-motion, and contrast test passes
  on representative screens.
- Production-signed R8/shrinker smoke and signed-APK upgrade testing; production
  signing credentials are not stored in the workspace.
- Physical-device macrobenchmark metrics for plan generation and card advance.
- Full screenshot/golden coverage for every theme, orientation, card family, and
  settings state.
- Further decomposition of the remaining adaptive-model orchestration inside the
  legacy repository.

