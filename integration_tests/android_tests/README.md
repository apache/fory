# Android Integration Tests

This project runs Android API 26+ instrumented tests for Java `fory-core`, Java
`fory-json`, and Kotlin `fory-json-kotlin`. API 26 runs both debug coverage and
the release-minified suite. API 36 runs the release-minified suite. Release
coverage verifies static serializers, processor-generated Fory JSON execution
for mutable Java classes, `JsonCreator` classes, object-mapped and `JsonValue`
desugared Records, runtime Kotlin metadata and exact KSP retention through R8
minification, generated validator invocation and failure propagation, and
exact target-Mixin behavior. Mixin coverage registers the source at runtime
after R8 minification so broad application keep rules cannot hide missing
processor output.

The tests consume `org.apache.fory:fory-core:1.7.1`,
`org.apache.fory:fory-json:1.7.1`,
`org.apache.fory:fory-annotation-processor:1.7.1`,
`org.apache.fory:fory-json-kotlin:1.7.1`,
`org.apache.fory:fory-json-kotlin-ksp:1.7.1`, and the shared Kotlin JSON
corpus from the local Maven repository. From the repository root, install the
Java, Kotlin, KSP, and corpus artifacts through the single Kotlin CI owner before
running Gradle. The fixture uses Gradle 8.13, Android Gradle Plugin 8.13.2,
Android Build Tools 35.0.0, Kotlin Android plugin 2.3.20, and KSP 2.3.8. KSP
packages exact consumer rules through its standard resource output; the fixture
adds no application-specific transform or processor option.

```bash
python ./ci/run_ci.py kotlin --task install-json
cd integration_tests/android_tests
gradle --no-daemon -PforyTestBuildType=debug verifyKotlinJsonRules connectedCheck
gradle --no-daemon -PforyTestBuildType=release connectedCheck
```

The `foryTestBuildType` property is test-only. It selects the target build type
used by the instrumentation suite; production build configuration is unchanged.

`java/fory-format` is intentionally not covered here because it is not part of
the Android support surface.
