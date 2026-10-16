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
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonAnySetter;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonDuplicateProperties;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonProperty.NullHandling;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonUnwrapped;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonDuplicatePropertiesTest extends ForyJsonTestModels {
  private static final int REPRESENTATIONS = 4;

  @Factory(dataProvider = "enableCodegen")
  public JsonDuplicatePropertiesTest(boolean codegen) {
    super(codegen);
  }

  private static <T> T read(ForyJson json, String text, Class<T> type, int representation) {
    switch (representation) {
      case 0:
        return json.fromJson(text, type);
      case 1:
        // A trailing unknown member makes the input UTF-16 while known members keep their order.
        return json.fromJson(
            text.substring(0, text.length() - 1) + ",\"ignoredUnicode\":\"\u4e2d\"}", type);
      case 2:
        return json.fromJson(text.getBytes(StandardCharsets.UTF_8), type);
      default:
        return json.fromJson(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), type);
    }
  }

  private static void assertDuplicate(ForyJson json, String text, Class<?> type, String property) {
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      int current = representation;
      ForyJsonException error =
          expectThrows(ForyJsonException.class, () -> read(json, text, type, current));
      assertTrue(
          String.valueOf(error.getMessage()).contains("Duplicate JSON property " + property),
          String.valueOf(error.getMessage()));
    }
  }

  @Test
  public void lastValueWinsByDefault() {
    ForyJson json = newJson();
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      Bean bean = read(json, "{\"a\":1,\"b\":2,\"a\":3}", Bean.class, representation);
      assertEquals(bean.a, 3);
      assertEquals(bean.b, 2);
    }
  }

  @Test
  public void beanMembers() {
    ForyJson json = newJsonBuilder().failOnDuplicateProperties(true).build();
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      Bean bean =
          read(json, "{\"a\":1,\"c\":3,\"b\":2,\"x\":0,\"x\":0}", Bean.class, representation);
      assertEquals(bean.a, 1);
      assertEquals(bean.b, 2);
      assertEquals(bean.c, 3);
      bean = read(json, "{\"c\":3,\"a\":1}", Bean.class, representation);
      assertEquals(bean.a, 1);
      assertEquals(bean.c, 3);
    }
    assertDuplicate(json, "{\"a\":1,\"a\":2}", Bean.class, "a");
    assertDuplicate(json, "{\"a\":1,\"b\":2,\"c\":3,\"a\":4}", Bean.class, "a");
    assertDuplicate(json, "{\"a\":1,\"c\":3,\"c\":4}", Bean.class, "c");
    assertDuplicate(json, "{\"b\":2,\"x\":0,\"b\":2}", Bean.class, "b");
    assertDuplicate(json, "{\"c\":3,\"a\":1,\"c\":3}", Bean.class, "c");
    assertDuplicate(json, "{\"x\":0,\"a\":1,\"b\":2,\"a\":1}", Bean.class, "a");
  }

  @Test
  public void explicitNullCounts() {
    ForyJson json =
        newJsonBuilder().failOnDuplicateProperties(true).onNullRead(NullHandling.SKIP).build();
    assertDuplicate(json, "{\"name\":null,\"name\":\"x\"}", Named.class, "name");
    assertDuplicate(json, "{\"name\":\"x\",\"name\":null}", Named.class, "name");
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      assertEquals(read(json, "{\"name\":null}", Named.class, representation).name, "initial");
    }
  }

  @Test
  public void classSetting() {
    ForyJson lenient = newJson();
    assertDuplicate(lenient, "{\"a\":1,\"a\":2}", StrictBean.class, "a");
    ForyJson strict = newJsonBuilder().failOnDuplicateProperties(true).build();
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      assertEquals(read(strict, "{\"a\":1,\"a\":2}", LenientBean.class, representation).a, 2);
    }
  }

  @Test
  public void nestedObjectsKeepTheirSetting() {
    ForyJson json = newJson();
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      StrictParent parent =
          read(
              json,
              "{\"child\":{\"a\":1,\"a\":2},\"id\":5,\"other\":{\"a\":3}}",
              StrictParent.class,
              representation);
      assertEquals(parent.child.a, 2);
      assertEquals(parent.other.a, 3);
      assertEquals(parent.id, 5);
    }
    assertDuplicate(json, "{\"child\":{\"a\":1},\"child\":{\"a\":2}}", StrictParent.class, "child");
    assertDuplicate(json, "{\"other\":{\"a\":1,\"a\":2}}", StrictParent.class, "a");
  }

  @Test
  public void creatorMembers() {
    ForyJson json = newJsonBuilder().failOnDuplicateProperties(true).build();
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      Created created = read(json, "{\"b\":\"y\",\"a\":1}", Created.class, representation);
      assertEquals(created.a, 1);
      assertEquals(created.b, "y");
    }
    assertDuplicate(json, "{\"a\":1,\"a\":2}", Created.class, "a");
    assertDuplicate(json, "{\"a\":1,\"b\":\"y\",\"b\":\"z\"}", Created.class, "b");
    assertDuplicate(json, "{\"a\":1,\"b\":\"y\",\"a\":2}", Created.class, "a");
    assertDuplicate(json, "{\"b\":\"y\",\"a\":1,\"b\":\"z\"}", Created.class, "b");
    assertDuplicate(json, "{\"x\":0,\"a\":1,\"a\":1}", Created.class, "a");
  }

  @Test
  public void anyMembers() {
    ForyJson json = newJsonBuilder().failOnDuplicateProperties(true).build();
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      AnyBean bean = read(json, "{\"id\":1,\"x\":2,\"x\":3}", AnyBean.class, representation);
      assertEquals(bean.id, 1);
      assertEquals(((Number) bean.values.get("x")).intValue(), 3);
      CreatedAny created =
          read(json, "{\"x\":2,\"id\":1,\"x\":3}", CreatedAny.class, representation);
      assertEquals(created.id, 1);
      assertEquals(((Number) created.values.get("x")).intValue(), 3);
    }
    assertDuplicate(json, "{\"id\":1,\"x\":2,\"id\":1}", AnyBean.class, "id");
    assertDuplicate(json, "{\"id\":1,\"x\":2,\"id\":1}", CreatedAny.class, "id");
  }

  @Test
  public void unwrappedMembers() {
    ForyJson json = newJsonBuilder().failOnDuplicateProperties(true).build();
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      Outer outer = read(json, "{\"y\":2,\"id\":1,\"x\":3}", Outer.class, representation);
      assertEquals(outer.id, 1);
      assertEquals(outer.inner.x, 3);
      assertEquals(outer.inner.y, 2);
    }
    assertDuplicate(json, "{\"id\":1,\"x\":3,\"id\":1}", Outer.class, "id");
    assertDuplicate(json, "{\"x\":3,\"id\":1,\"x\":4}", Outer.class, "x");
  }

  @Test
  public void wideBean() {
    ForyJson json = newJsonBuilder().failOnDuplicateProperties(true).build();
    StringBuilder ordered = new StringBuilder("{");
    for (int i = 0; i < 70; i++) {
      ordered.append(i == 0 ? "" : ",").append("\"f").append(i).append("\":").append(i);
    }
    String inOrder = ordered.append('}').toString();
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      Wide wide = read(json, inOrder, Wide.class, representation);
      assertEquals(wide.f0, 0);
      assertEquals(wide.f69, 69);
      wide = read(json, "{\"f69\":1,\"f0\":2,\"f64\":3}", Wide.class, representation);
      assertEquals(wide.f64, 3);
    }
    assertDuplicate(
        json, inOrder.substring(0, inOrder.length() - 1) + ",\"f66\":1}", Wide.class, "f66");
    assertDuplicate(
        json, inOrder.substring(0, inOrder.length() - 1) + ",\"f64\":1}", Wide.class, "f64");
    assertDuplicate(
        json, inOrder.substring(0, inOrder.length() - 1) + ",\"f3\":1}", Wide.class, "f3");
    assertDuplicate(json, "{\"f68\":1,\"f1\":2,\"f68\":3}", Wide.class, "f68");
  }

  @Test
  public void mixinSetting() {
    ForyJson json = newJsonBuilder().registerMixin(StrictMixin.class).build();
    assertDuplicate(json, "{\"a\":1,\"a\":2}", MixinTarget.class, "a");
  }

  @Test
  public void instancesKeepTheirSetting() {
    ForyJson strict = newJsonBuilder().failOnDuplicateProperties(true).build();
    ForyJson lenient = newJson();
    String text = "{\"a\":1,\"b\":2,\"a\":3}";
    assertDuplicate(strict, text, Bean.class, "a");
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      assertEquals(read(lenient, text, Bean.class, representation).a, 3);
    }
    assertDuplicate(strict, text, Bean.class, "a");
  }

  @Test
  public void groupHelperFallback() {
    ForyJson json = newJsonBuilder().failOnDuplicateProperties(true).build();
    assertDuplicate(
        json, "{\"f0\":0,\"f1\":1,\"f2\":2,\"f3\":3,\"x\":0,\"f3\":4}", Wide.class, "f3");
    assertDuplicate(
        json, "{\"f0\":0,\"f1\":1,\"f2\":2,\"f4\":4,\"x\":0,\"f4\":4}", Wide.class, "f4");
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      Wide wide =
          read(
              json,
              "{\"f0\":0,\"f1\":1,\"f2\":2,\"f4\":4,\"x\":0,\"f3\":3}",
              Wide.class,
              representation);
      assertEquals(wide.f3, 3);
      assertEquals(wide.f4, 4);
    }
  }

  @Test
  public void unwrappedCreatorsAndPrefixes() {
    ForyJson json = newJsonBuilder().failOnDuplicateProperties(true).build();
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      CreatorOuter outer =
          read(
              json,
              "{\"last\":\"b\",\"id\":1,\"first\":\"a\"}",
              CreatorOuter.class,
              representation);
      assertEquals(outer.id, 1);
      assertEquals(outer.name.first, "a");
      assertEquals(outer.name.last, "b");
      assertEquals(
          read(json, "{\"n_x\":1,\"id\":2}", PrefixOuter.class, representation).inner.x, 1);
    }
    assertDuplicate(json, "{\"id\":1,\"first\":\"a\",\"id\":2}", CreatorOuter.class, "id");
    assertDuplicate(
        json, "{\"first\":\"a\",\"id\":1,\"first\":\"b\"}", CreatorOuter.class, "first");
    assertDuplicate(json, "{\"n_x\":1,\"id\":2,\"n_x\":3}", PrefixOuter.class, "n_x");
  }

  @Test
  public void subtypeWithAny() {
    ForyJson json = newJsonBuilder().failOnDuplicateProperties(true).build();
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      Shaped shaped =
          read(json, "{\"kind\":\"any\",\"size\":1,\"x\":2,\"x\":3}", Shaped.class, representation);
      assertEquals(((AnyShape) shaped).size, 1);
    }
    assertDuplicate(json, "{\"kind\":\"any\",\"size\":1,\"x\":2,\"size\":3}", Shaped.class, "size");
  }

  @Test
  public void creatorNullCounts() {
    ForyJson json =
        newJsonBuilder().failOnDuplicateProperties(true).onNullRead(NullHandling.SKIP).build();
    assertDuplicate(json, "{\"a\":1,\"b\":null,\"b\":\"x\"}", Created.class, "b");
    assertDuplicate(json, "{\"b\":null,\"a\":1,\"b\":\"x\"}", Created.class, "b");
  }

  @Test
  public void genericBean() {
    ForyJson json = newJsonBuilder().failOnDuplicateProperties(true).build();
    TypeRef<Box<String>> type = new TypeRef<Box<String>>() {};
    assertEquals(json.fromJson("{\"value\":\"a\",\"n\":1}", type).value, "a");
    String text = "{\"value\":\"a\",\"n\":1,\"value\":\"b\"}";
    ForyJsonException error =
        expectThrows(ForyJsonException.class, () -> json.fromJson(text, type));
    assertTrue(error.getMessage().contains("Duplicate JSON property value"), error.getMessage());
    error =
        expectThrows(
            ForyJsonException.class,
            () -> json.fromJson(text.getBytes(StandardCharsets.UTF_8), type));
    assertTrue(error.getMessage().contains("Duplicate JSON property value"), error.getMessage());
  }

  @Test
  public void unorderedCreator() {
    ForyJson json = newJsonBuilder().failOnDuplicateProperties(true).build();
    StringBuilder text = new StringBuilder("{");
    for (int i = 0; i < 17; i++) {
      text.append(i == 0 ? "" : ",").append("\"p").append(i).append("\":").append(i);
    }
    String ordered = text.append('}').toString();
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      assertEquals(read(json, ordered, WideCreated.class, representation).p16, 16);
    }
    String repeated = ordered.substring(0, ordered.length() - 1) + ",\"p3\":2}";
    assertDuplicate(json, repeated, WideCreated.class, "p3");
  }

  @Test
  public void longNames() {
    ForyJson json = newJsonBuilder().failOnDuplicateProperties(true).build();
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      LongNames value =
          read(
              json,
              "{\"identifier\":1,\"description\":\"a\",\"longPropertyName\":2}",
              LongNames.class,
              representation);
      assertEquals(value.description, "a");
      assertEquals(value.identifier, 1);
    }
    assertDuplicate(
        json,
        "{\"description\":\"a\",\"identifier\":1,\"description\":\"b\"}",
        LongNames.class,
        "description");
    assertDuplicate(
        json,
        "{\"description\":\"a\",\"identifier\":1,\"longPropertyName\":2,\"identifier\":3}",
        LongNames.class,
        "identifier");
  }

  @Test
  public void unwrappedAny() {
    ForyJson json = newJsonBuilder().failOnDuplicateProperties(true).build();
    for (int representation = 0; representation < REPRESENTATIONS; representation++) {
      OuterAny outer =
          read(
              json,
              "{\"id\":1,\"x\":2,\"extra\":true,\"extra\":false}",
              OuterAny.class,
              representation);
      assertEquals(outer.inner.x, 2);
      assertEquals(outer.values.get("extra"), false);
    }
    assertDuplicate(json, "{\"id\":1,\"x\":2,\"extra\":true,\"x\":3}", OuterAny.class, "x");
  }

  public static class Bean {
    public int a;
    public int b;
    public int c;
  }

  public static class Named {
    public String name = "initial";
  }

  @JsonDuplicateProperties(fail = true)
  public static class StrictBean {
    public int a;
  }

  @JsonDuplicateProperties(fail = false)
  public static class LenientBean {
    public int a;
  }

  @JsonDuplicateProperties(fail = true)
  public static class StrictParent {
    public Bean child;
    public int id;
    public StrictBean other;
  }

  public static class Created {
    public final int a;
    public final String b;

    @JsonCreator
    public Created(@JsonProperty("a") int a, @JsonProperty("b") String b) {
      this.a = a;
      this.b = b;
    }
  }

  public static class AnyBean {
    public int id;
    public final Map<String, Object> values = new LinkedHashMap<>();

    @JsonAnySetter
    public void put(String name, Object value) {
      values.put(name, value);
    }
  }

  public static class CreatedAny {
    public final int id;
    @JsonAnyProperty public final Map<String, Object> values;

    @JsonCreator({"id", "values"})
    public CreatedAny(int id, Map<String, Object> values) {
      this.id = id;
      this.values = values;
    }
  }

  public static class Inner {
    public int x;
    public int y;
  }

  public static class Outer {
    public int id;
    @JsonUnwrapped public Inner inner;
  }

  public static class NameParts {
    public final String first;
    public final String last;

    @JsonCreator({"first", "last"})
    public NameParts(String first, String last) {
      this.first = first;
      this.last = last;
    }
  }

  public static class CreatorOuter {
    public final int id;
    @JsonUnwrapped public final NameParts name;

    @JsonCreator({"id", "name"})
    public CreatorOuter(int id, NameParts name) {
      this.id = id;
      this.name = name;
    }
  }

  public static class PrefixOuter {
    public int id;

    @JsonUnwrapped(prefix = "n_")
    public Inner inner;
  }

  @JsonSubTypes(property = "kind", value = @JsonSubTypes.Type(value = AnyShape.class, name = "any"))
  public interface Shaped {}

  public static class AnyShape implements Shaped {
    public int size;
    public final Map<String, Object> extra = new LinkedHashMap<>();

    @JsonAnySetter
    public void put(String name, Object value) {
      extra.put(name, value);
    }
  }

  public static class Box<T> {
    public T value;
    public int n;
  }

  public static class WideCreated {
    public final int p0;
    public final int p1;
    public final int p2;
    public final int p3;
    public final int p4;
    public final int p5;
    public final int p6;
    public final int p7;
    public final int p8;
    public final int p9;
    public final int p10;
    public final int p11;
    public final int p12;
    public final int p13;
    public final int p14;
    public final int p15;
    public final int p16;

    @JsonCreator({
      "p0", "p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9", "p10", "p11", "p12", "p13", "p14",
      "p15", "p16"
    })
    public WideCreated(
        int p0,
        int p1,
        int p2,
        int p3,
        int p4,
        int p5,
        int p6,
        int p7,
        int p8,
        int p9,
        int p10,
        int p11,
        int p12,
        int p13,
        int p14,
        int p15,
        int p16) {
      this.p0 = p0;
      this.p1 = p1;
      this.p2 = p2;
      this.p3 = p3;
      this.p4 = p4;
      this.p5 = p5;
      this.p6 = p6;
      this.p7 = p7;
      this.p8 = p8;
      this.p9 = p9;
      this.p10 = p10;
      this.p11 = p11;
      this.p12 = p12;
      this.p13 = p13;
      this.p14 = p14;
      this.p15 = p15;
      this.p16 = p16;
    }
  }

  public static class LongNames {
    public String description;
    public int identifier;
    public int longPropertyName;
  }

  public static class OuterAny {
    public int id;
    @JsonUnwrapped public Inner inner;
    public final Map<String, Object> values = new LinkedHashMap<>();

    @JsonAnySetter
    public void put(String name, Object value) {
      values.put(name, value);
    }
  }

  public static class Wide {
    public int f0;
    public int f1;
    public int f2;
    public int f3;
    public int f4;
    public int f5;
    public int f6;
    public int f7;
    public int f8;
    public int f9;
    public int f10;
    public int f11;
    public int f12;
    public int f13;
    public int f14;
    public int f15;
    public int f16;
    public int f17;
    public int f18;
    public int f19;
    public int f20;
    public int f21;
    public int f22;
    public int f23;
    public int f24;
    public int f25;
    public int f26;
    public int f27;
    public int f28;
    public int f29;
    public int f30;
    public int f31;
    public int f32;
    public int f33;
    public int f34;
    public int f35;
    public int f36;
    public int f37;
    public int f38;
    public int f39;
    public int f40;
    public int f41;
    public int f42;
    public int f43;
    public int f44;
    public int f45;
    public int f46;
    public int f47;
    public int f48;
    public int f49;
    public int f50;
    public int f51;
    public int f52;
    public int f53;
    public int f54;
    public int f55;
    public int f56;
    public int f57;
    public int f58;
    public int f59;
    public int f60;
    public int f61;
    public int f62;
    public int f63;
    public int f64;
    public int f65;
    public int f66;
    public int f67;
    public int f68;
    public int f69;
  }

  public static class MixinTarget {
    public int a;
  }

  @JsonMixin(target = MixinTarget.class)
  @JsonDuplicateProperties(fail = true)
  public abstract static class StrictMixin {}
}
