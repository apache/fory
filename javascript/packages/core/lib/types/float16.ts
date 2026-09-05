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

const float64View = new DataView(new ArrayBuffer(8));

export function toFloat16Bits(value: number) {
  // Round directly from binary64: narrowing to binary32 first can move a value
  // onto a binary16 midpoint and change the ties-to-even result.
  float64View.setFloat64(0, value);
  const high = float64View.getUint32(0);
  const sign = (high >>> 16) & 0x8000;
  const exponent = ((high >>> 20) & 0x7ff) - 1023;
  let significand = high & 0xfffff;

  if (exponent === 1024) {
    return sign | 0x7c00 | (significand !== 0 || float64View.getUint32(4) !== 0 ? 0x0200 : 0);
  }

  if (exponent > 15) {
    return sign | 0x7c00;
  }

  if (exponent < -25) {
    // Below half the smallest subnormal, round to signed zero before the
    // shift count can reach 32 and wrap in JavaScript.
    return sign;
  }

  let shift = 10;
  let bits = (exponent + 15) << 10;
  if (exponent < -14) {
    shift = -exponent - 4;
    significand |= 0x100000;
    bits = 0;
  }
  bits |= significand >>> shift;
  const remainder = significand & ((1 << shift) - 1);
  const halfway = 1 << (shift - 1);
  // The low binary64 word distinguishes an exact tie from a value above it.
  // Increment the complete encoding so rounding can carry into the exponent.
  if (
    remainder > halfway ||
    (remainder === halfway && (float64View.getUint32(4) !== 0 || (bits & 1) !== 0))
  ) {
    bits++;
  }

  return sign | bits;
}

export function fromFloat16Bits(bits: number): number {
  const sign = bits >> 15;
  const exponent = (bits >> 10) & 0x1f;
  const mantissa = bits & 0x3ff;

  if (exponent === 0) {
    if (mantissa === 0) {
      return sign === 0 ? 0 : -0;
    }
    return (sign === 0 ? 1 : -1) * mantissa * 2 ** (1 - 15 - 10);
  }

  if (exponent === 31) {
    return mantissa === 0 ? (sign === 0 ? Infinity : -Infinity) : NaN;
  }

  return (sign === 0 ? 1 : -1) * (1 + mantissa * 2 ** -10) * 2 ** (exponent - 15);
}

type Float16Source = ForyFloat16Array | Uint16Array | ArrayLike<number>;

export class ForyFloat16Array {
  static readonly BYTES_PER_ELEMENT = 2;
  readonly BYTES_PER_ELEMENT = 2;
  private _data: Uint16Array;

  constructor(length: number);
  constructor(source: Float16Source);
  constructor(lengthOrSource: number | Float16Source) {
    if (typeof lengthOrSource === "number") {
      this._data = new Uint16Array(lengthOrSource);
    } else if (lengthOrSource instanceof ForyFloat16Array) {
      this._data = new Uint16Array(lengthOrSource.raw);
    } else if (lengthOrSource instanceof Uint16Array) {
      this._data = new Uint16Array(lengthOrSource);
    } else {
      this._data = new Uint16Array(lengthOrSource.length);
      for (let i = 0; i < lengthOrSource.length; i++) {
        this._data[i] = toFloat16Bits(lengthOrSource[i]);
      }
    }
  }

  get length(): number {
    return this._data.length;
  }

  get byteLength(): number {
    return this._data.byteLength;
  }

  get byteOffset(): number {
    return this._data.byteOffset;
  }

  get buffer(): ArrayBufferLike {
    return this._data.buffer;
  }

  get raw(): Uint16Array {
    return this._data;
  }

  get(index: number): number {
    return fromFloat16Bits(this._data[index]);
  }

  at(index: number): number | undefined {
    const normalized = index < 0 ? this.length + index : index;
    if (normalized < 0 || normalized >= this.length) {
      return undefined;
    }
    return this.get(normalized);
  }

  set(values: ForyFloat16Array | ArrayLike<number>, offset = 0): void {
    if (values instanceof ForyFloat16Array) {
      this._data.set(values.raw, offset);
      return;
    }
    for (let i = 0; i < values.length; i++) {
      this._data[offset + i] = toFloat16Bits(values[i]);
    }
  }

  setValue(index: number, value: number): void {
    this._data[index] = toFloat16Bits(value);
  }

  fill(value: number, start?: number, end?: number): this {
    this._data.fill(toFloat16Bits(value), start, end);
    return this;
  }

  slice(start?: number, end?: number): ForyFloat16Array {
    return ForyFloat16Array.fromRaw(this._data.slice(start, end));
  }

  subarray(begin?: number, end?: number): ForyFloat16Array {
    return ForyFloat16Array.fromRaw(this._data.subarray(begin, end));
  }

  toArray(): number[] {
    return Array.from(this);
  }

  static fromRaw(data: Uint16Array): ForyFloat16Array {
    const array = Object.create(ForyFloat16Array.prototype) as ForyFloat16Array;
    array._data = data;
    return array;
  }

  [Symbol.iterator](): IterableIterator<number> {
    let i = 0;
    const data = this._data;
    const len = data.length;
    return {
      next(): IteratorResult<number> {
        if (i < len) {
          return { value: fromFloat16Bits(data[i++]), done: false };
        }
        return { value: undefined as unknown as number, done: true };
      },
      [Symbol.iterator]() {
        return this;
      },
    };
  }
}

type NativeFloat16ArrayConstructor = {
  new (length: number): ArrayLike<number>;
  new (source: ArrayLike<number>): ArrayLike<number>;
  readonly BYTES_PER_ELEMENT: number;
};

const NativeFloat16Array = (
  globalThis as unknown as { Float16Array?: NativeFloat16ArrayConstructor }
).Float16Array;

export type Float16Array = ForyFloat16Array | ArrayLike<number>;

export const Float16Array = (NativeFloat16Array ?? ForyFloat16Array) as
  NativeFloat16ArrayConstructor | typeof ForyFloat16Array;

export function isFloat16Array(value: unknown): boolean {
  return (
    value instanceof ForyFloat16Array ||
    (NativeFloat16Array !== undefined && value instanceof NativeFloat16Array)
  );
}

export function getFloat16Raw(value: unknown): Uint16Array | null {
  return value instanceof ForyFloat16Array ? value.raw : null;
}

export function createFloat16ArrayFromRaw(raw: Uint16Array): Float16Array {
  if (NativeFloat16Array !== undefined) {
    const result = new NativeFloat16Array(raw.length) as { [index: number]: number };
    for (let i = 0; i < raw.length; i++) {
      result[i] = fromFloat16Bits(raw[i]);
    }
    return result as Float16Array;
  }
  return ForyFloat16Array.fromRaw(raw);
}

export function createFloat16Array(source: ArrayLike<number>): Float16Array {
  return new Float16Array(source) as Float16Array;
}
