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

package org.apache.fory.integration_tests;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.ForyStruct;
import org.apache.fory.annotation.Int32Type;
import org.apache.fory.annotation.Nullable;
import org.apache.fory.builder.CodecUtils;
import org.apache.fory.builder.Generated.GeneratedStaticCompatibleSerializer;
import org.apache.fory.collection.BFloat16List;
import org.apache.fory.collection.BoolList;
import org.apache.fory.collection.Float16List;
import org.apache.fory.collection.Float32List;
import org.apache.fory.collection.Float64List;
import org.apache.fory.collection.Int16List;
import org.apache.fory.collection.Int32List;
import org.apache.fory.collection.Int64List;
import org.apache.fory.collection.Int8List;
import org.apache.fory.collection.UInt16List;
import org.apache.fory.collection.UInt32List;
import org.apache.fory.collection.UInt64List;
import org.apache.fory.collection.UInt8List;
import org.apache.fory.config.Int32Encoding;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.MetaWriteContext;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.serializer.DeferedLazySerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.StaticGeneratedStructSerializer;
import org.apache.fory.type.BFloat16;
import org.apache.fory.type.BFloat16Array;
import org.apache.fory.type.Float16;
import org.apache.fory.type.Float16Array;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ExampleStaticGeneratedSerializerTest {
  private static final AtomicInteger FORY_ID = new AtomicInteger();

  @DataProvider
  public static Object[][] modes() {
    return new Object[][] {{false, false}, {true, false}, {false, true}, {true, true}};
  }

  @DataProvider
  public static Object[][] xlangModes() {
    return new Object[][] {{false}, {true}};
  }

  @DataProvider
  public static Object[][] runtimeModes() {
    return new Object[][] {
      {false, false, false},
      {true, false, false},
      {false, false, true},
      {true, false, true},
      {false, true, false},
      {true, true, false},
      {false, true, true},
      {true, true, true}
    };
  }

  @Test(dataProvider = "modes")
  public void testStaticClass(boolean xlang, boolean compatible) throws Exception {
    ExampleMessage value = newStruct(ExampleMessage.class);
    ThreadSafeFory fory = threadSafeFory(ExampleMessage.class, xlang, compatible, false);
    assertStaticSerializer(fory, ExampleMessage.class);

    ExampleMessage roundTrip = deserialize(fory, serialize(fory, value), ExampleMessage.class);
    assertStructEquals(value, roundTrip, ExampleMessage.class);
  }

  @Test(dataProvider = "modes")
  public void testStaticRecord(boolean xlang, boolean compatible) throws Exception {
    ExampleRecordMessage value = newRecord(ExampleRecordMessage.class);
    ThreadSafeFory fory = threadSafeFory(ExampleRecordMessage.class, xlang, compatible, false);
    assertStaticSerializer(fory, ExampleRecordMessage.class);

    ExampleRecordMessage roundTrip =
        deserialize(fory, serialize(fory, value), ExampleRecordMessage.class);
    assertRecordEquals(value, roundTrip);
  }

  @Test(dataProvider = "xlangModes")
  public void testCompatibleClassEvolution(boolean xlang) throws Exception {
    ExampleMessage value = newStruct(ExampleMessage.class);
    ThreadSafeFory writer = threadSafeFory(ExampleMessage.class, xlang, true, false);
    byte[] bytes = serialize(writer, value);

    ThreadSafeFory emptyReader = threadSafeFory(EmptyMessage.class, xlang, true, false);
    Assert.assertNotNull(deserialize(emptyReader, bytes, EmptyMessage.class));

    ThreadSafeFory reader = threadSafeFory(InconsistentMessage.class, xlang, true, false);
    InconsistentMessage result = deserialize(reader, bytes, InconsistentMessage.class);
    Assert.assertEquals(result.fixedI32Value, value.fixedI32Value);
    Assert.assertEquals(result.stringValue, value.stringValue);
    Assert.assertEquals(result.added, "default");
  }

  @Test(dataProvider = "runtimeModes")
  public void testRuntimeCompatibleEmptyReaderSkipsExampleMessage(
      boolean xlang, boolean writerCodegen, boolean readerCodegen) throws Exception {
    RuntimeExampleMessage value = newStruct(RuntimeExampleMessage.class);
    Fory writer = fory(RuntimeExampleMessage.class, xlang, true, writerCodegen);
    Fory emptyReader = fory(RuntimeEmptyMessage.class, xlang, true, readerCodegen);

    writer.setMetaWriteContext(new MetaWriteContext());
    byte[] bytes = writer.serialize(value);
    assertNotStaticSerializer(writer, RuntimeExampleMessage.class);

    emptyReader.setMetaReadContext(new MetaReadContext());
    Assert.assertNotNull(emptyReader.deserialize(bytes, RuntimeEmptyMessage.class));
    assertNotStaticSerializer(emptyReader, RuntimeEmptyMessage.class);
  }

  @Test(dataProvider = "xlangModes")
  public void testCompatibleRecordEvolution(boolean xlang) throws Exception {
    ExampleRecordMessage value = newRecord(ExampleRecordMessage.class);
    ThreadSafeFory writer = threadSafeFory(ExampleRecordMessage.class, xlang, true, false);
    byte[] bytes = serialize(writer, value);

    ThreadSafeFory emptyReader = threadSafeFory(EmptyRecordMessage.class, xlang, true, false);
    Assert.assertNotNull(deserialize(emptyReader, bytes, EmptyRecordMessage.class));

    ThreadSafeFory reader = threadSafeFory(InconsistentRecordMessage.class, xlang, true, false);
    InconsistentRecordMessage result = deserialize(reader, bytes, InconsistentRecordMessage.class);
    Assert.assertEquals(result.fixedI32Value(), value.fixedI32Value());
    Assert.assertEquals(result.stringValue(), value.stringValue());
    Assert.assertNull(result.added());
  }

  @Test(dataProvider = "xlangModes")
  public void testStaticCompatibleBuilder(boolean xlang) throws Exception {
    ExampleMessage value = newStruct(ExampleMessage.class);
    Fory writer = fory(ExampleMessage.class, xlang, true, true);
    Fory reader = fory(InconsistentMessage.class, xlang, true, true);
    TypeDef remoteTypeDef = TypeDef.buildTypeDef(writer.getTypeResolver(), ExampleMessage.class);
    Assert.assertNotEquals(
        remoteTypeDef.getId(),
        TypeDef.buildTypeDef(reader.getTypeResolver(), InconsistentMessage.class).getId());

    Class<? extends Serializer> compatibleSerializerClass =
        CodecUtils.loadOrGenStaticCompatibleCodecClass(
            reader.getTypeResolver(), InconsistentMessage.class, remoteTypeDef);
    Assert.assertTrue(
        GeneratedStaticCompatibleSerializer.class.isAssignableFrom(compatibleSerializerClass));
    writer.setMetaWriteContext(new MetaWriteContext());
    byte[] bytes = writer.serialize(value);

    try (MockedStatic<CodecUtils> codecUtils =
        Mockito.mockStatic(CodecUtils.class, Mockito.CALLS_REAL_METHODS)) {
      codecUtils
          .when(
              () ->
                  CodecUtils.loadOrGenCompatibleCodecClass(
                      same(reader.getTypeResolver()),
                      eq(InconsistentMessage.class),
                      any(TypeDef.class)))
          .thenReturn(compatibleSerializerClass);
      reader.setMetaReadContext(new MetaReadContext());
      InconsistentMessage result = (InconsistentMessage) reader.deserialize(bytes);
      codecUtils.verify(
          () ->
              CodecUtils.loadOrGenCompatibleCodecClass(
                  same(reader.getTypeResolver()),
                  eq(InconsistentMessage.class),
                  any(TypeDef.class)),
          atLeastOnce());
      Assert.assertEquals(result.fixedI32Value, value.fixedI32Value);
      Assert.assertEquals(result.stringValue, value.stringValue);
      Assert.assertEquals(result.added, "default");
    }
  }

  @Test
  public void testMetaShareStaticSkipShapes() throws Exception {
    StaticGeneratedMetaShareWriter value = new StaticGeneratedMetaShareWriter();
    value.date = LocalDate.of(2026, 5, 15);
    value.flags = Arrays.asList(true, false, true);
    value.after = "after";
    Fory writer = foryWithNativeId(StaticGeneratedMetaShareWriter.class, 1700);
    Fory reader = foryWithNativeId(StaticGeneratedMetaShareReader.class, 1700);
    assertStaticSerializer(writer, StaticGeneratedMetaShareWriter.class);
    assertStaticSerializer(reader, StaticGeneratedMetaShareReader.class);

    writer.setMetaWriteContext(new MetaWriteContext());
    byte[] bytes = writer.serialize(value);
    reader.setMetaReadContext(new MetaReadContext());
    StaticGeneratedMetaShareReader result =
        (StaticGeneratedMetaShareReader) reader.deserialize(bytes);
    Assert.assertEquals(result.after, "after");
  }

  @Test(dataProvider = "modes")
  public void testCompatibleFieldNames(boolean annotated, boolean codegen) throws Exception {
    Class<? extends ExtraFieldNames> remoteType =
        annotated ? ExtraFieldNames.class : RuntimeExtraFieldNames.class;
    Class<? extends FieldNames> localType = annotated ? FieldNames.class : RuntimeFieldNames.class;
    Fory writer = Fory.builder().withXlang(true).withCompatible(true).withCodegen(codegen).build();
    Fory reader = Fory.builder().withXlang(true).withCompatible(true).withCodegen(codegen).build();
    writer.register(remoteType, 9201);
    reader.register(localType, 9201);
    ExtraFieldNames value = remoteType.getDeclaredConstructor().newInstance();
    value.fooURL = 7;
    value._URL_Value_ = 11;
    value.already__snake = 13;
    value.extra = 17;
    FieldNames result = (FieldNames) reader.deserialize(writer.serialize(value));
    Assert.assertEquals(result.fooURL, 7);
    Assert.assertEquals(result._URL_Value_, 11);
    Assert.assertEquals(result.already__snake, 13);
    if (annotated && !codegen) {
      assertStaticSerializer(reader, localType);
      assertStaticSerializer(writer, remoteType);
    } else {
      assertNotStaticSerializer(reader, localType);
      assertNotStaticSerializer(writer, remoteType);
    }
  }

  @ForyStruct
  public static class FieldNames {
    public int fooURL;
    public int _URL_Value_;
    public int already__snake;
  }

  @ForyStruct
  public static class ExtraFieldNames {
    public int fooURL;
    public int _URL_Value_;
    public int already__snake;
    public int extra;
  }

  public static class RuntimeFieldNames extends FieldNames {}

  public static class RuntimeExtraFieldNames extends ExtraFieldNames {}

  @ForyStruct
  public static class EmptyMessage {
    public EmptyMessage() {}
  }

  @ForyStruct
  public static class StaticGeneratedMetaShareWriter {
    @ForyField(id = 1)
    public LocalDate date;

    @ForyField(id = 2)
    public List<Boolean> flags;

    @ForyField(id = 3)
    public String after;

    public StaticGeneratedMetaShareWriter() {}
  }

  @ForyStruct
  public static class StaticGeneratedMetaShareReader {
    @ForyField(id = 3)
    public String after;

    public StaticGeneratedMetaShareReader() {}
  }

  public static class RuntimeEmptyMessage {
    public RuntimeEmptyMessage() {}
  }

  public static class RuntimeExampleMessage extends ExampleMessage {
    public RuntimeExampleMessage() {}
  }

  @ForyStruct
  public static class InconsistentMessage {
    @ForyField(id = 4)
    public @Int32Type(encoding = Int32Encoding.FIXED) int fixedI32Value;

    @ForyField(id = 20)
    public String stringValue;

    @Nullable
    @ForyField(id = 900)
    public String added = "default";

    public InconsistentMessage() {}
  }

  @ForyStruct
  public record EmptyRecordMessage() {}

  @ForyStruct
  public record InconsistentRecordMessage(
      @ForyField(id = 4) @Int32Type(encoding = Int32Encoding.FIXED) int fixedI32Value,
      @ForyField(id = 20) String stringValue,
      @Nullable @ForyField(id = 900) String added) {}

  private static ThreadSafeFory threadSafeFory(
      Class<?> type, boolean xlang, boolean compatible, boolean codegen) {
    ThreadSafeFory fory =
        Fory.builder()
            .withName("latest-static-" + FORY_ID.incrementAndGet())
            .withXlang(xlang)
            .withCodegen(codegen)
            .withMetaShare(compatible)
            .withScopedMetaShare(false)
            .withCompatible(compatible)
            .requireClassRegistration(false)
            .buildThreadSafeForyPool(1);
    registerType(fory, type, xlang);
    return fory;
  }

  private static Fory fory(Class<?> type, boolean xlang, boolean compatible, boolean codegen) {
    Fory fory = newFory(xlang, compatible, codegen);
    registerType(fory, type, xlang);
    return fory;
  }

  private static Fory foryWithNativeId(Class<?> type, int nativeId) {
    Fory fory = newFory(false, true, false);
    register(fory, type, false, nativeId, type.getSimpleName());
    return fory;
  }

  private static Fory newFory(boolean xlang, boolean compatible, boolean codegen) {
    return Fory.builder()
        .withName("latest-static-" + FORY_ID.incrementAndGet())
        .withXlang(xlang)
        .withCodegen(codegen)
        .withMetaShare(compatible)
        .withScopedMetaShare(false)
        .withCompatible(compatible)
        .requireClassRegistration(false)
        .build();
  }

  private static void registerType(org.apache.fory.BaseFory fory, Class<?> type, boolean xlang) {
    if (type == ExampleRecordMessage.class
        || type == EmptyRecordMessage.class
        || type == InconsistentRecordMessage.class) {
      register(fory, type, xlang, 1510, "ExampleRecordMessage");
      register(fory, ExampleRecordMessage.Leaf.class, xlang, 1512, "Leaf");
      register(fory, ExampleRecordMessage.State.class, xlang, 1513, "State");
    } else {
      register(fory, type, xlang, 1500, "ExampleMessage");
      register(fory, ExampleMessage.Leaf.class, xlang, 1502, "Leaf");
      register(fory, ExampleMessage.State.class, xlang, 1503, "State");
    }
  }

  private static void register(
      org.apache.fory.BaseFory fory, Class<?> type, boolean xlang, int nativeId, String typeName) {
    if (xlang) {
      fory.register(type, "example", typeName);
    } else {
      fory.register(type, nativeId);
    }
  }

  private static void assertStaticSerializer(ThreadSafeFory fory, Class<?> type) {
    fory.execute(
        runtime -> {
          assertStaticSerializer(runtime, type);
          return null;
        });
  }

  private static void assertStaticSerializer(Fory fory, Class<?> type) {
    Serializer<?> serializer = fory.getTypeResolver().getTypeInfo(type).getSerializer();
    if (serializer instanceof DeferedLazySerializer) {
      serializer = ((DeferedLazySerializer) serializer).resolveSerializer();
    }
    Assert.assertTrue(
        serializer instanceof StaticGeneratedStructSerializer, serializer.getClass().getName());
  }

  private static void assertNotStaticSerializer(Fory fory, Class<?> type) {
    Serializer<?> serializer = fory.getTypeResolver().getTypeInfo(type).getSerializer();
    if (serializer instanceof DeferedLazySerializer) {
      serializer = ((DeferedLazySerializer) serializer).resolveSerializer();
    }
    Assert.assertFalse(
        serializer instanceof StaticGeneratedStructSerializer, serializer.getClass().getName());
  }

  private static byte[] serialize(ThreadSafeFory fory, Object value) {
    return fory.execute(
        f -> {
          f.setMetaWriteContext(new MetaWriteContext());
          return f.serialize(value);
        });
  }

  private static <T> T deserialize(ThreadSafeFory fory, byte[] bytes, Class<T> type) {
    return fory.execute(
        f -> {
          f.setMetaReadContext(new MetaReadContext());
          return f.deserialize(bytes, type);
        });
  }

  private static <T> T newStruct(Class<T> type) throws Exception {
    T value = type.getConstructor().newInstance();
    for (Field field : type.getFields()) {
      if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
        field.set(value, valueFor(field.getGenericType(), field.getType(), field.getName()));
      }
    }
    return value;
  }

  private static <T> T newRecord(Class<T> type) throws Exception {
    RecordComponent[] components = type.getRecordComponents();
    Object[] args = new Object[components.length];
    Class<?>[] parameterTypes = new Class<?>[components.length];
    for (int i = 0; i < components.length; i++) {
      args[i] =
          valueFor(
              components[i].getGenericType(), components[i].getType(), components[i].getName());
      parameterTypes[i] = components[i].getType();
    }
    return type.getDeclaredConstructor(parameterTypes).newInstance(args);
  }

  private static Object valueFor(Type genericType, Class<?> type, String name) throws Exception {
    if (type == boolean.class || type == Boolean.class) {
      return true;
    } else if (type == byte.class || type == Byte.class) {
      return (byte) 7;
    } else if (type == short.class || type == Short.class) {
      return (short) 8;
    } else if (type == int.class || type == Integer.class) {
      return 42;
    } else if (type == long.class || type == Long.class) {
      return 43L;
    } else if (type == float.class || type == Float.class) {
      return 44.5f;
    } else if (type == double.class || type == Double.class) {
      return 45.5d;
    } else if (type == String.class) {
      return name + "-value";
    } else if (type == byte[].class) {
      return new byte[] {1, 2, 3};
    } else if (type == boolean[].class) {
      return new boolean[] {true, false};
    } else if (type == short[].class) {
      return new short[] {1, 2};
    } else if (type == int[].class) {
      return new int[] {1, 2};
    } else if (type == long[].class) {
      return new long[] {1L, 2L};
    } else if (type == float[].class) {
      return new float[] {1.5f, 2.5f};
    } else if (type == double[].class) {
      return new double[] {1.5d, 2.5d};
    } else if (type == LocalDate.class) {
      return LocalDate.of(2026, 5, 12);
    } else if (type == Instant.class) {
      return Instant.parse("2026-05-12T00:00:00Z");
    } else if (type == Duration.class) {
      return Duration.ofSeconds(123);
    } else if (type == BigDecimal.class) {
      return new BigDecimal("123.45");
    } else if (type == Float16.class) {
      return Float16.valueOf(1.5f);
    } else if (type == BFloat16.class) {
      return BFloat16.valueOf(2.5f);
    } else if (type == Float16Array.class) {
      return Float16Array.of(1.5f, 2.5f);
    } else if (type == BFloat16Array.class) {
      return BFloat16Array.of(1.5f, 2.5f);
    } else if (type == BoolList.class) {
      return new BoolList(new boolean[] {true, false});
    } else if (type == Int8List.class) {
      return new Int8List(new byte[] {1, 2});
    } else if (type == Int16List.class) {
      return new Int16List(new short[] {1, 2});
    } else if (type == Int32List.class) {
      return new Int32List(new int[] {1, 2});
    } else if (type == Int64List.class) {
      return new Int64List(new long[] {1L, 2L});
    } else if (type == UInt8List.class) {
      return new UInt8List(new byte[] {1, 2});
    } else if (type == UInt16List.class) {
      return new UInt16List(new short[] {1, 2});
    } else if (type == UInt32List.class) {
      return new UInt32List(new int[] {1, 2});
    } else if (type == UInt64List.class) {
      return new UInt64List(new long[] {1L, 2L});
    } else if (type == Float16List.class) {
      return new Float16List(new short[] {Float16.toBits(1.5f), Float16.toBits(2.5f)});
    } else if (type == BFloat16List.class) {
      return new BFloat16List(new short[] {BFloat16.toBits(1.5f), BFloat16.toBits(2.5f)});
    } else if (type == Float32List.class) {
      return new Float32List(new float[] {1.5f, 2.5f});
    } else if (type == Float64List.class) {
      return new Float64List(new double[] {1.5d, 2.5d});
    } else if (type == ExampleMessage.Leaf.class || type == ExampleRecordMessage.Leaf.class) {
      return type.isRecord() ? newRecord(type) : newStruct(type);
    } else if (type.isEnum()) {
      return type.getEnumConstants()[1];
    } else if (List.class.isAssignableFrom(type)) {
      ArrayList<Object> list = new ArrayList<>();
      list.add(valueFor(typeArgument(genericType, 0), typeArgumentClass(genericType, 0), name));
      return list;
    } else if (Map.class.isAssignableFrom(type)) {
      LinkedHashMap<Object, Object> map = new LinkedHashMap<>();
      map.put(
          valueFor(typeArgument(genericType, 0), typeArgumentClass(genericType, 0), name + "Key"),
          valueFor(
              typeArgument(genericType, 1), typeArgumentClass(genericType, 1), name + "Value"));
      return map;
    }
    throw new IllegalArgumentException(type.getName());
  }

  private static Type typeArgument(Type type, int index) {
    return ((ParameterizedType) type).getActualTypeArguments()[index];
  }

  private static Class<?> typeArgumentClass(Type type, int index) {
    Type argument = typeArgument(type, index);
    if (argument instanceof Class) {
      return (Class<?>) argument;
    }
    if (argument instanceof ParameterizedType) {
      return (Class<?>) ((ParameterizedType) argument).getRawType();
    }
    return Object.class;
  }

  private static void assertStructEquals(Object expected, Object actual, Class<?> type)
      throws Exception {
    Assert.assertSame(actual.getClass(), type);
    for (Field field : type.getFields()) {
      if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
        assertDeepEquals(field.get(expected), field.get(actual), field.getName());
      }
    }
  }

  private static void assertRecordEquals(Object expected, Object actual) throws Exception {
    Assert.assertSame(actual.getClass(), expected.getClass());
    for (RecordComponent component : expected.getClass().getRecordComponents()) {
      assertDeepEquals(
          component.getAccessor().invoke(expected),
          component.getAccessor().invoke(actual),
          component.getName());
    }
  }

  private static void assertDeepEquals(Object expected, Object actual, String name) {
    if (expected != null && expected.getClass().isArray()) {
      Assert.assertTrue(actual != null && actual.getClass().isArray(), name);
      Assert.assertEquals(Array.getLength(actual), Array.getLength(expected), name);
      for (int i = 0; i < Array.getLength(expected); i++) {
        assertDeepEquals(Array.get(expected, i), Array.get(actual, i), name + "[" + i + "]");
      }
    } else if (expected instanceof List) {
      List<?> expectedList = (List<?>) expected;
      List<?> actualList = (List<?>) actual;
      Assert.assertEquals(actualList.size(), expectedList.size(), name);
      for (int i = 0; i < expectedList.size(); i++) {
        assertDeepEquals(expectedList.get(i), actualList.get(i), name + "[" + i + "]");
      }
    } else if (expected instanceof Map) {
      Map<?, ?> expectedMap = (Map<?, ?>) expected;
      Map<?, ?> actualMap = (Map<?, ?>) actual;
      Assert.assertEquals(actualMap.size(), expectedMap.size(), name);
      for (Map.Entry<?, ?> entry : expectedMap.entrySet()) {
        Assert.assertTrue(actualMap.containsKey(entry.getKey()), name);
        assertDeepEquals(entry.getValue(), actualMap.get(entry.getKey()), name);
      }
    } else if (isExampleStruct(expected)) {
      try {
        assertStructEquals(expected, actual, expected.getClass());
      } catch (Exception e) {
        throw new AssertionError(name, e);
      }
    } else if (isExampleRecord(expected)) {
      try {
        assertRecordEquals(expected, actual);
      } catch (Exception e) {
        throw new AssertionError(name, e);
      }
    } else {
      Assert.assertEquals(actual, expected, name);
    }
  }

  private static boolean isExampleStruct(Object value) {
    return value instanceof ExampleMessage.Leaf;
  }

  private static boolean isExampleRecord(Object value) {
    return value instanceof ExampleRecordMessage.Leaf;
  }
}
