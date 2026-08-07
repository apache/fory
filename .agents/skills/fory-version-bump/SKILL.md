---
name: fory-version-bump
description: Bump Apache Fory release or post-release development versions across Java, Kotlin, Scala, Python, Rust, Go, C++, C#, Dart, JavaScript, Swift, integration tests, examples, and source docs. Use when preparing a release version, moving main to the next development version, switching install docs to a released version, or auditing Fory version consistency after release scripts run.
---

# Fory Version Bump

## Version Intent

Separate these two targets before editing:

- **Released version**: use in install docs, examples, and package-manager snippets.
- **Next development version**: use in active package and build metadata.

Do not edit `fory-site` unless the user explicitly includes that repository.

## Ecosystem Version Forms

Let `ci/release.py` normalize the input instead of converting versions by hand.

For input `1.1.0-dev`, expected forms are:

| Surface                                         | Expected form    |
| ----------------------------------------------- | ---------------- |
| Java, Kotlin, Scala, Maven integration projects | `1.1.0-SNAPSHOT` |
| Python and compiler packages                    | `1.1.0.dev0`     |
| Rust packages and path dependency versions      | `1.1.0-alpha.0`  |
| Go module dependencies on Fory                  | `v1.1.0-alpha.0` |
| JavaScript package versions                     | `1.1.0-alpha.0`  |
| Dart packages                                   | `1.1.0-dev`      |
| C# package metadata                             | `1.1.0-dev`      |
| CMake project versions                          | `1.1.0`          |
| Bazel module version                            | `1.1.0`          |
| User install docs after a `1.0.0` release       | `1.0.0`          |

## Workflow

1. Read `AGENTS.md`, `tasks/lessons.md`, `.agents/docs-and-formatting.md`, and
   `./.local/AGENTS.md` when present. Review `git status --short --branch`.
2. Record the released and next-development versions in the task file.
3. For a post-release bump, run:

```bash
python ci/release.py bump_version -l all -version <next-dev-version> \
  -release-version <released-version>
```

4. Inspect the complete diff. The helper should update package/build metadata,
   source docs, examples, and these historically missed surfaces:

- `MODULE.bazel`
- `javascript/package-lock.json`
- `integration_tests/idl_tests/kotlin/pom.xml`
- `integration_tests/idl_tests/dart/pubspec.yaml`
- `integration_tests/idl_tests/rust/Cargo.lock`
- `dart/CHANGELOG.md` and `dart/packages/fory/CHANGELOG.md`

5. Search for stale previous development forms and accidental next-development
   versions in user-facing docs:

```bash
rg -n '<previous-development-version-regex>' \
  --glob '!tasks/**' --glob '!**/target/**' --glob '!**/build/**' \
  --glob '!**/bin/**' --glob '!**/obj/**' --glob '!**/node_modules/**'
rg -n '<next-development-version-regex>' \
  README.md docs csharp/README.md swift/README.md scala/README.md \
  java/README.md rust/README.md dart/packages/fory/README.md examples \
  --glob '!**/target/**' --glob '!**/build/**' --glob '!**/node_modules/**'
```

Inspect every hit. Keep unrelated third-party versions, benchmark numbers,
historical changelog entries, test fixtures, and ignored build outputs unchanged.

6. Format changed Markdown and check the patch:

```bash
prettier --write <changed-markdown-files>
git diff --check
```

## Verification Boundary

Treat a version-only bump as a textual metadata and documentation update. Do not
run unit tests, builds, Maven, CMake, Cargo metadata, package-manager validation,
or language test suites locally, including through subagents, unless the user
explicitly asks. CI owns those checks.

Verify only that:

- each ecosystem uses the expected version form;
- install docs use the released version and development metadata uses the next version;
- changelogs contain the released version before the next development section;
- no unexplained stale development version remains;
- formatting and `git diff --check` pass; and
- task files are not staged.

## Finish

- Summarize exact version forms used by ecosystem.
- Note any intentionally retained old version hits.
- Commit tracked code and documentation changes, excluding task scratch files.
