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

package org.apache.fory.serializer.collection;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.apache.fory.Fory;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.JdkVersion;
import org.testng.Assert;
import org.testng.annotations.Test;

public class AndroidCollectionFeatureTest {
  private static final String ANDROID_CHILD_CONTAINER_PAYLOAD_PREFIX =
      "ANDROID_CHILD_CONTAINER_PAYLOAD=";
  private static final String ANDROID_SUBLIST_PAYLOAD_PREFIX = "ANDROID_SUBLIST_PAYLOAD=";
  private static final String ANDROID_ENUM_MAP_PAYLOAD_PREFIX = "ANDROID_ENUM_MAP_PAYLOAD=";
  private static final String ANDROID_EMPTY_ENUM_MAP_PAYLOAD_PREFIX =
      "ANDROID_EMPTY_ENUM_MAP_PAYLOAD=";

  @Test
  public void testAndroidCollectionFeaturePaths() throws Exception {
    Fory jvmFory = newCompatibleChildContainerFory();
    AndroidCollectionFeatureProbe.AndroidChildArrayList jvmValue =
        newChildContainerValue("jvm-label");
    String jvmPayload = encode(jvmFory, jvmValue);
    String jvmSubListPayload = encode(jvmFory, newSubListValue());
    String jvmEnumMapPayload = encode(jvmFory, newEnumMapValue());
    String jvmEmptyEnumMapPayload =
        encode(jvmFory, new EnumMap<>(AndroidCollectionFeatureProbe.TestEnum.class));
    String jvmArrayQueuePayload = encode(jvmFory, newArrayBlockingQueueValue());
    String jvmLinkedQueuePayload = encode(jvmFory, newLinkedBlockingQueueValue());

    String javaBin =
        System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    ArrayList<String> command = new ArrayList<>();
    command.add(javaBin);
    if (!System.getProperty("java.specification.version").startsWith("1.")) {
      command.add("--add-opens");
      command.add("java.base/java.util=ALL-UNNAMED");
      command.add("--add-opens");
      command.add("java.base/java.lang.invoke=ALL-UNNAMED");
    }
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(AndroidCollectionFeatureProbe.class.getName());
    command.add(jvmPayload);
    command.add(jvmSubListPayload);
    command.add(jvmEnumMapPayload);
    command.add(jvmEmptyEnumMapPayload);
    command.add(jvmArrayQueuePayload);
    command.add(jvmLinkedQueuePayload);
    ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
    processBuilder.environment().put("FORY_ANDROID_ENABLED", "1");
    Process process = processBuilder.start();
    String output = readFully(process.getInputStream());
    Assert.assertEquals(process.waitFor(), 0, output);

    Map<String, String> payloads = new HashMap<>();
    for (String line : output.split("\\r?\\n")) {
      if (line.startsWith(ANDROID_CHILD_CONTAINER_PAYLOAD_PREFIX)) {
        payloads.put(ANDROID_CHILD_CONTAINER_PAYLOAD_PREFIX, line);
      } else if (line.startsWith(ANDROID_SUBLIST_PAYLOAD_PREFIX)) {
        payloads.put(ANDROID_SUBLIST_PAYLOAD_PREFIX, line);
      } else if (line.startsWith(ANDROID_ENUM_MAP_PAYLOAD_PREFIX)) {
        payloads.put(ANDROID_ENUM_MAP_PAYLOAD_PREFIX, line);
      } else if (line.startsWith(ANDROID_EMPTY_ENUM_MAP_PAYLOAD_PREFIX)) {
        payloads.put(ANDROID_EMPTY_ENUM_MAP_PAYLOAD_PREFIX, line);
      }
    }
    AndroidCollectionFeatureProbe.AndroidChildArrayList restored =
        (AndroidCollectionFeatureProbe.AndroidChildArrayList)
            jvmFory.deserialize(decodePayload(payloads, ANDROID_CHILD_CONTAINER_PAYLOAD_PREFIX));
    Assert.assertEquals(restored, newChildContainerValue("android-label"));
    Assert.assertEquals(restored.label, "android-label");
    Object subListRestored =
        jvmFory.deserialize(decodePayload(payloads, ANDROID_SUBLIST_PAYLOAD_PREFIX));
    Assert.assertEquals(subListRestored, Arrays.asList(2, 3));
    Assert.assertEquals(subListRestored.getClass(), ArrayList.class);
    Assert.assertEquals(
        jvmFory.deserialize(decodePayload(payloads, ANDROID_ENUM_MAP_PAYLOAD_PREFIX)),
        newEnumMapValue());
    Assert.assertEquals(
        jvmFory.deserialize(decodePayload(payloads, ANDROID_EMPTY_ENUM_MAP_PAYLOAD_PREFIX)),
        new EnumMap<>(AndroidCollectionFeatureProbe.TestEnum.class));
  }

  private static String encode(Fory fory, Object value) {
    return Base64.getEncoder().encodeToString(fory.serialize(value));
  }

  private static byte[] decodePayload(Map<String, String> payloads, String prefix) {
    String line = payloads.get(prefix);
    Assert.assertNotNull(line, "Missing " + prefix);
    return Base64.getDecoder().decode(line.substring(prefix.length()));
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

  private static Fory newCompatibleChildContainerFory() {
    return Fory.builder()
        .withXlang(false)
        .withCodegen(true)
        .withRefTracking(true)
        .requireClassRegistration(false)
        .withCompatibleMode(CompatibleMode.COMPATIBLE)
        .withCompatible(false)
        .build();
  }

  private static AndroidCollectionFeatureProbe.AndroidChildArrayList newChildContainerValue(
      String label) {
    AndroidCollectionFeatureProbe.AndroidChildArrayList value =
        new AndroidCollectionFeatureProbe.AndroidChildArrayList(label);
    value.add("a");
    value.add("b");
    return value;
  }

  private static List<Integer> newSubListValue() {
    return new ArrayList<>(Arrays.asList(1, 2, 3, 4)).subList(1, 3);
  }

  private static EnumMap<AndroidCollectionFeatureProbe.TestEnum, String> newEnumMapValue() {
    EnumMap<AndroidCollectionFeatureProbe.TestEnum, String> map =
        new EnumMap<>(AndroidCollectionFeatureProbe.TestEnum.class);
    map.put(AndroidCollectionFeatureProbe.TestEnum.ONE, "one");
    map.put(AndroidCollectionFeatureProbe.TestEnum.TWO, "two");
    return map;
  }

  private static ArrayBlockingQueue<String> newArrayBlockingQueueValue() {
    ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(5);
    queue.add("a");
    queue.add("b");
    return queue;
  }

  private static LinkedBlockingQueue<String> newLinkedBlockingQueueValue() {
    LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(6);
    queue.add("a");
    queue.add("b");
    return queue;
  }

  public static final class AndroidCollectionFeatureProbe {
    public static void main(String[] args) throws Exception {
      check(
          args.length == 6,
          "Expected JVM child-container, sublist, enum-map, empty-enum-map, and queue payloads");
      System.setProperty("java.vm.name", "Dalvik");
      System.setProperty("java.runtime.name", "Android Runtime");
      check(AndroidSupport.IS_ANDROID, "AndroidSupport should detect Dalvik runtime");

      Fory fory =
          Fory.builder()
              .withXlang(false)
              .withCodegen(true)
              .withRefTracking(true)
              .requireClassRegistration(false)
              .withCompatibleMode(CompatibleMode.COMPATIBLE)
              .withCompatible(false)
              .build();
      check(!fory.getConfig().isCodeGenEnabled(), "Android must force codegen off");
      verifyUnmodifiableWrappers(fory);
      verifySynchronizedWrappers(fory);
      verifyWrapperReferenceAlignment(fory);
      verifySubList(fory);
      verifyArraysAsList(fory);
      verifySetFromMap(fory);
      verifyEnumMap(fory);
      if (JdkVersion.MAJOR_VERSION >= 9) {
        verifyImmutableCollections(fory);
      }
      verifyChildContainerFields(Base64.getDecoder().decode(args[0]));
      verifyJvmSubListPayload(fory, Base64.getDecoder().decode(args[1]));
      verifyJvmEnumMapPayloads(
          fory, Base64.getDecoder().decode(args[2]), Base64.getDecoder().decode(args[3]));
      verifyJvmQueuePayloads(
          fory, Base64.getDecoder().decode(args[4]), Base64.getDecoder().decode(args[5]));
      writeAndroidFeaturePayloads(fory);
    }

    private static void verifyUnmodifiableWrappers(Fory fory) {
      List<String> list = Collections.unmodifiableList(Arrays.asList("a", "b"));
      List<String> listRestored = (List<String>) roundTrip(fory, list);
      checkEquals(listRestored, list, "unmodifiable list");
      expectUnsupportedAdd(listRestored);

      Set<String> set = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("x", "y")));
      Set<String> setRestored = (Set<String>) roundTrip(fory, set);
      checkEquals(setRestored, set, "unmodifiable set");
      expectUnsupportedAdd(setRestored);

      TreeSet<String> sortedSource = new TreeSet<>(new LengthComparator());
      Collections.addAll(sortedSource, "bbb", "a", "cc");
      SortedSet<String> sorted = Collections.unmodifiableSortedSet(sortedSource);
      SortedSet<String> sortedRestored = (SortedSet<String>) roundTrip(fory, sorted);
      checkEquals(sortedRestored, sorted, "unmodifiable sorted set");
      check(
          sortedRestored.comparator() instanceof LengthComparator,
          "unmodifiable sorted set comparator");
      expectUnsupportedAdd(sortedRestored);

      Map<String, Integer> map = Collections.unmodifiableMap(singletonMap("one", 1));
      Map<String, Integer> mapRestored = (Map<String, Integer>) roundTrip(fory, map);
      checkEquals(mapRestored, map, "unmodifiable map");
      expectUnsupportedPut(mapRestored);

      TreeMap<String, Integer> sortedMapSource = new TreeMap<>(new LengthComparator());
      sortedMapSource.put("bbb", 3);
      sortedMapSource.put("a", 1);
      sortedMapSource.put("cc", 2);
      SortedMap<String, Integer> sortedMap = Collections.unmodifiableSortedMap(sortedMapSource);
      SortedMap<String, Integer> sortedMapRestored =
          (SortedMap<String, Integer>) roundTrip(fory, sortedMap);
      checkEquals(sortedMapRestored, sortedMap, "unmodifiable sorted map");
      check(
          sortedMapRestored.comparator() instanceof LengthComparator,
          "unmodifiable sorted map comparator");
      expectUnsupportedPut(sortedMapRestored);
    }

    private static void verifySynchronizedWrappers(Fory fory) {
      List<String> list = Collections.synchronizedList(new ArrayList<>(Arrays.asList("a", "b")));
      checkEquals(roundTrip(fory, list), list, "synchronized list");

      Set<String> set = Collections.synchronizedSet(new HashSet<>(Arrays.asList("x", "y")));
      checkEquals(roundTrip(fory, set), set, "synchronized set");

      TreeSet<String> sortedSource = new TreeSet<>(new LengthComparator());
      Collections.addAll(sortedSource, "bbb", "a", "cc");
      SortedSet<String> sorted = Collections.synchronizedSortedSet(sortedSource);
      SortedSet<String> sortedRestored = (SortedSet<String>) roundTrip(fory, sorted);
      checkEquals(sortedRestored, sorted, "synchronized sorted set");
      check(sortedRestored.comparator() instanceof LengthComparator, "synchronized set comparator");

      Map<String, Integer> map = Collections.synchronizedMap(singletonMap("one", 1));
      checkEquals(roundTrip(fory, map), map, "synchronized map");

      TreeMap<String, Integer> sortedMapSource = new TreeMap<>(new LengthComparator());
      sortedMapSource.put("bbb", 3);
      sortedMapSource.put("a", 1);
      sortedMapSource.put("cc", 2);
      SortedMap<String, Integer> sortedMap = Collections.synchronizedSortedMap(sortedMapSource);
      SortedMap<String, Integer> sortedMapRestored =
          (SortedMap<String, Integer>) roundTrip(fory, sortedMap);
      checkEquals(sortedMapRestored, sortedMap, "synchronized sorted map");
      check(
          sortedMapRestored.comparator() instanceof LengthComparator,
          "synchronized map comparator");
    }

    private static void verifyWrapperReferenceAlignment(Fory fory) {
      List<String> wrapper = Collections.unmodifiableList(Arrays.asList("a", "b"));
      ArrayList<String> repeated = new ArrayList<>();
      repeated.add("tail");
      ArrayList<Object> graph = new ArrayList<>();
      graph.add(wrapper);
      graph.add(repeated);
      graph.add(repeated);
      graph.add(wrapper);

      List<Object> restored = (List<Object>) roundTrip(fory, graph);
      check(restored.get(1) == restored.get(2), "wrapper source must not shift later ref ids");
      check(restored.get(0) == restored.get(3), "wrapper outer ref id");
    }

    private static void verifySubList(Fory fory) {
      List<Integer> subList = new ArrayList<>(Arrays.asList(1, 2, 3, 4)).subList(1, 3);
      Object restored = roundTrip(fory, subList);
      check(
          restored.getClass() == ArrayList.class,
          "Android sublist should deserialize as ArrayList");
      checkEquals(restored, Arrays.asList(2, 3), "sublist visible elements");
    }

    private static void verifyArraysAsList(Fory fory) {
      List<String> list = Arrays.asList("a", "b", "c");
      checkEquals(roundTrip(fory, list), list, "Arrays.asList");
      checkEquals(fory.copy(list), list, "Arrays.asList copy");
    }

    private static void verifySetFromMap(Fory fory) {
      Set<String> set = Collections.newSetFromMap(new HashMap<String, Boolean>());
      Collections.addAll(set, "a", "b");
      Set<String> restored = (Set<String>) roundTrip(fory, set);
      checkEquals(restored, set, "SetFromMap");
      Set<String> copy = (Set<String>) fory.copy(set);
      checkEquals(copy, set, "SetFromMap copy");
    }

    private static void verifyJvmQueuePayloads(
        Fory fory, byte[] jvmArrayQueuePayload, byte[] jvmLinkedQueuePayload) {
      ArrayBlockingQueue<String> arrayQueue = newArrayBlockingQueueValue();
      ArrayBlockingQueue<String> arrayRestored =
          (ArrayBlockingQueue<String>) fory.deserialize(jvmArrayQueuePayload);
      checkEquals(
          new ArrayList<>(arrayRestored),
          new ArrayList<>(arrayQueue),
          "JVM ArrayBlockingQueue payload");
      checkEquals(arrayRestored.remainingCapacity(), 3, "ArrayBlockingQueue capacity");

      LinkedBlockingQueue<String> linkedQueue = newLinkedBlockingQueueValue();
      LinkedBlockingQueue<String> linkedRestored =
          (LinkedBlockingQueue<String>) fory.deserialize(jvmLinkedQueuePayload);
      checkEquals(
          new ArrayList<>(linkedRestored),
          new ArrayList<>(linkedQueue),
          "JVM LinkedBlockingQueue payload");
      checkEquals(linkedRestored.remainingCapacity(), 4, "LinkedBlockingQueue capacity");
    }

    private static void verifyEnumMap(Fory fory) {
      EnumMap<TestEnum, String> map = new EnumMap<>(TestEnum.class);
      map.put(TestEnum.ONE, "one");
      map.put(TestEnum.TWO, "two");
      checkEquals(roundTrip(fory, map), map, "EnumMap");

      EnumMap<TestEnum, String> empty = new EnumMap<>(TestEnum.class);
      checkEquals(roundTrip(fory, empty), empty, "empty EnumMap");
    }

    private static void verifyImmutableCollections(Fory fory) throws Exception {
      List<String> list = immutableList("a", "b");
      List<String> listRestored = (List<String>) roundTrip(fory, list);
      checkEquals(listRestored, list, "immutable list");
      expectUnsupportedAdd(listRestored);

      Set<String> set = immutableSet("x", "y");
      Set<String> setRestored = (Set<String>) roundTrip(fory, set);
      checkEquals(setRestored, set, "immutable set");
      expectUnsupportedAdd(setRestored);

      Map<String, Integer> map = immutableMap("one", 1, "two", 2);
      Map<String, Integer> mapRestored = (Map<String, Integer>) roundTrip(fory, map);
      checkEquals(mapRestored, map, "immutable map");
      expectUnsupportedPut(mapRestored);
    }

    private static void verifyChildContainerFields(byte[] jvmPayload) {
      Fory fory = newCompatibleChildContainerFory();
      AndroidChildArrayList jvmRestored = (AndroidChildArrayList) fory.deserialize(jvmPayload);
      checkEquals(jvmRestored, newChildContainerValue("jvm-label"), "JVM child ArrayList elements");
      checkEquals(jvmRestored.label, "jvm-label", "JVM child ArrayList field");

      AndroidChildArrayList value = newChildContainerValue("android-label");
      AndroidChildArrayList restored = (AndroidChildArrayList) roundTrip(fory, value);
      checkEquals(restored, value, "child ArrayList elements");
      checkEquals(restored.label, "android-label", "child ArrayList field");
      System.out.println(
          ANDROID_CHILD_CONTAINER_PAYLOAD_PREFIX
              + Base64.getEncoder().encodeToString(fory.serialize(value)));
    }

    private static void verifyJvmSubListPayload(Fory fory, byte[] jvmPayload) {
      Object restored = fory.deserialize(jvmPayload);
      checkEquals(restored, Arrays.asList(2, 3), "JVM sublist payload");
    }

    private static void verifyJvmEnumMapPayloads(
        Fory fory, byte[] jvmEnumMapPayload, byte[] jvmEmptyEnumMapPayload) {
      checkEquals(fory.deserialize(jvmEnumMapPayload), newEnumMapValue(), "JVM EnumMap payload");
      checkEquals(
          fory.deserialize(jvmEmptyEnumMapPayload),
          new EnumMap<>(TestEnum.class),
          "JVM empty EnumMap payload");
    }

    private static void writeAndroidFeaturePayloads(Fory fory) {
      System.out.println(
          ANDROID_SUBLIST_PAYLOAD_PREFIX
              + Base64.getEncoder().encodeToString(fory.serialize(newSubListValue())));
      System.out.println(
          ANDROID_ENUM_MAP_PAYLOAD_PREFIX
              + Base64.getEncoder().encodeToString(fory.serialize(newEnumMapValue())));
      System.out.println(
          ANDROID_EMPTY_ENUM_MAP_PAYLOAD_PREFIX
              + Base64.getEncoder().encodeToString(fory.serialize(new EnumMap<>(TestEnum.class))));
    }

    private static Object roundTrip(Fory fory, Object value) {
      return fory.deserialize(fory.serialize(value));
    }

    private static <K, V> Map<K, V> singletonMap(K key, V value) {
      HashMap<K, V> map = new HashMap<>();
      map.put(key, value);
      return map;
    }

    private static List<String> immutableList(String... values) throws Exception {
      Method method = List.class.getMethod("of", Object[].class);
      return (List<String>) method.invoke(null, new Object[] {values});
    }

    private static Set<String> immutableSet(String... values) throws Exception {
      Method method = Set.class.getMethod("of", Object[].class);
      return (Set<String>) method.invoke(null, new Object[] {values});
    }

    private static Map<String, Integer> immutableMap(
        String key1, int value1, String key2, int value2) throws Exception {
      Method method =
          Map.class.getMethod("of", Object.class, Object.class, Object.class, Object.class);
      return (Map<String, Integer>) method.invoke(null, key1, value1, key2, value2);
    }

    private static void expectUnsupportedAdd(Collection collection) {
      try {
        collection.add(new Object());
        throw new AssertionError("Expected add to be unsupported");
      } catch (UnsupportedOperationException expected) {
        // Expected.
      }
    }

    private static void expectUnsupportedPut(Map map) {
      try {
        map.put(new Object(), new Object());
        throw new AssertionError("Expected put to be unsupported");
      } catch (UnsupportedOperationException expected) {
        // Expected.
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

    public enum TestEnum {
      ONE,
      TWO
    }

    public static final class LengthComparator implements Comparator<String>, Serializable {
      @Override
      public int compare(String left, String right) {
        int lengthCompare = Integer.compare(left.length(), right.length());
        if (lengthCompare != 0) {
          return lengthCompare;
        }
        return left.compareTo(right);
      }
    }

    public static final class AndroidChildArrayList extends ArrayList<String> {
      private String label;

      public AndroidChildArrayList() {}

      AndroidChildArrayList(String label) {
        this.label = label;
      }
    }
  }
}
