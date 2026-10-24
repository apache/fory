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

// A variable-width value such as a string, array, map, or nested struct is stored after the fixed
// slots. Its slot holds one UInt64 ticket: the offset from the start of the enclosing row or array
// in the upper 32 bits and the byte size in the lower 32 bits, so the size comes first in memory.

// Packs an offset and a size into one slot value. Both must fit in 32 bits.
func rowEncodeOffsetAndSize(offset: Int, size: Int) throws -> UInt64 {
    guard let offset32 = UInt32(exactly: offset), let size32 = UInt32(exactly: size) else {
        throw rowValueTooLarge(offset: offset, size: size)
    }
    return UInt64(offset32) << 32 | UInt64(size32)
}

// Splits a slot value back into its offset and size. They stay UInt32 so a large offset cannot
// trap on platforms where Int is 32 bits.
@inline(__always)
func rowDecodeOffsetAndSize(_ slot: UInt64) -> (offset: UInt32, size: UInt32) {
    (UInt32(truncatingIfNeeded: slot >> 32), UInt32(truncatingIfNeeded: slot))
}

// Checks that `size` bytes at `offset` fit inside a region of `regionCount` bytes and returns their
// absolute range in the buffer. The sum is taken in UInt64, so it cannot overflow.
@inline(__always)
func rowCheckedRange(offset: UInt32, size: UInt32, regionBase: Int, regionCount: Int) throws -> Range<Int> {
    guard UInt64(offset) + UInt64(size) <= UInt64(regionCount) else {
        throw rowOutOfBounds(offset: offset, size: size, regionCount: regionCount)
    }
    let start = regionBase + Int(offset)
    return start..<(start + Int(size))
}

@inline(never)
func rowValueTooLarge(offset: Int, size: Int) -> ForyError {
    .encodingError(
        "row value at offset \(offset) with \(size) bytes does not fit the row format's 32-bit offset and size"
    )
}

@inline(never)
func rowOutOfBounds(offset: UInt32, size: UInt32, regionCount: Int) -> ForyError {
    .outOfBounds(cursor: Int(clamping: offset), need: Int(clamping: size), length: regionCount)
}
