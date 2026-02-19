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
import ForySwift

// MARK: - Shared test types

private enum PeerColor: UInt32 {
    case green = 0
    case red = 1
    case blue = 2
    case white = 3
}

private enum PeerTestEnum: UInt32 {
    case valueA = 0
    case valueB = 1
    case valueC = 2
}

private protocol PeerUInt32EnumSerializer: RawRepresentable, Serializer where RawValue == UInt32 {}

extension PeerUInt32EnumSerializer {
    static func foryDefault() -> Self {
        Self(rawValue: 0)!
    }

    static var staticTypeId: ForyTypeId {
        .enumType
    }

    func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.writer.writeVarUInt32(rawValue)
    }

    static func foryReadData(_ context: ReadContext) throws -> Self {
        let ordinal = try context.reader.readVarUInt32()
        guard let value = Self(rawValue: ordinal) else {
            throw ForyError.invalidData("unknown enum ordinal \(ordinal)")
        }
        return value
    }
}

extension PeerColor: PeerUInt32EnumSerializer {}
extension PeerTestEnum: PeerUInt32EnumSerializer {}

private struct PeerDate: Serializer, Equatable {
    var daysSinceEpoch: Int32 = 0

    static func foryDefault() -> PeerDate {
        PeerDate()
    }

    static var staticTypeId: ForyTypeId {
        .date
    }

    func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.writer.writeInt32(daysSinceEpoch)
    }

    static func foryReadData(_ context: ReadContext) throws -> PeerDate {
        PeerDate(daysSinceEpoch: try context.reader.readInt32())
    }
}

private struct PeerTimestamp: Serializer, Equatable {
    var seconds: Int64 = 0
    var nanos: Int32 = 0

    static func foryDefault() -> PeerTimestamp {
        PeerTimestamp()
    }

    static var staticTypeId: ForyTypeId {
        .timestamp
    }

    func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.writer.writeInt64(seconds)
        context.writer.writeInt32(nanos)
    }

    static func foryReadData(_ context: ReadContext) throws -> PeerTimestamp {
        PeerTimestamp(seconds: try context.reader.readInt64(), nanos: try context.reader.readInt32())
    }
}

@ForyObject
private struct Item {
    var name: String = ""
}

@ForyObject
private struct SimpleStruct {
    var f1: [Int32: Double] = [:]
    var f2: Int32 = 0
    var f3: Item = .foryDefault()
    var f4: String = ""
    var f5: PeerColor = .green
    var f6: [String] = []
    var f7: Int32 = 0
    var f8: Int32 = 0
    var last: Int32 = 0
}

@ForyObject
private struct Item1 {
    var f1: Int32 = 0
    var f2: Int32 = 0
    var f3: Int32 = 0
    var f4: Int32 = 0
    var f5: Int32 = 0
    var f6: Int32 = 0
}

@ForyObject
private struct StructWithList {
    var items: [String?] = []
}

@ForyObject
private struct StructWithMap {
    var data: [String?: String?] = [:]
}

@ForyObject
private struct VersionCheckStruct {
    var f1: Int32 = 0
    var f2: String? = nil
    var f3: Double = 0
}

@ForyObject
private struct EmptyStructEvolution {}

@ForyObject
private struct OneStringFieldStruct {
    var f1: String? = nil
}

@ForyObject
private struct TwoStringFieldStruct {
    var f1: String = ""
    var f2: String = ""
}

@ForyObject
private struct OneEnumFieldStruct {
    var f1: PeerTestEnum = .valueA
}

@ForyObject
private struct TwoEnumFieldStruct {
    var f1: PeerTestEnum = .valueA
    var f2: PeerTestEnum = .valueA
}

@ForyObject
private struct NullableComprehensiveSchemaConsistent {
    var byteField: Int8 = 0
    var shortField: Int16 = 0
    var intField: Int32 = 0
    var longField: Int64 = 0
    var floatField: Float = 0
    var doubleField: Double = 0
    var boolField: Bool = false

    var stringField: String = ""
    var listField: [String] = []
    var setField: Set<String> = []
    var mapField: [String: String] = [:]

    var nullableInt: Int32? = nil
    var nullableLong: Int64? = nil
    var nullableFloat: Float? = nil

    var nullableDouble: Double? = nil
    var nullableBool: Bool? = nil
    var nullableString: String? = nil
    var nullableList: [String]? = nil
    var nullableSet: Set<String>? = nil
    var nullableMap: [String: String]? = nil
}

@ForyObject
private struct NullableComprehensiveCompatibleSwift {
    var byteField: Int8 = 0
    var shortField: Int16 = 0
    var intField: Int32 = 0
    var longField: Int64 = 0
    var floatField: Float = 0
    var doubleField: Double = 0
    var boolField: Bool = false

    var boxedInt: Int32 = 0
    var boxedLong: Int64 = 0
    var boxedFloat: Float = 0
    var boxedDouble: Double = 0
    var boxedBool: Bool = false

    var stringField: String = ""
    var listField: [String] = []
    var setField: Set<String> = []
    var mapField: [String: String] = [:]

    var nullableInt1: Int32 = 0
    var nullableLong1: Int64 = 0
    var nullableFloat1: Float = 0
    var nullableDouble1: Double = 0
    var nullableBool1: Bool = false

    var nullableString2: String = ""
    var nullableList2: [String] = []
    var nullableSet2: Set<String> = []
    var nullableMap2: [String: String] = [:]
}

@ForyObject
private final class RefInnerSchemaConsistent {
    var id: Int32 = 0
    var name: String = ""

    required init() {}
}

@ForyObject
private final class RefOuterSchemaConsistent {
    var inner1: RefInnerSchemaConsistent? = nil
    var inner2: RefInnerSchemaConsistent? = nil

    required init() {}
}

@ForyObject
private final class RefInnerCompatible {
    var id: Int32 = 0
    var name: String = ""

    required init() {}
}

@ForyObject
private final class RefOuterCompatible {
    var inner1: RefInnerCompatible? = nil
    var inner2: RefInnerCompatible? = nil

    required init() {}
}

@ForyObject
private final class RefOverrideElement {
    var id: Int32 = 0
    var name: String = ""

    required init() {}
}

@ForyObject
private final class RefOverrideContainer {
    var listField: [RefOverrideElement] = []
    var mapField: [String: RefOverrideElement] = [:]

    required init() {}
}

@ForyObject
private final class CircularRefStruct {
    var name: String = ""
    weak var selfRef: CircularRefStruct? = nil

    required init() {}
}

@ForyObject
private struct MyStruct {
    var id: Int32 = 0
}

private struct MyExt: Serializer, Equatable {
    var id: Int32 = 0

    static func foryDefault() -> MyExt {
        MyExt()
    }

    static var staticTypeId: ForyTypeId {
        .ext
    }

    func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.writer.writeVarInt32(id)
    }

    static func foryReadData(_ context: ReadContext) throws -> MyExt {
        MyExt(id: try context.reader.readVarInt32())
    }
}

@ForyObject
private struct MyWrapper {
    var color: PeerColor = .green
    var myExt: MyExt = .foryDefault()
    var myStruct: MyStruct = .foryDefault()
}

@ForyObject
private struct EmptyWrapper {}

@ForyObject
private struct Dog {
    var age: Int32 = 0
    var name: String? = nil
}

@ForyObject
private struct Cat {
    var age: Int32 = 0
    var lives: Int32 = 0
}

@ForyObject
private struct AnimalListHolder {
    var animals: [Any] = []
}

@ForyObject
private struct AnimalMapHolder {
    var animalMap: [String: Any] = [:]
}

private enum StringOrLong: Serializer, Equatable {
    case str(String)
    case long(Int64)

    static func foryDefault() -> StringOrLong {
        .str("")
    }

    static var staticTypeId: ForyTypeId {
        .typedUnion
    }

    func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        switch self {
        case .str(let value):
            context.writer.writeVarUInt32(0)
            try value.foryWrite(context, refMode: .tracking, writeTypeInfo: true, hasGenerics: false)
        case .long(let value):
            context.writer.writeVarUInt32(1)
            try value.foryWrite(context, refMode: .tracking, writeTypeInfo: true, hasGenerics: false)
        }
    }

    static func foryReadData(_ context: ReadContext) throws -> StringOrLong {
        let tag = try context.reader.readVarUInt32()
        switch tag {
        case 0:
            let value: String = try String.foryRead(context, refMode: .tracking, readTypeInfo: true)
            return .str(value)
        case 1:
            let value: Int64 = try Int64.foryRead(context, refMode: .tracking, readTypeInfo: true)
            return .long(value)
        default:
            throw ForyError.invalidData("unknown union tag \(tag)")
        }
    }
}

@ForyObject
private struct StructWithUnion2 {
    var union: StringOrLong = .foryDefault()
}

@ForyObject
private struct UnsignedSchemaConsistentSimple {
    @ForyField(encoding: .tagged)
    var u64Tagged: UInt64 = 0
    @ForyField(encoding: .tagged)
    var u64TaggedNullable: UInt64? = nil
}

@ForyObject
private struct UnsignedSchemaConsistent {
    var u8Field: UInt8 = 0
    var u16Field: UInt16 = 0
    var u32VarField: UInt32 = 0
    @ForyField(encoding: .fixed)
    var u32FixedField: UInt32 = 0
    var u64VarField: UInt64 = 0
    @ForyField(encoding: .fixed)
    var u64FixedField: UInt64 = 0
    @ForyField(encoding: .tagged)
    var u64TaggedField: UInt64 = 0

    var u8NullableField: UInt8? = nil
    var u16NullableField: UInt16? = nil
    var u32VarNullableField: UInt32? = nil
    @ForyField(encoding: .fixed)
    var u32FixedNullableField: UInt32? = nil
    var u64VarNullableField: UInt64? = nil
    @ForyField(encoding: .fixed)
    var u64FixedNullableField: UInt64? = nil
    @ForyField(encoding: .tagged)
    var u64TaggedNullableField: UInt64? = nil
}

@ForyObject
private struct UnsignedSchemaCompatible {
    var u8Field1: UInt8? = nil
    var u16Field1: UInt16? = nil
    var u32VarField1: UInt32? = nil
    @ForyField(encoding: .fixed)
    var u32FixedField1: UInt32? = nil
    var u64VarField1: UInt64? = nil
    @ForyField(encoding: .fixed)
    var u64FixedField1: UInt64? = nil
    @ForyField(encoding: .tagged)
    var u64TaggedField1: UInt64? = nil

    var u8Field2: UInt8 = 0
    var u16Field2: UInt16 = 0
    var u32VarField2: UInt32 = 0
    @ForyField(encoding: .fixed)
    var u32FixedField2: UInt32 = 0
    var u64VarField2: UInt64 = 0
    @ForyField(encoding: .fixed)
    var u64FixedField2: UInt64 = 0
    @ForyField(encoding: .tagged)
    var u64TaggedField2: UInt64 = 0
}

private enum PeerError: Error, CustomStringConvertible {
    case missingDataFile
    case missingCaseName
    case invalidFieldValue(String)
    case unsupportedCase(String)

    var description: String {
        switch self {
        case .missingDataFile:
            return "DATA_FILE environment variable is required"
        case .missingCaseName:
            return "test case name is required"
        case .invalidFieldValue(let message):
            return "invalid field value: \(message)"
        case .unsupportedCase(let name):
            return "unsupported case \(name)"
        }
    }
}

private func caseName(from args: [String]) -> String? {
    if args.count >= 3, args[1] == "--case" {
        return args[2]
    }
    if args.count >= 2 {
        return args[1]
    }
    return nil
}

private func isDebugEnabled() -> Bool {
    ProcessInfo.processInfo.environment["ENABLE_FORY_DEBUG_OUTPUT"] == "1"
}

private func debugLog(_ message: String) {
    if isDebugEnabled() {
        fputs("[swift-xlang-peer] \(message)\n", stderr)
    }
}

private func verifyBufferCase(_ caseName: String, _ payload: [UInt8]) throws -> [UInt8] {
    let reader = ByteReader(bytes: payload)
    let writer = ByteWriter(capacity: payload.count)
    switch caseName {
    case "test_buffer":
        writer.writeUInt8(try reader.readUInt8())
        writer.writeInt8(try reader.readInt8())
        writer.writeInt16(try reader.readInt16())
        writer.writeInt32(try reader.readInt32())
        writer.writeInt64(try reader.readInt64())
        writer.writeFloat32(try reader.readFloat32())
        writer.writeFloat64(try reader.readFloat64())
        writer.writeVarUInt32(try reader.readVarUInt32())
        let bytesLen = Int(try reader.readInt32())
        writer.writeInt32(Int32(bytesLen))
        writer.writeBytes(try reader.readBytes(count: bytesLen))
    case "test_buffer_var":
        for _ in 0..<18 {
            writer.writeVarInt32(try reader.readVarInt32())
        }
        for _ in 0..<12 {
            writer.writeVarUInt32(try reader.readVarUInt32())
        }
        for _ in 0..<19 {
            writer.writeVarUInt64(try reader.readVarUInt64())
        }
        for _ in 0..<15 {
            writer.writeVarInt64(try reader.readVarInt64())
        }
    default:
        throw PeerError.unsupportedCase(caseName)
    }
    if reader.remaining != 0 {
        throw ForyError.invalidData("unexpected trailing bytes for case \(caseName)")
    }
    return [UInt8](writer.toData())
}

private func roundTripSingle<T: Serializer>(
    _ bytes: [UInt8],
    fory: Fory,
    as _: T.Type = T.self
) throws -> [UInt8] {
    let decoded: T = try fory.deserialize(Data(bytes))
    return [UInt8](try fory.serialize(decoded))
}

private func roundTripStream(
    _ bytes: [UInt8],
    _ action: (_ reader: ByteReader, _ out: inout Data) throws -> Void
) throws -> [UInt8] {
    let reader = ByteReader(bytes: bytes)
    var out = Data()
    try action(reader, &out)
    if reader.remaining != 0 {
        throw ForyError.invalidData("unexpected trailing bytes in stream: \(reader.remaining)")
    }
    return [UInt8](out)
}

private func handleMurmurHash(_ bytes: [UInt8]) throws -> [UInt8] {
    let reader = ByteReader(bytes: bytes)
    let writer = ByteWriter(capacity: bytes.count)
    switch bytes.count {
    case 16:
        for _ in 0..<2 {
            writer.writeInt64(try reader.readInt64())
        }
    case 32:
        for _ in 0..<4 {
            writer.writeInt64(try reader.readInt64())
        }
    default:
        throw ForyError.invalidData("unexpected murmurhash payload size \(bytes.count)")
    }
    if reader.remaining != 0 {
        throw ForyError.invalidData("unexpected trailing bytes for murmurhash")
    }
    return [UInt8](writer.toData())
}

private func handleStringSerializer(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    return try roundTripStream(bytes) { reader, out in
        for _ in 0..<7 {
            let value: String = try fory.deserializeFrom(reader)
            try fory.serializeTo(&out, value: value)
        }
    }
}

private func handleCrossLanguageSerializer(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(PeerColor.self, id: 101)

    return try roundTripStream(bytes) { reader, out in
        let b1: Bool = try fory.deserializeFrom(reader)
        let b2: Bool = try fory.deserializeFrom(reader)
        let i32a: Int32 = try fory.deserializeFrom(reader)
        let i8a: Int8 = try fory.deserializeFrom(reader)
        let i8b: Int8 = try fory.deserializeFrom(reader)
        let i16a: Int16 = try fory.deserializeFrom(reader)
        let i16b: Int16 = try fory.deserializeFrom(reader)
        let i32b: Int32 = try fory.deserializeFrom(reader)
        let i32c: Int32 = try fory.deserializeFrom(reader)
        let i64a: Int64 = try fory.deserializeFrom(reader)
        let i64b: Int64 = try fory.deserializeFrom(reader)
        let f32: Float = try fory.deserializeFrom(reader)
        let f64: Double = try fory.deserializeFrom(reader)
        let str: String = try fory.deserializeFrom(reader)
        let day: PeerDate = try fory.deserializeFrom(reader)
        let ts: PeerTimestamp = try fory.deserializeFrom(reader)
        let boolArray: [Bool] = try fory.deserializeFrom(reader)
        let byteArray: [UInt8] = try fory.deserializeFrom(reader)
        let shortArray: [Int16] = try fory.deserializeFrom(reader)
        let intArray: [Int32] = try fory.deserializeFrom(reader)
        let longArray: [Int64] = try fory.deserializeFrom(reader)
        let floatArray: [Float] = try fory.deserializeFrom(reader)
        let doubleArray: [Double] = try fory.deserializeFrom(reader)
        let list: [String] = try fory.deserializeFrom(reader)
        let set: Set<String> = try fory.deserializeFrom(reader)
        let map: [String: String] = try fory.deserializeFrom(reader)
        let color: PeerColor = try fory.deserializeFrom(reader)

        try fory.serializeTo(&out, value: b1)
        try fory.serializeTo(&out, value: b2)
        try fory.serializeTo(&out, value: i32a)
        try fory.serializeTo(&out, value: i8a)
        try fory.serializeTo(&out, value: i8b)
        try fory.serializeTo(&out, value: i16a)
        try fory.serializeTo(&out, value: i16b)
        try fory.serializeTo(&out, value: i32b)
        try fory.serializeTo(&out, value: i32c)
        try fory.serializeTo(&out, value: i64a)
        try fory.serializeTo(&out, value: i64b)
        try fory.serializeTo(&out, value: f32)
        try fory.serializeTo(&out, value: f64)
        try fory.serializeTo(&out, value: str)
        try fory.serializeTo(&out, value: day)
        try fory.serializeTo(&out, value: ts)
        try fory.serializeTo(&out, value: boolArray)
        try fory.serializeTo(&out, value: byteArray)
        try fory.serializeTo(&out, value: shortArray)
        try fory.serializeTo(&out, value: intArray)
        try fory.serializeTo(&out, value: longArray)
        try fory.serializeTo(&out, value: floatArray)
        try fory.serializeTo(&out, value: doubleArray)
        try fory.serializeTo(&out, value: list)
        try fory.serializeTo(&out, value: set)
        try fory.serializeTo(&out, value: map)
        try fory.serializeTo(&out, value: color)
    }
}

private func handleSimpleStruct(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(PeerColor.self, id: 101)
    fory.register(Item.self, id: 102)
    fory.register(SimpleStruct.self, id: 103)
    return try roundTripSingle(bytes, fory: fory, as: SimpleStruct.self)
}

private func handleNamedSimpleStruct(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    try fory.register(PeerColor.self, namespace: "demo", name: "color")
    try fory.register(Item.self, namespace: "demo", name: "item")
    try fory.register(SimpleStruct.self, namespace: "demo", name: "simple_struct")
    return try roundTripSingle(bytes, fory: fory, as: SimpleStruct.self)
}

private func handleList(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(Item.self, id: 102)
    return try roundTripStream(bytes) { reader, out in
        let v1: [String?] = try fory.deserializeFrom(reader)
        let v2: [String?] = try fory.deserializeFrom(reader)
        let v3: [Item?] = try fory.deserializeFrom(reader)
        let v4: [Item?] = try fory.deserializeFrom(reader)
        try fory.serializeTo(&out, value: v1)
        try fory.serializeTo(&out, value: v2)
        try fory.serializeTo(&out, value: v3)
        try fory.serializeTo(&out, value: v4)
    }
}

private func handleMap(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(Item.self, id: 102)
    return try roundTripStream(bytes) { reader, out in
        let v1: [String?: String?] = try fory.deserializeFrom(reader)
        let v2: [String?: Item?] = try fory.deserializeFrom(reader)
        try fory.serializeTo(&out, value: v1)
        try fory.serializeTo(&out, value: v2)
    }
}

private func handleInteger(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(Item1.self, id: 101)
    return try roundTripStream(bytes) { reader, out in
        let item: Item1 = try fory.deserializeFrom(reader)
        let f1: Int32 = try fory.deserializeFrom(reader)
        let f2: Int32 = try fory.deserializeFrom(reader)
        let f3: Int32 = try fory.deserializeFrom(reader)
        let f4: Int32 = try fory.deserializeFrom(reader)
        let f5: Int32 = try fory.deserializeFrom(reader)
        let f6: Int32 = try fory.deserializeFrom(reader)
        try fory.serializeTo(&out, value: item)
        try fory.serializeTo(&out, value: f1)
        try fory.serializeTo(&out, value: f2)
        try fory.serializeTo(&out, value: f3)
        try fory.serializeTo(&out, value: f4)
        try fory.serializeTo(&out, value: f5)
        try fory.serializeTo(&out, value: f6)
    }
}

private func handleItem(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(Item.self, id: 102)
    return try roundTripStream(bytes) { reader, out in
        let i1: Item = try fory.deserializeFrom(reader)
        let i2: Item = try fory.deserializeFrom(reader)
        let i3: Item = try fory.deserializeFrom(reader)
        try fory.serializeTo(&out, value: i1)
        try fory.serializeTo(&out, value: i2)
        try fory.serializeTo(&out, value: i3)
    }
}

private func handleColor(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(PeerColor.self, id: 101)
    return try roundTripStream(bytes) { reader, out in
        for _ in 0..<4 {
            let color: PeerColor = try fory.deserializeFrom(reader)
            try fory.serializeTo(&out, value: color)
        }
    }
}

private func handleStructWithList(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(StructWithList.self, id: 201)
    return try roundTripStream(bytes) { reader, out in
        let v1: StructWithList = try fory.deserializeFrom(reader)
        let v2: StructWithList = try fory.deserializeFrom(reader)
        try fory.serializeTo(&out, value: v1)
        try fory.serializeTo(&out, value: v2)
    }
}

private func handleStructWithMap(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(StructWithMap.self, id: 202)
    return try roundTripStream(bytes) { reader, out in
        let v1: StructWithMap = try fory.deserializeFrom(reader)
        let v2: StructWithMap = try fory.deserializeFrom(reader)
        try fory.serializeTo(&out, value: v1)
        try fory.serializeTo(&out, value: v2)
    }
}

private func handleSkipIDCustom(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(PeerColor.self, id: 101)
    fory.register(MyStruct.self, id: 102)
    fory.register(MyExt.self, id: 103)
    fory.register(MyWrapper.self, id: 104)
    return try roundTripSingle(bytes, fory: fory, as: MyWrapper.self)
}

private func handleSkipNameCustom(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    try fory.register(PeerColor.self, name: "color")
    try fory.register(MyStruct.self, name: "my_struct")
    try fory.register(MyExt.self, name: "my_ext")
    try fory.register(MyWrapper.self, name: "my_wrapper")
    return try roundTripSingle(bytes, fory: fory, as: MyWrapper.self)
}

private func handleConsistentNamed(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: false))
    try fory.register(PeerColor.self, name: "color")
    try fory.register(MyStruct.self, name: "my_struct")
    try fory.register(MyExt.self, name: "my_ext")
    return try roundTripStream(bytes) { reader, out in
        for _ in 0..<3 {
            let color: PeerColor = try fory.deserializeFrom(reader)
            try fory.serializeTo(&out, value: color)
        }
        for _ in 0..<3 {
            let myStruct: MyStruct = try fory.deserializeFrom(reader)
            try fory.serializeTo(&out, value: myStruct)
        }
        for _ in 0..<3 {
            let myExt: MyExt = try fory.deserializeFrom(reader)
            try fory.serializeTo(&out, value: myExt)
        }
    }
}

private func handleStructVersionCheck(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: false))
    fory.register(VersionCheckStruct.self, id: 201)
    return try roundTripSingle(bytes, fory: fory, as: VersionCheckStruct.self)
}

private func registerPolymorphicTypes(_ fory: Fory) {
    fory.register(Dog.self, id: 302)
    fory.register(Cat.self, id: 303)
    fory.register(AnimalListHolder.self, id: 304)
    fory.register(AnimalMapHolder.self, id: 305)
}

private func handlePolymorphicList(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    registerPolymorphicTypes(fory)
    return try roundTripStream(bytes) { reader, out in
        let animals: [Any] = try fory.deserializeFrom(reader)
        let holder: AnimalListHolder = try fory.deserializeFrom(reader)
        try fory.serializeTo(&out, value: animals)
        try fory.serializeTo(&out, value: holder)
    }
}

private func handlePolymorphicMap(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    registerPolymorphicTypes(fory)
    return try roundTripStream(bytes) { reader, out in
        let animalMap: [String: Any] = try fory.deserializeFrom(reader)
        let holder: AnimalMapHolder = try fory.deserializeFrom(reader)
        try fory.serializeTo(&out, value: animalMap)
        try fory.serializeTo(&out, value: holder)
    }
}

private func handleUnionXlang(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(StructWithUnion2.self, id: 301)
    return try roundTripStream(bytes) { reader, out in
        let v1: StructWithUnion2 = try fory.deserializeFrom(reader)
        let v2: StructWithUnion2 = try fory.deserializeFrom(reader)
        try fory.serializeTo(&out, value: v1)
        try fory.serializeTo(&out, value: v2)
    }
}

private func handleOneStringFieldSchema(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: false))
    fory.register(OneStringFieldStruct.self, id: 200)
    return try roundTripSingle(bytes, fory: fory, as: OneStringFieldStruct.self)
}

private func handleOneStringFieldCompatible(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(OneStringFieldStruct.self, id: 200)
    return try roundTripSingle(bytes, fory: fory, as: OneStringFieldStruct.self)
}

private func handleTwoStringFieldCompatible(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(TwoStringFieldStruct.self, id: 201)
    return try roundTripSingle(bytes, fory: fory, as: TwoStringFieldStruct.self)
}

private func handleSchemaEvolutionCompatible(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(EmptyStructEvolution.self, id: 200)
    return try roundTripSingle(bytes, fory: fory, as: EmptyStructEvolution.self)
}

private func handleSchemaEvolutionCompatibleReverse(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(TwoStringFieldStruct.self, id: 200)
    return try roundTripSingle(bytes, fory: fory, as: TwoStringFieldStruct.self)
}

private func handleOneEnumFieldSchema(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: false))
    fory.register(PeerTestEnum.self, id: 210)
    fory.register(OneEnumFieldStruct.self, id: 211)
    return try roundTripSingle(bytes, fory: fory, as: OneEnumFieldStruct.self)
}

private func handleOneEnumFieldCompatible(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(PeerTestEnum.self, id: 210)
    fory.register(OneEnumFieldStruct.self, id: 211)
    return try roundTripSingle(bytes, fory: fory, as: OneEnumFieldStruct.self)
}

private func handleTwoEnumFieldCompatible(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(PeerTestEnum.self, id: 210)
    fory.register(TwoEnumFieldStruct.self, id: 212)
    return try roundTripSingle(bytes, fory: fory, as: TwoEnumFieldStruct.self)
}

private func handleEnumSchemaEvolutionCompatible(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(PeerTestEnum.self, id: 210)
    fory.register(EmptyStructEvolution.self, id: 211)
    return try roundTripSingle(bytes, fory: fory, as: EmptyStructEvolution.self)
}

private func handleEnumSchemaEvolutionCompatibleReverse(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(PeerTestEnum.self, id: 210)
    fory.register(TwoEnumFieldStruct.self, id: 211)
    return try roundTripSingle(bytes, fory: fory, as: TwoEnumFieldStruct.self)
}

private func handleNullableFieldSchemaConsistent(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: false))
    fory.register(NullableComprehensiveSchemaConsistent.self, id: 401)
    return try roundTripSingle(bytes, fory: fory, as: NullableComprehensiveSchemaConsistent.self)
}

private func handleNullableFieldCompatible(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(NullableComprehensiveCompatibleSwift.self, id: 402)
    return try roundTripSingle(bytes, fory: fory, as: NullableComprehensiveCompatibleSwift.self)
}

private func handleRefSchemaConsistent(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: true, compatible: false))
    fory.register(RefInnerSchemaConsistent.self, id: 501)
    fory.register(RefOuterSchemaConsistent.self, id: 502)
    return try roundTripSingle(bytes, fory: fory, as: RefOuterSchemaConsistent.self)
}

private func handleRefCompatible(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: true, compatible: true))
    fory.register(RefInnerCompatible.self, id: 503)
    fory.register(RefOuterCompatible.self, id: 504)
    return try roundTripSingle(bytes, fory: fory, as: RefOuterCompatible.self)
}

private func handleCollectionElementRefOverride(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: true, compatible: false))
    fory.register(RefOverrideElement.self, id: 701)
    fory.register(RefOverrideContainer.self, id: 702)
    return try roundTripSingle(bytes, fory: fory, as: RefOverrideContainer.self)
}

private func handleCircularRefSchemaConsistent(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: true, compatible: false))
    fory.register(CircularRefStruct.self, id: 601)
    return try roundTripSingle(bytes, fory: fory, as: CircularRefStruct.self)
}

private func handleCircularRefCompatible(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: true, compatible: true))
    fory.register(CircularRefStruct.self, id: 602)
    return try roundTripSingle(bytes, fory: fory, as: CircularRefStruct.self)
}

private func handleUnsignedSchemaConsistentSimple(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: false))
    fory.register(UnsignedSchemaConsistentSimple.self, id: 1)
    return try roundTripSingle(bytes, fory: fory, as: UnsignedSchemaConsistentSimple.self)
}

private func handleUnsignedSchemaConsistent(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: false))
    fory.register(UnsignedSchemaConsistent.self, id: 501)
    return try roundTripSingle(bytes, fory: fory, as: UnsignedSchemaConsistent.self)
}

private func handleUnsignedSchemaCompatible(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(UnsignedSchemaCompatible.self, id: 502)
    return try roundTripSingle(bytes, fory: fory, as: UnsignedSchemaCompatible.self)
}

private func rewritePayload(caseName: String, bytes: [UInt8]) throws -> [UInt8] {
    switch caseName {
    case "test_buffer", "test_buffer_var":
        return try verifyBufferCase(caseName, bytes)
    case "test_murmurhash3":
        return try handleMurmurHash(bytes)
    case "test_string_serializer":
        return try handleStringSerializer(bytes)
    case "test_cross_language_serializer":
        return try handleCrossLanguageSerializer(bytes)
    case "test_simple_struct":
        return try handleSimpleStruct(bytes)
    case "test_named_simple_struct":
        return try handleNamedSimpleStruct(bytes)
    case "test_list":
        return try handleList(bytes)
    case "test_map":
        return try handleMap(bytes)
    case "test_integer":
        return try handleInteger(bytes)
    case "test_item":
        return try handleItem(bytes)
    case "test_color":
        return try handleColor(bytes)
    case "test_struct_with_list":
        return try handleStructWithList(bytes)
    case "test_struct_with_map":
        return try handleStructWithMap(bytes)
    case "test_skip_id_custom":
        return try handleSkipIDCustom(bytes)
    case "test_skip_name_custom":
        return try handleSkipNameCustom(bytes)
    case "test_consistent_named":
        return try handleConsistentNamed(bytes)
    case "test_struct_version_check":
        return try handleStructVersionCheck(bytes)
    case "test_polymorphic_list":
        return try handlePolymorphicList(bytes)
    case "test_polymorphic_map":
        return try handlePolymorphicMap(bytes)
    case "test_one_string_field_schema":
        return try handleOneStringFieldSchema(bytes)
    case "test_one_string_field_compatible":
        return try handleOneStringFieldCompatible(bytes)
    case "test_two_string_field_compatible":
        return try handleTwoStringFieldCompatible(bytes)
    case "test_schema_evolution_compatible":
        return try handleSchemaEvolutionCompatible(bytes)
    case "test_schema_evolution_compatible_reverse":
        return try handleSchemaEvolutionCompatibleReverse(bytes)
    case "test_one_enum_field_schema":
        return try handleOneEnumFieldSchema(bytes)
    case "test_one_enum_field_compatible":
        return try handleOneEnumFieldCompatible(bytes)
    case "test_two_enum_field_compatible":
        return try handleTwoEnumFieldCompatible(bytes)
    case "test_enum_schema_evolution_compatible":
        return try handleEnumSchemaEvolutionCompatible(bytes)
    case "test_enum_schema_evolution_compatible_reverse":
        return try handleEnumSchemaEvolutionCompatibleReverse(bytes)
    case "test_nullable_field_schema_consistent_not_null", "test_nullable_field_schema_consistent_null":
        return try handleNullableFieldSchemaConsistent(bytes)
    case "test_nullable_field_compatible_not_null", "test_nullable_field_compatible_null":
        return try handleNullableFieldCompatible(bytes)
    case "test_union_xlang":
        return try handleUnionXlang(bytes)
    case "test_ref_schema_consistent":
        return try handleRefSchemaConsistent(bytes)
    case "test_ref_compatible":
        return try handleRefCompatible(bytes)
    case "test_collection_element_ref_override":
        return try handleCollectionElementRefOverride(bytes)
    case "test_circular_ref_schema_consistent":
        return try handleCircularRefSchemaConsistent(bytes)
    case "test_circular_ref_compatible":
        return try handleCircularRefCompatible(bytes)
    case "test_unsigned_schema_consistent_simple":
        return try handleUnsignedSchemaConsistentSimple(bytes)
    case "test_unsigned_schema_consistent":
        return try handleUnsignedSchemaConsistent(bytes)
    case "test_unsigned_schema_compatible":
        return try handleUnsignedSchemaCompatible(bytes)
    default:
        throw PeerError.unsupportedCase(caseName)
    }
}

private func run() throws {
    let args = CommandLine.arguments
    guard let caseName = caseName(from: args) else {
        throw PeerError.missingCaseName
    }
    guard let dataFile = ProcessInfo.processInfo.environment["DATA_FILE"] else {
        throw PeerError.missingDataFile
    }

    let dataURL = URL(fileURLWithPath: dataFile)
    let bytes = [UInt8](try Data(contentsOf: dataURL))
    debugLog("Running case \(caseName), payload bytes: \(bytes.count)")

    let rewritten = try rewritePayload(caseName: caseName, bytes: bytes)
    try Data(rewritten).write(to: dataURL, options: .atomic)
}

do {
    try run()
} catch {
    fputs("Swift xlang peer failed: \(error)\n", stderr)
    exit(1)
}
