# Android Integration Tests

This project runs Android API 26+ instrumented tests for Java `fory-core`. The
instrumented tests run against the release build type so R8/minification covers
the static generated serializer path.

The tests consume `org.apache.fory:fory-core:1.3.0-SNAPSHOT` and
`org.apache.fory:fory-annotation-processor:1.3.0-SNAPSHOT` from the local Maven
repository, so install the Java artifacts before running Gradle:

```bash
cd ../../java
mvn -T16 --no-transfer-progress -pl fory-core,fory-annotation-processor -am install -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true
cd ../integration_tests/android_tests
gradle --no-daemon connectedCheck
```

`java/fory-format` is intentionally not covered here because it is not part of
the Android support surface.
