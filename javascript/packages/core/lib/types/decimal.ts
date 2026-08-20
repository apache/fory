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

const DECIMAL_SMALL_MIN = -(1n << 62n);
const DECIMAL_SMALL_MAX = (1n << 62n) - 1n;
export const DECIMAL_MAX_MAGNITUDE_BYTES = 10_000;
export const DECIMAL_MAX_SCALE = 10_000;
// Compare against the exclusive bit-width bound before constructing magnitude bytes.
const DECIMAL_MAGNITUDE_LIMIT = 1n << BigInt(DECIMAL_MAX_MAGNITUDE_BYTES * 8);
const DECIMAL_NEGATIVE_MAGNITUDE_LIMIT = -DECIMAL_MAGNITUDE_LIMIT;
const HEX_BYTES = Array.from({ length: 256 }, (_, value) => value.toString(16).padStart(2, "0"));
const HEX_CHUNK_BYTES = 4096;

export class Decimal {
  readonly unscaledValue: bigint;
  readonly scale: number;

  constructor(unscaledValue: bigint | number | string, scale: number) {
    if (!Number.isInteger(scale)) {
      throw new Error(`Decimal scale must be an integer, got ${scale}`);
    }
    this.unscaledValue = BigInt(unscaledValue);
    this.scale = scale;
  }

  static from(unscaledValue: bigint | number | string, scale = 0): Decimal {
    return new Decimal(unscaledValue, scale);
  }

  equals(other: unknown): boolean {
    return (
      other instanceof Decimal &&
      other.scale === this.scale &&
      other.unscaledValue === this.unscaledValue
    );
  }

  toString(): string {
    return `${this.unscaledValue.toString()}e${-this.scale}`;
  }
}

export class DecimalCodec {
  static canUseSmallEncoding(value: bigint): boolean {
    return value >= DECIMAL_SMALL_MIN && value <= DECIMAL_SMALL_MAX;
  }

  static encodeZigZag64(value: bigint): bigint {
    return (value << 1n) ^ (value >> 63n);
  }

  static decodeZigZag64(value: bigint): bigint {
    return (value >> 1n) ^ -(value & 1n);
  }

  static toCanonicalLittleEndianMagnitude(value: bigint): Uint8Array {
    if (value <= DECIMAL_NEGATIVE_MAGNITUDE_LIMIT || value >= DECIMAL_MAGNITUDE_LIMIT) {
      throw new Error(`Decimal magnitude exceeds ${DECIMAL_MAX_MAGNITUDE_BYTES} bytes.`);
    }
    let magnitude = value < 0n ? -value : value;
    if (magnitude === 0n) {
      throw new Error("Zero must use the small decimal encoding.");
    }
    const bytes: number[] = [];
    while (magnitude !== 0n) {
      bytes.push(Number(magnitude & 0xffn));
      magnitude >>= 8n;
    }
    return Uint8Array.from(bytes);
  }

  static fromCanonicalLittleEndianMagnitude(bytes: Uint8Array): bigint {
    if (bytes.length === 0) {
      return 0n;
    }
    const chunks = new Array<string>(Math.ceil(bytes.length / HEX_CHUNK_BYTES));
    let chunkIndex = 0;
    for (let end = bytes.length; end > 0; end -= HEX_CHUNK_BYTES) {
      const start = Math.max(0, end - HEX_CHUNK_BYTES);
      const chunk = new Array<string>(end - start);
      for (let i = end - 1, j = 0; i >= start; i--, j++) {
        chunk[j] = HEX_BYTES[bytes[i]];
      }
      chunks[chunkIndex++] = chunk.join("");
    }
    return BigInt(`0x${chunks.join("")}`);
  }
}
