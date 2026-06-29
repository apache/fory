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

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.apache.fory.json.data.FastContainers;
import org.apache.fory.json.data.GeneratedCollectionFields;
import org.apache.fory.json.data.JsonTestData;
import org.apache.fory.json.data.Kind;
import org.apache.fory.json.data.MethodsIgnored;
import org.apache.fory.json.data.NumericBoundaries;
import org.apache.fory.json.data.PrivateFields;
import org.apache.fory.json.data.PublicFields;
import org.apache.fory.json.data.TokenValues;
import org.apache.fory.json.data.UnicodeMatrix;
import org.apache.fory.reflect.FieldAccessor;
import org.testng.SkipException;

public abstract class ForyJsonTestModels {
  protected static final String TWO_BYTE_TEXT = JsonTestData.TWO_BYTE_TEXT;
  protected static final String THREE_BYTE_TEXT = JsonTestData.THREE_BYTE_TEXT;
  protected static final String SUPPLEMENTARY_TEXT = JsonTestData.SUPPLEMENTARY_TEXT;
  protected static final String MIXED_SCRIPT_TEXT = JsonTestData.MIXED_SCRIPT_TEXT;
  protected static final String COMBINING_TEXT = JsonTestData.COMBINING_TEXT;
  protected static final String ZH_TEXT = JsonTestData.ZH_TEXT;
  protected static final String EU_TEXT = JsonTestData.EU_TEXT;

  protected static TokenValues tokenValue(int count, String name, List<String> tags, long total) {
    TokenValues value = new TokenValues();
    value.count = count;
    value.name = name;
    value.tags = tags;
    value.total = total;
    return value;
  }

  protected static String hiddenValue(MethodsIgnored value) {
    return MethodsIgnored.hiddenValue(value);
  }

  protected static int privateId(PrivateFields value) {
    return PrivateFields.id(value);
  }

  protected static String privateName(PrivateFields value) {
    return PrivateFields.name(value);
  }

  protected static String privateNullable(PrivateFields value) {
    return PrivateFields.nullable(value);
  }

  protected static String privateTransientValue(PrivateFields value) {
    return PrivateFields.transientValue(value);
  }

  protected static String privateStaticValue() {
    return PrivateFields.staticValue();
  }

  protected static Map<String, Integer> scores() {
    return JsonTestData.scores();
  }

  protected static Map<String, Boolean> flags() {
    return JsonTestData.flags();
  }

  protected static Map<Integer, String> intNames() {
    return JsonTestData.intNames();
  }

  protected static EnumMap<Kind, Integer> enumScores() {
    return JsonTestData.enumScores();
  }

  protected static Calendar calendar(long millis) {
    return JsonTestData.calendar(millis);
  }

  protected static byte[] byteBufferBytes(ByteBuffer buffer) {
    ByteBuffer copy = buffer.duplicate();
    byte[] bytes = new byte[copy.remaining()];
    copy.get(bytes);
    return bytes;
  }

  protected static Map<String, String> unicodeMap() {
    return JsonTestData.unicodeMap();
  }

  protected static String unicodeMatrixJson() {
    return "{\"boxedChar\":\"\u20ac\",\"charThreeByte\":\"\u4f60\","
        + "\"charTwoByte\":\"\u0100\",\"chars\":[\"\u0100\",\"\u07ff\",\"\u0800\","
        + "\"\u20ac\",\"\u4f60\"],\"combining\":\""
        + COMBINING_TEXT
        + "\",\"eu\":\""
        + EU_TEXT
        + "\",\"mixedScripts\":\""
        + MIXED_SCRIPT_TEXT
        + "\",\"supplementary\":\""
        + SUPPLEMENTARY_TEXT
        + "\",\"threeByte\":\""
        + THREE_BYTE_TEXT
        + "\",\"twoByte\":\""
        + TWO_BYTE_TEXT
        + "\",\"valueMap\":{\""
        + TWO_BYTE_TEXT
        + "\":\""
        + THREE_BYTE_TEXT
        + "\",\""
        + ZH_TEXT
        + "\":\""
        + EU_TEXT
        + "\",\"\u043a\u043b\u044e\u0447\":\"\uD83D\uDE00\","
        + "\"\u0645\u0631\u062d\u0628\u0627\":\"\u0928\u092e\u0938\u094d\u0924\u0947\"},"
        + "\"values\":[\""
        + TWO_BYTE_TEXT
        + "\",\""
        + THREE_BYTE_TEXT
        + "\",\""
        + SUPPLEMENTARY_TEXT
        + "\",\""
        + MIXED_SCRIPT_TEXT
        + "\",\""
        + COMBINING_TEXT
        + "\",\""
        + ZH_TEXT
        + "\",\""
        + EU_TEXT
        + "\"],\"zh\":\""
        + ZH_TEXT
        + "\"}";
  }

  protected static void assertUnicodeMatrix(UnicodeMatrix value) {
    assertEquals(value.boxedChar, Character.valueOf('€'));
    assertEquals(value.charThreeByte, '你');
    assertEquals(value.charTwoByte, 'Ā');
    assertEquals(value.chars, new char[] {'Ā', '߿', 'ࠀ', '€', '你'});
    assertEquals(value.combining, COMBINING_TEXT);
    assertEquals(value.eu, EU_TEXT);
    assertEquals(value.mixedScripts, MIXED_SCRIPT_TEXT);
    assertEquals(value.supplementary, SUPPLEMENTARY_TEXT);
    assertEquals(value.threeByte, THREE_BYTE_TEXT);
    assertEquals(value.twoByte, TWO_BYTE_TEXT);
    assertEquals(value.valueMap, unicodeMap());
    assertEquals(
        value.values,
        Arrays.asList(
            TWO_BYTE_TEXT,
            THREE_BYTE_TEXT,
            SUPPLEMENTARY_TEXT,
            MIXED_SCRIPT_TEXT,
            COMBINING_TEXT,
            ZH_TEXT,
            EU_TEXT));
    assertEquals(value.zh, ZH_TEXT);
  }

  protected static void assertFastContainers(FastContainers value) {
    assertEquals(value.booleans, Arrays.asList(Boolean.TRUE, Boolean.FALSE));
    assertEquals(value.flags, flags());
    assertEquals(value.intNames, intNames());
    assertEquals(value.ints, Arrays.asList(1, 2));
    assertEquals(value.names, Arrays.asList("alpha", ZH_TEXT));
    assertEquals(value.scores, scores());
  }

  protected static void assertGeneratedCollections(GeneratedCollectionFields value) {
    assertTrue(value.kinds instanceof EnumSet);
    assertEquals(value.kinds, EnumSet.of(Kind.FAST, Kind.SMALL));
    assertTrue(value.names instanceof LinkedHashSet);
    assertEquals(value.names, new LinkedHashSet<>(Arrays.asList("alpha", ZH_TEXT)));
    assertTrue(value.numbers instanceof ArrayDeque);
    assertEquals(new ArrayList<>(value.numbers), Arrays.asList(1, 2));
  }

  protected static void assertNumericBoundaries(NumericBoundaries value, String text) {
    assertEquals(value.intMax, Integer.MAX_VALUE);
    assertEquals(value.intMin, Integer.MIN_VALUE);
    assertEquals(value.longMax, Long.MAX_VALUE);
    assertEquals(value.longMin, Long.MIN_VALUE);
    assertEquals(value.small, -7);
    assertEquals(value.text, text);
  }

  protected static Class<?> compileRecordClass(String simpleName, String source) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertTrue(compiler != null);
    Path output =
        Paths.get(
            ForyJsonTestModels.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Path sourceDir = Files.createTempDirectory("fory-json-record");
    Files.createDirectories(sourceDir);
    Path sourceFile = sourceDir.resolve(simpleName + ".java");
    Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));
    int exit =
        compiler.run(
            null, null, null, "--release", "17", "-d", output.toString(), sourceFile.toString());
    assertEquals(exit, 0);
    return Class.forName(
        "org.apache.fory.json.records." + simpleName,
        true,
        ForyJsonTestModels.class.getClassLoader());
  }

  protected static void assertRecordValue(Object value, Class<?> childType) throws Exception {
    Class<?> type = value.getClass();
    assertEquals(type.getMethod("id").invoke(value), Integer.valueOf(7));
    assertEquals(type.getMethod("name").invoke(value), ZH_TEXT);
    assertEquals(type.getMethod("tags").invoke(value), Arrays.asList("a", "b"));
    Object child = type.getMethod("child").invoke(value);
    assertEquals(childType.getMethod("label").invoke(child), "kid");
  }

  protected static void assertTextRoundTrip(ForyJson json, String text) {
    String rootJson = "\"" + text + "\"";
    assertEquals(json.toJson(text), rootJson);
    assertEquals(new String(json.toJsonBytes(text), StandardCharsets.UTF_8), rootJson);
    assertEquals(json.fromJson(rootJson, String.class), text);
    assertEquals(json.fromJson(rootJson.getBytes(StandardCharsets.UTF_8), String.class), text);

    PublicFields fields = new PublicFields();
    fields.name = text;
    String objectJson = "{\"active\":true,\"id\":7,\"name\":\"" + text + "\"}";
    assertEquals(json.toJson(fields), objectJson);
    assertEquals(new String(json.toJsonBytes(fields), StandardCharsets.UTF_8), objectJson);
    assertEquals(json.fromJson(objectJson, PublicFields.class).name, text);
    assertEquals(
        json.fromJson(objectJson.getBytes(StandardCharsets.UTF_8), PublicFields.class).name, text);
  }

  protected static void assertGeneratedWhenSupported(ForyJson json, Class<?> type) {
    assertTrue(json.hasGeneratedWriter(type));
  }

  protected static String repeat(char ch, int length) {
    char[] chars = new char[length];
    Arrays.fill(chars, ch);
    return new String(chars);
  }

  protected static String nestedArray(int depth) {
    StringBuilder builder = new StringBuilder(depth * 2 + 1);
    for (int i = 0; i < depth; i++) {
      builder.append('[');
    }
    builder.append('0');
    for (int i = 0; i < depth; i++) {
      builder.append(']');
    }
    return builder.toString();
  }

  protected static int arrayCapacity(ArrayList<?> list) {
    try {
      Field field = ArrayList.class.getDeclaredField("elementData");
      Object[] array = (Object[]) FieldAccessor.createAccessor(field).getObject(list);
      return array.length;
    } catch (Throwable cause) {
      throw new SkipException("Cannot inspect ArrayList capacity on this runtime", cause);
    }
  }

  protected static int mapCapacity(HashMap<?, ?> map) {
    try {
      Field field = HashMap.class.getDeclaredField("table");
      Object[] table = (Object[]) FieldAccessor.createAccessor(field).getObject(map);
      return table == null ? 0 : table.length;
    } catch (Throwable cause) {
      throw new SkipException("Cannot inspect HashMap capacity on this runtime", cause);
    }
  }

  protected static Map<String, Object> values() {
    return JsonTestData.values();
  }
}
