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
import org.apache.fory.collection.ObjectMap;
import org.apache.fory.memory.LittleEndian;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.EncodedMetaString;
import org.apache.fory.meta.Encoders;
import org.apache.fory.meta.MetaStringDecoder;
import org.apache.fory.resolver.MetaStringRef;
import org.apache.fory.util.MurmurHash3;

/** Read-side state for meta-string references. */
@Internal
public final class MetaStringReader {
  private static final int INITIAL_CAPACITY = 2;
  private static final float LOAD_FACTOR = 0.5f;
  private static final int SMALL_STRING_THRESHOLD = 16;

  private final ObjectMap<EncodedMetaString, String> metaString2StringMap =
      new ObjectMap<>(INITIAL_CAPACITY, LOAD_FACTOR);
  private final LongMap<EncodedMetaString> hash2MetaStringMap =
      new LongMap<>(INITIAL_CAPACITY, LOAD_FACTOR);
  private final LongLongByteMap<EncodedMetaString> longLongMetaStringMap =
      new LongLongByteMap<>(INITIAL_CAPACITY, LOAD_FACTOR);
  private final MetaStringRef emptyMetaStringRef;
  private MetaStringRef[] dynamicReadStringIds = new MetaStringRef[INITIAL_CAPACITY];
  private short dynamicReadStringId;

  public MetaStringReader() {
    emptyMetaStringRef = new MetaStringRef(EncodedMetaString.EMPTY);
    metaString2StringMap.put(EncodedMetaString.EMPTY, "");
  }

  public String readMetaString(MemoryBuffer buffer) {
    MetaStringRef metaStringRef = readMetaStringBytes(buffer);
    EncodedMetaString encodedMetaString = metaStringRef.getEncoded();
    String str = metaString2StringMap.get(encodedMetaString);
    if (str == null) {
      str = metaStringRef.decode(Encoders.GENERIC_DECODER);
      metaString2StringMap.put(encodedMetaString, str);
    }
    return str;
  }

  public MetaStringRef readMetaStringBytesWithFlag(MemoryBuffer buffer, int header) {
    int len = header >>> 2;
    if ((header & 0b10) == 0) {
      MetaStringRef metaStringRef =
          len <= SMALL_STRING_THRESHOLD
              ? readSmallMetaStringBytes(buffer, len)
              : readBigMetaStringBytes(buffer, len, buffer.readInt64());
      updateDynamicString(metaStringRef);
      return metaStringRef;
    }
    return dynamicReadStringIds[len - 1];
  }

  public MetaStringRef readMetaStringBytesWithFlag(
      MemoryBuffer buffer, MetaStringRef cache, int header) {
    int len = header >>> 2;
    if ((header & 0b10) == 0) {
      MetaStringRef metaStringRef =
          len <= SMALL_STRING_THRESHOLD
              ? readSmallMetaStringBytes(buffer, cache, len)
              : readBigMetaStringBytes(buffer, cache, len);
      updateDynamicString(metaStringRef);
      return metaStringRef;
    }
    return dynamicReadStringIds[len - 1];
  }

  public MetaStringRef readMetaStringBytes(MemoryBuffer buffer) {
    int header = buffer.readVarUint32Small7();
    int len = header >>> 1;
    if ((header & 0b1) == 0) {
      MetaStringRef metaStringRef =
          len > SMALL_STRING_THRESHOLD
              ? readBigMetaStringBytes(buffer, len, buffer.readInt64())
              : readSmallMetaStringBytes(buffer, len);
      updateDynamicString(metaStringRef);
      return metaStringRef;
    }
    return dynamicReadStringIds[len - 1];
  }

  public MetaStringRef readMetaStringBytes(MemoryBuffer buffer, MetaStringRef cache) {
    int header = buffer.readVarUint32Small7();
    int len = header >>> 1;
    if ((header & 0b1) == 0) {
      MetaStringRef metaStringRef =
          len <= SMALL_STRING_THRESHOLD
              ? readSmallMetaStringBytes(buffer, cache, len)
              : readBigMetaStringBytes(buffer, cache, len);
      updateDynamicString(metaStringRef);
      return metaStringRef;
    }
    return dynamicReadStringIds[len - 1];
  }

  private MetaStringRef readBigMetaStringBytes(MemoryBuffer buffer, MetaStringRef cache, int len) {
    long hashCode = buffer.readInt64();
    if (cache.getEncoded().hash == hashCode) {
      buffer.increaseReaderIndex(len);
      return cache;
    }
    return readBigMetaStringBytes(buffer, len, hashCode);
  }

  private MetaStringRef readBigMetaStringBytes(MemoryBuffer buffer, int len, long hashCode) {
    EncodedMetaString encodedMetaString = hash2MetaStringMap.get(hashCode);
    if (encodedMetaString == null) {
      EncodedMetaString newMetaString = new EncodedMetaString(buffer.readBytes(len), hashCode);
      hash2MetaStringMap.put(hashCode, newMetaString);
      return new MetaStringRef(newMetaString);
    }
    buffer.increaseReaderIndex(len);
    return new MetaStringRef(encodedMetaString);
  }

  private MetaStringRef readSmallMetaStringBytes(MemoryBuffer buffer, int len) {
    if (len == 0) {
      return emptyMetaStringRef;
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
      return createSmallMetaStringBytes(len, encoding, v1, v2);
    }
    return new MetaStringRef(encodedMetaString);
  }

  private MetaStringRef readSmallMetaStringBytes(
      MemoryBuffer buffer, MetaStringRef cache, int len) {
    if (len == 0) {
      return emptyMetaStringRef;
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
    EncodedMetaString cachedMetaString = cache.getEncoded();
    if (cachedMetaString.first8Bytes == v1 && cachedMetaString.second8Bytes == v2) {
      return cache;
    }
    EncodedMetaString encodedMetaString = longLongMetaStringMap.get(v1, v2, encoding);
    if (encodedMetaString == null) {
      return createSmallMetaStringBytes(len, encoding, v1, v2);
    }
    return new MetaStringRef(encodedMetaString);
  }

  private MetaStringRef createSmallMetaStringBytes(int len, byte encoding, long v1, long v2) {
    byte[] data = new byte[16];
    LittleEndian.putInt64(data, 0, v1);
    LittleEndian.putInt64(data, 8, v2);
    long hashCode = MurmurHash3.murmurhash3_x64_128(data, 0, len, 47)[0];
    hashCode = Math.abs(hashCode);
    hashCode = (hashCode & 0xffffffffffffff00L) | encoding;
    EncodedMetaString encodedMetaString = new EncodedMetaString(Arrays.copyOf(data, len), hashCode);
    longLongMetaStringMap.put(v1, v2, encoding, encodedMetaString);
    return new MetaStringRef(encodedMetaString);
  }

  private void updateDynamicString(MetaStringRef metaStringRef) {
    short currentDynamicReadId = dynamicReadStringId++;
    MetaStringRef[] readStringIds = dynamicReadStringIds;
    if (readStringIds.length <= currentDynamicReadId) {
      readStringIds = dynamicReadStringIds = growRead(readStringIds, currentDynamicReadId);
    }
    readStringIds[currentDynamicReadId] = metaStringRef;
  }

  private MetaStringRef[] growRead(MetaStringRef[] current, int id) {
    int newLength = current.length;
    while (newLength <= id) {
      newLength <<= 1;
    }
    MetaStringRef[] expanded = new MetaStringRef[newLength];
    System.arraycopy(current, 0, expanded, 0, current.length);
    return expanded;
  }

  public void reset() {
    int dynamicReadId = dynamicReadStringId;
    if (dynamicReadId != 0) {
      for (int i = 0; i < dynamicReadId; i++) {
        dynamicReadStringIds[i] = null;
      }
      dynamicReadStringId = 0;
    }
  }

  public String decode(MetaStringRef metaStringRef, MetaStringDecoder decoder) {
    return metaStringRef.decode(decoder);
  }
}
