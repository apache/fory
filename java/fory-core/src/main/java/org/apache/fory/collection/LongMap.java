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

import static org.apache.fory.collection.ForyObjectMap.MASK_NUMBER;

import java.util.Arrays;

// Derived from
// https://github.com/EsotericSoftware/kryo/blob/135df69526615bb3f6b34846e58ba3fec3b631c3/src/com/esotericsoftware/kryo/util/IntMap.java.

/**
 * An unordered map where the keys are unboxed longs and values are objects. No allocation is done
 * except when growing the table size.
 *
 * <p>This class performs fast contains and remove (typically O(1), worst case O(n) but that is rare
 * in practice). Add may be slightly slower, depending on hash collisions. Hashcodes are rehashed to
 * reduce collisions and the need to resize. Load factors greater than 0.91 greatly increase the
 * chances to resize to the next higher POT size.
 *
 * <p>Unordered sets and maps are not designed to provide especially fast iteration.
 *
 * <p>This implementation uses linear probing with the backward shift algorithm for removal.
 * Hashcodes are rehashed using Fibonacci hashing, instead of the more common power-of-two mask, to
 * better distribute poor hashCodes (see <a
 * href="https://probablydance.com/2018/06/16/fibonacci-hashing-the-optimization-that-the-world-forgot-or-a-better-alternative-to-integer-modulo/">Malte
 * Skarupke's blog post</a>). Linear probing continues to work even when all hashCodes collide, just
 * more slowly.
 *
 * @author Nathan Sweet
 * @author Tommy Ettinger
 */
public class LongMap<V> {
  public int size;

  long[] keyTable;
  V[] valueTable;

  V zeroValue;
  boolean hasZeroValue;

  private final float loadFactor;
  private int threshold;

  /**
   * Used by {@link #place(long)} to bit shift the upper bits of a {@code long} into a usable range
   * (&gt;= 0 and &lt;= {@link #mask}). The shift can be negative, which is convenient to match the
   * number of bits in mask: if mask is a 7-bit number, a shift of -7 shifts the upper 7 bits into
   * the lowest 7 positions. This class sets the shift &gt; 32 and &lt; 64, which if used with an
   * int will still move the upper bits of an int to the lower bits due to Java's implicit modulus
   * on shifts.
   *
   * <p>{@link #mask} can also be used to mask the low bits of a number, which may be faster for
   * some hashcodes, if {@link #place(long)} is overridden.
   */
  protected int shift;

  /**
   * A bitmask used to confine hash codes to the size of the table. Must be all 1 bits in its low
   * positions, ie a power of two minus 1. If {@link #place(long)} is override, this can be used
   * instead of {@link #shift} to isolate usable bits of a hash.
   */
  protected int mask;

  /** Creates a new map with an initial capacity of 51 and a load factor of 0.5. */
  public LongMap() {
    this(51, 0.5f);
  }

  /**
   * Creates a new map with a load factor of 0.5.
   *
   * @param initialCapacity If not a power of two, it is increased to the next nearest power of two.
   */
  public LongMap(int initialCapacity) {
    this(initialCapacity, 0.5f);
  }

  /**
   * Creates a new map with the specified initial capacity and load factor. This map will hold
   * initialCapacity items before growing the backing table.
   *
   * @param initialCapacity If not a power of two, it is increased to the next nearest power of two.
   */
  public LongMap(int initialCapacity, float loadFactor) {
    if (loadFactor <= 0f || loadFactor >= 1f) {
      throw new IllegalArgumentException("loadFactor must be > 0 and < 1: " + loadFactor);
    }
    this.loadFactor = loadFactor;

    int tableSize = ForyObjectMap.tableSize(initialCapacity, loadFactor);
    threshold = (int) (tableSize * loadFactor);
    mask = tableSize - 1;
    shift = Long.numberOfLeadingZeros(mask);

    keyTable = new long[tableSize];
    valueTable = (V[]) new Object[tableSize];
  }

  /** Creates a new map identical to the specified map. */
  public LongMap(LongMap<? extends V> map) {
    this((int) (map.keyTable.length * map.loadFactor), map.loadFactor);
    System.arraycopy(map.keyTable, 0, keyTable, 0, map.keyTable.length);
    System.arraycopy(map.valueTable, 0, valueTable, 0, map.valueTable.length);
    size = map.size;
    zeroValue = map.zeroValue;
    hasZeroValue = map.hasZeroValue;
  }

  /**
   * Returns an index >= 0 and <= {@link #mask} for the specified {@code item}.
   *
   * <p>The default implementation uses Fibonacci hashing on the item's {@link Object#hashCode()}:
   * the hashcode is multiplied by a long constant (2 to the 64th, divided by the golden ratio) then
   * the uppermost bits are shifted into the lowest positions to obtain an index in the desired
   * range. Multiplication by a long may be slower than int (eg on GWT) but greatly improves
   * rehashing, allowing even very poor hashcodes, such as those that only differ in their upper
   * bits, to be used without high collision rates. Fibonacci hashing has increased collision rates
   * when all or most hashcodes are multiples of larger Fibonacci numbers (see <a href=
   * "https://probablydance.com/2018/06/16/fibonacci-hashing-the-optimization-that-the-world-forgot-or-a-better-alternative-to-integer-modulo/">Malte
   * Skarupke's blog post</a>).
   *
   * <p>This method can be overridden to customizing hashing. This may be useful eg in the unlikely
   * event that most hashcodes are Fibonacci numbers, if keys provide poor or incorrect hashcodes,
   * or to simplify hashing if keys provide high quality hashcodes and don't need Fibonacci hashing:
   * {@code return item.hashCode() & mask;}
   */
  protected int place(long item) {
    return (int) (item * MASK_NUMBER >>> shift);
  }

  /**
   * Returns the index of the key if already present, else -(index + 1) for the next empty index.
   * This can be overridden in this package to compare for equality differently than {@link
   * Object#equals(Object)}.
   */
  private int locateKey(long key) {
    long[] keyTable = this.keyTable;
    int mask = this.mask;
    for (int i = place(key); ; i = i + 1 & mask) {
      long other = keyTable[i];
      if (other == 0) {
        return -(i + 1); // Empty space is available.
      }
      if (other == key) {
        return i; // Same key was found.
      }
    }
  }

  public V put(long key, V value) {
    if (key == 0) {
      V oldValue = zeroValue;
      zeroValue = value;
      if (!hasZeroValue) {
        hasZeroValue = true;
        size++;
      }
      return oldValue;
    }
    int i = locateKey(key);
    if (i >= 0) { // Existing key was found.
      V[] valueTable = this.valueTable;
      V oldValue = valueTable[i];
      valueTable[i] = value;
      return oldValue;
    }
    i = -(i + 1); // Empty space was found.
    keyTable[i] = key;
    valueTable[i] = value;
    if (++size >= threshold) {
      resize(keyTable.length << 1);
    }
    return null;
  }

  /** Skips checks for existing keys, doesn't increment size, doesn't need to handle key 0. */
  private void putResize(long key, V value) {
    long[] keyTable = this.keyTable;
    for (int i = place(key); ; i = (i + 1) & mask) {
      if (keyTable[i] == 0) {
        keyTable[i] = key;
        valueTable[i] = value;
        return;
      }
    }
  }

  public V get(long key) {
    if (key == 0) {
      return hasZeroValue ? zeroValue : null;
    }
    long[] keyTable = this.keyTable;
    for (int i = place(key); ; i = i + 1 & mask) {
      long other = keyTable[i];
      if (other == 0) {
        return null;
      }
      if (other == key) {
        return valueTable[i];
      }
    }
  }

  public V get(long key, V defaultValue) {
    if (key == 0) {
      return hasZeroValue ? zeroValue : null;
    }
    long[] keyTable = this.keyTable;
    int mask = this.mask;
    for (int i = place(key); ; i = i + 1 & mask) {
      long other = keyTable[i];
      if (other == 0) {
        return defaultValue;
      }
      if (other == key) {
        return valueTable[i];
      }
    }
  }

  public V remove(long key) {
    if (key == 0) {
      if (!hasZeroValue) {
        return null;
      }
      hasZeroValue = false;
      V oldValue = zeroValue;
      zeroValue = null;
      size--;
      return oldValue;
    }

    int i = locateKey(key);
    if (i < 0) {
      return null;
    }
    long[] keyTable = this.keyTable;
    V[] valueTable = this.valueTable;
    V oldValue = valueTable[i];
    int mask = this.mask, next = i + 1 & mask;
    while ((key = keyTable[next]) != 0) {
      int placement = place(key);
      if ((next - placement & mask) > (i - placement & mask)) {
        keyTable[i] = key;
        valueTable[i] = valueTable[next];
        i = next;
      }
      next = next + 1 & mask;
    }
    keyTable[i] = 0;
    size--;
    return oldValue;
  }

  /** Returns true if the map has one or more items. */
  public boolean notEmpty() {
    return size > 0;
  }

  /** Returns true if the map is empty. */
  public boolean isEmpty() {
    return size == 0;
  }

  /**
   * Clears the map and reduces the size of the backing arrays to be the specified capacity /
   * loadFactor, if they are larger.
   */
  public void clear(int maximumCapacity) {
    int tableSize = ForyObjectMap.tableSize(maximumCapacity, loadFactor);
    if (keyTable.length <= tableSize) {
      clear();
      return;
    }
    size = 0;
    hasZeroValue = false;
    zeroValue = null;
    resize(tableSize);
  }

  public void clear() {
    if (size == 0) {
      return;
    }
    size = 0;
    Arrays.fill(keyTable, 0);
    ObjectArray.clearObjectArray(valueTable, 0, valueTable.length);
    zeroValue = null;
    hasZeroValue = false;
  }

  public boolean containsKey(int key) {
    if (key == 0) {
      return hasZeroValue;
    }
    return locateKey(key) >= 0;
  }

  /**
   * Increases the size of the backing array to accommodate the specified number of additional items
   * / loadFactor. Useful before adding many items to avoid multiple backing array resizes.
   */
  public void ensureCapacity(int additionalCapacity) {
    int tableSize = ForyObjectMap.tableSize(size + additionalCapacity, loadFactor);
    if (keyTable.length < tableSize) {
      resize(tableSize);
    }
  }

  private void resize(int newSize) {
    int oldCapacity = keyTable.length;
    threshold = (int) (newSize * loadFactor);
    mask = newSize - 1;
    shift = Long.numberOfLeadingZeros(mask);
    long[] oldKeyTable = keyTable;
    V[] oldValueTable = valueTable;
    keyTable = new long[newSize];
    valueTable = (V[]) new Object[newSize];
    if (size > 0) {
      for (int i = 0; i < oldCapacity; i++) {
        long key = oldKeyTable[i];
        if (key != 0) {
          putResize(key, oldValueTable[i]);
        }
      }
    }
  }

  public String toString() {
    if (size == 0) {
      return "[]";
    }
    StringBuilder buffer = new StringBuilder(32);
    buffer.append('[');
    long[] keyTable = this.keyTable;
    V[] valueTable = this.valueTable;
    int i = keyTable.length;
    if (hasZeroValue) {
      buffer.append("0=");
      buffer.append(zeroValue);
    } else {
      while (i-- > 0) {
        long key = keyTable[i];
        if (key == 0) {
          continue;
        }
        buffer.append(key);
        buffer.append('=');
        buffer.append(valueTable[i]);
        break;
      }
    }
    while (i-- > 0) {
      long key = keyTable[i];
      if (key == 0) {
        continue;
      }
      buffer.append(", ");
      buffer.append(key);
      buffer.append('=');
      buffer.append(valueTable[i]);
    }
    buffer.append(']');
    return buffer.toString();
  }
}
