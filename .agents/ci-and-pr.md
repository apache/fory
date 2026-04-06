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

## Common CI Failures

- Code style failures: run the relevant formatter (`clang-format`, `prettier`, `spotless:apply`, `dotnet format`, `cargo fmt`, and so on).
- Markdown lint failures: run `prettier --write <file>`.
- C++ build failures: check missing dependencies, includes, and architecture-specific Bazel flags.
- Test failures: reproduce locally with the smallest command set that proves the fix.

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
