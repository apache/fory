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

package org.apache.fory.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.io.ForyInputStream;
import org.apache.fory.io.ForyReadableChannel;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.StaticGeneratedStructSerializer;
import org.apache.fory.type.union.Union2;

public final class AndroidForyRuntimeScenarios {
  private AndroidForyRuntimeScenarios() {}

  public static void androidRuntimeDisablesCodegenAndUnsafeCopies() {
    check(AndroidSupport.IS_ANDROID, "AndroidSupport must detect Android runtime");

    Fory fory = nativeBuilder().withCodegen(true).withAsyncCompilation(true).build();
    check(!fory.getConfig().isCodeGenEnabled(), "runtime codegen must be disabled on Android");
    check(
        !fory.getConfig().isAsyncCompilationEnabled(),
        "async compilation must be disabled on Android");

    MemoryBuffer buffer = MemoryUtils.buffer(16);
    byte[] target = new byte[16];
    buffer.copyToByteArray(0, target, 0, 1);
  }

  public static void structEnumCollectionAndMapRoundTrip() {
    Fory fory = nativeBuilder().build();
    AndroidStruct value = AndroidStruct.create();
    AndroidStruct copy = roundTrip(fory, value);

    checkEquals(value, copy);
    checkEquals(AndroidEnum.GREEN, roundTrip(fory, AndroidEnum.GREEN));
  }

  public static void customSerializerExtRoundTrip() {
    Fory fory = nativeBuilder().build();
    fory.registerSerializerAndType(AndroidExt.class, AndroidExtSerializer.class);

    AndroidExt value = new AndroidExt("android", 26);
    checkEquals(value, roundTrip(fory, value));
  }

  public static void xlangUnionRoundTrip() {
    Fory fory =
        Fory.builder().withXlang(true).registerGuavaTypes(false).withCompatible(true).build();

    Union2<String, Integer> value = Union2.ofT2(26);
    Object copy = roundTrip(fory, value);

    checkEquals(value, copy);
  }

  public static void staticGeneratedSerializerSurvivesReleaseMinification() {
    Class<?> expectedSerializerClass;
    try {
      expectedSerializerClass =
          Class.forName(
              "org.apache.fory.android.AndroidGeneratedStruct_ForySerializer",
              false,
              AndroidGeneratedStruct.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new AssertionError(
          "static generated serializer was removed by release minification", e);
    }

    Fory fory =
        Fory.builder()
            .withXlang(true)
            .requireClassRegistration(true)
            .registerGuavaTypes(false)
            .withCompatible(true)
            .build();
    fory.register(AndroidGeneratedStruct.class, "android", "GeneratedStruct");

    AndroidGeneratedStruct value = new AndroidGeneratedStruct(7, "generated");
    checkEquals(value, roundTrip(fory, value));

    Serializer<?> serializer = fory.getTypeResolver().getSerializer(AndroidGeneratedStruct.class);
    check(
        serializer instanceof StaticGeneratedStructSerializer,
        "expected static generated serializer but got " + serializer.getClass().getName());
    checkSame(expectedSerializerClass, serializer.getClass());
  }

  public static void jdkProxyCycleRoundTripAndCopy() {
    Fory fory = nativeBuilder().withRefCopy(true).build();
    AndroidProxyHandler handler = new AndroidProxyHandler();
    AndroidProxyService proxy =
        (AndroidProxyService)
            Proxy.newProxyInstance(
                AndroidProxyService.class.getClassLoader(),
                new Class[] {AndroidProxyService.class},
                handler);
    handler.proxy = proxy;

    AndroidProxyService copy = roundTrip(fory, proxy);
    checkEquals(42, copy.value());
    checkSame(copy, copy.self());

    AndroidProxyService copiedAgain = fory.copy(proxy);
    checkEquals(42, copiedAgain.value());
    checkSame(copiedAgain, copiedAgain.self());
  }

  public static void internalCollectionMapAndSetWrappersRoundTrip() {
    Fory fory = nativeBuilder().build();

    List<String> list = Arrays.asList("a", "b", "c");
    checkEquals(list, roundTrip(fory, list));
    checkEquals(list.subList(1, 3), roundTrip(fory, list.subList(1, 3)));

    Set<String> set = new HashSet<>(list);
    Map<String, Integer> map = new HashMap<>();
    map.put("a", 1);
    map.put("b", 2);

    checkEquals(
        Collections.unmodifiableList(list), roundTrip(fory, Collections.unmodifiableList(list)));
    checkEquals(
        Collections.unmodifiableSet(set), roundTrip(fory, Collections.unmodifiableSet(set)));
    checkEquals(
        Collections.unmodifiableMap(map), roundTrip(fory, Collections.unmodifiableMap(map)));
    checkEquals(
        Collections.synchronizedList(new ArrayList<>(list)),
        roundTrip(fory, Collections.synchronizedList(new ArrayList<>(list))));
    checkEquals(
        Collections.synchronizedSet(set), roundTrip(fory, Collections.synchronizedSet(set)));
    checkEquals(
        Collections.synchronizedMap(map), roundTrip(fory, Collections.synchronizedMap(map)));

    Set<String> setFromMap = Collections.newSetFromMap(new HashMap<String, Boolean>());
    setFromMap.addAll(list);
    checkEquals(setFromMap, roundTrip(fory, setFromMap));

    EnumMap<AndroidEnum, String> enumMap = new EnumMap<>(AndroidEnum.class);
    enumMap.put(AndroidEnum.BLUE, "blue");
    checkEquals(enumMap, roundTrip(fory, enumMap));
    checkEquals(
        new EnumMap<>(AndroidEnum.class), roundTrip(fory, new EnumMap<>(AndroidEnum.class)));
  }

  public static void byteBufferStreamChannelAndOutOfBandRoundTrip() throws Exception {
    ThreadSafeFory threadSafeFory = nativeBuilder().buildThreadSafeFory();
    Fory fory = nativeBuilder().build();
    AndroidStruct value = AndroidStruct.create();

    byte[] bytes = fory.serialize(value);
    ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length + 2);
    direct.position(1);
    direct.put(bytes);
    direct.limit(direct.position());
    direct.position(1);
    ByteBuffer readonly = direct.asReadOnlyBuffer();
    int position = readonly.position();
    int limit = readonly.limit();
    checkEquals(value, threadSafeFory.deserialize(readonly));
    checkEquals(position, readonly.position());
    checkEquals(limit, readonly.limit());

    ByteArrayOutputStream streamBytes = new ByteArrayOutputStream();
    fory.serialize(streamBytes, value);
    ForyInputStream inputStream =
        new ForyInputStream(new ByteArrayInputStream(streamBytes.toByteArray()));
    checkEquals(value, fory.deserialize(inputStream));

    ForyReadableChannel channel =
        new ForyReadableChannel(
            Channels.newChannel(new ByteArrayInputStream(streamBytes.toByteArray())));
    checkEquals(value, fory.deserialize(channel));

    final List<MemoryBuffer> buffers = new ArrayList<>();
    int[] numbers = new int[] {1, 2, 3, 4, 5, 6};
    byte[] payload =
        fory.serialize(
            numbers,
            bufferObject -> {
              buffers.add(bufferObject.toBuffer());
              return false;
            });
    checkArrayEquals(numbers, (int[]) fory.deserialize(payload, buffers));
  }

  private static ForyBuilder nativeBuilder() {
    return Fory.builder()
        .withXlang(false)
        .withRefTracking(true)
        .requireClassRegistration(false)
        .withCompatible(false)
        .registerGuavaTypes(false);
  }

  @SuppressWarnings("unchecked")
  private static <T> T roundTrip(Fory fory, T value) {
    return (T) fory.deserialize(fory.serialize(value));
  }

  public enum AndroidEnum {
    RED,
    GREEN,
    BLUE
  }

  public static class AndroidStruct {
    public int id;
    public String name;
    public AndroidEnum color;
    public ArrayList<String> tags;
    public HashMap<String, Integer> scores;
    public AndroidNested nested;

    public AndroidStruct() {}

    public static AndroidStruct create() {
      AndroidStruct struct = new AndroidStruct();
      struct.id = 26;
      struct.name = "android";
      struct.color = AndroidEnum.GREEN;
      struct.tags = new ArrayList<>(Arrays.asList("core", "runtime"));
      struct.scores = new HashMap<>();
      struct.scores.put("api", 26);
      struct.scores.put("status", 1);
      struct.nested = new AndroidNested("child", 7);
      return struct;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof AndroidStruct)) {
        return false;
      }
      AndroidStruct that = (AndroidStruct) o;
      return id == that.id
          && equalsNullable(name, that.name)
          && color == that.color
          && equalsNullable(tags, that.tags)
          && equalsNullable(scores, that.scores)
          && equalsNullable(nested, that.nested);
    }

    @Override
    public int hashCode() {
      return id;
    }
  }

  public static class AndroidNested {
    public String label;
    public int count;

    public AndroidNested() {}

    public AndroidNested(String label, int count) {
      this.label = label;
      this.count = count;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof AndroidNested)) {
        return false;
      }
      AndroidNested that = (AndroidNested) o;
      return count == that.count && equalsNullable(label, that.label);
    }

    @Override
    public int hashCode() {
      return count;
    }
  }

  public static class AndroidExt {
    public String name;
    public int api;

    public AndroidExt() {}

    public AndroidExt(String name, int api) {
      this.name = name;
      this.api = api;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof AndroidExt)) {
        return false;
      }
      AndroidExt that = (AndroidExt) o;
      return api == that.api && equalsNullable(name, that.name);
    }

    @Override
    public int hashCode() {
      return api;
    }
  }

  public static final class AndroidExtSerializer extends Serializer<AndroidExt> {
    public AndroidExtSerializer(TypeResolver typeResolver, Class<AndroidExt> cls) {
      super(typeResolver.getConfig(), cls);
    }

    @Override
    public void write(WriteContext writeContext, AndroidExt value) {
      writeContext.writeString(value.name);
      writeContext.getBuffer().writeVarInt32(value.api);
    }

    @Override
    public AndroidExt read(ReadContext readContext) {
      AndroidExt value = new AndroidExt();
      value.name = readContext.readString();
      value.api = readContext.getBuffer().readVarInt32();
      return value;
    }
  }

  public interface AndroidProxyService {
    int value();

    AndroidProxyService self();
  }

  public static final class AndroidProxyHandler implements InvocationHandler, Serializable {
    public AndroidProxyService proxy;

    public AndroidProxyHandler() {}

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      switch (method.getName()) {
        case "value":
          return 42;
        case "self":
          return this.proxy;
        case "toString":
          return "AndroidProxyService";
        case "hashCode":
          return System.identityHashCode(proxy);
        case "equals":
          return proxy == args[0];
        default:
          throw new UnsupportedOperationException(method.toString());
      }
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      fail(message);
    }
  }

  private static void checkSame(Object expected, Object actual) {
    if (expected != actual) {
      fail("Expected same instance, expected=" + expected + ", actual=" + actual);
    }
  }

  private static void checkEquals(Object expected, Object actual) {
    if (!equalsNullable(expected, actual)) {
      fail("Expected " + expected + " but got " + actual);
    }
  }

  private static void checkArrayEquals(int[] expected, int[] actual) {
    if (!Arrays.equals(expected, actual)) {
      fail("Expected " + Arrays.toString(expected) + " but got " + Arrays.toString(actual));
    }
  }

  private static void fail(String message) {
    throw new AssertionError(message);
  }

  private static boolean equalsNullable(Object left, Object right) {
    return left == right || (left != null && left.equals(right));
  }
}
