# Performance Round Template

Use this template for every optimization round in `tasks/perf_optimization_rounds.md`.

````markdown
## <Date> Round <N> - <Short Title>

- Goal:
  - <Single bottleneck target and KPI>

- Hypothesis:
  - <Why this change should improve measured cost>

- Code change:
  - <Files and structural changes>
  - <Ownership/cache/dispatch impact>

- Verification commands:
  ```bash
  <build/test/lint commands>
  <targeted benchmark command>
  <repeat targeted benchmark command>
  <optional short full-suite sanity command>
  ```
````

- Before:
  - <KPI metrics and command context>

- After:
  - <KPI metrics and command context>

- Result:
  - <Delta with interpretation: gain/loss/noise>
  - <Correctness and compatibility status>

- Decision:
  - <Kept/Reverted>
  - <Reason tied to data and constraints>

````

## Baseline Snapshot Template

```markdown
## Baseline <commit-sha>

- Command:
  ```bash
  <exact command>
````

- Environment:
  - <machine, OS, build mode, duration>
- Metrics:
  - <ops/sec or ns/op>
- Notes:
  - <why this baseline is the comparison anchor>

````

## Final Summary Template

```markdown
## Final Summary

- Primary KPI:
  - before: <value>
  - after: <value>
  - delta: <value>

- Retained rounds:
  - <list>

- Reverted rounds:
  - <list>

- Correctness checks:
  - <tests/lint/xlang>

- Remaining risk:
  - <open bottlenecks or noise caveats>
````
