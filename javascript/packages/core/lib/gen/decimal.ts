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
import { Decimal, DecimalCodec } from "../types/decimal";

class DecimalSerializerGenerator extends BaseSerializerGenerator {
  typeInfo: TypeInfo;

  constructor(typeInfo: TypeInfo, builder: CodecBuilder, scope: Scope) {
    super(typeInfo, builder, scope);
    this.typeInfo = typeInfo;
  }

  write(accessor: string): string {
    const codec = this.builder.getExternal(DecimalCodec.name);
    const scale = this.scope.uniqueName("decimal_scale");
    const unscaled = this.scope.uniqueName("decimal_unscaled");
    const payload = this.scope.uniqueName("decimal_payload");
    const meta = this.scope.uniqueName("decimal_meta");
    return `
      const ${scale} = ${accessor}.scale;
      const ${unscaled} = ${accessor}.unscaledValue;
      ${this.builder.writer.writeVarInt32(scale)}
      if (${codec}.canUseSmallEncoding(${unscaled})) {
        ${this.builder.writer.writeVarUInt64(`(${codec}.encodeZigZag64(${unscaled}) << 1n)`)}
        return;
      }
      const ${payload} = ${codec}.toCanonicalLittleEndianMagnitude(${unscaled});
      const ${meta} = (BigInt(${payload}.length) << 1n) | (${unscaled} < 0n ? 1n : 0n);
      ${this.builder.writer.writeVarUInt64(`((${meta} << 1n) | 1n)`)}
      ${this.builder.writer.buffer(payload)}
    `;
  }

  read(accessor: (expr: string) => string): string {
    const codec = this.builder.getExternal(DecimalCodec.name);
    const decimal = this.builder.getExternal(Decimal.name);
    const scale = this.scope.uniqueName("decimal_scale");
    const header = this.scope.uniqueName("decimal_header");
    const meta = this.scope.uniqueName("decimal_meta");
    const length = this.scope.uniqueName("decimal_length");
    const payload = this.scope.uniqueName("decimal_payload");
    const magnitude = this.scope.uniqueName("decimal_magnitude");
    const unscaled = this.scope.uniqueName("decimal_unscaled");
    return `
      const ${scale} = ${this.builder.reader.readVarInt32()};
      const ${header} = ${this.builder.reader.readVarUInt64()};
      if ((${header} & 1n) === 0n) {
        ${accessor(`new ${decimal}(${codec}.decodeZigZag64(${header} >> 1n), ${scale})`)}
        return;
      }
      const ${meta} = ${header} >> 1n;
      const ${length} = Number(${meta} >> 1n);
      if (${length} <= 0 || ${length} > 0x7fffffff) {
        throw new Error(\`Invalid decimal magnitude length \${${length}}.\`);
      }
      const ${payload} = ${this.builder.reader.buffer(length)};
      if (${payload}[${length} - 1] === 0) {
        throw new Error("Non-canonical decimal payload: trailing zero byte.");
      }
      const ${magnitude} = ${codec}.fromCanonicalLittleEndianMagnitude(${payload});
      if (${magnitude} === 0n) {
        throw new Error("Big decimal encoding must not represent zero.");
      }
      const ${unscaled} = ((${meta} & 1n) === 0n) ? ${magnitude} : -${magnitude};
      ${accessor(`new ${decimal}(${unscaled}, ${scale})`)}
    `;
  }

  getFixedSize(): number {
    return 20;
  }
}

CodegenRegistry.register(TypeId.DECIMAL, DecimalSerializerGenerator);
CodegenRegistry.registerExternal(Decimal);
CodegenRegistry.registerExternal(DecimalCodec);
