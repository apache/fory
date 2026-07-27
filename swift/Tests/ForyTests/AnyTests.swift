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
private struct AnyHashableDynamicKey: Equatable, Hashable {
    var id: Int32 = 0
}

@ForyStruct
private struct AnyHashableDynamicValue: Equatable {
    var label: String = ""
    var score: Int32 = 0
}

@ForyStruct
private final class AnyObjectDynamicNode {
    var value: Int32 = 0

    required init() {}

    init(value: Int32) {
        self.value = value
    }
}

@ForyStruct
private final class AnyObjectDynamicGraphNode {
    var value: Int32 = 0
    var next: AnyObjectDynamicGraphNode?

    required init() {}

    init(value: Int32, next: AnyObjectDynamicGraphNode? = nil) {
        self.value = value
        self.next = next
    }
}

@ForyStruct
private struct AnyHashableMapHolder {
    var map: [AnyHashable: Any] = [:]
    var optionalMap: [AnyHashable: Any]?
}

@ForyStruct
private struct AnyCoreFieldHolder {
    var anyValue: Any = ForyAnyNullValue()
    var anyObjectValue: AnyObject = NSNull()
    var anyList: [Any] = []
    var stringAnyMap: [String: Any] = [:]
    var int32AnyMap: [Int32: Any] = [:]
}

@ForyStruct
private struct AnyHashableSetHolder {
    var set: Set<AnyHashable> = []
    var optionalSet: Set<AnyHashable>?
}

@ForyStruct
private struct AnyHashableValueHolder {
    var value: AnyHashable = AnyHashable(Int32(0))
}

private typealias AnyArraySerializer = ArraySerializer<DynamicSerializer<Any>>
private typealias StringAnyMapSerializer =
    DictionarySerializer<String, DynamicSerializer<Any>>
private typealias Int32AnyMapSerializer =
    DictionarySerializer<Int32, DynamicSerializer<Any>>
private typealias AnyHashableAnyMapSerializer =
    DictionarySerializer<DynamicSerializer<AnyHashable>, DynamicSerializer<Any>>

private func nestedDynamicAnyList(depth: Int) -> Any {
    var value: Any = Int32(1)
    if depth <= 0 {
        return value
    }
    for _ in 0..<depth {
        value = [value] as [Any]
    }
    return value
}

@Test
func directDynamicRootsRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: true, compatible: false))
    try fory.register(AnyObjectDynamicNode.self, id: 512)

    let anyValue: Any = Int32(7)
    let anyData = try fory.serialize(anyValue)
    let explicitAnyData = try fory.serialize(
        anyValue,
        with: DynamicSerializer<Any>.self
    )
    #expect(anyData == explicitAnyData)

    let anyDecoded: Any = try fory.deserialize(anyData)
    #expect(anyDecoded as? Int32 == 7)

    var anyBuffer = Data()
    try fory.serialize(anyValue, to: &anyBuffer)
    #expect(anyBuffer == explicitAnyData)
    let bufferedAny: Any = try fory.deserialize(from: ByteBuffer(data: anyBuffer))
    #expect(bufferedAny as? Int32 == 7)

    let objectValue: AnyObject = AnyObjectDynamicNode(value: 8)
    let objectData = try fory.serialize(objectValue)
    let explicitObjectData = try fory.serialize(
        objectValue,
        with: DynamicSerializer<AnyObject>.self
    )
    #expect(objectData == explicitObjectData)

    let objectDecoded: AnyObject = try fory.deserialize(objectData)
    #expect((objectDecoded as? AnyObjectDynamicNode)?.value == 8)

    var objectBuffer = Data()
    try fory.serialize(objectValue, to: &objectBuffer)
    #expect(objectBuffer == explicitObjectData)
    let bufferedObject: AnyObject = try fory.deserialize(
        from: ByteBuffer(data: objectBuffer)
    )
    #expect((bufferedObject as? AnyObjectDynamicNode)?.value == 8)
}

@Test
func explicitDynamicRootsRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(AnyHashableDynamicKey.self, id: 510)
    try fory.register(AnyHashableDynamicValue.self, id: 511)

    let anyValue: Any = AnyHashableDynamicValue(label: "context-any", score: 1)
    let anyData = try fory.serialize(anyValue, with: DynamicSerializer<Any>.self)
    let anyDecoded = try fory.deserialize(anyData, with: DynamicSerializer<Any>.self)
    #expect(
        anyDecoded as? AnyHashableDynamicValue
            == AnyHashableDynamicValue(label: "context-any", score: 1)
    )

    let listValue: [Any] = [
        Int32(2),
        "context-list",
        AnyHashableDynamicValue(label: "context-list-obj", score: 3)
    ]
    let listData = try fory.serialize(listValue, with: AnyArraySerializer.self)
    let listDecoded = try fory.deserialize(listData, with: AnyArraySerializer.self)
    #expect(listDecoded[0] as? Int32 == 2)
    #expect(listDecoded[1] as? String == "context-list")
    #expect(
        listDecoded[2] as? AnyHashableDynamicValue
            == AnyHashableDynamicValue(label: "context-list-obj", score: 3)
    )

    let stringMapValue: [String: Any] = [
        "a": Int32(4),
        "b": AnyHashableDynamicValue(label: "context-string-map", score: 5)
    ]
    let stringMapData = try fory.serialize(stringMapValue, with: StringAnyMapSerializer.self)
    let stringMapDecoded = try fory.deserialize(stringMapData, with: StringAnyMapSerializer.self)
    #expect(stringMapDecoded["a"] as? Int32 == 4)
    #expect(
        stringMapDecoded["b"] as? AnyHashableDynamicValue
            == AnyHashableDynamicValue(label: "context-string-map", score: 5)
    )

    let int32MapValue: [Int32: Any] = [
        6: "context-int-map",
        7: AnyHashableDynamicValue(label: "context-int-map-obj", score: 8)
    ]
    let int32MapData = try fory.serialize(int32MapValue, with: Int32AnyMapSerializer.self)
    let int32MapDecoded = try fory.deserialize(int32MapData, with: Int32AnyMapSerializer.self)
    #expect(int32MapDecoded[6] as? String == "context-int-map")
    #expect(
        int32MapDecoded[7] as? AnyHashableDynamicValue
            == AnyHashableDynamicValue(label: "context-int-map-obj", score: 8)
    )

    let anyHashableMapValue: [AnyHashable: Any] = [
        AnyHashable("x"): Int32(9),
        AnyHashable(AnyHashableDynamicKey(id: 10)):
            AnyHashableDynamicValue(label: "context-any-map", score: 11)
    ]
    let anyHashableMapData = try fory.serialize(
        anyHashableMapValue,
        with: AnyHashableAnyMapSerializer.self
    )
    let anyHashableMapDecoded = try fory.deserialize(
        anyHashableMapData,
        with: AnyHashableAnyMapSerializer.self
    )
    #expect(anyHashableMapDecoded[AnyHashable("x")] as? Int32 == 9)
    #expect(
        anyHashableMapDecoded[AnyHashable(AnyHashableDynamicKey(id: 10))]
            as? AnyHashableDynamicValue
            == AnyHashableDynamicValue(label: "context-any-map", score: 11)
    )
}

@Test
func topLevelAnyHashableRoundTrip() throws {
    let fory = Fory()

    let value = AnyHashable(Int32(123))
    let data = try fory.serialize(value)
    let decoded: AnyHashable = try fory.deserialize(data)
    #expect(decoded.base as? Int32 == 123)

    var buffer = Data()
    try fory.serialize(value, to: &buffer)
    let decodedFrom: AnyHashable = try fory.deserialize(from: ByteBuffer(data: buffer))
    #expect(decodedFrom.base as? Int32 == 123)
}

@Test
func topLevelAnyHashableAnyMapRoundTrip() throws {
    let fory = Fory()
    try fory.register(AnyHashableDynamicKey.self, id: 410)
    try fory.register(AnyHashableDynamicValue.self, id: 411)

    let value: [AnyHashable: Any] = [
        AnyHashable("name"): "fory",
        AnyHashable(Int32(7)): Int64(9001),
        AnyHashable(true): NSNull(),
        AnyHashable(AnyHashableDynamicKey(id: 3)): AnyHashableDynamicValue(label: "swift", score: 99)
    ]

    let data = try fory.serialize(value, with: AnyHashableAnyMapSerializer.self)
    let decoded = try fory.deserialize(data, with: AnyHashableAnyMapSerializer.self)

    #expect(decoded.count == value.count)
    #expect(decoded[AnyHashable("name")] as? String == "fory")
    #expect(decoded[AnyHashable(Int32(7))] as? Int64 == 9001)
    #expect(decoded[AnyHashable(true)] is ForyAnyNullValue)
    #expect(
        decoded[AnyHashable(AnyHashableDynamicKey(id: 3))] as? AnyHashableDynamicValue
            == AnyHashableDynamicValue(label: "swift", score: 99)
    )

    var buffer = Data()
    try fory.serialize(value, with: AnyHashableAnyMapSerializer.self, to: &buffer)
    let decodedFrom = try fory.deserialize(
        from: ByteBuffer(data: buffer),
        with: AnyHashableAnyMapSerializer.self
    )
    #expect(decodedFrom.count == value.count)
    #expect(decodedFrom[AnyHashable("name")] as? String == "fory")
}

@Test
func topLevelAnyHashableSetRoundTrip() throws {
    let fory = Fory()
    try fory.register(AnyHashableDynamicKey.self, id: 412)

    let value: Set<AnyHashable> = [
        AnyHashable("name"),
        AnyHashable(Int32(7)),
        AnyHashable(true),
        AnyHashable(AnyHashableDynamicKey(id: 11))
    ]

    let data = try fory.serialize(value)
    let decoded: Set<AnyHashable> = try fory.deserialize(data)

    #expect(decoded.count == value.count)
    #expect(decoded.contains(AnyHashable("name")))
    #expect(decoded.contains(AnyHashable(Int32(7))))
    #expect(decoded.contains(AnyHashable(true)))
    #expect(decoded.contains(AnyHashable(AnyHashableDynamicKey(id: 11))))
}

@Test
func topLevelDynamicAnySetRoundTrip() throws {
    let fory = Fory()
    try fory.register(AnyHashableDynamicKey.self, id: 413)

    let value: Any = Set<AnyHashable>([
        AnyHashable("name"),
        AnyHashable(Int32(9)),
        AnyHashable(AnyHashableDynamicKey(id: 12))
    ])

    let data = try fory.serialize(value, with: DynamicSerializer<Any>.self)
    let decoded = try fory.deserialize(data, with: DynamicSerializer<Any>.self)
    let set = decoded as? Set<AnyHashable>
    #expect(set != nil)
    #expect(set?.contains(AnyHashable("name")) == true)
    #expect(set?.contains(AnyHashable(Int32(9))) == true)
    #expect(set?.contains(AnyHashable(AnyHashableDynamicKey(id: 12))) == true)
}

@Test
func macroAnyHashableAnyMapFieldsRoundTrip() throws {
    let fory = Fory()
    try fory.register(AnyHashableDynamicKey.self, id: 420)
    try fory.register(AnyHashableDynamicValue.self, id: 421)
    try fory.register(AnyHashableMapHolder.self, id: 422)

    let value = AnyHashableMapHolder(
        map: [
            AnyHashable("id"): Int32(1),
            AnyHashable(Int32(2)): "value2",
            AnyHashable(AnyHashableDynamicKey(id: 5)): AnyHashableDynamicValue(label: "nested", score: 8)
        ],
        optionalMap: [
            AnyHashable(false): NSNull()
        ]
    )

    let data = try fory.serialize(value)
    let decoded: AnyHashableMapHolder = try fory.deserialize(data)

    #expect(decoded.map[AnyHashable("id")] as? Int32 == 1)
    #expect(decoded.map[AnyHashable(Int32(2))] as? String == "value2")
    #expect(
        decoded.map[AnyHashable(AnyHashableDynamicKey(id: 5))] as? AnyHashableDynamicValue
            == AnyHashableDynamicValue(label: "nested", score: 8)
    )
    #expect(decoded.optionalMap?[AnyHashable(false)] is ForyAnyNullValue)
}

@Test
func macroAnyHashableSetFieldsRoundTrip() throws {
    let fory = Fory()
    try fory.register(AnyHashableDynamicKey.self, id: 423)
    try fory.register(AnyHashableSetHolder.self, id: 424)

    let value = AnyHashableSetHolder(
        set: [
            AnyHashable("a"),
            AnyHashable(Int32(3)),
            AnyHashable(AnyHashableDynamicKey(id: 9))
        ],
        optionalSet: [
            AnyHashable(false)
        ]
    )

    let data = try fory.serialize(value)
    let decoded: AnyHashableSetHolder = try fory.deserialize(data)

    #expect(decoded.set.count == 3)
    #expect(decoded.set.contains(AnyHashable("a")))
    #expect(decoded.set.contains(AnyHashable(Int32(3))))
    #expect(decoded.set.contains(AnyHashable(AnyHashableDynamicKey(id: 9))))
    #expect(decoded.optionalSet?.contains(AnyHashable(false)) == true)
}

@Test
func macroCoreAnyFieldsRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: true, compatible: false))
    try fory.register(AnyHashableDynamicValue.self, id: 425)
    try fory.register(AnyObjectDynamicNode.self, id: 426)
    try fory.register(AnyCoreFieldHolder.self, id: 427)

    let value = AnyCoreFieldHolder(
        anyValue: AnyHashableDynamicValue(label: "core-any", score: 41),
        anyObjectValue: AnyObjectDynamicNode(value: 42),
        anyList: [Int32(44), "core-list", AnyHashableDynamicValue(label: "core-list-obj", score: 45)],
        stringAnyMap: [
            "k1": Int32(46),
            "k2": AnyHashableDynamicValue(label: "core-map-a", score: 47)
        ],
        int32AnyMap: [
            48: "core-map-b",
            49: AnyHashableDynamicValue(label: "core-map-c", score: 50)
        ]
    )

    let data = try fory.serialize(value)
    let decoded: AnyCoreFieldHolder = try fory.deserialize(data)

    #expect(decoded.anyValue as? AnyHashableDynamicValue == AnyHashableDynamicValue(label: "core-any", score: 41))
    #expect((decoded.anyObjectValue as? AnyObjectDynamicNode)?.value == 42)

    #expect(decoded.anyList.count == 3)
    #expect(decoded.anyList[0] as? Int32 == 44)
    #expect(decoded.anyList[1] as? String == "core-list")
    #expect(decoded.anyList[2] as? AnyHashableDynamicValue == AnyHashableDynamicValue(label: "core-list-obj", score: 45))

    #expect(decoded.stringAnyMap["k1"] as? Int32 == 46)
    #expect(decoded.stringAnyMap["k2"] as? AnyHashableDynamicValue == AnyHashableDynamicValue(label: "core-map-a", score: 47))

    #expect(decoded.int32AnyMap[48] as? String == "core-map-b")
    #expect(decoded.int32AnyMap[49] as? AnyHashableDynamicValue == AnyHashableDynamicValue(label: "core-map-c", score: 50))
}

@Test
func macroAnyHashableValueFieldRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: true, compatible: false))
    try fory.register(AnyHashableDynamicKey.self, id: 428)
    try fory.register(AnyHashableValueHolder.self, id: 429)

    let value = AnyHashableValueHolder(value: AnyHashable(AnyHashableDynamicKey(id: 51)))
    let data = try fory.serialize(value)
    let decoded: AnyHashableValueHolder = try fory.deserialize(data)

    #expect(decoded.value.base as? AnyHashableDynamicKey == AnyHashableDynamicKey(id: 51))
}

@Test
func anyHashableMapPreservesTarget() throws {
    let fory = Fory()

    let heterogeneous: Any =
        [
            AnyHashable("k"): Int32(1),
            AnyHashable(Int32(2)): "v2"
        ] as [AnyHashable: Any]
    let heteroData = try fory.serialize(heterogeneous, with: DynamicSerializer<Any>.self)
    let heteroDecoded = try fory.deserialize(heteroData, with: DynamicSerializer<Any>.self)
    let heteroMap = heteroDecoded as? [AnyHashable: Any]
    #expect(heteroMap != nil)
    #expect(heteroMap?[AnyHashable("k")] as? Int32 == 1)
    #expect(heteroMap?[AnyHashable(Int32(2))] as? String == "v2")

    let homogeneous: Any =
        [
            AnyHashable("a"): Int32(10),
            AnyHashable("b"): Int32(20)
        ] as [AnyHashable: Any]
    let homogeneousData = try fory.serialize(homogeneous, with: DynamicSerializer<Any>.self)
    let homogeneousDecoded = try fory.deserialize(
        homogeneousData,
        with: DynamicSerializer<Any>.self
    )
    let homogeneousMap = homogeneousDecoded as? [AnyHashable: Any]
    #expect(homogeneousMap != nil)
    #expect(homogeneousMap?[AnyHashable("a")] as? Int32 == 10)
    #expect(homogeneousMap?[AnyHashable("b")] as? Int32 == 20)
}

@Test
func topLevelAllSupportedAnyTypesRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: true, compatible: false))
    try fory.register(AnyHashableDynamicKey.self, id: 500)
    try fory.register(AnyHashableDynamicValue.self, id: 501)
    try fory.register(AnyObjectDynamicNode.self, id: 502)

    let anyValue: Any = AnyHashableDynamicValue(label: "root-any", score: 1)
    let anyData = try fory.serialize(anyValue, with: DynamicSerializer<Any>.self)
    let anyDecoded = try fory.deserialize(anyData, with: DynamicSerializer<Any>.self)
    #expect(anyDecoded as? AnyHashableDynamicValue == AnyHashableDynamicValue(label: "root-any", score: 1))

    let anyObjectValue: AnyObject = AnyObjectDynamicNode(value: 10)
    let anyObjectData = try fory.serialize(
        anyObjectValue,
        with: DynamicSerializer<AnyObject>.self
    )
    let anyObjectDecoded = try fory.deserialize(
        anyObjectData,
        with: DynamicSerializer<AnyObject>.self
    )
    #expect((anyObjectDecoded as? AnyObjectDynamicNode)?.value == 10)

    let anyHashableValue = AnyHashable(AnyHashableDynamicKey(id: 3))
    let anyHashableData = try fory.serialize(anyHashableValue)
    let anyHashableDecoded: AnyHashable = try fory.deserialize(anyHashableData)
    #expect(anyHashableDecoded.base as? AnyHashableDynamicKey == AnyHashableDynamicKey(id: 3))

    let anyListValue: [Any] = [
        Int32(4),
        "list",
        AnyHashableDynamicValue(label: "list-obj", score: 5)
    ]
    let anyListData = try fory.serialize(anyListValue, with: AnyArraySerializer.self)
    let anyListDecoded = try fory.deserialize(anyListData, with: AnyArraySerializer.self)
    #expect(anyListDecoded.count == 3)
    #expect(anyListDecoded[0] as? Int32 == 4)
    #expect(anyListDecoded[1] as? String == "list")
    #expect(anyListDecoded[2] as? AnyHashableDynamicValue == AnyHashableDynamicValue(label: "list-obj", score: 5))

    let primitiveArrayValue: Any = [Int32(14), Int32(15)] as [Int32]
    let primitiveArrayData = try fory.serialize(
        primitiveArrayValue,
        with: DynamicSerializer<Any>.self
    )
    let primitiveArrayDecoded = try fory.deserialize(
        primitiveArrayData,
        with: DynamicSerializer<Any>.self
    )
    #expect(primitiveArrayDecoded as? [Int32] == [Int32(14), Int32(15)])

    let stringAnyMapValue: [String: Any] = [
        "a": Int32(6),
        "b": AnyHashableDynamicValue(label: "map-a", score: 7)
    ]
    let stringAnyMapData = try fory.serialize(
        stringAnyMapValue,
        with: StringAnyMapSerializer.self
    )
    let stringAnyMapDecoded = try fory.deserialize(
        stringAnyMapData,
        with: StringAnyMapSerializer.self
    )
    #expect(stringAnyMapDecoded["a"] as? Int32 == 6)
    #expect(stringAnyMapDecoded["b"] as? AnyHashableDynamicValue == AnyHashableDynamicValue(label: "map-a", score: 7))

    let int32AnyMapValue: [Int32: Any] = [
        8: "v8",
        9: AnyHashableDynamicValue(label: "map-b", score: 9)
    ]
    let int32AnyMapData = try fory.serialize(
        int32AnyMapValue,
        with: Int32AnyMapSerializer.self
    )
    let int32AnyMapDecoded = try fory.deserialize(
        int32AnyMapData,
        with: Int32AnyMapSerializer.self
    )
    #expect(int32AnyMapDecoded[8] as? String == "v8")
    #expect(int32AnyMapDecoded[9] as? AnyHashableDynamicValue == AnyHashableDynamicValue(label: "map-b", score: 9))

    let anyHashableAnyMapValue: [AnyHashable: Any] = [
        AnyHashable("x"): Int32(10),
        AnyHashable(Int32(11)): AnyHashableDynamicValue(label: "map-c", score: 11)
    ]
    let anyHashableAnyMapData = try fory.serialize(
        anyHashableAnyMapValue,
        with: AnyHashableAnyMapSerializer.self
    )
    let anyHashableAnyMapDecoded = try fory.deserialize(
        anyHashableAnyMapData,
        with: AnyHashableAnyMapSerializer.self
    )
    #expect(anyHashableAnyMapDecoded[AnyHashable("x")] as? Int32 == 10)
    #expect(
        anyHashableAnyMapDecoded[AnyHashable(Int32(11))] as? AnyHashableDynamicValue
            == AnyHashableDynamicValue(label: "map-c", score: 11)
    )

    let anyHashableSetValue: Set<AnyHashable> = [
        AnyHashable("set"),
        AnyHashable(Int32(12)),
        AnyHashable(AnyHashableDynamicKey(id: 13))
    ]
    let anyHashableSetData = try fory.serialize(anyHashableSetValue)
    let anyHashableSetDecoded: Set<AnyHashable> = try fory.deserialize(anyHashableSetData)
    #expect(anyHashableSetDecoded.count == 3)
    #expect(anyHashableSetDecoded.contains(AnyHashable("set")))
    #expect(anyHashableSetDecoded.contains(AnyHashable(Int32(12))))
    #expect(anyHashableSetDecoded.contains(AnyHashable(AnyHashableDynamicKey(id: 13))))
}

@Test
func homogeneousCarrierRootsRoundTrip() throws {
    let fory = Fory()

    let listValue = ["alpha", "beta"]
    let listData = try fory.serialize(listValue, with: ArraySerializer<String>.self)
    let listDecoded = try fory.deserialize(listData, with: ArraySerializer<String>.self)
    #expect(listDecoded == listValue)

    let mapValue = ["k1": "v1", "k2": "v2"]
    let mapData = try fory.serialize(
        mapValue,
        with: DictionarySerializer<String, String>.self
    )
    let mapDecoded = try fory.deserialize(
        mapData,
        with: DictionarySerializer<String, String>.self
    )
    #expect(mapDecoded == mapValue)
}

@Test
func dynamicAnyListTracksRefs() throws {
    let fory = Fory(config: .init(trackRef: true, compatible: false))
    try fory.register(AnyObjectDynamicGraphNode.self, id: 503)

    let shared = AnyObjectDynamicGraphNode(value: 17)
    let payload = try fory.serialize([shared, shared] as [Any], with: AnyArraySerializer.self)
    let decoded = try fory.deserialize(payload, with: AnyArraySerializer.self)
    let first = decoded.first as? AnyObjectDynamicGraphNode
    let second = decoded.dropFirst().first as? AnyObjectDynamicGraphNode

    #expect(decoded.count == 2)
    #expect(first != nil)
    #expect(first === second)
}

@Test
func dynamicMapNullsTrackRefs() throws {
    let fory = Fory(config: .init(trackRef: true, compatible: false))
    try fory.register(AnyHashableDynamicKey.self, id: 505)
    try fory.register(AnyObjectDynamicNode.self, id: 506)

    let nullKey = AnyHashable(ForyAnyNullValue())
    let nullValueKey = AnyHashable(AnyHashableDynamicKey(id: 32))
    let value: [AnyHashable: Any] = [
        nullKey: AnyObjectDynamicNode(value: 31),
        nullValueKey: NSNull()
    ]

    let payload = try fory.serialize(value, with: AnyHashableAnyMapSerializer.self)
    let decoded = try fory.deserialize(payload, with: AnyHashableAnyMapSerializer.self)

    #expect((decoded[nullKey] as? AnyObjectDynamicNode)?.value == 31)
    #expect(decoded[nullValueKey] is ForyAnyNullValue)
}

@Test
func dynamicAnyObjectTracksCycle() throws {
    let fory = Fory(config: .init(trackRef: true, compatible: false))
    try fory.register(AnyObjectDynamicGraphNode.self, id: 504)

    let node = AnyObjectDynamicGraphNode(value: 21)
    node.next = node

    let payload = try fory.serialize(node as AnyObject, with: DynamicSerializer<AnyObject>.self)
    let decoded = try fory.deserialize(payload, with: DynamicSerializer<AnyObject>.self)
    let graphNode = decoded as? AnyObjectDynamicGraphNode

    #expect(graphNode != nil)
    #expect(graphNode?.value == 21)
    #expect(graphNode?.next === graphNode)
}

@Test
func dynamicAnyMaxDepthRejectsDeepNesting() throws {
    let value = nestedDynamicAnyList(depth: 3)
    let writer = Fory(config: .init(maxDepth: 8))
    let payload = try writer.serialize(value, with: DynamicSerializer<Any>.self)

    let limited = Fory(config: .init(maxDepth: 3))
    do {
        _ = try limited.deserialize(payload, with: DynamicSerializer<Any>.self)
        #expect(Bool(false))
    } catch {
        #expect(String(describing: error).contains("maxDepth"))
    }
}

@Test
func dynamicAnyMaxDepthAllowsBoundaryDepth() throws {
    let value = nestedDynamicAnyList(depth: 3)
    let fory = Fory(config: .init(maxDepth: 4))

    let payload = try fory.serialize(value, with: DynamicSerializer<Any>.self)
    let decoded = try fory.deserialize(payload, with: DynamicSerializer<Any>.self)

    let level1 = decoded as? [Any]
    let level2 = level1?.first as? [Any]
    let level3 = level2?.first as? [Any]

    #expect(level1 != nil)
    #expect(level2 != nil)
    #expect(level3 != nil)
    #expect(level3?.first as? Int32 == 1)
}
