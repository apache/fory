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

package org.apache.fory.util;

import com.google.common.collect.MapMaker;
import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.fory.Fory;
import org.apache.fory.annotation.Internal;

/**
 * An util to bind {@link Fory} with {@link ClassLoader}. If {@link ClassLoader} are changed, the
 * previous bind {@link Fory} will be canceled by default, one can set different {@link StagingType}
 * to preserve previous {@link Fory} in a strong/soft referenced way.
 */
@Internal
public final class LoaderBinding {
  private final Function<ClassLoader, Fory> foryFactory;
  // `WeakHashMap` won't work here too, since `Fory` hold classes which reference `ClassLoader`,
  // which cause
  // circular reference between ClassLoader and Fory.
  private final HashMap<ClassLoader, Fory> foryMap = new HashMap<>();
  private final Map<ClassLoader, SoftReference<Fory>> forySoftMap =
      GraalvmSupport.IN_GRAALVM_NATIVE_IMAGE
          ? new ConcurrentHashMap<>()
          : new MapMaker().weakKeys().makeMap();
  private Consumer<Fory> bindingCallback = f -> {};
  private ClassLoader loader;
  private Fory fory;

  public LoaderBinding(Function<ClassLoader, Fory> foryFactory) {
    this.foryFactory = foryFactory;
  }

  public Fory get() {
    return fory;
  }

  public void visitAllFory(Consumer<Fory> consumer) {
    if (forySoftMap.isEmpty()) {
      for (Fory f : foryMap.values()) {
        consumer.accept(f);
      }
    } else if (foryMap.isEmpty()) {
      for (SoftReference<Fory> ref : forySoftMap.values()) {
        Fory f = ref.get();
        if (f != null) {
          consumer.accept(f);
        }
      }
    } else {
      Set<Fory> forySet = new HashSet<>(foryMap.size());
      forySet.addAll(foryMap.values());
      for (SoftReference<Fory> ref : forySoftMap.values()) {
        Fory f = ref.get();
        if (f != null) {
          forySet.add(f);
        }
      }
      for (Fory f : forySet) {
        consumer.accept(f);
      }
    }
  }

  public ClassLoader getClassLoader() {
    return loader;
  }

  /**
   * Set classloader for resolving unregistered class name to class. If <code>classLoader</code> is
   * different classloader, a new {@link Fory} instance will be created. If this is not expected,
   * {@link #setClassLoader(ClassLoader, StagingType)} and {@link #clearClassLoader} should be used.
   */
  public void setClassLoader(ClassLoader classLoader) {
    setClassLoader(classLoader, StagingType.STRONG_STAGING);
  }

  /**
   * Set classloader for resolving unregistered class name to class.
   *
   * <p>If <code>staging</code> is true, a cached {@link Fory} instance will be returned if not
   * null, and the previous classloader and associated {@link Fory} instance won't be gc unless
   * {@link #clearClassLoader} is called explicitly. If false, and the passed <code>classLoader
   * </code> is different, a new {@link Fory} instance will be created, previous classLoader and
   * associated {@link Fory} instance will be cleared.
   *
   * @param classLoader {@link ClassLoader} for resolving unregistered class name to class
   * @param stagingType Whether cache previous classloader and associated {@link Fory} instance.
   */
  public void setClassLoader(ClassLoader classLoader, StagingType stagingType) {
    if (this.loader != classLoader) {
      if (classLoader == null) {
        // may be used to clear some classloader
        classLoader = Fory.class.getClassLoader();
      }
      this.loader = classLoader;
      switch (stagingType) {
        case NO_STAGING:
          fory = foryFactory.apply(classLoader);
          bindingCallback.accept(fory);
          break;
        case SOFT_STAGING:
          {
            SoftReference<Fory> forySoftReference = forySoftMap.get(classLoader);
            Fory fory = forySoftReference == null ? null : forySoftReference.get();
            if (fory == null) {
              fory = foryFactory.apply(classLoader);
              bindingCallback.accept(fory);
              forySoftMap.put(classLoader, new SoftReference<>(fory));
            }
            this.fory = fory;
            break;
          }
        case STRONG_STAGING:
          {
            Fory fory = foryMap.get(classLoader);
            if (fory == null) {
              fory = foryFactory.apply(classLoader);
              bindingCallback.accept(fory);
              foryMap.put(classLoader, fory);
            }
            this.fory = fory;
            break;
          }
        default:
          throw new IllegalArgumentException();
      }
    }
  }

  /**
   * Clean up classloader set by {@link #setClassLoader(ClassLoader, StagingType)}, <code>
   * classLoader
   * </code> won't be referenced by {@link Fory} after this call and can be gc if it's not
   * referenced by other objects.
   */
  public void clearClassLoader(ClassLoader classLoader) {
    foryMap.remove(classLoader);
    SoftReference<Fory> softReference = forySoftMap.remove(classLoader);
    if (softReference != null) {
      softReference.clear();
    }
    if (this.loader == classLoader) {
      this.loader = null;
      this.fory = null;
    }
  }

  public void register(Class<?> clz) {
    foryMap.values().forEach(fory -> fory.register(clz));
    bindingCallback = bindingCallback.andThen(fory -> fory.register(clz));
  }

  public void register(Class<?> clz, long id) {
    long unsignedId = id < 0 ? id & 0xffff_ffffL : id;
    Preconditions.checkArgument(
        unsignedId >= 0 && unsignedId <= 0xffff_fffEL,
        "User type id must be in range [0, 0xfffffffe]");
    int checkedId = (int) unsignedId;
    foryMap.values().forEach(fory -> fory.register(clz, checkedId));
    bindingCallback = bindingCallback.andThen(fory -> fory.register(clz, checkedId));
  }

  public void setBindingCallback(Consumer<Fory> bindingCallback) {
    this.bindingCallback = bindingCallback;
  }

  public enum StagingType {
    /**
     * Don't cache fory. A new {@link Fory} will be created if classloader is switched to a new one.
     */
    NO_STAGING,
    /**
     * Cache fory to a classloader using a {@link SoftReference}, so it can be gc when there is
     * memory pressure, but doesn't cause class memory leak and doesn't cause circular reference
     * between classloader and fory.
     */
    SOFT_STAGING,
    /**
     * Cache fory to a classloader using a strong reference, {@link #clearClassLoader} should be
     * invoked to clear classloader and fory. Otherwise, classloader/fory may never be gc until
     * {@link LoaderBinding} is garbage collected.
     */
    STRONG_STAGING,
  }
}
