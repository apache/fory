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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.fory.AbstractThreadSafeFory;
import org.apache.fory.Fory;
import org.apache.fory.annotation.Internal;
import org.apache.fory.io.ForyInputStream;
import org.apache.fory.io.ForyReadableChannel;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.serializer.BufferCallback;
import org.apache.fory.util.LoaderBinding;

@ThreadSafe
public class ThreadPoolFory extends AbstractThreadSafeFory {

  private static final Logger LOG = LoggerFactory.getLogger(ThreadPoolFory.class);

  private final ForyPooledObjectFactory foryPooledObjectFactory;
  private Consumer<Fory> factoryCallback = f -> {};
  private final Object callbackLock = new Object();

  public ThreadPoolFory(
      Function<ClassLoader, Fory> foryFactory,
      int minPoolSize,
      int maxPoolSize,
      long expireTime,
      TimeUnit timeUnit) {
    this.foryPooledObjectFactory =
        new ForyPooledObjectFactory(
            foryFactory,
            minPoolSize,
            maxPoolSize,
            expireTime,
            timeUnit,
            fory -> factoryCallback.accept(fory));
  }

  @Internal
  @Override
  public void registerCallback(Consumer<Fory> callback) {
    synchronized (callbackLock) {
      factoryCallback = factoryCallback.andThen(callback);
      for (ClassLoaderForyPooled foryPooled :
          foryPooledObjectFactory.classLoaderForyPooledCache.asMap().values()) {
        foryPooled.allFory.keySet().forEach(callback);
      }
    }
  }

  @Override
  public <R> R execute(Function<Fory, R> action) {
    ClassLoaderForyPooled pooledCache = null;
    Fory fory = null;
    try {
      pooledCache = foryPooledObjectFactory.getPooledCache();
      fory = pooledCache.getFory();
      return action.apply(fory);
    } catch (Exception e) {
      LOG.error(e.getMessage(), e);
      throw new RuntimeException(e);
    } finally {
      if (pooledCache != null) {
        pooledCache.returnFory(fory);
      }
    }
  }

  @Override
  public byte[] serialize(Object obj) {
    return execute(fory -> fory.serialize(obj));
  }

  @Override
  public byte[] serialize(Object obj, BufferCallback callback) {
    return execute(fory -> fory.serialize(obj, callback));
  }

  @Override
  public MemoryBuffer serialize(Object obj, long address, int size) {
    return execute(fory -> fory.serialize(obj, address, size));
  }

  @Override
  public MemoryBuffer serialize(MemoryBuffer buffer, Object obj) {
    return execute(fory -> fory.serialize(buffer, obj));
  }

  @Override
  public MemoryBuffer serialize(MemoryBuffer buffer, Object obj, BufferCallback callback) {
    return execute(fory -> fory.serialize(buffer, obj, callback));
  }

  @Override
  public void serialize(OutputStream outputStream, Object obj) {
    execute(
        fory -> {
          fory.serialize(outputStream, obj);
          return null;
        });
  }

  @Override
  public void serialize(OutputStream outputStream, Object obj, BufferCallback callback) {
    execute(
        fory -> {
          fory.serialize(outputStream, obj, callback);
          return null;
        });
  }

  @Override
  public Object deserialize(byte[] bytes) {
    return execute(fory -> fory.deserialize(bytes));
  }

  @Override
  public <T> T deserialize(byte[] bytes, Class<T> type) {
    return execute(fory -> fory.deserialize(bytes, type));
  }

  @Override
  public Object deserialize(byte[] bytes, Iterable<MemoryBuffer> outOfBandBuffers) {
    return execute(fory -> fory.deserialize(bytes, outOfBandBuffers));
  }

  @Override
  public Object deserialize(long address, int size) {
    return execute(fory -> fory.deserialize(address, size));
  }

  @Override
  public Object deserialize(MemoryBuffer buffer) {
    return execute(fory -> fory.deserialize(buffer));
  }

  @Override
  public Object deserialize(ByteBuffer byteBuffer) {
    return execute(fory -> fory.deserialize(MemoryUtils.wrap(byteBuffer)));
  }

  @Override
  public Object deserialize(MemoryBuffer buffer, Iterable<MemoryBuffer> outOfBandBuffers) {
    return execute(fory -> fory.deserialize(buffer, outOfBandBuffers));
  }

  @Override
  public Object deserialize(ForyInputStream inputStream) {
    return execute(fory -> fory.deserialize(inputStream));
  }

  @Override
  public Object deserialize(ForyInputStream inputStream, Iterable<MemoryBuffer> outOfBandBuffers) {
    return execute(fory -> fory.deserialize(inputStream, outOfBandBuffers));
  }

  @Override
  public Object deserialize(ForyReadableChannel channel) {
    return execute(fory -> fory.deserialize(channel));
  }

  @Override
  public Object deserialize(ForyReadableChannel channel, Iterable<MemoryBuffer> outOfBandBuffers) {
    return execute(fory -> fory.deserialize(channel, outOfBandBuffers));
  }

  @Override
  public byte[] serializeJavaObject(Object obj) {
    return execute(fory -> fory.serializeJavaObject(obj));
  }

  @Override
  public void serializeJavaObject(MemoryBuffer buffer, Object obj) {
    execute(
        fory -> {
          fory.serializeJavaObject(buffer, obj);
          return null;
        });
  }

  @Override
  public void serializeJavaObject(OutputStream outputStream, Object obj) {
    execute(
        fory -> {
          fory.serializeJavaObject(outputStream, obj);
          return null;
        });
  }

  @Override
  public <T> T deserializeJavaObject(byte[] data, Class<T> cls) {
    return execute(fory -> fory.deserializeJavaObject(data, cls));
  }

  @Override
  public <T> T deserializeJavaObject(MemoryBuffer buffer, Class<T> cls) {
    return execute(fory -> fory.deserializeJavaObject(buffer, cls));
  }

  @Override
  public <T> T deserializeJavaObject(ForyInputStream inputStream, Class<T> cls) {
    return execute(fory -> fory.deserializeJavaObject(inputStream, cls));
  }

  @Override
  public <T> T deserializeJavaObject(ForyReadableChannel channel, Class<T> cls) {
    return execute(fory -> fory.deserializeJavaObject(channel, cls));
  }

  @Override
  public byte[] serializeJavaObjectAndClass(Object obj) {
    return execute(fory -> fory.serializeJavaObjectAndClass(obj));
  }

  @Override
  public void serializeJavaObjectAndClass(MemoryBuffer buffer, Object obj) {
    execute(
        fory -> {
          fory.serializeJavaObjectAndClass(buffer, obj);
          return null;
        });
  }

  @Override
  public void serializeJavaObjectAndClass(OutputStream outputStream, Object obj) {
    execute(
        fory -> {
          fory.serializeJavaObjectAndClass(outputStream, obj);
          return null;
        });
  }

  @Override
  public Object deserializeJavaObjectAndClass(byte[] data) {
    return execute(fory -> fory.deserializeJavaObjectAndClass(data));
  }

  @Override
  public Object deserializeJavaObjectAndClass(MemoryBuffer buffer) {
    return execute(fory -> fory.deserializeJavaObjectAndClass(buffer));
  }

  @Override
  public Object deserializeJavaObjectAndClass(ForyInputStream inputStream) {
    return execute(fory -> fory.deserializeJavaObjectAndClass(inputStream));
  }

  @Override
  public Object deserializeJavaObjectAndClass(ForyReadableChannel channel) {
    return execute(fory -> fory.deserializeJavaObjectAndClass(channel));
  }

  @Override
  public <T> T copy(T obj) {
    return execute(fory -> fory.copy(obj));
  }

  @Override
  public void setClassLoader(ClassLoader classLoader) {
    setClassLoader(classLoader, LoaderBinding.StagingType.SOFT_STAGING);
  }

  @Override
  public void setClassLoader(ClassLoader classLoader, LoaderBinding.StagingType stagingType) {
    foryPooledObjectFactory.setClassLoader(classLoader, stagingType);
  }

  @Override
  public ClassLoader getClassLoader() {
    return foryPooledObjectFactory.getClassLoader();
  }

  @Override
  public void clearClassLoader(ClassLoader loader) {
    foryPooledObjectFactory.clearClassLoader(loader);
  }
}
