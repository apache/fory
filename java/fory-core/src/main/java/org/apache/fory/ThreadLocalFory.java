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
import javax.annotation.concurrent.ThreadSafe;
import org.apache.fory.annotation.Internal;
import org.apache.fory.io.ForyInputStream;
import org.apache.fory.io.ForyReadableChannel;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.serializer.BufferCallback;
import org.apache.fory.util.LoaderBinding;
import org.apache.fory.util.LoaderBinding.StagingType;

/**
 * A thread safe serialization entrance for {@link Fory} by binding a {@link Fory} for every thread.
 * Note that the thread shouldn't be created and destroyed frequently, otherwise the {@link Fory}
 * will be created and destroyed frequently, which is slow.
 */
@ThreadSafe
public class ThreadLocalFory extends AbstractThreadSafeFory {
  private final ThreadLocal<MemoryBuffer> bufferLocal =
      ThreadLocal.withInitial(() -> MemoryUtils.buffer(32));

  private final ThreadLocal<LoaderBinding> bindingThreadLocal;
  private Consumer<Fory> factoryCallback;
  private final Map<LoaderBinding, Object> allFory;

  private ClassLoader classLoader;
  private final Object callbackLock = new Object();

  public ThreadLocalFory(Function<ClassLoader, Fory> foryFactory) {
    factoryCallback = f -> {};
    allFory = Collections.synchronizedMap(new WeakHashMap<>());
    bindingThreadLocal =
        ThreadLocal.withInitial(
            () -> {
              LoaderBinding binding = new LoaderBinding(foryFactory);
              binding.setBindingCallback(factoryCallback);
              ClassLoader cl =
                  classLoader == null
                      ? Thread.currentThread().getContextClassLoader()
                      : classLoader;
              binding.setClassLoader(cl);
              allFory.put(binding, null);
              return binding;
            });
    // 1. init and warm for current thread.
    // Fory creation took about 1~2 ms, but first creation
    // in a process load some classes which is not cheap.
    // 2. Make fory generate code at graalvm build time.
    Fory fory = bindingThreadLocal.get().get();
  }

  @Internal
  @Override
  public void registerCallback(Consumer<Fory> callback) {
    synchronized (callbackLock) {
      factoryCallback = factoryCallback.andThen(callback);
      for (LoaderBinding binding : allFory.keySet()) {
        binding.visitAllFory(callback);
        binding.setBindingCallback(factoryCallback);
      }
    }
  }

  @Override
  public <R> R execute(Function<Fory, R> action) {
    Fory fory = bindingThreadLocal.get().get();
    return action.apply(fory);
  }

  @Override
  public byte[] serialize(Object obj) {
    MemoryBuffer buffer = bufferLocal.get();
    buffer.writerIndex(0);
    bindingThreadLocal.get().get().serialize(buffer, obj);
    return buffer.getBytes(0, buffer.writerIndex());
  }

  @Override
  public byte[] serialize(Object obj, BufferCallback callback) {
    MemoryBuffer buffer = bufferLocal.get();
    buffer.writerIndex(0);
    bindingThreadLocal.get().get().serialize(buffer, obj, callback);
    return buffer.getBytes(0, buffer.writerIndex());
  }

  @Override
  public MemoryBuffer serialize(Object obj, long address, int size) {
    return bindingThreadLocal.get().get().serialize(obj, address, size);
  }

  @Override
  public MemoryBuffer serialize(MemoryBuffer buffer, Object obj) {
    return bindingThreadLocal.get().get().serialize(buffer, obj);
  }

  @Override
  public MemoryBuffer serialize(MemoryBuffer buffer, Object obj, BufferCallback callback) {
    return bindingThreadLocal.get().get().serialize(buffer, obj, callback);
  }

  @Override
  public void serialize(OutputStream outputStream, Object obj) {
    bindingThreadLocal.get().get().serialize(outputStream, obj);
  }

  @Override
  public void serialize(OutputStream outputStream, Object obj, BufferCallback callback) {
    bindingThreadLocal.get().get().serialize(outputStream, obj, callback);
  }

  @Override
  public Object deserialize(byte[] bytes) {
    return bindingThreadLocal.get().get().deserialize(bytes);
  }

  @Override
  public <T> T deserialize(byte[] bytes, Class<T> type) {
    return bindingThreadLocal.get().get().deserialize(bytes, type);
  }

  @Override
  public Object deserialize(byte[] bytes, Iterable<MemoryBuffer> outOfBandBuffers) {
    return bindingThreadLocal.get().get().deserialize(bytes, outOfBandBuffers);
  }

  @Override
  public Object deserialize(long address, int size) {
    return bindingThreadLocal.get().get().deserialize(address, size);
  }

  @Override
  public Object deserialize(MemoryBuffer buffer) {
    return bindingThreadLocal.get().get().deserialize(buffer);
  }

  @Override
  public Object deserialize(ByteBuffer byteBuffer) {
    return bindingThreadLocal.get().get().deserialize(MemoryUtils.wrap(byteBuffer));
  }

  @Override
  public Object deserialize(MemoryBuffer buffer, Iterable<MemoryBuffer> outOfBandBuffers) {
    return bindingThreadLocal.get().get().deserialize(buffer, outOfBandBuffers);
  }

  @Override
  public Object deserialize(ForyInputStream inputStream) {
    return bindingThreadLocal.get().get().deserialize(inputStream);
  }

  @Override
  public Object deserialize(ForyInputStream inputStream, Iterable<MemoryBuffer> outOfBandBuffers) {
    return bindingThreadLocal.get().get().deserialize(inputStream, outOfBandBuffers);
  }

  @Override
  public Object deserialize(ForyReadableChannel channel) {
    return bindingThreadLocal.get().get().deserialize(channel);
  }

  @Override
  public Object deserialize(ForyReadableChannel channel, Iterable<MemoryBuffer> outOfBandBuffers) {
    return bindingThreadLocal.get().get().deserialize(channel, outOfBandBuffers);
  }

  @Override
  public byte[] serializeJavaObject(Object obj) {
    return bindingThreadLocal.get().get().serializeJavaObject(obj);
  }

  @Override
  public void serializeJavaObject(MemoryBuffer buffer, Object obj) {
    bindingThreadLocal.get().get().serializeJavaObject(buffer, obj);
  }

  @Override
  public void serializeJavaObject(OutputStream outputStream, Object obj) {
    bindingThreadLocal.get().get().serializeJavaObject(outputStream, obj);
  }

  @Override
  public <T> T deserializeJavaObject(byte[] data, Class<T> cls) {
    return bindingThreadLocal.get().get().deserializeJavaObject(data, cls);
  }

  @Override
  public <T> T deserializeJavaObject(MemoryBuffer buffer, Class<T> cls) {
    return bindingThreadLocal.get().get().deserializeJavaObject(buffer, cls);
  }

  @Override
  public <T> T deserializeJavaObject(ForyInputStream inputStream, Class<T> cls) {
    return bindingThreadLocal.get().get().deserializeJavaObject(inputStream, cls);
  }

  @Override
  public <T> T deserializeJavaObject(ForyReadableChannel channel, Class<T> cls) {
    return bindingThreadLocal.get().get().deserializeJavaObject(channel, cls);
  }

  @Override
  public byte[] serializeJavaObjectAndClass(Object obj) {
    return bindingThreadLocal.get().get().serializeJavaObjectAndClass(obj);
  }

  @Override
  public void serializeJavaObjectAndClass(MemoryBuffer buffer, Object obj) {
    bindingThreadLocal.get().get().serializeJavaObjectAndClass(buffer, obj);
  }

  @Override
  public void serializeJavaObjectAndClass(OutputStream outputStream, Object obj) {
    bindingThreadLocal.get().get().serializeJavaObjectAndClass(outputStream, obj);
  }

  @Override
  public Object deserializeJavaObjectAndClass(byte[] data) {
    return bindingThreadLocal.get().get().deserializeJavaObjectAndClass(data);
  }

  @Override
  public Object deserializeJavaObjectAndClass(MemoryBuffer buffer) {
    return bindingThreadLocal.get().get().deserializeJavaObjectAndClass(buffer);
  }

  @Override
  public Object deserializeJavaObjectAndClass(ForyInputStream inputStream) {
    return bindingThreadLocal.get().get().deserializeJavaObjectAndClass(inputStream);
  }

  @Override
  public Object deserializeJavaObjectAndClass(ForyReadableChannel channel) {
    return bindingThreadLocal.get().get().deserializeJavaObjectAndClass(channel);
  }

  @Override
  public <T> T copy(T obj) {
    return bindingThreadLocal.get().get().copy(obj);
  }

  @Override
  public void setClassLoader(ClassLoader classLoader) {
    setClassLoader(classLoader, StagingType.STRONG_STAGING);
  }

  @Override
  public void setClassLoader(ClassLoader classLoader, StagingType stagingType) {
    this.classLoader = classLoader;
    bindingThreadLocal.get().setClassLoader(classLoader, stagingType);
  }

  @Override
  public ClassLoader getClassLoader() {
    return bindingThreadLocal.get().getClassLoader();
  }

  @Override
  public void clearClassLoader(ClassLoader loader) {
    bindingThreadLocal.get().clearClassLoader(loader);
  }
}
