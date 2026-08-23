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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.codec.AbstractJsonValueCodec;
import org.apache.fory.json.codec.CompositeJsonCodec;
import org.apache.fory.json.codec.JsonObjectModel;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.ScalarCodecs;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.ExactTypeRequiredException;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.resolver.UnsupportedJsonTypeException;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.Types;
import org.testng.annotations.Test;

public class JsonModuleTest {
  @Test
  public void installsOnceAndFreezesContext() {
    AtomicInteger installs = new AtomicInteger();
    ModuleContext[] retained = new ModuleContext[1];
    ForyJsonModule module =
        context -> {
          installs.incrementAndGet();
          retained[0] = context;
          context.registerCodec(Text.class, new TextCodec("module:"));
        };
    ForyJson json = ForyJson.builder().withModule(module).withModule(module).build();
    assertEquals(installs.get(), 1);
    assertEquals(json.toJson(new Text("value")), "\"module:value\"");
    assertThrows(
        IllegalStateException.class,
        () -> retained[0].registerCodec(Text.class, new TextCodec("late:")));
  }

  @Test
  public void installationIsTransactional() {
    AtomicBoolean fail = new AtomicBoolean(true);
    ForyJsonModule module =
        context -> {
          context.registerCodec(Text.class, new TextCodec("module:"));
          if (fail.getAndSet(false)) {
            throw new IllegalStateException("failed install");
          }
        };
    ForyJsonBuilder builder = ForyJson.builder().withModule(module);
    assertThrows(IllegalStateException.class, builder::build);
    ForyJson json = builder.build();
    assertEquals(json.toJson(new Text("value")), "\"module:value\"");
  }

  @Test
  public void applicationExactCodecWins() {
    ForyJsonModule module = context -> context.registerCodec(Text.class, new TextCodec("module:"));
    ForyJson json =
        ForyJson.builder()
            .withModule(module)
            .registerCodec(Text.class, new TextCodec("application:"))
            .build();
    assertEquals(json.toJson(new Text("value")), "\"application:value\"");
  }

  @Test
  public void semanticPrimitiveUsesModuleCodec() {
    JsonCodecFactory factory =
        (type, resolver, runtimeType) ->
            type.getRawType() == int.class
                    && type.getTypeExtMeta() != null
                    && type.getTypeExtMeta().typeId() == Types.UINT32
                ? UnsignedIntCodec.INSTANCE
                : null;
    ForyJson json =
        ForyJson.builder().withModule(context -> context.registerCodecFactory(factory)).build();
    TypeRef<Integer> unsignedInt =
        TypeRef.of(int.class, TypeExtMeta.of(Types.UINT32, false, false));

    assertEquals(json.toJson(-1, unsignedInt), "4294967295");
    assertEquals(json.toJsonBytes(-1, unsignedInt), "4294967295".getBytes(StandardCharsets.UTF_8));
    assertEquals(json.fromJson("4294967295", unsignedInt), Integer.valueOf(-1));
    assertEquals(
        json.fromJson("4294967295".getBytes(StandardCharsets.UTF_8), unsignedInt),
        Integer.valueOf(-1));

    assertThrows(
        IllegalArgumentException.class,
        () -> ForyJson.builder().registerCodec(int.class, ScalarCodecs.IntCodec.PRIMITIVE));
  }

  @Test
  public void languageModelOwnsAnnotatedType() {
    JsonCodecFactory factory =
        (type, resolver, runtimeType) ->
            type.getRawType() == ModuleObject.class
                ? resolver.createObjectCodec(
                    type, JsonObjectModel.fixedInstance(ModuleObject.INSTANCE))
                : null;
    ForyJson json =
        ForyJson.builder().withModule(context -> context.registerCodecFactory(factory)).build();

    assertEquals(json.toJson(ModuleObject.INSTANCE, ModuleObject.class), "{}");
    assertSame(json.fromJson("{}", ModuleObject.class), ModuleObject.INSTANCE);
    assertThrows(
        ForyJsonException.class,
        () -> ForyJson.builder().build().toJson(ModuleObject.INSTANCE, ModuleObject.class));
  }

  @Test
  public void hostedFactoryDefersRawSemanticType() throws Exception {
    AtomicInteger rawAttempts = new AtomicInteger();
    JsonCodecFactory factory =
        (type, resolver, runtimeType) -> {
          if (type.getRawType() != SemanticLeaf.class) {
            return null;
          }
          if (!type.hasTypeExtMeta()) {
            rawAttempts.incrementAndGet();
            throw new ExactTypeRequiredException("SemanticLeaf requires an exact occurrence");
          }
          return ScalarCodecs.StringCodec.INSTANCE;
        };
    ForyJson configured =
        ForyJson.builder().withModule(context -> context.registerCodecFactory(factory)).build();
    Constructor<JsonSharedRegistry> constructor =
        JsonSharedRegistry.class.getDeclaredConstructor(
            JsonConfig.class, ExecutorService.class, boolean.class);
    constructor.setAccessible(true);
    JsonTypeResolver resolver =
        new JsonTypeResolver(constructor.newInstance(configured.config(), null, true));

    assertEquals(resolver.generateHostedCodecs(SemanticLeaf.class).size(), 0);
    assertEquals(rawAttempts.get(), 1);
    assertEquals(resolver.generateHostedCodecs(SemanticLeaf.class).size(), 0);
    assertEquals(rawAttempts.get(), 2);

    TypeRef<SemanticLeaf> exactType =
        TypeRef.of(SemanticLeaf.class, TypeExtMeta.of(Types.UNKNOWN, false, false));
    JsonTypeInfo exactInfo = resolver.getTypeInfo(exactType);
    assertSame(exactInfo.stringWriter(), ScalarCodecs.StringCodec.INSTANCE);
  }

  @Test
  public void duplicateModuleKeyFails() {
    ForyJsonModule first = new KeyedModule("same");
    ForyJsonModule second = new KeyedModule("same");
    ForyJsonBuilder builder = ForyJson.builder().withModule(first).withModule(second);
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void conflictingFactoriesIgnoreOrder() {
    ForyJsonModule first = new FactoryModule("first", new TextCodec("first:"), false);
    ForyJsonModule second = new FactoryModule("second", null, true);
    ForyJson left = ForyJson.builder().withModule(first).withModule(second).build();
    ForyJson right = ForyJson.builder().withModule(second).withModule(first).build();
    assertThrows(ForyJsonException.class, () -> left.toJson(new Family("value")));
    assertThrows(ForyJsonException.class, () -> right.toJson(new Family("value")));
  }

  @Test
  public void recursiveCompositeRollsBack() {
    AtomicBoolean fail = new AtomicBoolean(true);
    AtomicInteger creations = new AtomicInteger();
    JsonCodecFactory factory =
        (type, resolver, runtimeType) -> {
          if (type.getRawType() != RecursiveValue.class) {
            return null;
          }
          creations.incrementAndGet();
          return new RecursiveCodec(fail);
        };
    ForyJsonModule module = context -> context.registerCodecFactory(factory);
    ForyJson json = ForyJson.builder().withModule(module).withCodegen(false).build();
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"next\":null}", RecursiveValue.class));
    RecursiveValue value = json.fromJson("{\"next\":{\"next\":null}}", RecursiveValue.class);
    assertEquals(json.toJson(value), "{\"next\":{\"next\":null}}");
    assertEquals(creations.get(), 2);
  }

  @Test
  public void runtimeCompositeRollsBack() {
    AtomicBoolean fail = new AtomicBoolean(true);
    AtomicInteger creations = new AtomicInteger();
    List<Boolean> runtimeTypes = new ArrayList<>();
    JsonCodecFactory factory =
        (type, resolver, runtimeType) -> {
          if (type.getRawType() != RecursiveValue.class) {
            return null;
          }
          creations.incrementAndGet();
          runtimeTypes.add(runtimeType);
          return new RecursiveCodec(fail);
        };
    ForyJson json =
        ForyJson.builder()
            .withModule(context -> context.registerCodecFactory(factory))
            .withCodegen(false)
            .build();

    RecursiveValue value = new RecursiveValue(new RecursiveValue(null));
    assertThrows(ForyJsonException.class, () -> json.toJson(value));
    assertEquals(json.toJson(value), "{\"next\":{\"next\":null}}");
    assertEquals(creations.get(), 2);

    // A dynamic write binding must not authorize or populate a declared read binding.
    RecursiveValue decoded = json.fromJson("{\"next\":{\"next\":null}}", RecursiveValue.class);
    assertEquals(json.toJson(decoded), "{\"next\":{\"next\":null}}");
    assertEquals(creations.get(), 3);
    assertEquals(runtimeTypes, Arrays.asList(true, true, false));
  }

  @Test
  public void runtimeCompositeReverseRecursion() {
    List<Class<?>> creations = new ArrayList<>();
    List<Boolean> runtimeTypes = new ArrayList<>();
    JsonCodecFactory factory =
        (type, resolver, runtimeType) -> {
          Class<?> rawType = type.getRawType();
          boolean exactLayer = rawType == RuntimeLayerA.class && type.hasTypeExtMeta();
          Class<?> child =
              exactLayer
                  ? null
                  : rawType == RuntimeLayerA.class
                      ? RuntimeLayerB.class
                      : rawType == RuntimeLayerB.class
                          ? RuntimeLayerC.class
                          : rawType == RuntimeLayerC.class ? RuntimeLayerA.class : null;
          if (child == null && !exactLayer) {
            return null;
          }
          creations.add(rawType);
          runtimeTypes.add(runtimeType);
          return new RuntimeLayerCodec(child);
        };
    ForyJson json =
        ForyJson.builder()
            .withModule(context -> context.registerCodecFactory(factory))
            .withCodegen(false)
            .build();

    assertEquals(json.toJson(new RuntimeLayerA()), "null");
    assertEquals(
        creations,
        Arrays.asList(
            RuntimeLayerA.class, RuntimeLayerB.class, RuntimeLayerC.class, RuntimeLayerA.class));
    assertEquals(runtimeTypes, Arrays.asList(true, false, false, false));
  }

  @Test
  public void runtimeObjectModelStaysWriteOnly() {
    List<Boolean> runtimeTypes = new ArrayList<>();
    JsonCodecFactory factory =
        (type, resolver, runtimeType) -> {
          if (type.getRawType() != RuntimeFixedValue.class) {
            return null;
          }
          runtimeTypes.add(runtimeType);
          return resolver.createObjectCodec(
              type,
              JsonObjectModel.fixedInstance(
                  runtimeType ? RuntimeFixedValue.RUNTIME : RuntimeFixedValue.DECLARED));
        };
    ForyJson json =
        ForyJson.builder()
            .withModule(context -> context.registerCodecFactory(factory))
            .withConcurrencyLevel(1)
            .withCodegen(false)
            .build();

    assertEquals(json.toJson(RuntimeFixedValue.RUNTIME), "{}");
    assertSame(json.fromJson("{}", RuntimeFixedValue.class), RuntimeFixedValue.DECLARED);
    assertEquals(runtimeTypes, Arrays.asList(true, false));
  }

  @Test
  public void runtimeObjectModelGeneratesCapabilities() throws Exception {
    List<Boolean> runtimeTypes = new ArrayList<>();
    JsonCodecFactory factory =
        (type, resolver, runtimeType) -> {
          if (type.getRawType() != RuntimeGeneratedValue.class) {
            return null;
          }
          runtimeTypes.add(runtimeType);
          return resolver.createObjectCodec(type, runtimeGeneratedModel());
        };
    ForyJson json =
        ForyJson.builder()
            .withModule(context -> context.registerCodecFactory(factory))
            .withConcurrencyLevel(1)
            .withAsyncCompilation(false)
            .build();

    RuntimeGeneratedValue value = new RuntimeGeneratedValue();
    value.setValue("runtime");
    assertEquals(json.toJson(value), "{\"value\":\"runtime\"}");
    JsonTypeResolver resolver = JsonTestSupport.currentTypeResolver(json);
    JsonTypeInfo typeInfo = JsonTestSupport.runtimeTypeInfo(json, RuntimeGeneratedValue.class);
    assertGeneratedCapabilities(resolver, typeInfo);

    RuntimeGeneratedValue decoded =
        json.fromJson("{\"value\":\"declared\"}", RuntimeGeneratedValue.class);
    assertEquals(decoded.getValue(), "declared");
    assertEquals(runtimeTypes, Arrays.asList(true, false));
  }

  @Test
  public void defaultRuntimeObjectStaysCanonical() {
    ForyJson json = ForyJson.builder().withConcurrencyLevel(1).withAsyncCompilation(false).build();
    RuntimeGeneratedValue value = new RuntimeGeneratedValue();
    value.setValue("runtime");
    assertEquals(json.toJson(value), "{\"value\":\"runtime\"}");
    assertEquals(
        json.fromJson("{\"value\":\"declared\"}", RuntimeGeneratedValue.class).getValue(),
        "declared");

    JsonTypeResolver resolver = JsonTestSupport.currentTypeResolver(json);
    JsonTypeInfo runtime = JsonTestSupport.runtimeTypeInfo(json, RuntimeGeneratedValue.class);
    JsonTypeInfo declared =
        resolver.getTypeInfo(RuntimeGeneratedValue.class, RuntimeGeneratedValue.class);
    assertSame(runtime, declared);
    assertGeneratedCapabilities(resolver, runtime);
  }

  private static void assertGeneratedCapabilities(
      JsonTypeResolver resolver, JsonTypeInfo typeInfo) {
    ObjectCodec<?> owner = resolver.canonicalObjectCodec(typeInfo);
    assertNotNull(owner);
    assertNotSame(typeInfo.stringWriter(), owner);
    assertNotSame(typeInfo.utf8Writer(), owner);
    assertNotSame(typeInfo.latin1Reader(), owner);
    assertNotSame(typeInfo.utf16Reader(), owner);
    assertNotSame(typeInfo.utf8Reader(), owner);
  }

  private static JsonObjectModel runtimeGeneratedModel() {
    try {
      Constructor<RuntimeGeneratedValue> constructor = RuntimeGeneratedValue.class.getConstructor();
      Method getter = RuntimeGeneratedValue.class.getMethod("getValue");
      Method setter = RuntimeGeneratedValue.class.getMethod("setValue", String.class);
      return new JsonObjectModel(
          constructor,
          null,
          new String[0],
          new Method[0],
          new Method[0],
          new int[0],
          new boolean[0],
          new TypeRef<?>[0],
          new String[] {"value"},
          new Method[] {getter},
          new Method[] {setter},
          new TypeRef<?>[] {TypeRef.of(String.class)});
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static final class KeyedModule implements ForyJsonModule {
    private final String key;

    private KeyedModule(String key) {
      this.key = key;
    }

    @Override
    public String moduleKey() {
      return key;
    }

    @Override
    public void install(ModuleContext context) {}
  }

  private static final class FactoryModule implements ForyJsonModule {
    private final String key;
    private final JsonValueCodec<?> codec;
    private final boolean reject;

    private FactoryModule(String key, JsonValueCodec<?> codec, boolean reject) {
      this.key = key;
      this.codec = codec;
      this.reject = reject;
    }

    @Override
    public String moduleKey() {
      return key;
    }

    @Override
    public void install(ModuleContext context) {
      context.registerCodecFactory(
          new JsonCodecFactory() {
            @Override
            public JsonValueCodec<?> create(
                TypeRef<?> type, JsonTypeResolver resolver, boolean runtimeType) {
              if (type.getRawType() != Family.class) {
                return null;
              }
              if (reject) {
                throw new UnsupportedJsonTypeException("rejected by " + key);
              }
              return codec;
            }

            @Override
            public String factoryKey() {
              return key;
            }
          });
    }
  }

  private static final class TextCodec extends AbstractJsonValueCodec<Text> {
    private final String prefix;

    private TextCodec(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public void write(JsonWriter writer, Text value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(prefix + value.value);
      }
    }

    @Override
    public Text read(JsonReader reader) {
      return reader.tryReadNullToken() ? null : new Text(reader.readString());
    }
  }

  private static final class UnsignedIntCodec extends AbstractJsonValueCodec<Integer> {
    private static final UnsignedIntCodec INSTANCE = new UnsignedIntCodec();

    @Override
    public void write(JsonWriter writer, Integer value) {
      writer.writeUnsignedInt(value.intValue());
    }

    @Override
    public Integer read(JsonReader reader) {
      return reader.readUnsignedInt();
    }
  }

  private static final class RecursiveCodec implements CompositeJsonCodec<RecursiveValue> {
    private final AtomicBoolean fail;
    private JsonTypeInfo self;

    private RecursiveCodec(AtomicBoolean fail) {
      this.fail = fail;
    }

    @Override
    public void resolveTypes(TypeRef<?> type, JsonTypeResolver resolver) {
      self = resolver.getTypeInfo(type.getType(), type.getRawType());
      if (fail.getAndSet(false)) {
        throw new ForyJsonException("forced recursive resolution failure");
      }
    }

    @Override
    public void writeString(StringJsonWriter writer, RecursiveValue value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writer.writeObjectStart();
      writer.writeFieldName("next");
      self.stringWriter().writeString(writer, value.next);
      writer.writeObjectEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, RecursiveValue value) {
      if (value == null) {
        writer.writeNull();
        return;
      }
      writer.writeObjectStart();
      writer.writeFieldName("next");
      self.utf8Writer().writeUtf8(writer, value.next);
      writer.writeObjectEnd();
    }

    @Override
    public RecursiveValue readLatin1(Latin1JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('{');
      expectNext(reader.readFieldName());
      reader.expectNextToken(':');
      RecursiveValue result =
          new RecursiveValue((RecursiveValue) self.latin1Reader().readLatin1(reader));
      expectEnd(reader.consumeNextCommaOrEndObject());
      reader.exitDepth();
      return result;
    }

    @Override
    public RecursiveValue readUtf16(Utf16JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('{');
      expectNext(reader.readFieldName());
      reader.expectNextToken(':');
      RecursiveValue result =
          new RecursiveValue((RecursiveValue) self.utf16Reader().readUtf16(reader));
      expectEnd(reader.consumeNextCommaOrEndObject());
      reader.exitDepth();
      return result;
    }

    @Override
    public RecursiveValue readUtf8(Utf8JsonReader reader) {
      if (reader.tryReadNullToken()) {
        return null;
      }
      reader.enterDepth();
      reader.expectNextToken('{');
      expectNext(reader.readFieldName());
      reader.expectNextToken(':');
      RecursiveValue result =
          new RecursiveValue((RecursiveValue) self.utf8Reader().readUtf8(reader));
      expectEnd(reader.consumeNextCommaOrEndObject());
      reader.exitDepth();
      return result;
    }

    private static void expectNext(String name) {
      if (!"next".equals(name)) {
        throw new ForyJsonException("Expected recursive next property");
      }
    }

    private static void expectEnd(boolean hasMore) {
      if (hasMore) {
        throw new ForyJsonException("Expected one recursive property");
      }
    }
  }

  private static final class RuntimeLayerCodec extends AbstractJsonValueCodec<Object>
      implements CompositeJsonCodec<Object> {
    private final Class<?> child;

    private RuntimeLayerCodec(Class<?> child) {
      this.child = child;
    }

    @Override
    public void resolveTypes(TypeRef<?> type, JsonTypeResolver resolver) {
      if (child == null) {
        return;
      }
      resolver.getTypeInfo(child, child);
      if (child == RuntimeLayerA.class) {
        resolver.getTypeInfo(
            TypeRef.of(
                RuntimeLayerA.class, TypeExtMeta.of(Types.UNKNOWN, false, true, false, false)));
      }
    }

    @Override
    public void write(JsonWriter writer, Object value) {
      writer.writeNull();
    }

    @Override
    public Object read(JsonReader reader) {
      reader.readNull();
      return null;
    }
  }

  private static final class Text {
    private final String value;

    private Text(String value) {
      this.value = value;
    }
  }

  private static final class Family {
    private final String value;

    private Family(String value) {
      this.value = value;
    }
  }

  private static final class RecursiveValue {
    private final RecursiveValue next;

    private RecursiveValue(RecursiveValue next) {
      this.next = next;
    }
  }

  private static final class SemanticLeaf {}

  @JsonType
  private static final class ModuleObject {
    private static final ModuleObject INSTANCE = new ModuleObject();

    private ModuleObject() {}
  }

  private static final class RuntimeLayerA {}

  private static final class RuntimeLayerB {}

  private static final class RuntimeLayerC {}

  private static final class RuntimeFixedValue {
    private static final RuntimeFixedValue RUNTIME = new RuntimeFixedValue();
    private static final RuntimeFixedValue DECLARED = new RuntimeFixedValue();

    private RuntimeFixedValue() {}
  }

  public static final class RuntimeGeneratedValue {
    private String value;

    public RuntimeGeneratedValue() {}

    public String getValue() {
      return value;
    }

    public void setValue(String value) {
      this.value = value;
    }
  }
}
