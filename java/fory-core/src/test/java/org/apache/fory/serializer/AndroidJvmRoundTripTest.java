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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.fory.Fory;
import org.apache.fory.annotation.Int32Type;
import org.apache.fory.annotation.Nullable;
import org.apache.fory.annotation.Ref;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Int32Encoding;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.MetaWriteContext;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.reflect.TypeUseMetadata;
import org.apache.fory.serializer.scala.SingletonCollectionSerializer;
import org.apache.fory.serializer.scala.SingletonMapSerializer;
import org.apache.fory.serializer.scala.SingletonObjectSerializer;
import org.apache.fory.type.Types;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class AndroidJvmRoundTripTest {
  private static final String ANDROID_ENABLED_ENV = "FORY_ANDROID_ENABLED";

  static class TypeUseStruct {
    @Nullable String name;
    List<@Ref(enable = false) @Int32Type(encoding = Int32Encoding.FIXED) Integer> codes;
  }

  @DataProvider(name = "roundTripKinds")
  public Object[][] roundTripKinds() {
    return new Object[][] {
      {RoundTripKind.STRING_VALUES},
      {RoundTripKind.EXCEPTION_VALUE},
      {RoundTripKind.OBJECT_STREAM_VALUE},
      {RoundTripKind.REPLACE_RESOLVE_VALUE},
      {RoundTripKind.PROXY_VALUE},
      {RoundTripKind.CODEGEN_VALUE},
      {RoundTripKind.META_SHARED_VALUE},
      {RoundTripKind.COLLECTION_VALUE},
      {RoundTripKind.SINGLETON_VALUE}
    };
  }

  @Test(dataProvider = "roundTripKinds")
  public void testAndroidJvmSerializerRoundTrip(RoundTripKind kind) throws Exception {
    requireAndroidMode();
    File androidPayload = File.createTempFile("fory-android-payload-", ".bin");
    File jvmPayload = File.createTempFile("fory-jvm-payload-", ".bin");
    try {
      Fory androidWriter = newFory(kind);
      Files.write(androidPayload.toPath(), serialize(kind, androidWriter, newValue(kind)));
      runJvmPeer(kind, androidPayload, jvmPayload);

      Fory androidReader = newFory(kind);
      Object jvmValue = deserialize(kind, androidReader, Files.readAllBytes(jvmPayload.toPath()));
      verifyValue(kind, jvmValue);
    } finally {
      androidPayload.delete();
      jvmPayload.delete();
    }
  }

  @Test
  public void testAndroidLambdaSerializersAreUnsupported() {
    requireAndroidMode();
    Fory fory = newFory(RoundTripKind.STRING_VALUES);
    expectUnsupported(fory.getSerializer(LambdaSerializer.ReplaceStub.class));
    expectUnsupported(fory.getSerializer(SerializedLambda.class));
  }

  @Test
  public void testForcedAndroidJvmModeKeepsTypeUseMetadata() throws Exception {
    requireAndroidMode();
    Assert.assertTrue(AndroidSupport.IS_ANDROID);

    Field nameField = TypeUseStruct.class.getDeclaredField("name");
    TypeExtMeta nameMeta =
        TypeRef.ofTypeUse(TypeUseMetadata.fieldTypeUse(nameField)).getTypeExtMeta();
    Assert.assertTrue(nameMeta.nullable());

    Field codesField = TypeUseStruct.class.getDeclaredField("codes");
    TypeExtMeta elementMeta =
        TypeRef.ofTypeUse(TypeUseMetadata.fieldTypeUse(codesField))
            .getTypeArguments()
            .get(0)
            .getTypeExtMeta();
    Assert.assertEquals(elementMeta.typeId(), Types.INT32);
    Assert.assertFalse(elementMeta.trackingRef());
  }

  private static void runJvmPeer(RoundTripKind kind, File androidPayload, File jvmPayload)
      throws Exception {
    ArrayList<String> command = new ArrayList<>();
    command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
    addAddOpens(command);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(JvmPeer.class.getName());
    command.add(kind.name());
    command.add(androidPayload.getAbsolutePath());
    command.add(jvmPayload.getAbsolutePath());
    ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
    processBuilder.environment().put(ANDROID_ENABLED_ENV, "0");
    Process process = processBuilder.start();
    String output = readFully(process.getInputStream());
    Assert.assertEquals(process.waitFor(), 0, output);
  }

  private static void addAddOpens(ArrayList<String> command) {
    if (System.getProperty("java.specification.version").startsWith("1.")) {
      return;
    }
    addAddOpens(command, "java.base/java.lang=ALL-UNNAMED");
    addAddOpens(command, "java.base/java.lang.reflect=ALL-UNNAMED");
    addAddOpens(command, "java.base/java.util=ALL-UNNAMED");
  }

  private static void addAddOpens(ArrayList<String> command, String modulePackage) {
    command.add("--add-opens");
    command.add(modulePackage);
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

  private static void requireAndroidMode() {
    if (!AndroidSupport.IS_ANDROID) {
      throw new SkipException("Android/JVM round-trip tests require " + ANDROID_ENABLED_ENV + "=1");
    }
  }

  private static Fory newFory(RoundTripKind kind) {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withCodegen(true)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withMetaShare(kind == RoundTripKind.META_SHARED_VALUE)
            .withCompatible(false)
            .build();
    fory.registerSerializer(
        StreamHookValue.class,
        new ObjectStreamSerializer(fory.getTypeResolver(), StreamHookValue.class));
    fory.registerSerializer(ReplaceValue.class, ReplaceResolveSerializer.class);
    fory.registerSerializer(ReplacementValue.class, ReplaceResolveSerializer.class);
    fory.registerSerializer(SingletonObject.class, SingletonObjectSerializer.class);
    fory.registerSerializer(SingletonCollection.class, SingletonCollectionSerializer.class);
    fory.registerSerializer(SingletonMap.class, SingletonMapSerializer.class);
    return fory;
  }

  private static byte[] serialize(RoundTripKind kind, Fory fory, Object value) {
    resetMetaContexts(kind, fory);
    return fory.serialize(value);
  }

  private static Object deserialize(RoundTripKind kind, Fory fory, byte[] bytes) {
    resetMetaContexts(kind, fory);
    return fory.deserialize(bytes);
  }

  private static void resetMetaContexts(RoundTripKind kind, Fory fory) {
    if (kind == RoundTripKind.META_SHARED_VALUE) {
      fory.setMetaWriteContext(new MetaWriteContext());
      fory.setMetaReadContext(new MetaReadContext());
    }
  }

  private static Object newValue(RoundTripKind kind) {
    switch (kind) {
      case STRING_VALUES:
        return new StringValues("ascii", "mañana", "你好, Android", "builder-你好", "buffer-λ");
      case EXCEPTION_VALUE:
        return newException();
      case OBJECT_STREAM_VALUE:
        return new StreamHookValue("stream-hook");
      case REPLACE_RESOLVE_VALUE:
        return new ReplaceValue("replace");
      case PROXY_VALUE:
        return Proxy.newProxyInstance(
            AndroidJvmRoundTripTest.class.getClassLoader(),
            new Class<?>[] {GreetingService.class},
            new GreetingHandler("hello", 42));
      case CODEGEN_VALUE:
        return new CodegenValue(7, "codegen", new NestedValue("nested", 13));
      case META_SHARED_VALUE:
        return new MetaShareValue(19, "meta", Arrays.asList(1, 2, 3));
      case COLLECTION_VALUE:
        return newCollectionValue();
      case SINGLETON_VALUE:
        return newSingletonValue();
      default:
        throw new AssertionError("Unknown kind " + kind);
    }
  }

  private static Throwable newException() {
    IllegalStateException exception = new IllegalStateException("android-jvm");
    exception.initCause(new IllegalArgumentException("cause"));
    exception.addSuppressed(new IOException("suppressed"));
    exception.setStackTrace(
        new StackTraceElement[] {
          new StackTraceElement(
              AndroidJvmRoundTripTest.class.getName(),
              "newException",
              "AndroidJvmRoundTripTest.java",
              1)
        });
    return exception;
  }

  private static ArrayList<Object> newCollectionValue() {
    ArrayList<Object> value = new ArrayList<>();
    value.add(Collections.unmodifiableList(Arrays.asList("a", "b")));
    value.add(Collections.unmodifiableSet(new HashSet<>(Arrays.asList("x", "y"))));
    TreeSet<String> sortedSet = new TreeSet<>(new LengthComparator());
    Collections.addAll(sortedSet, "bbb", "a", "cc");
    value.add(Collections.unmodifiableSortedSet(sortedSet));
    value.add(Collections.unmodifiableMap(singletonMap("one", 1)));
    TreeMap<String, Integer> sortedMap = new TreeMap<>(new LengthComparator());
    sortedMap.put("bbb", 3);
    sortedMap.put("a", 1);
    sortedMap.put("cc", 2);
    value.add(Collections.unmodifiableSortedMap(sortedMap));
    value.add(Collections.synchronizedList(new ArrayList<>(Arrays.asList("sync-a", "sync-b"))));
    value.add(Collections.synchronizedSet(new HashSet<>(Arrays.asList("sync-x", "sync-y"))));
    value.add(Collections.synchronizedMap(singletonMap("sync", 1)));
    value.add(new ArrayList<>(Arrays.asList(1, 2, 3, 4)).subList(1, 3));
    value.add(newEnumMapValue());
    value.add(new EnumMap<>(TestEnum.class));
    value.add(jdkImmutableList());
    value.add(jdkImmutableSet());
    value.add(jdkImmutableMap());
    value.add(new ChildArrayList("child-list", "a", "b"));
    value.add(new ChildHashSet("child-set", "x", "y"));
    value.add(new ChildTreeSet("child-sorted-set", "a", "bbb", "cc"));
    value.add(new ChildPriorityQueue("child-queue", "p", "q"));
    value.add(new ChildHashMap("child-map", singletonMap("k", 9)));
    value.add(new ChildTreeMap("child-sorted-map", singletonMap("s", 11)));
    return value;
  }

  private static ArrayList<Object> newSingletonValue() {
    ArrayList<Object> value = new ArrayList<>();
    value.add(SingletonObject.MODULE$);
    value.add(SingletonCollection.MODULE$);
    value.add(SingletonMap.MODULE$);
    return value;
  }

  private static Map<String, Integer> singletonMap(String key, int value) {
    HashMap<String, Integer> map = new HashMap<>();
    map.put(key, value);
    return map;
  }

  private static EnumMap<TestEnum, String> newEnumMapValue() {
    EnumMap<TestEnum, String> map = new EnumMap<>(TestEnum.class);
    map.put(TestEnum.ONE, "one");
    map.put(TestEnum.TWO, "two");
    return map;
  }

  private static Object jdkImmutableList() {
    try {
      Method method = List.class.getMethod("of", Object[].class);
      return method.invoke(null, new Object[] {new Object[] {"i-a", "i-b"}});
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  private static Object jdkImmutableSet() {
    try {
      Method method = Set.class.getMethod("of", Object[].class);
      return method.invoke(null, new Object[] {new Object[] {"s-a", "s-b"}});
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  private static Object jdkImmutableMap() {
    try {
      Method method =
          Map.class.getMethod("of", Object.class, Object.class, Object.class, Object.class);
      return method.invoke(null, "m-a", 1, "m-b", 2);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  private static void verifyValue(RoundTripKind kind, Object value) {
    switch (kind) {
      case STRING_VALUES:
        verifyStringValues((StringValues) value);
        return;
      case EXCEPTION_VALUE:
        verifyException((Throwable) value);
        return;
      case OBJECT_STREAM_VALUE:
        verifyStreamHookValue((StreamHookValue) value);
        return;
      case REPLACE_RESOLVE_VALUE:
        verifyReplaceResolveValue((ReplaceValue) value);
        return;
      case PROXY_VALUE:
        verifyProxy(value);
        return;
      case CODEGEN_VALUE:
        Assert.assertEquals(value, newValue(kind));
        return;
      case META_SHARED_VALUE:
        Assert.assertEquals(value, newValue(kind));
        return;
      case COLLECTION_VALUE:
        verifyCollectionValue((List<?>) value);
        return;
      case SINGLETON_VALUE:
        verifySingletonValue((List<?>) value);
        return;
      default:
        throw new AssertionError("Unknown kind " + kind);
    }
  }

  private static void verifyStringValues(StringValues value) {
    Assert.assertEquals(value.ascii, "ascii");
    Assert.assertEquals(value.latin, "mañana");
    Assert.assertEquals(value.utf16, "你好, Android");
    Assert.assertEquals(value.builder.toString(), "builder-你好");
    Assert.assertEquals(value.buffer.toString(), "buffer-λ");
  }

  private static void verifyException(Throwable value) {
    Assert.assertEquals(value.getClass(), IllegalStateException.class);
    Assert.assertEquals(value.getMessage(), "android-jvm");
    Assert.assertEquals(value.getCause().getClass(), IllegalArgumentException.class);
    Assert.assertEquals(value.getCause().getMessage(), "cause");
    Assert.assertEquals(value.getSuppressed().length, 1);
    Assert.assertEquals(value.getSuppressed()[0].getMessage(), "suppressed");
    Assert.assertEquals(
        value.getStackTrace()[0].getClassName(), AndroidJvmRoundTripTest.class.getName());
  }

  private static void verifyStreamHookValue(StreamHookValue value) {
    Assert.assertEquals(value.value, "stream-hook");
    Assert.assertEquals(value.hookValue, "stream-hook-hook");
  }

  private static void verifyReplaceResolveValue(ReplaceValue value) {
    Assert.assertEquals(value.value, "replace-resolved");
  }

  private static void verifyProxy(Object value) {
    Assert.assertTrue(Proxy.isProxyClass(value.getClass()));
    GreetingService service = (GreetingService) value;
    Assert.assertEquals(service.greet("fory"), "hello fory");
    Assert.assertEquals(service.id(), 42);
  }

  @SuppressWarnings("unchecked")
  private static void verifyCollectionValue(List<?> value) {
    Assert.assertEquals(value.get(0), Arrays.asList("a", "b"));
    expectUnsupportedAdd((Collection<Object>) value.get(0));
    Assert.assertEquals(value.get(1), new HashSet<>(Arrays.asList("x", "y")));
    expectUnsupportedAdd((Collection<Object>) value.get(1));
    Assert.assertEquals(value.get(2), new TreeSet<>(Arrays.asList("a", "bbb", "cc")));
    Assert.assertTrue(((SortedSet<?>) value.get(2)).comparator() instanceof LengthComparator);
    Assert.assertEquals(value.get(3), singletonMap("one", 1));
    expectUnsupportedPut((Map<Object, Object>) value.get(3));
    Assert.assertEquals(value.get(4), sortedMapExpected());
    Assert.assertTrue(((SortedMap<?, ?>) value.get(4)).comparator() instanceof LengthComparator);
    Assert.assertEquals(value.get(5), Arrays.asList("sync-a", "sync-b"));
    Assert.assertEquals(value.get(6), new HashSet<>(Arrays.asList("sync-x", "sync-y")));
    Assert.assertEquals(value.get(7), singletonMap("sync", 1));
    Assert.assertEquals(value.get(8), Arrays.asList(2, 3));
    Assert.assertEquals(value.get(9), newEnumMapValue());
    Assert.assertEquals(value.get(10), new EnumMap<>(TestEnum.class));
    if (value.get(11) != null) {
      Assert.assertEquals(value.get(11), Arrays.asList("i-a", "i-b"));
      expectUnsupportedAdd((Collection<Object>) value.get(11));
    }
    if (value.get(12) != null) {
      Assert.assertEquals(value.get(12), new HashSet<>(Arrays.asList("s-a", "s-b")));
      expectUnsupportedAdd((Collection<Object>) value.get(12));
    }
    if (value.get(13) != null) {
      Assert.assertEquals(value.get(13), immutableMapExpected());
      expectUnsupportedPut((Map<Object, Object>) value.get(13));
    }
    ChildArrayList childList = (ChildArrayList) value.get(14);
    Assert.assertEquals(childList.label, "child-list");
    Assert.assertEquals(childList, Arrays.asList("a", "b"));
    ChildHashSet childSet = (ChildHashSet) value.get(15);
    Assert.assertEquals(childSet.label, "child-set");
    Assert.assertEquals(childSet, new HashSet<>(Arrays.asList("x", "y")));
    ChildTreeSet childSortedSet = (ChildTreeSet) value.get(16);
    Assert.assertEquals(childSortedSet.label, "child-sorted-set");
    Assert.assertEquals(childSortedSet, new TreeSet<>(Arrays.asList("a", "bbb", "cc")));
    ChildPriorityQueue childQueue = (ChildPriorityQueue) value.get(17);
    Assert.assertEquals(childQueue.label, "child-queue");
    Assert.assertEquals(new HashSet<>(childQueue), new HashSet<>(Arrays.asList("p", "q")));
    ChildHashMap childMap = (ChildHashMap) value.get(18);
    Assert.assertEquals(childMap.label, "child-map");
    Assert.assertEquals(childMap, singletonMap("k", 9));
    ChildTreeMap childSortedMap = (ChildTreeMap) value.get(19);
    Assert.assertEquals(childSortedMap.label, "child-sorted-map");
    Assert.assertEquals(childSortedMap, singletonMap("s", 11));
  }

  private static Map<String, Integer> immutableMapExpected() {
    HashMap<String, Integer> expected = new HashMap<>();
    expected.put("m-a", 1);
    expected.put("m-b", 2);
    return expected;
  }

  private static Map<String, Integer> sortedMapExpected() {
    TreeMap<String, Integer> expected = new TreeMap<>(new LengthComparator());
    expected.put("bbb", 3);
    expected.put("a", 1);
    expected.put("cc", 2);
    return expected;
  }

  private static void verifySingletonValue(List<?> value) {
    Assert.assertSame(value.get(0), SingletonObject.MODULE$);
    Assert.assertSame(value.get(1), SingletonCollection.MODULE$);
    Assert.assertSame(value.get(2), SingletonMap.MODULE$);
  }

  private static void expectUnsupportedAdd(Collection<Object> collection) {
    try {
      collection.add(new Object());
      Assert.fail("Expected collection add to be unsupported");
    } catch (UnsupportedOperationException expected) {
      // Expected.
    }
  }

  private static void expectUnsupportedPut(Map<Object, Object> map) {
    try {
      map.put("new", "value");
      Assert.fail("Expected map put to be unsupported");
    } catch (UnsupportedOperationException expected) {
      // Expected.
    }
  }

  private static void expectUnsupported(Serializer<?> serializer) {
    try {
      serializer.write(null, null);
      Assert.fail("Expected Android lambda write to fail");
    } catch (UnsupportedOperationException expected) {
      Assert.assertTrue(
          expected.getMessage().contains("Lambda serialization is unsupported on Android"));
    }
    try {
      serializer.read(null);
      Assert.fail("Expected Android lambda read to fail");
    } catch (UnsupportedOperationException expected) {
      Assert.assertTrue(
          expected.getMessage().contains("Lambda serialization is unsupported on Android"));
    }
    try {
      serializer.copy(null, null);
      Assert.fail("Expected Android lambda copy to fail");
    } catch (UnsupportedOperationException expected) {
      Assert.assertTrue(
          expected.getMessage().contains("Lambda serialization is unsupported on Android"));
    }
  }

  public static final class JvmPeer {
    public static void main(String[] args) throws Exception {
      if (args.length != 3) {
        throw new IllegalArgumentException(
            "Expected kind, Android payload path, and JVM payload path");
      }
      if (AndroidSupport.IS_ANDROID) {
        throw new AssertionError("JVM peer must run with " + ANDROID_ENABLED_ENV + "=0");
      }
      RoundTripKind kind = RoundTripKind.valueOf(args[0]);
      Fory jvmReader = newFory(kind);
      Object androidValue =
          deserialize(kind, jvmReader, Files.readAllBytes(new File(args[1]).toPath()));
      verifyValue(kind, androidValue);
      Fory jvmWriter = newFory(kind);
      Files.write(new File(args[2]).toPath(), serialize(kind, jvmWriter, newValue(kind)));
    }
  }

  private enum RoundTripKind {
    STRING_VALUES,
    EXCEPTION_VALUE,
    OBJECT_STREAM_VALUE,
    REPLACE_RESOLVE_VALUE,
    PROXY_VALUE,
    CODEGEN_VALUE,
    META_SHARED_VALUE,
    COLLECTION_VALUE,
    SINGLETON_VALUE
  }

  public enum TestEnum {
    ONE,
    TWO
  }

  public interface GreetingService {
    String greet(String name);

    int id();
  }

  public static final class GreetingHandler implements InvocationHandler, Serializable {
    public String prefix;
    public int id;

    public GreetingHandler() {}

    GreetingHandler(String prefix, int id) {
      this.prefix = prefix;
      this.id = id;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      if ("greet".equals(method.getName())) {
        return prefix + " " + args[0];
      }
      if ("id".equals(method.getName())) {
        return id;
      }
      if ("toString".equals(method.getName())) {
        return "GreetingService(" + id + ")";
      }
      if ("hashCode".equals(method.getName())) {
        return id;
      }
      if ("equals".equals(method.getName())) {
        return proxy == args[0];
      }
      throw new UnsupportedOperationException(method.toString());
    }
  }

  public static final class StringValues implements Serializable {
    public String ascii;
    public String latin;
    public String utf16;
    public StringBuilder builder;
    public StringBuffer buffer;

    public StringValues() {}

    StringValues(String ascii, String latin, String utf16, String builder, String buffer) {
      this.ascii = ascii;
      this.latin = latin;
      this.utf16 = utf16;
      this.builder = new StringBuilder(builder);
      this.buffer = new StringBuffer(buffer);
    }
  }

  public static final class StreamHookValue implements Serializable {
    public String value;
    public transient String hookValue;

    public StreamHookValue() {}

    StreamHookValue(String value) {
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

  public static final class ReplaceValue implements Serializable {
    public String value;

    public ReplaceValue() {}

    ReplaceValue(String value) {
      this.value = value;
    }

    private Object writeReplace() {
      return new ReplacementValue(value);
    }
  }

  public static final class ReplacementValue implements Serializable {
    public String value;

    public ReplacementValue() {}

    ReplacementValue(String value) {
      this.value = value;
    }

    private Object readResolve() {
      return new ReplaceValue(value + "-resolved");
    }
  }

  public static final class NestedValue implements Serializable {
    public String name;
    public int count;

    public NestedValue() {}

    NestedValue(String name, int count) {
      this.name = name;
      this.count = count;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof NestedValue)) {
        return false;
      }
      NestedValue other = (NestedValue) obj;
      return count == other.count && name.equals(other.name);
    }

    @Override
    public int hashCode() {
      return name.hashCode() * 31 + count;
    }
  }

  public static final class CodegenValue implements Serializable {
    public int id;
    public String name;
    public NestedValue nested;

    public CodegenValue() {}

    CodegenValue(int id, String name, NestedValue nested) {
      this.id = id;
      this.name = name;
      this.nested = nested;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof CodegenValue)) {
        return false;
      }
      CodegenValue other = (CodegenValue) obj;
      return id == other.id && name.equals(other.name) && nested.equals(other.nested);
    }

    @Override
    public int hashCode() {
      return (id * 31 + name.hashCode()) * 31 + nested.hashCode();
    }
  }

  public static final class MetaShareValue implements Serializable {
    public int id;
    public String name;
    public List<Integer> values;

    public MetaShareValue() {}

    MetaShareValue(int id, String name, List<Integer> values) {
      this.id = id;
      this.name = name;
      this.values = values;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof MetaShareValue)) {
        return false;
      }
      MetaShareValue other = (MetaShareValue) obj;
      return id == other.id && name.equals(other.name) && values.equals(other.values);
    }

    @Override
    public int hashCode() {
      return (id * 31 + name.hashCode()) * 31 + values.hashCode();
    }
  }

  public static final class LengthComparator implements java.util.Comparator<String>, Serializable {
    @Override
    public int compare(String left, String right) {
      int lengthCompare = Integer.compare(left.length(), right.length());
      return lengthCompare != 0 ? lengthCompare : left.compareTo(right);
    }
  }

  public static final class ChildArrayList extends ArrayList<String> {
    public String label;

    public ChildArrayList() {}

    ChildArrayList(String label, String... values) {
      this.label = label;
      Collections.addAll(this, values);
    }
  }

  public static final class ChildHashSet extends HashSet<String> {
    public String label;

    public ChildHashSet() {}

    ChildHashSet(String label, String... values) {
      this.label = label;
      Collections.addAll(this, values);
    }
  }

  public static final class ChildTreeSet extends TreeSet<String> {
    public String label;

    public ChildTreeSet() {}

    ChildTreeSet(String label, String... values) {
      super(new LengthComparator());
      this.label = label;
      Collections.addAll(this, values);
    }
  }

  public static final class ChildPriorityQueue extends PriorityQueue<String> {
    public String label;

    public ChildPriorityQueue() {}

    ChildPriorityQueue(String label, String... values) {
      this.label = label;
      Collections.addAll(this, values);
    }
  }

  public static final class ChildHashMap extends HashMap<String, Integer> {
    public String label;

    public ChildHashMap() {}

    ChildHashMap(String label, Map<String, Integer> values) {
      this.label = label;
      putAll(values);
    }
  }

  public static final class ChildTreeMap extends TreeMap<String, Integer> {
    public String label;

    public ChildTreeMap() {}

    ChildTreeMap(String label, Map<String, Integer> values) {
      super(new LengthComparator());
      this.label = label;
      putAll(values);
    }
  }

  public static final class SingletonObject implements Serializable {
    public static final SingletonObject MODULE$ = new SingletonObject();

    private SingletonObject() {}
  }

  public static final class SingletonCollection extends AbstractList<String>
      implements Serializable {
    public static final SingletonCollection MODULE$ = new SingletonCollection();

    private SingletonCollection() {}

    @Override
    public String get(int index) {
      throw new IndexOutOfBoundsException(String.valueOf(index));
    }

    @Override
    public int size() {
      return 0;
    }
  }

  public static final class SingletonMap extends AbstractMap<String, String>
      implements Serializable {
    public static final SingletonMap MODULE$ = new SingletonMap();

    private SingletonMap() {}

    @Override
    public Set<Map.Entry<String, String>> entrySet() {
      return Collections.emptySet();
    }
  }
}
