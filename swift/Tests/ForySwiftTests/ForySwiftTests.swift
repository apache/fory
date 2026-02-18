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

    let first = try reader.readInt16()
    let second = try reader.readVarInt64()
    let third = try reader.readVarInt32()

    let tailContext = ReadContext(reader: reader, typeResolver: fory.typeResolver, trackRef: false)
    let fourth = try String.readData(tailContext)

    #expect(first == value.b)
    #expect(second == value.a)
    #expect(third == value.c)
    #expect(fourth == value.z)
}
