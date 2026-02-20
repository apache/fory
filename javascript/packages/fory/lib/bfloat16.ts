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

const float32View = new Float32Array(1);
const uint32View = new Uint32Array(float32View.buffer);

export class BFloat16 {
  private readonly _bits: number;

  constructor(bits: number) {
    this._bits = bits & 0xffff;
  }

  toBits(): number {
    return this._bits;
  }

  static fromBits(bits: number): BFloat16 {
    return new BFloat16(bits & 0xffff);
  }

  static fromFloat32(f32: number): BFloat16 {
    float32View[0] = f32;
    const bits = uint32View[0];
    const exponent = (bits >> 23) & 0xff;
    if (exponent === 255) {
      return BFloat16.fromBits((bits >> 16) & 0xffff);
    }
    const remainder = bits & 0x1ffff;
    let u = (bits + 0x8000) >> 16;
    if (remainder === 0x8000 && (u & 1) !== 0) {
      u--;
    }
    return BFloat16.fromBits(u & 0xffff);
  }

  toFloat32(): number {
    float32View[0] = 0;
    uint32View[0] = this._bits << 16;
    return float32View[0];
  }
}

export class BFloat16Array {
  private readonly _data: Uint16Array;

  constructor(length: number);
  constructor(source: Uint16Array | BFloat16[] | number[]);
  constructor(lengthOrSource: number | Uint16Array | BFloat16[] | number[]) {
    if (typeof lengthOrSource === "number") {
      this._data = new Uint16Array(lengthOrSource);
    } else if (lengthOrSource instanceof Uint16Array) {
      this._data = new Uint16Array(lengthOrSource.length);
      this._data.set(lengthOrSource);
    } else {
      const arr = lengthOrSource as (BFloat16 | number)[];
      this._data = new Uint16Array(arr.length);
      for (let i = 0; i < arr.length; i++) {
        const v = arr[i];
        this._data[i]
          = v instanceof BFloat16 ? v.toBits() : BFloat16.fromFloat32(v).toBits();
      }
    }
  }

  get length(): number {
    return this._data.length;
  }

  get(index: number): BFloat16 {
    return BFloat16.fromBits(this._data[index]);
  }

  set(index: number, value: BFloat16 | number): void {
    this._data[index]
      = value instanceof BFloat16 ? value.toBits() : BFloat16.fromFloat32(value).toBits();
  }

  get raw(): Uint16Array {
    return this._data;
  }

  static fromRaw(data: Uint16Array): BFloat16Array {
    const arr = new BFloat16Array(data.length);
    arr._data.set(data);
    return arr;
  }

  [Symbol.iterator](): IterableIterator<BFloat16> {
    let i = 0;
    const data = this._data;
    const len = data.length;
    return {
      next(): IteratorResult<BFloat16> {
        if (i < len) {
          return { value: BFloat16.fromBits(data[i++]), done: false };
        }
        return { value: undefined as unknown as BFloat16, done: true };
      },
      [Symbol.iterator]() {
        return this;
      },
    };
  }
}
