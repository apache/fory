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
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.memory.Platform;

/**
 * A thread-safe serializer used to forward serialization to different serializer implementation.
 */
@ThreadSafe
@SuppressWarnings("unchecked")
public class ForwardSerializer {

  public abstract static class SerializerProxy<T> {
    /** Register custom serializers should be done in this method. */
    protected abstract T newSerializer();

    protected void register(T serializer, Class<?> clz) {
      throw new UnsupportedOperationException();
    }

    protected void register(T serializer, Class<?> clz, long id) {
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
      return deserialize(serializer, MemoryUtils.wrap(byteBuffer));
    }

    protected Object deserialize(T serializer, MemoryBuffer buffer) {
      byte[] bytes = buffer.getRemainingBytes();
      return deserialize(serializer, bytes);
    }

    protected Object copy(T serializer, Object obj) {
      throw new UnsupportedOperationException();
    }
  }

  public static class DefaultForyProxy extends SerializerProxy<Fory> {

    private final ThreadLocal<MemoryBuffer> bufferLocal =
        ThreadLocal.withInitial(() -> MemoryUtils.buffer(32));

    /** Override this method to register custom serializers. */
    @Override
    protected Fory newSerializer() {
      return newForySerializer();
    }

    protected Fory newForySerializer() {
      return Fory.builder()
          .withLanguage(Language.JAVA)
          .withRefTracking(true)
          .requireClassRegistration(false)
          .build();
    }

    @Override
    protected void register(Fory fory, Class<?> clz) {
      fory.register(clz);
    }

    @Override
    protected void register(Fory fory, Class<?> clz, long id) {
      long unsignedId = id < 0 ? id & 0xffff_ffffL : id;
      if (unsignedId < 0 || unsignedId > 0xffff_fffEL) {
        throw new IllegalArgumentException("User type id must be in range [0, 0xfffffffe]");
      }
      fory.register(clz, (int) unsignedId);
    }

    @Override
    protected byte[] serialize(Fory fory, Object obj) {
      MemoryBuffer buffer = bufferLocal.get();
      buffer.writerIndex(0);
      fory.serialize(buffer, obj);
      return buffer.getBytes(0, buffer.writerIndex());
    }

    @Override
    protected MemoryBuffer serialize(Fory fory, MemoryBuffer buffer, Object obj) {
      fory.serialize(buffer, obj);
      return buffer;
    }

    @Override
    protected Object deserialize(Fory fory, byte[] bytes) {
      return fory.deserialize(bytes);
    }

    @Override
    protected Object deserialize(Fory fory, MemoryBuffer buffer) {
      return fory.deserialize(buffer);
    }

    @Override
    protected Object copy(Fory fory, Object obj) {
      return fory.copy(obj);
    }
  }

  private final SerializerProxy proxy;
  private final ThreadLocal<Object> serializerLocal;
  private final Set<Object> serializerSet =
      Collections.newSetFromMap(Collections.synchronizedMap(new IdentityHashMap<>()));
  private Consumer<Object> serializerCallback = obj -> {};

  public ForwardSerializer(SerializerProxy proxy) {
    this.proxy = proxy;
    serializerLocal =
        ThreadLocal.withInitial(
            () -> {
              Object serializer = proxy.newSerializer();
              synchronized (ForwardSerializer.this) {
                serializerSet.add(serializer);
                serializerCallback.accept(serializer);
              }
              return serializer;
            });
  }

  public synchronized void register(Class<?> clz) {
    serializerSet.forEach(serializer -> proxy.register(serializer, clz));
    serializerCallback =
        serializerCallback.andThen(
            serializer -> {
              proxy.register(serializer, clz);
            });
  }

  public synchronized void register(Class<?> clz, long id) {
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
