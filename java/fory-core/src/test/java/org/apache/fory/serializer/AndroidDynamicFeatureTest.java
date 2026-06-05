/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fory.serializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import org.apache.fory.Fory;
import org.apache.fory.TestUtils;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.scala.SingletonCollectionSerializer;
import org.apache.fory.serializer.scala.SingletonMapSerializer;
import org.apache.fory.serializer.scala.SingletonObjectSerializer;
import org.apache.fory.type.union.Union;
import org.apache.fory.type.union.Union2;
import org.apache.fory.util.function.Functions;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AndroidDynamicFeatureTest {
  @Test
  public void testAndroidDynamicFeaturePaths() throws Exception {
    ProcessBuilder processBuilder =
        new ProcessBuilder(TestUtils.javaCommand(AndroidDynamicFeatureProbe.class))
            .redirectErrorStream(true);
    processBuilder.environment().put("FORY_ANDROID_ENABLED", "1");
    Process process = processBuilder.start();
    String output = readFully(process.getInputStream());
    Assert.assertEquals(process.waitFor(), 0, output);
  }

  private static String readFully(InputStream inputStream) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int read;
    while ((read = inputStream.read(buffer)) != -1) {
      outputStream.write(buffer, 0, read);
    }
    return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
  }

  public static final class AndroidDynamicFeatureProbe {
    public static void main(String[] args) {
      System.setProperty("java.vm.name", "Dalvik");
      System.setProperty("java.runtime.name", "Android Runtime");
      check(AndroidSupport.IS_ANDROID, "AndroidSupport should detect Dalvik runtime");
      check(
          LambdaSerializer.STUB_LAMBDA_CLASS == LambdaSerializer.ReplaceStub.class,
          "Android must not create a runtime lambda stub class");
      verifyReflectiveGetter();
      verifyJdkInternalFieldAccessDisabled();
      verifyXlangUnion();

      verifyFory(false);
      verifyFory(true);
    }

    private static void verifyReflectiveGetter() {
      try {
        Method method = AndroidGetter.class.getDeclaredMethod("value");
        ToIntFunction<Object> getter =
            (ToIntFunction<Object>) Functions.makeGetterFunction(method, int.class);
        checkEquals(getter.applyAsInt(new AndroidGetter(17)), 17, "Android reflective getter");
      } catch (NoSuchMethodException e) {
        throw new AssertionError(e);
      }
    }

    private static void verifyFory(boolean compressString) {
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withCodegen(true)
              .withStringCompressed(compressString)
              .withRefTracking(true)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      check(!fory.getConfig().isCodeGenEnabled(), "Android must force codegen off");
      registerPhase4Serializers(fory);

      AndroidCustomType custom =
          (AndroidCustomType) fory.deserialize(fory.serialize(new AndroidCustomType(23)));
      checkEquals(custom.value, 23, "Android reflective serializer constructor");

      assertRoundTrip(fory, "ascii");
      assertRoundTrip(fory, "mañana");
      assertRoundTrip(fory, "你好, Android");
      StringBuilder builder =
          (StringBuilder) fory.deserialize(fory.serialize(new StringBuilder("builder-你好")));
      checkEquals(builder.toString(), "builder-你好", "StringBuilder round trip");
      verifyOutputStreamSerialization(fory);
      verifyCustomUnionFactory(fory);
      verifyObjectStreamHooks(fory);
      verifyReplaceResolveHooks(fory);
      verifyScalaSingletons(fory);

      IllegalArgumentException exception = new IllegalArgumentException("android-message");
      exception.addSuppressed(new RuntimeException("suppressed"));
      IllegalArgumentException restored =
          (IllegalArgumentException) fory.deserialize(fory.serialize(exception));
      checkEquals(restored.getMessage(), exception.getMessage(), "Throwable message");
      checkEquals(restored.getSuppressed()[0].getMessage(), "suppressed", "suppressed message");

      check(
          fory.getTypeResolver().getSerializerClass(SerializedLambda.class)
              == SerializedLambdaSerializer.class,
          "SerializedLambda registration must stay stable on Android");
      expectUnsupported(fory.getSerializer(LambdaSerializer.ReplaceStub.class));
      expectUnsupported(fory.getSerializer(SerializedLambda.class));
    }

    private static void registerPhase4Serializers(Fory fory) {
      fory.registerSerializer(AndroidCustomType.class, AndroidCustomSerializer.class);
      fory.registerSerializer(
          AndroidObjectStreamType.class,
          new ObjectStreamSerializer(fory.getTypeResolver(), AndroidObjectStreamType.class));
      fory.registerSerializer(AndroidReplaceType.class, ReplaceResolveSerializer.class);
      fory.registerSerializer(AndroidReplacementType.class, ReplaceResolveSerializer.class);
      fory.registerSerializer(AndroidSingletonObject.class, SingletonObjectSerializer.class);
      fory.registerSerializer(
          AndroidSingletonCollection.class, SingletonCollectionSerializer.class);
      fory.registerSerializer(AndroidSingletonMap.class, SingletonMapSerializer.class);
    }

    private static void assertRoundTrip(Fory fory, String value) {
      checkEquals(fory.deserialize(fory.serialize(value)), value, "String round trip");
    }

    private static void verifyOutputStreamSerialization(Fory fory) {
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      String value = "stream-你好";
      fory.serialize(outputStream, value);
      checkEquals(fory.deserialize(outputStream.toByteArray()), value, "OutputStream round trip");
    }

    private static void verifyJdkInternalFieldAccessDisabled() {
      check(
          !MemoryUtils.JDK_INTERNAL_FIELD_ACCESS,
          "Android must report JDK internal field access unsupported");
    }

    private static void verifyXlangUnion() {
      Fory fory =
          Fory.builder().withXlang(true).registerGuavaTypes(false).withCompatible(true).build();
      Union2<String, Integer> value = Union2.ofT2(26);
      checkEquals(fory.deserialize(fory.serialize(value)), value, "Android xlang union");
    }

    private static void verifyCustomUnionFactory(Fory fory) {
      UnionSerializer serializer =
          new UnionSerializer(fory.getTypeResolver(), AndroidCustomUnion.class);
      AndroidCustomUnion copied =
          (AndroidCustomUnion) serializer.copy(null, new AndroidCustomUnion(7, null));
      checkEquals(copied.getIndex(), 7, "Android custom Union constructor");
      check(copied.getValue() == null, "Android custom Union value");
    }

    private static void verifyObjectStreamHooks(Fory fory) {
      AndroidObjectStreamType restored =
          (AndroidObjectStreamType)
              fory.deserialize(fory.serialize(new AndroidObjectStreamType("hook-value")));
      checkEquals(restored.value, "hook-value", "ObjectStream default field");
      checkEquals(restored.hookValue, "hook-value-hook", "ObjectStream private hooks");
    }

    private static void verifyReplaceResolveHooks(Fory fory) {
      AndroidReplaceType restored =
          (AndroidReplaceType) fory.deserialize(fory.serialize(new AndroidReplaceType("replace")));
      checkEquals(restored.value, "replace-resolved", "ReplaceResolve private hooks");
    }

    private static void verifyScalaSingletons(Fory fory) {
      check(
          fory.deserialize(fory.serialize(AndroidSingletonObject.MODULE$))
              == AndroidSingletonObject.MODULE$,
          "Scala singleton object");
      check(
          fory.deserialize(fory.serialize(AndroidSingletonCollection.MODULE$))
              == AndroidSingletonCollection.MODULE$,
          "Scala singleton collection");
      check(
          fory.deserialize(fory.serialize(AndroidSingletonMap.MODULE$))
              == AndroidSingletonMap.MODULE$,
          "Scala singleton map");
    }

    private static void expectUnsupported(Serializer<?> serializer) {
      try {
        serializer.write(null, null);
        throw new AssertionError("Expected Android lambda write to fail");
      } catch (UnsupportedOperationException expected) {
        check(
            expected.getMessage().contains("Lambda serialization is unsupported on Android"),
            "Unexpected unsupported message: " + expected.getMessage());
      }
      try {
        serializer.read(null);
        throw new AssertionError("Expected Android lambda read to fail");
      } catch (UnsupportedOperationException expected) {
        check(
            expected.getMessage().contains("Lambda serialization is unsupported on Android"),
            "Unexpected unsupported message: " + expected.getMessage());
      }
      try {
        serializer.copy(null, null);
        throw new AssertionError("Expected Android lambda copy to fail");
      } catch (UnsupportedOperationException expected) {
        check(
            expected.getMessage().contains("Lambda serialization is unsupported on Android"),
            "Unexpected unsupported message: " + expected.getMessage());
      }
    }

    private static void check(boolean value, String message) {
      if (!value) {
        throw new AssertionError(message);
      }
    }

    private static void checkEquals(Object actual, Object expected, String message) {
      if (!expected.equals(actual)) {
        throw new AssertionError(message + ": expected " + expected + ", actual " + actual);
      }
    }

    public static final class AndroidGetter {
      private final int value;

      private AndroidGetter(int value) {
        this.value = value;
      }

      private int value() {
        return value;
      }
    }

    public static final class AndroidCustomType {
      private final int value;

      private AndroidCustomType(int value) {
        this.value = value;
      }
    }

    public static final class AndroidCustomUnion extends Union {
      private AndroidCustomUnion(int index, Object value) {
        super(index, value);
      }
    }

    public static final class AndroidCustomSerializer extends Serializer<AndroidCustomType> {
      public AndroidCustomSerializer(TypeResolver typeResolver, Class<AndroidCustomType> type) {
        super(typeResolver.getConfig(), type, true);
      }

      @Override
      public void write(WriteContext writeContext, AndroidCustomType value) {
        writeContext.getBuffer().writeVarInt32(value.value);
      }

      @Override
      public AndroidCustomType read(ReadContext readContext) {
        return new AndroidCustomType(readContext.getBuffer().readVarInt32());
      }
    }

    public static final class AndroidObjectStreamType implements Serializable {
      private String value;
      private transient String hookValue;

      private AndroidObjectStreamType() {}

      private AndroidObjectStreamType(String value) {
        this.value = value;
      }

      private void writeObject(ObjectOutputStream outputStream) throws IOException {
        outputStream.defaultWriteObject();
        outputStream.writeObject(value + "-hook");
      }

      private void readObject(ObjectInputStream inputStream)
          throws IOException, ClassNotFoundException {
        inputStream.defaultReadObject();
        hookValue = (String) inputStream.readObject();
      }
    }

    public static final class AndroidReplaceType implements Serializable {
      private String value;

      private AndroidReplaceType() {}

      private AndroidReplaceType(String value) {
        this.value = value;
      }

      private Object writeReplace() {
        return new AndroidReplacementType(value);
      }
    }

    public static final class AndroidReplacementType implements Serializable {
      private String value;

      private AndroidReplacementType() {}

      private AndroidReplacementType(String value) {
        this.value = value;
      }

      private Object readResolve() {
        return new AndroidReplaceType(value + "-resolved");
      }
    }

    public static final class AndroidSingletonObject implements Serializable {
      public static final AndroidSingletonObject MODULE$ = new AndroidSingletonObject();

      private AndroidSingletonObject() {}
    }

    public static final class AndroidSingletonCollection extends AbstractList<String>
        implements Serializable {
      public static final AndroidSingletonCollection MODULE$ = new AndroidSingletonCollection();

      private AndroidSingletonCollection() {}

      @Override
      public String get(int index) {
        throw new IndexOutOfBoundsException(String.valueOf(index));
      }

      @Override
      public int size() {
        return 0;
      }
    }

    public static final class AndroidSingletonMap extends AbstractMap<String, String>
        implements Serializable {
      public static final AndroidSingletonMap MODULE$ = new AndroidSingletonMap();

      private AndroidSingletonMap() {}

      @Override
      public Set<Map.Entry<String, String>> entrySet() {
        return Collections.emptySet();
      }
    }
  }
}
