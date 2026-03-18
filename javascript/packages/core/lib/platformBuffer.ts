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

import { hasBuffer } from "./util";

const utf8Encoder = new TextEncoder();
const utf8Decoder = new TextDecoder("utf-8");
const utf16LEDecoder = new TextDecoder("utf-16le");

export type SupportedEncodings = "latin1" | "utf8" | "utf16le";

export interface PlatformBuffer extends Uint8Array {
  toString(encoding?: SupportedEncodings, start?: number, end?: number): string;
  write(string: string, offset: number, encoding?: SupportedEncodings): void;
  copy(target: Uint8Array, targetStart?: number, sourceStart?: number, sourceEnd?: number): void;
}

export class BrowserBuffer extends Uint8Array implements PlatformBuffer {
  write(string: string, offset: number, encoding: SupportedEncodings = "utf8"): void {
    switch (encoding) {
      case "utf8":
        return this.utf8Write(string, offset);
      case "utf16le":
        return this.ucs2Write(string, offset);
      case "latin1":
        return this.latin1Write(string, offset);
      default:
        break;
    }
  }

  private ucs2Write(string: string, offset: number): void {
    for (let i = 0; i < string.length; i++) {
      const codePoint = string.charCodeAt(i);
      this[offset++] = codePoint & 0xFF;
      this[offset++] = (codePoint >> 8) & 0xFF;
    }
  }

  toString(encoding: SupportedEncodings = "utf8", start = 0, end = this.length): string {
    switch (encoding) {
      case "utf8":
        return this.utf8Slice(start, end);
      case "utf16le":
        return this.utf16LESlice(start, end);
      case "latin1":
        return this.latin1Slice(start, end);
      default:
        return "";
    }
  }

  static alloc(size: number) {
    return new BrowserBuffer(new Uint8Array(size));
  }

  private latin1Write(string: string, offset: number) {
    let index = 0;
    for (; index < string.length; index++) {
      this[offset++] = string.charCodeAt(index);
    }
  }

  private utf8Write(string: string, offset: number) {
    utf8Encoder.encodeInto(string, this.subarray(offset));
  }

  private latin1Slice(start: number, end: number) {
    if (end - start < 1) {
      return "";
    }
    let str = "";
    for (let i = start; i < end;) {
      str += String.fromCharCode(this[i++]);
    }
    return str;
  }

  private utf16LESlice(start: number, end: number) {
    if (end - start < 1) {
      return "";
    }
    return utf16LEDecoder.decode(this.subarray(start, end));
  }

  private utf8Slice(start: number, end: number) {
    if (end - start < 1) {
      return "";
    }
    return utf8Decoder.decode(this.subarray(start, end));
  }

  copy(target: Uint8Array, targetStart?: number, sourceStart?: number, sourceEnd?: number) {
    target.set(this.subarray(sourceStart, sourceEnd), targetStart);
  }

  static byteLength(str: string) {
    let len = 0;
    let c = 0;
    for (let i = 0; i < str.length; ++i) {
      c = str.charCodeAt(i);
      if (c < 128)
        len += 1;
      else if (c < 2048)
        len += 2;
      else if ((c & 0xFC00) === 0xD800 && (str.charCodeAt(i + 1) & 0xFC00) === 0xDC00) {
        ++i;
        len += 4;
      } else
        len += 3;
    }
    return len;
  }
}

export const fromUint8Array = hasBuffer
  ? (ab: Buffer | Uint8Array) => {
      if (!Buffer.isBuffer(ab)) {
        // https://nodejs.org/docs/latest/api/buffer.html#static-method-bufferfromarraybuffer-byteoffset-length
        // Create a zero-copy Buffer wrapper around the ArrayBuffer pointed to by the Uint8Array
        return (Buffer.from(ab.buffer, ab.byteOffset, ab.byteLength) as unknown as PlatformBuffer);
      } else {
        return ab as unknown as PlatformBuffer;
      }
    }
  : (ab: Buffer | Uint8Array) => new BrowserBuffer(ab);

export const alloc = (hasBuffer ? Buffer.allocUnsafe : BrowserBuffer.alloc) as unknown as (size: number) => PlatformBuffer;

export const strByteLength = hasBuffer ? Buffer.byteLength : BrowserBuffer.byteLength;

export const fromString
= hasBuffer
  ? (str: string) => Buffer.from(str) as unknown as PlatformBuffer
  : (str: string) => {
      return new BrowserBuffer(utf8Encoder.encode(str));
    };
