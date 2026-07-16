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

package org.apache.fory.json.reader;

import java.util.Objects;
import org.apache.fory.json.resolver.JsonSharedRegistry.CachedFieldName;

/** Fixed two-candidate cache of shared field-name entries owned by one JSON reader. */
final class FieldNameCache {
  private final int maxEntries;
  private final int mask;
  private long[] hashes;
  private CachedFieldName[] entries;
  private int size;

  FieldNameCache(int maxEntries) {
    // JsonConfig owns range validation; its upper bound keeps this doubled slot count positive.
    this.maxEntries = maxEntries;
    int requiredSlots = maxEntries << 1;
    int slots = Integer.highestOneBit(requiredSlots - 1) << 1;
    mask = slots - 1;
  }

  CachedFieldName get(long hash) {
    CachedFieldName[] localEntries = entries;
    if (localEntries == null) {
      return null;
    }
    int home = slot(hash);
    CachedFieldName entry = localEntries[home];
    if (entry == null) {
      // Entries are never removed or replaced, so a hash cannot occupy the adjacent slot while
      // its home remains empty.
      return null;
    }
    if (hashes[home] == hash) {
      return entry;
    }
    int adjacent = (home + 1) & mask;
    entry = localEntries[adjacent];
    return entry != null && hashes[adjacent] == hash ? entry : null;
  }

  boolean canPut(long hash) {
    if (size >= maxEntries) {
      return false;
    }
    CachedFieldName[] localEntries = entries;
    if (localEntries == null) {
      return true;
    }
    int home = slot(hash);
    CachedFieldName entry = localEntries[home];
    if (entry == null) {
      return true;
    }
    if (hashes[home] == hash) {
      return false;
    }
    int adjacent = (home + 1) & mask;
    entry = localEntries[adjacent];
    return entry == null;
  }

  void put(long hash, CachedFieldName entry) {
    Objects.requireNonNull(entry, "entry");
    CachedFieldName[] localEntries = entries;
    if (localEntries == null) {
      hashes = new long[mask + 1];
      localEntries = new CachedFieldName[mask + 1];
      entries = localEntries;
    }
    int home = slot(hash);
    CachedFieldName cached = localEntries[home];
    if (cached == null) {
      if (size < maxEntries) {
        hashes[home] = hash;
        localEntries[home] = entry;
        size++;
      }
      return;
    }
    if (hashes[home] == hash) {
      return;
    }
    int adjacent = (home + 1) & mask;
    cached = localEntries[adjacent];
    if (cached == null) {
      if (size < maxEntries) {
        hashes[adjacent] = hash;
        localEntries[adjacent] = entry;
        size++;
      }
      return;
    }
    if (hashes[adjacent] == hash) {
      return;
    }
  }

  private int slot(long hash) {
    int spread = (int) (hash ^ (hash >>> 32));
    spread ^= spread >>> 16;
    return spread & mask;
  }
}
