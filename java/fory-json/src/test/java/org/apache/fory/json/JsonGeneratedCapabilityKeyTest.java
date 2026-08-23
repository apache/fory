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
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.security.auth.Subject;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.apache.fory.json.annotation.JsonAnyGetter;
import org.apache.fory.json.annotation.JsonAnySetter;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.annotation.JsonUnwrapped;
import org.apache.fory.json.codec.AbstractJsonValueCodec;
import org.apache.fory.json.codec.DirectUnboxedValueCodec;
import org.apache.fory.json.codec.JsonObjectModel;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.TransparentUnboxedValueCodec;
import org.apache.fory.json.codegen.GeneratedCodecKey;
import org.apache.fory.json.codegen.JsonCodegen;
import org.apache.fory.json.data.PublicFields;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.platform.JdkVersion;
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
    assertSame(raw.utf8Reader().getClass(), tracked.utf8Reader().getClass());
  }

  @Test
  public void collectionBindingVersionsClasses() {
    JsonTypeResolver resolver = resolver();
    TypeRef<?> element = TypeRef.of(String.class);
    JsonTypeInfo raw = resolver.getTypeInfo(listType(null, element));
    JsonTypeInfo nonNull = resolver.getTypeInfo(listType(ordinary(false), element));
    JsonTypeInfo nullable = resolver.getTypeInfo(listType(ordinary(true), element));

    assertNotSame(raw.utf8Writer().getClass(), nonNull.utf8Writer().getClass());
    assertNotSame(raw.utf8Writer().getClass(), nullable.utf8Writer().getClass());
    assertNotSame(raw.utf8Reader().getClass(), nonNull.utf8Reader().getClass());
    assertNotSame(raw.utf8Reader().getClass(), nullable.utf8Reader().getClass());

    JsonTypeInfo nonNullElement =
        resolver.getTypeInfo(listType(null, TypeRef.of(String.class, ordinary(false))));
    JsonTypeInfo nullableElement =
        resolver.getTypeInfo(listType(null, TypeRef.of(String.class, ordinary(true))));
    assertNotSame(nonNullElement.utf8Writer().getClass(), nullableElement.utf8Writer().getClass());
    assertNotSame(nonNullElement.utf8Reader().getClass(), nullableElement.utf8Reader().getClass());
  }

  @Test
  public void nestedBindingVersionsParentClass() {
    JsonTypeResolver resolver = resolver();
    TypeRef<?> nonNullArray =
        TypeRef.of(
            String[].class, ordinary(false), null, TypeRef.of(String.class, ordinary(false)));
    TypeRef<?> nullableArray =
        TypeRef.of(String[].class, ordinary(false), null, TypeRef.of(String.class, ordinary(true)));
    JsonTypeInfo nonNull = resolver.getTypeInfo(boxType(nonNullArray));
    JsonTypeInfo nullable = resolver.getTypeInfo(boxType(nullableArray));
    assertDifferentObjectClasses(nonNull, nullable);
  }

  @Test
  public void genericBindingsVersionClass() {
    ForyJson json = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeResolver resolver = JsonTestSupport.currentTypeResolver(json);
    JsonTypeInfo strings = resolver.getTypeInfo(new TypeRef<GenericModel<String>>() {});
    JsonTypeInfo integers = resolver.getTypeInfo(new TypeRef<GenericModel<Integer>>() {});
    JsonTypeInfo bytes = resolver.getTypeInfo(new TypeRef<GenericModel<Byte>>() {});
    JsonTypeInfo enums = resolver.getTypeInfo(new TypeRef<GenericModel<FirstEnum>>() {});
    assertDifferentObjectClasses(strings, integers);
    assertDifferentObjectClasses(strings, bytes);
    assertDifferentObjectClasses(strings, enums);

    GenericModel<String> stringValue = new GenericModel<>();
    stringValue.value = "value";
    GenericModel<Integer> intValue = new GenericModel<>();
    intValue.value = 7;
    GenericModel<Byte> byteValue = new GenericModel<>();
    byteValue.value = (byte) 3;
    GenericModel<FirstEnum> enumValue = new GenericModel<>();
    enumValue.value = FirstEnum.VALUE;
    TypeRef<GenericModel<String>> stringType = new TypeRef<GenericModel<String>>() {};
    TypeRef<GenericModel<Integer>> intType = new TypeRef<GenericModel<Integer>>() {};
    TypeRef<GenericModel<Byte>> byteType = new TypeRef<GenericModel<Byte>>() {};
    TypeRef<GenericModel<FirstEnum>> enumType = new TypeRef<GenericModel<FirstEnum>>() {};
    assertEquals(json.toJson(stringValue, stringType), "{\"value\":\"value\"}");
    assertEquals(json.toJson(intValue, intType), "{\"value\":7}");
    assertEquals(json.toJson(byteValue, byteType), "{\"value\":3}");
    assertEquals(json.toJson(enumValue, enumType), "{\"value\":\"VALUE\"}");
    assertEquals(json.fromJson(json.toJson(stringValue, stringType), stringType).value, "value");
    assertEquals(json.fromJson(json.toJson(intValue, intType), intType).value, 7);
    assertEquals(json.fromJson(json.toJson(byteValue, byteType), byteType).value, (byte) 3);
    assertSame(json.fromJson(json.toJson(enumValue, enumType), enumType).value, FirstEnum.VALUE);
  }

  @Test
  public void exactGenericBindingReusesClass() {
    TypeRef<GenericModel<String>> type = new TypeRef<GenericModel<String>>() {};
    ForyJson first = ForyJson.builder().withAsyncCompilation(false).build();
    ForyJson second = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeInfo firstType = JsonTestSupport.currentTypeResolver(first).getTypeInfo(type);
    JsonTypeInfo secondType =
        JsonTestSupport.currentTypeResolver(second)
            .getTypeInfo(new TypeRef<GenericModel<String>>() {});

    assertObjectClasses(firstType, secondType);
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

  @Test
  public void directCodecClassVersionsParent() {
    JsonTypeInfo first = parentType(new ChildCodecA());
    JsonTypeInfo equivalent = parentType(new ChildCodecA());
    JsonTypeInfo different = parentType(new ChildCodecB());

    assertObjectClasses(first, equivalent);
    assertDifferentObjectClasses(first, different);
  }

  @Test
  public void hiddenCodecClassesVersionParent() {
    JsonTypeInfo first = parentType(new HiddenChildCodecA());
    JsonTypeInfo second = parentType(new HiddenChildCodecB());

    assertDifferentObjectClasses(first, second);
  }

  @Test
  public void directFactoryVersionsParent() {
    assertDifferentObjectClasses(
        parentFactoryType("direct-a", new ChildCodecA(), true),
        parentFactoryType("direct-b", new ChildCodecB(), true));
  }

  @Test
  public void moduleFactoryVersionsParent() {
    assertDifferentObjectClasses(
        parentFactoryType("module-a", new ChildCodecA(), false),
        parentFactoryType("module-b", new ChildCodecB(), false));
  }

  @Test
  public void directMixinVersionsParent() {
    ForyJson first = ForyJson.builder().withAsyncCompilation(false).build();
    ForyJson second =
        ForyJson.builder().registerMixin(ChildCodecMixin.class).withAsyncCompilation(false).build();
    assertDifferentObjectClasses(parentType(first), parentType(second));

    Parent value = new Parent();
    value.child = new Child();
    value.child.value = "value";
    assertEquals(first.toJson(value), "{\"child\":{\"value\":\"value\"}}");
    assertEquals(second.toJson(value), "{\"child\":\"value\"}");
  }

  @Test
  public void objectFactoryVersionsClass() throws Exception {
    assertDifferentObjectClasses(factoryModelType(true), factoryModelType(false));
  }

  @Test
  public void unwrappedFactoryVersionsParent() throws Exception {
    assertDifferentObjectClasses(unwrappedFactoryType(true), unwrappedFactoryType(false));
  }

  @Test
  public void directCodecStateStaysInstanceOwned() {
    ForyJson first = parentJson(new StatefulChildCodec("first:"));
    ForyJson second = parentJson(new StatefulChildCodec("second:"));
    JsonTypeInfo firstType = parentType(first);
    JsonTypeInfo secondType = parentType(second);
    assertObjectClasses(firstType, secondType);

    Parent value = new Parent();
    value.child = new Child();
    value.child.value = "value";
    assertEquals(first.toJson(value), "{\"child\":\"first:value\"}");
    assertEquals(second.toJson(value), "{\"child\":\"second:value\"}");
    assertEquals(first.fromJson("{\"child\":\"first:value\"}", Parent.class).child.value, "value");
    assertEquals(
        second.fromJson("{\"child\":\"second:value\"}", Parent.class).child.value, "value");
  }

  @Test
  public void transparentDirectFactoryVersionsClass() throws Exception {
    ForyJson first = transparentJson(new DirectTerminalCodecA());
    ForyJson second = transparentJson(new DirectTerminalCodecB());
    JsonTypeInfo firstType = transparentType(first);
    JsonTypeInfo secondType = transparentType(second);
    assertDifferentObjectClasses(firstType, secondType);

    TransparentModel value = new TransparentModel();
    value.setValue(new LocalTerminal("value"));
    assertEquals(first.toJson(value), "{\"value\":\"a:value\"}");
    assertEquals(second.toJson(value), "{\"value\":\"b:value\"}");
    assertEquals(
        ((LocalTerminal)
                first.fromJson("{\"value\":\"a:value\"}", TransparentModel.class).getValue())
            .value,
        "value");
    assertEquals(
        ((LocalTerminal)
                second.fromJson("{\"value\":\"b:value\"}", TransparentModel.class).getValue())
            .value,
        "value");
  }

  @Test
  public void unrelatedRegistrationReusesParent() {
    JsonTypeInfo first = parentType(new ChildCodecA());
    ForyJson json = parentJson(new ChildCodecA(), true);
    assertObjectClasses(first, parentType(json));
  }

  @Test
  public void configuredLoaderDoesNotVersionClass() {
    ForyJson first = ForyJson.builder().withAsyncCompilation(false).build();
    ForyJson second =
        ForyJson.builder()
            .withClassLoader(new ClassLoader(Model.class.getClassLoader()) {})
            .withAsyncCompilation(false)
            .build();
    JsonTypeInfo firstType =
        JsonTestSupport.currentTypeResolver(first).getTypeInfo(Model.class, Model.class);
    JsonTypeInfo secondType =
        JsonTestSupport.currentTypeResolver(second).getTypeInfo(Model.class, Model.class);
    assertObjectClasses(firstType, secondType);
  }

  @Test
  public void writeNullVersionsWriters() {
    ForyJson first = ForyJson.builder().withAsyncCompilation(false).build();
    ForyJson second = ForyJson.builder().writeNullFields(true).withAsyncCompilation(false).build();
    JsonTypeInfo firstType =
        JsonTestSupport.currentTypeResolver(first).getTypeInfo(Model.class, Model.class);
    JsonTypeInfo secondType =
        JsonTestSupport.currentTypeResolver(second).getTypeInfo(Model.class, Model.class);
    assertNotSame(firstType.stringWriter().getClass(), secondType.stringWriter().getClass());
    assertNotSame(firstType.utf8Writer().getClass(), secondType.utf8Writer().getClass());
    assertSame(firstType.latin1Reader().getClass(), secondType.latin1Reader().getClass());
    assertSame(firstType.utf16Reader().getClass(), secondType.utf16Reader().getClass());
    assertSame(firstType.utf8Reader().getClass(), secondType.utf8Reader().getClass());
  }

  @Test
  public void fieldModeVersionsClasses() {
    ForyJson properties = ForyJson.builder().withAsyncCompilation(false).build();
    ForyJson fields = ForyJson.builder().withFieldMode(true).withAsyncCompilation(false).build();
    JsonTypeInfo propertyType =
        JsonTestSupport.currentTypeResolver(properties).getTypeInfo(Model.class, Model.class);
    JsonTypeInfo fieldType =
        JsonTestSupport.currentTypeResolver(fields).getTypeInfo(Model.class, Model.class);

    assertDifferentObjectClasses(propertyType, fieldType);
  }

  @Test
  public void anyCodecVersionsGeneratedRoles() {
    JsonTypeInfo getterA = anyType(GetterAny.class, new ChildCodecA());
    JsonTypeInfo getterB = anyType(GetterAny.class, new ChildCodecB());
    assertNotSame(getterA.stringWriter().getClass(), getterB.stringWriter().getClass());
    assertNotSame(getterA.utf8Writer().getClass(), getterB.utf8Writer().getClass());
    assertSame(getterA.latin1Reader().getClass(), getterB.latin1Reader().getClass());
    assertSame(getterA.utf16Reader().getClass(), getterB.utf16Reader().getClass());
    assertSame(getterA.utf8Reader().getClass(), getterB.utf8Reader().getClass());

    JsonTypeInfo setterA = anyType(SetterAny.class, new ChildCodecA());
    JsonTypeInfo setterB = anyType(SetterAny.class, new ChildCodecB());
    assertSame(setterA.stringWriter().getClass(), setterB.stringWriter().getClass());
    assertSame(setterA.utf8Writer().getClass(), setterB.utf8Writer().getClass());
    assertNotSame(setterA.latin1Reader().getClass(), setterB.latin1Reader().getClass());
    assertNotSame(setterA.utf16Reader().getClass(), setterB.utf16Reader().getClass());
    assertNotSame(setterA.utf8Reader().getClass(), setterB.utf8Reader().getClass());
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void hostedConcealedTypeUsesInterpretedRole() throws Exception {
    if (JdkVersion.MAJOR_VERSION < 9) {
      return;
    }
    String packageName = "org.apache.fory.json.concealed";
    Path targetOutput = Files.createTempDirectory("fory-json-concealed-target");
    compileSource(
        targetOutput,
        packageName,
        "ConcealedModel",
        "public final class ConcealedModel { public jdk.internal.misc.Unsafe value; }",
        "--add-exports",
        "java.base/jdk.internal.misc=ALL-UNNAMED");
    try (URLClassLoader targetLoader =
        new URLClassLoader(new URL[] {targetOutput.toUri().toURL()}, getClass().getClassLoader())) {
      Class<?> target = Class.forName(packageName + ".ConcealedModel", true, targetLoader);
      Class<?> concealed = Class.forName("jdk.internal.misc.Unsafe");
      ForyJson configured =
          ForyJson.builder().registerCodec((Class) concealed, JsonTestSupport.nullCodec()).build();
      JsonTypeResolver resolver = hostedResolver(configured);
      resolver.generateHostedCodecs(target);
      assertInterpretedObject(resolver.getTypeInfo((Class) target, target));
    }
  }

  @Test
  public void hostedBootstrapUsesInterpretedRole() throws Exception {
    JsonTypeResolver resolver =
        hostedResolver(
            ForyJson.builder()
                .registerCodec(java.security.Principal.class, JsonTestSupport.nullCodec())
                .build());
    resolver.generateHostedCodecs(Subject.class);
    assertInterpretedObject(resolver.getTypeInfo(Subject.class, Subject.class));
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void hostedTerminalUsesInterpretedRole() throws Exception {
    String packageName = "org.apache.fory.json.terminal";
    Path terminalOutput = Files.createTempDirectory("fory-json-terminal");
    compileSource(
        terminalOutput,
        packageName,
        "SiblingTerminal",
        "public final class SiblingTerminal implements "
            + SiblingCarrier.class.getCanonicalName()
            + " {}");
    try (URLClassLoader terminalLoader =
        new URLClassLoader(
            new URL[] {terminalOutput.toUri().toURL()}, getClass().getClassLoader())) {
      Class<?> terminal = Class.forName(packageName + ".SiblingTerminal", true, terminalLoader);
      JsonObjectModel model = transparentModel();
      ForyJson configured =
          ForyJson.builder()
              .registerCodec((Class) terminal, JsonTestSupport.nullCodec())
              .registerCodec(
                  SiblingValue.class,
                  (type, resolver, runtimeType) ->
                      new SiblingTransparentCodec(resolver.getTypeInfo((Class) terminal, terminal)))
              .registerCodec(
                  TransparentModel.class,
                  (type, resolver, runtimeType) -> resolver.createObjectCodec(type, model))
              .build();
      JsonTypeResolver resolver = hostedResolver(configured);
      resolver.generateHostedCodecs(TransparentModel.class);
      assertInterpretedObject(resolver.getTypeInfo(TransparentModel.class, TransparentModel.class));
    }
  }

  @Test
  public void concurrentInstancesShareFirstClass() throws Exception {
    ForyJson first = ForyJson.builder().withAsyncCompilation(false).build();
    ForyJson second = ForyJson.builder().withAsyncCompilation(false).build();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<JsonTypeInfo> firstType =
          executor.submit(
              () -> {
                start.await();
                return JsonTestSupport.currentTypeResolver(first)
                    .getTypeInfo(Model.class, Model.class);
              });
      Future<JsonTypeInfo> secondType =
          executor.submit(
              () -> {
                start.await();
                return JsonTestSupport.currentTypeResolver(second)
                    .getTypeInfo(Model.class, Model.class);
              });
      start.countDown();
      assertObjectClasses(firstType.get(), secondType.get());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void cacheResetAllowsEquivalentClass() {
    ForyJson first = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeInfo firstType =
        JsonTestSupport.currentTypeResolver(first).getTypeInfo(Model.class, Model.class);
    JsonCodegen.resetGeneratedClassCache();
    ForyJson second = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeInfo secondType =
        JsonTestSupport.currentTypeResolver(second).getTypeInfo(Model.class, Model.class);
    assertDifferentObjectClasses(firstType, secondType);

    Model value = new Model();
    value.value = "retained";
    assertEquals(first.fromJson(first.toJson(value), Model.class).value, "retained");
    assertEquals(second.fromJson(second.toJson(value), Model.class).value, "retained");
  }

  @Test
  public void collectionClassIgnoresElementCodec() {
    JsonTypeInfo first = collectionType(new ChildCodecA());
    JsonTypeInfo different = collectionType(new ChildCodecB());

    assertSame(first.utf8Writer().getClass(), different.utf8Writer().getClass());
    assertSame(first.utf8Reader().getClass(), different.utf8Reader().getClass());
  }

  @Test
  public void sameNamedLoaderClassesDoNotCollide() throws Exception {
    Class<?> firstClass = JsonTestSupport.shadowClass(PublicFields.class);
    Class<?> secondClass = JsonTestSupport.shadowClass(PublicFields.class);
    assertNotSame(firstClass, secondClass);
    GeneratedCodecKey firstKey =
        GeneratedCodecKey.object(
            firstClass, null, GeneratedCodecKey.Role.STRING_WRITER, new Object[0]);
    GeneratedCodecKey secondKey =
        GeneratedCodecKey.object(
            secondClass, null, GeneratedCodecKey.Role.STRING_WRITER, new Object[0]);
    assertEquals(firstKey.hashCode(), secondKey.hashCode());
    assertNotEquals(firstKey, secondKey);

    TypeRef<?> firstBinding =
        TypeRef.ofDeclaredTypeArguments(
            GenericModel.class, null, Collections.singletonList(TypeRef.of(firstClass)), null);
    TypeRef<?> secondBinding =
        TypeRef.ofDeclaredTypeArguments(
            GenericModel.class, null, Collections.singletonList(TypeRef.of(secondClass)), null);
    GeneratedCodecKey firstGenericKey =
        GeneratedCodecKey.object(
            GenericModel.class, firstBinding, GeneratedCodecKey.Role.STRING_WRITER, new Object[0]);
    GeneratedCodecKey secondGenericKey =
        GeneratedCodecKey.object(
            GenericModel.class, secondBinding, GeneratedCodecKey.Role.STRING_WRITER, new Object[0]);
    assertEquals(firstGenericKey.hashCode(), secondGenericKey.hashCode());
    assertNotEquals(firstGenericKey, secondGenericKey);

    JsonTypeInfo first = loaderType(firstClass);
    JsonTypeInfo second = loaderType(secondClass);
    assertDifferentObjectClasses(first, second);
  }

  @Test
  public void equalKeysHaveEqualHash() {
    TypeRef<?> strings =
        TypeRef.ofSemanticTypeArguments(
            GenericModel.class, null, Collections.singletonList(TypeRef.of(String.class)), null);
    TypeRef<?> integers =
        TypeRef.ofSemanticTypeArguments(
            GenericModel.class, null, Collections.singletonList(TypeRef.of(Integer.class)), null);
    assertEquals(strings, integers);

    GeneratedCodecKey first =
        GeneratedCodecKey.object(
            GenericModel.class, strings, GeneratedCodecKey.Role.STRING_WRITER, new Object[0]);
    GeneratedCodecKey second =
        GeneratedCodecKey.object(
            GenericModel.class, integers, GeneratedCodecKey.Role.STRING_WRITER, new Object[0]);
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }

  @Test
  public void rootBindingAnchorsBootstrapTarget() {
    TypeRef<Map.Entry<Child, String>> binding = new TypeRef<Map.Entry<Child, String>>() {};
    GeneratedCodecKey key =
        GeneratedCodecKey.object(
            Map.Entry.class, binding, GeneratedCodecKey.Role.STRING_WRITER, new Object[0]);
    assertSame(key.anchorClass(), Child.class);

    TypeRef<Map.Entry<? super Child, String>> lowerBound =
        new TypeRef<Map.Entry<? super Child, String>>() {};
    GeneratedCodecKey lowerBoundKey =
        GeneratedCodecKey.object(
            Map.Entry.class, lowerBound, GeneratedCodecKey.Role.STRING_WRITER, new Object[0]);
    assertSame(lowerBoundKey.anchorClass(), Child.class);
  }

  private static JsonTypeResolver resolver() {
    ForyJson json = ForyJson.builder().withAsyncCompilation(false).build();
    return JsonTestSupport.currentTypeResolver(json);
  }

  private static JsonTypeInfo parentType(JsonValueCodec<Child> codec) {
    return parentType(parentJson(codec));
  }

  private static ForyJson parentJson(JsonValueCodec<Child> codec) {
    return parentJson(codec, false);
  }

  private static ForyJson parentJson(JsonValueCodec<Child> codec, boolean unrelated) {
    ForyJsonBuilder builder =
        ForyJson.builder().registerCodec(Child.class, codec).withAsyncCompilation(false);
    if (unrelated) {
      JsonCodecFactory factory =
          new JsonCodecFactory() {
            @Override
            public JsonValueCodec<?> create(
                TypeRef<?> type, JsonTypeResolver resolver, boolean runtimeType) {
              return type.getRawType() == OtherUnrelated.class ? JsonTestSupport.nullCodec() : null;
            }

            @Override
            public String factoryKey() {
              return "unrelated-module";
            }
          };
      builder
          .registerCodec(Unrelated.class, JsonTestSupport.nullCodec())
          .registerMixin(UnrelatedMixin.class)
          .withModule(
              context -> {
                context.registerCodec(OtherUnrelated.class, factory);
                context.registerCodecFactory(factory);
              });
    }
    return builder.build();
  }

  private static JsonTypeInfo parentFactoryType(
      String key, JsonValueCodec<Child> codec, boolean exact) {
    JsonCodecFactory factory =
        new JsonCodecFactory() {
          @Override
          public JsonValueCodec<?> create(
              TypeRef<?> type, JsonTypeResolver resolver, boolean runtimeType) {
            return type.getRawType() == Child.class ? codec : null;
          }

          @Override
          public String factoryKey() {
            return key;
          }
        };
    ForyJsonBuilder builder = ForyJson.builder().withAsyncCompilation(false);
    if (exact) {
      builder.registerCodec(Child.class, factory);
    } else {
      builder.withModule(context -> context.registerCodecFactory(factory));
    }
    return parentType(builder.build());
  }

  private static JsonTypeInfo parentType(ForyJson json) {
    return JsonTestSupport.currentTypeResolver(json).getTypeInfo(Parent.class, Parent.class);
  }

  private static ForyJson transparentJson(JsonValueCodec<LocalTerminal> terminalCodec)
      throws Exception {
    JsonObjectModel model = transparentModel();
    JsonCodecFactory siblingFactory =
        new JsonCodecFactory() {
          @Override
          public JsonValueCodec<?> create(
              TypeRef<?> type, JsonTypeResolver resolver, boolean runtimeType) {
            return new SiblingTransparentCodec(
                resolver.getTypeInfo(LocalTerminal.class, LocalTerminal.class));
          }

          @Override
          public String factoryKey() {
            return "sibling-transparent:" + terminalCodec.getClass().getName();
          }
        };
    return ForyJson.builder()
        .registerCodec(LocalTerminal.class, terminalCodec)
        .registerCodec(SiblingValue.class, siblingFactory)
        .registerCodec(
            TransparentModel.class,
            (type, resolver, runtimeType) -> resolver.createObjectCodec(type, model))
        .withAsyncCompilation(false)
        .build();
  }

  private static JsonTypeInfo transparentType(ForyJson json) {
    return JsonTestSupport.currentTypeResolver(json)
        .getTypeInfo(TransparentModel.class, TransparentModel.class);
  }

  private static Method publicMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
    try {
      return owner.getMethod(name, parameterTypes);
    } catch (NoSuchMethodException e) {
      throw new AssertionError(e);
    }
  }

  private static JsonTypeInfo collectionType(JsonValueCodec<Child> codec) {
    ForyJson json =
        ForyJson.builder().registerCodec(Child.class, codec).withAsyncCompilation(false).build();
    return JsonTestSupport.currentTypeResolver(json).getTypeInfo(new TypeRef<List<Child>>() {});
  }

  private static JsonTypeInfo factoryModelType(boolean first) throws Exception {
    JsonObjectModel model = factoryModel(first);
    JsonCodecFactory factory =
        new JsonCodecFactory() {
          @Override
          public JsonValueCodec<?> create(
              TypeRef<?> type, JsonTypeResolver resolver, boolean runtimeType) {
            return resolver.createObjectCodec(type, model);
          }

          @Override
          public String factoryKey() {
            return first ? "factory-model-first" : "factory-model-second";
          }
        };
    ForyJson json =
        ForyJson.builder()
            .registerCodec(FactoryModel.class, factory)
            .withAsyncCompilation(false)
            .build();
    return JsonTestSupport.currentTypeResolver(json)
        .getTypeInfo(FactoryModel.class, FactoryModel.class);
  }

  private static JsonTypeInfo unwrappedFactoryType(boolean first) throws Exception {
    JsonObjectModel model = factoryModel(first);
    JsonCodecFactory factory =
        new JsonCodecFactory() {
          @Override
          public JsonValueCodec<?> create(
              TypeRef<?> type, JsonTypeResolver resolver, boolean runtimeType) {
            return resolver.createObjectCodec(type, model);
          }

          @Override
          public String factoryKey() {
            return first ? "unwrapped-first" : "unwrapped-second";
          }
        };
    ForyJson json =
        ForyJson.builder()
            .registerCodec(FactoryModel.class, factory)
            .withAsyncCompilation(false)
            .build();
    return JsonTestSupport.currentTypeResolver(json)
        .getTypeInfo(UnwrappedFactoryModel.class, UnwrappedFactoryModel.class);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static JsonTypeInfo anyType(Class<?> type, JsonValueCodec<Child> codec) {
    ForyJson json = parentJson(codec);
    return JsonTestSupport.currentTypeResolver(json).getTypeInfo((Class) type, type);
  }

  private static JsonTypeResolver hostedResolver(ForyJson json) throws Exception {
    Constructor<JsonSharedRegistry> constructor =
        JsonSharedRegistry.class.getDeclaredConstructor(
            JsonConfig.class, ExecutorService.class, boolean.class);
    constructor.setAccessible(true);
    return new JsonTypeResolver(constructor.newInstance(json.config(), null, true));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static JsonTypeInfo loaderType(Class<?> type) {
    ForyJson json =
        ForyJson.builder()
            .withClassLoader(type.getClassLoader())
            .withAsyncCompilation(false)
            .build();
    return JsonTestSupport.currentTypeResolver(json).getTypeInfo((Class) type, type);
  }

  private static void compileSource(
      Path output, String packageName, String simpleName, String declaration) throws IOException {
    compileSource(output, packageName, simpleName, declaration, new String[0]);
  }

  private static void compileSource(
      Path output, String packageName, String simpleName, String declaration, String... options)
      throws IOException {
    Path source = output.resolve(simpleName + ".java");
    Files.write(
        source, ("package " + packageName + "; " + declaration).getBytes(StandardCharsets.UTF_8));
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    ArrayList<String> arguments = new ArrayList<>();
    Collections.addAll(
        arguments, "-proc:none", "-classpath", System.getProperty("java.class.path"));
    Collections.addAll(arguments, options);
    Collections.addAll(arguments, "-d", output.toString(), source.toString());
    assertEquals(compiler.run(null, null, null, arguments.toArray(new String[0])), 0);
  }

  private static JsonObjectModel transparentModel() throws Exception {
    TypeRef<?> logicalType = TypeRef.of(SiblingValue.class, ordinary(false));
    return new JsonObjectModel(
        TransparentModel.class.getConstructor(),
        null,
        new String[0],
        new Method[0],
        new Method[0],
        new int[0],
        new boolean[0],
        new TypeRef<?>[0],
        new String[] {"value"},
        new Method[] {TransparentModel.class.getMethod("getValue")},
        new Method[] {TransparentModel.class.getMethod("setValue", SiblingCarrier.class)},
        new TypeRef<?>[] {logicalType});
  }

  private static JsonObjectModel factoryModel(boolean first) throws Exception {
    String name = first ? "first" : "second";
    Class<?> valueType = first ? String.class : long.class;
    String getter = first ? "getFirst" : "getSecond";
    String setter = first ? "setFirst" : "setSecond";
    return new JsonObjectModel(
        FactoryModel.class.getConstructor(),
        null,
        new String[0],
        new Method[0],
        new Method[0],
        new int[0],
        new boolean[0],
        new TypeRef<?>[0],
        new String[] {name},
        new Method[] {FactoryModel.class.getMethod(getter)},
        new Method[] {FactoryModel.class.getMethod(setter, valueType)},
        new TypeRef<?>[] {TypeRef.of(valueType)});
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

  private static void assertDifferentObjectClasses(JsonTypeInfo expected, JsonTypeInfo actual) {
    assertNotSame(expected.stringWriter().getClass(), actual.stringWriter().getClass());
    assertNotSame(expected.utf8Writer().getClass(), actual.utf8Writer().getClass());
    assertNotSame(expected.latin1Reader().getClass(), actual.latin1Reader().getClass());
    assertNotSame(expected.utf16Reader().getClass(), actual.utf16Reader().getClass());
    assertNotSame(expected.utf8Reader().getClass(), actual.utf8Reader().getClass());
  }

  private static void assertGeneratedObject(JsonTypeInfo typeInfo) {
    assertFalse(ObjectCodec.class.isAssignableFrom(typeInfo.stringWriter().getClass()));
    assertFalse(ObjectCodec.class.isAssignableFrom(typeInfo.utf8Writer().getClass()));
    assertFalse(ObjectCodec.class.isAssignableFrom(typeInfo.latin1Reader().getClass()));
    assertFalse(ObjectCodec.class.isAssignableFrom(typeInfo.utf16Reader().getClass()));
    assertFalse(ObjectCodec.class.isAssignableFrom(typeInfo.utf8Reader().getClass()));
  }

  private static void assertInterpretedObject(JsonTypeInfo typeInfo) {
    assertTrue(typeInfo.stringWriter() instanceof ObjectCodec<?>);
    assertTrue(typeInfo.utf8Writer() instanceof ObjectCodec<?>);
    assertTrue(typeInfo.latin1Reader() instanceof ObjectCodec<?>);
    assertTrue(typeInfo.utf16Reader() instanceof ObjectCodec<?>);
    assertTrue(typeInfo.utf8Reader() instanceof ObjectCodec<?>);
  }

  public static final class Model {
    public String value;

    public Model() {}
  }

  public static final class Parent {
    public Child child;

    public Parent() {}
  }

  public static final class GenericModel<T> {
    public T value;

    public GenericModel() {}
  }

  public enum FirstEnum {
    VALUE
  }

  public static final class FactoryModel {
    private String first;
    private long second;

    public FactoryModel() {}

    public String getFirst() {
      return first;
    }

    public void setFirst(String first) {
      this.first = first;
    }

    public long getSecond() {
      return second;
    }

    public void setSecond(long second) {
      this.second = second;
    }
  }

  public static final class UnwrappedFactoryModel {
    @JsonUnwrapped public FactoryModel value;

    public UnwrappedFactoryModel() {}
  }

  public static final class GetterAny {
    private final Map<String, Child> values = new LinkedHashMap<>();

    @JsonAnyGetter
    public Map<String, Child> values() {
      return values;
    }
  }

  public static final class SetterAny {
    private final Map<String, Child> values = new LinkedHashMap<>();

    @JsonAnySetter
    public void put(String name, Child value) {
      values.put(name, value);
    }
  }

  public static final class Child {
    public String value;

    public Child() {}
  }

  public interface SiblingCarrier {}

  public static final class LocalTerminal implements SiblingCarrier {
    final String value;

    LocalTerminal(String value) {
      this.value = value;
    }
  }

  public static final class SiblingValue {}

  public static final class TransparentModel {
    private SiblingCarrier value;

    public TransparentModel() {}

    public SiblingCarrier getValue() {
      return value;
    }

    public void setValue(SiblingCarrier value) {
      this.value = value;
    }
  }

  public static class ChildCodecA implements JsonValueCodec<Child> {
    @Override
    public void writeString(StringJsonWriter writer, Child value) {
      writer.writeString(value.value);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Child value) {
      writer.writeString(value.value);
    }

    @Override
    public Child readLatin1(Latin1JsonReader reader) {
      return child(reader.readString());
    }

    @Override
    public Child readUtf16(Utf16JsonReader reader) {
      return child(reader.readString());
    }

    @Override
    public Child readUtf8(Utf8JsonReader reader) {
      return child(reader.readString());
    }

    private static Child child(String text) {
      Child value = new Child();
      value.value = text;
      return value;
    }
  }

  public static final class ChildCodecB extends ChildCodecA {}

  private static final class HiddenChildCodecA extends ChildCodecA {}

  private static final class HiddenChildCodecB extends ChildCodecA {}

  public abstract static class DirectTerminalCodec
      implements JsonValueCodec<LocalTerminal>, DirectUnboxedValueCodec {
    private final String prefix;

    DirectTerminalCodec(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public void writeString(StringJsonWriter writer, LocalTerminal value) {
      writer.writeString(prefix + value.value);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, LocalTerminal value) {
      writer.writeString(prefix + value.value);
    }

    @Override
    public LocalTerminal readLatin1(Latin1JsonReader reader) {
      return terminal(reader.readString());
    }

    @Override
    public LocalTerminal readUtf16(Utf16JsonReader reader) {
      return terminal(reader.readString());
    }

    @Override
    public LocalTerminal readUtf8(Utf8JsonReader reader) {
      return terminal(reader.readString());
    }

    @Override
    public Class<?> carrierType() {
      return LocalTerminal.class;
    }

    @Override
    public Object readLatin1Carrier(Latin1JsonReader reader) {
      return readLatin1(reader);
    }

    @Override
    public Object readUtf16Carrier(Utf16JsonReader reader) {
      return readUtf16(reader);
    }

    @Override
    public Object readUtf8Carrier(Utf8JsonReader reader) {
      return readUtf8(reader);
    }

    @Override
    public void writeStringCarrier(StringJsonWriter writer, Object carrier) {
      writeString(writer, (LocalTerminal) carrier);
    }

    @Override
    public void writeUtf8Carrier(Utf8JsonWriter writer, Object carrier) {
      writeUtf8(writer, (LocalTerminal) carrier);
    }

    private LocalTerminal terminal(String value) {
      return new LocalTerminal(value.substring(prefix.length()));
    }
  }

  public static final class DirectTerminalCodecA extends DirectTerminalCodec {
    DirectTerminalCodecA() {
      super("a:");
    }

    @Override
    public Method readCarrierMethod() {
      return publicMethod(DirectTerminalCodecA.class, "read", JsonReader.class);
    }

    @Override
    public Method writeCarrierMethod() {
      return publicMethod(
          DirectTerminalCodecA.class, "write", JsonWriter.class, LocalTerminal.class);
    }

    public static LocalTerminal read(JsonReader reader) {
      return new LocalTerminal(reader.readString().substring(2));
    }

    public static void write(JsonWriter writer, LocalTerminal value) {
      writer.writeString("a:" + value.value);
    }
  }

  public static final class DirectTerminalCodecB extends DirectTerminalCodec {
    DirectTerminalCodecB() {
      super("b:");
    }

    @Override
    public Method readCarrierMethod() {
      return publicMethod(DirectTerminalCodecB.class, "read", JsonReader.class);
    }

    @Override
    public Method writeCarrierMethod() {
      return publicMethod(
          DirectTerminalCodecB.class, "write", JsonWriter.class, LocalTerminal.class);
    }

    public static LocalTerminal read(JsonReader reader) {
      return new LocalTerminal(reader.readString().substring(2));
    }

    public static void write(JsonWriter writer, LocalTerminal value) {
      writer.writeString("b:" + value.value);
    }
  }

  public static final class StatefulChildCodec extends ChildCodecA {
    private final String prefix;

    public StatefulChildCodec(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public void writeString(StringJsonWriter writer, Child value) {
      writer.writeString(prefix + value.value);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Child value) {
      writer.writeString(prefix + value.value);
    }

    @Override
    public Child readLatin1(Latin1JsonReader reader) {
      return childWithoutPrefix(reader.readString());
    }

    @Override
    public Child readUtf16(Utf16JsonReader reader) {
      return childWithoutPrefix(reader.readString());
    }

    @Override
    public Child readUtf8(Utf8JsonReader reader) {
      return childWithoutPrefix(reader.readString());
    }

    private Child childWithoutPrefix(String text) {
      Child value = new Child();
      value.value = text.substring(prefix.length());
      return value;
    }
  }

  public static final class SiblingTransparentCodec extends AbstractJsonValueCodec<SiblingValue>
      implements TransparentUnboxedValueCodec {
    private final JsonTypeInfo valueTypeInfo;

    public SiblingTransparentCodec(JsonTypeInfo valueTypeInfo) {
      this.valueTypeInfo = valueTypeInfo;
    }

    @Override
    public JsonTypeInfo valueTypeInfo() {
      return valueTypeInfo;
    }

    @Override
    public Object constructCarrier(JsonReader reader, Object value) {
      return construct(value);
    }

    @Override
    public Object extractValue(Object carrier) {
      return extract((SiblingCarrier) carrier);
    }

    @Override
    public Method[] constructMethods() {
      return new Method[] {method("construct", Object.class)};
    }

    @Override
    public int[] constructBoxBytes() {
      return new int[] {0};
    }

    @Override
    public Method[] extractMethods() {
      return new Method[] {method("extract", SiblingCarrier.class)};
    }

    @Override
    public void write(JsonWriter writer, SiblingValue value) {
      writer.writeNull();
    }

    @Override
    public SiblingValue read(JsonReader reader) {
      return null;
    }

    @Override
    public Class<?> carrierType() {
      return SiblingCarrier.class;
    }

    @Override
    public Object readLatin1Carrier(Latin1JsonReader reader) {
      return null;
    }

    @Override
    public Object readUtf16Carrier(Utf16JsonReader reader) {
      return null;
    }

    @Override
    public Object readUtf8Carrier(Utf8JsonReader reader) {
      return null;
    }

    @Override
    public void writeStringCarrier(StringJsonWriter writer, Object carrier) {
      writer.writeNull();
    }

    @Override
    public void writeUtf8Carrier(Utf8JsonWriter writer, Object carrier) {
      writer.writeNull();
    }

    public static SiblingCarrier construct(Object value) {
      return (SiblingCarrier) value;
    }

    public static Object extract(SiblingCarrier carrier) {
      return carrier;
    }

    private static Method method(String name, Class<?>... parameterTypes) {
      try {
        return SiblingTransparentCodec.class.getMethod(name, parameterTypes);
      } catch (NoSuchMethodException e) {
        throw new AssertionError(e);
      }
    }
  }

  public static final class Unrelated {}

  public static final class OtherUnrelated {}

  @JsonMixin(target = Child.class)
  @JsonCodec(ChildCodecA.class)
  public abstract static class ChildCodecMixin {}

  @JsonMixin(target = Unrelated.class)
  public abstract static class UnrelatedMixin {}

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
