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

/// A read-only view of one row's bytes. Each getter reads its field in place and returns nil when the
/// field is null. An index outside `0..<fieldCount` stops the program, like an array subscript.
public struct Row {
    public let fields: [RowField]
    public let sizeInBytes: Int
    // A nested row shares its parent's array, so every position is counted from `base`.
    private let bytes: [UInt8]
    private let base: Int
    private let bitmapWidth: Int

    public init(schema: RowSchema, bytes: [UInt8]) throws {
        try self.init(fields: schema.fields, bytes: bytes, base: 0, count: bytes.count)
    }

    // The bitmap and slots are checked once here, therefore fixed-width getters can read them without
    // checking again.
    init(fields: [RowField], bytes: [UInt8], base: Int, count: Int) throws {
        precondition(base >= 0 && count >= 0 && count <= bytes.count - base, "row range is outside its buffer")
        let bitmapWidth = rowBitmapWidth(fields.count)
        let fixedSize = bitmapWidth + fields.count * 8
        guard count >= fixedSize else {
            throw rowTooShort(count: count, fixedSize: fixedSize)
        }
        self.fields = fields
        self.sizeInBytes = count
        self.bytes = bytes
        self.base = base
        self.bitmapWidth = bitmapWidth
    }

    public var fieldCount: Int {
        fields.count
    }

    public func isNull(at index: Int) -> Bool {
        precondition(index >= 0 && index < fields.count, "field index \(index) is outside 0..<\(fields.count)")
        return bytes.withUnsafeBytes { rowIsBitSet($0, bitmapOffset: base, index: index) }
    }

    public func bool(at index: Int) -> Bool? {
        isNull(at: index) ? nil : loadSlot(UInt8.self, at: index) != 0
    }

    public func int8(at index: Int) -> Int8? {
        isNull(at: index) ? nil : Int8(bitPattern: loadSlot(UInt8.self, at: index))
    }

    public func int16(at index: Int) -> Int16? {
        isNull(at: index) ? nil : Int16(bitPattern: loadSlot(UInt16.self, at: index))
    }

    public func int32(at index: Int) -> Int32? {
        isNull(at: index) ? nil : Int32(bitPattern: loadSlot(UInt32.self, at: index))
    }

    public func int64(at index: Int) -> Int64? {
        isNull(at: index) ? nil : Int64(bitPattern: loadSlot(UInt64.self, at: index))
    }

    public func float32(at index: Int) -> Float? {
        isNull(at: index) ? nil : Float(bitPattern: loadSlot(UInt32.self, at: index))
    }

    public func float64(at index: Int) -> Double? {
        isNull(at: index) ? nil : Double(bitPattern: loadSlot(UInt64.self, at: index))
    }

    public func localDate(at index: Int) -> LocalDate? {
        int32(at: index).map { LocalDate(epochDay: Int64($0)) }
    }

    public func timestamp(at index: Int) -> Date? {
        int64(at: index).map(rowDate(micros:))
    }

    public func duration(at index: Int) -> Duration? {
        int64(at: index).map { .microseconds($0) }
    }

    /// Throws if the stored offset and size point outside this row, or the bytes are not valid UTF-8.
    public func string(at index: Int) throws -> String? {
        try valueRange(at: index).map { try rowString(bytes, in: $0) }
    }

    /// Throws if the stored offset and size point outside this row.
    public func binary(at index: Int) throws -> [UInt8]? {
        guard let range = try valueRange(at: index) else {
            return nil
        }
        return Array(bytes[range])
    }

    /// The field must have a struct type.
    public func row(at index: Int) throws -> Row? {
        guard case .struct(let childFields) = fields[index].dataType else {
            preconditionFailure("field \(index) has type \(fields[index].dataType), not a struct")
        }
        guard let range = try valueRange(at: index) else {
            return nil
        }
        return try Row(fields: childFields, bytes: bytes, base: range.lowerBound, count: range.count)
    }

    /// The field must have a list type.
    public func array(at index: Int) throws -> RowArray? {
        guard case .list(let element) = fields[index].dataType else {
            preconditionFailure("field \(index) has type \(fields[index].dataType), not a list")
        }
        guard let range = try valueRange(at: index) else {
            return nil
        }
        return try RowArray(element: element, bytes: bytes, base: range.lowerBound, count: range.count)
    }

    /// The field must have a map type.
    public func map(at index: Int) throws -> RowMap? {
        guard case .map(let key, let value) = fields[index].dataType else {
            preconditionFailure("field \(index) has type \(fields[index].dataType), not a map")
        }
        guard let range = try valueRange(at: index) else {
            return nil
        }
        return try RowMap(key: key, value: value, bytes: bytes, base: range.lowerBound, count: range.count)
    }

    /// A copy of only this row's bytes, even when it sits inside a larger buffer.
    public func toBytes() -> [UInt8] {
        Array(bytes[base..<(base + sizeInBytes)])
    }

    @inline(__always)
    private func loadSlot<T: FixedWidthInteger>(_: T.Type, at index: Int) -> T {
        rowLoad(T.self, bytes, at: base + bitmapWidth + index * 8)
    }

    // The null bit is checked before the slot is read, because a null field's slot can hold anything.
    private func valueRange(at index: Int) throws -> Range<Int>? {
        if isNull(at: index) {
            return nil
        }
        let ticket = rowDecodeOffsetAndSize(loadSlot(UInt64.self, at: index))
        return try rowCheckedRange(offset: ticket.offset, size: ticket.size, regionBase: base, regionCount: sizeInBytes)
    }
}

/// A read-only view of one array's bytes, with the same getters as ``Row`` taking element indexes.
public struct RowArray {
    public let element: RowField
    public let elementCount: Int
    public let sizeInBytes: Int
    private let bytes: [UInt8]
    private let base: Int
    private let headerSize: Int
    private let elementWidth: Int

    // An array is an 8-byte element count, a null bitmap, then one slot per element. A fixed-width
    // element takes only its own width, so three int8 values take three bytes, not three slots of 8.
    init(element: RowField, bytes: [UInt8], base: Int, count: Int) throws {
        precondition(base >= 0 && count >= 0 && count <= bytes.count - base, "array range is outside its buffer")
        guard count >= 8 else {
            throw rowArrayTooShort(count: count)
        }
        // Every element takes at least one byte, so a count above the byte count is rejected before
        // any arithmetic that a forged count could overflow.
        let declaredCount = rowLoad(UInt64.self, bytes, at: base)
        guard declaredCount <= UInt64(count) else {
            throw rowArrayCountTooLarge(elementCount: declaredCount, count: count)
        }
        let elementCount = Int(declaredCount)
        let headerSize = 8 + rowBitmapWidth(elementCount)
        let elementWidth = element.dataType.byteWidth ?? 8
        let (elementsSize, multiplyOverflow) = elementCount.multipliedReportingOverflow(by: elementWidth)
        let (fixedSize, addOverflow) = headerSize.addingReportingOverflow(elementsSize)
        guard !multiplyOverflow, !addOverflow, fixedSize <= count else {
            throw rowArrayCountTooLarge(elementCount: declaredCount, count: count)
        }
        self.element = element
        self.elementCount = elementCount
        self.sizeInBytes = count
        self.bytes = bytes
        self.base = base
        self.headerSize = headerSize
        self.elementWidth = elementWidth
    }

    public func isNull(at index: Int) -> Bool {
        precondition(index >= 0 && index < elementCount, "element index \(index) is outside 0..<\(elementCount)")
        return bytes.withUnsafeBytes { rowIsBitSet($0, bitmapOffset: base + 8, index: index) }
    }

    public func bool(at index: Int) -> Bool? {
        isNull(at: index) ? nil : loadSlot(UInt8.self, at: index) != 0
    }

    public func int8(at index: Int) -> Int8? {
        isNull(at: index) ? nil : Int8(bitPattern: loadSlot(UInt8.self, at: index))
    }

    public func int16(at index: Int) -> Int16? {
        isNull(at: index) ? nil : Int16(bitPattern: loadSlot(UInt16.self, at: index))
    }

    public func int32(at index: Int) -> Int32? {
        isNull(at: index) ? nil : Int32(bitPattern: loadSlot(UInt32.self, at: index))
    }

    public func int64(at index: Int) -> Int64? {
        isNull(at: index) ? nil : Int64(bitPattern: loadSlot(UInt64.self, at: index))
    }

    public func float32(at index: Int) -> Float? {
        isNull(at: index) ? nil : Float(bitPattern: loadSlot(UInt32.self, at: index))
    }

    public func float64(at index: Int) -> Double? {
        isNull(at: index) ? nil : Double(bitPattern: loadSlot(UInt64.self, at: index))
    }

    public func localDate(at index: Int) -> LocalDate? {
        int32(at: index).map { LocalDate(epochDay: Int64($0)) }
    }

    public func timestamp(at index: Int) -> Date? {
        int64(at: index).map(rowDate(micros:))
    }

    public func duration(at index: Int) -> Duration? {
        int64(at: index).map { .microseconds($0) }
    }

    public func string(at index: Int) throws -> String? {
        try valueRange(at: index).map { try rowString(bytes, in: $0) }
    }

    public func binary(at index: Int) throws -> [UInt8]? {
        guard let range = try valueRange(at: index) else {
            return nil
        }
        return Array(bytes[range])
    }

    public func row(at index: Int) throws -> Row? {
        guard case .struct(let childFields) = element.dataType else {
            preconditionFailure("array element type is \(element.dataType), not a struct")
        }
        guard let range = try valueRange(at: index) else {
            return nil
        }
        return try Row(fields: childFields, bytes: bytes, base: range.lowerBound, count: range.count)
    }

    public func array(at index: Int) throws -> RowArray? {
        guard case .list(let childElement) = element.dataType else {
            preconditionFailure("array element type is \(element.dataType), not a list")
        }
        guard let range = try valueRange(at: index) else {
            return nil
        }
        return try RowArray(element: childElement, bytes: bytes, base: range.lowerBound, count: range.count)
    }

    public func map(at index: Int) throws -> RowMap? {
        guard case .map(let key, let value) = element.dataType else {
            preconditionFailure("array element type is \(element.dataType), not a map")
        }
        guard let range = try valueRange(at: index) else {
            return nil
        }
        return try RowMap(key: key, value: value, bytes: bytes, base: range.lowerBound, count: range.count)
    }

    public func toBytes() -> [UInt8] {
        Array(bytes[base..<(base + sizeInBytes)])
    }

    // Slots here can be narrower than 8 bytes, so a getter that does not match the element type
    // could read past the last slot and out of the buffer. That is a bug in the caller, so it stops.
    @inline(__always)
    private func loadSlot<T: FixedWidthInteger>(_: T.Type, at index: Int) -> T {
        precondition(MemoryLayout<T>.size <= elementWidth, "array element type is \(element.dataType)")
        return rowLoad(T.self, bytes, at: base + headerSize + index * elementWidth)
    }

    private func valueRange(at index: Int) throws -> Range<Int>? {
        if isNull(at: index) {
            return nil
        }
        let ticket = rowDecodeOffsetAndSize(loadSlot(UInt64.self, at: index))
        return try rowCheckedRange(offset: ticket.offset, size: ticket.size, regionBase: base, regionCount: sizeInBytes)
    }
}

/// A read-only view of one map: an array of keys and an array of values, paired by index.
public struct RowMap {
    public let keys: RowArray
    public let values: RowArray

    // A map is the byte size of its keys array, then the keys array, then the values array. Each
    // array counts its offsets from its own start, not from the map's.
    init(key: RowField, value: RowField, bytes: [UInt8], base: Int, count: Int) throws {
        precondition(base >= 0 && count >= 0 && count <= bytes.count - base, "map range is outside its buffer")
        guard count >= 8 else {
            throw rowMapTooShort(count: count)
        }
        let declaredKeysSize = rowLoad(UInt64.self, bytes, at: base)
        guard declaredKeysSize <= UInt64(count - 8) else {
            throw rowMapKeysTooLarge(keysSize: declaredKeysSize, count: count)
        }
        let keysSize = Int(declaredKeysSize)
        let keys = try RowArray(element: key, bytes: bytes, base: base + 8, count: keysSize)
        let values = try RowArray(element: value, bytes: bytes, base: base + 8 + keysSize, count: count - 8 - keysSize)
        // Readers walk both arrays with one index, so unequal counts would run past the shorter one.
        guard keys.elementCount == values.elementCount else {
            throw rowMapCountMismatch(keyCount: keys.elementCount, valueCount: values.elementCount)
        }
        self.keys = keys
        self.values = values
    }

    public var count: Int {
        keys.elementCount
    }
}

// Reads the little-endian integer at an absolute position that the caller has already checked.
@inline(__always)
private func rowLoad<T: FixedWidthInteger>(_: T.Type, _ bytes: [UInt8], at offset: Int) -> T {
    bytes.withUnsafeBytes { T(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: T.self)) }
}

private func rowString(_ bytes: [UInt8], in range: Range<Int>) throws -> String {
    let decoded = bytes.withUnsafeBufferPointer { decodeValidatedUTF8(UnsafeBufferPointer(rebasing: $0[range])) }
    guard let decoded else {
        throw rowInvalidUTF8()
    }
    return decoded
}

@inline(never)
func rowTooShort(count: Int, fixedSize: Int) -> ForyError {
    .invalidData("row of \(count) bytes is shorter than its \(fixedSize)-byte fixed region")
}

@inline(never)
func rowInvalidUTF8() -> ForyError {
    .invalidData("invalid UTF-8 sequence")
}

@inline(never)
func rowArrayTooShort(count: Int) -> ForyError {
    .invalidData("array of \(count) bytes is shorter than its 8-byte element count")
}

@inline(never)
func rowArrayCountTooLarge(elementCount: UInt64, count: Int) -> ForyError {
    .invalidData("array declares \(elementCount) elements but holds only \(count) bytes")
}

@inline(never)
func rowMapTooShort(count: Int) -> ForyError {
    .invalidData("map of \(count) bytes is shorter than its 8-byte keys size")
}

@inline(never)
func rowMapKeysTooLarge(keysSize: UInt64, count: Int) -> ForyError {
    .invalidData("map keys array of \(keysSize) bytes does not fit in a \(count)-byte map")
}

@inline(never)
func rowMapCountMismatch(keyCount: Int, valueCount: Int) -> ForyError {
    .invalidData("map has \(keyCount) keys but \(valueCount) values")
}
