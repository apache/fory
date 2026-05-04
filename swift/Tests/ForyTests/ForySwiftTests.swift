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

@ForyStruct
struct Address: Equatable {
    var street: String
    var zip: Int32
}

@ForyStruct
struct Person: Equatable {
    var id: Int64
    var name: String
    var nickname: String?
    var scores: [Int32]
    var tags: Set<String>
    var addresses: [Address]
    var metadata: [Int8: Int32?]
}

@ForyStruct
struct FieldOrder: Equatable {
    var textTail: String
    var longValue: Int64
    var shortValue: Int16
    var intValue: Int32
}

@ForyStruct
struct TaggedFieldOrder: Equatable {
    @ForyField(id: 1)
    var textTail: String

    @ForyField(id: 10)
    var intValue: Int32
}

@ForyStruct
struct EncodedNumberFields: Equatable {
    @ForyField(encoding: .fixed)
    var u32Fixed: UInt32

    @ForyField(encoding: .tagged)
    var u64Tagged: UInt64
}

@ForyStruct
struct ReducedPrecisionMacroFields: Equatable {
    var float16Value: Float16
    var bfloat16Value: BFloat16
    @ArrayField(element: .float16)
    var float16Array: [Float16]
    @ArrayField(element: .bfloat16)
    var bfloat16Array: [BFloat16]
}

@ForyStruct
struct FieldIdConfigured: Equatable {
    @ForyField(id: 2)
    var stableID: Int32

    @ForyField(id: 5, encoding: .fixed)
    var fixedValue: Int32
}

@ForyStruct
struct FieldIdSource: Equatable {
    @ForyField(id: 1)
    var value: Int32

    @ForyField(id: 4)
    var label: String
}

@ForyStruct
struct FieldIdTarget: Equatable {
    @ForyField(id: 1)
    var renamedValue: Int32

    @ForyField(id: 4)
    var renamedLabel: String
}

@ForyEnum
enum SparseStatus: Int32, CaseIterable {
    case unknown = 4096
    case ok = 8192
}

@ForyStruct
struct EvolvingOverrideValue: Equatable {
    var f1: String = ""
}

@ForyStruct(evolving: false)
struct FixedOverrideValue: Equatable {
    var f1: String = ""
}

@ForyUnion
enum FieldIdUnionSource: Equatable {
    @ForyCase(id: 3)
    case number(Int32)

    @ForyCase(id: 9)
    case text(String)
}

@ForyUnion
enum FieldIdUnionTarget: Equatable {
    @ForyCase(id: 3)
    case renamedNumber(Int32)

    @ForyCase(id: 9)
    case renamedText(String)
}

@ForyStruct
struct CompatibleNestedItem: Equatable {
    var id: Int32
    var name: String
}

@ForyStruct
struct CompatibleNestedArrayHolder: Equatable {
    var items: [CompatibleNestedItem]
}

@ForyStruct
struct CompatibleNestedOptionalArrayHolder: Equatable {
    var items: [CompatibleNestedItem?]
}

@ForyStruct
struct CompatibleNestedMapHolder: Equatable {
    var items: [Int32: CompatibleNestedItem]
}

@ForyStruct
final class Node {
    var value: Int32 = 0
    var next: Node?

    required init() {}

    init(value: Int32, next: Node? = nil) {
        self.value = value
        self.next = next
    }
}

@ForyStruct
final class WeakNode {
    var value: Int32 = 0
    weak var next: WeakNode?

    required init() {}

    init(value: Int32, next: WeakNode? = nil) {
        self.value = value
        self.next = next
    }
}

@ForyStruct
struct AnyObjectHolder {
    var value: AnyObject
    var optionalValue: AnyObject?
    var items: [AnyObject]
}

@ForyStruct
struct AnySerializerHolder {
    var value: any Serializer
    var items: [any Serializer]
    var map: [String: any Serializer]
}

@ForyStruct
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
func extendedWireTypesRoundTrip() throws {
    let fory = Fory()

    let float16Value = Float16(3.5)
    let float16Data = try fory.serialize(float16Value)
    let float16Decoded: Float16 = try fory.deserialize(float16Data)
    #expect(float16Decoded.bitPattern == float16Value.bitPattern)

    let bfloatValue = BFloat16(rawValue: 0x3F80)
    let bfloatData = try fory.serialize(bfloatValue)
    let bfloatDecoded: BFloat16 = try fory.deserialize(bfloatData)
    #expect(bfloatDecoded == bfloatValue)

    let durationValue = Duration.seconds(-2) + Duration.nanoseconds(123_456_789)
    let durationData = try fory.serialize(durationValue)
    let durationDecoded: Duration = try fory.deserialize(durationData)
    #expect(durationDecoded == durationValue)

    let float16Array: [Float16] = [Float16(1), Float16(-2), Float16(4.5)]
    let float16ArrayData = try fory.serialize(float16Array)
    let float16ArrayDecoded: [Float16] = try fory.deserialize(float16ArrayData)
    #expect(float16ArrayDecoded.map(\.bitPattern) == float16Array.map(\.bitPattern))
}

@Test
func floatingSpecialsRoundTrip() throws {
    let fory = Fory()

    let floatValues: [Float] = [
        0.0,
        -0.0,
        .infinity,
        -.infinity,
        .leastNonzeroMagnitude,
        .greatestFiniteMagnitude,
        Float(bitPattern: 0x7FC0_1234)
    ]
    for value in floatValues {
        let decoded: Float = try fory.deserialize(try fory.serialize(value))
        #expect(decoded.bitPattern == value.bitPattern)
    }

    let doubleValues: [Double] = [
        0.0,
        -0.0,
        .infinity,
        -.infinity,
        .leastNonzeroMagnitude,
        .greatestFiniteMagnitude,
        Double(bitPattern: 0x7FF8_0000_0000_1234)
    ]
    for value in doubleValues {
        let decoded: Double = try fory.deserialize(try fory.serialize(value))
        #expect(decoded.bitPattern == value.bitPattern)
    }

    let float16Values: [Float16] = [
        .init(bitPattern: 0x0000),
        .init(bitPattern: 0x8000),
        .init(bitPattern: 0x7C00),
        .init(bitPattern: 0xFC00),
        .init(bitPattern: 0x0001),
        .init(bitPattern: 0x7BFF),
        .init(bitPattern: 0x7E11)
    ]
    for value in float16Values {
        let decoded: Float16 = try fory.deserialize(try fory.serialize(value))
        #expect(decoded.bitPattern == value.bitPattern)
    }

    let bfloat16Values: [BFloat16] = [
        .init(rawValue: 0x0000),
        .init(rawValue: 0x8000),
        .init(rawValue: 0x7F80),
        .init(rawValue: 0xFF80),
        .init(rawValue: 0x0001),
        .init(rawValue: 0x7FC1)
    ]
    for value in bfloat16Values {
        let decoded: BFloat16 = try fory.deserialize(try fory.serialize(value))
        #expect(decoded.rawValue == value.rawValue)
    }
}

@Test
func namedInitializerBuildsConfig() {
    let defaultConfig = Fory()
    #expect(defaultConfig.config.xlang == true)
    #expect(defaultConfig.config.trackRef == false)
    #expect(defaultConfig.config.compatible == false)
    #expect(defaultConfig.config.checkClassVersion == true)
    #expect(defaultConfig.config.maxDepth == 5)

    let explicitConfig = Fory(xlang: false, trackRef: true, compatible: true, maxDepth: 7)
    #expect(explicitConfig.config.xlang == false)
    #expect(explicitConfig.config.trackRef == true)
    #expect(explicitConfig.config.compatible == true)
    #expect(explicitConfig.config.checkClassVersion == false)
    #expect(explicitConfig.config.maxDepth == 7)

    let configInit = Fory(config: .init(xlang: false, trackRef: false, compatible: true, maxDepth: 9))
    #expect(configInit.config.xlang == false)
    #expect(configInit.config.trackRef == false)
    #expect(configInit.config.compatible == true)
    #expect(configInit.config.checkClassVersion == false)
    #expect(configInit.config.maxDepth == 9)

    let nativeDirect = Fory(xlang: false, trackRef: true, compatible: false)
    let nativeViaConfig = Fory(config: Config(xlang: false, trackRef: true, compatible: false))
    #expect(nativeDirect.config.checkClassVersion == false)
    #expect(nativeViaConfig.config.checkClassVersion == false)
}

@Test
func structEvolvingOverrideUsesSmallerCompatiblePayload() throws {
    let fory = Fory(compatible: true)
    fory.register(EvolvingOverrideValue.self, id: 1001)
    fory.register(FixedOverrideValue.self, id: 1002)

    let evolving = EvolvingOverrideValue(f1: "payload")
    let fixed = FixedOverrideValue(f1: "payload")

    let evolvingData = try fory.serialize(evolving)
    let fixedData = try fory.serialize(fixed)

    #expect(fixedData.count < evolvingData.count)
    let decodedEvolving: EvolvingOverrideValue = try fory.deserialize(evolvingData)
    let decodedFixed: FixedOverrideValue = try fory.deserialize(fixedData)
    #expect(decodedEvolving == evolving)
    #expect(decodedFixed == fixed)
}

@Test
func decodeLimitsRejectOversizedPayloads() throws {
    let writer = Fory()

    let oversizedCollection = try writer.serialize(["a", "b", "c"])
    let collectionLimited = Fory(config: .init(maxCollectionSize: 2))
    do {
        let _: [String] = try collectionLimited.deserialize(oversizedCollection)
        #expect(Bool(false))
    } catch {}

    let oversizedMap = try writer.serialize([Int32(1): Int32(1), 2: 2, 3: 3])
    do {
        let _: [Int32: Int32] = try collectionLimited.deserialize(oversizedMap)
        #expect(Bool(false))
    } catch {}

    let oversizedBinary = try writer.serialize(Data([0x01, 0x02, 0x03, 0x04]))
    let binaryLimited = Fory(config: .init(maxBinarySize: 3))
    do {
        let _: Data = try binaryLimited.deserialize(oversizedBinary)
        #expect(Bool(false))
    } catch {}

    let oversizedArrayPayload = try writer.serialize([UInt16(1), 2])
    let payloadLimited = Fory(config: .init(maxCollectionSize: 1))
    do {
        let _: [UInt16] = try payloadLimited.deserialize(oversizedArrayPayload)
        #expect(Bool(false))
    } catch {}
}

@Test
func deserializeRejectsTrailingBytes() throws {
    let fory = Fory()
    let payload = try fory.serialize(Int32(7))
    var bytes = [UInt8](payload)
    bytes.append(0xFF)
    let withTrailing = Data(bytes)

    do {
        let _: Int32 = try fory.deserialize(withTrailing)
        #expect(Bool(false))
    } catch {}
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
    #expect(UInt32(int32Bytes[2]) == TypeId.list.rawValue)

    let uint8Data = try fory.serialize([UInt8(1), 2, 3])
    let uint8Bytes = [UInt8](uint8Data)
    #expect(UInt32(uint8Bytes[2]) == TypeId.list.rawValue)
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
func macroClassRefTracking() throws {
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
func macroClassWeakRefTracking() throws {
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
func dynamicUserTypesDecodeByID() throws {
    let fory = Fory()
    fory.register(Address.self, id: 600)
    try fory.register(Person.self, name: "demo.person")

    let value: Any = Address(street: "mixed", zip: 7788)
    let data = try fory.serialize(value)
    let decoded: Any = try fory.deserialize(data)
    #expect(decoded as? Address == Address(street: "mixed", zip: 7788))
}

@Test
func duplicateNameRegistrationIsRejected() throws {
    let resolver = TypeResolver(trackRef: false)
    try resolver.register(Address.self, namespace: "demo", typeName: "entity")

    do {
        try resolver.register(Person.self, namespace: "demo", typeName: "entity")
        #expect(Bool(false))
    } catch {}
}

@Test
func registrationIsRejectedAfterFirstTopLevelUse() throws {
    let fory = Fory()
    _ = try fory.serialize(Int32(7))

    do {
        try fory.register(Address.self, name: "demo.address")
        #expect(Bool(false))
    } catch {
        #expect("\(error)".contains("cannot register more types"))
    }
}

@Test
func serializeToAppendsRoots() throws {
    let fory = Fory()
    let first = Int32(7)
    let second = "swift-buffer"
    let third: String? = nil

    let firstData = try fory.serialize(first)
    let secondData = try fory.serialize(second)
    let thirdData = try fory.serialize(third)

    var stream = Data()
    try fory.serialize(first, to: &stream)
    try fory.serialize(second, to: &stream)
    try fory.serialize(third, to: &stream)

    var expected = Data()
    expected.append(firstData)
    expected.append(secondData)
    expected.append(thirdData)
    #expect(stream == expected)

    let buffer = ByteBuffer(data: stream)
    let decodedFirst: Int32 = try fory.deserialize(from: buffer)
    #expect(decodedFirst == first)
    #expect(buffer.getCursor() == firstData.count)

    let decodedSecond: String = try fory.deserialize(from: buffer)
    #expect(decodedSecond == second)
    #expect(buffer.getCursor() == firstData.count + secondData.count)

    let decodedThird: String? = try fory.deserialize(from: buffer)
    #expect(decodedThird == nil)
    #expect(buffer.remaining == 0)
}

@Test
func rootBufferHonorsCursor() throws {
    let fory = Fory()
    let prefix: [UInt8] = [0xAA, 0xBB, 0xCC]
    let payload = try fory.serialize("offset")

    let buffer = ByteBuffer()
    buffer.writeBytes(prefix)
    buffer.writeBytes(Array(payload))
    buffer.setCursor(prefix.count)

    let decoded: String = try fory.deserialize(from: buffer)
    #expect(decoded == "offset")
    #expect(buffer.getCursor() == buffer.count)
    #expect(Array(buffer.storage.prefix(prefix.count)) == prefix)
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
            "address": Address(street: "Mapped", zip: 10003)
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
func dynamicAnySerializerTracksRefs() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: true))
    fory.register(Node.self, id: 226)
    fory.register(AnySerializerHolder.self, id: 227)

    let shared = Node(value: 88)
    shared.next = shared
    let value = AnySerializerHolder(
        value: shared,
        items: [shared],
        map: ["shared": shared]
    )

    let decoded: AnySerializerHolder = try fory.deserialize(try fory.serialize(value))
    let root = decoded.value as? Node
    let item = decoded.items.first as? Node
    let mapped = decoded.map["shared"] as? Node

    #expect(root != nil)
    #expect(root === item)
    #expect(item === mapped)
    #expect(root?.next === root)
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
            "empty": NSNull()
        ],
        int32Map: [
            1: Int32(-9),
            2: "v2",
            3: Address(street: "AnyIntMap", zip: 11004),
            4: NSNull()
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
func collectionAndMapRefTracking() throws {
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

    let value = FieldOrder(textTail: "tail", longValue: 123456789, shortValue: 17, intValue: 99)
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

    #expect(first == value.shortValue)
    #expect(second == value.longValue)
    #expect(third == value.intValue)
    #expect(fourth == value.textTail)
}

@Test
func macroTaggedFieldsKeepGroupedPayloadOrder() throws {
    let fory = Fory()
    fory.register(TaggedFieldOrder.self, id: 303)

    let fields = TaggedFieldOrder.foryFieldsInfo(trackRef: false)
    #expect(fields.map(\.fieldName) == ["intValue", "textTail"])
    #expect(fields.map(\.fieldID) == [10, 1])

    let value = TaggedFieldOrder(textTail: "tail", intValue: 99)
    let data = try fory.serialize(value)
    let buffer = ByteBuffer(data: data)
    _ = try fory.readHead(buffer: buffer)
    _ = try buffer.readInt8()
    _ = try buffer.readVarUInt32()
    _ = try buffer.readVarUInt32()
    _ = try buffer.readInt32()

    #expect(try buffer.readVarInt32() == value.intValue)
    let tailContext = ReadContext(buffer: buffer, typeResolver: fory.typeResolver, trackRef: false)
    #expect(try String.foryReadData(tailContext) == value.textTail)
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
func macroEnumUsesExplicitIntegerRawValue() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: false))
    fory.register(SparseStatus.self, id: 302)

    let data = try fory.serialize(SparseStatus.ok)
    let buffer = ByteBuffer(data: data)
    _ = try fory.readHead(buffer: buffer)
    _ = try buffer.readInt8()
    _ = try buffer.readVarUInt32()
    _ = try buffer.readVarUInt32()
    #expect(try buffer.readVarUInt32() == 8192)

    let decoded: SparseStatus = try fory.deserialize(data)
    #expect(decoded == .ok)
}

@Test
func macroFieldEncodingOverridesCompatibleTypeMeta() throws {
    let fields = EncodedNumberFields.foryFieldsInfo(trackRef: false)
    #expect(fields.count == 2)
    #expect(fields[0].fieldName == "u32Fixed")
    #expect(fields[0].fieldType.typeID == TypeId.uint32.rawValue)
    #expect(fields[1].fieldName == "u64Tagged")
    #expect(fields[1].fieldType.typeID == TypeId.taggedUInt64.rawValue)
}

@Test
func macroReducedPrecisionFieldsUseXlangTypeIDs() {
    let fields = ReducedPrecisionMacroFields.foryFieldsInfo(trackRef: false)
    #expect(fields.count == 4)
    #expect(fields.map(\.fieldName) == ["float16Value", "bfloat16Value", "float16Array", "bfloat16Array"])
    #expect(fields.map(\.fieldType.typeID) == [
        TypeId.float16.rawValue,
        TypeId.bfloat16.rawValue,
        TypeId.float16Array.rawValue,
        TypeId.bfloat16Array.rawValue
    ])
}

@Test
func macroFieldIDsPopulateCompatibleTypeMeta() {
    let fields = FieldIdConfigured.foryFieldsInfo(trackRef: false)
    #expect(fields.count == 2)

    var byID: [Int16: TypeMeta.FieldInfo] = [:]
    for field in fields {
        if let id = field.fieldID {
            byID[id] = field
        }
    }

    #expect(byID[2]?.fieldName == "stableID")
    #expect(byID[2]?.fieldType.typeID == TypeId.varint32.rawValue)
    #expect(byID[5]?.fieldName == "fixedValue")
    #expect(byID[5]?.fieldType.typeID == TypeId.int32.rawValue)
}

@Test
func macroFieldIDsDriveCompatibleStructDecodeAcrossRenames() throws {
    let writer = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    writer.register(FieldIdSource.self, id: 9101)

    let reader = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    reader.register(FieldIdTarget.self, id: 9101)

    let source = FieldIdSource(value: 42, label: "alpha")
    let bytes = try writer.serialize(source)
    let decoded: FieldIdTarget = try reader.deserialize(bytes)

    #expect(decoded.renamedValue == source.value)
    #expect(decoded.renamedLabel == source.label)

    let roundTrip = try reader.serialize(decoded)
    let back: FieldIdSource = try writer.deserialize(roundTrip)
    #expect(back == source)
}

@Test
func macroFieldIDsDriveTaggedUnionDecodeAcrossRenames() throws {
    let writer = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    writer.register(FieldIdUnionSource.self, id: 9102)

    let reader = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    reader.register(FieldIdUnionTarget.self, id: 9102)

    let source = FieldIdUnionSource.number(123)
    let bytes = try writer.serialize(source)
    let decoded: FieldIdUnionTarget = try reader.deserialize(bytes)

    switch decoded {
    case .renamedNumber(let value):
        #expect(value == 123)
    default:
        #expect(Bool(false))
    }
}

@Test
func compatibleNestedStructArrayRoundTrip() throws {
    let writer = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    writer.register(CompatibleNestedItem.self, id: 9103)
    writer.register(CompatibleNestedArrayHolder.self, id: 9104)

    let reader = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    reader.register(CompatibleNestedItem.self, id: 9103)
    reader.register(CompatibleNestedArrayHolder.self, id: 9104)

    let value = CompatibleNestedArrayHolder(
        items: [
            CompatibleNestedItem(id: 1, name: "alpha"),
            CompatibleNestedItem(id: 2, name: "beta")
        ]
    )
    let bytes = try writer.serialize(value)
    let decoded: CompatibleNestedArrayHolder = try reader.deserialize(bytes)
    #expect(decoded == value)
}

@Test
func compatibleNestedStructOptionalArrayRoundTrip() throws {
    let writer = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    writer.register(CompatibleNestedItem.self, id: 9103)
    writer.register(CompatibleNestedOptionalArrayHolder.self, id: 9105)

    let reader = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    reader.register(CompatibleNestedItem.self, id: 9103)
    reader.register(CompatibleNestedOptionalArrayHolder.self, id: 9105)

    let value = CompatibleNestedOptionalArrayHolder(
        items: [
            CompatibleNestedItem(id: 1, name: "alpha"),
            nil,
            CompatibleNestedItem(id: 2, name: "beta")
        ]
    )
    let bytes = try writer.serialize(value)
    let decoded: CompatibleNestedOptionalArrayHolder = try reader.deserialize(bytes)
    #expect(decoded == value)
}

@Test
func compatibleNestedStructMapRoundTrip() throws {
    let writer = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    writer.register(CompatibleNestedItem.self, id: 9103)
    writer.register(CompatibleNestedMapHolder.self, id: 9106)

    let reader = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    reader.register(CompatibleNestedItem.self, id: 9103)
    reader.register(CompatibleNestedMapHolder.self, id: 9106)

    let value = CompatibleNestedMapHolder(
        items: [
            1: CompatibleNestedItem(id: 10, name: "first"),
            2: CompatibleNestedItem(id: 20, name: "second")
        ]
    )
    let bytes = try writer.serialize(value)
    let decoded: CompatibleNestedMapHolder = try reader.deserialize(bytes)
    #expect(decoded == value)
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
        UInt64.max
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
        Int64.max
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
    #expect(minBuffer.count == 9)
    #expect(minBuffer.storage.prefix(minBuffer.count).allSatisfy { $0 == 0xFF })

    let encoded = Array(writeBuffer.storage.prefix(writeBuffer.count))

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

    let fields: [TypeMeta.FieldInfo] = [
        .init(
            fieldID: nil,
            fieldName: "createdAt",
            fieldType: .init(typeID: TypeId.varint64.rawValue, nullable: false)
        ),
        .init(
            fieldID: nil,
            fieldName: "tags",
            fieldType: .init(
                typeID: TypeId.list.rawValue,
                nullable: false,
                generics: [.init(typeID: TypeId.string.rawValue, nullable: true)]
            )
        ),
        .init(
            fieldID: nil,
            fieldName: "attributes",
            fieldType: .init(
                typeID: TypeId.map.rawValue,
                nullable: true,
                generics: [
                    .init(typeID: TypeId.string.rawValue, nullable: false),
                    .init(typeID: TypeId.varint32.rawValue, nullable: true)
                ]
            )
        ),
        .init(
            fieldID: 7,
            fieldName: "ignored_for_tag_mode",
            fieldType: .init(typeID: TypeId.varint32.rawValue, nullable: false)
        )
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
        typeID: TypeId.structType.rawValue,
        userTypeID: 101,
        namespace: emptyNamespace,
        typeName: emptyTypeName,
        registerByName: false,
        fields: []
    )

    let encoded = try meta.encode()
    let decoded = try TypeMeta.decode(encoded)

    #expect(decoded.registerByName == false)
    #expect(decoded.typeID == TypeId.structType.rawValue)
    #expect(decoded.userTypeID == 101)
    #expect(decoded.fields.isEmpty)
}
