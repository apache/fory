# The release-minified instrumented test calls target-APK methods from a
# separate androidTest APK. Keep the target-side scenario entry points so R8
# sees the same Fory API reachability that a production app would have from its
# own code.
-keep class org.apache.fory.android.AndroidForyRuntimeScenarios { *; }
-keep class org.apache.fory.android.AndroidForyRuntimeScenarios$* { *; }

# Instrumentation calls these target-APK entry points from a separate APK. The
# JSON fixture classes remain outside this keep so their own R8 rules are tested.
-keep,allowoptimization class org.apache.fory.android.AndroidJsonScenarios {
  public static void plainReflectionWithoutRules(boolean);
  public static void manualPlainRules();
  public static void generatedPlainRules();
  public static void generatedRecord();
  public static void generatedValueRecord();
  public static void manualCodecs();
  public static void generatedCodecs();
  public static void generatedUnwrapped();
  public static void generatedMixin();
}

# Equivalent user-authored rules for models that deliberately omit @JsonType.
-keepattributes Signature,RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations,AnnotationDefault,MethodParameters
-keepattributes InnerClasses,EnclosingMethod

-keep,allowoptimization class org.apache.fory.android.ManualPlainJsonModel {
  public <init>();
  public int id;
  public java.lang.String name;
}

-keep,allowoptimization class org.apache.fory.android.ManualJsonModel {
  public <init>();
  public org.apache.fory.android.ManualJsonModel$DirectValue direct;
  public org.apache.fory.android.ManualJsonModel$MemberValue declaredField;
  public java.util.List values;
  public org.apache.fory.android.ManualJsonModel$MemberValue[] array;
  public java.util.concurrent.atomic.AtomicReferenceArray atomicArray;
  public java.util.Map byName;
  public java.util.Optional optional;
  public java.util.concurrent.atomic.AtomicReference atomic;
  public java.util.Map extra;
  private org.apache.fory.android.ManualJsonModel$MemberValue declaredProperty;
  private org.apache.fory.android.ManualJsonModel$MemberValue parameterProperty;
  public org.apache.fory.android.ManualJsonModel$MemberValue getDeclaredProperty();
  public void setDeclaredProperty(org.apache.fory.android.ManualJsonModel$MemberValue);
  public org.apache.fory.android.ManualJsonModel$MemberValue getParameterProperty();
  public void setParameterProperty(org.apache.fory.android.ManualJsonModel$MemberValue);
}

-keep,allowoptimization class org.apache.fory.android.ManualJsonModel$DirectValue
-keep,allowoptimization,allowobfuscation class org.apache.fory.android.ManualJsonModel$DirectValueCodec {
  public <init>();
}
-keep,allowoptimization,allowobfuscation class org.apache.fory.android.ManualJsonModel$MemberValueCodec {
  public <init>();
}
-keep,allowoptimization,allowobfuscation class org.apache.fory.android.ManualJsonModel$KeyCodec {
  public <init>();
}
