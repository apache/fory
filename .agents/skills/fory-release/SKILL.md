---
name: fory-release
description: Stage or verify an Apache Fory release candidate. Use the GitHub Actions release workflow by default for ATR source staging and Nexus JVM staging; use the retained local manual workflow only when the user explicitly requests manual publishing.
---

# Apache Fory Release

Use `.github/workflows/stage-release-candidate.yml` for source and JVM staging
unless the user explicitly asks for a manual release. The workflow uses
`ci/release.py`; do not reproduce its source-build, JVM-publication, Nexus
closure, or artifact-verification logic in shell commands.

Do not add unrelated test runs. An invalid candidate gets a higher RC number;
never move or reuse an RC tag.

## Required Inputs

Collect these values:

- `release_version`: final version without `v` or an RC suffix, such as `1.7.0`.
- `rc`: RC suffix, such as `rc3`.
- `previous_version`: previous release tag version, such as `1.6.1`.
- Release discussion URL, if already known.

Derive the release values:

```bash
release_branch="releases-${release_version}"
rc_tag="v${release_version}-${rc}"
release_candidate_url="https://release-test.apache.org/vote/fory/${release_version}"
```

If the discussion URL was not supplied, find the exact root discussion thread:

```bash
discussion_url="$(
  python3 .agents/skills/fory-release/scripts/find_discussion.py \
    "$release_version"
)"
```

Open the URL and verify that its subject and body discuss the exact version. If
the helper finds zero or multiple roots, search the Fory development-list
archive for the exact version and a `[DISCUSS]` subject. Ask the release manager
only if the result remains absent or ambiguous.

## Default CI Workflow

### 1. Create the release branch and commit

Run from the repository root. Clean means no staged, modified, or untracked
files.

```bash
test -z "$(git status --porcelain)"
test "$(git remote get-url apache)" = "git@github.com:apache/fory.git"
git fetch apache main --tags
git switch -c "$release_branch" apache/main
python3 ci/release.py bump_version -version "$release_version" -l all
git diff --check
git status --short
git add -u
git commit -m "prepare release for ${release_version}"
test -z "$(git status --porcelain)"
release_commit="$(git rev-parse HEAD)"
```

Stop if the branch already exists or the tree is not clean. Review the version
diff and stage only the files changed by `bump_version`.

### 2. Create and push the RC tag

```bash
test -z "$(git tag --list "$rc_tag")"
test -z "$(git ls-remote --tags apache "refs/tags/${rc_tag}")"
test "$(git rev-parse HEAD)" = "$release_commit"
git tag "$rc_tag"
git push apache "$rc_tag"
test "$(git rev-parse "${rc_tag}^{commit}")" = "$release_commit"
```

The tag starts the ecosystem package workflows. Once pushed, any staging or
verification failure invalidates this RC.

### 3. Stage source and JVM artifacts in CI

Dispatch the workflow on the RC tag with both default jobs enabled:

```bash
gh workflow run stage-release-candidate.yml \
  --repo apache/fory \
  --ref "$rc_tag" \
  -f source=true \
  -f jvm=true
```

Find the newly created `workflow_dispatch` run for this exact tag, record its
run ID and URL, and wait for it with `gh run watch --exit-status`. Do not select
a run only by commit SHA because main-branch runs can share the same commit.

The source job builds, signs, and checksum-verifies the existing source-release
archive before uploading `dist/` to Apache Trusted Release (ATR) through OIDC.
It does not use SVN credentials. The JVM job publishes from the tag commit,
identifies only the Nexus repositories created by that run, closes both in one
request, and verifies representative artifacts through anonymous downloads.

From the successful run log or job summary, record the distinct
`java_kotlin_staging_id` and `scala_staging_id`. Open the ATR candidate URL and
both closed Nexus repository URLs before drafting the vote.

### 4. Check tag-triggered workflows

Require all workflows triggered by the RC tag to succeed:

```bash
python3 .agents/skills/fory-release/scripts/check_tag_workflows.py \
  --repo apache/fory \
  --tag "$rc_tag" \
  --commit "$release_commit" \
  --watch
```

If the release manager explicitly waives workflow monitoring for this RC, use
`--allow-incomplete` instead of `--watch` and record the run states and reason.
Do not report incomplete workflows as successful.

### 5. Draft the vote email

Load release-manager identity from `.local/fory-release.env` only when drafting
the email. Never commit this ignored file.

```bash
release_config="$(git rev-parse --show-toplevel)/.local/fory-release.env"
test -f "$release_config"
. "$release_config"
release_manager_name="${FORY_RELEASE_MANAGER_NAME:?missing release manager name}"
apache_email="${FORY_RELEASE_APACHE_EMAIL:?missing Apache email}"
gpg_fingerprint="${FORY_RELEASE_GPG_FINGERPRINT:?missing GPG fingerprint}"
```

If the file does not exist, ask once for the three missing values and create
the ignored local file. Do not store the GitHub or Nexus secrets in it.

Read [the vote email template](assets/vote-email.txt) and produce a complete,
copyable email. Fill every placeholder from verified output, confirm that no
`${...}` placeholder remains, use an explicit UTC deadline at least 72 hours
after sending, and do not send the email unless requested.

## Explicit Manual Workflow

Only when the user explicitly requests manual publishing, read and follow
[the manual release workflow](references/manual-release.md). Do not fall back
to it automatically after a CI failure. A failed CI candidate still requires a
higher RC tag.

## Verification-Only Requests

Verification belongs to this skill; do not create or invoke a separate
release-verification workflow. For an existing candidate, inspect the exact
workflow run and tag, then download and verify the remote source artifacts:

```bash
set -euo pipefail
: "${release_version:?missing release version}"
: "${release_candidate_url:?missing release candidate URL}"
: "${gpg_fingerprint:?missing expected GPG fingerprint}"
release_candidate_url="${release_candidate_url%/}"

archive="apache-fory-${release_version}-src.tar.gz"
verify_root="$(mktemp -d)"
verify_dist="$verify_root/dist"
verify_gnupg="$verify_root/gnupg"
mkdir -m 700 "$verify_gnupg"
mkdir "$verify_dist"
trap 'rm -rf "$verify_root"' EXIT

curl -fL "$release_candidate_url/$archive" -o "$verify_dist/$archive"
curl -fL "$release_candidate_url/$archive.asc" -o "$verify_dist/$archive.asc"
curl -fL "$release_candidate_url/$archive.sha512" \
  -o "$verify_dist/$archive.sha512"
curl -fL https://downloads.apache.org/fory/KEYS -o "$verify_root/KEYS"
GNUPGHOME="$verify_gnupg" gpg --batch --import "$verify_root/KEYS"

(
  cd "$verify_dist"
  if command -v sha512sum >/dev/null; then
    sha512sum --check "$archive.sha512"
  else
    shasum -a 512 -c "$archive.sha512"
  fi
  GNUPGHOME="$verify_gnupg" gpg --batch --verify "$archive.asc" "$archive"
)

# VALIDSIG may append the primary-key fingerprint for subkey signatures.
signer_primary_fingerprint="$(
  GNUPGHOME="$verify_gnupg" gpg --batch --status-fd 1 \
    --verify "$verify_dist/$archive.asc" "$verify_dist/$archive" 2>/dev/null |
    awk '$2 == "VALIDSIG" { print (NF >= 12 ? $12 : $3); exit }'
)"
expected_fingerprint="$(
  printf '%s' "$gpg_fingerprint" | tr -d ' ' | tr '[:lower:]' '[:upper:]'
)"
test "$signer_primary_fingerprint" = "$expected_fingerprint"
```

Use the staging IDs recorded by the exact CI run and the Nexus credentials from
the release manager's secret store to repeat the existing read-only repository
state and anonymous download checks:

```bash
: "${java_kotlin_staging_id:?missing Java/Kotlin staging ID}"
: "${scala_staging_id:?missing Scala staging ID}"
: "${rc_tag:?missing release-candidate tag}"
: "${NEXUS_USERNAME:?missing Nexus username}"
: "${NEXUS_PASSWORD:?missing Nexus password}"
python3 ci/release.py close_jvm_staging \
  -v "$release_version" \
  --rc-tag "$rc_tag" \
  --java-kotlin-id "$java_kotlin_staging_id" \
  --scala-id "$scala_staging_id" \
  --verify-only
```

Finally run the tag-workflow checker from the default workflow without
publishing or changing remote state. Report verification only when the remote
checksum, signature, signer fingerprint, Nexus state/download checks, and tag
workflows all pass.

## Stop Conditions

Before pushing the tag, stop if the tree is dirty, the tag exists, a command
fails, or the tag target differs from the release commit. After pushing it,
stop on any CI, ATR, Nexus, signature, checksum, artifact, or tag-workflow
failure and create a higher RC after the cause is fixed. Do not promote Nexus
repositories or send the vote until the vote itself has passed.

## References

- [Fory development-list archive](https://lists.apache.org/list.html?dev@fory.apache.org)
- [Apache Pekko CI release workflow](https://github.com/apache/pekko/blob/main/.github/workflows/stage-release-candidate.yml)
