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

package org.apache.fory.resolver;

import java.util.Arrays;
import java.util.Objects;

import org.apache.fory.collection.LongLongByteMap;
import org.apache.fory.collection.LongMap;
import org.apache.fory.collection.ObjectMap;
import org.apache.fory.memory.LittleEndian;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.Encoders;
import org.apache.fory.meta.MetaString;
import org.apache.fory.util.MurmurHash3;

/**
 * A resolver for limited string value writing. Currently, we only support classname dynamic
 * writing. In the future, we may profile string field value dynamically and writing by this
 * resolver to reduce string cost. TODO add common inner package names and classnames here. TODO
 * share common immutable datastructure globally across multiple fory.
 */
public final class MetaStringResolver {
  private static final int initialCapacity = 8;
  // use a lower load factor to minimize hash collision
  private static final float foryMapLoadFactor = 0.25f;
  private static final int SMALL_STRING_THRESHOLD = 16;

  // Every deserialization for unregistered string will query it, performance is important.
  private final ObjectMap<MetaStringBytes, String> metaStringBytes2StringMap =
      new ObjectMap<>(initialCapacity, foryMapLoadFactor);
  private final LongMap<MetaStringBytes> hash2MetaStringBytesMap =
      new LongMap<>(initialCapacity, foryMapLoadFactor);
  private final LongLongByteMap<MetaStringBytes> longLongByteMap =
      new LongLongByteMap<>(initialCapacity, foryMapLoadFactor);
  // Every enum bytes should be singleton at every fory, since we keep state in it.
  private final ObjectMap<MetaString, MetaStringBytes> metaString2BytesMap =
      new ObjectMap<>(initialCapacity, foryMapLoadFactor);
  private MetaStringBytes[] dynamicWrittenString = new MetaStringBytes[32];
  private MetaStringBytes[] dynamicReadStringIds = new MetaStringBytes[32];
  private short dynamicWriteStringId;
  private short dynamicReadStringId;

  public MetaStringResolver() {
    dynamicWriteStringId = 0;
    dynamicReadStringId = 0;
  }

  public MetaStringBytes getOrCreateMetaStringBytes(MetaString str) {
    MetaStringBytes metaStringBytes = metaString2BytesMap.get(str);
    if (metaStringBytes == null) {
      metaStringBytes = MetaStringBytes.of(str);
      metaString2BytesMap.put(str, metaStringBytes);
    }
    return metaStringBytes;
  }

  public void writeMetaStringBytesWithFlag(MemoryBuffer buffer, MetaStringBytes byteString) {
    Objects.requireNonNull(byteString);
    short id = byteString.dynamicWriteStringId;
    if (id == MetaStringBytes.DEFAULT_DYNAMIC_WRITE_STRING_ID) {
      // noinspection Duplicates
      id = dynamicWriteStringId++;
      byteString.dynamicWriteStringId = id;
      MetaStringBytes[] dynamicWrittenMetaString = this.dynamicWrittenString;
      if (dynamicWrittenMetaString.length <= id) {
        dynamicWrittenMetaString = growWrite(id);
      }
      dynamicWrittenMetaString[id] = byteString;
      int length = byteString.bytes.length;
      // last bit `1` indicates class is written by name instead of registered id.
      buffer.writeVarUint32Small7(length << 2 | 0b1);
      if (length > SMALL_STRING_THRESHOLD) {
        buffer.writeInt64(byteString.hashCode);
      } else if (length != 0) {
        buffer.writeByte(byteString.encoding.getValue());
      }
      buffer.writeBytes(byteString.bytes);
    } else {
      // last bit `1` indicates class is written by name instead of registered id.
      buffer.writeVarUint32Small7(((id + 1) << 2) | 0b11);
    }
  }

  public void writeMetaStringBytes(MemoryBuffer buffer, MetaStringBytes byteString) {
    short id = byteString.dynamicWriteStringId;
    if (id == MetaStringBytes.DEFAULT_DYNAMIC_WRITE_STRING_ID) {
      // noinspection Duplicates
      id = dynamicWriteStringId++;
      byteString.dynamicWriteStringId = id;
      MetaStringBytes[] dynamicWrittenMetaString = this.dynamicWrittenString;
      if (dynamicWrittenMetaString.length <= id) {
        dynamicWrittenMetaString = growWrite(id);
      }
      dynamicWrittenMetaString[id] = byteString;
      int length = byteString.bytes.length;
      buffer.writeVarUint32Small7(length << 1);
      if (length > SMALL_STRING_THRESHOLD) {
        buffer.writeInt64(byteString.hashCode);
      } else if (length != 0) {
        buffer.writeByte(byteString.encoding.getValue());
      }
      buffer.writeBytes(byteString.bytes);
    } else {
      buffer.writeVarUint32Small7(((id + 1) << 1) | 1);
    }
  }

  private MetaStringBytes[] growWrite(int id) {
    MetaStringBytes[] tmp = new MetaStringBytes[id * 2];
    System.arraycopy(dynamicWrittenString, 0, tmp, 0, dynamicWrittenString.length);
    return this.dynamicWrittenString = tmp;
  }

  public String readMetaString(MemoryBuffer buffer) {
    MetaStringBytes byteString = readMetaStringBytes(buffer);
    String str = metaStringBytes2StringMap.get(byteString);
    if (str == null) {
      // TODO support meta string in other languages.
      str = byteString.decode(Encoders.GENERIC_DECODER);
      metaStringBytes2StringMap.put(byteString, str);
    }
    return str;
  }

  public MetaStringBytes readMetaStringBytesWithFlag(MemoryBuffer buffer, int header) {
    int len = header >>> 2;
    if ((header & 0b10) == 0) {
      MetaStringBytes byteString =
          len <= SMALL_STRING_THRESHOLD
              ? readSmallMetaStringBytes(buffer, len)
              : readBigMetaStringBytes(buffer, len, buffer.readInt64());
      updateDynamicString(byteString);
      return byteString;
    } else {
      return dynamicReadStringIds[len - 1];
    }
  }

  public MetaStringBytes readMetaStringBytesWithFlag(
      MemoryBuffer buffer, MetaStringBytes cache, int header) {
    int len = header >>> 2;
    if ((header & 0b10) == 0) {
      MetaStringBytes byteString =
          len <= SMALL_STRING_THRESHOLD
              ? readSmallMetaStringBytes(buffer, cache, len)
              : readBigMetaStringBytes(buffer, cache, len);
      updateDynamicString(byteString);
      return byteString;
    } else {
      return dynamicReadStringIds[len - 1];
    }
  }

  public MetaStringBytes readMetaStringBytes(MemoryBuffer buffer) {
    int header = buffer.readVarUint32Small7();
    int len = header >>> 1;
    if ((header & 0b1) == 0) {
      MetaStringBytes byteString =
          len > SMALL_STRING_THRESHOLD
              ? readBigMetaStringBytes(buffer, len, buffer.readInt64())
              : readSmallMetaStringBytes(buffer, len);
      updateDynamicString(byteString);
      return byteString;
    } else {
      return dynamicReadStringIds[len - 1];
    }
  }

  MetaStringBytes readMetaStringBytes(MemoryBuffer buffer, MetaStringBytes cache) {
    int header = buffer.readVarUint32Small7();
    int len = header >>> 1;
    if ((header & 0b1) == 0) {
      MetaStringBytes byteString =
          len <= SMALL_STRING_THRESHOLD
              ? readSmallMetaStringBytes(buffer, cache, len)
              : readBigMetaStringBytes(buffer, cache, len);
      updateDynamicString(byteString);
      return byteString;
    } else {
      return dynamicReadStringIds[len - 1];
    }
  }

  private MetaStringBytes readBigMetaStringBytes(
      MemoryBuffer buffer, MetaStringBytes cache, int len) {
    long hashCode = buffer.readInt64();
    if (cache.hashCode == hashCode) {
      // skip byteString data
      buffer.increaseReaderIndex(len);
      return cache;
    } else {
      return readBigMetaStringBytes(buffer, len, hashCode);
    }
  }

  /** Read enum string by try to reuse previous read {@link MetaStringBytes} object. */
  private MetaStringBytes readBigMetaStringBytes(MemoryBuffer buffer, int len, long hashCode) {
    MetaStringBytes byteString = hash2MetaStringBytesMap.get(hashCode);
    if (byteString == null) {
      byteString = new MetaStringBytes(buffer.readBytes(len), hashCode);
      hash2MetaStringBytesMap.put(hashCode, byteString);
    } else {
      // skip byteString data
      buffer.increaseReaderIndex(len);
    }
    return byteString;
  }

  private MetaStringBytes readSmallMetaStringBytes(MemoryBuffer buffer, int len) {
    if (len == 0) {
      return MetaStringBytes.EMPTY;
    }
    byte encoding = buffer.readByte();
    long v1, v2 = 0;
    if (len <= 8) {
      v1 = buffer.readBytesAsInt64(len);
    } else {
      v1 = buffer.readInt64();
      v2 = buffer.readBytesAsInt64(len - 8);
    }
    MetaStringBytes byteString = longLongByteMap.get(v1, v2, encoding);
    if (byteString == null) {
      byteString = createSmallMetaStringBytes(len, encoding, v1, v2);
    }
    return byteString;
  }

  private MetaStringBytes readSmallMetaStringBytes(
      MemoryBuffer buffer, MetaStringBytes cache, int len) {
    if (len == 0) {
      return MetaStringBytes.EMPTY;
    }
    byte encoding = buffer.readByte();
    long v1, v2 = 0;
    if (len <= 8) {
      v1 = buffer.readBytesAsInt64(len);
    } else {
      v1 = buffer.readInt64();
      v2 = buffer.readBytesAsInt64(len - 8);
    }
    if (cache.first8Bytes == v1 && cache.second8Bytes == v2) {
      return cache;
    }
    MetaStringBytes byteString = longLongByteMap.get(v1, v2, encoding);
    if (byteString == null) {
      byteString = createSmallMetaStringBytes(len, encoding, v1, v2);
    }
    return byteString;
  }

  private MetaStringBytes createSmallMetaStringBytes(int len, byte encoding, long v1, long v2) {
    byte[] data = new byte[16];
    LittleEndian.putInt64(data, 0, v1);
    LittleEndian.putInt64(data, 8, v2);
    long hashCode = MurmurHash3.murmurhash3_x64_128(data, 0, len, 47)[0];
    hashCode = Math.abs(hashCode);
    hashCode = (hashCode & 0xffffffffffffff00L) | encoding;
    MetaStringBytes metaStringBytes = new MetaStringBytes(Arrays.copyOf(data, len), hashCode);
    longLongByteMap.put(v1, v2, encoding, metaStringBytes);
    return metaStringBytes;
  }

  private void updateDynamicString(MetaStringBytes byteString) {
    short currentDynamicReadId = dynamicReadStringId++;
    MetaStringBytes[] dynamicReadStringIds = this.dynamicReadStringIds;
    if (dynamicReadStringIds.length <= currentDynamicReadId) {
      dynamicReadStringIds = growRead(currentDynamicReadId);
    }
    dynamicReadStringIds[currentDynamicReadId] = byteString;
  }

  private MetaStringBytes[] growRead(int id) {
    MetaStringBytes[] tmp = new MetaStringBytes[id * 2];
    System.arraycopy(dynamicReadStringIds, 0, tmp, 0, dynamicReadStringIds.length);
    return this.dynamicReadStringIds = tmp;
  }

  public void reset() {
    resetRead();
    resetWrite();
  }

  public void resetRead() {
    int dynamicReadId = this.dynamicReadStringId;
    if (dynamicReadId != 0) {
      for (int i = 0; i < dynamicReadId; i++) {
        dynamicReadStringIds[i] = null;
      }
      this.dynamicReadStringId = 0;
    }
  }

  public void resetWrite() {
    int dynamicWriteStringId = this.dynamicWriteStringId;
    if (dynamicWriteStringId != 0) {
      for (int i = 0; i < dynamicWriteStringId; i++) {
        dynamicWrittenString[i].dynamicWriteStringId =
            MetaStringBytes.DEFAULT_DYNAMIC_WRITE_STRING_ID;
        dynamicWrittenString[i] = null;
      }
      this.dynamicWriteStringId = 0;
    }
  }
}
