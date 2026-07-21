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

import static org.apache.fory.json.JsonTestSupport.nullCodec;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.fail;

import java.nio.charset.StandardCharsets;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonProperty;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonCreatorTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonCreatorTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void propertyListConstructor() {
    ForyJson json = newJson();
    User value = json.fromJson("{\"name\":\"alice\",\"id\":7}", User.class);
    assertEquals(value.id, 7L);
    assertEquals(value.name, "alice");
    assertEquals(json.toJson(value), "{\"id\":7,\"name\":\"alice\"}");
    User utf16 = json.fromJson("{\"name\":\"你好\",\"id\":8}", User.class);
    assertEquals(utf16.name, "你好");
    User utf8 =
        json.fromJson("{\"name\":\"你好\",\"id\":9}".getBytes(StandardCharsets.UTF_8), User.class);
    assertEquals(utf8.id, 9L);
    assertEquals(utf8.name, "你好");
  }

  @Test
  public void parameterLocalFactory() {
    ForyJson json = newJson();
    FactoryUser value =
        json.fromJson("{\"display_name\":\"alice\",\"user_id\":9}", FactoryUser.class);
    assertEquals(value.id, 9L);
    assertEquals(value.name, "alice");
    assertEquals(json.toJson(value), "{\"id\":9,\"name\":\"alice\"}");
  }

  @Test
  public void primitiveNullRejected() {
    ForyJson json = newJson();
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"id\":null,\"name\":\"alice\"}", User.class));
  }

  @Test
  public void customPrimitiveNullRejected() {
    ForyJson json = newJsonBuilder().registerCodec(int.class, nullCodec()).build();
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"id\":null}", CustomPrimitiveCreator.class));
    assertThrows(
        ForyJsonException.class,
        () ->
            json.fromJson(
                "{\"id\":null}".getBytes(StandardCharsets.UTF_8), CustomPrimitiveCreator.class));
  }

  @Test
  public void packagePrivateOwner() {
    ForyJson json = newJson();
    assertEquals(json.fromJson("{\"id\":3}", PackagePrivateCreator.class).id, 3);
    assertEquals(
        json.fromJson("{\"id\":4}".getBytes(StandardCharsets.UTF_8), PackagePrivateCreator.class)
            .id,
        4);
  }

  @Test
  public void rejectInvalidCreators() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"id\":1}", Multiple.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"id\":1}", UnknownProperty.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"id\":1}", TypeMismatch.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"id\":1}", BadFactory.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"id\":1}", NullFactory.class));
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"input\":1}", CreatorOnlyInclude.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"id\":1}", DeadProperty.class));
  }

  @Test
  public void validateBeforeCreatorCall() {
    CountingFactory.calls = 0;
    ForyJson json = newJson();
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"id\":1,}", CountingFactory.class));
    assertEquals(CountingFactory.calls, 0);
    assertEquals(json.fromJson("{\"id\":1}", CountingFactory.class).id, 1);
    assertEquals(CountingFactory.calls, 1);
  }

  @Test
  public void wrapCreatorException() {
    try {
      newJson().fromJson("{\"id\":1}", CheckedFactory.class);
      fail("Expected creator failure");
    } catch (ForyJsonException e) {
      assertEquals(e.getCause().getMessage(), "creator failure");
    }
  }

  @Test
  public void wrapCreatorThrowable() {
    try {
      newJson().fromJson("{\"id\":1}", ThrowableFactory.class);
      fail("Expected creator failure");
    } catch (ForyJsonException e) {
      assertEquals(e.getCause().getClass(), Throwable.class);
      assertEquals(e.getCause().getMessage(), "creator throwable");
    }
  }

  @Test
  public void propagateCreatorError() {
    assertThrows(AssertionError.class, () -> newJson().fromJson("{\"id\":1}", ErrorFactory.class));
  }

  @Test
  public void hiddenCreatorParameter() {
    PublicHiddenCreator value =
        newJson().fromJson("{\"input\":{\"value\":7}}", PublicHiddenCreator.class);
    assertEquals(value.value, 7);
  }

  static final class HiddenArgument {
    public int value;
  }

  public static final class PublicHiddenCreator {
    public final int value;

    @JsonCreator
    public PublicHiddenCreator(@JsonProperty("input") HiddenArgument input) {
      value = input.value;
    }
  }

  public static final class User {
    public final long id;
    public final String name;

    @JsonCreator({"id", "name"})
    public User(long id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  public static final class CustomPrimitiveCreator {
    public final int id;

    @JsonCreator({"id"})
    public CustomPrimitiveCreator(int id) {
      this.id = id;
    }
  }

  static final class PackagePrivateCreator {
    public final int id;

    @JsonCreator({"id"})
    public PackagePrivateCreator(int id) {
      this.id = id;
    }
  }

  public static final class FactoryUser {
    public final long id;
    public final String name;

    private FactoryUser(long id, String name) {
      this.id = id;
      this.name = name;
    }

    @JsonCreator
    public static FactoryUser create(
        @JsonProperty("user_id") long id, @JsonProperty("display_name") String name) {
      return new FactoryUser(id, name);
    }
  }

  public static final class Multiple {
    public final int id;

    @JsonCreator({"id"})
    public Multiple(int id) {
      this.id = id;
    }

    @JsonCreator
    public static Multiple create(@JsonProperty("id") int id) {
      return new Multiple(id);
    }
  }

  public static final class UnknownProperty {
    public final int id;

    @JsonCreator({"missing"})
    public UnknownProperty(int id) {
      this.id = id;
    }
  }

  public static final class TypeMismatch {
    public final int id;

    @JsonCreator({"id"})
    public TypeMismatch(long id) {
      this.id = (int) id;
    }
  }

  public static final class BadFactory {
    public int id;

    @JsonCreator
    public static Object create(@JsonProperty("id") int id) {
      return new BadFactory();
    }
  }

  public static final class NullFactory {
    public int id;

    @JsonCreator
    public static NullFactory create(@JsonProperty("id") int id) {
      return null;
    }
  }

  public static final class CreatorOnlyInclude {
    public final int id;

    private CreatorOnlyInclude(int id) {
      this.id = id;
    }

    @JsonCreator
    public static CreatorOnlyInclude create(
        @JsonProperty(value = "input", include = JsonProperty.Include.ALWAYS) int id) {
      return new CreatorOnlyInclude(id);
    }
  }

  public static final class DeadProperty {
    public final int id;

    @JsonCreator({"id"})
    public DeadProperty(int id) {
      this.id = id;
    }

    @JsonProperty("unused")
    public void setUnused(String value) {}
  }

  public static final class CountingFactory {
    static int calls;
    public final int id;

    private CountingFactory(int id) {
      this.id = id;
    }

    @JsonCreator
    public static CountingFactory create(@JsonProperty("id") int id) {
      calls++;
      return new CountingFactory(id);
    }
  }

  public static final class CheckedFactory {
    public final int id;

    private CheckedFactory(int id) {
      this.id = id;
    }

    @JsonCreator
    public static CheckedFactory create(@JsonProperty("id") int id) throws Exception {
      throw new Exception("creator failure");
    }
  }

  public static final class ThrowableFactory {
    public final int id;

    private ThrowableFactory(int id) {
      this.id = id;
    }

    @JsonCreator
    public static ThrowableFactory create(@JsonProperty("id") int id) throws Throwable {
      throw new Throwable("creator throwable");
    }
  }

  public static final class ErrorFactory {
    public final int id;

    private ErrorFactory(int id) {
      this.id = id;
    }

    @JsonCreator
    public static ErrorFactory create(@JsonProperty("id") int id) {
      throw new AssertionError("creator error");
    }
  }
}
