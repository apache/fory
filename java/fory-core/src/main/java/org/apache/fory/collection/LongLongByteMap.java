/* Copyright (c) 2008-2023, Nathan Sweet
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. */

package org.apache.fory.collection;

import org.apache.fory.annotation.Internal;
import org.apache.fory.util.Preconditions;

/**
 * A fast linear hash probe based map whose key is two long and a byte values `(long k1, long k2,
 * byte k3)`. This map can avoid creating a java object for key to save memory/cpu cost.
 */
// The linear probed hash is derived from
// https://github.com/EsotericSoftware/kryo/blob/135df69526615bb3f6b34846e58ba3fec3b631c3/src/com/esotericsoftware/kryo/util/IntMap.java.
@SuppressWarnings("unchecked")
@Internal
public final class LongLongByteMap<V> {
  private static final class LongLongByteKey {
    private final long k1;
    private final long k2;
    private final byte k3;

    public LongLongByteKey(long k1, long k2, byte k3) {
      this.k1 = k1;
      this.k2 = k2;
      this.k3 = k3;
    }

    @Override
    public String toString() {
      return "LongLongByteKey{" + "k1=" + k1 + ", k2=" + k2 + ", k3=" + k3 + '}';
    }
  }

  public int size;
  LongLongByteKey[] keyTable;
  V[] valueTable;
  private final float loadFactor;
  private int threshold;

  private int shift;

  private int mask;

  /**
   * Creates a new map with the specified initial capacity and load factor. This map will hold
   * initialCapacity items before growing the backing table.
   *
   * @param initialCapacity If not a power of two, it is increased to the next nearest power of two.
   */
  public LongLongByteMap(int initialCapacity, float loadFactor) {
    Preconditions.checkArgument(
        0 <= loadFactor && loadFactor <= 1, "loadFactor %s must be > 0 and < 1", loadFactor);
    this.loadFactor = loadFactor;
    int tableSize = ForyObjectMap.tableSize(initialCapacity, loadFactor);
    threshold = (int) (tableSize * loadFactor);
    mask = tableSize - 1;
    shift = Long.numberOfLeadingZeros(mask);
    keyTable = new LongLongByteKey[tableSize];
    valueTable = (V[]) new Object[tableSize];
  }

  private int place(long k1, long k2, byte k3) {
    return (int) ((k1 * 31 + k2 * 17 + k3) * ForyObjectMap.MASK_NUMBER >>> shift);
  }

  /**
   * Returns the index of the key if already present, else -(index + 1) for the next empty index.
   * This can be overridden in this package to compare for equality differently than {@link
   * Object#equals(Object)}.
   */
  private int locateKey(long k1, long k2, byte k3) {
    LongLongByteKey[] keyTable = this.keyTable;
    int mask = this.mask;
    for (int i = place(k1, k2, k3); ; i = i + 1 & mask) {
      LongLongByteKey other = keyTable[i];
      if (other == null) {
        return -(i + 1); // Empty space is available.
      }
      if (other.k1 == k1 && other.k2 == k2 && other.k3 == k3) {
        return i; // Same key was found.
      }
    }
  }

  public V put(long k1, long k2, byte k3, V value) {
    int i = locateKey(k1, k2, k3);
    if (i >= 0) { // Existing key was found.
      V[] valueTable = this.valueTable;
      V oldValue = valueTable[i];
      valueTable[i] = value;
      return oldValue;
    }
    i = -(i + 1); // Empty space was found.
    keyTable[i] = new LongLongByteKey(k1, k2, k3);
    valueTable[i] = value;
    if (++size >= threshold) {
      resize(keyTable.length << 1);
    }
    return null;
  }

  public V get(long k1, long k2, byte k3) {
    LongLongByteKey[] keyTable = this.keyTable;
    for (int i = place(k1, k2, k3); ; i = i + 1 & mask) {
      LongLongByteKey other = keyTable[i];
      if (other == null) {
        return null;
      }
      if (other.k1 == k1 && other.k2 == k2 && other.k3 == k3) {
        return valueTable[i];
      }
    }
  }

  private void resize(int newSize) {
    int oldCapacity = keyTable.length;
    threshold = (int) (newSize * loadFactor);
    mask = newSize - 1;
    shift = Long.numberOfLeadingZeros(mask);
    LongLongByteKey[] oldKeyTable = keyTable;
    V[] oldValueTable = valueTable;
    keyTable = new LongLongByteKey[newSize];
    valueTable = (V[]) new Object[newSize];
    if (size > 0) {
      for (int i = 0; i < oldCapacity; i++) {
        LongLongByteKey key = oldKeyTable[i];
        if (key != null) {
          for (int j = place(key.k1, key.k2, key.k3); ; j = (j + 1) & mask) {
            if (keyTable[j] == null) {
              keyTable[j] = new LongLongByteKey(key.k1, key.k2, key.k3);
              valueTable[j] = oldValueTable[i];
              break;
            }
          }
        }
      }
    }
  }
}
