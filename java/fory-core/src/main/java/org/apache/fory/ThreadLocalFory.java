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

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.fory.annotation.Internal;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.io.ForyInputStream;
import org.apache.fory.io.ForyReadableChannel;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.resolver.SharedRegistry;
import org.apache.fory.serializer.BufferCallback;

/**
 * A thread safe serialization entrance for {@link Fory} by binding a {@link Fory} for every thread.
 * Note that the thread shouldn't be created and destroyed frequently, otherwise the {@link Fory}
 * will be created and destroyed frequently, which is slow.
 */
@ThreadSafe
public class ThreadLocalFory extends AbstractThreadSafeFory {
  private final Supplier<Fory> foryFactory;
  private final ThreadLocal<Fory> foryThreadLocal;
  private Consumer<Fory> factoryCallback;
  private final Map<Fory, Object> allFory;
  private final Object callbackLock = new Object();

  public ThreadLocalFory(Function<ForyBuilder, Fory> factory) {
    SharedRegistry sharedRegistry = new SharedRegistry();
    foryFactory = () -> factory.apply(Fory.builder().withSharedRegistry(sharedRegistry));
    factoryCallback = f -> {};
    allFory = Collections.synchronizedMap(new WeakHashMap<>());
    foryThreadLocal = ThreadLocal.withInitial(this::newFory);
    // 1. init and warm for current thread.
    // Fory creation took about 1~2 ms, but first creation
    // in a process load some classes which is not cheap.
    // 2. Make fory generate code at graalvm build time.
    foryThreadLocal.get();
  }

  private Fory newFory() {
    synchronized (callbackLock) {
      Fory fory = foryFactory.get();
      factoryCallback.accept(fory);
      allFory.put(fory, null);
      return fory;
    }
  }

  private Fory currentFory() {
    return foryThreadLocal.get();
  }

  @Internal
  @Override
  public void registerCallback(Consumer<Fory> callback) {
    synchronized (callbackLock) {
      factoryCallback = factoryCallback.andThen(callback);
      synchronized (allFory) {
        allFory.keySet().forEach(callback);
      }
    }
  }

  @Override
  public <R> R execute(Function<Fory, R> action) {
    return action.apply(currentFory());
  }

  @Override
  public byte[] serialize(Object obj) {
    return currentFory().serialize(obj);
  }

  @Override
  public byte[] serialize(Object obj, BufferCallback callback) {
    return currentFory().serialize(obj, callback);
  }

  @Override
  public MemoryBuffer serialize(Object obj, long address, int size) {
    return currentFory().serialize(obj, address, size);
  }

  @Override
  public MemoryBuffer serialize(MemoryBuffer buffer, Object obj) {
    return currentFory().serialize(buffer, obj);
  }

  @Override
  public MemoryBuffer serialize(MemoryBuffer buffer, Object obj, BufferCallback callback) {
    return currentFory().serialize(buffer, obj, callback);
  }

  @Override
  public void serialize(OutputStream outputStream, Object obj) {
    currentFory().serialize(outputStream, obj);
  }

  @Override
  public void serialize(OutputStream outputStream, Object obj, BufferCallback callback) {
    currentFory().serialize(outputStream, obj, callback);
  }

  @Override
  public Object deserialize(byte[] bytes) {
    return currentFory().deserialize(bytes);
  }

  @Override
  public <T> T deserialize(byte[] bytes, Class<T> type) {
    return currentFory().deserialize(bytes, type);
  }

  @Override
  public <T> T deserialize(MemoryBuffer buffer, Class<T> type) {
    return currentFory().deserialize(buffer, type);
  }

  @Override
  public <T> T deserialize(ForyInputStream inputStream, Class<T> type) {
    return currentFory().deserialize(inputStream, type);
  }

  @Override
  public <T> T deserialize(ForyReadableChannel channel, Class<T> type) {
    return currentFory().deserialize(channel, type);
  }

  @Override
  public Object deserialize(byte[] bytes, Iterable<MemoryBuffer> outOfBandBuffers) {
    return currentFory().deserialize(bytes, outOfBandBuffers);
  }

  @Override
  public Object deserialize(long address, int size) {
    return currentFory().deserialize(address, size);
  }

  @Override
  public Object deserialize(MemoryBuffer buffer) {
    return currentFory().deserialize(buffer);
  }

  @Override
  public Object deserialize(ByteBuffer byteBuffer) {
    return currentFory().deserialize(MemoryUtils.wrap(byteBuffer));
  }

  @Override
  public Object deserialize(MemoryBuffer buffer, Iterable<MemoryBuffer> outOfBandBuffers) {
    return currentFory().deserialize(buffer, outOfBandBuffers);
  }

  @Override
  public Object deserialize(ForyInputStream inputStream) {
    return currentFory().deserialize(inputStream);
  }

  @Override
  public Object deserialize(ForyInputStream inputStream, Iterable<MemoryBuffer> outOfBandBuffers) {
    return currentFory().deserialize(inputStream, outOfBandBuffers);
  }

  @Override
  public Object deserialize(ForyReadableChannel channel) {
    return currentFory().deserialize(channel);
  }

  @Override
  public Object deserialize(ForyReadableChannel channel, Iterable<MemoryBuffer> outOfBandBuffers) {
    return currentFory().deserialize(channel, outOfBandBuffers);
  }

  @Override
  public <T> T copy(T obj) {
    return currentFory().copy(obj);
  }
}
