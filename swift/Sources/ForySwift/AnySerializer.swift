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

private enum DynamicAnyMapHeader {
    static let keyNull: UInt8 = 0b0000_0010
    static let declaredKeyType: UInt8 = 0b0000_0100
    static let valueNull: UInt8 = 0b0001_0000
}

public struct ForyAnyNullValue {
    public init() {}
}

private struct DynamicAnyValue: Serializer {
    var value: Any = ForyAnyNullValue()

    init(_ value: Any) {
        self.value = value
    }

    static func foryDefault() -> DynamicAnyValue {
        DynamicAnyValue(ForyAnyNullValue())
    }

    static var staticTypeId: ForyTypeId {
        .unknown
    }

    static var isNullableType: Bool {
        true
    }

    static var isReferenceTrackableType: Bool {
        true
    }

    var foryIsNone: Bool {
        value is ForyAnyNullValue
    }

    static func wrapped(_ value: Any?) -> DynamicAnyValue {
        guard let value else {
            return .foryDefault()
        }
        guard let unwrapped = unwrapOptionalAny(value) else {
            return .foryDefault()
        }
        if unwrapped is NSNull {
            return .foryDefault()
        }
        return DynamicAnyValue(unwrapped)
    }

    func anyValue() -> Any? {
        foryIsNone ? nil : value
    }

    func anyValueForCollection() -> Any {
        foryIsNone ? NSNull() : value
    }

    func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        if foryIsNone {
            return
        }
        try writeAnyPayload(value, context: context, hasGenerics: hasGenerics)
    }

    static func foryReadData(_ context: ReadContext) throws -> DynamicAnyValue {
        guard let typeInfo = context.dynamicTypeInfo(for: Self.self) else {
            throw ForyError.invalidData("dynamic Any value requires type info")
        }
        context.clearDynamicTypeInfo(for: Self.self)
        if typeInfo.wireTypeID == .none {
            return .foryDefault()
        }
        return DynamicAnyValue(try context.typeResolver.readDynamicValue(typeInfo: typeInfo, context: context))
    }

    static func foryWriteTypeInfo(_ context: WriteContext) throws {
        _ = context
        throw ForyError.invalidData("dynamic Any value type info is runtime-only")
    }

    func foryWriteTypeInfo(_ context: WriteContext) throws {
        if foryIsNone {
            context.writer.writeUInt8(UInt8(truncatingIfNeeded: ForyTypeId.none.rawValue))
            return
        }
        try writeAnyTypeInfo(value, context: context)
    }

    static func foryReadTypeInfo(_ context: ReadContext) throws {
        let typeInfo = try context.typeResolver.readDynamicTypeInfo(context: context)
        context.setDynamicTypeInfo(for: Self.self, typeInfo)
    }

    func foryWrite(
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool,
        hasGenerics: Bool
    ) throws {
        if refMode != .none {
            if foryIsNone {
                context.writer.writeInt8(RefFlag.null.rawValue)
                return
            }
            if refMode == .tracking, anyValueIsReferenceTrackable(value), let object = value as AnyObject? {
                if context.refWriter.tryWriteReference(writer: context.writer, object: object) {
                    return
                }
            } else {
                context.writer.writeInt8(RefFlag.notNullValue.rawValue)
            }
        }

        if writeTypeInfo {
            try foryWriteTypeInfo(context)
        }
        try foryWriteData(context, hasGenerics: hasGenerics)
    }

    static func foryRead(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> DynamicAnyValue {
        if refMode != .none {
            let rawFlag = try context.reader.readInt8()
            guard let flag = RefFlag(rawValue: rawFlag) else {
                throw ForyError.refError("invalid ref flag \(rawFlag)")
            }

            switch flag {
            case .null:
                return .foryDefault()
            case .ref:
                let refID = try context.reader.readVarUInt32()
                let referenced = try context.refReader.readRefValue(refID)
                guard let value = referenced as? DynamicAnyValue else {
                    throw ForyError.refError("ref_id \(refID) has unexpected runtime type")
                }
                return value
            case .refValue:
                let reservedRefID = context.refReader.reserveRefID()
                context.pushPendingReference(reservedRefID)
                if readTypeInfo {
                    try foryReadTypeInfo(context)
                }
                let value = try foryReadData(context)
                context.finishPendingReferenceIfNeeded(value)
                context.popPendingReference()
                return value
            case .notNullValue:
                break
            }
        }

        if readTypeInfo {
            try foryReadTypeInfo(context)
        }
        return try foryReadData(context)
    }
}

private func unwrapOptionalAny(_ value: Any) -> Any? {
    let mirror = Mirror(reflecting: value)
    guard mirror.displayStyle == .optional else {
        return value
    }
    guard let (_, child) = mirror.children.first else {
        return nil
    }
    return child
}

private func anyValueIsReferenceTrackable(_ value: Any) -> Bool {
    guard let serializer = value as? any Serializer else {
        return false
    }
    return type(of: serializer).isReferenceTrackableType
}

private func writeAnyTypeInfo(_ value: Any, context: WriteContext) throws {
    if let serializer = value as? any Serializer {
        try serializer.foryWriteTypeInfo(context)
        return
    }

    if value is [Any] {
        context.writer.writeUInt8(UInt8(truncatingIfNeeded: ForyTypeId.list.rawValue))
        return
    }
    if value is [String: Any] || value is [Int32: Any] {
        context.writer.writeUInt8(UInt8(truncatingIfNeeded: ForyTypeId.map.rawValue))
        return
    }

    throw ForyError.invalidData("unsupported dynamic Any runtime type \(type(of: value))")
}

private func writeAnyPayload(_ value: Any, context: WriteContext, hasGenerics: Bool) throws {
    if let serializer = value as? any Serializer {
        try serializer.foryWriteData(context, hasGenerics: hasGenerics)
        return
    }
    if let list = value as? [Any] {
        try writeAnyList(list, context: context, refMode: .none, hasGenerics: hasGenerics)
        return
    }
    if let map = value as? [String: Any] {
        // Always include key type info for dynamic map payload.
        try writeStringAnyMap(map, context: context, refMode: .none, hasGenerics: false)
        return
    }
    if let map = value as? [Int32: Any] {
        // Always include key type info for dynamic map payload.
        try writeInt32AnyMap(map, context: context, refMode: .none, hasGenerics: false)
        return
    }
    throw ForyError.invalidData("unsupported dynamic Any runtime type \(type(of: value))")
}

public func castAnyDynamicValue<T>(_ value: Any?, to type: T.Type) throws -> T {
    guard let typed = value as? T else {
        throw ForyError.invalidData("cannot cast dynamic Any value to \(type)")
    }
    return typed
}

public func writeAny(
    _ value: Any?,
    context: WriteContext,
    refMode: RefMode,
    writeTypeInfo: Bool = true,
    hasGenerics: Bool = false
) throws {
    try DynamicAnyValue.wrapped(value).foryWrite(
        context,
        refMode: refMode,
        writeTypeInfo: writeTypeInfo,
        hasGenerics: hasGenerics
    )
}

public func readAny(
    context: ReadContext,
    refMode: RefMode,
    readTypeInfo: Bool = true
) throws -> Any? {
    try DynamicAnyValue.foryRead(context, refMode: refMode, readTypeInfo: readTypeInfo).anyValue()
}

public func writeAnyList(
    _ value: [Any]?,
    context: WriteContext,
    refMode: RefMode,
    writeTypeInfo: Bool = false,
    hasGenerics: Bool = true
) throws {
    let wrapped = value?.map { DynamicAnyValue.wrapped($0) }
    try wrapped.foryWrite(
        context,
        refMode: refMode,
        writeTypeInfo: writeTypeInfo,
        hasGenerics: hasGenerics
    )
}

public func readAnyList(
    context: ReadContext,
    refMode: RefMode,
    readTypeInfo: Bool = false
) throws -> [Any]? {
    let wrapped: [DynamicAnyValue]? = try [DynamicAnyValue]?.foryRead(
        context,
        refMode: refMode,
        readTypeInfo: readTypeInfo
    )
    return wrapped?.map { $0.anyValueForCollection() }
}

public func writeStringAnyMap(
    _ value: [String: Any]?,
    context: WriteContext,
    refMode: RefMode,
    writeTypeInfo: Bool = false,
    hasGenerics: Bool = true
) throws {
    let wrapped = value?.reduce(into: [String: DynamicAnyValue]()) { result, pair in
        result[pair.key] = DynamicAnyValue.wrapped(pair.value)
    }
    try wrapped.foryWrite(
        context,
        refMode: refMode,
        writeTypeInfo: writeTypeInfo,
        hasGenerics: hasGenerics
    )
}

public func readStringAnyMap(
    context: ReadContext,
    refMode: RefMode,
    readTypeInfo: Bool = false
) throws -> [String: Any]? {
    let wrapped: [String: DynamicAnyValue]? = try [String: DynamicAnyValue]?.foryRead(
        context,
        refMode: refMode,
        readTypeInfo: readTypeInfo
    )
    guard let wrapped else {
        return nil
    }
    var map: [String: Any] = [:]
    map.reserveCapacity(wrapped.count)
    for pair in wrapped {
        map[pair.key] = pair.value.anyValueForCollection()
    }
    return map
}

public func writeInt32AnyMap(
    _ value: [Int32: Any]?,
    context: WriteContext,
    refMode: RefMode,
    writeTypeInfo: Bool = false,
    hasGenerics: Bool = true
) throws {
    let wrapped = value?.reduce(into: [Int32: DynamicAnyValue]()) { result, pair in
        result[pair.key] = DynamicAnyValue.wrapped(pair.value)
    }
    try wrapped.foryWrite(
        context,
        refMode: refMode,
        writeTypeInfo: writeTypeInfo,
        hasGenerics: hasGenerics
    )
}

public func readInt32AnyMap(
    context: ReadContext,
    refMode: RefMode,
    readTypeInfo: Bool = false
) throws -> [Int32: Any]? {
    let wrapped: [Int32: DynamicAnyValue]? = try [Int32: DynamicAnyValue]?.foryRead(
        context,
        refMode: refMode,
        readTypeInfo: readTypeInfo
    )
    guard let wrapped else {
        return nil
    }
    var map: [Int32: Any] = [:]
    map.reserveCapacity(wrapped.count)
    for pair in wrapped {
        map[pair.key] = pair.value.anyValueForCollection()
    }
    return map
}

func readDynamicAnyMapValue(context: ReadContext) throws -> Any {
    let mapStart = context.reader.getCursor()
    let keyTypeID = try peekDynamicMapKeyTypeID(context: context)
    context.reader.setCursor(mapStart)

    switch keyTypeID {
    case .int32, .varint32:
        return try readInt32AnyMap(context: context, refMode: .none) ?? [:]
    case nil, .string:
        return try readStringAnyMap(context: context, refMode: .none) ?? [:]
    default:
        throw ForyError.invalidData("unsupported dynamic map key type \(String(describing: keyTypeID))")
    }
}

private func peekDynamicMapKeyTypeID(context: ReadContext) throws -> ForyTypeId? {
    let start = context.reader.getCursor()
    defer {
        context.reader.setCursor(start)
    }

    let length = Int(try context.reader.readVarUInt32())
    if length == 0 {
        return nil
    }

    let header = try context.reader.readUInt8()
    let keyNull = (header & DynamicAnyMapHeader.keyNull) != 0
    let keyDeclared = (header & DynamicAnyMapHeader.declaredKeyType) != 0
    let valueNull = (header & DynamicAnyMapHeader.valueNull) != 0

    if keyDeclared {
        return nil
    }
    if keyNull {
        return nil
    }

    if !valueNull {
        _ = try context.reader.readUInt8()
    }

    let rawTypeID = try context.reader.readVarUInt32()
    return ForyTypeId(rawValue: rawTypeID)
}
