# SibirskySpeak — Adaptive Tutor Final Plan

> **⚠ SUPERSEDED — phases G1–G12 below have shipped** (see git history: "Implement
> Adaptive Tutor roadmap phases G1-G12"). The "audit below"'s numbers (e.g. "Grammar
> concepts: 40") are stale — the real count is 112 (`GrammarConcepts.kt`) with the
> full `family`/`stage` schema already in place. For current state use
> `docs/PATCH_NOTES.md` / `docs/IMPLEMENTATION_MASTERLIST.md`; read this doc only
> for the rationale behind decisions already made.

**Status:** successor roadmap to `docs/MASTER_PLAN.md`, whose Phases 0–7 are
substantially implemented (see the audit below). This document reconciles the
2026-07 "Adaptive AI-Generated Russian Learning System A1–C2" proposal against
the code that actually exists, keeps what fills real gaps, rejects what
conflicts with the app's axioms, and lays out the next execution phases.
Read `MASTER_PLAN.md` Parts I–II first — its hard constraints, decision log,
and pedagogical principles remain law and are not restated in full here.

---

# Part I — Review verdict

## The one-sentence verdict

The proposal describes, mostly accurately, the app this codebase was *already
rebuilt into* during Master Plan Phases 0–7 — its value is not its architecture
(which largely exists) but a handful of specific gaps it names correctly, and
its main danger is that it re-imports multi-user/product assumptions and
runtime-AI patterns that the decision log has already ruled out.

## Hard constraints (inherited, unchanged)

1. **Offline forever.** No backend, no network at runtime.
2. **No runtime LLM.** All AI is build-time (the agent + `tools/preprocess`
   pytest gates); the device assembles, grades deterministically, measures.
3. **Android only**, Kotlin + Compose, single activity.
4. **All content ships as build-time-validated bundled assets.**
5. **N=1.** One learner, one device (+ backups). No fleet, no anonymous users.

## What the proposal asks for that ALREADY EXISTS

Verified in code, 2026-07:

| Proposal section | Reality in the codebase |
|---|---|
| §2.1 CEFR is the hard gate | `LearningRepository.effectiveCefrOrdinal()` — new-card selection is capped at the first CEFR band below 60 % tier-0 mastery (`CEFR_GATE_MASTERY_THRESHOLD`), +1 stretch level (`CEFR_STRETCH_LEVELS`). `CardDao.getNewCardsOrdered(limit, cefrOrdinal)` enforces it. Tier = provenance, level = readiness is already the runtime semantics. |
| §2.2 AI is the curriculum author, validated without native review | This *is* the operating model: the build-time agent authors content into `tools/preprocess/`, and `audit_curriculum.py` + the pytest wall (`test_curriculum*.py`, `test_bootstrap_quality.py`, controlled-vocab/stress/gloss checks) are the validation layers. The proposal's Generator/Annotator/Critic/Regenerator roles map onto the existing author→gate→iterate loop, not onto missing infrastructure. |
| §2.3 Teach meaning before forms | LESSON cards + concept gating (`lockedConceptIds()`, probation until first success) — the teach-before-test invariant is implemented and load-bearing. |
| §2.4 Recognition / production / listening tracked separately | Card types are the skill axes (RU_TO_MEANING vs MEANING_TO_RU vs AUDIO_TO_RU vs DICTATION vs SPEAK…), each with independent FSRS state; `CardPedagogy` assigns per-type `LearningFacet` (MEANING/FORM/LISTENING/PRONUNCIATION/SYNTAX/MORPHOLOGY), `EvidenceStrength`, stage weights, cognitive cost; `WorldModel` holds per-skill Bayesian ratings. No concept has "a single mastery value" today. |
| §2.5 Errors drive adaptation (partially) | `review/AnswerDiagnosis.kt` classifies *which wrong form* was produced (CASE_FILL, ADJ_AGREE, VERB_FORM); `confusion_event` table persists it; the planner injects contrastive frame pairs when a confusion recurs (Master Plan P4.5, shipped). |
| §2.6 / §12 Reading & listening first-class | Sentence bank + serial stories + due-word-aware reader selection (`dueOverlap`), listening mode over any text, and reading/listening **evidence credit** into the scheduler via the evidence bus (capped passive credit, never a state transition). The "closed input loop" the proposal wants is Master Plan P5, shipped. |
| §11 Activity types | Nearly the full menu exists: LESSON, recognition/production cards, CLOZE, DICTATION, SENTENCE_BUILD, CASE_FILL/ADJ_AGREE/VERB_FORM/GENDER_ID/ASPECT_SELECT, CONCEPT_DRILL, **CONCEPT_APPLY** (concept-level FSRS over freshly realized frames), **CHUNK/TRANSFORM/NOVEL_PRODUCE** (production ladder), **SPEAK_SENTENCE** (elicited imitation), dialogues (`generation/DialogueEngine.kt`), micro-composition (`generation/MicroComposition.kt`). |
| §15 Structured generation, reject loose content | Frames are typed slot patterns (`frames.json` → `FrameRealizer.kt` over `MorphologyEngine`), property-tested for agreement; content that fails the pipeline never ships. Generation is deterministic composition, exactly as the axioms require. |
| §16.3 Detect bad items → quarantine | The N=1 substitutes exist and are better-suited: the learner simulator (`simCheck` CI) catches pedagogy regressions, `ItemDifficulty`/telemetry catch per-item outliers, and the **checkpoint engine** (`buildCheckpointSession`, writes *no* FSRS state) is the unbiased referee for whether "known" is real. |
| §18.1 Placement | `learning/PlacementTest.kt` exists. |

**Conclusion:** treat the proposal's Sections 2, 5.3(skills), 7, 11, 12, 15, 16
as *descriptions of the status quo*, not work items.

## What the proposal gets RIGHT (genuine gaps, adopted)

1. **A1 claims full cases it shouldn't (§4.3).** `a1_starter.py` drills `GEN`,
   `DAT`, `INS`, `PREP` as full concepts inside A1. The chunk-preview model
   (`GEN_CHUNK_POSSESSION`, `PREP_CHUNK_LOCATION`, `DAT_CHUNK_EXPERIENCER`,
   `INS_CHUNK_WITH`, `GEN_CHUNK_ABSENCE`) with full singular production
   unlocking at A2 and plural declension at B1 is pedagogically correct and
   matches the existing chunk machinery (CHUNK cards). → **Phase G1.**
2. **The concept graph has no edges (§5).** `GrammarConcepts.kt` has 40 concepts
   but zero prerequisite or interference relations (verified: no such fields).
   Edges are what let the scheduler answer "what unlocks the most" and make
   contrastive-pair selection principled instead of confusion-log-only. This is
   also the missing substrate for the never-landed Master Plan P6.5 curriculum
   DAG. → **Phase G2.**
3. **Error taxonomy is narrow (§10).** Diagnosis covers 3 card types and only
   form-confusion. A named category enum (CASE_ROLE vs CASE_ENDING,
   ASPECT_CHOICE, MOTION_CONSTRUAL, AGREEMENT, LISTENING_DISCRIMINATION,
   LEXICAL_CONFUSION, …) over *all* gradeable card types, each category mapped
   to a deterministic repair (a contrastive frame pair — machinery already
   shipped), is a real upgrade. → **Phase G3.**
4. **Effective level ignores skill balance (§8.3).** `effectiveCefrOrdinal()`
   counts mastered tier-0 notes per band; a learner strong on recognition but
   weak on production/listening ratchets up anyway. `effective = min(spine
   mastery, weakest-core-skill + allowed_gap)` using the `WorldModel` ratings
   the app already maintains is cheap and honest. → **Phase G2.**
5. **No content versioning (§17).** Verified: no curriculum manifest, no
   version/checksum anywhere in `AssetBootstrap.kt` or the pipeline. The
   stale-snapshot problem the proposal describes is real even at N=1 (app
   updates reseed content with no integrity check or report). → **Phase G4.**
6. **No session-time-budget planning (§9.2).** `sessionPlan()` has budgets and
   doctrines but no explicit minutes parameter; `CardPedagogy.cognitiveCost`
   already provides the per-card currency to plan a 5/15/45-minute session
   honestly. → **Phase G5.**
7. **Domain overlays & register transformation (§13.6, §14).** Tier 2 is a
   static vocabulary domain; the proposal's stronger idea — same grammar,
   domain-flavored *examples* (frame realization over a domain-preferred
   inventory) and B2+/C1 neutral↔formal TRANSFORM drills — extends two engines
   that already exist (FrameRealizer, Transformer). → **Phase G6.**
8. **Missions / exit tickets (§11.6, §18.2).** Master Plan P6.5 (units.yaml
   DAG + capstones) never landed. Fold its capstone idea into the exit-ticket
   form here: a unit exit = short mixed proof (recognize / produce / listen /
   read) built from existing task types, displayed proof, never a hard lock.
   → **Phase G6.**

## What the proposal gets WRONG (rejected, with reasons)

| Proposal item | Rejection reason |
|---|---|
| **A0 boot camp** (§13.1, §20) | Decision log: the sole user reads and types Cyrillic. Ruled out; do not re-litigate. |
| **Full per-sentence KC graph with per-KC×per-skill FSRS quadruples** (§5, §8.2) | Over-engineered duplicate at N=1. The app already decomposes skill × item three ways: card types (skill axes with independent FSRS state), `LearningFacet` + `EvidenceStrength` (evidence bus), and CONCEPT_APPLY (concept-level FSRS). Annotating every sentence with `pronoun_я_nominative`-grade KCs multiplies authoring cost ~10× for a mastery model no one can calibrate on one learner. **Adopted granularity: concept + facet.** Revisit only if checkpoint calibration (P6.4 data) shows concept granularity measurably too coarse. |
| **Runtime AI anywhere** — "error classification prompts", "AI regeneration triggers", AI-generated repair sets on device (§10.3, §16, §19 P4) | Violates axiom 2. Error classification stays deterministic (`AnswerDiagnosis` + MorphologyEngine `analyze`); repair sets are *composed* on device from validated frames, never generated by a model; regeneration happens at build time when telemetry/checkpoint data flags an item. |
| **Multi-learner machinery** — fleet item metrics ("many learners choose different wrong answers"), learner reports, cross-device migration messaging (§16.2–.3, §17.3) | N=1. The substitutes are already built and better: the learner simulator (CI), per-item telemetry, and the monthly checkpoint engine. Keep the *per-item* metrics (times seen, correct rate, latency — largely present in telemetry/ItemDifficulty); drop everything population-shaped. |
| **The 8-weight linear priority formula** (§9.1) | Violates principle 3 ("the scheduler is FSRS; everything else advises"). The app's layered design — FSRS due-date math, advised by pace doctrine, world model, bandit, goal priorities — is strictly more calibratable than one hand-tuned scalar formula, and it's already shipped. The formula's *inputs* (error-repair value, prerequisite unlock value) enter as advisors in G2/G3 instead. |
| **C2 band + C2 subtracks** (§4.1, §13.7) | Not rejected on principle, but deferred: the spine currently ends at C1 and the owner is years from C2. The proposal's one good C2 rule — rare vocabulary should be reader-triggered, not queue-forced — is *already* how tier-1 matrix words behave. Revisit when B2 spine mastery is real. |
| **YAML content-object schema replacing current fields** (§6) | The JSONL note schema + Card/CardType decomposition already carries the load (cefrLevel, tier, unit, concept, tags, frequency rank…). Adopt missing *fields* where a phase needs them (e.g. `interference_set` → concept edges in G2; `domain` → G6) rather than a wholesale schema migration. |

---

# Part II — The phases (G-series)

Ordering: pedagogy-correctness first (G1), then the model/graph work that
several later items hang off (G2), then diagnosis→repair (G3), then integrity
and convenience (G4–G5), then breadth (G6). Standing gates from
`MASTER_PLAN.md` Part II apply to every PR (unit tests, lint, pytest +
`build_bootstrap.py`, migrations with tests, `simCheck` for anything touching
scheduling/gating/curriculum).

## Phase G1 — A1 case-chunk rework (content + gating)

**Intent:** stop A1 from claiming full case mastery; teach case *functions* as
fixed chunks first, forms as systems later. Highest pedagogical value per hour
in the proposal.

- New `GrammarConcept` ids in `GrammarConcepts.kt` **and** matching `"concept"`
  values in `a1_starter.py` (the Kotlin/Python sync has no compile-time link —
  extend `audit_curriculum.py` to cross-check the two id sets, closing that
  hole permanently):
  `GEN_CHUNK_POSSESSION`, `GEN_CHUNK_ABSENCE`, `PREP_CHUNK_LOCATION`,
  `DAT_CHUNK_EXPERIENCER`, `INS_CHUNK_WITH`.
- A1 notes currently drilling full `GEN/DAT/INS/PREP`: re-author as chunk
  previews (fixed phrases — «у меня есть», «мне нравится», «в школе», «с
  другом») drilled via CHUNK/CLOZE, **no** free-form CASE_FILL on those cases
  until A2. Full singular case concepts move to the A2 band; plural declension
  concepts to B1 (audit current placement, move what's misplaced).
- **Learner-state migration:** existing cards carry `gramConcept = "GEN"` etc.
  Map old→new concept ids in an `AppDatabase` migration (concept id remap on
  `Card.gramConcept` / `Note.conceptId`) so no FSRS state is orphaned; a
  chunk-concept LESSON counts as introduced if its parent full-case LESSON was
  already reviewed (never re-lock what the learner has passed).
- Gates: pytest + curriculum audit green with the new id cross-check; sim
  reachability unchanged; migration test proves no card loses its concept gate.

## Phase G2 — Concept edges + skill-balanced effective level

**Intent:** give the 40-concept inventory the structure everything else keeps
needing (prerequisites, interference), and make the CEFR gate honest about
skill balance.

- `GrammarConcept` gains `prerequisites: List<String>` and
  `interferesWith: List<String>` (hand-authored, ~40 concepts; the agent
  authors, a unit test validates acyclicity and id validity).
- Consumers:
  - Gating: a concept's LESSON only surfaces when prerequisites are introduced
    (generalizes today's unit-order implicit assumption; removes the class of
    deadlock the sim was built to catch — assert it there).
  - Contrastive drills: `interferesWith` seeds contrastive frame pairs
    *proactively* at introduction time, not only after 4 logged confusions.
  - Advisor: "prerequisite unlock value" enters new-material selection as an
    ordering hint (never overriding FSRS due math).
- **Skill-balanced effective level:** `effectiveCefrOrdinal()` becomes
  `min(spineMasteryOrdinal, weakestCoreSkillOrdinal + allowedGap)` where core
  skills = production and listening ratings from `WorldModel`, mapped to CEFR
  ordinals by fixed thresholds. `allowedGap = 1`. Surface the binding
  constraint in Lab ("level capped by listening").
- Gates: sim — no deadlock under edge gating, reachability bound holds;
  golden test that a balanced learner's effective level is unchanged.

## Phase G3 — Error taxonomy v2 → deterministic repair

**Intent:** every wrong answer teaches the system something, across all card
types, with named categories and an automatic repair path. (Proposal §10,
constrained to deterministic classification.)

- `review/AnswerDiagnosis.kt`: add `enum class ErrorCategory` (CASE_ROLE,
  CASE_ENDING, PREPOSITION_CASE, GENDER, NUMBER, AGREEMENT, VERB_CONJUGATION,
  TENSE, ASPECT_CHOICE, MOTION_CONSTRUAL, REFLEXIVE, WORD_ORDER, ORTHOGRAPHY,
  LISTENING_DISCRIMINATION, LEXICAL_CONFUSION, REGISTER — add only categories a
  deterministic classifier can actually emit). `Diagnosis` gains `category`.
- Extend `classifyAnswer` coverage: ASPECT_SELECT (chose the other aspect),
  TRANSFORM/NOVEL_PRODUCE (per-slot feats mismatch via `MorphologyEngine.
  analyze` — the slot that failed names the category), DICTATION/AUDIO
  (near-homophone table → LISTENING_DISCRIMINATION), MEANING_TO_RU
  (ConfusablePair hit → LEXICAL_CONFUSION).
- `confusion_event` gains the category column (migration); repair lookup =
  `category → contrastive frame recipe` table (pure Kotlin), realized on device
  from validated frames. Immediate repair injection when the same category
  recurs within a session; scheduled contrastive pair otherwise (existing P4.5
  hook).
- Weekly letter + Lab report by category; checkpoint engine samples the worst
  category monthly.
- Gates: classifier unit tests per category (gold set of wrong answers); sim
  with an error-prone synthetic learner shows repair injection without review
  starvation.

## Phase G4 — Curriculum manifest + integrity check

**Intent:** make content updates observable and stale snapshots impossible to
miss. (Proposal §17, scoped to one device.)

- `build_bootstrap.py` emits `curriculum_manifest.json` next to the JSONL:
  `curriculumVersion` (date-slug), schema version, per-CEFR-band and per-tier
  note counts, content checksum, frames/stories/dialogues asset versions.
- `AssetBootstrap` on launch: compare stored vs bundled manifest; on change,
  reseed as today but record a **migration report** row (what appeared/moved/
  retired) shown once in Lab — no learner-facing scary banners; at N=1 the
  owner *is* the operator.
- The pytest wall asserts the manifest matches the built JSONL (counts,
  checksum), so the two can never ship out of sync.
- Gates: unit test — mismatched manifest triggers reseed + report exactly
  once; backup/restore round-trip preserves the stored manifest.

## Phase G5 — Time-budgeted sessions

**Intent:** "how much time do you have?" as a first-class planner input.
(Proposal §9.2, on top of the existing budget/doctrine machinery.)

- `sessionPlan(timeBudgetMinutes: Int?)`: convert budget to a cognitive-cost
  allowance via `CardPedagogy.cognitiveCost` calibrated against the learner's
  own `medianReviewMinutes`; fill in priority order (urgent reviews → one
  repair → new/contrast → input task), truncating whole blocks, never
  mid-block. Presets surface as 5/15/45 chips on the start surface; micro
  (widget/notification) sessions are the existing 5-minute path.
- Doctrine still sets *composition* bias; budget sets *size*. FSRS due math
  untouched — an over-budget due load spills to tomorrow's contract, which the
  session-complete screen already shows.
- Gates: sim day-patterns of all-5-minute vs all-45-minute sessions both stay
  deadlock-free and debt-bounded.

## Phase G6 — Breadth: domain overlays, register ladder, exit tickets

**Intent:** the proposal's genuinely new surface area, built entirely on
shipped engines. Do after G1–G5; items are independent.

- **Domain overlays (§14):** tag frames and sentence-bank rows with `domain`;
  a Settings preference biases FrameRealizer slot fills and reader/listening
  selection toward the preferred domain's inventory (tier-2 words where level
  permits). Grammar order never changes — same frame, «Министр дал ответ
  журналисту» instead of «Я дал книгу другу».
- **Register ladder (§13.6):** extend `transform/Transformer.kt` with
  neutral↔formal transformation drills at B2+ (authored transformation pairs,
  build-time validated, TRANSFORM card type — only pairs with a unique
  deterministic answer ship).
- **Unit exit tickets (§18.2) + capstones (Master Plan P6.5 revival):** a unit
  exit = short mixed proof (one recognition, one production, one listening,
  one micro-reader question) assembled from existing task types over that
  unit's inventory; result displayed as proof (and fed to the evidence bus at
  PRACTICE strength), **never a hard lock**. The G2 concept edges provide the
  DAG that P6.5 wanted; strict-linear unit gating relaxes to
  prereq-concepts + sliding window.
- Gates: controlled-vocab audit on all new authored content; sim reachability
  against the relaxed unit gate.

---

# Part III — Dependency sketch and first session

```
G1 (A1 chunks)          — independent; content + one migration
G2 (edges + eff. level) — enables G3 proactive contrast, G6 exit-ticket DAG
G3 (taxonomy → repair)  — needs G2 only for proactive seeding; else independent
G4 (manifest)           — independent
G5 (time budgets)       — independent
G6 (breadth)            — after G1–G2; internally parallel
```

**First-session blitz** (mutually independent):
1. G1 concept-id cross-check in `audit_curriculum.py` (closes the Kotlin↔Python
   sync hole even before the chunk rework lands).
2. G4 manifest emission + pytest assertion (pipeline side only).
3. G2 `GrammarConcept` edge fields + acyclicity test (data authoring, no
   consumer change yet).

Then phase order, one PR at a time, gates green. When in doubt, `MASTER_PLAN.md`
Part I principles decide — this document only extends them.

---

# Part IV — Curriculum optimality review (the content IS the product)

**Status:** second-pass deep review, 2026-07. Parts I–III fixed the *machinery*
around the curriculum. This part reviews the curriculum itself — what is
taught, in what order, at what density — measured directly from
`bootstrap_notes.jsonl` and benchmarked against external standards (TORFL
level inventories, standard university scope-and-sequence, SLA sequencing
research). The engine is now world-class; the syllabus it schedules is not.
These findings supersede any softer wording above.

## The measurements (2026-07, re-measure before acting)

| Fact | Value | Implication |
|---|---|---|
| Grammar concepts | **40**, spanning A1→C2 | A serious A1→C2 inventory is 100–150 teachable items; TORFL-B1 alone tests all 6 cases sg+pl, aspect across tense/imperative/infinitive, unprefixed+prefixed motion, and complex subordination |
| Lessons per concept | **exactly 1** (one LESSON note each) | ASPECT — the hardest category in Russian — is *one card* at unit 11, then silence until ASPECT_NUANCE (C1, unit 40). MOTION is one card at A2. Macro-concepts are being "introduced" the way one introduces мой/твой |
| Last unit containing any new grammar | **unit 49** | Units 50–262 — **164 units, 5,867 notes, 95 % of tier-0** — introduce zero grammar. Above ~B1 the "curriculum" is a CEFR-labeled frequency list with drills |
| A1 case sequencing | ACC→GEN→PREP→DAT→INS in **consecutive units 5–9** | Textbook interference-maximizing design; every standard curriculum spaces cases across a year and interleaves them with verb/topic work |
| A1 composition | 515 of 615 notes tagged `matrix/curated` (frequency-promoted); **2 numerals**, 11 pronouns, 3 interjections | The A1 band is a frequency band, not a survival syllabus. A learner "completing A1" cannot count to ten, tell time, or name a date |
| Cumulative vocab per band | A1 615 → A2 1,231 → B1 2,330 → B2 3,878 | The *counts* track TORFL lexical minimums (~780 / 1,300 / 2,300) well — the size is right, the composition and grammar density are the problems |
| Phonology / intonation strand | **none** (STRESS_MARK retired as legacy) | No ы/и, ш/щ, hard/soft discrimination; no IK-1/2/3 intonation contours; no vowel-reduction or fast-speech listening. Listening tasks exist but nothing *teaches* the sound system they depend on |
| Mixed-level units | 128 (A2/B1), 156 (B1/B2), 196 (B2/C1), 220 (C1/C2) | Band-boundary hygiene bug in the pipeline |

## Diagnosis

The app has the inverse of the usual problem. Language apps typically have
rich syllabi and dumb scheduling; SibirskySpeak has arguably the most
sophisticated scheduling layer of any Russian app in existence (FSRS with
on-device refit, evidence-graded modalities, concept-level generative drills,
checkpoint calibration) wrapped around a syllabus that is: **a good frequency
list + 40 one-shot grammar cards + no functional, phonological, or
word-formation dimension at all.** Every hour invested in Part II phases
multiplies content that, above unit 30, mostly isn't there.

Concretely missing strands, verified absent from the 40-concept inventory
(cross-checked against TORFL grammar inventories and first/second-year
university syllabi):

- **Numerals & quantification** — cardinals 1–100 at A1, ordinals and
  time-telling at A2, numeral government (2/3/4 + gen sg, 5+ + gen pl) before
  the existing B1 `NUMERAL_CASE`, collective numerals at B2.
- **Question formation & connectors** — interrogative system, intonation
  questions (IK-3), and the и/а/но contrast (а is a famously untranslatable
  A1 item; it appears in no lesson).
- **Plural case system** — currently implied inside the singular case
  concepts; must be explicit B1-band concepts (the Part I §4.3 adoption said
  this; it is repeated here because *no concept ids exist for it at all*).
- **Aspect as a strand** — currently 1 lesson + C1 nuance. Needs ≥6 staged
  concepts: past pf/impf contrast → future (буду + impf vs pf present-form) →
  aspect in infinitive after phase/modal verbs → imperative aspect →
  negated contexts (не + pf = warning) → C1 nuance.
- **Motion as a strand** — needs ≥5 staged concepts: идти/ехать →
  ходить/ездить (habitual/round-trip) → carried/led/driven set
  (нести/вести/везти) → transport & context choice → prefixed system
  (existing `MOTION_PREFIX` becomes the strand's stage 5, itself split by
  prefix family: при-/у-, в-/вы-, под-/от-, до-, за-, пере-, про-).
- **Verbs of position & placement** — стоять/лежать/висеть vs
  ставить/класть/вешать; a top-ten error source, absent.
- **Verbal government (управление)** — заниматься + ins, бояться + gen,
  помогать + dat… as explicit micro-concepts with contrast frames, not
  incidental example-sentence facts.
- **Time-expression system** — в + acc (days) vs в + prep (months/years),
  через/назад, dates-as-genitive, duration vs deadline (acc vs за + acc).
- **Word formation** — the single highest-transfer vocabulary skill at B1+:
  prefix semantics (по- inchoative, за- ingressive, пере- redo/over…),
  suffix families (-ость, -ение, -тель, -ник), diminutives, and
  aspect-by-prefixation. Feeds reading coverage directly (a learner who knows
  писать + prefix semantics reads записать/переписать/подписать free).
- **Indefinite pronouns** — -то vs -нибудь (classic B1 concept, absent),
  ни-series with genitive.
- **Negation grammar** — не vs нет, genitive of negation, ни…ни.
- **Phonology & intonation** — see G10 below.
- **Information structure** — Russian word order is not "free"; it encodes
  theme/rheme. NOVEL_PRODUCE currently grades word-order-free (correctly
  lenient), but nothing ever *teaches* why «Книгу купил Иван» answers a
  different question than «Иван купил книгу». One C2 `INVERSION_EMPHASIS`
  card is not a treatment.
- **Upper-register density** — 5 concepts for all of C1, 5 for C2, nothing
  new after unit 49. B2→C2 needs: participle strand (formation → reading →
  transformation from который-clauses), gerund stages, nominal style
  (already `NOMINALIZATION`, needs stages), concessives (хотя/несмотря на/
  пусть), connective semantics (однако/причём/впрочем/зато), particle
  semantics (же/ведь/-то/уж as *discourse concepts with contrast frames*, not
  vocabulary), formal genres (official statement, news lede, academic hedge).

## The phases (G7–G12)

Same standing gates as Part II. G7 is the flagship; G8–G12 hang off its
schema. All content authoring is the build-time agent working through the
pytest wall in installments (Master Plan Part II throughput norms apply).

### G7 — Grammar Spine 2.0: 40 → ~120 staged concepts

**Intent:** make the concept inventory as good as the scheduler that serves
it. This is the single highest-leverage investment left in the app.

- Schema: `GrammarConcept` gains `family: String` and `stage: Int` (e.g.
  family `ASPECT`, stages 1–6). Existing 40 ids become stage-1 (or sole-stage)
  members of their families — **no learner-state migration needed** for
  unchanged ids; new stages are new concepts with new LESSON notes shipped via
  bootstrap.
- Gating: stage N's LESSON surfaces only after stage N-1 is introduced *and*
  its CONCEPT_APPLY has ≥1 success (reuses the existing probation machinery).
  The G2 prerequisite edges connect *across* families (e.g. `MOTION_PREFIX_1`
  requires `ASPECT_2`, since prefixed motion is where aspect-by-prefix bites).
- Author the strands listed in the diagnosis: aspect (6), motion (5 + prefix
  substages), case-plural (4: gen/dat/ins/prep pl grouped by ending class),
  numerals (4), government (~10 micro-concepts), position verbs (2), time
  expressions (3), word formation (6), indefinites/negation (3), information
  structure (2), questions/connectors (2 at A1) — plus the B2–C2 density: full
  participle strand (4), gerunds (2), concessives/connectives (3), particle
  semantics (4), formal genres (3). Target: **~120 concepts, every unit
  1–49 keeps its current anchor, new concepts slot into the existing unit
  ordering and extend grammar past unit 49 up the vocab spine.**
- Every new concept ships the full kit the codebase already defines: LESSON
  note(s) + CONCEPT_APPLY frames exercising exactly that stage's contrast +
  drill eligibility + `audit_curriculum.py` cross-check entry.
- Gate: pytest wall green; sim reachability re-calibrated (more concepts must
  not slow the p50 learner's unit progression by >15 % — spread introductions,
  don't stack them); no unit introduces >1 new *family*.

### G8 — Interleaved spiral sequencing

**Intent:** stop teaching by blocks. Blocked practice (unit 6 = all genitive,
then done) is the single most consistently refuted sequencing choice in the
learning-science literature; interleaving + spaced re-encounter is the
best-supported. The scheduler already interleaves *reviews*; this phase
interleaves the *syllabus*.

- Re-sequence A1: break the unit 5–9 case pileup — no two case-family
  concepts in adjacent units; interleave with PRESENT/PAST verb work, chunk
  units (G1), and topic vocabulary. (This is a pipeline reorder of unit
  assignments; existing learner card state keys on concept id, not unit, so
  re-sequencing is safe for the installed base — verify with the migration
  report from G4.)
- **Planned re-encounters:** each concept family gets scheduled *syllabus*
  returns at +2, +5, +12 units after introduction — realized not as new
  lessons but as new CONCEPT_APPLY frame tiers (same concept, harder frames:
  more clause depth, mixed with the interfering neighbor) unlocked by unit
  progress. Schema: frames gain `minStage`/`tier`.
- Interference-aware adjacency rule in the pipeline: concepts listed in each
  other's `interferesWith` (G2) may not be *introduced* within 2 units of each
  other, but **must** be contrasted (contrastive frame pair) within 5 units of
  the second one's introduction — introduce apart, contrast deliberately soon.
- Gate: new pytest sequencing audit (adjacency + re-encounter coverage); sim
  confusion-rate assertion — synthetic learner's case-confusion events per
  100 reviews must not regress vs baseline.

### G9 — A1/A2 communicative repair

**Intent:** make the first 1,200 words a survival syllabus, not a frequency
band. Counts are already TORFL-shaped; composition isn't.

- **Numerals now:** cardinals 0–100 + time-telling + prices + dates as A1/A2
  chunk-first content (numeral government arrives as grammar in G7; at A1 the
  learner memorizes «два часа, пять часов» as chunks — exactly the G1 pattern).
- Thematic coverage audit as a pytest gate: a fixed semantic-field checklist
  per band (family, food/drink, body/health, weather, city/transport, time
  words, home, clothing, basic emotions…) with minimum counts; `audit_
  curriculum.py` fails when a field is under-covered. Source the checklist
  from the TORFL elementary/basic lexical-minimum topic lists.
- Re-band audit: matrix-promoted abstract words currently sitting in A1 that
  serve no A1 communicative function move to A2/B1 (tags make them queryable:
  `matrix` + `cefrLevel=A1`); survival items promoted the other way.
- Every A1/A2 unit gets 1 micro-dialogue + 1 micro-reader that use *that
  unit's* vocab against already-introduced grammar only (the P5/P6 engines
  consume these; the gap is authored coverage, not machinery).
- Gate: thematic audit green; controlled-vocab audit green; A1 numeral count
  ≥ 30 (currently 2).

### G10 — Phonology & listening strand

**Intent:** the app tests listening but never teaches the Russian sound
system. Build the missing strand on existing card machinery (AUDIO_TO_RU,
DICTATION, minimal-pair CHOICE), graded honestly given TTS limitations.

- **Minimal-pair discrimination** (A1): ы/и, ш/щ, hard/soft consonant pairs
  (мат/мять class), voiced/devoiced finals — audio plays one of a pair,
  learner picks which. New content, existing card mechanics, evidence
  strength MODERATE (single-bit answer).
- **Intonation contours** (A1–A2): IK-1 statement vs IK-2 wh-question vs IK-3
  yes/no question — recognition first («Это дом.» vs «Это дом?»), then
  SPEAK-graded imitation at PRACTICE strength. IK-3 is *grammatically
  load-bearing* in Russian (it is the only marker of yes/no questions) and
  currently untaught.
- **Reduction & fast speech** (A2–B1): vowel reduction patterns (о→а/ə),
  standard contractions (что=[што], сейчас≈[щас], тысяча≈[тыща]) as
  listening cards; B1+ listening tasks switch to natural-tempo TTS.
- **Stress mobility** (B1): the stress-shift *patterns* (рука́/ру́ку class) as
  concept stages under G7, drilled by audio discrimination rather than the
  retired typed STRESS_MARK.
- All audio built so the planned build-time neural audio pack (Master Plan
  decision log) is a pure asset swap; where device TTS cannot render a
  contrast reliably (some IK distinctions), the item ships *disabled* with a
  `requiresAudioPack` flag rather than shipping a broken drill.
- Gate: new `test_phonology.py` content audit; CardContentLint covers the new
  types; sim includes the new cards in the no-starvation assertion.

### G11 — Functional can-do layer + honest proficiency map

**Intent:** CEFR is a functional standard ("can order food"), and the app
currently measures only formal mastery (cards known, concepts introduced).
Close the definition gap and make "what does completing this app mean"
answerable.

- `units.yaml` (revived from Master Plan P6.5, now with G2/G7 substrate):
  every unit carries a can-do label and its exit ticket (G6) tests *the
  function* — the A2 motion exit ticket asks the learner to say where they're
  going, not to conjugate ехать in isolation.
- **Curriculum-completeness metric** — the honest number the app has never
  had: % of on-device Tatoeba sentence-bank sentences per band that are fully
  parseable (every lemma known-or-taught *and* every construction covered by
  an introduced concept, computed via the existing morphology/frame
  machinery). Pipeline computes it per band at build time; CI watches it;
  Lab shows it. This turns "40 concepts is too few" from a judgment into a
  dashboard number and measures every G7 installment's real coverage gain.
- Dashboard/Lab proficiency map: per-skill (reading/listening/production) ×
  per-band grid fed by the checkpoint engine and WorldModel — the learner's
  TORFL-shaped honest profile, replacing the single effective-level scalar as
  the *displayed* truth (the scalar remains the gate).
- Gate: metric computed reproducibly in CI; exit-ticket function coverage for
  all A1/A2 units.

### G12 — Upper-register buildout (B2→C2)

**Intent:** give units 50–262 a curriculum. Depends on G7 schema; sequenced
last because the sole learner reaches it last — but *specified* now so G7
authors the strands with the right stages from the start.

- Participle strand as reading-first: recognize → gloss → transform to/from
  который-clause (TRANSFORM extension already planned in G6 register work) →
  produce in formal writing (micro-composition prompts).
- News/officialese genre readers with grammar-highlight mode keyed to the
  currently-studied C-band concept (engine exists; content authored per G7
  particle/connective/nominal-style stages).
- C2 subtracks (discourse particles, literary syntax, argumentation) as
  **reader-triggered** concept introductions: a C-band concept's LESSON may
  be triggered by first encounter in a reader text rather than queue order —
  the one C2 idea from the reviewed proposal worth building mechanism for.
- Gate: curriculum-completeness metric (G11) for B2/C1 bands shows measured
  gains per installment; controlled-register audit (formal texts actually
  formal — checkable lexically via the tier-2 domain lists).

## Part IV dependency sketch

```
G7 (spine 2.0 schema+strands) ──► G8 (spiral), G10 (stress stages), G12 (upper register)
G2 edges (Part II) ────────────► G7 cross-family prerequisites, G8 adjacency rule
G9 (A1/A2 repair) ─────────────— independent of G7; do alongside
G11 (can-do + metric) ─────────► needs G6 exit tickets; metric needs only the pipeline
```

**Curriculum blitz** (first content sessions, mutually independent):
1. G7 schema (`family`/`stage` on GrammarConcept + gating) with the aspect
   strand as the pilot family — the hardest, highest-value strand proves the
   pattern.
2. G9 numerals installment (0–100, time, prices) — the single most
   embarrassing gap, fixable in one authoring session.
3. G11 curriculum-completeness metric in the pipeline — so every subsequent
   installment's coverage gain is measured from day one.
4. G8 A1 re-sequencing audit rules (pytest-only first: assert-and-fix the
   case-adjacency violations).

## External benchmarks used

- TORFL/ТРКИ level inventories and lexical minimums (SPbU Language Testing
  Centre; ~780/1,300/2,300 words for A1/A2/B1; B1 grammar tests all six cases
  sg+pl, aspect, motion verbs, complex subordination).
- Standard first/second-year university scope-and-sequence (case spacing
  across a full year; aspect and motion as multi-semester spirals; word
  formation as an explicit B1+ strand).
- SLA sequencing research: interleaving over blocking, spaced re-encounter,
  input-before-output for new forms, elicited imitation as proficiency probe
  (already adopted in P6.1).

The North Star restated for Part IV: the Part I–III engine decides *when* and
*how* to practice optimally; after G7–G12 there is finally a syllabus dense
enough that *what* it practices covers the actual Russian language, A1 to C2,
sounds included, functions included, measured honestly against a real corpus.
