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

/// A transparent serializer for an optional value whose wrapped value uses
/// `Wrapped`.
public enum OptionalSerializer<Wrapped: Serializer>: Serializer {
    public typealias Target = Wrapped.Target?

    public static var staticTypeId: TypeId { Wrapped.staticTypeId }
    public static var isNullableType: Bool { true }
    public static var isRefType: Bool { Wrapped.isRefType }
    public static var isWrapper: Bool { true }

    @inlinable
    @inline(__always)
    public static func isNone(_ value: Target) -> Bool {
        value == nil
    }

    @inlinable
    @inline(__always)
    public static func defaultValue(_: ReadContext) throws -> Target {
        nil
    }

    @inlinable
    @inline(__always)
    public static func writeData(_ value: Target, _ context: WriteContext) throws {
        guard let value else {
            throw optionalBodyError()
        }
        try Wrapped.writeData(value, context)
    }

    @inlinable
    @inline(__always)
    public static func readData(_ context: ReadContext) throws -> Target {
        .some(try Wrapped.readData(context))
    }

    @inlinable
    @inline(__always)
    public static func writeTypeInfo(_ context: WriteContext) throws {
        try Wrapped.writeTypeInfo(context)
    }

    @inlinable
    @inline(__always)
    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try Wrapped.readTypeInfo(context)
    }

    @inlinable
    public static func write(
        _ value: Target,
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool
    ) throws {
        switch refMode {
        case .none:
            guard let value else {
                throw optionalEnvelopeError()
            }
            try Wrapped.write(
                value,
                context,
                refMode: .none,
                writeTypeInfo: writeTypeInfo
            )
        case .nullOnly:
            guard let value else {
                context.buffer.writeInt8(RefFlag.null.rawValue)
                return
            }
            context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
            try Wrapped.write(
                value,
                context,
                refMode: .none,
                writeTypeInfo: writeTypeInfo
            )
        case .tracking:
            guard let value else {
                context.buffer.writeInt8(RefFlag.null.rawValue)
                return
            }
            try Wrapped.write(
                value,
                context,
                refMode: .tracking,
                writeTypeInfo: writeTypeInfo
            )
        }
    }

    @inlinable
    public static func read(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Target {
        let typeInfo =
            readTypeInfo
            ? nil
            : context.getTypeInfo(for: Self.self)
        switch refMode {
        case .none:
            return .some(
                try context.withTypeInfo(typeInfo, for: Wrapped.self) {
                    try Wrapped.read(
                        context,
                        refMode: .none,
                        readTypeInfo: readTypeInfo
                    )
                }
            )
        case .nullOnly:
            let refFlag = try context.buffer.readInt8()
            if refFlag == RefFlag.null.rawValue {
                return nil
            }
            guard refFlag == RefFlag.notNullValue.rawValue else {
                throw optionalRefFlagError(refFlag)
            }
            return .some(
                try context.withTypeInfo(typeInfo, for: Wrapped.self) {
                    try Wrapped.read(
                        context,
                        refMode: .none,
                        readTypeInfo: readTypeInfo
                    )
                }
            )
        case .tracking:
            let refFlag = try context.buffer.readInt8()
            if refFlag == RefFlag.null.rawValue {
                return nil
            }
            context.buffer.moveBack(1)
            return .some(
                try context.withTypeInfo(typeInfo, for: Wrapped.self) {
                    try Wrapped.read(
                        context,
                        refMode: .tracking,
                        readTypeInfo: readTypeInfo
                    )
                }
            )
        }
    }
}

public extension OptionalSerializer where Wrapped: FieldCodec {
    static func fieldType(
        nullable: Bool,
        trackRef: Bool,
        resolveSerializerTypeId: (Any.Type) throws -> TypeId
    ) throws -> TypeMeta.FieldType {
        try Wrapped.fieldType(
            nullable: nullable,
            trackRef: trackRef,
            resolveSerializerTypeId: resolveSerializerTypeId
        )
    }

    @inlinable
    @inline(__always)
    static func writeFieldData(
        _ value: Target,
        _ context: WriteContext,
        hasDeclaredChildren: Bool
    ) throws {
        guard let value else {
            throw optionalBodyError()
        }
        try Wrapped.writeFieldData(
            value,
            context,
            hasDeclaredChildren: hasDeclaredChildren
        )
    }

    @inlinable
    @inline(__always)
    static func readFieldData(_ context: ReadContext) throws -> Target {
        .some(try Wrapped.readFieldData(context))
    }

    @inlinable
    @inline(__always)
    static func writeFieldTypeInfo(_ context: WriteContext) throws {
        try Wrapped.writeFieldTypeInfo(context)
    }

    @inlinable
    @inline(__always)
    static func readFieldTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try Wrapped.readFieldTypeInfo(context)
    }

    @inlinable
    @inline(__always)
    static func withFieldTypeInfo<R>(
        _ typeInfo: TypeInfo?,
        _ context: ReadContext,
        _ body: () throws -> R
    ) rethrows -> R {
        try Wrapped.withFieldTypeInfo(typeInfo, context, body)
    }

    @inlinable
    static func writeField(
        _ value: Target,
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool,
        hasDeclaredChildren: Bool
    ) throws {
        switch refMode {
        case .none:
            guard let value else {
                throw optionalEnvelopeError()
            }
            try Wrapped.writeField(
                value,
                context,
                refMode: .none,
                writeTypeInfo: writeTypeInfo,
                hasDeclaredChildren: hasDeclaredChildren
            )
        case .nullOnly:
            guard let value else {
                context.buffer.writeInt8(RefFlag.null.rawValue)
                return
            }
            context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
            try Wrapped.writeField(
                value,
                context,
                refMode: .none,
                writeTypeInfo: writeTypeInfo,
                hasDeclaredChildren: hasDeclaredChildren
            )
        case .tracking:
            guard let value else {
                context.buffer.writeInt8(RefFlag.null.rawValue)
                return
            }
            try Wrapped.writeField(
                value,
                context,
                refMode: .tracking,
                writeTypeInfo: writeTypeInfo,
                hasDeclaredChildren: hasDeclaredChildren
            )
        }
    }

    @inlinable
    @inline(__always)
    static func readField(
        _ context: ReadContext,
        refMode: RefMode
    ) throws -> Target {
        try readField(
            context,
            refMode: refMode,
            readTypeInfo: TypeId.needsTypeInfoForField(Wrapped.staticTypeId)
        )
    }

    @inlinable
    static func readField(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Target {
        let typeInfo =
            readTypeInfo
            ? nil
            : context.getTypeInfo(for: Self.self)
        switch refMode {
        case .none:
            return .some(
                try Wrapped.withFieldTypeInfo(typeInfo, context) {
                    try Wrapped.readField(
                        context,
                        refMode: .none,
                        readTypeInfo: readTypeInfo
                    )
                }
            )
        case .nullOnly:
            let refFlag = try context.buffer.readInt8()
            if refFlag == RefFlag.null.rawValue {
                return nil
            }
            guard refFlag == RefFlag.notNullValue.rawValue else {
                throw optionalRefFlagError(refFlag)
            }
            return .some(
                try Wrapped.withFieldTypeInfo(typeInfo, context) {
                    try Wrapped.readField(
                        context,
                        refMode: .none,
                        readTypeInfo: readTypeInfo
                    )
                }
            )
        case .tracking:
            let refFlag = try context.buffer.readInt8()
            if refFlag == RefFlag.null.rawValue {
                return nil
            }
            context.buffer.moveBack(1)
            return .some(
                try Wrapped.withFieldTypeInfo(typeInfo, context) {
                    try Wrapped.readField(
                        context,
                        refMode: .tracking,
                        readTypeInfo: readTypeInfo
                    )
                }
            )
        }
    }

    @inlinable
    static func readCompatibleField(
        _ context: ReadContext,
        remoteFieldType: TypeMeta.FieldType,
        refMode: RefMode
    ) throws -> Target {
        switch refMode {
        case .none:
            return .some(
                try Wrapped.readCompatibleField(
                    context,
                    remoteFieldType: remoteFieldType,
                    refMode: .none
                )
            )
        case .nullOnly:
            let refFlag = try context.buffer.readInt8()
            if refFlag == RefFlag.null.rawValue {
                return nil
            }
            guard refFlag == RefFlag.notNullValue.rawValue else {
                throw optionalRefFlagError(refFlag)
            }
            return .some(
                try Wrapped.readCompatibleField(
                    context,
                    remoteFieldType: remoteFieldType,
                    refMode: .none
                )
            )
        case .tracking:
            let refFlag = try context.buffer.readInt8()
            if refFlag == RefFlag.null.rawValue {
                return nil
            }
            context.buffer.moveBack(1)
            return .some(
                try Wrapped.readCompatibleField(
                    context,
                    remoteFieldType: remoteFieldType,
                    refMode: .tracking
                )
            )
        }
    }
}

extension OptionalSerializer: FieldCodec where Wrapped: FieldCodec {}

extension Optional: Serializer where Wrapped: Serializer, Wrapped.Target == Wrapped {
    public typealias Target = Self

    @inlinable
    @inline(__always)
    public static var staticTypeId: TypeId {
        OptionalSerializer<Wrapped>.staticTypeId
    }

    @inlinable
    @inline(__always)
    public static var isNullableType: Bool {
        OptionalSerializer<Wrapped>.isNullableType
    }

    @inlinable
    @inline(__always)
    public static var isRefType: Bool {
        OptionalSerializer<Wrapped>.isRefType
    }

    @inlinable
    @inline(__always)
    public static var isWrapper: Bool {
        OptionalSerializer<Wrapped>.isWrapper
    }

    @inlinable
    @inline(__always)
    public static func isNone(_ value: Self) -> Bool {
        OptionalSerializer<Wrapped>.isNone(value)
    }

    @inlinable
    @inline(__always)
    public static func defaultValue(_ context: ReadContext) throws -> Self {
        try OptionalSerializer<Wrapped>.defaultValue(context)
    }

    @inlinable
    @inline(__always)
    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        try OptionalSerializer<Wrapped>.writeData(value, context)
    }

    @inlinable
    @inline(__always)
    public static func readData(_ context: ReadContext) throws -> Self {
        try OptionalSerializer<Wrapped>.readData(context)
    }

    @inlinable
    @inline(__always)
    public static func write(
        _ value: Self,
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool
    ) throws {
        try OptionalSerializer<Wrapped>.write(
            value,
            context,
            refMode: refMode,
            writeTypeInfo: writeTypeInfo
        )
    }

    @inlinable
    @inline(__always)
    public static func read(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Self {
        try OptionalSerializer<Wrapped>.read(
            context,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
    }

    @inlinable
    @inline(__always)
    public static func writeTypeInfo(_ context: WriteContext) throws {
        try OptionalSerializer<Wrapped>.writeTypeInfo(context)
    }

    @inlinable
    @inline(__always)
    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try OptionalSerializer<Wrapped>.readTypeInfo(context)
    }
}

@usableFromInline
@inline(never)
internal func optionalBodyError() -> ForyError {
    ForyError.invalidData("Optional.none cannot write a value body")
}

@usableFromInline
@inline(never)
internal func optionalEnvelopeError() -> ForyError {
    ForyError.invalidData("Optional.none cannot be written with RefMode.none")
}

@usableFromInline
@inline(never)
internal func optionalRefFlagError(_ rawFlag: Int8) -> ForyError {
    ForyError.refError("invalid optional ref flag \(rawFlag)")
}
