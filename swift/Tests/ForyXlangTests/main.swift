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
import Fory

// MARK: - Shared test types

@ForyObject
private enum PeerColor {
    case green
    case red
    case blue
    case white
}

@ForyObject
private enum PeerTestEnum {
    case valueA
    case valueB
    case valueC
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

    static var staticTypeId: TypeId {
        .ext
    }

    func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.buffer.writeVarInt32(id)
    }

    static func foryReadData(_ context: ReadContext) throws -> MyExt {
        MyExt(id: try context.buffer.readVarInt32())
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

@ForyObject
private enum StringOrLong {
    case text(String)
    case number(Int64)
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
    let inputBuffer = ByteBuffer(bytes: payload)
    let outputBuffer = ByteBuffer(capacity: payload.count)
    switch caseName {
    case "test_buffer":
        outputBuffer.writeUInt8(try inputBuffer.readUInt8())
        outputBuffer.writeInt8(try inputBuffer.readInt8())
        outputBuffer.writeInt16(try inputBuffer.readInt16())
        outputBuffer.writeInt32(try inputBuffer.readInt32())
        outputBuffer.writeInt64(try inputBuffer.readInt64())
        outputBuffer.writeFloat32(try inputBuffer.readFloat32())
        outputBuffer.writeFloat64(try inputBuffer.readFloat64())
        outputBuffer.writeVarUInt32(try inputBuffer.readVarUInt32())
        let bytesLen = Int(try inputBuffer.readInt32())
        outputBuffer.writeInt32(Int32(bytesLen))
        outputBuffer.writeBytes(try inputBuffer.readBytes(count: bytesLen))
    case "test_buffer_var":
        for _ in 0..<18 {
            outputBuffer.writeVarInt32(try inputBuffer.readVarInt32())
        }
        for _ in 0..<12 {
            outputBuffer.writeVarUInt32(try inputBuffer.readVarUInt32())
        }
        for _ in 0..<19 {
            outputBuffer.writeVarUInt64(try inputBuffer.readVarUInt64())
        }
        for _ in 0..<15 {
            outputBuffer.writeVarInt64(try inputBuffer.readVarInt64())
        }
    default:
        throw PeerError.unsupportedCase(caseName)
    }
    if inputBuffer.remaining != 0 {
        throw ForyError.invalidData("unexpected trailing bytes for case \(caseName)")
    }
    return [UInt8](outputBuffer.toData())
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
    _ action: (_ buffer: ByteBuffer, _ out: inout Data) throws -> Void
) throws -> [UInt8] {
    let buffer = ByteBuffer(bytes: bytes)
    var out = Data()
    try action(buffer, &out)
    if buffer.remaining != 0 {
        throw ForyError.invalidData("unexpected trailing bytes in stream: \(buffer.remaining)")
    }
    return [UInt8](out)
}

private func handleMurmurHash(_ bytes: [UInt8]) throws -> [UInt8] {
    let inputBuffer = ByteBuffer(bytes: bytes)
    let outputBuffer = ByteBuffer(capacity: bytes.count)
    switch bytes.count {
    case 16:
        for _ in 0..<2 {
            outputBuffer.writeInt64(try inputBuffer.readInt64())
        }
    case 32:
        for _ in 0..<4 {
            outputBuffer.writeInt64(try inputBuffer.readInt64())
        }
    default:
        throw ForyError.invalidData("unexpected murmurhash payload size \(bytes.count)")
    }
    if inputBuffer.remaining != 0 {
        throw ForyError.invalidData("unexpected trailing bytes for murmurhash")
    }
    return [UInt8](outputBuffer.toData())
}

private func handleStringSerializer(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    return try roundTripStream(bytes) { buffer, out in
        for _ in 0..<7 {
            let value: String = try fory.deserialize(from: buffer)
            try fory.serialize(value, to: &out)
        }
    }
}

private func handleCrossLanguageSerializer(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(PeerColor.self, id: 101)

    return try roundTripStream(bytes) { buffer, out in
        let b1: Bool = try fory.deserialize(from: buffer)
        let b2: Bool = try fory.deserialize(from: buffer)
        let i32a: Int32 = try fory.deserialize(from: buffer)
        let i8a: Int8 = try fory.deserialize(from: buffer)
        let i8b: Int8 = try fory.deserialize(from: buffer)
        let i16a: Int16 = try fory.deserialize(from: buffer)
        let i16b: Int16 = try fory.deserialize(from: buffer)
        let i32b: Int32 = try fory.deserialize(from: buffer)
        let i32c: Int32 = try fory.deserialize(from: buffer)
        let i64a: Int64 = try fory.deserialize(from: buffer)
        let i64b: Int64 = try fory.deserialize(from: buffer)
        let f32: Float = try fory.deserialize(from: buffer)
        let f64: Double = try fory.deserialize(from: buffer)
        let str: String = try fory.deserialize(from: buffer)
        let day: ForyDate = try fory.deserialize(from: buffer)
        let ts: ForyTimestamp = try fory.deserialize(from: buffer)
        let boolArray: [Bool] = try fory.deserialize(from: buffer)
        let byteArray: [UInt8] = try fory.deserialize(from: buffer)
        let shortArray: [Int16] = try fory.deserialize(from: buffer)
        let intArray: [Int32] = try fory.deserialize(from: buffer)
        let longArray: [Int64] = try fory.deserialize(from: buffer)
        let floatArray: [Float] = try fory.deserialize(from: buffer)
        let doubleArray: [Double] = try fory.deserialize(from: buffer)
        let list: [String] = try fory.deserialize(from: buffer)
        let set: Set<String> = try fory.deserialize(from: buffer)
        let map: [String: String] = try fory.deserialize(from: buffer)
        let color: PeerColor = try fory.deserialize(from: buffer)

        try fory.serialize(b1, to: &out)
        try fory.serialize(b2, to: &out)
        try fory.serialize(i32a, to: &out)
        try fory.serialize(i8a, to: &out)
        try fory.serialize(i8b, to: &out)
        try fory.serialize(i16a, to: &out)
        try fory.serialize(i16b, to: &out)
        try fory.serialize(i32b, to: &out)
        try fory.serialize(i32c, to: &out)
        try fory.serialize(i64a, to: &out)
        try fory.serialize(i64b, to: &out)
        try fory.serialize(f32, to: &out)
        try fory.serialize(f64, to: &out)
        try fory.serialize(str, to: &out)
        try fory.serialize(day, to: &out)
        try fory.serialize(ts, to: &out)
        try fory.serialize(boolArray, to: &out)
        try fory.serialize(byteArray, to: &out)
        try fory.serialize(shortArray, to: &out)
        try fory.serialize(intArray, to: &out)
        try fory.serialize(longArray, to: &out)
        try fory.serialize(floatArray, to: &out)
        try fory.serialize(doubleArray, to: &out)
        try fory.serialize(list, to: &out)
        try fory.serialize(set, to: &out)
        try fory.serialize(map, to: &out)
        try fory.serialize(color, to: &out)
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
    return try roundTripStream(bytes) { buffer, out in
        let v1: [String?] = try fory.deserialize(from: buffer)
        let v2: [String?] = try fory.deserialize(from: buffer)
        let v3: [Item?] = try fory.deserialize(from: buffer)
        let v4: [Item?] = try fory.deserialize(from: buffer)
        try fory.serialize(v1, to: &out)
        try fory.serialize(v2, to: &out)
        try fory.serialize(v3, to: &out)
        try fory.serialize(v4, to: &out)
    }
}

private func handleMap(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(Item.self, id: 102)
    return try roundTripStream(bytes) { buffer, out in
        let v1: [String?: String?] = try fory.deserialize(from: buffer)
        let v2: [String?: Item?] = try fory.deserialize(from: buffer)
        try fory.serialize(v1, to: &out)
        try fory.serialize(v2, to: &out)
    }
}

private func handleInteger(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(Item1.self, id: 101)
    return try roundTripStream(bytes) { buffer, out in
        let item: Item1 = try fory.deserialize(from: buffer)
        let f1: Int32 = try fory.deserialize(from: buffer)
        let f2: Int32 = try fory.deserialize(from: buffer)
        let f3: Int32 = try fory.deserialize(from: buffer)
        let f4: Int32 = try fory.deserialize(from: buffer)
        let f5: Int32 = try fory.deserialize(from: buffer)
        let f6: Int32 = try fory.deserialize(from: buffer)
        try fory.serialize(item, to: &out)
        try fory.serialize(f1, to: &out)
        try fory.serialize(f2, to: &out)
        try fory.serialize(f3, to: &out)
        try fory.serialize(f4, to: &out)
        try fory.serialize(f5, to: &out)
        try fory.serialize(f6, to: &out)
    }
}

private func handleItem(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(Item.self, id: 102)
    return try roundTripStream(bytes) { buffer, out in
        let i1: Item = try fory.deserialize(from: buffer)
        let i2: Item = try fory.deserialize(from: buffer)
        let i3: Item = try fory.deserialize(from: buffer)
        try fory.serialize(i1, to: &out)
        try fory.serialize(i2, to: &out)
        try fory.serialize(i3, to: &out)
    }
}

private func handleColor(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(PeerColor.self, id: 101)
    return try roundTripStream(bytes) { buffer, out in
        for _ in 0..<4 {
            let color: PeerColor = try fory.deserialize(from: buffer)
            try fory.serialize(color, to: &out)
        }
    }
}

private func handleStructWithList(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(StructWithList.self, id: 201)
    return try roundTripStream(bytes) { buffer, out in
        let v1: StructWithList = try fory.deserialize(from: buffer)
        let v2: StructWithList = try fory.deserialize(from: buffer)
        try fory.serialize(v1, to: &out)
        try fory.serialize(v2, to: &out)
    }
}

private func handleStructWithMap(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(StructWithMap.self, id: 202)
    return try roundTripStream(bytes) { buffer, out in
        let v1: StructWithMap = try fory.deserialize(from: buffer)
        let v2: StructWithMap = try fory.deserialize(from: buffer)
        try fory.serialize(v1, to: &out)
        try fory.serialize(v2, to: &out)
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
    return try roundTripStream(bytes) { buffer, out in
        for _ in 0..<3 {
            let color: PeerColor = try fory.deserialize(from: buffer)
            try fory.serialize(color, to: &out)
        }
        for _ in 0..<3 {
            let myStruct: MyStruct = try fory.deserialize(from: buffer)
            try fory.serialize(myStruct, to: &out)
        }
        for _ in 0..<3 {
            let myExt: MyExt = try fory.deserialize(from: buffer)
            try fory.serialize(myExt, to: &out)
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
    return try roundTripStream(bytes) { buffer, out in
        let animals: [Any] = try fory.deserialize(from: buffer)
        let holder: AnimalListHolder = try fory.deserialize(from: buffer)
        try fory.serialize(animals, to: &out)
        try fory.serialize(holder, to: &out)
    }
}

private func handlePolymorphicMap(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    registerPolymorphicTypes(fory)
    return try roundTripStream(bytes) { buffer, out in
        let animalMap: [String: Any] = try fory.deserialize(from: buffer)
        let holder: AnimalMapHolder = try fory.deserialize(from: buffer)
        try fory.serialize(animalMap, to: &out)
        try fory.serialize(holder, to: &out)
    }
}

private func handleUnionXlang(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(StructWithUnion2.self, id: 301)
    return try roundTripStream(bytes) { buffer, out in
        let v1: StructWithUnion2 = try fory.deserialize(from: buffer)
        let v2: StructWithUnion2 = try fory.deserialize(from: buffer)
        try fory.serialize(v1, to: &out)
        try fory.serialize(v2, to: &out)
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
