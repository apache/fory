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

package org.apache.fory.android;

import java.util.Arrays;
import org.apache.fory.json.ForyJson;

/** Android acceptance scenarios for reflection, generated metadata, and R8 retention. */
public final class AndroidJsonScenarios {
  private AndroidJsonScenarios() {}

  public static void plainReflectionWithoutRules(boolean debuggable) {
    if (!debuggable) {
      return;
    }
    ForyJson json = ForyJson.builder().build();
    ReflectionJsonModel value = new ReflectionJsonModel(26, "reflection");
    String encoded = json.toJson(value);
    checkEquals("{\"id\":26,\"name\":\"reflection\"}", encoded);
    ReflectionJsonModel decoded = json.fromJson(encoded, ReflectionJsonModel.class);
    checkEquals(26, decoded.id);
    checkEquals("reflection", decoded.name);
  }

  public static void manualPlainRules() {
    ForyJson json = ForyJson.builder().build();
    ManualPlainJsonModel value = new ManualPlainJsonModel(27, "manual-plain");
    String encoded = json.toJson(value);
    checkEquals("{\"id\":27,\"name\":\"manual-plain\"}", encoded);
    ManualPlainJsonModel decoded = json.fromJson(encoded, ManualPlainJsonModel.class);
    checkEquals(27, decoded.id);
    checkEquals("manual-plain", decoded.name);
  }

  public static void generatedPlainRules() {
    ForyJson json = ForyJson.builder().build();
    GeneratedPlainJsonModel value = new GeneratedPlainJsonModel(28, "generated-plain");
    String encoded = json.toJson(value);
    checkEquals("{\"id\":28,\"name\":\"generated-plain\"}", encoded);
    GeneratedPlainJsonModel decoded = json.fromJson(encoded, GeneratedPlainJsonModel.class);
    checkEquals(28, decoded.id);
    checkEquals("generated-plain", decoded.name);
  }

  public static void manualCodecs() {
    ForyJson json = ForyJson.builder().build();
    ManualJsonModel value = new ManualJsonModel();
    value.direct = new ManualJsonModel.DirectValue("direct");
    value.declaredField = new ManualJsonModel.MemberValue("field");
    value.setDeclaredProperty(new ManualJsonModel.MemberValue("method"));

    ManualJsonModel.resetCodecCalls();
    String encoded = json.toJson(value);
    check(encoded.contains("\"direct\":\"manual:direct\""));
    check(encoded.contains("\"declaredField\":\"manual:field\""));
    check(encoded.contains("\"declaredProperty\":\"manual:method\""));

    ManualJsonModel decoded = json.fromJson(encoded, ManualJsonModel.class);
    checkEquals("manual:direct", decoded.direct.text);
    checkEquals("manual:field", decoded.declaredField.text);
    checkEquals("manual:method", decoded.getDeclaredProperty().text);
    checkEquals(6, ManualJsonModel.codecCalls());
  }

  public static void nestedCodecNeedsJsonType() {
    ForyJson json = ForyJson.builder().build();
    ManualNestedModel value = new ManualNestedModel();
    value.values.add(new ManualNestedModel.NestedValue("plain"));

    ManualNestedModel.resetCodecCalls();
    String encoded = json.toJson(value);
    checkEquals("{\"values\":[{\"text\":\"plain\"}]}", encoded);
    ManualNestedModel decoded = json.fromJson(encoded, ManualNestedModel.class);
    checkEquals("plain", decoded.values.get(0).text);
    checkEquals(0, ManualNestedModel.codecCalls());
  }

  public static void generatedCodecs() {
    ForyJson json = ForyJson.builder().build();
    GeneratedJsonSubtype value = new GeneratedJsonSubtype();
    value.subtypeId = 29;
    value.values.add(new GeneratedJsonModel.Value("list"));
    value.rootValue = new GeneratedJsonModel.Value("root");
    value.setRootProperty(new GeneratedJsonModel.Value("property"));
    value.array = new GeneratedJsonModel.Value[] {new GeneratedJsonModel.Value("array")};
    value.byName.put("map", new GeneratedJsonModel.Value("map"));
    value.inheritedValues.add(new GeneratedJsonModel.Value("parent"));
    value.replaceInterfaceValues(Arrays.asList(new GeneratedJsonModel.Value("interface")));
    value.replaceSuppressedValues(Arrays.asList(new GeneratedJsonModel.PlainValue("plain")));
    value.extra.put("dynamic", new GeneratedJsonModel.Value("extra"));

    GeneratedJsonModel.resetCodecCalls();
    String encoded = json.toJson(value, GeneratedJsonModel.class);
    check(encoded.contains("\"kind\":\"generated\""));
    check(encoded.contains("\"generated:list\""));
    check(encoded.contains("\"generated:root\""));
    check(encoded.contains("\"generated:property\""));
    check(encoded.contains("\"generated:array\""));
    check(encoded.contains("\"generated:map\""));
    check(encoded.contains("\"generated:parent\""));
    check(encoded.contains("\"generated:interface\""));
    check(encoded.contains("\"suppressedValues\":[{\"text\":\"plain\"}]"));
    check(encoded.contains("\"dynamic\":\"generated:extra\""));

    GeneratedJsonModel decoded = json.fromJson(encoded, GeneratedJsonModel.class);
    check(decoded instanceof GeneratedJsonSubtype);
    GeneratedJsonSubtype subtype = (GeneratedJsonSubtype) decoded;
    checkEquals(29, subtype.subtypeId);
    checkEquals("generated:list", subtype.values.get(0).text);
    checkEquals("generated:root", subtype.rootValue.text);
    checkEquals("generated:property", subtype.getRootProperty().text);
    checkEquals("generated:array", subtype.array[0].text);
    checkEquals("generated:map", subtype.byName.get("map").text);
    checkEquals("generated:parent", subtype.inheritedValues.get(0).text);
    checkEquals("generated:interface", subtype.getInterfaceValues().get(0).text);
    checkEquals("plain", subtype.getSuppressedValues().get(0).text);
    checkEquals("generated:extra", subtype.extra.get("dynamic").text);

    GeneratedJsonModel.CreatedValue created =
        new GeneratedJsonModel.CreatedValue(
            Arrays.asList(new GeneratedJsonModel.Value("creator")),
            new GeneratedJsonModel.Value("direct"));
    String creatorJson = json.toJson(created);
    check(creatorJson.contains("\"values\":[\"generated:creator\"]"));
    check(creatorJson.contains("\"direct\":\"generated:direct\""));
    GeneratedJsonModel.CreatedValue decodedCreated =
        json.fromJson(creatorJson, GeneratedJsonModel.CreatedValue.class);
    checkEquals("generated:creator", decodedCreated.values.get(0).text);
    checkEquals("generated:direct", decodedCreated.direct.text);
    checkEquals(20, GeneratedJsonModel.codecCalls());
    checkEquals(0, GeneratedJsonModel.suppressedCodecCalls());
  }

  private static void check(boolean condition) {
    if (!condition) {
      throw new AssertionError("check failed");
    }
  }

  private static void checkEquals(Object expected, Object actual) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError("expected " + expected + " but got " + actual);
    }
  }
}
