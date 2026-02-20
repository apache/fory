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
@testable import Fory

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

@ForyObject
final class WeakNode {
    var value: Int32 = 0
    weak var next: WeakNode?

    required init() {}

    init(value: Int32, next: WeakNode? = nil) {
        self.value = value
        self.next = next
    }
}

@ForyObject
struct AnyObjectHolder {
    var value: AnyObject
    var optionalValue: AnyObject?
    var items: [AnyObject]
}

@ForyObject
struct AnySerializerHolder {
    var value: any Serializer
    var items: [any Serializer]
    var map: [String: any Serializer]
}

@ForyObject
struct AnyFieldHolder {
    var value: Any
    var optionalValue: Any?
    var list: [Any]
    var stringMap: [String: Any]
    var int32Map: [Int32: Any]
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
func macroClassWeakReferenceTracking() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: true))
    fory.register(WeakNode.self, id: 201)

    let node = WeakNode(value: 13)
    node.next = node

    let data = try fory.serialize(node)
    let decoded: WeakNode = try fory.deserialize(data)

    #expect(decoded.value == 13)
    #expect(decoded.next === decoded)
}

@Test
func topLevelAnyRoundTrip() throws {
    let fory = Fory()
    fory.register(Address.self, id: 209)

    let value: Any = Address(street: "AnyTop", zip: 8080)
    let data = try fory.serialize(value)
    let decoded: Any = try fory.deserialize(data)
    #expect(decoded as? Address == Address(street: "AnyTop", zip: 8080))

    var buffer = Data()
    try fory.serialize(value, to: &buffer)
    let decodedFrom: Any = try fory.deserialize(from: ByteBuffer(data: buffer))
    #expect(decodedFrom as? Address == Address(street: "AnyTop", zip: 8080))

    let nullAny: Any = Optional<Int32>.none as Any
    let nullData = try fory.serialize(nullAny)
    let nullDecoded: Any = try fory.deserialize(nullData)
    #expect(nullDecoded is ForyAnyNullValue)
}

@Test
func topLevelAnyObjectRoundTrip() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: true))
    fory.register(Node.self, id: 210)

    let value: AnyObject = Node(value: 123)
    let data = try fory.serialize(value)
    let decoded: AnyObject = try fory.deserialize(data)

    let node = decoded as? Node
    #expect(node != nil)
    #expect(node?.value == 123)

    var buffer = Data()
    try fory.serialize(value, to: &buffer)
    let decodedFrom: AnyObject = try fory.deserialize(from: ByteBuffer(data: buffer))
    #expect((decodedFrom as? Node)?.value == 123)
}

@Test
func topLevelAnySerializerRoundTrip() throws {
    let fory = Fory()
    fory.register(Address.self, id: 211)

    let value: any Serializer = Address(street: "AnyStreet", zip: 9090)
    let data = try fory.serialize(value)
    let decoded: any Serializer = try fory.deserialize(data)

    let address = decoded as? Address
    #expect(address == Address(street: "AnyStreet", zip: 9090))

    var buffer = Data()
    try fory.serialize(value, to: &buffer)
    let decodedFrom: any Serializer = try fory.deserialize(from: ByteBuffer(data: buffer))
    #expect(decodedFrom as? Address == Address(street: "AnyStreet", zip: 9090))
}

@Test
func macroDynamicAnyObjectAndAnySerializerFieldsRoundTrip() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: true))
    fory.register(Node.self, id: 220)
    fory.register(Address.self, id: 221)
    fory.register(AnyObjectHolder.self, id: 222)
    fory.register(AnySerializerHolder.self, id: 223)

    let sharedNode = Node(value: 77)
    let objectHolder = AnyObjectHolder(
        value: sharedNode,
        optionalValue: nil,
        items: [sharedNode, NSNull()]
    )
    let objectData = try fory.serialize(objectHolder)
    let objectDecoded: AnyObjectHolder = try fory.deserialize(objectData)
    #expect((objectDecoded.value as? Node)?.value == 77)
    #expect(objectDecoded.optionalValue == nil)
    #expect(objectDecoded.items.count == 2)
    #expect((objectDecoded.items[0] as? Node)?.value == 77)
    #expect(objectDecoded.items[1] is NSNull)

    let serializerHolder = AnySerializerHolder(
        value: Address(street: "Root", zip: 10001),
        items: [Int32(11), Address(street: "Nested", zip: 10002)],
        map: [
            "age": Int64(19),
            "address": Address(street: "Mapped", zip: 10003),
        ]
    )
    let serializerData = try fory.serialize(serializerHolder)
    let serializerDecoded: AnySerializerHolder = try fory.deserialize(serializerData)

    #expect(serializerDecoded.value as? Address == Address(street: "Root", zip: 10001))
    #expect(serializerDecoded.items.count == 2)
    #expect(serializerDecoded.items[0] as? Int32 == 11)
    #expect(serializerDecoded.items[1] as? Address == Address(street: "Nested", zip: 10002))
    #expect(serializerDecoded.map["age"] as? Int64 == 19)
    #expect(serializerDecoded.map["address"] as? Address == Address(street: "Mapped", zip: 10003))
}

@Test
func macroAnyFieldsRoundTrip() throws {
    let fory = Fory()
    fory.register(Address.self, id: 224)
    fory.register(AnyFieldHolder.self, id: 225)

    let value = AnyFieldHolder(
        value: Address(street: "AnyRoot", zip: 11001),
        optionalValue: nil,
        list: [Int32(7), "hello", Address(street: "AnyList", zip: 11002), NSNull()],
        stringMap: [
            "count": Int64(3),
            "name": "map",
            "address": Address(street: "AnyMap", zip: 11003),
            "empty": NSNull(),
        ],
        int32Map: [
            1: Int32(-9),
            2: "v2",
            3: Address(street: "AnyIntMap", zip: 11004),
            4: NSNull(),
        ]
    )
    let data = try fory.serialize(value)
    let decoded: AnyFieldHolder = try fory.deserialize(data)

    #expect(decoded.value as? Address == Address(street: "AnyRoot", zip: 11001))
    #expect(decoded.optionalValue == nil)
    #expect(decoded.list.count == 4)
    #expect(decoded.list[0] as? Int32 == 7)
    #expect(decoded.list[1] as? String == "hello")
    #expect(decoded.list[2] as? Address == Address(street: "AnyList", zip: 11002))
    #expect(decoded.list[3] is NSNull)
    #expect(decoded.stringMap["count"] as? Int64 == 3)
    #expect(decoded.stringMap["name"] as? String == "map")
    #expect(decoded.stringMap["address"] as? Address == Address(street: "AnyMap", zip: 11003))
    #expect(decoded.stringMap["empty"] is NSNull)
    #expect(decoded.int32Map[1] as? Int32 == -9)
    #expect(decoded.int32Map[2] as? String == "v2")
    #expect(decoded.int32Map[3] as? Address == Address(street: "AnyIntMap", zip: 11004))
    #expect(decoded.int32Map[4] is NSNull)
}

@Test
func collectionAndMapReferenceTracking() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: true))
    fory.register(Node.self, id: 200)

    let shared = Node(value: 11)
    let list: [Node?] = [shared, shared, nil]
    let listData = try fory.serialize(list)
    let listReader = ByteBuffer(data: listData)
    _ = try fory.readHead(buffer: listReader)
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
    let mapReader = ByteBuffer(data: mapData)
    _ = try fory.readHead(buffer: mapReader)
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

    let buffer = ByteBuffer(data: data)
    _ = try fory.readHead(buffer: buffer)
    _ = try buffer.readInt8() // root ref flag
    _ = try buffer.readVarUInt32() // type id
    _ = try buffer.readVarUInt32() // user type id
    _ = try buffer.readInt32() // schema hash

    let first = try buffer.readInt16()
    let second = try buffer.readVarInt64()
    let third = try buffer.readVarInt32()

    let tailContext = ReadContext(buffer: buffer, typeResolver: fory.typeResolver, trackRef: false)
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

    let buffer = ByteBuffer(data: data)
    _ = try fory.readHead(buffer: buffer)
    _ = try buffer.readInt8()
    _ = try buffer.readVarUInt32()
    _ = try buffer.readVarUInt32()
    _ = try buffer.readInt32()

    #expect(try buffer.readUInt32() == value.u32Fixed)
    #expect(try buffer.readTaggedUInt64() == value.u64Tagged)
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

    let writeBuffer = ByteBuffer()
    for value in uintValues {
        writeBuffer.writeVarUInt64(value)
    }
    for value in intValues {
        writeBuffer.writeVarInt64(value)
    }
    let minBuffer = ByteBuffer()
    minBuffer.writeVarInt64(Int64.min)
    #expect(minBuffer.storage.count == 9)
    #expect(minBuffer.storage.allSatisfy { $0 == 0xFF })

    let encoded = writeBuffer.storage

    let readBuffer = ByteBuffer(bytes: encoded)
    for value in uintValues {
        #expect(try readBuffer.readVarUInt64() == value)
    }
    for value in intValues {
        #expect(try readBuffer.readVarInt64() == value)
    }
    #expect(readBuffer.remaining == 0)
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
