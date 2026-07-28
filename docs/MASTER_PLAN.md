# SibirskySpeak Master Plan

> **⚠ SUPERSEDED — the phased work below has shipped; see `docs/ADAPTIVE_TUTOR_FINAL_PLAN.md`
> for the successor roadmap and `docs/PATCH_NOTES.md` for what's actually current.**
> The "audit evidence" table further down (grammar concepts: 40, notes: 9,291, etc.)
> is a 2026-07 snapshot that's now wrong on every row — re-measure from the live code
> rather than trusting these numbers (the doc's own caveat already says this).
> Kept as the historical record of the vision/decisions/rationale behind this app's
> architecture (Parts I–II's principles still hold), not as a live status board.

**Audience:** the agent(s) executing this plan. You were not part of the planning
conversation. This document is your complete context: the vision, the evidence it
rests on, the decisions already made (do not re-litigate them), the principles for
resolving ambiguity, and the phased technical work. Read Parts I–III before writing
any code. `CLAUDE.md` at the repo root covers build commands and codebase
architecture; this document covers intent.

---

# Part I — Vision and intent

## What this app is

SibirskySpeak is a **personal instrument for exactly one learner**: the repo owner,
Alejandro. It is not a product with users. He is literate in Cyrillic, studies
Russian daily, and owns every design decision. This changes what "optimal" means:

- Optimal = optimal *for him*, measured on *his* data, which the app fully possesses
  (years of review logs, telemetry, pace history).
- There is no onboarding funnel, no anonymous-user analytics, no A/B testing on a
  fleet. The substitutes are: **simulation** (synthetic learners in CI) and
  **self-experimentation** (within-subject comparisons refereed by an independent
  assessment engine).
- The whole plan's risk concentrates in one variable: whether he keeps showing up.
  Daily-experience quality and adherence engineering therefore rank *above* content
  volume in execution order.

## Hard constraints — design axioms, never bypassed

1. **Offline forever.** No backend, no server, no network calls at runtime.
2. **No runtime LLM.** No on-device model, no API calls. All AI happens at *build
   time* (an agent like you, authoring and validating content in the Python
   pipeline), shipped as static assets. The device only assembles, plays, grades
   deterministically, and measures.
3. **Android only.** Kotlin + Jetpack Compose, single activity.
4. **All content ships as build-time-validated bundled assets.** The Kotlin app
   never validates content; the Python pipeline's pytest gates are the only
   quality wall. Anything not test-gated is assumed broken.
5. **N=1.** See above.

## The central diagnosis (why this plan exists)

A full architectural review reached one core conclusion:

> **The app's measurement machinery is dramatically ahead of both its content and
> its task variety.** FSRS with on-device refit, evidence-graded card types,
> Bayesian skill models, capacity/willingness pacing — world-class. But *what* gets
> practiced is architecturally frozen: every drill is anchored to one lexeme with
> one fixed carrier sentence, so mature cards test item memory ("I remember this
> exact sentence") rather than transferable skill ("I can apply this case to any
> noun"). Meanwhile the input side (reading) generates zero scheduling evidence,
> and several shipped pedagogical features are starved of the content they need.

Every phase of this plan attacks that diagnosis from a different side:
foundations that make tasks *generative* (Phase 0, 4), an instrument worth using
daily (Phase 1, 2), content to feed starved features (Phase 3), input that pays
into the scheduler (Phase 5), and independent proof it's all real (Phase 6).

## The audit evidence (numbers measured directly from the repo, 2026-07)

These numbers justify the plan. If they've drifted by the time you read this,
re-measure before acting (profile `app/src/main/assets/bootstrap_notes.jsonl`).

| Fact | Value | Implication |
|---|---|---|
| Notes | 9,291 (6,170 tier-0 across 214 units; 2,406 matrix; 715 domain) | Curriculum spine is real and large |
| Notes with exactly ONE example sentence | 9,251 of 9,291 (only 227 have a 2nd, **3** have a 3rd) | `exampleFor()` rep-rotation machinery exists but is starved; every word lives in one frozen context |
| Mnemonics | **0** | `mnemonicLine()` renderer ships, fed by nothing |
| Second senses | **2** notes | `secondSenseExposure()` polysemy mechanic ships, fed by nothing |
| Verbs with aspect labels | 311 of 1,982 (16 %) | `ASPECT_SELECT`, the most sophisticated drill, covers a sliver |
| Grammar concepts | 40, one LESSON note each | Concept inventory fine; concept *scheduling* absent |
| Reader texts | 386 snippets, median **36 words** | This is captions, not reading |
| Tatoeba corpus on device | **123,616 sentences**, lemma-indexed, +44k collocations | The sentence supply already exists; the gap is indexing/selection, not authorship |

**Pattern:** shipped features starved of content, and a massive on-device corpus
nobody queries strategically. The plan's generation strategy is therefore:
**index and compose, don't hand-write.** A few hundred authored templates
(frames) × a morphology engine × 6k known words = combinatorial task space.
Authored prose (stories, dialogues) is written by the build-time agent in
test-gated installments — never a heroic 10,000-sentence effort.

## Decisions already made — do NOT re-litigate

| Decision | Rationale (short) |
|---|---|
| **No on-device LLM, ever** | Owner ruled it out. All generation is build-time + deterministic runtime assembly. |
| **No alphabet/typing onboarding module** | Sole user already reads and types Cyrillic. |
| **No pre-rendered neural audio pack (for now)** | Device TTS is acceptable to the owner. The listening features must be built so swapping in build-time audio later is a pure asset change. |
| **UI/adherence work (Phases 1–2) lands BEFORE bulk content (Phase 3)** | Content pays off over months; the instrument is touched tonight. Adherence is the multiplier on everything. |
| **Rival/TrueSkill UI is on notice, not deleted** | Demote to Lab screen; retire only if pace-log data shows no behavioral coupling. Decide with data, not taste. |
| **Concept-level scheduling supersedes per-note grammar drills gradually** | Per-note drills remain the acquisition on-ramp; they taper via a selection gate, their FSRS state is never destroyed. |
| **Passive reading evidence is hard-capped and can never graduate/transition a card** | Protects FSRS integrity; reading credit is a nudge, not a review. |
| **Licensing cleanup (textbook-sourced reader texts) rides along with Phase 5**, not urgent | Personal use today; must be complete before any public distribution. |

## Pedagogical principles (how to resolve ambiguity)

When the plan under-specifies something, decide using these, in priority order:

1. **Transfer over item memory.** If a design choice makes the learner retrieve a
   *rule or skill* in a novel context rather than recognize a memorized string,
   take it. This is the plan's soul. (It's why carriers must vary, why frames
   exist, why `ADJ_CARRIER_NOUNS`-style fixed pairings are the enemy at maturity —
   though *stability during early acquisition* is fine and intentional.)
2. **Evidence integrity over feature richness.** Never let a new activity write
   scheduling evidence stronger than the retrieval it actually demonstrates. The
   `EvidenceStrength` ladder (STRONG/MODERATE/PRACTICE/INSTRUCTION) is the law.
   When unsure, grade evidence *weaker*.
3. **The scheduler is FSRS; everything else advises.** Pace, world model, bandit —
   they order and budget. They never override due-date math.
4. **Scaffolding fades; it never disappears by surprise.** The codebase's existing
   pattern (guided choices → typed production as reps grow) is correct. New task
   types must implement a fade, and repair paths (existing scaffold/repair loop)
   must exist for failures.
5. **Teach before test.** The concept-gating invariant (LESSON seen before drills
   surface, probation until first success) extends to every new mechanic.
6. **Deterministic grading, honest feedback.** If a task can't be graded reliably
   offline, either constrain the task until it can (word banks, frames,
   acceptable-sets) or grade leniently and mark the evidence PRACTICE. Never fake
   confidence.
7. **The learner's time is the scarcest resource.** Cognitive cost per card type is
   already modeled (`CardPedagogy.cognitiveCost`) — respect it in anything that
   builds sessions.

## Product principles for the UI/adherence work

1. **The review loop is touched 100+ times a day; everything else is furniture.**
   Polish gradient must be extreme in its favor.
2. **Cold-start-to-first-card is the north-star UI metric.** Target < 2 s.
3. **Dashboard answers exactly three questions in order:** what do I do now (one
   action) / am I trending well (one line) / does anything need attention (only if
   nonzero). Diagnostics live in a separate Lab screen.
4. **Lower the activation floor below "a session":** widget and notification
   micro-reviews. Ten seconds at a bus stop must count.
5. **Show contracts, not mysteries:** tomorrow's load is always visible and always
   a number ("19 reviews, ~7 min").
6. **The most motivating number the app can compute is goal coverage:** "216 words
   between you and «Мастер и Маргарита»". Surface it centrally (mechanism exists:
   `target:` reader sources + `goalDirectedPriorities`).

---

# Part II — Execution norms

## Working style

- Every work item below is a PR-sized unit with its own test gate. Do them in
  dependency order. Do not batch unrelated items into one PR.
- **Refactors are behavior-frozen:** the evidence-bus refactor and the UI state
  split must produce byte-identical outcomes (golden tests) before any behavior
  change lands on top.
- Match existing code idiom (comment density, error-handling shape — e.g. the
  `runCatching{}.onSuccess/.onFailure` ViewModel action pattern documented in
  CLAUDE.md).
- New interactive controls get `Modifier.testTag` entries in `TestTags.kt`.
- When you author content (mnemonics, stories, dialogues): you are the build-time
  LLM. Write in batches, run the pipeline gates, expect rejections, iterate.
  Throughput expectation: 50–150 *validated* items per session. Never ship content
  that only you have judged — the pytest gate judges.

## Standing gates for every PR

- `.\gradlew.bat testDebugUnitTest` and `.\gradlew.bat lint` green.
- `python -m pytest -q tools/preprocess` green; re-run `build_bootstrap.py` after
  any content/pipeline change (the app loads whatever JSONL ships — no runtime
  validation exists).
- `AppDatabase` schema change ⇒ version bump + `Migration` object + migration test
  (pattern: `Migration18To19Test`).
- `ContentDatabase` schema change ⇒ regenerate the `tatoeba.db` asset and bump the
  asset version (it is NEVER migrated in place).
- UI changes verified via `scripts/review-emulator-ui.ps1` (screenshot +
  uiautomator dump; testTags appear as resource-ids).
- From Phase 0 on: `simCheck` (the learner simulator, built in P0.3) green for any
  change touching scheduling, gating, budgets, or curriculum structure.

## Key files map (orientation)

- `data/LearningRepository.kt` (~3.9k lines) — orchestrator: session planning, gating,
  reader, evidence. Most work lands here or extracts from here.
- `review/ReviewPrompt.kt` — the single `(Card, Note) → prompt` map for all 15 card
  types; `diagnosticFeedbackFor` does structured error analysis (currently discarded).
- `learning/CardPedagogy.kt` — pedagogical semantics per card type (facet, evidence
  strength, stage weights, cognitive cost). Extend for every new card type.
- `learning/PaceController.kt` — session budgets/doctrines; `dueForecast` lives here.
- `scheduler/FsrsScheduler.kt` — pure interval math; weights via provider lambda.
- `review/ReviewViewModel.kt` (~2.2k lines) — the god-ViewModel to be split (P1.1).
- `MainActivity.kt` — hand-rolled navigation (`studyActive` + `SessionStep` + back
  handlers) to be replaced by `NavState` (P1.1).
- `tools/preprocess/` — the content pipeline; `build_bootstrap.py` is the assembler,
  `audit_curriculum.py` + pytest are the wall.

---

# Part III — The phases

Ordering rationale: Phase 0 builds the three things everything else stands on
(morphology, evidence bus, simulator). Phases 1–2 fix the daily instrument and
adherence loop *before* content, per the owner's decision. Phase 3 feeds starved
features. Phase 4 delivers the transfer revolution (generative tasks). Phase 5
closes the input loop. Phase 6 adds communication tasks and independent proof.
Phase 7 is the permanent operating mode.

---

## Phase 0 — Foundations

### P0.1 MorphologyEngine
**Intent:** one authoritative, queryable model of Russian morphology on device.
Kills the known-buggy per-note `declensionJson` blobs (see the `possessiveIyForm`
presentation-time patch in `ReviewPrompt.kt` — a symptom to be deleted). Enables:
generating any drill on any known word, and *parsing learner output* — the
foundation of all Phase 4/6 production grading.

Build-time (`tools/preprocess/build_paradigms.py`):
- For every deck lemma, emit full pymorphy3 paradigms into a new `paradigm` table in
  `tatoeba.db`: `(lemma, pos, feats, surface, stressed)`. `feats` reuses the existing
  key vocabulary (`GEN_SG`, `PRES_3SG`, `PAST_F`, `FEM_NOM`, …) so Kotlin consumers
  keep their key space.
- Emit an `analysis` reverse index `(surface_norm, lemma, feats)` over all generated
  forms — this backs `analyze()` with zero on-device morphology code.
- Regenerate every note's `declensionJson` from the same source inside
  `build_bootstrap.py` (compatibility bridge until all consumers migrate).
- Gate: `test_paradigms.py` against a hand-verified gold set (≥60 forms: possessive
  -ий adjectives, irregular verbs идти/есть/дать/хотеть, fleeting vowels, ё).

Runtime (`app/src/main/java/com/sibirskyspeak/morph/MorphologyEngine.kt`):
- `inflect(lemma, feats): String?` · `analyze(surface): List<Analysis>` ·
  `agreementOk(adj, noun): Boolean` — backed by new ContentDatabase DAOs, LRU cache.
- Migrate `ReviewPrompt` drill paths to the engine behind a flag; delete the JSON
  fallback and patch functions once golden tests pass.

### P0.2 Evidence Bus
**Intent:** today `review()` is the only door into the scheduler, so reading,
listening, dialogue, and composition can never earn credit. One typed event stream
fixes that — and it's the refactor that starts taming the 3.9k-line repository.

- `learning/Evidence.kt`:
  ```kotlin
  data class EvidenceEvent(
      val noteId: Long?, val conceptId: String?,
      val facet: LearningFacet, val strength: EvidenceStrength,
      val correct: Boolean, val source: ReviewSource, val at: Long
  )
  ```
- `LearningRepository.recordEvidence(event)` = the only writer of scheduling
  evidence. `review()` routes through it. **Golden test: identical FSRS outputs
  before/after the refactor.**
- `FsrsScheduler.applyPassiveEvidence(card, factor)`: stability multiplier capped at
  ×1.15 per event, ≤1 passive credit per card per day, **no state transition ever**,
  logged with new `ReviewSource.READING / LISTENING / PRODUCTION` values.
- AppDatabase v19→v20: add nullable `ReviewLog.evidenceStrength` (reuse the log; no
  new table). Migration + test.
- Gate: unit tests for caps; NEW cards cannot be touched by passive evidence; undo
  path unaffected.

### P0.3 Learner Simulator + pedagogy CI
**Intent:** with N=1 there is no user fleet to catch pedagogy regressions; the
codebase has already shipped one silent curriculum deadlock (unit-gating,
since fixed). The simulator is the substitute for a QA population and the
precondition for every scheduler change after this point.

- `app/src/test/java/com/sibirskyspeak/sim/`:
  - `SyntheticLearner`: per-item true stability ~ lognormal keyed on frequency rank
    + card-type cognitive cost; recall = exponential forgetting; answer noise per
    AnswerMode (typos on typed, ASR misses on speak).
  - `SimHarness`: Robolectric in-memory Room seeded with the *real*
    `bootstrap_notes.jsonl`; day loop = `sessionPlan()` → answer prompts → `review()`
    → advance clock; optional daily reading completion.
  - Assertions (fast profile: N=25 learners, 400 sim-days, seeded RNG):
    1. **No deadlock** — new-card introduction never halts while eligible tier-0
       notes remain.
    2. **Debt bounded** — `pace.debtRatio` p95 < 1.0.
    3. **Reachability** — p50 learner passes unit 30 by day ~120 (calibrate once,
       freeze as regression bound).
    4. **No starvation** — every enabled CardType appears within 30 sim-days.
- CI: Gradle task `simCheck` in `.github/workflows/build.yml`; nightly N=200.
- Gate for the harness itself: re-inject the historical unit-gating bug and prove
  the simulator catches it.

### P0.4 Durable backups
**Intent:** years of one person's learning state in one local DB is the app's most
irreplaceable artifact. A lost phone must not mean a lost year.

- SAF tree URI chosen once (Settings → Data), persisted; WorkManager writes a
  versioned backup after every `finishAdaptiveSession` (keep 14 rolling, then
  weekly thinning). A Drive-synced folder gives cloud durability with no backend.
- Audit `exportFullState()` coverage: must include model tables (`SkillRating`,
  `OptimizerParameter`, `ItemDifficulty`, `ConceptMastery`, `PaceLog`, bandit
  state) — extend if missing.
- Gate: instrumented round-trip — export → wipe → import → `sessionPlan()`
  equivalent modulo clock.

### P0.5 Budgets in CI
**Intent:** content is about to grow ~10×; decide ceilings before overruns are
crises. Assets ≤150 MB hard (warn 120); `sessionPlan(includeReaderInsights=false)`
on a 3× synthetic deck < 1.5 s in a JVM timing test (generous — catches complexity
regressions, not noise); APK size diff vs baseline (+10 % fails).

---

## Phase 1 — Instrument rework

**Intent:** the UI is a daily instrument for one expert operator. Current reality
(verified in code): one ~40-field `ReviewUiState` on one 2.2k-line ViewModel drives
every screen; navigation is `studyActive` + a `SessionStep` enum that mixes tabs
with session phases + hand-stacked BackHandlers (`ReviewViewModel.kt:217` documents
the three competing sources of navigation truth); the dashboard is ~15 sibling
widgets at one altitude. None of this is cosmetic debt — it is why the app cannot
be entered any way except "open app, full session", which blocks Phase 2 entirely.

### P1.1 State split + navigation machine
Two mechanical, behavior-frozen steps:
1. Split `ReviewUiState` into `StudyUiState` / `ReaderUiState` / `DashboardUiState`
   / `ImportUiState`, exposed as separate StateFlows from the existing ViewModel;
   screens subscribe to their slice only. Old state object remains temporarily as a
   deprecated composition of the four.
2. Extract `ReaderViewModel` and `ImportViewModel` (least coupled). Study+Dashboard
   stay together (genuinely shared session state).
- Navigation: sealed back stack replacing the boolean/enum tangle:
  ```kotlin
  sealed interface Dest { Study; Dashboard; Reader(textId: Long?); Lab; Import; Reference; Settings }
  ```
  `SessionStep` shrinks to session *phases* only (REVIEWS/RULE/BLOCKED/INTERLEAVED).
  One BackHandler = `NavState.pop()`.
- Gate: all existing testTag emulator flows pass unchanged; NavState unit tests.

### P1.2 Study surface redesign
- Launch lands *in* the next due card when due > 0 (dashboard one swipe away).
- Cold-start-to-first-card < 2 s: persist a "plan skeleton" (top-5 card ids +
  prebuilt prompts) at session end; render instantly on launch while full
  `sessionPlan()` reconciles behind it (drop stale entries).
- Focus mode during study: chrome hidden; dedicated `RussianDisplay` TextStyle
  (≥28 sp, line height tuned for combining-acute stress marks — legibility of
  stressed Cyrillic is a first-class requirement); answer field `imePadding`-pinned;
  rating row in thumb zone; interval preview + `queueReason` kept but visually quiet.

### P1.3 Dashboard 3-layer + Lab
- Dashboard = **Act** (one primary card: today's contract + start) / **Trend** (one
  7-day sparkline + goal-coverage line) / **Attend** (nudges/leeches/backup failures,
  rendered only when nonzero).
- New `LabScreens.kt` receives: SkillRadar, Rival + match history, calibration
  drift, pace diagnostics, model snapshots, retention-by-card-type.
- Rival decision: analyze pace logs/telemetry for behavioral coupling; demote to Lab
  now, retire later only per the data (see decision log).

---

## Phase 2 — Adherence loop

**Intent:** the willingness model literally predicts return probability and the
product does nothing with it except budget cards. Make the product act on its own
models. Every item here exists to protect the one variable the plan cannot survive
losing: daily contact.

### P2.1 Micro-review surfaces
- Glance widget: due count + top card's Russian; tap → deep link (`mode=micro`
  intent) into a 3-card scoped session (normal review path, auto-finishes).
- Notification inline review (daily, only when ≥1 at-risk card): an ENGLISH-answer
  card via `RemoteInput`, graded by `AnswerNormalizer`, writing a normal ReviewLog
  row. Only ENGLISH-mode prompts are eligible on these surfaces.
- Gate: micro sessions write byte-identical ReviewLog rows; sim runs a micro-heavy
  day pattern deadlock-free.

### P2.2 Smart reminders
- Replace fixed-time `Reminders.kt`: preferred hour = median session-start hour of
  the last 30 active days (from ReviewLog); message is a concrete contract:
  "«N» reviews, ~«M» min — streak day «S»" (from `dailyPlan()` +
  `medianReviewMinutes`).

### P2.3 Tomorrow's contract
- Session-complete screen shows tomorrow's forecast (expose
  `PaceController.dueForecast` day-1 bucket + planned new budget through
  `finishAdaptiveSession`). Known-size commitments get kept.

### P2.4 Goal coverage as the central metric
- "Set as goal" action on any reader text (writes `source = "target:…"` — the
  ranking mechanism `goalDirectedPriorities` already consumes it).
- `GoalProgress(textTitle, coveragePct, unknownLemmaCount, deltaThisWeek)` computed
  in `sessionPlan` via existing `coverageFor`; rendered on the Trend layer:
  **"216 words to «goal title»"**. This is the app's most personally meaningful
  number; treat it as such.

### P2.5 Streak insurance
- `restDayCredits` in SettingsStore: +1 per 7 consecutive active days (cap 2);
  `gamificationStats` treats a 1-day gap as continuous when a credit exists
  (consuming it); StreakCard shows credits as shields. Rationale: with N=1, a single
  broken streak is the classic abandonment trigger — insure against it honestly.

### P2.6 Weekly letter
- Sunday WorkManager job → `report` table + notification → Lab → Reports screen.
  Contents: retention trend (rolling-window personalization data), top confusion
  pair of the week (P4.5; until then worst accuracy category), minutes invested,
  goal-coverage delta, one recommendation (DoctrineAdvisor pattern, widened).
- This is also the self-experimentation readout: when a parameter changes, this is
  where the owner sees the effect (checkpoint engine, P6.4, is the unbiased referee).

---

## Phase 3 — Content debt

**Intent:** light up the starved features (see audit table). All Python-pipeline
work; parallelizable with Phases 1–2. The Kotlin app needs zero changes for
P3.1–P3.4 — the renderers already exist.

### P3.1 Build-time example miner
- `tools/preprocess/mine_examples.py`: for each tier-0 note with <3 examples, query
  Tatoeba `lemma_index` for sentences containing the lemma where every other lemma
  is available at that note's unit position (unit-ordered vocab prefix), 4–10
  tokens, ranked (fewer unknowns, closer to 6 tokens), deduped; write into
  `exampleSentence2/3` at bootstrap build.
- **Why:** contextual variety is the single most evidence-backed upgrade for
  vocabulary transfer, and `exampleFor()` already rotates by rep count — it just has
  nothing to rotate.
- Gate: `test_examples.py` — every tier-0 note ≥2 examples (target 3), controlled-
  vocab audit passes, no duplicates within a note.

### P3.2 Mnemonics (A1–A2 band, ~1,200 words)
- Agent-authored batches (~150/session) in `tools/preprocess/mnemonics_a1a2.json`
  (`lemma → hook`), merged by `build_bootstrap.py`. Keyword-method style: sound-alike
  or vivid image, English-primary, ≤120 chars. **Scope discipline: below B1 only** —
  mnemonics stop earning their keep above it.
- Gate: coverage 100 % of A1/A2 tier-0; format lint.

### P3.3 Aspect labels to ≥90 % of verbs
- Extend `extended_verb_pairs.py`: pymorphy aspect tags for all verbs + curated
  partner table; aktionsart via rule lists with confidence, agent-reviewed.
- **Why:** multiplies the best grammar drill's (`ASPECT_SELECT`) coverage ~6×.
- Gate: ≥90 % of verb notes carry `aspect`; every PF with a common partner links it.

### P3.4 Second senses (top ~300 polysemes)
- Candidates from translation-field divergence + frequency (мир, язык, свет, есть…);
  agent authors sense + example + translation. Runtime mechanic needs no changes.

### P3.5 Sentence Bank
**Intent:** sentence-shaped content is currently scattered across five ad-hoc paths
(authored examples, runtime mining, cloze carriers, aspect templates, micro-reads).
One indexed corpus makes "generation" mostly mean "query", and it is the substrate
for frames (P4.1), story selection (P5), and elicited imitation (P6.1).

- `tools/preprocess/build_sentence_bank.py` → `sentence_bank` table in `tatoeba.db`:
  `(sent_id, unit_min, band, token_count, grammar_feats /*JSON: cases, tenses,
  aspect cues present*/, source)`. `unit_min` = first unit at which the sentence is
  100 % readable against the unit-ordered vocab prefix.
- Runtime DAO: `sentencesFor(unitMax, requiredLemma?, requiredFeat?, limit)`.
- Gate: `test_sentence_bank.py` — unit_min sanity; ≥N sentences per band from unit 3.

### P3.6 Card-content linter
**Intent:** make bad cards unshippable instead of discoverable-in-use.
- JVM test `CardContentLintTest`: load the real JSONL through `CardFactory` +
  `buildPrompt` for every card type × note; assert non-blank prompt, cloze actually
  blanked, CHOICE ≥2 distinct options, expectedAnswer parseable by MorphologyEngine,
  explanation present on grammar types. PR run samples 500 notes (seeded); nightly
  runs full.

---

## Phase 4 — Generation (the transfer revolution)

**Intent:** this phase is the answer to the central diagnosis. Grammar drills stop
being `(one lexeme, one frozen carrier)` and become `(concept, freshly composed
carrier)`. Production climbs a ladder from chunks to novel sentences. The learner
can no longer pass by remembering strings; only the skill passes.

### P4.1 Frame mining + curation
- `tools/preprocess/mine_frames.py`: dependency-parse Tatoeba (natasha/slovnet) →
  extract high-frequency clause templates as typed slot patterns:
  ```json
  {"slots":[{"role":"subj","pos":"noun","feats":"NOM_SG","anim":true},
            {"role":"verb","feats":"PAST_M","aspect":"PF"},
            {"role":"obj","pos":"noun","feats":"ACC_SG","anim":false}],
   "fixed":["вчера"], "en_frame":"Yesterday {subj} {verb} {obj}"}
  ```
- Agent curates to ~200 validated frames (`frames.json` → `frame` table in
  content.db). Curation standard: natural for ANY slot-compatible filler — reject
  frames whose naturalness depends on specific lexemes (the lesson of the old
  transitive-only "___ этот вопрос" aspect carrier bug).
- Gate: `test_frames.py` — every frame realizes grammatically for 20 random
  known-inventory fills (paradigm agreement verified); en_frame renders.

### P4.2 Runtime FrameRealizer
- `generation/FrameRealizer.kt`: `realize(frame, inventory, seed) → (ru, en,
  targetSlot)` via MorphologyEngine (inflection + agreement + government).
  Deterministic per `(frameId, epochDay, cardId)`: stable within a day, novel
  across days.
- Gate: property tests — 1,000 realizations, all pass `agreementOk`, target slot
  present, no unknown lemma ever used (i+0 vocabulary is a hard invariant).

### P4.3 Concept-level scheduling
- New `CardType.CONCEPT_APPLY`, one card per GrammarConcept, attached to the
  concept's lesson note (AppDatabase v20→v21 migration inserts them; teach-before-
  test gate applies unchanged).
- Prompt realized at display time by FrameRealizer over a frame exercising the
  concept's features; the FSRS state belongs to the *concept*, so "GEN after нет" is
  now a schedulable, decaying, reviewable memory of its own.
- Per-note drills taper: once a concept's CONCEPT_APPLY reaches `reps ≥ 4 ∧
  consecutiveCorrect ≥ 3`, sibling per-note drills for that concept stop being
  *introduced* (selection-level gate only; existing card state untouched).
- `CardPedagogy` profile: STRONG evidence, transfer-heavy stage weights
  (≈ 0.10/0.60/1.20/1.60).
- Gate: sim shows concept cards surface and per-note drills taper; selector check:
  no drill shows the same realized carrier twice consecutively.

### P4.4 Production ladder L1–L3
- **L1 — CHUNK cards:** top 2–3 collocations per tier-0 word (44k-row collocation
  table) become chunk notes (`kind=chunk`, linked to parent); typed production
  ("strong tea" → «крепкий чай»). Introduced only after the parent word graduates
  recognition. *Why: chunks are the actual unit of fluent speech, and the data is
  already on the device, shown passively in lessons, never drilled.*
- **L2 — `CardType.TRANSFORM`:** sentence-bank sentence + instruction ("negate",
  "past tense", "subject → мы"); expected answer derived by
  `transform/Transformer.kt` (tense/person/number/negation rules over the engine).
  Only transformations with a unique deterministic answer are generated. *Why:
  infinite, novel, exactly-gradeable production with zero new content — and it
  trains precisely what Russian demands: re-inflecting a held sentence.*
- **L3 — `CardType.NOVEL_PRODUCE`:** FrameRealizer English cue → typed Russian.
  Graded by lemma-set match + per-token feats match via `analyze` (word order free);
  partial credit names the failed slot. *This is the ladder's payoff: producing a
  sentence that has never existed before.*
- Every new CardType requires the full kit: enum + CardPedagogy profile +
  buildPrompt branch + grader/AnswerNormalizer handling + migration seeding +
  linter coverage + sim inclusion + testTags.

### P4.5 Confusion matrix → contrastive drills
**Intent:** `diagnosticFeedbackFor` already classifies *which wrong form* the
learner produced (e.g., genitive when dative was asked) — a confusion-matrix
observation currently rendered as a string and discarded. Persist it and act on it.
- Extract classification into `review/AnswerDiagnosis.kt` returning
  `Diagnosis(expectedKey, producedKey)`; UI strings unchanged.
- New table `confusion_event(expectedKey, producedKey, cardType, at)` (v21→v22).
- Planner rule: pair with ≥4 events in 14 days → inject a **contrastive pair**: two
  realizations of the *same frame* differing only in the confused feature,
  back-to-back (paired-insertion hook in `NextCardSelector`).
- Weekly letter (P2.6) upgrades to report the top confusion pair.

---

## Phase 5 — Input at scale (reading + listening)

**Intent:** invert the app's economics at maturity. Today reviews are flashcards
with reading as garnish; the end state is **reading as the primary review modality**
with flashcards as the remedial path for what reading fails to maintain — which is
what naturalistic acquisition looks like. Requires: real texts (5.1), smart
selection (5.2), a listening modality (5.3), and evidence credit (5.4).

### P5.1 Serial narratives
- `tools/preprocess/stories/` — agent-authored in installments. Start: A1 series
  ×20 chapters + A2 ×20 (80–300 words each, recurring cast, chapter vocab ⊆ unit
  prefix + ≤3 glossed new words). *Why serial: narrow-reading research — recurring
  protagonists/settings slash per-text vocabulary load while keeping engagement. One
  20-chapter series beats 100 disconnected snippets.*
- Validated by the existing controlled-vocab auditor + new `test_stories.py`
  (band, length ladder, gloss budget, `seriesId/chapter` continuity).
- Textbook-derived reader texts (`source = textbook:*`) are retired band-by-band as
  original content reaches parity — complete before any public distribution.

### P5.2 Due-word-aware selection
- Reader scoring gains `dueOverlap` = count of text lemmas with cards due ≤48 h;
  comparator = (coverage window, dueOverlap desc, series order). Merge
  `consolidationReader` into this scorer. *The day's chapter deliberately smuggles
  in the words FSRS wants reviewed.*

### P5.3 Listening mode
- Reader variant on any text: sentence revealed only after `RussianTextToSpeech`
  playback; early tap-to-reveal = listening miss. Schedule entries tagged
  `mode=listening` share the ReadingSchedule SRS, alternating modality per text rep.
  Checkpoint questions reused as the comprehension gate.
- Built so a future build-time audio pack is a pure asset swap (decision log).

### P5.4 Reading/listening evidence credit — closes the input loop
- On `completeScheduledReading`: for each text lemma with a card due ≤7 days —
  no lookup/reveal this session → passive positive evidence (P0.2 path; capped);
  looked up/revealed → weak negative (stability ×0.9, floored, never a state
  change).
- Gate: sim with a "reader-heavy" profile maintains ≥60 % of due load through
  reading at B1-band inventory without accuracy collapse (calibrate once, freeze).

---

## Phase 6 — Communication & proof

### P6.1 Elicited imitation (`CardType.SPEAK_SENTENCE`)
- TTS plays a sentence-bank sentence (unit-appropriate, 5–9 tokens); learner repeats
  from memory; ASR tokens aligned (order-aware, per-token normalized Levenshtein);
  ≥80 % = pass; per-lemma evidence for content words, strength **PRACTICE** (ASR
  noise — principle 2). *Why: elicited imitation is among the best-validated
  proficiency probes in SLA — you cannot repeat a sentence above your competence;
  it forces parsing, not parroting.*

### P6.2 Dialogues
- Content: `dialogue(id, unitMin, function, title)` +
  `dialogue_node(id, dialogueId, speaker, ru, en, acceptableJson, nextIds)` in
  content.db; agent-authored per curriculum function label (P6.5), controlled-vocab
  validated. Covers the register/pragmatics dimension (ты/вы, formulaic speech)
  nothing else in the app touches.
- Runtime `DialogueScreen` + `DialogueEngine` state machine. Learner-turn scaffold
  ladder by unit mastery: choice → word bank → typed → spoken. Near-miss grading via
  MorphologyEngine (acceptable-set match modulo inflection slips, which get
  corrective feedback). Turns emit chunk/lemma evidence.

### P6.3 Micro-composition
- Optional post-session daily task: "2 sentences about «topic» using «3 due words»".
  Grading tiers: (1) target lemmas present (`analyze`); (2) local agreement checks
  (adj–noun pairs; preposition→case from a fixed government table); (3) nearest-
  frame structural hint. Verified use → STRONG production evidence; unparseable
  extras are never penalized. *Principle: pushed output drives acquisition even when
  grading is partial — grade what you can prove, stay silent on the rest.*

### P6.4 Checkpoint engine — the unbiased referee
**Intent:** the app currently grades its own homework — all evidence comes from
scheduler-chosen moments, which biases everything optimistic. Monthly independent
assessment is the only honest measure of whether "known" is real, and it is the
outcome metric for all self-experiments.
- Monthly WorkManager-prompted session (~15 min, clearly labeled, **writes no FSRS
  state**): (a) 20 graduated notes sampled uniformly over graduation age; (b) 10
  novel frame realizations over "known" inventory; (c) 1 unseen sentence-bank text
  at claimed coverage, with comprehension questions.
- Stores `checkpoint_result(at, itemKey, kind, predictedP, correct)`; Lab report:
  calibration curve (predicted retrievability vs observed) + coverage-claim vs
  comprehension.
- Bounded feedback loop: graduation thresholds adjustable ±20 % by calibration,
  applied through the existing model-snapshot/rollback mechanism only.

### P6.5 Curriculum DAG + capstones
- `tools/preprocess/units.yaml`: per unit — prerequisite *concepts* (not unit
  numbers), a function label ("introducing yourself", …), a capstone reference
  (story chapter or dialogue id).
- `unitMastery` unlock = prereq concepts introduced ∧ sliding window (current +2) —
  generalizes the existing `previewUnit` peek; strict-linear gating removed. *Why:
  the linear gate has already caused one deadlock and keeps needing exceptions; a
  DAG matches how difficulty actually distributes and lets the app answer "what's
  the cheapest next capability?"*
- Capstone = completing the unit's referenced dialogue/story with ≤2 misses —
  displayed proof, **not** a hard lock.
- Gate: sim reachability re-verified against the DAG.

---

## Phase 7 — Operating mode (permanent)

- Content releases: agent authors installment (stories/dialogues/mnemonics) →
  pipeline gates → `build_bootstrap.py` → sim smoke → ship.
- Every scheduler/parameter PR: `simCheck` + checkpoint-calibration non-regression
  note.
- Self-experiments: within-subject (alternate configuration by ISO week via the
  existing bandit/snapshot machinery); weekly letter reports, checkpoint engine
  referees.
- Quarterly: retention-by-card-type review → CardPedagogy weights adjusted or weak
  formats retired (data, not taste).

---

# Part IV — Dependency graph and first session

```
P0.2 evidence bus ─────► P5.4 reading credit ────► input-driven review
P0.1 morphology ───────► P4.2 realizer ─► P4.3 concept cards / P4.4 L2,L3 ─► P4.5 contrastive   ◄── CRITICAL PATH
P0.3 simulator ────────► gates every scheduler/curriculum change from Phase 4 on
P1.1 state split ──────► P1.2 study surface, P2.1 widget/notification
P3.5 sentence bank ────► P4.1 frames, P5.1/5.2 selection, P6.1 imitation
P6.4 checkpoints ◄────── needs P0.2 + P3.5; feeds Phase 7 forever
```

**First-session blitz** (maximally unblocking, mutually independent):
1. **P0.2** evidence-bus refactor + v20 migration (golden-equivalence tests first).
2. **P1.1 step 1** state split inside ReviewViewModel + NavState (zero visual change).
3. **P0.1 build-time** `build_paradigms.py` + gold tests.
4. **P0.3** sim harness skeleton with the no-deadlock assertion.
5. **P3.1** example-miner script + first mined batch (units 1–20).

After that: phase order, item order, one PR at a time, gates green. When in doubt,
re-read Part I — the principles decide.
