# Agent Reference Index

Load `AGENTS.md` first. Do not load every file under `.agents/` by default; read only the files that match the task.

## Shared References

- `repo-reference.md`: repo layout, protocol overview, compiler notes, and runtime map.
- `docs-and-formatting.md`: docs, specs, markdown formatting, publishing notes, and generated-artifact rules.
- `ci-and-pr.md`: GitHub Actions triage, PR expectations, and commit message format.
- `testing/integration-tests.md`: `integration_tests/` prerequisites, regeneration rules, and commands.

## Runtime References

- `languages/java.md`: Java runtime rules, module map, Maven commands, and Java-specific debugging.
- `languages/csharp.md`: C# rules, `dotnet` commands, and Java-driven C# xlang validation.
- `languages/cpp.md`: C++ rules, Bazel/arch constraints, core layout, and debugging/profile pointers.
- `languages/python.md`: Python/Cython modes, Bazel build variants, tests, and debugging workflow.
- `languages/go.md`: Go rules, `go test` workflow, and panic/debug guidance.
- `languages/rust.md`: Rust rules, cargo workflow, and debug/backtrace guidance.
- `languages/swift.md`: Swift rules, lint/test workflow, and Java-driven xlang validation.
- `languages/javascript.md`: JavaScript/TypeScript commands and package-manager expectations.
- `languages/dart.md`: Dart codegen, test, and analysis commands.
- `languages/kotlin.md`: Kotlin build/test commands and dependency on installed Java artifacts.
- `languages/scala.md`: Scala build/test/format commands and dependency on installed Java artifacts.

## Common Loading Recipes

- Java or JVM runtime work:
  - `AGENTS.md`
  - `languages/java.md`
  - `repo-reference.md`
- Cross-language protocol or xlang work:
  - `AGENTS.md`
  - `docs-and-formatting.md`
  - `testing/integration-tests.md`
  - every touched runtime file under `languages/`
- Performance work:
  - `AGENTS.md`
  - relevant runtime file under `languages/`
  - `docs-and-formatting.md`
  - skill-specific references if using a performance skill
- PR review or CI triage:
  - `AGENTS.md`
  - `ci-and-pr.md`
  - relevant runtime file under `languages/`

## Canonicality

- Treat the files in this directory as the canonical agent-specific guidance.
- Existing skill reference matrices under `.agents/skills/` are task-focused quick references; when they overlap with runtime rules, prefer the matching file in `languages/` or `testing/`.
