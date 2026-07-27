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

public struct ForyAnyNullValue: Hashable, Serializer {
    public typealias Target = Self

    public init() {}

    public static var staticTypeId: TypeId { .none }
    public static var isNullableType: Bool { true }

    public static func isNone(_: Self) -> Bool { true }

    public static func defaultValue(_: ReadContext) throws -> Self {
        Self()
    }

    public static func writeData(_: Self, _: WriteContext) throws {}

    public static func readData(_: ReadContext) throws -> Self {
        Self()
    }
}

private protocol DynamicOptional {
    static var dynamicNone: Any { get }
    var dynamicWrapped: Any? { get }
}

extension Optional: DynamicOptional {
    static var dynamicNone: Any { Self.none as Any }

    var dynamicWrapped: Any? {
        switch self {
        case .some(let value):
            return value
        case .none:
            return nil
        }
    }
}

@inline(__always)
private func unwrapDynamicTarget(_ value: Any) -> Any? {
    if value is ForyAnyNullValue || value is NSNull {
        return nil
    }
    if let optional = value as? any DynamicOptional {
        guard let wrapped = optional.dynamicWrapped else {
            return nil
        }
        return unwrapDynamicTarget(wrapped)
    }
    if Swift.type(of: value) == AnyHashable.self,
        let anyHashable = value as? AnyHashable
    {
        return unwrapDynamicTarget(anyHashable.base)
    }
    return value
}

@inline(never)
private func unsupportedDynamicBody(_ operation: String) throws -> Never {
    throw ForyError.invalidData(
        "DynamicSerializer.\(operation) requires complete-value dynamic type information")
}

@inline(never)
private func missingDynamicDefault<T>(_ type: T.Type) throws -> Never {
    throw ForyError.invalidData("dynamic target \(type) has no default value")
}

@inline(never)
private func missingDynamicTypeInfo() throws -> Never {
    throw ForyError.invalidData("dynamic value requires concrete type information")
}

@inline(never)
private func nullDynamicWithoutEnvelope() throws -> Never {
    throw ForyError.invalidData("null dynamic value cannot use RefMode.none")
}

@inline(never)
private func invalidDynamicRefFlag(_ rawFlag: Int8) throws -> Never {
    throw ForyError.refError("invalid ref flag \(rawFlag)")
}

@inline(never)
private func dynamicTargetMismatch<T>(_ value: Any, _ type: T.Type) throws -> Never {
    throw ForyError.invalidData(
        "dynamic target \(Swift.type(of: value)) does not conform to requested type \(type)")
}

@inline(never)
private func valueTargetAsAnyObject(_ typeInfo: TypeInfo) throws -> Never {
    throw ForyError.invalidData(
        "dynamic value type \(typeInfo.typeID) cannot be materialized as AnyObject")
}

@inline(never)
private func valueTargetAsAnyObject(_ type: Any.Type) throws -> Never {
    throw ForyError.invalidData(
        "dynamic value type \(type) cannot be materialized as AnyObject")
}

@inline(__always)
private func dynamicDefault<T>(_ type: T.Type) throws -> T {
    let value: Any
    if type == Any.self {
        value = ForyAnyNullValue()
    } else if type == AnyObject.self {
        value = NSNull()
    } else if type == AnyHashable.self {
        value = AnyHashable(ForyAnyNullValue())
    } else if let optionalType = type as? any DynamicOptional.Type {
        value = optionalType.dynamicNone
    } else {
        try missingDynamicDefault(type)
    }
    guard let typed = value as? T else {
        try dynamicTargetMismatch(value, type)
    }
    return typed
}

@inline(__always)
private func castDynamicTarget<T>(
    _ value: Any,
    to type: T.Type,
    typeInfo: TypeInfo? = nil
) throws -> T {
    if type == AnyObject.self {
        if let typeInfo {
            if !typeInfo.isRefType {
                try valueTargetAsAnyObject(typeInfo)
            }
        } else if !(Swift.type(of: value) is AnyObject.Type) {
            try valueTargetAsAnyObject(Swift.type(of: value))
        }
    }
    if type == AnyHashable.self {
        let hashable = try toAnyHashable(value)
        guard let typed = hashable as? T else {
            try dynamicTargetMismatch(value, type)
        }
        return typed
    }
    guard let typed = value as? T else {
        try dynamicTargetMismatch(value, type)
    }
    return typed
}

@inline(never)
private func toAnyHashable(_ value: Any) throws -> AnyHashable {
    if let value = value as? AnyHashable {
        return value
    }
    guard let value = value as? any Hashable else {
        throw ForyError.invalidData(
            "dynamic AnyHashable target must be Hashable, got \(Swift.type(of: value))")
    }
    return AnyHashable(value)
}

@usableFromInline
@inline(__always)
internal func writeDynamicTypeInfo<T>(
    for value: T,
    _ context: WriteContext
) throws -> TypeInfo {
    guard let target = unwrapDynamicTarget(value) else {
        try missingDynamicTypeInfo()
    }
    let typeInfo = try context.typeInfo(forTarget: Swift.type(of: target))
    try typeInfo.writeTypeInfo(context)
    return typeInfo
}

@usableFromInline
@inline(__always)
internal func writeDynamicValue<T>(
    _ value: T,
    typeInfo: TypeInfo,
    _ context: WriteContext,
    refMode: RefMode
) throws {
    guard let target = unwrapDynamicTarget(value) else {
        guard refMode != .none else {
            try nullDynamicWithoutEnvelope()
        }
        context.buffer.writeInt8(RefFlag.null.rawValue)
        return
    }

    if refMode != .none {
        context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
    }

    try context.enterDynamicAnyDepth()
    defer { context.leaveDynamicAnyDepth() }
    try typeInfo.writeDynamic(target, context)
}

@inline(__always)
private func readDynamicValue<S: Serializer>(
    _ context: ReadContext,
    as serializer: S.Type,
    refMode: RefMode,
    readTypeInfo: Bool
) throws -> S.Target {
    let reservedRefID: UInt32?
    if refMode != .none {
        let rawFlag = try context.buffer.readInt8()
        guard let flag = RefFlag(rawValue: rawFlag) else {
            try invalidDynamicRefFlag(rawFlag)
        }
        switch flag {
        case .null:
            return try serializer.defaultValue(context)
        case .ref:
            let refID = try context.buffer.readVarUInt32()
            let value = try context.refReader.readRefValue(refID)
            return try castDynamicTarget(value, to: S.Target.self)
        case .refValue:
            reservedRefID = context.trackRef ? context.refReader.reserveRefID() : nil
        case .notNullValue:
            reservedRefID = nil
        }
    } else {
        reservedRefID = nil
    }

    try context.enterDynamicAnyDepth()
    defer { context.leaveDynamicAnyDepth() }

    let typeInfo: TypeInfo
    if readTypeInfo {
        typeInfo = try context.readTypeInfo()
    } else if let scoped = context.getTypeInfo(for: serializer) {
        typeInfo = scoped
    } else {
        try missingDynamicTypeInfo()
    }

    if S.Target.self == AnyObject.self, !typeInfo.isRefType {
        try valueTargetAsAnyObject(typeInfo)
    }
    let value = try typeInfo.readDynamic(context)
    if let reservedRefID {
        context.refReader.storeRef(value, at: reservedRefID)
    }
    return try castDynamicTarget(value, to: S.Target.self, typeInfo: typeInfo)
}

/// Serializes a dynamic value by resolving its registered concrete target type.
///
/// Select this serializer explicitly for `Any`, `AnyObject`, application
/// protocol existentials, and dynamic children of carrier serializers.
public enum DynamicSerializer<T>: Serializer {
    public typealias Target = T

    public static var staticTypeId: TypeId { .unknown }
    public static var isNullableType: Bool { true }
    public static var isRefType: Bool { true }

    public static func isNone(_ value: T) -> Bool {
        unwrapDynamicTarget(value) == nil
    }

    public static func defaultValue(_: ReadContext) throws -> T {
        try dynamicDefault(T.self)
    }

    public static func writeData(_: T, _: WriteContext) throws {
        try unsupportedDynamicBody("writeData")
    }

    public static func readData(_: ReadContext) throws -> T {
        try unsupportedDynamicBody("readData")
    }

    public static func writeTypeInfo(_: WriteContext) throws {
        try unsupportedDynamicBody("writeTypeInfo")
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readTypeInfo()
    }

    public static func write(
        _ value: T,
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool
    ) throws {
        guard let target = unwrapDynamicTarget(value) else {
            guard refMode != .none else {
                try nullDynamicWithoutEnvelope()
            }
            context.buffer.writeInt8(RefFlag.null.rawValue)
            return
        }

        if refMode != .none {
            context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
        }

        try context.enterDynamicAnyDepth()
        defer { context.leaveDynamicAnyDepth() }

        let typeInfo = try context.typeInfo(forTarget: Swift.type(of: target))
        if writeTypeInfo {
            try typeInfo.writeTypeInfo(context)
        }
        try typeInfo.writeDynamic(target, context)
    }

    public static func read(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> T {
        try readDynamicValue(
            context,
            as: Self.self,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
    }
}

extension AnyHashable: Serializer {
    public typealias Target = Self

    public static var staticTypeId: TypeId { .unknown }

    public static func defaultValue(_: ReadContext) throws -> Self {
        AnyHashable(Int32(0))
    }

    public static func writeData(_: Self, _: WriteContext) throws {
        try unsupportedDynamicBody("writeData")
    }

    public static func readData(_: ReadContext) throws -> Self {
        try unsupportedDynamicBody("readData")
    }

    public static func writeTypeInfo(_: WriteContext) throws {
        try unsupportedDynamicBody("writeTypeInfo")
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readTypeInfo()
    }

    public static func write(
        _ value: Self,
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool
    ) throws {
        try DynamicSerializer<AnyHashable>.write(
            value,
            context,
            refMode: refMode,
            writeTypeInfo: writeTypeInfo
        )
    }

    public static func read(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Self {
        try readDynamicValue(
            context,
            as: Self.self,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
    }
}
