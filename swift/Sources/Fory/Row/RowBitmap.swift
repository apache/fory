// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

// The null bitmap keeps one bit per field or array element, and a set bit means the value is null.
// Bit `index` lives in byte `bitmapOffset + index / 8`, counting bits from the least significant end.

// Returns the bitmap size in bytes for `count` fields or elements, rounded up to whole 8-byte
// words. `count` must not be negative.
@inline(__always)
func rowBitmapWidth(_ count: Int) -> Int {
    ((count + 63) / 64) * 8
}

// Rounds `size` up to the next multiple of 8.
@inline(__always)
func rowRoundToWord(_ size: Int) -> Int {
    (size + 7) & ~7
}

// Reports whether bit `index` is set in the bitmap that starts at `bitmapOffset`.
@inline(__always)
func rowIsBitSet(_ bytes: UnsafeRawBufferPointer, bitmapOffset: Int, index: Int) -> Bool {
    bytes[bitmapOffset + (index >> 3)] & (UInt8(1) << UInt8(index & 7)) != 0
}

// Sets bit `index` in the bitmap that starts at `bitmapOffset`.
@inline(__always)
func rowSetBit(_ bytes: UnsafeMutableRawBufferPointer, bitmapOffset: Int, index: Int) {
    bytes[bitmapOffset + (index >> 3)] |= UInt8(1) << UInt8(index & 7)
}
