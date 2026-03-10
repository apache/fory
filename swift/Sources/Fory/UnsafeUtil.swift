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

import Foundation

// swiftlint:disable type_body_length
public enum UnsafeUtil {
    @inlinable
    @inline(__always)
    public static func writeBool(
        _ value: Bool,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        base[index] = value ? 1 : 0
        return index + 1
    }

    @inlinable
    @inline(__always)
    public static func writeInt8(
        _ value: Int8,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        base[index] = UInt8(bitPattern: value)
        return index + 1
    }

    @inlinable
    @inline(__always)
    public static func writeUInt8(
        _ value: UInt8,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        base[index] = value
        return index + 1
    }

    @inlinable
    @inline(__always)
    public static func writeInt16(
        _ value: Int16,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        writeUInt16(UInt16(bitPattern: value), to: base, index: index)
    }

    @inlinable
    @inline(__always)
    public static func writeUInt16(
        _ value: UInt16,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        let little = value.littleEndian
        base[index] = UInt8(truncatingIfNeeded: little)
        base[index + 1] = UInt8(truncatingIfNeeded: little >> 8)
        return index + 2
    }

    @inlinable
    @inline(__always)
    public static func writeInt32(
        _ value: Int32,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        let zigzag = UInt32(bitPattern: (value << 1) ^ (value >> 31))
        return writeVarUInt32(zigzag, to: base, index: index)
    }

    @inlinable
    @inline(__always)
    public static func writeInt64(
        _ value: Int64,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        let zigzag = UInt64(bitPattern: (value << 1) ^ (value >> 63))
        return writeVarUInt64(zigzag, to: base, index: index)
    }

    @inlinable
    @inline(__always)
    public static func writeInt(
        _ value: Int,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        writeInt64(Int64(value), to: base, index: index)
    }

    @inlinable
    @inline(__always)
    public static func writeUInt32(
        _ value: UInt32,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        writeVarUInt32(value, to: base, index: index)
    }

    @inlinable
    @inline(__always)
    public static func writeUInt64(
        _ value: UInt64,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        writeVarUInt64(value, to: base, index: index)
    }

    @inlinable
    @inline(__always)
    public static func writeUInt(
        _ value: UInt,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        writeVarUInt64(UInt64(value), to: base, index: index)
    }

    @inlinable
    @inline(__always)
    public static func writeFloat32(
        _ value: Float,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        writeFixedUInt32(value.bitPattern, to: base, index: index)
    }

    @inlinable
    @inline(__always)
    public static func writeFloat64(
        _ value: Double,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        writeFixedUInt64(value.bitPattern, to: base, index: index)
    }

    @inlinable
    @inline(__always)
    public static func varUInt32Size(_ value: UInt32) -> Int {
        if value < 0x80 {
            return 1
        }
        if value < 0x4000 {
            return 2
        }
        if value < 0x20_0000 {
            return 3
        }
        if value < 0x1000_0000 {
            return 4
        }
        return 5
    }

    @inlinable
    @inline(__always)
    public static func varInt32Size(_ value: Int32) -> Int {
        let zigzag = UInt32(bitPattern: (value << 1) ^ (value >> 31))
        return varUInt32Size(zigzag)
    }

    @inlinable
    @inline(__always)
    public static func varUInt64Size(_ value: UInt64) -> Int {
        if value < 0x80 {
            return 1
        }
        if value < 0x4000 {
            return 2
        }
        if value < 0x20_0000 {
            return 3
        }
        if value < 0x1000_0000 {
            return 4
        }
        if value < 0x8_0000_0000 {
            return 5
        }
        if value < 0x400_0000_0000 {
            return 6
        }
        if value < 0x2_0000_0000_0000 {
            return 7
        }
        if value < 0x100_0000_0000_0000 {
            return 8
        }
        return 9
    }

    @inlinable
    @inline(__always)
    public static func varInt64Size(_ value: Int64) -> Int {
        let zigzag = UInt64(bitPattern: (value << 1) ^ (value >> 63))
        return varUInt64Size(zigzag)
    }

    @inlinable
    @inline(__always)
    public static func varIntSize(_ value: Int) -> Int {
        varInt64Size(Int64(value))
    }

    @inlinable
    @inline(__always)
    public static func varUIntSize(_ value: UInt) -> Int {
        varUInt64Size(UInt64(value))
    }

    @inlinable
    @inline(__always)
    public static func writeVarUInt32(
        _ value: UInt32,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        if value < 0x80 {
            base[index] = UInt8(value)
            return index + 1
        }
        if value < 0x4000 {
            base[index] = (UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80
            base[index + 1] = UInt8(truncatingIfNeeded: value >> 7)
            return index + 2
        }
        if value < 0x20_0000 {
            base[index] = (UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80
            base[index + 1] = (UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80
            base[index + 2] = UInt8(truncatingIfNeeded: value >> 14)
            return index + 3
        }
        if value < 0x1000_0000 {
            base[index] = (UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80
            base[index + 1] = (UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80
            base[index + 2] = (UInt8(truncatingIfNeeded: value >> 14) & 0x7F) | 0x80
            base[index + 3] = UInt8(truncatingIfNeeded: value >> 21)
            return index + 4
        }
        base[index] = (UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80
        base[index + 1] = (UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80
        base[index + 2] = (UInt8(truncatingIfNeeded: value >> 14) & 0x7F) | 0x80
        base[index + 3] = (UInt8(truncatingIfNeeded: value >> 21) & 0x7F) | 0x80
        base[index + 4] = UInt8(truncatingIfNeeded: value >> 28)
        return index + 5
    }

    @inlinable
    @inline(__always)
    public static func writeVarUInt64(
        _ value: UInt64,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        if value < 0x80 {
            base[index] = UInt8(value)
            return index + 1
        }
        if value < 0x4000 {
            base[index] = (UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80
            base[index + 1] = UInt8(truncatingIfNeeded: value >> 7)
            return index + 2
        }
        if value < 0x20_0000 {
            base[index] = (UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80
            base[index + 1] = (UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80
            base[index + 2] = UInt8(truncatingIfNeeded: value >> 14)
            return index + 3
        }
        if value < 0x1000_0000 {
            base[index] = (UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80
            base[index + 1] = (UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80
            base[index + 2] = (UInt8(truncatingIfNeeded: value >> 14) & 0x7F) | 0x80
            base[index + 3] = UInt8(truncatingIfNeeded: value >> 21)
            return index + 4
        }
        if value < 0x8_0000_0000 {
            base[index] = (UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80
            base[index + 1] = (UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80
            base[index + 2] = (UInt8(truncatingIfNeeded: value >> 14) & 0x7F) | 0x80
            base[index + 3] = (UInt8(truncatingIfNeeded: value >> 21) & 0x7F) | 0x80
            base[index + 4] = UInt8(truncatingIfNeeded: value >> 28)
            return index + 5
        }
        if value < 0x400_0000_0000 {
            base[index] = (UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80
            base[index + 1] = (UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80
            base[index + 2] = (UInt8(truncatingIfNeeded: value >> 14) & 0x7F) | 0x80
            base[index + 3] = (UInt8(truncatingIfNeeded: value >> 21) & 0x7F) | 0x80
            base[index + 4] = (UInt8(truncatingIfNeeded: value >> 28) & 0x7F) | 0x80
            base[index + 5] = UInt8(truncatingIfNeeded: value >> 35)
            return index + 6
        }
        if value < 0x2_0000_0000_0000 {
            base[index] = (UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80
            base[index + 1] = (UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80
            base[index + 2] = (UInt8(truncatingIfNeeded: value >> 14) & 0x7F) | 0x80
            base[index + 3] = (UInt8(truncatingIfNeeded: value >> 21) & 0x7F) | 0x80
            base[index + 4] = (UInt8(truncatingIfNeeded: value >> 28) & 0x7F) | 0x80
            base[index + 5] = (UInt8(truncatingIfNeeded: value >> 35) & 0x7F) | 0x80
            base[index + 6] = UInt8(truncatingIfNeeded: value >> 42)
            return index + 7
        }
        if value < 0x100_0000_0000_0000 {
            base[index] = (UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80
            base[index + 1] = (UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80
            base[index + 2] = (UInt8(truncatingIfNeeded: value >> 14) & 0x7F) | 0x80
            base[index + 3] = (UInt8(truncatingIfNeeded: value >> 21) & 0x7F) | 0x80
            base[index + 4] = (UInt8(truncatingIfNeeded: value >> 28) & 0x7F) | 0x80
            base[index + 5] = (UInt8(truncatingIfNeeded: value >> 35) & 0x7F) | 0x80
            base[index + 6] = (UInt8(truncatingIfNeeded: value >> 42) & 0x7F) | 0x80
            base[index + 7] = UInt8(truncatingIfNeeded: value >> 49)
            return index + 8
        }
        base[index] = (UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80
        base[index + 1] = (UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80
        base[index + 2] = (UInt8(truncatingIfNeeded: value >> 14) & 0x7F) | 0x80
        base[index + 3] = (UInt8(truncatingIfNeeded: value >> 21) & 0x7F) | 0x80
        base[index + 4] = (UInt8(truncatingIfNeeded: value >> 28) & 0x7F) | 0x80
        base[index + 5] = (UInt8(truncatingIfNeeded: value >> 35) & 0x7F) | 0x80
        base[index + 6] = (UInt8(truncatingIfNeeded: value >> 42) & 0x7F) | 0x80
        base[index + 7] = (UInt8(truncatingIfNeeded: value >> 49) & 0x7F) | 0x80
        base[index + 8] = UInt8(truncatingIfNeeded: value >> 56)
        return index + 9
    }

    @inlinable
    @inline(__always)
    public static func readBoolUnchecked(
        from base: UnsafePointer<UInt8>,
        index: Int
    ) -> Bool {
        base[index] != 0
    }

    @inlinable
    @inline(__always)
    public static func readInt8Unchecked(
        from base: UnsafePointer<UInt8>,
        index: Int
    ) -> Int8 {
        Int8(bitPattern: base[index])
    }

    @inlinable
    @inline(__always)
    public static func readUInt8Unchecked(
        from base: UnsafePointer<UInt8>,
        index: Int
    ) -> UInt8 {
        base[index]
    }

    @inlinable
    @inline(__always)
    public static func readInt16Unchecked(
        from base: UnsafePointer<UInt8>,
        index: Int
    ) -> Int16 {
        Int16(bitPattern: readUInt16Unchecked(from: base, index: index))
    }

    @inlinable
    @inline(__always)
    public static func readUInt16Unchecked(
        from base: UnsafePointer<UInt8>,
        index: Int
    ) -> UInt16 {
        let b0 = UInt16(base[index])
        let b1 = UInt16(base[index + 1]) << 8
        return b0 | b1
    }

    @inlinable
    @inline(__always)
    public static func readFloat32Unchecked(
        from base: UnsafePointer<UInt8>,
        index: Int
    ) -> Float {
        Float(bitPattern: readFixedUInt32Unchecked(from: base, index: index))
    }

    @inlinable
    @inline(__always)
    public static func readFloat64Unchecked(
        from base: UnsafePointer<UInt8>,
        index: Int
    ) -> Double {
        Double(bitPattern: readFixedUInt64Unchecked(from: base, index: index))
    }

    @inlinable
    @inline(__always)
    public static func readBool(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> Bool {
        try readUInt8(from: bytes, index: &index) != 0
    }

    @inlinable
    @inline(__always)
    public static func readInt8(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> Int8 {
        Int8(bitPattern: try readUInt8(from: bytes, index: &index))
    }

    @inlinable
    @inline(__always)
    public static func readUInt8(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> UInt8 {
        try checkReadable(bytes, index: index, need: 1)
        guard let base = bytes.baseAddress else {
            throw ForyError.outOfBounds(cursor: index, need: 1, length: bytes.count)
        }
        let value = base[index]
        index += 1
        return value
    }

    @inlinable
    @inline(__always)
    public static func readInt16(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> Int16 {
        Int16(bitPattern: try readUInt16(from: bytes, index: &index))
    }

    @inlinable
    @inline(__always)
    public static func readUInt16(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> UInt16 {
        try checkReadable(bytes, index: index, need: 2)
        guard let base = bytes.baseAddress else {
            throw ForyError.outOfBounds(cursor: index, need: 2, length: bytes.count)
        }
        let value = readUInt16Unchecked(from: UnsafePointer(base), index: index)
        index += 2
        return value
    }

    @inlinable
    @inline(__always)
    public static func readInt32(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> Int32 {
        try readVarInt32(from: bytes, index: &index)
    }

    @inlinable
    @inline(__always)
    public static func readUInt32(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> UInt32 {
        try readVarUInt32(from: bytes, index: &index)
    }

    @inlinable
    @inline(__always)
    public static func readInt64(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> Int64 {
        try readVarInt64(from: bytes, index: &index)
    }

    @inlinable
    @inline(__always)
    public static func readUInt64(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> UInt64 {
        try readVarUInt64(from: bytes, index: &index)
    }

    @inlinable
    @inline(__always)
    public static func readInt(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> Int {
        Int(try readVarInt64(from: bytes, index: &index))
    }

    @inlinable
    @inline(__always)
    public static func readUInt(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> UInt {
        UInt(try readVarUInt64(from: bytes, index: &index))
    }

    @inlinable
    @inline(__always)
    public static func readFloat32(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> Float {
        Float(bitPattern: try readFixedUInt32(from: bytes, index: &index))
    }

    @inlinable
    @inline(__always)
    public static func readFloat64(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> Double {
        Double(bitPattern: try readFixedUInt64(from: bytes, index: &index))
    }

    @inlinable
    @inline(__always)
    public static func readVarInt32(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> Int32 {
        let encoded = try readVarUInt32(from: bytes, index: &index)
        return Int32(bitPattern: (encoded >> 1) ^ (~(encoded & 1) &+ 1))
    }

    @inlinable
    @inline(__always)
    public static func readVarInt64(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> Int64 {
        let encoded = try readVarUInt64(from: bytes, index: &index)
        return Int64(bitPattern: (encoded >> 1) ^ (~(encoded & 1) &+ 1))
    }

    @inlinable
    @inline(__always)
    public static func readInt32(
        from base: UnsafePointer<UInt8>,
        length: Int,
        index: inout Int
    ) throws -> Int32 {
        try readVarInt32(from: base, length: length, index: &index)
    }

    @inlinable
    @inline(__always)
    public static func readUInt32(
        from base: UnsafePointer<UInt8>,
        length: Int,
        index: inout Int
    ) throws -> UInt32 {
        try readVarUInt32(from: base, length: length, index: &index)
    }

    @inlinable
    @inline(__always)
    public static func readInt64(
        from base: UnsafePointer<UInt8>,
        length: Int,
        index: inout Int
    ) throws -> Int64 {
        try readVarInt64(from: base, length: length, index: &index)
    }

    @inlinable
    @inline(__always)
    public static func readUInt64(
        from base: UnsafePointer<UInt8>,
        length: Int,
        index: inout Int
    ) throws -> UInt64 {
        try readVarUInt64(from: base, length: length, index: &index)
    }

    @inlinable
    @inline(__always)
    public static func readInt(
        from base: UnsafePointer<UInt8>,
        length: Int,
        index: inout Int
    ) throws -> Int {
        Int(try readVarInt64(from: base, length: length, index: &index))
    }

    @inlinable
    @inline(__always)
    public static func readUInt(
        from base: UnsafePointer<UInt8>,
        length: Int,
        index: inout Int
    ) throws -> UInt {
        UInt(try readVarUInt64(from: base, length: length, index: &index))
    }

    @inlinable
    @inline(__always)
    public static func readVarUInt32(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> UInt32 {
        guard let base = bytes.baseAddress else {
            throw ForyError.outOfBounds(cursor: index, need: 1, length: bytes.count)
        }
        let available = bytes.count - index
        guard available > 0 else {
            throw ForyError.outOfBounds(cursor: index, need: 1, length: bytes.count)
        }

        let b0 = base[index]
        if b0 < 0x80 {
            index += 1
            return UInt32(b0)
        }
        guard available >= 2 else {
            throw ForyError.outOfBounds(cursor: index, need: 2, length: bytes.count)
        }
        let b1 = base[index + 1]
        if b1 < 0x80 {
            index += 2
            return UInt32(b0 & 0x7F) | (UInt32(b1) << 7)
        }
        guard available >= 3 else {
            throw ForyError.outOfBounds(cursor: index, need: 3, length: bytes.count)
        }
        let b2 = base[index + 2]
        if b2 < 0x80 {
            index += 3
            return UInt32(b0 & 0x7F) | (UInt32(b1 & 0x7F) << 7) | (UInt32(b2) << 14)
        }
        guard available >= 4 else {
            throw ForyError.outOfBounds(cursor: index, need: 4, length: bytes.count)
        }
        let b3 = base[index + 3]
        if b3 < 0x80 {
            index += 4
            return UInt32(b0 & 0x7F) |
                (UInt32(b1 & 0x7F) << 7) |
                (UInt32(b2 & 0x7F) << 14) |
                (UInt32(b3) << 21)
        }
        guard available >= 5 else {
            throw ForyError.outOfBounds(cursor: index, need: 5, length: bytes.count)
        }
        let b4 = base[index + 4]
        if b4 >= 0x80 {
            throw ForyError.encodingError("varuint32 overflow")
        }
        index += 5
        return UInt32(b0 & 0x7F) |
            (UInt32(b1 & 0x7F) << 7) |
            (UInt32(b2 & 0x7F) << 14) |
            (UInt32(b3 & 0x7F) << 21) |
            (UInt32(b4) << 28)
    }

    @inlinable
    @inline(__always)
    public static func readVarUInt64(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> UInt64 {
        let available = bytes.count - index
        guard let base = bytes.baseAddress else {
            throw ForyError.outOfBounds(cursor: index, need: 1, length: bytes.count)
        }
        if available >= 9 {
            let offset = index
            let b0 = base[offset]
            if b0 < 0x80 {
                index = offset + 1
                return UInt64(b0)
            }

            let b1 = base[offset + 1]
            if b1 < 0x80 {
                index = offset + 2
                return UInt64(b0 & 0x7F) | (UInt64(b1) << 7)
            }

            let b2 = base[offset + 2]
            if b2 < 0x80 {
                index = offset + 3
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2) << 14)
            }

            let b3 = base[offset + 3]
            if b3 < 0x80 {
                index = offset + 4
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2 & 0x7F) << 14) |
                    (UInt64(b3) << 21)
            }

            let b4 = base[offset + 4]
            if b4 < 0x80 {
                index = offset + 5
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2 & 0x7F) << 14) |
                    (UInt64(b3 & 0x7F) << 21) |
                    (UInt64(b4) << 28)
            }

            let b5 = base[offset + 5]
            if b5 < 0x80 {
                index = offset + 6
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2 & 0x7F) << 14) |
                    (UInt64(b3 & 0x7F) << 21) |
                    (UInt64(b4 & 0x7F) << 28) |
                    (UInt64(b5) << 35)
            }

            let b6 = base[offset + 6]
            if b6 < 0x80 {
                index = offset + 7
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2 & 0x7F) << 14) |
                    (UInt64(b3 & 0x7F) << 21) |
                    (UInt64(b4 & 0x7F) << 28) |
                    (UInt64(b5 & 0x7F) << 35) |
                    (UInt64(b6) << 42)
            }

            let b7 = base[offset + 7]
            if b7 < 0x80 {
                index = offset + 8
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2 & 0x7F) << 14) |
                    (UInt64(b3 & 0x7F) << 21) |
                    (UInt64(b4 & 0x7F) << 28) |
                    (UInt64(b5 & 0x7F) << 35) |
                    (UInt64(b6 & 0x7F) << 42) |
                    (UInt64(b7) << 49)
            }

            let b8 = base[offset + 8]
            index = offset + 9
            let low = UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3 & 0x7F) << 21)
            let high = (UInt64(b4 & 0x7F) << 28) |
                (UInt64(b5 & 0x7F) << 35) |
                (UInt64(b6 & 0x7F) << 42) |
                (UInt64(b7 & 0x7F) << 49) |
                (UInt64(b8) << 56)
            return low | high
        }

        try checkReadable(bytes, index: index, need: 1)
        let b0 = base[index]
        if b0 < 0x80 {
            index += 1
            return UInt64(b0)
        }

        try checkReadable(bytes, index: index, need: 2)
        let b1 = base[index + 1]
        if b1 < 0x80 {
            index += 2
            return UInt64(b0 & 0x7F) | (UInt64(b1) << 7)
        }

        try checkReadable(bytes, index: index, need: 3)
        let b2 = base[index + 2]
        if b2 < 0x80 {
            index += 3
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2) << 14)
        }

        try checkReadable(bytes, index: index, need: 4)
        let b3 = base[index + 3]
        if b3 < 0x80 {
            index += 4
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3) << 21)
        }

        try checkReadable(bytes, index: index, need: 5)
        let b4 = base[index + 4]
        if b4 < 0x80 {
            index += 5
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3 & 0x7F) << 21) |
                (UInt64(b4) << 28)
        }

        try checkReadable(bytes, index: index, need: 6)
        let b5 = base[index + 5]
        if b5 < 0x80 {
            index += 6
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3 & 0x7F) << 21) |
                (UInt64(b4 & 0x7F) << 28) |
                (UInt64(b5) << 35)
        }

        try checkReadable(bytes, index: index, need: 7)
        let b6 = base[index + 6]
        if b6 < 0x80 {
            index += 7
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3 & 0x7F) << 21) |
                (UInt64(b4 & 0x7F) << 28) |
                (UInt64(b5 & 0x7F) << 35) |
                (UInt64(b6) << 42)
        }

        try checkReadable(bytes, index: index, need: 8)
        let b7 = base[index + 7]
        if b7 < 0x80 {
            index += 8
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3 & 0x7F) << 21) |
                (UInt64(b4 & 0x7F) << 28) |
                (UInt64(b5 & 0x7F) << 35) |
                (UInt64(b6 & 0x7F) << 42) |
                (UInt64(b7) << 49)
        }

        try checkReadable(bytes, index: index, need: 9)
        let b8 = base[index + 8]
        index += 9
        let low = UInt64(b0 & 0x7F) |
            (UInt64(b1 & 0x7F) << 7) |
            (UInt64(b2 & 0x7F) << 14) |
            (UInt64(b3 & 0x7F) << 21)
        let high = (UInt64(b4 & 0x7F) << 28) |
            (UInt64(b5 & 0x7F) << 35) |
            (UInt64(b6 & 0x7F) << 42) |
            (UInt64(b7 & 0x7F) << 49) |
            (UInt64(b8) << 56)
        return low | high
    }

    @inlinable
    @inline(__always)
    public static func readVarInt32(
        from base: UnsafePointer<UInt8>,
        length: Int,
        index: inout Int
    ) throws -> Int32 {
        let encoded = try readVarUInt32(from: base, length: length, index: &index)
        return Int32(bitPattern: (encoded >> 1) ^ (~(encoded & 1) &+ 1))
    }

    @inlinable
    @inline(__always)
    public static func readVarInt64(
        from base: UnsafePointer<UInt8>,
        length: Int,
        index: inout Int
    ) throws -> Int64 {
        let encoded = try readVarUInt64(from: base, length: length, index: &index)
        return Int64(bitPattern: (encoded >> 1) ^ (~(encoded & 1) &+ 1))
    }

    @inlinable
    @inline(__always)
    public static func readVarUInt32(
        from base: UnsafePointer<UInt8>,
        length: Int,
        index: inout Int
    ) throws -> UInt32 {
        let available = length - index
        guard available > 0 else {
            throw ForyError.outOfBounds(cursor: index, need: 1, length: length)
        }

        let b0 = base[index]
        if b0 < 0x80 {
            index += 1
            return UInt32(b0)
        }
        guard available >= 2 else {
            throw ForyError.outOfBounds(cursor: index, need: 2, length: length)
        }
        let b1 = base[index + 1]
        if b1 < 0x80 {
            index += 2
            return UInt32(b0 & 0x7F) | (UInt32(b1) << 7)
        }
        guard available >= 3 else {
            throw ForyError.outOfBounds(cursor: index, need: 3, length: length)
        }
        let b2 = base[index + 2]
        if b2 < 0x80 {
            index += 3
            return UInt32(b0 & 0x7F) | (UInt32(b1 & 0x7F) << 7) | (UInt32(b2) << 14)
        }
        guard available >= 4 else {
            throw ForyError.outOfBounds(cursor: index, need: 4, length: length)
        }
        let b3 = base[index + 3]
        if b3 < 0x80 {
            index += 4
            return UInt32(b0 & 0x7F) |
                (UInt32(b1 & 0x7F) << 7) |
                (UInt32(b2 & 0x7F) << 14) |
                (UInt32(b3) << 21)
        }
        guard available >= 5 else {
            throw ForyError.outOfBounds(cursor: index, need: 5, length: length)
        }
        let b4 = base[index + 4]
        if b4 >= 0x80 {
            throw ForyError.encodingError("varuint32 overflow")
        }
        index += 5
        return UInt32(b0 & 0x7F) |
            (UInt32(b1 & 0x7F) << 7) |
            (UInt32(b2 & 0x7F) << 14) |
            (UInt32(b3 & 0x7F) << 21) |
            (UInt32(b4) << 28)
    }

    @inlinable
    @inline(__always)
    public static func readVarUInt64(
        from base: UnsafePointer<UInt8>,
        length: Int,
        index: inout Int
    ) throws -> UInt64 {
        let available = length - index
        if available >= 9 {
            let offset = index
            let b0 = base[offset]
            if b0 < 0x80 {
                index = offset + 1
                return UInt64(b0)
            }

            let b1 = base[offset + 1]
            if b1 < 0x80 {
                index = offset + 2
                return UInt64(b0 & 0x7F) | (UInt64(b1) << 7)
            }

            let b2 = base[offset + 2]
            if b2 < 0x80 {
                index = offset + 3
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2) << 14)
            }

            let b3 = base[offset + 3]
            if b3 < 0x80 {
                index = offset + 4
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2 & 0x7F) << 14) |
                    (UInt64(b3) << 21)
            }

            let b4 = base[offset + 4]
            if b4 < 0x80 {
                index = offset + 5
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2 & 0x7F) << 14) |
                    (UInt64(b3 & 0x7F) << 21) |
                    (UInt64(b4) << 28)
            }

            let b5 = base[offset + 5]
            if b5 < 0x80 {
                index = offset + 6
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2 & 0x7F) << 14) |
                    (UInt64(b3 & 0x7F) << 21) |
                    (UInt64(b4 & 0x7F) << 28) |
                    (UInt64(b5) << 35)
            }

            let b6 = base[offset + 6]
            if b6 < 0x80 {
                index = offset + 7
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2 & 0x7F) << 14) |
                    (UInt64(b3 & 0x7F) << 21) |
                    (UInt64(b4 & 0x7F) << 28) |
                    (UInt64(b5 & 0x7F) << 35) |
                    (UInt64(b6) << 42)
            }

            let b7 = base[offset + 7]
            if b7 < 0x80 {
                index = offset + 8
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2 & 0x7F) << 14) |
                    (UInt64(b3 & 0x7F) << 21) |
                    (UInt64(b4 & 0x7F) << 28) |
                    (UInt64(b5 & 0x7F) << 35) |
                    (UInt64(b6 & 0x7F) << 42) |
                    (UInt64(b7) << 49)
            }

            let b8 = base[offset + 8]
            index = offset + 9
            let low = UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3 & 0x7F) << 21)
            let high = (UInt64(b4 & 0x7F) << 28) |
                (UInt64(b5 & 0x7F) << 35) |
                (UInt64(b6 & 0x7F) << 42) |
                (UInt64(b7 & 0x7F) << 49) |
                (UInt64(b8) << 56)
            return low | high
        }

        try checkReadable(length: length, index: index, need: 1)
        let b0 = base[index]
        if b0 < 0x80 {
            index += 1
            return UInt64(b0)
        }

        try checkReadable(length: length, index: index, need: 2)
        let b1 = base[index + 1]
        if b1 < 0x80 {
            index += 2
            return UInt64(b0 & 0x7F) | (UInt64(b1) << 7)
        }

        try checkReadable(length: length, index: index, need: 3)
        let b2 = base[index + 2]
        if b2 < 0x80 {
            index += 3
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2) << 14)
        }

        try checkReadable(length: length, index: index, need: 4)
        let b3 = base[index + 3]
        if b3 < 0x80 {
            index += 4
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3) << 21)
        }

        try checkReadable(length: length, index: index, need: 5)
        let b4 = base[index + 4]
        if b4 < 0x80 {
            index += 5
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3 & 0x7F) << 21) |
                (UInt64(b4) << 28)
        }

        try checkReadable(length: length, index: index, need: 6)
        let b5 = base[index + 5]
        if b5 < 0x80 {
            index += 6
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3 & 0x7F) << 21) |
                (UInt64(b4 & 0x7F) << 28) |
                (UInt64(b5) << 35)
        }

        try checkReadable(length: length, index: index, need: 7)
        let b6 = base[index + 6]
        if b6 < 0x80 {
            index += 7
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3 & 0x7F) << 21) |
                (UInt64(b4 & 0x7F) << 28) |
                (UInt64(b5 & 0x7F) << 35) |
                (UInt64(b6) << 42)
        }

        try checkReadable(length: length, index: index, need: 8)
        let b7 = base[index + 7]
        if b7 < 0x80 {
            index += 8
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3 & 0x7F) << 21) |
                (UInt64(b4 & 0x7F) << 28) |
                (UInt64(b5 & 0x7F) << 35) |
                (UInt64(b6 & 0x7F) << 42) |
                (UInt64(b7) << 49)
        }

        try checkReadable(length: length, index: index, need: 9)
        let b8 = base[index + 8]
        index += 9
        let low = UInt64(b0 & 0x7F) |
            (UInt64(b1 & 0x7F) << 7) |
            (UInt64(b2 & 0x7F) << 14) |
            (UInt64(b3 & 0x7F) << 21)
        let high = (UInt64(b4 & 0x7F) << 28) |
            (UInt64(b5 & 0x7F) << 35) |
            (UInt64(b6 & 0x7F) << 42) |
            (UInt64(b7 & 0x7F) << 49) |
            (UInt64(b8) << 56)
        return low | high
    }

    @inlinable
    @inline(__always)
    public static func readFixedUInt32(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> UInt32 {
        try checkReadable(bytes, index: index, need: 4)
        guard let base = bytes.baseAddress else {
            throw ForyError.outOfBounds(cursor: index, need: 4, length: bytes.count)
        }
        let value = readFixedUInt32Unchecked(from: UnsafePointer(base), index: index)
        index += 4
        return value
    }

    @inlinable
    @inline(__always)
    public static func readFixedUInt64(
        from bytes: UnsafeBufferPointer<UInt8>,
        index: inout Int
    ) throws -> UInt64 {
        try checkReadable(bytes, index: index, need: 8)
        guard let base = bytes.baseAddress else {
            throw ForyError.outOfBounds(cursor: index, need: 8, length: bytes.count)
        }
        let value = readFixedUInt64Unchecked(from: UnsafePointer(base), index: index)
        index += 8
        return value
    }

    @inlinable
    @inline(__always)
    static func writeFixedUInt32(
        _ value: UInt32,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        let little = value.littleEndian
        base[index] = UInt8(truncatingIfNeeded: little)
        base[index + 1] = UInt8(truncatingIfNeeded: little >> 8)
        base[index + 2] = UInt8(truncatingIfNeeded: little >> 16)
        base[index + 3] = UInt8(truncatingIfNeeded: little >> 24)
        return index + 4
    }

    @inlinable
    @inline(__always)
    static func writeFixedUInt64(
        _ value: UInt64,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        let little = value.littleEndian
        base[index] = UInt8(truncatingIfNeeded: little)
        base[index + 1] = UInt8(truncatingIfNeeded: little >> 8)
        base[index + 2] = UInt8(truncatingIfNeeded: little >> 16)
        base[index + 3] = UInt8(truncatingIfNeeded: little >> 24)
        base[index + 4] = UInt8(truncatingIfNeeded: little >> 32)
        base[index + 5] = UInt8(truncatingIfNeeded: little >> 40)
        base[index + 6] = UInt8(truncatingIfNeeded: little >> 48)
        base[index + 7] = UInt8(truncatingIfNeeded: little >> 56)
        return index + 8
    }

    @inlinable
    @inline(__always)
    static func readFixedUInt32Unchecked(
        from base: UnsafePointer<UInt8>,
        index: Int
    ) -> UInt32 {
        let b0 = UInt32(base[index])
        let b1 = UInt32(base[index + 1]) << 8
        let b2 = UInt32(base[index + 2]) << 16
        let b3 = UInt32(base[index + 3]) << 24
        return b0 | b1 | b2 | b3
    }

    @inlinable
    @inline(__always)
    static func readFixedUInt64Unchecked(
        from base: UnsafePointer<UInt8>,
        index: Int
    ) -> UInt64 {
        let b0 = UInt64(base[index])
        let b1 = UInt64(base[index + 1]) << 8
        let b2 = UInt64(base[index + 2]) << 16
        let b3 = UInt64(base[index + 3]) << 24
        let b4 = UInt64(base[index + 4]) << 32
        let b5 = UInt64(base[index + 5]) << 40
        let b6 = UInt64(base[index + 6]) << 48
        let b7 = UInt64(base[index + 7]) << 56
        return b0 | b1 | b2 | b3 | b4 | b5 | b6 | b7
    }

    @inlinable
    @inline(__always)
    public static func checkReadable(
        _ bytes: UnsafeBufferPointer<UInt8>,
        index: Int,
        need: Int
    ) throws {
        if index < 0 || need < 0 || index + need > bytes.count {
            throw ForyError.outOfBounds(cursor: index, need: need, length: bytes.count)
        }
    }

    @inlinable
    @inline(__always)
    public static func checkReadable(
        length: Int,
        index: Int,
        need: Int
    ) throws {
        if index < 0 || need < 0 || index + need > length {
            throw ForyError.outOfBounds(cursor: index, need: need, length: length)
        }
    }

    @inlinable
    @inline(__always)
    public static func writeRegion(
        buffer: ByteBuffer,
        exactCount: Int,
        _ body: (UnsafeMutablePointer<UInt8>) -> Void
    ) {
        guard exactCount > 0 else {
            return
        }
        let start = buffer.storage.count
        buffer.storage.append(contentsOf: repeatElement(0, count: exactCount))
        buffer.storage.withUnsafeMutableBufferPointer { bytes in
            body(bytes.baseAddress!.advanced(by: start))
        }
    }

    @inlinable
    @inline(__always)
    public static func writeRegion(
        buffer: ByteBuffer,
        maxCount: Int,
        _ body: (UnsafeMutablePointer<UInt8>) -> Int
    ) {
        // Keep the closure non-throwing for the hot path; callers return the
        // actual byte count and we trim the unused suffix afterward.
        guard maxCount > 0 else {
            return
        }
        let start = buffer.storage.count
        buffer.storage.append(contentsOf: repeatElement(0, count: maxCount))
        let written = buffer.storage.withUnsafeMutableBufferPointer { bytes in
            body(bytes.baseAddress!.advanced(by: start))
        }
        precondition(
            written >= 0 && written <= maxCount,
            "writeRegion wrote \(written) bytes into a maxCount \(maxCount) region"
        )
        if written < maxCount {
            buffer.storage.removeLast(maxCount - written)
        }
    }

    @inlinable
    @inline(__always)
    public static func readRegion(
        buffer: ByteBuffer,
        _ body: (UnsafeBufferPointer<UInt8>) throws -> Int
    ) throws {
        let startIndex = buffer.cursor
        let readableCount = buffer.count
        if _slowPath(startIndex < 0 || startIndex > readableCount) {
            throw ForyError.outOfBounds(
                cursor: startIndex,
                need: 0,
                length: readableCount
            )
        }
        let available = readableCount - startIndex
        let consumed = try buffer.storage.withUnsafeBufferPointer { bytes -> Int in
            let start = bytes.baseAddress.map { $0.advanced(by: startIndex) }
            let region = UnsafeBufferPointer(start: start, count: available)
            return try body(region)
        }
        if consumed < 0 || consumed > available {
            throw ForyError.outOfBounds(cursor: startIndex, need: consumed, length: readableCount)
        }
        buffer.cursor = startIndex + consumed
    }

    @inline(__always)
    static func copyBytes(
        _ bytes: [UInt8],
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        guard !bytes.isEmpty else {
            return index
        }
        bytes.withUnsafeBufferPointer { source in
            guard let sourceBase = source.baseAddress else {
                return
            }
            UnsafeMutableRawPointer(base.advanced(by: index))
                .copyMemory(from: UnsafeRawPointer(sourceBase), byteCount: source.count)
        }
        return index + bytes.count
    }

    @inline(__always)
    static func copyBytes(
        _ bytes: UnsafeBufferPointer<UInt8>,
        to base: UnsafeMutablePointer<UInt8>,
        index: Int
    ) -> Int {
        guard let sourceBase = bytes.baseAddress, bytes.count > 0 else {
            return index
        }
        UnsafeMutableRawPointer(base.advanced(by: index))
            .copyMemory(from: UnsafeRawPointer(sourceBase), byteCount: bytes.count)
        return index + bytes.count
    }
}
// swiftlint:enable type_body_length
