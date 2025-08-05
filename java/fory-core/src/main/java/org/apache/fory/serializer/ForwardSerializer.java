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

package org.apache.fory.serializer;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.fory.Fory;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.util.LoaderBinding;
import org.apache.fory.util.LoaderBinding.StagingType;

/**
 * A thread-safe serializer used to forward serialization to different serializer implementation.
 */
@ThreadSafe
@SuppressWarnings("unchecked")
public class ForwardSerializer {

  public abstract static class SerializerProxy<T> {
    /** Register custom serializers should be done in this method. */
    protected abstract T newSerializer();

    protected void setClassLoader(T serializer, ClassLoader classLoader) {
      throw new UnsupportedOperationException();
    }

    public void setClassLoader(T serializer, ClassLoader classLoader, StagingType stagingType) {
      setClassLoader(serializer, classLoader);
    }

    protected ClassLoader getClassLoader(T serializer) {
      throw new UnsupportedOperationException();
    }

    public void clearClassLoader(T serializer, ClassLoader loader) {}

    protected void register(T serializer, Class<?> clz) {
      throw new UnsupportedOperationException();
    }

    protected void register(T serializer, Class<?> clz, int id) {
      throw new UnsupportedOperationException();
    }

    protected abstract byte[] serialize(T serializer, Object obj);

    protected MemoryBuffer serialize(T serializer, MemoryBuffer buffer, Object obj) {
      byte[] bytes = serialize(serializer, obj);
      buffer.writeBytes(bytes);
      return buffer;
    }

    protected ByteBuffer serialize(T serializer, ByteBuffer buffer, Object obj) {
      byte[] bytes = serialize(serializer, obj);
      buffer.put(bytes);
      return buffer;
    }

    protected abstract Object deserialize(T serializer, byte[] bytes);

    protected Object deserialize(T serializer, long address, int size) {
      byte[] bytes = new byte[size];
      Platform.copyMemory(null, address, bytes, Platform.BYTE_ARRAY_OFFSET, size);
      return deserialize(serializer, bytes);
    }

    protected Object deserialize(T serializer, ByteBuffer byteBuffer) {
      return deserialize(serializer, MemoryBuffer.wrap(byteBuffer));
    }

    protected Object deserialize(T serializer, MemoryBuffer buffer) {
      byte[] bytes = buffer.getRemainingBytes();
      return deserialize(serializer, bytes);
    }

    protected Object copy(T serializer, Object obj) {
      throw new UnsupportedOperationException();
    }
  }

  public static class DefaultForyProxy extends SerializerProxy<LoaderBinding> {

    private final ThreadLocal<MemoryBuffer> bufferLocal =
        ThreadLocal.withInitial(() -> MemoryBuffer.buffer(32));

    /** Override this method to register custom serializers. */
    @Override
    protected LoaderBinding newSerializer() {
      LoaderBinding loaderBinding = new LoaderBinding(this::newForySerializer);
      loaderBinding.setClassLoader(Thread.currentThread().getContextClassLoader());
      return loaderBinding;
    }

    protected Fory newForySerializer(ClassLoader loader) {
      return Fory.builder()
          .withLanguage(Language.JAVA)
          .withRefTracking(true)
          .withClassLoader(loader)
          .requireClassRegistration(false)
          .build();
    }

    @Override
    protected void setClassLoader(LoaderBinding binding, ClassLoader classLoader) {
      binding.setClassLoader(classLoader);
    }

    @Override
    public void setClassLoader(
        LoaderBinding binding, ClassLoader classLoader, StagingType stagingType) {
      binding.setClassLoader(classLoader, stagingType);
    }

    @Override
    protected ClassLoader getClassLoader(LoaderBinding binding) {
      return binding.getClassLoader();
    }

    @Override
    public void clearClassLoader(LoaderBinding loaderBinding, ClassLoader loader) {
      loaderBinding.clearClassLoader(loader);
    }

    @Override
    protected void register(LoaderBinding binding, Class<?> clz) {
      binding.register(clz);
    }

    @Override
    protected void register(LoaderBinding binding, Class<?> clz, int id) {
      binding.register(clz, (short) id);
    }

    @Override
    protected byte[] serialize(LoaderBinding binding, Object obj) {
      MemoryBuffer buffer = bufferLocal.get();
      buffer.writerIndex(0);
      binding.get().serialize(buffer, obj);
      return buffer.getBytes(0, buffer.writerIndex());
    }

    @Override
    protected MemoryBuffer serialize(LoaderBinding binding, MemoryBuffer buffer, Object obj) {
      binding.get().serialize(buffer, obj);
      return buffer;
    }

    @Override
    protected Object deserialize(LoaderBinding serializer, byte[] bytes) {
      return serializer.get().deserialize(bytes);
    }

    @Override
    protected Object deserialize(LoaderBinding serializer, MemoryBuffer buffer) {
      return serializer.get().deserialize(buffer);
    }

    @Override
    protected Object copy(LoaderBinding serializer, Object obj) {
      return serializer.get().copy(obj);
    }
  }

  private final SerializerProxy proxy;
  private final ThreadLocal<Object> serializerLocal;
  private Set<Object> serializerSet = Collections.newSetFromMap(new IdentityHashMap<>());
  private Consumer<Object> serializerCallback = obj -> {};

  public ForwardSerializer(SerializerProxy proxy) {
    this.proxy = proxy;
    serializerLocal =
        ThreadLocal.withInitial(
            () -> {
              Object serializer = proxy.newSerializer();
              synchronized (ForwardSerializer.this) {
                serializerCallback.accept(serializer);
              }
              serializerSet.add(serializer);
              return serializer;
            });
  }

  /** Set classLoader of serializer for current thread only. */
  public void setClassLoader(ClassLoader classLoader) {
    proxy.setClassLoader(serializerLocal.get(), classLoader);
  }

  public void setClassLoader(ClassLoader classLoader, StagingType stagingType) {
    proxy.setClassLoader(serializerLocal.get(), classLoader, stagingType);
  }

  /** Returns classLoader of serializer for current thread. */
  public ClassLoader getClassLoader() {
    return proxy.getClassLoader(serializerLocal.get());
  }

  /**
   * Clean up classloader set by {@link #setClassLoader(ClassLoader, StagingType)}, <code>
   * classLoader
   * </code> won't be referenced by {@link Fory} after this call and can be gc if it's not
   * referenced by other objects.
   */
  public void clearClassLoader(ClassLoader loader) {
    proxy.clearClassLoader(serializerLocal.get(), loader);
  }

  public synchronized void register(Class<?> clz) {
    serializerSet.forEach(serializer -> proxy.register(serializer, clz));
    serializerCallback =
        serializerCallback.andThen(
            serializer -> {
              proxy.register(serializer, clz);
            });
  }

  public synchronized void register(Class<?> clz, int id) {
    serializerSet.forEach(serializer -> proxy.register(serializer, clz, id));
    serializerCallback =
        serializerCallback.andThen(
            serializer -> {
              proxy.register(serializer, clz, id);
            });
  }

  public byte[] serialize(Object obj) {
    return proxy.serialize(serializerLocal.get(), obj);
  }

  public MemoryBuffer serialize(MemoryBuffer buffer, Object obj) {
    return proxy.serialize(serializerLocal.get(), buffer, obj);
  }

  public ByteBuffer serialize(ByteBuffer buffer, Object obj) {
    return proxy.serialize(serializerLocal.get(), buffer, obj);
  }

  public <T> T deserialize(byte[] bytes) {
    return (T) proxy.deserialize(serializerLocal.get(), bytes);
  }

  public <T> T deserialize(long address, int size) {
    return (T) proxy.deserialize(serializerLocal.get(), address, size);
  }

  public <T> T deserialize(ByteBuffer byteBuffer) {
    return (T) proxy.deserialize(serializerLocal.get(), byteBuffer);
  }

  public <T> T deserialize(MemoryBuffer buffer) {
    return (T) proxy.deserialize(serializerLocal.get(), buffer);
  }

  public <T> T copy(T obj) {
    return (T) proxy.copy(serializerLocal.get(), obj);
  }
}
