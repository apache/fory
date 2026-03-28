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

package org.apache.fory;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Config;
import org.apache.fory.config.LongEncoding;
import org.apache.fory.config.UnknownEnumValueStrategy;
import org.testng.annotations.Test;

public class VirtualThreadSafeForyTest {

  static class CustomClassLoader extends ClassLoader {
    CustomClassLoader(ClassLoader parent) {
      super(parent);
    }
  }

  @Test
  public void testPlatformDetectsVirtualThreads() throws InterruptedException {
    AtomicReference<Boolean> isVirtual = new AtomicReference<>();
    Thread thread =
        Thread.startVirtualThread(() -> isVirtual.set(Thread.currentThread().isVirtual()));
    thread.join();
    assertTrue(Boolean.TRUE.equals(isVirtual.get()));
  }

  @Test
  public void testBuildThreadSafeForyUsesFastPoolForVirtualThreadClassLoader()
      throws InterruptedException {
    ThreadSafeFory fory = Fory.builder().requireClassRegistration(false).buildThreadSafeFory();
    AtomicReference<Throwable> error = new AtomicReference<>();
    Thread thread =
        Thread.startVirtualThread(
            () -> {
              ClassLoader original = Thread.currentThread().getContextClassLoader();
              ClassLoader classLoader = new CustomClassLoader(original);
              try {
                assertTrue(Thread.currentThread().isVirtual());
                fory.setClassLoader(classLoader);
                assertSame(fory.getClassLoader(), classLoader);
                fory.clearClassLoader(classLoader);
                assertSame(Thread.currentThread().getContextClassLoader(), original);
              } catch (Throwable t) {
                error.set(t);
              } finally {
                Thread.currentThread().setContextClassLoader(original);
              }
            });
    thread.join();
    if (error.get() != null) {
      throw new AssertionError(error.get());
    }
  }

  @Test
  public void testBuildVirtualThreadSafeForyUsesThreadContextClassLoaderAndSerializes()
      throws InterruptedException {
    ThreadSafeFory fory =
        Fory.builder().requireClassRegistration(false).buildVirtualThreadSafeFory();
    AtomicReference<Throwable> error = new AtomicReference<>();
    Thread thread =
        Thread.startVirtualThread(
            () -> {
              ClassLoader original = Thread.currentThread().getContextClassLoader();
              ClassLoader classLoader = new CustomClassLoader(original);
              try {
                assertTrue(Thread.currentThread().isVirtual());
                fory.setClassLoader(classLoader);
                assertSame(Thread.currentThread().getContextClassLoader(), classLoader);
                assertSame(fory.getClassLoader(), classLoader);
                byte[] bytes = fory.serialize("abc");
                assertEquals(fory.deserialize(bytes), "abc");
              } catch (Throwable t) {
                error.set(t);
              } finally {
                Thread.currentThread().setContextClassLoader(original);
              }
            });
    thread.join();
    if (error.get() != null) {
      throw new AssertionError(error.get());
    }
  }

  @Test
  public void testBuildVirtualThreadSafeForyReplaysBuilderConfigToPooledFory() {
    ThreadSafeFory fory =
        Fory.builder()
            .withName("virtual-thread-replay")
            .withRefTracking(true)
            .withRefCopy(true)
            .ignoreStringRef(false)
            .withUnknownEnumValueStrategy(UnknownEnumValueStrategy.RETURN_NULL)
            .serializeEnumByName(true)
            .ignoreTimeRef(false)
            .withIntCompressed(false)
            .withLongCompressed(LongEncoding.FIXED)
            .withIntArrayCompressed(true)
            .withLongArrayCompressed(true)
            .withStringCompressed(true)
            .withWriteNumUtf16BytesForUtf8Encoding(false)
            .withBufferSizeLimitBytes(2048)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withJdkClassSerializableCheck(false)
            .registerGuavaTypes(false)
            .requireClassRegistration(false)
            .suppressClassRegistrationWarnings(false)
            .withMetaShare(true)
            .withScopedMetaShare(true)
            .withDeserializeUnknownClass(true)
            .withCodegen(false)
            .withAsyncCompilation(true)
            .withMaxDepth(64)
            .withMapRefLoadFactor(0.75f)
            .buildVirtualThreadSafeFory(1);
    fory.execute(
        f -> {
          Config config = f.getConfig();
          assertEquals(config.getName(), "virtual-thread-replay");
          assertTrue(config.trackingRef());
          assertTrue(config.copyRef());
          assertFalse(config.isStringRefIgnored());
          assertEquals(config.getUnknownEnumValueStrategy(), UnknownEnumValueStrategy.RETURN_NULL);
          assertTrue(config.serializeEnumByName());
          assertFalse(config.isTimeRefIgnored());
          assertFalse(config.compressInt());
          assertEquals(config.longEncoding(), LongEncoding.FIXED);
          assertTrue(config.compressIntArray());
          assertTrue(config.compressLongArray());
          assertTrue(config.compressString());
          assertFalse(config.writeNumUtf16BytesForUtf8Encoding());
          assertEquals(config.bufferSizeLimitBytes(), 2048);
          assertEquals(config.getCompatibleMode(), CompatibleMode.COMPATIBLE);
          assertFalse(config.checkJdkClassSerializable());
          assertFalse(config.registerGuavaTypes());
          assertFalse(config.requireClassRegistration());
          assertFalse(config.suppressClassRegistrationWarnings());
          assertTrue(config.isMetaShareEnabled());
          assertTrue(config.isScopedMetaShareEnabled());
          assertTrue(config.deserializeUnknownClass());
          assertFalse(config.isCodeGenEnabled());
          assertTrue(config.isAsyncCompilationEnabled());
          assertEquals(config.maxDepth(), 64);
          assertEquals(config.mapRefLoadFactor(), 0.75f);
          assertTrue(config.forVirtualThread());
          return null;
        });
  }
}
