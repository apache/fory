---
name: fory-release
description: Stage or verify an Apache Fory release candidate. Use GitHub Actions by default for ATR source staging and Nexus JVM staging; use the retained local manual workflow only when the user explicitly requests manual publishing.
---

# Apache Fory Release

Use `.github/workflows/stage-release-candidate.yml` for source and JVM staging
unless the user explicitly asks for a manual release. The workflow uses
`ci/release.py`; do not reproduce its source-build, JVM-publication, Nexus
closure, or artifact-verification logic in shell commands. Do not add unrelated
test runs.

## Required Inputs

Collect these values before starting:

- `release_version`: final version without `v` or an RC suffix, such as `1.7.0`.
- `rc`: RC suffix, such as `rc3`.
- `previous_version`: previous release tag version, such as `1.6.1`.
- Release discussion URL, if already known. If it is not supplied, find the
  exact release thread in the Fory development-list archive as described below.

Derive the release values:

```bash
release_branch="releases-${release_version}"
rc_tag="v${release_version}-${rc}"
release_candidate_url="https://release-test.apache.org/vote/fory/${release_version}"
```

### Find the release discussion

If the release discussion URL was not supplied, search the
[Fory development-list archive](https://lists.apache.org/list.html?dev@fory.apache.org)
for the exact release version and a `[DISCUSS]` subject. Use the deterministic
helper, which accepts exactly one root discussion thread:

```bash
discussion_url="$(
  python3 .agents/skills/fory-release/scripts/find_discussion.py \
    "$release_version"
)"
```

Open the resulting URL and verify that its subject and body discuss the exact
`release_version`. If the automated search finds zero or multiple roots, use
the archive UI to search the same exact version and `[DISCUSS]`; ask the release
manager only if the result remains absent or ambiguous. When a URL is supplied,
open and verify it instead of assuming it matches this release.

## Default CI Workflow

### 1. Create a clean release branch

Run from the repository root. Clean means no staged, modified, or untracked files.

```bash
test -z "$(git status --porcelain)"
test "$(git remote get-url apache)" = "git@github.com:apache/fory.git"
git fetch apache main --tags
git switch -c "$release_branch" apache/main
test -z "$(git status --porcelain)"
```

For a new release branch, stop if the branch already exists or either cleanliness check fails. To resume an existing candidate, follow [Release retries](#release-retries) instead of recreating its branch or tag. Do not remove or hide user files to make the check pass.

### 2. Bump the version

```bash
python3 ci/release.py bump_version -version "$release_version" -l all
git diff --check
git status --short
```

Review the version diff. Use this command directly; do not substitute another version-bump workflow.

### 3. Commit the release version

```bash
git add -u
git commit -m "prepare release for ${release_version}"
test -z "$(git status --porcelain)"
release_commit="$(git rev-parse HEAD)"
```

Stage only the version changes produced by the release script.

### 4. Create and push the RC tag

Before creating a new RC tag, confirm that neither the local nor remote tag already exists. Never move, delete, or overwrite a published RC tag. Retrying a workflow for its existing tag does not create a new candidate; follow [Release retries](#release-retries).

```bash
test -z "$(git tag --list "$rc_tag")"
test -z "$(git ls-remote --tags apache "refs/tags/${rc_tag}")"
test "$(git rev-parse HEAD)" = "$release_commit"
git tag "$rc_tag" && git push apache "$rc_tag"
test "$(git rev-parse "${rc_tag}^{commit}")" = "$release_commit"
```

The tag starts the ecosystem package-release workflows. A later infrastructure
failure does not by itself change the candidate; diagnose it using
[Release retries](#release-retries).

### 5. Stage source and JVM artifacts in CI

Dispatch the staging workflow on the immutable RC tag:

```bash
gh workflow run stage-release-candidate.yml \
  --repo apache/fory \
  --ref "$rc_tag" \
  -f source=true \
  -f jvm=true
```

Record the exact workflow run ID and URL and wait for it with
`gh run watch --exit-status`. Do not select a run only by commit SHA because a
main-branch run can share the same commit. The workflow stages the signed source
archive to Apache Trusted Release through OIDC, publishes the JVM artifacts,
and closes the Java/Kotlin and Scala Nexus repositories. It does not use SVN.

From the successful run summary, record and open both distinct Nexus staging
repository IDs and the ATR candidate URL:

```bash
java_kotlin_staging_id="orgapachefory-..."
scala_staging_id="orgapachefory-..."
test -n "$java_kotlin_staging_id"
test -n "$scala_staging_id"
test "$java_kotlin_staging_id" != "$scala_staging_id"
```

Keep both repositories closed during the vote. Do not promote them until the
vote passes.

### 6. Check the tag-triggered workflows

Inspect every workflow triggered by the tag. Filter by the tag rather than only
by commit SHA so main-branch runs at the same commit are not mixed into the
result. By default, wait for every run and require successful conclusions:

```bash
python3 .agents/skills/fory-release/scripts/check_tag_workflows.py \
  --repo apache/fory \
  --tag "$rc_tag" \
  --commit "$release_commit" \
  --watch
```

The helper re-queries by tag after waiting to catch later-created runs. A failed
run requires diagnosis, not an automatic RC increment. For a transient failure,
rerun only the failed jobs with `gh run rerun <run-id> --repo apache/fory --failed`,
then run the helper again for the same tag and commit.

If the release manager explicitly waives workflow monitoring for a particular RC, run
the same command with `--allow-incomplete` instead of `--watch`, and record the
snapshot IDs, states, and reason. Do not cancel the remote workflows or report
incomplete runs as successful.

### 7. Verify all CI artifacts on trusted hardware

Do not run this comparison in GitHub Actions. Obtain the public fingerprint of
the Infra-managed CI signing key, then run the repository verifier on a machine
controlled by the release manager:

```bash
: "${gpg_fingerprint:?missing CI signing-key fingerprint}"
python3 ci/release.py verify_ci_artifacts \
  -v "$release_version" \
  --rc-tag "$rc_tag" \
  --java-kotlin-id "$java_kotlin_staging_id" \
  --scala-id "$scala_staging_id" \
  --gpg-fingerprint "$gpg_fingerprint" \
  --source-url "$release_candidate_url"
```

The command checks out the exact RC commit in a temporary clone, rebuilds the
source archive and the complete JVM publication without signing or upload
credentials, verifies the staged signatures with the public key, and compares
every source, JAR, POM, source JAR, documentation JAR, and distribution file
byte-for-byte. Each JVM ecosystem is built once and may reuse the machine's
normal dependency caches; only source and build outputs are clean. It writes a
Markdown report under `dist/`. Any mismatch or missing artifact blocks the vote.

### 8. Draft the vote email

Load only the release-manager identity from `.local/fory-release.env` when
drafting the email. Never commit this ignored file or store release secrets in
it.

```bash
release_config="$(git rev-parse --show-toplevel)/.local/fory-release.env"
test -f "$release_config"
. "$release_config"
release_manager_name="${FORY_RELEASE_MANAGER_NAME:?missing release manager name}"
```

Read [the vote email template](assets/vote-email.txt) and produce a complete,
copyable email. Fill every placeholder from verified output, link the
trusted-hardware report, use an explicit UTC deadline at least 72 hours after
sending, and do not send the email unless requested.

## Explicit Manual Workflow

Only when the user explicitly requests manual publishing, read and follow
[the manual release workflow](references/manual-release.md). Do not fall back
to it automatically after a CI failure.

## Verification-Only Requests

For an existing CI-staged candidate, do not create another tag or staging
repository. Confirm the exact tag, commit, workflow run, ATR URL, both Nexus
repository IDs, and CI signing-key fingerprint, then run steps 6 and 7.

## Release retries

An RC identifies the release content under review, not a CI attempt. Inspect the
failed step and its logs before deciding how to recover. Network timeouts,
dependency-download failures, runner failures, and interrupted status checks do
not by themselves invalidate an unchanged candidate.

Without a substantive release change, keep the same release commit and RC tag
and resume only the failed or incomplete steps. Preserve verified Maven staging
repositories and source artifacts; do not rebuild or republish successful
artifacts merely because an unrelated CI job failed. Before retrying a push,
upload, Nexus action, or SVN commit, inspect remote state because an interrupted
request may already have succeeded. Use the Nexus reference's `--verify-only`
path for repositories that are already closed.

Create a higher RC only when a substantive fix changes the release content,
such as source, dependencies, build inputs, or packaging. Commit the actual fix
before tagging the replacement; do not create an empty commit or a second RC
at the same unchanged release commit just to retry infrastructure. Keep the
previous tag immutable and verify the replacement artifacts through the release
workflow. An unresolved code or artifact defect still blocks the vote.

## Stop Conditions

Before creating a new tag, stop if the Git tree is dirty, the tag already
exists, or its target would differ from the release commit. A failed workflow,
staging operation, or artifact check pauses the dependent step until it is
diagnosed and recovered under [Release retries](#release-retries); failure alone
does not require a higher RC. Before sending the vote, require the ATR candidate
and both closed Nexus repositories to be public, the trusted-hardware report to
show a complete byte-for-byte match, and tag workflows to be successful unless
the release manager explicitly waived monitoring.

## References

- [Apache Fory release guide](https://fory.apache.org/docs/community/how_to_release)
- [Fory development-list archive](https://lists.apache.org/list.html?dev@fory.apache.org)
- [Apache Pekko CI release workflow](https://github.com/apache/pekko/blob/main/.github/workflows/stage-release-candidate.yml)
- [Sonatype Nexus 2 staging REST example](https://support.sonatype.com/hc/en-us/articles/213465448-Automatically-dropping-old-staging-repositories)
