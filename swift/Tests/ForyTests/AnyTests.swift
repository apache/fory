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
private struct AnyHashableMapHolder {
    var map: [AnyHashable: Any] = [:]
    var optionalMap: [AnyHashable: Any]? = nil
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
