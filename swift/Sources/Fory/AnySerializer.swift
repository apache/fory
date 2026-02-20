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

public struct ForyAnyNullValue: Serializer {
    public init() {}

    public static func foryDefault() -> ForyAnyNullValue {
        ForyAnyNullValue()
    }

    public static var staticTypeId: ForyTypeId {
        .none
    }

    public var foryIsNone: Bool {
        true
    }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = context
        _ = hasGenerics
    }

    public static func foryReadData(_ context: ReadContext) throws -> ForyAnyNullValue {
        _ = context
        return ForyAnyNullValue()
    }
}

extension AnyHashable: Serializer {
    public static func foryDefault() -> AnyHashable {
        AnyHashable(Int32(0))
    }

    public static var staticTypeId: ForyTypeId {
        .unknown
    }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        try writeAnyPayload(base, context: context, hasGenerics: hasGenerics)
    }

    public static func foryReadData(_ context: ReadContext) throws -> AnyHashable {
        guard let typeInfo = context.dynamicTypeInfo(for: Self.self) else {
            throw ForyError.invalidData("dynamic AnyHashable key requires type info")
        }
        context.clearDynamicTypeInfo(for: Self.self)
        if typeInfo.wireTypeID == .none {
            throw ForyError.invalidData("dynamic AnyHashable key cannot be null")
        }
        let decoded = try context.typeResolver.readDynamicValue(typeInfo: typeInfo, context: context)
        return try toAnyHashableKey(decoded)
    }

    public static func foryWriteTypeInfo(_ context: WriteContext) throws {
        _ = context
        throw ForyError.invalidData("dynamic AnyHashable key type info is runtime-only")
    }

    public func foryWriteTypeInfo(_ context: WriteContext) throws {
        try writeAnyTypeInfo(base, context: context)
    }

    public static func foryReadTypeInfo(_ context: ReadContext) throws {
        let typeInfo = try context.typeResolver.readDynamicTypeInfo(context: context)
        context.setDynamicTypeInfo(for: Self.self, typeInfo)
    }

    public func foryWrite(
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool,
        hasGenerics: Bool
    ) throws {
        if refMode != .none {
            context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
        }
        if writeTypeInfo {
            try foryWriteTypeInfo(context)
        }
        try foryWriteData(context, hasGenerics: hasGenerics)
    }
}

private protocol OptionalTypeMarker {
    static var noneValue: Self { get }
}

extension Optional: OptionalTypeMarker {
    static var noneValue: Optional<Wrapped> { nil }
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
            context.buffer.writeUInt8(UInt8(truncatingIfNeeded: ForyTypeId.none.rawValue))
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
                context.buffer.writeInt8(RefFlag.null.rawValue)
                return
            }
            if refMode == .tracking, anyValueIsReferenceTrackable(value), let object = value as AnyObject? {
                if context.refWriter.tryWriteReference(buffer: context.buffer, object: object) {
                    return
                }
            } else {
                context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
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
            let rawFlag = try context.buffer.readInt8()
            guard let flag = RefFlag(rawValue: rawFlag) else {
                throw ForyError.refError("invalid ref flag \(rawFlag)")
            }

            switch flag {
            case .null:
                return .foryDefault()
            case .ref:
                let refID = try context.buffer.readVarUInt32()
                let referenced = try context.refReader.readRefValue(refID)
                if let value = referenced as? DynamicAnyValue {
                    return value
                }
                if referenced is NSNull {
                    return .foryDefault()
                }
                return DynamicAnyValue(referenced)
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

private func toAnyHashableKey(_ value: Any) throws -> AnyHashable {
    if let anyHashable = value as? AnyHashable {
        return anyHashable
    }
    if value is ForyAnyNullValue {
        throw ForyError.invalidData("dynamic AnyHashable key cannot be null")
    }
    guard let hashableValue = value as? any Hashable else {
        throw ForyError.invalidData("dynamic AnyHashable key must be Hashable, got \(type(of: value))")
    }
    return AnyHashable(hashableValue)
}

private func writeAnyTypeInfo(_ value: Any, context: WriteContext) throws {
    if let serializer = value as? any Serializer {
        try serializer.foryWriteTypeInfo(context)
        return
    }

    if value is [Any] {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: ForyTypeId.list.rawValue))
        return
    }
    if value is [String: Any] || value is [Int32: Any] || value is [AnyHashable: Any] {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: ForyTypeId.map.rawValue))
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
    if let map = value as? [AnyHashable: Any] {
        // Always include key type info for dynamic map payload.
        try writeAnyHashableAnyMap(map, context: context, refMode: .none, hasGenerics: false)
        return
    }
    throw ForyError.invalidData("unsupported dynamic Any runtime type \(type(of: value))")
}

public func castAnyDynamicValue<T>(_ value: Any?, to type: T.Type) throws -> T {
    _ = type
    if value == nil {
        if T.self == Any.self {
            return ForyAnyNullValue() as! T
        }
        if T.self == AnyObject.self {
            return NSNull() as! T
        }
        if T.self == (any Serializer).self {
            return ForyAnyNullValue() as! T
        }
        if let optionalType = T.self as? any OptionalTypeMarker.Type {
            return optionalType.noneValue as! T
        }
    }

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

public func writeAnyHashableAnyMap(
    _ value: [AnyHashable: Any]?,
    context: WriteContext,
    refMode: RefMode,
    writeTypeInfo: Bool = false,
    hasGenerics: Bool = true
) throws {
    let wrapped = value?.reduce(into: [AnyHashable: DynamicAnyValue]()) { result, pair in
        result[pair.key] = DynamicAnyValue.wrapped(pair.value)
    }
    try wrapped.foryWrite(
        context,
        refMode: refMode,
        writeTypeInfo: writeTypeInfo,
        hasGenerics: hasGenerics
    )
}

public func readAnyHashableAnyMap(
    context: ReadContext,
    refMode: RefMode,
    readTypeInfo: Bool = false
) throws -> [AnyHashable: Any]? {
    let wrapped: [AnyHashable: DynamicAnyValue]? = try [AnyHashable: DynamicAnyValue]?.foryRead(
        context,
        refMode: refMode,
        readTypeInfo: readTypeInfo
    )
    guard let wrapped else {
        return nil
    }
    var map: [AnyHashable: Any] = [:]
    map.reserveCapacity(wrapped.count)
    for pair in wrapped {
        map[pair.key] = pair.value.anyValueForCollection()
    }
    return map
}

func readDynamicAnyMapValue(context: ReadContext) throws -> Any {
    let map = try readAnyHashableAnyMap(context: context, refMode: .none) ?? [:]
    if map.isEmpty {
        return [String: Any]()
    }
    var stringMap: [String: Any] = [:]
    stringMap.reserveCapacity(map.count)
    for pair in map {
        guard let key = pair.key.base as? String else {
            stringMap.removeAll(keepingCapacity: false)
            break
        }
        stringMap[key] = pair.value
    }
    if stringMap.count == map.count {
        return stringMap
    }

    var int32Map: [Int32: Any] = [:]
    int32Map.reserveCapacity(map.count)
    for pair in map {
        guard let key = pair.key.base as? Int32 else {
            return map
        }
        int32Map[key] = pair.value
    }
    if int32Map.count == map.count {
        return int32Map
    }

    return map
}
