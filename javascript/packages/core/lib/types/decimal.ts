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
    return other instanceof Decimal
      && other.scale === this.scale
      && other.unscaledValue === this.unscaledValue;
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

  static fromCanonicalLittleEndianMagnitude(payload: Uint8Array): bigint {
    let magnitude = 0n;
    for (let i = payload.length - 1; i >= 0; i--) {
      magnitude = (magnitude << 8n) | BigInt(payload[i]);
    }
    return magnitude;
  }
}
