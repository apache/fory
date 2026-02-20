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

public final class ByteBuffer {
    @usableFromInline
    internal var storage: [UInt8]

    @usableFromInline
    internal var cursor: Int

    public init(capacity: Int = 256) {
        storage = []
        storage.reserveCapacity(capacity)
        cursor = 0
    }

    public init(data: Data) {
        storage = Array(data)
        cursor = 0
    }

    public init(bytes: [UInt8]) {
        storage = bytes
        cursor = 0
    }

    public var count: Int {
        storage.count
    }

    public var remaining: Int {
        storage.count - cursor
    }

    public func reserve(_ additional: Int) {
        storage.reserveCapacity(storage.count + additional)
    }

    public func clear() {
        storage.removeAll(keepingCapacity: true)
        cursor = 0
    }

    public func reset() {
        clear()
    }

    public func flip() {
        cursor = 0
    }

    public func setCursor(_ value: Int) {
        cursor = value
    }

    public func getCursor() -> Int {
        cursor
    }

    public func moveBack(_ amount: Int) {
        cursor -= amount
    }

    public func writeUInt8(_ value: UInt8) {
        storage.append(value)
    }

    public func writeInt8(_ value: Int8) {
        storage.append(UInt8(bitPattern: value))
    }

    public func writeUInt16(_ value: UInt16) {
        let le = value.littleEndian
        storage.append(UInt8(truncatingIfNeeded: le))
        storage.append(UInt8(truncatingIfNeeded: le >> 8))
    }

    public func writeInt16(_ value: Int16) {
        writeUInt16(UInt16(bitPattern: value))
    }

    public func writeUInt32(_ value: UInt32) {
        let le = value.littleEndian
        storage.append(UInt8(truncatingIfNeeded: le))
        storage.append(UInt8(truncatingIfNeeded: le >> 8))
        storage.append(UInt8(truncatingIfNeeded: le >> 16))
        storage.append(UInt8(truncatingIfNeeded: le >> 24))
    }

    public func writeInt32(_ value: Int32) {
        writeUInt32(UInt32(bitPattern: value))
    }

    public func writeUInt64(_ value: UInt64) {
        let le = value.littleEndian
        storage.append(UInt8(truncatingIfNeeded: le))
        storage.append(UInt8(truncatingIfNeeded: le >> 8))
        storage.append(UInt8(truncatingIfNeeded: le >> 16))
        storage.append(UInt8(truncatingIfNeeded: le >> 24))
        storage.append(UInt8(truncatingIfNeeded: le >> 32))
        storage.append(UInt8(truncatingIfNeeded: le >> 40))
        storage.append(UInt8(truncatingIfNeeded: le >> 48))
        storage.append(UInt8(truncatingIfNeeded: le >> 56))
    }

    public func writeInt64(_ value: Int64) {
        writeUInt64(UInt64(bitPattern: value))
    }

    public func writeVarUInt32(_ value: UInt32) {
        var remaining = value
        while remaining >= 0x80 {
            writeUInt8(UInt8(remaining & 0x7F) | 0x80)
            remaining >>= 7
        }
        writeUInt8(UInt8(remaining))
    }

    public func writeVarUInt64(_ value: UInt64) {
        // Fory PVL varuint64 uses at most 9 bytes.
        // The first 8 bytes use 7 data bits + continuation bit.
        // The 9th byte (if needed) stores the top 8 bits directly.
        var remaining = value
        for _ in 0..<8 {
            if remaining < 0x80 {
                writeUInt8(UInt8(remaining))
                return
            }
            writeUInt8(UInt8(remaining & 0x7F) | 0x80)
            remaining >>= 7
        }
        writeUInt8(UInt8(remaining & 0xFF))
    }

    public func writeVarUInt36Small(_ value: UInt64) {
        precondition(value < (1 << 36), "varuint36small overflow")
        writeVarUInt64(value)
    }

    public func writeVarInt32(_ value: Int32) {
        let zigzag = UInt32(bitPattern: (value << 1) ^ (value >> 31))
        writeVarUInt32(zigzag)
    }

    public func writeVarInt64(_ value: Int64) {
        let zigzag = UInt64(bitPattern: (value << 1) ^ (value >> 63))
        writeVarUInt64(zigzag)
    }

    public func writeTaggedInt64(_ value: Int64) {
        if (-1_073_741_824 ... 1_073_741_823).contains(value) {
            writeInt32(Int32(truncatingIfNeeded: value) << 1)
        } else {
            writeUInt8(0x01)
            writeInt64(value)
        }
    }

    public func writeTaggedUInt64(_ value: UInt64) {
        if value <= UInt64(Int32.max) {
            writeUInt32(UInt32(truncatingIfNeeded: value) << 1)
        } else {
            writeUInt8(0x01)
            writeUInt64(value)
        }
    }

    public func writeFloat32(_ value: Float) {
        writeUInt32(value.bitPattern)
    }

    public func writeFloat64(_ value: Double) {
        writeUInt64(value.bitPattern)
    }

    public func writeBytes(_ bytes: some Collection<UInt8>) {
        storage.append(contentsOf: bytes)
    }

    public func writeData(_ data: Data) {
        storage.append(contentsOf: data)
    }

    public func setByte(at index: Int, to value: UInt8) {
        storage[index] = value
    }

    public func setBytes(at index: Int, to bytes: some Collection<UInt8>) {
        var idx = index
        for byte in bytes {
            storage[idx] = byte
            idx += 1
        }
    }

    public func checkBound(_ need: Int) throws {
        if cursor + need > storage.count {
            throw ForyError.outOfBounds(cursor: cursor, need: need, length: storage.count)
        }
    }

    public func readUInt8() throws -> UInt8 {
        try checkBound(1)
        defer { cursor += 1 }
        return storage[cursor]
    }

    public func readInt8() throws -> Int8 {
        Int8(bitPattern: try readUInt8())
    }

    public func readUInt16() throws -> UInt16 {
        try checkBound(2)
        let b0 = UInt16(storage[cursor])
        let b1 = UInt16(storage[cursor + 1]) << 8
        cursor += 2
        return b0 | b1
    }

    public func readInt16() throws -> Int16 {
        Int16(bitPattern: try readUInt16())
    }

    public func readUInt32() throws -> UInt32 {
        try checkBound(4)
        let b0 = UInt32(storage[cursor])
        let b1 = UInt32(storage[cursor + 1]) << 8
        let b2 = UInt32(storage[cursor + 2]) << 16
        let b3 = UInt32(storage[cursor + 3]) << 24
        cursor += 4
        return b0 | b1 | b2 | b3
    }

    public func readInt32() throws -> Int32 {
        Int32(bitPattern: try readUInt32())
    }

    public func readUInt64() throws -> UInt64 {
        try checkBound(8)
        let b0 = UInt64(storage[cursor])
        let b1 = UInt64(storage[cursor + 1]) << 8
        let b2 = UInt64(storage[cursor + 2]) << 16
        let b3 = UInt64(storage[cursor + 3]) << 24
        let b4 = UInt64(storage[cursor + 4]) << 32
        let b5 = UInt64(storage[cursor + 5]) << 40
        let b6 = UInt64(storage[cursor + 6]) << 48
        let b7 = UInt64(storage[cursor + 7]) << 56
        cursor += 8
        return b0 | b1 | b2 | b3 | b4 | b5 | b6 | b7
    }

    public func readInt64() throws -> Int64 {
        Int64(bitPattern: try readUInt64())
    }

    public func readVarUInt32() throws -> UInt32 {
        var result: UInt32 = 0
        var shift: UInt32 = 0
        while true {
            let byte = try readUInt8()
            result |= UInt32(byte & 0x7F) << shift
            if (byte & 0x80) == 0 {
                return result
            }
            shift += 7
            if shift > 28 {
                throw ForyError.encodingError("varuint32 overflow")
            }
        }
    }

    public func readVarUInt64() throws -> UInt64 {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        for _ in 0..<8 {
            let byte = try readUInt8()
            result |= UInt64(byte & 0x7F) << shift
            if (byte & 0x80) == 0 {
                return result
            }
            shift += 7
        }
        let last = try readUInt8()
        result |= UInt64(last) << 56
        return result
    }

    public func readVarUInt36Small() throws -> UInt64 {
        let value = try readVarUInt64()
        if value >= (1 << 36) {
            throw ForyError.encodingError("varuint36small overflow")
        }
        return value
    }

    public func readVarInt32() throws -> Int32 {
        let encoded = try readVarUInt32()
        return Int32(bitPattern: (encoded >> 1) ^ (~(encoded & 1) &+ 1))
    }

    public func readVarInt64() throws -> Int64 {
        let encoded = try readVarUInt64()
        return Int64(bitPattern: (encoded >> 1) ^ (~(encoded & 1) &+ 1))
    }

    public func readTaggedInt64() throws -> Int64 {
        let first = try readInt32()
        if (first & 1) == 0 {
            return Int64(first >> 1)
        }
        moveBack(3)
        return try readInt64()
    }

    public func readTaggedUInt64() throws -> UInt64 {
        let first = try readUInt32()
        if (first & 1) == 0 {
            return UInt64(first >> 1)
        }
        moveBack(3)
        return try readUInt64()
    }

    public func readFloat32() throws -> Float {
        Float(bitPattern: try readUInt32())
    }

    public func readFloat64() throws -> Double {
        Double(bitPattern: try readUInt64())
    }

    public func readBytes(count: Int) throws -> [UInt8] {
        try checkBound(count)
        let out = Array(storage[cursor..<(cursor + count)])
        cursor += count
        return out
    }

    public func skip(_ count: Int) throws {
        try checkBound(count)
        cursor += count
    }

    public func toData() -> Data {
        Data(storage)
    }
}
