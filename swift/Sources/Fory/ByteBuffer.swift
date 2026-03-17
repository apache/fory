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

    private var dataBridge = Data()

    @inlinable
    public init(capacity: Int = 256) {
        storage = []
        storage.reserveCapacity(capacity)
        cursor = 0
    }

    @inlinable
    public init(data: Data) {
        storage = Array(data)
        cursor = 0
    }

    @inlinable
    public init(bytes: [UInt8]) {
        storage = bytes
        cursor = 0
    }

    @inlinable
    public var count: Int {
        storage.count
    }

    @inlinable
    public var remaining: Int {
        storage.count - cursor
    }

    @usableFromInline
    @inline(__always)
    internal var readableCount: Int {
        storage.count
    }

    @usableFromInline
    @inline(__always)
    internal func byte(at index: Int) -> UInt8 {
        return storage[index]
    }

    @usableFromInline
    @inline(__always)
    internal func copyBytes(start: Int, end: Int) -> [UInt8] {
        let length = end - start
        guard length > 0 else {
            return []
        }
        return Array(storage[start..<end])
    }

    @usableFromInline
    @inline(__always)
    internal func matchesBytes(start: Int, bytes: [UInt8]) -> Bool {
        var index = 0
        while index < bytes.count {
            if storage[start + index] != bytes[index] {
                return false
            }
            index += 1
        }
        return true
    }

    @usableFromInline
    @inline(__always)
    internal func withUnsafeReadableBytes<R>(
        _ body: (UnsafeBufferPointer<UInt8>) throws -> R
    ) rethrows -> R {
        return try storage.withUnsafeBufferPointer(body)
    }

    @inlinable
    public func reserve(_ additional: Int) {
        storage.reserveCapacity(storage.count + additional)
    }

    @inlinable
    public func clear() {
        storage.removeAll(keepingCapacity: true)
        cursor = 0
    }

    @inlinable
    public func reset() {
        clear()
    }

    @inlinable
    public func flip() {
        cursor = 0
    }

    @inlinable
    public func setCursor(_ value: Int) {
        cursor = value
    }

    @inlinable
    public func replace(with data: Data) {
        let dataCount = data.count
        if storage.count < dataCount {
            storage.append(contentsOf: repeatElement(0, count: dataCount - storage.count))
        } else if storage.count > dataCount {
            storage.removeLast(storage.count - dataCount)
        }

        if dataCount > 0 {
            data.withUnsafeBytes { source in
                storage.withUnsafeMutableBufferPointer { destination in
                    guard let sourceBase = source.baseAddress, let destinationBase = destination.baseAddress else {
                        return
                    }
                    UnsafeMutableRawPointer(destinationBase).copyMemory(
                        from: sourceBase,
                        byteCount: dataCount
                    )
                }
            }
        }
        cursor = 0
    }

    @usableFromInline
    @inline(__always)
    internal func swapState(with other: ByteBuffer) {
        swap(&storage, &other.storage)
        swap(&cursor, &other.cursor)
        swap(&dataBridge, &other.dataBridge)
    }

    @usableFromInline
    @inline(__always)
    internal func copyToData() -> Data {
        let byteCount = storage.count
        if dataBridge.count != byteCount {
            dataBridge.count = byteCount
        }
        if byteCount > 0 {
            dataBridge.withUnsafeMutableBytes { destination in
                guard let destinationBase = destination.baseAddress else {
                    return
                }
                storage.withUnsafeBytes { source in
                    guard let sourceBase = source.baseAddress else {
                        return
                    }
                    destinationBase.copyMemory(from: sourceBase, byteCount: byteCount)
                }
            }
        }
        return dataBridge
    }

    @usableFromInline
    @inline(__always)
    internal func materializeData(
        byteCount: Int,
        _ body: (UnsafeMutablePointer<UInt8>) -> Void
    ) -> Data {
        if dataBridge.count != byteCount {
            dataBridge.count = byteCount
        }
        if byteCount > 0 {
            dataBridge.withUnsafeMutableBytes { destination in
                guard let base = destination.baseAddress?.assumingMemoryBound(to: UInt8.self) else {
                    return
                }
                body(base)
            }
        }
        return dataBridge
    }

    @inlinable
    public func getCursor() -> Int {
        cursor
    }

    @inlinable
    public func moveBack(_ amount: Int) {
        cursor -= amount
    }

    @inlinable
    @inline(__always)
    internal func appendLittleEndian<T: FixedWidthInteger>(_ value: T) {
        var little = value.littleEndian
        withUnsafeBytes(of: &little) { raw in
            storage.append(contentsOf: raw)
        }
    }

    @inlinable
    @inline(__always)
    public func writeUInt8(_ value: UInt8) {
        storage.append(value)
    }

    @inlinable
    @inline(__always)
    public func writeInt8(_ value: Int8) {
        storage.append(UInt8(bitPattern: value))
    }

    @inlinable
    @inline(__always)
    public func writeUInt16(_ value: UInt16) {
        appendLittleEndian(value)
    }

    @inlinable
    public func writeInt16(_ value: Int16) {
        writeUInt16(UInt16(bitPattern: value))
    }

    @inlinable
    @inline(__always)
    public func writeUInt32(_ value: UInt32) {
        appendLittleEndian(value)
    }

    @inlinable
    public func writeInt32(_ value: Int32) {
        writeUInt32(UInt32(bitPattern: value))
    }

    @inlinable
    @inline(__always)
    public func writeUInt64(_ value: UInt64) {
        appendLittleEndian(value)
    }

    @inlinable
    public func writeInt64(_ value: Int64) {
        writeUInt64(UInt64(bitPattern: value))
    }

    @inlinable
    @inline(__always)
    public func writeVarUInt32(_ value: UInt32) {
        if value < 0x80 {
            storage.append(UInt8(value))
            return
        }
        if value < 0x4000 {
            storage.append((UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80)
            storage.append(UInt8(truncatingIfNeeded: value >> 7))
            return
        }
        if value < 0x20_0000 {
            storage.append((UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80)
            storage.append(UInt8(truncatingIfNeeded: value >> 14))
            return
        }
        if value < 0x1000_0000 {
            storage.append((UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 14) & 0x7F) | 0x80)
            storage.append(UInt8(truncatingIfNeeded: value >> 21))
            return
        }

        storage.append((UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80)
        storage.append((UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80)
        storage.append((UInt8(truncatingIfNeeded: value >> 14) & 0x7F) | 0x80)
        storage.append((UInt8(truncatingIfNeeded: value >> 21) & 0x7F) | 0x80)
        storage.append(UInt8(truncatingIfNeeded: value >> 28))
    }

    @inlinable
    @inline(__always)
    public func writeVarUInt64(_ value: UInt64) {
        if value < 0x80 {
            storage.append(UInt8(value))
            return
        }
        if value < 0x4000 {
            storage.append((UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80)
            storage.append(UInt8(truncatingIfNeeded: value >> 7))
            return
        }
        if value < 0x20_0000 {
            storage.append((UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80)
            storage.append(UInt8(truncatingIfNeeded: value >> 14))
            return
        }
        if value < 0x1000_0000 {
            storage.append((UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 14) & 0x7F) | 0x80)
            storage.append(UInt8(truncatingIfNeeded: value >> 21))
            return
        }
        if value < 0x8_0000_0000 {
            storage.append((UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 14) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 21) & 0x7F) | 0x80)
            storage.append(UInt8(truncatingIfNeeded: value >> 28))
            return
        }
        if value < 0x400_0000_0000 {
            storage.append((UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 14) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 21) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 28) & 0x7F) | 0x80)
            storage.append(UInt8(truncatingIfNeeded: value >> 35))
            return
        }
        if value < 0x2_0000_0000_0000 {
            storage.append((UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 14) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 21) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 28) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 35) & 0x7F) | 0x80)
            storage.append(UInt8(truncatingIfNeeded: value >> 42))
            return
        }
        if value < 0x100_0000_0000_0000 {
            storage.append((UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 14) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 21) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 28) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 35) & 0x7F) | 0x80)
            storage.append((UInt8(truncatingIfNeeded: value >> 42) & 0x7F) | 0x80)
            storage.append(UInt8(truncatingIfNeeded: value >> 49))
            return
        }

        storage.append((UInt8(truncatingIfNeeded: value) & 0x7F) | 0x80)
        storage.append((UInt8(truncatingIfNeeded: value >> 7) & 0x7F) | 0x80)
        storage.append((UInt8(truncatingIfNeeded: value >> 14) & 0x7F) | 0x80)
        storage.append((UInt8(truncatingIfNeeded: value >> 21) & 0x7F) | 0x80)
        storage.append((UInt8(truncatingIfNeeded: value >> 28) & 0x7F) | 0x80)
        storage.append((UInt8(truncatingIfNeeded: value >> 35) & 0x7F) | 0x80)
        storage.append((UInt8(truncatingIfNeeded: value >> 42) & 0x7F) | 0x80)
        storage.append((UInt8(truncatingIfNeeded: value >> 49) & 0x7F) | 0x80)
        storage.append(UInt8(truncatingIfNeeded: value >> 56))
    }

    @inlinable
    public func writeVarUInt36Small(_ value: UInt64) {
        precondition(value < (1 << 36), "varuint36small overflow")
        writeVarUInt64(value)
    }

    @inlinable
    @inline(__always)
    public func writeVarInt32(_ value: Int32) {
        let zigzag = UInt32(bitPattern: (value << 1) ^ (value >> 31))
        writeVarUInt32(zigzag)
    }

    @inlinable
    @inline(__always)
    public func writeVarInt64(_ value: Int64) {
        let zigzag = UInt64(bitPattern: (value << 1) ^ (value >> 63))
        writeVarUInt64(zigzag)
    }

    @inlinable
    public func writeTaggedInt64(_ value: Int64) {
        if (-1_073_741_824 ... 1_073_741_823).contains(value) {
            writeInt32(Int32(truncatingIfNeeded: value) << 1)
        } else {
            writeUInt8(0x01)
            writeInt64(value)
        }
    }

    @inlinable
    public func writeTaggedUInt64(_ value: UInt64) {
        if value <= UInt64(Int32.max) {
            writeUInt32(UInt32(truncatingIfNeeded: value) << 1)
        } else {
            writeUInt8(0x01)
            writeUInt64(value)
        }
    }

    @inlinable
    public func writeFloat32(_ value: Float) {
        writeUInt32(value.bitPattern)
    }

    @inlinable
    public func writeFloat64(_ value: Double) {
        writeUInt64(value.bitPattern)
    }

    @inlinable
    public func writeBytes(_ bytes: some Collection<UInt8>) {
        storage.append(contentsOf: bytes)
    }

    @inlinable
    @inline(__always)
    public func writeBytes(_ bytes: [UInt8]) {
        storage.append(contentsOf: bytes)
    }

    @inlinable
    public func writeBytes(_ bytes: UnsafeRawBufferPointer) {
        storage.append(contentsOf: bytes)
    }

    @inlinable
    public func writeData(_ data: Data) {
        storage.append(contentsOf: data)
    }

    @inlinable
    public func setByte(at index: Int, to value: UInt8) {
        storage[index] = value
    }

    @inlinable
    public func setBytes(at index: Int, to bytes: some Collection<UInt8>) {
        var idx = index
        for byte in bytes {
            storage[idx] = byte
            idx += 1
        }
    }

    @inlinable
    @inline(__always)
    public func checkBound(_ need: Int) throws {
        let length = readableCount
        if cursor + need > length {
            throw ForyError.outOfBounds(cursor: cursor, need: need, length: length)
        }
    }

    @inlinable
    public func readBytes(into destination: UnsafeMutableRawBufferPointer) throws {
        try checkBound(destination.count)
        guard destination.count > 0, let destinationBase = destination.baseAddress else {
            return
        }
        withUnsafeReadableBytes { rawBytes in
            guard let sourceBase = rawBytes.baseAddress else {
                return
            }
            destinationBase.copyMemory(from: sourceBase.advanced(by: cursor), byteCount: destination.count)
        }
        cursor += destination.count
    }

    @inlinable
    @inline(__always)
    public func readUInt8() throws -> UInt8 {
        try checkBound(1)
        defer { cursor += 1 }
        return byte(at: cursor)
    }

    @inlinable
    @inline(__always)
    public func readInt8() throws -> Int8 {
        Int8(bitPattern: try readUInt8())
    }

    @inlinable
    @inline(__always)
    public func readUInt16() throws -> UInt16 {
        try checkBound(2)
        let b0 = UInt16(byte(at: cursor))
        let b1 = UInt16(byte(at: cursor + 1)) << 8
        cursor += 2
        return b0 | b1
    }

    @inlinable
    public func readInt16() throws -> Int16 {
        Int16(bitPattern: try readUInt16())
    }

    @inlinable
    @inline(__always)
    public func readUInt32() throws -> UInt32 {
        try checkBound(4)
        let b0 = UInt32(byte(at: cursor))
        let b1 = UInt32(byte(at: cursor + 1)) << 8
        let b2 = UInt32(byte(at: cursor + 2)) << 16
        let b3 = UInt32(byte(at: cursor + 3)) << 24
        cursor += 4
        return b0 | b1 | b2 | b3
    }

    @inlinable
    public func readInt32() throws -> Int32 {
        Int32(bitPattern: try readUInt32())
    }

    @inlinable
    @inline(__always)
    public func readUInt64() throws -> UInt64 {
        try checkBound(8)
        let b0 = UInt64(byte(at: cursor))
        let b1 = UInt64(byte(at: cursor + 1)) << 8
        let b2 = UInt64(byte(at: cursor + 2)) << 16
        let b3 = UInt64(byte(at: cursor + 3)) << 24
        let b4 = UInt64(byte(at: cursor + 4)) << 32
        let b5 = UInt64(byte(at: cursor + 5)) << 40
        let b6 = UInt64(byte(at: cursor + 6)) << 48
        let b7 = UInt64(byte(at: cursor + 7)) << 56
        cursor += 8
        return b0 | b1 | b2 | b3 | b4 | b5 | b6 | b7
    }

    @inlinable
    public func readInt64() throws -> Int64 {
        Int64(bitPattern: try readUInt64())
    }

    @inlinable
    @inline(__always)
    public func readVarUInt32() throws -> UInt32 {
        let available = readableCount - cursor
        if available >= 5 {
            let offset = cursor
            let b0 = byte(at: offset)
            if b0 < 0x80 {
                cursor = offset + 1
                return UInt32(b0)
            }

            let b1 = byte(at: offset + 1)
            if b1 < 0x80 {
                cursor = offset + 2
                return UInt32(b0 & 0x7F) | (UInt32(b1) << 7)
            }

            let b2 = byte(at: offset + 2)
            if b2 < 0x80 {
                cursor = offset + 3
                return UInt32(b0 & 0x7F) | (UInt32(b1 & 0x7F) << 7) | (UInt32(b2) << 14)
            }

            let b3 = byte(at: offset + 3)
            if b3 < 0x80 {
                cursor = offset + 4
                return UInt32(b0 & 0x7F) |
                    (UInt32(b1 & 0x7F) << 7) |
                    (UInt32(b2 & 0x7F) << 14) |
                    (UInt32(b3) << 21)
            }

            let b4 = byte(at: offset + 4)
            if b4 >= 0x80 {
                throw ForyError.encodingError("varuint32 overflow")
            }
            cursor = offset + 5
            return UInt32(b0 & 0x7F) |
                (UInt32(b1 & 0x7F) << 7) |
                (UInt32(b2 & 0x7F) << 14) |
                (UInt32(b3 & 0x7F) << 21) |
                (UInt32(b4) << 28)
        }

        try checkBound(1)
        let b0 = byte(at: cursor)
        if b0 < 0x80 {
            cursor += 1
            return UInt32(b0)
        }

        try checkBound(2)
        let b1 = byte(at: cursor + 1)
        if b1 < 0x80 {
            cursor += 2
            return UInt32(b0 & 0x7F) | (UInt32(b1) << 7)
        }

        try checkBound(3)
        let b2 = byte(at: cursor + 2)
        if b2 < 0x80 {
            cursor += 3
            return UInt32(b0 & 0x7F) | (UInt32(b1 & 0x7F) << 7) | (UInt32(b2) << 14)
        }

        try checkBound(4)
        let b3 = byte(at: cursor + 3)
        if b3 < 0x80 {
            cursor += 4
            return UInt32(b0 & 0x7F) |
                (UInt32(b1 & 0x7F) << 7) |
                (UInt32(b2 & 0x7F) << 14) |
                (UInt32(b3) << 21)
        }

        try checkBound(5)
        let b4 = byte(at: cursor + 4)
        if b4 >= 0x80 {
            throw ForyError.encodingError("varuint32 overflow")
        }
        cursor += 5
        return UInt32(b0 & 0x7F) |
            (UInt32(b1 & 0x7F) << 7) |
            (UInt32(b2 & 0x7F) << 14) |
            (UInt32(b3 & 0x7F) << 21) |
            (UInt32(b4) << 28)
    }

    @inlinable
    @inline(__always)
    public func readVarUInt64() throws -> UInt64 {
        let available = readableCount - cursor
        if available >= 9 {
            let offset = cursor
            let b0 = byte(at: offset)
            if b0 < 0x80 {
                cursor = offset + 1
                return UInt64(b0)
            }

            let b1 = byte(at: offset + 1)
            if b1 < 0x80 {
                cursor = offset + 2
                return UInt64(b0 & 0x7F) | (UInt64(b1) << 7)
            }

            let b2 = byte(at: offset + 2)
            if b2 < 0x80 {
                cursor = offset + 3
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2) << 14)
            }

            let b3 = byte(at: offset + 3)
            if b3 < 0x80 {
                cursor = offset + 4
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2 & 0x7F) << 14) |
                    (UInt64(b3) << 21)
            }

            let b4 = byte(at: offset + 4)
            if b4 < 0x80 {
                cursor = offset + 5
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2 & 0x7F) << 14) |
                    (UInt64(b3 & 0x7F) << 21) |
                    (UInt64(b4) << 28)
            }

            let b5 = byte(at: offset + 5)
            if b5 < 0x80 {
                cursor = offset + 6
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2 & 0x7F) << 14) |
                    (UInt64(b3 & 0x7F) << 21) |
                    (UInt64(b4 & 0x7F) << 28) |
                    (UInt64(b5) << 35)
            }

            let b6 = byte(at: offset + 6)
            if b6 < 0x80 {
                cursor = offset + 7
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2 & 0x7F) << 14) |
                    (UInt64(b3 & 0x7F) << 21) |
                    (UInt64(b4 & 0x7F) << 28) |
                    (UInt64(b5 & 0x7F) << 35) |
                    (UInt64(b6) << 42)
            }

            let b7 = byte(at: offset + 7)
            if b7 < 0x80 {
                cursor = offset + 8
                return UInt64(b0 & 0x7F) |
                    (UInt64(b1 & 0x7F) << 7) |
                    (UInt64(b2 & 0x7F) << 14) |
                    (UInt64(b3 & 0x7F) << 21) |
                    (UInt64(b4 & 0x7F) << 28) |
                    (UInt64(b5 & 0x7F) << 35) |
                    (UInt64(b6 & 0x7F) << 42) |
                    (UInt64(b7) << 49)
            }

            let b8 = byte(at: offset + 8)
            cursor = offset + 9
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

        try checkBound(1)
        let b0 = byte(at: cursor)
        if b0 < 0x80 {
            cursor += 1
            return UInt64(b0)
        }

        try checkBound(2)
        let b1 = byte(at: cursor + 1)
        if b1 < 0x80 {
            cursor += 2
            return UInt64(b0 & 0x7F) | (UInt64(b1) << 7)
        }

        try checkBound(3)
        let b2 = byte(at: cursor + 2)
        if b2 < 0x80 {
            cursor += 3
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2) << 14)
        }

        try checkBound(4)
        let b3 = byte(at: cursor + 3)
        if b3 < 0x80 {
            cursor += 4
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3) << 21)
        }

        try checkBound(5)
        let b4 = byte(at: cursor + 4)
        if b4 < 0x80 {
            cursor += 5
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3 & 0x7F) << 21) |
                (UInt64(b4) << 28)
        }

        try checkBound(6)
        let b5 = byte(at: cursor + 5)
        if b5 < 0x80 {
            cursor += 6
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3 & 0x7F) << 21) |
                (UInt64(b4 & 0x7F) << 28) |
                (UInt64(b5) << 35)
        }

        try checkBound(7)
        let b6 = byte(at: cursor + 6)
        if b6 < 0x80 {
            cursor += 7
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3 & 0x7F) << 21) |
                (UInt64(b4 & 0x7F) << 28) |
                (UInt64(b5 & 0x7F) << 35) |
                (UInt64(b6) << 42)
        }

        try checkBound(8)
        let b7 = byte(at: cursor + 7)
        if b7 < 0x80 {
            cursor += 8
            return UInt64(b0 & 0x7F) |
                (UInt64(b1 & 0x7F) << 7) |
                (UInt64(b2 & 0x7F) << 14) |
                (UInt64(b3 & 0x7F) << 21) |
                (UInt64(b4 & 0x7F) << 28) |
                (UInt64(b5 & 0x7F) << 35) |
                (UInt64(b6 & 0x7F) << 42) |
                (UInt64(b7) << 49)
        }

        try checkBound(9)
        let b8 = byte(at: cursor + 8)
        cursor += 9
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
    public func readVarUInt36Small() throws -> UInt64 {
        let value = try readVarUInt64()
        if value >= (1 << 36) {
            throw ForyError.encodingError("varuint36small overflow")
        }
        return value
    }

    @inlinable
    @inline(__always)
    public func readVarInt32() throws -> Int32 {
        let encoded = try readVarUInt32()
        return Int32(bitPattern: (encoded >> 1) ^ (~(encoded & 1) &+ 1))
    }

    @inlinable
    @inline(__always)
    public func readVarInt64() throws -> Int64 {
        let encoded = try readVarUInt64()
        return Int64(bitPattern: (encoded >> 1) ^ (~(encoded & 1) &+ 1))
    }

    @inlinable
    public func readTaggedInt64() throws -> Int64 {
        let first = try readInt32()
        if (first & 1) == 0 {
            return Int64(first >> 1)
        }
        moveBack(3)
        return try readInt64()
    }

    @inlinable
    public func readTaggedUInt64() throws -> UInt64 {
        let first = try readUInt32()
        if (first & 1) == 0 {
            return UInt64(first >> 1)
        }
        moveBack(3)
        return try readUInt64()
    }

    @inlinable
    public func readFloat32() throws -> Float {
        Float(bitPattern: try readUInt32())
    }

    @inlinable
    public func readFloat64() throws -> Double {
        Double(bitPattern: try readUInt64())
    }

    @inlinable
    public func readBytes(count: Int) throws -> [UInt8] {
        if count == 0 {
            return []
        }
        return try [UInt8](unsafeUninitializedCapacity: count) { destination, initializedCount in
            try readBytes(into: UnsafeMutableRawBufferPointer(destination))
            initializedCount = count
        }
    }

    @inlinable
    @inline(__always)
    public func readUTF8String(count: Int) throws -> String {
        try checkBound(count)
        if count == 0 {
            return ""
        }
        let start = cursor
        let end = start + count
        cursor = end
        let decoded = withUnsafeReadableBytes { buffer -> String? in
            guard let base = buffer.baseAddress else {
                return nil
            }
            let utf8Bytes = UnsafeBufferPointer(start: base.advanced(by: start), count: count)
            return String(bytes: utf8Bytes, encoding: .utf8)
        }
        guard let decoded else {
            throw ForyError.invalidData("invalid UTF-8 sequence")
        }
        return decoded
    }

    @inlinable
    public func skip(_ count: Int) throws {
        try checkBound(count)
        cursor += count
    }

    @inlinable
    public func toData() -> Data {
        return storage.withUnsafeBytes { rawBytes in
            guard let baseAddress = rawBytes.baseAddress else {
                return Data()
            }
            return Data(bytes: baseAddress, count: rawBytes.count)
        }
    }
}
