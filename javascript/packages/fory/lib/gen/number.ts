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
    (builder, accessor) => builder.writer.int8(accessor),
    builder => builder.reader.int8()
  )
);

CodegenRegistry.register(TypeId.INT16,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.int16(accessor),
    builder => builder.reader.int16()
  )
);

CodegenRegistry.register(TypeId.INT32,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.int32(accessor),
    builder => builder.reader.int32()
  )
);

CodegenRegistry.register(TypeId.VARINT32,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.varInt32(accessor),
    builder => builder.reader.varInt32()
  )
);

CodegenRegistry.register(TypeId.INT64,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.sliInt64(accessor),
    builder => builder.reader.sliInt64()
  )
);

CodegenRegistry.register(TypeId.TAGGED_INT64,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.sliInt64(accessor),
    builder => builder.reader.sliInt64()
  )
);
CodegenRegistry.register(TypeId.FLOAT16,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.float32(accessor),
    builder => builder.reader.float16()
  )
);
CodegenRegistry.register(TypeId.FLOAT32,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.float32(accessor),
    builder => builder.reader.float32()
  )
);
CodegenRegistry.register(TypeId.FLOAT64,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.float64(accessor),
    builder => builder.reader.float64()
  )
);

CodegenRegistry.register(TypeId.UINT8,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.uint8(accessor),
    builder => builder.reader.uint8()
  )
);

CodegenRegistry.register(TypeId.UINT16,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.uint16(accessor),
    builder => builder.reader.uint16()
  )
);

CodegenRegistry.register(TypeId.UINT32,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.uint32(accessor),
    builder => builder.reader.uint32()
  )
);

CodegenRegistry.register(TypeId.VAR_UINT32,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.varUInt32(accessor),
    builder => builder.reader.varUInt32()
  )
);

CodegenRegistry.register(TypeId.UINT64,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.uint64(accessor),
    builder => builder.reader.uint64()
  )
);

CodegenRegistry.register(TypeId.VAR_UINT64,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.varUInt64(accessor),
    builder => builder.reader.varUInt64()
  )
);

CodegenRegistry.register(TypeId.TAGGED_UINT64,
  buildNumberSerializer(
    (builder, accessor) => builder.writer.varUInt64(accessor),
    builder => builder.reader.varUInt64()
  )
);
