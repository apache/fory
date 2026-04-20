# AGENTS.md

This is the entry point for AI guidance in Apache Fory. Read this file first, then load only the `.agents/*.md` files that match the runtimes or areas you touch.

## Load Additional Guidance On Demand

- `.agents/README.md`: routing table for selective loading.
- `.agents/repo-reference.md`: repo layout, architecture, compiler notes, and key directories.
- `.agents/docs-and-formatting.md`: documentation, specification, and markdown rules.
- `.agents/ci-and-pr.md`: CI triage, PR expectations, and commit conventions.
- `.agents/testing/integration-tests.md`: `integration_tests/` prerequisites, regeneration rules, and commands.
- `.agents/languages/java.md`
- `.agents/languages/csharp.md`
- `.agents/languages/cpp.md`
- `.agents/languages/python.md`
- `.agents/languages/go.md`
- `.agents/languages/rust.md`
- `.agents/languages/swift.md`
- `.agents/languages/javascript.md`
- `.agents/languages/dart.md`
- `.agents/languages/kotlin.md`
- `.agents/languages/scala.md`
- For protocol or xlang changes, load the relevant language files plus `.agents/docs-and-formatting.md` and `.agents/testing/integration-tests.md`.

## Repo-Wide Hard Rules

- Do not preserve legacy, dead, or useless code, tests, or docs unless the user explicitly requests it.
- Ignore internal API compatibility unless the user explicitly requests it. Do not keep shims, wrappers, or transitional paths only to preserve internal call sites.
- Performance is the top priority. Do not introduce regressions without explicit justification.
- "Refactor" means changing structure, ownership, naming, or API shape without changing behavior, wire format, or implementation strategy unless the user explicitly asks for those changes.
- Do not make design tradeoffs the user did not request. If a refactor appears to require a behavior, logic, protocol, or performance tradeoff, stop and ask.
- Treat existing low-level or optimized code as deliberate by default. During a refactor, preserve the current implementation strategy unless the user explicitly asks to redesign or optimize it.
- Do not replace existing C, C++, Cython, unsafe, or other low-level optimized paths with simpler high-level implementations just to make a refactor easier.
- If a refactor accidentally changes logic or implementation strategy, revert that part and re-implement the refactor around the existing logic.
- Use English only in code, comments, and documentation.
- Add comments only when behavior is hard to understand or an algorithm is non-obvious.
- Only add tests that verify internal behaviors or fix specific bugs; do not create unnecessary tests unless requested.
- Do not add cleanup-sentinel tests that only pin deleted APIs or removed fields.
- Tests must exercise the actual code you wrote or changed. Do not write tests that pass by exercising a pre-existing code path that produces similar-looking results. Before writing a test, identify the exact new code path (annotation, codegen output, new API) and verify the test would fail if that code path were removed. When the change involves codegen or annotations, the test must use those annotations on real structs, run through the codegen pipeline, and verify the generated output drives the expected runtime behavior.
- When reading code, skip files not tracked by git by default unless you generated them yourself or the task explicitly requires them.
- Maintain cross-language consistency while respecting language-specific idioms.
- Do not introduce checked exceptions in new code or new APIs.
- Do not use `ThreadLocal` or other ambient runtime-context patterns in Java runtime code. `WriteContext`, `ReadContext`, and `CopyContext` state must stay explicit, generated serializers must not retain context fields, and `Fory` must stay a root-operation facade rather than accumulating serializer/runtime convenience state.
- When a serializer class and constructor shape are known at the call site, prefer direct constructor lambdas or direct instantiation over reflective `Serializers.newSerializer(...)`. Keep reflection for dynamic or general construction paths only.
- For GraalVM, use `fory codegen` to generate serializers when building native images. Do not use GraalVM reflection configuration except for JDK `proxy`.
- In Java native mode (`xlang=false`), only `Types.BOOL` through `Types.STRING` share type IDs with xlang mode (`xlang=true`). Other native-mode type IDs differ.
- Keep class registration enabled unless explicitly requested otherwise.
- Prefer schema-consistent mode unless compatibility work requires something else.
- When debugging test errors, always set `ENABLE_FORY_DEBUG_OUTPUT=1` to see debug output.
- Never work around failures. Find and fix the root cause. Do not hack, weaken, or bypass tests to make them pass.

## Source of Truth

- Primary references: `README.md`, `CONTRIBUTING.md`, `docs/DEVELOPMENT.md`, and language guides under `docs/guide/`.
- Protocol changes require reading and updating the relevant specs in `docs/specification/**` and aligning the relevant cross-language tests.
- If instructions conflict, follow the most specific module docs and call out the conflict.
- `docs/DEVELOPMENT.md` plus updates under `docs/guide/` and `docs/benchmarks/` are synced to `apache/fory-site`; other website content belongs there.
- When benchmark logic, scripts, configuration, or compared serializers change, rerun the relevant benchmarks and refresh the artifacts under `docs/benchmarks/**`.

## Shared Engineering Expectations

- Favor zero-copy techniques, JIT or codegen opportunities, and cache-friendly memory access patterns in performance-critical paths.
- Public APIs must be well-documented and easy to understand.
- Implement comprehensive error handling with meaningful messages.
- Use strong typing and generics appropriately.
- Handle null values appropriately for each language.
- Preserve protocol compatibility across languages.
- Read and respect `docs/specification/xlang_type_mapping.md` when changing cross-language type behavior.
- Handle byte order correctly for cross-platform compatibility.

## Git And Review Rules

- Use `git@github.com:apache/fory.git` as the remote repository. Do not use other remotes when you want to check code under `main`; `apache/main` is the only target main branch instead of `origin/main`.
- Treat `apache/main` as the only mainline baseline, not `origin/main`.
- Before any diff, review, or compare work against `apache/main`, run `git fetch apache main` so comparisons use the latest remote main.
- When reviewing a GitHub pull request, always do the review in a new local git worktree. Do not switch the current branch or reuse the current worktree for that review unless the user explicitly asks for it.
- Contributors should fork `git@github.com:apache/fory.git`, push code changes to the fork, and open pull requests from that fork into `apache/fory`.

## Shared Validation Expectations

- Run the relevant tests for every touched language or subsystem before finishing.
- Use `integration_tests/` for cross-language compatibility validation when behavior crosses runtimes.
- If xlang behavior or type mapping changes, run `org.apache.fory.xlang.CPPXlangTest`, `org.apache.fory.xlang.CSharpXlangTest`, `org.apache.fory.xlang.RustXlangTest`, `org.apache.fory.xlang.GoXlangTest`, and `org.apache.fory.xlang.PythonXlangTest`.
- If Swift xlang behavior changes, run `org.apache.fory.xlang.SwiftXlangTest` too.
- For performance work, run the relevant benchmark immediately after each change and report the command plus before/after numbers.
- For performance-optimization rounds, append the hypothesis, change, benchmark command, before/after numbers, and keep/revert decision to `tasks/perf_optimization_rounds.md`.
- For refactors on performance-sensitive code, validate not only tests but also that no implementation-strategy drift was introduced relative to `apache/main` unless the user explicitly asked for that change.

## Working Process

1. Read the relevant specs, guides, and focused `.agents/*.md` files before editing.
2. Understand the affected architecture, subsystem boundaries, and existing tests before changing behavior.
3. Review related issues for context when the change is tied to a known bug, regression, or feature request.
4. Follow the language-specific rules in `.agents/languages/*.md` for the touched runtimes.
5. Update docs, examples, and specs when public behavior, protocol behavior, or workflows change.
6. Format and verify the changed areas before concluding.
7. For refactors, identify the invariants that must not change before editing: behavior, protocol or wire format, implementation strategy, and performance-sensitive data structures.
8. If code is already optimized, refactor around it instead of simplifying it.
9. When in doubt during a refactor, prefer preserving the existing implementation over cleaning it up.

## Repo Map

- `docs/`: specifications, guides, benchmarks, and compiler docs
- `compiler/`: Fory compiler, parser, IR, and code generators
- `java/`, `csharp/`, `cpp/`, `python/`, `go/`, `rust/`, `swift/`, `javascript/`, `dart/`, `kotlin/`, `scala/`: language implementations
- `integration_tests/`: cross-language integration coverage
- `benchmarks/`: benchmark harnesses and reports
- `.github/workflows/` and `ci/`: CI configuration and helper scripts

## Commit And PR Expectations

- PR titles must follow Conventional Commits; `.github/workflows/pr-lint.yml` enforces this.
- Performance changes should use the `perf` type and include benchmark data.
- See `.agents/ci-and-pr.md` for GitHub CLI triage commands and commit message examples.
