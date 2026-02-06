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
    return sign | ((exponent === 128) ? 0x7c00 : 0x7fff);
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
