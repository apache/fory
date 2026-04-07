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

package org.apache.fory.context;

import java.util.Arrays;
import org.apache.fory.annotation.Internal;
import org.apache.fory.collection.LongLongByteMap;
import org.apache.fory.collection.LongMap;
import org.apache.fory.memory.LittleEndian;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.EncodedMetaString;
import org.apache.fory.resolver.SharedRegistry;
import org.apache.fory.util.MurmurHash3;

/**
 * Read-side state for meta-string references.
 *
 * <p>The reader interns incoming {@link EncodedMetaString} values and assigns dense dynamic ids to
 * newly seen entries so later references can resolve them without allocating new wrappers.
 */
@Internal
public final class MetaStringReader {
  private static final int INITIAL_CAPACITY = 2;
  private static final float LOAD_FACTOR = 0.5f;
  private static final int SMALL_STRING_THRESHOLD = 16;

  private final LongMap<EncodedMetaString> hash2MetaStringMap =
      new LongMap<>(INITIAL_CAPACITY, LOAD_FACTOR);
  private final LongLongByteMap<EncodedMetaString> longLongMetaStringMap =
      new LongLongByteMap<>(INITIAL_CAPACITY, LOAD_FACTOR);
  private final SharedRegistry sharedRegistry;
  private EncodedMetaString[] dynamicReadStringIds = new EncodedMetaString[INITIAL_CAPACITY];
  private short dynamicReadStringId;

  /** Creates an empty reader state for one deserialization stream. */
  public MetaStringReader(SharedRegistry sharedRegistry) {
    this.sharedRegistry = sharedRegistry;
  }

  /**
   * Reads a meta string whose header has already been parsed from the stream and includes the
   * protocol flag bit layout.
   */
  public EncodedMetaString readMetaStringWithFlag(MemoryBuffer buffer, int header) {
    int len = header >>> 2;
    if ((header & 0b10) == 0) {
      EncodedMetaString encodedMetaString =
          len <= SMALL_STRING_THRESHOLD
              ? readSmallMetaString(buffer, len)
              : readBigMetaString(buffer, len, buffer.readInt64());
      updateDynamicString(encodedMetaString);
      return encodedMetaString;
    }
    return dynamicReadStringIds[len - 1];
  }

  /**
   * Reads a flagged meta string while consulting a caller-supplied cache candidate first.
   *
   * <p>The cache allows call sites with a likely expected value to avoid an additional map lookup
   * for exact matches.
   */
  public EncodedMetaString readMetaStringWithFlag(
      MemoryBuffer buffer, EncodedMetaString cache, int header) {
    int len = header >>> 2;
    if ((header & 0b10) == 0) {
      EncodedMetaString encodedMetaString =
          len <= SMALL_STRING_THRESHOLD
              ? readSmallMetaString(buffer, cache, len)
              : readBigMetaString(buffer, cache, len);
      updateDynamicString(encodedMetaString);
      return encodedMetaString;
    }
    return dynamicReadStringIds[len - 1];
  }

  /** Reads a meta string from the current buffer, including any dynamic-id indirection. */
  public EncodedMetaString readMetaString(MemoryBuffer buffer) {
    int header = buffer.readVarUint32Small7();
    int len = header >>> 1;
    if ((header & 0b1) == 0) {
      EncodedMetaString encodedMetaString =
          len > SMALL_STRING_THRESHOLD
              ? readBigMetaString(buffer, len, buffer.readInt64())
              : readSmallMetaString(buffer, len);
      updateDynamicString(encodedMetaString);
      return encodedMetaString;
    }
    return dynamicReadStringIds[len - 1];
  }

  /**
   * Reads a meta string from the current buffer while consulting a caller-supplied cache candidate
   * first.
   */
  public EncodedMetaString readMetaString(MemoryBuffer buffer, EncodedMetaString cache) {
    int header = buffer.readVarUint32Small7();
    int len = header >>> 1;
    if ((header & 0b1) == 0) {
      EncodedMetaString encodedMetaString =
          len <= SMALL_STRING_THRESHOLD
              ? readSmallMetaString(buffer, cache, len)
              : readBigMetaString(buffer, cache, len);
      updateDynamicString(encodedMetaString);
      return encodedMetaString;
    }
    return dynamicReadStringIds[len - 1];
  }

  private EncodedMetaString readBigMetaString(
      MemoryBuffer buffer, EncodedMetaString cache, int len) {
    long hashCode = buffer.readInt64();
    if (cache.hash == hashCode) {
      buffer.increaseReaderIndex(len);
      return cache;
    }
    return readBigMetaString(buffer, len, hashCode);
  }

  private EncodedMetaString readBigMetaString(MemoryBuffer buffer, int len, long hashCode) {
    EncodedMetaString encodedMetaString = hash2MetaStringMap.get(hashCode);
    if (encodedMetaString == null) {
      encodedMetaString =
          sharedRegistry.getOrCreateEncodedMetaString(buffer.readBytes(len), hashCode);
      hash2MetaStringMap.put(hashCode, encodedMetaString);
      return encodedMetaString;
    }
    buffer.increaseReaderIndex(len);
    return encodedMetaString;
  }

  private EncodedMetaString readSmallMetaString(MemoryBuffer buffer, int len) {
    if (len == 0) {
      return EncodedMetaString.EMPTY;
    }
    byte encoding = buffer.readByte();
    long v1;
    long v2 = 0;
    if (len <= 8) {
      v1 = buffer.readBytesAsInt64(len);
    } else {
      v1 = buffer.readInt64();
      v2 = buffer.readBytesAsInt64(len - 8);
    }
    EncodedMetaString encodedMetaString = longLongMetaStringMap.get(v1, v2, encoding);
    if (encodedMetaString == null) {
      return createSmallMetaString(len, encoding, v1, v2);
    }
    return encodedMetaString;
  }

  private EncodedMetaString readSmallMetaString(
      MemoryBuffer buffer, EncodedMetaString cache, int len) {
    if (len == 0) {
      return EncodedMetaString.EMPTY;
    }
    byte encoding = buffer.readByte();
    long v1;
    long v2 = 0;
    if (len <= 8) {
      v1 = buffer.readBytesAsInt64(len);
    } else {
      v1 = buffer.readInt64();
      v2 = buffer.readBytesAsInt64(len - 8);
    }
    if (cache.first8Bytes == v1 && cache.second8Bytes == v2) {
      return cache;
    }
    EncodedMetaString encodedMetaString = longLongMetaStringMap.get(v1, v2, encoding);
    if (encodedMetaString == null) {
      return createSmallMetaString(len, encoding, v1, v2);
    }
    return encodedMetaString;
  }

  private EncodedMetaString createSmallMetaString(int len, byte encoding, long v1, long v2) {
    byte[] data = new byte[16];
    LittleEndian.putInt64(data, 0, v1);
    LittleEndian.putInt64(data, 8, v2);
    long hashCode = MurmurHash3.murmurhash3_x64_128(data, 0, len, 47)[0];
    hashCode = Math.abs(hashCode);
    hashCode = (hashCode & 0xffffffffffffff00L) | encoding;
    EncodedMetaString encodedMetaString =
        sharedRegistry.getOrCreateEncodedMetaString(Arrays.copyOf(data, len), hashCode);
    longLongMetaStringMap.put(v1, v2, encoding, encodedMetaString);
    return encodedMetaString;
  }

  private void updateDynamicString(EncodedMetaString encodedMetaString) {
    short currentDynamicReadId = dynamicReadStringId++;
    EncodedMetaString[] readStringIds = dynamicReadStringIds;
    if (readStringIds.length <= currentDynamicReadId) {
      readStringIds = dynamicReadStringIds = growRead(readStringIds, currentDynamicReadId);
    }
    readStringIds[currentDynamicReadId] = encodedMetaString;
  }

  private EncodedMetaString[] growRead(EncodedMetaString[] current, int id) {
    int newLength = current.length;
    while (newLength <= id) {
      newLength <<= 1;
    }
    EncodedMetaString[] expanded = new EncodedMetaString[newLength];
    System.arraycopy(current, 0, expanded, 0, current.length);
    return expanded;
  }

  /** Clears all dynamic ids so this reader can be reused for a new deserialization stream. */
  public void reset() {
    int dynamicReadId = dynamicReadStringId;
    if (dynamicReadId != 0) {
      for (int i = 0; i < dynamicReadId; i++) {
        dynamicReadStringIds[i] = null;
      }
      dynamicReadStringId = 0;
    }
  }
}
