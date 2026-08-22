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
import org.apache.fory.exception.ForyException;
import org.apache.fory.memory.LittleEndian;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.EncodedMetaString;
import org.apache.fory.meta.MetaString;
import org.apache.fory.resolver.SharedRegistry;

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
  private static final int ENCODING_BITS = 4;
  private static final int MAX_CACHED_READ_META_STRINGS = 8192;
  private static final int MAX_CACHED_READ_META_STRING_LENGTH = 2048;

  private final LongMap<EncodedMetaString> hash2MetaStringMap =
      new LongMap<>(INITIAL_CAPACITY, LOAD_FACTOR);
  private final LongLongByteMap<EncodedMetaString> longLongMetaStringMap =
      new LongLongByteMap<>(INITIAL_CAPACITY, LOAD_FACTOR);
  private final SharedRegistry sharedRegistry;
  private EncodedMetaString[] dynamicReadStringIds = new EncodedMetaString[INITIAL_CAPACITY];
  private int dynamicReadStringId;

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
    return readDynamicString(len);
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
    return readDynamicString(len);
  }

  /** Reads a meta string from the current buffer, including any dynamic-id indirection. */
  public EncodedMetaString readMetaString(MemoryBuffer buffer) {
    int header = buffer.readVarUInt32Small7();
    int len = header >>> 1;
    if ((header & 0b1) == 0) {
      EncodedMetaString encodedMetaString =
          len > SMALL_STRING_THRESHOLD
              ? readBigMetaString(buffer, len, buffer.readInt64())
              : readSmallMetaString(buffer, len);
      updateDynamicString(encodedMetaString);
      return encodedMetaString;
    }
    return readDynamicString(len);
  }

  /**
   * Reads a meta string from the current buffer while consulting a caller-supplied cache candidate
   * first.
   */
  public EncodedMetaString readMetaString(MemoryBuffer buffer, EncodedMetaString cache) {
    int header = buffer.readVarUInt32Small7();
    int len = header >>> 1;
    if ((header & 0b1) == 0) {
      EncodedMetaString encodedMetaString =
          len <= SMALL_STRING_THRESHOLD
              ? readSmallMetaString(buffer, cache, len)
              : readBigMetaString(buffer, cache, len);
      updateDynamicString(encodedMetaString);
      return encodedMetaString;
    }
    return readDynamicString(len);
  }

  private EncodedMetaString readBigMetaString(
      MemoryBuffer buffer, EncodedMetaString cache, int len) {
    long hashCode = buffer.readInt64();
    if (cache.hash == hashCode) {
      // The hash is the complete wire identity for a checked big meta-string cache hit. The frame
      // length controls only readable-byte validation and cursor advancement; it must not reopen
      // identity validation after the entry was first checked and published.
      buffer.checkReadableBytes(len);
      buffer._increaseReaderIndexUnsafe(len);
      return cache;
    }
    return readBigMetaString(buffer, len, hashCode);
  }

  private EncodedMetaString readBigMetaString(MemoryBuffer buffer, int len, long hashCode) {
    buffer.checkReadableBytes(len);
    EncodedMetaString encodedMetaString = hash2MetaStringMap.get(hashCode);
    if (encodedMetaString != null) {
      // Entries reach this map only after their bytes matched the wire hash. A repeat hash hit uses
      // that checked identity and advances over this frame's redundant body without revalidation.
      buffer._increaseReaderIndexUnsafe(len);
      return encodedMetaString;
    }
    byte[] bytes = readAndValidateBigMetaString(buffer, len, hashCode);
    EncodedMetaString canonicalMetaString =
        sharedRegistry.getOrCreateEncodedMetaString(bytes, hashCode);
    if (encodedMetaString == null
        && len <= MAX_CACHED_READ_META_STRING_LENGTH
        && hash2MetaStringMap.size < MAX_CACHED_READ_META_STRINGS) {
      hash2MetaStringMap.put(hashCode, canonicalMetaString);
    }
    return canonicalMetaString;
  }

  private byte[] readAndValidateBigMetaString(MemoryBuffer buffer, int len, long hashCode) {
    byte[] bytes = buffer.readBytes(len);
    MetaString.Encoding encoding = MetaString.Encoding.fromInt((int) (hashCode & 0xff));
    long canonicalHash = EncodedMetaString.computeHash(bytes, encoding);
    if (canonicalHash != hashCode) {
      throw new ForyException("Malformed meta string hash");
    }
    return bytes;
  }

  private boolean shouldCacheSmallMetaString() {
    return longLongMetaStringMap.size < MAX_CACHED_READ_META_STRINGS;
  }

  private EncodedMetaString cacheSmallMetaString(
      long v1, long v2, byte key, EncodedMetaString encodedMetaString) {
    if (shouldCacheSmallMetaString()) {
      longLongMetaStringMap.put(v1, v2, key, encodedMetaString);
    }
    return encodedMetaString;
  }

  private EncodedMetaString createSmallMetaString(
      int len, MetaString.Encoding encoding, byte key, long v1, long v2) {
    byte[] data = new byte[16];
    LittleEndian.putInt64(data, 0, v1);
    LittleEndian.putInt64(data, 8, v2);
    byte[] bytes = Arrays.copyOf(data, len);
    long hashCode = EncodedMetaString.computeHash(bytes, encoding);
    return cacheSmallMetaString(
        v1, v2, key, sharedRegistry.getOrCreateEncodedMetaString(bytes, hashCode));
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
    int encodingValue = encoding & 0xff;
    byte key = smallMetaStringKey(len, encodingValue);
    EncodedMetaString encodedMetaString = longLongMetaStringMap.get(v1, v2, key);
    if (encodedMetaString == null) {
      return createSmallMetaString(len, MetaString.Encoding.fromInt(encodingValue), key, v1, v2);
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
    int encodingValue = encoding & 0xff;
    if (cache.bytes.length == len
        && cache.encodingValue == encodingValue
        && cache.first8Bytes == v1
        && cache.second8Bytes == v2) {
      return cache;
    }
    byte key = smallMetaStringKey(len, encodingValue);
    EncodedMetaString encodedMetaString = longLongMetaStringMap.get(v1, v2, key);
    if (encodedMetaString == null) {
      return createSmallMetaString(len, MetaString.Encoding.fromInt(encodingValue), key, v1, v2);
    }
    return encodedMetaString;
  }

  private static byte smallMetaStringKey(int len, int encodingValue) {
    return (byte) (((len - 1) << ENCODING_BITS) | encodingValue);
  }

  private EncodedMetaString readDynamicString(int dynamicId) {
    if (dynamicId <= 0 || dynamicId > dynamicReadStringId) {
      throw new ForyException("Invalid meta string reference id " + dynamicId);
    }
    return dynamicReadStringIds[dynamicId - 1];
  }

  private void updateDynamicString(EncodedMetaString encodedMetaString) {
    int currentDynamicReadId = dynamicReadStringId++;
    EncodedMetaString[] readStringIds = dynamicReadStringIds;
    if (readStringIds.length <= currentDynamicReadId) {
      if (currentDynamicReadId >= MAX_CACHED_READ_META_STRINGS) {
        throw new ForyException("Too many meta string references in payload");
      }
      readStringIds = dynamicReadStringIds = growRead(readStringIds, currentDynamicReadId);
    }
    readStringIds[currentDynamicReadId] = encodedMetaString;
  }

  private static EncodedMetaString[] growRead(EncodedMetaString[] current, int id) {
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
