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
private struct AnyHashableDynamicKey: Equatable, Hashable {
    var id: Int32 = 0
}

@ForyObject
private struct AnyHashableDynamicValue: Equatable {
    var label: String = ""
    var score: Int32 = 0
}

@ForyObject
private final class AnyObjectDynamicNode {
    var value: Int32 = 0

    required init() {}

    init(value: Int32) {
        self.value = value
    }
}

@ForyObject
private struct AnyHashableMapHolder {
    var map: [AnyHashable: Any] = [:]
    var optionalMap: [AnyHashable: Any]? = nil
}

@ForyObject
private struct AnyCoreFieldHolder {
    var anyValue: Any = ForyAnyNullValue()
    var anyObjectValue: AnyObject = NSNull()
    var anySerializerValue: any Serializer = ForyAnyNullValue()
    var anyList: [Any] = []
    var stringAnyMap: [String: Any] = [:]
    var int32AnyMap: [Int32: Any] = [:]
}

@ForyObject
private struct AnyHashableSetHolder {
    var set: Set<AnyHashable> = []
    var optionalSet: Set<AnyHashable>? = nil
}

@ForyObject
private struct AnyHashableValueHolder {
    var value: AnyHashable = AnyHashable(Int32(0))
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
    fory.register(AnyHashableDynamicKey.self, id: 410)
    fory.register(AnyHashableDynamicValue.self, id: 411)

    let value: [AnyHashable: Any] = [
        AnyHashable("name"): "fory",
        AnyHashable(Int32(7)): Int64(9001),
        AnyHashable(true): NSNull(),
        AnyHashable(AnyHashableDynamicKey(id: 3)): AnyHashableDynamicValue(label: "swift", score: 99),
    ]

    let data = try fory.serialize(value)
    let decoded: [AnyHashable: Any] = try fory.deserialize(data)

    #expect(decoded.count == value.count)
    #expect(decoded[AnyHashable("name")] as? String == "fory")
    #expect(decoded[AnyHashable(Int32(7))] as? Int64 == 9001)
    #expect(decoded[AnyHashable(true)] is NSNull)
    #expect(
        decoded[AnyHashable(AnyHashableDynamicKey(id: 3))] as? AnyHashableDynamicValue
            == AnyHashableDynamicValue(label: "swift", score: 99)
    )

    var buffer = Data()
    try fory.serialize(value, to: &buffer)
    let decodedFrom: [AnyHashable: Any] = try fory.deserialize(from: ByteBuffer(data: buffer))
    #expect(decodedFrom.count == value.count)
    #expect(decodedFrom[AnyHashable("name")] as? String == "fory")
}

@Test
func topLevelAnyHashableSetRoundTrip() throws {
    let fory = Fory()
    fory.register(AnyHashableDynamicKey.self, id: 412)

    let value: Set<AnyHashable> = [
        AnyHashable("name"),
        AnyHashable(Int32(7)),
        AnyHashable(true),
        AnyHashable(AnyHashableDynamicKey(id: 11)),
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
func macroAnyHashableAnyMapFieldsRoundTrip() throws {
    let fory = Fory()
    fory.register(AnyHashableDynamicKey.self, id: 420)
    fory.register(AnyHashableDynamicValue.self, id: 421)
    fory.register(AnyHashableMapHolder.self, id: 422)

    let value = AnyHashableMapHolder(
        map: [
            AnyHashable("id"): Int32(1),
            AnyHashable(Int32(2)): "value2",
            AnyHashable(AnyHashableDynamicKey(id: 5)): AnyHashableDynamicValue(label: "nested", score: 8),
        ],
        optionalMap: [
            AnyHashable(false): NSNull(),
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
    #expect(decoded.optionalMap?[AnyHashable(false)] is NSNull)
}

@Test
func macroAnyHashableSetFieldsRoundTrip() throws {
    let fory = Fory()
    fory.register(AnyHashableDynamicKey.self, id: 423)
    fory.register(AnyHashableSetHolder.self, id: 424)

    let value = AnyHashableSetHolder(
        set: [
            AnyHashable("a"),
            AnyHashable(Int32(3)),
            AnyHashable(AnyHashableDynamicKey(id: 9)),
        ],
        optionalSet: [
            AnyHashable(false),
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
    let fory = Fory(config: .init(xlang: true, trackRef: true))
    fory.register(AnyHashableDynamicValue.self, id: 425)
    fory.register(AnyObjectDynamicNode.self, id: 426)
    fory.register(AnyCoreFieldHolder.self, id: 427)

    let value = AnyCoreFieldHolder(
        anyValue: AnyHashableDynamicValue(label: "core-any", score: 41),
        anyObjectValue: AnyObjectDynamicNode(value: 42),
        anySerializerValue: AnyHashableDynamicValue(label: "core-serializer", score: 43),
        anyList: [Int32(44), "core-list", AnyHashableDynamicValue(label: "core-list-obj", score: 45)],
        stringAnyMap: [
            "k1": Int32(46),
            "k2": AnyHashableDynamicValue(label: "core-map-a", score: 47),
        ],
        int32AnyMap: [
            48: "core-map-b",
            49: AnyHashableDynamicValue(label: "core-map-c", score: 50),
        ]
    )

    let data = try fory.serialize(value)
    let decoded: AnyCoreFieldHolder = try fory.deserialize(data)

    #expect(decoded.anyValue as? AnyHashableDynamicValue == AnyHashableDynamicValue(label: "core-any", score: 41))
    #expect((decoded.anyObjectValue as? AnyObjectDynamicNode)?.value == 42)
    #expect(decoded.anySerializerValue as? AnyHashableDynamicValue == AnyHashableDynamicValue(label: "core-serializer", score: 43))

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
    let fory = Fory(config: .init(xlang: true, trackRef: true))
    fory.register(AnyHashableDynamicKey.self, id: 428)
    fory.register(AnyHashableValueHolder.self, id: 429)

    let value = AnyHashableValueHolder(value: AnyHashable(AnyHashableDynamicKey(id: 51)))
    let data = try fory.serialize(value)
    let decoded: AnyHashableValueHolder = try fory.deserialize(data)

    #expect(decoded.value.base as? AnyHashableDynamicKey == AnyHashableDynamicKey(id: 51))
}

@Test
func dynamicAnyMapNormalizationForAnyHashableKeys() throws {
    let fory = Fory()

    let heterogeneous: Any = [
        AnyHashable("k"): Int32(1),
        AnyHashable(Int32(2)): "v2",
    ] as [AnyHashable: Any]
    let heteroData = try fory.serialize(heterogeneous)
    let heteroDecoded: Any = try fory.deserialize(heteroData)
    let heteroMap = heteroDecoded as? [AnyHashable: Any]
    #expect(heteroMap != nil)
    #expect(heteroMap?[AnyHashable("k")] as? Int32 == 1)
    #expect(heteroMap?[AnyHashable(Int32(2))] as? String == "v2")

    let homogeneous: Any = [
        AnyHashable("a"): Int32(10),
        AnyHashable("b"): Int32(20),
    ] as [AnyHashable: Any]
    let homogeneousData = try fory.serialize(homogeneous)
    let homogeneousDecoded: Any = try fory.deserialize(homogeneousData)
    let homogeneousMap = homogeneousDecoded as? [String: Any]
    #expect(homogeneousMap != nil)
    #expect(homogeneousMap?["a"] as? Int32 == 10)
    #expect(homogeneousMap?["b"] as? Int32 == 20)
}

@Test
func topLevelAllSupportedAnyTypesRoundTrip() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: true))
    fory.register(AnyHashableDynamicKey.self, id: 500)
    fory.register(AnyHashableDynamicValue.self, id: 501)
    fory.register(AnyObjectDynamicNode.self, id: 502)

    let anyValue: Any = AnyHashableDynamicValue(label: "root-any", score: 1)
    let anyData = try fory.serialize(anyValue)
    let anyDecoded: Any = try fory.deserialize(anyData)
    #expect(anyDecoded as? AnyHashableDynamicValue == AnyHashableDynamicValue(label: "root-any", score: 1))

    let anyObjectValue: AnyObject = AnyObjectDynamicNode(value: 10)
    let anyObjectData = try fory.serialize(anyObjectValue)
    let anyObjectDecoded: AnyObject = try fory.deserialize(anyObjectData)
    #expect((anyObjectDecoded as? AnyObjectDynamicNode)?.value == 10)

    let anySerializerValue: any Serializer = AnyHashableDynamicValue(label: "root-serializer", score: 2)
    let anySerializerData = try fory.serialize(anySerializerValue)
    let anySerializerDecoded: any Serializer = try fory.deserialize(anySerializerData)
    #expect(anySerializerDecoded as? AnyHashableDynamicValue == AnyHashableDynamicValue(label: "root-serializer", score: 2))

    let anyHashableValue = AnyHashable(AnyHashableDynamicKey(id: 3))
    let anyHashableData = try fory.serialize(anyHashableValue)
    let anyHashableDecoded: AnyHashable = try fory.deserialize(anyHashableData)
    #expect(anyHashableDecoded.base as? AnyHashableDynamicKey == AnyHashableDynamicKey(id: 3))

    let anyListValue: [Any] = [
        Int32(4),
        "list",
        AnyHashableDynamicValue(label: "list-obj", score: 5),
    ]
    let anyListData = try fory.serialize(anyListValue)
    let anyListDecoded: [Any] = try fory.deserialize(anyListData)
    #expect(anyListDecoded.count == 3)
    #expect(anyListDecoded[0] as? Int32 == 4)
    #expect(anyListDecoded[1] as? String == "list")
    #expect(anyListDecoded[2] as? AnyHashableDynamicValue == AnyHashableDynamicValue(label: "list-obj", score: 5))

    let stringAnyMapValue: [String: Any] = [
        "a": Int32(6),
        "b": AnyHashableDynamicValue(label: "map-a", score: 7),
    ]
    let stringAnyMapData = try fory.serialize(stringAnyMapValue)
    let stringAnyMapDecoded: [String: Any] = try fory.deserialize(stringAnyMapData)
    #expect(stringAnyMapDecoded["a"] as? Int32 == 6)
    #expect(stringAnyMapDecoded["b"] as? AnyHashableDynamicValue == AnyHashableDynamicValue(label: "map-a", score: 7))

    let int32AnyMapValue: [Int32: Any] = [
        8: "v8",
        9: AnyHashableDynamicValue(label: "map-b", score: 9),
    ]
    let int32AnyMapData = try fory.serialize(int32AnyMapValue)
    let int32AnyMapDecoded: [Int32: Any] = try fory.deserialize(int32AnyMapData)
    #expect(int32AnyMapDecoded[8] as? String == "v8")
    #expect(int32AnyMapDecoded[9] as? AnyHashableDynamicValue == AnyHashableDynamicValue(label: "map-b", score: 9))

    let anyHashableAnyMapValue: [AnyHashable: Any] = [
        AnyHashable("x"): Int32(10),
        AnyHashable(Int32(11)): AnyHashableDynamicValue(label: "map-c", score: 11),
    ]
    let anyHashableAnyMapData = try fory.serialize(anyHashableAnyMapValue)
    let anyHashableAnyMapDecoded: [AnyHashable: Any] = try fory.deserialize(anyHashableAnyMapData)
    #expect(anyHashableAnyMapDecoded[AnyHashable("x")] as? Int32 == 10)
    #expect(
        anyHashableAnyMapDecoded[AnyHashable(Int32(11))] as? AnyHashableDynamicValue
            == AnyHashableDynamicValue(label: "map-c", score: 11)
    )

    let anyHashableSetValue: Set<AnyHashable> = [
        AnyHashable("set"),
        AnyHashable(Int32(12)),
        AnyHashable(AnyHashableDynamicKey(id: 13)),
    ]
    let anyHashableSetData = try fory.serialize(anyHashableSetValue)
    let anyHashableSetDecoded: Set<AnyHashable> = try fory.deserialize(anyHashableSetData)
    #expect(anyHashableSetDecoded.count == 3)
    #expect(anyHashableSetDecoded.contains(AnyHashable("set")))
    #expect(anyHashableSetDecoded.contains(AnyHashable(Int32(12))))
    #expect(anyHashableSetDecoded.contains(AnyHashable(AnyHashableDynamicKey(id: 13))))
}
