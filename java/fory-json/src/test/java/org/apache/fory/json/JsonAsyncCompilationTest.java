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
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.data.RecursiveParent;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.serializer.StringSerializer;
import org.testng.annotations.Test;

public class JsonAsyncCompilationTest {
  @Test
  public void defaultBuilderEnablesAsync() throws Exception {
    assertTrue(asyncCompilationEnabled(ForyJson.builder().build()));
    assertFalse(asyncCompilationEnabled(ForyJson.builder().withAsyncCompilation(false).build()));
    assertFalse(asyncCompilationEnabled(ForyJson.builder().withCodegen(false).build()));
  }

  @Test
  public void capabilitiesCompileLazily() throws Exception {
    ForyJson json = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeResolver resolver = resolver(json);
    ObjectCodec<AsyncChild> owner = resolver.getObjectCodec(AsyncChild.class);
    JsonTypeInfo info = resolver.getTypeInfo(AsyncChild.class, AsyncChild.class);
    assertAllOwner(info, owner);

    AsyncChild value = child("root", 1);
    assertEquals(json.toJson(value), "{\"id\":1,\"name\":\"root\"}");
    assertNotSame(info.stringWriter(), owner);
    assertSame(info.utf8Writer(), owner);
    assertSame(info.latin1Reader(), owner);
    assertSame(info.utf16Reader(), owner);
    assertSame(info.utf8Reader(), owner);

    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8),
        "{\"id\":1,\"name\":\"root\"}");
    assertNotSame(info.utf8Writer(), owner);
    assertEquals(json.fromJson("{\"id\":2,\"name\":\"latin\"}", AsyncChild.class).id, 2);
    if (StringSerializer.isBytesBackedString()) {
      assertNotSame(info.latin1Reader(), owner);
      assertSame(info.utf16Reader(), owner);
    } else {
      assertSame(info.latin1Reader(), owner);
      assertNotSame(info.utf16Reader(), owner);
      resolver.latin1Reader(owner);
      assertNotSame(info.latin1Reader(), owner);
    }
    assertEquals(json.fromJson("{\"id\":3,\"name\":\"\u4f60\"}", AsyncChild.class).id, 3);
    assertNotSame(info.utf16Reader(), owner);
    assertEquals(
        json.fromJson(
                "{\"id\":4,\"name\":\"utf8\"}".getBytes(StandardCharsets.UTF_8), AsyncChild.class)
            .id,
        4);
    assertNotSame(info.utf8Reader(), owner);
    assertSame(resolver.getObjectCodec(AsyncChild.class), owner);
  }

  @Test
  public void asyncInstancesAreResolverLocal() throws Exception {
    ForyJson json = ForyJson.builder().build();
    JsonSharedRegistry registry = (JsonSharedRegistry) field(json, "sharedRegistry");
    JsonTypeResolver first = new JsonTypeResolver(registry);
    JsonTypeResolver second = new JsonTypeResolver(registry);
    ObjectCodec<AsyncChild> firstOwner = first.getObjectCodec(AsyncChild.class);
    ObjectCodec<AsyncChild> secondOwner = second.getObjectCodec(AsyncChild.class);

    StringWriterCodec<AsyncChild> firstWriter = awaitStringWriter(first, firstOwner);
    StringWriterCodec<AsyncChild> secondWriter = awaitStringWriter(second, secondOwner);
    assertTrue(firstWriter != firstOwner);
    assertTrue(secondWriter != secondOwner);
    assertTrue(firstWriter != secondWriter);
    assertEquals(firstWriter.getClass(), secondWriter.getClass());
    assertSame(first.getObjectCodec(AsyncChild.class), firstOwner);
    assertSame(second.getObjectCodec(AsyncChild.class), secondOwner);
  }

  @Test
  public void nestedAndRecursiveTypes() throws Exception {
    ForyJson json = ForyJson.builder().build();
    AsyncParent parent = new AsyncParent();
    parent.child = child("child", 1);
    parent.children = new LinkedHashMap<>();
    parent.children.put("nested", child("nested", 2));
    parent.list = Arrays.asList(child("listed", 3));
    String expected =
        "{\"child\":{\"id\":1,\"name\":\"child\"},\"children\":{\"nested\":{\"id\":2,\"name\":\"nested\"}},"
            + "\"list\":[{\"id\":3,\"name\":\"listed\"}]}";
    assertEquals(json.toJson(parent), expected);
    awaitStringWriter(resolver(json), resolver(json).getObjectCodec(AsyncParent.class));
    awaitStringWriter(resolver(json), resolver(json).getObjectCodec(AsyncChild.class));
    assertEquals(json.toJson(parent), expected);
    awaitUtf8Readers(json, expected, AsyncParent.class, AsyncChild.class);
    AsyncParent decoded =
        json.fromJson(expected.getBytes(StandardCharsets.UTF_8), AsyncParent.class);
    assertEquals(decoded.children.get("nested").id, 2);
    assertEquals(decoded.list.get(0).id, 3);
    assertNestedUtf8Readers(json);

    RecursiveParent recursive = new RecursiveParent();
    assertEquals(json.toJson(recursive), "{\"child\":{\"name\":\"child\"},\"name\":\"parent\"}");
  }

  @Test
  public void objectClassKeepsNaturalSemantics() {
    ForyJson json = ForyJson.builder().build();
    assertEquals(json.fromJson("7", Object.class), Long.valueOf(7));
    assertTrue(json.fromJson("[1]", Object.class) instanceof JsonArray);
    JsonObject object = (JsonObject) json.fromJson("{\"items\":[1]}", Object.class);
    assertTrue(object.get("items") instanceof JsonArray);
  }

  @Test
  public void selfRecursiveWriterUsesThis() throws Exception {
    ForyJson json = ForyJson.builder().withAsyncCompilation(false).build();
    SelfRecursive value = new SelfRecursive();
    value.id = 1;
    value.next = new SelfRecursive();
    value.next.id = 2;
    assertEquals(json.toJson(value), "{\"id\":1,\"next\":{\"id\":2}}");

    JsonTypeResolver resolver = resolver(json);
    ObjectCodec<SelfRecursive> owner = resolver.getObjectCodec(SelfRecursive.class);
    JsonTypeInfo typeInfo = resolver.getTypeInfo(SelfRecursive.class, SelfRecursive.class);
    JsonFieldInfo recursiveField = null;
    for (JsonFieldInfo field : owner.writeFields()) {
      if (field.name().equals("next")) {
        recursiveField = field;
        break;
      }
    }
    if (recursiveField == null) {
      fail("Missing recursive JSON field");
    }
    assertSame(recursiveField.writeTypeInfo(), typeInfo);
    assertSame(recursiveField.readTypeInfo(), typeInfo);
    StringWriterCodec<SelfRecursive> writer = resolver.stringWriter(owner);
    assertTrue(writer != owner);
    for (Field field : writer.getClass().getDeclaredFields()) {
      assertFalse(field.getType() == StringWriterCodec.class, field.toString());
    }
  }

  @Test
  public void independentPublication() throws Exception {
    ForyJson json = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeResolver resolver = resolver(json);
    ObjectCodec<AsyncParent> parent = resolver.getObjectCodec(AsyncParent.class);
    ObjectCodec<AsyncChild> child = resolver.getObjectCodec(AsyncChild.class);
    JsonTypeInfo childInfo = resolver.getTypeInfo(AsyncChild.class, AsyncChild.class);

    Object parentCapability = resolver.stringWriter(parent);
    assertCapabilityFields(parentCapability, StringWriterCodec.class, child, 1);
    Object childCapability = resolver.stringWriter(child);
    assertSame(childInfo.stringWriter(), childCapability);
    assertPublishedChild(parentCapability, StringWriterCodec.class, childCapability, child, 1);

    parentCapability = resolver.utf8Writer(parent);
    assertCapabilityFields(parentCapability, Utf8WriterCodec.class, child, 1);
    childCapability = resolver.utf8Writer(child);
    assertSame(childInfo.utf8Writer(), childCapability);
    assertPublishedChild(parentCapability, Utf8WriterCodec.class, childCapability, child, 1);

    parentCapability = resolver.latin1Reader(parent);
    assertCapabilityFields(parentCapability, Latin1ReaderCodec.class, child, 2);
    childCapability = resolver.latin1Reader(child);
    assertSame(childInfo.latin1Reader(), childCapability);
    assertPublishedChild(parentCapability, Latin1ReaderCodec.class, childCapability, child, 2);

    parentCapability = resolver.utf16Reader(parent);
    assertCapabilityFields(parentCapability, Utf16ReaderCodec.class, child, 2);
    childCapability = resolver.utf16Reader(child);
    assertSame(childInfo.utf16Reader(), childCapability);
    assertPublishedChild(parentCapability, Utf16ReaderCodec.class, childCapability, child, 2);

    parentCapability = resolver.utf8Reader(parent);
    assertCapabilityFields(parentCapability, Utf8ReaderCodec.class, child, 2);
    childCapability = resolver.utf8Reader(child);
    assertSame(childInfo.utf8Reader(), childCapability);
    assertPublishedChild(parentCapability, Utf8ReaderCodec.class, childCapability, child, 2);
  }

  @Test
  public void mutualPublication() throws Exception {
    ForyJson json = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeResolver resolver = resolver(json);
    ObjectCodec<MutualFirst> firstOwner = resolver.getObjectCodec(MutualFirst.class);
    ObjectCodec<MutualSecond> secondOwner = resolver.getObjectCodec(MutualSecond.class);
    JsonTypeInfo firstInfo = resolver.getTypeInfo(MutualFirst.class, MutualFirst.class);
    JsonTypeInfo secondInfo = resolver.getTypeInfo(MutualSecond.class, MutualSecond.class);

    Object first = resolver.stringWriter(firstOwner);
    assertCapabilityFields(first, StringWriterCodec.class, secondOwner, 1);
    Object second = resolver.stringWriter(secondOwner);
    assertSame(secondInfo.stringWriter(), second);
    assertMutualFields(
        firstInfo.stringWriter(), secondInfo.stringWriter(), StringWriterCodec.class);

    first = resolver.utf8Writer(firstOwner);
    assertCapabilityFields(first, Utf8WriterCodec.class, secondOwner, 1);
    second = resolver.utf8Writer(secondOwner);
    assertSame(secondInfo.utf8Writer(), second);
    assertMutualFields(firstInfo.utf8Writer(), secondInfo.utf8Writer(), Utf8WriterCodec.class);

    first = resolver.latin1Reader(firstOwner);
    assertCapabilityFields(first, Latin1ReaderCodec.class, secondOwner, 1);
    second = resolver.latin1Reader(secondOwner);
    assertSame(secondInfo.latin1Reader(), second);
    assertMutualFields(
        firstInfo.latin1Reader(), secondInfo.latin1Reader(), Latin1ReaderCodec.class);

    first = resolver.utf16Reader(firstOwner);
    assertCapabilityFields(first, Utf16ReaderCodec.class, secondOwner, 1);
    second = resolver.utf16Reader(secondOwner);
    assertSame(secondInfo.utf16Reader(), second);
    assertMutualFields(firstInfo.utf16Reader(), secondInfo.utf16Reader(), Utf16ReaderCodec.class);

    first = resolver.utf8Reader(firstOwner);
    assertCapabilityFields(first, Utf8ReaderCodec.class, secondOwner, 1);
    second = resolver.utf8Reader(secondOwner);
    assertSame(secondInfo.utf8Reader(), second);
    assertMutualFields(firstInfo.utf8Reader(), secondInfo.utf8Reader(), Utf8ReaderCodec.class);
  }

  private static void assertPublishedChild(
      Object parent,
      Class<?> fieldType,
      Object child,
      ObjectCodec<?> childOwner,
      int expectedFields)
      throws Exception {
    assertFalse(parent instanceof ObjectCodec<?>, parent.getClass().getName());
    assertTrue(child != childOwner, child.getClass().getName());
    assertCapabilityFields(parent, fieldType, child, expectedFields);
  }

  private static void assertMutualFields(Object first, Object second, Class<?> fieldType)
      throws Exception {
    assertCapabilityFields(first, fieldType, second, 1);
    assertCapabilityFields(second, fieldType, first, 1);
  }

  private static void assertCapabilityFields(
      Object owner, Class<?> fieldType, Object expected, int expectedFields) throws Exception {
    int count = 0;
    for (Field field : owner.getClass().getDeclaredFields()) {
      if (field.getType() == fieldType) {
        field.setAccessible(true);
        assertSame(field.get(owner), expected, field.toString());
        count++;
      }
    }
    assertEquals(count, expectedFields, owner.getClass().getName());
  }

  private static void assertAllOwner(JsonTypeInfo info, ObjectCodec<?> owner) {
    assertSame(info.stringWriter(), owner);
    assertSame(info.utf8Writer(), owner);
    assertSame(info.latin1Reader(), owner);
    assertSame(info.utf16Reader(), owner);
    assertSame(info.utf8Reader(), owner);
  }

  private static <T> StringWriterCodec<T> awaitStringWriter(
      JsonTypeResolver resolver, ObjectCodec<T> owner) throws InterruptedException {
    for (int i = 0; i < 200; i++) {
      StringWriterCodec<T> writer = resolver.stringWriter(owner);
      if (writer != owner) {
        return writer;
      }
      Thread.sleep(10);
    }
    fail("Timed out waiting for generated JSON string writer for " + owner.type());
    throw new IllegalStateException("unreachable");
  }

  private static void awaitUtf8Readers(ForyJson json, String input, Class<?>... types)
      throws Exception {
    JsonTypeResolver resolver = resolver(json);
    byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < 200; i++) {
      json.fromJson(bytes, AsyncParent.class);
      boolean generated = true;
      for (Class<?> type : types) {
        ObjectCodec<?> owner = resolver.getObjectCodec(type);
        generated &= resolver.getTypeInfo(type, type).utf8Reader() != owner;
      }
      if (generated) {
        return;
      }
      Thread.sleep(10);
    }
    fail("Timed out waiting for generated JSON UTF8 readers");
  }

  private static void assertNestedUtf8Readers(ForyJson json) throws Exception {
    JsonTypeResolver resolver = resolver(json);
    Object parentReader = resolver.getTypeInfo(AsyncParent.class, AsyncParent.class).utf8Reader();
    Object childReader = resolver.getTypeInfo(AsyncChild.class, AsyncChild.class).utf8Reader();
    int nestedReaders = 0;
    for (Field field : parentReader.getClass().getDeclaredFields()) {
      if (field.getType() == Utf8ReaderCodec.class) {
        field.setAccessible(true);
        assertSame(field.get(parentReader), childReader);
        nestedReaders++;
      }
    }
    assertEquals(nestedReaders, 2);
  }

  private static JsonTypeResolver resolver(ForyJson json) throws Exception {
    Object pooledState =
        ((java.util.concurrent.atomic.AtomicReference<?>) field(json, "primarySlot")).get();
    Object state = field(pooledState, "state");
    return (JsonTypeResolver) field(state, "typeResolver");
  }

  private static boolean asyncCompilationEnabled(ForyJson json) throws Exception {
    Object registry = field(json, "sharedRegistry");
    Object jitContext = field(registry, "jitContext");
    return (boolean) field(jitContext, "asyncCompilationEnabled");
  }

  private static Object field(Object owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner);
  }

  private static AsyncChild child(String name, int id) {
    AsyncChild child = new AsyncChild();
    child.id = id;
    child.name = name;
    return child;
  }

  public static final class AsyncParent {
    public AsyncChild child;
    public Map<String, AsyncChild> children;
    public List<AsyncChild> list;
  }

  public static final class AsyncChild {
    public int id;
    public String name;
  }

  public static final class SelfRecursive {
    public int id;
    public SelfRecursive next;
  }

  public static final class MutualFirst {
    public MutualSecond second;
  }

  public static final class MutualSecond {
    public MutualFirst first;
  }
}
