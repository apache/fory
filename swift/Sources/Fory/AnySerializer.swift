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

    public static var staticTypeId: TypeId {
        .none
    }

    public static func foryWriteStaticTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func foryReadTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
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

    public static var staticTypeId: TypeId {
        .unknown
    }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        try writeAnyPayload(base, context: context, hasGenerics: hasGenerics)
    }

    public static func foryReadData(_ context: ReadContext) throws -> AnyHashable {
        _ = context
        throw ForyError.invalidData(
            "dynamic AnyHashable key read requires type info; foryReadData should not be called directly"
        )
    }

    public static func foryReadCompatibleData(_ context: ReadContext, remoteTypeInfo: TypeInfo) throws -> AnyHashable {
        let typeInfo = remoteTypeInfo
        if typeInfo.typeID == .none {
            throw ForyError.invalidData("dynamic AnyHashable key cannot be null")
        }
        let decoded = try context.readAnyValue(typeInfo: typeInfo)
        return try toAnyHashableKey(decoded)
    }

    public static func foryWriteStaticTypeInfo(_ context: WriteContext) throws {
        _ = context
        throw ForyError.invalidData("dynamic AnyHashable key type info is runtime-only")
    }

    public func foryWriteTypeInfo(_ context: WriteContext) throws {
        try writeAnyTypeInfo(base, context: context)
    }

    public static func foryReadTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readTypeInfo()
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
    static var noneValue: Wrapped? { nil }
}

struct SerializableAny: Serializer {
    var value: Any = ForyAnyNullValue()

    init(_ value: Any) {
        self.value = value
    }

    static func foryDefault() -> SerializableAny {
        SerializableAny(ForyAnyNullValue())
    }

    static var staticTypeId: TypeId {
        .unknown
    }

    static var isNullableType: Bool {
        true
    }

    static var isRefType: Bool {
        true
    }

    var foryIsNone: Bool {
        value is ForyAnyNullValue
    }

    static func wrapped(_ value: Any?) -> SerializableAny {
        guard let value else {
            return .foryDefault()
        }
        guard let unwrapped = unwrapOptionalAny(value) else {
            return .foryDefault()
        }
        if unwrapped is NSNull {
            return .foryDefault()
        }
        return SerializableAny(unwrapped)
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

    static func foryReadData(_ context: ReadContext) throws -> SerializableAny {
        _ = context
        throw ForyError.invalidData(
            "dynamic Any read requires type info; foryReadData should not be called directly"
        )
    }

    static func foryReadCompatibleData(_ context: ReadContext, remoteTypeInfo: TypeInfo) throws -> SerializableAny {
        let typeInfo = remoteTypeInfo
        if typeInfo.typeID == .none {
            return .foryDefault()
        }
        return SerializableAny(try context.readAnyValue(typeInfo: typeInfo))
    }

    static func foryWriteStaticTypeInfo(_ context: WriteContext) throws {
        _ = context
        throw ForyError.invalidData("dynamic Any value type info is runtime-only")
    }

    func foryWriteTypeInfo(_ context: WriteContext) throws {
        if foryIsNone {
            context.buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.none.rawValue))
            return
        }
        try writeAnyTypeInfo(value, context: context)
    }

    static func foryReadTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readTypeInfo()
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
            context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
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
    ) throws -> SerializableAny {
        @inline(__always)
        func requireDynamicTypeInfo() throws -> TypeInfo {
            if readTypeInfo {
                guard let remoteTypeInfo = try foryReadTypeInfo(context) else {
                    throw ForyError.invalidData("dynamic Any value requires type info")
                }
                return remoteTypeInfo
            }
            guard let remoteTypeInfo = context.getTypeInfo(for: Self.self) else {
                throw ForyError.invalidData("dynamic Any value requires type info")
            }
            return remoteTypeInfo
        }

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
                if let value = referenced as? SerializableAny {
                    return value
                }
                if referenced is NSNull {
                    return .foryDefault()
                }
                return SerializableAny(referenced)
            case .refValue:
                let reservedRefID = context.trackRef ? context.refReader.reserveRefID() : nil
                let remoteTypeInfo = try requireDynamicTypeInfo()
                let value = try foryReadCompatibleData(context, remoteTypeInfo: remoteTypeInfo)
                if let reservedRefID {
                    if let object = value.value as AnyObject? {
                        context.refReader.storeRef(object, at: reservedRefID)
                    } else {
                        context.refReader.storeRef(value, at: reservedRefID)
                    }
                }
                return value
            case .notNullValue:
                break
            }
        }

        return try foryReadCompatibleData(context, remoteTypeInfo: requireDynamicTypeInfo())
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

@inline(never)
private func hasExactRuntimeType<T>(_ value: Any, _: T.Type) -> Bool {
    Swift.type(of: value) == T.self
}

@inline(never)
private func writePrimitiveArrayAnyTypeInfo(_ value: Any, context: WriteContext) -> Bool {
    if hasExactRuntimeType(value, [Bool].self) {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.boolArray.rawValue))
        return true
    }
    if hasExactRuntimeType(value, [Int8].self) {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.int8Array.rawValue))
        return true
    }
    if hasExactRuntimeType(value, [Int16].self) {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.int16Array.rawValue))
        return true
    }
    if hasExactRuntimeType(value, [Int32].self) {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.int32Array.rawValue))
        return true
    }
    if hasExactRuntimeType(value, [Int64].self) {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.int64Array.rawValue))
        return true
    }
    if hasExactRuntimeType(value, [UInt8].self) {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.uint8Array.rawValue))
        return true
    }
    if hasExactRuntimeType(value, [UInt16].self) {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.uint16Array.rawValue))
        return true
    }
    if hasExactRuntimeType(value, [UInt32].self) {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.uint32Array.rawValue))
        return true
    }
    if hasExactRuntimeType(value, [UInt64].self) {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.uint64Array.rawValue))
        return true
    }
    if hasExactRuntimeType(value, [Float16].self) {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.float16Array.rawValue))
        return true
    }
    if hasExactRuntimeType(value, [BFloat16].self) {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.bfloat16Array.rawValue))
        return true
    }
    if hasExactRuntimeType(value, [Float].self) {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.float32Array.rawValue))
        return true
    }
    if hasExactRuntimeType(value, [Double].self) {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.float64Array.rawValue))
        return true
    }
    return false
}

@inline(never)
private func writePrimitiveArrayAnyPayload(_ value: Any, context: WriteContext) -> Bool {
    if hasExactRuntimeType(value, [Bool].self), let array = value as? [Bool] {
        writePrimitiveArray(array, context: context)
        return true
    }
    if hasExactRuntimeType(value, [Int8].self), let array = value as? [Int8] {
        writePrimitiveArray(array, context: context)
        return true
    }
    if hasExactRuntimeType(value, [Int16].self), let array = value as? [Int16] {
        writePrimitiveArray(array, context: context)
        return true
    }
    if hasExactRuntimeType(value, [Int32].self), let array = value as? [Int32] {
        writePrimitiveArray(array, context: context)
        return true
    }
    if hasExactRuntimeType(value, [Int64].self), let array = value as? [Int64] {
        writePrimitiveArray(array, context: context)
        return true
    }
    if hasExactRuntimeType(value, [UInt8].self), let array = value as? [UInt8] {
        writePrimitiveArray(array, context: context)
        return true
    }
    if hasExactRuntimeType(value, [UInt16].self), let array = value as? [UInt16] {
        writePrimitiveArray(array, context: context)
        return true
    }
    if hasExactRuntimeType(value, [UInt32].self), let array = value as? [UInt32] {
        writePrimitiveArray(array, context: context)
        return true
    }
    if hasExactRuntimeType(value, [UInt64].self), let array = value as? [UInt64] {
        writePrimitiveArray(array, context: context)
        return true
    }
    if hasExactRuntimeType(value, [Float16].self), let array = value as? [Float16] {
        writePrimitiveArray(array, context: context)
        return true
    }
    if hasExactRuntimeType(value, [BFloat16].self), let array = value as? [BFloat16] {
        writePrimitiveArray(array, context: context)
        return true
    }
    if hasExactRuntimeType(value, [Float].self), let array = value as? [Float] {
        writePrimitiveArray(array, context: context)
        return true
    }
    if hasExactRuntimeType(value, [Double].self), let array = value as? [Double] {
        writePrimitiveArray(array, context: context)
        return true
    }
    return false
}

private func writeAnyTypeInfo(_ value: Any, context: WriteContext) throws {
    if writePrimitiveArrayAnyTypeInfo(value, context: context) {
        return
    }

    if let serializer = value as? any Serializer {
        try serializer.foryWriteTypeInfo(context)
        return
    }

    if value is [Any] {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.list.rawValue))
        return
    }
    if value is [String: Any] || value is [Int32: Any] || value is [AnyHashable: Any] {
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.map.rawValue))
        return
    }

    throw ForyError.invalidData("unsupported dynamic Any runtime type \(type(of: value))")
}

private func writeAnyPayload(_ value: Any, context: WriteContext, hasGenerics: Bool) throws {
    try context.enterDynamicAnyDepth()
    defer { context.leaveDynamicAnyDepth() }

    if writePrimitiveArrayAnyPayload(value, context: context) {
        return
    }

    if let serializer = value as? any Serializer {
        if type(of: serializer).isRefType {
            try serializer.foryWrite(
                context,
                refMode: .tracking,
                writeTypeInfo: false,
                hasGenerics: hasGenerics
            )
        } else {
            try serializer.foryWriteData(context, hasGenerics: hasGenerics)
        }
        return
    }
    if let list = value as? [Any] {
        try writeListOfAny(list, context: context, refMode: .none, hasGenerics: hasGenerics)
        return
    }
    if let map = value as? [String: Any] {
        // Always include key type info for dynamic map payload.
        try writeMapStringToAny(map, context: context, refMode: .none, hasGenerics: false)
        return
    }
    if let map = value as? [Int32: Any] {
        // Always include key type info for dynamic map payload.
        try writeMapInt32ToAny(map, context: context, refMode: .none, hasGenerics: false)
        return
    }
    if let map = value as? [AnyHashable: Any] {
        // Always include key type info for dynamic map payload.
        try writeMapAnyHashableToAny(map, context: context, refMode: .none, hasGenerics: false)
        return
    }
    throw ForyError.invalidData("unsupported dynamic Any runtime type \(type(of: value))")
}

public func castAnyDynamicValue<T>(_ value: Any?, to type: T.Type) throws -> T {
    _ = type
    func castNilSentinel(_ sentinel: Any) throws -> T {
        guard let casted = sentinel as? T else {
            throw ForyError.invalidData("cannot cast dynamic Any value to \(type)")
        }
        return casted
    }

    if value == nil {
        if T.self == Any.self {
            return try castNilSentinel(ForyAnyNullValue())
        }
        if T.self == AnyObject.self {
            return try castNilSentinel(NSNull())
        }
        if T.self == (any Serializer).self {
            return try castNilSentinel(ForyAnyNullValue())
        }
        if let optionalType = T.self as? any OptionalTypeMarker.Type {
            return try castNilSentinel(optionalType.noneValue)
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
    try SerializableAny.wrapped(value).foryWrite(
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
    try SerializableAny.foryRead(context, refMode: refMode, readTypeInfo: readTypeInfo).anyValue()
}

public func writeListOfAny(
    _ value: [Any]?,
    context: WriteContext,
    refMode: RefMode,
    writeTypeInfo: Bool = false,
    hasGenerics: Bool = true
) throws {
    let wrapped = value?.map { SerializableAny.wrapped($0) }
    try wrapped.foryWrite(
        context,
        refMode: refMode,
        writeTypeInfo: writeTypeInfo,
        hasGenerics: hasGenerics
    )
}

public func readListOfAny(
    context: ReadContext,
    refMode: RefMode,
    readTypeInfo: Bool = false
) throws -> [Any]? {
    let wrapped: [SerializableAny]? = try [SerializableAny]?.foryRead(
        context,
        refMode: refMode,
        readTypeInfo: readTypeInfo
    )
    return wrapped?.map { $0.anyValueForCollection() }
}

public func writeMapStringToAny(
    _ value: [String: Any]?,
    context: WriteContext,
    refMode: RefMode,
    writeTypeInfo: Bool = false,
    hasGenerics: Bool = true
) throws {
    let wrapped = value?.reduce(into: [String: SerializableAny]()) { result, pair in
        result[pair.key] = SerializableAny.wrapped(pair.value)
    }
    try wrapped.foryWrite(
        context,
        refMode: refMode,
        writeTypeInfo: writeTypeInfo,
        hasGenerics: hasGenerics
    )
}

public func readMapStringToAny(
    context: ReadContext,
    refMode: RefMode,
    readTypeInfo: Bool = false
) throws -> [String: Any]? {
    let wrapped: [String: SerializableAny]? = try [String: SerializableAny]?.foryRead(
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

public func writeMapInt32ToAny(
    _ value: [Int32: Any]?,
    context: WriteContext,
    refMode: RefMode,
    writeTypeInfo: Bool = false,
    hasGenerics: Bool = true
) throws {
    let wrapped = value?.reduce(into: [Int32: SerializableAny]()) { result, pair in
        result[pair.key] = SerializableAny.wrapped(pair.value)
    }
    try wrapped.foryWrite(
        context,
        refMode: refMode,
        writeTypeInfo: writeTypeInfo,
        hasGenerics: hasGenerics
    )
}

public func readMapInt32ToAny(
    context: ReadContext,
    refMode: RefMode,
    readTypeInfo: Bool = false
) throws -> [Int32: Any]? {
    let wrapped: [Int32: SerializableAny]? = try [Int32: SerializableAny]?.foryRead(
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

public func writeMapAnyHashableToAny(
    _ value: [AnyHashable: Any]?,
    context: WriteContext,
    refMode: RefMode,
    writeTypeInfo: Bool = false,
    hasGenerics: Bool = true
) throws {
    let wrapped = value?.reduce(into: [AnyHashable: SerializableAny]()) { result, pair in
        result[pair.key] = SerializableAny.wrapped(pair.value)
    }
    try wrapped.foryWrite(
        context,
        refMode: refMode,
        writeTypeInfo: writeTypeInfo,
        hasGenerics: hasGenerics
    )
}

public func readMapAnyHashableToAny(
    context: ReadContext,
    refMode: RefMode,
    readTypeInfo: Bool = false
) throws -> [AnyHashable: Any]? {
    let wrapped: [AnyHashable: SerializableAny]? = try [AnyHashable: SerializableAny]?.foryRead(
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
    let map = try readMapAnyHashableToAny(context: context, refMode: .none) ?? [:]
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
