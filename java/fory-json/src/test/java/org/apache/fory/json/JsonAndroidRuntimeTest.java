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
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.meta.GeneratedJsonCodecMeta;
import org.apache.fory.json.meta.JsonTypeUse;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.JdkVersion;
import org.testng.annotations.Test;

public class JsonAndroidRuntimeTest {
  @Test
  public void generatedMap() {
    GeneratedJsonCodecMeta meta = new AndroidModel_ForyJsonCodecMeta();
    Map<Member, JsonTypeUse[]> first = meta.typeUses();
    assertSame(meta.typeUses(), first);
    assertThrows(
        UnsupportedOperationException.class,
        () -> first.put(first.keySet().iterator().next(), new JsonTypeUse[0]));
  }

  @Test
  public void normalJvmIgnoresCompanion() throws Exception {
    Field rootField = RootFieldModel.class.getField("value");
    Method rootGetter = RootGetterModel.class.getMethod("getValue");
    assertNull(rootField.getAnnotation(JsonCodec.class));
    assertNotNull(rootField.getAnnotatedType().getAnnotation(JsonCodec.class));
    assertNull(rootGetter.getAnnotation(JsonCodec.class));
    assertNotNull(rootGetter.getAnnotatedReturnType().getAnnotation(JsonCodec.class));

    int constructions = AndroidModel_ForyJsonCodecMeta.constructions;
    int calls = AndroidModel_ForyJsonCodecMeta.calls;
    ForyJson json = ForyJson.builder().withCodegen(false).build();
    AndroidModel model = new AndroidModel();
    model.values = Collections.singletonList("plain");
    String text = json.toJson(model);
    assertTrue(text.contains("\"plain\""), text);
    assertTrue(text.contains("\"label\""), text);
    assertFalse(text.contains("\"A:"), text);
    assertEquals(AndroidModel_ForyJsonCodecMeta.constructions, constructions);
    assertEquals(AndroidModel_ForyJsonCodecMeta.calls, calls);
  }

  @Test
  public void forcedAndroid() throws Exception {
    ProcessBuilder processBuilder =
        new ProcessBuilder(javaCommand(System.getProperty("java.class.path"), AndroidMain.class))
            .redirectErrorStream(true);
    processBuilder.environment().put("FORY_ANDROID_ENABLED", "1");
    Process process = processBuilder.start();
    String output = readFully(process.getInputStream());
    assertEquals(process.waitFor(), 0, output);
    assertTrue(output.contains("RESULT:ok"), output);
  }

  private static List<String> javaCommand(String classPath, Class<?> mainClass) {
    List<String> command =
        new ArrayList<>(
            Arrays.asList(
                System.getProperty("java.home")
                    + File.separator
                    + "bin"
                    + File.separator
                    + "java"));
    if (JdkVersion.MAJOR_VERSION >= 25) {
      command.add("--add-opens=java.base/java.lang.invoke=ALL-UNNAMED");
    }
    command.add("-cp");
    command.add(classPath);
    command.add(mainClass.getName());
    return command;
  }

  private static String readFully(InputStream inputStream) throws IOException {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int read;
    while ((read = inputStream.read(buffer)) != -1) {
      outputStream.write(buffer, 0, read);
    }
    return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
  }

  public static final class AndroidMain {
    public static void main(String[] args) {
      assertTrue(AndroidSupport.IS_ANDROID);
      ForyJson json = ForyJson.builder().withCodegen(true).withAsyncCompilation(true).build();
      assertFalse(ForyJsonTestModels.hasGeneratedCapability(json, AndroidModel.class));

      AndroidModel model = new AndroidModel();
      model.values = Arrays.asList("one", "two");
      String text = json.toJson(model);
      assertTrue(text.contains("\"values\":[\"A:one\",\"A:two\"]"), text);
      assertTrue(text.contains("\"inherited\":[\"A:one\",\"A:two\"]"), text);
      assertTrue(text.contains("\"A:label\""), text);
      AndroidModel decoded = json.fromJson("{\"values\":[\"A:three\"]}", AndroidModel.class);
      assertEquals(decoded.values, Collections.singletonList("three"));

      DirectModel direct = new DirectModel();
      direct.value = "four";
      assertEquals(json.toJson(direct), "{\"value\":\"A:four\"}");
      assertEquals(json.fromJson("{\"value\":\"A:five\"}", DirectModel.class).value, "five");

      GetterModel getter = new GetterModel();
      getter.setValue("six");
      assertEquals(json.toJson(getter), "{\"value\":\"A:six\"}");
      assertEquals(json.fromJson("{\"value\":\"A:seven\"}", GetterModel.class).getValue(), "seven");

      RootFieldModel rootField = new RootFieldModel();
      rootField.value = "eight";
      assertEquals(json.toJson(rootField), "{\"value\":\"eight\"}");
      assertEquals(json.fromJson("{\"value\":\"nine\"}", RootFieldModel.class).value, "nine");

      RootGetterModel rootGetter = new RootGetterModel();
      rootGetter.setValue("ten");
      assertEquals(json.toJson(rootGetter), "{\"value\":\"ten\"}");
      assertEquals(
          json.fromJson("{\"value\":\"eleven\"}", RootGetterModel.class).getValue(), "eleven");

      JsonCodecAnnotationTest.ParameterLocalCreator creator =
          json.fromJson(
              "{\"value\":\"A:creator\"}", JsonCodecAnnotationTest.ParameterLocalCreator.class);
      assertEquals(creator.getValue(), "A:creator");
      assertEquals(json.toJson(creator), "{\"value\":\"A:creator\"}");

      assertEquals(json.toJson(new JsonCodecAnnotationTest.DeclaredValue()), "\"declared-value\"");
      assertTrue(
          json.fromJson("\"declared-value\"", JsonCodecAnnotationTest.DeclaredValue.class)
              instanceof JsonCodecAnnotationTest.DeclaredValue);

      MissingCompanion plain = new MissingCompanion();
      plain.values = Collections.singletonList("plain");
      assertEquals(json.toJson(plain), "{\"values\":[\"plain\"]}");

      ForyJsonException malformed =
          expectThrows(ForyJsonException.class, () -> json.toJson(new MalformedModel()));
      assertTrue(malformed.getMessage().contains("declaring type is outside the hierarchy"));
      assertEquals(AndroidModel_ForyJsonCodecMeta.constructions, 1);
      assertEquals(AndroidModel_ForyJsonCodecMeta.calls, 1);
      System.out.println("RESULT:ok");
    }
  }

  public static class AndroidParent<T> {
    public List<T> values;

    public List<T> getInherited() {
      return values;
    }
  }

  public interface AndroidLabels {
    default List<String> getLabels() {
      return Collections.singletonList("label");
    }
  }

  public static final class AndroidModel extends AndroidParent<String> implements AndroidLabels {}

  public static final class AndroidModel_ForyJsonCodecMeta implements GeneratedJsonCodecMeta {
    private static final Map<Member, JsonTypeUse[]> TYPE_USES = buildTypeUses();
    private static int constructions;
    private static int calls;

    public AndroidModel_ForyJsonCodecMeta() {
      constructions++;
    }

    @Override
    public Map<Member, JsonTypeUse[]> typeUses() {
      calls++;
      return TYPE_USES;
    }

    private static Map<Member, JsonTypeUse[]> buildTypeUses() {
      try {
        Field values = AndroidParent.class.getDeclaredField("values");
        Method inherited = AndroidParent.class.getDeclaredMethod("getInherited");
        Method labels = AndroidLabels.class.getMethod("getLabels");
        Map<Member, JsonTypeUse[]> typeUses = new LinkedHashMap<>();
        typeUses.put(
            values,
            new JsonTypeUse[] {
              JsonTypeUse.generated(
                  values.getGenericType(),
                  JsonCodecAnnotationTest.AStringCodec.class,
                  "field " + values,
                  0)
            });
        typeUses.put(
            inherited,
            new JsonTypeUse[] {
              JsonTypeUse.generated(
                  inherited.getGenericReturnType(),
                  JsonCodecAnnotationTest.AStringCodec.class,
                  "method return " + inherited,
                  0)
            });
        typeUses.put(
            labels,
            new JsonTypeUse[] {
              JsonTypeUse.generated(
                  labels.getGenericReturnType(),
                  JsonCodecAnnotationTest.AStringCodec.class,
                  "method return " + labels,
                  0)
            });
        return Collections.unmodifiableMap(typeUses);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }

  public static final class DirectModel {
    @JsonCodec(JsonCodecAnnotationTest.AStringCodec.class)
    public String value;
  }

  public static final class GetterModel {
    private String value;

    @JsonCodec(JsonCodecAnnotationTest.AStringCodec.class)
    public String getValue() {
      return value;
    }

    public void setValue(String value) {
      this.value = value;
    }
  }

  public static final class RootFieldModel {
    public java.lang.@JsonCodec(JsonCodecAnnotationTest.AStringCodec.class) String value;
  }

  public static final class RootGetterModel {
    private String value;

    public java.lang.@JsonCodec(JsonCodecAnnotationTest.AStringCodec.class) String getValue() {
      return value;
    }

    public void setValue(String value) {
      this.value = value;
    }
  }

  public static final class MissingCompanion {
    public List<String> values;
  }

  public static final class MalformedModel {
    public List<String> values;
  }

  public static final class MalformedModel_ForyJsonCodecMeta implements GeneratedJsonCodecMeta {
    private static final Map<Member, JsonTypeUse[]> TYPE_USES = buildTypeUses();

    public MalformedModel_ForyJsonCodecMeta() {}

    @Override
    public Map<Member, JsonTypeUse[]> typeUses() {
      return TYPE_USES;
    }

    private static Map<Member, JsonTypeUse[]> buildTypeUses() {
      try {
        Field values = AndroidParent.class.getDeclaredField("values");
        Map<Member, JsonTypeUse[]> typeUses = new LinkedHashMap<>();
        typeUses.put(
            values,
            new JsonTypeUse[] {
              JsonTypeUse.generated(
                  values.getGenericType(),
                  JsonCodecAnnotationTest.AStringCodec.class,
                  "field " + values,
                  0)
            });
        return Collections.unmodifiableMap(typeUses);
      } catch (ReflectiveOperationException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
  }
}
