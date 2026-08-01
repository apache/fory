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

package org.apache.fory.serializer.collection;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.fory.collection.ObjectArray;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.RefReader;

/** Operation-local repair state for hash containers which observed an incomplete back-reference. */
final class HashContainerReadState implements RefReader.RefReadListener {
  private static final Object CONTEXT_KEY = new Object();
  private static final Object PRESENT = new Object();

  private final IdentityHashMap<Object, Object> seen = new IdentityHashMap<>();
  private final ObjectArray<Object> containers = new ObjectArray<>(2);

  static void trackCollection(ReadContext readContext, Collection collection) {
    track(readContext, collection);
  }

  static void trackMap(ReadContext readContext, Map map) {
    track(readContext, map);
  }

  private static void track(ReadContext readContext, Object container) {
    RefReader refReader = readContext.getRefReader();
    if (!refReader.hasMaterializingRefs()) {
      rebuild(container);
      return;
    }
    HashContainerReadState state =
        (HashContainerReadState) readContext.getContextObject(CONTEXT_KEY);
    if (state == null) {
      state = new HashContainerReadState();
      readContext.putContextObject(CONTEXT_KEY, state);
      refReader.setRefReadListener(state);
    }
    if (state.seen.put(container, PRESENT) == null) {
      state.containers.add(container);
    }
  }

  @Override
  public void onRefReadsComplete() {
    ObjectArray<Object> containers = this.containers;
    try {
      // Inner containers finish and register before their outer owners. Rebuild in that same order
      // so an outer key whose hashCode consults an inner container observes repaired lookups.
      for (int i = 0; i < containers.size; i++) {
        rebuild(containers.objects[i]);
      }
    } finally {
      containers.clear();
      seen.clear();
    }
  }

  private static void rebuild(Object container) {
    if (container instanceof Map) {
      rebuildMap((Map) container);
    } else {
      rebuildCollection((Collection) container);
    }
  }

  private static void rebuildCollection(Collection collection) {
    // A back-reference must be published before its fields are complete to preserve identity.
    // Reordering fields only hides mutable-hash failures, so rebuild the affected buckets after
    // materialization while retaining the original container and encounter order.
    Object[] elements = collection.toArray();
    collection.clear();
    for (Object element : elements) {
      collection.add(element);
    }
  }

  private static void rebuildMap(Map map) {
    Object[] entries = new Object[Math.multiplyExact(map.size(), 2)];
    int index = 0;
    for (Object entryObject : map.entrySet()) {
      Entry entry = (Entry) entryObject;
      entries[index++] = entry.getKey();
      entries[index++] = entry.getValue();
    }
    map.clear();
    for (int i = 0; i < index; i += 2) {
      map.put(entries[i], entries[i + 1]);
    }
  }
}
