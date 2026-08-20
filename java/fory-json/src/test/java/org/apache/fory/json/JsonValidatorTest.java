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

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonUnwrapped;
import org.apache.fory.json.annotation.JsonValidator;
import org.apache.fory.json.annotation.JsonValue;
import org.apache.fory.json.codec.AbstractJsonValueCodec;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.reflect.TypeRef;
import org.testng.SkipException;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonValidatorTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonValidatorTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void inputPaths() {
    ForyJson json = newJson();
    TypeRef<MutableValue> type = new TypeRef<MutableValue>() {};
    assertMutable(json.fromJson("{\"name\":\"latin\"}", MutableValue.class), "latin");
    assertMutable(json.fromJson("{\"name\":\"latin\"}", type), "latin");
    assertMutable(json.fromJson("{\"name\":\"你好\"}", MutableValue.class), "你好");
    assertMutable(json.fromJson("{\"name\":\"你好\"}", type), "你好");
    byte[] bytes = "{\"name\":\"utf8\"}".getBytes(StandardCharsets.UTF_8);
    assertMutable(json.fromJson(bytes, MutableValue.class), "utf8");
    assertMutable(json.fromJson(bytes, type), "utf8");
    assertGeneratedWhenSupported(json, MutableValue.class);
  }

  @Test
  public void parameterizedType() {
    ForyJson json = newJson();
    TypeRef<GenericValue<String>> type = new TypeRef<GenericValue<String>>() {};
    GenericValue<String> value = json.fromJson("{\"value\":\"ready\"}", type);
    assertEquals(value.value, "ready");
    assertEquals(value.observed, "ready");
    assertEquals(value.validations, 1);
  }

  @Test
  public void creatorAndRecord() throws Exception {
    CreatorValue creator = newJson().fromJson("{\"id\":7}", CreatorValue.class);
    assertEquals(creator.id, 7);
    assertEquals(creator.validations, 1);

    if (JdkVersion.MAJOR_VERSION < 17) {
      throw new SkipException("Java record test requires JDK 17+");
    }
    String name = codegenEnabled() ? "GeneratedValidatorRecord" : "ValidatorRecord";
    Class<?> type =
        compileRecordClass(
            name,
            "package org.apache.fory.json.records;\n"
                + "import org.apache.fory.json.annotation.JsonValidator;\n"
                + "public record "
                + name
                + "(int id) {\n"
                + "  public static int validations;\n"
                + "  @JsonValidator public void validate() {\n"
                + "    if (id != 9) throw new IllegalArgumentException();\n"
                + "    validations++;\n"
                + "  }\n"
                + "}\n");
    Field validations = type.getField("validations");
    validations.setInt(null, 0);
    Object record = newJson().fromJson("{\"id\":9}", type);
    assertEquals(type.getMethod("id").invoke(record), Integer.valueOf(9));
    assertEquals(validations.getInt(null), 1);
  }

  @Test
  public void nestedOrder() {
    ForyJson json = newJson();
    NestedParent nested = json.fromJson("{\"child\":{\"id\":3}}", NestedParent.class);
    assertEquals(nested.child.validations, 1);
    assertEquals(nested.validations, 1);

    UnwrappedParent unwrapped = json.fromJson("{\"child_id\":4}", UnwrappedParent.class);
    assertEquals(unwrapped.child.validations, 1);
    assertEquals(unwrapped.validations, 1);
  }

  @Test
  public void anyAndSubtype() {
    ForyJson json = newJson();
    AnyValue any = json.fromJson("{\"dynamic\":5}", AnyValue.class);
    assertEquals(any.properties.get("dynamic"), Integer.valueOf(5));
    assertEquals(any.validations, 1);

    Shape shape = json.fromJson("{\"kind\":\"circle\",\"radius\":6}", Shape.class);
    assertTrue(shape instanceof ValidatedCircle);
    ValidatedCircle circle = (ValidatedCircle) shape;
    assertEquals(circle.radius, 6);
    assertEquals(circle.validations, 1);
  }

  @Test
  public void multipleValidators() {
    MultipleValue value = newJson().fromJson("{}", MultipleValue.class);
    assertTrue(value.firstCalled);
    assertTrue(value.secondCalled);
  }

  @Test
  public void inheritance() {
    ForyJson json = newJson();
    InheritedValue inherited = json.fromJson("{}", InheritedValue.class);
    assertEquals(inherited.validations, 1);

    OverrideValue override = json.fromJson("{}", OverrideValue.class);
    assertEquals(override.validations, 0);
  }

  @Test
  public void nullSkipsValidation() {
    NullTracked.validations = 0;
    assertEquals(newJson().fromJson("null", NullTracked.class), null);
    assertEquals(NullTracked.validations, 0);
  }

  @Test
  public void invalidDeclarations() {
    for (Class<?> type :
        new Class<?>[] {
          PrivateValidator.class,
          StaticValidator.class,
          ArgumentValidator.class,
          ReturningValidator.class
        }) {
      ForyJsonException failure =
          expectThrows(ForyJsonException.class, () -> newJson().fromJson("{}", type));
      assertTrue(failure.getMessage().contains("@JsonValidator"), failure.getMessage());
    }
  }

  @Test
  public void failureSemanticsAndCleanup() {
    ForyJson json = newJson();
    ForyJsonException checked =
        expectThrows(
            ForyJsonException.class,
            () -> json.fromJson("{\"failure\":\"checked\"}", FailureValue.class));
    assertTrue(checked.getCause() instanceof IOException, checked.toString());

    ForyJsonException runtime =
        expectThrows(
            ForyJsonException.class,
            () -> json.fromJson("{\"failure\":\"runtime\"}", FailureValue.class));
    assertTrue(runtime.getCause() instanceof IllegalStateException, runtime.toString());

    AssertionError error =
        expectThrows(
            AssertionError.class,
            () -> json.fromJson("{\"failure\":\"error\"}", FailureValue.class));
    assertEquals(error.getMessage(), "validator error");

    FailureValue recovered = json.fromJson("{\"failure\":\"none\"}", FailureValue.class);
    assertEquals(recovered.validations, 1);
  }

  @Test
  public void valueMappingOwnsValidation() {
    ScalarValue value = newJson().fromJson("\"ready\"", ScalarValue.class);
    assertEquals(value.value, "ready");
    assertEquals(value.validations, 0);
  }

  @Test
  public void completeCodecOwnsValidation() {
    ForyJson json =
        newJsonBuilder().registerCodec(CompleteValue.class, new CompleteValueCodec()).build();
    CompleteValue value = json.fromJson("\"ready\"", CompleteValue.class);
    assertEquals(value.value, "ready");
    assertEquals(value.validations, 0);
  }

  private static void assertMutable(MutableValue value, String expected) {
    assertEquals(value.name, expected);
    assertEquals(value.observed, expected);
    assertEquals(value.validations, 1);
  }

  public static final class MutableValue {
    public String name;
    public transient String observed;
    public transient int validations;

    @JsonValidator
    public void validate() {
      observed = name;
      validations++;
    }
  }

  public static final class GenericValue<T> {
    public T value;
    public transient T observed;
    public transient int validations;

    @JsonValidator
    public void validate() {
      observed = value;
      validations++;
    }
  }

  public static final class CreatorValue {
    public final int id;
    public transient int validations;

    @JsonCreator({"id"})
    public CreatorValue(int id) {
      this.id = id;
    }

    @JsonValidator
    public void validate() {
      if (id != 7) {
        throw new IllegalArgumentException("invalid id");
      }
      validations++;
    }
  }

  public static final class ValidatedChild {
    public int id;
    public transient int validations;

    @JsonValidator
    public void validate() {
      if (id <= 0) {
        throw new IllegalArgumentException("invalid child");
      }
      validations++;
    }
  }

  public static final class NestedParent {
    public ValidatedChild child;
    public transient int validations;

    @JsonValidator
    public void validate() {
      if (child == null || child.validations != 1) {
        throw new IllegalStateException("child not validated");
      }
      validations++;
    }
  }

  public static final class UnwrappedParent {
    @JsonUnwrapped(prefix = "child_")
    public ValidatedChild child;

    public transient int validations;

    @JsonValidator
    public void validate() {
      if (child == null || child.validations != 1) {
        throw new IllegalStateException("child not validated");
      }
      validations++;
    }
  }

  public static final class AnyValue {
    @JsonAnyProperty public Map<String, Integer> properties;
    public transient int validations;

    @JsonValidator
    public void validate() {
      if (properties == null || properties.get("dynamic") == null) {
        throw new IllegalStateException("missing dynamic property");
      }
      validations++;
    }
  }

  @JsonSubTypes(
      property = "kind",
      value = {@JsonSubTypes.Type(value = ValidatedCircle.class, name = "circle")})
  public interface Shape {}

  public static final class ValidatedCircle implements Shape {
    public int radius;
    public transient int validations;

    @JsonValidator
    public void validate() {
      if (radius <= 0) {
        throw new IllegalArgumentException("invalid radius");
      }
      validations++;
    }
  }

  public static final class MultipleValue {
    public transient boolean firstCalled;
    public transient boolean secondCalled;

    @JsonValidator
    public void first() {
      firstCalled = true;
    }

    @JsonValidator
    public void second() {
      secondCalled = true;
    }
  }

  public static class ParentValue {
    public transient int validations;

    @JsonValidator
    public void validate() {
      validations++;
    }
  }

  public static final class InheritedValue extends ParentValue {}

  public static final class OverrideValue extends ParentValue {
    @Override
    public void validate() {}
  }

  public static final class NullTracked {
    static int validations;

    @JsonValidator
    public void validate() {
      validations++;
    }
  }

  public static final class PrivateValidator {
    @JsonValidator
    private void validate() {}
  }

  public static final class StaticValidator {
    @JsonValidator
    public static void validate() {}
  }

  public static final class ArgumentValidator {
    @JsonValidator
    public void validate(int value) {}
  }

  public static final class ReturningValidator {
    @JsonValidator
    public int validate() {
      return 0;
    }
  }

  public static final class FailureValue {
    public String failure;
    public transient int validations;

    @JsonValidator
    public void validate() throws IOException {
      if ("checked".equals(failure)) {
        throw new IOException("checked validator");
      }
      if ("runtime".equals(failure)) {
        throw new IllegalStateException("runtime validator");
      }
      if ("error".equals(failure)) {
        throw new AssertionError("validator error");
      }
      validations++;
    }
  }

  public static final class ScalarValue {
    @JsonValue public String value;
    public transient int validations;

    @JsonCreator
    public ScalarValue(String value) {
      this.value = value;
    }

    @JsonValidator
    public void validate() {
      validations++;
    }
  }

  public static final class CompleteValue {
    public final String value;
    public transient int validations;

    CompleteValue(String value) {
      this.value = value;
    }

    @JsonValidator
    public void validate() {
      validations++;
    }
  }

  private static final class CompleteValueCodec extends AbstractJsonValueCodec<CompleteValue> {
    @Override
    public void write(JsonWriter writer, CompleteValue value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.value);
      }
    }

    @Override
    public CompleteValue read(JsonReader reader) {
      String value = reader.readNullableString();
      return value == null ? null : new CompleteValue(value);
    }
  }
}
