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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.MetaWriteContext;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.resolver.SharedRegistry;
import org.apache.fory.test.bean.BeanA;
import org.apache.fory.test.bean.Foo;
import org.testng.annotations.Test;

@Test(enabled = false)
public class ForyInitPerf {
  private static final int DEFAULT_CREATION_COUNT = 5_000;
  private static final int DEFAULT_MEMORY_COUNT = 128;
  private static final int WARMUP_COUNT = 500;
  private static final int GC_ROUNDS = 3;
  private static final long GC_SLEEP_MILLIS = 200L;
  private static volatile Object sink;

  public static void main(String[] args) throws Exception {
    LoggerFactory.disableLogging();
    ForyInitPerf perf = new ForyInitPerf();
    String mode = args.length == 0 ? "all" : args[0];
    switch (mode) {
      case "all":
        perf.runCreationBenchmark("private-registry", false, DEFAULT_CREATION_COUNT);
        perf.runCreationBenchmark("shared-registry", true, DEFAULT_CREATION_COUNT);
        perf.runMemoryBenchmark("private-registry", false, DEFAULT_MEMORY_COUNT);
        perf.runMemoryBenchmark("shared-registry", true, DEFAULT_MEMORY_COUNT);
        return;
      case "creation-private":
        perf.runCreationBenchmark(
            "private-registry", false, parseCount(args, DEFAULT_CREATION_COUNT));
        return;
      case "creation-shared":
        perf.runCreationBenchmark(
            "shared-registry", true, parseCount(args, DEFAULT_CREATION_COUNT));
        return;
      case "memory-private":
        perf.runMemoryBenchmark("private-registry", false, parseCount(args, DEFAULT_MEMORY_COUNT));
        return;
      case "memory-shared":
        perf.runMemoryBenchmark("shared-registry", true, parseCount(args, DEFAULT_MEMORY_COUNT));
        return;
      default:
        throw new IllegalArgumentException("Unknown mode " + mode);
    }
  }

  private void runCreationBenchmark(String name, boolean useSharedRegistry, int count)
      throws Exception {
    BenchmarkContext warmupContext = newBenchmarkContext();
    for (int i = 0; i < WARMUP_COUNT; i++) {
      sink = newFory(warmupContext, useSharedRegistry);
    }
    sink = null;
    BenchmarkContext benchmarkContext = newBenchmarkContext();
    long checksum = 0;
    long start = System.nanoTime();
    for (int i = 0; i < count; i++) {
      Fory fory = newFory(benchmarkContext, useSharedRegistry);
      checksum += System.identityHashCode(fory);
    }
    long elapsedNanos = System.nanoTime() - start;
    sink = checksum;
    double elapsedMillis = elapsedNanos / 1_000_000.0;
    double tps = count * 1_000_000_000.0 / elapsedNanos;
    System.out.printf(
        "%s create %d fory in %.3f ms, throughput %.2f ops/s%n", name, count, elapsedMillis, tps);
  }

  private void runMemoryBenchmark(String name, boolean useSharedRegistry, int count)
      throws Exception {
    BenchmarkContext benchmarkContext = newBenchmarkContext();
    if (useSharedRegistry) {
      warmSharedRegistry(benchmarkContext);
    }
    forceGc();
    long before = usedMemory();
    List<Fory> foryList = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      Fory fory = newFory(benchmarkContext, useSharedRegistry);
      initializeFory(fory);
      foryList.add(fory);
    }
    sink = foryList;
    forceGc();
    long retainedBytes = usedMemory() - before;
    System.out.printf(
        "%s retained %d bytes for %d initialized fory, %.2f bytes/fory%n",
        name, retainedBytes, count, retainedBytes / (double) count);
    sink = null;
    forceGc();
  }

  private void warmSharedRegistry(BenchmarkContext benchmarkContext) {
    Fory warmupFory = newFory(benchmarkContext, true);
    initializeFory(warmupFory);
  }

  private static void initializeFory(Fory fory) {
    fory.setMetaWriteContext(new MetaWriteContext());
    byte[] beanBytes = fory.serialize(BeanA.createBeanA(2));
    fory.setMetaReadContext(new MetaReadContext());
    fory.deserialize(beanBytes);
    fory.setMetaWriteContext(new MetaWriteContext());
    byte[] fooBytes = fory.serialize(Foo.create());
    fory.setMetaReadContext(new MetaReadContext());
    fory.deserialize(fooBytes);
  }

  private static BenchmarkContext newBenchmarkContext() throws Exception {
    ForyBuilder builder =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withNumberCompressed(true)
            .withMetaShare(true)
            .requireClassRegistration(false)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withAsyncCompilation(false)
            .withCodegen(true);
    finishBuilder(builder);
    return new BenchmarkContext(builder, benchmarkClassLoader(), new SharedRegistry());
  }

  private static Fory newFory(BenchmarkContext context, boolean useSharedRegistry) {
    SharedRegistry sharedRegistry =
        useSharedRegistry ? context.sharedRegistry : new SharedRegistry();
    return new Fory(context.builder, context.classLoader, sharedRegistry);
  }

  private static void finishBuilder(ForyBuilder builder)
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    Method finishMethod = ForyBuilder.class.getDeclaredMethod("finish");
    finishMethod.setAccessible(true);
    finishMethod.invoke(builder);
  }

  private static ClassLoader benchmarkClassLoader() {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    return loader != null ? loader : Fory.class.getClassLoader();
  }

  private static void forceGc() throws InterruptedException {
    for (int i = 0; i < GC_ROUNDS; i++) {
      System.gc();
      System.runFinalization();
      Thread.sleep(GC_SLEEP_MILLIS);
    }
  }

  private static long usedMemory() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  private static int parseCount(String[] args, int defaultCount) {
    return args.length > 1 ? Integer.parseInt(args[1]) : defaultCount;
  }

  private static final class BenchmarkContext {
    private final ForyBuilder builder;
    private final ClassLoader classLoader;
    private final SharedRegistry sharedRegistry;

    private BenchmarkContext(
        ForyBuilder builder, ClassLoader classLoader, SharedRegistry sharedRegistry) {
      this.builder = builder;
      this.classLoader = classLoader;
      this.sharedRegistry = sharedRegistry;
    }
  }
}
