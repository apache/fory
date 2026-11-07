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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonDefaultCodec;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.codec.AbstractJsonValueCodec;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.writer.JsonWriter;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonDefaultCodecTest extends ForyJsonTestModels {
  private static final BigDecimal TEN = new BigDecimal("1E+1");
  // Plain notation reads back with scale 0, which BigDecimal.equals distinguishes from 1E+1.
  private static final BigDecimal PLAIN_TEN = new BigDecimal("10");

  @Factory(dataProvider = "enableCodegen")
  public JsonDefaultCodecTest(boolean codegen) {
    super(codegen);
  }

  /** Writes a decimal as a tagged plain-notation string so both directions are observable. */
  public static final class TaggedDecimalCodec extends AbstractJsonValueCodec<BigDecimal> {
    @Override
    public void write(JsonWriter writer, BigDecimal value) {
      writer.writeString("d:" + value.toPlainString());
    }

    @Override
    public BigDecimal read(JsonReader reader) {
      String text = reader.readString();
      if (!text.startsWith("d:")) {
        throw new ForyJsonException("Untagged decimal " + text);
      }
      return new BigDecimal(text.substring(2));
    }
  }

  public static final class OtherDecimalCodec extends AbstractJsonValueCodec<BigDecimal> {
    @Override
    public void write(JsonWriter writer, BigDecimal value) {
      writer.writeString("o:" + value.toPlainString());
    }

    @Override
    public BigDecimal read(JsonReader reader) {
      return new BigDecimal(reader.readString().substring(2));
    }
  }

  public static final class TaggedOptionalCodec
      extends AbstractJsonValueCodec<Optional<BigDecimal>> {
    @Override
    public void write(JsonWriter writer, Optional<BigDecimal> value) {
      writer.writeString("od:" + value.get().toPlainString());
    }

    @Override
    public Optional<BigDecimal> read(JsonReader reader) {
      return Optional.of(new BigDecimal(reader.readString().substring(3)));
    }
  }

  public static final class TaggedIntegerCodec extends AbstractJsonValueCodec<Integer> {
    @Override
    public void write(JsonWriter writer, Integer value) {
      writer.writeString("i:" + value);
    }

    @Override
    public Integer read(JsonReader reader) {
      return Integer.valueOf(reader.readString().substring(2));
    }
  }

  public static final class WildcardListCodec extends AbstractJsonValueCodec<List<?>> {
    @Override
    public void write(JsonWriter writer, List<?> value) {
      writer.writeNull();
    }

    @Override
    public List<?> read(JsonReader reader) {
      return null;
    }
  }

  @JsonDefaultCodec(TaggedDecimalCodec.class)
  public static class Prices {
    public BigDecimal price;
    public Optional<BigDecimal> bid = Optional.empty();
    public List<BigDecimal> floors;
    public BigDecimal[] steps;
    public Map<String, BigDecimal> byCurrency;
    public AtomicReference<BigDecimal> last;
    public String name;

    @JsonCodec(OtherDecimalCodec.class)
    public BigDecimal explicit;

    public Nested nested;
  }

  public static class Nested {
    public BigDecimal value;
  }

  @Test
  public void selectsWholeValueAndDirectChildren() {
    ForyJson json = newJson();
    Prices prices = new Prices();
    prices.price = TEN;
    prices.bid = Optional.of(TEN);
    prices.floors = Arrays.asList(TEN, BigDecimal.ONE);
    prices.steps = new BigDecimal[] {TEN};
    prices.byCurrency = Collections.singletonMap("USD", TEN);
    prices.last = new AtomicReference<>(TEN);
    prices.name = "p";
    prices.explicit = TEN;
    prices.nested = new Nested();
    prices.nested.value = TEN;
    String text = json.toJson(prices);
    assertEquals(
        text,
        "{\"price\":\"d:10\",\"bid\":\"d:10\",\"floors\":[\"d:10\",\"d:1\"],\"steps\":[\"d:10\"],"
            + "\"byCurrency\":{\"USD\":\"d:10\"},\"last\":\"d:10\",\"name\":\"p\","
            + "\"explicit\":\"o:10\",\"nested\":{\"value\":1E+1}}");
    for (Prices read :
        Arrays.asList(
            json.fromJson(text, Prices.class),
            json.fromJson(text.getBytes(StandardCharsets.UTF_8), Prices.class))) {
      assertEquals(read.price, PLAIN_TEN);
      assertEquals(read.bid, Optional.of(PLAIN_TEN));
      assertEquals(read.floors, Arrays.asList(PLAIN_TEN, BigDecimal.ONE));
      assertEquals(read.steps, new BigDecimal[] {PLAIN_TEN});
      assertEquals(read.byCurrency, Collections.singletonMap("USD", PLAIN_TEN));
      assertEquals(read.last.get(), PLAIN_TEN);
      assertEquals(read.explicit, PLAIN_TEN);
      assertEquals(read.nested.value, TEN);
    }
    // The tagged reader proves the default codec, not the built-in decimal mapping, reads it.
    expectThrows(ForyJsonException.class, () -> json.fromJson("{\"price\":\"10\"}", Prices.class));
  }

  @JsonDefaultCodec({TaggedDecimalCodec.class, TaggedOptionalCodec.class})
  public static class ListForm {
    public BigDecimal price;
    public Optional<BigDecimal> bid = Optional.empty();
  }

  @JsonDefaultCodec(TaggedDecimalCodec.class)
  @JsonDefaultCodec(TaggedOptionalCodec.class)
  public static class RepeatedForm {
    public BigDecimal price;
    public Optional<BigDecimal> bid = Optional.empty();
  }

  @Test
  public void listAndRepeatedFormsPreferExactParameterizedTarget() {
    ForyJson json = newJson();
    ListForm list = new ListForm();
    list.price = TEN;
    list.bid = Optional.of(TEN);
    RepeatedForm repeated = new RepeatedForm();
    repeated.price = TEN;
    repeated.bid = Optional.of(TEN);
    String expected = "{\"price\":\"d:10\",\"bid\":\"od:10\"}";
    assertEquals(json.toJson(list), expected);
    assertEquals(json.toJson(repeated), expected);
    assertEquals(json.fromJson(expected, ListForm.class).bid, Optional.of(PLAIN_TEN));
    assertEquals(json.fromJson(expected, RepeatedForm.class).bid, Optional.of(PLAIN_TEN));
  }

  @JsonDefaultCodec(TaggedDecimalCodec.class)
  public static class Base {
    public BigDecimal inherited;
  }

  public static class Child extends Base {
    public BigDecimal own;
  }

  @JsonDefaultCodec(OtherDecimalCodec.class)
  public static class Overriding extends Base {
    public BigDecimal own;
  }

  @Test
  public void superclassDefaultsApplyAndSubclassReplacesThem() {
    ForyJson json = newJson();
    Child child = new Child();
    child.inherited = TEN;
    child.own = TEN;
    assertEquals(json.toJson(child), "{\"inherited\":\"d:10\",\"own\":\"d:10\"}");
    Overriding overriding = new Overriding();
    overriding.inherited = TEN;
    overriding.own = TEN;
    assertEquals(json.toJson(overriding), "{\"inherited\":\"o:10\",\"own\":\"o:10\"}");
    assertEquals(json.fromJson("{\"inherited\":\"o:10\"}", Overriding.class).inherited, PLAIN_TEN);
  }

  @JsonDefaultCodec(TaggedDecimalCodec.class)
  public static class Created {
    private final BigDecimal price;
    private final Optional<BigDecimal> bid;

    @JsonCreator
    public Created(
        @JsonProperty("price") BigDecimal price,
        @JsonProperty("bid") Optional<BigDecimal> bid,
        @JsonProperty("extra") BigDecimal extra) {
      this.price = extra == null ? price : extra;
      this.bid = bid;
    }

    public BigDecimal getPrice() {
      return price;
    }

    public Optional<BigDecimal> getBid() {
      return bid;
    }
  }

  @Test
  public void creatorParameters() {
    ForyJson json = newJson();
    Created created = json.fromJson("{\"price\":\"d:10\",\"bid\":\"d:1\"}", Created.class);
    assertEquals(created.getPrice(), PLAIN_TEN);
    assertEquals(created.getBid(), Optional.of(BigDecimal.ONE));
    // A creator-only parameter uses the class default as well.
    assertEquals(json.fromJson("{\"extra\":\"d:10\"}", Created.class).getPrice(), PLAIN_TEN);
    assertEquals(
        json.toJson(new Created(TEN, Optional.of(TEN), null)),
        "{\"price\":\"d:10\",\"bid\":\"d:10\"}");
  }

  @JsonDefaultCodec(TaggedIntegerCodec.class)
  public static class Counts {
    public int primitive;
    public Integer boxed;
  }

  @Test
  public void primitiveMatchesItsWrapper() {
    ForyJson json = newJson();
    Counts counts = new Counts();
    counts.primitive = 5;
    counts.boxed = 6;
    String text = json.toJson(counts);
    assertEquals(text, "{\"primitive\":\"i:5\",\"boxed\":\"i:6\"}");
    Counts read = json.fromJson(text, Counts.class);
    assertEquals(read.primitive, 5);
    assertEquals(read.boxed, Integer.valueOf(6));
  }

  public static class Plain {
    public BigDecimal price;
  }

  @JsonMixin(target = Plain.class)
  @JsonDefaultCodec(TaggedDecimalCodec.class)
  public interface PlainMixin {}

  @Test
  public void mixinSuppliesDefaults() {
    ForyJson json = newJsonBuilder().registerMixin(PlainMixin.class).build();
    Plain plain = new Plain();
    plain.price = TEN;
    assertEquals(json.toJson(plain), "{\"price\":\"d:10\"}");
    assertEquals(json.fromJson("{\"price\":\"d:10\"}", Plain.class).price, PLAIN_TEN);
    assertEquals(newJson().toJson(plain), "{\"price\":1E+1}");
  }

  @JsonMixin(target = RepeatedForm.class)
  @JsonDefaultCodec(OtherDecimalCodec.class)
  public interface RepeatedFormMixin {}

  @JsonMixin(target = ListForm.class)
  @JsonDefaultCodec(OtherDecimalCodec.class)
  @JsonDefaultCodec(TaggedOptionalCodec.class)
  public interface ListFormMixin {}

  @Test
  public void mixinReplacesBothDeclarationForms() {
    ForyJson json =
        newJsonBuilder()
            .registerMixin(RepeatedFormMixin.class)
            .registerMixin(ListFormMixin.class)
            .build();
    RepeatedForm repeated = new RepeatedForm();
    repeated.price = TEN;
    repeated.bid = Optional.of(TEN);
    // The single Mixin declaration replaces the target's repeated ones, Optional<BigDecimal>
    // included.
    assertEquals(json.toJson(repeated), "{\"price\":\"o:10\",\"bid\":\"o:10\"}");
    ListForm list = new ListForm();
    list.price = TEN;
    list.bid = Optional.of(TEN);
    assertEquals(json.toJson(list), "{\"price\":\"o:10\",\"bid\":\"od:10\"}");
  }

  /** Owns its whole representation, so its elements are not a child slot a default can fill. */
  public static final class DecimalBagCodec extends AbstractJsonValueCodec<DecimalBag> {
    @Override
    public void write(JsonWriter writer, DecimalBag value) {
      writer.writeString("bag:" + value.size());
    }

    @Override
    public DecimalBag read(JsonReader reader) {
      reader.readString();
      return new DecimalBag();
    }
  }

  @JsonCodec(DecimalBagCodec.class)
  public static class DecimalBag extends java.util.ArrayList<BigDecimal> {}

  @JsonDefaultCodec(TaggedDecimalCodec.class)
  public static class WithBag {
    public DecimalBag bag;
    public List<BigDecimal> registered;
  }

  @Test
  public void completeContainerCodecHidesChildDefault() {
    ForyJson json =
        newJsonBuilder()
            .registerCodec(
                List.class,
                new AbstractJsonValueCodec<List>() {
                  @Override
                  public void write(JsonWriter writer, List value) {
                    writer.writeString("list:" + value.size());
                  }

                  @Override
                  public List read(JsonReader reader) {
                    reader.readString();
                    return Collections.emptyList();
                  }
                })
            .build();
    WithBag value = new WithBag();
    value.bag = new DecimalBag();
    value.bag.add(TEN);
    value.registered = Collections.singletonList(TEN);
    assertEquals(json.toJson(value), "{\"bag\":\"bag:1\",\"registered\":\"list:1\"}");
  }

  @JsonDefaultCodec({TaggedDecimalCodec.class, OtherDecimalCodec.class})
  public static class DuplicateTarget {
    public BigDecimal price;
  }

  @JsonDefaultCodec(TaggedDecimalCodec.class)
  @JsonDefaultCodec(OtherDecimalCodec.class)
  public static class RepeatedDuplicateTarget {
    public BigDecimal price;
  }

  @JsonDefaultCodec(WildcardListCodec.class)
  public static class NoConcreteTarget {
    public BigDecimal price;
  }

  @Test
  public void invalidDeclarations() {
    ForyJson json = newJson();
    assertInvalid(json, DuplicateTarget.class, "Duplicate @JsonDefaultCodec target");
    assertInvalid(json, RepeatedDuplicateTarget.class, "Duplicate @JsonDefaultCodec target");
    assertInvalid(json, NoConcreteTarget.class, "does not declare a concrete value type");
  }

  private static void assertInvalid(ForyJson json, Class<?> type, String message) {
    ForyJsonException error =
        expectThrows(ForyJsonException.class, () -> json.fromJson("{}", type));
    assertTrue(
        String.valueOf(error.getMessage()).contains(message), String.valueOf(error.getMessage()));
  }
}
