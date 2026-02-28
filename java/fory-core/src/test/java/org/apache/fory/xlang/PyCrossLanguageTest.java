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

package org.apache.fory.xlang;

import static org.testng.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.ArraySerializersTest;
import org.apache.fory.serializer.BufferObject;
import org.apache.fory.serializer.EnumSerializerTest;
import org.apache.fory.serializer.ObjectSerializer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.UnknownClass.UnknownStruct;
import org.apache.fory.test.TestUtils;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.DescriptorGrouper;
import org.apache.fory.util.DateTimeUtils;
import org.apache.fory.util.MurmurHash3;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** Tests in this class need fory python installed. */
@Test
public class PyCrossLanguageTest extends ForyTestBase {
  private static final Logger LOG = LoggerFactory.getLogger(PyCrossLanguageTest.class);
  private static final String PYTHON_MODULE = "pyfory.tests.test_cross_language";
  private static final String PYTHON_EXECUTABLE = "python";

  @BeforeClass
  public void ensurePeerReady() {
    String enabled = System.getenv("FORY_PYTHON_JAVA_CI");
    if (!"1".equals(enabled)) {
      throw new SkipException("Skipping CrossLanguageTest: FORY_PYTHON_JAVA_CI not set to 1");
    }
    TestUtils.verifyPyforyInstalled();
  }

  /**
   * Execute an external command.
   *
   * @return Whether the command succeeded.
   */
  private boolean executeCommand(List<String> command) {
    return TestUtils.executeCommand(
        command, 60, ImmutableMap.of("ENABLE_CROSS_LANGUAGE_TESTS", "true"));
  }

  @Data
  public static class A {
    public int f1;
    public Map<String, String> f2;

    public static A create() {
      A a = new A();
      a.f1 = 1;
      a.f2 = new HashMap<>();
      a.f2.put("pid", "12345");
      a.f2.put("ip", "0.0.0.0");
      a.f2.put("k1", "v1");
      return a;
    }
  }

  @Test
  public void testBuffer() throws IOException {
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    buffer.writeBoolean(true);
    buffer.writeByte(Byte.MAX_VALUE);
    buffer.writeInt16(Short.MAX_VALUE);
    buffer.writeInt32(Integer.MAX_VALUE);
    buffer.writeInt64(Long.MAX_VALUE);
    buffer.writeFloat32(-1.1f);
    buffer.writeFloat64(-1.1);
    buffer.writeVarUint32(100);
    byte[] bytes = {'a', 'b'};
    buffer.writeInt32(bytes.length);
    buffer.writeBytes(bytes);
    Path dataFile = Files.createTempFile("test_buffer", "data");
    Files.write(dataFile, buffer.getBytes(0, buffer.writerIndex()));
    ImmutableList<String> command =
        ImmutableList.of(
            PYTHON_EXECUTABLE,
            "-m",
            PYTHON_MODULE,
            "test_buffer",
            dataFile.toAbsolutePath().toString());
    Assert.assertTrue(executeCommand(command));
    buffer = MemoryUtils.wrap(Files.readAllBytes(dataFile));
    Assert.assertTrue(buffer.readBoolean());
    Assert.assertEquals(buffer.readByte(), Byte.MAX_VALUE);
    Assert.assertEquals(buffer.readInt16(), Short.MAX_VALUE);
    Assert.assertEquals(buffer.readInt32(), Integer.MAX_VALUE);
    Assert.assertEquals(buffer.readInt64(), Long.MAX_VALUE);
    Assert.assertEquals(buffer.readFloat32(), -1.1f, 0.0001);
    Assert.assertEquals(buffer.readFloat64(), -1.1, 0.0001);
    Assert.assertEquals(buffer.readVarUint32(), 100);
    Assert.assertTrue(Arrays.equals(buffer.readBytes(buffer.readInt32()), bytes));
  }

  @Test
  public void testMurmurHash3() throws IOException {
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    byte[] hash1 = Hashing.murmur3_128(47).hashBytes(new byte[] {1, 2, 8}).asBytes();
    buffer.writeBytes(hash1);
    byte[] hash2 =
        Hashing.murmur3_128(47)
            .hashBytes("01234567890123456789".getBytes(StandardCharsets.UTF_8))
            .asBytes();
    buffer.writeBytes(hash2);
    Path dataFile = Files.createTempFile("test_murmurhash3", "data");
    Files.write(dataFile, buffer.getBytes(0, buffer.writerIndex()));
    ImmutableList<String> command =
        ImmutableList.of(
            PYTHON_EXECUTABLE,
            "-m",
            PYTHON_MODULE,
            "test_murmurhash3",
            dataFile.toAbsolutePath().toString());
    Assert.assertTrue(executeCommand(command));
    long[] longs = MurmurHash3.murmurhash3_x64_128(new byte[] {1, 2, 8}, 0, 3, 47);
    buffer.writerIndex(0);
    buffer.writeInt64(longs[0]);
    buffer.writeInt64(longs[1]);
    Files.write(
        dataFile, buffer.getBytes(0, buffer.writerIndex()), StandardOpenOption.TRUNCATE_EXISTING);
    Assert.assertTrue(executeCommand(command));
  }

  /** Keep this in sync with `foo_schema` in test_cross_language.py */
  @Data
  public static class Foo {
    public int f1;
    public String f2;
    public List<String> f3;
    public Map<String, Integer> f4;
    public Bar f5;

    public static Foo create() {
      Foo foo = new Foo();
      foo.f1 = 1;
      foo.f2 = "str";
      foo.f3 = Arrays.asList("str1", null, "str2");
      foo.f4 =
          new HashMap<String, Integer>() {
            {
              put("k1", 1);
              put("k2", 2);
              put("k3", 3);
              put("k4", 4);
              put("k5", 5);
              put("k6", 6);
            }
          };
      foo.f5 = Bar.create();
      return foo;
    }
  }

  /** Keep this in sync with `bar_schema` in test_cross_language.py */
  @Data
  public static class Bar {
    public int f1;
    public String f2;

    public static Bar create() {
      Bar bar = new Bar();
      bar.f1 = 1;
      bar.f2 = "str";
      return bar;
    }
  }

  @Test
  public void testCrossLanguageSerializer() throws Exception {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    fory.serialize(buffer, true);
    fory.serialize(buffer, false);
    fory.serialize(buffer, -1);
    fory.serialize(buffer, Byte.MAX_VALUE);
    fory.serialize(buffer, Byte.MIN_VALUE);
    fory.serialize(buffer, Short.MAX_VALUE);
    fory.serialize(buffer, Short.MIN_VALUE);
    fory.serialize(buffer, Integer.MAX_VALUE);
    fory.serialize(buffer, Integer.MIN_VALUE);
    fory.serialize(buffer, Long.MAX_VALUE);
    fory.serialize(buffer, Long.MIN_VALUE);
    fory.serialize(buffer, -1.f);
    fory.serialize(buffer, -1.d);
    fory.serialize(buffer, "str");
    LocalDate day = LocalDate.of(2021, 11, 23);
    fory.serialize(buffer, day);
    Instant instant = Instant.ofEpochSecond(100);
    fory.serialize(buffer, instant);
    List<Object> list = Arrays.asList("a", 1, -1.0, instant, day);
    fory.serialize(buffer, list);
    Map<Object, Object> map = new HashMap<>();
    for (int i = 0; i < list.size(); i++) {
      map.put("k" + i, list.get(i));
      map.put(list.get(i), list.get(i));
    }
    fory.serialize(buffer, map);
    Set<Object> set = new HashSet<>(list);
    fory.serialize(buffer, set);

    // test primitive arrays
    fory.serialize(buffer, new boolean[] {true, false});
    fory.serialize(buffer, new short[] {1, Short.MAX_VALUE});
    fory.serialize(buffer, new int[] {1, Integer.MAX_VALUE});
    fory.serialize(buffer, new long[] {1, Long.MAX_VALUE});
    fory.serialize(buffer, new float[] {1.f, 2.f});
    fory.serialize(buffer, new double[] {1.0, 2.0});

    BiConsumer<MemoryBuffer, Boolean> function =
        (MemoryBuffer buf, Boolean useToString) -> {
          assertStringEquals(fory.deserialize(buf), true, useToString);
          assertStringEquals(fory.deserialize(buf), false, useToString);
          assertStringEquals(fory.deserialize(buf), -1, useToString);
          assertStringEquals(fory.deserialize(buf), Byte.MAX_VALUE, useToString);
          assertStringEquals(fory.deserialize(buf), Byte.MIN_VALUE, useToString);
          assertStringEquals(fory.deserialize(buf), Short.MAX_VALUE, useToString);
          assertStringEquals(fory.deserialize(buf), Short.MIN_VALUE, useToString);
          assertStringEquals(fory.deserialize(buf), Integer.MAX_VALUE, useToString);
          assertStringEquals(fory.deserialize(buf), Integer.MIN_VALUE, useToString);
          assertStringEquals(fory.deserialize(buf), Long.MAX_VALUE, useToString);
          assertStringEquals(fory.deserialize(buf), Long.MIN_VALUE, useToString);
          assertStringEquals(fory.deserialize(buf), -1.f, useToString);
          assertStringEquals(fory.deserialize(buf), -1.d, useToString);
          assertStringEquals(fory.deserialize(buf), "str", useToString);
          assertStringEquals(fory.deserialize(buf), day, useToString);
          assertStringEquals(fory.deserialize(buf), instant, useToString);
          assertStringEquals(fory.deserialize(buf), list, useToString);
          assertStringEquals(fory.deserialize(buf), map, useToString);
          assertStringEquals(fory.deserialize(buf), set, useToString);
          assertStringEquals(fory.deserialize(buf), new boolean[] {true, false}, false);
          assertStringEquals(fory.deserialize(buf), new short[] {1, Short.MAX_VALUE}, false);
          assertStringEquals(fory.deserialize(buf), new int[] {1, Integer.MAX_VALUE}, false);
          assertStringEquals(fory.deserialize(buf), new long[] {1, Long.MAX_VALUE}, false);
          assertStringEquals(fory.deserialize(buf), new float[] {1.f, 2.f}, false);
          assertStringEquals(fory.deserialize(buf), new double[] {1.0, 2.0}, false);
        };
    function.accept(buffer, false);

    Path dataFile = Files.createTempFile("test_cross_language_serializer", "data");
    // Files.deleteIfExists(Paths.get("test_cross_language_serializer.data"));
    // Path dataFile = Files.createFile(Paths.get("test_cross_language_serializer.data"));
    Files.write(dataFile, buffer.getBytes(0, buffer.writerIndex()));
    ImmutableList<String> command =
        ImmutableList.of(
            PYTHON_EXECUTABLE,
            "-m",
            PYTHON_MODULE,
            "test_cross_language_serializer",
            dataFile.toAbsolutePath().toString());
    Assert.assertTrue(executeCommand(command));
    MemoryBuffer buffer2 = MemoryUtils.wrap(Files.readAllBytes(dataFile));
    function.accept(buffer2, true);
  }

  @Test
  public void testCrossLanguagePreserveTypes() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    Assert.assertEquals(new String[] {"str", "str"}, serDeTyped(fory, new String[] {"str", "str"}));
    Assert.assertEquals(new Object[] {"str", 1}, serDeTyped(fory, new Object[] {"str", 1}));
    Assert.assertTrue(
        Arrays.deepEquals(
            new Integer[][] {{1, 2}, {1, 2}}, serDeTyped(fory, new Integer[][] {{1, 2}, {1, 2}})));

    Assert.assertEquals(Arrays.asList(1, 2), xserDe(fory, Arrays.asList(1, 2)));
    List<String> arrayList = Arrays.asList("str", "str");
    Assert.assertEquals(arrayList, xserDe(fory, arrayList));
    Assert.assertEquals(new LinkedList<>(arrayList), xserDe(fory, new LinkedList<>(arrayList)));
    Assert.assertEquals(new HashSet<>(arrayList), xserDe(fory, new HashSet<>(arrayList)));
    TreeSet<String> treeSet = new TreeSet<>(Comparator.naturalOrder());
    treeSet.add("str1");
    treeSet.add("str2");
    Assert.assertEquals(treeSet, xserDe(fory, treeSet));

    HashMap<String, Integer> hashMap = new HashMap<>();
    hashMap.put("k1", 1);
    hashMap.put("k2", 2);
    Assert.assertEquals(hashMap, xserDe(fory, hashMap));
    Assert.assertEquals(new LinkedHashMap<>(hashMap), xserDe(fory, new LinkedHashMap<>(hashMap)));
    Assert.assertEquals(Collections.EMPTY_LIST, xserDe(fory, Collections.EMPTY_LIST));
    Assert.assertEquals(Collections.EMPTY_SET, xserDe(fory, Collections.EMPTY_SET));
    Assert.assertEquals(Collections.EMPTY_MAP, xserDe(fory, Collections.EMPTY_MAP));
    Assert.assertEquals(
        Collections.singletonList("str"), xserDe(fory, Collections.singletonList("str")));
    Assert.assertEquals(Collections.singleton("str"), xserDe(fory, Collections.singleton("str")));
    Assert.assertEquals(
        Collections.singletonMap("k", 1), xserDe(fory, Collections.singletonMap("k", 1)));
  }

  @SuppressWarnings("unchecked")
  private void assertStringEquals(Object actual, Object expected, boolean useToString) {
    if (useToString) {
      if (expected instanceof Map) {
        Map actualMap =
            (Map)
                ((Map) actual)
                    .entrySet().stream()
                        .collect(
                            Collectors.toMap(
                                (Map.Entry e) -> e.getKey().toString(),
                                (Map.Entry e) -> e.getValue().toString()));
        Map expectedMap =
            (Map)
                ((Map) expected)
                    .entrySet().stream()
                        .collect(
                            Collectors.toMap(
                                (Map.Entry e) -> e.getKey().toString(),
                                (Map.Entry e) -> e.getValue().toString()));
        Assert.assertEquals(actualMap, expectedMap);
      } else if (expected instanceof Set) {
        Object actualSet =
            ((Set) actual).stream().map(Object::toString).collect(Collectors.toSet());
        Object expectedSet =
            ((Set) expected).stream().map(Object::toString).collect(Collectors.toSet());
        Assert.assertEquals(actualSet, expectedSet);
      } else {
        Assert.assertEquals(actual.toString(), expected.toString());
      }
    } else {
      Assert.assertEquals(actual, expected);
    }
  }

  private Object xserDe(Fory fory, Object obj) {
    byte[] bytes = fory.serialize(obj);
    return fory.deserialize(bytes);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testCrossLanguageReference() throws Exception {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    List<Object> list = new ArrayList<>();
    Map<Object, Object> map = new HashMap<>();
    list.add(list);
    list.add(map);
    map.put("k1", map);
    map.put("k2", list);
    MemoryBuffer buffer = MemoryUtils.buffer(32);
    fory.serialize(buffer, list);

    Consumer<MemoryBuffer> function =
        (MemoryBuffer buf) -> {
          List<Object> newList = (List<Object>) fory.deserialize(buf);
          Assert.assertNotNull(newList);
          Assert.assertSame(newList, newList.get(0));
          Map<Object, Object> newMap = (Map<Object, Object>) newList.get(1);
          Assert.assertSame(newMap.get("k1"), newMap);
          Assert.assertSame(newMap.get("k2"), newList);
        };

    Path dataFile = Files.createTempFile("test_cross_language_reference", "data");
    // Files.deleteIfExists(Paths.get("test_cross_language_reference.data"));
    // Path dataFile = Files.createFile(Paths.get("test_cross_language_reference.data"));
    Files.write(dataFile, buffer.getBytes(0, buffer.writerIndex()));
    ImmutableList<String> command =
        ImmutableList.of(
            PYTHON_EXECUTABLE,
            "-m",
            PYTHON_MODULE,
            "test_cross_language_reference",
            dataFile.toAbsolutePath().toString());
    Assert.assertTrue(executeCommand(command));
    MemoryBuffer buffer2 = MemoryUtils.wrap(Files.readAllBytes(dataFile));
    function.accept(buffer2);
  }

  @Data
  public static class ComplexObject1 {
    Object f1;
    String f2;
    List<String> f3;
    Map<Byte, Integer> f4;
    byte f5;
    short f6;
    int f7;
    long f8;
    float f9;
    double f10;
    short[] f11;
    List<Short> f12;
  }

  @Data
  public static class ComplexObject2 {
    Object f1;
    Map<Byte, Integer> f2;
  }

  @Test
  public void testStructHash() throws Exception {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withRefTracking(true)
            .withCodegen(false)
            .requireClassRegistration(false)
            .build();
    fory.register(ComplexObject1.class, "test.ComplexObject1");
    // Serialize a valid object to trigger serializer creation
    ComplexObject1 obj = new ComplexObject1();
    obj.f1 = true; // non-null value
    obj.f2 = "test"; // non-null string
    obj.f3 = new ArrayList<>(); // non-null list
    obj.f4 = new HashMap<>(); // non-null map
    obj.f11 = new short[0]; // non-null array
    obj.f12 = new ArrayList<>(); // non-null list
    fory.serialize(obj);
    Serializer<?> serializer = fory.getSerializer(ComplexObject1.class);
    // Unwrap DeferedLazySerializer if needed - use reflection since getSerializer() is private
    if (serializer instanceof org.apache.fory.serializer.DeferedLazySerializer) {
      java.lang.reflect.Method getSerializerMethod =
          org.apache.fory.serializer.DeferedLazySerializer.class.getDeclaredMethod("getSerializer");
      getSerializerMethod.setAccessible(true);
      serializer = (Serializer<?>) getSerializerMethod.invoke(serializer);
    }
    ObjectSerializer objSerializer = (ObjectSerializer) serializer;
    Method method =
        ObjectSerializer.class.getDeclaredMethod(
            "computeStructHash", Fory.class, DescriptorGrouper.class);
    method.setAccessible(true);
    TypeResolver resolver = fory.getTypeResolver();
    Collection<Descriptor> descriptors = resolver.getFieldDescriptors(ComplexObject1.class, false);
    DescriptorGrouper grouper = resolver.createDescriptorGrouper(descriptors, false);
    Integer hash = (Integer) method.invoke(objSerializer, fory, grouper);
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(4);
    buffer.writeInt32(hash);
    roundBytes("test_struct_hash", buffer.getBytes(0, 4));
  }

  @Test(dataProvider = "compatible")
  public void testSerializeSimpleStruct(boolean compatible) throws Exception {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(
                compatible ? CompatibleMode.COMPATIBLE : CompatibleMode.SCHEMA_CONSISTENT)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    fory.register(ComplexObject2.class, "test.ComplexObject2");

    ComplexObject2 obj2 = new ComplexObject2();
    obj2.f1 = true;
    obj2.f2 = new HashMap<>(ImmutableMap.of((byte) -1, 2));
    structRoundBack(fory, obj2, "test_serialize_simple_struct" + (compatible ? "_compatible" : ""));
  }

  @Test(dataProvider = "compatible")
  public void testRegisterById(boolean compatible) throws Exception {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(
                compatible ? CompatibleMode.COMPATIBLE : CompatibleMode.SCHEMA_CONSISTENT)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    fory.register(ComplexObject2.class, 100);
    ComplexObject2 obj2 = new ComplexObject2();
    obj2.f1 = true;
    obj2.f2 = new HashMap<>(ImmutableMap.of((byte) -1, 2));
    structRoundBack(fory, obj2, "test_register_by_id" + (compatible ? "_compatible" : ""));
  }

  @Test(dataProvider = "enableCodegen")
  public void testRegisterByIdMetaShare(boolean enableCodegen) throws Exception {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCodegen(enableCodegen)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .requireClassRegistration(false)
            .build();
    fory.register(ComplexObject2.class, 100);
    ComplexObject2 obj = new ComplexObject2();
    obj.f1 = true;
    obj.f2 = new HashMap<>(ImmutableMap.of((byte) -1, 2));
    byte[] serialized = fory.serialize(obj);
    Assert.assertEquals(fory.deserialize(serialized), obj);
  }

  @Test(dataProvider = "compatible")
  public void testSerializeComplexStruct(boolean compatible) throws Exception {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(
                compatible ? CompatibleMode.COMPATIBLE : CompatibleMode.SCHEMA_CONSISTENT)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    fory.register(ComplexObject1.class, "test.ComplexObject1");
    fory.register(ComplexObject2.class, "test.ComplexObject2");
    ComplexObject2 obj2 = new ComplexObject2();
    obj2.f1 = true;
    obj2.f2 = ImmutableMap.of((byte) -1, 2);
    ComplexObject1 obj = new ComplexObject1();
    obj.f1 = obj2;
    obj.f2 = "abc";
    obj.f3 = Arrays.asList("abc", "abc");
    obj.f4 = ImmutableMap.of((byte) 1, 2);
    obj.f5 = Byte.MAX_VALUE;
    obj.f6 = Short.MAX_VALUE;
    obj.f7 = Integer.MAX_VALUE;
    obj.f8 = Long.MAX_VALUE;
    obj.f9 = 1.0f / 2;
    obj.f10 = 1 / 3.0;
    obj.f11 = new short[] {(short) 1, (short) 2};
    obj.f12 = ImmutableList.of((short) -1, (short) 4);

    structRoundBack(fory, obj, "test_serialize_complex_struct" + (compatible ? "_compatible" : ""));
  }

  private void structRoundBack(Fory fory, Object obj, String testName) throws IOException {
    byte[] serialized = fory.serialize(obj);
    Assert.assertEquals(fory.deserialize(serialized), obj);
    Path dataFile = Paths.get(testName);
    System.out.println(dataFile.toAbsolutePath());
    Files.deleteIfExists(dataFile);
    Files.write(dataFile, serialized);
    dataFile.toFile().deleteOnExit();
    ImmutableList<String> command =
        ImmutableList.of(
            PYTHON_EXECUTABLE, "-m", PYTHON_MODULE, testName, dataFile.toAbsolutePath().toString());
    Assert.assertTrue(executeCommand(command));
    Assert.assertEquals(fory.deserialize(Files.readAllBytes(dataFile)), obj);
  }

  private void structBackwardCompatibility(Fory fory, Object obj, String testName)
      throws IOException {
    byte[] serialized = fory.serialize(obj);
    Assert.assertEquals(fory.deserialize(serialized), obj);
    Path dataFile = Paths.get(testName);
    System.out.println(dataFile.toAbsolutePath());
    Files.deleteIfExists(dataFile);
    Files.write(dataFile, serialized);
    dataFile.toFile().deleteOnExit();
    ImmutableList<String> command =
        ImmutableList.of(
            PYTHON_EXECUTABLE, "-m", PYTHON_MODULE, testName, dataFile.toAbsolutePath().toString());
    // Just test that Python can read the data - don't check round-trip
    Assert.assertTrue(executeCommand(command));
  }

  private static class ComplexObject1Serializer extends Serializer<ComplexObject1> {

    public ComplexObject1Serializer(Fory fory, Class<ComplexObject1> cls) {
      super(fory, cls);
    }

    @Override
    public void write(MemoryBuffer buffer, ComplexObject1 value) {
      fory.writeRef(buffer, value.f1);
      fory.writeRef(buffer, value.f2);
      fory.writeRef(buffer, value.f3);
    }

    @SuppressWarnings("unchecked")
    @Override
    public ComplexObject1 read(MemoryBuffer buffer) {
      ComplexObject1 obj = new ComplexObject1();
      fory.getRefResolver().reference(obj);
      obj.f1 = fory.readRef(buffer);
      obj.f2 = (String) fory.readRef(buffer);
      obj.f3 = (List<String>) fory.readRef(buffer);
      return obj;
    }
  }

  @Test
  public void testRegisterSerializer() throws Exception {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    fory.register(ComplexObject1.class, "test.ComplexObject1");
    fory.registerSerializer(ComplexObject1.class, ComplexObject1Serializer.class);
    ComplexObject1 obj = new ComplexObject1();
    obj.f1 = true;
    obj.f2 = "abc";
    obj.f3 = Arrays.asList("abc", "abc");
    byte[] serialized = fory.serialize(obj);
    Assert.assertEquals(fory.deserialize(serialized), obj);
    Path dataFile = Files.createTempFile("test_register_serializer", "data");
    Files.write(dataFile, serialized);
    ImmutableList<String> command =
        ImmutableList.of(
            PYTHON_EXECUTABLE,
            "-m",
            PYTHON_MODULE,
            "test_register_serializer",
            dataFile.toAbsolutePath().toString());
    Assert.assertTrue(executeCommand(command));
    Assert.assertEquals(fory.deserialize(Files.readAllBytes(dataFile)), obj);
  }

  private byte[] roundBytes(String testName, byte[] bytes) throws IOException {
    Path dataFile = Paths.get(testName);
    System.out.println(dataFile.toAbsolutePath());
    Files.deleteIfExists(dataFile);
    Files.write(dataFile, bytes);
    dataFile.toFile().deleteOnExit();
    ImmutableList<String> command =
        ImmutableList.of(
            PYTHON_EXECUTABLE, "-m", PYTHON_MODULE, testName, dataFile.toAbsolutePath().toString());
    Assert.assertTrue(executeCommand(command));
    return Files.readAllBytes(dataFile);
  }

  @Test
  public void testOutOfBandBuffer() throws Exception {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withRefTracking(true)
            .requireClassRegistration(false)
            .build();
    AtomicInteger counter = new AtomicInteger(0);
    List<byte[]> data =
        IntStream.range(0, 10).mapToObj(i -> new byte[] {0, 1}).collect(Collectors.toList());
    List<BufferObject> bufferObjects = new ArrayList<>();
    byte[] serialized =
        fory.serialize(
            data,
            o -> {
              if (counter.incrementAndGet() % 2 == 0) {
                bufferObjects.add(o);
                return false;
              } else {
                return true;
              }
            });
    List<MemoryBuffer> buffers =
        bufferObjects.stream().map(BufferObject::toBuffer).collect(Collectors.toList());
    List<byte[]> newObj = (List<byte[]>) fory.deserialize(serialized, buffers);
    for (int i = 0; i < data.size(); i++) {
      Assert.assertEquals(data.get(i), newObj.get(i));
    }

    Path intBandDataFile = Files.createTempFile("test_oob_buffer_in_band", "data");
    Files.write(intBandDataFile, serialized);
    Path outOfBandDataFile = Files.createTempFile("test_oob_buffer_out_of_band", "data");
    Files.deleteIfExists(outOfBandDataFile);
    Files.createFile(outOfBandDataFile);
    MemoryBuffer outOfBandBuffer = MemoryBuffer.newHeapBuffer(32);
    outOfBandBuffer.writeInt32(buffers.size());
    for (int i = 0; i < buffers.size(); i++) {
      outOfBandBuffer.writeInt32(bufferObjects.get(i).totalBytes());
      bufferObjects.get(i).writeTo(outOfBandBuffer);
    }
    Files.write(outOfBandDataFile, outOfBandBuffer.getBytes(0, outOfBandBuffer.writerIndex()));
    ImmutableList<String> command =
        ImmutableList.of(
            PYTHON_EXECUTABLE,
            "-m",
            PYTHON_MODULE,
            "test_oob_buffer",
            intBandDataFile.toAbsolutePath().toString(),
            outOfBandDataFile.toAbsolutePath().toString());
    Assert.assertTrue(executeCommand(command));

    MemoryBuffer inBandBuffer = MemoryUtils.wrap(Files.readAllBytes(intBandDataFile));
    outOfBandBuffer = MemoryUtils.wrap(Files.readAllBytes(outOfBandDataFile));
    int numBuffers = outOfBandBuffer.readInt32();
    buffers = new ArrayList<>();
    for (int i = 0; i < numBuffers; i++) {
      int len = outOfBandBuffer.readInt32();
      int readerIndex = outOfBandBuffer.readerIndex();
      buffers.add(outOfBandBuffer.slice(readerIndex, len));
      outOfBandBuffer.readerIndex(readerIndex + len);
    }
    newObj = (List<byte[]>) fory.deserialize(inBandBuffer, buffers);
    Assert.assertNotNull(newObj);
    for (int i = 0; i < data.size(); i++) {
      Assert.assertEquals(data.get(i), newObj.get(i));
    }
  }

  @Data
  static class ArrayStruct {
    ArrayField[] f1;
  }

  @Data
  static class ArrayField {
    public String a;
  }

  @Test
  public void testStructArrayField() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).requireClassRegistration(true).build();
    fory.register(ArrayStruct.class, "example.bar");
    fory.register(ArrayField.class, "example.foo");

    ArrayField a = new ArrayField();
    a.a = "123";
    ArrayStruct struct = new ArrayStruct();
    struct.f1 = new ArrayField[] {a};
    Assert.assertEquals(xserDe(fory, struct), struct);
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void basicTest(boolean referenceTracking) {
    ForyBuilder builder =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withRefTracking(referenceTracking)
            .requireClassRegistration(false);
    Fory fory1 = builder.build();
    Fory fory2 = builder.build();
    fory1.register(EnumSerializerTest.EnumFoo.class);
    fory2.register(EnumSerializerTest.EnumFoo.class);
    fory1.register(EnumSerializerTest.EnumSubClass.class);
    fory2.register(EnumSerializerTest.EnumSubClass.class);
    assertEquals("str", serDe(fory1, fory2, "str"));
    assertEquals("str", serDeObject(fory1, fory2, new StringBuilder("str")).toString());
    assertEquals("str", serDeObject(fory1, fory2, new StringBuffer("str")).toString());
    assertEquals(EnumSerializerTest.EnumFoo.A, serDe(fory1, fory2, EnumSerializerTest.EnumFoo.A));
    assertEquals(EnumSerializerTest.EnumFoo.B, serDe(fory1, fory2, EnumSerializerTest.EnumFoo.B));
    assertEquals(
        EnumSerializerTest.EnumSubClass.A, serDe(fory1, fory2, EnumSerializerTest.EnumSubClass.A));
    assertEquals(
        EnumSerializerTest.EnumSubClass.B, serDe(fory1, fory2, EnumSerializerTest.EnumSubClass.B));
    java.sql.Date sqlDate = new java.sql.Date(System.currentTimeMillis());
    assertEquals(sqlDate, serDeTyped(fory1, fory2, sqlDate));
    LocalDate localDate = LocalDate.now();
    assertEquals(localDate, serDeTyped(fory1, fory2, localDate));
    Date utilDate = new Date();
    assertEquals(utilDate, serDeTyped(fory1, fory2, utilDate));
    Timestamp timestamp = new Timestamp(System.currentTimeMillis());
    assertEquals(timestamp, serDeTyped(fory1, fory2, timestamp));
    Instant instant = DateTimeUtils.truncateInstantToMicros(Instant.now());
    assertEquals(instant, serDeTyped(fory1, fory2, instant));

    ArraySerializersTest.testPrimitiveArray(fory1, fory2);

    assertEquals(Arrays.asList(1, 2), serDe(fory1, fory2, Arrays.asList(1, 2)));
    List<String> arrayList = Arrays.asList("str", "str");
    assertEquals(arrayList, serDe(fory1, fory2, arrayList));
    assertEquals(new LinkedList<>(arrayList), serDe(fory1, fory2, new LinkedList<>(arrayList)));
    assertEquals(new HashSet<>(arrayList), serDe(fory1, fory2, new HashSet<>(arrayList)));
    TreeSet<String> treeSet = new TreeSet<>(Comparator.naturalOrder());
    treeSet.add("str1");
    treeSet.add("str2");
    assertEquals(treeSet, serDe(fory1, fory2, treeSet));

    HashMap<String, Integer> hashMap = new HashMap<>();
    hashMap.put("k1", 1);
    hashMap.put("k2", 2);
    assertEquals(hashMap, serDe(fory1, fory2, hashMap));
    assertEquals(new LinkedHashMap<>(hashMap), serDe(fory1, fory2, new LinkedHashMap<>(hashMap)));
    TreeMap<String, Integer> treeMap = new TreeMap<>(Comparator.naturalOrder());
    treeMap.putAll(hashMap);
    assertEquals(treeMap, serDe(fory1, fory2, treeMap));
    assertEquals(Collections.EMPTY_LIST, serDe(fory1, fory2, Collections.EMPTY_LIST));
    assertEquals(Collections.EMPTY_SET, serDe(fory1, fory2, Collections.EMPTY_SET));
    assertEquals(Collections.EMPTY_MAP, serDe(fory1, fory2, Collections.EMPTY_MAP));
    assertEquals(
        Collections.singletonList("str"), serDe(fory1, fory2, Collections.singletonList("str")));
    assertEquals(Collections.singleton("str"), serDe(fory1, fory2, Collections.singleton("str")));
    assertEquals(
        Collections.singletonMap("k", 1), serDe(fory1, fory2, Collections.singletonMap("k", 1)));
  }

  enum EnumTestClass {
    FOO,
    BAR
  }

  @Data
  static class EnumFieldStruct {
    EnumTestClass f1;
    EnumTestClass f2;
    String f3;
  }

  @Test(dataProvider = "compatible")
  public void testEnumField(boolean compatible) throws java.io.IOException {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCompatibleMode(
                compatible ? CompatibleMode.COMPATIBLE : CompatibleMode.SCHEMA_CONSISTENT)
            .requireClassRegistration(true)
            .build();
    fory.register(EnumTestClass.class, "test.EnumTestClass");
    fory.register(EnumFieldStruct.class, "test.EnumFieldStruct");

    EnumFieldStruct a = new EnumFieldStruct();
    a.f1 = EnumTestClass.FOO;
    a.f2 = EnumTestClass.BAR;
    a.f3 = "abc";
    Assert.assertEquals(xserDe(fory, a), a);
    structRoundBack(fory, a, "test_enum_field" + (compatible ? "_compatible" : ""));
  }

  @Test(dataProvider = "compatible")
  public void testNamedEnum(boolean compatible) {
    Fory fory =
        Fory.builder()
            // avoid generated code conflict with register by name
            .withName("testEnumObject")
            .withLanguage(Language.XLANG)
            .withCompatibleMode(
                compatible ? CompatibleMode.COMPATIBLE : CompatibleMode.SCHEMA_CONSISTENT)
            .requireClassRegistration(true)
            .build();
    fory.register(EnumTestClass.class, "demo.Enum1");
    fory.register(EnumFieldStruct.class, "demo.EnumFieldStruct");
    Assert.assertEquals(xserDe(fory, EnumTestClass.FOO), EnumTestClass.FOO);
    EnumFieldStruct a = new EnumFieldStruct();
    a.f1 = EnumTestClass.FOO;
    a.f2 = EnumTestClass.BAR;
    a.f3 = "abc";
    Assert.assertEquals(xserDe(fory, a), a);
  }

  @Test(dataProvider = "compatible")
  public void testEnumFieldRegisterById(boolean compatible) throws java.io.IOException {
    Fory fory =
        Fory.builder()
            // avoid generated code conflict with register by name
            .withName("testEnumFieldRegisterById")
            .withLanguage(Language.XLANG)
            .withCompatibleMode(
                compatible ? CompatibleMode.COMPATIBLE : CompatibleMode.SCHEMA_CONSISTENT)
            .requireClassRegistration(true)
            .build();
    fory.register(EnumTestClass.class, 1);
    fory.register(EnumFieldStruct.class, 2);

    EnumFieldStruct a = new EnumFieldStruct();
    a.f1 = EnumTestClass.FOO;
    a.f2 = EnumTestClass.BAR;
    a.f3 = "abc";
    Assert.assertEquals(xserDe(fory, a), a);
    structRoundBack(fory, a, "test_enum_field_register_by_id" + (compatible ? "_compatible" : ""));
  }

  static class EnumFieldStruct2 {}

  @Test(dataProvider = "enableCodegen")
  public void testMissingEnumField(boolean enableCodegen) {
    Supplier<Fory> builder =
        () ->
            Fory.builder()
                .withLanguage(Language.XLANG)
                .withCompatibleMode(CompatibleMode.COMPATIBLE)
                .withCodegen(enableCodegen)
                .build();
    Fory fory = builder.get();
    fory.register(EnumTestClass.class, "test_enum");
    fory.register(EnumFieldStruct.class, 2);

    EnumFieldStruct a = new EnumFieldStruct();
    a.f1 = EnumTestClass.FOO;
    a.f2 = EnumTestClass.BAR;
    a.f3 = "abc";

    {
      Fory fory2 = builder.get();
      fory2.register(EnumTestClass.class, "test_enum");
      fory2.register(EnumFieldStruct2.class, 2);
      Assert.assertEquals(fory2.deserialize(fory.serialize(a)).getClass(), EnumFieldStruct2.class);
    }
    {
      Fory fory2 = builder.get();
      fory2.register(EnumTestClass.class, "test_enum");
      Assert.assertEquals(fory2.deserialize(fory.serialize(a)).getClass(), UnknownStruct.class);
    }
  }

  @Test
  public void testCrossLanguageMetaShare() throws Exception {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .requireClassRegistration(false)
            .build();
    fory.register(ComplexObject2.class, "test.ComplexObject2");

    ComplexObject2 obj = new ComplexObject2();
    obj.f1 = true;
    obj.f2 = new HashMap<>(ImmutableMap.of((byte) -1, 2));

    // Test with meta share enabled
    byte[] serialized = fory.serialize(obj);
    Assert.assertEquals(fory.deserialize(serialized), obj);

    structRoundBack(fory, obj, "test_cross_language_meta_share");
  }

  @Test(dataProvider = "enableCodegen")
  public void testCrossLanguageMetaShareComplex(boolean enableCodegen) throws Exception {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCodegen(enableCodegen)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .requireClassRegistration(false)
            .build();
    fory.register(ComplexObject1.class, "test.ComplexObject1");
    fory.register(ComplexObject2.class, "test.ComplexObject2");

    ComplexObject2 obj2 = new ComplexObject2();
    obj2.f1 = true;
    obj2.f2 = ImmutableMap.of((byte) -1, 2);

    ComplexObject1 obj = new ComplexObject1();
    obj.f1 = obj2;
    obj.f2 = "meta_share_test";
    obj.f3 = Arrays.asList("compatible", "mode");
    obj.f4 = ImmutableMap.of((byte) 1, 2);
    obj.f5 = Byte.MAX_VALUE;
    obj.f6 = Short.MAX_VALUE;
    obj.f7 = Integer.MAX_VALUE;
    obj.f8 = Long.MAX_VALUE;
    obj.f9 = 1.0f / 2;
    obj.f10 = 1 / 3.0;
    obj.f11 = new short[] {(short) 1, (short) 2};
    obj.f12 = ImmutableList.of((short) -1, (short) 4);

    // Test with meta share enabled
    byte[] serialized = fory.serialize(obj);
    Assert.assertEquals(fory.deserialize(serialized), obj);

    structRoundBack(fory, obj, "test_cross_language_meta_share_complex");
  }

  // Compatibility test classes - Version 1 (original)
  @Data
  public static class CompatTestV1 {
    String name;
    Integer age;
  }

  // Compatibility test classes - Version 2 (with additional field)
  @Data
  public static class CompatTestV2 {
    String name;
    Integer age;
    String email; // New field added
  }

  // Compatibility test classes - Version 3 (with reordered fields)
  @Data
  public static class CompatTestV3 {
    Integer age; // Reordered
    String name; // Reordered
    String email;
    Boolean active; // Another new field
  }

  @Test(dataProvider = "enableCodegen")
  public void testSchemaEvolution(boolean enableCodegen) throws Exception {
    // Test simple schema evolution compatibility
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCodegen(enableCodegen)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .requireClassRegistration(false)
            .build();

    fory.register(CompatTestV1.class, "test.CompatTest");

    CompatTestV1 objV1 = new CompatTestV1();
    objV1.name = "Schema Evolution Test";
    objV1.age = 42;

    // Serialize with V1 schema
    Assert.assertEquals(fory.deserialize(fory.serialize(objV1)), objV1);

    structRoundBack(fory, objV1, "test_schema_evolution");
  }

  @Test(dataProvider = "enableCodegen")
  public void testBackwardCompatibility(boolean enableCodegen) throws Exception {
    // Test that old version can read new data (ignoring unknown fields)
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCodegen(enableCodegen)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .requireClassRegistration(false)
            .build();

    fory.register(CompatTestV2.class, "test.CompatTest");

    CompatTestV2 objV2 = new CompatTestV2();
    objV2.name = "Bob";
    objV2.age = 30;
    objV2.email = "bob@example.com";

    // Serialize with V2 schema
    Assert.assertEquals(fory.deserialize(fory.serialize(objV2)), objV2);

    // Test: old version (V1) reads new version (V2) data
    // Expected: V1 should successfully read name and age, ignoring email
    structBackwardCompatibility(fory, objV2, "test_backward_compatibility");
  }

  @Test(dataProvider = "enableCodegen")
  public void testFieldReorderingCompatibility(boolean enableCodegen) throws Exception {
    // Test that field reordering doesn't break compatibility
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withCodegen(enableCodegen)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .requireClassRegistration(false)
            .build();

    fory.register(CompatTestV3.class, "test.CompatTest");

    CompatTestV3 objV3 = new CompatTestV3();
    objV3.name = "Charlie";
    objV3.age = 35;
    objV3.email = "charlie@example.com";
    objV3.active = true;

    // Serialize with V3 schema (reordered fields)
    Assert.assertEquals(fory.deserialize(fory.serialize(objV3)), objV3);

    structRoundBack(fory, objV3, "test_field_reordering_compatibility");
  }

  @Data
  public static class CompatContainer {
    CompatTestV1 oldObject;
    CompatTestV2 newObject;
  }

  @Test(dataProvider = "enableCodegen")
  public void testCrossVersionCompatibility(boolean enableCodegen) throws Exception {
    // Test mixed version compatibility in one test
    Fory fory =
        Fory.builder()
            .withLanguage(Language.XLANG)
            .withRefTracking(true)
            .withCodegen(enableCodegen)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .requireClassRegistration(false)
            .build();

    fory.register(CompatContainer.class, "test.CompatContainer");
    fory.register(CompatTestV1.class, "test.CompatTestV1");
    fory.register(CompatTestV2.class, "test.CompatTestV2");

    CompatTestV1 v1 = new CompatTestV1();
    v1.name = "Old Format";
    v1.age = 20;

    CompatTestV2 v2 = new CompatTestV2();
    v2.name = "New Format";
    v2.age = 25;
    v2.email = "new@example.com";

    CompatContainer container = new CompatContainer();
    container.oldObject = v1;
    container.newObject = v2;

    structRoundBack(fory, container, "test_cross_version_compatibility");
  }
}
