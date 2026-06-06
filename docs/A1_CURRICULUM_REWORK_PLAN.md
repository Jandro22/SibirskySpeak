# A1 Curriculum Rework — Implementation Plan

> **Update — extended to C1.** The plan below describes the original A1 rework. The
> tier-0 course has since been extended across every CEFR level (A1 → C1): notes carry
> a `cefrLevel`, the grammar spine grew to 32 concepts (`GrammarConcepts.kt`), and the
> content lives in per-level modules `a1_starter.py`…`c1_starter.py` sharing
> `curriculum_common.py`, validated end-to-end by `tools/preprocess/test_curriculum.py`
> (cumulative controlled vocabulary + real glosses). Grammar above A1 is taught via
> lesson cards (participles, gerunds, passive, syntax, register, idiom) rather than
> auto-drilled, since the morphology engine can't derive those forms safely.

## Goal

Turn SibirskySpeak from a *grammar-drilling engine* into a *progressive course* that an
A1 (true beginner) learner can actually follow. The app currently force-tests grammar
before teaching it, ships sample sentences a beginner cannot read, and opens with
abstract political vocabulary. This plan reworks every facet — content, sequencing,
teaching, and UI — while preserving the existing FSRS scheduler, dual-queue model, and
reader.

---

## Diagnosis (why the current curriculum fails an A1 learner)

1. **Grammar is tested before it is taught.** The teaching text (`caseTeaching`,
   `genderTeaching`, drill `explanation`) is only rendered *after* reveal
   (`MainActivity.kt` `RevealPanel`, line ~1278). The learner meets "Make 'state'
   genitive" with no prior instruction. (`ReviewPrompt.kt:205` `caseTeaching`.)

2. **Sample sentences are not comprehensible.** `exampleTranslation` is the *headword*,
   not a translation of the sentence. Root cause: `build_bootstrap.py`
   `corpus_or_fallback()` returns a matched corpus sentence paired with
   `corpus_gloss = term_en(translation)` (just the headword). The well-formed fallback
   translations are discarded whenever a corpus sentence matches. So
   `Совет обсудил новые санкции против государства.` ships with gloss `"state"`, and the
   UI prints **"Meaning: state"** under it (`MainActivity.kt:2863`). CLOZE blanks a word
   in a sentence the learner cannot parse.

3. **Register is wrong for A1.** Notes are sorted by `domainFreqRank`; day-one words are
   `государство, правительство, президент, министр`. The whole domain is
   Kremlin/TASS/military reading. Beginners need concrete, high-frequency, picturable
   survival vocabulary first.

4. **No concept-level progression.** Each domain note emits *all* case/gender/aspect/
   verb cards at once (`LearningRepository.cardsFor`, line ~1068). The only pacing is a
   flat 15-new-cards/day throttle and post-hoc category *graduation*. There is no
   "introduce nominative + gender, consolidate, then unlock accusative" ordering, and no
   per-learner skill/progress state to drive one.

---

## Target learner experience (the A1 spine)

A staged course with explicit **units**. Each unit introduces a small vocabulary set in
the learner's register tier and **at most one new grammar concept**, taught with a short
lesson card *before* any drill, then consolidated. Grammar concepts unlock in pedagogical
order, not all at once:

```
Unit 0  Alphabet / sounds, greetings, я/ты/вы, быть-less present ("это …")
Unit 1  Concrete nouns + gender (NOM only).            New grammar: noun gender
Unit 2  Plurals (NOM).                                  New grammar: NOM plural
Unit 3  Common verbs, present tense (intro, no drill).  New grammar: subject pronouns
Unit 4  Direct objects.                                 New grammar: ACCUSATIVE
Unit 5  Possession / "of" / нет.                         New grammar: GENITIVE
Unit 6  Location (в/на + …), "about".                    New grammar: PREPOSITIONAL
Unit 7  Giving / "to/for" / age.                          New grammar: DATIVE
Unit 8  "with/by", быть/стать.                            New grammar: INSTRUMENTAL
Unit 9  Past tense + gender agreement.                   New grammar: PAST forms
Unit 10 Aspect (was X-ing vs. did X).                    New grammar: ASPECT
... domain (political/security) register layered on AFTER the A1 spine is solid.
```

The political-domain corpus is **kept** but demoted to an advanced tier that unlocks once
the A1 grammar spine is graduated.

---

## Workstreams

### A. Content data model — add a curriculum scaffold

The note/card schema has no concept of unit, register tier, or grammar concept. Add them.

- **`Note`**: add `tier: Int` (0 = A1 starter, 1 = general, 2 = domain) and
  `unit: Int?` (curriculum unit index, null = uncurated). Backwards-compatible defaults.
- **New entity `GrammarConcept` / progression state.** Track per-concept learner state
  (`LOCKED`, `INTRODUCED`, `PRACTICING`, `GRADUATED`) keyed by a concept id
  (`GENDER`, `NOM_PL`, `ACC`, `GEN`, `PREP`, `DAT`, `INS`, `PAST`, `ASPECT`). This is the
  gate that staged sequencing reads. Store either as a small Room table or in
  `SettingsStore` (concept→state map). Room table is cleaner for querying and survives in
  the existing backup/export path.
- **New `CardType.LESSON`** (a teaching card: shows a concept explanation + a worked
  example, "Got it" instead of grading; logged so it counts as the concept's
  introduction and is scheduled for light review).
- Room migration (current schema v5 → v6). The repo already does destructive-migration +
  backup-restore, so add the columns and bump the version; the backup importer
  (`importJsonLines`) must read/write the new fields.

### B. Fix comprehensible input (highest impact, smallest change)

This is independent of the bigger rework and should land first.

1. **`build_bootstrap.py`:** make `exampleTranslation` always a real sentence
   translation. Stop using `term_en()` as the corpus gloss. Options, in order of
   preference:
   - Prefer the **templated fallback** sentence+translation (they are already aligned
     pairs) for curated tiers, so every studyable sentence has a true gloss.
   - Only use a corpus sentence when a real aligned translation exists for it (the
     reader-text corpus has none today), otherwise keep the template.
2. **Gate CLOZE / CASE_FILL generation** (`LearningRepository.cardsFor`) on the note
   having a non-blank `exampleTranslation` that differs from the headword — no cloze on an
   un-glossed sentence.
3. **UI:** show the sentence gloss on the **prompt** side for cloze/case cards (currently
   `reviewContext` only feeds the useless headword). Render the Russian example with its
   English translation beneath it everywhere a sentence appears.
4. **Regenerate** `bootstrap_notes.jsonl` and verify a sample of glosses are full
   sentences. Add a `validate-notes` check: reject rows whose `exampleTranslation`
   equals/contains only the headword.

### C. A1 starter content (Tier 0)

- New `tools/preprocess/a1_starter.py`: a hand-curated, ~300–400 word A1 wordlist grouped
  by the units above, each entry with a **concrete, readable example sentence + true
  translation** built only from words already introduced in earlier units (controlled
  vocabulary, so every sentence is comprehensible at the point it appears).
- Each entry tagged with `tier=0`, `unit=N`, and (for grammar-bearing units) the concept
  it teaches.
- Author **lesson cards** per concept: 1–2 short paragraphs in plain English with a worked
  example (e.g. accusative: "The direct object — the thing the action happens to — takes
  the accusative. For most masculine inanimate and neuter nouns it looks the same as the
  dictionary form; feminine -а → -у. *Я читаю книгу.* — *I read a book.*").
- Author a handful of **graded reader texts per unit** using only that unit's controlled
  vocabulary, replacing "day-one = TASS" with "day-one = readable sentences".

### D. Sequencing & gating (the engine change)

In `LearningRepository`:

- **Order new-card introduction by `(tier, unit, freqRank)`** instead of raw
  `domainFreqRank`. `getNewCards` ordering + `newCardSession` need a tier/unit-aware sort.
- **Concept gating in `cardsFor` / new-card selection:** a grammar card for concept C is
  only eligible to be *introduced* once C's progression state is `INTRODUCED`+ (i.e. its
  lesson card has been seen). Until then those cards stay dormant. This replaces "emit all
  case cards immediately" with "emit them, but the queue won't surface them until the
  concept is unlocked."
- **Concept unlock flow:** when the learner reaches a unit whose grammar concept is still
  `LOCKED`, surface its **LESSON card first**; completing it flips the concept to
  `INTRODUCED` and lets its drills enter rotation. Reuse the existing category
  *graduation* machinery (accuracy-based) to flip `PRACTICING → GRADUATED`, which unlocks
  the next unit's content.
- **Don't unlock the domain tier** until the core A1 concept spine
  (GENDER, ACC, GEN, PREP, PAST) is graduated.
- Keep the 15/day throttle and interleaving as-is — they're sound.

### E. Teach-before-test in the UI

`MainActivity` review screen:

- Render `CardType.LESSON` as a distinct screen: concept title, explanation, worked
  example with audio, single "Got it" button (no grading UI).
- For grammar drills, move a **one-line concept reminder onto the prompt** (before the
  learner answers), not only into the post-reveal `explanation`. Keep the fuller
  explanation on reveal.
- Always pair Russian example sentences with their English translation in the prompt and
  reveal panels.
- A simple **"Unit N — <title>" progress header** so the learner sees the course spine and
  what concept they're on.

### F. Tests & validation

- `LearningRepositoryTest`: new-card ordering respects tier/unit; locked-concept drills
  don't surface; lesson-card completion unlocks drills; domain tier stays locked until A1
  spine graduates.
- `build_bootstrap` / preprocess tests: every emitted curated row has a sentence-level
  gloss distinct from the headword; controlled-vocabulary check (a unit's example uses no
  word from a later unit).
- `ReviewPromptTest`: cloze/case cards are not built without a real gloss; lesson prompts
  build correctly.
- Migration test: v5 backup restores into v6 with default tier/unit.

---

## Suggested landing order (each step shippable on its own)

1. **B — comprehensible input fix** (real glosses + UI + cloze gating). Immediate
   quality jump, no schema change.
2. **A — schema scaffold** (`tier`, `unit`, concept progression, `LESSON` type,
   migration v6).
3. **E — lesson card UI + prompt-side teaching.**
4. **D — sequencing & concept gating.**
5. **C — A1 starter content + graded readers** (the largest authoring effort; can grow
   incrementally unit by unit).
6. **F — tests throughout.**

## Open questions to settle before/while building

- **Concept progression storage:** Room table (queryable, in backup) vs. SettingsStore
  map (simpler). Recommend Room.
- **A1 wordlist sourcing:** hand-author vs. adapt an existing A1 list (e.g. a CEFR A1
  Russian frequency list) — affects content effort and licensing.
- **How hard to gate the domain tier:** strict lock until A1 spine graduates, or just
  reorder so A1 comes first but advanced users can opt in.
