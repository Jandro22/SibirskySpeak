# Test strategy

The app now verifies the highest-risk seams in addition to the learning algorithms.

## Local checks

```text
./gradlew testDebugUnitTest jacocoDebugUnitTestReport
./gradlew assembleQaAndroidTest
./gradlew connectedQaAndroidTest
./gradlew lint
./gradlew assembleRelease
python -m pytest -q tools/preprocess
```

`jacocoDebugUnitTestReport` writes XML and HTML output under
`app/build/reports/jacoco/debugUnitTest/`. Coverage is report-only while the first
baseline is collected; CI publishes the report so thresholds can be introduced from
measured data instead of an arbitrary number.

## Protected boundaries

- Every Room migration (currently 7→32) has its own instrumented test
  (`Migration<N>To<N+1>Test`) plus `MigrationChainTest`, which runs the full chain
  and asserts fixture data survives intact end to end — see `app/src/androidTest/`.
- Real Room DAO queries and the repository review/undo transaction run in instrumentation.
- Compose smoke tests cover onboarding, dashboard start/customize actions, lesson rating,
  answer forwarding, and rating assertion callbacks.
- The QA build includes the Compose test manifest explicitly, so standalone
  `createComposeRule()` tests launch their activity instead of failing before composition.
- `SessionReducer` tests cover reveal, correction, duplicate events, pause/resume, queue
  advancement, and JSON snapshot restoration.
- Shipped JSONL is checked against the versioned curriculum contract in both Python and
  Android tests.
- Speech-recognition error policy and Russian TTS normalization/chunking are unit-tested.

## Lifecycle contract

The active study queue is checkpointed as card ids and counters in `SettingsStore`.
On process recreation, the queue is reconstructed from Room, cards that have already
been durably scheduled into the future are removed, and the current prompt reopens
unrevealed so a half-completed answer cannot be committed twice. A completed session
clears the checkpoint.
