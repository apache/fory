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

package org.apache.fory.pool;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.fory.AbstractThreadSafeFory;
import org.apache.fory.Fory;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.io.ForyInputStream;
import org.apache.fory.io.ForyReadableChannel;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.resolver.SharedRegistry;
import org.apache.fory.serializer.BufferCallback;
import org.apache.fory.util.LoaderBinding;

/**
 * A lightweight non-expiring {@link Fory} pool optimized for fast borrow and return.
 *
 * <p>The pool keeps at most {@code maxPoolSize} idle {@link Fory} instances for reuse after
 * operations complete. Bursts may still create more instances temporarily, but any returned
 * instance beyond the idle retention limit is dropped instead of retained.
 *
 * <p>Class loader control is based on the current thread context class loader (TCCL). {@link
 * #setClassLoader(ClassLoader)} updates the current thread TCCL, and {@link #getClassLoader()}
 * reads from TCCL with {@code defaultClassLoader} as a fallback when TCCL is null.
 */
public class FastForyPool extends AbstractThreadSafeFory {
  private static final int DEFAULT_POOL_SIZE = 1024;
  private final Function<ForyBuilder, Fory> foryFactory;
  private final SharedRegistry sharedRegistry;
  private final ConcurrentLinkedQueue<Fory> pool = new ConcurrentLinkedQueue<>();
  private final Object callbackLock = new Object();
  private final ClassLoader defaultClassLoader;
  private final int maxPoolSize;
  // Tracks remaining idle capacity, not the number of borrowed instances.
  private final Semaphore idleCapacity;
  private Consumer<Fory> factoryCallback = f -> {};
  private volatile boolean callbackRegistrationClosed;

  /** Creates a pool with a private shared registry and the default idle retention limit. */
  public FastForyPool(Function<ForyBuilder, Fory> foryFactory) {
    this(foryFactory, new SharedRegistry(), null, DEFAULT_POOL_SIZE);
  }

  /**
   * Creates a pool that reuses the provided shared registry and the default idle retention limit.
   */
  public FastForyPool(Function<ForyBuilder, Fory> foryFactory, SharedRegistry sharedRegistry) {
    this(foryFactory, sharedRegistry, null, DEFAULT_POOL_SIZE);
  }

  /**
   * Creates a pool with the provided shared registry and default class loader fallback.
   *
   * <p>The default class loader is used only when the current thread context class loader is null.
   */
  public FastForyPool(
      Function<ForyBuilder, Fory> foryFactory,
      SharedRegistry sharedRegistry,
      ClassLoader defaultClassLoader) {
    this(foryFactory, sharedRegistry, defaultClassLoader, DEFAULT_POOL_SIZE);
  }

  /**
   * Creates a pool with explicit shared state, class loader fallback, and idle retention limit.
   *
   * <p>{@code maxPoolSize} limits how many idle {@link Fory} instances are retained after
   * operations complete.
   */
  public FastForyPool(
      Function<ForyBuilder, Fory> foryFactory,
      SharedRegistry sharedRegistry,
      ClassLoader defaultClassLoader,
      int maxPoolSize) {
    this.foryFactory = Objects.requireNonNull(foryFactory);
    this.sharedRegistry = Objects.requireNonNull(sharedRegistry);
    this.defaultClassLoader = defaultClassLoader;
    if (maxPoolSize < 0) {
      throw new IllegalArgumentException(
          String.format("FastForyPool maxPoolSize must be >= 0, got %s", maxPoolSize));
    }
    this.maxPoolSize = maxPoolSize;
    idleCapacity = new Semaphore(maxPoolSize);
  }

  @Override
  public <R> R execute(Function<Fory, R> action) {
    Fory fory = acquire();
    try {
      return action.apply(fory);
    } finally {
      release(fory);
    }
  }

  @Override
  public void setClassLoader(ClassLoader classLoader) {
    setClassLoader(classLoader, LoaderBinding.StagingType.NO_STAGING);
  }

  @Override
  public void setClassLoader(ClassLoader classLoader, LoaderBinding.StagingType stagingType) {
    Thread.currentThread().setContextClassLoader(classLoader);
  }

  @Override
  public ClassLoader getClassLoader() {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    if (loader != null) {
      return loader;
    }
    return defaultClassLoader == null ? Fory.class.getClassLoader() : defaultClassLoader;
  }

  @Override
  public void clearClassLoader(ClassLoader loader) {}

  @Override
  public void registerCallback(Consumer<Fory> callback) {
    if (callbackRegistrationClosed) {
      throw new IllegalStateException(
          "registerCallback must be invoked before FastForyPool serialize/deserialize starts.");
    }
    synchronized (callbackLock) {
      if (callbackRegistrationClosed) {
        throw new IllegalStateException(
            "registerCallback must be invoked before FastForyPool serialize/deserialize starts.");
      }
      factoryCallback = factoryCallback.andThen(callback);
    }
  }

  @Override
  public byte[] serialize(Object obj) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.serialize(obj));
  }

  @Override
  public byte[] serialize(Object obj, BufferCallback callback) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.serialize(obj, callback));
  }

  @Override
  public MemoryBuffer serialize(Object obj, long address, int size) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.serialize(obj, address, size));
  }

  @Override
  public MemoryBuffer serialize(MemoryBuffer buffer, Object obj) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.serialize(buffer, obj));
  }

  @Override
  public MemoryBuffer serialize(MemoryBuffer buffer, Object obj, BufferCallback callback) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.serialize(buffer, obj, callback));
  }

  @Override
  public void serialize(OutputStream outputStream, Object obj) {
    callbackRegistrationClosed = true;
    execute(
        fory -> {
          fory.serialize(outputStream, obj);
          return null;
        });
  }

  @Override
  public void serialize(OutputStream outputStream, Object obj, BufferCallback callback) {
    callbackRegistrationClosed = true;
    execute(
        fory -> {
          fory.serialize(outputStream, obj, callback);
          return null;
        });
  }

  @Override
  public Object deserialize(ByteBuffer byteBuffer) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.deserialize(MemoryUtils.wrap(byteBuffer)));
  }

  @Override
  public Object deserialize(byte[] bytes) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.deserialize(bytes));
  }

  @Override
  public <T> T deserialize(byte[] bytes, Class<T> type) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.deserialize(bytes, type));
  }

  @Override
  public <T> T deserialize(MemoryBuffer buffer, Class<T> type) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.deserialize(buffer, type));
  }

  @Override
  public <T> T deserialize(ForyInputStream inputStream, Class<T> type) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.deserialize(inputStream, type));
  }

  @Override
  public <T> T deserialize(ForyReadableChannel channel, Class<T> type) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.deserialize(channel, type));
  }

  @Override
  public Object deserialize(byte[] bytes, Iterable<MemoryBuffer> outOfBandBuffers) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.deserialize(bytes, outOfBandBuffers));
  }

  @Override
  public Object deserialize(long address, int size) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.deserialize(address, size));
  }

  @Override
  public Object deserialize(MemoryBuffer buffer) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.deserialize(buffer));
  }

  @Override
  public Object deserialize(MemoryBuffer buffer, Iterable<MemoryBuffer> outOfBandBuffers) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.deserialize(buffer, outOfBandBuffers));
  }

  @Override
  public Object deserialize(ForyInputStream inputStream) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.deserialize(inputStream));
  }

  @Override
  public Object deserialize(ForyInputStream inputStream, Iterable<MemoryBuffer> outOfBandBuffers) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.deserialize(inputStream, outOfBandBuffers));
  }

  @Override
  public Object deserialize(ForyReadableChannel channel) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.deserialize(channel));
  }

  @Override
  public Object deserialize(ForyReadableChannel channel, Iterable<MemoryBuffer> outOfBandBuffers) {
    callbackRegistrationClosed = true;
    return execute(fory -> fory.deserialize(channel, outOfBandBuffers));
  }

  @Override
  public <T> T copy(T obj) {
    return execute(fory -> fory.copy(obj));
  }

  /** Borrows a pooled instance or creates a new one when the idle pool is empty. */
  private Fory acquire() {
    Fory fory = pool.poll();
    if (fory != null) {
      freeIdleCapacity();
      return fory;
    }
    synchronized (callbackLock) {
      ForyBuilder builder =
          new ForyBuilder().withSharedRegistry(sharedRegistry).withClassLoader(defaultClassLoader);
      fory = foryFactory.apply(builder);
      factoryCallback.accept(fory);
    }
    return fory;
  }

  /** Returns an instance to the idle pool only when there is idle capacity left to occupy. */
  private void release(Fory fory) {
    if (fory != null) {
      if (!tryOccupyIdleCapacity()) {
        return;
      }
      pool.add(fory);
    }
  }

  /** Visible for tests to validate idle retention accounting. */
  int pooledForyCount() {
    return maxPoolSize - idleCapacity.availablePermits();
  }

  /** Attempts to consume one unit of idle capacity before retaining a returned instance. */
  private boolean tryOccupyIdleCapacity() {
    return idleCapacity.tryAcquire();
  }

  /** Frees one unit of idle capacity after a pooled instance is borrowed. */
  private void freeIdleCapacity() {
    idleCapacity.release();
  }
}
