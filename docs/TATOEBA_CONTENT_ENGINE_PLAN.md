SIBIRSKYSPEAK — CONTEXT-FIRST CONTENT ENGINE + LEARNING-EFFICIENCY PLAN
(Tatoeba-backed, on-device, free. Status: plan / not yet implemented.)

=====================================================================
0. WHY — grounded in the live learner state (device, DB v15, 2026-06-29)
=====================================================================
- Near-beginner; ~4-day streak just rebuilt; 9 new/day (auto-cut from 11 after a
  66% session); goal 25; ~60 words in live REVIEW rotation.
- Leeches are ALL function words: по (5 lapses), в (4), а (3). "по"'s gloss is an
  8-sense comma-salad — unlearnable as a flashcard.
- Zero reading, zero grammar so far (reading_activities=0, encounters=0, lessons=0).
Root problems: (1) polysemous/abstract words taught context-free with kitchen-sink
glosses; (2) no comprehensible input. The 603 reader texts can't fix it (19% deck
coverage, 8% of verbs). Tatoeba removes that ceiling.
North star: every card is answerable IN CONTEXT; every word can be met in an i+1
sentence; reading is a daily winnable habit; the schedule does the FEWEST reviews
for the MOST durable knowledge. All free / on-device.

=====================================================================
DECISIONS LOCKED
=====================================================================
D1 SIZE = L. Bundle ~300k curated RU sentences (~40k lemmas), ~35–45 MB gz asset,
   capped ~8 sentences/lemma. Future-proofs the entire multi-year deck incl. the
   long tail; coverage is never the bottleneck again.
D2 AUDIO = TTS now. On-device Russian TTS reads any mined sentence (offline, 100%
   coverage, zero size). The miner PREFERS sentences that have Tatoeba native audio
   (cheap tiebreak) so a Phase-3 Wi-Fi opt-in can attach real recordings later.
D3 STRESS = BUILD-TIME ANNOTATION (more ambitious than runtime overlay, and correct).
   declensionJson is 0% stressed and partly wrong, so runtime overlay can't be trusted.
   Instead, stress-mark Tatoeba sentences OFFLINE during the build with an open
   stress tool/dictionary (russtress / Wiktionary stress dump / Hunspell), baking
   stressed text into the asset — so mined sentences ship pre-stressed like the 603
   texts, with zero runtime risk and homographs disambiguated by build-time POS.

=====================================================================
S. THE DAILY SESSION OPTIMIZER  (orchestration core — what the learner experiences)
=====================================================================
Sections 1–6 are INGREDIENTS. This is the engine that assembles them into one optimal
sitting. Today these parts exist only as scattered heuristics (sessionPlan, queueReason,
recoveryQueueAfter, adaptiveDailyLoad, triageMode, fatigue_adjustment); here they become
ONE explicit optimizer with a single objective. Without this, every other improvement
just lands in a static, un-optimized order.

OBJECTIVE: maximize expected DURABLE knowledge gained per MINUTE, subject to:
  (a) protect existing memory (don't let due items decay past target),
  (b) stay under the cognitive-load / fatigue ceiling,
  (c) end on a win.
Every sizing and ordering choice serves this single objective.

A. SIZE — how much today:
 - Compute the AT-RISK set FIRST: due items whose modeled retrievability (FsrsScheduler
   .retrievabilityOf) has fallen / will fall below target today. Non-negotiable core
   (this is what protects memory).
 - Add new cards up to the adaptive cap (adaptiveDailyLoad), GATED by readiness (prereq
   root/concept introduced) and reduced when backlog is high or recent accuracy is low.
 - Fit the learner's real capacity: infer typical sitting length/throughput from
   telemetry (session_start/complete) and offer "Quick (at-risk only)" / "Full" /
   "Stretch". MINIMUM-EFFECTIVE-DOSE mode on busy days = at-risk set only.
 - After a gap: triage oldest/most-at-risk first and CAP daily reviews — never dump the
   whole backlog (triageMode already does this; bound it by at-risk ranking).

B. COMPOSITION — the mix and its shape (one sitting = WARM-UP -> CORE -> COOL-DOWN):
 - Warm-up (2–3): secure, high-retrievability reviews to build momentum (existing
   "Warm-up" reason).
 - Core: interleave at-risk reviews + new intros + grammar + a contrast pair, smoothing
   load by alternating HARD productive cards (cloze/typing) with EASY ones
   (recognition/cognate).
 - Reading inserted mid-core and at the end (i+1 micro-read — you currently read 0).
 - Cool-down: end on a REACHABLE win (a near-due recognition or a cognate) so the sitting
   closes on success. (Primacy/recency: important+hard early while fresh; easy win last.)

C. PER-STEP NEXT-CARD SCORER — replace the static queue with a live, re-rankable pool.
   At each step pick argmax over candidates of:
     nextScore = w_urgency   * retentionRisk(card)            // most-at-risk due first
               + w_new       * newReadiness(card)             // prereqs met, within cap
               + w_interleave* spacingOK(card)                // not same note/root/concept as last few
               + w_contrast  * desirableDifficulty(card)      // place confusables/aspect pairs NEAR (never siblings)
               + w_load      * loadSmooth(card, recentDifficulty) // alternate hard/easy
               + w_arc       * positionFit(card, sessionPhase)    // warm-up / core / cool-down shaping
   HARD CONSTRAINTS: never two facets of the SAME note back-to-back; never a drill whose
   lesson isn't introduced; a lapsed card returns ~6 cards later, not immediately
   (recoveryQueueAfter — keep; tune the gap by load).

D. REAL-TIME INTRA-SESSION ADAPTATION — close the loop WITHIN the sitting, not just next day:
 - Track response-time drift + rolling accuracy live (fatigue_adjustment exists). If
   FATIGUE rising: stop new intros, defer the hardest productive items to tomorrow,
   inject an easy win or a micro-read, and surface a graceful "good place to stop" at a
   high note.
 - If FLOW (fast + accurate): offer a stretch — extra new cards, a harder facet, or a
   contrast challenge — to capture the productive window.
 - Dynamically resize the remaining queue (shrink under struggle, extend under flow).
 - Calibration micro-checks (6.9) feed self-grade accuracy back into FSRS mid-session.

E. LOOP TO TOMORROW:
 - The sitting's accuracy/latency/fatigue set tomorrow's load (adaptiveDailyLoad) AND
   pre-compute tomorrow's at-risk set for an instant, optimal start.
 - "Stop while winning" guidance tied to the live flow state (session-complete card).

HOW §6 PLUGS IN (the multipliers ARE the optimizer's levers):
 root families (6.1) gate new-card readiness + cluster intros; collocations (6.2) are
 core items; efficiency-frontier retention (6.3) sets the at-risk threshold + review
 budget; scaffolding (6.4) is the per-card hint ladder; cognates (6.6) are cool-down wins
 + load relief; confusables (6.7) are the contrast injections; fatigue/flow (6.8) drives
 D; calibration (6.9) tunes grades; goal-directed vocab (6.10) biases new-card selection.

IMPLEMENTATION HOOK: evolve LearningRepository.sessionPlan() from "assemble a static
queue" into (1) a SessionBlueprint (size + composition + arc) computed once, and (2) a
NextCardSelector invoked each step over a live candidate pool, so ORDER responds to
in-session performance. Keep off-main (already in computeDispatcher). ReviewViewModel
drives per-step selection instead of popping a frozen list.

TESTS: blueprint sizing under backlog/fatigue; scorer constraints (no sibling adjacency,
lesson gating, lapse spacing); at-risk ranking matches retrievability; flow/fatigue
transitions; minimum-effective-dose == at-risk set.

=====================================================================
S.ADV  ADVANCED ENGINES FOR SESSION EFFICIENCY (layer ONTO §S; all lightweight + free)
=====================================================================
§S uses HAND-TUNED weights and FSRS alone. The frontier is to make the optimizer
self-tuning and model-based. These are statistical/ML engines small enough to run
on-device for a single learner (or precomputed at build time) — no servers, no cost.

1. SELF-TUNING POLICY — CONTEXTUAL BANDIT over the NextCardSelector (HIGHEST ROI).
   The scorer weights (w_urgency, w_interleave, w_load…) + format + hint-level are
   hand-set. Make them a contextual bandit (Thompson sampling / LinUCB): context =
   learner+session state (fatigue, rolling accuracy, time-of-day, card features); action
   = which card/format/hint to show next; reward = learning-per-minute proxy (next-review
   success + speed − time spent). It LEARNS, online, what maximizes durable learning for
   THIS learner in <1 KB of params. The optimizer optimizes itself.

2. ITEM-DIFFICULTY + ABILITY — ELO / IRT engine.
   An Elo rating per CARD (difficulty) and per LEARNER (ability), updated each answer
   (Duolingo-class). Gives: new-card ORDERING matched to current ability (the ~85%-success
   desirable-difficulty sweet spot), a better SEED for FSRS initial difficulty than the
   stock prior, and an expected-success signal §S can target. ~2 floats/card, trivial.

3. OPTIMAL-CONTROL REVIEW TIMING — Hawkes-process "Memorize" (Tabibian et al., PNAS 2019).
   Closed-form optimal review INTENSITY minimizing recall risk under a review-rate budget.
   Use it to set §S's at-risk THRESHOLD and the daily review BUDGET rigorously — the
   "fewest reviews for most retention" objective made exact — complementing FSRS's
   per-card interval. Pure math, no training, on-device.

4. MASTERY TRACING — BAYESIAN KNOWLEDGE TRACING per concept/root.
   FSRS tracks per-CARD memory; BKT tracks per-SKILL mastery probability (grammar
   concepts, root families, collocations), ~4 params/skill. Lets the optimizer pick items
   that maximize expected MASTERY gain, unlock dependent skills in order, and KNOW when a
   concept is learned (stop drilling it). Cheap and principled.

5. SEMANTIC ENGINE — build-time RU word embeddings (fastText, free, offline).
   Precompute nearest-neighbours per deck word at BUILD time, ship as a neighbour table.
   Powers smart CONFUSABLE DISTRACTORS (semantically-close wrong options), SEMANTIC
   INTERLEAVING/contrast (related items placed for discrimination), and elaborative
   CLUSTERING for intros. Representation learning where all the cost is offline.

6. SESSION LOOKAHEAD — model-predictive control with FSRS as the simulator.
   Monte-Carlo simulate the next 7 days under candidate session compositions; pick the one
   that minimizes future review load while hitting today's retention. FSRS already is the
   forward model; a few hundred rollouts is cheap. Turns §S sizing from heuristic into a
   planned optimum.

WHAT TO SKIP (honesty): deep knowledge tracing / RNN-transformer schedulers and on-device
LLM planners — heavy, data-hungry, and marginal over the above for a single-learner phone.
Engines 1–4 dominate on ROI; 5 is free at build time; 6 is the stretch.

ENABLER: log a per-answer record (cardFeatures, context vector, latency, outcome) so the
bandit/Elo/BKT have inputs — you already capture most of this in telemetry; add the
card-feature + context vectors. Fold into Optimizer v2 (Phase 3), ROI order:
(2)+(1) → (3) → (4) → (5) → (6).

=====================================================================
1. TATOEBA CORPUS — ACQUISITION, FILTERING, PACKAGING
=====================================================================
1.1 Source & license: weekly CSV exports (sentences, links, sentences_with_audio,
    users_sentences, tags). License CC-BY 2.0 FR (attribution, no ShareAlike). Ship a
    credits screen: "Example sentences from Tatoeba (tatoeba.org), CC-BY 2.0 FR."
1.2 Build pipeline — new tools/preprocess/build_tatoeba.py (mirrors build_bootstrap.py):
    (a) keep rus sentences with >=1 eng translation;
    (b) HARD filter: 3–12 tokens; drop ALL-CAPS/URL/digit-heavy/proper-noun-heavy/
        profanity; prefer rated/owned sentences; one canonical EN per RU;
    (c) LEMMATIZE with pymorphy2 (free, offline) -> token->lemma + POS;
    (d) STRESS-ANNOTATE each sentence offline (D3);
    (e) cap K=8 sentences/lemma, preferring shorter + higher-rated + has-audio;
    (f) MINE COLLOCATIONS while here: frequent 2–4 word chunks per lemma (see 6.2);
    (g) emit read-only SQLite app/src/main/assets/tatoeba.db:
        sentence(id, ru_stressed, ru_plain, en, n_tokens, audio, rating)
        lemma_index(lemma, sentence_id, target_pos)  INDEX(lemma)
        collocation(lemma, chunk, freq)
        meta(key,value)  -- version, counts, attribution
    (h) validate (pytest): top-N deck lemmas have candidates; every sentence has EN;
        length/cap respected; stress coverage >X%.
1.3 Size = L (locked): ~300k sentences, ~40k lemmas, ~35–45 MB. Optional later: split
    base (M) + on-demand expansion download, but ship L now.

=====================================================================
2. ON-DEVICE STORAGE
=====================================================================
- Bundled read-only ContentDatabase (Room createFromAsset "tatoeba.db"), SEPARATE from
  the user DB (never enters migrations/backups). DAO: candidatesForLemma, chunksForLemma.
- New user-DB table (migration v15->v16): mined_examples(noteId PK, ru, en, sentenceId,
  anchoredGloss, score, source['tatoeba'|'reader'|'synthetic'], knownAtMine, createdAt).
  Cache; idempotent; re-mined as known-set grows. Note.exampleSentence stays the
  authored/curated source; renderer prefers authored, else mined_examples (mining only
  FILLS gaps, never overwrites curated content).

=====================================================================
3. MINING ENGINE (ExampleMiner — pure, testable)
=====================================================================
Input: note + learner known-set (reuse knownNoteIds()/formIndex()). Output: ranked
candidate sentences.
3.1 i+1 scoring (the ideal card = sentence where the target is the ONLY unknown):
    score = 3.0*(unknown==1 && that unknown is the target)
          + 1.5*coverageOfRestByKnownSet
          + 1.0*lengthFit(5..8 ideal)
          + 0.8*senseMatch(target gloss <-> EN translation)
          + 0.4*rating + 0.2*hasAudio
          - properNoun/too-long/off-level penalties.
    Re-rank as the known-set grows (early: accept <=2 unknowns, gloss the extra inline).
3.2 Gloss/sense anchoring (kills the "по" problem): split gloss into senses; pick the
    sense whose keywords best overlap the chosen sentence's EN translation; store as
    anchoredGloss. Generalizes the existing contextualMeaning() hand-rules into data.
3.3 Run on note introduction + lazily for the example-gap words, off-main
    (computeDispatcher), idempotent (same pattern as repairConcatenatedExamples()).
    Telemetry: example_mined (coverage %, avg score, i+1 rate).

=====================================================================
4. CARD-TYPE CHANGES — CONTEXT-FIRST (ReviewPrompt / CommonComponents)
=====================================================================
- Polysemous/function/abstract words -> CONTEXT cards: extend isContextBoundFunctionWord()
  to also fire when gloss has > N senses; always show the mined sentence and ask the
  ANCHORED single sense (no bare "по -> 8 meanings").
- Every card gets an example: reviewContext/reviewRevealContext fall back to
  mined_examples when no authored example (closes the 309 gap and beyond).
- CLOZE upgrade: prefer a mined i+1 sentence; blank target at target_pos; accept the
  correct INFLECTED form validated by a TRUSTWORTHY morphology source (pymorphy2 at
  build / a reliable runtime check) — NOT declensionJson (it has errors, see 8).
- Dictation/audio from real sentences (TTS), so listening is full-sentence + contextual.
- Keep the recognition-first FSRS facet ladder; mining changes CONTEXT, not facet order.

=====================================================================
5. READING LAYER (you currently read nothing)
=====================================================================
5.1 i+1 micro-reading: assemble 3–5 mined sentences fully inside the known-set into a
    tiny graded passage, woven into the integrated session (dueReadingAssignment already
    inserts reading mid/post-session). Completion -> reading_activities + XP.
5.2 Reading-driven SRS: reader tap -> mineSentence() (exists) -> word enters SRS WITH
    that sentence; Tatoeba is the fallback example for imported texts.
5.3 AUTO-GENERATED NARROW READING (ambitious): chain i+1 sentences that SHARE vocabulary
    into a mini "story"/themed set (narrow reading = repeated exposure to the same words
    in varied contexts = fastest acquisition). Sequence by lemma-overlap + topic tags;
    no LLM needed. This turns 300k isolated sentences into endless graded readers.
5.4 Surface it: given 0 reading, route the caught-up state and the daily nudge into a
    2-minute micro-read; add a "comprehensible input" streak alongside the review streak.

=====================================================================
6. EFFICIENCY MULTIPLIERS (the ambitious layer — research-backed, all free/on-device)
=====================================================================
6.1 MORPHOLOGICAL ROOT/AFFIX FAMILIES (huge for Russian). Cluster the deck by root and
    teach affixes as multipliers (ход -> при-ход, по-ход, вы-ход, в-ход, у-ход). Learning
    one root + a handful of prefixes unlocks dozens of words. Derive clusters at build
    time (stemming + a prefix/suffix table); on intro, show the family and the affix
    meaning. Single highest-leverage efficiency lever for Russian specifically.
6.2 COLLOCATION / CHUNK LEARNING. Fluency is built from multiword chunks, not isolated
    words. Mine frequent collocations from Tatoeba (6.1f) and teach high-frequency chunks
    (принимать решение, иметь в виду) as units. Boosts production + listening speed.
6.3 FSRS EFFICIENCY-FRONTIER RETENTION. Instead of a fixed 0.9 desired-retention,
    auto-tune toward the workload-optimal point (FSRS research: ~0.85–0.90 minimizes
    total reviews for a knowledge target). Compute per-learner from their own
    stability/lapse data; do FEWER reviews for the SAME durable knowledge. You already
    have the weight-fit + interval-modifier infrastructure; add an objective that
    targets reviews-per-retained-item, not just hit-rate.
6.4 GRADUATED RETRIEVAL SCAFFOLDING (desirable difficulty, calibrated to SUCCESS).
    Replace binary reveal with progressive hints (first letter -> skeleton -> full) and
    EXPANDING retrieval on new items. Keep retrieval effortful but mostly successful
    (the sweet spot for long-term memory). Extend the existing scaffold system
    (scaffold_inserted) into a hint ladder; cheaper-than-AGAIN partial credit.
6.5 DUAL CODING for concrete nouns. Pair concrete words with a tiny emoji/icon (free,
    bundled, ~KB) — зонт=☂, дом=🏠. Picture+word doubles memory traces vs. text alone.
    Map a curated concrete-noun set to emoji at build time; show on the intro + reveal.
6.6 COGNATE FAST-TRACK. Many RU words are international cognates (телефон, политика,
    проблема, информация). Detect them (orthographic/phonetic similarity to EN) and
    fast-track: lighter intro, faster intervals, "you already know this" framing. Free
    wins that build momentum and free up review budget for hard words.
6.7 MINIMAL-PAIR / CONFUSABLE CONTRAST CARDS. Interleave easily-confused items and
    present them together to sharpen boundaries: aspect pairs (поговорить/говорить),
    case-governing prepositions (в/на, по/при), and the confusable_pairs table (112,
    currently unused). Contrast > isolation for discrimination.
6.8 IN-SESSION FLOW & FATIGUE ADAPTATION. Monitor response-time drift within a sitting
    (you already emit fatigue_adjustment); when latency rises / accuracy drops, taper
    new cards, inject an easy win or a micro-read, and suggest stopping — protecting the
    "stop while you're winning" pacing the session-complete card already advises.
6.9 METACOGNITIVE CALIBRATION. Briefly ask predicted recall vs. show actual, and report
    calibration over time. Better self-grades -> better FSRS input -> better schedule.
    A small nudge with outsized scheduling payoff.
6.10 GOAL-DIRECTED VOCABULARY. If the learner imports a text they WANT to read, compute
    which unknown words unlock the most of it and prioritize those into new-card
    selection ("learn these 20 to read this at 95%"). Ties SRS to real-world payoff and
    maximizes comprehension-per-word-learned.

=====================================================================
7. PHASING (sequenced to the trajectory; each phase shippable + measurable)
=====================================================================
Phase 0 — INGEST: build_tatoeba.py (filter+lemmatize+stress+collocations) + bundled
          tatoeba.db + ContentDatabase. Includes 6.1 root clusters + 6.2 collocations
          + 6.5 emoji map as build outputs.
Phase 1 — CONTEXT VOCABULARY (immediate pain relief): ExampleMiner, gloss anchoring,
          example backfill, context cards for function/abstract words, cognate
          fast-track (6.6). Directly attacks по/в/а leeches + the no-example gap.
Phase 1.5 — SESSION OPTIMIZER v1 (§S): SessionBlueprint (at-risk sizing + warm-up/core/
          cool-down arc) + NextCardSelector (the per-step scorer with interleave/load/
          arc constraints) + intra-session fatigue/flow adaptation. This is what makes
          every ingredient land in the OPTIMAL order — build it right after Phase 1 so
          the new context cards are already orchestrated well. Optimizer v2 (calibration,
          goal-directed bias, stretch offers) ships in Phase 3.
Phase 2 — READING + INPUT: i+1 micro-reading (5.1), reading-driven SRS (5.2),
          narrow-reading generator (5.3), input streak.
Phase 3 — EFFICIENCY DEPTH (gated on known-set size): morphological families (6.1),
          collocation cards (6.2), efficiency-frontier retention (6.3), graduated
          scaffolding (6.4), dual coding (6.5), minimal-pair/confusable contrast (6.7),
          flow/fatigue (6.8), calibration (6.9), goal-directed vocab (6.10), adaptive
          facet scheduling (matureRetentionByCardType), pronunciation/shadowing
          (TTS+ASR), "you can now read X" milestones, optional native audio (D2).

=====================================================================
8. DATA-QUALITY FIXES THIS WORK DEPENDS ON
=====================================================================
- declensionJson is UNSTRESSED and has morphological ERRORS (e.g. свого->should be
  своего). Do NOT validate cloze inflections against it. Use pymorphy2 at build time
  and/or a reliable runtime morphology; consider regenerating/repairing declensions
  from pymorphy2 as a separate cleanup (the curated-irregulars layer stays authoritative).
- Kitchen-sink glosses (по, что, в...) get sense-anchored at runtime (3.2); a build-time
  pass could also pre-split senses for the worst offenders.
- The space-before-punctuation tidy (tidyPunctuationSpacing) applies to mined text too.

=====================================================================
9. TELEMETRY & SUCCESS METRICS
=====================================================================
New events: example_mined, gloss_anchored, context_card_shown, reading_microsession,
narrow_read, chunk_card, cognate_fasttrack, hint_used, calibration_sample.
Baselines -> targets: cards-with-CONTEXTUAL-example ~0% -> ~100%; function-word lapse
(по/в/а) high -> down; session accuracy ~66% -> 80%+; true retention 78% -> ~88–90% at
LOWER review count (6.3); reading_activities 0 -> daily. A/B via learning_experiment_v1.

=====================================================================
10. RISKS & MITIGATIONS
=====================================================================
- License: ship CC-BY attribution screen (mandatory, low effort).
- Asset size (L ~40 MB): acceptable for a learning app; gz + per-lemma cap; optional
  on-demand expansion later.
- Build-time stress accuracy: validate against the deck's known stresses; leave
  low-confidence words unstressed rather than wrong.
- Lemmatization accuracy (pymorphy2): a few misses = fewer candidates, not wrong cards.
- Sentence quality: hard filter + rating preference + length cap + spot-check top lemmas.
- Perf: read-only indexed lookups + mined_examples cache, all off-main.
- Never overwrite curated content; mining only fills gaps.

=====================================================================
11. BUILD ARTIFACTS & TESTS
=====================================================================
- tools/preprocess/build_tatoeba.py (+ requirements: pymorphy2, stress tool). Output:
  app/src/main/assets/tatoeba.db (+ root-family/collocation/emoji tables).
- tools/preprocess/test_tatoeba.py: index covers top-N deck lemmas; every sentence has
  EN; length/cap; stress coverage; collocation sanity.
- Kotlin tests: ExampleMiner scoring (i+1 rank, anchoring, inflected-cloze accept),
  stress integrity, mined_examples idempotency, migration v15->v16, cognate detector,
  root-family clustering.

=====================================================================
12. MILESTONES (independently shippable)
=====================================================================
M1 Ingest spike: build_tatoeba.py + an L-asset subset + ContentDatabase + a debug
   "mine this word" view. Proves coverage + i+1 rate on the real deck.
M2 Phase 1 + Optimizer v1 (§S): miner + anchoring + backfill + context cards + cognate
   fast-track, THEN the SessionBlueprint + NextCardSelector + fatigue/flow so the new
   cards are delivered in the optimal order. Behind a flag; measure function-word lapse,
   accuracy, AND reviews-per-retained-item (the §S objective).
M3 Phase 2: micro-reading + reading-driven SRS + narrow-reading generator.
M4 Phase 3: efficiency multipliers unlocked by known-set thresholds.
