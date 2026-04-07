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

package org.apache.fory.collection;

import com.google.common.base.FinalizableReferenceQueue;
import com.google.common.base.FinalizableWeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.fory.util.GraalvmSupport;

/**
 * Hash table based implementation with weak keys. An entry in a MultiKeyWeakMap will automatically
 * be removed when all keys are no longer in ordinary use. More precisely, the presence of a mapping
 * for the given keys will not prevent the keys from being discarded by the garbage collector.
 *
 * @param <T> the type of values maintained by this map
 * @see java.util.WeakHashMap
 */
public class MultiKeyWeakMap<T> {
  private static final Set<KeyReference> REFERENCES = ConcurrentHashMap.newKeySet();
  private static volatile FinalizableReferenceQueue referenceQueue;
  private final Map<Object, T> map;

  public MultiKeyWeakMap() {
    map = new ConcurrentHashMap<>();
  }

  public void put(Object[] keys, T value) {
    map.put(createKey(keys), value);
  }

  public T get(Object[] keys) {
    List<? extends KeyReference> keyRefs = createKey(keys);
    T t = map.get(keyRefs);
    keyRefs.forEach(REFERENCES::remove);
    return t;
  }

  private List<? extends KeyReference> createKey(Object[] keys) {
    boolean[] reclaimedFlags = new boolean[keys.length];
    if (GraalvmSupport.isGraalBuildtime()) {
      List<NoCallbackRef> keyRefs = new ArrayList<>();
      for (Object key : keys) {
        keyRefs.add(new NoCallbackRef(key));
      }
      return keyRefs;
    }
    List<FinalizableKeyReference> keyRefs = new ArrayList<>();
    for (int i = 0; i < keys.length; i++) {
      keyRefs.add(new FinalizableKeyReference(keys[i], keyRefs, reclaimedFlags, i));
    }
    return keyRefs;
  }

  private static FinalizableReferenceQueue getReferenceQueue() {
    FinalizableReferenceQueue queue = referenceQueue;
    if (queue == null) {
      synchronized (MultiKeyWeakMap.class) {
        queue = referenceQueue;
        if (queue == null) {
          queue = new FinalizableReferenceQueue();
          referenceQueue = queue;
        }
      }
    }
    return queue;
  }

  private interface KeyReference {}

  private static final class NoCallbackRef implements KeyReference {
    private final Object obj;

    private NoCallbackRef(Object obj) {
      this.obj = obj;
    }

    @Override
    public boolean equals(Object o1) {
      if (this == o1) {
        return true;
      }
      if (o1 == null || getClass() != o1.getClass()) {
        return false;
      }
      NoCallbackRef that = (NoCallbackRef) o1;
      return Objects.equals(obj, that.obj);
    }

    @Override
    public int hashCode() {
      return Objects.hash(obj);
    }
  }

  private final class FinalizableKeyReference extends FinalizableWeakReference<Object>
      implements KeyReference {
    private final boolean[] reclaimedFlags;
    private final int index;
    private final List<FinalizableKeyReference> keyRefs;
    private final int hashcode;

    public FinalizableKeyReference(
        Object obj, List<FinalizableKeyReference> keyRefs, boolean[] reclaimedFlags, int index) {
      super(obj, getReferenceQueue());
      this.reclaimedFlags = reclaimedFlags;
      this.index = index;
      this.keyRefs = keyRefs;
      hashcode = obj.hashCode();
      REFERENCES.add(this);
    }

    @Override
    public void finalizeReferent() {
      reclaimedFlags[index] = true;
      REFERENCES.remove(this);
      if (IntStream.range(0, reclaimedFlags.length).allMatch(i -> reclaimedFlags[i])) {
        map.remove(keyRefs);
      }
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      @SuppressWarnings("unchecked")
      FinalizableKeyReference that = (FinalizableKeyReference) o;
      Object referent1 = this.get();
      if (referent1 != null) {
        return referent1.equals(that.get());
      } else {
        // referent not exists, continue compare is meaningless.
        return false;
      }
    }

    @Override
    public int hashCode() {
      return hashcode;
    }

    @Override
    public String toString() {
      return "KeyReference{"
          + "reclaimedFlags="
          + Arrays.toString(reclaimedFlags)
          + ", index="
          + index
          + ", keyRefs="
          + keyRefs.stream().map(FinalizableKeyReference::get).collect(Collectors.toList())
          + ", hashcode="
          + hashcode
          + '}';
    }
  }
}
