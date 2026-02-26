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

private enum CollectionHeader {
    static let trackingRef: UInt8 = 0b0000_0001
    static let hasNull: UInt8 = 0b0000_0010
    static let declaredElementType: UInt8 = 0b0000_0100
    static let sameType: UInt8 = 0b0000_1000
}

private enum MapHeader {
    static let trackingKeyRef: UInt8 = 0b0000_0001
    static let keyNull: UInt8 = 0b0000_0010
    static let declaredKeyType: UInt8 = 0b0000_0100

    static let trackingValueRef: UInt8 = 0b0000_1000
    static let valueNull: UInt8 = 0b0001_0000
    static let declaredValueType: UInt8 = 0b0010_0000
}

private func primitiveArrayTypeID<Element: Serializer>(for _: Element.Type) -> TypeId? {
    if Element.self == UInt8.self { return .binary }
    if Element.self == Bool.self { return .boolArray }
    if Element.self == Int8.self { return .int8Array }
    if Element.self == Int16.self { return .int16Array }
    if Element.self == Int32.self { return .int32Array }
    if Element.self == Int64.self { return .int64Array }
    if Element.self == UInt16.self { return .uint16Array }
    if Element.self == UInt32.self { return .uint32Array }
    if Element.self == UInt64.self { return .uint64Array }
    if Element.self == Float16.self { return .float16Array }
    if Element.self == BFloat16.self { return .bfloat16Array }
    if Element.self == Float.self { return .float32Array }
    if Element.self == Double.self { return .float64Array }
    return nil
}

private let hostIsLittleEndian = Int(littleEndian: 1) == 1

@inline(__always)
private func uncheckedArrayCast<From, To>(_ array: [From], to _: To.Type) -> [To] {
    assert(From.self == To.self)
    return unsafeBitCast(array, to: [To].self)
}

@inline(__always)
private func readArrayUninitialized<Element>(
    count: Int,
    _ initializer: (UnsafeMutablePointer<Element>) throws -> Void
) rethrows -> [Element] {
    try Array<Element>(unsafeUninitializedCapacity: count) { destination, initializedCount in
        if count > 0 {
            try initializer(destination.baseAddress!)
        }
        initializedCount = count
    }
}

private func writePrimitiveArray<Element: Serializer>(_ value: [Element], context: WriteContext) {
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

private func readPrimitiveArray<Element: Serializer>(_ context: ReadContext) throws -> [Element] {
    let payloadSize = Int(try context.buffer.readVarUInt32())
    try context.ensureRemainingBytes(payloadSize, label: "\(Element.self)_array_payload")

    if Element.self == UInt8.self {
        try context.ensureCollectionLength(payloadSize, label: "uint8_array")
        let bytes = try context.buffer.readBytes(count: payloadSize)
        return uncheckedArrayCast(bytes, to: Element.self)
    }

    if Element.self == Bool.self {
        try context.ensureCollectionLength(payloadSize, label: "bool_array")
        let out = try readArrayUninitialized(count: payloadSize) { destination in
            for index in 0..<payloadSize {
                destination.advanced(by: index).initialize(to: try context.buffer.readUInt8() != 0)
            }
        }
        return uncheckedArrayCast(out, to: Element.self)
    }

    if Element.self == Int8.self {
        try context.ensureCollectionLength(payloadSize, label: "int8_array")
        var out = Array(repeating: Int8(0), count: payloadSize)
        try out.withUnsafeMutableBytes { rawBytes in
            try context.buffer.readBytes(into: rawBytes)
        }
        return uncheckedArrayCast(out, to: Element.self)
    }

    if Element.self == Int16.self {
        if payloadSize % 2 != 0 { throw ForyError.invalidData("int16 array payload size mismatch") }
        let count = payloadSize / 2
        try context.ensureCollectionLength(count, label: "int16_array")
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

    if Element.self == Int32.self {
        if payloadSize % 4 != 0 { throw ForyError.invalidData("int32 array payload size mismatch") }
        let count = payloadSize / 4
        try context.ensureCollectionLength(count, label: "int32_array")
        if hostIsLittleEndian {
            var out = Array(repeating: Int32(0), count: count)
            try out.withUnsafeMutableBytes { rawBytes in
                try context.buffer.readBytes(into: rawBytes)
            }
            return uncheckedArrayCast(out, to: Element.self)
        }
        let out = try readArrayUninitialized(count: count) { destination in
            for index in 0..<count {
                destination.advanced(by: index).initialize(to: try context.buffer.readInt32())
            }
        }
        return uncheckedArrayCast(out, to: Element.self)
    }

    if Element.self == UInt32.self {
        if payloadSize % 4 != 0 { throw ForyError.invalidData("uint32 array payload size mismatch") }
        let count = payloadSize / 4
        try context.ensureCollectionLength(count, label: "uint32_array")
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

    if Element.self == Int64.self {
        if payloadSize % 8 != 0 { throw ForyError.invalidData("int64 array payload size mismatch") }
        let count = payloadSize / 8
        try context.ensureCollectionLength(count, label: "int64_array")
        if hostIsLittleEndian {
            var out = Array(repeating: Int64(0), count: count)
            try out.withUnsafeMutableBytes { rawBytes in
                try context.buffer.readBytes(into: rawBytes)
            }
            return uncheckedArrayCast(out, to: Element.self)
        }
        let out = try readArrayUninitialized(count: count) { destination in
            for index in 0..<count {
                destination.advanced(by: index).initialize(to: try context.buffer.readInt64())
            }
        }
        return uncheckedArrayCast(out, to: Element.self)
    }

    if Element.self == UInt64.self {
        if payloadSize % 8 != 0 { throw ForyError.invalidData("uint64 array payload size mismatch") }
        let count = payloadSize / 8
        try context.ensureCollectionLength(count, label: "uint64_array")
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
        if payloadSize % 2 != 0 { throw ForyError.invalidData("uint16 array payload size mismatch") }
        let count = payloadSize / 2
        try context.ensureCollectionLength(count, label: "uint16_array")
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
        if payloadSize % 2 != 0 { throw ForyError.invalidData("float16 array payload size mismatch") }
        let count = payloadSize / 2
        try context.ensureCollectionLength(count, label: "float16_array")
        let values = try readArrayUninitialized(count: count) { destination in
            for index in 0..<count {
                destination.advanced(by: index).initialize(to: Float16(bitPattern: try context.buffer.readUInt16()))
            }
        }
        return uncheckedArrayCast(values, to: Element.self)
    }

    if Element.self == BFloat16.self {
        if payloadSize % 2 != 0 { throw ForyError.invalidData("bfloat16 array payload size mismatch") }
        let count = payloadSize / 2
        try context.ensureCollectionLength(count, label: "bfloat16_array")
        let values = try readArrayUninitialized(count: count) { destination in
            for index in 0..<count {
                destination.advanced(by: index).initialize(to: BFloat16(rawValue: try context.buffer.readUInt16()))
            }
        }
        return uncheckedArrayCast(values, to: Element.self)
    }

    if Element.self == Float.self {
        if payloadSize % 4 != 0 { throw ForyError.invalidData("float32 array payload size mismatch") }
        let count = payloadSize / 4
        try context.ensureCollectionLength(count, label: "float32_array")
        if hostIsLittleEndian {
            var out = Array(repeating: Float(0), count: count)
            try out.withUnsafeMutableBytes { rawBytes in
                try context.buffer.readBytes(into: rawBytes)
            }
            return uncheckedArrayCast(out, to: Element.self)
        }
        let out = try readArrayUninitialized(count: count) { destination in
            for index in 0..<count {
                destination.advanced(by: index).initialize(to: try context.buffer.readFloat32())
            }
        }
        return uncheckedArrayCast(out, to: Element.self)
    }

    if payloadSize % 8 != 0 { throw ForyError.invalidData("float64 array payload size mismatch") }
    let count = payloadSize / 8
    try context.ensureCollectionLength(count, label: "float64_array")
    if hostIsLittleEndian {
        var out = Array(repeating: Double(0), count: count)
        try out.withUnsafeMutableBytes { rawBytes in
            try context.buffer.readBytes(into: rawBytes)
        }
        return uncheckedArrayCast(out, to: Element.self)
    }
    let out = try readArrayUninitialized(count: count) { destination in
        for index in 0..<count {
            destination.advanced(by: index).initialize(to: try context.buffer.readFloat64())
        }
    }
    return uncheckedArrayCast(out, to: Element.self)
}

extension Array: Serializer where Element: Serializer {
    public static func foryDefault() -> [Element] {
        []
    }

    public static var staticTypeId: TypeId {
        // Primitive Swift arrays must use ARRAY ids in protocol, not LIST.
        primitiveArrayTypeID(for: Element.self) ?? .list
    }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        if primitiveArrayTypeID(for: Element.self) != nil {
            writePrimitiveArray(self, context: context)
            return
        }

        let buffer = context.buffer
        buffer.writeVarUInt32(UInt32(self.count))
        if self.isEmpty {
            return
        }

        let hasNull = Element.isNullableType && self.contains(where: { $0.foryIsNone })
        let trackRef = context.trackRef && Element.isReferenceTrackableType
        let declaredElementType = hasGenerics && !TypeId.needsTypeInfoForField(Element.staticTypeId)
        let dynamicElementType = Element.staticTypeId == .unknown

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
            try Element.foryWriteTypeInfo(context)
        }

        if dynamicElementType {
            let refMode: RefMode
            if trackRef {
                refMode = .tracking
            } else if hasNull {
                refMode = .nullOnly
            } else {
                refMode = .none
            }
            for element in self {
                try element.foryWrite(context, refMode: refMode, writeTypeInfo: true, hasGenerics: hasGenerics)
            }
            return
        }

        if trackRef {
            for element in self {
                try element.foryWrite(context, refMode: .tracking, writeTypeInfo: false, hasGenerics: hasGenerics)
            }
        } else if hasNull {
            for element in self {
                if element.foryIsNone {
                    buffer.writeInt8(RefFlag.null.rawValue)
                } else {
                    buffer.writeInt8(RefFlag.notNullValue.rawValue)
                    try element.foryWriteData(context, hasGenerics: hasGenerics)
                }
            }
        } else {
            for element in self {
                try element.foryWriteData(context, hasGenerics: hasGenerics)
            }
        }
    }

    public static func foryReadData(_ context: ReadContext) throws -> [Element] {
        if primitiveArrayTypeID(for: Element.self) != nil {
            return try readPrimitiveArray(context)
        }

        let buffer = context.buffer
        let length = Int(try buffer.readVarUInt32())
        try context.ensureCollectionLength(length, label: "array")
        if length == 0 {
            return []
        }

        let header = try buffer.readUInt8()
        let trackRef = (header & CollectionHeader.trackingRef) != 0
        let hasNull = (header & CollectionHeader.hasNull) != 0
        let declared = (header & CollectionHeader.declaredElementType) != 0
        let sameType = (header & CollectionHeader.sameType) != 0
        let canonicalizeElements = context.trackRef && !trackRef && Element.isReferenceTrackableType

        if !sameType {
            if trackRef {
                return try readArrayUninitialized(count: length) { destination in
                    for index in 0..<length {
                        destination.advanced(by: index).initialize(
                            to: try Element.foryRead(context, refMode: .tracking, readTypeInfo: true)
                        )
                    }
                }
            }

            if hasNull {
                return try readArrayUninitialized(count: length) { destination in
                    for index in 0..<length {
                        let refFlag = try buffer.readInt8()
                        if refFlag == RefFlag.null.rawValue {
                            destination.advanced(by: index).initialize(to: Element.foryDefault())
                        } else if refFlag == RefFlag.notNullValue.rawValue {
                            if canonicalizeElements {
                                let start = buffer.getCursor()
                                let value = try Element.foryRead(context, refMode: .none, readTypeInfo: true)
                                let end = buffer.getCursor()
                                destination.advanced(by: index).initialize(
                                    to: context.canonicalizeNonTrackingReference(value, start: start, end: end)
                                )
                            } else {
                                destination.advanced(by: index).initialize(
                                    to: try Element.foryRead(context, refMode: .none, readTypeInfo: true)
                                )
                            }
                        } else {
                            throw ForyError.refError("invalid nullability flag \(refFlag)")
                        }
                    }
                }
            }

            return try readArrayUninitialized(count: length) { destination in
                for index in 0..<length {
                    if canonicalizeElements {
                        let start = buffer.getCursor()
                        let value = try Element.foryRead(context, refMode: .none, readTypeInfo: true)
                        let end = buffer.getCursor()
                        destination.advanced(by: index).initialize(
                            to: context.canonicalizeNonTrackingReference(value, start: start, end: end)
                        )
                    } else {
                        destination.advanced(by: index).initialize(
                            to: try Element.foryRead(context, refMode: .none, readTypeInfo: true)
                        )
                    }
                }
            }
        }

        if !declared {
            try Element.foryReadTypeInfo(context)
        }

        if trackRef {
            let values = try readArrayUninitialized(count: length) { destination in
                for index in 0..<length {
                    destination.advanced(by: index).initialize(
                        to: try Element.foryRead(context, refMode: .tracking, readTypeInfo: false)
                    )
                }
            }
            if !declared {
                context.clearDynamicTypeInfo(for: Element.self)
            }
            return values
        }

        if hasNull {
            let values = try readArrayUninitialized(count: length) { destination in
                for index in 0..<length {
                    let refFlag = try buffer.readInt8()
                    if refFlag == RefFlag.null.rawValue {
                        destination.advanced(by: index).initialize(to: Element.foryDefault())
                    } else {
                        if canonicalizeElements {
                            let start = buffer.getCursor()
                            let value = try Element.foryReadData(context)
                            let end = buffer.getCursor()
                            destination.advanced(by: index).initialize(
                                to: context.canonicalizeNonTrackingReference(value, start: start, end: end)
                            )
                        } else {
                            destination.advanced(by: index).initialize(to: try Element.foryReadData(context))
                        }
                    }
                }
            }
            if !declared {
                context.clearDynamicTypeInfo(for: Element.self)
            }
            return values
        }

        let values = try readArrayUninitialized(count: length) { destination in
            for index in 0..<length {
                if canonicalizeElements {
                    let start = buffer.getCursor()
                    let value = try Element.foryReadData(context)
                    let end = buffer.getCursor()
                    destination.advanced(by: index).initialize(
                        to: context.canonicalizeNonTrackingReference(value, start: start, end: end)
                    )
                } else {
                    destination.advanced(by: index).initialize(to: try Element.foryReadData(context))
                }
            }
        }
        if !declared {
            context.clearDynamicTypeInfo(for: Element.self)
        }
        return values
    }
}

extension Set: Serializer where Element: Serializer & Hashable {
    public static func foryDefault() -> Set<Element> { [] }

    public static var staticTypeId: TypeId { .set }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        try Array(self).foryWriteData(context, hasGenerics: hasGenerics)
    }

    public static func foryReadData(_ context: ReadContext) throws -> Set<Element> {
        Set(try Array<Element>.foryReadData(context))
    }
}

extension Dictionary: Serializer where Key: Serializer & Hashable, Value: Serializer {
    public static func foryDefault() -> Dictionary<Key, Value> { [:] }

    public static var staticTypeId: TypeId { .map }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.buffer.writeVarUInt32(UInt32(self.count))
        if self.isEmpty {
            return
        }

        let trackKeyRef = context.trackRef && Key.isReferenceTrackableType
        let trackValueRef = context.trackRef && Value.isReferenceTrackableType
        let keyDeclared = hasGenerics && !TypeId.needsTypeInfoForField(Key.staticTypeId)
        let valueDeclared = hasGenerics && !TypeId.needsTypeInfoForField(Value.staticTypeId)
        let keyDynamicType = Key.staticTypeId == .unknown
        let valueDynamicType = Value.staticTypeId == .unknown

        if keyDynamicType || valueDynamicType {
            for pair in self {
                let keyIsNil = pair.key.foryIsNone
                let valueIsNil = pair.value.foryIsNone
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
                    if !valueDeclared {
                        if valueDynamicType {
                            try pair.value.foryWriteTypeInfo(context)
                        } else {
                            try Value.foryWriteTypeInfo(context)
                        }
                    }
                    if trackValueRef {
                        try pair.value.foryWrite(
                            context,
                            refMode: .tracking,
                            writeTypeInfo: false,
                            hasGenerics: hasGenerics
                        )
                    } else {
                        try pair.value.foryWriteData(context, hasGenerics: hasGenerics)
                    }
                    continue
                }

                if valueIsNil {
                    if !keyDeclared {
                        if keyDynamicType {
                            try pair.key.foryWriteTypeInfo(context)
                        } else {
                            try Key.foryWriteTypeInfo(context)
                        }
                    }
                    if trackKeyRef {
                        try pair.key.foryWrite(
                            context,
                            refMode: .tracking,
                            writeTypeInfo: false,
                            hasGenerics: hasGenerics
                        )
                    } else {
                        try pair.key.foryWriteData(context, hasGenerics: hasGenerics)
                    }
                    continue
                }

                context.buffer.writeUInt8(1)

                if !keyDeclared {
                    if keyDynamicType {
                        try pair.key.foryWriteTypeInfo(context)
                    } else {
                        try Key.foryWriteTypeInfo(context)
                    }
                }
                if !valueDeclared {
                    if valueDynamicType {
                        try pair.value.foryWriteTypeInfo(context)
                    } else {
                        try Value.foryWriteTypeInfo(context)
                    }
                }

                if trackKeyRef {
                    try pair.key.foryWrite(
                        context,
                        refMode: .tracking,
                        writeTypeInfo: false,
                        hasGenerics: hasGenerics
                    )
                } else {
                    try pair.key.foryWriteData(context, hasGenerics: hasGenerics)
                }
                if trackValueRef {
                    try pair.value.foryWrite(
                        context,
                        refMode: .tracking,
                        writeTypeInfo: false,
                        hasGenerics: hasGenerics
                    )
                } else {
                    try pair.value.foryWriteData(context, hasGenerics: hasGenerics)
                }
            }
            return
        }

        var iterator = makeIterator()
        var pendingPair = iterator.next()

        while let pair = pendingPair {
            let keyIsNil = pair.key.foryIsNone
            let valueIsNil = pair.value.foryIsNone

            if keyIsNil || valueIsNil {
                var header: UInt8 = 0
                if trackKeyRef {
                    header |= MapHeader.trackingKeyRef
                }
                if trackValueRef {
                    header |= MapHeader.trackingValueRef
                }
                if keyIsNil { header |= MapHeader.keyNull }
                if valueIsNil { header |= MapHeader.valueNull }
                if !keyIsNil && keyDeclared { header |= MapHeader.declaredKeyType }
                if !valueIsNil && valueDeclared { header |= MapHeader.declaredValueType }

                context.buffer.writeUInt8(header)
                if !keyIsNil {
                    if !keyDeclared {
                        try Key.foryWriteTypeInfo(context)
                    }
                    if trackKeyRef {
                        try pair.key.foryWrite(context, refMode: .tracking, writeTypeInfo: false, hasGenerics: hasGenerics)
                    } else {
                        try pair.key.foryWriteData(context, hasGenerics: hasGenerics)
                    }
                }
                if !valueIsNil {
                    if !valueDeclared {
                        try Value.foryWriteTypeInfo(context)
                    }
                    if trackValueRef {
                        try pair.value.foryWrite(context, refMode: .tracking, writeTypeInfo: false, hasGenerics: hasGenerics)
                    } else {
                        try pair.value.foryWriteData(context, hasGenerics: hasGenerics)
                    }
                }
                pendingPair = iterator.next()
                continue
            }

            var header: UInt8 = 0
            if trackKeyRef { header |= MapHeader.trackingKeyRef }
            if trackValueRef { header |= MapHeader.trackingValueRef }
            if keyDeclared { header |= MapHeader.declaredKeyType }
            if valueDeclared { header |= MapHeader.declaredValueType }

            context.buffer.writeUInt8(header)
            let chunkSizeOffset = context.buffer.count
            context.buffer.writeUInt8(0)

            if !keyDeclared {
                try Key.foryWriteTypeInfo(context)
            }
            if !valueDeclared {
                try Value.foryWriteTypeInfo(context)
            }

            var chunkSize: UInt8 = 0
            while chunkSize < UInt8.max, let current = pendingPair {
                if current.key.foryIsNone || current.value.foryIsNone {
                    break
                }
                if trackKeyRef {
                    try current.key.foryWrite(context, refMode: .tracking, writeTypeInfo: false, hasGenerics: hasGenerics)
                } else {
                    try current.key.foryWriteData(context, hasGenerics: hasGenerics)
                }
                if trackValueRef {
                    try current.value.foryWrite(context, refMode: .tracking, writeTypeInfo: false, hasGenerics: hasGenerics)
                } else {
                    try current.value.foryWriteData(context, hasGenerics: hasGenerics)
                }
                chunkSize &+= 1
                pendingPair = iterator.next()
            }
            context.buffer.setByte(at: chunkSizeOffset, to: chunkSize)
        }
    }

    public static func foryReadData(_ context: ReadContext) throws -> Dictionary<Key, Value> {
        let totalLength = Int(try context.buffer.readVarUInt32())
        try context.ensureCollectionLength(totalLength, label: "map")
        if totalLength == 0 {
            return [:]
        }

        var map: [Key: Value] = [:]
        map.reserveCapacity(Swift.min(totalLength, context.buffer.remaining))
        let keyDynamicType = Key.staticTypeId == .unknown
        let valueDynamicType = Value.staticTypeId == .unknown
        let canonicalizeValues = context.trackRef && Value.isReferenceTrackableType

        if keyDynamicType || valueDynamicType {
            var dynamicReadCount = 0
            while dynamicReadCount < totalLength {
                let header = try context.buffer.readUInt8()
                let trackKeyRef = (header & MapHeader.trackingKeyRef) != 0
                let keyNull = (header & MapHeader.keyNull) != 0
                let keyDeclared = (header & MapHeader.declaredKeyType) != 0

                let trackValueRef = (header & MapHeader.trackingValueRef) != 0
                let valueNull = (header & MapHeader.valueNull) != 0
                let valueDeclared = (header & MapHeader.declaredValueType) != 0

                if keyNull && valueNull {
                    map[Key.foryDefault()] = Value.foryDefault()
                    dynamicReadCount += 1
                    continue
                }

                if keyNull {
                    if trackValueRef || !canonicalizeValues {
                        let value = try Value.foryRead(
                            context,
                            refMode: trackValueRef ? .tracking : .none,
                            readTypeInfo: valueDynamicType || !valueDeclared
                        )
                        map[Key.foryDefault()] = value
                    } else {
                        let start = context.buffer.getCursor()
                        let value = try Value.foryRead(
                            context,
                            refMode: .none,
                            readTypeInfo: valueDynamicType || !valueDeclared
                        )
                        let end = context.buffer.getCursor()
                        map[Key.foryDefault()] = context.canonicalizeNonTrackingReference(
                            value,
                            start: start,
                            end: end
                        )
                    }
                    dynamicReadCount += 1
                    continue
                }

                if valueNull {
                    let key = try Key.foryRead(
                        context,
                        refMode: trackKeyRef ? .tracking : .none,
                        readTypeInfo: keyDynamicType || !keyDeclared
                    )
                    map[key] = Value.foryDefault()
                    dynamicReadCount += 1
                    continue
                }

                let chunkSize = Int(try context.buffer.readUInt8())
                if chunkSize > (totalLength - dynamicReadCount) {
                    throw ForyError.invalidData("map dynamic chunk size exceeds remaining entries")
                }
                if !keyDeclared {
                    try Key.foryReadTypeInfo(context)
                }
                if !valueDeclared {
                    try Value.foryReadTypeInfo(context)
                }
                for _ in 0..<chunkSize {
                    let key = try Key.foryRead(
                        context,
                        refMode: trackKeyRef ? .tracking : .none,
                        readTypeInfo: false
                    )
                    if trackValueRef || !canonicalizeValues {
                        let value = try Value.foryRead(
                            context,
                            refMode: trackValueRef ? .tracking : .none,
                            readTypeInfo: false
                        )
                        map[key] = value
                    } else {
                        let start = context.buffer.getCursor()
                        let value = try Value.foryRead(
                            context,
                            refMode: .none,
                            readTypeInfo: false
                        )
                        let end = context.buffer.getCursor()
                        map[key] = context.canonicalizeNonTrackingReference(value, start: start, end: end)
                    }
                }
                if !keyDeclared {
                    context.clearDynamicTypeInfo(for: Key.self)
                }
                if !valueDeclared {
                    context.clearDynamicTypeInfo(for: Value.self)
                }
                dynamicReadCount += chunkSize
            }
            return map
        }

        var readCount = 0
        while readCount < totalLength {
            let header = try context.buffer.readUInt8()
            let trackKeyRef = (header & MapHeader.trackingKeyRef) != 0
            let keyNull = (header & MapHeader.keyNull) != 0
            let keyDeclared = (header & MapHeader.declaredKeyType) != 0

            let trackValueRef = (header & MapHeader.trackingValueRef) != 0
            let valueNull = (header & MapHeader.valueNull) != 0
            let valueDeclared = (header & MapHeader.declaredValueType) != 0

            if keyNull && valueNull {
                map[Key.foryDefault()] = Value.foryDefault()
                readCount += 1
                continue
            }

            if keyNull {
                if trackValueRef || !canonicalizeValues {
                    let value = try Value.foryRead(
                        context,
                        refMode: trackValueRef ? .tracking : .none,
                        readTypeInfo: !valueDeclared
                    )
                    map[Key.foryDefault()] = value
                } else {
                    let start = context.buffer.getCursor()
                    let value = try Value.foryRead(
                        context,
                        refMode: .none,
                        readTypeInfo: !valueDeclared
                    )
                    let end = context.buffer.getCursor()
                    map[Key.foryDefault()] = context.canonicalizeNonTrackingReference(
                        value,
                        start: start,
                        end: end
                    )
                }
                readCount += 1
                continue
            }

            if valueNull {
                let key = try Key.foryRead(
                    context,
                    refMode: trackKeyRef ? .tracking : .none,
                    readTypeInfo: !keyDeclared
                )
                map[key] = Value.foryDefault()
                readCount += 1
                continue
            }

            let chunkSize = Int(try context.buffer.readUInt8())
            if chunkSize > (totalLength - readCount) {
                throw ForyError.invalidData("map chunk size exceeds remaining entries")
            }
            if !keyDeclared {
                try Key.foryReadTypeInfo(context)
            }
            if !valueDeclared {
                try Value.foryReadTypeInfo(context)
            }

            for _ in 0..<chunkSize {
                let key = try Key.foryRead(
                    context,
                    refMode: trackKeyRef ? .tracking : .none,
                    readTypeInfo: false
                )
                if trackValueRef || !canonicalizeValues {
                    let value = try Value.foryRead(
                        context,
                        refMode: trackValueRef ? .tracking : .none,
                        readTypeInfo: false
                    )
                    map[key] = value
                } else {
                    let start = context.buffer.getCursor()
                    let value = try Value.foryRead(
                        context,
                        refMode: .none,
                        readTypeInfo: false
                    )
                    let end = context.buffer.getCursor()
                    map[key] = context.canonicalizeNonTrackingReference(value, start: start, end: end)
                }
            }
            readCount += chunkSize
        }

        return map
    }
}
