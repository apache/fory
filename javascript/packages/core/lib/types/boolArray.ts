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

export class BoolArray {
  static readonly BYTES_PER_ELEMENT = 1;
  readonly BYTES_PER_ELEMENT = 1;
  private _data: Uint8Array | null;
  private _length: number;
  private _packedBits: number;

  constructor(length: number);
  constructor(source: BoolArray | Uint8Array | ArrayLike<boolean>);
  constructor(lengthOrSource: number | BoolArray | Uint8Array | ArrayLike<boolean>) {
    this._packedBits = 0;
    if (typeof lengthOrSource === "number") {
      this._data = new Uint8Array(lengthOrSource);
      this._length = lengthOrSource;
    } else if (lengthOrSource instanceof BoolArray) {
      this._data = new Uint8Array(lengthOrSource.raw);
      this._length = this._data.length;
    } else if (lengthOrSource instanceof Uint8Array) {
      this._data = new Uint8Array(lengthOrSource);
      this._length = this._data.length;
    } else {
      this._data = new Uint8Array(lengthOrSource.length);
      this._length = lengthOrSource.length;
      for (let i = 0; i < lengthOrSource.length; i++) {
        this._data[i] = lengthOrSource[i] ? 1 : 0;
      }
    }
  }

  get length(): number {
    return this._length;
  }

  get byteLength(): number {
    return this._length;
  }

  get byteOffset(): number {
    return this._data === null ? 0 : this._data.byteOffset;
  }

  get buffer(): ArrayBufferLike {
    return this.raw.buffer;
  }

  get raw(): Uint8Array {
    let data = this._data;
    if (data !== null) {
      return data;
    }
    data = new Uint8Array(this._length);
    let bits = this._packedBits;
    for (let i = 0; i < this._length; i++) {
      data[i] = bits & 0xff;
      bits >>>= 8;
    }
    this._data = data;
    this._packedBits = 0;
    return data;
  }

  get(index: number): boolean {
    const data = this._data;
    if (data !== null) {
      return data[index] !== 0;
    }
    return ((this._packedBits >>> (index * 8)) & 0xff) !== 0;
  }

  at(index: number): boolean | undefined {
    const normalized = index < 0 ? this.length + index : index;
    if (normalized < 0 || normalized >= this.length) {
      return undefined;
    }
    return this.get(normalized);
  }

  set(values: BoolArray | ArrayLike<boolean>, offset = 0): void {
    const data = this.raw;
    if (values instanceof BoolArray) {
      data.set(values.raw, offset);
      return;
    }
    for (let i = 0; i < values.length; i++) {
      data[offset + i] = values[i] ? 1 : 0;
    }
  }

  setValue(index: number, value: boolean): void {
    this.raw[index] = value ? 1 : 0;
  }

  fill(value: boolean, start?: number, end?: number): this {
    this.raw.fill(value ? 1 : 0, start, end);
    return this;
  }

  slice(start?: number, end?: number): BoolArray {
    return BoolArray.fromRaw(this.raw.slice(start, end));
  }

  subarray(begin?: number, end?: number): BoolArray {
    return BoolArray.fromRaw(this.raw.subarray(begin, end));
  }

  toArray(): boolean[] {
    return Array.from(this);
  }

  static fromRaw(data: Uint8Array): BoolArray {
    const array = Object.create(BoolArray.prototype) as BoolArray;
    array._data = data;
    array._length = data.length;
    array._packedBits = 0;
    return array;
  }

  static fromPackedBytes(bits: number, length: number): BoolArray {
    const array = Object.create(BoolArray.prototype) as BoolArray;
    array._data = null;
    array._length = length;
    array._packedBits = bits;
    return array;
  }

  [Symbol.iterator](): IterableIterator<boolean> {
    let i = 0;
    const data = this._data;
    const len = this._length;
    const bits = this._packedBits;
    return {
      next(): IteratorResult<boolean> {
        if (i < len) {
          const value = data === null ? ((bits >>> (i++ * 8)) & 0xff) !== 0 : data[i++] !== 0;
          return { value, done: false };
        }
        return { value: undefined as unknown as boolean, done: true };
      },
      [Symbol.iterator]() {
        return this;
      },
    };
  }
}
