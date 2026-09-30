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

import static org.apache.fory.json.JsonTestSupport.generatedUtf8WriterClass;
import static org.apache.fory.json.JsonTestSupport.newLatin1Reader;
import static org.apache.fory.json.JsonTestSupport.newUtf8Reader;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.codegen.CompileUnit;
import org.apache.fory.codegen.JaninoUtils;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonProperty.Include;
import org.apache.fory.json.codec.AbstractJsonValueCodec;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.codegen.JsonCodegen;
import org.apache.fory.json.data.GeneratedCollectionFields;
import org.apache.fory.json.data.Kind;
import org.apache.fory.json.data.PublicFields;
import org.apache.fory.json.data.RecursiveChild;
import org.apache.fory.json.data.RecursiveParent;
import org.apache.fory.json.data.TokenGroup;
import org.apache.fory.json.data.TokenValues;
import org.apache.fory.json.meta.JsonAsciiToken;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.util.ClassLoaderUtils.ByteArrayClassLoader;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class JsonGeneratedCodecTest extends ForyJsonTestModels {

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
    String pretty = json.toPrettyJson(utf16Value);
    assertEquals(new String(json.toPrettyJsonBytes(utf16Value), StandardCharsets.UTF_8), pretty);
    assertObjectCollections(json.fromJson(pretty, ObjectCollections.class), ZH_TEXT);
    utf16Value.values = new LinkedList<>(utf16Value.values);
    assertEquals(json.toJson(utf16Value), utf16);
    assertEquals(json.toPrettyJson(utf16Value), pretty);
    if (codegen) {
      JsonTypeInfo collection =
          JsonTestSupport.currentTypeResolver(json)
              .getTypeInfo(new TypeRef<List<TokenValues>>() {});
      assertTrue(collection.stringWriter().getClass().getName().contains("StringCollectionWriter"));
      assertTrue(collection.utf8Writer().getClass().getName().contains("Utf8CollectionWriter"));
    }
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
  public void sameConfigUsesSameClass(boolean codegen) {
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

    Class<?> firstCodecClass = generatedUtf8WriterClass(first, PublicFields.class);
    Class<?> secondCodecClass = generatedUtf8WriterClass(second, PublicFields.class);
    Class<?> writeNullCodecClass = generatedUtf8WriterClass(writeNullFields, PublicFields.class);
    Class<?> snakeCaseCodecClass = generatedUtf8WriterClass(snakeCase, PublicFields.class);
    assertEquals(firstCodecClass.getPackage().getName(), PublicFields.class.getPackage().getName());
    assertEquals(
        secondCodecClass.getPackage().getName(), PublicFields.class.getPackage().getName());
    assertSame(secondCodecClass, firstCodecClass);
    assertNotSame(writeNullCodecClass, firstCodecClass);
    assertNotSame(snakeCaseCodecClass, firstCodecClass);
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

  @Test
  public void readFieldNamePrefix() {
    String input = " \n\t\"alpha\":1";
    int expected = (int) JsonAsciiToken.prefix("\"alpha\":");
    Latin1JsonReader latin1 = newLatin1Reader(latin1Bytes(input));
    assertEquals(latin1.readFieldNamePrefix(), expected);
    assertEquals(latin1.position(), 3);
    assertTrue(
        latin1.tryReadNextFieldNameToken0(
            JsonAsciiToken.prefix("\"alpha\":"), -1L, "\"alpha\":".length()));
    assertEquals(latin1.readIntTokenValue(), 1);

    Utf8JsonReader utf8 = newUtf8Reader(input.getBytes(StandardCharsets.UTF_8));
    assertEquals(utf8.readFieldNamePrefix(), expected);
    assertEquals(utf8.position(), 3);
    assertTrue(
        utf8.tryReadNextFieldNameToken0(
            JsonAsciiToken.prefix("\"alpha\":"), -1L, "\"alpha\":".length()));
    assertEquals(utf8.readIntTokenValue(), 1);

    String commaField = ", \n\t\"alpha\":1";
    Utf8JsonReader adjacentComma = newUtf8Reader(commaField.getBytes(StandardCharsets.UTF_8));
    assertTrue(adjacentComma.tryConsumeNextOrderedComma());
    assertTrue(
        adjacentComma.tryReadNextFieldNameToken0(
            JsonAsciiToken.prefix("\"alpha\":"), -1L, "\"alpha\":".length()));
    assertEquals(adjacentComma.readIntTokenValue(), 1);

    Utf8JsonReader spacedComma =
        newUtf8Reader((" \n" + commaField).getBytes(StandardCharsets.UTF_8));
    assertTrue(spacedComma.consumeNextOrderedObjectEndOrSlow());
    assertTrue(
        spacedComma.tryReadNextFieldNameToken0(
            JsonAsciiToken.prefix("\"alpha\":"), -1L, "\"alpha\":".length()));
    assertEquals(spacedComma.readIntTokenValue(), 1);

    Latin1JsonReader truncatedLatin1 = newLatin1Reader(latin1Bytes(" \"a"));
    assertEquals(truncatedLatin1.readFieldNamePrefix(), 0);
    assertEquals(truncatedLatin1.position(), 1);
    Utf8JsonReader truncatedUtf8 = newUtf8Reader(" \"a".getBytes(StandardCharsets.UTF_8));
    assertEquals(truncatedUtf8.readFieldNamePrefix(), 0);
    assertEquals(truncatedUtf8.position(), 1);
  }

  @Test
  public void readGeneratedFieldPrefixCollision() {
    ForyJson json = newJson(true);
    String input = "{\"unknown\":0, \"alpine\":2, \"\\u0061lpha\":1, \"alpha\" :4, \"altar\":3}";
    PrefixFields latin1 = json.fromJson(input, PrefixFields.class);
    assertPrefixFields(latin1);
    PrefixFields utf8 = json.fromJson(input.getBytes(StandardCharsets.UTF_8), PrefixFields.class);
    assertPrefixFields(utf8);
    assertGeneratedWhenSupported(json, PrefixFields.class, true);
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

  @Test
  public void readOrderedCreator() {
    ForyJson json = newJson(true);
    JsonCreatorTest.User latin1 =
        json.fromJson("{\"id\":7, \n\t\"name\":\"alice\"}", JsonCreatorTest.User.class);
    JsonCreatorTest.User latin1Fallback =
        json.fromJson("{\"name\":\"bob\",\"id\":8}", JsonCreatorTest.User.class);
    JsonCreatorTest.User utf16 =
        json.fromJson("{\"id\":9, \n\t\"name\":\"你好\"}", JsonCreatorTest.User.class);
    JsonCreatorTest.User utf16Fallback =
        json.fromJson("{\"name\":\"你好\",\"id\":10}", JsonCreatorTest.User.class);
    JsonCreatorTest.User utf8 =
        json.fromJson(
            "{\"id\":11, \n\t\"name\":\"carol\"}".getBytes(StandardCharsets.UTF_8),
            JsonCreatorTest.User.class);
    JsonCreatorTest.User utf8Fallback =
        json.fromJson(
            "{\"name\":\"dave\",\"id\":12}".getBytes(StandardCharsets.UTF_8),
            JsonCreatorTest.User.class);
    assertEquals(latin1.id, 7L);
    assertEquals(latin1Fallback.id, 8L);
    assertEquals(utf16.id, 9L);
    assertEquals(utf16Fallback.id, 10L);
    assertEquals(utf8.id, 11L);
    assertEquals(utf8Fallback.id, 12L);
    assertEquals(latin1.name, "alice");
    assertEquals(latin1Fallback.name, "bob");
    assertEquals(utf16.name, "你好");
    assertEquals(utf16Fallback.name, "你好");
    assertEquals(utf8.name, "carol");
    assertEquals(utf8Fallback.name, "dave");
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

  @Test
  public void writeSplitGeneratedFields() throws Exception {
    ForyJson json = newJsonBuilder(true).writeNullFields(true).build();
    WideWriterFields value = new WideWriterFields();
    value.field01 = null;
    StringBuilder expected = new StringBuilder("{\"field00\":1");
    for (int i = 1; i < 24; i++) {
      expected.append(",\"field");
      if (i < 10) {
        expected.append('0');
      }
      expected.append(i).append("\":");
      if (i == 1) {
        expected.append("null");
        continue;
      }
      expected.append("\"v");
      if (i < 10) {
        expected.append('0');
      }
      expected.append(i).append('"');
    }
    expected.append('}');
    assertEquals(json.toJson(value), expected.toString());
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected.toString());

    JsonTypeInfo typeInfo =
        JsonTestSupport.currentTypeResolver(json)
            .getTypeInfo(WideWriterFields.class, WideWriterFields.class);
    assertTrue(
        Arrays.stream(typeInfo.stringWriter().getClass().getDeclaredMethods())
            .anyMatch(method -> method.getName().startsWith("writeStringMembers")));
    Class<?> generated = generatedUtf8WriterClass(json, WideWriterFields.class);
    int groups = 0;
    for (Method method : generated.getDeclaredMethods()) {
      if (method.getName().startsWith("writeUtf8Group")) {
        groups++;
      }
    }
    assertTrue(groups > 0, generated.getName());
    for (Field field : generated.getDeclaredFields()) {
      assertFalse(Utf8WriterCodec.class.isAssignableFrom(field.getType()), field.toString());
    }
  }

  @Test
  public void writeGeneratedFieldsPastPrefixIndexCollision() throws Exception {
    // Prefix fields are named from the property index, so property 160 meets property 0's
    // UTF-16 prefix names unless the two are kept apart.
    int count = 161;
    StringBuilder source =
        new StringBuilder("package org.apache.fory.json.dynamic; public class ManyStringFields {");
    for (int i = 0; i < count; i++) {
      source.append("public String f").append(i).append(";");
    }
    source.append('}');
    ClassLoader parent = getClass().getClassLoader();
    Map<String, byte[]> classes =
        JaninoUtils.toBytecode(
            parent,
            "",
            new CompileUnit("org.apache.fory.json.dynamic", "ManyStringFields", source.toString()));
    ClassLoader loader = new ByteArrayClassLoader(classes, parent);
    Class<?> type = Class.forName("org.apache.fory.json.dynamic.ManyStringFields", true, loader);
    Object value = type.getConstructor().newInstance();
    StringBuilder expected = new StringBuilder("{");
    for (int i = 0; i < count; i++) {
      // A character above 0xFF makes the string writer emit the UTF-16 prefixes.
      String text = i == 0 ? "v\u0100" : "v" + i;
      type.getField("f" + i).set(value, text);
      expected.append(i == 0 ? "\"" : ",\"").append('f').append(i).append("\":\"");
      expected.append(text).append('"');
    }
    expected.append('}');

    ForyJson json = newJsonBuilder(true).withClassLoader(loader).build();
    assertEquals(json.toJson(value), expected.toString());
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected.toString());
    Object read = json.fromJson(expected.toString(), type);
    for (int i = 0; i < count; i++) {
      assertEquals(type.getField("f" + i).get(read), type.getField("f" + i).get(value));
    }
    assertGeneratedWhenSupported(json, type, true);
  }

  @DataProvider
  public Object[][] bytecodeSchemas() {
    return new Object[][] {{WideWriterFields.class}, {WideMapFields.class}};
  }

  @Test(dataProvider = "bytecodeSchemas")
  public void groupBytecode(Class<?> type) throws Exception {
    ForyJson json = newJsonBuilder(false).writeNullFields(true).build();
    JsonTypeResolver resolver = JsonTestSupport.currentTypeResolver(json);
    ObjectCodec<?> codec = resolver.getObjectCodec(type);
    ClassLoader loader = getClass().getClassLoader();
    Constructor<JsonCodegen> constructor =
        JsonCodegen.class.getDeclaredConstructor(
            CodeGenerator.class, ClassLoader.class, boolean.class, Class.class, String.class);
    constructor.setAccessible(true);
    for (String role :
        new String[] {"StringWriter", "Utf8Writer", "Latin1Reader", "Utf16Reader", "Utf8Reader"}) {
      List<JaninoUtils.CodeStats> compiled = new ArrayList<>();
      CodeGenerator compiler =
          new CodeGenerator(loader) {
            @Override
            public ClassLoader compileDirect(
                CompileUnit unit, JaninoUtils.DirectInvocation... invocations) {
              Map<String, byte[]> classes = JaninoUtils.toBytecode(loader, "", unit);
              byte[] bytes = classes.get(unit.getQualifiedClassName().replace('.', '/') + ".class");
              compiled.add(JaninoUtils.getClassStats(bytes));
              return new ByteArrayClassLoader(classes, loader);
            }
          };
      JsonCodegen codegen = constructor.newInstance(compiler, loader, false, null, "GroupSizes");
      Method build =
          JsonCodegen.class.getDeclaredMethod(
              "build" + role, ObjectCodec.class, JsonTypeResolver.class);
      build.setAccessible(true);
      build.invoke(codegen, codec, resolver);
      int groups = 0;
      for (Map.Entry<String, Integer> entry : compiled.get(0).methodsSize.entrySet()) {
        String name = entry.getKey();
        if (name.contains("Group") || name.startsWith("writeStringMembers")) {
          assertTrue(entry.getValue() > 325, role + " " + entry);
          groups++;
        }
      }
      assertTrue(groups > 0, role);
      if (!role.equals("StringWriter")) {
        String root = role.endsWith("Reader") ? "read" + role.replace("Reader", "") : "writeUtf8";
        assertTrue(compiled.get(0).methodsSize.get(root) > 325, role);
      }
    }
  }

  @Test
  public void nonEmptyFinalValues() throws Exception {
    ForyJson json = newJsonBuilder(true).defaultPropertyInclusion(Include.NON_EMPTY).build();
    for (String role : new String[] {"StringWriter", "Utf8Writer"}) {
      String finalValues = writerSource(json, FinalNonEmptyValues.class, role);
      for (String field :
          new String[] {".count;", ".total;", ".kind;", ".operation;", ".nested;"}) {
        assertTrue(finalValues.contains(field), field + " in " + finalValues);
      }
      assertFalse(finalValues.contains("JsonFieldInfo.isEmpty("), finalValues);
      // The wide bean is split into member helpers, so this covers the grouped writer shape.
      String wideValues = writerSource(json, WideNonEmptyValues.class, role);
      assertTrue(wideValues.contains("Members("), wideValues);
      assertFalse(wideValues.contains("JsonFieldInfo.isEmpty("), wideValues);
      for (Class<?> type :
          new Class<?>[] {
            CustomNonEmptyValue.class, DecimalNonEmptyValue.class, DynamicNonEmptyValue.class
          }) {
        String source = writerSource(json, type, role);
        assertTrue(source.contains("JsonFieldInfo.isEmpty("), source);
      }
    }

    FinalNonEmptyValues value = new FinalNonEmptyValues();
    String expected =
        "{\"count\":0,\"kind\":\"SMALL\",\"operation\":\"ADD\",\"nested\":{\"empty\":false}}";
    assertJson(json, value, expected);
    assertJson(json, new CustomNonEmptyValue(), "{}");
    assertJson(json, new DecimalNonEmptyValue(), "{\"price\":1}");
    assertJson(json, new DynamicNonEmptyValue(), "{}");
    assertGeneratedWhenSupported(json, FinalNonEmptyValues.class, true);
    assertGeneratedWhenSupported(json, CustomNonEmptyValue.class, true);
    assertGeneratedWhenSupported(json, DecimalNonEmptyValue.class, true);
    assertGeneratedWhenSupported(json, DynamicNonEmptyValue.class, true);

    ForyJson interpreted =
        newJsonBuilder(false).defaultPropertyInclusion(Include.NON_EMPTY).build();
    for (Object fixture :
        new Object[] {
          value,
          new CustomNonEmptyValue(),
          new DecimalNonEmptyValue(),
          new DynamicNonEmptyValue(),
          new WideNonEmptyValues()
        }) {
      assertJson(json, fixture, interpreted.toJson(fixture));
      assertGeneratedWhenSupported(json, fixture.getClass(), true);
    }
  }

  @Test
  public void builtInEmptyTypes() {
    // Generated writers drop the emptiness check when mayBeEmpty is false, so every type that
    // JsonFieldInfo.isEmpty tests itself must keep mayBeEmpty true.
    JsonTypeResolver resolver = JsonTestSupport.currentTypeResolver(newJson(false));
    for (JsonFieldInfo field : resolver.getObjectCodec(BuiltInEmptyValues.class).writeFields()) {
      assertTrue(field.mayBeEmpty(), field.name());
    }
    for (JsonFieldInfo field : resolver.getObjectCodec(FinalNonEmptyValues.class).writeFields()) {
      assertFalse(field.mayBeEmpty(), field.name());
    }
  }

  @Test
  public void nonEmptyRegisteredCodec() throws Exception {
    // Generated writer classes are shared across instances, so an instance whose registered codec
    // overrides isEmpty must keep the runtime check after an instance whose codec keeps the default
    // has generated the writer. Only this order can observe a shared class.
    assertRegisteredEmptiness(new RegisteredValue(), Registration.CODEC);
    assertRegisteredEmptiness(new FactoryValue(), Registration.FACTORY);
    assertRegisteredEmptiness(new MixinValue(), Registration.MIXIN);
    assertRegisteredEmptiness(new InheritedValue(), Registration.INHERITED);
    assertRegisteredEmptiness(new BaseClassValue(), Registration.BASE_CLASS);
  }

  private enum Registration {
    CODEC,
    FACTORY,
    MIXIN,
    INHERITED,
    BASE_CLASS
  }

  private void assertRegisteredEmptiness(Object value, Registration registration) throws Exception {
    ForyJsonBuilder plainBuilder = newJsonBuilder(true).defaultPropertyInclusion(Include.NON_EMPTY);
    ForyJsonBuilder registeredBuilder =
        newJsonBuilder(true).defaultPropertyInclusion(Include.NON_EMPTY);
    if (registration == Registration.MIXIN) {
      plainBuilder.registerMixin(PlainEmptyObjectMixin.class);
      registeredBuilder.registerMixin(EmptyObjectMixin.class);
    } else if (registration == Registration.FACTORY) {
      plainBuilder.registerCodec(
          JsonInclusionTest.EmptyObject.class,
          (JsonCodecFactory) (type, resolver, runtimeType) -> new PlainEmptyObjectCodec());
      registeredBuilder.registerCodec(
          JsonInclusionTest.EmptyObject.class,
          (JsonCodecFactory)
              (type, resolver, runtimeType) -> new JsonInclusionTest.EmptyObjectCodec());
    } else {
      plainBuilder.registerCodec(JsonInclusionTest.EmptyObject.class, new PlainEmptyObjectCodec());
      registeredBuilder.registerCodec(
          JsonInclusionTest.EmptyObject.class,
          registration == Registration.INHERITED
              ? new InheritedEmptinessCodec()
              : registration == Registration.BASE_CLASS
                  ? new BaseClassEmptinessCodec()
                  : new JsonInclusionTest.EmptyObjectCodec());
    }
    ForyJson plain = plainBuilder.build();
    ForyJson registered = registeredBuilder.build();
    assertJson(plain, value, "{\"value\":\"plain\"}");
    assertJson(registered, value, "{}");
    ForyJson interpreted =
        newJsonBuilder(false)
            .defaultPropertyInclusion(Include.NON_EMPTY)
            .registerCodec(
                JsonInclusionTest.EmptyObject.class, new JsonInclusionTest.EmptyObjectCodec())
            .build();
    assertEquals(interpreted.toJson(value), "{}");
    // Both instances must run generated writers, or the outputs above would not involve the
    // decision.
    assertNotSame(
        JsonTestSupport.generatedUtf8WriterClass(plain, value.getClass()),
        JsonTestSupport.generatedUtf8WriterClass(registered, value.getClass()));
    assertFalse(
        writerSource(plain, value.getClass(), "Utf8Writer").contains("JsonFieldInfo.isEmpty("));
    assertTrue(
        writerSource(registered, value.getClass(), "Utf8Writer")
            .contains("JsonFieldInfo.isEmpty("));
  }

  private static void assertJson(ForyJson json, Object value, String expected) {
    assertEquals(json.toJson(value), expected);
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
  }

  private String writerSource(ForyJson json, Class<?> type, String role) throws Exception {
    JsonTypeResolver resolver = JsonTestSupport.currentTypeResolver(json);
    ClassLoader loader = getClass().getClassLoader();
    List<String> sources = new ArrayList<>();
    CodeGenerator compiler =
        new CodeGenerator(loader) {
          @Override
          public ClassLoader compileDirect(
              CompileUnit unit, JaninoUtils.DirectInvocation... invocations) {
            sources.add(unit.getCode());
            return new ByteArrayClassLoader(JaninoUtils.toBytecode(loader, "", unit), loader);
          }
        };
    Constructor<JsonCodegen> constructor =
        JsonCodegen.class.getDeclaredConstructor(
            CodeGenerator.class, ClassLoader.class, boolean.class, Class.class, String.class);
    constructor.setAccessible(true);
    JsonCodegen codegen = constructor.newInstance(compiler, loader, false, null, "NonEmpty");
    Method build =
        JsonCodegen.class.getDeclaredMethod(
            "build" + role, ObjectCodec.class, JsonTypeResolver.class);
    build.setAccessible(true);
    resolver.lockJIT();
    try {
      build.invoke(codegen, resolver.getObjectCodec(type), resolver);
    } finally {
      resolver.unlockJIT();
    }
    assertEquals(sources.size(), 1);
    return sources.get(0);
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

  private static void assertPrefixFields(PrefixFields value) {
    assertEquals(value.alpha, 4);
    assertEquals(value.alpine, 2);
    assertEquals(value.altar, 3);
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

  public static class WideMapFields {
    public int first;
    public Map<String, String> f01;
    public Map<String, String> f02;
    public Map<String, String> f03;
    public Map<String, String> f04;
    public Map<String, String> f05;
    public Map<String, String> f06;
    public Map<String, String> f07;
    public Map<String, String> f08;
    public Map<String, String> f09;
    public Map<String, String> f10;
    public Map<String, String> f11;
    public Map<String, String> f12;
    public Map<String, String> f13;
    public Map<String, String> f14;
    public Map<String, String> f15;
    public Map<String, String> f16;
    public Map<String, String> f17;
    public Map<String, String> f18;
    public Map<String, String> f19;
    public Map<String, String> f20;
    public Map<String, String> f21;
    public Map<String, String> f22;
    public Map<String, String> f23;
    public Map<String, String> f24;
    public Map<String, String> f25;
    public Map<String, String> f26;
    public Map<String, String> f27;
    public Map<String, String> f28;
    public Map<String, String> f29;
    public Map<String, String> f30;
    public Map<String, String> f31;
    public Map<String, String> f32;
  }

  public static class PrefixFields {
    public int alpha;
    public int alpine;
    public int altar;
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

  public static class WideWriterFields {
    public int field00 = 1;
    public String field01 = "v01";
    public String field02 = "v02";
    public String field03 = "v03";
    public String field04 = "v04";
    public String field05 = "v05";
    public String field06 = "v06";
    public String field07 = "v07";
    public String field08 = "v08";
    public String field09 = "v09";
    public String field10 = "v10";
    public String field11 = "v11";
    public String field12 = "v12";
    public String field13 = "v13";
    public String field14 = "v14";
    public String field15 = "v15";
    public String field16 = "v16";
    public String field17 = "v17";
    public String field18 = "v18";
    public String field19 = "v19";
    public String field20 = "v20";
    public String field21 = "v21";
    public String field22 = "v22";
    public String field23 = "v23";
  }

  public static final class ObjectCollections {
    public List<TokenValues> values;
    public Set<TokenValues> set;
  }

  public static final class RecursiveCollection {
    public List<RecursiveCollection> children;
    public int id;
  }

  public static final class FinalNonEmptyValues {
    public Integer count = 0;
    public Long total;
    public Kind kind = Kind.SMALL;
    public Operation operation = Operation.ADD;
    public JsonInclusionTest.EmptyObject nested = new JsonInclusionTest.EmptyObject();
  }

  // The constant bodies make Operation non-final, so it covers the enum clause of mayBeEmpty.
  public enum Operation {
    ADD {
      @Override
      int apply(int left, int right) {
        return left + right;
      }
    },
    SUBTRACT {
      @Override
      int apply(int left, int right) {
        return left - right;
      }
    };

    abstract int apply(int left, int right);
  }

  public static final class DecimalNonEmptyValue {
    public BigDecimal price = BigDecimal.ONE;
  }

  public static final class CustomNonEmptyValue {
    @JsonCodec(JsonInclusionTest.EmptyObjectCodec.class)
    public JsonInclusionTest.EmptyObject value = emptyObject();

    private static JsonInclusionTest.EmptyObject emptyObject() {
      JsonInclusionTest.EmptyObject value = new JsonInclusionTest.EmptyObject();
      value.empty = true;
      return value;
    }
  }

  public static final class PlainEmptyObjectCodec
      extends AbstractJsonValueCodec<JsonInclusionTest.EmptyObject> {
    @Override
    public void write(JsonWriter writer, JsonInclusionTest.EmptyObject value) {
      writer.writeString("plain");
    }

    @Override
    public JsonInclusionTest.EmptyObject read(JsonReader reader) {
      reader.readString();
      return new JsonInclusionTest.EmptyObject();
    }
  }

  // Generated classes are cached per owner class, so each registration kind needs its own owner.
  public static final class RegisteredValue {
    public JsonInclusionTest.EmptyObject value = CustomNonEmptyValue.emptyObject();
  }

  public static final class FactoryValue {
    public JsonInclusionTest.EmptyObject value = CustomNonEmptyValue.emptyObject();
  }

  public interface EmptyObjectEmptiness extends JsonValueCodec<JsonInclusionTest.EmptyObject> {
    @Override
    default boolean isEmpty(JsonWriter writer, JsonInclusionTest.EmptyObject value) {
      return value.empty;
    }
  }

  // Inherits isEmpty from an interface instead of declaring it.
  public static final class InheritedEmptinessCodec
      extends AbstractJsonValueCodec<JsonInclusionTest.EmptyObject>
      implements EmptyObjectEmptiness {
    @Override
    public void write(JsonWriter writer, JsonInclusionTest.EmptyObject value) {
      writer.writeString("");
    }

    @Override
    public JsonInclusionTest.EmptyObject read(JsonReader reader) {
      reader.readString();
      return new JsonInclusionTest.EmptyObject();
    }
  }

  public static final class InheritedValue {
    public JsonInclusionTest.EmptyObject value = CustomNonEmptyValue.emptyObject();
  }

  public abstract static class EmptinessAwareCodec<T> extends AbstractJsonValueCodec<T> {
    @Override
    public boolean isEmpty(JsonWriter writer, T value) {
      return ((JsonInclusionTest.EmptyObject) value).empty;
    }
  }

  // Inherits isEmpty from an abstract base class instead of declaring it.
  public static final class BaseClassEmptinessCodec
      extends EmptinessAwareCodec<JsonInclusionTest.EmptyObject> {
    @Override
    public void write(JsonWriter writer, JsonInclusionTest.EmptyObject value) {
      writer.writeString("");
    }

    @Override
    public JsonInclusionTest.EmptyObject read(JsonReader reader) {
      reader.readString();
      return new JsonInclusionTest.EmptyObject();
    }
  }

  public static final class BaseClassValue {
    public JsonInclusionTest.EmptyObject value = CustomNonEmptyValue.emptyObject();
  }

  public static final class MixinValue {
    public JsonInclusionTest.EmptyObject value = CustomNonEmptyValue.emptyObject();
  }

  @JsonMixin(target = JsonInclusionTest.EmptyObject.class)
  @JsonCodec(PlainEmptyObjectCodec.class)
  public interface PlainEmptyObjectMixin {}

  @JsonMixin(target = JsonInclusionTest.EmptyObject.class)
  @JsonCodec(JsonInclusionTest.EmptyObjectCodec.class)
  public interface EmptyObjectMixin {}

  public static final class WideNonEmptyValues {
    public int id = 1;
    public Integer v0 = 0;
    public Long v1 = 1L;
    public Kind v2 = Kind.FAST;
    public Integer v3 = 3;
    public Long v4 = 4L;
    public Kind v5 = Kind.FAST;
    public Integer v6 = 6;
    public Long v7 = 7L;
    public Kind v8 = Kind.FAST;
    public Integer v9 = 9;
    public Long v10 = 10L;
    public Kind v11 = Kind.FAST;
    public Integer v12 = 12;
    public Long v13 = 13L;
    public Kind v14 = Kind.FAST;
    public Integer v15 = 15;
    public Long v16 = 16L;
    public Kind v17 = Kind.FAST;
    public Integer v18 = 18;
    public Long v19 = 19L;
    public Kind v20 = Kind.FAST;
    public Integer v21 = 21;
    public Long v22 = 22L;
    public Kind v23 = Kind.FAST;
    public Integer v24 = 24;
    public Long v25 = 25L;
    public Kind v26 = Kind.FAST;
    public Integer v27 = 27;
    public Long v28 = 28L;
    public Kind v29 = Kind.FAST;
  }

  public static final class BuiltInEmptyValues {
    public String string = "";
    public StringBuilder text = new StringBuilder();
    public FinalList list = new FinalList();
    public FinalMap map = new FinalMap();
    public int[] array = new int[0];
    public Optional<String> optional = Optional.empty();
    public OptionalInt optionalInt = OptionalInt.empty();
    public OptionalLong optionalLong = OptionalLong.empty();
    public OptionalDouble optionalDouble = OptionalDouble.empty();
    public JsonInclusionTest.TextEnum textEnum = JsonInclusionTest.TextEnum.EMPTY;
  }

  public static final class FinalList extends ArrayList<String> {}

  public static final class FinalMap extends HashMap<String, String> {}

  public static final class DynamicNonEmptyValue {
    public Object value = "";
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
}
