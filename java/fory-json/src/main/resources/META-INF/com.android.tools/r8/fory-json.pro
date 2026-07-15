-assumevalues class org.apache.fory.platform.AndroidSupport {
  public static final boolean IS_ANDROID return true;
}

-keepattributes Signature
-keep,allowshrinking,allowoptimization,allowobfuscation class org.apache.fory.reflect.TypeRef {
}
-keep,allowshrinking,allowoptimization,allowobfuscation class * extends org.apache.fory.reflect.TypeRef {
}
