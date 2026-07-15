# Android Integration Tests

This multi-project build validates Fory Core and Fory JSON on Android release builds with R8 full
mode. The base profile has `minSdk 26`; the record profile has `minSdk 34`. The test applications
consume Fory artifacts from the local Maven repository.

The fixture contains these ownership paths:

- the root application owns ordinary `@JsonType` models and retains the existing Fory Core tests;
- `json-model-jar` supplies annotated models from a Java-library JAR;
- `json-model-aar` supplies annotated models from an Android-library AAR;
- `record-app`, `record-model-jar`, and `record-model-aar` provide the corresponding Java record
  paths on API 34 and later;
- `AarModel` exercises a generated method `@JsonAnySetter`, including the bound exact-handle
  runtime path; and
- the private nested `PrivateStaticModel` exposes public annotated getter/setter methods so its
  generated metadata must use source-inaccessible method access after shrinking.

Generated companions and their rules are not kept by application model rules. The only handwritten
keep rules preserve static test entry methods called from the separately optimized instrumentation
APK. `verify_release.py` checks application annotation-processor `CLASS_OUTPUT`, the final JAR and
AAR, merged R8 configuration, mapping, seeds, usage, APK, and DEX files before device tests run.

## CI-owned R8 Matrix

The minimum supported build profile uses Android Gradle Plugin 8.0 and runs on API 26 and the
current Android API. CI pins this matrix to AGP 8.0.2/API 26, AGP 8.0.2/API 36, AGP 8.13.2/API 26,
and AGP 8.13.2/API 36. The current profile uses the repository's current Android Gradle Plugin.

Records are intentionally a separate API 34 profile. The minimum record build uses Android Gradle
Plugin 8.2 and JDK 17; Android Gradle Plugin 8.0 is not a record acceptance target. CI pins this
matrix to AGP 8.2.2/API 34 and AGP 8.13.2/API 36. All record modules use Java 17 source and target
compatibility and `minSdk 34`.

Release/minified builds, R8 inspection, emulator instrumentation, benchmarks, and the record
minimum-SDK rejection are GitHub CI-owned acceptance checks. This keeps the evidence on the exact
declared toolchain matrix and preserves the uploaded APK, Android App Bundle, mapping, seeds,
usage, dependency, instrumentation, and benchmark artifacts. Local R8 or device runs are not final
acceptance evidence.

## Local Verifier Tests

The release verifier itself has a synthetic mapping test that requires forbidden classes to be
rejected even when R8 renamed them:

```bash
python3 -m unittest test_verify_release.py
```

Run this command from `integration_tests/android_tests`. It does not build Android artifacts or
invoke R8.

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
- a forbidden runtime-codegen, generated-code instantiator, Janino, or `AnnotatedType` class
  survives in the R8 mapping, including under an obfuscated output name;
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
