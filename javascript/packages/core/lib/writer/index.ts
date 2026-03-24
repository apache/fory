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

import { HalfMaxInt32, HalfMinInt32, Hps, LATIN1, UTF16, UTF8 } from "../type";
import { PlatformBuffer, alloc, strByteLength } from "../platformBuffer";
import { OwnershipError } from "../error";
import { toFloat16, toBFloat16 } from "./number";
import { BFloat16 } from "../bfloat16";

const MAX_POOL_SIZE = 1024 * 1024 * 3; // 3MB

function getInternalStringDetector() {
  if (!globalThis || !globalThis.require) {
    return null;
  }
  const { isStringOneByteRepresentation } = global.require("node:v8");
  return isStringOneByteRepresentation;
}

export class BinaryWriter {
  private cursor = 0;
  private byteLength = 0;
  private platformBuffer!: PlatformBuffer;
  private dataView!: DataView;
  private reserved = 0;
  private locked = false;
  private config: {
    hps?: Hps;
  };

  private hpsEnable = false;
  private internalStringDetector: (((content: string) => boolean) | null) = null;

  constructor(config: {
    hps?: Hps;
  } = {}) {
    this.initPoll();
    this.config = config;
    this.hpsEnable = Boolean(config?.hps);
    this.internalStringDetector = getInternalStringDetector();
  }

  private initPoll() {
    this.byteLength = 1024 * 100;
    this.platformBuffer = alloc(this.byteLength);
    this.dataView = new DataView(this.platformBuffer.buffer, this.platformBuffer.byteOffset);
  }

  reserve(len: number) {
    this.reserved += len;
    if (this.byteLength - this.cursor <= this.reserved) {
      const newAb = alloc(this.byteLength * 2 + len);
      this.platformBuffer.copy(newAb, 0);
      this.platformBuffer = newAb;
      this.byteLength = this.platformBuffer.byteLength;
      this.dataView = new DataView(this.platformBuffer.buffer, this.platformBuffer.byteOffset);
    }
  }

  reset() {
    if (this.locked) {
      throw new OwnershipError("Ownership of writer was held by dumpAndOwn, but not released");
    }
    this.cursor = 0;
    this.reserved = 0;
  }

  bool(bool: boolean) {
    this.dataView.setUint8(this.cursor, bool ? 1 : 0);
    this.cursor++;
  }

  writeUint8(v: number) {
    this.dataView.setUint8(this.cursor, v);
    this.cursor++;
  }

  writeInt8(v: number) {
    this.dataView.setInt8(this.cursor, v);
    this.cursor++;
  }

  writeInt24(v: number) {
    this.dataView.setUint32(this.cursor, v, true);
    this.cursor += 3;
  }

  writeUint16(v: number) {
    this.dataView.setUint16(this.cursor, v, true);
    this.cursor += 2;
  }

  writeInt16(v: number) {
    this.dataView.setInt16(this.cursor, v, true);
    this.cursor += 2;
  }

  writeSkip(len: number) {
    this.cursor += len;
  }

  writeInt32(v: number) {
    this.dataView.setInt32(this.cursor, v, true);
    this.cursor += 4;
  }

  writeUint32(v: number) {
    this.dataView.setUint32(this.cursor, v, true);
    this.cursor += 4;
  }

  writeInt64(v: bigint) {
    if (typeof v !== "bigint") {
      this.dataView.setBigInt64(this.cursor, BigInt(v), true);
    } else {
      this.dataView.setBigInt64(this.cursor, v, true);
    }
    this.cursor += 8;
  }

  writeSliInt64(v: bigint | number) {
    if (v <= HalfMaxInt32 && v >= HalfMinInt32) {
      // write:
      // 00xxx -> 0xxx
      // 11xxx -> 1xxx
      // read:
      // 0xxx -> 00xxx
      // 1xxx -> 11xxx
      this.dataView.setUint32(this.cursor, Number(v) << 1, true);
      this.cursor += 4;
    } else {
      const BIG_LONG_FLAG = 0b1; // bit 0 set, means big long.
      this.dataView.setUint8(this.cursor, BIG_LONG_FLAG);
      this.cursor += 1;
      this.writeVarInt64(BigInt(v));
    }
  }

  /**
   * Write signed long using fory Tagged(Small long as int) encoding.
   * If long is in [0xc0000000, 0x3fffffff], encode as 4 bytes int: | little-endian: ((int) value) << 1 |
   * Otherwise write as 9 bytes: | 0b1 | little-endian 8bytes long |
   */
  writeTaggedInt64(value: bigint | number): number {
    if (typeof value !== "bigint") {
      value = BigInt(value);
    }

    const halfMaxInt32 = 0x3fffffffn; // 0x3fffffff
    const halfMinInt32 = -0x40000000n; // 0xc0000000 as signed

    if (value >= halfMinInt32 && value <= halfMaxInt32) {
      // Small long encoded as int
      const v = Number(value) << 1; // bit 0 unset, means int
      this.dataView.setInt32(this.cursor, v, true);
      this.cursor += 4;
      return 4;
    } else {
      // Big long encoded as 8 bytes
      const BIG_LONG_FLAG = 0b1; // bit 0 set, means big long
      this.dataView.setUint8(this.cursor, BIG_LONG_FLAG);
      this.dataView.setBigInt64(this.cursor + 1, value, true);
      this.cursor += 9;
      return 9;
    }
  }

  /**
   * Write unsigned long using fory Tagged(Small long as int) encoding.
   * If long is in [0, 0x7fffffff], encode as 4 bytes int: | little-endian: ((int) value) << 1 |
   * Otherwise write as 9 bytes: | 0b1 | little-endian 8bytes long |
   */
  writeTaggedUInt64(value: bigint | number): number {
    if (typeof value !== "bigint") {
      value = BigInt(value);
    }

    const maxUInt32 = 0x7fffffffn; // 0x7fffffff

    if (value >= 0n && value <= maxUInt32) {
      // Small ulong encoded as uint
      const v = Number(value) << 1; // bit 0 unset, means int
      this.dataView.setUint32(this.cursor, v, true);
      this.cursor += 4;
      return 4;
    } else {
      // Big ulong encoded as 8 bytes
      const BIG_LONG_FLAG = 0b1; // bit 0 set, means big long
      this.dataView.setUint8(this.cursor, BIG_LONG_FLAG);
      this.dataView.setBigUint64(this.cursor + 1, value, true);
      this.cursor += 9;
      return 9;
    }
  }

  writeFloat32(v: number) {
    this.dataView.setFloat32(this.cursor, v, true);
    this.cursor += 4;
  }

  writeFloat64(v: number) {
    this.dataView.setFloat64(this.cursor, v, true);
    this.cursor += 8;
  }

  arrayBuffer(v: ArrayBuffer, byteOffset: number, byteLength: number) {
    this.reserve(byteLength);
    this.platformBuffer.set(new Uint8Array(v, byteOffset, byteLength), this.cursor);
    this.cursor += byteLength;
  }

  buffer(v: ArrayLike<number>) {
    this.reserve(v.length);
    this.platformBuffer.set(v, this.cursor);
    this.cursor += v.length;
  }

  writeUint64(v: bigint) {
    this.dataView.setBigUint64(this.cursor, v, true);
    this.cursor += 8;
  }

  bufferWithoutMemCheck(bf: PlatformBuffer, byteLen: number) {
    bf.copy(this.platformBuffer, this.cursor);
    this.cursor += byteLen;
  }

  fastWriteStringUtf8(string: string, buffer: Uint8Array, offset: number) {
    let c1: number;
    let c2: number;
    for (let i = 0; i < string.length; ++i) {
      c1 = string.charCodeAt(i);
      if (c1 < 128) {
        buffer[offset++] = c1;
      } else if (c1 < 2048) {
        const u1 = (c1 >> 6) | 192;
        const u2 = (c1 & 63) | 128;
        this.dataView.setUint16(offset, (u1 << 8) | u2);
        offset += 2;
      } else if (
        (c1 & 0xfc00) === 0xd800
        && ((c2 = string.charCodeAt(i + 1)) & 0xfc00) === 0xdc00
      ) {
        c1 = 0x10000 + ((c1 & 0x03ff) << 10) + (c2 & 0x03ff);
        ++i;
        const u1 = (c1 >> 18) | 240;
        const u2 = ((c1 >> 12) & 63) | 128;
        const u3 = ((c1 >> 6) & 63) | 128;
        const u4 = (c1 & 63) | 128;
        this.dataView.setUint32(offset, (u1 << 24) | (u2 << 16) | (u3 << 8) | u4);
        offset += 4;
      } else {
        const u1 = (c1 >> 12) | 224;
        const u2 = ((c1 >> 6) & 63) | 128;
        this.dataView.setUint16(offset, (u1 << 8) | u2);
        offset += 2;
        buffer[offset++] = (c1 & 63) | 128;
      }
    }
  }

  stringWithHeaderFast(v: string) {
    const { serializeString } = this.config.hps!;
    this.cursor = serializeString(v, this.platformBuffer, this.cursor);
  }

  //   const header = this.readVarUint36Small();
  // const type = header & 0b11;
  // const len = header >>> 2;

  stringWithHeaderWithDetector(v: string) {
    const isLatin1 = this.internalStringDetector!(v);
    if (isLatin1) {
      const len = v.length;
      this.writeVarUInt32((len << 2) | LATIN1);
      this.reserve(len);
      if (len < 40) {
        for (let index = 0; index < v.length; index++) {
          this.platformBuffer[this.cursor + index] = v.charCodeAt(index);
        }
      } else {
        this.platformBuffer.write(v, this.cursor, "latin1");
      }
      this.cursor += len;
    } else {
      const len = v.length * 2;
      this.writeVarUInt32((len << 2) | UTF16);
      this.reserve(len);
      this.platformBuffer.write(v, this.cursor, "utf16le");
      this.cursor += len;
    }
  }

  stringWithHeaderCompatibly(v: string) {
    const len = strByteLength(v);
    const isLatin1 = len === v.length;
    this.writeVarUInt32((len << 2) | (isLatin1 ? LATIN1 : UTF8));
    this.reserve(len);
    if (isLatin1) {
      if (len < 40) {
        for (let index = 0; index < v.length; index++) {
          this.platformBuffer[this.cursor + index] = v.charCodeAt(index);
        }
      } else {
        this.platformBuffer.write(v, this.cursor, "latin1");
      }
    } else {
      if (len < 40) {
        this.fastWriteStringUtf8(v, this.platformBuffer, this.cursor);
      } else {
        this.platformBuffer.write(v, this.cursor, "utf8");
      }
    }
    this.cursor += len;
  }

  writeVarInt32(v: number) {
    return this.writeVarUInt32((v << 1) ^ (v >> 31));
  }

  writeVarUInt32(value: number) {
    value = (value >>> 0) & 0xFFFFFFFF; // keep only the lower 32 bits

    if (value >> 7 == 0) {
      this.platformBuffer[this.cursor++] = value;
      return;
    }
    const rawCursor = this.cursor;
    let u32 = 0;
    if (value >> 14 == 0) {
      u32 = ((value & 0x7f | 0x80) << 24) | ((value >> 7) << 16);
      this.cursor += 2;
    } else if (value >> 21 == 0) {
      u32 = ((value & 0x7f | 0x80) << 24) | ((value >> 7 & 0x7f | 0x80) << 16) | ((value >> 14) << 8);
      this.cursor += 3;
    } else if (value >> 28 == 0) {
      u32 = ((value & 0x7f | 0x80) << 24) | ((value >> 7 & 0x7f | 0x80) << 16) | ((value >> 14 & 0x7f | 0x80) << 8) | (value >> 21);
      this.cursor += 4;
    } else {
      u32 = ((value & 0x7f | 0x80) << 24) | ((value >> 7 & 0x7f | 0x80) << 16) | ((value >> 14 & 0x7f | 0x80) << 8) | (value >> 21 & 0x7f | 0x80);
      this.platformBuffer[rawCursor + 4] = value >> 28;
      this.cursor += 5;
    }
    this.dataView.setUint32(rawCursor, u32);
  }

  writeVarUint32Small7(value: number) {
    if (value >>> 7 === 0) {
      this.platformBuffer[this.cursor++] = value;
      return;
    }
    this.cursor += this.continueWriteVarUint32Small7(value);
  }

  private continueWriteVarUint32Small7(value: number) {
    let encoded = (value & 0x7F);
    encoded |= (((value & 0x3f80) << 1) | 0x80);
    const writerIdx = this.cursor;
    if (value >>> 14 === 0) {
      this.dataView.setUint32(writerIdx, encoded, true);
      return 2;
    }
    return this.continuePutVarInt36(writerIdx, encoded, value);
  }

  private continuePutVarInt36(index: number, encoded: number, value: number): number {
    // 0x1fc000: 0b1111111 << 14
    encoded |= (((value & 0x1fc000) << 2) | 0x8000);
    if (value >>> 21 === 0) {
      this.dataView.setUint32(index, encoded, true);
      return 3;
    }
    // 0xfe00000: 0b1111111 << 21
    encoded |= ((value & 0xfe00000) << 3) | 0x800000;
    if (value >>> 28 === 0) {
      this.dataView.setUint32(index, encoded, true);
      return 4;
    }
    // 5-byte case: bits 28-31 go to the 5th byte
    const encodedLong = (encoded >>> 0) | 0x80000000;
    const highByte = value >>> 28;
    this.dataView.setUint32(index, encodedLong, true);
    this.dataView.setUint8(index + 4, highByte);
    return 5;
  }

  writeVarInt64(v: bigint) {
    if (typeof v !== "bigint") {
      v = BigInt(v);
    }
    return this.writeVarUInt64((v << 1n) ^ (v >> 63n));
  }

  writeVarUInt64(val: bigint | number) {
    if (typeof val !== "bigint") {
      val = BigInt(val);
    }
    val = val & 0xFFFFFFFFFFFFFFFFn; // keep only the lower 64 bits

    // Match Java's 1-9 byte varuint64 encoding:
    // - 7 bits per byte for the first 8 bytes
    // - the 9th byte (if present) uses full 8 bits, allowing values with the 64th bit set
    for (let i = 0; i < 8; i++) {
      if ((val >> 7n) === 0n) {
        this.platformBuffer[this.cursor++] = Number(val);
        return;
      }
      this.platformBuffer[this.cursor++] = Number((val & 127n) | 128n);
      val >>= 7n;
    }
    this.platformBuffer[this.cursor++] = Number(val & 255n);
  }

  tryFreePool() {
    if (this.byteLength > MAX_POOL_SIZE) {
      this.initPoll();
    }
  }

  dump() {
    const result = alloc(this.cursor);
    this.platformBuffer.copy(result, 0, 0, this.cursor);
    this.tryFreePool();
    return result;
  }

  dumpAndOwn() {
    this.locked = true;
    return {
      get: () => {
        return this.platformBuffer.subarray(0, this.cursor);
      },
      dispose: () => {
        this.locked = false;
      },
    };
  }

  writeFloat16(value: number) {
    this.writeUint16(toFloat16(value));
  }

  writeBfloat16(value: BFloat16 | number) {
    const bits
      = value instanceof BFloat16 ? value.toBits() : toBFloat16(value);
    this.writeUint16(bits);
  }

  writeGetCursor() {
    return this.cursor;
  }

  setUint32Position(offset: number, v: number) {
    this.dataView.setUint32(offset, v, true);
  }

  setUint8Position(offset: number, v: number) {
    this.dataView.setUint8(offset, v);
  }

  setUint16Position(offset: number, v: number) {
    this.dataView.setUint16(offset, v, true);
  }

  getByteLen() {
    return this.byteLength;
  }

  getReserved() {
    return this.reserved;
  }

  stringWithHeader(v: string) {
    if (this.hpsEnable) {
      return this.stringWithHeaderFast(v);
    }
    if (this.internalStringDetector !== null) {
      return this.stringWithHeaderWithDetector(v);
    }
    return this.stringWithHeaderCompatibly(v);
  }
}
