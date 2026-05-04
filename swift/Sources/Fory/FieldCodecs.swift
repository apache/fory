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

public protocol FieldCodec {
    associatedtype Value

    static var typeId: TypeId { get }
    static var defaultValue: Value { get }
    static var isNullableType: Bool { get }
    static var isRefType: Bool { get }

    static func isNone(_ value: Value) -> Bool
    static func fieldType(nullable: Bool, trackRef: Bool) -> TypeMeta.FieldType
    static func writePayload(_ value: Value, _ context: WriteContext) throws
    static func readPayload(_ context: ReadContext) throws -> Value
    static func writeStaticTypeInfo(_ context: WriteContext) throws
    static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo?
    static func withTypeInfo<R>(_ typeInfo: TypeInfo?, _ context: ReadContext, _ body: () throws -> R) rethrows -> R
}

public extension FieldCodec {
    static var isNullableType: Bool { false }
    static var isRefType: Bool { false }

    static func isNone(_: Value) -> Bool { false }

    static func fieldType(nullable: Bool, trackRef: Bool) -> TypeMeta.FieldType {
        TypeMeta.FieldType(typeID: typeId.rawValue, nullable: nullable, trackRef: trackRef)
    }

    static func writeStaticTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(typeId)
    }

    static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(typeId)
    }

    static func withTypeInfo<R>(
        _ typeInfo: TypeInfo?,
        _ context: ReadContext,
        _ body: () throws -> R
    ) rethrows -> R {
        _ = typeInfo
        _ = context
        return try body()
    }

    static func write(
        _ value: Value,
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool
    ) throws {
        if refMode != .none {
            if refMode == .tracking, isRefType, let object = value as AnyObject? {
                if context.refWriter.tryWriteRef(buffer: context.buffer, object: object) {
                    return
                }
            } else {
                context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
            }
        }

        if writeTypeInfo {
            try writeStaticTypeInfo(context)
        }
        try writePayload(value, context)
    }

    static func read(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Value {
        switch refMode {
        case .none:
            if readTypeInfo {
                let typeInfo = try Self.readTypeInfo(context)
                return try withTypeInfo(typeInfo, context) {
                    try readPayload(context)
                }
            }
            return try readPayload(context)
        case .nullOnly:
            let rawFlag = try context.buffer.readInt8()
            switch rawFlag {
            case RefFlag.null.rawValue:
                return defaultValue
            case RefFlag.notNullValue.rawValue:
                if readTypeInfo {
                    let typeInfo = try Self.readTypeInfo(context)
                    return try withTypeInfo(typeInfo, context) {
                        try readPayload(context)
                    }
                }
                return try readPayload(context)
            case RefFlag.refValue.rawValue:
                if context.trackRef {
                    let reservedRefID = context.refReader.reserveRefID()
                    let value = try readPayloadAfterTypeInfo(context, readTypeInfo: readTypeInfo)
                    context.refReader.storeRef(value, at: reservedRefID)
                    return value
                }
                return try readPayloadAfterTypeInfo(context, readTypeInfo: readTypeInfo)
            case RefFlag.ref.rawValue:
                let refID = try context.buffer.readVarUInt32()
                return try context.refReader.readRef(refID, as: Value.self)
            default:
                throw ForyError.refError("invalid ref flag \(rawFlag)")
            }
        case .tracking:
            let rawFlag = try context.buffer.readInt8()
            guard let flag = RefFlag(rawValue: rawFlag) else {
                throw ForyError.refError("invalid ref flag \(rawFlag)")
            }
            switch flag {
            case .null:
                return defaultValue
            case .ref:
                let refID = try context.buffer.readVarUInt32()
                return try context.refReader.readRef(refID, as: Value.self)
            case .refValue:
                let reservedRefID = context.trackRef ? context.refReader.reserveRefID() : nil
                let value = try readPayloadAfterTypeInfo(context, readTypeInfo: readTypeInfo)
                if let reservedRefID {
                    context.refReader.storeRef(value, at: reservedRefID)
                }
                return value
            case .notNullValue:
                return try readPayloadAfterTypeInfo(context, readTypeInfo: readTypeInfo)
            }
        }
    }

    private static func readPayloadAfterTypeInfo(
        _ context: ReadContext,
        readTypeInfo: Bool
    ) throws -> Value {
        if readTypeInfo {
            let typeInfo = try Self.readTypeInfo(context)
            return try withTypeInfo(typeInfo, context) {
                try readPayload(context)
            }
        }
        return try readPayload(context)
    }
}

public enum SerializerCodec<T: Serializer>: FieldCodec {
    public typealias Value = T

    public static var typeId: TypeId { T.staticTypeId }
    public static var defaultValue: T { T.foryDefault() }
    public static var isNullableType: Bool { T.isNullableType }
    public static var isRefType: Bool { T.isRefType }

    public static func isNone(_ value: T) -> Bool {
        value.foryIsNone
    }

    public static func fieldType(nullable: Bool, trackRef: Bool) -> TypeMeta.FieldType {
        let fieldTypeID = T.staticTypeId == .structType ? TypeId.compatibleStruct.rawValue : T.staticTypeId.rawValue
        return TypeMeta.FieldType(typeID: fieldTypeID, nullable: nullable, trackRef: trackRef)
    }

    public static func writePayload(_ value: T, _ context: WriteContext) throws {
        try value.foryWriteData(context, hasGenerics: false)
    }

    public static func readPayload(_ context: ReadContext) throws -> T {
        try T.foryReadPayload(context, readTypeInfo: false)
    }

    public static func writeStaticTypeInfo(_ context: WriteContext) throws {
        try T.foryWriteStaticTypeInfo(context)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try T.foryReadTypeInfo(context)
    }

    public static func withTypeInfo<R>(
        _ typeInfo: TypeInfo?,
        _ context: ReadContext,
        _ body: () throws -> R
    ) rethrows -> R {
        try context.withTypeInfo(typeInfo, for: T.self, body)
    }

    public static func write(
        _ value: T,
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool
    ) throws {
        try value.foryWrite(context, refMode: refMode, writeTypeInfo: writeTypeInfo, hasGenerics: false)
    }

    public static func read(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> T {
        try T.foryRead(context, refMode: refMode, readTypeInfo: readTypeInfo)
    }
}

public enum OptionalFieldCodec<WrappedCodec: FieldCodec>: FieldCodec {
    public typealias Value = WrappedCodec.Value?

    public static var typeId: TypeId { WrappedCodec.typeId }
    public static var defaultValue: Value { nil }
    public static var isNullableType: Bool { true }
    public static var isRefType: Bool { WrappedCodec.isRefType }

    public static func isNone(_ value: Value) -> Bool {
        value == nil
    }

    public static func fieldType(nullable: Bool, trackRef: Bool) -> TypeMeta.FieldType {
        WrappedCodec.fieldType(nullable: nullable, trackRef: trackRef)
    }

    public static func writePayload(_ value: Value, _ context: WriteContext) throws {
        guard let value else {
            throw ForyError.invalidData("Option.none cannot write raw payload")
        }
        try WrappedCodec.writePayload(value, context)
    }

    public static func readPayload(_ context: ReadContext) throws -> Value {
        try WrappedCodec.readPayload(context)
    }

    public static func writeStaticTypeInfo(_ context: WriteContext) throws {
        try WrappedCodec.writeStaticTypeInfo(context)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try WrappedCodec.readTypeInfo(context)
    }

    public static func withTypeInfo<R>(
        _ typeInfo: TypeInfo?,
        _ context: ReadContext,
        _ body: () throws -> R
    ) rethrows -> R {
        try WrappedCodec.withTypeInfo(typeInfo, context, body)
    }

    public static func write(
        _ value: Value,
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool
    ) throws {
        switch refMode {
        case .none:
            guard let value else {
                throw ForyError.invalidData("Option.none with RefMode.none")
            }
            try WrappedCodec.write(value, context, refMode: .none, writeTypeInfo: writeTypeInfo)
        case .nullOnly:
            guard let value else {
                context.buffer.writeInt8(RefFlag.null.rawValue)
                return
            }
            context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
            try WrappedCodec.write(value, context, refMode: .none, writeTypeInfo: writeTypeInfo)
        case .tracking:
            guard let value else {
                context.buffer.writeInt8(RefFlag.null.rawValue)
                return
            }
            try WrappedCodec.write(value, context, refMode: .tracking, writeTypeInfo: writeTypeInfo)
        }
    }

    public static func read(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Value {
        switch refMode {
        case .none:
            return try WrappedCodec.read(context, refMode: .none, readTypeInfo: readTypeInfo)
        case .nullOnly:
            let refFlag = try context.buffer.readInt8()
            if refFlag == RefFlag.null.rawValue {
                return nil
            }
            return try WrappedCodec.read(context, refMode: .none, readTypeInfo: readTypeInfo)
        case .tracking:
            let refFlag = try context.buffer.readInt8()
            if refFlag == RefFlag.null.rawValue {
                return nil
            }
            context.buffer.moveBack(1)
            return try WrappedCodec.read(context, refMode: .tracking, readTypeInfo: readTypeInfo)
        }
    }
}

public enum BoolCodec: FieldCodec {
    public static let typeId: TypeId = .bool
    public static let defaultValue = false
    public static func writePayload(_ value: Bool, _ context: WriteContext) {
        context.buffer.writeUInt8(value ? 1 : 0)
    }
    public static func readPayload(_ context: ReadContext) throws -> Bool {
        try context.buffer.readUInt8() != 0
    }
}

public enum Int8Codec: FieldCodec {
    public static let typeId: TypeId = .int8
    public static let defaultValue = Int8(0)
    public static func writePayload(_ value: Int8, _ context: WriteContext) {
        context.buffer.writeInt8(value)
    }
    public static func readPayload(_ context: ReadContext) throws -> Int8 {
        try context.buffer.readInt8()
    }
}

public enum Int16Codec: FieldCodec {
    public static let typeId: TypeId = .int16
    public static let defaultValue = Int16(0)
    public static func writePayload(_ value: Int16, _ context: WriteContext) {
        context.buffer.writeInt16(value)
    }
    public static func readPayload(_ context: ReadContext) throws -> Int16 {
        try context.buffer.readInt16()
    }
}

public enum Int32VarintCodec: FieldCodec {
    public static let typeId: TypeId = .varint32
    public static let defaultValue = Int32(0)
    public static func writePayload(_ value: Int32, _ context: WriteContext) {
        context.buffer.writeVarInt32(value)
    }
    public static func readPayload(_ context: ReadContext) throws -> Int32 {
        try context.buffer.readVarInt32()
    }
}

public enum Int32FixedCodec: FieldCodec {
    public static let typeId: TypeId = .int32
    public static let defaultValue = Int32(0)
    public static func writePayload(_ value: Int32, _ context: WriteContext) {
        context.buffer.writeInt32(value)
    }
    public static func readPayload(_ context: ReadContext) throws -> Int32 {
        try context.buffer.readInt32()
    }
}

public enum Int64VarintCodec: FieldCodec {
    public static let typeId: TypeId = .varint64
    public static let defaultValue = Int64(0)
    public static func writePayload(_ value: Int64, _ context: WriteContext) {
        context.buffer.writeVarInt64(value)
    }
    public static func readPayload(_ context: ReadContext) throws -> Int64 {
        try context.buffer.readVarInt64()
    }
}

public enum Int64FixedCodec: FieldCodec {
    public static let typeId: TypeId = .int64
    public static let defaultValue = Int64(0)
    public static func writePayload(_ value: Int64, _ context: WriteContext) {
        context.buffer.writeInt64(value)
    }
    public static func readPayload(_ context: ReadContext) throws -> Int64 {
        try context.buffer.readInt64()
    }
}

public enum Int64TaggedCodec: FieldCodec {
    public static let typeId: TypeId = .taggedInt64
    public static let defaultValue = Int64(0)
    public static func writePayload(_ value: Int64, _ context: WriteContext) {
        context.buffer.writeTaggedInt64(value)
    }
    public static func readPayload(_ context: ReadContext) throws -> Int64 {
        try context.buffer.readTaggedInt64()
    }
}

public enum UInt8Codec: FieldCodec {
    public static let typeId: TypeId = .uint8
    public static let defaultValue = UInt8(0)
    public static func writePayload(_ value: UInt8, _ context: WriteContext) {
        context.buffer.writeUInt8(value)
    }
    public static func readPayload(_ context: ReadContext) throws -> UInt8 {
        try context.buffer.readUInt8()
    }
}

public enum UInt16Codec: FieldCodec {
    public static let typeId: TypeId = .uint16
    public static let defaultValue = UInt16(0)
    public static func writePayload(_ value: UInt16, _ context: WriteContext) {
        context.buffer.writeUInt16(value)
    }
    public static func readPayload(_ context: ReadContext) throws -> UInt16 {
        try context.buffer.readUInt16()
    }
}

public enum UInt32VarintCodec: FieldCodec {
    public static let typeId: TypeId = .varUInt32
    public static let defaultValue = UInt32(0)
    public static func writePayload(_ value: UInt32, _ context: WriteContext) {
        context.buffer.writeVarUInt32(value)
    }
    public static func readPayload(_ context: ReadContext) throws -> UInt32 {
        try context.buffer.readVarUInt32()
    }
}

public enum UInt32FixedCodec: FieldCodec {
    public static let typeId: TypeId = .uint32
    public static let defaultValue = UInt32(0)
    public static func writePayload(_ value: UInt32, _ context: WriteContext) {
        context.buffer.writeUInt32(value)
    }
    public static func readPayload(_ context: ReadContext) throws -> UInt32 {
        try context.buffer.readUInt32()
    }
}

public enum UInt64VarintCodec: FieldCodec {
    public static let typeId: TypeId = .varUInt64
    public static let defaultValue = UInt64(0)
    public static func writePayload(_ value: UInt64, _ context: WriteContext) {
        context.buffer.writeVarUInt64(value)
    }
    public static func readPayload(_ context: ReadContext) throws -> UInt64 {
        try context.buffer.readVarUInt64()
    }
}

public enum UInt64FixedCodec: FieldCodec {
    public static let typeId: TypeId = .uint64
    public static let defaultValue = UInt64(0)
    public static func writePayload(_ value: UInt64, _ context: WriteContext) {
        context.buffer.writeUInt64(value)
    }
    public static func readPayload(_ context: ReadContext) throws -> UInt64 {
        try context.buffer.readUInt64()
    }
}

public enum UInt64TaggedCodec: FieldCodec {
    public static let typeId: TypeId = .taggedUInt64
    public static let defaultValue = UInt64(0)
    public static func writePayload(_ value: UInt64, _ context: WriteContext) {
        context.buffer.writeTaggedUInt64(value)
    }
    public static func readPayload(_ context: ReadContext) throws -> UInt64 {
        try context.buffer.readTaggedUInt64()
    }
}

public enum IntVarintCodec: FieldCodec {
    public static let typeId: TypeId = .varint64
    public static let defaultValue = Int(0)
    public static func writePayload(_ value: Int, _ context: WriteContext) {
        context.buffer.writeVarInt64(Int64(value))
    }
    public static func readPayload(_ context: ReadContext) throws -> Int {
        Int(try context.buffer.readVarInt64())
    }
}

public enum IntFixedCodec: FieldCodec {
    public static let typeId: TypeId = .int64
    public static let defaultValue = Int(0)
    public static func writePayload(_ value: Int, _ context: WriteContext) {
        context.buffer.writeInt64(Int64(value))
    }
    public static func readPayload(_ context: ReadContext) throws -> Int {
        Int(try context.buffer.readInt64())
    }
}

public enum IntTaggedCodec: FieldCodec {
    public static let typeId: TypeId = .taggedInt64
    public static let defaultValue = Int(0)
    public static func writePayload(_ value: Int, _ context: WriteContext) {
        context.buffer.writeTaggedInt64(Int64(value))
    }
    public static func readPayload(_ context: ReadContext) throws -> Int {
        Int(try context.buffer.readTaggedInt64())
    }
}

public enum UIntVarintCodec: FieldCodec {
    public static let typeId: TypeId = .varUInt64
    public static let defaultValue = UInt(0)
    public static func writePayload(_ value: UInt, _ context: WriteContext) {
        context.buffer.writeVarUInt64(UInt64(value))
    }
    public static func readPayload(_ context: ReadContext) throws -> UInt {
        UInt(try context.buffer.readVarUInt64())
    }
}

public enum UIntFixedCodec: FieldCodec {
    public static let typeId: TypeId = .uint64
    public static let defaultValue = UInt(0)
    public static func writePayload(_ value: UInt, _ context: WriteContext) {
        context.buffer.writeUInt64(UInt64(value))
    }
    public static func readPayload(_ context: ReadContext) throws -> UInt {
        UInt(try context.buffer.readUInt64())
    }
}

public enum UIntTaggedCodec: FieldCodec {
    public static let typeId: TypeId = .taggedUInt64
    public static let defaultValue = UInt(0)
    public static func writePayload(_ value: UInt, _ context: WriteContext) {
        context.buffer.writeTaggedUInt64(UInt64(value))
    }
    public static func readPayload(_ context: ReadContext) throws -> UInt {
        UInt(try context.buffer.readTaggedUInt64())
    }
}

public enum Float16Codec: FieldCodec {
    public static let typeId: TypeId = .float16
    public static let defaultValue = Float16(0)
    public static func writePayload(_ value: Float16, _ context: WriteContext) {
        context.buffer.writeUInt16(value.bitPattern)
    }
    public static func readPayload(_ context: ReadContext) throws -> Float16 {
        Float16(bitPattern: try context.buffer.readUInt16())
    }
}

public enum BFloat16Codec: FieldCodec {
    public static let typeId: TypeId = .bfloat16
    public static let defaultValue = BFloat16()
    public static func writePayload(_ value: BFloat16, _ context: WriteContext) {
        context.buffer.writeUInt16(value.rawValue)
    }
    public static func readPayload(_ context: ReadContext) throws -> BFloat16 {
        BFloat16(rawValue: try context.buffer.readUInt16())
    }
}

public enum FloatCodec: FieldCodec {
    public static let typeId: TypeId = .float32
    public static let defaultValue = Float(0)
    public static func writePayload(_ value: Float, _ context: WriteContext) {
        context.buffer.writeFloat32(value)
    }
    public static func readPayload(_ context: ReadContext) throws -> Float {
        try context.buffer.readFloat32()
    }
}

public enum DoubleCodec: FieldCodec {
    public static let typeId: TypeId = .float64
    public static let defaultValue = Double(0)
    public static func writePayload(_ value: Double, _ context: WriteContext) {
        context.buffer.writeFloat64(value)
    }
    public static func readPayload(_ context: ReadContext) throws -> Double {
        try context.buffer.readFloat64()
    }
}

public typealias StringCodec = SerializerCodec<String>
public typealias DurationCodec = SerializerCodec<Duration>
public typealias TimestampCodec = SerializerCodec<Date>
public typealias LocalDateCodec = SerializerCodec<LocalDate>
public typealias DecimalCodec = SerializerCodec<Decimal>
public typealias DataCodec = SerializerCodec<Data>

public enum ListFieldCodec<ElementCodec: FieldCodec>: FieldCodec {
    public typealias Value = [ElementCodec.Value]

    public static var typeId: TypeId { .list }
    public static var defaultValue: Value { [] }

    public static func fieldType(nullable: Bool, trackRef: Bool) -> TypeMeta.FieldType {
        return TypeMeta.FieldType(
            typeID: TypeId.list.rawValue,
            nullable: nullable,
            trackRef: trackRef,
            generics: [ElementCodec.fieldType(nullable: ElementCodec.isNullableType, trackRef: false)]
        )
    }

    public static func writePayload(_ value: Value, _ context: WriteContext) throws {
        try writeCollectionPayload(value, context, elementCodec: ElementCodec.self)
    }

    public static func readPayload(_ context: ReadContext) throws -> Value {
        return try readCollectionPayload(context, elementCodec: ElementCodec.self)
    }
}

public enum ArrayFieldCodec<ElementCodec: FieldCodec>: FieldCodec {
    public typealias Value = [ElementCodec.Value]

    public static var typeId: TypeId {
        guard let typeID = packedArrayTypeID(for: ElementCodec.self) else {
            preconditionFailure("ArrayFieldCodec requires a non-null numeric or bool element codec")
        }
        return typeID
    }

    public static var defaultValue: Value { [] }

    public static func fieldType(nullable: Bool, trackRef: Bool) -> TypeMeta.FieldType {
        TypeMeta.FieldType(typeID: typeId.rawValue, nullable: nullable, trackRef: trackRef)
    }

    public static func writePayload(_ value: Value, _ context: WriteContext) throws {
        if try writePackedArrayPayload(value, context, elementCodec: ElementCodec.self) {
            return
        }
        throw ForyError.invalidData("unsupported array field element codec \(ElementCodec.self)")
    }

    public static func readPayload(_ context: ReadContext) throws -> Value {
        if let value = try readPackedArrayPayload(context, elementCodec: ElementCodec.self) {
            return value
        }
        throw ForyError.invalidData("unsupported array field element codec \(ElementCodec.self)")
    }

    public static func write(
        _ value: Value,
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool
    ) throws {
        if refMode == .none, !writeTypeInfo {
            try writePayload(value, context)
            return
        }
        if refMode != .none {
            context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
        }
        if writeTypeInfo {
            try writeStaticTypeInfo(context)
        }
        try writePayload(value, context)
    }

    public static func read(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Value {
        switch refMode {
        case .none:
            return try readPayloadAfterTypeInfo(context, readTypeInfo: readTypeInfo)
        case .nullOnly:
            let rawFlag = try context.buffer.readInt8()
            switch rawFlag {
            case RefFlag.null.rawValue:
                return defaultValue
            case RefFlag.notNullValue.rawValue:
                return try readPayloadAfterTypeInfo(context, readTypeInfo: readTypeInfo)
            case RefFlag.refValue.rawValue:
                if context.trackRef {
                    let reservedRefID = context.refReader.reserveRefID()
                    let value = try readPayloadAfterTypeInfo(context, readTypeInfo: readTypeInfo)
                    context.refReader.storeRef(value, at: reservedRefID)
                    return value
                }
                return try readPayloadAfterTypeInfo(context, readTypeInfo: readTypeInfo)
            case RefFlag.ref.rawValue:
                let refID = try context.buffer.readVarUInt32()
                return try context.refReader.readRef(refID, as: Value.self)
            default:
                throw ForyError.refError("invalid ref flag \(rawFlag)")
            }
        case .tracking:
            let rawFlag = try context.buffer.readInt8()
            guard let flag = RefFlag(rawValue: rawFlag) else {
                throw ForyError.refError("invalid ref flag \(rawFlag)")
            }
            switch flag {
            case .null:
                return defaultValue
            case .ref:
                let refID = try context.buffer.readVarUInt32()
                return try context.refReader.readRef(refID, as: Value.self)
            case .refValue:
                let reservedRefID = context.trackRef ? context.refReader.reserveRefID() : nil
                let value = try readPayloadAfterTypeInfo(context, readTypeInfo: readTypeInfo)
                if let reservedRefID {
                    context.refReader.storeRef(value, at: reservedRefID)
                }
                return value
            case .notNullValue:
                return try readPayloadAfterTypeInfo(context, readTypeInfo: readTypeInfo)
            }
        }
    }

    private static func readPayloadAfterTypeInfo(
        _ context: ReadContext,
        readTypeInfo: Bool
    ) throws -> Value {
        if readTypeInfo {
            let typeInfo = try Self.readTypeInfo(context)
            return try withTypeInfo(typeInfo, context) {
                try readPayload(context)
            }
        }
        return try readPayload(context)
    }
}

public enum SetFieldCodec<ElementCodec: FieldCodec>: FieldCodec where ElementCodec.Value: Hashable {
    public typealias Value = Set<ElementCodec.Value>

    public static var typeId: TypeId { .set }
    public static var defaultValue: Value { [] }

    public static func fieldType(nullable: Bool, trackRef: Bool) -> TypeMeta.FieldType {
        TypeMeta.FieldType(
            typeID: TypeId.set.rawValue,
            nullable: nullable,
            trackRef: trackRef,
            generics: [ElementCodec.fieldType(nullable: ElementCodec.isNullableType, trackRef: false)]
        )
    }

    public static func writePayload(_ value: Value, _ context: WriteContext) throws {
        try writeCollectionPayload(Array(value), context, elementCodec: ElementCodec.self)
    }

    public static func readPayload(_ context: ReadContext) throws -> Value {
        Set(try readCollectionPayload(context, elementCodec: ElementCodec.self))
    }
}

public enum MapFieldCodec<KeyCodec: FieldCodec, ValueCodec: FieldCodec>: FieldCodec
where KeyCodec.Value: Hashable {
    public typealias Value = [KeyCodec.Value: ValueCodec.Value]

    private struct MapEntryWriteOptions {
        var trackKeyRef: Bool
        var trackValueRef: Bool
        var keyDeclared: Bool
        var valueDeclared: Bool
        var keyDynamicType: Bool
        var valueDynamicType: Bool
        var keyIsNil: Bool
        var valueIsNil: Bool
    }

    public static var typeId: TypeId { .map }
    public static var defaultValue: Value { [:] }

    public static func fieldType(nullable: Bool, trackRef: Bool) -> TypeMeta.FieldType {
        TypeMeta.FieldType(
            typeID: TypeId.map.rawValue,
            nullable: nullable,
            trackRef: trackRef,
            generics: [
                KeyCodec.fieldType(nullable: KeyCodec.isNullableType, trackRef: false),
                ValueCodec.fieldType(nullable: ValueCodec.isNullableType, trackRef: false)
            ]
        )
    }

    public static func writePayload(_ value: Value, _ context: WriteContext) throws {
        context.buffer.writeVarUInt32(UInt32(value.count))
        if value.isEmpty {
            return
        }

        let trackKeyRef = context.trackRef && KeyCodec.isRefType
        let trackValueRef = context.trackRef && ValueCodec.isRefType
        let keyDeclared = !TypeId.needsTypeInfoForField(KeyCodec.typeId)
        let valueDeclared = !TypeId.needsTypeInfoForField(ValueCodec.typeId)
        let keyDynamicType = KeyCodec.typeId == .unknown
        let valueDynamicType = ValueCodec.typeId == .unknown
        let commonOptions = MapEntryWriteOptions(
            trackKeyRef: trackKeyRef,
            trackValueRef: trackValueRef,
            keyDeclared: keyDeclared,
            valueDeclared: valueDeclared,
            keyDynamicType: keyDynamicType,
            valueDynamicType: valueDynamicType,
            keyIsNil: false,
            valueIsNil: false
        )

        var iterator = value.makeIterator()
        var pendingPair = iterator.next()
        while let pair = pendingPair {
            let keyIsNil = KeyCodec.isNone(pair.key)
            let valueIsNil = ValueCodec.isNone(pair.value)

            if keyDynamicType || valueDynamicType || keyIsNil || valueIsNil {
                var options = commonOptions
                options.keyIsNil = keyIsNil
                options.valueIsNil = valueIsNil
                try writeMapEntry(
                    pair,
                    context,
                    options: options
                )
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
                try KeyCodec.writeStaticTypeInfo(context)
            }
            if !valueDeclared {
                try ValueCodec.writeStaticTypeInfo(context)
            }

            var chunkSize: UInt8 = 0
            while chunkSize < UInt8.max, let current = pendingPair {
                if KeyCodec.isNone(current.key) || ValueCodec.isNone(current.value) {
                    break
                }
                try writeMapPayload(
                    current,
                    context,
                    trackKeyRef: trackKeyRef,
                    trackValueRef: trackValueRef
                )
                chunkSize &+= 1
                pendingPair = iterator.next()
            }
            context.buffer.setByte(at: chunkSizeOffset, to: chunkSize)
        }
    }

    public static func readPayload(_ context: ReadContext) throws -> Value {
        let totalLength = Int(try context.buffer.readVarUInt32())
        try context.ensureCollectionLength(totalLength, label: "map")
        if totalLength == 0 {
            return [:]
        }

        var map: Value = [:]
        map.reserveCapacity(Swift.min(totalLength, context.buffer.remaining))
        var readCount = 0
        while readCount < totalLength {
            let header = try context.buffer.readUInt8()
            // IMPORTANT: map readers must obey the sender-written key/value ref
            // bits in this header. Local Swift field metadata must not
            // override that decision while reading. Shared xlang tests
            // intentionally deserialize one ref policy and then serialize
            // another local payload. DO NOT REMOVE this comment.
            let trackKeyRef = (header & MapHeader.trackingKeyRef) != 0
            let keyNull = (header & MapHeader.keyNull) != 0
            let keyDeclared = (header & MapHeader.declaredKeyType) != 0

            let trackValueRef = (header & MapHeader.trackingValueRef) != 0
            let valueNull = (header & MapHeader.valueNull) != 0
            let valueDeclared = (header & MapHeader.declaredValueType) != 0

            if keyNull && valueNull {
                map[KeyCodec.defaultValue] = ValueCodec.defaultValue
                readCount += 1
                continue
            }

            if keyNull {
                let value = try readMapValue(
                    context,
                    declared: valueDeclared,
                    trackRef: trackValueRef
                )
                map[KeyCodec.defaultValue] = value
                readCount += 1
                continue
            }

            if valueNull {
                let key = try readMapKey(
                    context,
                    declared: keyDeclared,
                    trackRef: trackKeyRef
                )
                map[key] = ValueCodec.defaultValue
                readCount += 1
                continue
            }

            let chunkSize = Int(try context.buffer.readUInt8())
            if chunkSize > (totalLength - readCount) {
                throw ForyError.invalidData("map chunk size exceeds remaining entries")
            }
            let keyTypeInfo = keyDeclared ? nil : try KeyCodec.readTypeInfo(context)
            let valueTypeInfo = valueDeclared ? nil : try ValueCodec.readTypeInfo(context)
            for _ in 0..<chunkSize {
                let key = try KeyCodec.withTypeInfo(keyTypeInfo, context) {
                    try KeyCodec.read(
                        context,
                        refMode: trackKeyRef ? .tracking : .none,
                        readTypeInfo: false
                    )
                }
                let value = try ValueCodec.withTypeInfo(valueTypeInfo, context) {
                    try ValueCodec.read(
                        context,
                        refMode: trackValueRef ? .tracking : .none,
                        readTypeInfo: false
                    )
                }
                map[key] = value
            }
            readCount += chunkSize
        }
        return map
    }

    private static func writeMapEntry(
        _ pair: Dictionary<KeyCodec.Value, ValueCodec.Value>.Element,
        _ context: WriteContext,
        options: MapEntryWriteOptions
    ) throws {
        var header: UInt8 = 0
        if options.trackKeyRef { header |= MapHeader.trackingKeyRef }
        if options.trackValueRef { header |= MapHeader.trackingValueRef }
        if options.keyIsNil {
            header |= MapHeader.keyNull
        } else if !options.keyDynamicType && options.keyDeclared {
            header |= MapHeader.declaredKeyType
        }
        if options.valueIsNil {
            header |= MapHeader.valueNull
        } else if !options.valueDynamicType && options.valueDeclared {
            header |= MapHeader.declaredValueType
        }
        context.buffer.writeUInt8(header)

        if !options.keyIsNil {
            if !options.keyDeclared {
                try KeyCodec.writeStaticTypeInfo(context)
            }
            try KeyCodec.write(
                pair.key,
                context,
                refMode: options.trackKeyRef ? .tracking : .none,
                writeTypeInfo: false
            )
        }
        if !options.valueIsNil {
            if !options.valueDeclared {
                try ValueCodec.writeStaticTypeInfo(context)
            }
            try ValueCodec.write(
                pair.value,
                context,
                refMode: options.trackValueRef ? .tracking : .none,
                writeTypeInfo: false
            )
        }
    }

    private static func writeMapPayload(
        _ pair: Dictionary<KeyCodec.Value, ValueCodec.Value>.Element,
        _ context: WriteContext,
        trackKeyRef: Bool,
        trackValueRef: Bool
    ) throws {
        try KeyCodec.write(
            pair.key,
            context,
            refMode: trackKeyRef ? .tracking : .none,
            writeTypeInfo: false
        )
        try ValueCodec.write(
            pair.value,
            context,
            refMode: trackValueRef ? .tracking : .none,
            writeTypeInfo: false
        )
    }

    private static func readMapKey(
        _ context: ReadContext,
        declared: Bool,
        trackRef: Bool
    ) throws -> KeyCodec.Value {
        let typeInfo = declared ? nil : try KeyCodec.readTypeInfo(context)
        return try KeyCodec.withTypeInfo(typeInfo, context) {
            try KeyCodec.read(context, refMode: trackRef ? .tracking : .none, readTypeInfo: false)
        }
    }

    private static func readMapValue(
        _ context: ReadContext,
        declared: Bool,
        trackRef: Bool
    ) throws -> ValueCodec.Value {
        let typeInfo = declared ? nil : try ValueCodec.readTypeInfo(context)
        return try ValueCodec.withTypeInfo(typeInfo, context) {
            try ValueCodec.read(context, refMode: trackRef ? .tracking : .none, readTypeInfo: false)
        }
    }
}

@inline(__always)
private func uncheckedPackedArrayCast<From, To>(_ array: [From], to _: To.Type) -> [To] {
    assert(From.self == To.self)
    return unsafeBitCast(array, to: [To].self)
}

private func packedArrayTypeID<ElementCodec: FieldCodec>(for _: ElementCodec.Type) -> TypeId? {
    if ElementCodec.isNullableType {
        return nil
    }
    if ElementCodec.self == BoolCodec.self {
        return .boolArray
    }
    if ElementCodec.self == Int8Codec.self {
        return .int8Array
    }
    if ElementCodec.self == Int16Codec.self {
        return .int16Array
    }
    if ElementCodec.self == Int32FixedCodec.self {
        return .int32Array
    }
    if ElementCodec.self == Int64FixedCodec.self || ElementCodec.self == IntFixedCodec.self {
        return .int64Array
    }
    if ElementCodec.self == UInt8Codec.self {
        return .uint8Array
    }
    if ElementCodec.self == UInt16Codec.self {
        return .uint16Array
    }
    if ElementCodec.self == UInt32FixedCodec.self {
        return .uint32Array
    }
    if ElementCodec.self == UInt64FixedCodec.self || ElementCodec.self == UIntFixedCodec.self {
        return .uint64Array
    }
    if ElementCodec.self == Float16Codec.self {
        return .float16Array
    }
    if ElementCodec.self == BFloat16Codec.self {
        return .bfloat16Array
    }
    if ElementCodec.self == FloatCodec.self {
        return .float32Array
    }
    if ElementCodec.self == DoubleCodec.self {
        return .float64Array
    }
    return nil
}

private func writePackedArrayPayload<ElementCodec: FieldCodec>(
    _ value: [ElementCodec.Value],
    _ context: WriteContext,
    elementCodec _: ElementCodec.Type
) throws -> Bool {
    if ElementCodec.self == BoolCodec.self {
        writePrimitiveArray(uncheckedPackedArrayCast(value, to: Bool.self), context: context)
        return true
    }
    if ElementCodec.self == Int8Codec.self {
        writePrimitiveArray(uncheckedPackedArrayCast(value, to: Int8.self), context: context)
        return true
    }
    if ElementCodec.self == Int16Codec.self {
        writePrimitiveArray(uncheckedPackedArrayCast(value, to: Int16.self), context: context)
        return true
    }
    if ElementCodec.self == Int32FixedCodec.self {
        writePrimitiveArray(uncheckedPackedArrayCast(value, to: Int32.self), context: context)
        return true
    }
    if ElementCodec.self == Int64FixedCodec.self {
        writePrimitiveArray(uncheckedPackedArrayCast(value, to: Int64.self), context: context)
        return true
    }
    if ElementCodec.self == IntFixedCodec.self {
        writeIntArrayPayload(uncheckedPackedArrayCast(value, to: Int.self), context)
        return true
    }
    if ElementCodec.self == UInt8Codec.self {
        writePrimitiveArray(uncheckedPackedArrayCast(value, to: UInt8.self), context: context)
        return true
    }
    if ElementCodec.self == UInt16Codec.self {
        writePrimitiveArray(uncheckedPackedArrayCast(value, to: UInt16.self), context: context)
        return true
    }
    if ElementCodec.self == UInt32FixedCodec.self {
        writePrimitiveArray(uncheckedPackedArrayCast(value, to: UInt32.self), context: context)
        return true
    }
    if ElementCodec.self == UInt64FixedCodec.self {
        writePrimitiveArray(uncheckedPackedArrayCast(value, to: UInt64.self), context: context)
        return true
    }
    if ElementCodec.self == UIntFixedCodec.self {
        writeUIntArrayPayload(uncheckedPackedArrayCast(value, to: UInt.self), context)
        return true
    }
    if ElementCodec.self == Float16Codec.self {
        writePrimitiveArray(uncheckedPackedArrayCast(value, to: Float16.self), context: context)
        return true
    }
    if ElementCodec.self == BFloat16Codec.self {
        writePrimitiveArray(uncheckedPackedArrayCast(value, to: BFloat16.self), context: context)
        return true
    }
    if ElementCodec.self == FloatCodec.self {
        writePrimitiveArray(uncheckedPackedArrayCast(value, to: Float.self), context: context)
        return true
    }
    if ElementCodec.self == DoubleCodec.self {
        writePrimitiveArray(uncheckedPackedArrayCast(value, to: Double.self), context: context)
        return true
    }
    return false
}

private func readPackedArrayPayload<ElementCodec: FieldCodec>(
    _ context: ReadContext,
    elementCodec _: ElementCodec.Type
) throws -> [ElementCodec.Value]? {
    if ElementCodec.self == BoolCodec.self {
        return uncheckedPackedArrayCast(try readPrimitiveArray(context) as [Bool], to: ElementCodec.Value.self)
    }
    if ElementCodec.self == Int8Codec.self {
        return uncheckedPackedArrayCast(try readPrimitiveArray(context) as [Int8], to: ElementCodec.Value.self)
    }
    if ElementCodec.self == Int16Codec.self {
        return uncheckedPackedArrayCast(try readPrimitiveArray(context) as [Int16], to: ElementCodec.Value.self)
    }
    if ElementCodec.self == Int32FixedCodec.self {
        return uncheckedPackedArrayCast(try readPrimitiveArray(context) as [Int32], to: ElementCodec.Value.self)
    }
    if ElementCodec.self == Int64FixedCodec.self {
        return uncheckedPackedArrayCast(try readPrimitiveArray(context) as [Int64], to: ElementCodec.Value.self)
    }
    if ElementCodec.self == IntFixedCodec.self {
        return uncheckedPackedArrayCast(try readIntArrayPayload(context), to: ElementCodec.Value.self)
    }
    if ElementCodec.self == UInt8Codec.self {
        return uncheckedPackedArrayCast(try readPrimitiveArray(context) as [UInt8], to: ElementCodec.Value.self)
    }
    if ElementCodec.self == UInt16Codec.self {
        return uncheckedPackedArrayCast(try readPrimitiveArray(context) as [UInt16], to: ElementCodec.Value.self)
    }
    if ElementCodec.self == UInt32FixedCodec.self {
        return uncheckedPackedArrayCast(try readPrimitiveArray(context) as [UInt32], to: ElementCodec.Value.self)
    }
    if ElementCodec.self == UInt64FixedCodec.self {
        return uncheckedPackedArrayCast(try readPrimitiveArray(context) as [UInt64], to: ElementCodec.Value.self)
    }
    if ElementCodec.self == UIntFixedCodec.self {
        return uncheckedPackedArrayCast(try readUIntArrayPayload(context), to: ElementCodec.Value.self)
    }
    if ElementCodec.self == Float16Codec.self {
        return uncheckedPackedArrayCast(try readPrimitiveArray(context) as [Float16], to: ElementCodec.Value.self)
    }
    if ElementCodec.self == BFloat16Codec.self {
        return uncheckedPackedArrayCast(try readPrimitiveArray(context) as [BFloat16], to: ElementCodec.Value.self)
    }
    if ElementCodec.self == FloatCodec.self {
        return uncheckedPackedArrayCast(try readPrimitiveArray(context) as [Float], to: ElementCodec.Value.self)
    }
    if ElementCodec.self == DoubleCodec.self {
        return uncheckedPackedArrayCast(try readPrimitiveArray(context) as [Double], to: ElementCodec.Value.self)
    }
    return nil
}

private func writeIntArrayPayload(_ value: [Int], _ context: WriteContext) {
    context.buffer.writeVarUInt32(UInt32(value.count * 8))
    for item in value {
        context.buffer.writeInt64(Int64(item))
    }
}

private func writeUIntArrayPayload(_ value: [UInt], _ context: WriteContext) {
    context.buffer.writeVarUInt32(UInt32(value.count * 8))
    for item in value {
        context.buffer.writeUInt64(UInt64(item))
    }
}

private func readIntArrayPayload(_ context: ReadContext) throws -> [Int] {
    let count = try readPackedArrayElementCount(context, width: 8, label: "int64_array")
    var values: [Int] = []
    values.reserveCapacity(count)
    for _ in 0..<count {
        values.append(Int(try context.buffer.readInt64()))
    }
    return values
}

private func readUIntArrayPayload(_ context: ReadContext) throws -> [UInt] {
    let count = try readPackedArrayElementCount(context, width: 8, label: "uint64_array")
    var values: [UInt] = []
    values.reserveCapacity(count)
    for _ in 0..<count {
        values.append(UInt(try context.buffer.readUInt64()))
    }
    return values
}

private func readPackedArrayElementCount(
    _ context: ReadContext,
    width: Int,
    label: String
) throws -> Int {
    let payloadSize = Int(try context.buffer.readVarUInt32())
    try context.ensureRemainingBytes(payloadSize, label: "primitive_array_payload")
    if payloadSize % width != 0 {
        throw ForyError.invalidData("\(label) payload size mismatch")
    }
    let count = payloadSize / width
    try context.ensureCollectionLength(count, label: label)
    return count
}

private func writeCollectionPayload<ElementCodec: FieldCodec>(
    _ value: [ElementCodec.Value],
    _ context: WriteContext,
    elementCodec _: ElementCodec.Type
) throws {
    let buffer = context.buffer
    buffer.writeVarUInt32(UInt32(value.count))
    if value.isEmpty {
        return
    }

    let hasNull = ElementCodec.isNullableType && value.contains(where: ElementCodec.isNone)
    let trackRef = context.trackRef && ElementCodec.isRefType
    let declaredElementType = !TypeId.needsTypeInfoForField(ElementCodec.typeId)
    let dynamicElementType = ElementCodec.typeId == .unknown

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
        try ElementCodec.writeStaticTypeInfo(context)
    }

    if dynamicElementType {
        let refMode = RefMode.from(nullable: hasNull, trackRef: trackRef)
        for element in value {
            try ElementCodec.write(element, context, refMode: refMode, writeTypeInfo: true)
        }
        return
    }

    if trackRef {
        for element in value {
            try ElementCodec.write(element, context, refMode: .tracking, writeTypeInfo: false)
        }
    } else if hasNull {
        for element in value {
            if ElementCodec.isNone(element) {
                buffer.writeInt8(RefFlag.null.rawValue)
            } else {
                buffer.writeInt8(RefFlag.notNullValue.rawValue)
                try ElementCodec.writePayload(element, context)
            }
        }
    } else {
        for element in value {
            try ElementCodec.writePayload(element, context)
        }
    }
}

private func readCollectionPayload<ElementCodec: FieldCodec>(
    _ context: ReadContext,
    elementCodec _: ElementCodec.Type
) throws -> [ElementCodec.Value] {
    let buffer = context.buffer
    let length = Int(try buffer.readVarUInt32())
    try context.ensureCollectionLength(length, label: "array")
    if length == 0 {
        return []
    }

    let header = try buffer.readUInt8()
    // IMPORTANT: collection readers must obey the ref/null bits written on the
    // wire, not the local Swift element metadata that may imply a different
    // ref policy. Shared xlang tests intentionally deserialize one ref policy
    // and then serialize another local payload. DO NOT REMOVE this comment.
    let trackRef = (header & CollectionHeader.trackingRef) != 0
    let hasNull = (header & CollectionHeader.hasNull) != 0
    let declared = (header & CollectionHeader.declaredElementType) != 0
    let sameType = (header & CollectionHeader.sameType) != 0

    var result: [ElementCodec.Value] = []
    result.reserveCapacity(length)

    if !sameType {
        let refMode = RefMode.from(nullable: hasNull, trackRef: trackRef)
        for _ in 0..<length {
            result.append(try ElementCodec.read(context, refMode: refMode, readTypeInfo: true))
        }
        return result
    }

    let elementTypeInfo = declared ? nil : try ElementCodec.readTypeInfo(context)
    return try ElementCodec.withTypeInfo(elementTypeInfo, context) {
        if trackRef {
            for _ in 0..<length {
                result.append(try ElementCodec.read(context, refMode: .tracking, readTypeInfo: false))
            }
        } else if hasNull {
            for _ in 0..<length {
                let refFlag = try buffer.readInt8()
                if refFlag == RefFlag.null.rawValue {
                    result.append(ElementCodec.defaultValue)
                } else if refFlag == RefFlag.notNullValue.rawValue {
                    result.append(try ElementCodec.readPayload(context))
                } else {
                    throw ForyError.refError("invalid nullability flag \(refFlag)")
                }
            }
        } else {
            for _ in 0..<length {
                result.append(try ElementCodec.readPayload(context))
            }
        }
        return result
    }
}
