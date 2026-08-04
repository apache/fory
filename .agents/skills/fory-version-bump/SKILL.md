---
name: fory-version-bump
description: Bump Apache Fory release or post-release development versions across Java, Kotlin, Scala, Python, Rust, Go, C++, C#, Dart, JavaScript, Swift, integration tests, examples, and source docs. Use when preparing a release version, moving main to the next development version, switching install docs to a released version, or auditing Fory version consistency after release scripts run.
---

# Fory Version Bump

## Mission

Move Apache Fory versions consistently across package metadata, build files, integration tests, examples, lockfiles, and source documentation without mixing release install docs with next-development package versions.

This skill is a guardrail, not a complete source of truth. Version surfaces change over time. After following the checklist, run an independent audit pass to find missed files, stale docs, generated lockfiles, and inconsistent ecosystem-specific version formats.

## Load Context

1. Read `AGENTS.md`, `tasks/lessons.md`, `.agents/docs-and-formatting.md`, and `.agents/ci-and-pr.md`.
2. Check `./.local/AGENTS.md` if it exists.
3. If touching language-specific build files beyond version strings, read the matching `.agents/languages/*.md`.
4. Inspect current dirty state before editing:

```bash
git status --short --branch
```

## Decide Version Intent

Separate these two targets before editing:

- **Release/package version**: the version being released, for example `1.0.0`.
- **Next development version**: the version after release, for example `1.1.0-dev`.

Common post-release split:

- Active package/build metadata moves to next development version.
- User-facing install docs, examples, and package-manager snippets point at the released version.
- Website repository configuration is out of this repo; do not edit `fory-site` unless the current user explicitly asks for that repository too.

## Ecosystem Version Forms

Prefer `ci/release.py` normalization instead of hand-converting every ecosystem.

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

Do not rewrite unrelated third-party dependency versions, benchmark numbers, old changelog sections, generated build outputs under ignored `bin/`, `obj/`, `target/`, or lockfile third-party package entries.

## Primary Workflow

1. Make or update the durable task file when the bump is broad.
2. Run the release helper for package/build metadata:

```bash
python ci/release.py bump_version -l all -version <next-dev-version>
```

3. Audit release-helper gaps. Historically important surfaces include:

- `MODULE.bazel`
- `javascript/package-lock.json`
- `integration_tests/idl_tests/kotlin/pom.xml`
- `integration_tests/idl_tests/dart/pubspec.yaml`
- `integration_tests/idl_tests/rust/Cargo.lock`
- Package-level changelogs, especially `dart/packages/fory/CHANGELOG.md`

4. Update source docs and examples:

- `README.md`
- Runtime README files such as `java/README.md`, `rust/README.md`, `scala/README.md`, `csharp/README.md`, `swift/README.md`, `dart/packages/fory/README.md`
- `docs/start/**` and `docs/object-serialization/**`
- `docs/row-format/**`, `docs/json/**`, and `docs/grpc/**`
- `docs/compiler/**` when compiler examples include Fory package versions
- `examples/**`

5. Keep the doc split clean:

- Install snippets for released packages use the release version.
- Development build files use the next development version.
- Snapshot-only internal test docs may use the snapshot version when they describe local Maven consumption.
- Package publishing changelogs must mention the package version being published before the post-release development bump. Dart pub validates `CHANGELOG.md` for the current package version and can fail publication when the release version is missing.

## Independent Audit Pass

Always do a fresh audit after the mechanical bump. Do not assume the checklist above is complete.

Recommended searches:

```bash
rg -n '0\.(13|14|16|17|18)(\.0|-dev|-alpha\.0)?|v0\.17\.0' \
  README.md docs csharp/README.md swift/README.md scala/README.md \
  java/README.md rust/README.md dart/packages/fory/README.md examples \
  --glob '!docs/benchmarks/**' --glob '!**/target/**' --glob '!**/build/**' \
  --glob '!**/node_modules/**'

rg -n '<next-dev-version-regex>' \
  README.md docs csharp/README.md swift/README.md scala/README.md \
  java/README.md rust/README.md dart/packages/fory/README.md examples \
  --glob '!**/target/**' --glob '!**/build/**' --glob '!**/node_modules/**'

rg -n '<previous-release-or-dev-version-regex>' \
  --glob '!tasks/**' --glob '!**/target/**' --glob '!**/build/**' \
  --glob '!**/bin/**' --glob '!**/obj/**' --glob '!**/node_modules/**'
```

Then inspect every hit. Expected false positives include third-party dependency versions, benchmark measurements, historical changelog sections, and tests whose data intentionally contains version-like values.

## Verification Checklist

- [ ] `git status --short --branch` reviewed before edits.
- [ ] Release and next-development target versions are written down.
- [ ] `ci/release.py bump_version` was run when applicable.
- [ ] Helper gaps were audited and patched.
- [ ] CMake/Bazel use numeric versions, not `.dev` suffixes.
- [ ] Maven/SBT/Kotlin versions use `-SNAPSHOT` for development.
- [ ] Python versions use PEP 440 form such as `.dev0`.
- [ ] Rust, Go, and JavaScript versions use semver prerelease form such as `-alpha.0`.
- [ ] Dart and C# development versions use `-dev`.
- [ ] User-facing install docs use the released version, not the next development version.
- [ ] Package-level `CHANGELOG.md` files mention the exact released package version before publishing.
- [ ] Post-release changelogs retain or add a top next-development section only after the released version section exists.
- [ ] `javascript/package-lock.json` is consistent with changed JS package versions.
- [ ] Rust lockfiles for touched integration tests are consistent with changed Rust package versions.
- [ ] Markdown files outside `tasks/` were formatted with Prettier.
- [ ] JSON package files parse successfully.
- [ ] POM files parse or Maven parent validates.
- [ ] CMake config validates when CMake project versions changed.
- [ ] Independent audit pass found no unexplained stale or inconsistent Fory version hits.
- [ ] Task files under `tasks/` were not staged.

## Useful Validation Commands

```bash
prettier --write <changed-markdown-files>
git diff --check
node -e 'const fs=require("fs"); for (const p of process.argv.slice(1)) JSON.parse(fs.readFileSync(p,"utf8"));' \
  javascript/package-lock.json javascript/packages/core/package.json javascript/packages/hps/package.json
mvn -q -f java/pom.xml -N validate
mvn -q -f kotlin/pom.xml -N validate
cmake -S cpp -B /tmp/fory-cmake-version-check -DFORY_BUILD_TESTS=OFF
cargo metadata --locked --format-version 1
```

Run commands from the relevant working directory. For `cargo metadata`, use the specific Rust workspace or integration test directory whose lockfile changed.

## Finish

- Summarize exact version forms used by ecosystem.
- List audit searches and validation commands.
- Note any intentionally retained old version hits.
- Commit tracked code and documentation changes, excluding task scratch files.
