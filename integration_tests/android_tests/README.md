# Android Integration Tests

This application runs the Fory Core and Fory JSON instrumented tests on Android API 26 and the
current Android API. It consumes the locally installed `fory-core`, `fory-json`, and
`fory-annotation-processor` artifacts.

The single application module owns all Android JSON fixtures. Its release build uses R8 full mode,
and the instrumented tests exercise generated metadata, field and method accessors, creators,
`@JsonCodec`, `@JsonAnyProperty`, subtype metadata, distinct `ForyJsonBuilder` configurations, and
the exact-codec requirement for model hierarchies that cross an Android platform superclass. Java
record coverage uses the same application and instrumentation test class; Android skips that test
below API 34.

## CI Verification

GitHub CI installs the Java artifacts, builds the release APK and Android App Bundle, runs
`verify_release.py`, and then runs the release instrumentation APK. The verifier checks that:

- the annotation processor wrote every expected metadata companion and targeted rule under the
  standard `META-INF/com.android.tools/r8/` resource directory;
- R8 consumed the generated rules and Fory's Android configuration;
- metadata companion names survived shrinking while Android-incompatible JSON code-generation
  classes and Janino did not;
- generated companions are present in the release APK and Android App Bundle DEX files and absent
  from R8's removed-code report; and
- the release mapping, usage, APK, and Android App Bundle artifacts exist and are nonempty.

R8 verification and Android execution are CI-owned. Do not run local R8 builds as acceptance
evidence.

`java/fory-format` is intentionally not covered because it is outside the Android support surface.
