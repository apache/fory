# Manual Apache Fory Release Staging

Use this path only when the release manager explicitly requests manual
publishing. Complete the release branch, version commit, and immutable RC tag
steps from the main skill first.

## Local Configuration

Load these values from the ignored `.local/fory-release.env`. Ask for missing
values once and never commit the file.

```bash
FORY_RELEASE_MANAGER_NAME="..."
FORY_RELEASE_GPG_FINGERPRINT="..."
FORY_DIST_DEV_WC="..."
```

Load the configuration and derive the manual release values:

```bash
release_config="$(git rev-parse --show-toplevel)/.local/fory-release.env"
test -f "$release_config"
. "$release_config"
release_manager_name="${FORY_RELEASE_MANAGER_NAME:?missing release manager name}"
gpg_fingerprint="${FORY_RELEASE_GPG_FINGERPRINT:?missing GPG fingerprint}"
svn_wc="${FORY_DIST_DEV_WC:?missing ASF Subversion working-copy path}"
```

Export `NEXUS_USERNAME` and `NEXUS_PASSWORD` from the release manager's secret
store. Do not print them or save them in the local release environment file.

## Stage JVM Artifacts

```bash
python3 ci/release.py publish_jvm
```

Record the distinct Java/Kotlin and Scala staging IDs created by this command,
plus the `java.runtime.version` and `java.vendor` printed for the publication JDK.
Use those exact values as `java_runtime_version` and `java_vendor` below.
Then read and follow [Nexus staging closure](nexus-staging.md), using only those
two IDs. Keep both repositories closed during the vote and do not promote them
until the vote passes.

## Build the Source Release

Start from the clean release branch. Archive the exact RC tag without changing
tracked files, creating commits, or moving refs. The root LICENSE and NOTICE
must remain unchanged; benchmark attribution stays with the excluded benchmarks.
Install the pinned Syft version specified in the main skill before building.

```bash
test -z "$(git status --porcelain)"
before_build="$(git rev-parse HEAD)"
python3 ci/release.py build -v "$release_version" --rc-tag "$rc_tag"
test "$(git rev-parse HEAD)" = "$before_build"
test -z "$(git status --porcelain)"
test -f "dist/apache-fory-${release_version}-src.tar.gz"
test -f "dist/apache-fory-${release_version}-src.tar.gz.asc"
test -f "dist/apache-fory-${release_version}-src.tar.gz.sha512"
test -f "dist/apache-fory-${release_version}-src.tar.gz.cdx.json"
test -f "dist/apache-fory-${release_version}-src.tar.gz.cdx.json.asc"
test -f "dist/apache-fory-${release_version}-src.tar.gz.cdx.json.sha512"
test "$(gzip -dc "dist/apache-fory-${release_version}-src.tar.gz" | git get-tar-commit-id)" = "$release_commit"
```

The command verifies the PGP signatures and SHA-512 checksums of the source
archive and its SBOM.

## Upload the Source Release to ASF Subversion

Use a clean, updated working copy of the ASF development distribution
repository. Upload the source archive and its SBOM, each with its signature and checksum.
Verification reports and logs are not distribution artifacts and must not be
committed to SVN.

```bash
test -d "$svn_wc/.svn" || \
  svn checkout https://dist.apache.org/repos/dist/dev/fory "$svn_wc"
svn update "$svn_wc"
mkdir -p "$svn_wc/$release_version"
cp "dist/apache-fory-${release_version}-src.tar.gz" \
  "dist/apache-fory-${release_version}-src.tar.gz.asc" \
  "dist/apache-fory-${release_version}-src.tar.gz.sha512" \
  "dist/apache-fory-${release_version}-src.tar.gz.cdx.json" \
  "dist/apache-fory-${release_version}-src.tar.gz.cdx.json.asc" \
  "dist/apache-fory-${release_version}-src.tar.gz.cdx.json.sha512" \
  "$svn_wc/$release_version/"
svn add --force "$svn_wc/$release_version"
svn status "$svn_wc/$release_version"
svn commit "$svn_wc/$release_version" -m "Prepare Apache Fory ${rc_tag}"
test -z "$(svn status "$svn_wc/$release_version")"
svn log -l 1 "$svn_wc/$release_version"
svn ls "https://dist.apache.org/repos/dist/dev/fory/${release_version}/"
```

Inspect `svn status` before committing. The upload is complete only after the
commit returns a revision and remote `svn ls` shows all six source-release
files. For the vote template, set:

```bash
release_candidate_url="https://dist.apache.org/repos/dist/dev/fory/${release_version}/"
```

## Verify Staged Artifacts

After closing both Nexus repositories, rebuild from the exact RC tag and compare
the unsigned local output with every staged source and JVM artifact. Set
`JAVA_HOME` and `PATH` to the same JDK distribution and full runtime version used
for publication; the verifier checks both before downloading or building:

```bash
python3 ci/release.py verify_ci_artifacts \
  -v "$release_version" \
  --rc-tag "$rc_tag" \
  --java-kotlin-id "$java_kotlin_staging_id" \
  --scala-id "$scala_staging_id" \
  --gpg-fingerprint "$gpg_fingerprint" \
  --java-runtime-version "$java_runtime_version" \
  --java-vendor "$java_vendor" \
  --source-url "$release_candidate_url"
```

This verification uses only the public signing key. It does not sign or upload
artifacts. Keep the generated report locally; it can be attached to the vote
email or referenced through an existing public URL, but not uploaded to SVN.
