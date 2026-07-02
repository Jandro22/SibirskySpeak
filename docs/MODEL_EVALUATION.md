# Learning-model evaluation and governance

SibirskySpeak treats adaptive-model changes as controlled policy changes, not as
constants that may be tuned directly in production.

## Evaluation stack

- `CalibrationDiagnostics` computes Brier score, log loss, calibration bias,
  expected calibration error, reliability bins, and breakdowns by card type and
  CEFR level. Drift requires adequate reference/recent sample sizes.
- `PopulationSimulator` replays seeded heterogeneous learners over 30–365 days,
  including memory, ability, time capacity, overload, return probability, review
  burden, conserved review backlog, and fatigue protection. Review frequency grows
  with target retention (`1 / -ln(rho)`); introductions and reviews consume capacity
  separately.
- `CounterfactualEvaluator` uses paired seeds for candidate and baseline policies.
  Promotion requires a positive lower 95% bound on utility lift and non-inferior
  return/overload guardrails.
- `ReplayParameterTuner` exhaustively evaluates the configured retention,
  new-card, and uncertainty-weight grid. It never activates its winner directly.
- `UncertaintyAwareSelection` gives a bounded information-value bonus only near
  the desired-success frontier. Urgency and prerequisites remain dominant.

## Version lifecycle

`LearningRepository.tuneAndStageLearningPolicy()` creates an immutable,
namespaced snapshot in `optimizer_parameters`. A candidate may be promoted only
when `ModelGovernance.promotionDecision()` passes counterfactual and calibration
guardrails. Promotion and rollback replace active parameter keys in a Room
transaction. Parent-version metadata provides an explicit rollback target and its
chain is protected from history pruning. Sufficient post-version calibration drift
automatically rolls back the active model.

Features, predictions, and model versions are frozen before grading and stored with
local calibration events.
Diagnostics therefore evaluate the model that actually made each prediction and
do not reconstruct old predictions using current parameters or post-answer updates.

## Verification

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug assembleDebug assembleDebugAndroidTest
python -m pytest -q tools\preprocess
```

The JVM suite includes 10,000 hostile randomized states, forgetting-curve
monotonicity, multi-month population replay, paired counterfactual evaluation,
calibration drift, tuning-grid coverage, governance/rollback, and a generous
throughput floor. Instrumented tests cover transactional persistence across a
database close/reopen and recovery from malformed persisted model values.
