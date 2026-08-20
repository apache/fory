# Workflow Checklist

## 1) Intake

- Capture exact user objective and KPI.
- Capture explicit bans and constraints.
- Capture reference commit(s), if provided.
- Confirm target implementation language and benchmark command.

## 2) Context Loading

- Read `tasks/perf_optimization_rounds.md` for prior attempts and measured outcomes.
- Read `tasks/lessons.md` for repeated failure patterns and guardrails.
- Read relevant spec docs under `docs/specification/` before touching protocol-adjacent code.

## 3) Baseline

- Benchmark current `HEAD` first.
- Persist the requested reference commit identity and built benchmark artifact; do not treat a stored
  historical number as the baseline for a later comparison.
- Use identical command, duration, and machine state for comparisons.
- Run only one benchmark process at a time; never overlap benchmark commands.
- Under high or variable load, run multiple short adjacent pairs in
  `baseline, current, baseline, current` order. Do not lengthen one process or batch samples by side.
- Record every pair, its command, load evidence, and result as it completes. Exclude only objectively
  contaminated pairs, record why, and never select pairs by whether their delta is favorable.

## 4) Profiling

- Profile the exact bottleneck benchmark (not a proxy test).
- Attribute top cost buckets to concrete code paths.
- Write one hypothesis tied to one measurable bottleneck.

## 5) Round Execution

- Implement one focused change.
- Keep protocol bytes and semantics unchanged unless explicitly requested.
- Keep API surface minimal and internal-first; avoid adding new public APIs unless explicitly required.
- Remove touched legacy/dead code and stale docs instead of preserving compatibility scaffolding in perf rounds.
- Run local build/test/lint for the touched language.
- Run targeted benchmark sequentially (at least 2 adjacent baseline/current pairs).
- Use the paired-delta median and dispersion. Do not change code based on one pair, non-adjacent
  results, or an objectively contaminated pair; wait for a cleaner window when no stable signal
  remains.
- Run one short full-suite sanity benchmark.
- Keep or revert based on measured data.
- Append full round entry to `tasks/perf_optimization_rounds.md`.

## 6) Finalization

- Summarize before/after with exact commands.
- State kept/reverted rounds and rationale.
- List residual risks and follow-up rounds if target is not met.

## Stop And Re-Plan Triggers

- Results are non-deterministic across repeated sequential runs.
- High host load prevents multiple short adjacent pairs from producing a stable retained signal.
- Profile findings do not match expected bottleneck after a code change.
- Proposed fix requires violating user constraints or protocol semantics.
- Workspace state changed unexpectedly (reset/rebase/checkout); re-check `HEAD`.
- Improvement is within noise but complexity increased.

## Anti-Patterns To Reject

- Benchmark-only hacks (payload identity cache, intern-bytes tricks, hardcoded fixture paths).
- Protocol changes to manufacture benchmark wins.
- Reintroducing removed API surface against explicit direction.
- Adding new public "performance" APIs that expose benchmark-driven shortcuts.
- Preserving dead/legacy code/docs after optimization refactors.
- Parallel before/after benchmarking on one machine.
- Long single runs or side-batched runs on a busy machine when short adjacent pairs are available.
- Optimizing from one pair, non-adjacent results, or a load-contaminated result.
- Keeping speculative complexity without repeatable gains.
