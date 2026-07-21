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

import static org.apache.fory.json.JsonTestSupport.newLatin1Reader;
import static org.apache.fory.json.JsonTestSupport.newUtf8Reader;
import static org.apache.fory.json.JsonTestSupport.primaryTypeResolver;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
import org.testng.annotations.Test;

public class JsonGeneratedCodecTest extends ForyJsonTestModels {
  private static final String GENERATED_SUFFIX = "ForyJsonCodec";

  @Test(dataProvider = "enableCodegen")
  public void writeRecursiveGeneratedTypes(boolean codegen) {
    ForyJson json = newJson(codegen);
    RecursiveParent value = new RecursiveParent();
    assertEquals(json.toJson(value), "{\"child\":{\"name\":\"child\"},\"name\":\"parent\"}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8),
        "{\"child\":{\"name\":\"child\"},\"name\":\"parent\"}");
    assertGeneratedWhenSupported(json, RecursiveParent.class, codegen);
    assertGeneratedWhenSupported(json, RecursiveChild.class, codegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void writeGeneratedTokenChanges(boolean codegen) {
    ForyJson json = newJson(codegen);
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
    assertGeneratedWhenSupported(json, TokenValues.class, codegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void writeGeneratedTokenLanes(boolean codegen) {
    ForyJson json = newJson(codegen);
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
    assertGeneratedWhenSupported(json, TokenGroup.class, codegen);
    assertGeneratedWhenSupported(json, TokenValues.class, codegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void readGeneratedObjectCollection(boolean codegen) {
    ForyJson json = newJson(codegen);
    String input = "{\"values\":[{\"count\":1,\"name\":\"alpha\",\"tags\":[\"x\"],\"total\":2}]}";
    TokenGroup stringValue = json.fromJson(input, TokenGroup.class);
    TokenGroup utf8Value = json.fromJson(input.getBytes(StandardCharsets.UTF_8), TokenGroup.class);
    assertEquals(stringValue.values.size(), 1);
    assertEquals(stringValue.values.get(0).name, "alpha");
    assertEquals(stringValue.values.get(0).tags, Arrays.asList("x"));
    assertEquals(utf8Value.values.size(), 1);
    assertEquals(utf8Value.values.get(0).total, 2);
    assertGeneratedWhenSupported(json, TokenGroup.class, codegen);
    assertGeneratedWhenSupported(json, TokenValues.class, codegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void generatedObjectCollections(boolean codegen) {
    ForyJson json = newJson(codegen);
    String latin1 = objectCollectionsJson("value");
    ObjectCollections latin1Value = json.fromJson(latin1, ObjectCollections.class);
    assertObjectCollections(latin1Value, "value");

    String utf16 = objectCollectionsJson(ZH_TEXT);
    ObjectCollections utf16Value = json.fromJson(utf16, ObjectCollections.class);
    assertObjectCollections(utf16Value, ZH_TEXT);
    ObjectCollections utf8Value =
        json.fromJson(utf16.getBytes(StandardCharsets.UTF_8), ObjectCollections.class);
    assertObjectCollections(utf8Value, ZH_TEXT);

    ObjectCollections empty = json.fromJson("{\"values\":[],\"set\":[]}", ObjectCollections.class);
    assertTrue(empty.values.isEmpty());
    assertTrue(empty.set.isEmpty());

    assertEquals(json.toJson(utf16Value), utf16);
    assertEquals(new String(json.toJsonBytes(utf16Value), StandardCharsets.UTF_8), utf16);
    assertGeneratedWhenSupported(json, ObjectCollections.class, codegen);
    assertGeneratedWhenSupported(json, TokenValues.class, codegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void recursiveObjectCollection(boolean codegen) {
    ForyJson json = newJson(codegen);
    String input = "{\"children\":[{\"children\":[],\"id\":2},null],\"id\":1}";
    RecursiveCollection stringValue = json.fromJson(input, RecursiveCollection.class);
    RecursiveCollection utf8Value =
        json.fromJson(input.getBytes(StandardCharsets.UTF_8), RecursiveCollection.class);
    assertEquals(stringValue.id, 1);
    assertEquals(stringValue.children.get(0).id, 2);
    assertEquals(stringValue.children.get(1), null);
    assertEquals(utf8Value.children.get(0).children.size(), 0);
    assertEquals(json.toJson(stringValue), input);
    assertEquals(new String(json.toJsonBytes(stringValue), StandardCharsets.UTF_8), input);
    assertGeneratedWhenSupported(json, RecursiveCollection.class, codegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void readGeneratedCollectionFields(boolean codegen) {
    ForyJson json = newJson(codegen);
    String input =
        "{\"kinds\":[\"FAST\",\"SMALL\"],\"names\":[\"alpha\",\"你好，Fory\"]," + "\"numbers\":[1,2]}";
    assertGeneratedCollections(json.fromJson(input, GeneratedCollectionFields.class));
    assertGeneratedCollections(
        json.fromJson(input.getBytes(StandardCharsets.UTF_8), GeneratedCollectionFields.class));
    assertGeneratedWhenSupported(json, GeneratedCollectionFields.class, codegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void sameConfigUsesSameId(boolean codegen) throws Exception {
    ForyJson first = newJson(codegen);
    ForyJson second = newJson(codegen);
    ForyJson writeNullFields = newJsonBuilder(codegen).writeNullFields(true).build();
    ForyJson snakeCase =
        newJsonBuilder(codegen)
            .withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
            .build();
    first.toJsonBytes(new PublicFields());
    second.toJsonBytes(new PublicFields());
    writeNullFields.toJsonBytes(new PublicFields());
    snakeCase.toJsonBytes(new PublicFields());
    if (!codegen) {
      assertFalse(hasGeneratedCapability(first, PublicFields.class));
      assertFalse(hasGeneratedCapability(second, PublicFields.class));
      assertFalse(hasGeneratedCapability(writeNullFields, PublicFields.class));
      assertFalse(hasGeneratedCapability(snakeCase, PublicFields.class));
      return;
    }

    Class<?> firstCodecClass = generatedCodecClass(first, PublicFields.class);
    Class<?> secondCodecClass = generatedCodecClass(second, PublicFields.class);
    Class<?> writeNullCodecClass = generatedCodecClass(writeNullFields, PublicFields.class);
    Class<?> snakeCaseCodecClass = generatedCodecClass(snakeCase, PublicFields.class);
    assertEquals(firstCodecClass.getPackage().getName(), PublicFields.class.getPackage().getName());
    assertEquals(
        secondCodecClass.getPackage().getName(), PublicFields.class.getPackage().getName());
    assertGeneratedName(firstCodecClass, PublicFields.class, "Utf8Writer");
    assertGeneratedName(secondCodecClass, PublicFields.class, "Utf8Writer");
    assertGeneratedName(writeNullCodecClass, PublicFields.class, "Utf8Writer");
    assertGeneratedName(snakeCaseCodecClass, PublicFields.class, "Utf8Writer");
    assertEquals(generatedId(secondCodecClass), generatedId(firstCodecClass));
    assertNotEquals(generatedId(writeNullCodecClass), generatedId(firstCodecClass));
    assertNotEquals(generatedId(snakeCaseCodecClass), generatedId(firstCodecClass));
  }

  @Test
  public void readLongAsciiFieldToken() {
    String token = "\"favoriteFruit\":";
    long prefix = JsonAsciiToken.prefix(token);
    long suffix = JsonAsciiToken.suffixLong(token);
    long suffixMask = JsonAsciiToken.suffixMask(token.length());
    Utf8JsonReader utf8 = newUtf8Reader((token + "\"apple\"").getBytes(StandardCharsets.UTF_8));
    assertTrue(utf8.tryReadNextFieldNameToken8(prefix, suffix, suffixMask, token.length()));
    assertEquals(utf8.readNullableStringToken(), "apple");

    Latin1JsonReader latin1 = newLatin1Reader(latin1Bytes(token + "\"pear\""));
    assertTrue(latin1.tryReadNextFieldNameToken8(prefix, suffix, suffixMask, token.length()));
    assertEquals(latin1.readNullableStringToken(), "pear");

    String tailToken = "\"registered\":";
    long tailPrefix = JsonAsciiToken.prefix(tailToken);
    long tailSuffix = JsonAsciiToken.suffixLong(tailToken);
    long tailSuffixMask = JsonAsciiToken.suffixMask(tailToken.length());
    Utf8JsonReader tailUtf8 = newUtf8Reader((tailToken + "1").getBytes(StandardCharsets.UTF_8));
    assertTrue(
        tailUtf8.tryReadNextFieldNameToken8(
            tailPrefix, tailSuffix, tailSuffixMask, tailToken.length()));
    assertEquals(tailUtf8.readIntTokenValue(), 1);
    Latin1JsonReader tailLatin1 = newLatin1Reader(latin1Bytes(tailToken + "2"));
    assertTrue(
        tailLatin1.tryReadNextFieldNameToken8(
            tailPrefix, tailSuffix, tailSuffixMask, tailToken.length()));
    assertEquals(tailLatin1.readIntTokenValue(), 2);

    Utf8JsonReader mismatch =
        newUtf8Reader("\"favoriteSeed\":\"pit\"".getBytes(StandardCharsets.UTF_8));
    assertFalse(mismatch.tryReadNextFieldNameToken8(prefix, suffix, suffixMask, token.length()));
    assertEquals(mismatch.readFieldNameHash(), JsonFieldNameHash.hash("favoriteSeed"));
    mismatch.expectNextToken(':');
    assertEquals(mismatch.readNextNullableString(), "pit");
  }

  @Test(dataProvider = "enableCodegen")
  public void readGeneratedLongAsciiFields(boolean codegen) {
    ForyJson json = newJson(codegen);
    String input =
        "{\"registered\":\"today\",\"longitude\":12.5,\"favoriteFruit\":\"apple\","
            + "\"shortName\":\"core\"}";
    assertLongAsciiFields(json.fromJson(input, LongAsciiFields.class));
    assertLongAsciiFields(
        json.fromJson(input.getBytes(StandardCharsets.UTF_8), LongAsciiFields.class));
    assertGeneratedWhenSupported(json, LongAsciiFields.class, codegen);
  }

  @Test(dataProvider = "enableCodegen")
  public void readSplitGeneratedFields(boolean codegen) {
    ForyJson json = newJson(codegen);
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
    assertGeneratedWhenSupported(json, WideFields.class, codegen);
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

  public static final class ObjectCollections {
    public List<TokenValues> values;
    public Set<TokenValues> set;
  }

  public static final class RecursiveCollection {
    public List<RecursiveCollection> children;
    public int id;
  }

  private static String objectCollectionsJson(String name) {
    StringBuilder values = new StringBuilder();
    for (int i = 0; i < 10; i++) {
      if (i != 0) {
        values.append(',');
      }
      values.append(i == 4 ? "null" : tokenJson(i, name));
    }
    return "{\"values\":["
        + values
        + "],\"set\":["
        + tokenJson(10, name)
        + ","
        + tokenJson(11, name)
        + "]}";
  }

  private static String tokenJson(int id, String name) {
    return "{\"count\":"
        + id
        + ",\"name\":\""
        + name
        + id
        + "\",\"tags\":[\"x\"],\"total\":"
        + (id + 1)
        + "}";
  }

  private static void assertObjectCollections(ObjectCollections value, String name) {
    assertEquals(value.values.size(), 10);
    assertEquals(value.values.get(0).name, name + 0);
    assertEquals(value.values.get(4), null);
    assertEquals(value.values.get(9).total, 10L);
    assertTrue(value.set instanceof LinkedHashSet);
    assertEquals(value.set.size(), 2);
    assertEquals(value.set.iterator().next().name, name + 10);
  }

  private static Class<?> generatedCodecClass(ForyJson json, Class<?> type) throws Exception {
    JsonTypeResolver typeResolver = primaryTypeResolver(json);
    Object owner = typeResolver.getObjectCodec(type);
    Object codec = typeResolver.getTypeInfo(type, type).utf8Writer();
    assertTrue(codec != owner, codec.getClass().getName());
    return codec.getClass();
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
