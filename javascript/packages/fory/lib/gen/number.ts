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

import { TypeInfo } from "../typeInfo";
import { CodecBuilder } from "./builder";
import { BaseSerializerGenerator } from "./serializer";
import { CodegenRegistry } from "./router";
import { TypeId } from "../type";
import { Scope } from "./scope";

function buildNumberSerializer(writeFun: (builder: CodecBuilder, accessor: string) => string, read: (builder: CodecBuilder) => string) {
  return class NumberSerializerGenerator extends BaseSerializerGenerator {
    typeInfo: TypeInfo;

    constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
      super(typeInfo, builder, scope);
      this.typeInfo = typeInfo;
    }

    write(accessor: string): string {
      return writeFun(this.builder, accessor);
    }

    read(accessor: (expr: string) => string): string {
      return accessor(read(this.builder));
    }

    getFixedSize(): number {
      return 11;
    }
  };
}

CodegenRegistry.register(TypeId.INT8,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeInt8(accessor),
    builder => builder.reader.readInt8()
  )
);

CodegenRegistry.register(TypeId.INT16,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeInt16(accessor),
    builder => builder.reader.readInt16()
  )
);

CodegenRegistry.register(TypeId.INT32,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeInt32(accessor),
    builder => builder.reader.readInt32()
  )
);

CodegenRegistry.register(TypeId.VARINT32,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeVarInt32(accessor),
    builder => builder.reader.readVarInt32()
  )
);

CodegenRegistry.register(TypeId.INT64,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeInt64(accessor),
    builder => builder.reader.readInt64()
  )
);

CodegenRegistry.register(TypeId.TAGGED_INT64,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeTaggedInt64(accessor),
    builder => builder.reader.readTaggedInt64()
  )
);

CodegenRegistry.register(TypeId.TAGGED_UINT64,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeTaggedUInt64(accessor),
    builder => builder.reader.readTaggedUInt64()
  )
);

CodegenRegistry.register(TypeId.FLOAT16,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeFloat16(accessor),
    builder => builder.reader.readFloat16()
  )
);
CodegenRegistry.register(TypeId.BFLOAT16,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeBfloat16(accessor),
    builder => builder.reader.readBfloat16()
  )
);
CodegenRegistry.register(TypeId.FLOAT32,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeFloat32(accessor),
    builder => builder.reader.readFloat32()
  )
);
CodegenRegistry.register(TypeId.FLOAT64,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeFloat64(accessor),
    builder => builder.reader.readFloat64()
  )
);

CodegenRegistry.register(TypeId.UINT8,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeUint8(accessor),
    builder => builder.reader.readUint8()
  )
);

CodegenRegistry.register(TypeId.UINT16,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeUint16(accessor),
    builder => builder.reader.readUint16()
  )
);

CodegenRegistry.register(TypeId.UINT32,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeUint32(accessor),
    builder => builder.reader.readUint32()
  )
);

CodegenRegistry.register(TypeId.VAR_UINT32,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeVarUInt32(accessor),
    builder => builder.reader.readVarUInt32()
  )
);

CodegenRegistry.register(TypeId.UINT64,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeUint64(accessor),
    builder => builder.reader.readUint64()
  )
);

CodegenRegistry.register(TypeId.VAR_UINT64,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeVarUInt64(accessor),
    builder => builder.reader.readVarUInt64()
  )
);

CodegenRegistry.register(TypeId.VARINT64,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.writeVarInt64(accessor),
    builder => builder.reader.readVarInt64()
  )
);
