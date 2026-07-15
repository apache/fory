# The release target APK and its separate instrumentation APK are optimized independently. Keep
# only the test entry class and its invoked public static methods. Model types and generated JSON
# companions intentionally receive no application-authored keep rule.
-keep,allowoptimization class org.apache.fory.android.AndroidForyRuntimeScenarios {
  public static void *(...);
}
# These nested models intentionally exercise Fory Core's reflective Android path. JSON
# models below are excluded so their generated exact rules remain the only R8 retention owner.
-keep,allowoptimization class org.apache.fory.android.AndroidForyRuntimeScenarios$* { *; }
-keep,allowoptimization class org.apache.fory.android.json.AndroidJsonRuntimeScenarios {
  public static *** *(...);
}
