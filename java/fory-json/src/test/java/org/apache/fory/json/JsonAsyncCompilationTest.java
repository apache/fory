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
import static org.apache.fory.json.JsonTestSupport.primaryTypeResolver;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fory.collection.IdentityMap;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.codec.ClosedSubtypeCodec;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.codegen.JsonJITContext;
import org.apache.fory.json.data.RecursiveParent;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.CodecRegistry;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.reflect.TypeRef;
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
    JsonTypeResolver resolver = primaryTypeResolver(json);
    ObjectCodec<AsyncChild> owner = resolver.getObjectCodec(AsyncChild.class);
    JsonTypeInfo info = resolver.getTypeInfo(AsyncChild.class, AsyncChild.class);
    assertCapabilities(info, owner);

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
  public void inlineAnyReadersInstall() throws Exception {
    ControlledJson controlled = controlledJson();
    ForyJson json = controlled.json;
    AsyncInlineChild initial =
        (AsyncInlineChild) json.fromJson("{\"kind\":\"child\",\"x\":1}", AsyncInlineShape.class);
    assertEquals(initial.properties, Collections.singletonMap("x", 1));

    controlled.executor.runAll();
    JsonTypeResolver resolver = primaryTypeResolver(json);
    ClosedSubtypeCodec codec =
        (ClosedSubtypeCodec)
            resolver.getTypeInfo(AsyncInlineShape.class, AsyncInlineShape.class).latin1Reader();
    assertNotNull(((Object[]) field(codec, "inlineLatin1Readers"))[0]);
    assertNotNull(((Object[]) field(codec, "inlineUtf16Readers"))[0]);
    assertNotNull(((Object[]) field(codec, "inlineUtf8Readers"))[0]);

    AsyncInlineChild latin1 =
        (AsyncInlineChild) json.fromJson("{\"x\":2,\"kind\":\"child\"}", AsyncInlineShape.class);
    assertEquals(latin1.properties, Collections.singletonMap("x", 2));
    AsyncInlineChild utf16 =
        (AsyncInlineChild) json.fromJson("{\"键\":3,\"kind\":\"child\"}", AsyncInlineShape.class);
    assertEquals(utf16.properties, Collections.singletonMap("键", 3));
    AsyncInlineChild utf8 =
        (AsyncInlineChild)
            json.fromJson(
                "{\"kind\":\"child\",\"byte\":4}".getBytes(StandardCharsets.UTF_8),
                AsyncInlineShape.class);
    assertEquals(utf8.properties, Collections.singletonMap("byte", 4));
  }

  @Test
  public void creatorCapabilitiesInstall() throws Exception {
    ControlledJson controlled = controlledJson();
    ForyJson json = controlled.json;
    AsyncCreator value = new AsyncCreator(1, "root");
    assertEquals(json.toJson(value), "{\"id\":1,\"name\":\"root\"}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8),
        "{\"id\":1,\"name\":\"root\"}");
    assertEquals(json.fromJson("{\"id\":2,\"name\":\"latin\"}", AsyncCreator.class).id, 2);
    assertEquals(json.fromJson("{\"id\":3,\"name\":\"你好\"}", AsyncCreator.class).id, 3);
    assertEquals(
        json.fromJson(
                "{\"id\":4,\"name\":\"utf8\"}".getBytes(StandardCharsets.UTF_8), AsyncCreator.class)
            .id,
        4);
    JsonTypeResolver resolver = primaryTypeResolver(json);
    ObjectCodec<AsyncCreator> owner = resolver.getObjectCodec(AsyncCreator.class);
    if (!StringSerializer.isBytesBackedString()) {
      // Java 8 strings are char-backed, so public String reads select the UTF-16 reader and cannot
      // request Latin-1 compilation. Explicitly request that cold capability to keep this test's
      // coverage of every asynchronously installed codec independent of the JDK string layout.
      resolver.lockJIT();
      try {
        assertSame(resolver.latin1Reader(owner), owner);
      } finally {
        resolver.unlockJIT();
      }
    }
    controlled.executor.runAll();
    JsonTypeInfo info = resolver.getTypeInfo(AsyncCreator.class, AsyncCreator.class);
    assertNotSame(info.stringWriter(), owner);
    assertNotSame(info.utf8Writer(), owner);
    assertNotSame(info.latin1Reader(), owner);
    assertNotSame(info.utf16Reader(), owner);
    assertNotSame(info.utf8Reader(), owner);
    assertEquals(json.fromJson("{\"name\":\"again\",\"id\":5}", AsyncCreator.class).id, 5);
  }

  @Test
  public void asyncInstancesAreResolverLocal() throws Exception {
    ControlledJson controlled = controlledJson();
    JsonSharedRegistry registry = controlled.registry;
    JsonTypeResolver first = new JsonTypeResolver(registry);
    JsonTypeResolver second = new JsonTypeResolver(registry);
    first.lockJIT();
    ObjectCodec<AsyncChild> firstOwner;
    try {
      firstOwner = first.getObjectCodec(AsyncChild.class);
      first.getTypeInfo(AsyncChild.class, AsyncChild.class);
      assertSame(first.stringWriter(firstOwner), firstOwner);
    } finally {
      first.unlockJIT();
    }
    second.lockJIT();
    ObjectCodec<AsyncChild> secondOwner;
    try {
      secondOwner = second.getObjectCodec(AsyncChild.class);
      second.getTypeInfo(AsyncChild.class, AsyncChild.class);
      assertSame(second.stringWriter(secondOwner), secondOwner);
    } finally {
      second.unlockJIT();
    }
    assertEquals(controlled.executor.pendingTasks(), 2);
    controlled.executor.runAll();

    StringWriterCodec<AsyncChild> firstWriter = stringWriter(first, firstOwner);
    StringWriterCodec<AsyncChild> secondWriter = stringWriter(second, secondOwner);
    assertTrue(firstWriter != firstOwner);
    assertTrue(secondWriter != secondOwner);
    assertTrue(firstWriter != secondWriter);
    assertEquals(firstWriter.getClass(), secondWriter.getClass());
    first.lockJIT();
    try {
      assertSame(first.getObjectCodec(AsyncChild.class), firstOwner);
      assertSame(first.getTypeInfo(AsyncChild.class, AsyncChild.class).stringWriter(), firstWriter);
    } finally {
      first.unlockJIT();
    }
    second.lockJIT();
    try {
      assertSame(second.getObjectCodec(AsyncChild.class), secondOwner);
      assertSame(
          second.getTypeInfo(AsyncChild.class, AsyncChild.class).stringWriter(), secondWriter);
    } finally {
      second.unlockJIT();
    }
  }

  @Test
  public void asyncCompletionPublishesAllPaths() throws Exception {
    ControlledJson controlled = controlledJson();
    ForyJson json = controlled.json;
    JsonTypeResolver resolver = primaryTypeResolver(json);
    resolver.lockJIT();
    ObjectCodec<AsyncChild> owner;
    JsonTypeInfo info;
    try {
      owner = resolver.getObjectCodec(AsyncChild.class);
      info = resolver.getTypeInfo(AsyncChild.class, AsyncChild.class);
    } finally {
      resolver.unlockJIT();
    }
    AsyncChild value = child("all", 1);

    assertEquals(json.toJson(value), "{\"id\":1,\"name\":\"all\"}");
    assertSame(info.stringWriter(), owner);
    controlled.executor.runNext();
    assertNotSame(info.stringWriter(), owner);

    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "{\"id\":1,\"name\":\"all\"}");
    assertSame(info.utf8Writer(), owner);
    controlled.executor.runNext();
    assertNotSame(info.utf8Writer(), owner);

    resolver.lockJIT();
    try {
      assertSame(resolver.latin1Reader(owner), owner);
    } finally {
      resolver.unlockJIT();
    }
    assertSame(info.latin1Reader(), owner);
    controlled.executor.runNext();
    assertNotSame(info.latin1Reader(), owner);

    assertEquals(json.fromJson("{\"id\":2,\"name\":\"你\"}", AsyncChild.class).id, 2);
    assertSame(info.utf16Reader(), owner);
    controlled.executor.runNext();
    assertNotSame(info.utf16Reader(), owner);

    assertEquals(
        json.fromJson(
                "{\"id\":3,\"name\":\"utf8\"}".getBytes(StandardCharsets.UTF_8), AsyncChild.class)
            .id,
        3);
    assertSame(info.utf8Reader(), owner);
    controlled.executor.runNext();
    assertNotSame(info.utf8Reader(), owner);
    assertEquals(controlled.executor.submittedTasks(), 5);
  }

  @Test
  public void duplicateCallbacksPublish() throws Exception {
    ControlledJson controlled = controlledJson();
    JsonTypeResolver resolver = new JsonTypeResolver(controlled.registry);
    resolver.lockJIT();
    ObjectCodec<AsyncChild> owner;
    try {
      owner = resolver.getObjectCodec(AsyncChild.class);
      resolver.getTypeInfo(AsyncChild.class, AsyncChild.class);
      assertSame(resolver.stringWriter(owner), owner);
      assertSame(resolver.stringWriter(owner), owner);
    } finally {
      resolver.unlockJIT();
    }

    assertEquals(controlled.executor.submittedTasks(), 2);
    assertEquals(controlled.executor.pendingTasks(), 2);
    controlled.executor.runAll();
    assertNotSame(stringWriter(resolver, owner), owner);
  }

  @Test
  public void rolledBackReaderTasksStayIsolated() throws Exception {
    ControlledJson controlled = controlledJson();
    JsonTypeResolver resolver = primaryTypeResolver(controlled.json);
    resolver.lockJIT();
    ObjectCodec<RollbackCircle> replacementOwner;
    JsonTypeInfo replacement;
    try {
      expectThrows(
          ForyJsonException.class,
          () -> resolver.getTypeInfo(BrokenShape.class, BrokenShape.class));
      assertEquals(controlled.executor.pendingTasks(), 3);
      replacementOwner = resolver.getObjectCodec(RollbackCircle.class);
      replacement = resolver.getTypeInfo(RollbackCircle.class, RollbackCircle.class);
      assertSame(replacement.latin1Reader(), replacementOwner);
      assertSame(replacement.utf16Reader(), replacementOwner);
      assertSame(replacement.utf8Reader(), replacementOwner);
    } finally {
      resolver.unlockJIT();
    }

    // The failed subtype transaction queued parent-local Any readers against its provisional
    // slots. Completing those tasks must not mutate the replacement generation for the same class.
    controlled.executor.runAll();
    assertSame(replacement.latin1Reader(), replacementOwner);
    assertSame(replacement.utf16Reader(), replacementOwner);
    assertSame(replacement.utf8Reader(), replacementOwner);
  }

  @Test
  public void rollbackClearsCanonicalOwners() throws Exception {
    FlakyStringCodec.CONSTRUCTIONS.set(0);
    ControlledJson controlled = controlledJson();
    JsonTypeResolver resolver = new JsonTypeResolver(controlled.registry);
    Field field = JsonTypeResolver.class.getDeclaredField("canonicalObjectTypeInfos");
    field.setAccessible(true);
    IdentityMap<?, ?> canonicalOwners = (IdentityMap<?, ?>) field.get(resolver);

    expectThrows(ForyJsonException.class, () -> resolver.getObjectCodec(RollbackAfterSelf.class));
    assertEquals(canonicalOwners.size, 0);

    ObjectCodec<RollbackAfterSelf> replacement = resolver.getObjectCodec(RollbackAfterSelf.class);
    JsonTypeInfo replacementInfo =
        resolver.getTypeInfo(RollbackAfterSelf.class, RollbackAfterSelf.class);
    assertSame(replacementInfo.stringWriter(), replacement);
    assertEquals(canonicalOwners.size, 1);
  }

  @Test
  public void asyncFailureKeepsInterpretedResult() {
    ControlledExecutor executor = new ControlledExecutor();
    JsonJITContext context = new JsonJITContext(true, executor);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    int result =
        context.registerJITCallback(
            () -> 1,
            () -> {
              throw new IllegalStateException("compile failure");
            },
            new JsonJITContext.JITCallback<Integer>() {
              @Override
              public void onSuccess(Integer generated) {
                fail("Unexpected generated result");
              }

              @Override
              public void onFailure(Throwable throwable) {
                failure.set(throwable);
              }

              @Override
              public Object id() {
                return "failure";
              }
            });
    assertEquals(result, 1);
    executor.runNext();
    assertTrue(failure.get() instanceof IllegalStateException);
  }

  @Test
  public void rootAndCompletionUseLocalLock() throws Exception {
    CountDownLatch rootEntered = new CountDownLatch(1);
    CountDownLatch releaseRoot = new CountDownLatch(1);
    CodecRegistry codecs = new CodecRegistry();
    codecs.register(BlockingValue.class, new BlockingCodec(rootEntered, releaseRoot));
    ControlledJson controlled = controlledJson(codecs);
    JsonTypeResolver compilerResolver = new JsonTypeResolver(controlled.registry);
    compilerResolver.lockJIT();
    try {
      ObjectCodec<AsyncChild> owner = compilerResolver.getObjectCodec(AsyncChild.class);
      compilerResolver.getTypeInfo(AsyncChild.class, AsyncChild.class);
      assertSame(compilerResolver.stringWriter(owner), owner);
    } finally {
      compilerResolver.unlockJIT();
    }
    controlled.executor.runNext();

    JsonTypeResolver resolver = primaryTypeResolver(controlled.json);
    resolver.lockJIT();
    ObjectCodec<AsyncChild> owner;
    JsonTypeInfo info;
    try {
      owner = resolver.getObjectCodec(AsyncChild.class);
      info = resolver.getTypeInfo(AsyncChild.class, AsyncChild.class);
      assertSame(resolver.stringWriter(owner), owner);
    } finally {
      resolver.unlockJIT();
    }

    AtomicReference<Throwable> rootFailure = new AtomicReference<>();
    Thread root =
        new Thread(
            () -> {
              try {
                assertEquals(controlled.json.toJson(new BlockingValue()), "null");
              } catch (Throwable t) {
                rootFailure.set(t);
              }
            });
    root.start();
    await(rootEntered);

    CountDownLatch installStarted = new CountDownLatch(1);
    CountDownLatch installFinished = new CountDownLatch(1);
    AtomicReference<Throwable> installFailure = new AtomicReference<>();
    Thread installer =
        new Thread(
            () -> {
              installStarted.countDown();
              try {
                controlled.executor.runNext();
              } catch (Throwable t) {
                installFailure.set(t);
              } finally {
                installFinished.countDown();
              }
            });
    installer.start();
    await(installStarted);
    assertSame(info.stringWriter(), owner);
    releaseRoot.countDown();
    root.join();
    await(installFinished);
    assertFailure(rootFailure.get());
    assertFailure(installFailure.get());
    assertNotSame(info.stringWriter(), owner);
  }

  @Test
  public void outputDoesNotHoldLocalLock() throws Exception {
    ControlledJson controlled = controlledJson();
    JsonTypeResolver compilerResolver = new JsonTypeResolver(controlled.registry);
    compilerResolver.lockJIT();
    try {
      ObjectCodec<AsyncChild> owner = compilerResolver.getObjectCodec(AsyncChild.class);
      compilerResolver.getTypeInfo(AsyncChild.class, AsyncChild.class);
      assertSame(compilerResolver.utf8Writer(owner), owner);
    } finally {
      compilerResolver.unlockJIT();
    }
    controlled.executor.runNext();

    JsonTypeResolver resolver = primaryTypeResolver(controlled.json);
    resolver.lockJIT();
    ObjectCodec<AsyncChild> owner;
    JsonTypeInfo info;
    try {
      owner = resolver.getObjectCodec(AsyncChild.class);
      info = resolver.getTypeInfo(AsyncChild.class, AsyncChild.class);
      assertSame(resolver.utf8Writer(owner), owner);
    } finally {
      resolver.unlockJIT();
    }

    CountDownLatch outputEntered = new CountDownLatch(1);
    CountDownLatch releaseOutput = new CountDownLatch(1);
    AtomicReference<Throwable> rootFailure = new AtomicReference<>();
    Thread root =
        new Thread(
            () -> {
              try {
                controlled.json.writeJsonTo(
                    "root", new BlockingOutput(outputEntered, releaseOutput));
              } catch (Throwable t) {
                rootFailure.set(t);
              }
            });
    root.start();
    await(outputEntered);

    CountDownLatch installFinished = new CountDownLatch(1);
    AtomicReference<Throwable> installFailure = new AtomicReference<>();
    Thread installer =
        new Thread(
            () -> {
              try {
                controlled.executor.runNext();
              } catch (Throwable t) {
                installFailure.set(t);
              } finally {
                installFinished.countDown();
              }
            });
    installer.start();
    try {
      await(installFinished);
      assertFailure(installFailure.get());
      assertNotSame(info.utf8Writer(), owner);
    } finally {
      releaseOutput.countDown();
      root.join();
      installer.join();
    }
    assertFailure(rootFailure.get());
  }

  @Test
  public void pooledStatesRemainConcurrent() throws Exception {
    CountDownLatch rootEntered = new CountDownLatch(1);
    CountDownLatch releaseRoot = new CountDownLatch(1);
    CodecRegistry codecs = new CodecRegistry();
    codecs.register(BlockingValue.class, new BlockingCodec(rootEntered, releaseRoot));
    ControlledJson controlled = controlledJson(codecs);
    AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    Thread first =
        new Thread(
            () -> {
              try {
                assertEquals(controlled.json.toJson(new BlockingValue()), "null");
              } catch (Throwable t) {
                firstFailure.set(t);
              }
            });
    first.start();
    await(rootEntered);

    CountDownLatch secondFinished = new CountDownLatch(1);
    AtomicReference<Throwable> secondFailure = new AtomicReference<>();
    Thread second =
        new Thread(
            () -> {
              try {
                assertEquals(
                    controlled.json.toJson(child("free", 2)), "{\"id\":2,\"name\":\"free\"}");
              } catch (Throwable t) {
                secondFailure.set(t);
              } finally {
                secondFinished.countDown();
              }
            });
    second.start();
    await(secondFinished);
    assertFailure(secondFailure.get());
    assertEquals(controlled.executor.pendingTasks(), 1);
    releaseRoot.countDown();
    first.join();
    assertFailure(firstFailure.get());
    controlled.executor.runNext();
  }

  @Test
  public void capabilityFailureIsIndependent() throws Exception {
    ControlledJson controlled = controlledJson();
    JsonTypeResolver resolver = primaryTypeResolver(controlled.json);
    resolver.lockJIT();
    ObjectCodec<AsyncChild> owner;
    JsonTypeInfo info;
    try {
      owner = resolver.getObjectCodec(AsyncChild.class);
      info = resolver.getTypeInfo(AsyncChild.class, AsyncChild.class);
    } finally {
      resolver.unlockJIT();
    }
    controlled.executor.rejectNext();
    expectThrows(RejectedExecutionException.class, () -> controlled.json.toJson(child("x", 1)));
    assertSame(info.stringWriter(), owner);

    controlled.json.toJsonBytes(child("x", 1));
    controlled.executor.runNext();
    assertNotSame(info.utf8Writer(), owner);
    assertSame(info.stringWriter(), owner);
  }

  @Test
  public void semanticBindingsRemainOwners() throws Exception {
    ControlledJson controlled = controlledJson();
    TypeRef<GenericAsyncBox<String>> declaredType = new TypeRef<GenericAsyncBox<String>>() {};
    GenericAsyncBox<String> decoded =
        controlled.json.fromJson(
            "{\"value\":\"typed\"}".getBytes(StandardCharsets.UTF_8), declaredType);
    assertEquals(decoded.value, "typed");
    assertEquals(controlled.executor.submittedTasks(), 0);
    assertEquals(controlled.json.fromJson("7", Object.class), Long.valueOf(7));
    assertEquals(controlled.executor.submittedTasks(), 0);

    JsonTypeResolver resolver = primaryTypeResolver(controlled.json);
    resolver.lockJIT();
    JsonTypeInfo parameterized;
    Object parameterizedReader;
    try {
      parameterized = resolver.getTypeInfo(declaredType.getType(), GenericAsyncBox.class);
      parameterizedReader = parameterized.utf8Reader();
      assertFalse(parameterized.usesDefaultObjectCodec());
    } finally {
      resolver.unlockJIT();
    }

    controlled.json.fromJson(
        "{\"value\":\"raw\"}".getBytes(StandardCharsets.UTF_8), GenericAsyncBox.class);
    controlled.executor.runNext();
    assertSame(parameterized.utf8Reader(), parameterizedReader);

    JsonValueCodec<AsyncChild> codec = nullCodec();
    CodecRegistry codecs = new CodecRegistry();
    codecs.register(AsyncChild.class, codec);
    ControlledJson custom = controlledJson(codecs);
    assertEquals(custom.json.toJson(child("ignored", 1)), "null");
    assertEquals(new String(custom.json.toJsonBytes(child("ignored", 1))), "null");
    assertSame(custom.json.fromJson("null", AsyncChild.class), null);
    assertSame(
        custom.json.fromJson("null".getBytes(StandardCharsets.UTF_8), AsyncChild.class), null);
    JsonTypeInfo customInfo =
        primaryTypeResolver(custom.json).getTypeInfo(AsyncChild.class, AsyncChild.class);
    assertCapabilities(customInfo, codec);
    assertEquals(custom.executor.submittedTasks(), 0);
  }

  @Test
  public void nestedAndRecursiveTypes() throws Exception {
    ControlledJson controlled = controlledJson();
    ForyJson json = controlled.json;
    AsyncParent parent = new AsyncParent();
    parent.child = child("child", 1);
    parent.children = new LinkedHashMap<>();
    parent.children.put("nested", child("nested", 2));
    parent.list = Arrays.asList(child("listed", 3));
    String expected =
        "{\"child\":{\"id\":1,\"name\":\"child\"},\"children\":{\"nested\":{\"id\":2,\"name\":\"nested\"}},"
            + "\"list\":[{\"id\":3,\"name\":\"listed\"}]}";
    assertEquals(json.toJson(parent), expected);
    controlled.executor.runAll();
    assertEquals(json.toJson(parent), expected);
    AsyncParent decoded =
        json.fromJson(expected.getBytes(StandardCharsets.UTF_8), AsyncParent.class);
    controlled.executor.runAll();
    decoded = json.fromJson(expected.getBytes(StandardCharsets.UTF_8), AsyncParent.class);
    assertEquals(decoded.children.get("nested").id, 2);
    assertEquals(decoded.list.get(0).id, 3);
    assertNestedUtf8Readers(json);

    RecursiveParent recursive = new RecursiveParent();
    assertEquals(json.toJson(recursive), "{\"child\":{\"name\":\"child\"},\"name\":\"parent\"}");
    controlled.executor.runAll();
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

    JsonTypeResolver resolver = primaryTypeResolver(json);
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
  public void nestedPublication() throws Exception {
    ForyJson json = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeResolver resolver = primaryTypeResolver(json);
    ObjectCodec<AsyncParent> parent = resolver.getObjectCodec(AsyncParent.class);
    ObjectCodec<AsyncChild> child = resolver.getObjectCodec(AsyncChild.class);
    resolver.getTypeInfo(AsyncParent.class, AsyncParent.class);
    JsonTypeInfo childInfo = resolver.getTypeInfo(AsyncChild.class, AsyncChild.class);

    Object parentCapability = resolver.stringWriter(parent);
    Object childCapability = childInfo.stringWriter();
    assertSame(resolver.stringWriter(child), childCapability);
    assertSame(childInfo.stringWriter(), childCapability);
    assertPublishedChild(parentCapability, StringWriterCodec.class, childCapability, child, 1);

    parentCapability = resolver.utf8Writer(parent);
    childCapability = childInfo.utf8Writer();
    assertSame(resolver.utf8Writer(child), childCapability);
    assertSame(childInfo.utf8Writer(), childCapability);
    assertPublishedChild(parentCapability, Utf8WriterCodec.class, childCapability, child, 1);

    parentCapability = resolver.latin1Reader(parent);
    childCapability = childInfo.latin1Reader();
    assertSame(resolver.latin1Reader(child), childCapability);
    assertSame(childInfo.latin1Reader(), childCapability);
    assertPublishedChild(parentCapability, Latin1ReaderCodec.class, childCapability, child, 1);

    parentCapability = resolver.utf16Reader(parent);
    childCapability = childInfo.utf16Reader();
    assertSame(resolver.utf16Reader(child), childCapability);
    assertSame(childInfo.utf16Reader(), childCapability);
    assertPublishedChild(parentCapability, Utf16ReaderCodec.class, childCapability, child, 1);

    parentCapability = resolver.utf8Reader(parent);
    childCapability = childInfo.utf8Reader();
    assertSame(resolver.utf8Reader(child), childCapability);
    assertSame(childInfo.utf8Reader(), childCapability);
    assertPublishedChild(parentCapability, Utf8ReaderCodec.class, childCapability, child, 1);
  }

  @Test
  public void mutualPublication() throws Exception {
    ForyJson json = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeResolver resolver = primaryTypeResolver(json);
    ObjectCodec<MutualFirst> firstOwner = resolver.getObjectCodec(MutualFirst.class);
    ObjectCodec<MutualSecond> secondOwner = resolver.getObjectCodec(MutualSecond.class);
    JsonTypeInfo firstInfo = resolver.getTypeInfo(MutualFirst.class, MutualFirst.class);
    JsonTypeInfo secondInfo = resolver.getTypeInfo(MutualSecond.class, MutualSecond.class);

    Object first = resolver.stringWriter(firstOwner);
    Object second = secondInfo.stringWriter();
    assertSame(resolver.stringWriter(secondOwner), second);
    assertSame(secondInfo.stringWriter(), second);
    assertMutualFields(
        firstInfo.stringWriter(), secondInfo.stringWriter(), StringWriterCodec.class);

    first = resolver.utf8Writer(firstOwner);
    second = secondInfo.utf8Writer();
    assertSame(resolver.utf8Writer(secondOwner), second);
    assertSame(secondInfo.utf8Writer(), second);
    assertMutualFields(firstInfo.utf8Writer(), secondInfo.utf8Writer(), Utf8WriterCodec.class);

    first = resolver.latin1Reader(firstOwner);
    second = secondInfo.latin1Reader();
    assertSame(resolver.latin1Reader(secondOwner), second);
    assertSame(secondInfo.latin1Reader(), second);
    assertMutualFields(
        firstInfo.latin1Reader(), secondInfo.latin1Reader(), Latin1ReaderCodec.class);

    first = resolver.utf16Reader(firstOwner);
    second = secondInfo.utf16Reader();
    assertSame(resolver.utf16Reader(secondOwner), second);
    assertSame(secondInfo.utf16Reader(), second);
    assertMutualFields(firstInfo.utf16Reader(), secondInfo.utf16Reader(), Utf16ReaderCodec.class);

    first = resolver.utf8Reader(firstOwner);
    second = secondInfo.utf8Reader();
    assertSame(resolver.utf8Reader(secondOwner), second);
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

  private static void assertCapabilities(JsonTypeInfo info, Object expected) {
    assertSame(info.stringWriter(), expected);
    assertSame(info.utf8Writer(), expected);
    assertSame(info.latin1Reader(), expected);
    assertSame(info.utf16Reader(), expected);
    assertSame(info.utf8Reader(), expected);
  }

  private static <T> StringWriterCodec<T> stringWriter(
      JsonTypeResolver resolver, ObjectCodec<T> owner) {
    resolver.lockJIT();
    try {
      return resolver.stringWriter(owner);
    } finally {
      resolver.unlockJIT();
    }
  }

  private static void assertNestedUtf8Readers(ForyJson json) throws Exception {
    JsonTypeResolver resolver = primaryTypeResolver(json);
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
    assertEquals(nestedReaders, 1);
  }

  private static ControlledJson controlledJson() throws Exception {
    return controlledJson(new CodecRegistry());
  }

  private static ControlledJson controlledJson(CodecRegistry codecs) throws Exception {
    JsonConfig config =
        new JsonConfig(
            false,
            true,
            true,
            true,
            PropertyNamingStrategy.LOWER_CAMEL_CASE,
            JsonAsyncCompilationTest.class.getClassLoader(),
            ForyJson.DEFAULT_MAX_DEPTH,
            1,
            2 * 1024 * 1024,
            codecs,
            null);
    ControlledExecutor executor = new ControlledExecutor();
    Constructor<JsonSharedRegistry> constructor =
        JsonSharedRegistry.class.getDeclaredConstructor(JsonConfig.class, ExecutorService.class);
    constructor.setAccessible(true);
    JsonSharedRegistry registry = constructor.newInstance(config, executor);
    return new ControlledJson(new ForyJson(config, registry), registry, executor);
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for test coordination");
  }

  private static void assertFailure(Throwable failure) {
    if (failure != null) {
      fail("Unexpected worker failure", failure);
    }
  }

  private static boolean asyncCompilationEnabled(ForyJson json) throws Exception {
    Object jitContext = field(primaryTypeResolver(json), "jitContext");
    return (boolean) field(jitContext, "asyncCompilationEnabled");
  }

  private static Object field(Object owner, String name) throws Exception {
    for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
      try {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
      } catch (NoSuchFieldException ignored) {
        // Continue through superclasses for generated codec fields.
      }
    }
    throw new NoSuchFieldException(name);
  }

  private static final class ControlledJson {
    private final ForyJson json;
    private final JsonSharedRegistry registry;
    private final ControlledExecutor executor;

    private ControlledJson(
        ForyJson json, JsonSharedRegistry registry, ControlledExecutor executor) {
      this.json = json;
      this.registry = registry;
      this.executor = executor;
    }
  }

  private static final class ControlledExecutor extends AbstractExecutorService {
    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final AtomicInteger submitted = new AtomicInteger();
    private volatile boolean shutdown;
    private volatile boolean rejectNext;

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      List<Runnable> pending = new ArrayList<>();
      tasks.drainTo(pending);
      return pending;
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown && tasks.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return isTerminated();
    }

    @Override
    public void execute(Runnable command) {
      if (shutdown || rejectNext) {
        rejectNext = false;
        throw new RejectedExecutionException("controlled rejection");
      }
      submitted.incrementAndGet();
      tasks.add(command);
    }

    private void rejectNext() {
      rejectNext = true;
    }

    private int submittedTasks() {
      return submitted.get();
    }

    private int pendingTasks() {
      return tasks.size();
    }

    private void runNext() {
      Runnable task = tasks.poll();
      assertNotNull(task, "No compilation task is pending");
      task.run();
    }

    private void runAll() {
      Runnable task;
      while ((task = tasks.poll()) != null) {
        task.run();
      }
    }
  }

  private static final class BlockingCodec implements JsonValueCodec<BlockingValue> {
    private final CountDownLatch entered;
    private final CountDownLatch release;

    private BlockingCodec(CountDownLatch entered, CountDownLatch release) {
      this.entered = entered;
      this.release = release;
    }

    @Override
    public void writeString(StringJsonWriter writer, BlockingValue value) {
      block();
      writer.writeNull();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, BlockingValue value) {
      block();
      writer.writeNull();
    }

    @Override
    public BlockingValue readLatin1(Latin1JsonReader reader) {
      reader.skipValue();
      return null;
    }

    @Override
    public BlockingValue readUtf16(Utf16JsonReader reader) {
      reader.skipValue();
      return null;
    }

    @Override
    public BlockingValue readUtf8(Utf8JsonReader reader) {
      reader.skipValue();
      return null;
    }

    private void block() {
      entered.countDown();
      try {
        assertTrue(release.await(30, TimeUnit.SECONDS), "Timed out waiting to release root codec");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  private static final class BlockingOutput extends OutputStream {
    private final CountDownLatch entered;
    private final CountDownLatch release;

    private BlockingOutput(CountDownLatch entered, CountDownLatch release) {
      this.entered = entered;
      this.release = release;
    }

    @Override
    public void write(int value) throws IOException {
      block();
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      block();
    }

    private void block() throws IOException {
      entered.countDown();
      try {
        assertTrue(release.await(30, TimeUnit.SECONDS), "Timed out waiting to release output");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(e);
      }
    }
  }

  private static final class BlockingValue {}

  private static AsyncChild child(String name, int id) {
    AsyncChild child = new AsyncChild();
    child.id = id;
    child.name = name;
    return child;
  }

  @JsonSubTypes(
      property = "kind",
      value = {@JsonSubTypes.Type(value = AsyncInlineChild.class, name = "child")})
  public interface AsyncInlineShape {}

  public static final class AsyncInlineChild implements AsyncInlineShape {
    @JsonAnyProperty public Map<String, Integer> properties;
  }

  @JsonSubTypes(
      property = "kind",
      value = {
        @JsonSubTypes.Type(value = RollbackCircle.class, name = "circle"),
        @JsonSubTypes.Type(value = CollidingSubtype.class, name = "collision")
      })
  public interface BrokenShape {}

  public static final class RollbackCircle implements BrokenShape {
    @JsonAnyProperty public Map<String, Integer> properties;
  }

  public static final class CollidingSubtype implements BrokenShape {
    public String kind;
  }

  public static final class AsyncParent {
    public AsyncChild child;
    public Map<String, AsyncChild> children;
    public List<AsyncChild> list;
  }

  public static final class AsyncCreator {
    public final int id;
    public final String name;

    @JsonCreator({"id", "name"})
    public AsyncCreator(int id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  public static final class AsyncChild {
    public int id;
    public String name;
  }

  public static final class GenericAsyncBox<T> {
    public T value;
  }

  public static final class SelfRecursive {
    public int id;
    public SelfRecursive next;
  }

  public static final class RollbackAfterSelf {
    public RollbackAfterSelf aSelf;

    @JsonCodec(FlakyStringCodec.class)
    public String zValue;
  }

  public static final class FlakyStringCodec extends JsonCodecAnnotationTest.TaggedStringCodec {
    private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    public FlakyStringCodec() {
      if (CONSTRUCTIONS.getAndIncrement() == 0) {
        throw new IllegalStateException("expected first construction failure");
      }
    }

    @Override
    protected String tag() {
      return "rollback";
    }
  }

  public static final class MutualFirst {
    public MutualSecond second;
  }

  public static final class MutualSecond {
    public MutualFirst first;
  }
}
