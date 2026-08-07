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

private let fieldReferenceBytes = 4

@inline(__always)
private func fieldElementBytes<ElementCodec: FieldCodec>(_ codec: ElementCodec.Type) -> Int {
    if codec.staticTypeId == .unknown {
        return max(1, MemoryLayout<ElementCodec.Target>.stride)
    }
    return codec.isRefType
        ? fieldReferenceBytes
        : max(1, MemoryLayout<ElementCodec.Target>.stride)
}

@inline(__always)
private func serializerElementBytes<Element: Serializer>(_ type: Element.Type) -> Int {
    if type.staticTypeId == .unknown {
        return max(1, MemoryLayout<Element.Target>.stride)
    }
    return type.isRefType
        ? fieldReferenceBytes
        : max(1, MemoryLayout<Element.Target>.stride)
}

@inline(__always)
private func fieldOwnerBytes<T>(_ type: T.Type) -> Int {
    max(1, MemoryLayout<T>.stride)
}

@inline(__always)
private func reserveFieldStorage(
    _ context: ReadContext,
    ownerBytes: Int,
    count: Int,
    elementBytes: Int
) throws {
    if ownerBytes < 0 || count < 0 || elementBytes < 0 {
        throw fieldGraphEstimateOverflow()
    }
    let (storageBytes, overflow) = count.multipliedReportingOverflow(by: elementBytes)
    if overflow {
        throw fieldGraphEstimateOverflow()
    }
    let (bytes, addOverflow) = ownerBytes.addingReportingOverflow(storageBytes)
    if addOverflow {
        throw fieldGraphEstimateOverflow()
    }
    try context.reserveGraphMemory(bytes)
}

@inline(never)
private func fieldGraphEstimateOverflow() -> ForyError {
    ForyError.invalidData("graph memory estimate overflows")
}

@inline(__always)
private func reserveFieldArrayStorage<ElementCodec: FieldCodec>(
    _ context: ReadContext,
    _ codec: ElementCodec.Type,
    ownerBytes: Int,
    count: Int
) throws {
    try reserveFieldStorage(
        context, ownerBytes: ownerBytes, count: count, elementBytes: fieldElementBytes(codec))
}

@inline(__always)
private func reserveSerializerArrayMemory<Element: Serializer>(
    _ context: ReadContext,
    _ type: Element.Type,
    ownerBytes: Int,
    count: Int
) throws {
    try reserveFieldStorage(
        context, ownerBytes: ownerBytes, count: count, elementBytes: serializerElementBytes(type))
}

/// Field-only schema and compatibility behavior layered on a `Serializer`.
///
/// `Serializer` owns exact value I/O. A field codec additionally owns the
/// declared field node, including nested child metadata and compatible reads.
public protocol FieldCodec: Serializer {
    static func fieldType(
        nullable: Bool,
        trackRef: Bool,
        resolveSerializerTypeId: (Any.Type) throws -> TypeId
    ) throws -> TypeMeta.FieldType

    static func writeFieldData(
        _ value: Target,
        _ context: WriteContext,
        hasDeclaredChildren: Bool
    ) throws

    static func readFieldData(_ context: ReadContext) throws -> Target

    static func writeFieldTypeInfo(_ context: WriteContext) throws
    static func readFieldTypeInfo(_ context: ReadContext) throws -> TypeInfo?

    static func withFieldTypeInfo<R>(
        _ typeInfo: TypeInfo?, _ context: ReadContext, _ body: () throws -> R
    )
        rethrows -> R

    static func writeField(
        _ value: Target,
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool,
        hasDeclaredChildren: Bool
    ) throws

    static func readField(
        _ context: ReadContext,
        refMode: RefMode
    ) throws -> Target

    static func readField(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Target

    static func readCompatibleField(
        _ context: ReadContext,
        remoteFieldType: TypeMeta.FieldType,
        refMode: RefMode
    ) throws -> Target
}

public extension FieldCodec {
    static func fieldType(
        nullable: Bool,
        trackRef: Bool,
        resolveSerializerTypeId _: (Any.Type) throws -> TypeId
    ) throws -> TypeMeta.FieldType {
        TypeMeta.FieldType(
            typeID: fieldWireTypeId(Self.self).rawValue,
            nullable: nullable,
            trackRef: trackRef
        )
    }

    @inlinable
    static func writeFieldData(
        _ value: Target,
        _ context: WriteContext,
        hasDeclaredChildren _: Bool
    ) throws {
        try Self.writeData(value, context)
    }

    @inlinable
    static func readFieldData(_ context: ReadContext) throws -> Target {
        try Self.readData(context)
    }

    static func writeFieldTypeInfo(_ context: WriteContext) throws {
        let fieldTypeId = fieldWireTypeId(Self.self)
        if fieldTypeId == staticTypeId {
            try Self.writeTypeInfo(context)
        } else {
            context.writeStaticTypeInfo(fieldTypeId)
        }
    }

    static func readFieldTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        let fieldTypeId = fieldWireTypeId(Self.self)
        if fieldTypeId == staticTypeId {
            return try Self.readTypeInfo(context)
        }
        return try context.readStaticTypeInfo(fieldTypeId)
    }

    static func withFieldTypeInfo<R>(
        _ typeInfo: TypeInfo?,
        _ context: ReadContext,
        _ body: () throws -> R
    ) rethrows -> R {
        try context.withTypeInfo(typeInfo, for: Self.self, body)
    }

    @inlinable
    static func writeField(
        _ value: Target,
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool,
        hasDeclaredChildren: Bool
    ) throws {
        if refMode != .none {
            if refMode == .tracking {
                if isRefType {
                    let object = value as AnyObject
                    if context.refWriter.tryWriteRef(buffer: context.buffer, object: object) {
                        return
                    }
                } else {
                    // Peers that track every object assign this value a ref id too.
                    context.buffer.writeInt8(RefFlag.refValue.rawValue)
                    context.refWriter.reserveRefID()
                }
            } else {
                context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
            }
        }

        if writeTypeInfo {
            try Self.writeFieldTypeInfo(context)
        }
        try Self.writeFieldData(
            value,
            context,
            hasDeclaredChildren: hasDeclaredChildren
        )
    }

    @inlinable
    static func readField(
        _ context: ReadContext,
        refMode: RefMode
    ) throws -> Target {
        try readField(
            context,
            refMode: refMode,
            readTypeInfo: TypeId.needsTypeInfoForField(fieldWireTypeId(Self.self))
        )
    }

    @inlinable
    static func readField(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Target {
        switch refMode {
        case .none:
            return try readFieldValue(context, readTypeInfo: readTypeInfo)
        case .nullOnly:
            let rawFlag = try context.buffer.readInt8()
            switch rawFlag {
            case RefFlag.null.rawValue:
                return try Self.defaultValue(context)
            case RefFlag.notNullValue.rawValue:
                return try readFieldValue(context, readTypeInfo: readTypeInfo)
            case RefFlag.refValue.rawValue:
                let reservedRefID = context.trackRef ? context.refReader.reserveRefID() : nil
                let value = try readFieldValue(context, readTypeInfo: readTypeInfo)
                if let reservedRefID {
                    context.refReader.storeRef(value, at: reservedRefID)
                }
                return value
            case RefFlag.ref.rawValue:
                let refID = try context.buffer.readVarUInt32()
                return try context.refReader.readRef(refID, as: Target.self)
            default:
                throw invalidFieldRefFlag(rawFlag)
            }
        case .tracking:
            let rawFlag = try context.buffer.readInt8()
            guard let flag = RefFlag(rawValue: rawFlag) else {
                throw invalidFieldRefFlag(rawFlag)
            }
            switch flag {
            case .null:
                return try Self.defaultValue(context)
            case .ref:
                let refID = try context.buffer.readVarUInt32()
                return try context.refReader.readRef(refID, as: Target.self)
            case .refValue:
                let reservedRefID = context.trackRef ? context.refReader.reserveRefID() : nil
                let value = try readFieldValue(context, readTypeInfo: readTypeInfo)
                if let reservedRefID {
                    context.refReader.storeRef(value, at: reservedRefID)
                }
                return value
            case .notNullValue:
                return try readFieldValue(context, readTypeInfo: readTypeInfo)
            }
        }
    }

    static func readCompatibleField(
        _ context: ReadContext,
        remoteFieldType: TypeMeta.FieldType,
        refMode: RefMode
    ) throws -> Target {
        try readField(
            context,
            refMode: refMode,
            readTypeInfo: TypeId.needsTypeInfoForField(
                TypeId(rawValue: remoteFieldType.typeID) ?? .unknown)
        )
    }

    @inlinable
    @inline(__always)
    internal static func readFieldValue(
        _ context: ReadContext,
        readTypeInfo: Bool
    ) throws -> Target {
        if readTypeInfo {
            let typeInfo = try Self.readFieldTypeInfo(context)
            return try withFieldTypeInfo(typeInfo, context) {
                try Self.readFieldData(context)
            }
        }
        return try Self.readFieldData(context)
    }
}

public extension FieldCodec where Target: Serializer, Target.Target == Target {
    @inlinable
    static var staticTypeId: TypeId { Target.staticTypeId }

    @inlinable
    static var isNullableType: Bool { Target.isNullableType }

    @inlinable
    static var isRefType: Bool { Target.isRefType }

    @inlinable
    static var isWrapper: Bool { Target.isWrapper }

    @inlinable
    static func isNone(_ value: Target) -> Bool {
        Target.isNone(value)
    }

    @inlinable
    static func defaultValue(_ context: ReadContext) throws -> Target {
        try Target.defaultValue(context)
    }

    @inlinable
    static func writeData(_ value: Target, _ context: WriteContext) throws {
        try Target.writeData(value, context)
    }

    @inlinable
    static func readData(_ context: ReadContext) throws -> Target {
        try Target.readData(context)
    }

    @inlinable
    static func write(
        _ value: Target,
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool
    ) throws {
        try Target.write(
            value,
            context,
            refMode: refMode,
            writeTypeInfo: writeTypeInfo
        )
    }

    @inlinable
    static func read(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Target {
        try Target.read(
            context,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
    }

    @inlinable
    static func writeTypeInfo(_ context: WriteContext) throws {
        try Target.writeTypeInfo(context)
    }

    @inlinable
    static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try Target.readTypeInfo(context)
    }
}

@usableFromInline
@inline(__always)
internal func fieldWireTypeId<Codec: FieldCodec>(_: Codec.Type) -> TypeId {
    if Codec.self == Int32FixedCodec.self {
        return .int32
    }
    if Codec.self == Int64FixedCodec.self || Codec.self == IntFixedCodec.self {
        return .int64
    }
    if Codec.self == Int64TaggedCodec.self || Codec.self == IntTaggedCodec.self {
        return .taggedInt64
    }
    if Codec.self == UInt32FixedCodec.self {
        return .uint32
    }
    if Codec.self == UInt64FixedCodec.self || Codec.self == UIntFixedCodec.self {
        return .uint64
    }
    if Codec.self == UInt64TaggedCodec.self || Codec.self == UIntTaggedCodec.self {
        return .taggedUInt64
    }
    return Codec.staticTypeId
}

@inline(never)
private func validateSerializerCodecLeaf<S: Serializer>(_ serializer: S.Type) throws {
    if serializer.isWrapper {
        throw ForyError.invalidData(
            "\(serializer) is a transparent carrier; declare its recursive field shape"
        )
    }
    switch serializer.staticTypeId {
    case .list, .set, .map, .array,
        .boolArray, .int8Array, .int16Array, .int32Array, .int64Array,
        .uint8Array, .uint16Array, .uint32Array, .uint64Array,
        .float8Array, .float16Array, .bfloat16Array, .float32Array, .float64Array:
        throw ForyError.invalidData(
            "\(serializer) is a carrier serializer; declare its recursive field shape"
        )
    default:
        return
    }
}

@usableFromInline
@inline(never)
internal func invalidFieldRefFlag(_ rawFlag: Int8) -> ForyError {
    ForyError.refError("invalid ref flag \(rawFlag)")
}

/// Adapts `S` to one generated field leaf without changing its target or
/// value-level wire body. Fory never instantiates this zero-state type.
public enum SerializerCodec<S: Serializer>: FieldCodec {
    public typealias Target = S.Target

    @inlinable
    public static var staticTypeId: TypeId { S.staticTypeId }
    @inlinable
    public static var isNullableType: Bool { S.isNullableType }
    @inlinable
    public static var isRefType: Bool { S.isRefType }
    @inlinable
    public static var isWrapper: Bool { S.isWrapper }
    @inlinable
    public static var readDataAlwaysAdvances: Bool { S.readDataAlwaysAdvances }

    @inlinable
    public static func isNone(_ value: Target) -> Bool {
        S.isNone(value)
    }

    @inlinable
    public static func defaultValue(_ context: ReadContext) throws -> Target {
        try S.defaultValue(context)
    }

    @inlinable
    public static func writeData(_ value: Target, _ context: WriteContext) throws {
        try S.writeData(value, context)
    }

    @inlinable
    public static func readData(_ context: ReadContext) throws -> Target {
        return try S.readData(context)
    }

    @inlinable
    public static func write(
        _ value: Target,
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool
    ) throws {
        try S.write(
            value,
            context,
            refMode: refMode,
            writeTypeInfo: writeTypeInfo
        )
    }

    @inlinable
    public static func read(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Target {
        try S.read(
            context,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
    }

    @inlinable
    public static func writeTypeInfo(_ context: WriteContext) throws {
        try S.writeTypeInfo(context)
    }

    @inlinable
    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try S.readTypeInfo(context)
    }

    public static func fieldType(
        nullable: Bool,
        trackRef: Bool,
        resolveSerializerTypeId: (Any.Type) throws -> TypeId
    ) throws -> TypeMeta.FieldType {
        try validateSerializerCodecLeaf(S.self)
        let typeId =
            S.staticTypeId.isUserTypeKind
            ? try resolveSerializerTypeId(S.self)
            : S.staticTypeId
        return TypeMeta.FieldType(
            typeID: typeId.rawValue,
            nullable: nullable,
            trackRef: trackRef
        )
    }

    @inlinable
    public static func writeFieldData(
        _ value: Target,
        _ context: WriteContext,
        hasDeclaredChildren _: Bool
    ) throws {
        try S.writeData(value, context)
    }

    @inline(__always)
    public static func readFieldData(_ context: ReadContext) throws -> Target {
        if S.staticTypeId.isUserTypeKind {
            return try S.read(
                context,
                refMode: .none,
                readTypeInfo: false
            )
        }
        return try S.readData(context)
    }

    @inlinable
    public static func writeFieldTypeInfo(_ context: WriteContext) throws {
        try S.writeTypeInfo(context)
    }

    @inlinable
    public static func readFieldTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try S.readTypeInfo(context)
    }

    public static func withFieldTypeInfo<R>(
        _ typeInfo: TypeInfo?,
        _ context: ReadContext,
        _ body: () throws -> R
    ) rethrows -> R {
        try context.withTypeInfo(typeInfo, for: S.self, body)
    }

    @inlinable
    public static func writeField(
        _ value: Target,
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool,
        hasDeclaredChildren _: Bool
    ) throws {
        try S.write(
            value,
            context,
            refMode: refMode,
            writeTypeInfo: writeTypeInfo
        )
    }

    @inline(__always)
    public static func readField(
        _ context: ReadContext,
        refMode: RefMode
    ) throws -> Target {
        try S.read(
            context,
            refMode: refMode,
            readTypeInfo: TypeId.needsTypeInfoForField(S.staticTypeId)
        )
    }

    @inlinable
    public static func readField(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Target {
        try S.read(
            context,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
    }
}

public enum BoolCodec: FieldCodec {
    public typealias Target = Bool
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: Bool, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeUInt8(value ? 1 : 0)
    }

    public static func readFieldData(_ context: ReadContext) throws -> Bool {
        try context.buffer.readUInt8() != 0
    }
}

public enum Int8Codec: FieldCodec {
    public typealias Target = Int8
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: Int8, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeInt8(value)
    }

    public static func readFieldData(_ context: ReadContext) throws -> Int8 {
        try context.buffer.readInt8()
    }
}

public enum Int16Codec: FieldCodec {
    public typealias Target = Int16
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: Int16, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeInt16(value)
    }

    public static func readFieldData(_ context: ReadContext) throws -> Int16 {
        try context.buffer.readInt16()
    }
}

public enum Int32VarintCodec: FieldCodec {
    public typealias Target = Int32
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: Int32, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeVarInt32(value)
    }

    public static func readFieldData(_ context: ReadContext) throws -> Int32 {
        try context.buffer.readVarInt32()
    }
}

public enum Int32FixedCodec: FieldCodec {
    public typealias Target = Int32
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: Int32, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeInt32(value)
    }

    public static func readFieldData(_ context: ReadContext) throws -> Int32 {
        try context.buffer.readInt32()
    }
}

public enum Int64VarintCodec: FieldCodec {
    public typealias Target = Int64
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: Int64, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeVarInt64(value)
    }

    public static func readFieldData(_ context: ReadContext) throws -> Int64 {
        try context.buffer.readVarInt64()
    }
}

public enum Int64FixedCodec: FieldCodec {
    public typealias Target = Int64
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: Int64, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeInt64(value)
    }

    public static func readFieldData(_ context: ReadContext) throws -> Int64 {
        try context.buffer.readInt64()
    }
}

public enum Int64TaggedCodec: FieldCodec {
    public typealias Target = Int64
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: Int64, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeTaggedInt64(value)
    }

    public static func readFieldData(_ context: ReadContext) throws -> Int64 {
        try context.buffer.readTaggedInt64()
    }
}

public enum UInt8Codec: FieldCodec {
    public typealias Target = UInt8
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: UInt8, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeUInt8(value)
    }

    public static func readFieldData(_ context: ReadContext) throws -> UInt8 {
        try context.buffer.readUInt8()
    }
}

public enum UInt16Codec: FieldCodec {
    public typealias Target = UInt16
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: UInt16, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeUInt16(value)
    }

    public static func readFieldData(_ context: ReadContext) throws -> UInt16 {
        try context.buffer.readUInt16()
    }
}

public enum UInt32VarintCodec: FieldCodec {
    public typealias Target = UInt32
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: UInt32, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeVarUInt32(value)
    }

    public static func readFieldData(_ context: ReadContext) throws -> UInt32 {
        try context.buffer.readVarUInt32()
    }
}

public enum UInt32FixedCodec: FieldCodec {
    public typealias Target = UInt32
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: UInt32, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeUInt32(value)
    }

    public static func readFieldData(_ context: ReadContext) throws -> UInt32 {
        try context.buffer.readUInt32()
    }
}

public enum UInt64VarintCodec: FieldCodec {
    public typealias Target = UInt64
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: UInt64, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeVarUInt64(value)
    }

    public static func readFieldData(_ context: ReadContext) throws -> UInt64 {
        try context.buffer.readVarUInt64()
    }
}

public enum UInt64FixedCodec: FieldCodec {
    public typealias Target = UInt64
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: UInt64, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeUInt64(value)
    }

    public static func readFieldData(_ context: ReadContext) throws -> UInt64 {
        try context.buffer.readUInt64()
    }
}

public enum UInt64TaggedCodec: FieldCodec {
    public typealias Target = UInt64
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: UInt64, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeTaggedUInt64(value)
    }

    public static func readFieldData(_ context: ReadContext) throws -> UInt64 {
        try context.buffer.readTaggedUInt64()
    }
}

public enum IntVarintCodec: FieldCodec {
    public typealias Target = Int
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: Int, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeVarInt64(Int64(value))
    }

    public static func readFieldData(_ context: ReadContext) throws -> Int {
        Int(try context.buffer.readVarInt64())
    }
}

public enum IntFixedCodec: FieldCodec {
    public typealias Target = Int
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: Int, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeInt64(Int64(value))
    }

    public static func readFieldData(_ context: ReadContext) throws -> Int {
        Int(try context.buffer.readInt64())
    }
}

public enum IntTaggedCodec: FieldCodec {
    public typealias Target = Int
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: Int, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeTaggedInt64(Int64(value))
    }

    public static func readFieldData(_ context: ReadContext) throws -> Int {
        Int(try context.buffer.readTaggedInt64())
    }
}

public enum UIntVarintCodec: FieldCodec {
    public typealias Target = UInt
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: UInt, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeVarUInt64(UInt64(value))
    }

    public static func readFieldData(_ context: ReadContext) throws -> UInt {
        UInt(try context.buffer.readVarUInt64())
    }
}

public enum UIntFixedCodec: FieldCodec {
    public typealias Target = UInt
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: UInt, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeUInt64(UInt64(value))
    }

    public static func readFieldData(_ context: ReadContext) throws -> UInt {
        UInt(try context.buffer.readUInt64())
    }
}

public enum UIntTaggedCodec: FieldCodec {
    public typealias Target = UInt
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: UInt, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeTaggedUInt64(UInt64(value))
    }

    public static func readFieldData(_ context: ReadContext) throws -> UInt {
        UInt(try context.buffer.readTaggedUInt64())
    }
}

public enum Float16Codec: FieldCodec {
    public typealias Target = Float16
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: Float16, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeUInt16(value.bitPattern)
    }

    public static func readFieldData(_ context: ReadContext) throws -> Float16 {
        Float16(bitPattern: try context.buffer.readUInt16())
    }
}

public enum BFloat16Codec: FieldCodec {
    public typealias Target = BFloat16
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: BFloat16, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeUInt16(value.rawValue)
    }

    public static func readFieldData(_ context: ReadContext) throws -> BFloat16 {
        BFloat16(rawValue: try context.buffer.readUInt16())
    }
}

public enum FloatCodec: FieldCodec {
    public typealias Target = Float
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: Float, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeFloat32(value)
    }

    public static func readFieldData(_ context: ReadContext) throws -> Float {
        try context.buffer.readFloat32()
    }
}

public enum DoubleCodec: FieldCodec {
    public typealias Target = Double
    public static var readDataAlwaysAdvances: Bool { true }

    public static func writeFieldData(
        _ value: Double, _ context: WriteContext, hasDeclaredChildren _: Bool
    ) {
        context.buffer.writeFloat64(value)
    }

    public static func readFieldData(_ context: ReadContext) throws -> Double {
        try context.buffer.readFloat64()
    }
}

public typealias StringCodec = SerializerCodec<String>
public typealias DurationCodec = SerializerCodec<Duration>
public typealias TimestampCodec = SerializerCodec<Date>
public typealias LocalDateCodec = SerializerCodec<LocalDate>
public typealias DecimalCodec = SerializerCodec<Decimal>
public typealias DataCodec = SerializerCodec<Data>

public enum ArrayFieldCodec<ElementCodec: FieldCodec>: FieldCodec {
    public typealias Target = [ElementCodec.Target]

    public static var staticTypeId: TypeId { ArraySerializer<ElementCodec>.staticTypeId }
    public static var readDataAlwaysAdvances: Bool { true }

    public static func defaultValue(_ context: ReadContext) throws -> Target {
        try ArraySerializer<ElementCodec>.defaultValue(context)
    }

    public static func writeData(_ value: Target, _ context: WriteContext) throws {
        try ArraySerializer<ElementCodec>.writeData(value, context)
    }

    public static func readData(_ context: ReadContext) throws -> Target {
        try ArraySerializer<ElementCodec>.readData(context)
    }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        try ArraySerializer<ElementCodec>.writeTypeInfo(context)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try ArraySerializer<ElementCodec>.readTypeInfo(context)
    }

    public static func fieldType(
        nullable: Bool,
        trackRef: Bool,
        resolveSerializerTypeId _: (Any.Type) throws -> TypeId
    ) throws -> TypeMeta.FieldType {
        TypeMeta.FieldType(
            typeID: try requirePackedArrayTypeId(ElementCodec.self).rawValue,
            nullable: nullable,
            trackRef: trackRef
        )
    }

    public static func writeFieldData(
        _ value: Target,
        _ context: WriteContext,
        hasDeclaredChildren _: Bool
    ) throws {
        if try writePackedArrayPayload(value, context, elementCodec: ElementCodec.self) {
            return
        }
        throw unsupportedArrayFieldCodec(ElementCodec.self)
    }

    public static func readFieldData(_ context: ReadContext) throws -> Target {
        if let value = try readPackedArrayPayload(
            context,
            elementCodec: ElementCodec.self
        ) {
            return value
        }
        throw unsupportedArrayFieldCodec(ElementCodec.self)
    }

    public static func writeFieldTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(try requirePackedArrayTypeId(ElementCodec.self))
    }

    public static func readFieldTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(requirePackedArrayTypeId(ElementCodec.self))
    }

    public static func readCompatibleField(
        _ context: ReadContext,
        remoteFieldType: TypeMeta.FieldType,
        refMode: RefMode
    ) throws -> Target {
        if remoteFieldType.typeID == TypeId.list.rawValue,
            let element = remoteFieldType.generics.first,
            let localArrayTypeID = packedArrayTypeID(for: ElementCodec.self),
            TypeId.listElementTypeID(element.typeID, matchesDenseArrayTypeID: localArrayTypeID.rawValue)
        {
            return try readListPayloadAsArray(
                context,
                refMode: refMode,
                elementCodec: ElementCodec.self,
                remoteElementTypeID: element.typeID
            )
        }
        return try readField(
            context,
            refMode: refMode,
            readTypeInfo: TypeId.needsTypeInfoForField(
                TypeId(rawValue: remoteFieldType.typeID) ?? .unknown
            )
        )
    }

    @inlinable
    @inline(__always)
    public static func writeField(
        _ value: Target,
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool,
        hasDeclaredChildren: Bool
    ) throws {
        if refMode == .none, !writeTypeInfo {
            try writeFieldData(
                value,
                context,
                hasDeclaredChildren: hasDeclaredChildren
            )
            return
        }
        if refMode != .none {
            context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
        }
        if writeTypeInfo {
            try writeFieldTypeInfo(context)
        }
        try writeFieldData(
            value,
            context,
            hasDeclaredChildren: hasDeclaredChildren
        )
    }

    public static func readField(
        _ context: ReadContext,
        refMode: RefMode
    ) throws -> Target {
        try readField(context, refMode: refMode, readTypeInfo: false)
    }

    @inlinable
    @inline(__always)
    public static func readField(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Target {
        if refMode == .none {
            return try readFieldDataAfterTypeInfo(context, readTypeInfo: readTypeInfo)
        }
        return try readFieldWithEnvelope(
            context,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
    }

    // Keep the reference envelope out of generated `.none` packed-array reads.
    @usableFromInline
    internal static func readFieldWithEnvelope(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Target {
        switch refMode {
        case .none:
            return try readFieldDataAfterTypeInfo(context, readTypeInfo: readTypeInfo)
        case .nullOnly:
            let rawFlag = try context.buffer.readInt8()
            switch rawFlag {
            case RefFlag.null.rawValue:
                return []
            case RefFlag.notNullValue.rawValue:
                return try readFieldDataAfterTypeInfo(context, readTypeInfo: readTypeInfo)
            case RefFlag.refValue.rawValue:
                if context.trackRef {
                    let reservedRefID = context.refReader.reserveRefID()
                    let value = try readFieldDataAfterTypeInfo(context, readTypeInfo: readTypeInfo)
                    context.refReader.storeRef(value, at: reservedRefID)
                    return value
                }
                return try readFieldDataAfterTypeInfo(context, readTypeInfo: readTypeInfo)
            case RefFlag.ref.rawValue:
                let refID = try context.buffer.readVarUInt32()
                return try context.refReader.readRef(refID, as: Target.self)
            default:
                throw invalidFieldRefFlag(rawFlag)
            }
        case .tracking:
            let rawFlag = try context.buffer.readInt8()
            guard let flag = RefFlag(rawValue: rawFlag) else {
                throw invalidFieldRefFlag(rawFlag)
            }
            switch flag {
            case .null:
                return []
            case .ref:
                let refID = try context.buffer.readVarUInt32()
                return try context.refReader.readRef(refID, as: Target.self)
            case .refValue:
                let reservedRefID = context.trackRef ? context.refReader.reserveRefID() : nil
                let value = try readFieldDataAfterTypeInfo(context, readTypeInfo: readTypeInfo)
                if let reservedRefID {
                    context.refReader.storeRef(value, at: reservedRefID)
                }
                return value
            case .notNullValue:
                return try readFieldDataAfterTypeInfo(context, readTypeInfo: readTypeInfo)
            }
        }
    }

    @inlinable
    @inline(__always)
    internal static func readFieldDataAfterTypeInfo(
        _ context: ReadContext,
        readTypeInfo: Bool
    ) throws -> Target {
        if readTypeInfo {
            let typeInfo = try Self.readFieldTypeInfo(context)
            return try withFieldTypeInfo(typeInfo, context) {
                try readFieldData(context)
            }
        }
        return try readFieldData(context)
    }
}

public extension ArraySerializer where Element: FieldCodec {
    static func readCompatibleField(
        _ context: ReadContext,
        remoteFieldType: TypeMeta.FieldType,
        refMode: RefMode
    ) throws -> Target {
        if isCompatiblePackedArrayTypeID(
            remoteFieldType.typeID,
            elementCodec: Element.self
        ) {
            return try readCompatiblePackedArrayField(
                context,
                refMode: refMode,
                elementCodec: Element.self
            )
        }
        return try readField(
            context,
            refMode: refMode,
            readTypeInfo: TypeId.needsTypeInfoForField(
                TypeId(rawValue: remoteFieldType.typeID) ?? .unknown
            )
        )
    }
}

@inline(__always)
private func uncheckedPackedArrayCast<From, To>(_ array: [From], to _: To.Type) -> [To] {
    assert(From.self == To.self)
    return unsafeBitCast(array, to: [To].self)
}

@inline(__always)
private func uncheckedScalarCast<From, To>(_ value: From, to _: To.Type) -> To {
    assert(From.self == To.self)
    return unsafeBitCast(value, to: To.self)
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

@inline(never)
private func requirePackedArrayTypeId<ElementCodec: FieldCodec>(
    _ codec: ElementCodec.Type
) throws -> TypeId {
    guard let typeId = packedArrayTypeID(for: codec) else {
        throw unsupportedArrayFieldCodec(codec)
    }
    return typeId
}

@inline(never)
private func unsupportedArrayFieldCodec<ElementCodec: FieldCodec>(
    _ codec: ElementCodec.Type
) -> ForyError {
    ForyError.invalidData(
        "ArrayFieldCodec requires a canonical non-null numeric or bool codec, got \(codec)"
    )
}

private func isCompatiblePackedArrayTypeID<ElementCodec: FieldCodec>(
    _ typeID: UInt32,
    elementCodec _: ElementCodec.Type
) -> Bool {
    TypeId.listElementTypeID(
        fieldWireTypeId(ElementCodec.self).rawValue,
        matchesDenseArrayTypeID: typeID
    )
}

private func writePackedArrayPayload<ElementCodec: FieldCodec>(
    _ value: [ElementCodec.Target],
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
    reserveGraphStorage: Bool = false,
    elementCodec _: ElementCodec.Type
) throws -> [ElementCodec.Target]? {
    if ElementCodec.self == BoolCodec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: reserveGraphStorage) as [Bool],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == Int8Codec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: reserveGraphStorage) as [Int8],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == Int16Codec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: reserveGraphStorage) as [Int16],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == Int32FixedCodec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: reserveGraphStorage) as [Int32],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == Int64FixedCodec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: reserveGraphStorage) as [Int64],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == IntFixedCodec.self {
        return uncheckedPackedArrayCast(
            try readIntArrayPayload(context, reserveGraphStorage: reserveGraphStorage),
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == UInt8Codec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: reserveGraphStorage) as [UInt8],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == UInt16Codec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: reserveGraphStorage) as [UInt16],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == UInt32FixedCodec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: reserveGraphStorage) as [UInt32],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == UInt64FixedCodec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: reserveGraphStorage) as [UInt64],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == UIntFixedCodec.self {
        return uncheckedPackedArrayCast(
            try readUIntArrayPayload(context, reserveGraphStorage: reserveGraphStorage),
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == Float16Codec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: reserveGraphStorage) as [Float16],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == BFloat16Codec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: reserveGraphStorage) as [BFloat16],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == FloatCodec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: reserveGraphStorage) as [Float],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == DoubleCodec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: reserveGraphStorage) as [Double],
            to: ElementCodec.Target.self)
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

private func readIntArrayPayload(
    _ context: ReadContext, reserveGraphStorage: Bool = false
) throws
    -> [Int]
{
    let count = try readPackedArrayElementCount(context, width: 8, label: "int64_array")
    if reserveGraphStorage {
        try reserveSerializerArrayMemory(
            context, Int.self, ownerBytes: fieldOwnerBytes([Int].self), count: count)
    }
    var values: [Int] = []
    values.reserveCapacity(count)
    for _ in 0..<count {
        values.append(Int(try context.buffer.readInt64()))
    }
    return values
}

private func readUIntArrayPayload(
    _ context: ReadContext, reserveGraphStorage: Bool = false
) throws
    -> [UInt]
{
    let count = try readPackedArrayElementCount(context, width: 8, label: "uint64_array")
    if reserveGraphStorage {
        try reserveSerializerArrayMemory(
            context, UInt.self, ownerBytes: fieldOwnerBytes([UInt].self), count: count)
    }
    var values: [UInt] = []
    values.reserveCapacity(count)
    for _ in 0..<count {
        values.append(UInt(try context.buffer.readUInt64()))
    }
    return values
}

@inline(never)
private func readCompatiblePackedArrayField<ElementCodec: FieldCodec>(
    _ context: ReadContext,
    refMode: RefMode,
    elementCodec _: ElementCodec.Type
) throws -> [ElementCodec.Target] {
    switch refMode {
    case .none:
        return try readCompatiblePackedArrayPayload(context, elementCodec: ElementCodec.self)
    case .nullOnly, .tracking:
        let rawFlag = try context.buffer.readInt8()
        guard rawFlag != RefFlag.null.rawValue else {
            return []
        }
        if rawFlag == RefFlag.ref.rawValue {
            let refID = try context.buffer.readVarUInt32()
            return try context.refReader.readRef(refID, as: [ElementCodec.Target].self)
        }
        let reservedRefID =
            (rawFlag == RefFlag.refValue.rawValue && context.trackRef)
            ? context.refReader.reserveRefID()
            : nil
        let value = try readCompatiblePackedArrayPayload(context, elementCodec: ElementCodec.self)
        if let reservedRefID {
            context.refReader.storeRef(value, at: reservedRefID)
        }
        return value
    }
}

private func readCompatiblePackedArrayPayload<ElementCodec: FieldCodec>(
    _ context: ReadContext,
    elementCodec _: ElementCodec.Type
) throws -> [ElementCodec.Target] {
    if ElementCodec.self == BoolCodec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: true) as [Bool],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == Int8Codec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: true) as [Int8],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == Int16Codec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: true) as [Int16],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == Int32FixedCodec.self || ElementCodec.self == Int32VarintCodec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: true) as [Int32],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == Int64FixedCodec.self || ElementCodec.self == Int64VarintCodec.self
        || ElementCodec.self == Int64TaggedCodec.self
    {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: true) as [Int64],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == IntFixedCodec.self || ElementCodec.self == IntVarintCodec.self
        || ElementCodec.self == IntTaggedCodec.self
    {
        return uncheckedPackedArrayCast(
            try readIntArrayPayload(context, reserveGraphStorage: true), to: ElementCodec.Target.self)
    }
    if ElementCodec.self == UInt8Codec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: true) as [UInt8],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == UInt16Codec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: true) as [UInt16],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == UInt32FixedCodec.self || ElementCodec.self == UInt32VarintCodec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: true) as [UInt32],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == UInt64FixedCodec.self || ElementCodec.self == UInt64VarintCodec.self
        || ElementCodec.self == UInt64TaggedCodec.self
    {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: true) as [UInt64],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == UIntFixedCodec.self || ElementCodec.self == UIntVarintCodec.self
        || ElementCodec.self == UIntTaggedCodec.self
    {
        return uncheckedPackedArrayCast(
            try readUIntArrayPayload(context, reserveGraphStorage: true), to: ElementCodec.Target.self)
    }
    if ElementCodec.self == Float16Codec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: true) as [Float16],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == BFloat16Codec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: true) as [BFloat16],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == FloatCodec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: true) as [Float],
            to: ElementCodec.Target.self)
    }
    if ElementCodec.self == DoubleCodec.self {
        return uncheckedPackedArrayCast(
            try readPrimitiveArray(context, reserveGraphStorage: true) as [Double],
            to: ElementCodec.Target.self)
    }
    throw ForyError.invalidData(
        "unsupported compatible array-to-list field element codec \(ElementCodec.self)")
}

private func readCompatibleElementPayload<ElementCodec: FieldCodec>(
    _ context: ReadContext,
    elementCodec _: ElementCodec.Type,
    remoteElementTypeID: UInt32?
) throws -> ElementCodec.Target {
    guard let remoteElementTypeID,
        remoteElementTypeID != fieldWireTypeId(ElementCodec.self).rawValue,
        let remoteTypeID = TypeId(rawValue: remoteElementTypeID)
    else {
        return try ElementCodec.readFieldData(context)
    }

    if ElementCodec.self == Int32FixedCodec.self || ElementCodec.self == Int32VarintCodec.self {
        switch remoteTypeID {
        case .int32:
            return uncheckedScalarCast(
                try context.buffer.readInt32() as Int32, to: ElementCodec.Target.self)
        case .varint32:
            return uncheckedScalarCast(
                try context.buffer.readVarInt32() as Int32, to: ElementCodec.Target.self)
        default:
            break
        }
    }
    if ElementCodec.self == Int64FixedCodec.self || ElementCodec.self == Int64VarintCodec.self
        || ElementCodec.self == Int64TaggedCodec.self
    {
        switch remoteTypeID {
        case .int64:
            return uncheckedScalarCast(
                try context.buffer.readInt64() as Int64, to: ElementCodec.Target.self)
        case .varint64:
            return uncheckedScalarCast(
                try context.buffer.readVarInt64() as Int64, to: ElementCodec.Target.self)
        case .taggedInt64:
            return uncheckedScalarCast(
                try context.buffer.readTaggedInt64() as Int64, to: ElementCodec.Target.self)
        default:
            break
        }
    }
    if ElementCodec.self == IntFixedCodec.self || ElementCodec.self == IntVarintCodec.self
        || ElementCodec.self == IntTaggedCodec.self
    {
        switch remoteTypeID {
        case .int64:
            return uncheckedScalarCast(Int(try context.buffer.readInt64()), to: ElementCodec.Target.self)
        case .varint64:
            return uncheckedScalarCast(
                Int(try context.buffer.readVarInt64()), to: ElementCodec.Target.self)
        case .taggedInt64:
            return uncheckedScalarCast(
                Int(try context.buffer.readTaggedInt64()), to: ElementCodec.Target.self)
        default:
            break
        }
    }
    if ElementCodec.self == UInt32FixedCodec.self || ElementCodec.self == UInt32VarintCodec.self {
        switch remoteTypeID {
        case .uint32:
            return uncheckedScalarCast(
                try context.buffer.readUInt32() as UInt32, to: ElementCodec.Target.self)
        case .varUInt32:
            return uncheckedScalarCast(
                try context.buffer.readVarUInt32() as UInt32, to: ElementCodec.Target.self)
        default:
            break
        }
    }
    if ElementCodec.self == UInt64FixedCodec.self || ElementCodec.self == UInt64VarintCodec.self
        || ElementCodec.self == UInt64TaggedCodec.self
    {
        switch remoteTypeID {
        case .uint64:
            return uncheckedScalarCast(
                try context.buffer.readUInt64() as UInt64, to: ElementCodec.Target.self)
        case .varUInt64:
            return uncheckedScalarCast(
                try context.buffer.readVarUInt64() as UInt64, to: ElementCodec.Target.self)
        case .taggedUInt64:
            return uncheckedScalarCast(
                try context.buffer.readTaggedUInt64() as UInt64, to: ElementCodec.Target.self)
        default:
            break
        }
    }
    if ElementCodec.self == UIntFixedCodec.self || ElementCodec.self == UIntVarintCodec.self
        || ElementCodec.self == UIntTaggedCodec.self
    {
        switch remoteTypeID {
        case .uint64:
            return uncheckedScalarCast(UInt(try context.buffer.readUInt64()), to: ElementCodec.Target.self)
        case .varUInt64:
            return uncheckedScalarCast(
                UInt(try context.buffer.readVarUInt64()), to: ElementCodec.Target.self)
        case .taggedUInt64:
            return uncheckedScalarCast(
                UInt(try context.buffer.readTaggedUInt64()), to: ElementCodec.Target.self)
        default:
            break
        }
    }
    throw ForyError.typeMismatch(
        expected: fieldWireTypeId(ElementCodec.self).rawValue,
        actual: remoteElementTypeID
    )
}

private func readPackedArrayElementCount(
    _ context: ReadContext,
    width: Int,
    label: String
) throws -> Int {
    let byteSize = Int(try context.buffer.readVarUInt32())
    try context.ensureRemainingBytes(byteSize, label: "primitive_array_bytes")
    if byteSize % width != 0 {
        throw ForyError.invalidData("\(label) byte size mismatch")
    }
    let count = byteSize / width
    try context.ensureCollectionLength(count, label: label)
    return count
}

@inline(__always)
private func minimumListElementBytes(_ rawTypeID: UInt32) throws -> Int {
    guard let typeID = TypeId(rawValue: rawTypeID) else {
        throw ForyError.invalidData("unsupported compatible list element type id \(rawTypeID)")
    }
    switch typeID {
    case .bool, .int8, .uint8, .varint32, .varUInt32, .varint64, .varUInt64:
        return 1
    case .int16, .uint16, .float16, .bfloat16:
        return 2
    case .int32, .uint32, .float32, .taggedInt64, .taggedUInt64:
        return 4
    case .int64, .uint64, .float64:
        return 8
    default:
        throw ForyError.invalidData("unsupported compatible list element type id \(rawTypeID)")
    }
}

@inline(never)
private func readListPayloadAsArray<ElementCodec: FieldCodec>(
    _ context: ReadContext,
    refMode: RefMode,
    elementCodec _: ElementCodec.Type,
    remoteElementTypeID: UInt32
) throws -> [ElementCodec.Target] {
    switch refMode {
    case .none:
        return try readListPayloadAsArrayPayload(
            context,
            elementCodec: ElementCodec.self,
            remoteElementTypeID: remoteElementTypeID
        )
    case .nullOnly, .tracking:
        let rawFlag = try context.buffer.readInt8()
        guard rawFlag != RefFlag.null.rawValue else {
            return []
        }
        if rawFlag == RefFlag.ref.rawValue {
            let refID = try context.buffer.readVarUInt32()
            return try context.refReader.readRef(refID, as: [ElementCodec.Target].self)
        }
        let reservedRefID =
            (rawFlag == RefFlag.refValue.rawValue && context.trackRef)
            ? context.refReader.reserveRefID()
            : nil
        let value = try readListPayloadAsArrayPayload(
            context,
            elementCodec: ElementCodec.self,
            remoteElementTypeID: remoteElementTypeID
        )
        if let reservedRefID {
            context.refReader.storeRef(value, at: reservedRefID)
        }
        return value
    }
}

private func readListPayloadAsArrayPayload<ElementCodec: FieldCodec>(
    _ context: ReadContext,
    elementCodec _: ElementCodec.Type,
    remoteElementTypeID: UInt32
) throws -> [ElementCodec.Target] {
    let buffer = context.buffer
    let length = Int(try buffer.readVarUInt32())
    try context.ensureCollectionLength(length, label: "array")
    if length == 0 {
        return []
    }

    let header = try buffer.readUInt8()
    let trackRef = (header & CollectionHeader.trackingRef) != 0
    let hasNull = (header & CollectionHeader.hasNull) != 0
    if hasNull {
        throw ForyError.invalidData("compatible list-to-array field cannot read nullable elements")
    }
    let declared = (header & CollectionHeader.declaredElementType) != 0
    let sameType = (header & CollectionHeader.sameType) != 0

    if !sameType {
        throw ForyError.invalidData("compatible list-to-array field requires same-type elements")
    }

    if trackRef {
        throw ForyError.invalidData("compatible list-to-array field cannot read ref-tracked elements")
    }
    let elementTypeInfo: TypeInfo?
    if declared {
        elementTypeInfo = nil
    } else {
        throw ForyError.invalidData("compatible list-to-array field requires declared elements")
    }
    // Prove the remote element encoding before the dense target reserves storage.
    // Variable-width integer encodings use their protocol minimum so compact values remain valid.
    let elementBytes = try minimumListElementBytes(remoteElementTypeID)
    let (requiredBytes, overflow) = length.multipliedReportingOverflow(by: elementBytes)
    if overflow {
        throw ForyError.invalidData("compatible list payload size overflows")
    }
    try context.ensureRemainingBytes(requiredBytes, label: "array")
    var result: [ElementCodec.Target] = []
    result.reserveCapacity(length)
    return try ElementCodec.withFieldTypeInfo(elementTypeInfo, context) {
        for _ in 0..<length {
            result.append(
                try readCompatibleElementPayload(
                    context,
                    elementCodec: ElementCodec.self,
                    remoteElementTypeID: remoteElementTypeID
                ))
        }
        return result
    }
}
