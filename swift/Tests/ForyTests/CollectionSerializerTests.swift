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
private final class RefKeyNode: Hashable {
    var id: Int32 = 0

    required init() {}

    init(id: Int32) {
        self.id = id
    }

    static func == (lhs: RefKeyNode, rhs: RefKeyNode) -> Bool {
        lhs === rhs
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(ObjectIdentifier(self))
    }
}

@ForyStruct
private struct RefKeyHolder {
    var key: RefKeyNode = .init()
    var map: [RefKeyNode: Int32] = [:]
}

@ForyStruct
private struct RefKeyValueHolder {
    var map: [RefKeyNode: RefKeyNode] = [:]
}

@ForyStruct
private struct RefKeyChunkHolder {
    var keys: [RefKeyNode] = []
    var map: [RefKeyNode: Int32] = [:]
}

@ForyStruct
private struct AnnotatedFieldCodecHolder: Equatable {
    @ForyField(encoding: .fixed)
    var id: UInt32 = 0

    @ListField(element: .encoding(.fixed))
    var values: [Int32?] = []

    @ListField(element: .encoding(.fixed))
    var packedValues: [Int32] = []

    @ListField(element: .uint64(encoding: .fixed))
    var packedUInt64Values: [UInt64] = []

    @ArrayField(element: .int32())
    var denseValues: [Int32] = []

  @ForyField(type: .array(element: .uint64()))
  var denseUInt64Values: [UInt64] = []

    @SetField(element: .encoding(.fixed))
    var fixedSet: Set<Int32?> = []

    @SetField(element: .encoding(.fixed))
    var fixedNonNullSet: Set<Int32> = []

    @MapField(key: .encoding(.fixed), value: .encoding(.fixed))
    var data: [Int32?: Int32?] = [:]
}

@ForyStruct
private struct DeepAnnotatedFieldCodecHolder: Equatable {
    @MapField(
        value: .list(
            element: .map(
                key: .encoding(.fixed),
                value: .list(element: .encoding(.fixed))
            )
        )
    )
    var data: [String: [[Int32: [UInt32]]]] = [:]
}

private typealias MapAlias = [String: [Int32?]]

@ForyStruct
private struct AliasAnnotatedFieldCodecHolder: Equatable {
    @ForyField(type: .map(
        key: .string,
        value: .list(element: .int32(nullable: true, encoding: .fixed))
    ))
    var data: MapAlias = [:]
}

@Test
func primitiveArraysDefaultToListTypeIDsAndRoundTrip() throws {
    #expect([Bool].staticTypeId == .list)
    #expect([Int8].staticTypeId == .list)
    #expect([Int16].staticTypeId == .list)
    #expect([Int32].staticTypeId == .list)
    #expect([Int64].staticTypeId == .list)
    #expect([UInt8].staticTypeId == .list)
    #expect([UInt16].staticTypeId == .list)
    #expect([UInt32].staticTypeId == .list)
    #expect([UInt64].staticTypeId == .list)
    #expect([Float16].staticTypeId == .list)
    #expect([BFloat16].staticTypeId == .list)
    #expect([Float].staticTypeId == .list)
    #expect([Double].staticTypeId == .list)

    let fory = Fory()

    let bools = [true, false, true]
    let int8s: [Int8] = [-1, 0, 127]
    let int16s: [Int16] = [Int16.min, -1, Int16.max]
    let int32s: [Int32] = [Int32.min, 0, Int32.max]
    let int64s: [Int64] = [Int64.min, 0, Int64.max]
    let uint8s: [UInt8] = [0, 1, 255]
    let uint16s: [UInt16] = [0, 1, UInt16.max]
    let uint32s: [UInt32] = [0, 1, UInt32.max]
    let uint64s: [UInt64] = [0, 1, UInt64.max]
    let float16s: [Float16] = [Float16(0), Float16(-2.5), Float16(7.25)]
    let bfloat16s: [BFloat16] = [.init(rawValue: 0x0000), .init(rawValue: 0x3F80), .init(rawValue: 0xBF80)]
    let floats: [Float] = [-1.5, 0, 3.25]
    let doubles: [Double] = [-10.5, 0, 9.75]

    let decodedBools: [Bool] = try fory.deserialize(try fory.serialize(bools))
    let decodedInt8s: [Int8] = try fory.deserialize(try fory.serialize(int8s))
    let decodedInt16s: [Int16] = try fory.deserialize(try fory.serialize(int16s))
    let decodedInt32s: [Int32] = try fory.deserialize(try fory.serialize(int32s))
    let decodedInt64s: [Int64] = try fory.deserialize(try fory.serialize(int64s))
    let decodedUInt8s: [UInt8] = try fory.deserialize(try fory.serialize(uint8s))
    let decodedUInt16s: [UInt16] = try fory.deserialize(try fory.serialize(uint16s))
    let decodedUInt32s: [UInt32] = try fory.deserialize(try fory.serialize(uint32s))
    let decodedUInt64s: [UInt64] = try fory.deserialize(try fory.serialize(uint64s))
    let decodedFloat16s: [Float16] = try fory.deserialize(try fory.serialize(float16s))
    let decodedBFloat16s: [BFloat16] = try fory.deserialize(try fory.serialize(bfloat16s))
    let decodedFloats: [Float] = try fory.deserialize(try fory.serialize(floats))
    let decodedDoubles: [Double] = try fory.deserialize(try fory.serialize(doubles))

    #expect(decodedBools == bools)
    #expect(decodedInt8s == int8s)
    #expect(decodedInt16s == int16s)
    #expect(decodedInt32s == int32s)
    #expect(decodedInt64s == int64s)
    #expect(decodedUInt8s == uint8s)
    #expect(decodedUInt16s == uint16s)
    #expect(decodedUInt32s == uint32s)
    #expect(decodedUInt64s == uint64s)
    #expect(decodedFloat16s.map(\.bitPattern) == float16s.map(\.bitPattern))
    #expect(decodedBFloat16s == bfloat16s)
    #expect(decodedFloats == floats)
    #expect(decodedDoubles == doubles)
}

@Test
func floatingPointArraysPreserveBits() throws {
    let fory = Fory()

    let float16s: [Float16] = [
        .init(bitPattern: 0x0000),
        .init(bitPattern: 0x8000),
        .init(bitPattern: 0x7C00),
        .init(bitPattern: 0xFC00),
        .init(bitPattern: 0x0001),
        .init(bitPattern: 0x7E21)
    ]
    let bfloat16s: [BFloat16] = [
        .init(rawValue: 0x0000),
        .init(rawValue: 0x8000),
        .init(rawValue: 0x7F80),
        .init(rawValue: 0xFF80),
        .init(rawValue: 0x0001),
        .init(rawValue: 0x7FC1)
    ]
    let floats: [Float] = [
        0.0,
        -0.0,
        .infinity,
        -.infinity,
        .leastNonzeroMagnitude,
        .greatestFiniteMagnitude,
        Float(bitPattern: 0x7FC0_1234)
    ]
    let doubles: [Double] = [
        0.0,
        -0.0,
        .infinity,
        -.infinity,
        .leastNonzeroMagnitude,
        .greatestFiniteMagnitude,
        Double(bitPattern: 0x7FF8_0000_0000_1234)
    ]

    let decodedFloat16s: [Float16] = try fory.deserialize(try fory.serialize(float16s))
    let decodedBFloat16s: [BFloat16] = try fory.deserialize(try fory.serialize(bfloat16s))
    let decodedFloats: [Float] = try fory.deserialize(try fory.serialize(floats))
    let decodedDoubles: [Double] = try fory.deserialize(try fory.serialize(doubles))

    #expect(decodedFloat16s.map(\.bitPattern) == float16s.map(\.bitPattern))
    #expect(decodedBFloat16s.map(\.rawValue) == bfloat16s.map(\.rawValue))
    #expect(decodedFloats.map(\.bitPattern) == floats.map(\.bitPattern))
    #expect(decodedDoubles.map(\.bitPattern) == doubles.map(\.bitPattern))
}

@Test
func plainUInt8ArrayUsesListWireType() throws {
    let payload: [UInt8] = [0x00, 0x01, 0x7F, 0xFF]

    let schemaConsistent = Fory(config: .init(xlang: true, trackRef: false, compatible: false))
    let schemaBytes = try schemaConsistent.serialize(payload)
    #expect(Array(schemaBytes)[2] == UInt8(TypeId.list.rawValue))

    let compatible = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    let compatibleBytes = try compatible.serialize(payload)
    #expect(Array(compatibleBytes)[2] == UInt8(TypeId.list.rawValue))

    let decodedArray: [UInt8] = try compatible.deserialize(compatibleBytes)
    #expect(decodedArray == payload)
}

@Test
func nestedCollectionsAndNullabilityRoundTrip() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: true, compatible: true))

    let nested: [[String?]] = [
        ["alpha", nil],
        [],
        ["beta", "gamma"]
    ]
    let decodedNested: [[String?]] = try fory.deserialize(try fory.serialize(nested))
    #expect(decodedNested == nested)

    let set: Set<UInt16> = [0, 42, UInt16.max]
    let decodedSet: Set<UInt16> = try fory.deserialize(try fory.serialize(set))
    #expect(decodedSet == set)

    let map: [Int32?: [String?]?] = [
        1: ["x", nil, "z"],
        nil: nil,
        3: []
    ]
    let decodedMap: [Int32?: [String?]?] = try fory.deserialize(try fory.serialize(map))
    #expect(decodedMap == map)
}

@Test
func annotatedNestedFieldCodecsRoundTrip() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(AnnotatedFieldCodecHolder.self, id: 9601)
    fory.register(DeepAnnotatedFieldCodecHolder.self, id: 9602)
    fory.register(AliasAnnotatedFieldCodecHolder.self, id: 9603)

    let value = AnnotatedFieldCodecHolder(
        id: UInt32.max,
        values: [1, nil, -2, Int32.max],
        packedValues: [1, -3, 100_000],
        packedUInt64Values: [1, UInt64.max],
        denseValues: [4, -5, 6],
        denseUInt64Values: [8, UInt64.max],
        fixedSet: [nil, -7, 42],
        fixedNonNullSet: [6, -7],
        data: [
            nil: nil,
            1: -1,
            Int32.max: Int32.min
        ]
    )
    let decoded: AnnotatedFieldCodecHolder = try fory.deserialize(try fory.serialize(value))
    #expect(decoded == value)

    let deep = DeepAnnotatedFieldCodecHolder(
        data: [
            "left": [
                [1: [UInt32.max, 7]],
                [2: [3, 4]]
            ],
            "empty": []
        ]
    )
    let decodedDeep: DeepAnnotatedFieldCodecHolder = try fory.deserialize(try fory.serialize(deep))
    #expect(decodedDeep == deep)

    let alias = AliasAnnotatedFieldCodecHolder(data: ["numbers": [1, nil, -3]])
    let decodedAlias: AliasAnnotatedFieldCodecHolder = try fory.deserialize(try fory.serialize(alias))
    #expect(decodedAlias == alias)
}

@Test
func annotatedNestedFieldCodecsEmitRecursiveMetadata() {
    let fields = Dictionary(
        uniqueKeysWithValues: AnnotatedFieldCodecHolder.foryFieldsInfo(trackRef: false).map {
            ($0.fieldName, $0.fieldType)
        }
    )
    #expect(fields["id"] == UInt32FixedCodec.fieldType(nullable: false, trackRef: false))
    #expect(
        fields["values"] ==
            ListFieldCodec<OptionalFieldCodec<Int32FixedCodec>>.fieldType(nullable: false, trackRef: false)
    )
    #expect(
        fields["packedValues"] ==
            ListFieldCodec<Int32FixedCodec>.fieldType(nullable: false, trackRef: false)
    )
    #expect(
        fields["packedUInt64Values"] ==
            ListFieldCodec<UInt64FixedCodec>.fieldType(nullable: false, trackRef: false)
    )
    #expect(
        fields["denseValues"] ==
            TypeMeta.FieldType(typeID: TypeId.int32Array.rawValue, nullable: false, trackRef: false)
    )
    #expect(
        fields["denseUInt64Values"] ==
            TypeMeta.FieldType(typeID: TypeId.uint64Array.rawValue, nullable: false, trackRef: false)
    )
    #expect(
        fields["fixedSet"] ==
            SetFieldCodec<OptionalFieldCodec<Int32FixedCodec>>.fieldType(nullable: false, trackRef: false)
    )
    #expect(
        fields["fixedNonNullSet"] ==
            SetFieldCodec<Int32FixedCodec>.fieldType(nullable: false, trackRef: false)
    )
    #expect(fields["fixedNonNullSet"]?.typeID == TypeId.set.rawValue)
    #expect(
        fields["data"] ==
            MapFieldCodec<
                OptionalFieldCodec<Int32FixedCodec>,
                OptionalFieldCodec<Int32FixedCodec>
            >.fieldType(nullable: false, trackRef: false)
    )

    let deepFields = Dictionary(
        uniqueKeysWithValues: DeepAnnotatedFieldCodecHolder.foryFieldsInfo(trackRef: false).map {
            ($0.fieldName, $0.fieldType)
        }
    )
    #expect(
        deepFields["data"] ==
            MapFieldCodec<
                StringCodec,
                ListFieldCodec<
                    MapFieldCodec<
                        Int32FixedCodec,
                        ListFieldCodec<UInt32FixedCodec>
                    >
                >
            >.fieldType(nullable: false, trackRef: false)
    )

    let aliasFields = Dictionary(
        uniqueKeysWithValues: AliasAnnotatedFieldCodecHolder.foryFieldsInfo(trackRef: false).map {
            ($0.fieldName, $0.fieldType)
        }
    )
    #expect(
        aliasFields["data"] ==
            MapFieldCodec<
                StringCodec,
                ListFieldCodec<OptionalFieldCodec<Int32FixedCodec>>
            >.fieldType(nullable: false, trackRef: false)
    )
}

@Test
func mapRefKeysTrackIdentity() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: true, compatible: true))
    fory.register(RefKeyNode.self, id: 9501)
    fory.register(RefKeyHolder.self, id: 9502)

    let sharedKey = RefKeyNode(id: 7)
    let value = RefKeyHolder(key: sharedKey, map: [sharedKey: 99])

    let decoded: RefKeyHolder = try fory.deserialize(try fory.serialize(value))
    let pair = decoded.map.first

    #expect(pair != nil)
    if let pair {
        #expect(decoded.key === pair.key)
        #expect(pair.value == 99)
    }
}

@Test
func mapRefKeyAndValueShareIdentity() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: true, compatible: true))
    fory.register(RefKeyNode.self, id: 9501)
    fory.register(RefKeyValueHolder.self, id: 9503)

    let shared = RefKeyNode(id: 11)
    let value = RefKeyValueHolder(map: [shared: shared])

    let decoded: RefKeyValueHolder = try fory.deserialize(try fory.serialize(value))
    let pair = decoded.map.first

    #expect(pair != nil)
    if let pair {
        #expect(pair.key === pair.value)
    }
}

@Test
func mapRefKeysChunkAcross255Entries() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: true, compatible: true))
    fory.register(RefKeyNode.self, id: 9501)
    fory.register(RefKeyChunkHolder.self, id: 9504)

    var keys: [RefKeyNode] = []
    var map: [RefKeyNode: Int32] = [:]
    for index in 0..<300 {
        let key = RefKeyNode(id: Int32(index))
        keys.append(key)
        map[key] = Int32(index * 2)
    }
    let value = RefKeyChunkHolder(keys: keys, map: map)

    let decoded: RefKeyChunkHolder = try fory.deserialize(try fory.serialize(value))
    #expect(decoded.keys.count == 300)
    #expect(decoded.map.count == 300)

    var decodedMapKeysByID: [Int32: RefKeyNode] = [:]
    for key in decoded.map.keys {
        decodedMapKeysByID[key.id] = key
    }

    for key in decoded.keys {
        #expect(decodedMapKeysByID[key.id] === key)
        #expect(decoded.map[decodedMapKeysByID[key.id]!] == key.id * 2)
    }
}

@Test
func collectionSerializersRejectMalformedPrimitivePayloads() throws {
    let int16Buffer = ByteBuffer()
    int16Buffer.writeVarUInt32(3)
    int16Buffer.writeBytes([0x01, 0x02, 0x03])
    let int16Context = ReadContext(
        buffer: int16Buffer,
        typeResolver: TypeResolver(trackRef: false),
        trackRef: false
    )
    do {
        let _: [Int16] = try ArrayFieldCodec<Int16Codec>.readPayload(int16Context)
        #expect(Bool(false))
    } catch {
        #expect("\(error)".contains("payload size mismatch"))
    }

    let float64Buffer = ByteBuffer()
    float64Buffer.writeVarUInt32(4)
    float64Buffer.writeBytes([0x01, 0x02, 0x03, 0x04])
    let float64Context = ReadContext(
        buffer: float64Buffer,
        typeResolver: TypeResolver(trackRef: false),
        trackRef: false
    )
    do {
        let _: [Double] = try ArrayFieldCodec<DoubleCodec>.readPayload(float64Context)
        #expect(Bool(false))
    } catch {
        #expect("\(error)".contains("payload size mismatch"))
    }
}
