# Apache Nexus Staging Closure

Read this reference after `ci/release.py publish_jvm` creates the distinct
Java/Kotlin and Scala staging repositories and their IDs have been recorded in
`java_kotlin_staging_id` and `scala_staging_id`.

## Nexus Repository 2 API

Apache `repository.apache.org` currently exposes the Nexus Repository 2
staging API. The
[Sonatype Nexus 2 staging REST example](https://support.sonatype.com/hc/en-us/articles/213465448-Automatically-dropping-old-staging-repositories)
documents the repository-list response and bulk-action payload. Its
`/bulk/drop` operation deletes a staging repository; it is not the close
operation used here. Close both Fory repositories with `/bulk/close`.

Use the same credentials as the Maven server `apache.releases.https`. Load them
into `NEXUS_USERNAME` and `NEXUS_PASSWORD` from the release manager's secret
store. Do not print them, put them in `.local/fory-release.env`, or write them
to task logs.

```bash
: "${NEXUS_USERNAME:?export the Apache Nexus username}"
: "${NEXUS_PASSWORD:?export the Apache Nexus password}"
```

## Close and Verify the Repositories

Run the repository-owned command with both IDs from the current
`publish_jvm` output:

```bash
python3 ci/release.py close_jvm_staging \
  -v "$release_version" \
  --rc-tag "$rc_tag" \
  --java-kotlin-id "$java_kotlin_staging_id" \
  --scala-id "$scala_staging_id"
```

The command requires two distinct `orgapachefory-*` IDs and an RC tag matching
the final release version. Before mutation, it requires both recorded
repositories to exist and be `open`. It then sends exactly one authenticated
`/bulk/close` request, requires HTTP 201, polls both repositories until they are
`closed`, and verifies anonymous HTTP 200 access to representative Java,
Kotlin, Scala 2.13, and Scala 3 artifacts.

If closure times out, the command retrieves and logs the activity for both
repositories before stopping. Do not repeatedly submit close requests or
proceed to the vote while either repository is open or transitioning.

## Read-Only Verification

For recovery or audit work on repositories that are already closed, add
`--verify-only`:

```bash
python3 ci/release.py close_jvm_staging \
  -v "$release_version" \
  --rc-tag "$rc_tag" \
  --java-kotlin-id "$java_kotlin_staging_id" \
  --scala-id "$scala_staging_id" \
  --verify-only
```

This path never submits a close request. It requires both repositories to
already be `closed` and repeats the anonymous artifact checks. HTTP 200 from an
authenticated API request is not a substitute for these public download
checks.

Record both repository IDs, their `closed` states, and the public verification
results. Keep the repositories closed during the vote. Do not promote/release
them until the vote passes. Drop a failed candidate only after the release
manager decides to abandon that RC.
