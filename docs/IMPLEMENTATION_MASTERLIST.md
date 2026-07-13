# SibirskySpeak improvement masterlist

This is the implementation tracker for the product/code audit. Items marked
implemented have a corresponding code path and verification gate in the current
tree; follow-up items are intentionally kept visible instead of being mistaken
for completed behavior.

Scope note: localization and accessibility expansion were explicitly deferred by
the user for this implementation pass.

## Implemented in the current tree

- [x] Versioned Room migrations through schema 32 with migration tests (including
  durable reader bookmarks).
- [x] Process-death session checkpoints and safe prompt restoration.
- [x] Single sealed navigation stack with one ordered back policy.
- [x] Deferred reader/maintenance work so first interaction is not blocked.
- [x] Adaptive queue explanations (`Why this card`) and dashboard plan reasons.
- [x] Bounded micro-review path and user-visible three-card short session.
- [x] Atomic two-generation backups with SHA-256/format validation and fallback.
- [x] Optional automatic public Downloads backup; local/custom backup remains available.
- [x] Password-based AES-GCM encryption for external mirrors with Keystore-wrapped
  credentials, recovery-key UX, and tamper/credential tests.
- [x] Backup/import preview, full-state restore, and backup instrumentation coverage.
- [x] Clean-install onboarding path directly into the first Study lesson.
- [x] Responsive dashboard cards for large text/narrow windows.
- [x] Bottom-navigation accessibility semantics and Compose regression coverage.
- [x] On-device speech-recognition preference and graceful unavailable-service handling.
- [x] Offline release permission gate (release APK rejects `INTERNET`).
- [x] APK/asset budget checks, content audit, curriculum tests, lint, JVM tests,
  and isolated QA instrumentation in CI.
- [x] Generated curriculum manifest includes source attribution and license metadata.
- [x] Settings exposes the bundled provenance/license manifest as content credits.
- [x] Reader recommendations expose syntax, morphology, idiom, and combined difficulty signals.
- [x] Reader has a local focus mode that collapses chrome while preserving an exit action.
- [x] Imported reader text entry records an explicit source/license label instead of
  silently treating every paste as anonymous local content.
- [x] Study settings provide temporary Balanced, Reviews-only, Recovery, Reader-only,
  and eight-card Focus overrides without changing adaptive-model state.
- [x] Signed-release workflow includes an emulator launch smoke test.
- [x] Curriculum manifest parsing is isolated behind a tested service seam.
- [x] Reader difficulty scoring is isolated behind a pure, unit-tested analyzer seam.
- [x] Placement quiz progression is isolated behind a pure, unit-tested session reducer.
- [x] Reader text persistence and bookmark mutations are isolated behind a tested
  `ReaderTextService` boundary used by the repository.
- [x] Review session queue/counter mutation is isolated behind a tested
  `SessionTrackingStateHolder`; existing planner/transaction/curriculum seams remain.
- [x] Emulator review captures screenshot, UI tree, startup duration, and memory data.
- [x] Dependency-free emulator benchmark and four-state screenshot capture scripts are available.
- [x] 200%-font/reduced-motion emulator review script is available and has been run.
- [x] Serial-targeted, data-preserving device installation with explicit empty-device opt-in.
- [x] Formal `com.android.test` macrobenchmark module with cold-start/card-reader
  scenarios and a physical-device-only baseline-profile collector; AVD runs are
  intentionally skipped because emulator timings are not representative.

## Remaining engineering work

- [x] Split the highest-risk ViewModel/Repository boundaries into session tracking,
  reader persistence, session planning, review transactions, backup, curriculum,
  and pure difficulty/placement services while preserving public test seams.
- [ ] Continue moving remaining adaptive-model orchestration into independently
  owned services; the legacy repository still coordinates transaction boundaries.
- [ ] Extend the macrobenchmark scenarios with plan generation and card-advance
  checkpoints once a physical benchmark device is available.
- [ ] Add release-variant (R8/shrinker) smoke instrumentation and upgrade-over-release
  migration testing on a signed APK.
- [ ] Expand screenshot/golden coverage across dark theme, large text, landscape,
  onboarding, every card family, feedback, reader lookup, and Settings/Data.
- [ ] Complete app-wide string-resource localization and plural/locale formatting.
- [ ] Add TalkBack, switch-access, 200%-font, reduced-motion, and contrast test passes
  on representative screens.
- [x] Reader bookshelf cards can edit imported-text provenance after import.
- [x] Source/license edits emit durable `reader_source_updated` telemetry so
  provenance changes are included in exported learner history.
- [x] Durable reader bookmarks can be toggled from the word detail card, shown in
  the text, and carried through full-state backup/restore.
- [x] Reader bookshelf/text view exposes durable completion history for each text.
- [x] Add reader difficulty metrics for syntax, morphology novelty, idioms, and
  a combined difficulty score in addition to vocabulary coverage.
- [x] Add long-horizon randomized review/session state-machine coverage, including
  simulated wall-clock corrections; backup tests cover corruption and interrupted writes.
- [x] Packaged asset contract and malformed-manifest fallback tests cover corrupted
  content inputs; backup tests cover interrupted/corrupt state writes.
- [x] Device resilience gate refuses unsafe low-storage handoff, validates APK
  asset CRCs, and confirms the installed package launches.

## Product backlog

- [x] Explainable adaptive controls include queue reasons plus safe temporary
  overrides for reviews-only, recovery, reader-only, and focus sessions.
- [x] Placement now reports recognition score and explicitly frames placement as a
  provisional prior that production reviews must verify.
- [x] Placement results offer a conservative lower-level correction action before
  provisional placement is applied.
- [x] Speaking cards offer a microphone-free self-check path with learner-controlled
  grading when offline speech recognition is unavailable or declined.
- [x] Difficulty notebook behavior is provided by parked-leech cards with editable
  gloss/example/mnemonic fields, plus deck search and reader bookmarks.
- [x] Offline reader text import and durable token bookmarks/history around saved positions.
- [x] Achievement thresholds, rest-day credits, bounded recovery sessions, and
  minimum viable study sessions are implemented in the adaptive/session layer.

## Verification baseline

The current baseline is: JVM unit tests green, preprocessing tests green, curriculum
audit clean, Android Lint clean of errors, connected QA green, clean-install emulator
review green, and a final debug APK installed on the available emulator.
