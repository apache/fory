# Manual Apache Fory Release Staging

Use this path only when the release manager explicitly requests manual
publishing. Complete the release branch, version commit, and immutable RC tag
steps from the main skill first.

## Local Configuration

Load these values from the ignored `.local/fory-release.env`. Ask for missing
values once and never commit the file.

```bash
FORY_RELEASE_MANAGER_NAME="..."
FORY_RELEASE_APACHE_EMAIL="..."
FORY_RELEASE_GPG_FINGERPRINT="..."
FORY_DIST_DEV_WC="..."
```

Export `NEXUS_USERNAME` and `NEXUS_PASSWORD` from the release manager's secret
store. Do not print them or save them in the local release environment file.

## Stage JVM Artifacts

```bash
python3 ci/release.py publish_jvm
```

Record the distinct Java/Kotlin and Scala staging IDs created by this command.
Then read and follow [Nexus staging closure](nexus-staging.md), using only those
two IDs. Keep both repositories closed during the vote and do not promote them
until the vote passes.

## Build the Source Release

Start from the clean release branch. The build temporarily commits the release
archive LICENSE change and resets it, so require the original commit and clean
tree afterward.

```bash
test -z "$(git status --porcelain)"
before_build="$(git rev-parse HEAD)"
python3 ci/release.py build -v "$release_version"
test "$(git rev-parse HEAD)" = "$before_build"
test -z "$(git status --porcelain)"
test -f "dist/apache-fory-${release_version}-src.tar.gz"
test -f "dist/apache-fory-${release_version}-src.tar.gz.asc"
test -f "dist/apache-fory-${release_version}-src.tar.gz.sha512"
```

The command verifies the generated PGP signature and SHA-512 checksum.

## Upload the Source Release to ASF Subversion

Use a clean, updated working copy of the ASF development distribution
repository.

```bash
svn_wc="${FORY_DIST_DEV_WC:?missing ASF Subversion working-copy path}"
test -d "$svn_wc/.svn" || \
  svn checkout https://dist.apache.org/repos/dist/dev/fory "$svn_wc"
svn update "$svn_wc"
mkdir -p "$svn_wc/$release_version"
cp dist/* "$svn_wc/$release_version/"
svn add --force "$svn_wc/$release_version"
svn status "$svn_wc/$release_version"
svn commit "$svn_wc/$release_version" -m "Prepare Apache Fory ${rc_tag}"
test -z "$(svn status "$svn_wc/$release_version")"
svn log -l 1 "$svn_wc/$release_version"
svn ls "https://dist.apache.org/repos/dist/dev/fory/${release_version}/"
```

Inspect `svn status` before committing. The upload is complete only after the
commit returns a revision and remote `svn ls` shows all three source-release
files. For the vote template, set:

```bash
release_candidate_url="https://dist.apache.org/repos/dist/dev/fory/${release_version}/"
```
