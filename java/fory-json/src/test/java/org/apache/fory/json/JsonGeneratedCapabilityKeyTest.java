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

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.codec.JsonObjectModel;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.StringSerializer;
import org.apache.fory.type.Types;
import org.testng.annotations.Test;

public class JsonGeneratedCapabilityKeyTest {
  @Test
  public void outerNullabilityReusesObjectClasses() {
    JsonTypeResolver resolver = resolver();
    JsonTypeInfo raw = resolver.getTypeInfo(Model.class, Model.class);
    JsonTypeInfo nonNull = resolver.getTypeInfo(TypeRef.of(Model.class, ordinary(false)));
    JsonTypeInfo nullable = resolver.getTypeInfo(TypeRef.of(Model.class, ordinary(true)));

    assertObjectClasses(raw, nonNull);
    assertObjectClasses(raw, nullable);

    JsonTypeInfo tracked =
        resolver.getTypeInfo(
            TypeRef.of(Model.class, TypeExtMeta.of(Types.UNKNOWN, false, true, false, false)));
    assertNotSame(raw.utf8Reader().getClass(), tracked.utf8Reader().getClass());
  }

  @Test
  public void outerNullabilityReusesCollectionClasses() {
    JsonTypeResolver resolver = resolver();
    TypeRef<?> element = TypeRef.of(String.class);
    JsonTypeInfo raw = resolver.getTypeInfo(listType(null, element));
    JsonTypeInfo nonNull = resolver.getTypeInfo(listType(ordinary(false), element));
    JsonTypeInfo nullable = resolver.getTypeInfo(listType(ordinary(true), element));

    assertSame(raw.utf8Writer().getClass(), nonNull.utf8Writer().getClass());
    assertSame(raw.utf8Writer().getClass(), nullable.utf8Writer().getClass());
    assertSame(raw.utf8Reader().getClass(), nonNull.utf8Reader().getClass());
    assertSame(raw.utf8Reader().getClass(), nullable.utf8Reader().getClass());

    JsonTypeInfo nonNullElement =
        resolver.getTypeInfo(listType(null, TypeRef.of(String.class, ordinary(false))));
    JsonTypeInfo nullableElement =
        resolver.getTypeInfo(listType(null, TypeRef.of(String.class, ordinary(true))));
    assertNotSame(nonNullElement.utf8Writer().getClass(), nullableElement.utf8Writer().getClass());
    assertNotSame(nonNullElement.utf8Reader().getClass(), nullableElement.utf8Reader().getClass());
  }

  @Test
  public void componentMetadataRemainsDistinct() {
    JsonTypeResolver resolver = resolver();
    TypeRef<?> nonNullArray =
        TypeRef.of(
            String[].class, ordinary(false), null, TypeRef.of(String.class, ordinary(false)));
    TypeRef<?> nullableArray =
        TypeRef.of(String[].class, ordinary(false), null, TypeRef.of(String.class, ordinary(true)));
    JsonTypeInfo nonNull = resolver.getTypeInfo(boxType(nonNullArray));
    JsonTypeInfo nullable = resolver.getTypeInfo(boxType(nullableArray));
    assertNotSame(nonNull.utf8Writer().getClass(), nullable.utf8Writer().getClass());
    assertNotSame(nonNull.utf8Reader().getClass(), nullable.utf8Reader().getClass());
  }

  @Test
  public void fixedSubtypeIsGeneratedLeaf() {
    ForyJson json =
        ForyJson.builder()
            .registerCodec(
                FixedValue.class,
                (type, resolver, runtimeType) ->
                    resolver.createObjectCodec(
                        type, JsonObjectModel.fixedInstance(FixedValue.INSTANCE)))
            .withAsyncCompilation(false)
            .build();
    JsonTypeInfo typeInfo =
        JsonTestSupport.currentTypeResolver(json)
            .getTypeInfo(FixedContainer.class, FixedContainer.class);
    assertGeneratedObject(typeInfo);

    FixedContainer value = new FixedContainer();
    value.value = FixedValue.INSTANCE;
    assertSame(json.fromJson(json.toJson(value), FixedContainer.class).value, FixedValue.INSTANCE);

    String input = "{\"value\":{\"kind\":\"fixed\"}}";
    if (StringSerializer.isBytesBackedString()) {
      assertSame(
          ((FixedContainer)
                  typeInfo.latin1Reader().readLatin1(JsonTestSupport.newLatin1Reader(input)))
              .value,
          FixedValue.INSTANCE);
    }
    assertSame(
        ((FixedContainer) typeInfo.utf16Reader().readUtf16(JsonTestSupport.newUtf16Reader(input)))
            .value,
        FixedValue.INSTANCE);
    assertSame(
        ((FixedContainer)
                typeInfo
                    .utf8Reader()
                    .readUtf8(
                        JsonTestSupport.newUtf8Reader(input.getBytes(StandardCharsets.UTF_8))))
            .value,
        FixedValue.INSTANCE);
  }

  @Test
  public void hostedModelNeedsNoCompanion() throws Exception {
    ForyJson json = ForyJson.builder().withAsyncCompilation(false).build();
    Constructor<JsonSharedRegistry> constructor =
        JsonSharedRegistry.class.getDeclaredConstructor(
            JsonConfig.class, ExecutorService.class, boolean.class);
    constructor.setAccessible(true);
    JsonSharedRegistry registry = constructor.newInstance(json.config(), null, true);
    JsonTypeInfo typeInfo =
        new JsonTypeResolver(registry)
            .getTypeInfo(HostedAnnotatedModel.class, HostedAnnotatedModel.class);
    assertSame(typeInfo.rawType(), HostedAnnotatedModel.class);
  }

  private static JsonTypeResolver resolver() {
    ForyJson json = ForyJson.builder().withAsyncCompilation(false).build();
    return JsonTestSupport.currentTypeResolver(json);
  }

  private static TypeExtMeta ordinary(boolean nullable) {
    return TypeExtMeta.of(Types.UNKNOWN, nullable, false, false, false);
  }

  private static TypeRef<?> listType(TypeExtMeta metadata, TypeRef<?> element) {
    return TypeRef.ofDeclaredTypeArguments(
        List.class, metadata, Collections.singletonList(element), null);
  }

  private static TypeRef<?> boxType(TypeRef<?> value) {
    return TypeRef.ofDeclaredTypeArguments(
        Box.class, ordinary(false), Collections.singletonList(value), null);
  }

  private static void assertObjectClasses(JsonTypeInfo expected, JsonTypeInfo actual) {
    assertSame(expected.stringWriter().getClass(), actual.stringWriter().getClass());
    assertSame(expected.utf8Writer().getClass(), actual.utf8Writer().getClass());
    assertSame(expected.latin1Reader().getClass(), actual.latin1Reader().getClass());
    assertSame(expected.utf16Reader().getClass(), actual.utf16Reader().getClass());
    assertSame(expected.utf8Reader().getClass(), actual.utf8Reader().getClass());
  }

  private static void assertGeneratedObject(JsonTypeInfo typeInfo) {
    assertFalse(ObjectCodec.class.isAssignableFrom(typeInfo.stringWriter().getClass()));
    assertFalse(ObjectCodec.class.isAssignableFrom(typeInfo.utf8Writer().getClass()));
    assertFalse(ObjectCodec.class.isAssignableFrom(typeInfo.latin1Reader().getClass()));
    assertFalse(ObjectCodec.class.isAssignableFrom(typeInfo.utf16Reader().getClass()));
    assertFalse(ObjectCodec.class.isAssignableFrom(typeInfo.utf8Reader().getClass()));
  }

  public static final class Model {
    public String value;

    public Model() {}
  }

  public static final class Box<T> {
    public T value;

    public Box() {}
  }

  @JsonSubTypes(
      value = @JsonSubTypes.Type(value = FixedValue.class, name = "fixed"),
      inclusion = JsonSubTypes.Inclusion.PROPERTY,
      property = "kind")
  public interface FixedBase {}

  public static final class FixedValue implements FixedBase {
    static final FixedValue INSTANCE = new FixedValue();

    private FixedValue() {}
  }

  public static final class FixedContainer {
    public FixedBase value;

    public FixedContainer() {}
  }

  @JsonType
  public static final class HostedAnnotatedModel {
    public int value;

    public HostedAnnotatedModel() {}
  }
}
