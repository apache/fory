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

import { PlatformBuffer } from "../platformBuffer";

const read1 = (buffer: Uint8Array, cursor: number) => {
  return String.fromCharCode(buffer[cursor]);
};
const read2 = (buffer: Uint8Array, cursor: number) => {
  return String.fromCharCode(buffer[cursor], buffer[cursor + 1]);
};
const read3 = (buffer: Uint8Array, cursor: number) => {
  return String.fromCharCode(buffer[cursor], buffer[cursor + 1], buffer[cursor + 2]);
};
const read4 = (buffer: Uint8Array, cursor: number) => {
  return String.fromCharCode(buffer[cursor], buffer[cursor + 1], buffer[cursor + 2], buffer[cursor + 3]);
};
const read5 = (buffer: Uint8Array, cursor: number) => {
  return String.fromCharCode(buffer[cursor], buffer[cursor + 1], buffer[cursor + 2], buffer[cursor + 3], buffer[cursor + 4]);
};
const read6 = (buffer: Uint8Array, cursor: number) => {
  return String.fromCharCode(buffer[cursor], buffer[cursor + 1], buffer[cursor + 2], buffer[cursor + 3], buffer[cursor + 4], buffer[cursor + 5]);
};
const read7 = (buffer: Uint8Array, cursor: number) => {
  return String.fromCharCode(buffer[cursor], buffer[cursor + 1], buffer[cursor + 2], buffer[cursor + 3], buffer[cursor + 4], buffer[cursor + 5], buffer[cursor + 6]);
};
const read8 = (buffer: Uint8Array, cursor: number) => {
  return String.fromCharCode(buffer[cursor], buffer[cursor + 1], buffer[cursor + 2], buffer[cursor + 3], buffer[cursor + 4], buffer[cursor + 5], buffer[cursor + 6], buffer[cursor + 7]);
};
const read9 = (buffer: Uint8Array, cursor: number) => {
  return String.fromCharCode(buffer[cursor], buffer[cursor + 1], buffer[cursor + 2], buffer[cursor + 3], buffer[cursor + 4], buffer[cursor + 5], buffer[cursor + 6], buffer[cursor + 7], buffer[cursor + 8]);
};
const read10 = (buffer: Uint8Array, cursor: number) => {
  return String.fromCharCode(buffer[cursor], buffer[cursor + 1], buffer[cursor + 2], buffer[cursor + 3], buffer[cursor + 4], buffer[cursor + 5], buffer[cursor + 6], buffer[cursor + 7], buffer[cursor + 8], buffer[cursor + 9]);
};
const read11 = (buffer: Uint8Array, cursor: number) => {
  return String.fromCharCode(buffer[cursor], buffer[cursor + 1], buffer[cursor + 2], buffer[cursor + 3], buffer[cursor + 4], buffer[cursor + 5], buffer[cursor + 6], buffer[cursor + 7], buffer[cursor + 8], buffer[cursor + 9], buffer[cursor + 10]);
};
const read12 = (buffer: Uint8Array, cursor: number) => {
  return String.fromCharCode(buffer[cursor], buffer[cursor + 1], buffer[cursor + 2], buffer[cursor + 3], buffer[cursor + 4], buffer[cursor + 5], buffer[cursor + 6], buffer[cursor + 7], buffer[cursor + 8], buffer[cursor + 9], buffer[cursor + 10], buffer[cursor + 11]);
};
const read13 = (buffer: Uint8Array, cursor: number) => {
  return String.fromCharCode(buffer[cursor], buffer[cursor + 1], buffer[cursor + 2], buffer[cursor + 3], buffer[cursor + 4], buffer[cursor + 5], buffer[cursor + 6], buffer[cursor + 7], buffer[cursor + 8], buffer[cursor + 9], buffer[cursor + 10], buffer[cursor + 11], buffer[cursor + 12]);
};
const read14 = (buffer: Uint8Array, cursor: number) => {
  return String.fromCharCode(buffer[cursor], buffer[cursor + 1], buffer[cursor + 2], buffer[cursor + 3], buffer[cursor + 4], buffer[cursor + 5], buffer[cursor + 6], buffer[cursor + 7], buffer[cursor + 8], buffer[cursor + 9], buffer[cursor + 10], buffer[cursor + 11], buffer[cursor + 12], buffer[cursor + 13]);
};
const read15 = (buffer: Uint8Array, cursor: number) => {
  return String.fromCharCode(buffer[cursor], buffer[cursor + 1], buffer[cursor + 2], buffer[cursor + 3], buffer[cursor + 4], buffer[cursor + 5], buffer[cursor + 6], buffer[cursor + 7], buffer[cursor + 8], buffer[cursor + 9], buffer[cursor + 10], buffer[cursor + 11], buffer[cursor + 12], buffer[cursor + 13], buffer[cursor + 14]);
};

export const readLatin1String = (buffer: PlatformBuffer, len: number, cursor: number) => {
  switch (len) {
    case 0:
      return "";
    case 1:
      return read1(buffer, cursor);
    case 2:
      return read2(buffer, cursor);
    case 3:
      return read3(buffer, cursor);
    case 4:
      return read4(buffer, cursor);
    case 5:
      return read5(buffer, cursor);
    case 6:
      return read6(buffer, cursor);
    case 7:
      return read7(buffer, cursor);
    case 8:
      return read8(buffer, cursor);
    case 9:
      return read9(buffer, cursor);
    case 10:
      return read10(buffer, cursor);
    case 11:
      return read11(buffer, cursor);
    case 12:
      return read12(buffer, cursor);
    case 13:
      return read13(buffer, cursor);
    case 14:
      return read14(buffer, cursor);
    case 15:
      return read15(buffer, cursor);
    default:
      return buffer.toString("latin1", cursor, cursor + len,);
  }
};
