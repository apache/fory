---
name: fory-code-review
description: Review Apache Fory pull requests, branches, commits, or diffs with a Fory-specific checklist. Use when auditing code in this repository for protocol or xlang regressions, performance or benchmark-methodology issues, cross-language inconsistencies, accidental public API growth, runtime ownership drift, missing tests, or docs/spec mismatches. Also use before posting Fory review findings inline to GitHub.
---

# Fory Code Review

## Mission

Find the highest-value bugs, regressions, and missing verification in Apache Fory changes. Prioritize correctness, protocol safety, performance discipline, and maintainability over style-only comments.

## Start Here

1. If the target is a GitHub PR, create a new local git worktree for the review before checking out or fetching the PR branch.
2. Do not switch the current branch or reuse the current worktree for PR review unless the user explicitly asks for that.
3. If reviewing against main, run `git fetch apache main` before diffing.
4. Inspect the changed files first and cluster them by subsystem.
5. Load only the references needed for the touched areas:
   - `references/review-checklist.md`
   - `references/lesson-derived-red-flags.md`
   - `references/validation-command-matrix.md`
   - matching runtime docs under `../../languages/*.md` when the patch is language-specific

## Review Workflow

1. Define the review target.

- Determine whether the user wants a review of a PR, branch, commit range, or local diff.
- For a GitHub PR, create and use a dedicated local worktree for the review. Keep the current worktree and branch unchanged unless the user explicitly requests otherwise.
- In that worktree, fetch the PR head and review there instead of checking out the PR branch in the current workspace.
- Prefer `git diff --stat` first, then inspect the full patch only for touched subsystems.

2. Load focused context.

- Protocol, type mapping, xlang, `TypeMeta`, `TypeInfo`, ref tracking, or schema evolution changes:
  - Read the relevant `docs/specification/**` sections before reviewing behavior.
- Benchmark or performance changes:
  - Review both benchmark code and generated `docs/benchmarks/**` artifacts.
- Runtime cleanup or cross-language alignment changes:
  - Compare the changed ownership/API shape to the reference runtimes first, usually C++ then Rust/Java.

3. Inspect in this priority order.

- Correctness, data corruption, security, and protocol drift.
- Cross-language consistency and native/xlang behavior boundaries.
- Performance regressions or invalid benchmark methodology.
- Public API growth, legacy shims, wrapper layers, and architecture drift.
- Missing tests, wrong test placement, and missing docs/spec updates.

4. Validate each finding.

- Tie the finding to exact changed lines.
- Explain the concrete failure mode or regression risk.
- State why the current code is wrong or incomplete, not only that it differs from another style.
- Recommend the missing test, benchmark, or spec/doc update when that is the gap.

5. Report findings.

- Findings first, ordered by severity.
- Keep overview and change summary brief and secondary.
- If there are no findings, say that explicitly and mention residual risks or testing gaps.

## Hard Rules

- Do not lead with style nits when there are correctness or verification risks.
- Treat benchmark-shape tricks, payload-specific caches, and methodology changes as real findings.
- Treat undocumented public API additions, compatibility shims, and one-line wrapper growth as findings when they increase maintenance surface without clear need.
- Treat protocol or performance claims without verification evidence as incomplete.
- When stream read loops are involved, remember that `(0, nil)` can be transient; do not assume immediate failure is correct.
- If the user wants comments posted on GitHub, produce findings suitable for inline comments, then use `gh-pr-inline-review` to publish them.

## Output Expectations

- Use clickable file references with line numbers.
- Keep each finding concrete: impact, evidence, and required fix.
- Mention missing verification commands when the patch touches protocol, performance, or cross-language behavior.
