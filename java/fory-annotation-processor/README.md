# Fory Annotation Processor

`fory-annotation-processor` generates static binary serializers for Java classes annotated with
`@ForyStruct` and Android JSON metadata for classes annotated with `@JsonType`. Both are generated
by javac during the application or library build.

Use this processor for:

- Android applications.
- ordinary JVM applications using `ForyBuilder#withCodegen(false)`.
- Android model classes that use Fory type-use annotations such as `@Ref`, `@UInt8Type`, or
  `@Float16Type`.
- Fory JSON models that must retain private members, generic type structure, JSON annotations, and
  direct member access through R8 shrinking and obfuscation.

For GraalVM native images, use Fory's GraalVM native-image build-time serializer generation instead.

## Ownership

The processor is an opt-in build tool. Applications add it to their annotation-processor path. It
depends on `fory-json`, while neither `fory-core` nor `fory-json` depends on the processor.

For Android JSON, add the matching runtime and processor dependencies to every module that compiles
default-mapped model source:

```gradle
dependencies {
  implementation("org.apache.fory:fory-json:<version>")
  annotationProcessor("org.apache.fory:fory-annotation-processor:<version>")
}
```

```java
import org.apache.fory.json.annotation.JsonType;

@JsonType
public final class Account {
  private long id;
}
```

The processor emits exact per-type rules under `META-INF/com.android.tools/r8/`. R8 consumes this
standard resource from application classes, Java-library JARs, and Android-library AARs, so no Fory
Gradle plugin or package-wide keep rule is required. Keep the runtime and processor on the same
version.

Ordinary Android JSON models support Android Gradle Plugin 8.0 or later with `minSdk 26`. Record
model paths require Android API 34 or later, Android Gradle Plugin 8.2 or later, a JDK 17 build, and
Java 17 source and target compatibility. An application that guards record paths by API level can
keep `minSdk 26`; records are not supported on Android API 26 through 33.

For the user-facing guides, see
[`docs/guide/java/android-support.md`](../../docs/guide/java/android-support.md) and
[`docs/guide/java/static-generated-serializers.md`](../../docs/guide/java/static-generated-serializers.md).
