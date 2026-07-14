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
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.apache.fory.json.annotation.JsonAnyGetter;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonAnySetter;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.StringSerializer;
import org.testng.SkipException;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonAnyPropertyTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonAnyPropertyTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void fieldBackedRoundTrip() {
    ForyJson json = newJson();
    MutableAny value = new MutableAny();
    value.id = 1;
    value.properties = linkedMap("first", 2, "second", null);
    String expected = "{\"id\":1,\"first\":2,\"second\":null}";
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);

    MutableAny latin1 = json.fromJson("{\"id\":3,\"plain\":4}", MutableAny.class);
    assertEquals(latin1.id, 3);
    assertEquals(latin1.properties, Collections.singletonMap("plain", 4));

    MutableAny utf16 = json.fromJson("{\"id\":5,\"键\\u540d\":6}", MutableAny.class);
    assertEquals(utf16.properties, Collections.singletonMap("键名", 6));

    MutableAny utf8 =
        json.fromJson(
            "{\"id\":7,\"emoji😀\":8}".getBytes(StandardCharsets.UTF_8), MutableAny.class);
    assertEquals(utf8.properties, Collections.singletonMap("emoji😀", 8));
    assertGeneratedCapabilities(json, MutableAny.class);
  }

  @Test
  public void fieldMapLifecycle() {
    ForyJson json = newJson();
    MutableAny noUnknown = json.fromJson("{\"id\":1}", MutableAny.class);
    assertNull(noUnknown.properties);
    noUnknown.properties = new LinkedHashMap<>();
    assertEquals(json.toJson(noUnknown), "{\"id\":1}");
    noUnknown.properties = null;
    assertEquals(json.toJson(noUnknown), "{\"id\":1}");

    MutableAny decoded = json.fromJson("{\"x\":1,\"y\":2}", MutableAny.class);
    assertEquals(decoded.properties, linkedMap("x", 1, "y", 2));

    PreinitializedAny preinitialized = json.fromJson("{\"x\":1}", PreinitializedAny.class);
    assertTrue(preinitialized.reused());
    assertEquals(preinitialized.properties, Collections.singletonMap("x", 1));
    assertEquals(json.toJson(preinitialized), "{\"x\":1}");
    assertEquals(new String(json.toJsonBytes(preinitialized), StandardCharsets.UTF_8), "{\"x\":1}");
    assertEquals(
        json.fromJson("{\"键\":2}", PreinitializedAny.class).properties,
        Collections.singletonMap("键", 2));
    assertEquals(
        json.fromJson("{\"byte\":3}".getBytes(StandardCharsets.UTF_8), PreinitializedAny.class)
            .properties,
        Collections.singletonMap("byte", 3));

    FinalAny finalValue = json.fromJson("{\"x\":1}", FinalAny.class);
    assertEquals(finalValue.properties, Collections.singletonMap("x", 1));
    assertEquals(json.toJson(finalValue), "{\"x\":1}");
    assertEquals(new String(json.toJsonBytes(finalValue), StandardCharsets.UTF_8), "{\"x\":1}");
    assertEquals(
        json.fromJson("{\"键\":2}", FinalAny.class).properties, Collections.singletonMap("键", 2));
    assertEquals(
        json.fromJson("{\"byte\":3}".getBytes(StandardCharsets.UTF_8), FinalAny.class).properties,
        Collections.singletonMap("byte", 3));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"x\":1}", NullFinalAny.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"x\":1}", UnmodifiableAny.class));
    assertGeneratedCapabilities(json, PreinitializedAny.class);
    assertGeneratedCapabilities(json, FinalAny.class);
  }

  @Test
  public void directionalField() {
    ForyJson json = newJson();
    OutputAny output = new OutputAny();
    output.id = 1;
    output.properties.put("x", 2);
    assertEquals(json.toJson(output), "{\"id\":1,\"x\":2}");
    assertEquals(
        new String(json.toJsonBytes(output), StandardCharsets.UTF_8), "{\"id\":1,\"x\":2}");
    OutputAny ignored = json.fromJson("{\"id\":3,\"x\":4}", OutputAny.class);
    assertEquals(ignored.id, 3);
    assertTrue(ignored.properties.isEmpty());
    OutputAny ignoredUtf16 = json.fromJson("{\"id\":5,\"键\":6}", OutputAny.class);
    assertEquals(ignoredUtf16.id, 5);
    assertTrue(ignoredUtf16.properties.isEmpty());
    OutputAny ignoredUtf8 =
        json.fromJson("{\"id\":7,\"byte\":8}".getBytes(StandardCharsets.UTF_8), OutputAny.class);
    assertEquals(ignoredUtf8.id, 7);
    assertTrue(ignoredUtf8.properties.isEmpty());

    InputAny input = json.fromJson("{\"id\":9,\"x\":10}", InputAny.class);
    assertEquals(input.properties, Collections.singletonMap("x", 10));
    assertEquals(json.toJson(input), "{\"id\":9}");
    assertEquals(new String(json.toJsonBytes(input), StandardCharsets.UTF_8), "{\"id\":9}");
    assertEquals(
        json.fromJson("{\"id\":11,\"键\":12}", InputAny.class).properties,
        Collections.singletonMap("键", 12));
    assertEquals(
        json.fromJson("{\"id\":13,\"byte\":14}".getBytes(StandardCharsets.UTF_8), InputAny.class)
            .properties,
        Collections.singletonMap("byte", 14));
    assertGeneratedCapabilities(json, OutputAny.class);
    assertGeneratedCapabilities(json, InputAny.class);
  }

  @Test
  public void fieldMode() {
    ForyJson json = newJsonBuilder().withFieldMode(true).build();
    FieldModeAny value = new FieldModeAny();
    value.id = 1;
    value.properties.put("x", 2);
    assertEquals(json.toJson(value), "{\"id\":1,\"x\":2}");
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "{\"id\":1,\"x\":2}");

    FieldModeAny latin1 = json.fromJson("{\"id\":3,\"ignored\":4,\"x\":5}", FieldModeAny.class);
    assertEquals(latin1.id, 3);
    assertEquals(latin1.ignored, 0);
    assertEquals(latin1.properties, Collections.singletonMap("x", 5));
    FieldModeAny utf16 = json.fromJson("{\"id\":6,\"ignored\":7,\"键\":8}", FieldModeAny.class);
    assertEquals(utf16.properties, Collections.singletonMap("键", 8));
    FieldModeAny utf8 =
        json.fromJson(
            "{\"id\":9,\"ignored\":10,\"byte\":11}".getBytes(StandardCharsets.UTF_8),
            FieldModeAny.class);
    assertEquals(utf8.properties, Collections.singletonMap("byte", 11));
    assertGeneratedCapabilities(json, FieldModeAny.class);

    value.properties.clear();
    value.properties.put("ignored", 12);
    assertThrows(ForyJsonException.class, () -> json.toJson(value));
    assertThrows(ForyJsonException.class, () -> json.toJsonBytes(value));
    assertThrows(ForyJsonException.class, () -> json.toJson(new GetterOnly()));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{}", SetterOnly.class));
  }

  @Test
  public void privateField() {
    ForyJson json = newJson();
    PrivateAny value = new PrivateAny();
    value.put("x", 1);
    assertEquals(json.toJson(value), "{\"x\":1}");
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "{\"x\":1}");
    PrivateAny decoded = json.fromJson("{\"y\":2}", PrivateAny.class);
    assertEquals(decoded.get("y"), Integer.valueOf(2));
    PrivateAny utf16 = json.fromJson("{\"键\":3}", PrivateAny.class);
    assertEquals(utf16.get("键"), Integer.valueOf(3));
    PrivateAny utf8 =
        json.fromJson("{\"byte\":4}".getBytes(StandardCharsets.UTF_8), PrivateAny.class);
    assertEquals(utf8.get("byte"), Integer.valueOf(4));
    assertGeneratedCapabilities(json, PrivateAny.class);
  }

  @Test
  public void methodBackedRoundTrip() {
    ForyJson json = newJson();
    MethodAny value = new MethodAny();
    value.properties.put("x", 1);
    assertEquals(json.toJson(value), "{\"x\":1}");
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "{\"x\":1}");

    MethodAny decoded =
        json.fromJson("{\"escaped\\u0020key\":2,\"escaped key\":3}", MethodAny.class);
    assertEquals(decoded.properties, Collections.singletonMap("escaped key", 3));
    assertEquals(decoded.calls, 2);
    assertEquals(decoded.setCalls, 0);
    MethodAny utf16 = json.fromJson("{\"键\":4}", MethodAny.class);
    assertEquals(utf16.properties, Collections.singletonMap("键", 4));
    MethodAny utf8 = json.fromJson("{\"键\":4}".getBytes(StandardCharsets.UTF_8), MethodAny.class);
    assertEquals(utf8.properties, Collections.singletonMap("键", 4));
    InheritedMethodAny inherited = json.fromJson("{\"inherited\":5}", InheritedMethodAny.class);
    assertEquals(inherited.getProperties(), Collections.singletonMap("inherited", 5));
    assertGeneratedCapabilities(json, MethodAny.class);
  }

  @Test
  public void independentMethods() {
    ForyJson json = newJson();
    GetterOnly getter = new GetterOnly();
    getter.storage.put("x", 1);
    assertEquals(json.toJson(getter), "{\"x\":1}");
    assertEquals(new String(json.toJsonBytes(getter), StandardCharsets.UTF_8), "{\"x\":1}");
    GetterOnly read = json.fromJson("{\"x\":2}", GetterOnly.class);
    assertTrue(read.storage.isEmpty());
    assertTrue(json.fromJson("{\"键\":3}", GetterOnly.class).storage.isEmpty());
    assertTrue(
        json.fromJson("{\"byte\":4}".getBytes(StandardCharsets.UTF_8), GetterOnly.class)
            .storage
            .isEmpty());

    SetterOnly setter = json.fromJson("{\"x\":1,\"x\":2}", SetterOnly.class);
    assertEquals(setter.storage, Collections.singletonMap("x", 2));
    assertEquals(setter.calls, 2);
    SetterOnly utf16 = json.fromJson("{\"键\":3}", SetterOnly.class);
    assertEquals(utf16.storage, Collections.singletonMap("键", 3));
    SetterOnly utf8 =
        json.fromJson("{\"byte\":4}".getBytes(StandardCharsets.UTF_8), SetterOnly.class);
    assertEquals(utf8.storage, Collections.singletonMap("byte", 4));
    assertEquals(json.toJson(setter), "{}");
    assertEquals(new String(json.toJsonBytes(setter), StandardCharsets.UTF_8), "{}");
    assertGeneratedCapabilities(json, GetterOnly.class);
    assertGeneratedCapabilities(json, SetterOnly.class);
  }

  @Test
  public void primitiveSetter() {
    ForyJson json = newJson();
    PrimitiveSetter value = json.fromJson("{\"x\":1}", PrimitiveSetter.class);
    assertEquals(value.storage, Collections.singletonMap("x", 1));
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"x\":null}", PrimitiveSetter.class));
    assertThrows(
        ForyJsonException.class,
        () ->
            json.fromJson("{\"x\":null}".getBytes(StandardCharsets.UTF_8), PrimitiveSetter.class));

    PrimitiveSetterWithGetter paired = json.fromJson("{\"x\":1}", PrimitiveSetterWithGetter.class);
    assertEquals(paired.storage, Collections.singletonMap("x", 1));
    assertEquals(json.toJson(paired), "{\"x\":1}");
    assertEquals(new String(json.toJsonBytes(paired), StandardCharsets.UTF_8), "{\"x\":1}");
    PrimitiveSetterWithGetter pairedUtf16 =
        json.fromJson("{\"键\":2}", PrimitiveSetterWithGetter.class);
    assertEquals(pairedUtf16.storage, Collections.singletonMap("键", 2));
    PrimitiveSetterWithGetter pairedUtf8 =
        json.fromJson(
            "{\"byte\":3}".getBytes(StandardCharsets.UTF_8), PrimitiveSetterWithGetter.class);
    assertEquals(pairedUtf8.storage, Collections.singletonMap("byte", 3));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"x\":null}", PrimitiveSetterWithGetter.class));
    assertGeneratedCapabilities(json, PrimitiveSetterWithGetter.class);

    OverloadedPrimitiveSetter overloaded =
        json.fromJson("{\"x\":4}", OverloadedPrimitiveSetter.class);
    assertEquals(overloaded.storage, Collections.singletonMap("x", 4));
    assertEquals(overloaded.primitiveCalls, 1);
    assertEquals(overloaded.boxedCalls, 0);
    OverloadedPrimitiveSetter overloadedUtf16 =
        json.fromJson("{\"键\":5}", OverloadedPrimitiveSetter.class);
    assertEquals(overloadedUtf16.primitiveCalls, 1);
    assertEquals(overloadedUtf16.boxedCalls, 0);
    OverloadedPrimitiveSetter overloadedUtf8 =
        json.fromJson(
            "{\"byte\":6}".getBytes(StandardCharsets.UTF_8), OverloadedPrimitiveSetter.class);
    assertEquals(overloadedUtf8.primitiveCalls, 1);
    assertEquals(overloadedUtf8.boxedCalls, 0);
    assertEquals(json.toJson(overloaded), "{}");
    assertEquals(new String(json.toJsonBytes(overloaded), StandardCharsets.UTF_8), "{}");
    assertGeneratedCapabilities(json, OverloadedPrimitiveSetter.class);
  }

  @Test
  public void logicalPropertyMerge() {
    ForyJson json = newJson();
    MethodAny value = new MethodAny();
    value.properties.put("dynamic", 1);
    assertEquals(json.toJson(value), "{\"dynamic\":1}");
    MethodAny decoded = json.fromJson("{\"properties\":2}", MethodAny.class);
    assertEquals(decoded.properties, Collections.singletonMap("properties", 2));
    assertEquals(json.toJson(decoded), "{\"properties\":2}");

    OverrideAny override = new OverrideAny();
    override.storage.put("x", 3);
    assertEquals(json.toJson(override), "{\"properties\":{\"x\":3}}");
  }

  @Test
  public void ignoredReadDoesNotDisableAnySetter() {
    ForyJson json = newJson();
    IgnoredReadMethodAny value = json.fromJson("{\"x\":1}", IgnoredReadMethodAny.class);
    assertEquals(value.properties, Collections.singletonMap("x", 1));
    assertEquals(value.calls, 1);
    assertEquals(json.toJson(value), "{\"x\":1}");
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "{\"x\":1}");

    IgnoredReadMethodAny utf16 = json.fromJson("{\"键\":2}", IgnoredReadMethodAny.class);
    assertEquals(utf16.properties, Collections.singletonMap("键", 2));
    IgnoredReadMethodAny utf8 =
        json.fromJson("{\"byte\":3}".getBytes(StandardCharsets.UTF_8), IgnoredReadMethodAny.class);
    assertEquals(utf8.properties, Collections.singletonMap("byte", 3));
    assertGeneratedCapabilities(json, IgnoredReadMethodAny.class);
  }

  @Test
  public void namingStrategy() {
    ForyJson json =
        newJsonBuilder().withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE).build();
    NamingAny value = new NamingAny();
    value.displayName = "name";
    value.storage.put("dynamicKey", 1);
    assertEquals(json.toJson(value), "{\"display_name\":\"name\",\"dynamicKey\":1}");
    NamingAny decoded =
        json.fromJson("{\"display_name\":\"other\",\"dynamicKey\":2}", NamingAny.class);
    assertEquals(decoded.displayName, "other");
    assertEquals(decoded.storage, Collections.singletonMap("dynamicKey", 2));
  }

  @Test
  public void explicitOrder() {
    ForyJson json = newJson();
    OrderedAny value = new OrderedAny();
    value.before = 1;
    value.after = 4;
    value.properties.put("second", 2);
    value.properties.put("third", 3);
    assertEquals(json.toJson(value), "{\"before\":1,\"second\":2,\"third\":3,\"after\":4}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8),
        "{\"before\":1,\"second\":2,\"third\":3,\"after\":4}");
    assertGeneratedWriters(json, OrderedAny.class);
  }

  @Test
  public void emptyAndFirstOrder() {
    ForyJson json = newJson();
    AnyFirst value = new AnyFirst();
    value.id = 2;
    assertEquals(json.toJson(value), "{\"id\":2}");
    value.properties = new LinkedHashMap<>();
    assertEquals(json.toJson(value), "{\"id\":2}");
    value.properties.put("x", 1);
    assertEquals(json.toJson(value), "{\"x\":1,\"id\":2}");

    OmittedAroundAny omitted = new OmittedAroundAny();
    omitted.tail = 2;
    omitted.properties.put("x", 1);
    assertEquals(json.toJson(omitted), "{\"x\":1,\"tail\":2}");
    omitted.properties.clear();
    assertEquals(json.toJson(omitted), "{\"tail\":2}");

    assertEquals(json.toJson(new NullGetter()), "{}");
  }

  @Test
  public void alphabeticAndIndexOrder() {
    ForyJson json = newJson();
    AlphabeticAny alphabetic = new AlphabeticAny();
    alphabetic.a = 1;
    alphabetic.z = 4;
    alphabetic.extras.put("y", 2);
    alphabetic.extras.put("x", 3);
    assertEquals(json.toJson(alphabetic), "{\"a\":1,\"y\":2,\"x\":3,\"z\":4}");

    IndexedAny indexed = new IndexedAny();
    indexed.a = 1;
    indexed.b = 2;
    indexed.c = 4;
    indexed.properties.put("x", 3);
    assertEquals(json.toJson(indexed), "{\"a\":1,\"b\":2,\"x\":3,\"c\":4}");
  }

  @Test
  public void finalNameOrderPrecedence() {
    ForyJson json = newJson();
    FinalNameBeforeAny value = new FinalNameBeforeAny();
    value.id = 1;
    value.extras.put("x", 2);
    value.tail = 3;
    assertEquals(json.toJson(value), "{\"extras\":1,\"x\":2,\"tail\":3}");
  }

  @Test
  public void fixedNamesAreReserved() {
    ForyJson json = newJson();
    MutableAny fixed = new MutableAny();
    fixed.properties = linkedMap("id", 1);
    assertThrows(ForyJsonException.class, () -> json.toJson(fixed));
    assertThrows(ForyJsonException.class, () -> json.toJsonBytes(fixed));

    IgnoredFixed ignored = json.fromJson("{\"s\\u0065cret\":1,\"x\":2}", IgnoredFixed.class);
    assertNull(ignored.secret);
    assertEquals(ignored.properties, Collections.singletonMap("x", 2));
    ignored.properties.put("secret", 3);
    assertThrows(ForyJsonException.class, () -> json.toJson(ignored));
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void rejectInvalidOutputKeys() {
    ForyJson json = newJson();
    MutableAny nullKey = new MutableAny();
    nullKey.properties = new LinkedHashMap<>();
    nullKey.properties.put(null, 1);
    assertThrows(ForyJsonException.class, () -> json.toJson(nullKey));

    MutableAny nonString = new MutableAny();
    nonString.properties = new LinkedHashMap<>();
    ((Map) nonString.properties).put(Integer.valueOf(1), Integer.valueOf(2));
    assertThrows(ForyJsonException.class, () -> json.toJson(nonString));
  }

  @Test
  public void dynamicKeyEscaping() {
    ForyJson json = newJson();
    EscapedKeyAny value = new EscapedKeyAny();
    value.properties.put("quote\"key", 1);
    value.properties.put("slash\\key", 2);
    value.properties.put("line\nkey", 3);
    value.properties.put("键", 4);
    String expected = "{\"quote\\\"key\":1,\"slash\\\\key\":2,\"line\\nkey\":3,\"键\":4}";
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    assertEquals(json.fromJson(expected, EscapedKeyAny.class).properties, value.properties);
    assertEquals(
        json.fromJson(expected.getBytes(StandardCharsets.UTF_8), EscapedKeyAny.class).properties,
        value.properties);
    assertEquals(
        json.fromJson("{\"plain\":5}", EscapedKeyAny.class).properties,
        Collections.singletonMap("plain", 5));
    assertGeneratedCapabilities(json, EscapedKeyAny.class);
  }

  @Test
  public void inheritedAndConcreteMaps() {
    ForyJson json = newJson();
    GenericAny value = json.fromJson("{\"x\":1}", GenericAny.class);
    assertEquals(value.properties, Collections.singletonMap("x", 1));
    assertEquals(json.toJson(value), "{\"x\":1}");

    TreeAny tree = json.fromJson("{\"z\":2,\"a\":1}", TreeAny.class);
    assertTrue(tree.properties instanceof TreeMap);
    assertEquals(json.toJson(tree), "{\"a\":1,\"z\":2}");
  }

  @Test
  public void nestedValues() {
    ForyJson json = newJson();
    NestedAny typed = new NestedAny();
    typed.properties = new LinkedHashMap<>();
    typed.properties.put("node", new NestedValue(1));
    String expected = "{\"node\":{\"value\":1}}";
    assertEquals(json.toJson(typed), expected);
    assertEquals(new String(json.toJsonBytes(typed), StandardCharsets.UTF_8), expected);
    assertEquals(json.fromJson(expected, NestedAny.class).properties.get("node").value, 1);
    assertEquals(
        json.fromJson("{\"键\":{\"value\":2}}", NestedAny.class).properties.get("键").value, 2);
    assertEquals(
        json.fromJson("{\"byte\":{\"value\":3}}".getBytes(StandardCharsets.UTF_8), NestedAny.class)
            .properties
            .get("byte")
            .value,
        3);
    assertGeneratedCapabilities(json, NestedAny.class);

    ObjectAny dynamic = json.fromJson("{\"outer\":{\"value\":1}}", ObjectAny.class);
    assertTrue(dynamic.properties.get("outer") instanceof JsonObject);
    assertEquals(((JsonObject) dynamic.properties.get("outer")).get("value"), Long.valueOf(1));
    assertEquals(json.toJson(dynamic), "{\"outer\":{\"value\":1}}");
    assertEquals(
        new String(json.toJsonBytes(dynamic), StandardCharsets.UTF_8), "{\"outer\":{\"value\":1}}");
    ObjectAny dynamicUtf16 = json.fromJson("{\"键\":{\"value\":2}}", ObjectAny.class);
    assertEquals(((JsonObject) dynamicUtf16.properties.get("键")).get("value"), Long.valueOf(2));
    ObjectAny dynamicUtf8 =
        json.fromJson("{\"byte\":{\"value\":3}}".getBytes(StandardCharsets.UTF_8), ObjectAny.class);
    assertEquals(((JsonObject) dynamicUtf8.properties.get("byte")).get("value"), Long.valueOf(3));
    assertGeneratedCapabilities(json, ObjectAny.class);
  }

  @Test
  public void recursiveValues() {
    ForyJson json = newJson();
    RecursiveAny value = new RecursiveAny();
    value.id = 1;
    value.properties = new LinkedHashMap<>();
    RecursiveAny child = new RecursiveAny();
    child.id = 2;
    value.properties.put("child", child);
    String expected = "{\"id\":1,\"child\":{\"id\":2}}";
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);

    RecursiveAny latin1 = json.fromJson(expected, RecursiveAny.class);
    assertEquals(latin1.properties.get("child").id, 2);
    RecursiveAny utf16 = json.fromJson("{\"id\":3,\"子\":{\"id\":4}}", RecursiveAny.class);
    assertEquals(utf16.properties.get("子").id, 4);
    RecursiveAny utf8 =
        json.fromJson(
            "{\"id\":5,\"byte\":{\"id\":6}}".getBytes(StandardCharsets.UTF_8), RecursiveAny.class);
    assertEquals(utf8.properties.get("byte").id, 6);
    assertGeneratedCapabilities(json, RecursiveAny.class);
  }

  @Test
  public void parameterizedBinding() {
    ForyJson json = newJson();
    TypeRef<ParameterizedAny<NestedValue>> type = new TypeRef<ParameterizedAny<NestedValue>>() {};
    ParameterizedAny<NestedValue> value = new ParameterizedAny<>();
    value.properties = new LinkedHashMap<>();
    value.properties.put("node", new NestedValue(1));
    String expected = "{\"node\":{\"value\":1}}";
    assertEquals(json.toJson(value, type), expected);
    assertEquals(new String(json.toJsonBytes(value, type), StandardCharsets.UTF_8), expected);
    assertEquals(json.fromJson(expected, type).properties.get("node").value, 1);
    assertEquals(json.fromJson("{\"键\":{\"value\":2}}", type).properties.get("键").value, 2);
    assertEquals(
        json.fromJson("{\"byte\":{\"value\":3}}".getBytes(StandardCharsets.UTF_8), type)
            .properties
            .get("byte")
            .value,
        3);
    assertInterpretedCapabilities(json, type);
  }

  @Test
  public void creatorAggregate() {
    ForyJson json = newJson();
    CreatorAny decoded = json.fromJson("{\"tail\":3,\"x\":2,\"id\":1}", CreatorAny.class);
    assertEquals(decoded.id, 1);
    assertEquals(decoded.properties, Collections.singletonMap("x", 2));
    assertEquals(decoded.tail, 3);
    assertEquals(json.toJson(decoded), "{\"id\":1,\"x\":2,\"tail\":3}");

    CreatorAny value = new CreatorAny(4, linkedMap("x", 5), 6);
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8),
        "{\"id\":4,\"x\":5,\"tail\":6}");
    CreatorAny noUnknown = json.fromJson("{\"tail\":8,\"id\":7}", CreatorAny.class);
    assertEquals(noUnknown.id, 7);
    assertNull(noUnknown.properties);
    assertEquals(noUnknown.tail, 8);
    CreatorAny utf16 = json.fromJson("{\"tail\":11,\"键\":10,\"id\":9}", CreatorAny.class);
    assertEquals(utf16.properties, Collections.singletonMap("键", 10));
    CreatorAny utf8 =
        json.fromJson(
            "{\"tail\":14,\"byte\":13,\"id\":12}".getBytes(StandardCharsets.UTF_8),
            CreatorAny.class);
    assertEquals(utf8.properties, Collections.singletonMap("byte", 13));
    assertGeneratedCapabilities(json, CreatorAny.class);
  }

  @Test
  public void recordAggregate() throws Exception {
    requireRecords();
    Class<?> type =
        compileRecordClass(
            "JsonAnyRecord",
            "package org.apache.fory.json.records;\n"
                + "import java.util.Map;\n"
                + "import org.apache.fory.json.annotation.JsonAnyProperty;\n"
                + "public record JsonAnyRecord("
                + "@JsonAnyProperty Map<String, Integer> properties, int id, int tail) {}\n");
    ForyJson json = newJson();
    Object decoded = json.fromJson("{\"tail\":3,\"x\":2,\"id\":1}", type);
    assertEquals(type.getMethod("id").invoke(decoded), Integer.valueOf(1));
    assertEquals(type.getMethod("properties").invoke(decoded), Collections.singletonMap("x", 2));
    assertEquals(type.getMethod("tail").invoke(decoded), Integer.valueOf(3));
    assertEquals(json.toJson(decoded), "{\"x\":2,\"id\":1,\"tail\":3}");
    assertEquals(
        new String(json.toJsonBytes(decoded), StandardCharsets.UTF_8),
        "{\"x\":2,\"id\":1,\"tail\":3}");

    Object noUnknown = json.fromJson("{\"tail\":5,\"id\":4}", type);
    assertEquals(type.getMethod("id").invoke(noUnknown), Integer.valueOf(4));
    assertNull(type.getMethod("properties").invoke(noUnknown));
    assertEquals(type.getMethod("tail").invoke(noUnknown), Integer.valueOf(5));
    Object utf16 = json.fromJson("{\"tail\":8,\"键\":7,\"id\":6}", type);
    assertEquals(type.getMethod("properties").invoke(utf16), Collections.singletonMap("键", 7));
    Object utf8 =
        json.fromJson("{\"tail\":11,\"byte\":10,\"id\":9}".getBytes(StandardCharsets.UTF_8), type);
    assertEquals(type.getMethod("properties").invoke(utf8), Collections.singletonMap("byte", 10));
    assertGeneratedCapabilities(json, type);
  }

  @Test
  public void getterOnlyRecordDefault() throws Exception {
    requireRecords();
    Class<?> type =
        compileRecordClass(
            "JsonAnyGetterRecord",
            "package org.apache.fory.json.records;\n"
                + "import java.util.Map;\n"
                + "import org.apache.fory.json.annotation.JsonAnyGetter;\n"
                + "public record JsonAnyGetterRecord(int id, Map<String, Integer> properties) {\n"
                + "  @JsonAnyGetter public Map<String, Integer> properties() { "
                + "return properties; }\n"
                + "}\n");
    ForyJson json = newJson();
    Object decoded = json.fromJson("{\"id\":1,\"x\":2}", type);
    assertEquals(type.getMethod("id").invoke(decoded), Integer.valueOf(1));
    assertNull(type.getMethod("properties").invoke(decoded));
    Map<String, Integer> properties = linkedMap("x", 2);
    Object value = type.getConstructor(int.class, Map.class).newInstance(1, properties);
    assertEquals(json.toJson(value), "{\"id\":1,\"x\":2}");
  }

  @Test
  public void inlineSubtype() {
    ForyJson json = newJson();
    ExtensibleCircle value = new ExtensibleCircle();
    value.radius = 2;
    value.properties.put("color", 3);
    String expected = "{\"kind\":\"circle\",\"radius\":2,\"color\":3}";
    assertEquals(json.toJson(value, ExtensibleShape.class), expected);
    assertEquals(
        new String(json.toJsonBytes(value, ExtensibleShape.class), StandardCharsets.UTF_8),
        expected);

    ExtensibleShape decoded =
        json.fromJson("{\"k\\u0069nd\":\"circle\",\"radius\":4,\"x\":5}", ExtensibleShape.class);
    ExtensibleCircle circle = (ExtensibleCircle) decoded;
    assertEquals(circle.radius, 4);
    assertEquals(circle.properties, Collections.singletonMap("x", 5));
    ExtensibleCircle utf16 =
        (ExtensibleCircle)
            json.fromJson("{\"kind\":\"circle\",\"radius\":6,\"键\":7}", ExtensibleShape.class);
    assertEquals(utf16.properties, Collections.singletonMap("键", 7));
    ExtensibleCircle utf8 =
        (ExtensibleCircle)
            json.fromJson(expected.getBytes(StandardCharsets.UTF_8), ExtensibleShape.class);
    assertEquals(utf8.properties, Collections.singletonMap("color", 3));

    value.properties.put("kind", 6);
    assertThrows(ForyJsonException.class, () -> json.toJson(value, ExtensibleShape.class));
    assertGeneratedCapabilities(json, ExtensibleCircle.class);
  }

  @Test
  public void accessorFailures() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.toJson(new ThrowingGetter()));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"x\":1}", ThrowingSetter.class));
  }

  @Test
  public void rejectFieldDeclarations() {
    ForyJson json = newJson();
    assertInvalidWrite(json, RawAny.class);
    assertInvalidWrite(json, NonStringKeyAny.class);
    assertInvalidWrite(json, WildcardKeyAny.class);
    assertInvalidWrite(json, DuplicateFieldAny.class);
    assertInvalidWrite(json, MixedAny.class);
    assertInvalidWrite(json, PropertyOnAny.class);
    assertInvalidWrite(json, PropertyOnLogicalAny.class);
    assertInvalidWrite(json, IgnoredAnyGetter.class);
    assertInvalidWrite(json, FullyIgnoredAny.class);
  }

  @Test
  public void rejectGetterDeclarations() {
    ForyJson json = newJson();
    assertInvalidWrite(json, StaticAnyGetter.class);
    assertInvalidWrite(json, ArgumentAnyGetter.class);
    assertInvalidWrite(json, NonMapAnyGetter.class);
    assertInvalidWrite(json, GenericAnyGetter.class);
    assertInvalidWrite(json, DuplicateAnyGetter.class);
  }

  @Test
  public void rejectSetterDeclarations() {
    ForyJson json = newJson();
    assertInvalidRead(json, StaticAnySetter.class);
    assertInvalidRead(json, PrivateAnySetter.class);
    assertInvalidRead(json, ReturningAnySetter.class);
    assertInvalidRead(json, BadNameAnySetter.class);
    assertInvalidRead(json, GenericAnySetter.class);
    assertInvalidRead(json, VarargsAnySetter.class);
    assertInvalidRead(json, DuplicateAnySetter.class);
    assertInvalidRead(json, PropertyOnAnySetter.class);
    assertInvalidRead(json, IncompatibleAnyMethods.class);
  }

  @Test
  public void rejectConstructionDeclarations() throws Exception {
    ForyJson json = newJson();
    assertInvalidRead(json, CreatorAnySetter.class);
    assertInvalidRead(json, ParameterLocalAny.class);
    assertInvalidRead(json, WriteOnlyCreatorAny.class);
    requireRecords();
    Class<?> type =
        compileRecordClass(
            "JsonAnySetterRecord",
            "package org.apache.fory.json.records;\n"
                + "import org.apache.fory.json.annotation.JsonAnySetter;\n"
                + "public record JsonAnySetterRecord(int id) {\n"
                + "  @JsonAnySetter public void put(String name, int value) {}\n"
                + "}\n");
    assertThrows(ForyJsonException.class, () -> json.fromJson("{}", type));
  }

  @Test
  public void rejectInvalidAnyOrder() {
    ForyJson json = newJson();
    assertInvalidWrite(json, OrderedSetterOnly.class);
    assertInvalidWrite(json, OrderedInputOnly.class);
  }

  private static void assertInvalidWrite(ForyJson json, Class<?> type) {
    assertThrows(ForyJsonException.class, () -> json.toJson(newInstance(type)));
  }

  private static void assertInvalidRead(ForyJson json, Class<?> type) {
    assertThrows(ForyJsonException.class, () -> json.fromJson("{}", type));
  }

  private void assertGeneratedCapabilities(ForyJson json, Class<?> type) {
    JsonTypeResolver resolver = JsonTestSupport.primaryTypeResolver(json);
    resolver.lockJIT();
    try {
      Object owner = resolver.getObjectCodec(type);
      JsonTypeInfo info = resolver.getTypeInfo(type, type);
      if (!StringSerializer.isBytesBackedString()) {
        resolver.latin1Reader((ObjectCodec<?>) owner);
      }
      assertGenerated(info.stringWriter(), owner);
      assertGenerated(info.utf8Writer(), owner);
      assertGenerated(info.latin1Reader(), owner);
      assertGenerated(info.utf16Reader(), owner);
      assertGenerated(info.utf8Reader(), owner);
    } finally {
      resolver.unlockJIT();
    }
  }

  private void assertGeneratedWriters(ForyJson json, Class<?> type) {
    JsonTypeResolver resolver = JsonTestSupport.primaryTypeResolver(json);
    resolver.lockJIT();
    try {
      Object owner = resolver.getObjectCodec(type);
      JsonTypeInfo info = resolver.getTypeInfo(type, type);
      assertGenerated(info.stringWriter(), owner);
      assertGenerated(info.utf8Writer(), owner);
    } finally {
      resolver.unlockJIT();
    }
  }

  private static void assertInterpretedCapabilities(ForyJson json, TypeRef<?> type) {
    JsonTypeResolver resolver = JsonTestSupport.primaryTypeResolver(json);
    resolver.lockJIT();
    try {
      JsonTypeInfo info = resolver.getTypeInfo(type.getType(), type.getRawType());
      Object owner = info.stringWriter();
      assertSame(info.utf8Writer(), owner);
      assertSame(info.latin1Reader(), owner);
      assertSame(info.utf16Reader(), owner);
      assertSame(info.utf8Reader(), owner);
    } finally {
      resolver.unlockJIT();
    }
  }

  private void assertGenerated(Object capability, Object owner) {
    if (codegenEnabled()) {
      assertNotSame(capability, owner);
    } else {
      assertSame(capability, owner);
    }
  }

  private static Object newInstance(Class<?> type) {
    try {
      return type.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static void requireRecords() {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
  }

  private static LinkedHashMap<String, Integer> linkedMap(String key, Integer value) {
    LinkedHashMap<String, Integer> map = new LinkedHashMap<>();
    map.put(key, value);
    return map;
  }

  private static LinkedHashMap<String, Integer> linkedMap(
      String firstKey, Integer firstValue, String secondKey, Integer secondValue) {
    LinkedHashMap<String, Integer> map = linkedMap(firstKey, firstValue);
    map.put(secondKey, secondValue);
    return map;
  }

  public static class MutableAny {
    public int id;

    @JsonAnyProperty public Map<String, Integer> properties;
  }

  public static final class PreinitializedAny {
    @JsonAnyProperty public Map<String, Integer> properties = new LinkedHashMap<>();

    @JsonIgnore private final Map<String, Integer> initial = properties;

    public boolean reused() {
      return properties == initial;
    }
  }

  public static final class FinalAny {
    @JsonAnyProperty public final Map<String, Integer> properties = new LinkedHashMap<>();
  }

  public static final class NullFinalAny {
    @JsonAnyProperty public final Map<String, Integer> properties = null;
  }

  public static final class UnmodifiableAny {
    @JsonAnyProperty
    public final Map<String, Integer> properties =
        Collections.unmodifiableMap(new LinkedHashMap<String, Integer>());
  }

  public static final class OutputAny {
    public int id;

    @JsonAnyProperty
    @JsonIgnore(ignoreRead = true, ignoreWrite = false)
    public Map<String, Integer> properties = new LinkedHashMap<>();
  }

  public static final class InputAny {
    public int id;

    @JsonAnyProperty
    @JsonIgnore(ignoreRead = false, ignoreWrite = true)
    public Map<String, Integer> properties;
  }

  public static final class FieldModeAny {
    public int id;

    @JsonIgnore public int ignored;

    @JsonAnyProperty public Map<String, Integer> properties = new LinkedHashMap<>();
  }

  public static final class PrivateAny {
    @JsonAnyProperty private Map<String, Integer> properties;

    public void put(String name, int value) {
      if (properties == null) {
        properties = new LinkedHashMap<>();
      }
      properties.put(name, value);
    }

    public Integer get(String name) {
      return properties.get(name);
    }
  }

  public static class MethodAny {
    private final Map<String, Integer> properties = new LinkedHashMap<>();
    @JsonIgnore private int calls;
    @JsonIgnore private int setCalls;

    @JsonAnyGetter
    public Map<String, Integer> getProperties() {
      return properties;
    }

    public void setProperties(Map<String, Integer> ignored) {
      setCalls++;
    }

    @JsonAnySetter
    public void putProperty(String name, Integer value) {
      calls++;
      properties.put(name, value);
    }
  }

  public static final class IgnoredReadMethodAny {
    @JsonIgnore(ignoreRead = true, ignoreWrite = false)
    private final Map<String, Integer> properties = new LinkedHashMap<>();

    @JsonIgnore private int calls;

    @JsonAnyGetter
    public Map<String, Integer> getProperties() {
      return properties;
    }

    @JsonAnySetter
    public void putProperty(String name, Integer value) {
      calls++;
      properties.put(name, value);
    }
  }

  public static final class InheritedMethodAny extends MethodAny {}

  public static final class GetterOnly {
    @JsonIgnore private final Map<String, Integer> storage = new LinkedHashMap<>();

    @JsonAnyGetter
    public Map<String, Integer> properties() {
      return storage;
    }
  }

  public static final class NullGetter {
    @JsonAnyGetter
    public Map<String, Integer> properties() {
      return null;
    }
  }

  public static final class SetterOnly {
    @JsonIgnore private final Map<String, Integer> storage = new LinkedHashMap<>();
    @JsonIgnore private int calls;

    @JsonAnySetter
    public void putProperty(String name, Integer value) {
      calls++;
      storage.put(name, value);
    }
  }

  public static final class PrimitiveSetter {
    @JsonIgnore private final Map<String, Integer> storage = new LinkedHashMap<>();

    @JsonAnySetter
    public void putProperty(String name, int value) {
      storage.put(name, value);
    }
  }

  public static final class PrimitiveSetterWithGetter {
    @JsonIgnore private final Map<String, Integer> storage = new LinkedHashMap<>();

    @JsonAnyGetter
    public Map<String, Integer> properties() {
      return storage;
    }

    @JsonAnySetter
    public void putProperty(String name, int value) {
      storage.put(name, value);
    }
  }

  public static final class OverloadedPrimitiveSetter {
    @JsonIgnore private final Map<String, Integer> storage = new LinkedHashMap<>();
    @JsonIgnore private int primitiveCalls;
    @JsonIgnore private int boxedCalls;

    @JsonAnySetter
    public void put(String name, int value) {
      primitiveCalls++;
      storage.put(name, value);
    }

    public void put(String name, Integer value) {
      boxedCalls++;
      storage.put(name, value);
    }
  }

  public static class ParentAny {
    @JsonIgnore protected final Map<String, Integer> storage = new LinkedHashMap<>();

    @JsonAnyGetter
    public Map<String, Integer> getProperties() {
      return storage;
    }
  }

  public static final class OverrideAny extends ParentAny {
    @Override
    public Map<String, Integer> getProperties() {
      return storage;
    }
  }

  @JsonPropertyOrder({"displayName", "properties"})
  public static final class NamingAny {
    public String displayName;

    @JsonIgnore private final Map<String, Integer> storage = new LinkedHashMap<>();

    @JsonAnyGetter
    public Map<String, Integer> properties() {
      return storage;
    }

    @JsonAnySetter
    public void put(String name, Integer value) {
      storage.put(name, value);
    }
  }

  @JsonPropertyOrder({"before", "properties", "after"})
  public static final class OrderedAny {
    public int before;
    @JsonAnyProperty public Map<String, Integer> properties = new LinkedHashMap<>();
    public int after;
  }

  @JsonPropertyOrder({"properties", "id"})
  public static final class AnyFirst {
    public int id;
    @JsonAnyProperty public Map<String, Integer> properties;
  }

  @JsonPropertyOrder({"before", "properties", "after", "tail"})
  public static final class OmittedAroundAny {
    public String before;
    @JsonAnyProperty public Map<String, Integer> properties = new LinkedHashMap<>();
    public String after;
    public int tail;
  }

  @JsonPropertyOrder(alphabetic = true)
  public static final class AlphabeticAny {
    public int z;
    @JsonAnyProperty public Map<String, Integer> extras = new LinkedHashMap<>();
    public int a;
  }

  public static final class IndexedAny {
    @JsonAnyProperty public Map<String, Integer> properties = new LinkedHashMap<>();

    @JsonProperty(index = 1)
    public int b;

    @JsonProperty(index = 0)
    public int a;

    public int c;
  }

  @JsonPropertyOrder({"extras"})
  public static final class FinalNameBeforeAny {
    @JsonAnyProperty public Map<String, Integer> extras = new LinkedHashMap<>();

    @JsonProperty("extras")
    public int id;

    public int tail;
  }

  public static final class IgnoredFixed {
    @JsonIgnore public String secret;
    @JsonAnyProperty public Map<String, Integer> properties;
  }

  public static class GenericAnyBase<T> {
    @JsonAnyProperty public Map<String, T> properties;
  }

  public static final class GenericAny extends GenericAnyBase<Integer> {}

  public static final class TreeAny {
    @JsonAnyProperty public TreeMap<String, Integer> properties;
  }

  public static final class EscapedKeyAny {
    @JsonAnyProperty public Map<String, Integer> properties = new LinkedHashMap<>();
  }

  public static final class NestedAny {
    @JsonAnyProperty public Map<String, NestedValue> properties;
  }

  public static final class NestedValue {
    public int value;

    public NestedValue() {}

    private NestedValue(int value) {
      this.value = value;
    }
  }

  public static final class ObjectAny {
    @JsonAnyProperty public Map<String, Object> properties;
  }

  public static final class RecursiveAny {
    public int id;
    @JsonAnyProperty public Map<String, RecursiveAny> properties;
  }

  public static final class ParameterizedAny<T> {
    @JsonAnyProperty public Map<String, T> properties;
  }

  public static final class CreatorAny {
    public final int id;
    @JsonAnyProperty public final Map<String, Integer> properties;
    public final int tail;

    @JsonCreator({"id", "properties", "tail"})
    public CreatorAny(int id, Map<String, Integer> properties, int tail) {
      this.id = id;
      this.properties = properties;
      this.tail = tail;
    }
  }

  @JsonSubTypes(
      property = "kind",
      value = {@JsonSubTypes.Type(value = ExtensibleCircle.class, name = "circle")})
  public interface ExtensibleShape {}

  public static final class ExtensibleCircle implements ExtensibleShape {
    public int radius;
    @JsonAnyProperty public Map<String, Integer> properties = new LinkedHashMap<>();
  }

  public static final class ThrowingGetter {
    @JsonAnyGetter
    public Map<String, Integer> properties() {
      throw new IllegalStateException("getter failure");
    }
  }

  public static final class ThrowingSetter {
    @JsonAnySetter
    public void put(String name, Integer value) {
      throw new IllegalStateException("setter failure");
    }
  }

  public static final class RawAny {
    @SuppressWarnings("rawtypes")
    @JsonAnyProperty
    public Map properties;
  }

  public static final class NonStringKeyAny {
    @JsonAnyProperty public Map<Integer, Integer> properties;
  }

  public static final class WildcardKeyAny {
    @JsonAnyProperty public Map<? extends String, Integer> properties;
  }

  public static final class DuplicateFieldAny {
    @JsonAnyProperty public Map<String, Integer> first;
    @JsonAnyProperty public Map<String, Integer> second;
  }

  public static final class MixedAny {
    @JsonAnyProperty public Map<String, Integer> properties;

    @JsonAnyGetter
    public Map<String, Integer> values() {
      return properties;
    }
  }

  public static final class PropertyOnAny {
    @JsonAnyProperty
    @JsonProperty("renamed")
    public Map<String, Integer> properties;
  }

  public static final class PropertyOnLogicalAny {
    @JsonProperty("renamed")
    public Map<String, Integer> properties;

    @JsonAnyGetter
    public Map<String, Integer> getProperties() {
      return properties;
    }
  }

  public static final class IgnoredAnyGetter {
    @JsonIgnore(ignoreRead = false, ignoreWrite = true)
    public Map<String, Integer> properties;

    @JsonAnyGetter
    public Map<String, Integer> getProperties() {
      return properties;
    }
  }

  public static final class FullyIgnoredAny {
    @JsonAnyProperty @JsonIgnore public Map<String, Integer> properties;
  }

  public static final class StaticAnyGetter {
    @JsonAnyGetter
    public static Map<String, Integer> properties() {
      return null;
    }
  }

  public static final class ArgumentAnyGetter {
    @JsonAnyGetter
    public Map<String, Integer> properties(int ignored) {
      return null;
    }
  }

  public static final class NonMapAnyGetter {
    @JsonAnyGetter
    public String properties() {
      return null;
    }
  }

  public static final class GenericAnyGetter {
    @JsonAnyGetter
    public <T> Map<String, T> properties() {
      return null;
    }
  }

  public static final class DuplicateAnyGetter {
    @JsonAnyGetter
    public Map<String, Integer> first() {
      return null;
    }

    @JsonAnyGetter
    public Map<String, Integer> second() {
      return null;
    }
  }

  public static final class StaticAnySetter {
    @JsonAnySetter
    public static void put(String name, Integer value) {}
  }

  public static final class PrivateAnySetter {
    @JsonAnySetter
    private void put(String name, Integer value) {}
  }

  public static final class ReturningAnySetter {
    @JsonAnySetter
    public int put(String name, Integer value) {
      return 0;
    }
  }

  public static final class BadNameAnySetter {
    @JsonAnySetter
    public void put(Object name, Integer value) {}
  }

  public static final class GenericAnySetter {
    @JsonAnySetter
    public <T> void put(String name, T value) {}
  }

  public static final class VarargsAnySetter {
    @JsonAnySetter
    public void put(String name, Integer... value) {}
  }

  public static final class DuplicateAnySetter {
    @JsonAnySetter
    public void first(String name, Integer value) {}

    @JsonAnySetter
    public void second(String name, Integer value) {}
  }

  public static final class PropertyOnAnySetter {
    @JsonAnySetter
    @JsonProperty("renamed")
    public void put(String name, Integer value) {}
  }

  public static final class IncompatibleAnyMethods {
    @JsonAnyGetter
    public Map<String, Integer> properties() {
      return null;
    }

    @JsonAnySetter
    public void put(String name, Long value) {}
  }

  public static final class CreatorAnySetter {
    public final int id;

    @JsonCreator({"id"})
    public CreatorAnySetter(int id) {
      this.id = id;
    }

    @JsonAnySetter
    public void put(String name, Integer value) {}
  }

  public static final class ParameterLocalAny {
    public final int id;
    @JsonAnyProperty public final Map<String, Integer> properties;

    @JsonCreator
    public ParameterLocalAny(
        @JsonProperty("id") int id, @JsonProperty("properties") Map<String, Integer> properties) {
      this.id = id;
      this.properties = properties;
    }
  }

  public static final class WriteOnlyCreatorAny {
    public final int id;

    @JsonAnyProperty
    @JsonIgnore(ignoreRead = true, ignoreWrite = false)
    public final Map<String, Integer> properties;

    @JsonCreator({"id", "properties"})
    public WriteOnlyCreatorAny(int id, Map<String, Integer> properties) {
      this.id = id;
      this.properties = properties;
    }
  }

  @JsonPropertyOrder({"putProperty"})
  public static final class OrderedSetterOnly {
    @JsonAnySetter
    public void putProperty(String name, Integer value) {}
  }

  @JsonPropertyOrder({"properties"})
  public static final class OrderedInputOnly {
    @JsonAnyProperty
    @JsonIgnore(ignoreRead = false, ignoreWrite = true)
    public Map<String, Integer> properties;
  }
}
