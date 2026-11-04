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
        writeInt32(try rowEpochDay(value), at: index)
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
        try setOffsetAndSize(at: index, absoluteStart: start, size: rowPadValue(buffer, from: start))
    }

    /// Throws if the row grows past the 32-bit offsets and sizes a slot can hold.
    public func writeBytes(_ value: some ContiguousBytes, at index: Int) throws {
        let start = buffer.count
        value.withUnsafeBytes { buffer.writeBytes($0) }
        try setOffsetAndSize(at: index, absoluteStart: start, size: rowPadValue(buffer, from: start))
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
}

/// Writes one array into a buffer: the element count, the null bitmap, each element at its natural
/// width, then strings, bytes, and nested values. It has the same write methods as ``RowWriter``,
/// taking element indexes.
public struct RowArrayWriter {
    public let elementWidth: Int
    public let buffer: ByteBuffer
    public private(set) var base: Int
    public private(set) var elementCount: Int
    private var headerSize: Int

    public init(element: RowField, buffer: ByteBuffer) {
        self.init(elementWidth: element.dataType.byteWidth ?? 8, buffer: buffer)
    }

    @usableFromInline
    init(elementWidth: Int, buffer: ByteBuffer) {
        self.elementWidth = elementWidth
        self.buffer = buffer
        self.base = buffer.count
        self.elementCount = 0
        self.headerSize = 8
    }

    /// Throws if `count` is negative or the elements would not fit in 2 GiB.
    public mutating func reset(count: Int) throws {
        // Dividing instead of multiplying keeps a huge count from overflowing before it is rejected.
        guard count >= 0, count <= (Int(Int32.max) - 15) / elementWidth else {
            throw rowArrayCountOutOfRange(count, elementWidth: elementWidth)
        }
        headerSize = 8 + rowBitmapWidth(count)
        base = buffer.appendZeros(headerSize + rowRoundToWord(count * elementWidth))
        elementCount = count
        buffer.storeLittleEndian(UInt64(count), at: base)
    }

    public var size: Int {
        buffer.count - base
    }

    public func setNullAt(_ index: Int) {
        let offset = elementOffset(index)
        buffer.storage.withUnsafeMutableBytes { raw in
            rowSetBit(raw, bitmapOffset: base + 8, index: index)
            for byte in offset..<offset + elementWidth {
                raw[byte] = 0
            }
        }
    }

    public func writeBool(_ value: Bool, at index: Int) {
        store(UInt8(value ? 1 : 0), at: index)
    }

    public func writeInt8(_ value: Int8, at index: Int) {
        store(UInt8(bitPattern: value), at: index)
    }

    public func writeInt16(_ value: Int16, at index: Int) {
        store(UInt16(bitPattern: value), at: index)
    }

    public func writeInt32(_ value: Int32, at index: Int) {
        store(UInt32(bitPattern: value), at: index)
    }

    public func writeInt64(_ value: Int64, at index: Int) {
        store(UInt64(bitPattern: value), at: index)
    }

    public func writeFloat32(_ value: Float, at index: Int) {
        store(value.bitPattern, at: index)
    }

    public func writeFloat64(_ value: Double, at index: Int) {
        store(value.bitPattern, at: index)
    }

    public func writeLocalDate(_ value: LocalDate, at index: Int) throws {
        writeInt32(try rowEpochDay(value), at: index)
    }

    public func writeTimestamp(_ value: Date, at index: Int) throws {
        writeInt64(try rowTimestampMicros(value), at: index)
    }

    public func writeDuration(_ value: Duration, at index: Int) throws {
        writeInt64(try rowDurationMicros(value), at: index)
    }

    public func writeString(_ value: String, at index: Int) throws {
        let start = buffer.count
        buffer.writeBytes(value.utf8)
        try setOffsetAndSize(at: index, absoluteStart: start, size: rowPadValue(buffer, from: start))
    }

    public func writeBytes(_ value: some ContiguousBytes, at index: Int) throws {
        let start = buffer.count
        value.withUnsafeBytes { buffer.writeBytes($0) }
        try setOffsetAndSize(at: index, absoluteStart: start, size: rowPadValue(buffer, from: start))
    }

    public func setOffsetAndSize(at index: Int, absoluteStart: Int, size: Int) throws {
        store(try rowEncodeOffsetAndSize(offset: absoluteStart - base, size: size), at: index)
    }

    public func toBytes() -> [UInt8] {
        buffer.copyBytes(start: base, end: buffer.count)
    }

    // Fills every element of an int8 array at once, one byte each.
    func writeInt8Elements(_ bytes: Data) {
        precondition(elementWidth == 1 && bytes.count == elementCount, "\(bytes.count) bytes do not fill the array")
        let start = base + headerSize
        buffer.storage.withUnsafeMutableBytes { raw in
            bytes.withUnsafeBytes { source in
                UnsafeMutableRawBufferPointer(rebasing: raw[start..<(start + source.count)]).copyMemory(from: source)
            }
        }
    }

    private func elementOffset(_ index: Int) -> Int {
        precondition(index >= 0 && index < elementCount, "element index \(index) is outside 0..<\(elementCount)")
        return base + headerSize + index * elementWidth
    }

    // A value wider than the element would overwrite its neighbors or run past the buffer.
    private func store<T: FixedWidthInteger>(_ value: T, at index: Int) {
        precondition(
            MemoryLayout<T>.size <= elementWidth,
            "\(MemoryLayout<T>.size)-byte value written to a \(elementWidth)-byte element"
        )
        buffer.storeLittleEndian(value, at: elementOffset(index))
    }
}

/// Writes one map into a buffer: the size of the keys array, the keys array, then the values array.
/// Call `reset()`, write the keys with a ``RowArrayWriter``, call `finishKeys()`, then write the
/// values with another ``RowArrayWriter`` on the same buffer.
public struct RowMapWriter {
    public let buffer: ByteBuffer
    public private(set) var base: Int

    public init(buffer: ByteBuffer) {
        self.buffer = buffer
        self.base = buffer.count
    }

    public mutating func reset() {
        base = buffer.appendZeros(8)
    }

    public func finishKeys() {
        buffer.storeLittleEndian(UInt64(buffer.count - base - 8), at: base)
    }

    public var size: Int {
        buffer.count - base
    }
}

// Pads the value appended since `start` to a whole word and returns its unpadded size.
private func rowPadValue(_ buffer: ByteBuffer, from start: Int) -> Int {
    let size = buffer.count - start
    _ = buffer.appendZeros(rowRoundToWord(size) - size)
    return size
}

@inline(never)
func rowArrayCountOutOfRange(_ count: Int, elementWidth: Int) -> ForyError {
    .encodingError("array of \(count) elements of \(elementWidth) bytes each cannot be written")
}
