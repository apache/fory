---
name: fory-performance-optimization
description: Run profile-driven bottleneck optimization across Apache Fory implementations (Java, C++, Python/Cython, Go, Rust, Swift, C#, JavaScript/TypeScript, Dart, Kotlin, Scala). Use when improving serialize/deserialize throughput or latency, recovering regressions against a reference commit, diagnosing flamegraphs, fixing perf-related CI failures, or porting proven optimizations across languages without protocol or API regressions.
---

# Fory Performance Optimization

## Mission

Deliver measurable performance improvements in Apache Fory without protocol drift, correctness regressions, benchmark-shape tricks, or accidental API rollback.

## Operating Principles

- Start from data, not intuition.
- Profile before changing hot code.
- Change one bottleneck at a time.
- Benchmark sequentially on the same machine state (one benchmark process at a time).
- Keep only measured wins or explicitly requested architecture cleanups.
- Revert speculative changes that do not pay off.
- Align with reference runtimes (usually C++ first, then Rust/Java) when behavior and ownership models differ.

## Enforce Hard Constraints

- Preserve wire protocol unless explicitly requested.
- Preserve cross-language semantics and xlang compatibility.
- Never run two benchmarks at the same time on one host; run exactly one benchmark command at a time.
- Do not optimize by changing benchmark payload definitions, field encodings, or benchmark methodology.
- Do not add payload-identity or repeated-input caches that depend on benchmark shape.
- Do not restore removed APIs/legacy wrappers when the user forbids it.
- Do not preserve legacy/dead code or stale docs in optimization rounds; remove them when touched.
- Keep API surface minimal: do not add new API unless required by protocol/correctness or explicitly requested.
- Never add public hacky API for performance shortcuts; keep optimization helpers internal/private and conceptually clean.
- Do not hide regressions behind unsafe compiler flags or benchmark-only code paths.
- Keep optimization surfaces nested-safe; avoid root-only shortcuts unless they are architecturally valid and requested.

## Execute Workflow

1. Read context and constraints.

- Read `tasks/perf_optimization_rounds.md` and `tasks/lessons.md`.
- Read the relevant spec in `docs/specification/` for any path that may affect wire behavior.
- Record explicit user constraints (forbidden APIs, naming, architecture, protocol rules).

2. Define target and baseline.

- Identify one primary KPI (for example `Struct Serialize ns/op` or ops/sec).
- Benchmark current `HEAD`.
- If a reference commit is provided, benchmark it once and persist the result in a file (for example `tasks/perf_baselines/<id>.md`) to avoid repeated reruns.

3. Profile the hotspot.

- Capture a flamegraph or sampled stacks on the exact benchmark command.
- Quantify top costs by bucket (runtime bookkeeping, dispatch, allocation/copy, map/cache operations, buffer growth, metadata parse/validation).
- Tie each bucket to concrete file/line ownership before proposing changes.

4. Form one round hypothesis.

- State one bottleneck and one expected effect.
- Prefer structural fixes over micro-tweaks.
- If another runtime already solved the same bottleneck, port its design shape first.

5. Implement minimal change.

- Touch the smallest surface that can validate the hypothesis.
- Keep invariants explicit: protocol bytes, ownership, cache lifetime, reference semantics, nullability, schema-compatible behavior.

6. Verify correctness.

- Run language-local build/test/lint for the touched implementation.
- Run cross-language checks when runtime/type/protocol behavior can affect xlang.
- Confirm serialized sizes and compatibility expectations where applicable.

7. Benchmark and compare.

- Run targeted benchmark at least twice sequentially.
- Use longer duration when signal is noisy.
- Run one short full-suite sanity benchmark to catch collateral regressions.

8. Decide keep or revert.

- Keep only if gain is repeatable or cleanup is explicitly requested and accepted with measured tradeoff.
- Revert if performance regresses or gain is within noise and complexity increases.
- If a required cleanup regresses, redesign inside the new architecture instead of restoring banned patterns.

9. Log every round.

- Append one round entry to `tasks/perf_optimization_rounds.md` before starting the next round.
- Include hypothesis, code change, exact commands, before/after numbers, and keep/revert decision.
- Commit retained non-trivial rounds immediately.

10. Re-plan on instability.

- Stop and re-plan when benchmark runs conflict, machine contention is suspected, or profile does not match hypothesis.
- Re-ground on current `HEAD` after reset/rebase/checkout events before making further changes.

## Apply Decision Rules

- Treat <1-2% movement as noise unless repeated under controlled runs.
- Require explicit proof for complexity-increasing optimizations.
- Prefer deleting dead APIs and dead state quickly after refactors.
- Keep naming/API cleanup only if performance remains in band.
- Never run before/after comparisons in parallel.

## Use References

- Use [`references/workflow-checklist.md`](references/workflow-checklist.md) for execution checklists and stop conditions.
- Use [`references/language-command-matrix.md`](references/language-command-matrix.md) for per-language build/test/benchmark/profile commands.
- Use [`references/bottleneck-playbook.md`](references/bottleneck-playbook.md) for hotspot-to-fix mapping.
- Use [`references/round-template.md`](references/round-template.md) to log each optimization round consistently.

## Produce Output

When finishing an optimization task, report:

- Baseline command and numbers.
- Final command and numbers.
- Net delta on primary KPI.
- Correctness and compatibility verification run.
- Kept vs reverted rounds and rationale.
