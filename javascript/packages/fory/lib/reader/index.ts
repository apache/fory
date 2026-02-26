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

import { LATIN1, UTF16, UTF8 } from "../type";
import { isNodeEnv } from "../util";
import { PlatformBuffer, alloc, fromUint8Array } from "../platformBuffer";
import { readLatin1String } from "./string";
import { BFloat16 } from "../bfloat16";

export class BinaryReader {
  private sliceStringEnable;
  private cursor = 0;
  private dataView!: DataView;
  private platformBuffer!: PlatformBuffer;
  private bigString = "";
  private byteLength = 0;
  private maxStringBytes?: number;

  constructor(config: {
    useSliceString?: boolean;
    maxStringBytes?: number;
  }) {
    this.sliceStringEnable = isNodeEnv && config.useSliceString;
    this.maxStringBytes = config.maxStringBytes;
  }

  reset(ab: Uint8Array) {
    this.platformBuffer = fromUint8Array(ab);
    this.byteLength = this.platformBuffer.byteLength;
    this.dataView = new DataView(this.platformBuffer.buffer, this.platformBuffer.byteOffset, this.byteLength);
    if (this.sliceStringEnable) {
      this.bigString = this.platformBuffer.toString("latin1", 0, this.byteLength);
    }
    this.cursor = 0;
  }

  readUint8() {
    return this.dataView.getUint8(this.cursor++);
  }

  readInt8() {
    return this.dataView.getInt8(this.cursor++);
  }

  readUint16() {
    const result = this.dataView.getUint16(this.cursor, true);
    this.cursor += 2;
    return result;
  }

  readInt16() {
    const result = this.dataView.getInt16(this.cursor, true);
    this.cursor += 2;
    return result;
  }

  readSkip(len: number) {
    this.cursor += len;
  }

  readInt32() {
    const result = this.dataView.getInt32(this.cursor, true);
    this.cursor += 4;
    return result;
  }

  readUint32() {
    const result = this.dataView.getUint32(this.cursor, true);
    this.cursor += 4;
    return result;
  }

  readInt64() {
    const result = this.dataView.getBigInt64(this.cursor, true);
    this.cursor += 8;
    return result;
  }

  readUint64() {
    const result = this.dataView.getBigUint64(this.cursor, true);
    this.cursor += 8;
    return result;
  }

  readSliInt64() {
    const i = this.dataView.getUint32(this.cursor, true);
    if ((i & 0b1) != 0b1) {
      this.cursor += 4;
      return BigInt(i >> 1);
    }
    this.cursor += 1;
    return this.readVarInt64();
  }

  /**
   * Read signed fory Tagged(Small Long as Int) encoded long.
   * If the first bit is 0, it's a 4-byte int shifted left by 1 bit.
   * If the first bit is 1, it's a 9-byte format with 0b1 flag + 8-byte long.
   */
  readTaggedInt64(): bigint {
    const readIdx = this.cursor;
    if (this.byteLength - readIdx < 4) {
      throw new Error("Insufficient bytes for tagged int64");
    }

    const i = this.dataView.getInt32(readIdx, true);
    if ((i & 0b1) !== 0b1) {
      // Small long encoded as int
      this.cursor = readIdx + 4;
      return BigInt(i >> 1);
    } else {
      // Big long encoded as 8 bytes
      if (this.byteLength - readIdx < 9) {
        throw new Error("Insufficient bytes for big tagged int64");
      }
      this.cursor = readIdx + 1; // Skip the flag byte
      return this.readInt64();
    }
  }

  /**
   * Read unsigned fory Tagged(Small Long as Int) encoded long.
   * If the first bit is 0, it's a 4-byte uint shifted left by 1 bit.
   * If the first bit is 1, it's a 9-byte format with 0b1 flag + 8-byte ulong.
   */
  readTaggedUInt64(): bigint {
    const readIdx = this.cursor;
    if (this.byteLength - readIdx < 4) {
      throw new Error("Insufficient bytes for tagged uint64");
    }

    const i = this.dataView.getUint32(readIdx, true);
    if ((i & 0b1) !== 0b1) {
      // Small ulong encoded as uint
      this.cursor = readIdx + 4;
      return BigInt(i >>> 1); // unsigned right shift
    } else {
      // Big ulong encoded as 8 bytes
      if (this.byteLength - readIdx < 9) {
        throw new Error("Insufficient bytes for big tagged uint64");
      }
      this.cursor = readIdx + 1; // Skip the flag byte
      return this.readUint64();
    }
  }

  readFloat32() {
    const result = this.dataView.getFloat32(this.cursor, true);
    this.cursor += 4;
    return result;
  }

  readFloat64() {
    const result = this.dataView.getFloat64(this.cursor, true);
    this.cursor += 8;
    return result;
  }

  stringUtf8At(start: number, len: number) {
    return this.platformBuffer.toString("utf8", start, start + len);
  }

  stringUtf8(len: number) {
    const result = this.platformBuffer.toString("utf8", this.cursor, this.cursor + len);
    this.cursor += len;
    return result;
  }

  stringUtf16LE(len: number) {
    const result = this.platformBuffer.toString("utf16le", this.cursor, this.cursor + len);
    this.cursor += len;
    return result;
  }

  stringWithHeader() {
    const header = this.readVarUint36Small();
    const type = header & 0b11;
    const len = header >>> 2;
    if (typeof this.maxStringBytes === "number" && this.maxStringBytes > 0 && len > this.maxStringBytes) {
      throw new Error(`String byte length ${len} exceeds configured maxStringBytes ${this.maxStringBytes}`);
    }
    switch (type) {
      case LATIN1:
        return this.stringLatin1(len);
      case UTF8:
        return this.stringUtf8(len);
      case UTF16:
        return this.stringUtf16LE(len);
      default:
        break;
    }
  }

  stringLatin1(len: number) {
    if (this.sliceStringEnable) {
      return this.stringLatin1Fast(len);
    }
    return this.stringLatin1Slow(len);
  }

  private stringLatin1Fast(len: number) {
    const result = this.bigString.substring(this.cursor, this.cursor + len);
    this.cursor += len;
    return result;
  }

  private stringLatin1Slow(len: number) {
    const rawCursor = this.cursor;
    this.cursor += len;
    return readLatin1String(this.platformBuffer, len, rawCursor);
  }

  buffer(len: number) {
    const result = alloc(len);
    this.platformBuffer.copy(result, 0, this.cursor, this.cursor + len);
    this.cursor += len;
    return result;
  }

  bufferRef(len: number) {
    const result = this.platformBuffer.subarray(this.cursor, this.cursor + len);
    this.cursor += len;
    return result;
  }

  readVarUInt32() {
    // Reduce memory reads as much as possible. Reading a uint32 at once is far faster than reading four uint8s separately.
    if (this.byteLength - this.cursor >= 5) {
      const fourByteValue = this.dataView.getUint32(this.cursor++, true);
      // | 1bit + 7bits | 1bit + 7bits | 1bit + 7bits | 1bit + 7bits |
      let result = fourByteValue & 0x7f;
      if ((fourByteValue & 0x80) != 0) {
        this.cursor++;
        // 0x3f80: 0b1111111 << 7
        result |= (fourByteValue >>> 1) & 0x3f80;
        if ((fourByteValue & 0x8000) != 0) {
          this.cursor++;
          // 0x1fc000: 0b1111111 << 14
          result |= (fourByteValue >>> 2) & 0x1fc000;
          if ((fourByteValue & 0x800000) != 0) {
            this.cursor++;
            // 0xfe00000: 0b1111111 << 21
            result |= (fourByteValue >>> 3) & 0xfe00000;
            if ((fourByteValue & 0x80000000) != 0) {
              result |= (this.readUint8()) << 28;
            }
          }
        }
      }
      return result;
    }
    let byte = this.readUint8();
    let result = byte & 0x7f;
    if ((byte & 0x80) != 0) {
      byte = this.readUint8();
      result |= (byte & 0x7f) << 7;
      if ((byte & 0x80) != 0) {
        byte = this.readUint8();
        result |= (byte & 0x7f) << 14;
        if ((byte & 0x80) != 0) {
          byte = this.readUint8();
          result |= (byte & 0x7f) << 21;
          if ((byte & 0x80) != 0) {
            byte = this.readUint8();
            result |= (byte) << 28;
          }
        }
      }
    }
    return result;
  }

  readVarUint32Small7(): number {
    const readIdx = this.cursor;
    if (this.byteLength - readIdx > 0) {
      const v = this.dataView.getUint8(readIdx);
      if ((v & 0x80) === 0) {
        this.cursor = readIdx + 1;
        return v;
      }
    }
    return this.readVarUint32Small14();
  }

  private readVarUint32Small14(): number {
    const readIdx = this.cursor;
    if (this.byteLength - readIdx >= 5) {
      const fourByteValue = this.dataView.getUint32(readIdx, true);
      this.cursor = readIdx + 1;
      let value = fourByteValue & 0x7F;
      if ((fourByteValue & 0x80) !== 0) {
        this.cursor++;
        value |= (fourByteValue >>> 1) & 0x3f80;
        if ((fourByteValue & 0x8000) !== 0) {
          return this.continueReadVarUint32(readIdx + 2, fourByteValue, value);
        }
      }
      return value;
    } else {
      return this.readVarUint36Slow();
    }
  }

  private continueReadVarUint32(readIdx: number, bulkRead: number, value: number): number {
    readIdx++;
    value |= (bulkRead >>> 2) & 0x1fc000;
    if ((bulkRead & 0x800000) !== 0) {
      readIdx++;
      value |= (bulkRead >>> 3) & 0xfe00000;
      if ((bulkRead & 0x80000000) !== 0) {
        value |= (this.dataView.getUint8(readIdx++) & 0x7F) << 28;
      }
    }
    this.cursor = readIdx;
    return value;
  }

  readVarUint36Small(): number {
    const readIdx = this.cursor;
    if (this.byteLength - readIdx >= 9) {
      const bulkValue = this.dataView.getBigUint64(readIdx, true);
      this.cursor = readIdx + 1;
      let result = Number(bulkValue & 0x7Fn);
      if ((bulkValue & 0x80n) !== 0n) {
        this.cursor++;
        result |= Number((bulkValue >> 1n) & 0x3f80n);
        if ((bulkValue & 0x8000n) !== 0n) {
          return this.continueReadVarInt36(readIdx + 2, bulkValue, result);
        }
      }
      return result;
    } else {
      return this.readVarUint36Slow();
    }
  }

  private continueReadVarInt36(readIdx: number, bulkValue: bigint, result: number): number {
    readIdx++;
    result |= Number((bulkValue >> 2n) & 0x1fc000n);
    if ((bulkValue & 0x800000n) !== 0n) {
      readIdx++;
      result |= Number((bulkValue >> 3n) & 0xfe00000n);
      if ((bulkValue & 0x80000000n) !== 0n) {
        readIdx++;
        result |= Number((bulkValue >> 4n) & 0xff0000000n);
      }
    }
    this.cursor = readIdx;
    return result;
  }

  private readVarUint36Slow(): number {
    let b = this.readUint8();
    let result = b & 0x7F;
    if ((b & 0x80) !== 0) {
      b = this.readUint8();
      result |= (b & 0x7F) << 7;
      if ((b & 0x80) !== 0) {
        b = this.readUint8();
        result |= (b & 0x7F) << 14;
        if ((b & 0x80) !== 0) {
          b = this.readUint8();
          result |= (b & 0x7F) << 21;
          if ((b & 0x80) !== 0) {
            b = this.readUint8();
            result |= (b & 0xFF) << 28;
          }
        }
      }
    }
    return result;
  }

  readVarInt32() {
    const v = this.readVarUInt32();
    return (v >>> 1) ^ -(v & 1); // zigZag decode
  }

  bigUInt8() {
    return BigInt(this.readUint8() >>> 0);
  }

  readVarUInt64() {
    // Creating BigInts is too performance-intensive; we'll use uint32 instead.
    if (this.byteLength - this.cursor < 8) {
      let byte = this.bigUInt8();
      let result = byte & 0x7fn;
      if ((byte & 0x80n) != 0n) {
        byte = this.bigUInt8();
        result |= (byte & 0x7fn) << 7n;
        if ((byte & 0x80n) != 0n) {
          byte = this.bigUInt8();
          result |= (byte & 0x7fn) << 14n;
          if ((byte & 0x80n) != 0n) {
            byte = this.bigUInt8();
            result |= (byte & 0x7fn) << 21n;
            if ((byte & 0x80n) != 0n) {
              byte = this.bigUInt8();
              result |= (byte & 0x7fn) << 28n;
              if ((byte & 0x80n) != 0n) {
                byte = this.bigUInt8();
                result |= (byte & 0x7fn) << 35n;
                if ((byte & 0x80n) != 0n) {
                  byte = this.bigUInt8();
                  result |= (byte & 0x7fn) << 42n;
                  if ((byte & 0x80n) != 0n) {
                    byte = this.bigUInt8();
                    result |= (byte & 0x7fn) << 49n;
                    if ((byte & 0x80n) != 0n) {
                      byte = this.bigUInt8();
                      result |= (byte) << 56n;
                    }
                  }
                }
              }
            }
          }
        }
      }
      return result;
    }
    const l32 = this.dataView.getUint32(this.cursor++, true);
    let byte = l32 & 0xff;
    let rl28 = byte & 0x7f;
    let rh28 = 0;
    if ((byte & 0x80) != 0) {
      byte = (l32 >>> 8) & 0xff;
      this.cursor++;
      rl28 |= (byte & 0x7f) << 7;
      if ((byte & 0x80) != 0) {
        byte = (l32 >>> 16) & 0xff;
        this.cursor++;
        rl28 |= (byte & 0x7f) << 14;
        if ((byte & 0x80) != 0) {
          byte = l32 >>> 24;
          this.cursor++;
          rl28 |= (byte & 0x7f) << 21;
          if ((byte & 0x80) != 0) {
            const h32 = this.dataView.getUint32(this.cursor++, true);
            byte = h32 & 0xff;
            rh28 |= (byte & 0x7f);
            if ((byte & 0x80) != 0) {
              byte = (h32 >>> 8) & 0xff;
              this.cursor++;
              rh28 |= (byte & 0x7f) << 7;
              if ((byte & 0x80) != 0) {
                byte = (h32 >>> 16) & 0xff;
                this.cursor++;
                rh28 |= (byte & 0x7f) << 14;
                if ((byte & 0x80) != 0) {
                  byte = h32 >>> 24;
                  this.cursor++;
                  rh28 |= (byte & 0x7f) << 21;
                  if ((byte & 0x80) != 0) {
                    return (BigInt(this.readUint8()) << 56n) | BigInt(rh28) << 28n | BigInt(rl28);
                  }
                }
              }
            }
          }
        }
      }
    }

    return BigInt(rh28) << 28n | BigInt(rl28);
  }

  readVarInt64() {
    const v = this.readVarUInt64();
    return (v >> 1n) ^ -(v & 1n); // zigZag decode
  }

  readFloat16() {
    const asUint16 = this.readUint16();
    const sign = asUint16 >> 15;
    const exponent = (asUint16 >> 10) & 0x1F;
    const mantissa = asUint16 & 0x3FF;

    // IEEE 754-2008
    if (exponent === 0) {
      if (mantissa === 0) {
        // +-0
        return sign === 0 ? 0 : -0;
      } else {
        // Denormalized number
        return (sign === 0 ? 1 : -1) * mantissa * 2 ** (1 - 15 - 10);
      }
    } else if (exponent === 31) {
      if (mantissa === 0) {
        // Infinity
        return sign === 0 ? Infinity : -Infinity;
      } else {
        // NaN
        return NaN;
      }
    } else {
      // Normalized number
      return (sign === 0 ? 1 : -1) * (1 + mantissa * 2 ** -10) * 2 ** (exponent - 15);
    }
  }

  readBfloat16(): BFloat16 {
    return BFloat16.fromBits(this.readUint16());
  }

  readGetCursor() {
    return this.cursor;
  }

  readSetCursor(v: number) {
    this.cursor = v;
  }
}
