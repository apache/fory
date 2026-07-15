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

package org.apache.fory.android.json;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.apache.fory.android.json.aar.AarModel;
import org.apache.fory.android.json.jar.JarAnyModel;
import org.apache.fory.android.json.jar.JarArrayShape;
import org.apache.fory.android.json.jar.JarCircle;
import org.apache.fory.android.json.jar.JarCodedValue;
import org.apache.fory.android.json.jar.JarCreatorModel;
import org.apache.fory.android.json.jar.JarModel;
import org.apache.fory.android.json.jar.JarObjectShape;
import org.apache.fory.android.json.jar.JarPrimitiveBean;
import org.apache.fory.android.json.jar.JarShape;
import org.apache.fory.android.json.jar.JarSquare;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.reflect.TypeRef;

public final class AndroidJsonRuntimeScenarios {
  private AndroidJsonRuntimeScenarios() {}

  public static void applicationJarAndAarModelsRoundTrip() {
    check(AndroidSupport.IS_ANDROID, "Fory JSON Android tests must run on Android");
    ForyJson json = ForyJson.builder().withCodegen(true).withAsyncCompilation(true).build();
    AppModel value = new AppModel();
    value.appId = 26;
    value.jarModel = new JarModel(7, "jar");
    value.jarModel.scores.add(Integer.valueOf(3));
    value.aarModel = new AarModel("aar");
    value.aarModel.putAttribute("source", "library");
    value.codedValue = new JarCodedValue("android");
    value.shape = new JarSquare(5);

    String text = json.toJson(value);
    AppModel fromString = json.fromJson(text, AppModel.class);
    AppModel fromUtf8 = json.fromJson(text.getBytes(StandardCharsets.UTF_8), AppModel.class);
    assertAppModel(fromString);
    assertAppModel(fromUtf8);

    byte[] bytes = json.toJsonBytes(value);
    assertEquals(text, new String(bytes, StandardCharsets.UTF_8));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    json.writeJsonTo(value, output);
    assertEquals(text, new String(output.toByteArray(), StandardCharsets.UTF_8));
  }

  public static void builderConfigurationsRemainIndependent() {
    AppModel value = new AppModel();
    value.appId = 11;
    value.jarModel = new JarModel(12, "display name");

    ForyJson lowerCamel =
        ForyJson.builder()
            .withCodegen(true)
            .withAsyncCompilation(true)
            .writeNullFields(false)
            .build();
    ForyJson snake =
        ForyJson.builder()
            .withCodegen(false)
            .withAsyncCompilation(false)
            .writeNullFields(true)
            .withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
            .build();
    ForyJson fields = ForyJson.builder().withFieldMode(true).build();
    FieldModeModel fieldValue = new FieldModeModel();
    fieldValue.appId = 11;

    String camelText = lowerCamel.toJson(value);
    String snakeText = snake.toJson(value);
    String fieldText = fields.toJson(fieldValue);
    check(camelText.contains("\"appId\""), "lower camel property missing: " + camelText);
    check(!camelText.contains("nullableName"), "null property must be omitted: " + camelText);
    check(snakeText.contains("\"app_id\""), "snake property missing: " + snakeText);
    check(snakeText.contains("\"nullable_name\":null"), "null property missing: " + snakeText);
    check(snakeText.contains("\"coded_value\":null"), "codec null property missing: " + snakeText);
    check(fieldText.contains("\"appId\""), "field mode property missing: " + fieldText);

    assertEquals(11, lowerCamel.fromJson(camelText, AppModel.class).appId);
    assertEquals(11, snake.fromJson(snakeText, AppModel.class).appId);
    assertEquals(11, fields.fromJson(fieldText, FieldModeModel.class).appId);
  }

  public static void primitiveBeanUsesEveryTypedAccessor() {
    JarPrimitiveBean value = new JarPrimitiveBean();
    value.setBoolValue(true);
    value.setByteValue((byte) 2);
    value.setCharValue('A');
    value.setShortValue((short) 4);
    value.setIntValue(5);
    value.setLongValue(6L);
    value.setFloatValue(7.5f);
    value.setDoubleValue(8.5d);
    value.setTextValue("bean");

    ForyJson json = ForyJson.builder().build();
    JarPrimitiveBean copy = json.fromJson(json.toJsonBytes(value), JarPrimitiveBean.class);
    check(copy.isBoolValue(), "boolean accessor mismatch");
    assertEquals(2, copy.getByteValue());
    assertEquals('A', copy.getCharValue());
    assertEquals(4, copy.getShortValue());
    assertEquals(5, copy.getIntValue());
    assertEquals(6L, copy.getLongValue());
    check(Float.compare(7.5f, copy.getFloatValue()) == 0, "float accessor mismatch");
    check(Double.compare(8.5d, copy.getDoubleValue()) == 0, "double accessor mismatch");
    assertEquals("bean", copy.getTextValue());
  }

  public static void creatorAnyCodecAndSubtypesRoundTrip() {
    ForyJson json = ForyJson.builder().build();

    JarCreatorModel creator =
        json.fromJson("{\"id\":17,\"name\":\"creator\"}", JarCreatorModel.class);
    assertEquals(17, creator.id);
    assertEquals("creator", creator.name);

    JarAnyModel any = new JarAnyModel();
    any.fixed = "fixed";
    any.extra.put("dynamic", Integer.valueOf(9));
    JarAnyModel anyCopy = json.fromJson(json.toJson(any), JarAnyModel.class);
    assertEquals("fixed", anyCopy.fixed);
    assertEquals(9, anyCopy.extra.get("dynamic").intValue());

    JarCodedValue coded = json.fromJson("\"coded:value\"", JarCodedValue.class);
    assertEquals("value", coded.value);
    assertEquals("\"coded:value\"", json.toJson(coded));

    JarShape square = json.fromJson(json.toJson(new JarSquare(4), JarShape.class), JarShape.class);
    check(square instanceof JarSquare, "literal subtype mismatch");
    assertEquals(4, ((JarSquare) square).size);
    JarShape circle = json.fromJson(json.toJson(new JarCircle(3), JarShape.class), JarShape.class);
    check(circle instanceof JarCircle, "class-name subtype mismatch");
    assertEquals(3, ((JarCircle) circle).radius);

    JarObjectShape objectShape =
        json.fromJson(json.toJson(new JarSquare(6), JarObjectShape.class), JarObjectShape.class);
    check(objectShape instanceof JarSquare, "wrapper-object subtype mismatch");
    assertEquals(6, ((JarSquare) objectShape).size);
    JarArrayShape arrayShape =
        json.fromJson(json.toJson(new JarSquare(8), JarArrayShape.class), JarArrayShape.class);
    check(arrayShape instanceof JarSquare, "wrapper-array subtype mismatch");
    assertEquals(8, ((JarSquare) arrayShape).size);

    PrivateCreatorModel privateCreator =
        json.fromJson("{\"number\":31}", PrivateCreatorModel.class);
    assertEquals(31, privateCreator.number);
    assertEquals("{\"number\":31}", json.toJson(privateCreator));
    PrivateFactoryModel privateFactory =
        json.fromJson("{\"number\":37}", PrivateFactoryModel.class);
    assertEquals(37, privateFactory.number);
    assertEquals("{\"number\":37}", json.toJson(privateFactory));
  }

  public static void typeRefAndPrivateStaticTypeSurviveR8() {
    ForyJson json = ForyJson.builder().build();
    TypeRef<List<JarModel>> type = new TypeRef<List<JarModel>>() {};
    List<JarModel> values = Arrays.asList(new JarModel(1, "one"), new JarModel(2, "two"));
    List<JarModel> copy = json.fromJson(json.toJson(values, type), type);
    assertEquals(2, copy.size());
    assertEquals("two", copy.get(1).displayName);

    PrivateStaticModel privateValue = new PrivateStaticModel();
    privateValue.number = 31;
    PrivateStaticModel privateCopy =
        json.fromJson(json.toJson(privateValue), PrivateStaticModel.class);
    assertEquals(31, privateCopy.number);
  }

  public static Object newWriteBenchmark() {
    return new WriteBenchmark(ForyJson.builder().build(), benchmarkValue());
  }

  public static Object writeBenchmark(Object state) {
    WriteBenchmark benchmark = (WriteBenchmark) state;
    return benchmark.json.toJsonBytes(benchmark.value);
  }

  public static Object newReadBenchmark() {
    ForyJson json = ForyJson.builder().build();
    byte[] bytes = json.toJson(benchmarkValue()).getBytes(StandardCharsets.UTF_8);
    return new ReadBenchmark(json, bytes);
  }

  public static Object readBenchmark(Object state) {
    ReadBenchmark benchmark = (ReadBenchmark) state;
    return benchmark.json.fromJson(benchmark.bytes, AppModel.class);
  }

  private static AppModel benchmarkValue() {
    AppModel value = new AppModel();
    value.appId = 42;
    value.jarModel = new JarModel(7, "benchmark");
    value.jarModel.scores.add(Integer.valueOf(1));
    value.aarModel = new AarModel("aar");
    value.codedValue = new JarCodedValue("value");
    value.shape = new JarCircle(2);
    return value;
  }

  private static final class WriteBenchmark {
    private final ForyJson json;
    private final AppModel value;

    private WriteBenchmark(ForyJson json, AppModel value) {
      this.json = json;
      this.value = value;
    }
  }

  private static final class ReadBenchmark {
    private final ForyJson json;
    private final byte[] bytes;

    private ReadBenchmark(ForyJson json, byte[] bytes) {
      this.json = json;
      this.bytes = bytes;
    }
  }

  private static void assertAppModel(AppModel value) {
    assertEquals(26, value.appId);
    assertEquals(7, value.jarModel.id);
    assertEquals("jar", value.jarModel.displayName);
    assertEquals(3, value.jarModel.scores.get(0).intValue());
    assertEquals("aar", value.aarModel.getLabel());
    assertEquals("library", value.aarModel.attributes().get("source"));
    assertEquals("android", value.codedValue.value);
    check(value.shape instanceof JarSquare, "nested subtype mismatch");
  }

  @JsonType
  private static final class PrivateStaticModel {
    private int number;

    private PrivateStaticModel() {}
  }

  @JsonType
  private static final class PrivateCreatorModel {
    private final int number;

    @JsonCreator
    public PrivateCreatorModel(@JsonProperty("number") int number) {
      this.number = number;
    }
  }

  @JsonType
  private static final class PrivateFactoryModel {
    private final int number;

    private PrivateFactoryModel(int number) {
      this.number = number;
    }

    @JsonCreator
    public static PrivateFactoryModel create(@JsonProperty("number") int number) {
      return new PrivateFactoryModel(number);
    }
  }

  private static void assertEquals(String expected, String actual) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError("Expected " + expected + " but got " + actual);
    }
  }

  private static void assertEquals(long expected, long actual) {
    if (expected != actual) {
      throw new AssertionError("Expected " + expected + " but got " + actual);
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
