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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
import org.apache.fory.io.ClassLoaderObjectInputStream;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.MetaContext;
import org.apache.fory.serializer.BufferObject;
import org.testng.Assert;
import org.testng.Assert.ThrowingRunnable;
import org.testng.annotations.DataProvider;

/** Fory unit test base class. */
@SuppressWarnings("unchecked")
public abstract class ForyTestBase {
  private static final String XLANG_DATAPROVIDER_THREAD_COUNT = "8";
  private static final ThreadLocal<Fory> javaForyLocal =
      ThreadLocal.withInitial(
          () ->
              Fory.builder()
                  .withLanguage(Language.JAVA)
                  .requireClassRegistration(false)
                  .suppressClassRegistrationWarnings(true)
                  .build());

  public static Fory getJavaFory() {
    return javaForyLocal.get();
  }

  public static ForyBuilder builder() {
    return Fory.builder()
        .withLanguage(Language.JAVA)
        .suppressClassRegistrationWarnings(true)
        .requireClassRegistration(false);
  }

  @DataProvider
  public static Object[] foryCopyConfig() {
    return new Object[][] {
      {
        builder()
            .withRefCopy(true)
            .withLanguage(Language.JAVA)
            .withJdkClassSerializableCheck(false)
            .withCodegen(false)
            .build()
      },
      {
        builder()
            .withRefCopy(true)
            .withLanguage(Language.JAVA)
            .withJdkClassSerializableCheck(false)
            .withCodegen(true)
            .build()
      },
    };
  }

  @DataProvider
  public static Object[][] referenceTrackingConfig() {
    return new Object[][] {{false}, {true}};
  }

  @DataProvider
  public static Object[][] trackingRefFory() {
    return new Object[][] {
      {
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withCodegen(false)
            .requireClassRegistration(false)
            .suppressClassRegistrationWarnings(true)
            .build()
      },
      {
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .withCodegen(false)
            .requireClassRegistration(false)
            .suppressClassRegistrationWarnings(true)
            .build()
      }
    };
  }

  @DataProvider
  public static Object[][] endian() {
    return new Object[][] {{false}, {true}};
  }

  @DataProvider
  public static Object[][] enableCodegen() {
    return new Object[][] {{false}, {true}};
  }

  @DataProvider(name = "enableCodegenParallel", parallel = true)
  public static Object[][] enableCodegenParallel() {
    System.setProperty("dataproviderthreadcount", XLANG_DATAPROVIDER_THREAD_COUNT);
    return enableCodegen();
  }

  @DataProvider
  public static Object[][] compatible() {
    return new Object[][] {{false}, {true}};
  }

  @DataProvider
  public static Object[][] compressNumber() {
    return new Object[][] {{false}, {true}};
  }

  @DataProvider
  public static Object[][] scopedMetaShare() {
    return new Object[][] {{false}, {true}};
  }

  @DataProvider
  public static Object[][] oneBoolOption() {
    return new Object[][] {{false}, {true}};
  }

  @DataProvider
  public static Object[][] twoBoolOptions() {
    return new Object[][] {{false, false}, {true, false}, {false, true}, {true, true}};
  }

  @DataProvider
  public static Object[][] compressNumberAndCodeGen() {
    return new Object[][] {{false, false}, {true, false}, {false, true}, {true, true}};
  }

  @DataProvider
  public static Object[][] compressNumberScopedMetaShare() {
    return new Object[][] {{false, false}, {true, false}, {false, true}, {true, true}};
  }

  @DataProvider
  public static Object[][] refTrackingAndCompressNumber() {
    return new Object[][] {{false, false}, {true, false}, {false, true}, {true, true}};
  }

  @DataProvider
  public static Object[][] crossLanguageReferenceTrackingConfig() {
    return new Object[][] {
      {false, Language.JAVA},
      {true, Language.JAVA},
      {false, Language.XLANG},
      {true, Language.XLANG}
    };
  }

  @DataProvider
  public static Object[][] language() {
    return new Object[][] {{Language.JAVA}, {Language.XLANG}};
  }

  @DataProvider(name = "javaFory")
  public static Object[][] javaForyConfig() {
    return new Object[][] {
      {
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withCodegen(false)
            .requireClassRegistration(false)
            .suppressClassRegistrationWarnings(true)
            .build()
      },
      {
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .withCodegen(false)
            .requireClassRegistration(false)
            .suppressClassRegistrationWarnings(true)
            .build()
      },
      {
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(true)
            .withCodegen(true)
            .requireClassRegistration(false)
            .suppressClassRegistrationWarnings(true)
            .build()
      },
      {
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .withCodegen(true)
            .requireClassRegistration(false)
            .suppressClassRegistrationWarnings(true)
            .build()
      },
    };
  }

  @DataProvider
  public static Object[][] javaForyKVCompatible() {
    Supplier<ForyBuilder> builder =
        () ->
            Fory.builder()
                .withLanguage(Language.JAVA)
                .requireClassRegistration(false)
                .suppressClassRegistrationWarnings(true)
                .withScopedMetaShare(false);
    return new Object[][] {
      {
        builder
            .get()
            .withRefTracking(true)
            .withCodegen(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build()
      },
      {
        builder
            .get()
            .withRefTracking(false)
            .withCodegen(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build()
      },
      {
        builder
            .get()
            .withRefTracking(true)
            .withCodegen(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build()
      },
      {
        builder
            .get()
            .withRefTracking(false)
            .withCodegen(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .build()
      },
    };
  }

  @DataProvider
  public static Object[][] basicMultiConfigFory() {
    return Sets.cartesianProduct(
            ImmutableSet.of(true, false), // trackingRef
            ImmutableSet.of(true, false), // codeGen
            ImmutableSet.of(true, false)) // compatible
        .stream()
        .map(List::toArray)
        .toArray(Object[][]::new);
  }

  public static void serDeCheckSerializerAndEqual(Fory fory, Object obj, String classRegex) {
    Assert.assertEquals(serDeCheckSerializer(fory, obj, classRegex), obj);
  }

  public static <T> T serDeCheckSerializer(Fory fory, Object obj, String classRegex) {
    byte[] bytes = fory.serialize(obj);
    String serializerName = fory.getClassResolver().getSerializerClass(obj.getClass()).getName();
    Matcher matcher = Pattern.compile(classRegex).matcher(serializerName);
    Assert.assertTrue(matcher.find());
    return (T) fory.deserialize(bytes);
  }

  public static Object serDeObject(Fory fory1, Fory fory2, Object obj) {
    byte[] bytes = fory1.serialize(obj);
    return fory2.deserialize(bytes);
  }

  public static <T> T serDe(Fory fory1, Fory fory2, T obj) {
    byte[] bytes = fory1.serialize(obj);
    return (T) fory2.deserialize(bytes);
  }

  public static <T> T serDeCheckTyped(Fory fory1, Fory fory2, T obj) {
    T o = serDeTyped(fory1, fory2, obj);
    Assert.assertEquals(o, obj);
    return o;
  }

  public static <T> T serDeCheck(Fory fory1, Fory fory2, T obj) {
    T o = serDe(fory1, fory2, obj);
    Assert.assertEquals(o, obj);
    return o;
  }

  public static <T> T serDeCheck(Fory fory, T obj) {
    T o = serDe(fory, obj);
    Assert.assertEquals(o, obj);
    return o;
  }

  public static <T> T serDe(Fory fory, T obj) {
    try {
      byte[] bytes = fory.serialize(obj);
      return (T) (fory.deserialize(bytes));
    } catch (Throwable t) {
      // Catch for add breakpoint for debugging.
      throw t;
    }
  }

  public static Object serDe(Fory fory1, Fory fory2, MemoryBuffer buffer, Object obj) {
    fory1.serialize(buffer, obj);
    return fory2.deserialize(buffer);
  }

  public static Object serDeCheckIndex(Fory fory1, Fory fory2, MemoryBuffer buffer, Object obj) {
    fory1.serialize(buffer, obj);
    Object newObj = fory2.deserialize(buffer);
    Assert.assertEquals(buffer.writerIndex(), buffer.readerIndex());
    return newObj;
  }

  @SuppressWarnings("unchecked")
  public static <T> T serDeTyped(Fory fory, T obj) {
    byte[] bytes = fory.serialize(obj);
    return (T) fory.deserialize(bytes, obj.getClass());
  }

  @SuppressWarnings("unchecked")
  public static <T> T serDeTyped(Fory fory1, Fory fory2, T obj) {
    byte[] bytes = fory1.serialize(obj);
    return (T) fory2.deserialize(bytes, obj.getClass());
  }

  public static void copyCheck(Fory fory, Object obj) {
    Object copy = fory.copy(obj);
    Assert.assertEquals(obj, copy);
    Assert.assertNotSame(obj, copy);
  }

  public static void copyCheckWithoutSame(Fory fory, Object obj) {
    Object copy = fory.copy(obj);
    Assert.assertEquals(obj, copy);
  }

  public static void roundCheck(Fory fory1, Fory fory2, Object o) {
    roundCheck(fory1, fory2, o, Function.identity());
  }

  public static void roundCheck(
      Fory fory1, Fory fory2, Object o, Function<Object, Object> compareHook) {
    byte[] bytes1 = fory1.serialize(o);
    Object o1 = fory2.deserialize(bytes1);
    Assert.assertEquals(compareHook.apply(o1), compareHook.apply(o));
    byte[] bytes2 = fory2.serialize(o1);
    Object o2 = fory1.deserialize(bytes2);
    Assert.assertEquals(compareHook.apply(o2), compareHook.apply(o));
  }

  public static Object serDeMetaShared(Fory fory, Object obj) {
    MetaContext context = new MetaContext();
    fory.getSerializationContext().setMetaContext(context);
    byte[] bytes = fory.serialize(obj);
    fory.getSerializationContext().setMetaContext(context);
    return fory.deserialize(bytes);
  }

  public static byte[] jdkSerialize(Object o) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(bos)) {
      objectOutputStream.writeObject(o);
      objectOutputStream.flush();
      return bos.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static Object jdkDeserialize(byte[] data) {
    try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
        ObjectInputStream objectInputStream =
            new ClassLoaderObjectInputStream(Thread.currentThread().getContextClassLoader(), bis)) {
      return objectInputStream.readObject();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static Object serDeOutOfBand(AtomicInteger counter, Fory fory1, Fory fory2, Object obj) {
    List<BufferObject> bufferObjects = new ArrayList<>();
    byte[] bytes =
        fory1.serialize(
            obj,
            o -> {
              if (counter.incrementAndGet() % 2 == 0) {
                bufferObjects.add(o);
                return false;
              } else {
                return true;
              }
            });
    List<MemoryBuffer> buffers =
        bufferObjects.stream().map(BufferObject::toBuffer).collect(Collectors.toList());
    return fory2.deserialize(bytes, buffers);
  }

  /** Update serialization depth by <code>diff</code>. */
  protected void increaseForyDepth(Fory fory, int diff) {
    long offset = ReflectionUtils.getFieldOffset(Fory.class, "depth");
    int depth = Platform.getInt(fory, offset);
    Platform.putInt(fory, offset, depth + diff);
  }

  public static <T extends Throwable> void assertThrowsCause(
      Class<T> throwableClass, ThrowingRunnable runnable) {
    try {
      runnable.run();
    } catch (Throwable t) {
      Throwable cause = t.getCause();
      Assert.assertNotNull(cause);
      if (throwableClass.isInstance(cause)) {
        return;
      } else {
        throw new AssertionError(
            String.format(
                "Expected %s to be thrown, but %s was thrown",
                throwableClass.getSimpleName(), cause.getClass().getSimpleName()),
            cause);
      }
    }
    throw new AssertionError(
        String.format(
            "Expected %s to be thrown, but nothing was thrown", throwableClass.getSimpleName()));
  }
}
