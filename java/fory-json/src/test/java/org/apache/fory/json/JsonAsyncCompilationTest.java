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
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fory.json.codec.BaseObjectCodec;
import org.apache.fory.json.codec.GeneratedObjectCodec;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.data.RecursiveParent;
import org.testng.annotations.Test;

public class JsonAsyncCompilationTest {
  @Test
  public void defaultBuilderEnablesAsync() throws Exception {
    assertTrue(asyncCompilationEnabled(ForyJson.builder().build()));
    assertFalse(asyncCompilationEnabled(ForyJson.builder().withAsyncCompilation(false).build()));
  }

  @Test
  public void asyncCompilationPublishesGeneratedCodec() throws Exception {
    ForyJson json = ForyJson.builder().build();
    AsyncChild value = child("root", 1);
    assertEquals(json.toJson(value), "{\"id\":1,\"name\":\"root\"}");
    awaitGenerated(json, AsyncChild.class);
    assertEquals(json.toJson(value), "{\"id\":1,\"name\":\"root\"}");
    assertEquals(json.fromJson("{\"id\":2,\"name\":\"read\"}", AsyncChild.class).name, "read");
  }

  @Test
  public void asyncUpdatesCachedNestedCodecs() throws Exception {
    ForyJson json = ForyJson.builder().build();
    AsyncParent value = parent();
    String expected =
        "{\"array\":[{\"id\":2,\"name\":\"array\"}],\"atomic\":{\"id\":6,\"name\":\"atomic\"},"
            + "\"child\":{\"id\":1,\"name\":\"child\"},\"list\":[{\"id\":3,\"name\":\"list\"}],"
            + "\"map\":{\"entry\":{\"id\":4,\"name\":\"map\"}},"
            + "\"optional\":{\"id\":5,\"name\":\"optional\"}}";
    assertEquals(json.toJson(value), expected);
    awaitGenerated(json, AsyncParent.class);
    awaitGenerated(json, AsyncChild.class);
    assertEquals(json.toJson(value), expected);
    AsyncParent read = json.fromJson(expected, AsyncParent.class);
    assertEquals(read.child.name, "child");
    assertEquals(read.array[0].name, "array");
    assertEquals(read.list.get(0).name, "list");
    assertEquals(read.map.get("entry").name, "map");
    assertEquals(read.optional.get().name, "optional");
    assertEquals(read.atomic.get().name, "atomic");
    assertCachedGeneratedChild(json, AsyncParent.class, AsyncChild.class);
  }

  @Test
  public void asyncRecursiveTypes() throws Exception {
    ForyJson json = ForyJson.builder().build();
    RecursiveParent value = new RecursiveParent();
    assertEquals(json.toJson(value), "{\"child\":{\"name\":\"child\"},\"name\":\"parent\"}");
    awaitGenerated(json, RecursiveParent.class);
    assertEquals(json.toJson(value), "{\"child\":{\"name\":\"child\"},\"name\":\"parent\"}");
  }

  @Test
  public void disabledCodegenIgnoresAsync() throws Exception {
    ForyJson json = ForyJson.builder().withCodegen(false).build();
    assertEquals(json.toJson(child("root", 1)), "{\"id\":1,\"name\":\"root\"}");
    Thread.sleep(50);
    assertFalse(json.hasGeneratedWriter(AsyncChild.class));
  }

  private static AsyncParent parent() {
    AsyncParent parent = new AsyncParent();
    parent.child = child("child", 1);
    parent.array = new AsyncChild[] {child("array", 2)};
    parent.list = Collections.singletonList(child("list", 3));
    parent.map = new LinkedHashMap<>();
    parent.map.put("entry", child("map", 4));
    parent.optional = Optional.of(child("optional", 5));
    parent.atomic = new AtomicReference<>(child("atomic", 6));
    return parent;
  }

  private static AsyncChild child(String name, int id) {
    AsyncChild child = new AsyncChild();
    child.name = name;
    child.id = id;
    return child;
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

  private static boolean asyncCompilationEnabled(ForyJson json) throws Exception {
    Object sharedRegistry = field(json, "sharedRegistry");
    Object jitContext = field(sharedRegistry, "jitContext");
    return (boolean) field(jitContext, "asyncCompilationEnabled");
  }

  private static void assertCachedGeneratedChild(
      ForyJson json, Class<?> parentType, Class<?> childType) throws Exception {
    GeneratedObjectCodec codec = generatedObjectCodec(json, parentType);
    AtomicInteger cachedChildFields = new AtomicInteger();
    scanCachedCodecs(
        codec, childType, cachedChildFields, Collections.newSetFromMap(new IdentityHashMap<>()));
    assertTrue(
        cachedChildFields.get() >= 6,
        "Expected cached generated child codecs, got " + cachedChildFields.get());
  }

  private static GeneratedObjectCodec generatedObjectCodec(ForyJson json, Class<?> type)
      throws Exception {
    Object primarySlot = ((AtomicReference<?>) field(json, "primarySlot")).get();
    Object state = field(primarySlot, "state");
    Object resolver = field(state, "typeResolver");
    BaseObjectCodec codec =
        (BaseObjectCodec)
            resolver.getClass().getMethod("getObjectCodec", Class.class).invoke(resolver, type);
    assertTrue(codec instanceof GeneratedObjectCodec);
    return (GeneratedObjectCodec) codec;
  }

  private static void scanCachedCodecs(
      Object owner, Class<?> childType, AtomicInteger cachedChildFields, java.util.Set<Object> seen)
      throws Exception {
    if (owner == null || !seen.add(owner)) {
      return;
    }
    if (owner instanceof BaseObjectCodec) {
      BaseObjectCodec codec = (BaseObjectCodec) owner;
      if (codec.type() == childType) {
        assertTrue(codec instanceof GeneratedObjectCodec, codec.getClass().getName());
        cachedChildFields.incrementAndGet();
      }
      if (!(codec instanceof GeneratedObjectCodec)) {
        return;
      }
    }
    Class<?> type = owner.getClass();
    if (!isCodecOwner(type)) {
      return;
    }
    for (Field field : allFields(type)) {
      if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
        continue;
      }
      field.setAccessible(true);
      Object value = field.get(owner);
      if (value instanceof BaseObjectCodec && ((BaseObjectCodec) value).type() == childType) {
        assertTrue(value instanceof GeneratedObjectCodec, value.getClass().getName());
        cachedChildFields.incrementAndGet();
        continue;
      }
      if (value instanceof BaseObjectCodec
          || value instanceof JsonCodec
          || isCodecOwner(value == null ? null : value.getClass())) {
        scanCachedCodecs(value, childType, cachedChildFields, seen);
      }
    }
  }

  private static boolean isCodecOwner(Class<?> type) {
    if (type == null) {
      return false;
    }
    Package pkg = type.getPackage();
    String packageName = pkg == null ? "" : pkg.getName();
    return packageName.startsWith("org.apache.fory.json.codec")
        || type.getSimpleName().contains("ForyJsonCodec");
  }

  private static java.util.List<Field> allFields(Class<?> type) {
    java.util.ArrayList<Field> fields = new java.util.ArrayList<>();
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      fields.addAll(Arrays.asList(current.getDeclaredFields()));
    }
    return fields;
  }

  private static Object field(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  public static final class AsyncParent {
    public AsyncChild[] array;
    public AtomicReference<AsyncChild> atomic;
    public AsyncChild child;
    public java.util.List<AsyncChild> list;
    public Map<String, AsyncChild> map;
    public Optional<AsyncChild> optional;
  }

  public static final class AsyncChild {
    public int id;
    public String name;
  }
}
