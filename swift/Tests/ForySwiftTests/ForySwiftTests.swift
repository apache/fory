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
import Testing
@testable import ForySwift

@ForyObject
struct Address: Equatable {
    var street: String
    var zip: Int32
}

@ForyObject
struct Person: Equatable {
    var id: Int64
    var name: String
    var nickname: String?
    var scores: [Int32]
    var tags: Set<String>
    var addresses: [Address]
    var metadata: [Int8: Int32?]
}

@ForyObject
struct FieldOrder: Equatable {
    var z: String
    var a: Int64
    var b: Int16
    var c: Int32
}

@ForyObject
struct EncodedNumberFields: Equatable {
    @ForyField(encoding: .fixed)
    var u32Fixed: UInt32

    @ForyField(encoding: .tagged)
    var u64Tagged: UInt64
}

@ForyObject
final class Node {
    var value: Int32 = 0
    var next: Node?

    required init() {}

    init(value: Int32, next: Node? = nil) {
        self.value = value
        self.next = next
    }
}

@Test
func primitiveRoundTrip() throws {
    let fory = Fory()

    let boolData = try fory.serialize(true)
    let boolValue: Bool = try fory.deserialize(boolData)
    #expect(boolValue == true)

    let int32Data = try fory.serialize(Int32(-123456))
    let int32Value: Int32 = try fory.deserialize(int32Data)
    #expect(int32Value == -123456)

    let int64Data = try fory.serialize(Int64(9_223_372_036_854_775_000))
    let int64Value: Int64 = try fory.deserialize(int64Data)
    #expect(int64Value == 9_223_372_036_854_775_000)

    let uint32Data = try fory.serialize(UInt32(123456))
    let uint32Value: UInt32 = try fory.deserialize(uint32Data)
    #expect(uint32Value == 123456)

    let uint64Data = try fory.serialize(UInt64(9_223_372_036_854_775_000))
    let uint64Value: UInt64 = try fory.deserialize(uint64Data)
    #expect(uint64Value == 9_223_372_036_854_775_000)

    let floatData = try fory.serialize(Float(3.25))
    let floatValue: Float = try fory.deserialize(floatData)
    #expect(floatValue == 3.25)

    let doubleData = try fory.serialize(Double(3.1415926))
    let doubleValue: Double = try fory.deserialize(doubleData)
    #expect(doubleValue == 3.1415926)

    let stringData = try fory.serialize("hello_fory")
    let stringValue: String = try fory.deserialize(stringData)
    #expect(stringValue == "hello_fory")

    let binary = Data([0x01, 0x02, 0x03, 0xFF])
    let binaryData = try fory.serialize(binary)
    let binaryValue: Data = try fory.deserialize(binaryData)
    #expect(binaryValue == binary)
}

@Test
func optionalRoundTrip() throws {
    let fory = Fory()

    let some: String? = "present"
    let someData = try fory.serialize(some)
    let someValue: String? = try fory.deserialize(someData)
    #expect(someValue == "present")

    let none: String? = nil
    let noneData = try fory.serialize(none)
    let noneValue: String? = try fory.deserialize(noneData)
    #expect(noneValue == nil)
}

@Test
func collectionsRoundTrip() throws {
    let fory = Fory()

    let list: [String?] = ["a", nil, "b"]
    let listData = try fory.serialize(list)
    let listValue: [String?] = try fory.deserialize(listData)
    #expect(listValue == list)

    let intArray: [Int32] = [1, 2, 3, 4]
    let intArrayData = try fory.serialize(intArray)
    let intArrayValue: [Int32] = try fory.deserialize(intArrayData)
    #expect(intArrayValue == intArray)

    let uint8Array: [UInt8] = [1, 2, 3, 250]
    let uint8ArrayData = try fory.serialize(uint8Array)
    let uint8ArrayValue: [UInt8] = try fory.deserialize(uint8ArrayData)
    #expect(uint8ArrayValue == uint8Array)

    let set: Set<Int16> = [1, 5, 8]
    let setData = try fory.serialize(set)
    let setValue: Set<Int16> = try fory.deserialize(setData)
    #expect(setValue == set)

    let map: [Int8: Int32?] = [1: 100, 2: nil, 3: -7]
    let mapData = try fory.serialize(map)
    let mapValue: [Int8: Int32?] = try fory.deserialize(mapData)
    #expect(mapValue == map)

    let nullableKeyMap: [Int8?: Int32?] = [1: 10, nil: nil]
    let nullableMapData = try fory.serialize(nullableKeyMap)
    let nullableMapValue: [Int8?: Int32?] = try fory.deserialize(nullableMapData)
    #expect(nullableMapValue == nullableKeyMap)
}

@Test
func primitiveArrayTypeIDs() throws {
    let fory = Fory()

    let int32Data = try fory.serialize([Int32(7), 9])
    let int32Bytes = [UInt8](int32Data)
    #expect(int32Bytes[0] == ForyHeaderFlag.isXlang)
    #expect(Int8(bitPattern: int32Bytes[1]) == RefFlag.notNullValue.rawValue)
    #expect(UInt32(int32Bytes[2]) == ForyTypeId.int32Array.rawValue)

    let uint8Data = try fory.serialize([UInt8(1), 2, 3])
    let uint8Bytes = [UInt8](uint8Data)
    #expect(UInt32(uint8Bytes[2]) == ForyTypeId.binary.rawValue)
}

@Test
func macroStructRoundTrip() throws {
    let fory = Fory()
    fory.register(Address.self, id: 100)
    fory.register(Person.self, id: 101)

    let person = Person(
        id: 42,
        name: "Alice",
        nickname: nil,
        scores: [10, 20, 30],
        tags: ["swift", "xlang"],
        addresses: [Address(street: "Main", zip: 94107)],
        metadata: [1: 100, 2: nil]
    )

    let data = try fory.serialize(person)
    let decoded: Person = try fory.deserialize(data)
    #expect(decoded == person)
}

@Test
func macroClassReferenceTracking() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: true))
    fory.register(Node.self, id: 200)

    let node = Node(value: 7)
    node.next = node

    let data = try fory.serialize(node)
    let decoded: Node = try fory.deserialize(data)

    #expect(decoded.value == 7)
    #expect(decoded.next === decoded)
}

@Test
func collectionAndMapReferenceTracking() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: true))
    fory.register(Node.self, id: 200)

    let shared = Node(value: 11)
    let list: [Node?] = [shared, shared, nil]
    let listData = try fory.serialize(list)
    let listReader = ByteReader(data: listData)
    _ = try fory.readHead(reader: listReader)
    _ = try listReader.readInt8()
    _ = try listReader.readVarUInt32()
    _ = try listReader.readVarUInt32()
    let listHeader = try listReader.readUInt8()
    #expect((listHeader & 0b0000_0001) != 0)

    let decodedList: [Node?] = try fory.deserialize(listData)
    #expect(decodedList.count == 3)
    #expect(decodedList[0] === decodedList[1])
    #expect(decodedList[2] == nil)

    let sharedValue = Node(value: 21)
    let map: [Int8: Node?] = [1: sharedValue, 2: sharedValue]
    let mapData = try fory.serialize(map)
    let mapReader = ByteReader(data: mapData)
    _ = try fory.readHead(reader: mapReader)
    _ = try mapReader.readInt8()
    _ = try mapReader.readVarUInt32()
    _ = try mapReader.readVarUInt32()
    let mapChunkHeader = try mapReader.readUInt8()
    #expect((mapChunkHeader & 0b0000_1000) != 0)

    let decodedMap: [Int8: Node?] = try fory.deserialize(mapData)
    let v1 = decodedMap[1] ?? nil
    let v2 = decodedMap[2] ?? nil
    #expect(v1 != nil)
    #expect(v1 === v2)
}

@Test
func macroFieldOrderFollowsForyRules() throws {
    let fory = Fory()
    fory.register(FieldOrder.self, id: 300)

    let value = FieldOrder(z: "tail", a: 123456789, b: 17, c: 99)
    let data = try fory.serialize(value)

    let reader = ByteReader(data: data)
    _ = try fory.readHead(reader: reader)
    _ = try reader.readInt8() // root ref flag
    _ = try reader.readVarUInt32() // type id
    _ = try reader.readVarUInt32() // user type id
    _ = try reader.readInt32() // schema hash

    let first = try reader.readInt16()
    let second = try reader.readVarInt64()
    let third = try reader.readVarInt32()

    let tailContext = ReadContext(reader: reader, typeResolver: fory.typeResolver, trackRef: false)
    let fourth = try String.foryReadData(tailContext)

    #expect(first == value.b)
    #expect(second == value.a)
    #expect(third == value.c)
    #expect(fourth == value.z)
}

@Test
func macroFieldEncodingOverridesForUnsignedTypes() throws {
    let fory = Fory()
    fory.register(EncodedNumberFields.self, id: 301)

    let value = EncodedNumberFields(
        u32Fixed: 0x11223344,
        u64Tagged: UInt64(Int32.max) + 99
    )
    let data = try fory.serialize(value)
    let decoded: EncodedNumberFields = try fory.deserialize(data)
    #expect(decoded == value)

    let reader = ByteReader(data: data)
    _ = try fory.readHead(reader: reader)
    _ = try reader.readInt8()
    _ = try reader.readVarUInt32()
    _ = try reader.readVarUInt32()
    _ = try reader.readInt32()

    #expect(try reader.readUInt32() == value.u32Fixed)
    #expect(try reader.readTaggedUInt64() == value.u64Tagged)
}

@Test
func macroFieldEncodingOverridesCompatibleTypeMeta() throws {
    let fields = EncodedNumberFields.foryCompatibleTypeMetaFields(trackRef: false)
    #expect(fields.count == 2)
    #expect(fields[0].fieldName == "u32Fixed")
    #expect(fields[0].fieldType.typeID == ForyTypeId.uint32.rawValue)
    #expect(fields[1].fieldName == "u64Tagged")
    #expect(fields[1].fieldType.typeID == ForyTypeId.taggedUInt64.rawValue)
}

@Test
func pvlVarInt64AndVarUInt64Extremes() throws {
    let uintValues: [UInt64] = [
        0,
        1,
        127,
        128,
        16_383,
        16_384,
        2_097_151,
        2_097_152,
        268_435_455,
        268_435_456,
        34_359_738_367,
        34_359_738_368,
        4_398_046_511_103,
        4_398_046_511_104,
        562_949_953_421_311,
        562_949_953_421_312,
        72_057_594_037_927_935,
        72_057_594_037_927_936,
        UInt64(Int64.max),
        UInt64.max,
    ]
    let intValues: [Int64] = [
        Int64.min,
        Int64.min + 1,
        -1_000_000_000_000,
        -1_000_000,
        -1_000,
        -128,
        -1,
        0,
        1,
        127,
        1_000,
        1_000_000,
        1_000_000_000_000,
        Int64.max - 1,
        Int64.max,
    ]

    let writer = ByteWriter()
    for value in uintValues {
        writer.writeVarUInt64(value)
    }
    for value in intValues {
        writer.writeVarInt64(value)
    }
    let minWriter = ByteWriter()
    minWriter.writeVarInt64(Int64.min)
    #expect(minWriter.storage.count == 9)
    #expect(minWriter.storage.allSatisfy { $0 == 0xFF })

    let encoded = writer.storage

    let reader = ByteReader(bytes: encoded)
    for value in uintValues {
        #expect(try reader.readVarUInt64() == value)
    }
    for value in intValues {
        #expect(try reader.readVarInt64() == value)
    }
    #expect(reader.remaining == 0)
}

@Test
func metaStringEncodingRoundTrip() throws {
    let encoder = MetaStringEncoder.fieldName
    let decoder = MetaStringDecoder.fieldName

    let lower = try encoder.encode("alpha_beta", encoding: .lowerSpecial)
    #expect(lower.encoding == .lowerSpecial)
    #expect(try decoder.decode(bytes: lower.bytes, encoding: lower.encoding).value == "alpha_beta")

    let firstLower = try encoder.encode("User_name", encoding: .firstToLowerSpecial)
    #expect(firstLower.encoding == .firstToLowerSpecial)
    #expect(try decoder.decode(bytes: firstLower.bytes, encoding: firstLower.encoding).value == "User_name")

    let allLower = try encoder.encode("MyHTTPType", encoding: .allToLowerSpecial)
    #expect(allLower.encoding == .allToLowerSpecial)
    #expect(try decoder.decode(bytes: allLower.bytes, encoding: allLower.encoding).value == "MyHTTPType")

    let lowerUpperDigit = try encoder.encode("userId2", encoding: .lowerUpperDigitSpecial)
    #expect(lowerUpperDigit.encoding == .lowerUpperDigitSpecial)
    #expect(try decoder.decode(bytes: lowerUpperDigit.bytes, encoding: lowerUpperDigit.encoding).value == "userId2")

    let autoUtf8 = try encoder.encode("naïve_meta")
    #expect(autoUtf8.encoding == .utf8)
    #expect(try decoder.decode(bytes: autoUtf8.bytes, encoding: autoUtf8.encoding).value == "naïve_meta")
}

@Test
func typeMetaRoundTripByName() throws {
    let namespace = try MetaStringEncoder.namespace.encode("com.example")
    let typeName = try MetaStringEncoder.typeName.encode("UserProfile")

    let fields: [TypeMetaFieldInfo] = [
        .init(
            fieldID: nil,
            fieldName: "createdAt",
            fieldType: .init(typeID: ForyTypeId.varint64.rawValue, nullable: false)
        ),
        .init(
            fieldID: nil,
            fieldName: "tags",
            fieldType: .init(
                typeID: ForyTypeId.list.rawValue,
                nullable: false,
                generics: [.init(typeID: ForyTypeId.string.rawValue, nullable: true)]
            )
        ),
        .init(
            fieldID: nil,
            fieldName: "attributes",
            fieldType: .init(
                typeID: ForyTypeId.map.rawValue,
                nullable: true,
                generics: [
                    .init(typeID: ForyTypeId.string.rawValue, nullable: false),
                    .init(typeID: ForyTypeId.varint32.rawValue, nullable: true),
                ]
            )
        ),
        .init(
            fieldID: 7,
            fieldName: "ignored_for_tag_mode",
            fieldType: .init(typeID: ForyTypeId.varint32.rawValue, nullable: false)
        ),
    ]

    let meta = try TypeMeta(
        typeID: nil,
        userTypeID: nil,
        namespace: namespace,
        typeName: typeName,
        registerByName: true,
        fields: fields
    )

    let encoded = try meta.encode()
    let decoded = try TypeMeta.decode(encoded)

    #expect(decoded.registerByName == true)
    #expect(decoded.namespace.value == "com.example")
    #expect(decoded.typeName.value == "UserProfile")
    #expect(decoded.typeID == nil)
    #expect(decoded.userTypeID == nil)
    #expect(decoded.fields.count == 4)
    #expect(decoded.fields[0].fieldName == "created_at")
    #expect(decoded.fields[3].fieldID == 7)
}

@Test
func typeMetaRoundTripByID() throws {
    let emptyNamespace = MetaString.empty(specialChar1: ".", specialChar2: "_")
    let emptyTypeName = MetaString.empty(specialChar1: "$", specialChar2: "_")

    let meta = try TypeMeta(
        typeID: ForyTypeId.structType.rawValue,
        userTypeID: 101,
        namespace: emptyNamespace,
        typeName: emptyTypeName,
        registerByName: false,
        fields: []
    )

    let encoded = try meta.encode()
    let decoded = try TypeMeta.decode(encoded)

    #expect(decoded.registerByName == false)
    #expect(decoded.typeID == ForyTypeId.structType.rawValue)
    #expect(decoded.userTypeID == 101)
    #expect(decoded.fields.isEmpty)
}
