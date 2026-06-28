# CI And PR Guidance

Load this file when doing Apache Fory code reviews of PRs, branches, commit ranges, or local diffs;
triaging GitHub Actions; preparing a pull request; or writing commit messages. Do not load this file
when explicitly acting as the independent general reviewer required by `AI_POLICY.md`.

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

## Review Subagents

- When the task environment supports review subagents, perform Apache Fory code review in a fresh read-only review subagent. The main agent coordinates scope, sanity-checks findings, and reports the final review.
- Reuse the same review subagent for later review passes on the same feature unless the user or workflow requires a fresh reviewer. Start a fresh review subagent for a different feature, PR, issue, branch, commit range, local diff topic, or subsystem review.
- Keep review subagents read-only. They must not edit files, apply patches, run tests, run builds, run benchmarks, run linters, install packages, commit, push, fix tests, update docs, or perform implementation work.
- Review subagents report findings or an explicit no-findings result to the caller. The main agent decides whether any separate implementation, CI fixing, or verification task should happen.

## Review Workflow

1. Define the review target.

- Determine whether the target is a GitHub PR, branch, commit range, local diff, or file subset.
- For GitHub PR review, create and use a dedicated local worktree before checking out or fetching the PR branch unless the user explicitly asks to reuse the current workspace.
- If reviewing against main, run `git fetch apache main` before diffing.
- Inspect `git diff --stat` first, then inspect the full patch by touched subsystem.

2. Load focused context.

- Protocol, type mapping, xlang, `TypeMeta`, `TypeInfo`, reference tracking, schema evolution, or wire-format changes require the relevant `docs/specification/**` sections.
- Runtime-specific changes require the matching `.agents/languages/*.md` file.
- Runtime cleanup or cross-language alignment changes require comparing the changed ownership/API shape to the reference runtimes before judging drift, usually C++ then Rust or Java.
- Cross-language changes require `.agents/testing/integration-tests.md`.
- Documentation, public API, benchmark report, or generated-artifact changes require `.agents/docs-and-formatting.md`.

3. Inspect in priority order.

- Correctness, data corruption, security, and protocol drift.
- Cross-language consistency and native/xlang behavior boundaries.
- Performance regressions or invalid benchmark methodology.
- Public API growth, legacy shims, wrapper layers, and architecture drift.
- Missing tests, wrong test placement, and missing docs/spec updates.

4. Validate each finding.

- Tie every finding to exact changed lines.
- Explain the concrete failure mode or regression risk.
- State why the current code is wrong or incomplete, not only that it differs from another style.
- Recommend the missing test, benchmark, spec update, or doc update when that is the gap.

5. Report findings.

- Findings come first and are ordered by severity.
- Keep overview and change summary brief and secondary.
- If there are no findings, say that explicitly and mention residual risks or testing gaps.
- If review comments will be posted on GitHub, write them as concise inline comments with enough context for the author to act.

## Review Red Flags

- One runtime changes xlang or protocol behavior while other affected runtimes are not updated or explicitly ruled out.
- Compatible-mode, type-mapping, or wire-format semantics are described from memory instead of current specs.
- Runtime ownership moves into broad facades or helper layers instead of the resolver, context, buffer, stream, or runtime type that owns the state.
- Removed APIs come back as aliases, shims, or thin wrappers without a clear user-owned reason.
- Hot shared paths gain mode booleans, per-call allocations, callback objects, holder objects, wrapper round trips, or unnecessary forwarding helpers.
- Benchmark-only flags, payload identity caches, fixture-specific shortcuts, or serializer-specific conversions inside timed loops change what is measured.
- Benchmark comparisons are run concurrently on one host, based on smoke settings, or reported without regenerated `docs/benchmarks/**` artifacts when benchmark logic changed.
- Two objects appear to own one reader or writer index.
- Stream bind or rebind paths do not detach stale backlinks.
- Flush behavior depends on implicit side effects instead of one explicit owner.
- Stream read-loop comments or fixes assume `(0, nil)` from a socket reader must be fatal immediately.
- Claimed cleanup is not backed by a full-tree search or exact command output.
- Documentation overstates runtime support, benchmark conclusions, or competitive claims beyond the evidence in code and tests.

## Review Validation Matrix

Canonical runtime-specific command rules live in `.agents/languages/*.md`, and cross-language
validation rules live in `.agents/testing/integration-tests.md`. This matrix is a review-oriented
shortcut for spotting missing evidence.

Use the smallest command set that proves the changed behavior. If protocol or xlang behavior changed, require the relevant cross-language tests even when the author did not run them yet.
For review-only tasks, use this matrix to identify missing verification evidence and recommend
commands; do not run these commands from a read-only review subagent. Run commands only during an
implementation, CI-fix, or verification task, or when the user explicitly asks for command execution.

- Repo-wide formatting/lint: `bash ci/format.sh --all`
- Java: from `java/`, `mvn -T16 spotless:check`, `mvn -T16 checkstyle:check`, `mvn -T16 test`, or targeted `mvn -T16 test -Dtest=<Class>#<method>`
- C#: from `csharp/`, `dotnet format Fory.sln --verify-no-changes`, `dotnet build Fory.sln -c Release --no-restore`, and `dotnet test Fory.sln -c Release`
- C++: from `cpp/`, `bazel build //cpp/...` and `bazel test $(bazel query //cpp/...)`; only add `--config=x86_64` on `x86_64` or `amd64`
- Python: from `python/`, `ruff format .`, `ruff check .`, `ENABLE_FORY_CYTHON_SERIALIZATION=0 pytest -v -s .`, and `ENABLE_FORY_CYTHON_SERIALIZATION=1 pytest -v -s .`
- Rust: from `rust/`, `cargo fmt --check`, `cargo clippy --all-targets --all-features -- -D warnings`, and `cargo test --features tests`
- Swift: from `swift/`, `swiftlint lint --config .swiftlint.yml`, `swift build`, and `swift test`
- Go: from `go/fory/`, `go fmt ./...` and `go test -v ./...`
- Xlang matrix triggers: for xlang behavior, type mapping, protocol bytes, compatible mode, `TypeMeta`, or cross-language container semantics, require `org.apache.fory.xlang.CPPXlangTest`, `org.apache.fory.xlang.CSharpXlangTest`, `org.apache.fory.xlang.RustXlangTest`, `org.apache.fory.xlang.GoXlangTest`, and `org.apache.fory.xlang.PythonXlangTest`; include `org.apache.fory.xlang.SwiftXlangTest` when Swift xlang behavior changes.

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
