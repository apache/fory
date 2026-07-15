# Android Integration Tests

This multi-project build validates Fory Core and Fory JSON on Android release builds with R8 full
mode. The base profile has `minSdk 26`; the record profile has `minSdk 34`. The test applications
consume Fory artifacts from the local Maven repository.

The fixture contains these ownership paths:

- the root application owns ordinary `@JsonType` models and retains the existing Fory Core tests;
- `json-model-jar` supplies annotated models from a Java-library JAR;
- `json-model-aar` supplies annotated models from an Android-library AAR;
- `record-app`, `record-model-jar`, and `record-model-aar` provide the corresponding Java record
  paths on API 34 and later.

Generated companions and their rules are not kept by application model rules. The only handwritten
keep rules preserve static test entry methods called from the separately optimized instrumentation
APK. `verify_release.py` checks application annotation-processor `CLASS_OUTPUT`, the final JAR and
AAR, merged R8 configuration, mapping, seeds, usage, APK, and DEX files before device tests run.

## Install Fory

```bash
cd ../../java
mvn -T16 --no-transfer-progress \
  -pl fory-json,fory-annotation-processor,fory-json-gradle-plugin -am install \
  -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true
cd ../integration_tests/android_tests
```

## Base Profile

The minimum supported build profile uses Android Gradle Plugin 8.0 and runs on API 26 and the
current Android API. CI pins this matrix to AGP 8.0.2/API 26, AGP 8.0.2/API 36, AGP 8.13.2/API 26,
and AGP 8.13.2/API 36. The current profile uses the repository's current Android Gradle Plugin.

```bash
gradle --no-daemon \
  -PagpVersion=8.0.2 -PcompileSdk=34 \
  :json-model-jar:jar :json-model-aar:assembleRelease :assembleRelease :bundleRelease

mkdir -p build/reports/android-json
gradle --no-daemon \
  -PagpVersion=8.0.2 -PcompileSdk=34 \
  dependencies --configuration releaseRuntimeClasspath \
  > build/reports/android-json/release-dependencies.txt

python3 verify_release.py \
  --profile non-record \
  --dependencies build/reports/android-json/release-dependencies.txt \
  --output build/reports/android-json/release-artifacts.json

gradle --no-daemon \
  -PagpVersion=8.0.2 -PcompileSdk=34 \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,LOW-BATTERY,ACTIVITY-MISSING,UNLOCKED \
  :connectedReleaseAndroidTest
```

Replace the plugin and compile SDK properties with `-PagpVersion=8.13.2 -PcompileSdk=36` for the
current profile.

## Record Profile

Records are intentionally a separate API 34 profile. The minimum record build uses Android Gradle
Plugin 8.2 and JDK 17; Android Gradle Plugin 8.0 is not a record acceptance target. CI pins this
matrix to AGP 8.2.2/API 34 and AGP 8.13.2/API 36. All record modules use Java 17 source and target
compatibility and `minSdk 34`.

```bash
gradle --no-daemon \
  -PagpVersion=8.2.2 -PcompileSdk=34 \
  :record-model-jar:jar :record-model-aar:assembleRelease \
  :record-app:assembleRelease :record-app:bundleRelease

mkdir -p record-app/build/reports/android-json
gradle --no-daemon \
  -PagpVersion=8.2.2 -PcompileSdk=34 \
  :record-app:dependencies --configuration releaseRuntimeClasspath \
  > record-app/build/reports/android-json/release-dependencies.txt

python3 verify_release.py \
  --profile record \
  --dependencies record-app/build/reports/android-json/release-dependencies.txt \
  --output record-app/build/reports/android-json/release-artifacts.json

gradle --no-daemon \
  -PagpVersion=8.2.2 -PcompileSdk=34 \
  :record-app:connectedReleaseAndroidTest
```

The required negative build proves that a record application cannot lower the supported platform
to API 26:

```bash
gradle --no-daemon \
  -PagpVersion=8.2.2 -PcompileSdk=34 -PrecordMinSdk=26 \
  :record-app:assembleRelease
```

This command must fail with `Fory JSON Android records require minSdk 34 or later`.

## Release Verification

The verifier rejects a release when any of these conditions is observed:

- a Java-library JAR is missing its generated metadata companion or exact rule carrier;
- a published AAR is missing its generated metadata companion or standard consumer rule, or still
  contains an internal rule carrier;
- the application variant did not collect exact rules from application, JAR, and same-build AAR
  class inputs;
- the merged R8 configuration did not consume Fory's Android constant assumption or a per-type
  generated rule;
- a generated companion convention name was obfuscated;
- the APK or Android App Bundle packages a rule carrier, or the APK packages Fory JSON runtime code
  generation, JIT state, generated-code instantiators, external or shaded Janino, or
  Android-incompatible annotated-type descriptors;
- the Android runtime dependency graph resolves external Janino;
- an application-authored broad keep rule covers JSON model packages.

The emitted JSON report records artifact size, DEX count, class count, and generated-rule count for
each toolchain profile. CI uploads those reports together with JARs, AARs, R8 mapping and
configuration files, APKs, Android App Bundles, instrumentation results, and AndroidX Benchmark
output.

`java/fory-format` is intentionally not covered because it is outside the Android support surface.
