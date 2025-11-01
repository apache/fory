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

import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.fory.Fory;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;

/** A thread-safe object pool of {@link Fory}. */
public class ClassLoaderForyPooled {

  private static final Logger LOG = LoggerFactory.getLogger(ClassLoaderForyPooled.class);

  private final Function<ClassLoader, Fory> foryFactory;
  private Consumer<Fory> factoryCallback = f -> {};

  private final ClassLoader classLoader;

  /** idle Fory cache change. by : 1. init() 2. getFory() 3.returnFory() */
  private final BlockingQueue<Fory> idleCacheQueue;

  final WeakHashMap<Fory, Object> allFory = new WeakHashMap<>();

  /**
   * The number of active Fory objects in the cache.Make sure it does not exceed the maximum number
   * of object pools.
   */
  private final AtomicInteger activeCacheNumber = new AtomicInteger(0);

  /**
   * Dynamic capacity expansion and contraction The user sets the maximum number of object pools.
   * Math.max(maxPoolSize, CPU * 2)
   */
  private final int maxPoolSize;

  private final Lock lock = new ReentrantLock();

  public ClassLoaderForyPooled(
      ClassLoader classLoader,
      Function<ClassLoader, Fory> foryFactory,
      int minPoolSize,
      int maxPoolSize) {
    Objects.requireNonNull(foryFactory);
    this.maxPoolSize = maxPoolSize;
    this.foryFactory = foryFactory;
    this.classLoader = classLoader;
    idleCacheQueue = new LinkedBlockingQueue<>(maxPoolSize);
    while (idleCacheQueue.size() < minPoolSize) {
      addFory(true);
    }
  }

  public Fory getFory() {
    if (activeCacheNumber.get() < maxPoolSize) {
      Fory fory = idleCacheQueue.poll();
      if (fory != null) {
        return fory;
      } else {
        // new Fory return directly, no need to add to queue, it will be added by returnFory()
        fory = addFory(false);
        if (fory != null) {
          return fory;
        }
      }
    }
    try {
      return idleCacheQueue.take();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public void returnFory(Fory fory) {
    Objects.requireNonNull(fory);
    idleCacheQueue.offer(fory);
  }

  private Fory addFory(boolean addQueue) {
    // only activeCacheNumber increment success, can lock and create new Fory, otherwise return
    // null, and block in getFory(), wait for other thread to release idleCacheQueue.
    int after = activeCacheNumber.incrementAndGet();
    if (after > maxPoolSize) {
      activeCacheNumber.decrementAndGet();
      return null;
    }
    try {
      lock.lock();
      Fory fory = foryFactory.apply(classLoader);
      factoryCallback.accept(fory);
      allFory.put(fory, null);
      if (addQueue) {
        idleCacheQueue.add(fory);
      }
      return fory;
    } finally {
      lock.unlock();
    }
  }

  void setFactoryCallback(Consumer<Fory> factoryCallback) {
    try {
      lock.lock();
      this.factoryCallback = this.factoryCallback.andThen(factoryCallback);
      allFory.keySet().forEach(factoryCallback);
    } finally {
      lock.unlock();
    }
  }
}
