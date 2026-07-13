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
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.nio.charset.StandardCharsets;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonPropertyOrder;
import org.apache.fory.platform.JdkVersion;
import org.testng.SkipException;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonPropertyOrderTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonPropertyOrderTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void explicitAndIndexOrder() {
    ForyJson json = newJson();
    CombinedOrder value =
        json.fromJson(
            "{\"age\":4,\"name\":\"alice\",\"id\":1,\"email\":\"a@example.com\"}",
            CombinedOrder.class);
    assertEquals(value.id, 1);
    assertEquals(value.name, "alice");
    assertEquals(value.email, "a@example.com");
    assertEquals(value.age, 4);
    assertEquals(
        json.toJson(value), "{\"id\":1,\"email\":\"a@example.com\",\"name\":\"alice\",\"age\":4}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8),
        "{\"id\":1,\"email\":\"a@example.com\",\"name\":\"alice\",\"age\":4}");
    assertGeneratedWhenSupported(json, CombinedOrder.class);
  }

  @Test
  public void resolveOrderNames() {
    ForyJson json =
        newJsonBuilder().withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE).build();
    assertEquals(
        json.toJson(new RenamedOrder()),
        "{\"display_name\":\"display\",\"plain_name\":\"plain\",\"tail\":\"tail\"}");
    assertEquals(json.toJson(new FinalNameWins()), "{\"target\":1,\"other\":2}");
    assertGeneratedWhenSupported(json, RenamedOrder.class);
    assertGeneratedWhenSupported(json, FinalNameWins.class);
  }

  @Test
  public void indexOrder() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new IndexedOnly()), "{\"a\":1,\"c\":3,\"b\":2}");
    assertEquals(json.toJson(new RepeatedIndex()), "{\"value\":3,\"tail\":4}");
    assertGeneratedWhenSupported(json, IndexedOnly.class);
    assertGeneratedWhenSupported(json, RepeatedIndex.class);
  }

  @Test
  public void creatorIndex() {
    ForyJson json = newJson();
    CreatorOrder value = json.fromJson("{\"name\":\"alice\",\"id\":7}", CreatorOrder.class);
    assertEquals(value.id, 7);
    assertEquals(value.name, "alice");
    assertEquals(json.toJson(value), "{\"id\":7,\"name\":\"alice\"}");
    assertGeneratedWhenSupported(json, CreatorOrder.class);
  }

  @Test
  public void recordIndex() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    Class<?> type =
        compileRecordClass(
            "JsonOrderedRecord",
            "package org.apache.fory.json.records;\n"
                + "import org.apache.fory.json.annotation.JsonProperty;\n"
                + "public record JsonOrderedRecord(@JsonProperty(index = 1) int b, "
                + "@JsonProperty(index = 0) int a) {}\n");
    Object value = jsonRecord(type, 2, 1);
    ForyJson json = newJson();
    assertEquals(json.toJson(value), "{\"a\":1,\"b\":2}");
    Object decoded = json.fromJson("{\"b\":4,\"a\":3}", type);
    assertEquals(type.getMethod("a").invoke(decoded), Integer.valueOf(3));
    assertEquals(type.getMethod("b").invoke(decoded), Integer.valueOf(4));
    assertGeneratedWhenSupported(json, type);
  }

  private static Object jsonRecord(Class<?> type, int b, int a) throws Exception {
    return type.getConstructor(int.class, int.class).newInstance(b, a);
  }

  @Test
  public void superclassOrder() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new WholeChild()), "{\"c\":3,\"a\":1,\"b\":2}");
    assertEquals(json.toJson(new InheritedChild()), "{\"child\":3,\"a\":1,\"b\":2}");
    assertEquals(json.toJson(new OverrideInvalidChild()), "{\"b\":2,\"a\":1}");
    assertEquals(json.toJson(new InterfaceOrderImpl()), "{\"a\":1,\"b\":2}");
    assertGeneratedWhenSupported(json, WholeChild.class);
    assertGeneratedWhenSupported(json, InheritedChild.class);
    assertGeneratedWhenSupported(json, OverrideInvalidChild.class);
    assertGeneratedWhenSupported(json, InterfaceOrderImpl.class);
  }

  @Test
  public void rejectInvalidIndex() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.toJson(new NegativeIndex()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new DuplicateIndex()));
    ForyJsonException conflict =
        expectThrows(ForyJsonException.class, () -> json.toJson(new ConflictingIndex()));
    assertTrue(
        conflict.getMessage().contains("property value on " + ConflictingIndex.class.getName()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new SetterOnlyIndex()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new WriteIgnoredIndex()));
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"input\":1}", CreatorOnlyIndex.class));
    ForyJsonException error =
        expectThrows(
            ForyJsonException.class, () -> json.fromJson("{\"id\":1}", NegativeCreatorIndex.class));
    assertTrue(
        error.getMessage().contains("property id on " + NegativeCreatorIndex.class.getName()));
  }

  @Test
  public void rejectInvalidOrder() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.toJson(new EmptyOrder()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new EmptyOrderEntry()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new UnknownOrder()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new DuplicateOrderEntry()));
  }

  @JsonPropertyOrder({"id"})
  public static final class CombinedOrder {
    @JsonProperty(index = 20)
    public String name;

    @JsonProperty(index = 10)
    public String email;

    public int id;
    public int age;
  }

  @JsonPropertyOrder({"display_name", "plainName"})
  public static final class RenamedOrder {
    public String plainName = "plain";

    @JsonProperty("display_name")
    public String displayName = "display";

    public String tail = "tail";
  }

  @JsonPropertyOrder({"target"})
  public static final class FinalNameWins {
    @JsonProperty("other")
    public int target = 2;

    @JsonProperty("target")
    public int renamed = 1;
  }

  public static final class IndexedOnly {
    @JsonProperty(index = 2)
    public int c = 3;

    public int b = 2;

    @JsonProperty(index = 0)
    public int a = 1;
  }

  public static final class RepeatedIndex {
    @JsonProperty(index = 0)
    private int value = 3;

    public int tail = 4;

    @JsonProperty(index = 0)
    public int getValue() {
      return value;
    }

    @JsonProperty(index = 0)
    public void setValue(int value) {
      this.value = value;
    }
  }

  public static final class CreatorOrder {
    public final String name;
    public final int id;

    private CreatorOrder(int id, String name) {
      this.id = id;
      this.name = name;
    }

    @JsonCreator
    public static CreatorOrder create(
        @JsonProperty(value = "id", index = 0) int id,
        @JsonProperty(value = "name", index = 1) String name) {
      return new CreatorOrder(id, name);
    }
  }

  @JsonPropertyOrder({"b"})
  public static class WholeParent {
    @JsonProperty(index = 1)
    public int b = 2;

    @JsonProperty(index = 0)
    public int a = 1;
  }

  @JsonPropertyOrder({"c"})
  public static final class WholeChild extends WholeParent {
    public int c = 3;
  }

  @JsonPropertyOrder({"child", "a"})
  public static class InheritedParent {
    public int b = 2;
    public int a = 1;
  }

  public static final class InheritedChild extends InheritedParent {
    public int child = 3;
  }

  @JsonPropertyOrder({"missing"})
  public static class InvalidOrderParent {
    public int a = 1;
  }

  @JsonPropertyOrder({"b"})
  public static final class OverrideInvalidChild extends InvalidOrderParent {
    public int b = 2;
  }

  @JsonPropertyOrder({"b"})
  public interface InterfaceOrder {}

  public static final class InterfaceOrderImpl implements InterfaceOrder {
    public int b = 2;

    @JsonProperty(index = 0)
    public int a = 1;
  }

  public static final class NegativeIndex {
    @JsonProperty(index = -2)
    public int value;
  }

  @JsonPropertyOrder({"a"})
  public static final class DuplicateIndex {
    @JsonProperty(index = 0)
    public int a;

    @JsonProperty(index = 0)
    public int b;
  }

  public static final class ConflictingIndex {
    @JsonProperty(index = 0)
    private int value;

    @JsonProperty(index = 1)
    public int getValue() {
      return value;
    }
  }

  public static final class SetterOnlyIndex {
    @JsonProperty(index = 0)
    public void setValue(int value) {}
  }

  public static final class WriteIgnoredIndex {
    @JsonIgnore(ignoreRead = false, ignoreWrite = true)
    @JsonProperty(index = 0)
    public int value;
  }

  public static final class CreatorOnlyIndex {
    public final int id;

    private CreatorOnlyIndex(int id) {
      this.id = id;
    }

    @JsonCreator
    public static CreatorOnlyIndex create(@JsonProperty(value = "input", index = 0) int id) {
      return new CreatorOnlyIndex(id);
    }
  }

  public static final class NegativeCreatorIndex {
    public final int id;

    private NegativeCreatorIndex(int id) {
      this.id = id;
    }

    @JsonCreator
    public static NegativeCreatorIndex create(@JsonProperty(value = "id", index = -2) int id) {
      return new NegativeCreatorIndex(id);
    }
  }

  @JsonPropertyOrder({})
  public static final class EmptyOrder {
    public int value;
  }

  @JsonPropertyOrder({""})
  public static final class EmptyOrderEntry {
    public int value;
  }

  @JsonPropertyOrder({"missing"})
  public static final class UnknownOrder {
    public int value;
  }

  @JsonPropertyOrder({"renamed", "value"})
  public static final class DuplicateOrderEntry {
    @JsonProperty("renamed")
    public int value;
  }
}
