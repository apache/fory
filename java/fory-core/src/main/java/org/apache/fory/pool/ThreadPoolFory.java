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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.fory.AbstractThreadSafeFory;
import org.apache.fory.Fory;
import org.apache.fory.annotation.Internal;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.io.ForyInputStream;
import org.apache.fory.io.ForyReadableChannel;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.resolver.SharedRegistry;
import org.apache.fory.serializer.BufferCallback;

/**
 * A thread-safe {@link Fory} runtime backed by a thread-agnostic fixed-slot pool.
 *
 * <p>The current thread identity hash is used only as a routing hint to choose a preferred slot
 * scan start. Borrowed {@link Fory} instances are shared pool entries, not thread-owned state.
 */
@ThreadSafe
public class ThreadPoolFory extends AbstractThreadSafeFory {
  private static final int PREFERRED_SLOT_RETRIES = 2;
  private final int poolSize;
  private final AtomicReferenceArray<PooledEntry> slots;
  private final Fory[] pooledFory;
  private final Semaphore waiterSignal = new Semaphore(0);
  private final AtomicInteger waitingBorrowers = new AtomicInteger();
  private final Object callbackLock = new Object();

  public ThreadPoolFory(Function<ForyBuilder, Fory> foryFactory, int poolSize) {
    if (poolSize <= 0) {
      throw new IllegalArgumentException(
          String.format("thread safe fory pool size error, please check it, size:[%s]", poolSize));
    }
    SharedRegistry sharedRegistry = new SharedRegistry();
    Supplier<Fory> factory =
        () -> foryFactory.apply(Fory.builder().withSharedRegistry(sharedRegistry));
    this.poolSize = poolSize;
    slots = new AtomicReferenceArray<>(poolSize);
    pooledFory = new Fory[poolSize];
    for (int i = 0; i < poolSize; i++) {
      Fory fory = factory.get();
      pooledFory[i] = fory;
      slots.set(i, new PooledEntry(fory, i));
    }
  }

  private PooledEntry acquire() {
    int slotIndex = slotIndexForCurrentThread();
    PooledEntry entry = tryBorrowPreferredSlots(slotIndex);
    if (entry != null) {
      return entry;
    }
    waitingBorrowers.incrementAndGet();
    try {
      while (true) {
        entry = tryBorrowPreferredSlots(slotIndex);
        if (entry != null) {
          return entry;
        }
        try {
          waiterSignal.acquire();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("Interrupted while borrowing a pooled Fory instance.", e);
        }
      }
    } finally {
      waitingBorrowers.decrementAndGet();
    }
  }

  private void release(PooledEntry entry) {
    slots.lazySet(entry.homeIndex, entry);
    if (waitingBorrowers.get() > 0) {
      waiterSignal.release();
    }
  }

  private PooledEntry tryBorrowPreferredSlots(int slotIndex) {
    PooledEntry entry = tryBorrowSlot(slotIndex);
    if (entry != null) {
      return entry;
    }
    for (int i = 1; i < PREFERRED_SLOT_RETRIES; i++) {
      entry = tryBorrowSlot(slotIndex);
      if (entry != null) {
        return entry;
      }
    }
    int index = slotIndex + 1;
    if (index == poolSize) {
      index = 0;
    }
    for (int i = 1; i < poolSize; i++) {
      entry = tryBorrowSlot(index);
      if (entry != null) {
        return entry;
      }
      index++;
      if (index == poolSize) {
        index = 0;
      }
    }
    return null;
  }

  private PooledEntry tryBorrowSlot(int index) {
    return slots.getAndSet(index, null);
  }

  private int slotIndexForCurrentThread() {
    return Math.floorMod(spread(System.identityHashCode(Thread.currentThread())), poolSize);
  }

  private static int spread(int hash) {
    return hash ^ (hash >>> 16);
  }

  @Internal
  @Override
  public void registerCallback(Consumer<Fory> callback) {
    synchronized (callbackLock) {
      for (Fory fory : pooledFory) {
        callback.accept(fory);
      }
    }
  }

  @Override
  public <R> R execute(Function<Fory, R> action) {
    PooledEntry entry = acquire();
    try {
      return action.apply(entry.fory);
    } finally {
      release(entry);
    }
  }

  @Override
  public byte[] serialize(Object obj) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.serialize(obj);
    } finally {
      release(entry);
    }
  }

  @Override
  public byte[] serialize(Object obj, BufferCallback callback) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.serialize(obj, callback);
    } finally {
      release(entry);
    }
  }

  @Override
  public MemoryBuffer serialize(Object obj, long address, int size) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.serialize(obj, address, size);
    } finally {
      release(entry);
    }
  }

  @Override
  public MemoryBuffer serialize(MemoryBuffer buffer, Object obj) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.serialize(buffer, obj);
    } finally {
      release(entry);
    }
  }

  @Override
  public MemoryBuffer serialize(MemoryBuffer buffer, Object obj, BufferCallback callback) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.serialize(buffer, obj, callback);
    } finally {
      release(entry);
    }
  }

  @Override
  public void serialize(OutputStream outputStream, Object obj) {
    PooledEntry entry = acquire();
    try {
      entry.fory.serialize(outputStream, obj);
    } finally {
      release(entry);
    }
  }

  @Override
  public void serialize(OutputStream outputStream, Object obj, BufferCallback callback) {
    PooledEntry entry = acquire();
    try {
      entry.fory.serialize(outputStream, obj, callback);
    } finally {
      release(entry);
    }
  }

  @Override
  public Object deserialize(byte[] bytes) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.deserialize(bytes);
    } finally {
      release(entry);
    }
  }

  @Override
  public <T> T deserialize(byte[] bytes, Class<T> type) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.deserialize(bytes, type);
    } finally {
      release(entry);
    }
  }

  @Override
  public <T> T deserialize(MemoryBuffer buffer, Class<T> type) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.deserialize(buffer, type);
    } finally {
      release(entry);
    }
  }

  @Override
  public <T> T deserialize(ForyInputStream inputStream, Class<T> type) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.deserialize(inputStream, type);
    } finally {
      release(entry);
    }
  }

  @Override
  public <T> T deserialize(ForyReadableChannel channel, Class<T> type) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.deserialize(channel, type);
    } finally {
      release(entry);
    }
  }

  @Override
  public Object deserialize(byte[] bytes, Iterable<MemoryBuffer> outOfBandBuffers) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.deserialize(bytes, outOfBandBuffers);
    } finally {
      release(entry);
    }
  }

  @Override
  public Object deserialize(long address, int size) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.deserialize(address, size);
    } finally {
      release(entry);
    }
  }

  @Override
  public Object deserialize(MemoryBuffer buffer) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.deserialize(buffer);
    } finally {
      release(entry);
    }
  }

  @Override
  public Object deserialize(ByteBuffer byteBuffer) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.deserialize(MemoryUtils.wrap(byteBuffer));
    } finally {
      release(entry);
    }
  }

  @Override
  public Object deserialize(MemoryBuffer buffer, Iterable<MemoryBuffer> outOfBandBuffers) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.deserialize(buffer, outOfBandBuffers);
    } finally {
      release(entry);
    }
  }

  @Override
  public Object deserialize(ForyInputStream inputStream) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.deserialize(inputStream);
    } finally {
      release(entry);
    }
  }

  @Override
  public Object deserialize(ForyInputStream inputStream, Iterable<MemoryBuffer> outOfBandBuffers) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.deserialize(inputStream, outOfBandBuffers);
    } finally {
      release(entry);
    }
  }

  @Override
  public Object deserialize(ForyReadableChannel channel) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.deserialize(channel);
    } finally {
      release(entry);
    }
  }

  @Override
  public Object deserialize(ForyReadableChannel channel, Iterable<MemoryBuffer> outOfBandBuffers) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.deserialize(channel, outOfBandBuffers);
    } finally {
      release(entry);
    }
  }

  @Override
  public <T> T copy(T obj) {
    PooledEntry entry = acquire();
    try {
      return entry.fory.copy(obj);
    } finally {
      release(entry);
    }
  }

  private static final class PooledEntry {
    private final Fory fory;
    private final int homeIndex;

    private PooledEntry(Fory fory, int homeIndex) {
      this.fory = fory;
      this.homeIndex = homeIndex;
    }
  }
}
