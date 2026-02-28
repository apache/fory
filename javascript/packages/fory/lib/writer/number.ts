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
const int32View = new Int32Array(float32View.buffer);

export function toFloat16(value: number) {
  float32View[0] = value;
  const floatValue = int32View[0];
  const sign = (floatValue >>> 16) & 0x8000; // sign only
  const exponent = ((floatValue >>> 23) & 0xff) - 127; // extract exponent from floatValue
  const significand = floatValue & 0x7fffff; // extract significand from floatValue

  if (exponent === 128) { // floatValue is NaN or Infinity
    return sign | 0x7c00 | (significand !== 0 ? 0x0200 : 0);
  }

  if (exponent > 15) {
    return sign | 0x7c00; // return Infinity
  }

  if (exponent < -14) {
    // subnormal
    // shift amount = 13 - 14 - exponent = -1 - exponent
    return sign | ((significand | 0x800000) >> (13 - 14 - exponent));
  }

  return sign | ((exponent + 15) << 10) | (significand >> 13);
}

const float32ViewBf = new Float32Array(1);
const uint32ViewBf = new Uint32Array(float32ViewBf.buffer);

/**
 * Convert float32 to bfloat16 bits (round-to-nearest, ties-to-even).
 * BFloat16 layout: 1 sign, 8 exponent, 7 mantissa.
 */
export function toBFloat16(value: number): number {
  float32ViewBf[0] = value;
  const bits = uint32ViewBf[0];
  const exponent = (bits >> 23) & 0xff;
  if (exponent === 255) {
    return (bits >> 16) & 0xffff;
  }
  const remainder = bits & 0x1ffff;
  let u = (bits + 0x8000) >> 16;
  if (remainder === 0x8000 && (u & 1) !== 0) {
    u--;
  }
  return u & 0xffff;
}
