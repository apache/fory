# CI And PR Guidance

Load this file when triaging GitHub Actions, preparing a pull request, or writing commit messages.

## Workflow Pointers

- `ci.yml`: main CI workflow across languages
- `build-native-*.yml`: macOS and Windows Python wheel builds
- `build-containerized-*.yml`: Linux containerized Python wheel builds
- `lint.yml`: formatting and lint checks
- `pr-lint.yml`: PR title and PR-specific checks

## GitHub CLI Triage

```bash
# List all checks for a PR and their status
gh pr checks <PR_NUMBER> --repo apache/fory

# View failed job logs (get the job ID from gh pr checks output)
gh run view <RUN_ID> --repo apache/fory --job <JOB_ID> --log-failed

# View full job logs
gh run view <RUN_ID> --repo apache/fory --job <JOB_ID> --log
```

Typical flow:

1. Run `gh pr checks <PR_NUMBER> --repo apache/fory`.
2. Inspect the failed job with `gh run view ... --log-failed`.
3. Reproduce the failure locally, fix the root cause, and rerun the relevant checks.
4. Monitor the latest PR head SHA until all required workflows have settled; local targeted validation and remote all-green confirmation are separate requirements.

## Scope And Worktrees

- Keep review tasks and fix tasks separate. For review-only requests, report findings without editing files, committing, pushing, fixing tests, or updating docs unless the user explicitly starts a fix task.
- Current-branch fixes should happen in the current workspace. Use extra worktrees for read-only baselines or isolated PR reviews unless the user asks for a separate implementation worktree.
- Before pushing PR work, verify `git remote -v`, the current branch, and the PR head repository/branch; do not infer push targets from contributor names.
- Do not stage or commit task scratch files such as `tasks/task-*.md`, `tasks/lessons.md`, or synthesis/plan notes unless the user explicitly asks to version them.

## Common CI Failures

- Code style failures: run the relevant formatter (`clang-format`, `prettier`, `spotless:apply`, `dotnet format`, `cargo fmt`, and so on).
- Markdown lint failures: run `prettier --write <file>`.
- C++ build failures: check missing dependencies, includes, and architecture-specific Bazel flags.
- Test failures: reproduce locally with the smallest command set that proves the fix.
- Java harnesses that capture child stdout/stderr must drain those streams concurrently or redirect them before waiting, otherwise chatty peer tests can deadlock.

## Workflow Changes

- In ASF GitHub Actions, verify new `uses:` entries against approved Apache infrastructure action patterns; replace unapproved actions with approved actions plus shell or Python logic when needed.
- Do not add workflow dependencies on new repository variables or secrets when GitHub Actions context, constants, or checked-in configuration can provide stable non-secret values. Coordinate required repo config before landing if no safe default exists.
- If a setup action fails because of a runtime deprecation, verify the current official major and upgrade to the compatible major rather than adding temporary runner flags.
- Reusable release automation belongs under `ci/` unless the user explicitly requests a language-local script.

## PR Expectations

- PR titles must follow Conventional Commits.
- Performance changes should use the `perf` type and include benchmark data.

## Commit Message Format

Use Conventional Commits with a language or area scope when it helps:

```text
feat(java): add codegen support for xlang serialization
fix(rust): fix collection header when collection is empty
docs(python): add docs for xlang serialization
refactor(java): unify serialization exceptions hierarchy
perf(cpp): optimize buffer allocation in encoder
test(integration): add cross-language reference cycle tests
ci: update build matrix for latest JDK versions
chore(deps): update guava dependency to 32.0.0
```
