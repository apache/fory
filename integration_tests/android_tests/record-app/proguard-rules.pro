# Keep only the static entry method called from the separately optimized instrumentation APK.
# Record models and generated JSON companions have no application-authored keep rules.
-keep,allowoptimization class org.apache.fory.android.json.record.RecordRuntimeScenarios {
  public static void *(...);
}
