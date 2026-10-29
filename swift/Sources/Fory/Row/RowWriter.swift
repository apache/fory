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

/// Writes one row into a buffer: the null bitmap, one slot per field, then strings, bytes, and
/// nested values after the slots. Call `reset()` before each row. An index outside
/// `0..<fields.count` stops the program, like an array subscript.
public struct RowWriter {
    public let fields: [RowField]
    public let buffer: ByteBuffer
    // Where the current row starts in the buffer. Offsets in slots count from here, so a nested row
    // written into its parent's buffer is still a complete row on its own.
    public private(set) var base: Int
    private let bitmapWidth: Int

    public init(schema: RowSchema, buffer: ByteBuffer = ByteBuffer()) {
        self.init(fields: schema.fields, buffer: buffer)
    }

    init(fields: [RowField], buffer: ByteBuffer) {
        self.fields = fields
        self.buffer = buffer
        self.base = buffer.count
        self.bitmapWidth = rowBitmapWidth(fields.count)
    }

    /// Starts a new row at the end of the buffer with every field non-null and every slot zero.
    public mutating func reset() {
        base = buffer.appendZeros(bitmapWidth + fields.count * 8)
    }

    public var size: Int {
        buffer.count - base
    }

    /// Marks the field null and zeroes its slot.
    public func setNullAt(_ index: Int) {
        let slot = slotOffset(index)
        buffer.storage.withUnsafeMutableBytes { rowSetBit($0, bitmapOffset: base, index: index) }
        buffer.storeLittleEndian(UInt64(0), at: slot)
    }

    // A narrow value fills only the low bytes of its slot; reset already zeroed the rest.
    public func writeBool(_ value: Bool, at index: Int) {
        buffer.storeLittleEndian(UInt8(value ? 1 : 0), at: slotOffset(index))
    }

    public func writeInt8(_ value: Int8, at index: Int) {
        buffer.storeLittleEndian(UInt8(bitPattern: value), at: slotOffset(index))
    }

    public func writeInt16(_ value: Int16, at index: Int) {
        buffer.storeLittleEndian(UInt16(bitPattern: value), at: slotOffset(index))
    }

    public func writeInt32(_ value: Int32, at index: Int) {
        buffer.storeLittleEndian(UInt32(bitPattern: value), at: slotOffset(index))
    }

    public func writeInt64(_ value: Int64, at index: Int) {
        buffer.storeLittleEndian(UInt64(bitPattern: value), at: slotOffset(index))
    }

    public func writeFloat32(_ value: Float, at index: Int) {
        buffer.storeLittleEndian(value.bitPattern, at: slotOffset(index))
    }

    public func writeFloat64(_ value: Double, at index: Int) {
        buffer.storeLittleEndian(value.bitPattern, at: slotOffset(index))
    }

    /// Throws if the date is too far from 1970 to fit in 32-bit days.
    public func writeLocalDate(_ value: LocalDate, at index: Int) throws {
        guard let days = Int32(exactly: value.epochDay) else {
            throw rowDateOutOfRange(value.epochDay)
        }
        writeInt32(days, at: index)
    }

    /// Stores the date rounded to the nearest microsecond. Throws if it does not fit in 64-bit
    /// microseconds from 1970.
    public func writeTimestamp(_ value: Date, at index: Int) throws {
        writeInt64(try rowTimestampMicros(value), at: index)
    }

    /// Stores the duration truncated toward zero to whole microseconds. Throws if it does not fit
    /// in 64-bit microseconds.
    public func writeDuration(_ value: Duration, at index: Int) throws {
        writeInt64(try rowDurationMicros(value), at: index)
    }

    /// Throws if the row grows past the 32-bit offsets and sizes a slot can hold.
    public func writeString(_ value: String, at index: Int) throws {
        let start = buffer.count
        buffer.writeBytes(value.utf8)
        try finishValue(at: index, start: start)
    }

    /// Throws if the row grows past the 32-bit offsets and sizes a slot can hold.
    public func writeBytes(_ value: some ContiguousBytes, at index: Int) throws {
        let start = buffer.count
        value.withUnsafeBytes { buffer.writeBytes($0) }
        try finishValue(at: index, start: start)
    }

    /// Points slot `index` at `size` bytes that already sit in the buffer at `absoluteStart`.
    public func setOffsetAndSize(at index: Int, absoluteStart: Int, size: Int) throws {
        let slot = slotOffset(index)
        buffer.storeLittleEndian(try rowEncodeOffsetAndSize(offset: absoluteStart - base, size: size), at: slot)
    }

    /// A copy of the current row's bytes.
    public func toBytes() -> [UInt8] {
        buffer.copyBytes(start: base, end: buffer.count)
    }

    private func slotOffset(_ index: Int) -> Int {
        precondition(index >= 0 && index < fields.count, "field index \(index) is outside 0..<\(fields.count)")
        return base + bitmapWidth + index * 8
    }

    // Pads the value that was just appended from `start` to a whole word and points the slot at it.
    // An empty value adds no padding, so its slot points at the current end with size 0.
    private func finishValue(at index: Int, start: Int) throws {
        let size = buffer.count - start
        _ = buffer.appendZeros(rowRoundToWord(size) - size)
        try setOffsetAndSize(at: index, absoluteStart: start, size: size)
    }
}

@inline(never)
func rowDateOutOfRange(_ epochDay: Int64) -> ForyError {
    .encodingError("date \(epochDay) days from 1970 does not fit in 32 bits")
}
