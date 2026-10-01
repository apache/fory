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
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonProperty.NullHandling;
import org.apache.fory.json.data.BeanProperties;
import org.apache.fory.json.data.BeanProperties.BooleanBean;
import org.apache.fory.json.data.BeanProperties.ConflictingTypesBean;
import org.apache.fory.json.data.BeanProperties.DuplicateGetterBean;
import org.apache.fory.json.data.BeanProperties.FinalFieldBean;
import org.apache.fory.json.data.BeanProperties.GetterBean;
import org.apache.fory.json.data.BeanProperties.GetterOnlyBean;
import org.apache.fory.json.data.BeanProperties.InheritedChild;
import org.apache.fory.json.data.BeanProperties.InheritedParent;
import org.apache.fory.json.data.BeanProperties.InvalidAccessorBean;
import org.apache.fory.json.data.BeanProperties.MixedBean;
import org.apache.fory.json.data.BeanProperties.OverloadedSetterBean;
import org.apache.fory.json.data.BeanProperties.SetterBean;
import org.apache.fory.json.data.BeanProperties.SetterOnlyBean;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class JsonPropertyTest extends ForyJsonTestModels {
  @Factory(dataProvider = "enableCodegen")
  public JsonPropertyTest(boolean codegen) {
    super(codegen);
  }

  @Test
  public void typedMethodAccessors() throws Exception {
    JsonFieldAccessor getter = JsonFieldAccessor.forGetter(GetterBean.class.getMethod("getId"));
    JsonFieldAccessor setter =
        JsonFieldAccessor.forSetter(SetterBean.class.getMethod("setId", int.class));
    // The typed operations must be implemented directly, without the base Object boxing path.
    assertEquals(
        getter.getClass().getMethod("getInt", Object.class).getDeclaringClass(), getter.getClass());
    assertEquals(
        setter.getClass().getMethod("putInt", Object.class, int.class).getDeclaringClass(),
        setter.getClass());
    assertEquals(getter.getInt(new GetterBean()), 17);
    assertEquals(getter.getObject(new GetterBean()), 17);
    SetterBean value = new SetterBean();
    setter.putInt(value, 123456);
    assertEquals(SetterBean.id(value), 123457);
    setter.putObject(value, 654321);
    assertEquals(SetterBean.id(value), 654322);
    assertEquals(SetterBean.setterCalls(value), 2);
  }

  @Test
  public void nullReadDefaults() {
    ForyJson json = newJsonBuilder().onNullRead(NullHandling.SKIP).build();
    String input = "{\"items\":null,\"optional\":null,\"number\":null,\"name\":null}";
    for (String text : new String[] {input, "{\"unicode\":\"汉\"," + input.substring(1)}) {
      for (boolean bytes : new boolean[] {false, true}) {
        NullDefaults value =
            bytes
                ? json.fromJson(text.getBytes(StandardCharsets.UTF_8), NullDefaults.class)
                : json.fromJson(text, NullDefaults.class);
        assertEquals(value.items, ImmutableList.of("default"));
        assertEquals(value.optional, Optional.of("default"));
        assertEquals(value.number, 42);
        assertEquals(value.name, null);
      }
    }
    assertEquals(
        json.fromJson("{\"items\":[\"new\"],\"items\":null}", NullDefaults.class).items,
        ImmutableList.of("new"));
  }

  @Test
  public void nullReadOverrides() {
    ForyJson json = newJsonBuilder().onNullRead(NullHandling.FAIL).build();
    assertEquals(
        json.fromJson("{\"items\":null,\"name\":null}", NullDefaults.class).items,
        ImmutableList.of("default"));
    assertThrows(
        ForyJsonException.class, () -> json.fromJson("{\"number\":null}", NullDefaults.class));
    assertEquals(json.fromJson("{\"number\":7}", NullDefaults.class).number, 7);
    assertThrows(
        IllegalArgumentException.class, () -> ForyJson.builder().onNullRead(NullHandling.DEFAULT));
    assertThrows(
        IllegalArgumentException.class,
        () -> ForyJson.builder().onContentNullRead(NullHandling.DEFAULT));
  }

  public static class NullDefaults {
    @JsonProperty(onNullRead = NullHandling.SKIP)
    public List<String> items = ImmutableList.of("default");

    public Optional<String> optional = Optional.of("default");
    public int number = 42;

    @JsonProperty(onNullRead = NullHandling.SET)
    public String name = "default";
  }

  @Test
  public void bootstrapMethodAccessors() throws Exception {
    JsonFieldAccessor getter = JsonFieldAccessor.forGetter(Character.class.getMethod("charValue"));
    assertEquals(getter.getChar('x'), 'x');
    JsonFieldAccessor setter =
        JsonFieldAccessor.forSetter(
            DecimalFormat.class.getMethod("setGroupingUsed", boolean.class));
    DecimalFormat format = new DecimalFormat();
    setter.putBoolean(format, false);
    assertEquals(format.isGroupingUsed(), false);
    setter.putBoolean(format, true);
    assertEquals(format.isGroupingUsed(), true);
  }

  @Test
  public void methodLoaderVisibility() throws Exception {
    try (PropertyClassLoader visible = new PropertyClassLoader(true);
        PropertyClassLoader isolated = new PropertyClassLoader(false)) {
      // Equal custom loaders still have independent class visibility.
      assertEquals(visible, isolated);
      for (PropertyClassLoader loader : new PropertyClassLoader[] {visible, isolated}) {
        Class<?> getterType = loader.loadClass(GetterBean.class.getName());
        Class<?> setterType = loader.loadClass(SetterBean.class.getName());
        assertSame(getterType.getClassLoader(), loader);
        assertSame(setterType.getClassLoader(), loader);
        for (int i = 0; i < 2; i++) {
          JsonFieldAccessor getter = JsonFieldAccessor.forGetter(getterType.getMethod("getId"));
          assertEquals(getter.getInt(getterType.getConstructor().newInstance()), 17);
          JsonFieldAccessor setter =
              JsonFieldAccessor.forSetter(setterType.getMethod("setId", int.class));
          Object value = setterType.getConstructor().newInstance();
          setter.putInt(value, 42);
          assertEquals(setterType.getMethod("id", setterType).invoke(null, value), 43);
        }
        // Both declaring classes and both accessor kinds share one visibility result.
        assertEquals(loader.visibilityChecks, 1);
      }
    }
  }

  @Test
  public void methodLoaderHashCollision() throws Exception {
    Map<Integer, Object> cache = loaderVisibilityCache();
    cache.clear();
    try (PropertyClassLoader visible = new PropertyClassLoader(true);
        PropertyClassLoader isolated = new PropertyClassLoader(false)) {
      Class<?> visibleType = visible.loadClass(GetterBean.class.getName());
      JsonFieldAccessor.forGetter(visibleType.getMethod("getId"));
      // Seed a colliding result deterministically instead of depending on VM hash allocation.
      cache.put(System.identityHashCode(isolated), cache.get(System.identityHashCode(visible)));
      Class<?> isolatedType = isolated.loadClass(GetterBean.class.getName());
      JsonFieldAccessor getter = JsonFieldAccessor.forGetter(isolatedType.getMethod("getId"));
      assertEquals(getter.getInt(isolatedType.getConstructor().newInstance()), 17);
      assertEquals(isolated.visibilityChecks, 1);
    } finally {
      cache.clear();
    }
  }

  @Test
  public void methodLoaderCacheReset() throws Exception {
    Map<Integer, Object> cache = loaderVisibilityCache();
    cache.clear();
    try (PropertyClassLoader loader = new PropertyClassLoader(false)) {
      Class<?> type = loader.loadClass(GetterBean.class.getName());
      Method method = type.getMethod("getId");
      JsonFieldAccessor.forGetter(method);
      int key = System.identityHashCode(loader);
      Object result = cache.get(key);
      cache.clear();
      for (int i = 1; i < 1024; i++) {
        cache.put(key + i, result);
      }
      JsonFieldAccessor.forGetter(method);
      assertEquals(cache.size(), 1024);
      cache.remove(key);
      cache.put(key + 1024, result);
      JsonFieldAccessor getter = JsonFieldAccessor.forGetter(method);
      assertTrue(cache.isEmpty());
      assertEquals(getter.getInt(type.getConstructor().newInstance()), 17);
      assertEquals(loader.visibilityChecks, 3);
    } finally {
      cache.clear();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<Integer, Object> loaderVisibilityCache() throws Exception {
    Field field = JsonFieldAccessor.class.getDeclaredField("LAMBDA_VISIBILITY");
    field.setAccessible(true);
    return (Map<Integer, Object>) field.get(null);
  }

  private static final class PropertyClassLoader extends URLClassLoader {
    private final boolean foryVisible;
    private int visibilityChecks;

    private PropertyClassLoader(boolean foryVisible) {
      super(
          new URL[] {BeanProperties.class.getProtectionDomain().getCodeSource().getLocation()},
          JsonFieldAccessor.class.getClassLoader());
      this.foryVisible = foryVisible;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof PropertyClassLoader;
    }

    @Override
    public int hashCode() {
      return 1;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        if (name.equals(JsonFieldAccessor.class.getName())) {
          visibilityChecks++;
          if (!foryVisible) {
            throw new ClassNotFoundException(name);
          }
        }
        if (name.startsWith(BeanProperties.class.getName() + "$")) {
          Class<?> type = findLoadedClass(name);
          if (type == null) {
            type = findClass(name);
          }
          if (resolve) {
            resolveClass(type);
          }
          return type;
        }
        return super.loadClass(name, resolve);
      }
    }
  }

  @Test
  public void voidGetter() throws Exception {
    VoidProperty value = new VoidProperty();
    JsonFieldAccessor getter =
        JsonFieldAccessor.forGetter(VoidProperty.class.getMethod("getValue"));
    assertEquals(getter.getObject(value), null);
    assertEquals(value.calls, 1);
  }

  public static final class VoidProperty {
    int calls;

    public void getValue() {
      calls++;
    }
  }

  @Test
  public void writePrivateGetters() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new GetterBean()), "{\"id\":17,\"name\":\"getter-field\"}");
    assertGeneratedWhenSupported(json, GetterBean.class);
  }

  @Test
  public void readPrivateSetters() {
    ForyJson json = newJson();
    SetterBean value = json.fromJson("{\"id\":4,\"name\":\"alpha\"}", SetterBean.class);
    assertEquals(SetterBean.id(value), 5);
    assertEquals(SetterBean.name(value), "set-alpha");
    assertEquals(SetterBean.setterCalls(value), 2);
    assertGeneratedWhenSupported(json, SetterBean.class);
  }

  @Test
  public void roundTripMixedProperties() {
    ForyJson json = newJson();
    String text = "{\"count\":3,\"name\":\"mixed\",\"score\":5}";
    assertEquals(json.toJson(new MixedBean()), text);
    assertEquals(json.toJson(json.fromJson(text, MixedBean.class)), text);
    assertGeneratedWhenSupported(json, MixedBean.class);
  }

  @Test
  public void writeBooleanIsGetter() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new BooleanBean()), "{\"ready\":true}");
  }

  @Test
  public void getterOnlyWrites() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new GetterOnlyBean()), "{\"computed\":6}");
    GetterOnlyBean value = json.fromJson("{\"computed\":99}", GetterOnlyBean.class);
    assertEquals(json.toJson(value), "{\"computed\":6}");
  }

  @Test
  public void setterOnlyReads() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new SetterOnlyBean()), "{}");
    SetterOnlyBean value = json.fromJson("{\"secret\":\"alpha\"}", SetterOnlyBean.class);
    assertEquals(SetterOnlyBean.received(value), "set-alpha");
  }

  @Test
  public void fieldOnlyMode() {
    ForyJson json = newJsonBuilder().withFieldMode(true).build();
    assertEquals(json.toJson(new GetterBean()), "{\"id\":7,\"name\":\"field\"}");
    assertEquals(json.toJson(new GetterOnlyBean()), "{}");
    SetterOnlyBean value = json.fromJson("{\"secret\":\"alpha\"}", SetterOnlyBean.class);
    assertEquals(SetterOnlyBean.received(value), null);
  }

  @Test
  public void rejectPropertyConflicts() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.toJson(new DuplicateGetterBean()));
    assertThrows(
        ForyJsonException.class,
        () -> json.fromJson("{\"value\":\"alpha\"}", OverloadedSetterBean.class));
    assertThrows(ForyJsonException.class, () -> json.toJson(new ConflictingTypesBean()));
  }

  @Test
  public void inheritedProperties() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new InheritedChild()), "{\"id\":4,\"name\":\"child\"}");
    InheritedChild value = json.fromJson("{\"id\":7,\"name\":\"json\"}", InheritedChild.class);
    assertEquals(InheritedParent.id(value), 8);
    assertEquals(value.name, "json");
  }

  @Test
  public void ignoreInvalidAccessors() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new InvalidAccessorBean()), "{\"value\":\"field\"}");
  }

  @Test
  public void finalFieldsStayReadOnly() {
    ForyJson json = newJson();
    assertEquals(json.toJson(new FinalFieldBean()), "{\"id\":1,\"name\":\"field\"}");
    FinalFieldBean value = json.fromJson("{\"id\":9,\"name\":\"json\"}", FinalFieldBean.class);
    assertEquals(value.id, 1);
    assertEquals(value.name, "json");
  }

  @Test
  public void propertyAnnotationAndNaming() {
    ForyJson json =
        newJsonBuilder().withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE).build();
    AnnotatedProperties value = new AnnotatedProperties();
    assertEquals(
        json.toJson(value),
        "{\"always_null\":null,\"URL_value\":\"fixed\",\"user_name\":\"alice\"}");
    AnnotatedProperties decoded =
        json.fromJson(
            "{\"URL_value\":\"changed\",\"always_null\":null,\"user_name\":\"bob\"}",
            AnnotatedProperties.class);
    assertEquals(decoded.userName, "bob");
    assertEquals(decoded.fixedName, "changed");
  }

  @Test
  public void namingExamples() {
    ForyJson json =
        newJsonBuilder().withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE).build();
    assertEquals(
        json.toJson(new NamingExamples()),
        "{\"url2_value\":1,\"url_value\":1,\"already_snake\":1,\"user_url\":1,"
            + "\"v2_api_client\":1,\"version2_fa\":1,\"x1_value\":1}");
  }

  @Test
  public void rejectAnnotationConflicts() {
    ForyJson json = newJson();
    assertThrows(ForyJsonException.class, () -> json.toJson(new NameConflict()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new IncludeConflict()));
    assertThrows(ForyJsonException.class, () -> json.toJson(new ReadOnlyInclude()));
    assertThrows(
        NullPointerException.class, () -> ForyJson.builder().withPropertyNamingStrategy(null));
    assertThrows(
        ForyJsonException.class,
        () ->
            newJsonBuilder()
                .withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
                .build()
                .toJson(new CanonicalNameConflict()));
    assertThrows(
        ForyJsonException.class,
        () -> newJsonBuilder().withFieldMode(true).build().toJson(new IneligibleAnnotation()));
    assertThrows(ForyJsonException.class, () -> newJson().toJson(new InheritedInvalidMethod()));
  }

  public static final class AnnotatedProperties {
    @JsonProperty(include = JsonProperty.Include.ALWAYS)
    public String alwaysNull;

    @JsonProperty("URL_value")
    public String fixedName = "fixed";

    public String userName = "alice";
  }

  public interface InvalidPropertyMethod {
    @JsonProperty("value")
    default int compute(int value) {
      return value;
    }
  }

  public static final class InheritedInvalidMethod implements InvalidPropertyMethod {}

  public static final class NamingExamples {
    public int URL2Value = 1;
    public int URLValue = 1;
    public int already_snake = 1;
    public int userURL = 1;
    public int v2APIClient = 1;
    public int version2FA = 1;
    public int x1Value = 1;
  }

  public static final class NameConflict {
    @JsonProperty("left")
    private String value;

    @JsonProperty("right")
    public String getValue() {
      return value;
    }
  }

  public static final class IncludeConflict {
    @JsonProperty(include = JsonProperty.Include.ALWAYS)
    private String value;

    @JsonProperty(include = JsonProperty.Include.NON_NULL)
    public String getValue() {
      return value;
    }
  }

  public static final class ReadOnlyInclude {
    @JsonProperty(include = JsonProperty.Include.ALWAYS)
    public void setValue(String value) {}
  }

  public static final class CanonicalNameConflict {
    public String userName;
    public String user_name;
  }

  public static final class IneligibleAnnotation {
    @JsonProperty("value")
    public transient String value;
  }
}
