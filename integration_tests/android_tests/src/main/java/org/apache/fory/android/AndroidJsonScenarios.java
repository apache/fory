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

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.annotation.JsonUnwrapped;
import org.apache.fory.json.annotation.JsonValue;

/** Android acceptance scenarios for reflection, declaration codecs, and R8 retention. */
public final class AndroidJsonScenarios {
  private AndroidJsonScenarios() {}

  public static void plainReflectionWithoutRules(boolean debuggable) {
    if (!debuggable) {
      return;
    }
    ForyJson json = ForyJson.builder().build();
    ReflectionJsonModel value = new ReflectionJsonModel(26, "reflection");
    String encoded = json.toJson(value);
    checkEquals("{\"id\":26,\"name\":\"reflection\"}", encoded);
    ReflectionJsonModel decoded = json.fromJson(encoded, ReflectionJsonModel.class);
    checkEquals(26, decoded.id);
    checkEquals("reflection", decoded.name);
  }

  public static void manualPlainRules() {
    ForyJson json = ForyJson.builder().build();
    ManualPlainJsonModel value = new ManualPlainJsonModel(27, "manual-plain");
    String encoded = json.toJson(value);
    checkEquals("{\"id\":27,\"name\":\"manual-plain\"}", encoded);
    ManualPlainJsonModel decoded = json.fromJson(encoded, ManualPlainJsonModel.class);
    checkEquals(27, decoded.id);
    checkEquals("manual-plain", decoded.name);
  }

  public static void generatedPlainRules() {
    ForyJson json = ForyJson.builder().build();
    GeneratedPlainJsonModel value = new GeneratedPlainJsonModel(28, "generated-plain");
    String encoded = json.toJson(value);
    checkEquals("{\"id\":28,\"name\":\"generated-plain\"}", encoded);
    GeneratedPlainJsonModel decoded = json.fromJson(encoded, GeneratedPlainJsonModel.class);
    checkEquals(28, decoded.id);
    checkEquals("generated-plain", decoded.name);
  }

  public static void generatedRecord() {
    try {
      Class.forName(
          "org.apache.fory.android.GeneratedJsonRecord_ForyJsonCodec",
          false,
          GeneratedJsonRecord.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new AssertionError("generated Record JSON codec was removed", e);
    }
    check(GeneratedJsonRecord.class.isAnnotationPresent(JsonType.class));
    ForyJson json = ForyJson.builder().build();
    String encoded = json.toJson(new GeneratedJsonRecord(30, "android"));
    checkEquals("{\"id\":30,\"name\":\"ANDROID\"}", encoded);
    GeneratedJsonRecord decoded = json.fromJson(encoded, GeneratedJsonRecord.class);
    checkEquals(30, decoded.id());
    checkEquals("ANDROID", decoded.name());
  }

  public static void generatedValueRecord() {
    try {
      Class.forName(
          "org.apache.fory.android.GeneratedJsonValueRecord_ForyJsonCodec",
          false,
          GeneratedJsonValueRecord.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new AssertionError("generated value Record JSON codec was removed", e);
    }
    ForyJson json = ForyJson.builder().build();
    GeneratedJsonValueRecord value = new GeneratedJsonValueRecord("android-value");
    checkEquals("\"android-value\"", json.toJson(value));
    checkEquals(
        "decoded-value",
        json.fromJson("\"decoded-value\"", GeneratedJsonValueRecord.class).value());
  }

  public static void manualCodecs() {
    ForyJson json = ForyJson.builder().build();
    ManualJsonModel value = new ManualJsonModel();
    value.direct = new ManualJsonModel.DirectValue("direct");
    value.declaredField = new ManualJsonModel.MemberValue("field");
    value.setDeclaredProperty(new ManualJsonModel.MemberValue("method"));
    value.setParameterProperty(new ManualJsonModel.MemberValue("parameter"));
    value.values.add(new ManualJsonModel.MemberValue("element"));
    value.array = new ManualJsonModel.MemberValue[] {new ManualJsonModel.MemberValue("array")};
    value.atomicArray =
        new AtomicReferenceArray<>(
            new ManualJsonModel.MemberValue[] {new ManualJsonModel.MemberValue("atomic-array")});
    value.byName.put(new ManualJsonModel.Key("map"), new ManualJsonModel.MemberValue("map-value"));
    value.optional = Optional.of(new ManualJsonModel.MemberValue("optional"));
    value.atomic = new AtomicReference<>(new ManualJsonModel.MemberValue("atomic"));
    value.extra.put("dynamic", new ManualJsonModel.MemberValue("any"));

    ManualJsonModel.resetCodecCalls();
    String encoded = json.toJson(value);
    check(encoded.contains("\"direct\":\"manual:direct\""));
    check(encoded.contains("\"declaredField\":\"manual:field\""));
    check(encoded.contains("\"declaredProperty\":\"manual:method\""));
    check(encoded.contains("\"parameterProperty\":\"manual:parameter\""));
    check(encoded.contains("\"values\":[\"manual:element\"]"));
    check(encoded.contains("\"array\":[\"manual:array\"]"));
    check(encoded.contains("\"atomicArray\":[\"manual:atomic-array\"]"));
    check(encoded.contains("\"manual-key:map\":\"manual:map-value\""));
    check(encoded.contains("\"optional\":\"manual:optional\""));
    check(encoded.contains("\"atomic\":\"manual:atomic\""));
    check(encoded.contains("\"dynamic\":\"manual:any\""));

    ManualJsonModel decoded = json.fromJson(encoded, ManualJsonModel.class);
    checkEquals("manual:direct", decoded.direct.text);
    checkEquals("manual:field", decoded.declaredField.text);
    checkEquals("manual:method", decoded.getDeclaredProperty().text);
    checkEquals("manual:parameter", decoded.getParameterProperty().text);
    checkEquals("manual:element", decoded.values.get(0).text);
    checkEquals("manual:array", decoded.array[0].text);
    checkEquals("manual:atomic-array", decoded.atomicArray.get(0).text);
    checkEquals("manual:map-value", decoded.byName.get(new ManualJsonModel.Key("map")).text);
    checkEquals("manual:optional", decoded.optional.get().text);
    checkEquals("manual:atomic", decoded.atomic.get().text);
    checkEquals("manual:any", decoded.extra.get("dynamic").text);
    checkEquals(22, ManualJsonModel.codecCalls());
    checkEquals(2, ManualJsonModel.keyCodecCalls());
  }

  public static void generatedCodecs() {
    ForyJson json = ForyJson.builder().build();
    GeneratedJsonSubtype value = new GeneratedJsonSubtype();
    value.subtypeId = 29;
    value.values.add(new GeneratedJsonModel.Value("list"));
    value.rootValue = new GeneratedJsonModel.Value("root");
    value.setRootProperty(new GeneratedJsonModel.Value("property"));
    value.setParameterProperty(new GeneratedJsonModel.Value("parameter"));
    value.array = new GeneratedJsonModel.Value[] {new GeneratedJsonModel.Value("array")};
    value.atomicArray =
        new AtomicReferenceArray<>(
            new GeneratedJsonModel.Value[] {new GeneratedJsonModel.Value("atomic-array")});
    value.byName.put(new GeneratedJsonModel.Key("map"), new GeneratedJsonModel.Value("map-value"));
    value.optional = Optional.of(new GeneratedJsonModel.Value("optional"));
    value.atomic = new AtomicReference<>(new GeneratedJsonModel.Value("atomic"));
    value.declared = new GeneratedJsonModel.DeclaredValue("type");
    value.setInheritedProperty(new GeneratedJsonModel.InheritedPropertyValue("property"));
    value.extra.put("dynamic", new GeneratedJsonModel.Value("extra"));

    GeneratedJsonModel.resetCodecCalls();
    String encoded = json.toJson(value, GeneratedJsonModel.class);
    check(encoded.contains("\"kind\":\"generated\""));
    check(encoded.contains("\"generated:list\""));
    check(encoded.contains("\"generated:root\""));
    check(encoded.contains("\"generated:property\""));
    check(encoded.contains("\"generated:parameter\""));
    check(encoded.contains("\"generated:array\""));
    check(encoded.contains("\"generated:atomic-array\""));
    check(encoded.contains("\"generated-key:map\":\"generated:map-value\""));
    check(encoded.contains("\"generated:optional\""));
    check(encoded.contains("\"generated:atomic\""));
    check(encoded.contains("\"declared:type\""));
    check(encoded.contains("\"inherited:property\""));
    check(encoded.contains("\"dynamic\":\"generated:extra\""));

    GeneratedJsonModel decoded = json.fromJson(encoded, GeneratedJsonModel.class);
    check(decoded instanceof GeneratedJsonSubtype);
    GeneratedJsonSubtype subtype = (GeneratedJsonSubtype) decoded;
    checkEquals(29, subtype.subtypeId);
    checkEquals("generated:list", subtype.values.get(0).text);
    checkEquals("generated:root", subtype.rootValue.text);
    checkEquals("generated:property", subtype.getRootProperty().text);
    checkEquals("generated:parameter", subtype.getParameterProperty().text);
    checkEquals("generated:array", subtype.array[0].text);
    checkEquals("generated:atomic-array", subtype.atomicArray.get(0).text);
    checkEquals("generated:map-value", subtype.byName.get(new GeneratedJsonModel.Key("map")).text);
    checkEquals("generated:optional", subtype.optional.get().text);
    checkEquals("generated:atomic", subtype.atomic.get().text);
    checkEquals("declared:type", subtype.declared.text);
    checkEquals("inherited:property", subtype.getInheritedProperty().text);
    checkEquals("generated:extra", subtype.extra.get("dynamic").text);

    GeneratedJsonModel.CreatedValue created =
        new GeneratedJsonModel.CreatedValue(
            Arrays.asList(new GeneratedJsonModel.Value("creator")),
            new GeneratedJsonModel.Value("direct"));
    String creatorJson = json.toJson(created);
    check(creatorJson.contains("\"values\":[\"generated:creator\"]"));
    check(creatorJson.contains("\"direct\":\"generated:direct\""));
    GeneratedJsonModel.CreatedValue decodedCreated =
        json.fromJson(creatorJson, GeneratedJsonModel.CreatedValue.class);
    checkEquals("generated:creator", decodedCreated.values.get(0).text);
    checkEquals("generated:direct", decodedCreated.direct.text);
    checkEquals(28, GeneratedJsonModel.codecCalls());
    checkEquals(2, GeneratedJsonModel.keyCodecCalls());
  }

  public static void generatedUnwrapped() {
    ForyJson json = ForyJson.builder().build();
    UnwrappedParent value = new UnwrappedParent(30, new UnwrappedChild("android", 31));
    String encoded = json.toJson(value);
    checkEquals("{\"id\":30,\"child_name\":\"android\",\"child_rank\":31}", encoded);
    UnwrappedParent decoded = json.fromJson(encoded, UnwrappedParent.class);
    checkEquals(30, decoded.id);
    checkEquals("android", decoded.child.name);
    checkEquals(31, decoded.child.rank);

    UnwrappedRecord record = new UnwrappedRecord(32, new UnwrappedRecordChild("record", 33));
    String recordJson = json.toJson(record);
    checkEquals("{\"id\":32,\"child_name\":\"record\",\"child_rank\":33}", recordJson);
    UnwrappedRecord decodedRecord = json.fromJson(recordJson, UnwrappedRecord.class);
    checkEquals(32, decodedRecord.id());
    checkEquals("record", decodedRecord.child().name());
    checkEquals(33, decodedRecord.child().rank());
  }

  public static void generatedMixin() {
    GeneratedJsonMixinTarget value =
        GeneratedJsonMixinTarget.create(
            34, new GeneratedJsonMixinTarget.Address("Hangzhou", 310000));
    String direct = ForyJson.builder().build().toJson(value);
    check(direct.contains("\"id\":34"));
    check(direct.contains("\"address\":{"));

    ForyJson json = ForyJson.builder().registerMixin(GeneratedJsonMixin.class).build();
    String encoded = json.toJson(value);
    checkEquals("{\"user_id\":34,\"address_city\":\"Hangzhou\",\"address_zip\":310000}", encoded);
    GeneratedJsonMixinTarget decoded = json.fromJson(encoded, GeneratedJsonMixinTarget.class);
    checkEquals(34, decoded.getId());
    checkEquals("Hangzhou", decoded.getAddress().city);
    checkEquals(310000, decoded.getAddress().zip);

    generatedMixinRecord();
    generatedMixinValue();
    generatedMixinValueRecord();
  }

  private static void generatedMixinRecord() {
    ForyJson json =
        ForyJson.builder().registerMixin(GeneratedJsonMixinRecordAnnotations.class).build();
    String encoded = json.toJson(new GeneratedJsonMixinRecord(35, "record-mixin"));
    checkEquals("{\"user_id\":35,\"display_name\":\"record-mixin\"}", encoded);
    GeneratedJsonMixinRecord decoded = json.fromJson(encoded, GeneratedJsonMixinRecord.class);
    checkEquals(35, decoded.id());
    checkEquals("record-mixin", decoded.name());
  }

  private static void generatedMixinValue() {
    ForyJson json = ForyJson.builder().registerMixin(GeneratedJsonMixinValue.class).build();
    GeneratedJsonMixinValueTarget value = GeneratedJsonMixinValueTarget.create("value-mixin");
    checkEquals("\"value-mixin\"", json.toJson(value));
    GeneratedJsonMixinValueTarget decoded =
        json.fromJson("\"decoded-mixin\"", GeneratedJsonMixinValueTarget.class);
    checkEquals("decoded-mixin", decoded.getValue());
  }

  private static void generatedMixinValueRecord() {
    ForyJson json =
        ForyJson.builder().registerMixin(GeneratedJsonMixinValueRecordAnnotations.class).build();
    checkEquals("\"record-value\"", json.toJson(new GeneratedJsonMixinValueRecord("record-value")));
    GeneratedJsonMixinValueRecord decoded =
        json.fromJson("\"decoded-record\"", GeneratedJsonMixinValueRecord.class);
    checkEquals("decoded-record", decoded.value());
  }

  @JsonType
  @JsonPropertyOrder({"id", "child"})
  public static final class UnwrappedParent {
    public final int id;

    @JsonUnwrapped(prefix = "child_")
    public final UnwrappedChild child;

    @JsonCreator({"id", "child"})
    public UnwrappedParent(int id, UnwrappedChild child) {
      this.id = id;
      this.child = child;
    }
  }

  @JsonType
  public static final class UnwrappedChild {
    public final String name;
    public final int rank;

    @JsonCreator({"name", "rank"})
    public UnwrappedChild(String name, int rank) {
      this.name = name;
      this.rank = rank;
    }
  }

  @JsonType
  @JsonPropertyOrder({"id", "child"})
  public record UnwrappedRecord(
      int id, @JsonUnwrapped(prefix = "child_") UnwrappedRecordChild child) {}

  @JsonType
  public record UnwrappedRecordChild(String name, int rank) {}

  @JsonMixin(target = GeneratedJsonMixinTarget.class)
  @JsonPropertyOrder({"id", "address"})
  public interface GeneratedJsonMixin {
    @JsonProperty("user_id")
    int getId();

    @JsonUnwrapped(prefix = "address_")
    GeneratedJsonMixinTarget.Address getAddress();

    @JsonCreator({"id", "address"})
    GeneratedJsonMixinTarget create(int id, GeneratedJsonMixinTarget.Address address);
  }

  @JsonType
  public static final class GeneratedJsonMixinTarget {
    private final int id;
    private final Address address;

    private GeneratedJsonMixinTarget(int id, Address address) {
      this.id = id;
      this.address = address;
    }

    public int getId() {
      return id;
    }

    public Address getAddress() {
      return address;
    }

    public static GeneratedJsonMixinTarget create(int id, Address address) {
      return new GeneratedJsonMixinTarget(id, address);
    }

    public static final class Address {
      public String city;
      public int zip;

      public Address() {}

      public Address(String city, int zip) {
        this.city = city;
        this.zip = zip;
      }
    }
  }

  public record GeneratedJsonMixinRecord(int id, String name) {}

  @JsonMixin(target = GeneratedJsonMixinRecord.class)
  @JsonPropertyOrder({"id", "name"})
  public abstract static class GeneratedJsonMixinRecordAnnotations {
    @JsonProperty("user_id")
    int id;

    @JsonProperty("display_name")
    String name;

    @JsonProperty("user_id")
    abstract int id();

    @JsonProperty("display_name")
    abstract String name();

    GeneratedJsonMixinRecordAnnotations(
        @JsonProperty("user_id") int id, @JsonProperty("display_name") String name) {}
  }

  @JsonMixin(target = GeneratedJsonMixinValueTarget.class)
  public interface GeneratedJsonMixinValue {
    @JsonValue
    String getValue();

    @JsonCreator
    GeneratedJsonMixinValueTarget create(String value);
  }

  public static final class GeneratedJsonMixinValueTarget {
    private final String value;

    private GeneratedJsonMixinValueTarget(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }

    public static GeneratedJsonMixinValueTarget create(String value) {
      return new GeneratedJsonMixinValueTarget(value);
    }
  }

  public record GeneratedJsonMixinValueRecord(String value) {}

  @JsonMixin(target = GeneratedJsonMixinValueRecord.class)
  public abstract static class GeneratedJsonMixinValueRecordAnnotations {
    @JsonValue String value;

    @JsonValue
    abstract String value();

    @JsonCreator
    GeneratedJsonMixinValueRecordAnnotations(String value) {}
  }

  private static void check(boolean condition) {
    if (!condition) {
      throw new AssertionError("check failed");
    }
  }

  private static void checkEquals(Object expected, Object actual) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError("expected " + expected + " but got " + actual);
    }
  }
}
