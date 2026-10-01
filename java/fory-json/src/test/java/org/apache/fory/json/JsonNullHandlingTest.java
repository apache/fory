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

package org.apache.fory.json;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonAnySetter;
import org.apache.fory.json.annotation.JsonByteArray;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonMixinRemove;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonProperty.NullHandling;
import org.apache.fory.json.annotation.JsonRawValue;
import org.apache.fory.json.annotation.JsonUnwrapped;
import org.apache.fory.json.codec.AbstractJsonValueCodec;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonNullHandlingTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonNullHandlingTest(boolean codegen) {
    super(codegen);
  }

  private <T> T read(ForyJson json, String text, Class<T> type, int representation) {
    switch (representation) {
      case 0:
        return json.fromJson(text, type);
      case 1:
        return json.fromJson("{\"ignoredUnicode\":\"中\"," + text.substring(1), type);
      case 2:
        return json.fromJson(text.getBytes(StandardCharsets.UTF_8), type);
      default:
        return json.fromJson(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), type);
    }
  }

  @Test
  public void propertyAssignments() {
    ForyJson skip = newJsonBuilder().onNullRead(NullHandling.SKIP).build();
    for (int reader = 0; reader < 4; reader++) {
      Values value =
          read(
              skip,
              "{\"value\":null,\"optional\":null,\"raw\":null,\"count\":null}",
              Values.class,
              reader);
      assertEquals(value.value, "initial");
      assertEquals(value.calls, 0);
      assertEquals(value.optional, Optional.of("initial"));
      assertEquals(value.raw, "{}");
      assertEquals(value.count, 9);
      value = read(skip, "{\"value\":\"new\",\"value\":null}", Values.class, reader);
      assertEquals(value.value, "new");
      assertEquals(value.calls, 1);
      assertNull(read(skip, "{\"value\":\"decode-null\"}", Values.class, reader).value);
    }
    assertNull(skip.fromJson("null", Values.class));
    assertEquals(skip.fromJson("null", new TypeRef<Optional<String>>() {}), Optional.empty());
    assertThrows(
        ForyJsonException.class, () -> newJson().fromJson("{\"count\":null}", Values.class));
  }

  @Test
  public void creatorPresence() {
    ForyJson skip = newJsonBuilder().onNullRead(NullHandling.SKIP).build();
    ForyJson strict =
        newJsonBuilder()
            .onNullRead(NullHandling.SKIP)
            .failOnMissingRequiredProperties(true)
            .build();
    for (int reader = 0; reader < 4; reader++) {
      assertNull(read(skip, "{\"value\":null}", Created.class, reader).value);
      assertEquals(
          read(strict, "{\"value\":\"new\",\"value\":null}", Created.class, reader).value, "new");
      int representation = reader;
      assertThrows(
          ForyJsonException.class,
          () -> read(strict, "{\"value\":null}", Created.class, representation));
      assertEquals(read(strict, "{\"value\":\"ok\"}", Created.class, reader).value, "ok");
    }
  }

  @Test
  public void factoryArguments() {
    ForyJson json = newJsonBuilder().onNullRead(NullHandling.FAIL).build();
    ForyJson strict = newJsonBuilder().failOnMissingRequiredProperties(true).build();
    for (int reader = 0; reader < 4; reader++) {
      assertNull(read(json, "{\"value\":null}", FactoryValue.class, reader).value);
      String duplicate = "{\"value\":\"kept\",\"value\":null}";
      assertEquals(read(strict, duplicate, FactoryValue.class, reader).value, "kept");
      int representation = reader;
      assertThrows(
          ForyJsonException.class,
          () -> read(strict, "{\"value\":null}", FactoryValue.class, representation));
    }
  }

  @Test
  public void shallowContentOverrides() {
    ForyJson json = newJsonBuilder().onContentNullRead(NullHandling.SKIP).build();
    String text =
        "{\"inherited\":[null,\"a\",null],\"set\":[null,\"b\"],"
            + "\"nested\":[null,[null,\"c\"]],\"map\":{\"a\":1,\"a\":null,\"b\":null},"
            + "\"optional\":[null,\"d\"],\"numbers\":[null,1,null,2]}";
    for (int reader = 0; reader < 4; reader++) {
      Containers value = read(json, text, Containers.class, reader);
      assertEquals(value.inherited, ImmutableList.of("a"));
      assertEquals(value.set, Arrays.asList(null, "b"));
      assertEquals(value.nested, ImmutableList.of(ImmutableList.of("c")));
      assertEquals(value.map, ImmutableMap.of("a", 1));
      assertEquals(value.optional, ImmutableList.of(Optional.of("d")));
      assertEquals(value.numbers, ImmutableSet.of(1, 2));
      assertEquals(
          read(json, "{\"inherited\":[null,null]}", Containers.class, reader).inherited,
          ImmutableList.of());
    }
    ForyJson defaults = newJson();
    Containers outerOnly = defaults.fromJson("{\"nested\":[null,[null,\"c\"]]}", Containers.class);
    assertEquals(outerOnly.nested, ImmutableList.of(Arrays.asList(null, "c")));
    assertEquals(
        defaults.fromJson("{\"inherited\":[null]}", Containers.class).inherited,
        Arrays.asList((String) null));
    ForyJson fail = newJsonBuilder().onContentNullRead(NullHandling.FAIL).build();
    for (int reader = 0; reader < 4; reader++) {
      int representation = reader;
      assertThrows(
          ForyJsonException.class,
          () -> read(fail, "{\"inherited\":[null]}", Containers.class, representation));
      assertEquals(
          read(fail, "{\"set\":[null]}", Containers.class, reader).set,
          Arrays.asList((String) null));
      assertEquals(
          read(fail, "{\"inherited\":[\"ok\"]}", Containers.class, reader).inherited,
          ImmutableList.of("ok"));
    }
  }

  @Test
  public void arrayContents() throws Exception {
    ForyJson skip = newJsonBuilder().onContentNullRead(NullHandling.SKIP).build();
    String text =
        "{\"ints\":[null,1,null,2],\"longs\":[null,1,null,2],"
            + "\"shorts\":[null,1,null,2],\"bytes\":[null,1,null,2],"
            + "\"floats\":[null,1,null,2],\"doubles\":[null,1,null,2],"
            + "\"booleans\":[null,true,null,false],\"chars\":[null,\"a\",null,\"b\"],"
            + "\"strings\":[null,\"a\",null,\"b\"],\"boxedInts\":[null,1,null,2],"
            + "\"boxedLongs\":[null,1,null,2],\"boxedShorts\":[null,1,null,2],"
            + "\"boxedBytes\":[null,1,null,2],\"boxedFloats\":[null,1,null,2],"
            + "\"boxedDoubles\":[null,1,null,2],\"boxedBooleans\":[null,true,null,false],"
            + "\"boxedChars\":[null,\"a\",null,\"b\"],\"objects\":[null,{\"id\":1},null,{\"id\":2}]}";
    for (int reader = 0; reader < 4; reader++) {
      ArraysValue value = read(skip, text, ArraysValue.class, reader);
      for (java.lang.reflect.Field field : ArraysValue.class.getFields()) {
        Object array = field.get(value);
        assertEquals(Array.getLength(array), 2, field.getName());
      }
      assertEquals(value.ints, new int[] {1, 2});
      assertEquals(value.longs, new long[] {1, 2});
      assertEquals(value.bytes, new byte[] {1, 2});
      assertEquals(value.strings, new String[] {"a", "b"});
      assertEquals(value.objects[1].id, 2);
      String allNull = "{\"longs\":[null,null],\"strings\":[null,null],\"ints\":[null,null]}";
      value = read(skip, allNull, ArraysValue.class, reader);
      assertEquals(value.longs.length, 0);
      assertEquals(value.strings.length, 0);
      assertEquals(value.ints.length, 0);
    }
    // Exercise the prefix boundary and batched tails of specialized readers.
    for (int size : new int[] {1, 8, 9, 16, 1024, 1025}) {
      StringBuilder input = new StringBuilder("[null");
      for (int i = 0; i < size; i++) input.append(",1,null");
      input.append(']');
      assertEquals(skip.fromJson(input.toString(), long[].class).length, size);
      assertEquals(
          skip.fromJson(input.toString().getBytes(StandardCharsets.UTF_8), int[].class).length,
          size);
    }
  }

  @Test
  public void rootContainers() {
    ForyJson skip = newJsonBuilder().onContentNullRead(NullHandling.SKIP).build();
    assertEquals(
        skip.fromJson("[null,\"a\",null]", new TypeRef<List<String>>() {}), ImmutableList.of("a"));
    assertEquals(
        skip.fromJson(
            "{\"1\":\"a\",\"1\":null,\"2\":null}", new TypeRef<Map<Integer, String>>() {}),
        ImmutableMap.of(1, "a"));
    assertEquals(
        skip.fromJson("{\"x\":[null,1,null],\"y\":null}", Object.class),
        ImmutableMap.of("x", ImmutableList.of(1L)));
    assertEquals(skip.fromJson("[null,\"null\"]", Object.class), ImmutableList.of("null"));
    assertEquals(skip.fromJson("[null,1,null,2]", AtomicIntegerArray.class).length(), 2);
    assertEquals(skip.fromJson("[null,1,null,2]", AtomicLongArray.class).get(1), 2L);
    assertEquals(
        skip.fromJson("[null,\"a\",null]", new TypeRef<AtomicReferenceArray<String>>() {}).get(0),
        "a");
  }

  @Test
  public void unwrappedAndAny() {
    ForyJson skip =
        newJsonBuilder().onNullRead(NullHandling.SKIP).onContentNullRead(NullHandling.SKIP).build();
    for (int reader = 0; reader < 4; reader++) {
      assertNull(read(skip, "{\"value\":null}", Unwrapped.class, reader).child);
      assertEquals(
          read(skip, "{\"value\":\"ok\",\"value\":null}", Unwrapped.class, reader).child.value,
          "ok");
      // Ignore the UTF-16 marker itself when checking Any setter calls.
      AnyValues value = read(skip, "{\"x\":null,\"y\":1}", AnyValues.class, reader);
      assertEquals(value.calls, reader == 1 ? 2 : 1);
      assertFalse(value.sawNull);
      AnyMap map = read(skip, "{\"x\":null}", AnyMap.class, reader);
      if (reader == 1) assertFalse(map.values.containsKey("x"));
      else assertNull(map.values);
      CreatedAny created = read(skip, "{\"x\":1,\"x\":null,\"y\":null}", CreatedAny.class, reader);
      assertEquals(created.values.get("x"), Long.valueOf(1));
      assertFalse(created.values.containsKey("y"));
    }
  }

  @Test
  public void mixinNullOverrides() {
    ForyJson replace = newJsonBuilder().registerMixin(SetMixin.class).build();
    ForyJson remove =
        newJsonBuilder().registerMixin(RemoveMixin.class).onNullRead(NullHandling.FAIL).build();
    for (int reader = 0; reader < 4; reader++) {
      assertEquals(read(newJson(), "{\"value\":null}", MixinValues.class, reader).value, "initial");
      assertNull(read(replace, "{\"value\":null}", MixinValues.class, reader).value);
      int representation = reader;
      assertThrows(
          ForyJsonException.class,
          () -> read(remove, "{\"value\":null}", MixinValues.class, representation));
    }
  }

  @Test
  public void specializedContents() {
    ForyJson skip = newJsonBuilder().onContentNullRead(NullHandling.SKIP).build();
    for (int reader = 0; reader < 4; reader++) {
      SpecialContents value =
          read(
              skip,
              "{\"enums\":[null,\"A\",null],\"ints\":[null,\"1\",null],"
                  + "\"guava\":[null,1,null,2],\"atomic\":[null,1,null]}",
              SpecialContents.class,
              reader);
      assertEquals(value.enums, new Kind[] {Kind.A});
      assertEquals(value.ints, new int[] {1});
      assertEquals(value.guava.toArray(), new int[] {1, 2});
      assertEquals(value.atomic.length(), 1);
    }
  }

  @Test
  public void customChildAndInvalidTargets() {
    ForyJson json = newJsonBuilder().onContentNullRead(NullHandling.SKIP).build();
    for (int reader = 0; reader < 4; reader++) {
      CustomContents value =
          read(json, "{\"values\":[null,\"decode-null\",\"a\"]}", CustomContents.class, reader);
      assertEquals(value.values, Arrays.asList(null, "a"));
    }
    for (Class<?> type :
        new Class<?>[] {
          ScalarContent.class,
          BinaryContent.class,
          UnwrappedContent.class,
          IgnoredRead.class,
          Conflict.class,
          OpaqueContent.class
        }) {
      assertThrows(ForyJsonException.class, () -> json.fromJson("{}", type));
    }
    assertEquals(
        json.fromJson("{\"values\":[null]}", OpaqueSet.class).values, Arrays.asList((String) null));
  }

  @Test
  public void builderSnapshotsAndFailureCleanup() {
    ForyJsonBuilder builder = newJsonBuilder().onNullRead(NullHandling.SKIP);
    ForyJson skip = builder.build();
    ForyJson fail = builder.onNullRead(NullHandling.FAIL).build();
    assertEquals(skip.fromJson("{\"count\":null}", Values.class).count, 9);
    for (String input : new String[] {"{\"count\":null}", "{\"count\":nul}", "{\"count\":nullx}"}) {
      assertThrows(ForyJsonException.class, () -> fail.fromJson(input, Values.class));
      assertEquals(fail.fromJson("{\"count\":3}", Values.class).count, 3);
    }
    assertThrows(NullPointerException.class, () -> builder.onNullRead(null));
    assertThrows(NullPointerException.class, () -> builder.onContentNullRead(null));
  }

  public static class Values {
    private String value = "initial";
    @JsonIgnore public int calls;
    public int count = 9;
    public Optional<String> optional = Optional.of("initial");
    @JsonRawValue public String raw = "{}";

    public void setValue(String value) {
      this.value = value;
      calls++;
    }

    @JsonCodec(NullStringCodec.class)
    public String getValue() {
      return value;
    }
  }

  public static class Created {
    public final String value;

    @JsonCreator
    public Created(@JsonProperty("value") String value) {
      this.value = value;
    }
  }

  public static class FactoryValue {
    public final String value;

    private FactoryValue(String value) {
      this.value = value;
    }

    @JsonCreator
    public static FactoryValue create(
        @JsonProperty(value = "value", onNullRead = NullHandling.SKIP) String value) {
      return new FactoryValue(value);
    }
  }

  public static class Containers {
    public List<String> inherited;

    @JsonProperty(onContentNullRead = NullHandling.SET)
    public List<String> set;

    @JsonProperty(onContentNullRead = NullHandling.SKIP)
    public List<List<String>> nested;

    public Map<String, Integer> map;
    public List<Optional<String>> optional;
    public Set<Integer> numbers;
  }

  public static class ArraysValue {
    public int[] ints;
    public long[] longs;
    public short[] shorts;

    @JsonByteArray(JsonByteArray.Format.ARRAY)
    public byte[] bytes;

    public float[] floats;
    public double[] doubles;
    public boolean[] booleans;
    public char[] chars;
    public String[] strings;
    public Integer[] boxedInts;
    public Long[] boxedLongs;
    public Short[] boxedShorts;
    public Byte[] boxedBytes;
    public Float[] boxedFloats;
    public Double[] boxedDoubles;
    public Boolean[] boxedBooleans;
    public Character[] boxedChars;
    public Item[] objects;
  }

  public static class Item {
    public int id;
  }

  public static class Child {
    public String value = "initial";
  }

  public static class Unwrapped {
    @JsonUnwrapped public Child child;
  }

  public static class AnyValues {
    @JsonIgnore public int calls;
    @JsonIgnore public boolean sawNull;

    @JsonAnySetter
    public void accept(String name, Object value) {
      calls++;
      sawNull |= value == null;
    }
  }

  public static class AnyMap {
    @JsonAnyProperty public Map<String, Object> values;
  }

  public static class CreatedAny {
    @JsonAnyProperty public final Map<String, Object> values;

    @JsonCreator({"values"})
    public CreatedAny(Map<String, Object> values) {
      this.values = values;
    }
  }

  public static class MixinValues {
    @JsonProperty(onNullRead = NullHandling.SKIP)
    public String value = "initial";
  }

  @JsonMixin(target = MixinValues.class)
  public abstract static class SetMixin {
    @JsonProperty(onNullRead = NullHandling.SET)
    public String value;
  }

  @JsonMixin(target = MixinValues.class)
  public abstract static class RemoveMixin {
    @JsonMixinRemove(JsonProperty.class)
    public String value;
  }

  public enum Kind {
    A
  }

  public static class SpecialContents {
    public Kind[] enums;

    @JsonCodec(elementCodec = StringIntCodec.class)
    public int[] ints;

    public com.google.common.primitives.ImmutableIntArray guava;
    public AtomicIntegerArray atomic;
  }

  public static class StringIntCodec extends AbstractJsonValueCodec<Integer> {
    @Override
    public void write(JsonWriter writer, Integer value) {
      writer.writeString(value.toString());
    }

    @Override
    public Integer read(JsonReader reader) {
      return Integer.valueOf(reader.readString());
    }
  }

  public static class CustomContents {
    @JsonCodec(elementCodec = NullStringCodec.class)
    public List<String> values;
  }

  public static class ScalarContent {
    @JsonProperty(onContentNullRead = NullHandling.SET)
    public String value;
  }

  public static class BinaryContent {
    @JsonProperty(onContentNullRead = NullHandling.SET)
    public byte[] value;
  }

  public static class UnwrappedContent {
    @JsonUnwrapped
    @JsonProperty(onNullRead = NullHandling.SKIP)
    public Child child;
  }

  public static class IgnoredRead {
    @JsonIgnore(ignoreRead = true, ignoreWrite = false)
    @JsonProperty(onNullRead = NullHandling.SKIP)
    public String value;
  }

  public static class Conflict {
    @JsonProperty(onNullRead = NullHandling.SKIP)
    public String value;

    @JsonProperty(onNullRead = NullHandling.FAIL)
    public void setValue(String value) {
      this.value = value;
    }
  }

  public static class OpaqueContent {
    @JsonCodec(StringListCodec.class)
    @JsonProperty(onContentNullRead = NullHandling.SKIP)
    public List<String> values;
  }

  public static class OpaqueSet {
    @JsonCodec(StringListCodec.class)
    @JsonProperty(onContentNullRead = NullHandling.SET)
    public List<String> values;
  }

  public static class NullStringCodec extends AbstractJsonValueCodec<String> {
    @Override
    public void write(JsonWriter writer, String value) {
      writer.writeString(value);
    }

    @Override
    public String read(JsonReader reader) {
      String value = reader.readString();
      return "decode-null".equals(value) ? null : value;
    }
  }

  public static class StringListCodec extends AbstractJsonValueCodec<List<String>> {
    @Override
    public void write(JsonWriter writer, List<String> value) {
      writer.writeArrayStart();
      writer.writeNull();
      writer.writeArrayEnd();
    }

    @Override
    public List<String> read(JsonReader reader) {
      reader.expect('[');
      reader.readNull();
      reader.expect(']');
      return Arrays.asList((String) null);
    }
  }
}
