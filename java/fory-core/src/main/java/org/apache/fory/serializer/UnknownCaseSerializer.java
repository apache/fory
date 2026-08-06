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
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.type.Types;
import org.apache.fory.type.union.UnknownCase;

/** Runtime serializer for the payload body owned by {@link UnknownCase}. */
public final class UnknownCaseSerializer {
  private UnknownCaseSerializer() {}

  public static void writePayload(WriteContext writeContext, UnknownCase unknownCase) {
    Object value = unknownCase.value();
    int typeId = unknownCase.typeId();
    // Wire order is ref metadata first, then Any type metadata, then value bytes. The carrier keeps
    // only type id, so numeric replay writes NotNullValue and the original type id before the
    // specialized body. Scalar Any payloads are not ref-tracked.
    if (writeTypedPayload(writeContext, typeId, value)) {
      return;
    }
    if (writeContext.writeRefOrNull(value)) {
      return;
    }
    writeContext.writeNonRef(value);
  }

  public static UnknownCase readPayload(ReadContext readContext, int caseId) {
    int nextReadRefId = readContext.tryPreserveRefId();
    if (nextReadRefId >= Fory.NOT_NULL_VALUE_FLAG) {
      // UnknownCase owns only the union payload envelope. It does not create a separate
      // depth frame; nested payload serializers and the root-context reset own depth state.
      TypeInfo typeInfo = readContext.getTypeResolver().readTypeInfo(readContext);
      Object value = readContext.readNonRef(typeInfo);
      readContext.setReadRef(nextReadRefId, value);
      return UnknownCase.ofRuntime(caseId, typeInfo.getTypeId(), value);
    }
    return UnknownCase.ofRuntime(caseId, Types.UNKNOWN, readContext.getReadRef());
  }

  private static boolean writeTypedPayload(WriteContext writeContext, int typeId, Object value) {
    MemoryBuffer buffer = writeContext.getBuffer();
    switch (typeId) {
      case Types.BOOL:
        if (value instanceof Boolean) {
          Boolean typed = (Boolean) value;
          writeRefAndType(buffer, typeId);
          buffer.writeBoolean(typed);
          return true;
        }
        return false;
      case Types.INT8:
      case Types.UINT8:
        if (value instanceof Number) {
          Number typed = (Number) value;
          writeRefAndType(buffer, typeId);
          buffer.writeByte(typed.byteValue());
          return true;
        }
        return false;
      case Types.INT16:
      case Types.UINT16:
        if (value instanceof Number) {
          Number typed = (Number) value;
          writeRefAndType(buffer, typeId);
          buffer.writeInt16(typed.shortValue());
          return true;
        }
        return false;
      case Types.INT32:
      case Types.UINT32:
        if (value instanceof Number) {
          Number typed = (Number) value;
          writeRefAndType(buffer, typeId);
          buffer.writeInt32(typed.intValue());
          return true;
        }
        return false;
      case Types.VARINT32:
        if (value instanceof Number) {
          Number typed = (Number) value;
          writeRefAndType(buffer, typeId);
          buffer.writeVarInt32(typed.intValue());
          return true;
        }
        return false;
      case Types.VAR_UINT32:
        if (value instanceof Number) {
          Number typed = (Number) value;
          writeRefAndType(buffer, typeId);
          buffer.writeVarUInt32(typed.intValue());
          return true;
        }
        return false;
      case Types.INT64:
      case Types.UINT64:
        if (value instanceof Number) {
          Number typed = (Number) value;
          writeRefAndType(buffer, typeId);
          buffer.writeInt64(typed.longValue());
          return true;
        }
        return false;
      case Types.VARINT64:
        if (value instanceof Number) {
          Number typed = (Number) value;
          writeRefAndType(buffer, typeId);
          buffer.writeVarInt64(typed.longValue());
          return true;
        }
        return false;
      case Types.VAR_UINT64:
        if (value instanceof Number) {
          Number typed = (Number) value;
          writeRefAndType(buffer, typeId);
          buffer.writeVarUInt64(typed.longValue());
          return true;
        }
        return false;
      case Types.TAGGED_INT64:
        if (value instanceof Number) {
          Number typed = (Number) value;
          writeRefAndType(buffer, typeId);
          buffer.writeTaggedInt64(typed.longValue());
          return true;
        }
        return false;
      case Types.TAGGED_UINT64:
        if (value instanceof Number) {
          Number typed = (Number) value;
          writeRefAndType(buffer, typeId);
          buffer.writeTaggedUInt64(typed.longValue());
          return true;
        }
        return false;
      default:
        return false;
    }
  }

  private static void writeRefAndType(MemoryBuffer buffer, int typeId) {
    buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG);
    buffer.writeUInt8(typeId);
  }
}
