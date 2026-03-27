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
import org.apache.fory.meta.EncodedMetaString;
import org.apache.fory.meta.Encoders;
import org.apache.fory.meta.MetaString;
import org.apache.fory.meta.MetaStringEncoder;
import org.apache.fory.util.MurmurHash3;

/**
 * A resolver for limited string value writing. Currently, we only support classname dynamic
 * writing. In the future, we may profile string field value dynamically and writing by this
 * resolver to reduce string cost. TODO add common inner package names and classnames here. TODO
 * share common immutable datastructure globally across multiple fory.
 */
public final class MetaStringResolver {
  private static final int initialCapacity = 4;
  // use a lower load factor to minimize hash collision
  private static final float foryMapLoadFactor = 0.5f;
  private static final int SMALL_STRING_THRESHOLD = 16;

  // Every deserialization for unregistered string will query it, performance is important.
  private final ObjectMap<EncodedMetaString, String> metaString2StringMap =
      new ObjectMap<>(initialCapacity, foryMapLoadFactor);
  private final LongMap<EncodedMetaString> hash2MetaStringMap =
      new LongMap<>(initialCapacity, foryMapLoadFactor);
  private final LongLongByteMap<EncodedMetaString> longLongMetaStringMap =
      new LongLongByteMap<>(initialCapacity, foryMapLoadFactor);
  private final ObjectMap<EncodedMetaString, MetaStringRef> metaString2RefMap =
      new ObjectMap<>(initialCapacity, foryMapLoadFactor);
  private final SharedRegistry sharedRegistry;
  private final MetaStringRef emptyMetaStringRef;
  private MetaStringRef[] dynamicWrittenString = new MetaStringRef[initialCapacity];
  private MetaStringRef[] dynamicReadStringIds = new MetaStringRef[initialCapacity];
  private short dynamicWriteStringId;
  private short dynamicReadStringId;

  public MetaStringResolver() {
    this(new SharedRegistry());
  }

  public MetaStringResolver(SharedRegistry sharedRegistry) {
    this.sharedRegistry = Objects.requireNonNull(sharedRegistry);
    emptyMetaStringRef = new MetaStringRef(EncodedMetaString.EMPTY);
    metaString2RefMap.put(EncodedMetaString.EMPTY, emptyMetaStringRef);
    metaString2StringMap.put(EncodedMetaString.EMPTY, "");
    dynamicWriteStringId = 0;
    dynamicReadStringId = 0;
  }

  public MetaStringRef getOrCreateGenericMetaStringBytes(String str) {
    return getOrCreateGenericMetaStringBytes(str, Encoders.computeGenericEncoding(str));
  }

  public MetaStringRef getOrCreateGenericMetaStringBytes(String str, MetaString.Encoding encoding) {
    return getOrCreateMetaStringBytes(
        str, Encoders.GENERIC_ENCODER, encoding, Encoders.GENERIC_ENCODER_TYPE_KEY);
  }

  public MetaStringRef getOrCreatePackageMetaStringBytes(String str) {
    return getOrCreateMetaStringBytes(
        str,
        Encoders.PACKAGE_ENCODER,
        Encoders.computePackageEncoding(str),
        Encoders.PACKAGE_ENCODER_TYPE_KEY);
  }

  public MetaStringRef getOrCreateTypeNameMetaStringBytes(String str) {
    return getOrCreateMetaStringBytes(
        str,
        Encoders.TYPE_NAME_ENCODER,
        Encoders.computeTypeNameEncoding(str),
        Encoders.TYPE_NAME_ENCODER_TYPE_KEY);
  }

  MetaStringRef getOrCreateMetaStringBytes(
      String str, MetaStringEncoder encoder, MetaString.Encoding encoding, String encoderTypeKey) {
    EncodedMetaString encodedMetaString =
        sharedRegistry.getOrCreateEncodedMetaString(str, encoder, encoding, encoderTypeKey);
    return getOrCreateMetaStringRef(encodedMetaString);
  }

  private MetaStringRef getOrCreateMetaStringRef(EncodedMetaString encodedMetaString) {
    MetaStringRef metaStringRef = metaString2RefMap.get(encodedMetaString);
    if (metaStringRef == null) {
      metaStringRef = new MetaStringRef(encodedMetaString);
      metaString2RefMap.put(encodedMetaString, metaStringRef);
    }
    return metaStringRef;
  }

  public void writeMetaStringBytesWithFlag(MemoryBuffer buffer, MetaStringRef metaStringRef) {
    Objects.requireNonNull(metaStringRef);
    short id = metaStringRef.dynamicWriteStringId;
    if (id == MetaStringRef.DEFAULT_DYNAMIC_WRITE_STRING_ID) {
      // noinspection Duplicates
      id = dynamicWriteStringId++;
      metaStringRef.dynamicWriteStringId = id;
      MetaStringRef[] dynamicWrittenMetaString = this.dynamicWrittenString;
      if (dynamicWrittenMetaString.length <= id) {
        dynamicWrittenMetaString = growWrite(id);
      }
      dynamicWrittenMetaString[id] = metaStringRef;
      EncodedMetaString encodedMetaString = metaStringRef.encoded;
      int length = encodedMetaString.bytes.length;
      // last bit `1` indicates class is written by name instead of registered id.
      buffer.writeVarUint32Small7(length << 2 | 0b1);
      if (length > SMALL_STRING_THRESHOLD) {
        buffer.writeInt64(encodedMetaString.hash);
      } else if (length != 0) {
        buffer.writeByte(encodedMetaString.encoding.getValue());
      }
      buffer.writeBytes(encodedMetaString.bytes);
    } else {
      // last bit `1` indicates class is written by name instead of registered id.
      buffer.writeVarUint32Small7(((id + 1) << 2) | 0b11);
    }
  }

  public void writeMetaStringBytes(MemoryBuffer buffer, MetaStringRef metaStringRef) {
    short id = metaStringRef.dynamicWriteStringId;
    if (id == MetaStringRef.DEFAULT_DYNAMIC_WRITE_STRING_ID) {
      // noinspection Duplicates
      id = dynamicWriteStringId++;
      metaStringRef.dynamicWriteStringId = id;
      MetaStringRef[] dynamicWrittenMetaString = this.dynamicWrittenString;
      if (dynamicWrittenMetaString.length <= id) {
        dynamicWrittenMetaString = growWrite(id);
      }
      dynamicWrittenMetaString[id] = metaStringRef;
      EncodedMetaString encodedMetaString = metaStringRef.encoded;
      int length = encodedMetaString.bytes.length;
      buffer.writeVarUint32Small7(length << 1);
      if (length > SMALL_STRING_THRESHOLD) {
        buffer.writeInt64(encodedMetaString.hash);
      } else if (length != 0) {
        buffer.writeByte(encodedMetaString.encoding.getValue());
      }
      buffer.writeBytes(encodedMetaString.bytes);
    } else {
      buffer.writeVarUint32Small7(((id + 1) << 1) | 1);
    }
  }

  private MetaStringRef[] growWrite(int id) {
    MetaStringRef[] tmp = new MetaStringRef[id * 2];
    System.arraycopy(dynamicWrittenString, 0, tmp, 0, dynamicWrittenString.length);
    return this.dynamicWrittenString = tmp;
  }

  public String readMetaString(MemoryBuffer buffer) {
    MetaStringRef metaStringRef = readMetaStringBytes(buffer);
    EncodedMetaString encodedMetaString = metaStringRef.encoded;
    String str = metaString2StringMap.get(encodedMetaString);
    if (str == null) {
      // TODO support meta string in other languages.
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
    } else {
      return dynamicReadStringIds[len - 1];
    }
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
    } else {
      return dynamicReadStringIds[len - 1];
    }
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
    } else {
      return dynamicReadStringIds[len - 1];
    }
  }

  MetaStringRef readMetaStringBytes(MemoryBuffer buffer, MetaStringRef cache) {
    int header = buffer.readVarUint32Small7();
    int len = header >>> 1;
    if ((header & 0b1) == 0) {
      MetaStringRef metaStringRef =
          len <= SMALL_STRING_THRESHOLD
              ? readSmallMetaStringBytes(buffer, cache, len)
              : readBigMetaStringBytes(buffer, cache, len);
      updateDynamicString(metaStringRef);
      return metaStringRef;
    } else {
      return dynamicReadStringIds[len - 1];
    }
  }

  private MetaStringRef readBigMetaStringBytes(MemoryBuffer buffer, MetaStringRef cache, int len) {
    long hashCode = buffer.readInt64();
    if (cache.encoded.hash == hashCode) {
      // skip byteString data
      buffer.increaseReaderIndex(len);
      return cache;
    } else {
      return readBigMetaStringBytes(buffer, len, hashCode);
    }
  }

  /** Read enum string by try to reuse previous read {@link MetaStringRef} object. */
  private MetaStringRef readBigMetaStringBytes(MemoryBuffer buffer, int len, long hashCode) {
    EncodedMetaString encodedMetaString = hash2MetaStringMap.get(hashCode);
    if (encodedMetaString == null) {
      EncodedMetaString newMetaString = new EncodedMetaString(buffer.readBytes(len), hashCode);
      MetaStringRef metaStringRef = getOrCreateMetaStringRef(newMetaString);
      hash2MetaStringMap.put(hashCode, metaStringRef.encoded);
      return metaStringRef;
    } else {
      // skip byteString data
      buffer.increaseReaderIndex(len);
      return getOrCreateMetaStringRef(encodedMetaString);
    }
  }

  private MetaStringRef readSmallMetaStringBytes(MemoryBuffer buffer, int len) {
    if (len == 0) {
      return emptyMetaStringRef;
    }
    byte encoding = buffer.readByte();
    long v1, v2 = 0;
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
    return getOrCreateMetaStringRef(encodedMetaString);
  }

  private MetaStringRef readSmallMetaStringBytes(
      MemoryBuffer buffer, MetaStringRef cache, int len) {
    if (len == 0) {
      return emptyMetaStringRef;
    }
    byte encoding = buffer.readByte();
    long v1, v2 = 0;
    if (len <= 8) {
      v1 = buffer.readBytesAsInt64(len);
    } else {
      v1 = buffer.readInt64();
      v2 = buffer.readBytesAsInt64(len - 8);
    }
    EncodedMetaString cachedMetaString = cache.encoded;
    if (cachedMetaString.first8Bytes == v1 && cachedMetaString.second8Bytes == v2) {
      return cache;
    }
    EncodedMetaString encodedMetaString = longLongMetaStringMap.get(v1, v2, encoding);
    if (encodedMetaString == null) {
      return createSmallMetaStringBytes(len, encoding, v1, v2);
    }
    return getOrCreateMetaStringRef(encodedMetaString);
  }

  private MetaStringRef createSmallMetaStringBytes(int len, byte encoding, long v1, long v2) {
    byte[] data = new byte[16];
    LittleEndian.putInt64(data, 0, v1);
    LittleEndian.putInt64(data, 8, v2);
    long hashCode = MurmurHash3.murmurhash3_x64_128(data, 0, len, 47)[0];
    hashCode = Math.abs(hashCode);
    hashCode = (hashCode & 0xffffffffffffff00L) | encoding;
    EncodedMetaString encodedMetaString = new EncodedMetaString(Arrays.copyOf(data, len), hashCode);
    MetaStringRef metaStringRef = getOrCreateMetaStringRef(encodedMetaString);
    longLongMetaStringMap.put(v1, v2, encoding, metaStringRef.encoded);
    return metaStringRef;
  }

  private void updateDynamicString(MetaStringRef metaStringRef) {
    short currentDynamicReadId = dynamicReadStringId++;
    MetaStringRef[] dynamicReadStringIds = this.dynamicReadStringIds;
    if (dynamicReadStringIds.length <= currentDynamicReadId) {
      dynamicReadStringIds = growRead(currentDynamicReadId);
    }
    dynamicReadStringIds[currentDynamicReadId] = metaStringRef;
  }

  private MetaStringRef[] growRead(int id) {
    MetaStringRef[] tmp = new MetaStringRef[id * 2];
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
            MetaStringRef.DEFAULT_DYNAMIC_WRITE_STRING_ID;
        dynamicWrittenString[i] = null;
      }
      this.dynamicWriteStringId = 0;
    }
  }
}
