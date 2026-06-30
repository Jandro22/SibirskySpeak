SIBIRSKYSPEAK — DESIGN VISION DECLARATION
(Read this before adding features. It is the north star, not a spec. Where a change
conflicts with this document, raise it explicitly rather than quietly violating it.)

This app is built for a single learner. It is not a deck manager, a flashcard queue, or
a manual study planner. It is a one-button personalized tutor.

=====================================================================
1. THE ONE INVARIANT — one route, one button
=====================================================================
The learner presses **Study**. The system generates the optimal session.

    User presses Study ─► System generates the optimal session

- There is ONE primary action on the dashboard. No "Quick / Full / Stretch" buttons, no
  separate grammar/reading/review buttons. Reviews, new material, grammar, and reading
  are interleaved into the single session automatically.
- Pace/mode (how many cards, how long, how hard) is an INTERNAL decision of the system,
  never a user-facing choice. If you find yourself adding a button that asks the learner
  to pick an amount or a mode, stop — that belongs in the pace engine.
- Settings express PHILOSOPHY (doctrine), not MECHANICS. The user may choose
  Conserve / Balanced / Ambitious / Recovery (objective-weight presets), never "25 cards
  a day." Arbitrary numeric caps are a smell.

=====================================================================
2. THE NORTH-STAR OBJECTIVE
=====================================================================
    Maximize retained, usable language ability over time WHILE protecting consistency.

Formally the policy maximizes a discounted sum of:
    + retained knowledge      + transferable ability     + effective minutes studied
    + habit strength / return probability
    − fatigue   − review-queue debt   − burnout/avoidance risk   − dangerous uncertainty

Consequences future agents must honor:
- The best session is NOT the longest. It is the one that maximizes FUTURE useful study.
- Optimize for future recall, transfer, fatigue, review burden, motivation, and
  P(returns tomorrow) — not just in-session correctness.
- Never push the learner harder if it lowers long-term consistency. Protect the streak of
  *returning*, not just the streak of *days*.

=====================================================================
3. THE TARGET ARCHITECTURE — a Learner World Model
=====================================================================
Upgrade path:
    deck scheduler → adaptive scheduler → generative pace engine → learner world model
    → model-predictive tutor

Move from "What is due?" to:
    "What action maximizes future retained usable language while keeping the learner
     coming back?"

Learner state S_t (multidimensional, each with UNCERTAINTY):
    K  concept/sense/construction mastery       (acquired vs not)
    M  item retrievability                      (acquired-but-forgotten)
    A  multidimensional ability                 (vocab, reading, listening, production,
                                                  cases, aspect, syntax, phonology)
    F  fatigue / attention
    W  willingness / return probability / habit strength
    C  sustainable study capacity
    Q  future review-queue pressure (debt)
    U  model uncertainty

A wrong answer is NOT one signal. It is evidence to be assigned to the most likely hidden
cause: not-acquired ≠ acquired-but-forgotten ≠ receptive-not-productive ≠ fatigued ≠ bad
card. Engines must update the specific implicated dimension, not "ability" globally.

Unified success model (one shared forecast, replacing scattered local predictions):
    P(correct | learner,item,format,time,concept)
      = σ( Σ_c q_ic·A_c + R_item(t) + K_concept + Fformat + Xitem − D_item − Lfatigue )
Everything (pace sizing, next-card choice, example comprehensibility, the rival) should
read from this one model.

=====================================================================
4. GENERATIVE PACE (replaces all arbitrary settings)
=====================================================================
The system GENERATES the session dose each time:
    Pace = [ target minutes, new budget, review budget, target retention,
             target difficulty, production ratio, reading dose, stretch/stop policy ]
solving:
    Pace* = argmax  E[ LearningGain + EffectiveMinutes + HabitStrength
                       − Fatigue − ReviewDebt − QuitRisk ]

Hard constraints the pace must respect:
- Review-debt: DebtRatio = E[future review minutes] / E[sustainable minutes] < δ
  (δ ≈ 0.30–0.40 for beginners, 0.60–0.75 when stable). When debt is high, spend extra
  time on LOW-DEBT actions (narrow reading, audio, same-day consolidation, contrast,
  easy comprehension) instead of new cards.
- Return probability: only push harder when P(return_tomorrow) stays ≥ τ.
- Capacity: model sustainable capacity C ~ N(μ_C, σ_C²); a session has a demand D_s; a
  "successful session" = completed + good accuracy + stable speed + low fatigue + returns
  soon (NOT merely finished). Update C from session outcomes.

=====================================================================
5. SESSION PLANNING IS MODEL-PREDICTIVE CONTROL
=====================================================================
Do not freeze a queue at the start. Generate a plan, execute one step/block, observe,
re-plan. Stop and Stretch are first-class ACTIONS with their own utility, not failures:
- Offer STRETCH only when accuracy>target AND speed≥baseline AND fatigue<thresh AND
  DebtRatio<δ AND P(return)>τ.
- A clean early STOP that protects tomorrow can beat squeezing out marginal gain today.

=====================================================================
6. TRUESKILL 2 IS THE UNIFYING SUBSTRATE
=====================================================================
The same Bayesian-belief engine (skill = Gaussian N(μ,σ), updated from "matches",
σ = uncertainty, σ inflates with idle time = rust) powers THREE things:
  (a) the multidimensional ABILITY radar (per-skill, correlated TS2 modes),
  (b) the sustainable-CAPACITY model that generates pace (capacity vs session-demand is a
      TrueSkill "match"),
  (c) the competitive RIVAL (see §7).
Uncertainty (σ) is a feature, not noise: it drives fast early adaptation, honest
confidence bands on the radar, active-learning probes, and conservative displayed ratings
(μ − 3σ).

=====================================================================
7. COMPETITION FOR A SOLO LEARNER — the Rival
=====================================================================
There are no other humans. The opponent is generated:
- THE RIVAL: a TrueSkill-tracked synthetic adversary kept at the learner's skill edge.
  Because balanced matches ARE the optimal-difficulty/flow zone, the competitive layer and
  the difficulty controller are the SAME machine. The Rival is strongest exactly where the
  learner is weakest, so beating it = targeted remediation.
- THE GHOST: the learner's past self, for the long-arc "am I improving" reward.
- Rewarding layer: conservative rating μ−3σ, Russian/CEFR-themed tiers, promotion series,
  win-streak vs the Rival ("competitive streak"), seasons with soft σ reset, a skill radar
  with uncertainty bands.
- HONESTY: competitive ratings update ONLY from objectively-graded outcomes (typed/choice/
  audio accuracy + latency), never self-graded EASY / Mark-known.

=====================================================================
8. CONTENT IS GENERATED, POLICY IS NOT CONTROLLED BY CONTENT
=====================================================================
The learner model controls the policy; the content generator only supplies candidates.
A served sentence should satisfy learner-specific comprehensibility, not a fixed unknown
count: P(comprehensible)∈[0.75,0.90], P(target inferable)>0.70, low ambiguity, bounded
grammar load. Upgrade i+1 mining from "count unknowns" to predicted comprehensibility.

Format selection (the bandit) must reward CAUSAL learning gain (future recall lift), not
immediate speed/comfort — otherwise it over-picks easy recognition. comfort ≠ learning.

=====================================================================
9. COLD START & THE SINGLE-LEARNER DATA DISCIPLINE  (critical engineering constraint)
=====================================================================
This is one learner, so DATA IS SCARCE. Therefore:
- Use lightweight, strongly-prior'd, closed-form Bayesian engines (TrueSkill/IRT, BKT,
  logistic fusion, Kalman-ish state). Do NOT build a deep / data-hungry RL world model or
  neural scheduler — it cannot fit on one person's data and will overfit noise.
- New state starts as a HIGH-UNCERTAINTY version of the full system, shrunk toward cohort/
  population priors: θ* = w·θ_personal + (1−w)·θ_prior, w = n/(n+k). Personal data takes
  over as observations accrue.
- Cold-start objective differs from mature: early on weight EARLY WINS + INFORMATION GAIN +
  HABIT FORMATION over raw difficulty. Use active-learning probes to infer Cyrillic
  comfort, cognate skill, listening weakness, production tolerance, stamina — without a
  forced placement test.

=====================================================================
10. HOW THE CURRENT ENGINES MAP (as of DB v16)
=====================================================================
Already in place (the substrate exists):
  FSRS retrievability ........... M (item memory)            ✓
  BKT (MasteryModel) ............ K (concept mastery)         ✓
  Elo (AbilityModel) ............ A (ability) — 1-D only; upgrade to multi-skill TrueSkill
  ContextualBandit .............. format policy — reward is immediate; upgrade to causal
  ExampleMiner (i+1) ............ content candidates — upgrade to comprehensibility model
  BlueprintBuilder .............. SKELETON of the pace engine — but fed by user settings;
                                  make its inputs (capacity, new-cap) GENERATIVE
  NextCardSelector .............. per-step MPC scorer          ✓
  SessionLookahead .............. MPC-lite (wire it in)        ~
  ReviewControl ................. optimal-retention frontier   ✓
Net-new to build:
  W (willingness/return model), C (capacity Gaussian), Q (review-debt constraint),
  the unified success model, doctrine presets, the Rival/Ghost, multi-skill TrueSkill.

Suggested staging:
  S1  Collapse to one button + generative pace v1: replace settings inputs to
      BlueprintBuilder with a capacity Gaussian + review-debt constraint; settings become
      doctrine presets. (DONE: button collapse. Pace generation: next.)
  S2  Unify the success model into one logistic read by all engines.
  S3  TrueSkill 2 substrate: multi-skill ability radar + capacity + the Rival/Ghost.
  S4  MPC planning + causal bandit reward + willingness/return model + debt control.

=====================================================================
11. DO / DON'T FOR FUTURE AGENTS
=====================================================================
DO   keep one visible Study button; generate pace; respect §2's objective; use σ/uncertainty;
     ground competition in objective grading; verify on the real device + real deck.
DON'T add user-facing card-count/mode/pace controls; cap behavior at arbitrary numbers;
     reward comfort over durable learning; build a deep RL model on one learner's data;
     freeze the queue and ignore in-session signals; let new cards outrun review-debt.
