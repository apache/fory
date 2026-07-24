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
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
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
import org.apache.fory.json.codec.ScalarCodecs;
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
import org.testng.annotations.Test;

public class JsonAsyncCompilationTest {
  @Test
  public void defaultBuilderEnablesAsync() throws Exception {
    assertTrue(asyncCompilationEnabled(ForyJson.builder().build()));
    assertFalse(asyncCompilationEnabled(ForyJson.builder().withAsyncCompilation(false).build()));
    assertFalse(asyncCompilationEnabled(ForyJson.builder().withCodegen(false).build()));
  }

  @Test
  public void capabilitiesRegisterAfterResolution() throws Exception {
    ForyJson json = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeResolver resolver = primaryTypeResolver(json);
    ObjectCodec<AsyncChild> owner = resolver.getObjectCodec(AsyncChild.class);
    JsonTypeInfo info = resolver.getTypeInfo(AsyncChild.class, AsyncChild.class);
    assertNotSame(info.stringWriter(), owner);
    assertNotSame(info.utf8Writer(), owner);
    assertNotSame(info.latin1Reader(), owner);
    assertNotSame(info.utf16Reader(), owner);
    assertNotSame(info.utf8Reader(), owner);

    AsyncChild value = child("root", 1);
    assertEquals(json.toJson(value), "{\"id\":1,\"name\":\"root\"}");
    assertEquals(
        new String(json.toJsonBytes(value), StandardCharsets.UTF_8),
        "{\"id\":1,\"name\":\"root\"}");
    assertEquals(json.fromJson("{\"id\":2,\"name\":\"latin\"}", AsyncChild.class).id, 2);
    assertEquals(json.fromJson("{\"id\":3,\"name\":\"\u4f60\"}", AsyncChild.class).id, 3);
    assertEquals(
        json.fromJson(
                "{\"id\":4,\"name\":\"utf8\"}".getBytes(StandardCharsets.UTF_8), AsyncChild.class)
            .id,
        4);
    assertSame(resolver.getObjectCodec(AsyncChild.class), owner);
  }

  @Test
  public void closedSubtypeBranchesInstall() throws Exception {
    ControlledJson controlled = controlledJson();
    ForyJson json = controlled.json;
    AsyncInlineChild initial =
        (AsyncInlineChild) json.fromJson("{\"kind\":\"child\",\"x\":1}", AsyncInlineShape.class);
    assertEquals(initial.properties, Collections.singletonMap("x", 1));

    controlled.executor.runAll();
    JsonTypeResolver resolver = primaryTypeResolver(json);
    JsonTypeInfo shapeInfo = resolver.getTypeInfo(AsyncInlineShape.class, AsyncInlineShape.class);
    assertTrue(shapeInfo.latin1Reader() instanceof ClosedSubtypeCodec);
    ClosedSubtypeCodec subtype = (ClosedSubtypeCodec) shapeInfo.latin1Reader();
    JsonTypeInfo childInfo = resolver.getTypeInfo(AsyncInlineChild.class, AsyncInlineChild.class);
    ObjectCodec<AsyncInlineChild> childOwner = resolver.getObjectCodec(AsyncInlineChild.class);
    assertNotSame(childInfo.latin1Reader(), childOwner);
    assertNotSame(childInfo.utf16Reader(), childOwner);
    assertNotSame(childInfo.utf8Reader(), childOwner);
    assertInlineReader(subtype.inlineLatin1Readers(), childInfo.latin1Reader());
    assertInlineReader(subtype.inlineUtf16Readers(), childInfo.utf16Reader());
    assertInlineReader(subtype.inlineUtf8Readers(), childInfo.utf8Reader());

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
    assertEquals(controlled.executor.pendingTasks(), 5);
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
  public void accessorsDoNotReregisterCapabilities() throws Exception {
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

    assertEquals(controlled.executor.submittedTasks(), 5);
    assertEquals(controlled.executor.pendingTasks(), 5);
    for (int i = 0; i < 100; i++) {
      assertSame(stringWriter(resolver, owner), owner);
    }
    assertEquals(controlled.executor.submittedTasks(), 5);
    assertEquals(controlled.executor.pendingTasks(), 5);
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
      assertEquals(controlled.executor.pendingTasks(), 0);
      replacementOwner = resolver.getObjectCodec(RollbackCircle.class);
      replacement = resolver.getTypeInfo(RollbackCircle.class, RollbackCircle.class);
      assertSame(replacement.latin1Reader(), replacementOwner);
      assertSame(replacement.utf16Reader(), replacementOwner);
      assertSame(replacement.utf8Reader(), replacementOwner);
      assertEquals(controlled.executor.pendingTasks(), 5);
    } finally {
      resolver.unlockJIT();
    }

    // A failed metadata transaction cannot register a capability graph. The later independent
    // binding registers exactly its own five representations and cannot be mutated by stale work.
    controlled.executor.runAll();
    assertNotSame(replacement.latin1Reader(), replacementOwner);
    assertNotSame(replacement.utf16Reader(), replacementOwner);
    assertNotSame(replacement.utf8Reader(), replacementOwner);
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
    JsonJITContext context = new JsonJITContext(true);
    CompletableFuture<Integer> future = new CompletableFuture<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    JsonJITContext.JITCallback<Integer> callback =
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
        };
    context.registerJITFuture(() -> future, callback);
    context.registerJITFuture(
        () -> {
          fail("Duplicate active request");
          return CompletableFuture.completedFuture(2);
        },
        callback);
    future.completeExceptionally(new IllegalStateException("compile failure"));
    assertTrue(failure.get() instanceof IllegalStateException);
  }

  @Test
  public void rootAndCompletionUseLocalLock() throws Exception {
    CountDownLatch rootEntered = new CountDownLatch(1);
    CountDownLatch releaseRoot = new CountDownLatch(1);
    CodecRegistry codecs = new CodecRegistry();
    codecs.register(BlockingValue.class, new BlockingCodec(rootEntered, releaseRoot));
    ControlledJson controlled = controlledJson(codecs);
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
    assertFalse(installFinished.await(100, TimeUnit.MILLISECONDS));
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
    assertEquals(controlled.executor.pendingTasks(), 5);
    releaseRoot.countDown();
    first.join();
    assertFailure(firstFailure.get());
    controlled.executor.runAll();
  }

  @Test
  public void capabilityFailureIsIndependent() throws Exception {
    ControlledJson controlled = controlledJson();
    controlled.executor.rejectNext();
    JsonTypeResolver resolver = primaryTypeResolver(controlled.json);
    assertEquals(controlled.json.toJson(child("x", 1)), "{\"id\":1,\"name\":\"x\"}");
    ObjectCodec<AsyncChild> owner = resolver.getObjectCodec(AsyncChild.class);
    JsonTypeInfo info = resolver.getTypeInfo(AsyncChild.class, AsyncChild.class);
    assertSame(info.stringWriter(), owner);
    assertEquals(controlled.executor.pendingTasks(), 4);
    controlled.executor.runAll();
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
      assertNull(resolver.canonicalObjectCodec(parameterized));
    } finally {
      resolver.unlockJIT();
    }

    controlled.json.fromJson(
        "{\"value\":\"raw\"}".getBytes(StandardCharsets.UTF_8), GenericAsyncBox.class);
    controlled.executor.runNext();
    assertSame(parameterized.utf8Reader(), parameterizedReader);
    resolver.lockJIT();
    try {
      JsonTypeInfo raw = resolver.getTypeInfo(GenericAsyncBox.class, GenericAsyncBox.class);
      assertSame(
          resolver.canonicalObjectCodec(raw), resolver.getObjectCodec(GenericAsyncBox.class));
    } finally {
      resolver.unlockJIT();
    }

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
    assertNull(primaryTypeResolver(custom.json).canonicalObjectCodec(customInfo));
    assertEquals(custom.executor.submittedTasks(), 0);
  }

  @Test
  public void sourceShapeIgnoresPublicationOrder() {
    ForyJson parentFirstJson = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeResolver parentFirstResolver = primaryTypeResolver(parentFirstJson);
    ObjectCodec<AsyncParent> parentFirstOwner =
        parentFirstResolver.getObjectCodec(AsyncParent.class);
    parentFirstResolver.getTypeInfo(AsyncParent.class, AsyncParent.class);
    Object parentFirst = parentFirstResolver.utf8Writer(parentFirstOwner);

    ForyJson childFirstJson = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeResolver childFirstResolver = primaryTypeResolver(childFirstJson);
    ObjectCodec<AsyncChild> childFirstOwner = childFirstResolver.getObjectCodec(AsyncChild.class);
    childFirstResolver.getTypeInfo(AsyncChild.class, AsyncChild.class);
    childFirstResolver.utf8Writer(childFirstOwner);
    ObjectCodec<AsyncParent> childFirstParent =
        childFirstResolver.getObjectCodec(AsyncParent.class);
    childFirstResolver.getTypeInfo(AsyncParent.class, AsyncParent.class);
    Object childFirst = childFirstResolver.utf8Writer(childFirstParent);

    assertEquals(declaredFieldTypes(parentFirst), declaredFieldTypes(childFirst));
    assertTrue(declaredFieldTypes(parentFirst).contains(Utf8WriterCodec.class.getName()));
  }

  @Test
  public void utf8GraphPublishesAtomically() throws Exception {
    ControlledJson controlled = controlledJson();
    String input =
        "{\"children\":[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"},"
            + "{\"id\":3,\"name\":\"c\"},{\"id\":4,\"name\":\"d\"},"
            + "{\"id\":5,\"name\":\"e\"},{\"id\":6,\"name\":\"f\"},"
            + "{\"id\":7,\"name\":\"g\"},{\"id\":8,\"name\":\"h\"},"
            + "{\"id\":9,\"name\":\"i\"}],\"friends\":[{\"id\":10}]}";
    AsyncCollections initial =
        controlled.json.fromJson(input.getBytes(StandardCharsets.UTF_8), AsyncCollections.class);
    assertEquals(initial.children.size(), 9);
    assertEquals(initial.friends.get(0).id, 10);

    JsonTypeResolver resolver = primaryTypeResolver(controlled.json);
    ObjectCodec<AsyncCollections> rootOwner = resolver.getObjectCodec(AsyncCollections.class);
    ObjectCodec<AsyncChild> childOwner = resolver.getObjectCodec(AsyncChild.class);
    ObjectCodec<AsyncFriend> friendOwner = resolver.getObjectCodec(AsyncFriend.class);
    JsonTypeInfo rootInfo = resolver.getTypeInfo(AsyncCollections.class, AsyncCollections.class);
    JsonTypeInfo childInfo = resolver.getTypeInfo(AsyncChild.class, AsyncChild.class);
    JsonTypeInfo friendInfo = resolver.getTypeInfo(AsyncFriend.class, AsyncFriend.class);
    JsonFieldInfo[] fields = rootOwner.readFields();
    JsonTypeInfo childrenInfo = fields[0].readTypeInfo();
    JsonTypeInfo friendsInfo = fields[1].readTypeInfo();
    Object initialRootReader = rootInfo.utf8Reader();
    Object initialChildReader = childInfo.utf8Reader();
    Object initialFriendReader = friendInfo.utf8Reader();
    Object initialChildren = childrenInfo.utf8Reader();
    Object initialFriends = friendsInfo.utf8Reader();
    JsonFieldInfo[] writeFields = rootOwner.writeFields();
    JsonTypeInfo writtenChildrenInfo = writeFields[0].writeTypeInfo();
    JsonTypeInfo writtenFriendsInfo = writeFields[1].writeTypeInfo();
    Object initialRootWriter = rootInfo.utf8Writer();
    Object initialChildWriter = childInfo.utf8Writer();
    Object initialFriendWriter = friendInfo.utf8Writer();
    Object initialChildrenWriter = writtenChildrenInfo.utf8Writer();
    Object initialFriendsWriter = writtenFriendsInfo.utf8Writer();
    assertEquals(new String(controlled.json.toJsonBytes(initial), StandardCharsets.UTF_8), input);

    int pendingTasks = controlled.executor.pendingTasks();
    assertEquals(pendingTasks, 19);
    for (int i = 0; i < pendingTasks; i++) {
      controlled.executor.runNext();
      boolean initialReaderGraph =
          rootInfo.utf8Reader() == initialRootReader
              && childInfo.utf8Reader() == initialChildReader
              && friendInfo.utf8Reader() == initialFriendReader
              && childrenInfo.utf8Reader() == initialChildren
              && friendsInfo.utf8Reader() == initialFriends;
      boolean generatedReaderGraph =
          rootInfo.utf8Reader() != initialRootReader
              && childInfo.utf8Reader() != initialChildReader
              && friendInfo.utf8Reader() != initialFriendReader
              && childrenInfo.utf8Reader() != initialChildren
              && friendsInfo.utf8Reader() != initialFriends;
      assertTrue(initialReaderGraph || generatedReaderGraph);

      boolean initialWriterGraph =
          rootInfo.utf8Writer() == initialRootWriter
              && childInfo.utf8Writer() == initialChildWriter
              && friendInfo.utf8Writer() == initialFriendWriter
              && writtenChildrenInfo.utf8Writer() == initialChildrenWriter
              && writtenFriendsInfo.utf8Writer() == initialFriendsWriter;
      boolean generatedWriterGraph =
          rootInfo.utf8Writer() != initialRootWriter
              && childInfo.utf8Writer() != initialChildWriter
              && friendInfo.utf8Writer() != initialFriendWriter
              && writtenChildrenInfo.utf8Writer() != initialChildrenWriter
              && writtenFriendsInfo.utf8Writer() != initialFriendsWriter;
      assertTrue(initialWriterGraph || generatedWriterGraph);
    }

    assertNotSame(rootInfo.utf8Reader(), rootOwner);
    assertNotSame(childInfo.utf8Reader(), childOwner);
    assertNotSame(friendInfo.utf8Reader(), friendOwner);
    assertNotSame(childrenInfo.utf8Reader(), initialChildren);
    assertNotSame(friendsInfo.utf8Reader(), initialFriends);
    assertTrue(childrenInfo.utf8Reader().getClass() != friendsInfo.utf8Reader().getClass());
    assertFinalField(childrenInfo.utf8Reader(), "elementReader", childInfo.utf8Reader());
    assertFinalField(friendsInfo.utf8Reader(), "elementReader", friendInfo.utf8Reader());
    assertFinalCollectionFields(
        rootInfo.utf8Reader(),
        Utf8ReaderCodec.class,
        childrenInfo.utf8Reader(),
        friendsInfo.utf8Reader());

    assertNotSame(rootInfo.utf8Writer(), initialRootWriter);
    assertNotSame(childInfo.utf8Writer(), initialChildWriter);
    assertNotSame(friendInfo.utf8Writer(), initialFriendWriter);
    assertNotSame(writtenChildrenInfo.utf8Writer(), initialChildrenWriter);
    assertNotSame(writtenFriendsInfo.utf8Writer(), initialFriendsWriter);
    assertTrue(
        writtenChildrenInfo.utf8Writer().getClass() != writtenFriendsInfo.utf8Writer().getClass());
    assertFinalField(writtenChildrenInfo.utf8Writer(), "elementWriter", childInfo.utf8Writer());
    assertFinalField(writtenFriendsInfo.utf8Writer(), "elementWriter", friendInfo.utf8Writer());
    assertFinalField(writtenChildrenInfo.utf8Writer(), "fallback", initialChildrenWriter);
    assertFinalField(writtenFriendsInfo.utf8Writer(), "fallback", initialFriendsWriter);
    assertFinalCollectionFields(
        rootInfo.utf8Writer(),
        Utf8WriterCodec.class,
        writtenChildrenInfo.utf8Writer(),
        writtenFriendsInfo.utf8Writer());
    assertEquals(writeUtf8(writtenChildrenInfo.utf8Writer(), null), "null");
    assertEquals(writeUtf8(writtenFriendsInfo.utf8Writer(), new ArrayList<>()), "[]");

    AsyncCollections generated =
        controlled.json.fromJson(input.getBytes(StandardCharsets.UTF_8), AsyncCollections.class);
    assertEquals(generated.children.size(), 9);
    assertEquals(generated.children.get(8).id, 9);
    assertEquals(generated.friends.get(0).id, 10);
    assertEquals(new String(controlled.json.toJsonBytes(generated), StandardCharsets.UTF_8), input);

    AsyncCollections fallback = new AsyncCollections();
    fallback.children = new LinkedList<>(generated.children);
    fallback.friends = new LinkedList<>(generated.friends);
    assertEquals(new String(controlled.json.toJsonBytes(fallback), StandardCharsets.UTF_8), input);

    AsyncCollections empty =
        controlled.json.fromJson(
            "{\"children\":[],\"friends\":null}".getBytes(StandardCharsets.UTF_8),
            AsyncCollections.class);
    assertTrue(empty.children.isEmpty());
    assertNull(empty.friends);
    assertEquals(
        new String(controlled.json.toJsonBytes(empty), StandardCharsets.UTF_8),
        "{\"children\":[]}");
  }

  @Test
  public void utf8ParentsCaptureFinalChild() throws Exception {
    ControlledJson controlled = controlledJson();
    byte[] creatorInput =
        "{\"child\":{\"id\":1,\"name\":\"creator\"}}".getBytes(StandardCharsets.UTF_8);

    JsonTypeResolver resolver = primaryTypeResolver(controlled.json);
    ObjectCodec<AsyncCreatorParent> creatorOwner =
        resolver.getObjectCodec(AsyncCreatorParent.class);
    ObjectCodec<AsyncGraphParent> graphOwner = resolver.getObjectCodec(AsyncGraphParent.class);
    ObjectCodec<AsyncChild> childOwner = resolver.getObjectCodec(AsyncChild.class);
    JsonTypeInfo creatorInfo =
        resolver.getTypeInfo(AsyncCreatorParent.class, AsyncCreatorParent.class);
    JsonTypeInfo graphInfo = resolver.getTypeInfo(AsyncGraphParent.class, AsyncGraphParent.class);
    JsonTypeInfo childInfo = resolver.getTypeInfo(AsyncChild.class, AsyncChild.class);
    resolver.lockJIT();
    try {
      assertSame(resolver.utf8Reader(creatorOwner), creatorOwner);
      assertSame(resolver.utf8Reader(graphOwner), graphOwner);
    } finally {
      resolver.unlockJIT();
    }

    assertEquals(controlled.executor.pendingTasks(), 15);
    controlled.executor.runAll();
    assertNotSame(creatorInfo.utf8Reader(), creatorOwner);
    assertNotSame(graphInfo.utf8Reader(), graphOwner);
    assertNotSame(childInfo.utf8Reader(), childOwner);
    assertCapabilityFields(
        creatorInfo.utf8Reader(), Utf8ReaderCodec.class, childInfo.utf8Reader(), 1);
    assertCapabilityFields(
        graphInfo.utf8Reader(), Utf8ReaderCodec.class, childInfo.utf8Reader(), 1);
    assertEquals(
        controlled.json.fromJson(creatorInput, AsyncCreatorParent.class).child.name, "creator");
  }

  @Test
  public void utf8ScalarCollectionCapability() throws Exception {
    ControlledJson controlled = controlledJson();
    String[] tokens = {
      "null",
      "\"\"",
      "\"short\"",
      "\"abcdefghijklmnopqrstuvwxyz0123456789\"",
      "\"quote\\\"\"",
      "\"slash\\\\\"",
      "\"line\\n\"",
      "\"你好\"",
      "\"eight\"",
      "\"nine\"",
      "\"ten\"",
      "null",
      "\"tail\""
    };
    List<String> expected =
        Arrays.asList(
            null,
            "",
            "short",
            "abcdefghijklmnopqrstuvwxyz0123456789",
            "quote\"",
            "slash\\",
            "line\n",
            "你好",
            "eight",
            "nine",
            "ten",
            null,
            "tail");
    String input = stringCollectionInput(tokens, tokens.length);
    AsyncStringCollections initial =
        controlled.json.fromJson(
            input.getBytes(StandardCharsets.UTF_8), AsyncStringCollections.class);
    assertEquals(initial.values, expected);

    JsonTypeResolver resolver = primaryTypeResolver(controlled.json);
    ObjectCodec<AsyncStringCollections> rootOwner =
        resolver.getObjectCodec(AsyncStringCollections.class);
    JsonTypeInfo rootInfo =
        resolver.getTypeInfo(AsyncStringCollections.class, AsyncStringCollections.class);
    JsonTypeInfo valuesInfo = rootOwner.readFields()[0].readTypeInfo();
    Object initialRootReader = rootInfo.utf8Reader();
    Object initialValues = valuesInfo.utf8Reader();
    JsonTypeInfo writtenValuesInfo = rootOwner.writeFields()[0].writeTypeInfo();
    Object initialRootWriter = rootInfo.utf8Writer();
    Object initialValuesWriter = writtenValuesInfo.utf8Writer();
    String expectedJson = "{\"values\":[" + String.join(",", tokens) + "]}";
    assertEquals(
        new String(controlled.json.toJsonBytes(initial), StandardCharsets.UTF_8), expectedJson);

    int pendingTasks = controlled.executor.pendingTasks();
    assertEquals(pendingTasks, 7);
    for (int i = 0; i < pendingTasks; i++) {
      controlled.executor.runNext();
      boolean initialReaderGraph =
          rootInfo.utf8Reader() == initialRootReader && valuesInfo.utf8Reader() == initialValues;
      boolean generatedReaderGraph =
          rootInfo.utf8Reader() != initialRootReader && valuesInfo.utf8Reader() != initialValues;
      assertTrue(initialReaderGraph || generatedReaderGraph);
      boolean initialWriterGraph =
          rootInfo.utf8Writer() == initialRootWriter
              && writtenValuesInfo.utf8Writer() == initialValuesWriter;
      boolean generatedWriterGraph =
          rootInfo.utf8Writer() != initialRootWriter
              && writtenValuesInfo.utf8Writer() != initialValuesWriter;
      assertTrue(initialWriterGraph || generatedWriterGraph);
    }

    assertNotSame(rootInfo.utf8Reader(), rootOwner);
    assertNotSame(valuesInfo.utf8Reader(), initialValues);
    assertFinalField(valuesInfo.utf8Reader(), "elementReader", ScalarCodecs.StringCodec.INSTANCE);
    assertFinalCollectionFields(
        rootInfo.utf8Reader(), Utf8ReaderCodec.class, valuesInfo.utf8Reader());
    assertNotSame(rootInfo.utf8Writer(), initialRootWriter);
    assertNotSame(writtenValuesInfo.utf8Writer(), initialValuesWriter);
    assertFinalField(writtenValuesInfo.utf8Writer(), "fallback", initialValuesWriter);
    assertEquals(writtenValuesInfo.utf8Writer().getClass().getDeclaredFields().length, 1);
    assertFinalCollectionFields(
        rootInfo.utf8Writer(), Utf8WriterCodec.class, writtenValuesInfo.utf8Writer());
    assertEquals(writeUtf8(writtenValuesInfo.utf8Writer(), null), "null");

    AsyncStringCollections generated =
        controlled.json.fromJson(
            input.getBytes(StandardCharsets.UTF_8), AsyncStringCollections.class);
    assertEquals(generated.values, expected);
    assertEquals(
        new String(controlled.json.toJsonBytes(generated), StandardCharsets.UTF_8), expectedJson);
    generated.values = new LinkedList<>(expected);
    assertEquals(
        new String(controlled.json.toJsonBytes(generated), StandardCharsets.UTF_8), expectedJson);
    generated.values = new ArrayList<>();
    assertEquals(
        new String(controlled.json.toJsonBytes(generated), StandardCharsets.UTF_8),
        "{\"values\":[]}");
    generated.values = null;
    assertEquals(new String(controlled.json.toJsonBytes(generated), StandardCharsets.UTF_8), "{}");
    for (int size = 0; size <= tokens.length; size++) {
      AsyncStringCollections prefix =
          controlled.json.fromJson(
              stringCollectionInput(tokens, size).getBytes(StandardCharsets.UTF_8),
              AsyncStringCollections.class);
      assertEquals(prefix.values, expected.subList(0, size));
    }
  }

  private static String stringCollectionInput(String[] tokens, int size) {
    StringBuilder input = new StringBuilder("{\"values\":[");
    for (int i = 0; i < size; i++) {
      if (i != 0) {
        input.append(i == 10 ? " \n, \t" : ",");
      }
      input.append(tokens[i]);
    }
    return input.append("]}").toString();
  }

  @Test
  public void nonListCollectionStaysOnOwner() throws Exception {
    ControlledJson controlled = controlledJson();
    AsyncFriend first = new AsyncFriend();
    first.id = 1;
    AsyncFriend second = new AsyncFriend();
    second.id = 2;
    AsyncSetCollections value = new AsyncSetCollections();
    value.values = new LinkedHashSet<>(Arrays.asList(first, second));
    String expected = "{\"values\":[{\"id\":1},{\"id\":2}]}";
    assertEquals(new String(controlled.json.toJsonBytes(value), StandardCharsets.UTF_8), expected);

    JsonTypeResolver resolver = primaryTypeResolver(controlled.json);
    ObjectCodec<AsyncSetCollections> rootOwner = resolver.getObjectCodec(AsyncSetCollections.class);
    JsonTypeInfo rootInfo =
        resolver.getTypeInfo(AsyncSetCollections.class, AsyncSetCollections.class);
    JsonTypeInfo valuesInfo = rootOwner.writeFields()[0].writeTypeInfo();
    Object initialRootWriter = rootInfo.utf8Writer();
    Object initialValuesWriter = valuesInfo.utf8Writer();

    assertEquals(controlled.executor.pendingTasks(), 10);
    controlled.executor.runAll();
    assertNotSame(rootInfo.utf8Writer(), initialRootWriter);
    assertSame(valuesInfo.utf8Writer(), initialValuesWriter);
    assertFinalCollectionFields(
        rootInfo.utf8Writer(), initialValuesWriter.getClass(), initialValuesWriter);
    assertEquals(new String(controlled.json.toJsonBytes(value), StandardCharsets.UTF_8), expected);
  }

  @Test
  public void utf8ShapeIgnoresPublishedChild() throws Exception {
    String input = "{\"creator\":{\"id\":1,\"name\":\"a\"},\"friends\":[{\"id\":2}]}";

    ControlledJson parentFirst = controlledJson();
    AsyncMixedParent first =
        parentFirst.json.fromJson(input.getBytes(StandardCharsets.UTF_8), AsyncMixedParent.class);
    parentFirst.executor.runAll();
    assertEquals(first.creator.id, 1);
    JsonTypeResolver firstResolver = primaryTypeResolver(parentFirst.json);
    JsonTypeInfo firstInfo =
        firstResolver.getTypeInfo(AsyncMixedParent.class, AsyncMixedParent.class);

    ControlledJson childFirst = controlledJson();
    childFirst.json.fromJson(
        "{\"id\":3,\"name\":\"child\"}".getBytes(StandardCharsets.UTF_8), AsyncCreator.class);
    childFirst.executor.runAll();
    AsyncMixedParent second =
        childFirst.json.fromJson(input.getBytes(StandardCharsets.UTF_8), AsyncMixedParent.class);
    childFirst.executor.runAll();
    assertEquals(second.friends.get(0).id, 2);
    JsonTypeResolver secondResolver = primaryTypeResolver(childFirst.json);
    JsonTypeInfo secondInfo =
        secondResolver.getTypeInfo(AsyncMixedParent.class, AsyncMixedParent.class);

    assertEquals(
        declaredFieldShape(firstInfo.utf8Reader()), declaredFieldShape(secondInfo.utf8Reader()));
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
    assertNotSame(writer, owner);
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
  public void mutualTypesUseCanonicalSlots() throws Exception {
    ForyJson json = ForyJson.builder().withAsyncCompilation(false).build();
    JsonTypeResolver resolver = primaryTypeResolver(json);
    ObjectCodec<MutualFirst> firstOwner = resolver.getObjectCodec(MutualFirst.class);
    ObjectCodec<MutualSecond> secondOwner = resolver.getObjectCodec(MutualSecond.class);
    JsonTypeInfo firstInfo = resolver.getTypeInfo(MutualFirst.class, MutualFirst.class);
    JsonTypeInfo secondInfo = resolver.getTypeInfo(MutualSecond.class, MutualSecond.class);

    assertCyclicSlot(firstInfo.stringWriter(), firstOwner, secondInfo);
    assertCyclicSlot(secondInfo.stringWriter(), secondOwner, firstInfo);
    assertCyclicSlot(firstInfo.utf8Writer(), firstOwner, secondInfo);
    assertCyclicSlot(secondInfo.utf8Writer(), secondOwner, firstInfo);
    assertCyclicSlot(firstInfo.latin1Reader(), firstOwner, secondInfo);
    assertCyclicSlot(secondInfo.latin1Reader(), secondOwner, firstInfo);
    assertCyclicSlot(firstInfo.utf16Reader(), firstOwner, secondInfo);
    assertCyclicSlot(secondInfo.utf16Reader(), secondOwner, firstInfo);
    assertCyclicSlot(firstInfo.utf8Reader(), firstOwner, secondInfo);
    assertCyclicSlot(secondInfo.utf8Reader(), secondOwner, firstInfo);
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

  private static void assertCapabilityFields(
      Object owner, Class<?> fieldType, Object expected, int expectedFields) throws Exception {
    int count = 0;
    for (Field field : owner.getClass().getDeclaredFields()) {
      if (field.getType() == fieldType) {
        field.setAccessible(true);
        if (field.get(owner) == expected) {
          count++;
        }
      }
    }
    assertEquals(count, expectedFields, owner.getClass().getName());
  }

  private static void assertCyclicSlot(Object capability, Object owner, JsonTypeInfo target)
      throws Exception {
    assertNotSame(capability, owner);
    int count = 0;
    for (Field field : capability.getClass().getDeclaredFields()) {
      if (field.getType() == JsonTypeInfo.class) {
        field.setAccessible(true);
        if (field.get(capability) == target) {
          assertTrue(Modifier.isFinal(field.getModifiers()));
          count++;
        }
      }
    }
    assertEquals(count, 1, capability.getClass().getName());
  }

  private static void assertCapabilities(JsonTypeInfo info, Object expected) {
    assertSame(info.stringWriter(), expected);
    assertSame(info.utf8Writer(), expected);
    assertSame(info.latin1Reader(), expected);
    assertSame(info.utf16Reader(), expected);
    assertSame(info.utf8Reader(), expected);
  }

  private static List<String> declaredFieldTypes(Object capability) {
    List<String> types = new ArrayList<>();
    for (Field field : capability.getClass().getDeclaredFields()) {
      types.add(field.getType().getName());
    }
    Collections.sort(types);
    return types;
  }

  private static List<String> declaredFieldShape(Object capability) {
    List<String> shape = new ArrayList<>();
    for (Field field : capability.getClass().getDeclaredFields()) {
      shape.add(
          field.getName()
              + ":"
              + field.getType().getName()
              + ":"
              + Modifier.isFinal(field.getModifiers()));
    }
    Collections.sort(shape);
    return shape;
  }

  private static void assertFinalField(Object owner, String name, Object expected)
      throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    assertTrue(Modifier.isFinal(field.getModifiers()));
    assertSame(field.get(owner), expected);
  }

  private static void assertInlineReader(Object[] readers, Object canonical) {
    assertNotNull(readers);
    assertEquals(readers.length, 1);
    assertNotNull(readers[0]);
    assertNotSame(readers[0], canonical);
    assertSame(readers[0].getClass(), canonical.getClass());
  }

  private static void assertFinalCollectionFields(
      Object root, Class<?> fieldType, Object... collections) throws Exception {
    int count = 0;
    for (Field field : root.getClass().getDeclaredFields()) {
      if (field.getType() == fieldType) {
        field.setAccessible(true);
        Object value = field.get(root);
        for (Object collection : collections) {
          if (value == collection) {
            assertTrue(Modifier.isFinal(field.getModifiers()));
            count++;
            break;
          }
        }
      }
    }
    assertEquals(count, collections.length);
  }

  private static String writeUtf8(Utf8WriterCodec<Object> codec, Object value) {
    Utf8JsonWriter writer = JsonTestSupport.newUtf8Writer();
    codec.writeUtf8(writer, value);
    return new String(writer.toJsonBytes(), StandardCharsets.UTF_8);
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
        if (field.get(parentReader) == childReader) {
          nestedReaders++;
        }
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
            ForyJson.DEFAULT_MAX_CACHED_FIELD_NAMES,
            1,
            2 * 1024 * 1024,
            codecs,
            Collections.<Class<?>, Class<?>>emptyMap(),
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

  public static final class AsyncCollections {
    public List<AsyncChild> children;
    public List<AsyncFriend> friends;
  }

  public static final class AsyncMixedParent {
    public AsyncCreator creator;
    public List<AsyncFriend> friends;
  }

  public static final class AsyncStringCollections {
    public List<String> values;
  }

  public static final class AsyncSetCollections {
    public Set<AsyncFriend> values;
  }

  public static final class AsyncCreatorParent {
    public final AsyncChild child;

    @JsonCreator({"child"})
    public AsyncCreatorParent(AsyncChild child) {
      this.child = child;
    }
  }

  public static final class AsyncGraphParent {
    public AsyncChild child;
  }

  public static final class AsyncFriend {
    public int id;
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
