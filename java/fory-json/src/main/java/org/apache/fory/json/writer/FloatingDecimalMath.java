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

package org.apache.fory.json.writer;

final class FloatingDecimalMath {
  private FloatingDecimalMath() {}

  static long unsignedMultiplyHigh(long x, long y) {
    long leftLow = x & 0xffffffffL;
    long leftHigh = x >>> 32;
    long rightLow = y & 0xffffffffL;
    long rightHigh = y >>> 32;
    long low = leftLow * rightLow;
    long cross = leftHigh * rightLow + (low >>> 32);
    long middle = (cross & 0xffffffffL) + leftLow * rightHigh;
    return leftHigh * rightHigh + (cross >>> 32) + (middle >>> 32);
  }
}
