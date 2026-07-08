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
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fory.json.codec.BaseObjectCodec;
import org.apache.fory.json.codec.GeneratedObjectCodec;
import org.apache.fory.json.data.GeneratedCollectionFields;
import org.apache.fory.json.data.PublicFields;
import org.apache.fory.json.data.RecursiveChild;
import org.apache.fory.json.data.RecursiveParent;
import org.apache.fory.json.data.TokenGroup;
import org.apache.fory.json.data.TokenValues;
import org.apache.fory.json.meta.JsonAsciiToken;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonGeneratedCodecTest extends ForyJsonTestModels {
  @Factory(dataProvider = "codegen")
  public JsonGeneratedCodecTest(boolean codegen) {
    super(codegen);
  }

  private static final String GENERATED_SUFFIX = "ForyJsonCodec";

  @Test
  public void writeRecursiveGeneratedTypes() {
    ForyJson json = newJson();
    RecursiveParent value = new RecursiveParent();
    assertEquals(json.toJson(value), "{\"child\":{\"name\":\"child\"},\"name\":\"parent\"}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8),
        "{\"child\":{\"name\":\"child\"},\"name\":\"parent\"}");
    assertGeneratedWhenSupported(json, RecursiveParent.class);
    assertGeneratedWhenSupported(json, RecursiveChild.class);
  }

  @Test
  public void writeGeneratedTokenChanges() {
    ForyJson json = newJson();
    TokenValues value = new TokenValues();
    String first = "{\"count\":1,\"name\":\"alpha\",\"tags\":[\"x\",\"y\"],\"total\":2}";
    assertEquals(json.toJson(value), first);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), first);
    assertEquals(json.toJson(value), first);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), first);
    value.count = 7;
    value.name = "beta";
    value.tags = new ArrayList<>(Arrays.asList("z", "x"));
    value.total = 9;
    String second = "{\"count\":7,\"name\":\"beta\",\"tags\":[\"z\",\"x\"],\"total\":9}";
    assertEquals(json.toJson(value), second);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), second);
    assertGeneratedWhenSupported(json, TokenValues.class);
  }

  @Test
  public void writeGeneratedTokenLanes() {
    ForyJson json = newJson();
    TokenGroup group = new TokenGroup();
    group.values =
        Arrays.asList(
            tokenValue(1, "alpha", Arrays.asList("x", "y"), 2),
            tokenValue(3, "beta", Arrays.asList("z", "x"), 4),
            tokenValue(5, "gamma", Arrays.asList("y", "z"), 6));
    String first =
        "{\"values\":[{\"count\":1,\"name\":\"alpha\",\"tags\":[\"x\",\"y\"],\"total\":2},"
            + "{\"count\":3,\"name\":\"beta\",\"tags\":[\"z\",\"x\"],\"total\":4},"
            + "{\"count\":5,\"name\":\"gamma\",\"tags\":[\"y\",\"z\"],\"total\":6}]}";
    assertEquals(json.toJson(group), first);
    assertEquals(new String(json.toJsonBytes(group), StandardCharsets.UTF_8), first);
    assertEquals(json.toJson(group), first);
    assertEquals(new String(json.toJsonBytes(group), StandardCharsets.UTF_8), first);
    TokenValues middle = group.values.get(1);
    middle.count = 7;
    middle.name = "delta";
    middle.tags = Arrays.asList("q", "x");
    middle.total = 8;
    String second =
        "{\"values\":[{\"count\":1,\"name\":\"alpha\",\"tags\":[\"x\",\"y\"],\"total\":2},"
            + "{\"count\":7,\"name\":\"delta\",\"tags\":[\"q\",\"x\"],\"total\":8},"
            + "{\"count\":5,\"name\":\"gamma\",\"tags\":[\"y\",\"z\"],\"total\":6}]}";
    assertEquals(json.toJson(group), second);
    assertEquals(new String(json.toJsonBytes(group), StandardCharsets.UTF_8), second);
    assertGeneratedWhenSupported(json, TokenGroup.class);
    assertGeneratedWhenSupported(json, TokenValues.class);
  }

  @Test
  public void readGeneratedObjectCollection() {
    ForyJson json = newJson();
    String input = "{\"values\":[{\"count\":1,\"name\":\"alpha\",\"tags\":[\"x\"],\"total\":2}]}";
    TokenGroup stringValue = json.fromJson(input, TokenGroup.class);
    TokenGroup utf8Value = json.fromJson(input.getBytes(StandardCharsets.UTF_8), TokenGroup.class);
    assertEquals(stringValue.values.size(), 1);
    assertEquals(stringValue.values.get(0).name, "alpha");
    assertEquals(stringValue.values.get(0).tags, Arrays.asList("x"));
    assertEquals(utf8Value.values.size(), 1);
    assertEquals(utf8Value.values.get(0).total, 2);
    assertGeneratedWhenSupported(json, TokenGroup.class);
    assertGeneratedWhenSupported(json, TokenValues.class);
  }

  @Test
  public void readGeneratedCollectionFields() {
    ForyJson json = newJson();
    String input =
        "{\"kinds\":[\"FAST\",\"SMALL\"],\"names\":[\"alpha\",\"你好，Fory\"]," + "\"numbers\":[1,2]}";
    assertGeneratedCollections(json.fromJson(input, GeneratedCollectionFields.class));
    assertGeneratedCollections(
        json.fromJson(input.getBytes(StandardCharsets.UTF_8), GeneratedCollectionFields.class));
    assertGeneratedWhenSupported(json, GeneratedCollectionFields.class);
  }

  @Test
  public void sameConfigUsesSameId() throws Exception {
    ForyJson first = newJson();
    ForyJson second = newJson();
    ForyJson writeNullFields = newJsonBuilder().writeNullFields(true).build();
    first.toJsonBytes(new PublicFields());
    second.toJsonBytes(new PublicFields());
    writeNullFields.toJsonBytes(new PublicFields());
    if (!codegenEnabled()) {
      assertFalse(first.hasGeneratedWriter(PublicFields.class));
      assertFalse(second.hasGeneratedWriter(PublicFields.class));
      assertFalse(writeNullFields.hasGeneratedWriter(PublicFields.class));
      return;
    }

    Class<?> firstWriterClass = generatedUtf8WriterClass(first, PublicFields.class);
    Class<?> secondWriterClass = generatedUtf8WriterClass(second, PublicFields.class);
    Class<?> writeNullWriterClass = generatedUtf8WriterClass(writeNullFields, PublicFields.class);
    ForyJson asyncDefault = ForyJson.builder().withCodegen(true).build();
    asyncDefault.toJsonBytes(new PublicFields());
    awaitGenerated(asyncDefault, PublicFields.class);
    Class<?> asyncWriterClass = generatedUtf8WriterClass(asyncDefault, PublicFields.class);
    assertEquals(
        firstWriterClass.getPackage().getName(), PublicFields.class.getPackage().getName());
    assertEquals(
        secondWriterClass.getPackage().getName(), PublicFields.class.getPackage().getName());
    assertEquals(
        asyncWriterClass.getPackage().getName(), PublicFields.class.getPackage().getName());
    assertGeneratedName(firstWriterClass, PublicFields.class, "Utf8");
    assertGeneratedName(secondWriterClass, PublicFields.class, "Utf8");
    assertGeneratedName(writeNullWriterClass, PublicFields.class, "Utf8");
    assertGeneratedName(asyncWriterClass, PublicFields.class, "Utf8");
    assertEquals(generatedId(secondWriterClass), generatedId(firstWriterClass));
    assertEquals(generatedId(asyncWriterClass), generatedId(firstWriterClass));
    assertNotEquals(generatedId(writeNullWriterClass), generatedId(firstWriterClass));
  }

  @Test
  public void readLongAsciiFieldToken() {
    String token = "\"favoriteFruit\":";
    long prefix = JsonAsciiToken.prefix(token);
    long suffix = JsonAsciiToken.suffixLong(token);
    long suffixMask = JsonAsciiToken.suffixMask(token.length());
    Utf8JsonReader utf8 =
        new Utf8JsonReader((token + "\"apple\"").getBytes(StandardCharsets.UTF_8));
    assertTrue(utf8.tryReadNextFieldNameToken8(prefix, suffix, suffixMask, token.length()));
    assertEquals(utf8.readNullableStringToken(), "apple");

    Latin1JsonReader latin1 = new Latin1JsonReader(latin1Bytes(token + "\"pear\""));
    assertTrue(latin1.tryReadNextFieldNameToken8(prefix, suffix, suffixMask, token.length()));
    assertEquals(latin1.readNullableStringToken(), "pear");

    String tailToken = "\"registered\":";
    long tailPrefix = JsonAsciiToken.prefix(tailToken);
    long tailSuffix = JsonAsciiToken.suffixLong(tailToken);
    long tailSuffixMask = JsonAsciiToken.suffixMask(tailToken.length());
    Utf8JsonReader tailUtf8 =
        new Utf8JsonReader((tailToken + "1").getBytes(StandardCharsets.UTF_8));
    assertTrue(
        tailUtf8.tryReadNextFieldNameToken8(
            tailPrefix, tailSuffix, tailSuffixMask, tailToken.length()));
    assertEquals(tailUtf8.readIntTokenValue(), 1);
    Latin1JsonReader tailLatin1 = new Latin1JsonReader(latin1Bytes(tailToken + "2"));
    assertTrue(
        tailLatin1.tryReadNextFieldNameToken8(
            tailPrefix, tailSuffix, tailSuffixMask, tailToken.length()));
    assertEquals(tailLatin1.readIntTokenValue(), 2);

    Utf8JsonReader mismatch =
        new Utf8JsonReader("\"favoriteSeed\":\"pit\"".getBytes(StandardCharsets.UTF_8));
    assertFalse(mismatch.tryReadNextFieldNameToken8(prefix, suffix, suffixMask, token.length()));
    assertEquals(mismatch.readFieldNameHash(), JsonFieldNameHash.hash("favoriteSeed"));
    mismatch.expectNextToken(':');
    assertEquals(mismatch.readNextNullableString(), "pit");
  }

  @Test
  public void readGeneratedLongAsciiFields() {
    ForyJson json = newJson();
    String input =
        "{\"registered\":\"today\",\"longitude\":12.5,\"favoriteFruit\":\"apple\","
            + "\"shortName\":\"core\"}";
    assertLongAsciiFields(json.fromJson(input, LongAsciiFields.class));
    assertLongAsciiFields(
        json.fromJson(input.getBytes(StandardCharsets.UTF_8), LongAsciiFields.class));
    assertGeneratedWhenSupported(json, LongAsciiFields.class);
  }

  @Test
  public void readSplitGeneratedFields() {
    ForyJson json = newJson();
    String ordered =
        "{\"f0\":0,\"f1\":\"one\",\"f2\":2,\"f3\":\"three\",\"f4\":4,\"f5\":\"five\","
            + "\"f6\":6,\"f7\":\"seven\",\"f8\":8,\"f9\":\"nine\",\"f10\":10,"
            + "\"f11\":\"eleven\",\"f12\":12,\"f13\":\"thirteen\"}";
    assertWideFields(json.fromJson(ordered, WideFields.class));
    assertWideFields(json.fromJson(ordered.getBytes(StandardCharsets.UTF_8), WideFields.class));

    String boundaryFallback =
        "{\"f0\":0,\"f2\":2,\"f1\":\"one\",\"f3\":\"three\",\"f4\":4,\"f5\":\"five\","
            + "\"f6\":6,\"f7\":\"seven\",\"f8\":8,\"f9\":\"nine\",\"f10\":10,"
            + "\"f11\":\"eleven\",\"f12\":12,\"f13\":\"thirteen\"}";
    assertWideFields(json.fromJson(boundaryFallback, WideFields.class));
    assertWideFields(
        json.fromJson(boundaryFallback.getBytes(StandardCharsets.UTF_8), WideFields.class));
    assertGeneratedWhenSupported(json, WideFields.class);
  }

  private static void assertWideFields(WideFields value) {
    assertEquals(value.f0, 0);
    assertEquals(value.f1, "one");
    assertEquals(value.f2, 2);
    assertEquals(value.f3, "three");
    assertEquals(value.f4, 4);
    assertEquals(value.f5, "five");
    assertEquals(value.f6, 6);
    assertEquals(value.f7, "seven");
    assertEquals(value.f8, 8);
    assertEquals(value.f9, "nine");
    assertEquals(value.f10, 10);
    assertEquals(value.f11, "eleven");
    assertEquals(value.f12, 12);
    assertEquals(value.f13, "thirteen");
  }

  private static void assertLongAsciiFields(LongAsciiFields value) {
    assertEquals(value.registered, "today");
    assertEquals(value.longitude, 12.5d);
    assertEquals(value.favoriteFruit, "apple");
    assertEquals(value.shortName, "core");
  }

  private static byte[] latin1Bytes(String value) {
    return value.getBytes(StandardCharsets.ISO_8859_1);
  }

  public static class LongAsciiFields {
    public String registered;
    public double longitude;
    public String favoriteFruit;
    public String shortName;
  }

  public static class WideFields {
    public int f0;
    public String f1;
    public int f2;
    public String f3;
    public int f4;
    public String f5;
    public int f6;
    public String f7;
    public int f8;
    public String f9;
    public int f10;
    public String f11;
    public int f12;
    public String f13;
  }

  private static Class<?> generatedUtf8WriterClass(ForyJson json, Class<?> type) throws Exception {
    Field primarySlotField = ForyJson.class.getDeclaredField("primarySlot");
    primarySlotField.setAccessible(true);
    AtomicReference<?> primarySlot = (AtomicReference<?>) primarySlotField.get(json);
    Object pooledState = primarySlot.get();
    Field stateField = pooledState.getClass().getDeclaredField("state");
    stateField.setAccessible(true);
    Object state = stateField.get(pooledState);
    Field typeResolverField = state.getClass().getDeclaredField("typeResolver");
    typeResolverField.setAccessible(true);
    JsonTypeResolver typeResolver = (JsonTypeResolver) typeResolverField.get(state);
    BaseObjectCodec codec = typeResolver.getObjectCodec(type);
    assertTrue(codec instanceof GeneratedObjectCodec);
    Field utf8WriterField = GeneratedObjectCodec.class.getDeclaredField("utf8Writer");
    utf8WriterField.setAccessible(true);
    return utf8WriterField.get(codec).getClass();
  }

  private static void awaitGenerated(ForyJson json, Class<?> type) throws InterruptedException {
    for (int i = 0; i < 200; i++) {
      if (json.hasGeneratedWriter(type)) {
        return;
      }
      Thread.sleep(10);
    }
    fail("Timed out waiting for generated JSON codec for " + type);
  }

  private static void assertGeneratedName(
      Class<?> generatedClass, Class<?> valueType, String role) {
    String simpleName = generatedClass.getSimpleName();
    assertTrue(simpleName.startsWith(valueType.getSimpleName()), generatedClass.getName());
    assertTrue(simpleName.contains(role + GENERATED_SUFFIX), generatedClass.getName());
    assertFalse(simpleName.contains(GENERATED_SUFFIX + "_"), generatedClass.getName());
    assertTrue(generatedId(generatedClass) >= 0, generatedClass.getName());
  }

  private static int generatedId(Class<?> generatedClass) {
    String simpleName = generatedClass.getSimpleName();
    int suffixStart = simpleName.lastIndexOf(GENERATED_SUFFIX);
    assertTrue(suffixStart >= 0, generatedClass.getName());
    String id = simpleName.substring(suffixStart + GENERATED_SUFFIX.length());
    return id.isEmpty() ? 0 : Integer.parseInt(id);
  }
}
