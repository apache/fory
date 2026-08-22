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

import static org.apache.fory.json.JsonTestSupport.nullCodec;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.fail;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonIgnore;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.codec.AbstractJsonValueCodec;
import org.apache.fory.json.codec.JsonObjectModel;
import org.apache.fory.json.codec.TransparentNullCodec;
import org.apache.fory.json.codec.TransparentUnboxedValueCodec;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonCreatorInfo;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonCreatorTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonCreatorTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void propertyListConstructor() {
    ForyJson json = newJson();
    User value = json.fromJson("{\"name\":\"alice\",\"id\":7}", User.class);
    assertEquals(value.id, 7L);
    assertEquals(value.name, "alice");
    assertEquals(json.toJson(value), "{\"id\":7,\"name\":\"alice\"}");
    User utf16 = json.fromJson("{\"name\":\"你好\",\"id\":8}", User.class);
    assertEquals(utf16.name, "你好");
    User utf8 =
        json.fromJson("{\"name\":\"你好\",\"id\":9}".getBytes(StandardCharsets.UTF_8), User.class);
    assertEquals(utf8.id, 9L);
    assertEquals(utf8.name, "你好");
  }

  @Test
  public void deferredTransparentNullCarrierResolution() throws Exception {
    ForyJson json =
        newJsonBuilder()
            .registerCodec(
                NullCarrier.class,
                (type, resolver, runtimeType) ->
                    new DeferredNullCarrierCodec(resolver.getTypeInfo(String.class, String.class)))
            .build();
    JsonTypeResolver resolver = JsonTestSupport.currentTypeResolver(json);
    JsonCreatorFieldInfo deferred =
        new JsonCreatorFieldInfo(
            "deferred", 1, TypeRef.of(NullCarrier.class), String.class, null, null, null, true);
    JsonCreatorInfo creator =
        new JsonCreatorInfo(
            DeferredCarrierOwner.class,
            DeferredCarrierOwner.class.getConstructor(String.class),
            new JsonCreatorFieldInfo[] {deferred},
            new Object[1],
            null);
    creator.resolveTypes(resolver);
  }

  @Test
  public void languageModelCreatorMapping() throws Exception {
    Executable listCreator = LanguageList.class.getConstructor(String.class);
    Method listGetter = LanguageList.class.getMethod("getOutput");
    ForyJson listJson =
        newJsonBuilder()
            .registerCodec(
                LanguageList.class,
                (type, resolver, runtimeType) ->
                    resolver.createObjectCodec(
                        type, languageModel(listCreator, "source", listGetter)))
            .build();
    LanguageList list = listJson.fromJson("{\"output\":\"list\"}", LanguageList.class);
    assertEquals(list.output, "list");
    assertEquals(listJson.toJson(list, LanguageList.class), "{\"output\":\"list\"}");

    Executable parameterCreator = LanguageParameter.class.getConstructor(String.class);
    Method parameterGetter = LanguageParameter.class.getMethod("getOutput");
    ForyJson parameterJson =
        newJsonBuilder()
            .registerCodec(
                LanguageParameter.class,
                (type, resolver, runtimeType) ->
                    resolver.createObjectCodec(
                        type, languageModel(parameterCreator, "source", parameterGetter)))
            .build();
    LanguageParameter parameter =
        parameterJson.fromJson("{\"wire_value\":\"parameter\"}", LanguageParameter.class);
    assertEquals(parameter.output, "parameter");
    assertEquals(
        parameterJson.toJson(parameter, LanguageParameter.class), "{\"wire_value\":\"parameter\"}");

    Executable factoryCreator = LanguageFactory.class.getMethod("create", String.class);
    Method factoryGetter = LanguageFactory.class.getMethod("getOutput");
    ForyJson factoryJson =
        newJsonBuilder()
            .registerCodec(
                LanguageFactory.class,
                (type, resolver, runtimeType) ->
                    resolver.createObjectCodec(
                        type, languageModel(factoryCreator, "source", factoryGetter)))
            .build();
    LanguageFactory factory =
        factoryJson.fromJson("{\"output\":\"factory\"}", LanguageFactory.class);
    assertEquals(factory.output, "factory");

    Executable mixinCreator = LanguageMixinTarget.class.getConstructor(String.class);
    Method mixinGetter = LanguageMixinTarget.class.getMethod("getOutput");
    ForyJson mixinJson =
        newJsonBuilder()
            .registerMixin(LanguageMixin.class)
            .registerCodec(
                LanguageMixinTarget.class,
                (type, resolver, runtimeType) ->
                    resolver.createObjectCodec(
                        type, languageModel(mixinCreator, "source", mixinGetter)))
            .build();
    LanguageMixinTarget mixin =
        mixinJson.fromJson("{\"output\":\"mixin\"}", LanguageMixinTarget.class);
    assertEquals(mixin.output, "mixin");

    Executable logicalCreator = LanguageInvocation.class.getDeclaredConstructor(String.class);
    Executable invocationCreator =
        LanguageInvocation.class.getConstructor(String.class, InvocationMarker.class);
    Method invocationGetter = LanguageInvocation.class.getMethod("getOutput");
    ForyJson invocationJson =
        newJsonBuilder()
            .registerCodec(
                LanguageInvocation.class,
                (type, resolver, runtimeType) ->
                    resolver.createObjectCodec(
                        type,
                        languageModel(
                            logicalCreator, invocationCreator, "source", invocationGetter)))
            .build();
    LanguageInvocation invocation =
        invocationJson.fromJson("{\"output\":\"bridge\"}", LanguageInvocation.class);
    assertEquals(invocation.output, "bridge");
  }

  private static JsonObjectModel languageModel(
      Executable creator, String parameterName, Method getter) {
    return languageModel(creator, creator, parameterName, getter);
  }

  private static JsonObjectModel languageModel(
      Executable creator, Executable invocationCreator, String parameterName, Method getter) {
    TypeRef<?> type = TypeRef.of(String.class);
    return new JsonObjectModel(
        creator,
        invocationCreator,
        null,
        new String[] {parameterName},
        new Method[] {getter},
        new Method[1],
        new int[] {-1},
        new boolean[] {false},
        new TypeRef<?>[] {type},
        new String[] {"output"},
        new Method[] {getter},
        new Method[1],
        new TypeRef<?>[] {type});
  }

  @Test
  public void fieldFallbacks() {
    ForyJson json = newJson();
    User spaced =
        json.fromJson("{ \n \"id\" \t : 7, \"name\" : \"alice\", \"unknown\" : 1}", User.class);
    assertEquals(spaced.id, 7L);
    assertEquals(spaced.name, "alice");

    User escaped =
        json.fromJson(
            "{\"\\u0069d\":8,\"unknown\":[1],\"name\":\"bob\"}".getBytes(StandardCharsets.UTF_8),
            User.class);
    assertEquals(escaped.id, 8L);
    assertEquals(escaped.name, "bob");

    User utf16 = json.fromJson("{\"unknown\":\"值\",\"id\" : 9,\"name\":\"你好\"}", User.class);
    assertEquals(utf16.id, 9L);
    assertEquals(utf16.name, "你好");
  }

  @Test
  public void fieldPrefixCollision() {
    ForyJson json = newJson();
    PrefixCreator latin1 = json.fromJson("{\"abcTwo\":2,\"abcOne\":1}", PrefixCreator.class);
    assertEquals(latin1.abcOne, 1);
    assertEquals(latin1.abcTwo, 2);

    PrefixCreator utf8 =
        json.fromJson(
            "{\"abcOne\":3,\"abcTwo\":4}".getBytes(StandardCharsets.UTF_8), PrefixCreator.class);
    assertEquals(utf8.abcOne, 3);
    assertEquals(utf8.abcTwo, 4);

    PrefixCreator utf16 =
        json.fromJson("{\"unknown\":\"值\",\"abcTwo\":6,\"abcOne\":5}", PrefixCreator.class);
    assertEquals(utf16.abcOne, 5);
    assertEquals(utf16.abcTwo, 6);
  }

  @Test
  public void malformedFieldNames() {
    ForyJson json = newJson();
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"id\" 7,\"name\":\"alice\"}", User.class));
    assertThrows(
        ForyJsonException.class,
        () ->
            json.fromJson(
                "{\"id:7,\"name\":\"alice\"}".getBytes(StandardCharsets.UTF_8), User.class));
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"id\" 9,\"name\":\"你好\"}", User.class));
  }

  @Test
  public void parameterLocalFactory() {
    ForyJson json = newJson();
    FactoryUser value =
        json.fromJson("{\"display_name\":\"alice\",\"user_id\":9}", FactoryUser.class);
    assertEquals(value.id, 9L);
    assertEquals(value.name, "alice");
    assertEquals(json.toJson(value), "{\"id\":9,\"name\":\"alice\"}");
  }

  @Test
  public void primitiveNullRejected() {
    ForyJson json = newJson();
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"id\":null,\"name\":\"alice\"}", User.class));
  }

  @Test
  public void customPrimitiveNullRejected() {
    ForyJson json = newJsonBuilder().registerCodec(int.class, nullCodec()).build();
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"id\":null}", CustomPrimitiveCreator.class));
    assertThrows(
        ForyJsonException.class,
        () ->
            json.fromJson(
                "{\"id\":null}".getBytes(StandardCharsets.UTF_8), CustomPrimitiveCreator.class));
  }

  @Test
  public void packagePrivateOwner() {
    ForyJson json = newJson();
    assertEquals(json.fromJson("{\"id\":3}", PackagePrivateCreator.class).id, 3);
    assertEquals(
        json.fromJson("{\"id\":4}".getBytes(StandardCharsets.UTF_8), PackagePrivateCreator.class)
            .id,
        4);
  }

  @Test
  public void rejectInvalidCreators() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"id\":1}", Multiple.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"id\":1}", UnknownProperty.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"id\":1}", TypeMismatch.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"id\":1}", BadFactory.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"id\":1}", NullFactory.class));
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"input\":1}", CreatorOnlyInclude.class));
    assertThrows(ForyJsonException.class, () -> json.fromJson("{\"id\":1}", DeadProperty.class));
  }

  @Test
  public void validateBeforeCreatorCall() {
    CountingFactory.calls = 0;
    ForyJson json = newJson();
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"id\":1,}", CountingFactory.class));
    assertEquals(CountingFactory.calls, 0);
    assertEquals(json.fromJson("{\"id\":1}", CountingFactory.class).id, 1);
    assertEquals(CountingFactory.calls, 1);
  }

  @Test
  public void wrapCreatorException() {
    try {
      newJson().fromJson("{\"id\":1}", CheckedFactory.class);
      fail("Expected creator failure");
    } catch (ForyJsonException e) {
      assertEquals(e.getCause().getMessage(), "creator failure");
    }
  }

  @Test
  public void wrapCreatorThrowable() {
    try {
      newJson().fromJson("{\"id\":1}", ThrowableFactory.class);
      fail("Expected creator failure");
    } catch (ForyJsonException e) {
      assertEquals(e.getCause().getClass(), Throwable.class);
      assertEquals(e.getCause().getMessage(), "creator throwable");
    }
  }

  @Test
  public void propagateCreatorError() {
    assertThrows(AssertionError.class, () -> newJson().fromJson("{\"id\":1}", ErrorFactory.class));
  }

  @Test
  public void hiddenCreatorParameter() {
    PublicHiddenCreator value =
        newJson().fromJson("{\"input\":{\"value\":7}}", PublicHiddenCreator.class);
    assertEquals(value.value, 7);
  }

  @Test
  public void ignoredCreatorParameter() {
    IgnoredCreator value =
        newJson().fromJson("{\"visible\":7,\"hidden\":\"secret\"}", IgnoredCreator.class);
    assertEquals(value.visible, 7);
    assertEquals(value.hidden, "secret");
    assertEquals(newJson().toJson(value), "{\"visible\":7}");
  }

  static final class HiddenArgument {
    public int value;
  }

  public static final class PublicHiddenCreator {
    public final int value;

    @JsonCreator
    public PublicHiddenCreator(@JsonProperty("input") HiddenArgument input) {
      value = input.value;
    }
  }

  public static final class IgnoredCreator {
    public final int visible;
    public final String hidden;

    @JsonCreator
    public IgnoredCreator(
        @JsonProperty("visible") int visible,
        @JsonProperty("hidden") @JsonIgnore(ignoreRead = false) String hidden) {
      this.visible = visible;
      this.hidden = hidden;
    }
  }

  public static final class User {
    public final long id;
    public final String name;

    @JsonCreator({"id", "name"})
    public User(long id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  public static final class PrefixCreator {
    public final int abcOne;
    public final int abcTwo;

    @JsonCreator({"abcOne", "abcTwo"})
    public PrefixCreator(int abcOne, int abcTwo) {
      this.abcOne = abcOne;
      this.abcTwo = abcTwo;
    }
  }

  public static final class CustomPrimitiveCreator {
    public final int id;

    @JsonCreator({"id"})
    public CustomPrimitiveCreator(int id) {
      this.id = id;
    }
  }

  static final class PackagePrivateCreator {
    public final int id;

    @JsonCreator({"id"})
    public PackagePrivateCreator(int id) {
      this.id = id;
    }
  }

  public static final class FactoryUser {
    public final long id;
    public final String name;

    private FactoryUser(long id, String name) {
      this.id = id;
      this.name = name;
    }

    @JsonCreator
    public static FactoryUser create(
        @JsonProperty("user_id") long id, @JsonProperty("display_name") String name) {
      return new FactoryUser(id, name);
    }
  }

  public static final class Multiple {
    public final int id;

    @JsonCreator({"id"})
    public Multiple(int id) {
      this.id = id;
    }

    @JsonCreator
    public static Multiple create(@JsonProperty("id") int id) {
      return new Multiple(id);
    }
  }

  public static final class UnknownProperty {
    public final int id;

    @JsonCreator({"missing"})
    public UnknownProperty(int id) {
      this.id = id;
    }
  }

  public static final class TypeMismatch {
    public final int id;

    @JsonCreator({"id"})
    public TypeMismatch(long id) {
      this.id = (int) id;
    }
  }

  public static final class BadFactory {
    public int id;

    @JsonCreator
    public static Object create(@JsonProperty("id") int id) {
      return new BadFactory();
    }
  }

  public static final class NullFactory {
    public int id;

    @JsonCreator
    public static NullFactory create(@JsonProperty("id") int id) {
      return null;
    }
  }

  public static final class CreatorOnlyInclude {
    public final int id;

    private CreatorOnlyInclude(int id) {
      this.id = id;
    }

    @JsonCreator
    public static CreatorOnlyInclude create(
        @JsonProperty(value = "input", include = JsonProperty.Include.ALWAYS) int id) {
      return new CreatorOnlyInclude(id);
    }
  }

  public static final class DeadProperty {
    public final int id;

    @JsonCreator({"id"})
    public DeadProperty(int id) {
      this.id = id;
    }

    @JsonProperty("unused")
    public void setUnused(String value) {}
  }

  public static final class CountingFactory {
    static int calls;
    public final int id;

    private CountingFactory(int id) {
      this.id = id;
    }

    @JsonCreator
    public static CountingFactory create(@JsonProperty("id") int id) {
      calls++;
      return new CountingFactory(id);
    }
  }

  public static final class CheckedFactory {
    public final int id;

    private CheckedFactory(int id) {
      this.id = id;
    }

    @JsonCreator
    public static CheckedFactory create(@JsonProperty("id") int id) throws Exception {
      throw new Exception("creator failure");
    }
  }

  public static final class ThrowableFactory {
    public final int id;

    private ThrowableFactory(int id) {
      this.id = id;
    }

    @JsonCreator
    public static ThrowableFactory create(@JsonProperty("id") int id) throws Throwable {
      throw new Throwable("creator throwable");
    }
  }

  public static final class ErrorFactory {
    public final int id;

    private ErrorFactory(int id) {
      this.id = id;
    }

    @JsonCreator
    public static ErrorFactory create(@JsonProperty("id") int id) {
      throw new AssertionError("creator error");
    }
  }

  public static final class LanguageList {
    private final String output;

    @JsonCreator({"output"})
    public LanguageList(String source) {
      output = source;
    }

    public String getOutput() {
      return output;
    }
  }

  public static final class LanguageParameter {
    private final String output;

    @JsonCreator
    public LanguageParameter(@JsonProperty("wire_value") String source) {
      output = source;
    }

    @JsonProperty("wire_value")
    public String getOutput() {
      return output;
    }
  }

  public static final class LanguageFactory {
    private final String output;

    private LanguageFactory(String output) {
      this.output = output;
    }

    @JsonCreator({"output"})
    public static LanguageFactory create(String source) {
      return new LanguageFactory(source);
    }

    public String getOutput() {
      return output;
    }
  }

  public static final class LanguageMixinTarget {
    private final String output;

    public LanguageMixinTarget(String source) {
      output = source;
    }

    public String getOutput() {
      return output;
    }
  }

  @JsonMixin(target = LanguageMixinTarget.class)
  public abstract static class LanguageMixin {
    @JsonCreator({"output"})
    LanguageMixin(String source) {}
  }

  public static final class InvocationMarker {}

  public static final class NullCarrier {}

  public static final class DeferredCarrierOwner {
    public DeferredCarrierOwner(String value) {}
  }

  private static final class DeferredNullCarrierCodec extends AbstractJsonValueCodec<NullCarrier>
      implements TransparentUnboxedValueCodec, TransparentNullCodec {
    private final org.apache.fory.json.resolver.JsonTypeInfo valueTypeInfo;

    private DeferredNullCarrierCodec(org.apache.fory.json.resolver.JsonTypeInfo valueTypeInfo) {
      this.valueTypeInfo = valueTypeInfo;
    }

    @Override
    public org.apache.fory.json.resolver.JsonTypeInfo valueTypeInfo() {
      return valueTypeInfo;
    }

    @Override
    public Object constructCarrier(JsonReader reader, Object value) {
      return value;
    }

    @Override
    public Object extractValue(Object carrier) {
      return carrier;
    }

    @Override
    public Method[] constructMethods() {
      return new Method[0];
    }

    @Override
    public int[] constructBoxBytes() {
      return new int[0];
    }

    @Override
    public Method[] extractMethods() {
      return new Method[0];
    }

    @Override
    public void write(JsonWriter writer, NullCarrier value) {
      writer.writeNull();
    }

    @Override
    public NullCarrier read(JsonReader reader) {
      reader.tryReadNull();
      return new NullCarrier();
    }

    @Override
    public Class<?> carrierType() {
      return String.class;
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
  }

  public static final class LanguageInvocation {
    private final String output;

    private LanguageInvocation(String source) {
      output = source;
    }

    @JsonCreator({"output"})
    public LanguageInvocation(String source, InvocationMarker ignored) {
      this(source);
    }

    public String getOutput() {
      return output;
    }
  }
}
