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
import static org.testng.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.fory.json.annotation.JsonBase64;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonRawValue;
import org.apache.fory.json.annotation.JsonValue;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.JdkVersion;
import org.testng.annotations.Test;

public class JsonAndroidRuntimeTest {
  @Test
  public void normalJvm() {
    assertRoundTrips(ForyJson.builder().withCodegen(false).build());
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

  private static void assertRoundTrips(ForyJson json) {
    AndroidModel model = new AndroidModel();
    model.values = Arrays.asList("one", "two");
    String text = json.toJson(model);
    assertTrue(text.contains("\"values\":[\"A:one\",\"A:two\"]"), text);
    assertTrue(text.contains("\"labels\":[\"A:label\"]"), text);
    assertEquals(
        json.fromJson("{\"values\":[\"A:three\"]}", AndroidModel.class).values,
        Collections.singletonList("three"));

    DirectModel direct = new DirectModel();
    direct.value = "four";
    assertEquals(json.toJson(direct), "{\"value\":\"A:four\"}");
    assertEquals(json.fromJson("{\"value\":\"A:five\"}", DirectModel.class).value, "five");

    GetterModel getter = new GetterModel();
    getter.setValue("six");
    assertEquals(json.toJson(getter), "{\"value\":\"A:six\"}");
    assertEquals(json.fromJson("{\"value\":\"A:seven\"}", GetterModel.class).getValue(), "seven");

    SetterModel setter = new SetterModel();
    setter.setValue("eight");
    assertEquals(json.toJson(setter), "{\"value\":\"A:eight\"}");
    assertEquals(json.fromJson("{\"value\":\"A:nine\"}", SetterModel.class).getValue(), "nine");

    JsonCodecAnnotationTest.ParameterLocalCreator creator =
        json.fromJson(
            "{\"value\":\"A:creator\"}", JsonCodecAnnotationTest.ParameterLocalCreator.class);
    assertEquals(creator.getValue(), "creator");
    assertEquals(json.toJson(creator), "{\"value\":\"A:creator\"}");

    assertEquals(json.toJson(new JsonCodecAnnotationTest.DeclaredValue()), "\"declared-value\"");
    assertTrue(
        json.fromJson("\"declared-value\"", JsonCodecAnnotationTest.DeclaredValue.class)
            instanceof JsonCodecAnnotationTest.DeclaredValue);

    AndroidValue androidValue = new AndroidValue("ten");
    assertEquals(json.toJson(androidValue), "\"ten\"");
    assertEquals(json.fromJson("\"eleven\"", AndroidValue.class).value, "eleven");

    AndroidRaw androidRaw = new AndroidRaw();
    androidRaw.body = "{\"ok\":true}";
    androidRaw.bytes = new byte[] {1, 2, 3};
    String rawJson = json.toJson(androidRaw);
    assertTrue(rawJson.contains("\"body\":{\"ok\":true}"), rawJson);
    assertTrue(rawJson.contains("\"bytes\":\"AQID\""), rawJson);
    AndroidRaw decodedRaw =
        json.fromJson("{\"body\":\"text\",\"bytes\":\"AQID\"}", AndroidRaw.class);
    assertEquals(decodedRaw.body, "text");
    assertEquals(decodedRaw.bytes, new byte[] {1, 2, 3});
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
      assertRoundTrips(json);
      System.out.println("RESULT:ok");
    }
  }

  public static class AndroidParent<T> {
    @JsonCodec(elementCodec = JsonCodecAnnotationTest.AStringCodec.class)
    public List<T> values;
  }

  public interface AndroidLabels {
    @JsonCodec(elementCodec = JsonCodecAnnotationTest.AStringCodec.class)
    default List<String> getLabels() {
      return Collections.singletonList("label");
    }
  }

  public static final class AndroidModel extends AndroidParent<String> implements AndroidLabels {}

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

  public static final class SetterModel {
    private String value;

    public String getValue() {
      return value;
    }

    public void setValue(@JsonCodec(JsonCodecAnnotationTest.AStringCodec.class) String value) {
      this.value = value;
    }
  }

  public static final class AndroidValue {
    private final String value;

    @JsonCreator
    public AndroidValue(String value) {
      this.value = value;
    }

    @JsonValue
    public String value() {
      return value;
    }
  }

  public static final class AndroidRaw {
    @JsonRawValue public String body;
    @JsonBase64 public byte[] bytes;
  }
}
