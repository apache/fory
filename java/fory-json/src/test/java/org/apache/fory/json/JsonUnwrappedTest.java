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

import static org.apache.fory.json.JsonTestSupport.primaryTypeResolver;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonBase64;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;
import org.apache.fory.json.annotation.JsonRawValue;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonUnwrapped;
import org.apache.fory.json.annotation.JsonValue;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.reflect.TypeRef;
import org.testng.SkipException;
import org.testng.annotations.Test;

public class JsonUnwrappedTest extends ForyJsonTestModels {
  @Test(dataProvider = "enableCodegen")
  public void mutableRoundTrip(boolean codegen) {
    ForyJson json = newJson(codegen);
    Person value = new Person();
    value.id = 7;
    value.name = new Name();
    value.name.first = "Ada";
    value.name.last = "Lovelace";
    String expected = "{\"id\":7,\"name_first_value\":\"Ada\",\"name_last_value\":\"Lovelace\"}";
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    assertPerson(json.fromJson(expected, Person.class));
    assertPerson(json.fromJson(expected.getBytes(StandardCharsets.UTF_8), Person.class));

    Person missing = json.fromJson("{\"id\":8}", Person.class);
    assertEquals(missing.id, 8);
    assertEquals(missing.name.first, "initial");
    value.name = null;
    assertEquals(json.toJson(value), "{\"id\":7}");
  }

  @Test(dataProvider = "enableCodegen")
  public void creatorChildAndParent(boolean codegen) {
    ForyJson json = newJson(codegen);
    CreatorName name = new CreatorName("Grace", "Hopper");
    CreatorPerson value = new CreatorPerson(9, name);
    String expected = "{\"id\":9,\"first\":\"Grace\",\"last\":\"Hopper\"}";
    assertEquals(json.toJson(value), expected);
    CreatorPerson decoded = json.fromJson(expected, CreatorPerson.class);
    assertEquals(decoded.id, 9);
    assertEquals(decoded.name.first, "Grace");
    assertEquals(decoded.name.last, "Hopper");

    CreatorPerson missing = json.fromJson("{\"id\":10}", CreatorPerson.class);
    assertEquals(missing.id, 10);
    assertEquals(missing.name, null);
  }

  @Test(dataProvider = "enableCodegen")
  public void creatorOnlyGroup(boolean codegen) {
    ForyJson json = newJson(codegen);
    CreatorOnly value = json.fromJson("{\"v_name\":\"child\"}", CreatorOnly.class);
    assertEquals(value.result.name, "child");
    assertEquals(json.fromJson("{}", CreatorOnly.class).result, null);
  }

  @Test(dataProvider = "enableCodegen")
  public void nestedNamesAndAny(boolean codegen) {
    ForyJson json = newJson(codegen);
    NestedParent value = new NestedParent();
    value.address = new Address();
    value.address.city = "Paris";
    value.address.geo = new Geo();
    value.address.geo.lat = 48;
    value.extra.put("other", "value");
    String expected =
        "{\"id\":3,\"addr_city_x\":\"Paris\",\"addr_geo_lat_v_x\":48," + "\"other\":\"value\"}";
    assertEquals(json.toJson(value), expected);
    NestedParent decoded = json.fromJson(expected, NestedParent.class);
    assertEquals(decoded.address.city, "Paris");
    assertEquals(decoded.address.geo.lat, 48);
    assertEquals(decoded.extra.get("other"), "value");
    assertEquals(decoded.extra.containsKey("addr_city_x"), false);

    value.extra.put("addr_city_x", "conflict");
    assertThrows(ForyJsonException.class, () -> json.toJson(value));
  }

  @Test
  public void rejectUnsupportedSchemas() {
    ForyJson json = ForyJson.builder().withCodegen(false).build();
    assertThrows(ForyJsonException.class, () -> json.toJson(new MapParent()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new RecursiveParent()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new CollisionParent()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new ConflictingAnyField()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new ConflictingValueField()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new ConflictingValueMethod()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new IgnoredUnwrappedField()));
    ForyJson fieldJson = ForyJson.builder().withFieldMode(true).withCodegen(false).build();
    assertThrows(ForyJsonException.class, () -> fieldJson.toJson(new IgnoredUnwrappedField()));
    assertThrows(
        ForyJsonException.class,
        () -> json.toJson(new DiscriminatorChild(), DiscriminatorParent.class));
  }

  @Test(dataProvider = "enableCodegen")
  public void recordParentAndChild(boolean codegen) throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    Class<?> type =
        compileRecordClass(
            "JsonUnwrappedRecord",
            "package org.apache.fory.json.records;\n"
                + "import org.apache.fory.json.annotation.JsonUnwrapped;\n"
                + "public record JsonUnwrappedRecord(int id, "
                + "@JsonUnwrapped(prefix=\"n_\") Name name) {\n"
                + "  public record Name(String first, String last) {}\n"
                + "}\n");
    Class<?> nameType = Class.forName(type.getName() + "$Name", true, type.getClassLoader());
    Object name = nameType.getConstructor(String.class, String.class).newInstance("Alan", "Turing");
    Object value = type.getConstructor(int.class, nameType).newInstance(11, name);
    ForyJson json = newJson(codegen);
    String expected = "{\"id\":11,\"n_first\":\"Alan\",\"n_last\":\"Turing\"}";
    assertEquals(json.toJson(value), expected);
    Object decoded = json.fromJson(expected, type);
    assertEquals(type.getMethod("id").invoke(decoded), Integer.valueOf(11));
    Object decodedName = type.getMethod("name").invoke(decoded);
    assertEquals(nameType.getMethod("first").invoke(decodedName), "Alan");
    assertEquals(nameType.getMethod("last").invoke(decodedName), "Turing");
    Object missing = json.fromJson("{\"id\":12}", type);
    assertEquals(type.getMethod("name").invoke(missing), null);
  }

  @Test(dataProvider = "enableCodegen")
  public void recordConstructionOrder(boolean codegen) throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    Class<?> type =
        compileRecordClass(
            "JsonUnwrappedAnyRecord",
            "package org.apache.fory.json.records;\n"
                + "import java.util.Map;\n"
                + "import org.apache.fory.json.annotation.JsonAnyProperty;\n"
                + "import org.apache.fory.json.annotation.JsonUnwrapped;\n"
                + "public record JsonUnwrappedAnyRecord("
                + "@JsonUnwrapped(prefix=\"n_\") Name name, int id, "
                + "@JsonAnyProperty Map<String, Integer> extra) {\n"
                + "  public record Name(String first) {}\n"
                + "}\n");
    ForyJson json = newJson(codegen);
    Object decoded = json.fromJson("{\"unknown\":3,\"id\":2,\"n_first\":\"Ada\"}", type);
    Object name = type.getMethod("name").invoke(decoded);
    assertEquals(name.getClass().getMethod("first").invoke(name), "Ada");
    assertEquals(type.getMethod("id").invoke(decoded), Integer.valueOf(2));
    assertEquals(type.getMethod("extra").invoke(decoded), Collections.singletonMap("unknown", 3));
  }

  @Test
  public void recordConstructorValidation() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    Class<?> type =
        compileRecordClass(
            "JsonUnwrappedOverload",
            "package org.apache.fory.json.records;\n"
                + "import org.apache.fory.json.annotation.JsonUnwrapped;\n"
                + "public record JsonUnwrappedOverload(\n"
                + "    @JsonUnwrapped(prefix=\"n_\") Name name, int id) {\n"
                + "  public JsonUnwrappedOverload(\n"
                + "      @JsonUnwrapped(prefix=\"n_\") Name name, long id) {\n"
                + "    this(name, (int) id);\n"
                + "  }\n"
                + "  public record Name(String value) {}\n"
                + "}\n");
    assertThrows(
        ForyJsonException.class,
        () -> ForyJson.builder().withCodegen(false).build().fromJson("{}", type));
  }

  @Test
  public void generatedCapabilities() {
    ForyJson json = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeResolver resolver = primaryTypeResolver(json);
    ObjectCodec<Person> owner = resolver.getObjectCodec(Person.class);
    JsonTypeInfo info = resolver.getTypeInfo(Person.class, Person.class);
    Person value = person();
    String expected = "{\"id\":7,\"name_first_value\":\"Ada\",\"name_last_value\":\"Lovelace\"}";

    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    assertPerson(json.fromJson(expected, Person.class));
    assertPerson(json.fromJson(expected.getBytes(StandardCharsets.UTF_8), Person.class));
    resolver.latin1Reader(owner);
    resolver.utf16Reader(owner);

    assertNotSame(info.stringWriter(), owner);
    assertNotSame(info.utf8Writer(), owner);
    assertNotSame(info.latin1Reader(), owner);
    assertNotSame(info.utf16Reader(), owner);
    assertNotSame(info.utf8Reader(), owner);
    assertEquals(
        Arrays.stream(info.stringWriter().getClass().getDeclaredFields())
            .anyMatch(field -> field.getName().equals("owner")),
        false);
    assertEquals(
        Arrays.stream(info.utf8Writer().getClass().getDeclaredFields())
            .anyMatch(field -> field.getName().equals("owner")),
        false);
  }

  @Test
  public void generatedChildBeforeParent() {
    ForyJson json = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeResolver resolver = primaryTypeResolver(json);
    ObjectCodec<Name> child = resolver.getObjectCodec(Name.class);
    Name name = new Name();
    name.first = "generated";
    name.last = "child";
    assertEquals(json.toJson(name), "{\"first\":\"generated\",\"last\":\"child\"}");
    assertNotSame(resolver.getTypeInfo(Name.class, Name.class).stringWriter(), child);

    ObjectCodec<Person> parent = resolver.getObjectCodec(Person.class);
    assertSame(parent.unwrappedInfo().declarations()[0].childCodec(), child);
    assertEquals(
        json.toJson(person()),
        "{\"id\":7,\"name_first_value\":\"Ada\",\"name_last_value\":\"Lovelace\"}");
  }

  @Test
  public void writeOnlyAnyReader() {
    ForyJson json = ForyJson.builder().withAsyncCompilation(false).build();
    WriteOnlyAnyParent value =
        json.fromJson(
            "{\"v_first\":\"read\",\"dynamic\":2,\"extra\":{\"ignored\":3}}",
            WriteOnlyAnyParent.class);
    assertEquals(value.name.first, "read");
    assertEquals(value.extra.isEmpty(), true);
    JsonTypeResolver resolver = primaryTypeResolver(json);
    ObjectCodec<WriteOnlyAnyParent> owner = resolver.getObjectCodec(WriteOnlyAnyParent.class);
    assertNotSame(resolver.latin1Reader(owner), owner);
  }

  @Test
  public void deepGeneratedCapabilities() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Dynamic deep-model test requires JDK 17+");
    }
    int depth = 128;
    StringBuilder source =
        new StringBuilder(
            "package org.apache.fory.json.records;\n"
                + "import org.apache.fory.json.annotation.JsonUnwrapped;\n"
                + "public class DeepUnwrappedModel {\n"
                + "  @JsonUnwrapped(prefix=\"p\", suffix=\"s\") public Level0 child;\n");
    for (int i = 0; i < depth; i++) {
      source.append("  public static class Level").append(i).append(" {\n");
      if (i + 1 == depth) {
        source.append("    public int value;\n");
      } else {
        source
            .append("    @JsonUnwrapped(prefix=\"p\", suffix=\"s\") public Level")
            .append(i + 1)
            .append(" child;\n");
      }
      source.append("  }\n");
    }
    source.append("}\n");
    Class<?> type = compileRecordClass("DeepUnwrappedModel", source.toString());
    StringBuilder name = new StringBuilder(depth * 2 + "value".length());
    for (int i = 0; i < depth; i++) {
      name.append('p');
    }
    name.append("value");
    for (int i = 0; i < depth; i++) {
      name.append('s');
    }
    String expected = "{\"" + name + "\":7}";

    ForyJson interpreted = ForyJson.builder().withCodegen(false).build();
    assertEquals(interpreted.toJson(interpreted.fromJson(expected, type)), expected);

    ForyJson generated = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeResolver resolver = primaryTypeResolver(generated);
    ObjectCodec<?> owner = resolver.getObjectCodec(type);
    JsonTypeInfo info = resolver.getTypeInfo(type, type);
    Object value = generated.fromJson(expected, type);
    assertEquals(generated.toJson(value), expected);
    assertEquals(new String(generated.toJsonBytes(value), StandardCharsets.UTF_8), expected);
    assertEquals(
        generated.toJson(generated.fromJson(expected.getBytes(StandardCharsets.UTF_8), type)),
        expected);
    resolver.latin1Reader(owner);
    resolver.utf16Reader(owner);
    assertNotSame(info.stringWriter(), owner);
    assertNotSame(info.utf8Writer(), owner);
    assertNotSame(info.latin1Reader(), owner);
    assertNotSame(info.utf16Reader(), owner);
    assertNotSame(info.utf8Reader(), owner);
  }

  @Test
  public void wideGeneratedCapabilities() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Dynamic wide-model test requires JDK 17+");
    }
    StringBuilder source =
        new StringBuilder(
            "package org.apache.fory.json.records;\n"
                + "import org.apache.fory.json.annotation.JsonUnwrapped;\n"
                + "public class WideUnwrappedModel {\n");
    StringBuilder expected = new StringBuilder("{");
    for (int i = 0; i < 20; i++) {
      source.append("  public int d").append(i).append(";\n");
      if (expected.length() > 1) {
        expected.append(',');
      }
      expected.append("\"d").append(i).append("\":").append(i);
    }
    source.append("  @JsonUnwrapped(prefix=\"a_\") public WideChild child;\n");
    for (int i = 0; i < 40; i++) {
      if (expected.length() > 1) {
        expected.append(',');
      }
      expected.append("\"a_f").append(i).append("\":").append(i);
    }
    for (int i = 20; i < 40; i++) {
      source.append("  public int d").append(i).append(";\n");
      expected.append(",\"d").append(i).append("\":").append(i);
    }
    source.append("  @JsonUnwrapped(prefix=\"b_\") public WideChild optional;\n");
    source.append("  public static class WideChild {\n");
    for (int i = 0; i < 40; i++) {
      source.append("    public int f").append(i).append(";\n");
    }
    source.append("  }\n}\n");
    expected.append('}');
    Class<?> type = compileRecordClass("WideUnwrappedModel", source.toString());
    ForyJson json = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeResolver resolver = primaryTypeResolver(json);
    ObjectCodec<?> owner = resolver.getObjectCodec(type);
    JsonTypeInfo info = resolver.getTypeInfo(type, type);

    Object value = json.fromJson(expected.toString(), type);
    assertEquals(json.toJson(value), expected.toString());
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected.toString());
    assertEquals(
        json.toJson(json.fromJson(expected.toString().getBytes(StandardCharsets.UTF_8), type)),
        expected.toString());
    resolver.latin1Reader(owner);
    resolver.utf16Reader(owner);

    assertNotSame(info.stringWriter(), owner);
    assertNotSame(info.utf8Writer(), owner);
    assertNotSame(info.latin1Reader(), owner);
    assertNotSame(info.utf16Reader(), owner);
    assertNotSame(info.utf8Reader(), owner);
  }

  @Test(dataProvider = "enableCodegen")
  public void partialNullAndAccessors(boolean codegen) {
    ForyJson json = newJson(codegen);
    AccessorParent value = new AccessorParent();
    value.child = new AccessorChild();
    value.child.name = "write";
    assertEquals(json.toJson(value), "{\"v_name\":\"write\",\"v_rank\":4}");
    assertEquals(value.getterCalls, 1);

    AccessorParent decoded =
        json.fromJson("{\"v_name\":null,\"v_rank\":5,\"v_name\":\"last\"}", AccessorParent.class);
    assertEquals(decoded.setterCalls, 1);
    assertEquals(decoded.child.name, "last");
    assertEquals(decoded.child.rank, 5);
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"v_rank\":null}", AccessorParent.class));

    AccessorParent partial = json.fromJson("{\"v_name\":\"partial\"}", AccessorParent.class);
    assertEquals(partial.child.name, "partial");
    assertEquals(partial.child.rank, 4);
  }

  @Test(dataProvider = "enableCodegen")
  public void orderingAndFactory(boolean codegen) {
    ForyJson json = newJson(codegen);
    OrderedParent value = new OrderedParent();
    value.group = FactoryChild.create("A", "B");
    String expected = "{\"tail\":2,\"g_second\":\"B\",\"g_first\":\"A\",\"head\":1}";
    assertEquals(json.toJson(value), expected);
    FactoryChild.creations = 0;
    OrderedParent decoded = json.fromJson(expected, OrderedParent.class);
    assertEquals(decoded.group.first, "A");
    assertEquals(decoded.group.second, "B");
    assertEquals(FactoryChild.creations, 1);

    OrderPrecedenceParent precedence = new OrderPrecedenceParent();
    precedence.group = new OrderPrecedenceChild();
    assertEquals(json.toJson(precedence), "{\"group\":1,\"value\":2}");
  }

  @Test(dataProvider = "enableCodegen")
  public void anyAndDirectionalNames(boolean codegen) {
    ForyJson json = newJson(codegen);
    DirectionalParent value =
        json.fromJson(
            "{\"child\":{\"writeOnly\":1},\"v_writeOnly\":2,\"unknown\":3}",
            DirectionalParent.class);
    assertEquals(value.child, null);
    assertEquals(value.extra.get("child") instanceof Map, true);
    assertEquals(value.extra.containsKey("v_writeOnly"), false);
    assertEquals(((Number) value.extra.get("unknown")).intValue(), 3);
  }

  @Test
  public void parameterizedParent() {
    ForyJson json = newJson(false);
    TypeRef<GenericParent<String>> type = new TypeRef<GenericParent<String>>() {};
    GenericParent<String> value =
        json.fromJson("{\"payload\":\"value\",\"n_first\":\"generic\"}", type);
    assertEquals(value.payload, "value");
    assertEquals(value.name.first, "generic");
    assertThrows(ForyJsonException.class, () -> json.toJson(new GenericChildParent()));
  }

  @Test(dataProvider = "enableCodegen")
  public void customLeafAndOrdinaryRecursion(boolean codegen) {
    ForyJson json = newJson(codegen);
    CustomLeafParent value = new CustomLeafParent();
    value.child = new CustomLeafChild();
    value.child.name = "codec";
    assertEquals(json.toJson(value), "{\"v_name\":\"A:codec\"}");
    assertEquals(
        json.fromJson("{\"v_name\":\"A:codec\"}", CustomLeafParent.class).child.name, "codec");

    OrdinaryRecursive recursive = new OrdinaryRecursive();
    recursive.name = new Name();
    recursive.name.first = "finite";
    assertEquals(json.toJson(recursive), "{\"first\":\"finite\"}");

    RecursiveLeafParent parent =
        json.fromJson(
            "{\"v_name\":\"root\",\"v_next\":{\"name\":\"nested\"}}", RecursiveLeafParent.class);
    assertEquals(parent.child.name, "root");
    assertEquals(parent.child.next.name, "nested");
  }

  @Test(dataProvider = "enableCodegen")
  public void valueRepresentations(boolean codegen) {
    ForyJson json = newJson(codegen);
    ValueRepresentationParent value = new ValueRepresentationParent();
    value.child = new ValueRepresentationChild();
    value.child.raw = "{\"id\":1}";
    value.child.bytes = new byte[] {1, 2, 3};
    assertEquals(json.toJson(value), "{\"v_raw\":{\"id\":1},\"v_bytes\":\"AQID\"}");

    ValueRepresentationParent decoded =
        json.fromJson("{\"v_raw\":\"text\",\"v_bytes\":\"AQID\"}", ValueRepresentationParent.class);
    assertEquals(decoded.child.raw, "text");
    assertEquals(decoded.child.bytes, new byte[] {1, 2, 3});

    assertThrows(ForyJsonException.class, () -> json.toJson(new ValueObjectParent()));
  }

  @Test(dataProvider = "enableCodegen")
  public void inlineSubtype(boolean codegen) {
    ForyJson json = newJson(codegen);
    InlineChild value = new InlineChild();
    value.id = 16;
    value.name = new Name();
    value.name.first = "inline";
    value.extra.put("dynamic", 17);
    String encoded = json.toJson(value, InlineParent.class);
    InlineParent decoded = json.fromJson(encoded, InlineParent.class);
    assertEquals(decoded instanceof InlineChild, true);
    InlineChild child = (InlineChild) decoded;
    assertEquals(child.id, 16);
    assertEquals(child.name.first, "inline");
    assertEquals(((Number) child.extra.get("dynamic")).intValue(), 17);

    InlineChild recursive =
        (InlineChild)
            json.fromJson(
                "{\"kind\":\"inline\",\"id\":18," + "\"child_nested\":{\"id\":19,\"kind\":20}}",
                InlineParent.class);
    assertEquals(recursive.holder.nested.id, 19);
    assertEquals(((Number) recursive.holder.nested.extra.get("kind")).intValue(), 20);
  }

  @Test(dataProvider = "enableCodegen")
  public void escapedNames(boolean codegen) {
    ForyJson json = newJson(codegen);
    EscapedParent value = new EscapedParent();
    value.child = new EscapedChild();
    value.child.value = "值";
    String escape = "\\";
    String stringExpected =
        "{\"" + escape + "u524d\\\"\\\\_" + escape + "u540d_" + escape + "u540e\":\"值\"}";
    String utf8Expected = "{\"前\\\"\\\\_名_后\":\"值\"}";
    assertEquals(json.toJson(value), stringExpected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), utf8Expected);
    assertEquals(json.fromJson(stringExpected, EscapedParent.class).child.value, "值");
    assertEquals(
        json.fromJson(stringExpected.getBytes(StandardCharsets.UTF_8), EscapedParent.class)
            .child
            .value,
        "值");
    assertEquals(json.fromJson(utf8Expected, EscapedParent.class).child.value, "值");
  }

  private static void assertPerson(Person value) {
    assertEquals(value.id, 7);
    assertEquals(value.name.first, "Ada");
    assertEquals(value.name.last, "Lovelace");
  }

  private static Person person() {
    Person value = new Person();
    value.id = 7;
    value.name = new Name();
    value.name.first = "Ada";
    value.name.last = "Lovelace";
    return value;
  }

  public static class Person {
    public int id;

    @JsonUnwrapped(prefix = "name_", suffix = "_value")
    public Name name = initialName();

    private static Name initialName() {
      Name name = new Name();
      name.first = "initial";
      return name;
    }
  }

  public static class Name {
    public String first;
    public String last;
  }

  public static final class CreatorPerson {
    public final int id;

    @JsonUnwrapped public final CreatorName name;

    @JsonCreator({"id", "name"})
    public CreatorPerson(int id, CreatorName name) {
      this.id = id;
      this.name = name;
    }
  }

  public static final class CreatorName {
    public final String first;
    public final String last;

    @JsonCreator({"first", "last"})
    public CreatorName(String first, String last) {
      this.first = first;
      this.last = last;
    }
  }

  public static final class CreatorOnly {
    public final CreatorOnlyChild result;

    @JsonCreator
    public CreatorOnly(
        @JsonProperty("input") @JsonUnwrapped(prefix = "v_") CreatorOnlyChild input) {
      result = input;
    }
  }

  public static class CreatorOnlyChild {
    public String name;
  }

  public static class NestedParent {
    public int id = 3;

    @JsonUnwrapped(prefix = "addr_", suffix = "_x")
    public Address address;

    @JsonAnyProperty public Map<String, Object> extra = new LinkedHashMap<>();
  }

  public static class Address {
    public String city;

    @JsonUnwrapped(prefix = "geo_", suffix = "_v")
    public Geo geo;
  }

  public static class Geo {
    public int lat;
  }

  public static class MapParent {
    @JsonUnwrapped public Map<String, Object> value;
  }

  public static class RecursiveParent {
    @JsonUnwrapped public RecursiveParent child;
  }

  public static class CollisionParent {
    public int id;

    @JsonUnwrapped public CollisionChild child;
  }

  public static class CollisionChild {
    public int id;
  }

  public static class AccessorParent {
    private AccessorChild child;
    @JsonIgnore int getterCalls;
    @JsonIgnore int setterCalls;

    @JsonUnwrapped(prefix = "v_")
    public AccessorChild getChild() {
      getterCalls++;
      return child;
    }

    @JsonUnwrapped(prefix = "v_")
    public void setChild(AccessorChild child) {
      setterCalls++;
      this.child = child;
    }
  }

  public static class AccessorChild {
    public String name = "initial";
    public int rank = 4;
  }

  @JsonPropertyOrder({"tail", "group", "head"})
  public static class OrderedParent {
    public int head = 1;

    @JsonUnwrapped(prefix = "g_")
    public FactoryChild group;

    public int tail = 2;
  }

  @JsonPropertyOrder({"second", "first"})
  public static final class FactoryChild {
    static int creations;
    public final String first;
    public final String second;

    private FactoryChild(String first, String second) {
      this.first = first;
      this.second = second;
    }

    @JsonCreator({"first", "second"})
    public static FactoryChild create(String first, String second) {
      creations++;
      return new FactoryChild(first, second);
    }
  }

  @JsonPropertyOrder("group")
  public static class OrderPrecedenceParent {
    @JsonUnwrapped public OrderPrecedenceChild group;

    @JsonProperty("group")
    public int direct = 1;
  }

  public static class OrderPrecedenceChild {
    public int value = 2;
  }

  public static class DirectionalParent {
    @JsonUnwrapped(prefix = "v_")
    public DirectionalChild child;

    @JsonAnyProperty public Map<String, Object> extra = new LinkedHashMap<>();
  }

  public static class DirectionalChild {
    @JsonIgnore(ignoreRead = true, ignoreWrite = false)
    public int writeOnly;

    public int readable;
  }

  public static class WriteOnlyAnyParent {
    @JsonUnwrapped(prefix = "v_")
    public Name name;

    @JsonAnyProperty
    @JsonIgnore(ignoreRead = true, ignoreWrite = false)
    public Map<String, Object> extra = new LinkedHashMap<>();
  }

  public static class GenericParent<T> {
    public T payload;

    @JsonUnwrapped(prefix = "n_")
    public Name name;
  }

  public static class GenericChildParent {
    @JsonUnwrapped public GenericChild<String> child;
  }

  public static class GenericChild<T> {
    public T value;
  }

  public static class CustomLeafParent {
    @JsonUnwrapped(prefix = "v_")
    public CustomLeafChild child;
  }

  public static class CustomLeafChild {
    @JsonCodec(JsonCodecAnnotationTest.AStringCodec.class)
    public String name;
  }

  public static class OrdinaryRecursive {
    @JsonUnwrapped public Name name;
    public OrdinaryRecursive next;
  }

  public static class RecursiveLeafParent {
    @JsonUnwrapped(prefix = "v_")
    public RecursiveLeaf child;
  }

  public static class RecursiveLeaf {
    public String name;
    public RecursiveLeaf next;
  }

  public static class ConflictingAnyField {
    @JsonAnyProperty @JsonUnwrapped public Map<String, Object> values;
  }

  public static class ConflictingValueField {
    @JsonValue @JsonUnwrapped public String value;
  }

  public static class ConflictingValueMethod {
    @JsonValue
    @JsonUnwrapped
    public String value() {
      return "value";
    }
  }

  public static class IgnoredUnwrappedField {
    @JsonIgnore @JsonUnwrapped public Name name;
  }

  public static class ValueRepresentationParent {
    @JsonUnwrapped(prefix = "v_")
    public ValueRepresentationChild child;
  }

  public static class ValueRepresentationChild {
    @JsonRawValue public String raw;
    @JsonBase64 public byte[] bytes;
  }

  public static class ValueObjectParent {
    @JsonUnwrapped public ValueObject child;
  }

  public static class ValueObject {
    @JsonValue public String value;
  }

  @JsonSubTypes(
      property = "kind",
      value = {@JsonSubTypes.Type(value = DiscriminatorChild.class, name = "child")})
  public abstract static class DiscriminatorParent {
    @JsonUnwrapped public DiscriminatorValue value;
  }

  public static final class DiscriminatorChild extends DiscriminatorParent {}

  public static class DiscriminatorValue {
    public String kind;
  }

  @JsonSubTypes(
      property = "kind",
      value = {@JsonSubTypes.Type(value = InlineChild.class, name = "inline")})
  public abstract static class InlineParent {}

  public static final class InlineChild extends InlineParent {
    public int id;

    @JsonUnwrapped(prefix = "v_")
    public Name name;

    @JsonUnwrapped(prefix = "child_")
    public InlineHolder holder;

    @JsonAnyProperty public Map<String, Object> extra = new LinkedHashMap<>();
  }

  public static class InlineHolder {
    public InlineChild nested;
  }

  public static class EscapedParent {
    @JsonUnwrapped(prefix = "前\"\\_", suffix = "_后")
    public EscapedChild child;
  }

  public static class EscapedChild {
    @JsonProperty("名")
    public String value;
  }
}
