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

package org.apache.fory.serializer;

import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.RefMode;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;
import org.apache.fory.type.DispatchId;

/**
 * Utility class for skipping field values in the buffer when a field doesn't exist in the current
 * class. This is used for schema compatibility when deserializing data from peers with different
 * class definitions.
 */
public class FieldSkipper {

  /**
   * Skip a field value in the buffer. Handles all dispatch IDs including basic types and complex
   * types. Whether to read a null flag is determined by fieldInfo.refMode.
   *
   * @param fory fory runtime
   * @param typeResolver resolver used for type metadata read
   * @param refResolver resolver used for reference tracking
   * @param fieldInfo the field metadata
   * @param buffer the buffer to skip from
   */
  static void skipField(
      Fory fory,
      TypeResolver typeResolver,
      RefResolver refResolver,
      SerializationFieldInfo fieldInfo,
      MemoryBuffer buffer) {
    int dispatchId = fieldInfo.dispatchId;
    RefMode refMode = fieldInfo.refMode;

    // For non-basic types, fall back to binding.readField
    if (!DispatchId.isBasicType(dispatchId)) {
      AbstractObjectSerializer.readField(fory, typeResolver, refResolver, fieldInfo, buffer);
      return;
    }

    // For nullable basic types, check null flag first
    if (refMode != RefMode.NONE) {
      if (buffer.readByte() == Fory.NULL_FLAG) {
        return; // Field is null, nothing more to skip
      }
    }

    // Skip the actual value bytes based on dispatch ID
    switch (dispatchId) {
      case DispatchId.BOOL:
      case DispatchId.INT8:
      case DispatchId.UINT8:
      case DispatchId.EXT_UINT8:
        buffer.increaseReaderIndex(1);
        break;
      case DispatchId.CHAR:
      case DispatchId.INT16:
      case DispatchId.UINT16:
      case DispatchId.EXT_UINT16:
        buffer.increaseReaderIndex(2);
        break;
      case DispatchId.INT32:
      case DispatchId.UINT32:
      case DispatchId.EXT_UINT32:
      case DispatchId.FLOAT32:
        buffer.increaseReaderIndex(4);
        break;
      case DispatchId.INT64:
      case DispatchId.UINT64:
      case DispatchId.EXT_UINT64:
      case DispatchId.FLOAT64:
        buffer.increaseReaderIndex(8);
        break;
      case DispatchId.VARINT32:
        buffer.readVarInt32();
        break;
      case DispatchId.VAR_UINT32:
        buffer.readVarUint32();
        break;
      case DispatchId.EXT_VAR_UINT32:
        buffer.readVarUint32();
        break;
      case DispatchId.VARINT64:
        buffer.readVarInt64();
        break;
      case DispatchId.VAR_UINT64:
        buffer.readVarUint64();
        break;
      case DispatchId.EXT_VAR_UINT64:
        buffer.readVarUint64();
        break;
      case DispatchId.TAGGED_INT64:
        buffer.readTaggedInt64();
        break;
      case DispatchId.TAGGED_UINT64:
        buffer.readTaggedUint64();
        break;
      case DispatchId.STRING:
        fory.readJavaString(buffer);
        break;
      default:
        throw new IllegalStateException("Unexpected basic dispatchId: " + dispatchId);
    }
  }
}
