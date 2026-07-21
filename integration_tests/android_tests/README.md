# Android Integration Tests

This project runs Android API 26+ instrumented tests for Java `fory-core` and
`fory-json`. API 26 runs both debug reflection coverage and the release-minified
suite. API 36 runs the release-minified suite. Release coverage verifies static
serializers, processor-generated Fory JSON execution for mutable classes,
`JsonCreator` classes, object-mapped and `JsonValue` desugared Records, generated
retention rules, and equivalent application-authored exact rules for
unannotated ordinary classes.

The tests consume `org.apache.fory:fory-core:1.4.0-SNAPSHOT`,
`org.apache.fory:fory-json:1.4.0-SNAPSHOT`, and
`org.apache.fory:fory-annotation-processor:1.4.0-SNAPSHOT` from the local Maven
repository, so install the Java artifacts before running Gradle:

```bash
cd ../../java
mvn -T16 --no-transfer-progress -pl fory-json,fory-annotation-processor -am install -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true
cd ../integration_tests/android_tests
gradle --no-daemon -PforyTestBuildType=debug connectedCheck
gradle --no-daemon -PforyTestBuildType=release connectedCheck
```

The `foryTestBuildType` property is test-only. It selects the target build type
used by the instrumentation suite; production build configuration is unchanged.

`java/fory-format` is intentionally not covered here because it is not part of
the Android support surface.
