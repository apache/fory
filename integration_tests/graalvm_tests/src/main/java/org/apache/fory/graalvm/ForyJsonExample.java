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

package org.apache.fory.graalvm;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.Lock;
import org.apache.fory.codegen.CompileState;
import org.apache.fory.graalvm.closed.ClosedJsonConfigs;
import org.apache.fory.graalvm.closed.ClosedJsonRecord;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.ForyJsonProvider;
import org.apache.fory.json.annotation.JsonAnyGetter;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonAnySetter;
import org.apache.fory.json.annotation.JsonByteArray;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonFormat;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;
import org.apache.fory.json.annotation.JsonRawValue;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.annotation.JsonUnwrapped;
import org.apache.fory.json.annotation.JsonValidator;
import org.apache.fory.json.annotation.JsonValue;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.MapKeyCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.serializer.GraphMemoryEstimates;
import org.apache.fory.util.Preconditions;

/** Native-image acceptance coverage for hosted code generation and interpreter fallback. */
public final class ForyJsonExample {
  // Portable lower bound: the 8-byte object base plus one 4-byte int field.
  private static final long GRAPH_BUDGET_VALUE_BYTES = 12;
  private static final int REF_BYTES = GraphMemoryEstimates.REFERENCE_BYTES;
  private static final ForyJson DEFAULT_JSON = ForyJson.builder().withConcurrencyLevel(1).build();

  private ForyJsonExample() {}

  public static void main(String[] args) {
    Preconditions.checkArgument(
        ClosedJsonConfigs.class.isAnnotationPresent(ForyJsonProvider.class));
    if (GraalvmSupport.isGraalRuntime()) {
      testHostedCodegenConfigurations();
    }
    testModels();
    testConfigurations();
    testCodecs();
    testValueAnnotations();
    testSubtypes();
    testSubtypeMixin();
    testContainerRoots();
    testGenericProperties();
    testUnwrapped();
    testValidator();
    testGraphMemoryBudget();
    testContainerGraphBudget();
    testSpecialContainerBudget();
    testMixin();
    testMixinValue();
    testMixinValueRecord();
    testMixinEnumValue();
    testMixinCodec();
    testBigDecimal();
    testSqlTypes();
    testFormatTimezone();
    testClosedPackage();
    System.out.println("Fory JSON succeed");
  }

  private static void testHostedCodegenConfigurations() {
    ForyJson providerJson = newProviderJson();
    ForyJson interpretedJson = newInterpretedJson();
    exerciseCodegenConfiguration(DEFAULT_JSON, true, true);
    exerciseCodegenConfiguration(providerJson, true, true);
    exerciseCodegenConfiguration(interpretedJson, false, true);
    testRegisteredCodec(providerJson);
    testEmptyMixin(providerJson, true, true);
    testEmptyMixin(interpretedJson, false, true);
    testInterpretedMetadata(interpretedJson);
    testPrimitiveProperties(interpretedJson);
    testIndependentChildCodegen();
    testExternalModuleMixin();
    testBootstrapMixin(providerJson);
  }

  private static ForyJson newProviderJson() {
    return ForyJson.builder()
        .writeNullFields(true)
        .withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
        .registerCodec(CodegenProbeValue.class, new CodegenProbeCodec())
        .registerMixin(CoreCompileStateMixin.class)
        .registerMixin(EmptyMixin.class)
        .registerMixin(SealedShapeMixin.class)
        .registerMixin(StackTraceElementMixin.class)
        .build();
  }

  private static void testBootstrapMixin(ForyJson json) {
    StackTraceElement value = new StackTraceElement("Owner", "method", "Owner.java", 12);
    String encoded = json.toJson(value);
    Preconditions.checkArgument(encoded.contains("Owner") && encoded.contains("method"));
  }

  private static ForyJson newInterpretedJson() {
    return ForyJson.builder()
        .withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
        .registerCodec(CodegenProbeValue.class, new CodegenProbeCodec())
        .registerMixin(EmptyMixin.class)
        .registerMixin(InterpretedMixin.class)
        .build();
  }

  private static void testEmptyMixin(
      ForyJson json, boolean writerGenerated, boolean readerGenerated) {
    CodegenProbeCodec.expect(EmptyMixinTarget.class, writerGenerated, readerGenerated);
    EmptyMixinTarget value = new EmptyMixinTarget();
    value.probe = new CodegenProbeValue("empty-mixin");
    String encoded = json.toJson(value);
    Preconditions.checkArgument(
        json.fromJson(encoded, EmptyMixinTarget.class).probe.value.equals("empty-mixin"));
  }

  private static void testInterpretedMetadata(ForyJson json) {
    InterpretedMixinTarget mixinValue = new InterpretedMixinTarget();
    mixinValue.setName("empty-mixin");
    String mixinJson = json.toJson(mixinValue);
    Preconditions.checkArgument(
        json.fromJson(mixinJson, InterpretedMixinTarget.class).getName().equals("empty-mixin"));

    InterpretedBean bean = new InterpretedBean();
    bean.setName("bean");
    bean.putExtra("dynamic", "extra");
    InterpretedBean decoded = json.fromJson(json.toJson(bean), InterpretedBean.class);
    Preconditions.checkArgument(decoded.getName().equals("bean"));
    Preconditions.checkArgument(decoded.extra().equals(Map.of("dynamic", "extra")));

    DirectValueRecord record = new DirectValueRecord("record-value");
    Preconditions.checkArgument(json.toJson(record).equals("\"record-value\""));
    Preconditions.checkArgument(
        json.fromJson("\"decoded-record\"", DirectValueRecord.class)
            .equals(new DirectValueRecord("decoded-record")));
    Preconditions.checkArgument(json.toJson(DirectValueEnum.READY).equals("\"ready\""));
    Preconditions.checkArgument(
        json.fromJson("\"done\"", DirectValueEnum.class) == DirectValueEnum.DONE);

    ValidatedValue validated = json.fromJson("{\"value\":22}", ValidatedValue.class);
    Preconditions.checkArgument(validated.value == 22);
    Preconditions.checkArgument(validated.validatorInvoked());
  }

  private static void testPrimitiveProperties(ForyJson json) {
    PrimitiveProperties value = new PrimitiveProperties();
    value.setBooleanValue(true);
    value.setByteValue((byte) 12);
    value.setShortValue((short) 1234);
    value.setIntValue(123456);
    value.setLongValue(123456789L);
    value.setFloatValue(12.5f);
    value.setDoubleValue(123.25d);
    value.setCharValue('\u4f60');
    PrimitiveProperties decoded = json.fromJson(json.toJson(value), PrimitiveProperties.class);
    Preconditions.checkArgument(decoded.isBooleanValue());
    Preconditions.checkArgument(decoded.getByteValue() == 12);
    Preconditions.checkArgument(decoded.getShortValue() == 1234);
    Preconditions.checkArgument(decoded.getIntValue() == 123456);
    Preconditions.checkArgument(decoded.getLongValue() == 123456789L);
    Preconditions.checkArgument(decoded.getFloatValue() == 12.5f);
    Preconditions.checkArgument(decoded.getDoubleValue() == 123.25d);
    Preconditions.checkArgument(decoded.getCharValue() == '\u4f60');
  }

  private static void exerciseCodegenConfiguration(
      ForyJson json, boolean writerGenerated, boolean readerGenerated) {
    CodegenProbeCodec.expect(CodegenProbeModel.class, writerGenerated, readerGenerated);
    CodegenProbeModel value = new CodegenProbeModel();
    value.id = 41;
    value.probe = new CodegenProbeValue("probe");
    value.children.add(new CodegenProbeChild("child"));
    String encoded = json.toJson(value);
    Preconditions.checkArgument(encoded.contains("probe"));
    String utf8 = new String(json.toJsonBytes(value), StandardCharsets.UTF_8);
    Preconditions.checkArgument(utf8.contains("probe"));
    Preconditions.checkArgument(
        json.fromJson(encoded, CodegenProbeModel.class).probe.value.equals("probe"));
    Preconditions.checkArgument(
        json.fromJson(encoded, CodegenProbeModel.class).children.get(0).name.equals("child"));
    String utf16 = encoded.replace(":\"probe\"", ":\"\u4f60\"");
    Preconditions.checkArgument(
        json.fromJson(utf16, CodegenProbeModel.class).probe.value.equals("\u4f60"));
    Preconditions.checkArgument(
        json.fromJson(utf8.getBytes(StandardCharsets.UTF_8), CodegenProbeModel.class)
            .probe
            .value
            .equals("probe"));
  }

  private static void testRegisteredCodec(ForyJson json) {
    CodegenProbeCodec.expect(RegisteredCodecModel.class, true);
    RegisteredCodecModel value = new RegisteredCodecModel();
    value.probe = new CodegenProbeValue("registered");
    String encoded = json.toJson(value);
    Preconditions.checkArgument(encoded.equals("{\"probe\":\"registered\"}"));
    Preconditions.checkArgument(
        json.fromJson(encoded, RegisteredCodecModel.class).probe.value.equals("registered"));
  }

  private static void testClosedPackage() {
    ClosedJsonRecord value = new ClosedJsonRecord(17, "closed");
    ForyJson interpreted = ForyJson.builder().build();
    String interpretedJson = interpreted.toJson(value);
    Preconditions.checkArgument(
        interpreted.fromJson(interpretedJson, ClosedJsonRecord.class).equals(value));

    ForyJson generated = newProviderJson();
    String generatedJson = generated.toJson(value);
    Preconditions.checkArgument(
        generated.fromJson(generatedJson, ClosedJsonRecord.class).equals(value));
  }

  private static void testIndependentChildCodegen() {
    CodegenProbeCodec.expect(PublicChild.class, true);
    ForyJson json = newProviderJson();
    PackagePrivateOwner value = new PackagePrivateOwner();
    value.child.name = "child";
    value.child.probe = new CodegenProbeValue("probe");
    String encoded = json.toJson(value);
    Preconditions.checkArgument(
        json.fromJson(encoded, PackagePrivateOwner.class).child.name.equals("child"));
    String utf16 = encoded.replace(":\"probe\"", ":\"你\"");
    Preconditions.checkArgument(
        json.fromJson(utf16, PackagePrivateOwner.class).child.probe.value.equals("你"));
    byte[] utf8 = json.toJsonBytes(value);
    Preconditions.checkArgument(
        json.fromJson(utf8, PackagePrivateOwner.class).child.probe.value.equals("probe"));
  }

  private static void testExternalModuleMixin() {
    ForyJson json = newProviderJson();
    CompileState value = new CompileState();
    value.finished = true;
    String encoded = json.toJson(value);
    Preconditions.checkArgument(encoded.equals("{\"finished\":true}"));
    Preconditions.checkArgument(json.fromJson(encoded, CompileState.class).finished);
  }

  private static void testMixin() {
    JsonMixinTarget value =
        JsonMixinTarget.create(18, new JsonMixinTarget.Address("Hangzhou", 310000));
    String direct = ForyJson.builder().build().toJson(value);
    Preconditions.checkArgument(direct.contains("\"id\":18"));
    Preconditions.checkArgument(direct.contains("\"address\":{"));

    ForyJson json = ForyJson.builder().registerMixin(JsonMixinModel.class).build();
    String encoded = json.toJson(value);
    Preconditions.checkArgument(
        encoded.equals("{\"user_id\":18,\"address_city\":\"Hangzhou\",\"address_zip\":310000}"));
    JsonMixinTarget decoded = json.fromJson(encoded, JsonMixinTarget.class);
    Preconditions.checkArgument(decoded.getId() == 18);
    Preconditions.checkArgument(decoded.getAddress().city.equals("Hangzhou"));
    Preconditions.checkArgument(decoded.getAddress().zip == 310000);
  }

  private static void testMixinValue() {
    ForyJson json = ForyJson.builder().registerMixin(JsonMixinValueModel.class).build();
    JsonMixinValueTarget value = JsonMixinValueTarget.create("value-mixin");
    Preconditions.checkArgument(json.toJson(value).equals("\"value-mixin\""));
    JsonMixinValueTarget decoded = json.fromJson("\"decoded-mixin\"", JsonMixinValueTarget.class);
    Preconditions.checkArgument(decoded.getValue().equals("decoded-mixin"));
  }

  private static void testMixinValueRecord() {
    ForyJson json = ForyJson.builder().registerMixin(JsonMixinValueRecordModel.class).build();
    Preconditions.checkArgument(
        json.toJson(new JsonMixinValueRecord("record-value")).equals("\"record-value\""));
    JsonMixinValueRecord decoded = json.fromJson("\"decoded-record\"", JsonMixinValueRecord.class);
    Preconditions.checkArgument(decoded.value().equals("decoded-record"));
  }

  private static void testMixinEnumValue() {
    ForyJson json = ForyJson.builder().registerMixin(JsonMixinEnumValueModel.class).build();
    Preconditions.checkArgument(json.toJson(JsonMixinEnumValueTarget.READY).equals("\"ready\""));
    Preconditions.checkArgument(
        json.fromJson("\"done\"", JsonMixinEnumValueTarget.class) == JsonMixinEnumValueTarget.DONE);
  }

  private static void testMixinCodec() {
    ForyJson json = ForyJson.builder().registerMixin(JsonMixinCodecModel.class).build();
    JsonMixinCodecTarget value = new JsonMixinCodecTarget("mixin-codec");
    Preconditions.checkArgument(json.toJson(value).equals("\"string:mixin-codec\""));
    JsonMixinCodecTarget decoded =
        json.fromJson("\"decoded-mixin-codec\"", JsonMixinCodecTarget.class);
    checkStringRead(decoded.text, "decoded-mixin-codec");

    ForyJson inherited =
        ForyJson.builder().registerMixin(JsonMixinInheritedCodecModel.class).build();
    JsonMixinInheritedCodecTarget inheritedValue =
        new JsonMixinInheritedCodecTarget("inherited-codec");
    Preconditions.checkArgument(
        inherited.toJson(inheritedValue).equals("\"string:inherited-codec\""));
    JsonMixinInheritedCodecTarget inheritedDecoded =
        inherited.fromJson("\"decoded-inherited-codec\"", JsonMixinInheritedCodecTarget.class);
    checkStringRead(inheritedDecoded.text, "decoded-inherited-codec");
  }

  private static void testModels() {
    ForyJson json = ForyJson.builder().build();
    Model value = new Model();
    value.child = new Child(1, "first");
    value.children = List.of(new Child(2, "second"));
    value.childrenByName = Map.of("third", new Child(3, "third"));
    value.concreteChildren = new ArrayList<>(List.of(new Child(11, "concrete")));
    value.concreteChildrenByName = new HashMap<>();
    value.concreteChildrenByName.put("map", new Child(12, "concrete-map"));
    value.childArray = new Child[] {new Child(4, "fourth")};
    value.status = Status.ACTIVE;
    value.bean = new Bean("interface");
    value.record = new DataRecord(5, "record");
    value.creator = new CreatorValue(6, "creator");
    value.factory = FactoryValue.create(7, "factory");
    value.extra.put("dynamic", 8);

    byte[] bytes = json.toJsonBytes(value);
    Model decoded = json.fromJson(bytes, Model.class);
    Preconditions.checkArgument(decoded.inheritedId() == 10);
    Preconditions.checkArgument(decoded.child.id == 1);
    Preconditions.checkArgument(decoded.children.get(0).name.equals("second"));
    Preconditions.checkArgument(decoded.childrenByName.get("third").id == 3);
    Preconditions.checkArgument(decoded.concreteChildren.get(0).id == 11);
    Preconditions.checkArgument(decoded.concreteChildrenByName.get("map").id == 12);
    Preconditions.checkArgument(decoded.childArray[0].name.equals("fourth"));
    Preconditions.checkArgument(decoded.status == Status.ACTIVE);
    Preconditions.checkArgument(decoded.bean.getDisplayName().equals("interface"));
    Preconditions.checkArgument(decoded.record.equals(new DataRecord(5, "record")));
    Preconditions.checkArgument(decoded.creator.name.equals("creator"));
    Preconditions.checkArgument(decoded.factory.id == 7);
    Preconditions.checkArgument(decoded.extra.containsKey("dynamic"));
  }

  private static void testConfigurations() {
    ConfigValue value = new ConfigValue();
    value.camelName = "configured";
    String defaults = ForyJson.builder().build().toJson(value);
    Preconditions.checkArgument(defaults.contains("\"camelName\""));
    Preconditions.checkArgument(!defaults.contains("nullValue"));

    ForyJson configured =
        ForyJson.builder()
            .withFieldMode(true)
            .writeNullFields(true)
            .withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
            .build();
    String snakeCase = configured.toJson(value);
    Preconditions.checkArgument(snakeCase.contains("\"camel_name\""));
    Preconditions.checkArgument(snakeCase.contains("\"null_value\":null"));
    ConfigValue decoded = configured.fromJson(snakeCase, ConfigValue.class);
    Preconditions.checkArgument(decoded.camelName.equals("configured"));
  }

  private static void testCodecs() {
    ForyJson json = ForyJson.builder().build();
    CodecModel value = new CodecModel();
    value.direct = new DirectValue("direct");
    value.inherited = new InheritedValue("inherited");
    value.elements = List.of(new CodecValue("element"));
    value.array = new CodecValue[] {new CodecValue("array")};
    value.atomicArray =
        new AtomicReferenceArray<>(new CodecValue[] {new CodecValue("atomic-array")});
    value.mapped = Map.of(new CodecKey("key"), new CodecValue("mapped"));
    value.optional = Optional.of(new CodecValue("optional"));
    value.atomic = new AtomicReference<>(new CodecValue("atomic"));
    value.extra.put("dynamic", new CodecValue("any"));
    value.setParameterValue(new CodecValue("parameter"));
    value.getterValue = new CodecValue("getter");
    value.record = new CodecRecord(new CodecValue("record"));
    value.creator = new CodecCreator(new CodecValue("creator"));
    value.factory = CodecFactory.create(new CodecValue("factory"));

    String stringJson = json.toJson(value);
    Preconditions.checkArgument(stringJson.contains("string:direct"));
    Preconditions.checkArgument(stringJson.contains("string:inherited"));
    Preconditions.checkArgument(stringJson.contains("string:element"));
    Preconditions.checkArgument(stringJson.contains("string:array"));
    Preconditions.checkArgument(stringJson.contains("string:atomic-array"));
    Preconditions.checkArgument(stringJson.contains("\"key:key\""));
    Preconditions.checkArgument(stringJson.contains("string:mapped"));
    Preconditions.checkArgument(stringJson.contains("string:optional"));
    Preconditions.checkArgument(stringJson.contains("string:atomic"));
    Preconditions.checkArgument(stringJson.contains("string:any"));
    Preconditions.checkArgument(stringJson.contains("string:getter"));
    Preconditions.checkArgument(stringJson.contains("string:parameter"));
    String utf8Json = new String(json.toJsonBytes(value), StandardCharsets.UTF_8);
    Preconditions.checkArgument(utf8Json.contains("utf8:direct"));
    Preconditions.checkArgument(utf8Json.contains("utf8:element"));

    DirectValue stringValue = json.fromJson("\"value\"", DirectValue.class);
    DirectValue utf16 = json.fromJson("\"\u4f60\"", DirectValue.class);
    DirectValue utf8 =
        json.fromJson("\"value\"".getBytes(StandardCharsets.UTF_8), DirectValue.class);
    checkStringRead(stringValue.text, "value");
    Preconditions.checkArgument(utf16.text.equals("utf16:\u4f60"));
    Preconditions.checkArgument(utf8.text.equals("utf8:value"));

    CodecModel decoded = json.fromJson(stringJson, CodecModel.class);
    checkStringRead(decoded.direct.text, "string:direct");
    checkStringRead(decoded.inherited.text, "string:inherited");
    checkStringRead(decoded.elements.get(0).text, "string:element");
    checkStringRead(decoded.array[0].text, "string:array");
    checkStringRead(decoded.atomicArray.get(0).text, "string:atomic-array");
    checkStringRead(decoded.mapped.get(new CodecKey("key")).text, "string:mapped");
    checkStringRead(decoded.optional.orElseThrow().text, "string:optional");
    checkStringRead(decoded.atomic.get().text, "string:atomic");
    checkStringRead(decoded.extra.get("dynamic").text, "string:any");
    checkStringRead(decoded.getGetterValue().text, "string:getter");
    checkStringRead(decoded.getParameterValue().text, "string:parameter");
    checkStringRead(decoded.record.value.text, "string:record");
    checkStringRead(decoded.creator.value.text, "string:creator");
    checkStringRead(decoded.factory.value.text, "string:factory");
  }

  private static void checkStringRead(String actual, String value) {
    Preconditions.checkArgument(actual.equals("latin:" + value) || actual.equals("utf16:" + value));
  }

  private static void testValueAnnotations() {
    ForyJson json = ForyJson.builder().build();
    ValueId value = new ValueId("native-value");
    Preconditions.checkArgument(json.toJson(value).equals("\"native-value\""));
    Preconditions.checkArgument(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8).equals("\"native-value\""));
    Preconditions.checkArgument(
        json.fromJson("\"decoded\"", ValueId.class).value.equals("decoded"));
    Preconditions.checkArgument(
        json.fromJson("\"bytes\"".getBytes(StandardCharsets.UTF_8), ValueId.class)
            .value
            .equals("bytes"));

    RawValue raw = new RawValue();
    raw.body = "{\"id\":1}";
    Preconditions.checkArgument(json.toJson(raw).equals("{\"body\":{\"id\":1}}"));
    Preconditions.checkArgument(
        new String(json.toJsonBytes(raw), StandardCharsets.UTF_8).equals("{\"body\":{\"id\":1}}"));
    Preconditions.checkArgument(
        json.fromJson("{\"body\":\"text\"}", RawValue.class).body.equals("text"));
    ArrayBytes arrayBytes = new ArrayBytes();
    arrayBytes.value = new byte[] {1, -2, 3};
    Preconditions.checkArgument(json.toJson(arrayBytes).equals("{\"value\":[1,-2,3]}"));
    Preconditions.checkArgument(
        new String(json.toJsonBytes(arrayBytes), StandardCharsets.UTF_8)
            .equals("{\"value\":[1,-2,3]}"));
    Preconditions.checkArgument(
        Arrays.equals(
            json.fromJson("{\"value\":[1,-2,3]}", ArrayBytes.class).value, arrayBytes.value));
    Preconditions.checkArgument(
        Arrays.equals(
            json.fromJson("{\"value\":[1,-2,3]}".getBytes(StandardCharsets.UTF_8), ArrayBytes.class)
                .value,
            arrayBytes.value));
    Base64Bytes base64Bytes = new Base64Bytes();
    base64Bytes.value = new byte[] {1, 2, 3};
    Preconditions.checkArgument(json.toJson(base64Bytes).equals("{\"value\":\"AQID\"}"));
    Preconditions.checkArgument(
        new String(json.toJsonBytes(base64Bytes), StandardCharsets.UTF_8)
            .equals("{\"value\":\"AQID\"}"));
    Preconditions.checkArgument(
        Arrays.equals(
            json.fromJson("{\"value\":\"AQID\"}", Base64Bytes.class).value, new byte[] {1, 2, 3}));
  }

  private static void testSubtypes() {
    ForyJson json = ForyJson.builder().build();
    Shape value = new Circle(9);
    String encoded = json.toJson(value, Shape.class);
    Shape decoded = json.fromJson(encoded, Shape.class);
    Preconditions.checkArgument(decoded instanceof Circle);
    Preconditions.checkArgument(((Circle) decoded).radius == 9);
  }

  private static void testSubtypeMixin() {
    ForyJson json = ForyJson.builder().registerMixin(SealedShapeMixin.class).build();
    MixinShape value = new MixinCircle(7);
    String encoded = json.toJson(value, MixinShape.class);
    MixinShape decoded = json.fromJson(encoded, MixinShape.class);
    Preconditions.checkArgument(decoded instanceof MixinCircle);
    Preconditions.checkArgument(((MixinCircle) decoded).radius == 7);
  }

  private static void testContainerRoots() {
    ForyJson json = ForyJson.builder().build();
    StringList list = json.fromJson("[\"first\",\"second\"]", StringList.class);
    Preconditions.checkArgument(list.equals(List.of("first", "second")));
    StringMap map = json.fromJson("{\"key\":\"value\"}", StringMap.class);
    Preconditions.checkArgument(map.equals(Map.of("key", "value")));
  }

  private static void testGenericProperties() {
    ForyJson json = ForyJson.builder().build();
    GenericModel value =
        json.fromJson("{\"values\":[{\"id\":13,\"name\":\"generic\"}]}", GenericModel.class);
    Object values = value.getValues();
    Preconditions.checkArgument(
        values.getClass().getName().equals(ForyJsonExample.class.getName() + "$ChildList"));
    Child child = (Child) ((List<?>) values).get(0);
    Preconditions.checkArgument(child.id == 13);
    Preconditions.checkArgument(child.name.equals("generic"));
  }

  private static void testUnwrapped() {
    ForyJson json = ForyJson.builder().build();
    UnwrappedModel value = new UnwrappedModel(14, new UnwrappedRecord("native", 15));
    String encoded = json.toJson(value);
    Preconditions.checkArgument(
        encoded.equals("{\"id\":14,\"child_name\":\"native\",\"child_rank\":15}"));
    UnwrappedModel decoded = json.fromJson(encoded, UnwrappedModel.class);
    Preconditions.checkArgument(decoded.id == 14);
    Preconditions.checkArgument(decoded.child.equals(new UnwrappedRecord("native", 15)));

    UnwrappedRootRecord record = new UnwrappedRootRecord(16, new UnwrappedRecord("record", 17));
    String recordJson = json.toJson(record);
    Preconditions.checkArgument(
        recordJson.equals("{\"id\":16,\"child_name\":\"record\",\"child_rank\":17}"));
    UnwrappedRootRecord decodedRecord = json.fromJson(recordJson, UnwrappedRootRecord.class);
    Preconditions.checkArgument(decodedRecord.equals(record));
  }

  private static void testValidator() {
    ForyJson json = newProviderJson();
    ValidatedValue value = json.fromJson("{\"value\":21}", ValidatedValue.class);
    Preconditions.checkArgument(value.value == 21);
    Preconditions.checkArgument(value.validatorInvoked());
    try {
      json.fromJson("{\"value\":0}", ValidatedValue.class);
      throw new AssertionError("Invalid native JSON input must fail validation");
    } catch (ForyJsonException expected) {
      Preconditions.checkArgument(expected.getCause() instanceof IllegalArgumentException);
    }
  }

  private static void testGraphMemoryBudget() {
    String text = "{\"value\":34}";
    ForyJson exact = ForyJson.builder().withMaxGraphMemoryBytes(GRAPH_BUDGET_VALUE_BYTES).build();
    Preconditions.checkArgument(exact.fromJson(text, GraphBudgetValue.class).value == 34);

    ForyJson insufficient =
        ForyJson.builder().withMaxGraphMemoryBytes(GRAPH_BUDGET_VALUE_BYTES - 1).build();
    try {
      insufficient.fromJson(text, GraphBudgetValue.class);
      throw new AssertionError("An undersized graph memory budget must reject the object");
    } catch (ForyJsonException expected) {
      // Expected: malformed and resource-limit error details are not a public contract.
    }
  }

  private static void testContainerGraphBudget() {
    long plainListBytes = minimumGraphBudget("[]", PlainGraphList.class);
    long fieldListBytes = minimumGraphBudget("[]", FieldGraphList.class);
    Preconditions.checkArgument(fieldListBytes == plainListBytes + Long.BYTES + Integer.BYTES);

    long plainMapBytes = minimumGraphBudget("{}", PlainGraphMap.class);
    long fieldMapBytes = minimumGraphBudget("{}", FieldGraphMap.class);
    Preconditions.checkArgument(fieldMapBytes == plainMapBytes + Long.BYTES + Integer.BYTES);
  }

  private static void testSpecialContainerBudget() {
    int enumMapShallow = GraphMemoryEstimates.shallowObjectBytes(EnumMap.class);
    int regularSetShallow =
        GraphMemoryEstimates.shallowObjectBytes(EnumSet.noneOf(Status.class).getClass());
    int jumboSetShallow =
        GraphMemoryEstimates.shallowObjectBytes(EnumSet.noneOf(BudgetJumboKey.class).getClass());
    Preconditions.checkArgument(enumMapShallow > 2 * REF_BYTES);
    Preconditions.checkArgument(regularSetShallow > 2 * REF_BYTES);
    Preconditions.checkArgument(jumboSetShallow > 2 * REF_BYTES);

    long enumMapBytes =
        GraphMemoryEstimates.shallowObjectBytes(GraphBudgetEnumMap.class)
            + enumMapShallow
            + GraphMemoryEstimates.objectArrayBytes()
            + (long) Status.values().length * REF_BYTES;
    Preconditions.checkArgument(
        minimumGraphBudget("{\"value\":{}}", GraphBudgetEnumMap.class) == enumMapBytes);

    long regularSetBytes =
        GraphMemoryEstimates.shallowObjectBytes(GraphBudgetRegularSet.class) + regularSetShallow;
    Preconditions.checkArgument(
        minimumGraphBudget("{\"value\":[]}", GraphBudgetRegularSet.class) == regularSetBytes);

    long jumboWords = (BudgetJumboKey.values().length + Long.SIZE - 1L) / Long.SIZE * Long.BYTES;
    long jumboSetBytes =
        GraphMemoryEstimates.shallowObjectBytes(GraphBudgetJumboSet.class)
            + jumboSetShallow
            + GraphMemoryEstimates.objectArrayBytes()
            + jumboWords;
    Preconditions.checkArgument(
        minimumGraphBudget("{\"value\":[]}", GraphBudgetJumboSet.class) == jumboSetBytes);
  }

  private static long minimumGraphBudget(String input, Class<?> type) {
    long low = 1;
    long high = 4096;
    while (low < high) {
      long middle = low + (high - low) / 2;
      if (fitsGraphBudget(input, type, middle)) {
        high = middle;
      } else {
        low = middle + 1;
      }
    }
    Preconditions.checkArgument(fitsGraphBudget(input, type, low));
    return low;
  }

  private static boolean fitsGraphBudget(String input, Class<?> type, long budget) {
    ForyJson json =
        ForyJson.builder()
            .withCodegen(false)
            .withConcurrencyLevel(1)
            .withMaxGraphMemoryBytes(budget)
            .build();
    try {
      json.fromJson(input, type);
      return true;
    } catch (ForyJsonException expected) {
      return false;
    }
  }

  private static void testBigDecimal() {
    ForyJson json = ForyJson.builder().build();
    BigDecimalHolder value = new BigDecimalHolder();
    value.value = new BigDecimalSubtype("12345678901234567890.125");
    String expected = "{\"value\":12345678901234567890.125}";
    Preconditions.checkArgument(json.toJson(value).equals(expected));
    Preconditions.checkArgument(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8).equals(expected));
  }

  private static void testSqlTypes() {
    ForyJson json = ForyJson.builder().build();
    SqlValues value = new SqlValues();
    value.date = new Date(1_000L);
    value.time = new Time(2_000L);
    value.timestamp = new Timestamp(3_000L);
    SqlValues decoded = json.fromJson(json.toJsonBytes(value), SqlValues.class);
    Preconditions.checkArgument(decoded.date.getTime() == 1_000L);
    Preconditions.checkArgument(decoded.time.getTime() == 2_000L);
    Preconditions.checkArgument(decoded.timestamp.getTime() == 3_000L);
  }

  private static void testFormatTimezone() {
    ForyJson json = ForyJson.builder().build();
    Instant instant = Instant.parse("2024-01-02T03:04:05Z");
    FormatTimezoneValues value = new FormatTimezoneValues();
    value.instant = instant;
    value.instants = List.of(instant, instant.plusSeconds(3600));
    String expected =
        "{\"instant\":\"2024-01-02 11:04:05 +08:00\","
            + "\"instants\":[\"2024-01-02 11:04:05 +08:00\","
            + "\"2024-01-02 12:04:05 +08:00\"]}";
    Preconditions.checkArgument(json.toJson(value).equals(expected));
    byte[] bytes = json.toJsonBytes(value);
    Preconditions.checkArgument(new String(bytes, StandardCharsets.UTF_8).equals(expected));
    FormatTimezoneValues decoded = json.fromJson(bytes, FormatTimezoneValues.class);
    Preconditions.checkArgument(decoded.instant.equals(instant));
    Preconditions.checkArgument(decoded.instants.equals(value.instants));
  }

  public interface InheritedJsonConfig {
    default ForyJson duplicateConfiguration() {
      return newProviderJson();
    }
  }

  @JsonType
  public static final class CodegenProbeModel {
    public int id;

    @JsonCodec(CodegenProbeCodec.class)
    public CodegenProbeValue probe;

    public List<CodegenProbeChild> children = new ArrayList<>();

    public CodegenProbeModel() {}
  }

  @JsonType
  public static final class CodegenProbeChild {
    public String name;

    public CodegenProbeChild() {}

    public CodegenProbeChild(String name) {
      this.name = name;
    }
  }

  @JsonType
  public static final class RegisteredCodecModel {
    public CodegenProbeValue probe;

    public RegisteredCodecModel() {}
  }

  public static final class EmptyMixinTarget {
    @JsonCodec(CodegenProbeCodec.class)
    private CodegenProbeValue probe;

    public EmptyMixinTarget() {}
  }

  @JsonMixin(target = EmptyMixinTarget.class)
  public interface EmptyMixin {}

  @JsonMixin(target = StackTraceElement.class)
  public interface StackTraceElementMixin {
    @JsonCodec(BootstrapProbeCodec.class)
    String getClassName();
  }

  public static final class InterpretedMixinTarget {
    private String name;

    public InterpretedMixinTarget() {}

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }

  @JsonMixin(target = InterpretedMixinTarget.class)
  public interface InterpretedMixin {}

  @JsonType
  public static final class InterpretedBean {
    private String name;
    private final transient Map<String, String> extra = new LinkedHashMap<>();

    public InterpretedBean() {}

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    @JsonAnyGetter
    public Map<String, String> extra() {
      return extra;
    }

    @JsonAnySetter
    public void putExtra(String key, String value) {
      extra.put(key, value);
    }
  }

  @JsonType
  public static final class PrimitiveProperties {
    private boolean booleanValue;
    private byte byteValue;
    private short shortValue;
    private int intValue;
    private long longValue;
    private float floatValue;
    private double doubleValue;
    private char charValue;

    public PrimitiveProperties() {}

    public boolean isBooleanValue() {
      return booleanValue;
    }

    public void setBooleanValue(boolean booleanValue) {
      this.booleanValue = booleanValue;
    }

    public byte getByteValue() {
      return byteValue;
    }

    public void setByteValue(byte byteValue) {
      this.byteValue = byteValue;
    }

    public short getShortValue() {
      return shortValue;
    }

    public void setShortValue(short shortValue) {
      this.shortValue = shortValue;
    }

    public int getIntValue() {
      return intValue;
    }

    public void setIntValue(int intValue) {
      this.intValue = intValue;
    }

    public long getLongValue() {
      return longValue;
    }

    public void setLongValue(long longValue) {
      this.longValue = longValue;
    }

    public float getFloatValue() {
      return floatValue;
    }

    public void setFloatValue(float floatValue) {
      this.floatValue = floatValue;
    }

    public double getDoubleValue() {
      return doubleValue;
    }

    public void setDoubleValue(double doubleValue) {
      this.doubleValue = doubleValue;
    }

    public char getCharValue() {
      return charValue;
    }

    public void setCharValue(char charValue) {
      this.charValue = charValue;
    }
  }

  public static final class CodegenProbeValue {
    private final String value;

    private CodegenProbeValue(String value) {
      this.value = value;
    }
  }

  public static final class CodegenProbeCodec implements JsonValueCodec<CodegenProbeValue> {
    private static Class<?> expectedType;
    private static boolean expectGeneratedWriter;
    private static boolean expectGeneratedReader;

    public CodegenProbeCodec() {}

    private static void expect(Class<?> type, boolean generated) {
      expect(type, generated, generated);
    }

    private static void expect(Class<?> type, boolean writerGenerated, boolean readerGenerated) {
      expectedType = type;
      expectGeneratedWriter = writerGenerated;
      expectGeneratedReader = readerGenerated;
    }

    @Override
    public void writeString(StringJsonWriter writer, CodegenProbeValue value) {
      checkCapability(
          writer.typeResolver().getTypeInfo(expectedType, expectedType).stringWriter(),
          expectGeneratedWriter);
      writer.writeString(value == null ? null : value.value);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, CodegenProbeValue value) {
      checkCapability(
          writer.typeResolver().getTypeInfo(expectedType, expectedType).utf8Writer(),
          expectGeneratedWriter);
      writer.writeString(value == null ? null : value.value);
    }

    @Override
    public CodegenProbeValue readLatin1(Latin1JsonReader reader) {
      checkCapability(
          reader.typeResolver().getTypeInfo(expectedType, expectedType).latin1Reader(),
          expectGeneratedReader);
      return reader.tryReadNullToken() ? null : new CodegenProbeValue(reader.readString());
    }

    @Override
    public CodegenProbeValue readUtf16(Utf16JsonReader reader) {
      checkCapability(
          reader.typeResolver().getTypeInfo(expectedType, expectedType).utf16Reader(),
          expectGeneratedReader);
      return reader.tryReadNullToken() ? null : new CodegenProbeValue(reader.readString());
    }

    @Override
    public CodegenProbeValue readUtf8(Utf8JsonReader reader) {
      checkCapability(
          reader.typeResolver().getTypeInfo(expectedType, expectedType).utf8Reader(),
          expectGeneratedReader);
      return reader.tryReadNullToken() ? null : new CodegenProbeValue(reader.readString());
    }

    private static void checkCapability(Object capability, boolean expectGenerated) {
      boolean generated = !(capability instanceof ObjectCodec<?>);
      Preconditions.checkArgument(generated == expectGenerated);
    }
  }

  public static final class BootstrapProbeCodec implements JsonValueCodec<String> {
    public BootstrapProbeCodec() {}

    @Override
    public void writeString(StringJsonWriter writer, String value) {
      CodegenProbeCodec.checkCapability(
          writer
              .typeResolver()
              .getTypeInfo(StackTraceElement.class, StackTraceElement.class)
              .stringWriter(),
          true);
      writer.writeString(value);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, String value) {
      CodegenProbeCodec.checkCapability(
          writer
              .typeResolver()
              .getTypeInfo(StackTraceElement.class, StackTraceElement.class)
              .utf8Writer(),
          true);
      writer.writeString(value);
    }

    @Override
    public String readLatin1(Latin1JsonReader reader) {
      return reader.readString();
    }

    @Override
    public String readUtf16(Utf16JsonReader reader) {
      return reader.readString();
    }

    @Override
    public String readUtf8(Utf8JsonReader reader) {
      return reader.readString();
    }
  }

  @JsonType
  static final class PackagePrivateOwner {
    public PublicChild child = new PublicChild();

    PackagePrivateOwner() {}
  }

  @JsonType
  public static final class PublicChild {
    public String name;

    @JsonCodec(CodegenProbeCodec.class)
    public CodegenProbeValue probe;

    public PublicChild() {}
  }

  @JsonMixin(target = CompileState.class)
  public abstract static class CoreCompileStateMixin {
    @JsonIgnore private Lock lock;
    @JsonProperty private boolean finished;
    @JsonIgnore private Map<String, byte[]> result;
  }

  public static class Parent {
    private int inheritedId = 10;

    int inheritedId() {
      return inheritedId;
    }
  }

  public static final class StringList extends ArrayList<String> {
    public StringList() {}
  }

  public static final class StringMap extends HashMap<String, String> {
    public StringMap() {}
  }

  public static final class PlainGraphList extends ArrayList<String> {
    public PlainGraphList() {}
  }

  public static class FieldGraphListBase extends ArrayList<String> {
    private long inheritedField;

    public FieldGraphListBase() {}
  }

  public static final class FieldGraphList extends FieldGraphListBase {
    private int directField;

    public FieldGraphList() {}
  }

  public static final class PlainGraphMap extends LinkedHashMap<String, String> {
    public PlainGraphMap() {}
  }

  public static class FieldGraphMapBase extends LinkedHashMap<String, String> {
    private long inheritedField;

    public FieldGraphMapBase() {}
  }

  public static final class FieldGraphMap extends FieldGraphMapBase {
    private int directField;

    public FieldGraphMap() {}
  }

  public abstract static class GenericProperty<T> {
    private Object value;

    @SuppressWarnings("unchecked")
    public T getValues() {
      return (T) value;
    }

    public void setValues(T value) {
      this.value = value;
    }
  }

  @JsonType
  public static final class GenericModel extends GenericProperty<ChildList> {}

  public static final class ChildList extends ArrayList<Child> {
    public ChildList() {}
  }

  @JsonType
  public static final class BigDecimalHolder {
    public BigDecimal value;
  }

  private static final class BigDecimalSubtype extends BigDecimal {
    private BigDecimalSubtype(String value) {
      super(value);
    }

    @Override
    public String toString() {
      throw new AssertionError("BigDecimal subtype toString must not be invoked");
    }

    @Override
    public BigInteger unscaledValue() {
      throw new AssertionError("BigDecimal subtype unscaledValue must not be invoked");
    }

    @Override
    public int scale() {
      throw new AssertionError("BigDecimal subtype scale must not be invoked");
    }

    @Override
    public BigDecimal negate() {
      throw new AssertionError("BigDecimal subtype negate must not be invoked");
    }
  }

  @JsonType
  public static final class Model extends Parent {
    public Child child;
    public List<Child> children;
    public Map<String, Child> childrenByName;
    public ArrayList<Child> concreteChildren;
    public HashMap<String, Child> concreteChildrenByName;
    public Child[] childArray;
    public Status status;
    public Bean bean;
    public DataRecord record;
    public CreatorValue creator;
    public FactoryValue factory;

    @JsonAnyProperty public Map<String, Object> extra = new LinkedHashMap<>();
  }

  @JsonType
  public static final class Child {
    public int id;
    public String name;

    public Child() {}

    Child(int id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  @JsonType
  public enum Status {
    ACTIVE,
    INACTIVE
  }

  public interface NamedBean {
    String getDisplayName();

    void setDisplayName(String value);
  }

  @JsonType
  public static final class Bean implements NamedBean {
    private String displayName;

    public Bean() {}

    Bean(String displayName) {
      this.displayName = displayName;
    }

    @Override
    public String getDisplayName() {
      return displayName;
    }

    @Override
    public void setDisplayName(String value) {
      displayName = value;
    }
  }

  @JsonType
  public record DataRecord(int id, String name) {}

  @JsonType
  public static final class UnwrappedModel {
    public final int id;

    @JsonUnwrapped(prefix = "child_")
    public final UnwrappedRecord child;

    @JsonCreator({"id", "child"})
    public UnwrappedModel(int id, UnwrappedRecord child) {
      this.id = id;
      this.child = child;
    }
  }

  @JsonType
  public record UnwrappedRecord(String name, int rank) {}

  @JsonType
  public record UnwrappedRootRecord(
      int id, @JsonUnwrapped(prefix = "child_") UnwrappedRecord child) {}

  @JsonType
  public static final class CreatorValue {
    public final int id;
    public final String name;

    @JsonCreator({"id", "name"})
    public CreatorValue(int id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  @JsonType
  public static final class FactoryValue {
    public final int id;
    public final String name;

    private FactoryValue(int id, String name) {
      this.id = id;
      this.name = name;
    }

    @JsonCreator({"id", "name"})
    public static FactoryValue create(int id, String name) {
      return new FactoryValue(id, name);
    }
  }

  @JsonType
  public static final class ValueId {
    private final String value;

    @JsonCreator
    public ValueId(String value) {
      this.value = value;
    }

    @JsonValue
    public String value() {
      return value;
    }
  }

  @JsonType
  public record DirectValueRecord(@JsonValue String value) {
    @JsonCreator
    public DirectValueRecord {}
  }

  @JsonType
  public enum DirectValueEnum {
    READY("ready"),
    DONE("done");

    private final String value;

    DirectValueEnum(String value) {
      this.value = value;
    }

    @JsonValue
    public String value() {
      return value;
    }

    @JsonCreator
    public static DirectValueEnum fromValue(String value) {
      for (DirectValueEnum candidate : values()) {
        if (candidate.value.equals(value)) {
          return candidate;
        }
      }
      throw new IllegalArgumentException(value);
    }
  }

  @JsonType
  public static final class RawValue {
    @JsonRawValue public String body;
  }

  @JsonType
  public static final class ArrayBytes {
    @JsonByteArray(JsonByteArray.Format.ARRAY)
    public byte[] value;
  }

  @JsonType
  public static final class Base64Bytes {
    @JsonByteArray(JsonByteArray.Format.BASE64)
    public byte[] value;
  }

  @JsonType
  public static final class ConfigValue {
    public String camelName;
    public String nullValue;
  }

  @JsonType
  public static final class ValidatedValue {
    public int value;
    private boolean validatorInvoked;

    @JsonValidator
    public void validate() {
      validatorInvoked = true;
      Preconditions.checkArgument(value > 0);
    }

    public boolean validatorInvoked() {
      return validatorInvoked;
    }
  }

  @JsonType
  public static final class GraphBudgetValue {
    public int value;
  }

  @JsonType
  public static final class GraphBudgetEnumMap {
    public EnumMap<Status, String> value;
  }

  @JsonType
  public static final class GraphBudgetRegularSet {
    public EnumSet<Status> value;
  }

  @JsonType
  public static final class GraphBudgetJumboSet {
    public EnumSet<BudgetJumboKey> value;
  }

  public enum BudgetJumboKey {
    V00,
    V01,
    V02,
    V03,
    V04,
    V05,
    V06,
    V07,
    V08,
    V09,
    V10,
    V11,
    V12,
    V13,
    V14,
    V15,
    V16,
    V17,
    V18,
    V19,
    V20,
    V21,
    V22,
    V23,
    V24,
    V25,
    V26,
    V27,
    V28,
    V29,
    V30,
    V31,
    V32,
    V33,
    V34,
    V35,
    V36,
    V37,
    V38,
    V39,
    V40,
    V41,
    V42,
    V43,
    V44,
    V45,
    V46,
    V47,
    V48,
    V49,
    V50,
    V51,
    V52,
    V53,
    V54,
    V55,
    V56,
    V57,
    V58,
    V59,
    V60,
    V61,
    V62,
    V63,
    V64
  }

  @JsonType
  public static final class CodecModel {
    public DirectValue direct;
    public InheritedValue inherited;

    @JsonCodec(elementCodec = ValueCodec.class)
    public List<CodecValue> elements = new ArrayList<>();

    @JsonCodec(elementCodec = ValueCodec.class)
    public CodecValue[] array;

    @JsonCodec(elementCodec = ValueCodec.class)
    public AtomicReferenceArray<CodecValue> atomicArray;

    @JsonCodec(keyCodec = KeyCodec.class, valueCodec = ValueCodec.class)
    public Map<CodecKey, CodecValue> mapped;

    @JsonCodec(contentCodec = ValueCodec.class)
    public Optional<CodecValue> optional;

    @JsonCodec(contentCodec = ValueCodec.class)
    public AtomicReference<CodecValue> atomic;

    @JsonAnyProperty
    @JsonCodec(valueCodec = ValueCodec.class)
    public Map<String, CodecValue> extra = new LinkedHashMap<>();

    private CodecValue getterValue;
    private CodecValue parameterValue;
    public CodecRecord record;
    public CodecCreator creator;
    public CodecFactory factory;

    @JsonCodec(ValueCodec.class)
    public CodecValue getGetterValue() {
      return getterValue;
    }

    public void setGetterValue(CodecValue getterValue) {
      this.getterValue = getterValue;
    }

    public CodecValue getParameterValue() {
      return parameterValue;
    }

    public void setParameterValue(@JsonCodec(ValueCodec.class) CodecValue parameterValue) {
      this.parameterValue = parameterValue;
    }
  }

  @JsonCodec(DirectCodec.class)
  public static final class DirectValue implements TextValue {
    private final String text;

    DirectValue(String text) {
      this.text = text;
    }

    @Override
    public String text() {
      return text;
    }
  }

  @JsonCodec(InheritedCodec.class)
  public interface InheritedText extends TextValue {}

  public static final class InheritedValue implements InheritedText {
    private final String text;

    InheritedValue(String text) {
      this.text = text;
    }

    @Override
    public String text() {
      return text;
    }
  }

  public static final class CodecValue implements TextValue {
    private final String text;

    CodecValue(String text) {
      this.text = text;
    }

    @Override
    public String text() {
      return text;
    }
  }

  @JsonType
  public record CodecRecord(@JsonCodec(ValueCodec.class) CodecValue value) {}

  @JsonType
  public static final class CodecCreator {
    public final CodecValue value;

    @JsonCreator
    public CodecCreator(@JsonProperty("value") @JsonCodec(ValueCodec.class) CodecValue value) {
      this.value = value;
    }
  }

  @JsonType
  public static final class CodecFactory {
    public final CodecValue value;

    private CodecFactory(CodecValue value) {
      this.value = value;
    }

    @JsonCreator
    public static CodecFactory create(
        @JsonProperty("value") @JsonCodec(ValueCodec.class) CodecValue value) {
      return new CodecFactory(value);
    }
  }

  public interface TextValue {
    String text();
  }

  public abstract static class TextCodec<T extends TextValue> implements JsonValueCodec<T> {
    public TextCodec() {}

    protected abstract T create(String text);

    @Override
    public void writeString(StringJsonWriter writer, T value) {
      writer.writeString(value == null ? null : "string:" + value.text());
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, T value) {
      writer.writeString(value == null ? null : "utf8:" + value.text());
    }

    @Override
    public T readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : create("latin:" + reader.readString());
    }

    @Override
    public T readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : create("utf16:" + reader.readString());
    }

    @Override
    public T readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : create("utf8:" + reader.readString());
    }
  }

  public static final class DirectCodec extends TextCodec<DirectValue> {
    public DirectCodec() {}

    @Override
    protected DirectValue create(String text) {
      return new DirectValue(text);
    }
  }

  public static final class InheritedCodec extends TextCodec<InheritedValue> {
    public InheritedCodec() {}

    @Override
    protected InheritedValue create(String text) {
      return new InheritedValue(text);
    }
  }

  public static final class ValueCodec extends TextCodec<CodecValue> {
    public ValueCodec() {}

    @Override
    protected CodecValue create(String text) {
      return new CodecValue(text);
    }
  }

  public static final class CodecKey {
    private final String text;

    CodecKey(String text) {
      this.text = text;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof CodecKey && Objects.equals(text, ((CodecKey) other).text);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(text);
    }
  }

  public static final class KeyCodec implements MapKeyCodec {
    public KeyCodec() {}

    @Override
    public String toName(Object key) {
      return "key:" + ((CodecKey) key).text;
    }

    @Override
    public Object fromName(String name) {
      return new CodecKey(name.substring("key:".length()));
    }
  }

  @JsonType
  @JsonSubTypes(property = "kind")
  public sealed interface Shape permits Circle {}

  public static final class Circle implements Shape {
    public int radius;

    public Circle() {}

    Circle(int radius) {
      this.radius = radius;
    }
  }

  public sealed interface MixinShape permits MixinCircle {}

  public static final class MixinCircle implements MixinShape {
    public int radius;

    public MixinCircle() {}

    MixinCircle(int radius) {
      this.radius = radius;
    }
  }

  @JsonMixin(target = MixinShape.class)
  @JsonSubTypes(property = "kind")
  public interface SealedShapeMixin {}

  @JsonType
  public static final class SqlValues {
    public Date date;
    public Time time;
    public Timestamp timestamp;
  }

  @JsonType
  public static final class FormatTimezoneValues {
    @JsonFormat(pattern = "uuuu-MM-dd HH:mm:ss XXX", timezone = "Asia/Shanghai")
    public Instant instant;

    @JsonFormat(pattern = "uuuu-MM-dd HH:mm:ss XXX", timezone = "Asia/Shanghai")
    public List<Instant> instants;
  }

  @JsonMixin(target = JsonMixinTarget.class)
  @JsonPropertyOrder({"id", "address"})
  public interface JsonMixinModel {
    @JsonProperty("user_id")
    int getId();

    @JsonUnwrapped(prefix = "address_")
    JsonMixinTarget.Address getAddress();

    @JsonCreator({"id", "address"})
    JsonMixinTarget create(int id, JsonMixinTarget.Address address);
  }

  @JsonType
  public static final class JsonMixinTarget {
    private final int id;
    private final Address address;

    private JsonMixinTarget(int id, Address address) {
      this.id = id;
      this.address = address;
    }

    public int getId() {
      return id;
    }

    public Address getAddress() {
      return address;
    }

    public static JsonMixinTarget create(int id, Address address) {
      return new JsonMixinTarget(id, address);
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

  @JsonMixin(target = JsonMixinValueTarget.class)
  public interface JsonMixinValueModel {
    @JsonValue
    String getValue();

    @JsonCreator
    JsonMixinValueTarget create(String value);
  }

  public static final class JsonMixinValueTarget {
    private final String value;

    private JsonMixinValueTarget(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }

    public static JsonMixinValueTarget create(String value) {
      return new JsonMixinValueTarget(value);
    }
  }

  public record JsonMixinValueRecord(String value) {}

  @JsonMixin(target = JsonMixinValueRecord.class)
  public abstract static class JsonMixinValueRecordModel {
    @JsonValue String value;

    @JsonValue
    abstract String value();

    @JsonCreator
    JsonMixinValueRecordModel(String value) {}
  }

  public enum JsonMixinEnumValueTarget {
    READY("ready"),
    DONE("done");

    private final String value;

    JsonMixinEnumValueTarget(String value) {
      this.value = value;
    }

    @JsonValue
    public String value() {
      return value;
    }

    public static JsonMixinEnumValueTarget fromValue(String value) {
      for (JsonMixinEnumValueTarget candidate : values()) {
        if (candidate.value.equals(value)) {
          return candidate;
        }
      }
      throw new IllegalArgumentException(value);
    }
  }

  @JsonMixin(target = JsonMixinEnumValueTarget.class)
  public interface JsonMixinEnumValueModel {
    @JsonCreator
    JsonMixinEnumValueTarget fromValue(String value);
  }

  @JsonMixin(target = JsonMixinCodecTarget.class)
  @JsonCodec(JsonMixinTargetCodec.class)
  public interface JsonMixinCodecModel {}

  public static final class JsonMixinCodecTarget implements TextValue {
    @JsonCodec(UnreachableFieldCodec.class)
    public CodecValue ignored;

    private final String text;

    JsonMixinCodecTarget(String text) {
      this.text = text;
    }

    @Override
    public String text() {
      return text;
    }
  }

  public static final class JsonMixinTargetCodec extends TextCodec<JsonMixinCodecTarget> {
    public JsonMixinTargetCodec() {}

    @Override
    protected JsonMixinCodecTarget create(String text) {
      return new JsonMixinCodecTarget(text);
    }
  }

  public static final class UnreachableFieldCodec extends TextCodec<CodecValue> {
    private UnreachableFieldCodec() {}

    @Override
    protected CodecValue create(String text) {
      return new CodecValue(text);
    }
  }

  @JsonCodec(JsonMixinInheritedTargetCodec.class)
  public interface JsonMixinInheritedCodecContract {}

  @JsonMixin(target = JsonMixinInheritedCodecTarget.class)
  public interface JsonMixinInheritedCodecModel {
    @JsonValue
    @JsonCodec(UnreachableFieldCodec.class)
    String value();
  }

  public static final class JsonMixinInheritedCodecTarget
      implements TextValue, JsonMixinInheritedCodecContract {
    private final String text;

    JsonMixinInheritedCodecTarget(String text) {
      this.text = text;
    }

    public String value() {
      return text;
    }

    @Override
    public String text() {
      return text;
    }
  }

  public static final class JsonMixinInheritedTargetCodec
      extends TextCodec<JsonMixinInheritedCodecTarget> {
    public JsonMixinInheritedTargetCodec() {}

    @Override
    protected JsonMixinInheritedCodecTarget create(String text) {
      return new JsonMixinInheritedCodecTarget(text);
    }
  }
}
