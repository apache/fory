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

@usableFromInline
enum CollectionHeader {
    @usableFromInline static let trackingRef: UInt8 = 0b0000_0001
    @usableFromInline static let hasNull: UInt8 = 0b0000_0010
    @usableFromInline static let declaredElementType: UInt8 = 0b0000_0100
    @usableFromInline static let sameType: UInt8 = 0b0000_1000
}

@usableFromInline
enum MapHeader {
    @usableFromInline static let trackingKeyRef: UInt8 = 0b0000_0001
    @usableFromInline static let keyNull: UInt8 = 0b0000_0010
    @usableFromInline static let declaredKeyType: UInt8 = 0b0000_0100

    @usableFromInline static let trackingValueRef: UInt8 = 0b0000_1000
    @usableFromInline static let valueNull: UInt8 = 0b0001_0000
    @usableFromInline static let declaredValueType: UInt8 = 0b0010_0000
}

@usableFromInline
internal let storedReferenceBytes = 4

@usableFromInline
internal let unbackedContainerCheckInterval = 1024

@usableFromInline
@inline(__always)
internal func ensureContainerAllocation(
    _ context: ReadContext,
    count: Int,
    itemReadAlwaysAdvances: Bool,
    label: String
) throws {
    if itemReadAlwaysAdvances {
        try context.ensureRemainingBytes(count, label: label)
    } else {
        let allowance = context.remainingUnbackedContainerItems
        try context.ensureRemainingBytes(count > allowance ? count - allowance : 0, label: label)
    }
}

@usableFromInline
@inline(__always)
internal func settleUnbackedContainerItems(
    _ context: ReadContext,
    completed: Int,
    startCursor: Int
) throws {
    let consumed = context.buffer.cursor - startCursor
    if completed > consumed {
        try context.reserveUnbackedContainerItems(completed - consumed)
    }
}

@usableFromInline
@inline(__always)
internal func fieldReadAlwaysAdvances<Codec: FieldCodec>(
    _ codec: Codec.Type,
    declared: Bool,
    typeInfo: TypeInfo?
) -> Bool {
    if codec.staticTypeId == .unknown {
        return typeInfo?.readDataAlwaysAdvances == true
    }
    // A static reader returns nil only after validating the exact wire type or
    // the exact local Struct header; a remote compatible schema returns TypeInfo.
    return codec.readDataAlwaysAdvances
        && (declared || typeInfo?.readDataAlwaysAdvances ?? true)
}

@inlinable
@inline(__always)
internal func storedElementBytes<Element: Serializer>(_ type: Element.Type) -> Int {
    if type.staticTypeId == .unknown {
        return max(1, MemoryLayout<Element.Target>.stride)
    }
    return type.isRefType ? storedReferenceBytes : max(1, MemoryLayout<Element.Target>.stride)
}

@inlinable
@inline(__always)
internal func storedOwnerBytes<T>(_ type: T.Type) -> Int {
    max(1, MemoryLayout<T>.stride)
}

@usableFromInline
@inline(__always)
internal func reserveGraphElements(
    _ context: ReadContext,
    ownerBytes: Int,
    count: Int,
    elementBytes: Int
) throws {
    if ownerBytes < 0 || count < 0 || elementBytes < 0 {
        throw graphEstimateOverflow()
    }
    let (storageBytes, overflow) = count.multipliedReportingOverflow(by: elementBytes)
    if overflow {
        throw graphEstimateOverflow()
    }
    let (bytes, addOverflow) = ownerBytes.addingReportingOverflow(storageBytes)
    if addOverflow {
        throw graphEstimateOverflow()
    }
    try context.reserveGraphMemory(bytes)
}

@inlinable
@inline(__always)
internal func reserveGraphArrayMemory<Element: Serializer>(
    _ context: ReadContext,
    _ type: Element.Type,
    ownerBytes: Int,
    count: Int
) throws {
    try reserveGraphElements(
        context, ownerBytes: ownerBytes, count: count, elementBytes: storedElementBytes(type))
}

@inlinable
@inline(__always)
internal func reserveGraphMapMemory<Key: Serializer, Value: Serializer>(
    _ context: ReadContext,
    key: Key.Type,
    value: Value.Type,
    ownerBytes: Int,
    count: Int
) throws {
    let keyBytes = storedElementBytes(key)
    let valueBytes = storedElementBytes(value)
    let (elementBytes, overflow) = keyBytes.addingReportingOverflow(valueBytes)
    if overflow {
        throw graphEstimateOverflow()
    }
    try reserveGraphElements(context, ownerBytes: ownerBytes, count: count, elementBytes: elementBytes)
}

@usableFromInline
@inline(never)
internal func graphEstimateOverflow() -> ForyError {
    ForyError.invalidData("graph memory estimate overflows")
}

private let hostIsLittleEndian = Int(littleEndian: 1) == 1

@inline(__always)
private func uncheckedArrayCast<From, To>(_ array: [From], to _: To.Type) -> [To] {
    assert(From.self == To.self)
    return unsafeBitCast(array, to: [To].self)
}

@usableFromInline
@inline(__always)
internal func readArrayUninitialized<Element>(
    count: Int,
    _ initializer: (UnsafeMutablePointer<Element>) throws -> Void
) rethrows -> [Element] {
    // This fast path is only safe for trivially destructible elements. Nontrivial elements must
    // update Array's initialized prefix after each successful initialization so a later throw
    // releases that prefix.
    try [Element](unsafeUninitializedCapacity: count) { destination, initializedCount in
        if count > 0 {
            try initializer(destination.baseAddress!)
        }
        initializedCount = count
    }
}

@usableFromInline
@inline(__always)
internal func readArrayTrackingInitialization<Element>(
    count: Int,
    _ initializer: (UnsafeMutablePointer<Element>, inout Int) throws -> Void
) rethrows -> [Element] {
    try [Element](unsafeUninitializedCapacity: count) { destination, initializedCount in
        if count > 0 {
            try initializer(destination.baseAddress!, &initializedCount)
        }
    }
}

func writePrimitiveArray<Element: Serializer>(_ value: [Element], context: WriteContext) {
    if Element.self == UInt8.self {
        let bytes = uncheckedArrayCast(value, to: UInt8.self)
        context.buffer.writeVarUInt32(UInt32(bytes.count))
        context.buffer.writeBytes(bytes)
        return
    }

    if Element.self == Bool.self {
        let bools = uncheckedArrayCast(value, to: Bool.self)
        context.buffer.writeVarUInt32(UInt32(bools.count))
        for item in bools {
            context.buffer.writeUInt8(item ? 1 : 0)
        }
        return
    }

    if Element.self == Int8.self {
        let values = uncheckedArrayCast(value, to: Int8.self)
        context.buffer.writeVarUInt32(UInt32(values.count))
        values.withUnsafeBytes { rawBytes in
            context.buffer.writeBytes(rawBytes)
        }
        return
    }

    if Element.self == Int16.self {
        let values = uncheckedArrayCast(value, to: Int16.self)
        context.buffer.writeVarUInt32(UInt32(values.count * 2))
        if hostIsLittleEndian {
            values.withUnsafeBytes { rawBytes in
                context.buffer.writeBytes(rawBytes)
            }
        } else {
            for item in values {
                context.buffer.writeInt16(item)
            }
        }
        return
    }

    if Element.self == Int32.self {
        let values = uncheckedArrayCast(value, to: Int32.self)
        context.buffer.writeVarUInt32(UInt32(values.count * 4))
        if hostIsLittleEndian {
            values.withUnsafeBytes { rawBytes in
                context.buffer.writeBytes(rawBytes)
            }
        } else {
            for item in values {
                context.buffer.writeInt32(item)
            }
        }
        return
    }

    if Element.self == UInt32.self {
        let values = uncheckedArrayCast(value, to: UInt32.self)
        context.buffer.writeVarUInt32(UInt32(values.count * 4))
        if hostIsLittleEndian {
            values.withUnsafeBytes { rawBytes in
                context.buffer.writeBytes(rawBytes)
            }
        } else {
            for item in values {
                context.buffer.writeUInt32(item)
            }
        }
        return
    }

    if Element.self == Int64.self {
        let values = uncheckedArrayCast(value, to: Int64.self)
        context.buffer.writeVarUInt32(UInt32(values.count * 8))
        if hostIsLittleEndian {
            values.withUnsafeBytes { rawBytes in
                context.buffer.writeBytes(rawBytes)
            }
        } else {
            for item in values {
                context.buffer.writeInt64(item)
            }
        }
        return
    }

    if Element.self == UInt64.self {
        let values = uncheckedArrayCast(value, to: UInt64.self)
        context.buffer.writeVarUInt32(UInt32(values.count * 8))
        if hostIsLittleEndian {
            values.withUnsafeBytes { rawBytes in
                context.buffer.writeBytes(rawBytes)
            }
        } else {
            for item in values {
                context.buffer.writeUInt64(item)
            }
        }
        return
    }

    if Element.self == UInt16.self {
        let values = uncheckedArrayCast(value, to: UInt16.self)
        context.buffer.writeVarUInt32(UInt32(values.count * 2))
        if hostIsLittleEndian {
            values.withUnsafeBytes { rawBytes in
                context.buffer.writeBytes(rawBytes)
            }
        } else {
            for item in values {
                context.buffer.writeUInt16(item)
            }
        }
        return
    }

    if Element.self == Float16.self {
        let values = uncheckedArrayCast(value, to: Float16.self)
        context.buffer.writeVarUInt32(UInt32(values.count * 2))
        for item in values {
            context.buffer.writeUInt16(item.bitPattern)
        }
        return
    }

    if Element.self == BFloat16.self {
        let values = uncheckedArrayCast(value, to: BFloat16.self)
        context.buffer.writeVarUInt32(UInt32(values.count * 2))
        for item in values {
            context.buffer.writeUInt16(item.rawValue)
        }
        return
    }

    if Element.self == Float.self {
        let values = uncheckedArrayCast(value, to: Float.self)
        context.buffer.writeVarUInt32(UInt32(values.count * 4))
        if hostIsLittleEndian {
            values.withUnsafeBytes { rawBytes in
                context.buffer.writeBytes(rawBytes)
            }
        } else {
            for item in values {
                context.buffer.writeFloat32(item)
            }
        }
        return
    }

    let values = uncheckedArrayCast(value, to: Double.self)
    context.buffer.writeVarUInt32(UInt32(values.count * 8))
    if hostIsLittleEndian {
        values.withUnsafeBytes { rawBytes in
            context.buffer.writeBytes(rawBytes)
        }
    } else {
        for item in values {
            context.buffer.writeFloat64(item)
        }
    }
}

@inline(__always)
private func preparePrimitiveArray<Element: Serializer>(
    _ context: ReadContext,
    reserveGraphStorage: Bool,
    type: Element.Type,
    count: Int,
    label: String
) throws {
    try preparePrimitiveArrayStorage(
        context,
        reserveGraphStorage: reserveGraphStorage,
        ownerBytes: storedOwnerBytes([Element].self),
        count: count,
        elementBytes: storedElementBytes(type),
        label: label
    )
}

@inline(__always)
private func preparePrimitiveArrayStorage(
    _ context: ReadContext,
    reserveGraphStorage: Bool,
    ownerBytes: Int,
    count: Int,
    elementBytes: Int,
    label: String
) throws {
    try context.ensureCollectionLength(count, label: label)
    if reserveGraphStorage {
        try reserveGraphElements(
            context, ownerBytes: ownerBytes, count: count, elementBytes: elementBytes)
    }
}

@inline(__always)
private func readPrimitiveArrayByteSize(_ context: ReadContext) throws -> Int {
    let byteSize = Int(try context.buffer.readVarUInt32())
    try context.ensureRemainingBytes(byteSize, label: "primitive_array_bytes")
    return byteSize
}

@inline(__always)
func readBoolPrimitiveArray(
    _ context: ReadContext,
    reserveGraphStorage: Bool = false
) throws -> [Bool] {
    let byteSize = try readPrimitiveArrayByteSize(context)
    try preparePrimitiveArrayStorage(
        context,
        reserveGraphStorage: reserveGraphStorage,
        ownerBytes: MemoryLayout<[Bool]>.stride,
        count: byteSize,
        elementBytes: MemoryLayout<Bool>.stride,
        label: "bool_array"
    )
    return try readArrayUninitialized(count: byteSize) { destination in
        for index in 0..<byteSize {
            destination.advanced(by: index).initialize(to: try context.buffer.readUInt8() != 0)
        }
    }
}

@inline(__always)
func readInt32PrimitiveArray(
    _ context: ReadContext,
    reserveGraphStorage: Bool = false
) throws -> [Int32] {
    let byteSize = try readPrimitiveArrayByteSize(context)
    if byteSize % 4 != 0 { throw primitiveArraySizeMismatch("int32") }
    let count = byteSize / 4
    try preparePrimitiveArrayStorage(
        context,
        reserveGraphStorage: reserveGraphStorage,
        ownerBytes: MemoryLayout<[Int32]>.stride,
        count: count,
        elementBytes: MemoryLayout<Int32>.stride,
        label: "int32_array"
    )
    if hostIsLittleEndian {
        return try readArrayUninitialized(count: count) { destination in
            try context.buffer.readBytes(
                into: UnsafeMutableRawBufferPointer(start: destination, count: byteSize))
        }
    }
    return try readArrayUninitialized(count: count) { destination in
        for index in 0..<count {
            destination.advanced(by: index).initialize(to: try context.buffer.readInt32())
        }
    }
}

@inline(__always)
func readInt64PrimitiveArray(
    _ context: ReadContext,
    reserveGraphStorage: Bool = false
) throws -> [Int64] {
    let byteSize = try readPrimitiveArrayByteSize(context)
    if byteSize % 8 != 0 { throw primitiveArraySizeMismatch("int64") }
    let count = byteSize / 8
    try preparePrimitiveArrayStorage(
        context,
        reserveGraphStorage: reserveGraphStorage,
        ownerBytes: MemoryLayout<[Int64]>.stride,
        count: count,
        elementBytes: MemoryLayout<Int64>.stride,
        label: "int64_array"
    )
    if hostIsLittleEndian {
        return try readArrayUninitialized(count: count) { destination in
            try context.buffer.readBytes(
                into: UnsafeMutableRawBufferPointer(start: destination, count: byteSize))
        }
    }
    return try readArrayUninitialized(count: count) { destination in
        for index in 0..<count {
            destination.advanced(by: index).initialize(to: try context.buffer.readInt64())
        }
    }
}

@inline(__always)
func readFloatPrimitiveArray(
    _ context: ReadContext,
    reserveGraphStorage: Bool = false
) throws -> [Float] {
    let byteSize = try readPrimitiveArrayByteSize(context)
    if byteSize % 4 != 0 { throw primitiveArraySizeMismatch("float32") }
    let count = byteSize / 4
    try preparePrimitiveArrayStorage(
        context,
        reserveGraphStorage: reserveGraphStorage,
        ownerBytes: MemoryLayout<[Float]>.stride,
        count: count,
        elementBytes: MemoryLayout<Float>.stride,
        label: "float32_array"
    )
    if hostIsLittleEndian {
        return try readArrayUninitialized(count: count) { destination in
            try context.buffer.readBytes(
                into: UnsafeMutableRawBufferPointer(start: destination, count: byteSize))
        }
    }
    return try readArrayUninitialized(count: count) { destination in
        for index in 0..<count {
            destination.advanced(by: index).initialize(to: try context.buffer.readFloat32())
        }
    }
}

@inline(__always)
func readDoublePrimitiveArray(
    _ context: ReadContext,
    reserveGraphStorage: Bool = false
) throws -> [Double] {
    let byteSize = try readPrimitiveArrayByteSize(context)
    if byteSize % 8 != 0 { throw primitiveArraySizeMismatch("float64") }
    let count = byteSize / 8
    try preparePrimitiveArrayStorage(
        context,
        reserveGraphStorage: reserveGraphStorage,
        ownerBytes: MemoryLayout<[Double]>.stride,
        count: count,
        elementBytes: MemoryLayout<Double>.stride,
        label: "float64_array"
    )
    if hostIsLittleEndian {
        return try readArrayUninitialized(count: count) { destination in
            try context.buffer.readBytes(
                into: UnsafeMutableRawBufferPointer(start: destination, count: byteSize))
        }
    }
    return try readArrayUninitialized(count: count) { destination in
        for index in 0..<count {
            destination.advanced(by: index).initialize(to: try context.buffer.readFloat64())
        }
    }
}

// Common packed types use concrete readers so Array metadata and initialization stay specialized.
// The generic entry delegates to the same readers to keep one decoding path per type.
// swiftlint:disable:next function_body_length
func readPrimitiveArray<Element: Serializer>(
    _ context: ReadContext,
    reserveGraphStorage: Bool = false
) throws -> [Element] {
    if Element.self == Bool.self {
        return uncheckedArrayCast(
            try readBoolPrimitiveArray(context, reserveGraphStorage: reserveGraphStorage),
            to: Element.self)
    }
    if Element.self == Int32.self {
        return uncheckedArrayCast(
            try readInt32PrimitiveArray(context, reserveGraphStorage: reserveGraphStorage),
            to: Element.self)
    }
    if Element.self == Int64.self {
        return uncheckedArrayCast(
            try readInt64PrimitiveArray(context, reserveGraphStorage: reserveGraphStorage),
            to: Element.self)
    }
    if Element.self == Float.self {
        return uncheckedArrayCast(
            try readFloatPrimitiveArray(context, reserveGraphStorage: reserveGraphStorage),
            to: Element.self)
    }
    if Element.self == Double.self {
        return uncheckedArrayCast(
            try readDoublePrimitiveArray(context, reserveGraphStorage: reserveGraphStorage),
            to: Element.self)
    }

    let byteSize = try readPrimitiveArrayByteSize(context)

    if Element.self == UInt8.self {
        try preparePrimitiveArray(
            context, reserveGraphStorage: reserveGraphStorage, type: Element.self, count: byteSize,
            label: "uint8_array")
        let bytes = try context.buffer.readBytes(count: byteSize)
        return uncheckedArrayCast(bytes, to: Element.self)
    }

    if Element.self == Int8.self {
        try preparePrimitiveArray(
            context, reserveGraphStorage: reserveGraphStorage, type: Element.self, count: byteSize,
            label: "int8_array")
        var out = Array(repeating: Int8(0), count: byteSize)
        try out.withUnsafeMutableBytes { rawBytes in
            try context.buffer.readBytes(into: rawBytes)
        }
        return uncheckedArrayCast(out, to: Element.self)
    }

    if Element.self == Int16.self {
        if byteSize % 2 != 0 { throw primitiveArraySizeMismatch("int16") }
        let count = byteSize / 2
        try preparePrimitiveArray(
            context, reserveGraphStorage: reserveGraphStorage, type: Element.self, count: count,
            label: "int16_array")
        if hostIsLittleEndian {
            var out = Array(repeating: Int16(0), count: count)
            try out.withUnsafeMutableBytes { rawBytes in
                try context.buffer.readBytes(into: rawBytes)
            }
            return uncheckedArrayCast(out, to: Element.self)
        }
        let out = try readArrayUninitialized(count: count) { destination in
            for index in 0..<count {
                destination.advanced(by: index).initialize(to: try context.buffer.readInt16())
            }
        }
        return uncheckedArrayCast(out, to: Element.self)
    }

    if Element.self == UInt32.self {
        if byteSize % 4 != 0 { throw primitiveArraySizeMismatch("uint32") }
        let count = byteSize / 4
        try preparePrimitiveArray(
            context, reserveGraphStorage: reserveGraphStorage, type: Element.self, count: count,
            label: "uint32_array")
        if hostIsLittleEndian {
            var out = Array(repeating: UInt32(0), count: count)
            try out.withUnsafeMutableBytes { rawBytes in
                try context.buffer.readBytes(into: rawBytes)
            }
            return uncheckedArrayCast(out, to: Element.self)
        }
        let out = try readArrayUninitialized(count: count) { destination in
            for index in 0..<count {
                destination.advanced(by: index).initialize(to: try context.buffer.readUInt32())
            }
        }
        return uncheckedArrayCast(out, to: Element.self)
    }

    if Element.self == UInt64.self {
        if byteSize % 8 != 0 { throw primitiveArraySizeMismatch("uint64") }
        let count = byteSize / 8
        try preparePrimitiveArray(
            context, reserveGraphStorage: reserveGraphStorage, type: Element.self, count: count,
            label: "uint64_array")
        if hostIsLittleEndian {
            var out = Array(repeating: UInt64(0), count: count)
            try out.withUnsafeMutableBytes { rawBytes in
                try context.buffer.readBytes(into: rawBytes)
            }
            return uncheckedArrayCast(out, to: Element.self)
        }
        let out = try readArrayUninitialized(count: count) { destination in
            for index in 0..<count {
                destination.advanced(by: index).initialize(to: try context.buffer.readUInt64())
            }
        }
        return uncheckedArrayCast(out, to: Element.self)
    }

    if Element.self == UInt16.self {
        if byteSize % 2 != 0 { throw primitiveArraySizeMismatch("uint16") }
        let count = byteSize / 2
        try preparePrimitiveArray(
            context, reserveGraphStorage: reserveGraphStorage, type: Element.self, count: count,
            label: "uint16_array")
        if hostIsLittleEndian {
            var out = Array(repeating: UInt16(0), count: count)
            try out.withUnsafeMutableBytes { rawBytes in
                try context.buffer.readBytes(into: rawBytes)
            }
            return uncheckedArrayCast(out, to: Element.self)
        }
        let out = try readArrayUninitialized(count: count) { destination in
            for index in 0..<count {
                destination.advanced(by: index).initialize(to: try context.buffer.readUInt16())
            }
        }
        return uncheckedArrayCast(out, to: Element.self)
    }

    if Element.self == Float16.self {
        if byteSize % 2 != 0 { throw primitiveArraySizeMismatch("float16") }
        let count = byteSize / 2
        try preparePrimitiveArray(
            context, reserveGraphStorage: reserveGraphStorage, type: Element.self, count: count,
            label: "float16_array")
        let values = try readArrayUninitialized(count: count) { destination in
            for index in 0..<count {
                destination.advanced(by: index).initialize(
                    to: Float16(bitPattern: try context.buffer.readUInt16()))
            }
        }
        return uncheckedArrayCast(values, to: Element.self)
    }

    if Element.self == BFloat16.self {
        if byteSize % 2 != 0 { throw primitiveArraySizeMismatch("bfloat16") }
        let count = byteSize / 2
        try preparePrimitiveArray(
            context, reserveGraphStorage: reserveGraphStorage, type: Element.self, count: count,
            label: "bfloat16_array")
        let values = try readArrayUninitialized(count: count) { destination in
            for index in 0..<count {
                destination.advanced(by: index).initialize(
                    to: BFloat16(rawValue: try context.buffer.readUInt16()))
            }
        }
        return uncheckedArrayCast(values, to: Element.self)
    }

    preconditionFailure("Unsupported primitive array element type \(Element.self)")
}

@inline(never)
private func primitiveArraySizeMismatch(_ element: String) -> ForyError {
    ForyError.invalidData("\(element) array byte size mismatch")
}

/// A LIST carrier serializer whose target is an array of `Element.Target`.
public enum ArraySerializer<Element: Serializer>: Serializer {
    public typealias Target = [Element.Target]

    public static var staticTypeId: TypeId { .list }
    public static var readDataAlwaysAdvances: Bool { true }

    public static func defaultValue(_: ReadContext) throws -> Target { [] }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    @inlinable
    @inline(__always)
    public static func writeData(_ value: Target, _ context: WriteContext) throws {
        try writeElements(
            value,
            context,
            codec: SerializerCodec<Element>.self,
            hasDeclaredChildren: false
        )
    }

    @inlinable
    @inline(__always)
    public static func readData(_ context: ReadContext) throws -> Target {
        try readElements(
            context,
            codec: SerializerCodec<Element>.self,
            ownerBytes: storedOwnerBytes(Target.self)
        )
    }

    @inlinable
    internal static func writeElements<Codec: FieldCodec>(
        _ value: [Codec.Target],
        _ context: WriteContext,
        codec _: Codec.Type,
        hasDeclaredChildren: Bool
    ) throws where Codec.Target == Element.Target {
        let buffer = context.buffer
        buffer.writeVarUInt32(UInt32(value.count))
        if value.isEmpty {
            return
        }

        let hasNull = Codec.isNullableType && value.contains(where: Codec.isNone)
        let trackRef = context.trackRef && Codec.isRefType
        let declaredElementType =
            hasDeclaredChildren && !TypeId.needsTypeInfoForField(Codec.staticTypeId)
        let dynamicElementType = Codec.staticTypeId == .unknown

        var header: UInt8 = dynamicElementType ? 0 : CollectionHeader.sameType
        if trackRef {
            header |= CollectionHeader.trackingRef
        }
        if hasNull {
            header |= CollectionHeader.hasNull
        }
        if declaredElementType {
            header |= CollectionHeader.declaredElementType
        }

        buffer.writeUInt8(header)
        if !dynamicElementType && !declaredElementType {
            try Codec.writeFieldTypeInfo(context)
        }

        if dynamicElementType {
            let refMode = RefMode.from(nullable: hasNull, trackRef: trackRef)
            for element in value {
                try Codec.writeField(
                    element,
                    context,
                    refMode: refMode,
                    writeTypeInfo: true,
                    hasDeclaredChildren: hasDeclaredChildren
                )
            }
            return
        }

        if trackRef {
            for element in value {
                try Codec.writeField(
                    element,
                    context,
                    refMode: .tracking,
                    writeTypeInfo: false,
                    hasDeclaredChildren: hasDeclaredChildren
                )
            }
        } else if hasNull {
            for element in value {
                if Codec.isNone(element) {
                    buffer.writeInt8(RefFlag.null.rawValue)
                } else {
                    buffer.writeInt8(RefFlag.notNullValue.rawValue)
                    try Codec.writeFieldData(
                        element,
                        context,
                        hasDeclaredChildren: hasDeclaredChildren
                    )
                }
            }
        } else {
            for element in value {
                try Codec.writeFieldData(
                    element,
                    context,
                    hasDeclaredChildren: hasDeclaredChildren
                )
            }
        }
    }

    @inlinable
    internal static func readElements<Codec: FieldCodec>(
        _ context: ReadContext,
        codec _: Codec.Type,
        ownerBytes: Int
    ) throws -> [Codec.Target] where Codec.Target == Element.Target {
        let buffer = context.buffer
        let length = Int(try buffer.readVarUInt32())
        try context.ensureCollectionLength(length, label: "array")
        if length == 0 {
            try reserveGraphArrayMemory(
                context,
                Codec.self,
                ownerBytes: ownerBytes,
                count: length
            )
            return []
        }

        let header = try buffer.readUInt8()
        // The wire header owns ref/null policy; local field metadata may differ.
        let trackRef = (header & CollectionHeader.trackingRef) != 0
        let hasNull = (header & CollectionHeader.hasNull) != 0
        let declared = (header & CollectionHeader.declaredElementType) != 0
        let sameType = (header & CollectionHeader.sameType) != 0

        try reserveGraphArrayMemory(
            context,
            Codec.self,
            ownerBytes: ownerBytes,
            count: length
        )
        if !sameType {
            try ensureContainerAllocation(
                context,
                count: length,
                itemReadAlwaysAdvances: true,
                label: "array"
            )
            let refMode = RefMode.from(nullable: hasNull, trackRef: trackRef)
            return try readArrayTrackingInitialization(
                count: length
            ) { destination, initializedCount in
                for index in 0..<length {
                    destination.advanced(by: index).initialize(
                        to: try Codec.readField(
                            context,
                            refMode: refMode,
                            readTypeInfo: true
                        )
                    )
                    initializedCount = index + 1
                }
            }
        }

        let elementTypeInfo = declared ? nil : try Codec.readFieldTypeInfo(context)
        let elementReadAlwaysAdvances =
            trackRef || hasNull
            || fieldReadAlwaysAdvances(Codec.self, declared: declared, typeInfo: elementTypeInfo)
        try ensureContainerAllocation(
            context,
            count: length,
            itemReadAlwaysAdvances: elementReadAlwaysAdvances,
            label: "array"
        )
        if !trackRef && !hasNull && elementReadAlwaysAdvances {
            if let elementTypeInfo {
                return try Codec.withFieldTypeInfo(elementTypeInfo, context) {
                    try readNonNullElements(context, codec: Codec.self, count: length)
                }
            }
            return try readNonNullElements(context, codec: Codec.self, count: length)
        }
        return try Codec.withFieldTypeInfo(elementTypeInfo, context) {
            if trackRef {
                return try readArrayTrackingInitialization(
                    count: length
                ) { destination, initializedCount in
                    for index in 0..<length {
                        destination.advanced(by: index).initialize(
                            to: try Codec.readField(
                                context,
                                refMode: .tracking,
                                readTypeInfo: false
                            )
                        )
                        initializedCount = index + 1
                    }
                }
            }

            if hasNull {
                return try readArrayTrackingInitialization(
                    count: length
                ) { destination, initializedCount in
                    for index in 0..<length {
                        let refFlag = try buffer.readInt8()
                        if refFlag == RefFlag.null.rawValue {
                            destination.advanced(by: index).initialize(
                                to: try Codec.defaultValue(context)
                            )
                        } else if refFlag == RefFlag.notNullValue.rawValue {
                            destination.advanced(by: index).initialize(
                                to: try Codec.readFieldData(context)
                            )
                        } else {
                            throw invalidCollectionRefFlag(refFlag)
                        }
                        initializedCount = index + 1
                    }
                }
            }

            return try readArrayTrackingInitialization(
                count: length
            ) { destination, initializedCount in
                var windowStart = buffer.cursor
                var windowItems = 0
                for index in 0..<length {
                    destination.advanced(by: index).initialize(
                        to: try Codec.readFieldData(context)
                    )
                    initializedCount = index + 1
                    windowItems += 1
                    if windowItems == unbackedContainerCheckInterval {
                        try settleUnbackedContainerItems(
                            context, completed: windowItems, startCursor: windowStart)
                        windowStart = buffer.cursor
                        windowItems = 0
                    }
                }
                if windowItems != 0 {
                    try settleUnbackedContainerItems(
                        context, completed: windowItems, startCursor: windowStart)
                }
            }
        }
    }

    @inlinable
    @inline(__always)
    internal static func readNonNullElements<Codec: FieldCodec>(
        _ context: ReadContext,
        codec _: Codec.Type,
        count: Int
    ) throws -> [Codec.Target] where Codec.Target == Element.Target {
        try [Codec.Target](unsafeUninitializedCapacity: count) { destination, initializedCount in
            let baseAddress = destination.baseAddress!
            for index in 0..<count {
                baseAddress.advanced(by: index).initialize(to: try Codec.readFieldData(context))
                // A later decode may throw, so Array must own every initialized prefix element.
                initializedCount = index + 1
            }
        }
    }
}

public extension ArraySerializer where Element: FieldCodec {
    static func fieldType(
        nullable: Bool,
        trackRef: Bool,
        resolveSerializerTypeId: (Any.Type) throws -> TypeId
    ) throws -> TypeMeta.FieldType {
        TypeMeta.FieldType(
            typeID: TypeId.list.rawValue,
            nullable: nullable,
            trackRef: trackRef,
            generics: [
                try Element.fieldType(
                    nullable: Element.isNullableType,
                    trackRef: trackRef && Element.isRefType,
                    resolveSerializerTypeId: resolveSerializerTypeId
                )
            ]
        )
    }

    @inlinable
    @inline(__always)
    static func writeFieldData(
        _ value: Target,
        _ context: WriteContext,
        hasDeclaredChildren: Bool
    ) throws {
        try writeElements(
            value,
            context,
            codec: Element.self,
            hasDeclaredChildren: hasDeclaredChildren
        )
    }

    @inlinable
    @inline(__always)
    static func readFieldData(_ context: ReadContext) throws -> Target {
        try readElements(
            context,
            codec: Element.self,
            ownerBytes: storedOwnerBytes(Target.self)
        )
    }
}

extension ArraySerializer: FieldCodec where Element: FieldCodec {}

extension Array: Serializer where Element: Serializer, Element.Target == Element {
    public typealias Target = Self

    @inlinable
    @inline(__always)
    public static var staticTypeId: TypeId { ArraySerializer<Element>.staticTypeId }

    @inlinable
    @inline(__always)
    public static var readDataAlwaysAdvances: Bool { true }

    @inlinable
    @inline(__always)
    public static func defaultValue(_ context: ReadContext) throws -> Self {
        try ArraySerializer<Element>.defaultValue(context)
    }

    @inlinable
    @inline(__always)
    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        try ArraySerializer<Element>.writeData(value, context)
    }

    @inlinable
    @inline(__always)
    public static func readData(_ context: ReadContext) throws -> Self {
        try ArraySerializer<Element>.readData(context)
    }

    @inlinable
    @inline(__always)
    public static func writeTypeInfo(_ context: WriteContext) throws {
        try ArraySerializer<Element>.writeTypeInfo(context)
    }

    @inlinable
    @inline(__always)
    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try ArraySerializer<Element>.readTypeInfo(context)
    }
}

/// A SET carrier serializer whose target is a set of `Element.Target`.
public enum SetSerializer<Element: Serializer>: Serializer where Element.Target: Hashable {
    public typealias Target = Set<Element.Target>

    public static var staticTypeId: TypeId { .set }
    public static var readDataAlwaysAdvances: Bool { true }

    public static func defaultValue(_: ReadContext) throws -> Target { [] }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    @inlinable
    @inline(__always)
    public static func writeData(_ value: Target, _ context: WriteContext) throws {
        try writeElements(
            value,
            context,
            codec: SerializerCodec<Element>.self,
            hasDeclaredChildren: false
        )
    }

    @inlinable
    @inline(__always)
    public static func readData(_ context: ReadContext) throws -> Target {
        try readElements(
            context,
            codec: SerializerCodec<Element>.self,
            ownerBytes: storedOwnerBytes(Target.self)
        )
    }

    @inlinable
    internal static func writeElements<Codec: FieldCodec>(
        _ value: Set<Codec.Target>,
        _ context: WriteContext,
        codec _: Codec.Type,
        hasDeclaredChildren: Bool
    ) throws where Codec.Target == Element.Target {
        let buffer = context.buffer
        buffer.writeVarUInt32(UInt32(value.count))
        if value.isEmpty {
            return
        }

        let hasNull = Codec.isNullableType && value.contains(where: Codec.isNone)
        let trackRef = context.trackRef && Codec.isRefType
        let declaredElementType =
            hasDeclaredChildren && !TypeId.needsTypeInfoForField(Codec.staticTypeId)
        let dynamicElementType = Codec.staticTypeId == .unknown

        var header: UInt8 = dynamicElementType ? 0 : CollectionHeader.sameType
        if trackRef {
            header |= CollectionHeader.trackingRef
        }
        if hasNull {
            header |= CollectionHeader.hasNull
        }
        if declaredElementType {
            header |= CollectionHeader.declaredElementType
        }

        buffer.writeUInt8(header)
        if !dynamicElementType && !declaredElementType {
            try Codec.writeFieldTypeInfo(context)
        }

        if dynamicElementType {
            let refMode = RefMode.from(nullable: hasNull, trackRef: trackRef)
            for element in value {
                try Codec.writeField(
                    element,
                    context,
                    refMode: refMode,
                    writeTypeInfo: true,
                    hasDeclaredChildren: hasDeclaredChildren
                )
            }
            return
        }

        if trackRef {
            for element in value {
                try Codec.writeField(
                    element,
                    context,
                    refMode: .tracking,
                    writeTypeInfo: false,
                    hasDeclaredChildren: hasDeclaredChildren
                )
            }
        } else if hasNull {
            for element in value {
                if Codec.isNone(element) {
                    buffer.writeInt8(RefFlag.null.rawValue)
                } else {
                    buffer.writeInt8(RefFlag.notNullValue.rawValue)
                    try Codec.writeFieldData(
                        element,
                        context,
                        hasDeclaredChildren: hasDeclaredChildren
                    )
                }
            }
        } else {
            for element in value {
                try Codec.writeFieldData(
                    element,
                    context,
                    hasDeclaredChildren: hasDeclaredChildren
                )
            }
        }
    }

    @inlinable
    internal static func readElements<Codec: FieldCodec>(
        _ context: ReadContext,
        codec _: Codec.Type,
        ownerBytes: Int
    ) throws -> Set<Codec.Target> where Codec.Target == Element.Target {
        let buffer = context.buffer
        let length = Int(try buffer.readVarUInt32())
        try context.ensureCollectionLength(length, label: "set")
        try reserveGraphArrayMemory(
            context,
            Codec.self,
            ownerBytes: ownerBytes,
            count: length
        )
        if length == 0 {
            return []
        }

        let header = try buffer.readUInt8()
        // The wire header owns ref/null policy; local field metadata may differ.
        let trackRef = (header & CollectionHeader.trackingRef) != 0
        let hasNull = (header & CollectionHeader.hasNull) != 0
        let declared = (header & CollectionHeader.declaredElementType) != 0
        let sameType = (header & CollectionHeader.sameType) != 0
        if !sameType {
            try ensureContainerAllocation(
                context,
                count: length,
                itemReadAlwaysAdvances: true,
                label: "set"
            )
            var result = Set<Codec.Target>()
            result.reserveCapacity(length)
            let refMode = RefMode.from(nullable: hasNull, trackRef: trackRef)
            for _ in 0..<length {
                result.insert(
                    try Codec.readField(
                        context,
                        refMode: refMode,
                        readTypeInfo: true
                    )
                )
            }
            return result
        }

        let elementTypeInfo = declared ? nil : try Codec.readFieldTypeInfo(context)
        let elementReadAlwaysAdvances =
            trackRef || hasNull
            || fieldReadAlwaysAdvances(Codec.self, declared: declared, typeInfo: elementTypeInfo)
        try ensureContainerAllocation(
            context,
            count: length,
            itemReadAlwaysAdvances: elementReadAlwaysAdvances,
            label: "set"
        )
        var result = Set<Codec.Target>()
        result.reserveCapacity(length)
        return try Codec.withFieldTypeInfo(elementTypeInfo, context) {
            if trackRef {
                for _ in 0..<length {
                    result.insert(
                        try Codec.readField(
                            context,
                            refMode: .tracking,
                            readTypeInfo: false
                        )
                    )
                }
            } else if hasNull {
                for _ in 0..<length {
                    let refFlag = try buffer.readInt8()
                    if refFlag == RefFlag.null.rawValue {
                        result.insert(try Codec.defaultValue(context))
                    } else if refFlag == RefFlag.notNullValue.rawValue {
                        result.insert(try Codec.readFieldData(context))
                    } else {
                        throw invalidCollectionRefFlag(refFlag)
                    }
                }
            } else if elementReadAlwaysAdvances {
                for _ in 0..<length {
                    result.insert(try Codec.readFieldData(context))
                }
            } else {
                var windowStart = buffer.cursor
                var windowItems = 0
                for _ in 0..<length {
                    result.insert(try Codec.readFieldData(context))
                    windowItems += 1
                    if windowItems == unbackedContainerCheckInterval {
                        try settleUnbackedContainerItems(
                            context, completed: windowItems, startCursor: windowStart)
                        windowStart = buffer.cursor
                        windowItems = 0
                    }
                }
                if windowItems != 0 {
                    try settleUnbackedContainerItems(
                        context, completed: windowItems, startCursor: windowStart)
                }
            }
            return result
        }
    }
}

public extension SetSerializer where Element: FieldCodec {
    static func fieldType(
        nullable: Bool,
        trackRef: Bool,
        resolveSerializerTypeId: (Any.Type) throws -> TypeId
    ) throws -> TypeMeta.FieldType {
        TypeMeta.FieldType(
            typeID: TypeId.set.rawValue,
            nullable: nullable,
            trackRef: trackRef,
            generics: [
                try Element.fieldType(
                    nullable: Element.isNullableType,
                    trackRef: trackRef && Element.isRefType,
                    resolveSerializerTypeId: resolveSerializerTypeId
                )
            ]
        )
    }

    @inlinable
    @inline(__always)
    static func writeFieldData(
        _ value: Target,
        _ context: WriteContext,
        hasDeclaredChildren: Bool
    ) throws {
        try writeElements(
            value,
            context,
            codec: Element.self,
            hasDeclaredChildren: hasDeclaredChildren
        )
    }

    @inlinable
    @inline(__always)
    static func readFieldData(_ context: ReadContext) throws -> Target {
        try readElements(
            context,
            codec: Element.self,
            ownerBytes: storedOwnerBytes(Target.self)
        )
    }
}

extension SetSerializer: FieldCodec where Element: FieldCodec {}

extension Set: Serializer
where Element: Serializer & Hashable, Element.Target == Element {
    public typealias Target = Self

    @inlinable
    @inline(__always)
    public static var staticTypeId: TypeId { SetSerializer<Element>.staticTypeId }

    @inlinable
    @inline(__always)
    public static var readDataAlwaysAdvances: Bool { true }

    @inlinable
    @inline(__always)
    public static func defaultValue(_ context: ReadContext) throws -> Self {
        try SetSerializer<Element>.defaultValue(context)
    }

    @inlinable
    @inline(__always)
    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        try SetSerializer<Element>.writeData(value, context)
    }

    @inlinable
    @inline(__always)
    public static func readData(_ context: ReadContext) throws -> Self {
        try SetSerializer<Element>.readData(context)
    }

    @inlinable
    @inline(__always)
    public static func writeTypeInfo(_ context: WriteContext) throws {
        try SetSerializer<Element>.writeTypeInfo(context)
    }

    @inlinable
    @inline(__always)
    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try SetSerializer<Element>.readTypeInfo(context)
    }
}

/// A MAP carrier serializer whose key and value targets are formed recursively
/// from `Key` and `Value`.
public enum DictionarySerializer<Key: Serializer, Value: Serializer>: Serializer
where Key.Target: Hashable {
    public typealias Target = [Key.Target: Value.Target]

    public static var staticTypeId: TypeId { .map }
    public static var readDataAlwaysAdvances: Bool { true }

    public static func defaultValue(_: ReadContext) throws -> Target { [:] }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    @inlinable
    @inline(__always)
    public static func writeData(_ value: Target, _ context: WriteContext) throws {
        try writeEntries(
            value,
            context,
            keyCodec: SerializerCodec<Key>.self,
            valueCodec: SerializerCodec<Value>.self,
            hasDeclaredChildren: false
        )
    }

    @inlinable
    @inline(__always)
    public static func readData(_ context: ReadContext) throws -> Target {
        try readEntries(
            context,
            keyCodec: SerializerCodec<Key>.self,
            valueCodec: SerializerCodec<Value>.self,
            ownerBytes: storedOwnerBytes(Target.self)
        )
    }

    // Dynamic and static map chunks stay in one specialized body so the static hot path does not
    // retain a forwarding layer or runtime dispatch after generic specialization.
    @inlinable
    // swiftlint:disable:next function_body_length
    internal static func writeEntries<KeyCodec: FieldCodec, ValueCodec: FieldCodec>(
        _ value: [KeyCodec.Target: ValueCodec.Target],
        _ context: WriteContext,
        keyCodec _: KeyCodec.Type,
        valueCodec _: ValueCodec.Type,
        hasDeclaredChildren: Bool
    ) throws
    where
        KeyCodec.Target == Key.Target,
        ValueCodec.Target == Value.Target
    {
        context.buffer.writeVarUInt32(UInt32(value.count))
        if value.isEmpty {
            return
        }

        let trackKeyRef = context.trackRef && KeyCodec.isRefType
        let trackValueRef = context.trackRef && ValueCodec.isRefType
        let keyDeclared =
            hasDeclaredChildren && !TypeId.needsTypeInfoForField(KeyCodec.staticTypeId)
        let valueDeclared =
            hasDeclaredChildren && !TypeId.needsTypeInfoForField(ValueCodec.staticTypeId)
        let keyDynamicType = KeyCodec.staticTypeId == .unknown
        let valueDynamicType = ValueCodec.staticTypeId == .unknown

        if keyDynamicType || valueDynamicType {
            for pair in value {
                let keyIsNil = KeyCodec.isNone(pair.key)
                let valueIsNil = ValueCodec.isNone(pair.value)
                var header: UInt8 = 0
                if trackKeyRef {
                    header |= MapHeader.trackingKeyRef
                }
                if trackValueRef {
                    header |= MapHeader.trackingValueRef
                }
                if keyIsNil {
                    header |= MapHeader.keyNull
                } else if !keyDynamicType && keyDeclared {
                    header |= MapHeader.declaredKeyType
                }
                if valueIsNil {
                    header |= MapHeader.valueNull
                } else if !valueDynamicType && valueDeclared {
                    header |= MapHeader.declaredValueType
                }
                context.buffer.writeUInt8(header)

                if keyIsNil && valueIsNil {
                    continue
                }
                if keyIsNil {
                    try ValueCodec.writeField(
                        pair.value,
                        context,
                        refMode: trackValueRef ? .tracking : .none,
                        writeTypeInfo: !valueDeclared,
                        hasDeclaredChildren: hasDeclaredChildren
                    )
                    continue
                }
                if valueIsNil {
                    try KeyCodec.writeField(
                        pair.key,
                        context,
                        refMode: trackKeyRef ? .tracking : .none,
                        writeTypeInfo: !keyDeclared,
                        hasDeclaredChildren: hasDeclaredChildren
                    )
                    continue
                }

                context.buffer.writeUInt8(1)
                let keyTypeInfo: TypeInfo?
                if keyDynamicType {
                    keyTypeInfo = try writeDynamicTypeInfo(
                        for: pair.key,
                        context
                    )
                } else {
                    keyTypeInfo = nil
                    if !keyDeclared {
                        try KeyCodec.writeFieldTypeInfo(context)
                    }
                }
                let valueTypeInfo: TypeInfo?
                if valueDynamicType {
                    valueTypeInfo = try writeDynamicTypeInfo(
                        for: pair.value,
                        context
                    )
                } else {
                    valueTypeInfo = nil
                    if !valueDeclared {
                        try ValueCodec.writeFieldTypeInfo(context)
                    }
                }

                if let keyTypeInfo {
                    try writeDynamicValue(
                        pair.key,
                        typeInfo: keyTypeInfo,
                        context,
                        refMode: trackKeyRef ? .tracking : .none
                    )
                } else {
                    try KeyCodec.writeField(
                        pair.key,
                        context,
                        refMode: trackKeyRef ? .tracking : .none,
                        writeTypeInfo: false,
                        hasDeclaredChildren: hasDeclaredChildren
                    )
                }
                if let valueTypeInfo {
                    try writeDynamicValue(
                        pair.value,
                        typeInfo: valueTypeInfo,
                        context,
                        refMode: trackValueRef ? .tracking : .none
                    )
                } else {
                    try ValueCodec.writeField(
                        pair.value,
                        context,
                        refMode: trackValueRef ? .tracking : .none,
                        writeTypeInfo: false,
                        hasDeclaredChildren: hasDeclaredChildren
                    )
                }
            }
            return
        }

        var iterator = value.makeIterator()
        var pendingPair = iterator.next()
        while let pair = pendingPair {
            let keyIsNil = KeyCodec.isNone(pair.key)
            let valueIsNil = ValueCodec.isNone(pair.value)
            if keyIsNil || valueIsNil {
                var header: UInt8 = 0
                if trackKeyRef {
                    header |= MapHeader.trackingKeyRef
                }
                if trackValueRef {
                    header |= MapHeader.trackingValueRef
                }
                if keyIsNil {
                    header |= MapHeader.keyNull
                }
                if valueIsNil {
                    header |= MapHeader.valueNull
                }
                if !keyIsNil && keyDeclared {
                    header |= MapHeader.declaredKeyType
                }
                if !valueIsNil && valueDeclared {
                    header |= MapHeader.declaredValueType
                }

                context.buffer.writeUInt8(header)
                if !keyIsNil {
                    try KeyCodec.writeField(
                        pair.key,
                        context,
                        refMode: trackKeyRef ? .tracking : .none,
                        writeTypeInfo: !keyDeclared,
                        hasDeclaredChildren: hasDeclaredChildren
                    )
                }
                if !valueIsNil {
                    try ValueCodec.writeField(
                        pair.value,
                        context,
                        refMode: trackValueRef ? .tracking : .none,
                        writeTypeInfo: !valueDeclared,
                        hasDeclaredChildren: hasDeclaredChildren
                    )
                }
                pendingPair = iterator.next()
                continue
            }

            var header: UInt8 = 0
            if trackKeyRef {
                header |= MapHeader.trackingKeyRef
            }
            if trackValueRef {
                header |= MapHeader.trackingValueRef
            }
            if keyDeclared {
                header |= MapHeader.declaredKeyType
            }
            if valueDeclared {
                header |= MapHeader.declaredValueType
            }

            context.buffer.writeUInt8(header)
            let chunkSizeOffset = context.buffer.count
            context.buffer.writeUInt8(0)
            if !keyDeclared {
                try KeyCodec.writeFieldTypeInfo(context)
            }
            if !valueDeclared {
                try ValueCodec.writeFieldTypeInfo(context)
            }

            var chunkSize: UInt8 = 0
            while chunkSize < UInt8.max, let current = pendingPair {
                if KeyCodec.isNone(current.key) || ValueCodec.isNone(current.value) {
                    break
                }
                try KeyCodec.writeField(
                    current.key,
                    context,
                    refMode: trackKeyRef ? .tracking : .none,
                    writeTypeInfo: false,
                    hasDeclaredChildren: hasDeclaredChildren
                )
                try ValueCodec.writeField(
                    current.value,
                    context,
                    refMode: trackValueRef ? .tracking : .none,
                    writeTypeInfo: false,
                    hasDeclaredChildren: hasDeclaredChildren
                )
                chunkSize &+= 1
                pendingPair = iterator.next()
            }
            context.buffer.setByte(at: chunkSizeOffset, to: chunkSize)
        }
    }

    @usableFromInline
    internal static func readDynamicEntries<KeyCodec: FieldCodec, ValueCodec: FieldCodec>(
        _ context: ReadContext,
        into map: inout [KeyCodec.Target: ValueCodec.Target],
        keyCodec _: KeyCodec.Type,
        valueCodec _: ValueCodec.Type,
        count: Int
    ) throws
    where
        KeyCodec.Target == Key.Target,
        ValueCodec.Target == Value.Target
    {
        var readCount = 0
        while readCount < count {
            let header = try context.buffer.readUInt8()
            let trackKeyRef = (header & MapHeader.trackingKeyRef) != 0
            let keyNull = (header & MapHeader.keyNull) != 0
            let keyDeclared = (header & MapHeader.declaredKeyType) != 0
            let trackValueRef = (header & MapHeader.trackingValueRef) != 0
            let valueNull = (header & MapHeader.valueNull) != 0
            let valueDeclared = (header & MapHeader.declaredValueType) != 0

            if keyNull && valueNull {
                map[try KeyCodec.defaultValue(context)] = try ValueCodec.defaultValue(context)
                readCount += 1
                continue
            }
            if keyNull {
                let value = try ValueCodec.readField(
                    context,
                    refMode: trackValueRef ? .tracking : .none,
                    readTypeInfo: !valueDeclared
                )
                map[try KeyCodec.defaultValue(context)] = value
                readCount += 1
                continue
            }
            if valueNull {
                let key = try KeyCodec.readField(
                    context,
                    refMode: trackKeyRef ? .tracking : .none,
                    readTypeInfo: !keyDeclared
                )
                map[key] = try ValueCodec.defaultValue(context)
                readCount += 1
                continue
            }

            let chunkSize = Int(try context.buffer.readUInt8())
            if chunkSize == 0 || chunkSize > count - readCount {
                throw invalidMapChunkSize(dynamic: true)
            }
            let keyTypeInfo = keyDeclared ? nil : try KeyCodec.readFieldTypeInfo(context)
            let valueTypeInfo = valueDeclared ? nil : try ValueCodec.readFieldTypeInfo(context)
            let alwaysAdvances =
                trackKeyRef || trackValueRef
                || fieldReadAlwaysAdvances(
                    KeyCodec.self, declared: keyDeclared, typeInfo: keyTypeInfo)
                || fieldReadAlwaysAdvances(
                    ValueCodec.self, declared: valueDeclared, typeInfo: valueTypeInfo)
            let chunkStart = alwaysAdvances ? 0 : context.buffer.cursor
            for _ in 0..<chunkSize {
                let key = try KeyCodec.withFieldTypeInfo(keyTypeInfo, context) {
                    try KeyCodec.readField(
                        context,
                        refMode: trackKeyRef ? .tracking : .none,
                        readTypeInfo: false
                    )
                }
                let value = try ValueCodec.withFieldTypeInfo(valueTypeInfo, context) {
                    try ValueCodec.readField(
                        context,
                        refMode: trackValueRef ? .tracking : .none,
                        readTypeInfo: false
                    )
                }
                map[key] = value
            }
            if !alwaysAdvances {
                try settleUnbackedContainerItems(
                    context, completed: chunkSize, startCursor: chunkStart)
            }
            readCount += chunkSize
        }
    }

    @inlinable
    internal static func readEntries<KeyCodec: FieldCodec, ValueCodec: FieldCodec>(
        _ context: ReadContext,
        keyCodec _: KeyCodec.Type,
        valueCodec _: ValueCodec.Type,
        ownerBytes: Int
    ) throws -> [KeyCodec.Target: ValueCodec.Target]
    where
        KeyCodec.Target == Key.Target,
        ValueCodec.Target == Value.Target
    {
        let totalLength = Int(try context.buffer.readVarUInt32())
        try context.ensureCollectionLength(totalLength, label: "map")
        try reserveGraphMapMemory(
            context,
            key: KeyCodec.self,
            value: ValueCodec.self,
            ownerBytes: ownerBytes,
            count: totalLength
        )
        if totalLength == 0 {
            return [:]
        }

        try ensureContainerAllocation(
            context,
            count: totalLength,
            itemReadAlwaysAdvances: false,
            label: "map"
        )
        var map: [KeyCodec.Target: ValueCodec.Target] = [:]
        map.reserveCapacity(totalLength)
        let keyDynamicType = KeyCodec.staticTypeId == .unknown
        let valueDynamicType = ValueCodec.staticTypeId == .unknown
        // A one-null entry uses complete-field order: ref envelope, optional TypeInfo, then body.
        // Keep it distinct from non-null chunks, whose shared TypeInfo values precede all bodies.
        if keyDynamicType || valueDynamicType {
            try readDynamicEntries(
                context,
                into: &map,
                keyCodec: KeyCodec.self,
                valueCodec: ValueCodec.self,
                count: totalLength
            )
            return map
        }

        var readCount = 0
        while readCount < totalLength {
            let header = try context.buffer.readUInt8()
            // The wire header owns key/value reference policy; local metadata may differ.
            let trackKeyRef = (header & MapHeader.trackingKeyRef) != 0
            let keyNull = (header & MapHeader.keyNull) != 0
            let keyDeclared = (header & MapHeader.declaredKeyType) != 0
            let trackValueRef = (header & MapHeader.trackingValueRef) != 0
            let valueNull = (header & MapHeader.valueNull) != 0
            let valueDeclared = (header & MapHeader.declaredValueType) != 0

            if keyNull && valueNull {
                map[try KeyCodec.defaultValue(context)] =
                    try ValueCodec.defaultValue(context)
                readCount += 1
                continue
            }
            if keyNull {
                let value = try ValueCodec.readField(
                    context,
                    refMode: trackValueRef ? .tracking : .none,
                    readTypeInfo: !valueDeclared
                )
                map[try KeyCodec.defaultValue(context)] = value
                readCount += 1
                continue
            }
            if valueNull {
                let key = try KeyCodec.readField(
                    context,
                    refMode: trackKeyRef ? .tracking : .none,
                    readTypeInfo: !keyDeclared
                )
                map[key] = try ValueCodec.defaultValue(context)
                readCount += 1
                continue
            }

            let chunkSize = Int(try context.buffer.readUInt8())
            if chunkSize == 0 || chunkSize > totalLength - readCount {
                throw invalidMapChunkSize(dynamic: false)
            }
            let keyTypeInfo =
                keyDeclared ? nil : try KeyCodec.readFieldTypeInfo(context)
            let valueTypeInfo =
                valueDeclared ? nil : try ValueCodec.readFieldTypeInfo(context)
            let alwaysAdvances =
                trackKeyRef || trackValueRef
                || fieldReadAlwaysAdvances(
                    KeyCodec.self, declared: keyDeclared, typeInfo: keyTypeInfo)
                || fieldReadAlwaysAdvances(
                    ValueCodec.self, declared: valueDeclared, typeInfo: valueTypeInfo)
            let chunkStart = alwaysAdvances ? 0 : context.buffer.cursor
            for _ in 0..<chunkSize {
                let key = try KeyCodec.withFieldTypeInfo(keyTypeInfo, context) {
                    try KeyCodec.readField(
                        context,
                        refMode: trackKeyRef ? .tracking : .none,
                        readTypeInfo: false
                    )
                }
                let value = try ValueCodec.withFieldTypeInfo(valueTypeInfo, context) {
                    try ValueCodec.readField(
                        context,
                        refMode: trackValueRef ? .tracking : .none,
                        readTypeInfo: false
                    )
                }
                map[key] = value
            }
            if !alwaysAdvances {
                try settleUnbackedContainerItems(
                    context, completed: chunkSize, startCursor: chunkStart)
            }
            readCount += chunkSize
        }
        return map
    }
}

extension Dictionary: Serializer
where
    Key: Serializer & Hashable,
    Value: Serializer,
    Key.Target == Key,
    Value.Target == Value
{
    public typealias Target = Self

    @inlinable
    @inline(__always)
    public static var staticTypeId: TypeId {
        DictionarySerializer<Key, Value>.staticTypeId
    }

    @inlinable
    @inline(__always)
    public static var readDataAlwaysAdvances: Bool { true }

    @inlinable
    @inline(__always)
    public static func defaultValue(_ context: ReadContext) throws -> Self {
        try DictionarySerializer<Key, Value>.defaultValue(context)
    }

    @inlinable
    @inline(__always)
    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        try DictionarySerializer<Key, Value>.writeData(value, context)
    }

    @inlinable
    @inline(__always)
    public static func readData(_ context: ReadContext) throws -> Self {
        try DictionarySerializer<Key, Value>.readData(context)
    }

    @inlinable
    @inline(__always)
    public static func writeTypeInfo(_ context: WriteContext) throws {
        try DictionarySerializer<Key, Value>.writeTypeInfo(context)
    }

    @inlinable
    @inline(__always)
    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try DictionarySerializer<Key, Value>.readTypeInfo(context)
    }
}

@usableFromInline
@inline(never)
internal func invalidCollectionRefFlag(_ rawFlag: Int8) -> ForyError {
    ForyError.refError("invalid nullability flag \(rawFlag)")
}

@usableFromInline
@inline(never)
internal func invalidMapChunkSize(dynamic: Bool) -> ForyError {
    ForyError.invalidData(
        dynamic
            ? "map dynamic chunk size must be positive and not exceed remaining entries"
            : "map chunk size must be positive and not exceed remaining entries"
    )
}

public extension DictionarySerializer where Key: FieldCodec, Value: FieldCodec {
    static func fieldType(
        nullable: Bool,
        trackRef: Bool,
        resolveSerializerTypeId: (Any.Type) throws -> TypeId
    ) throws -> TypeMeta.FieldType {
        TypeMeta.FieldType(
            typeID: TypeId.map.rawValue,
            nullable: nullable,
            trackRef: trackRef,
            generics: [
                try Key.fieldType(
                    nullable: Key.isNullableType,
                    trackRef: trackRef && Key.isRefType,
                    resolveSerializerTypeId: resolveSerializerTypeId
                ),
                try Value.fieldType(
                    nullable: Value.isNullableType,
                    trackRef: trackRef && Value.isRefType,
                    resolveSerializerTypeId: resolveSerializerTypeId
                )
            ]
        )
    }

    @inlinable
    @inline(__always)
    static func writeFieldData(
        _ value: Target,
        _ context: WriteContext,
        hasDeclaredChildren: Bool
    ) throws {
        try writeEntries(
            value,
            context,
            keyCodec: Key.self,
            valueCodec: Value.self,
            hasDeclaredChildren: hasDeclaredChildren
        )
    }

    @inlinable
    @inline(__always)
    static func readFieldData(_ context: ReadContext) throws -> Target {
        try readEntries(
            context,
            keyCodec: Key.self,
            valueCodec: Value.self,
            ownerBytes: storedOwnerBytes(Target.self)
        )
    }
}

extension DictionarySerializer: FieldCodec where Key: FieldCodec, Value: FieldCodec {}
