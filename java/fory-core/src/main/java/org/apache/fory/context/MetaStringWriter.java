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

import java.util.Objects;
import org.apache.fory.annotation.Internal;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.EncodedMetaString;
import org.apache.fory.meta.Encoders;
import org.apache.fory.meta.MetaString;
import org.apache.fory.meta.MetaStringEncoder;
import org.apache.fory.resolver.MetaStringRef;
import org.apache.fory.resolver.SharedRegistry;

/** Write-side state for meta-string references. */
@Internal
public final class MetaStringWriter {
  private static final int INITIAL_CAPACITY = 2;
  private static final int SMALL_STRING_THRESHOLD = 16;

  private final SharedRegistry sharedRegistry;
  private final MetaStringRef emptyMetaStringRef;
  private MetaStringRef[] dynamicWrittenStrings = new MetaStringRef[INITIAL_CAPACITY];
  private short dynamicWriteStringId;

  public MetaStringWriter(SharedRegistry sharedRegistry) {
    this.sharedRegistry = Objects.requireNonNull(sharedRegistry);
    emptyMetaStringRef = new MetaStringRef(EncodedMetaString.EMPTY);
  }

  public MetaStringRef getOrCreateGenericMetaStringBytes(String str) {
    return getOrCreateGenericMetaStringBytes(str, Encoders.computeGenericEncoding(str));
  }

  public MetaStringRef getOrCreateGenericMetaStringBytes(
      String str, MetaString.Encoding encoding) {
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

  public MetaStringRef getOrCreateMetaStringBytes(
      String str, MetaStringEncoder encoder, MetaString.Encoding encoding, String encoderTypeKey) {
    return new MetaStringRef(
        sharedRegistry.getOrCreateEncodedMetaString(str, encoder, encoding, encoderTypeKey));
  }

  public MetaStringRef getEmptyMetaStringRef() {
    return emptyMetaStringRef;
  }

  public void writeMetaStringBytesWithFlag(MemoryBuffer buffer, MetaStringRef metaStringRef) {
    Objects.requireNonNull(metaStringRef);
    short id = metaStringRef.dynamicWriteStringId;
    if (id == MetaStringRef.DEFAULT_DYNAMIC_WRITE_STRING_ID) {
      id = dynamicWriteStringId;
      dynamicWriteStringId = (short) (id + 1);
      metaStringRef.dynamicWriteStringId = id;
      MetaStringRef[] writtenStrings = dynamicWrittenStrings;
      if (writtenStrings.length <= id) {
        writtenStrings = dynamicWrittenStrings = growWrite(writtenStrings, id);
      }
      writtenStrings[id] = metaStringRef;
      writeNewMetaStringBytesWithFlag(buffer, metaStringRef);
    } else {
      buffer.writeVarUint32Small7(((id + 1) << 2) | 0b11);
    }
  }

  public void writeMetaStringBytes(MemoryBuffer buffer, MetaStringRef metaStringRef) {
    Objects.requireNonNull(metaStringRef);
    short id = metaStringRef.dynamicWriteStringId;
    if (id == MetaStringRef.DEFAULT_DYNAMIC_WRITE_STRING_ID) {
      id = dynamicWriteStringId;
      dynamicWriteStringId = (short) (id + 1);
      metaStringRef.dynamicWriteStringId = id;
      MetaStringRef[] writtenStrings = dynamicWrittenStrings;
      if (writtenStrings.length <= id) {
        writtenStrings = dynamicWrittenStrings = growWrite(writtenStrings, id);
      }
      writtenStrings[id] = metaStringRef;
      writeNewMetaStringBytes(buffer, metaStringRef);
    } else {
      buffer.writeVarUint32Small7(((id + 1) << 1) | 1);
    }
  }

  public void reset() {
    int dynamicId = dynamicWriteStringId;
    if (dynamicId != 0) {
      for (int i = 0; i < dynamicId; i++) {
        MetaStringRef metaStringRef = dynamicWrittenStrings[i];
        if (metaStringRef != null) {
          metaStringRef.dynamicWriteStringId = MetaStringRef.DEFAULT_DYNAMIC_WRITE_STRING_ID;
          dynamicWrittenStrings[i] = null;
        }
      }
      dynamicWriteStringId = 0;
    }
  }

  private void writeNewMetaStringBytesWithFlag(MemoryBuffer buffer, MetaStringRef metaStringRef) {
    EncodedMetaString encodedMetaString = metaStringRef.getEncoded();
    int length = encodedMetaString.bytes.length;
    buffer.writeVarUint32Small7(length << 2 | 0b1);
    if (length > SMALL_STRING_THRESHOLD) {
      buffer.writeInt64(encodedMetaString.hash);
    } else if (length != 0) {
      buffer.writeByte(encodedMetaString.encoding.getValue());
    }
    buffer.writeBytes(encodedMetaString.bytes);
  }

  private void writeNewMetaStringBytes(MemoryBuffer buffer, MetaStringRef metaStringRef) {
    EncodedMetaString encodedMetaString = metaStringRef.getEncoded();
    int length = encodedMetaString.bytes.length;
    buffer.writeVarUint32Small7(length << 1);
    if (length > SMALL_STRING_THRESHOLD) {
      buffer.writeInt64(encodedMetaString.hash);
    } else if (length != 0) {
      buffer.writeByte(encodedMetaString.encoding.getValue());
    }
    buffer.writeBytes(encodedMetaString.bytes);
  }

  private static MetaStringRef[] growWrite(MetaStringRef[] current, int id) {
    int newLength = current.length;
    while (newLength <= id) {
      newLength <<= 1;
    }
    MetaStringRef[] expanded = new MetaStringRef[newLength];
    System.arraycopy(current, 0, expanded, 0, current.length);
    return expanded;
  }
}
