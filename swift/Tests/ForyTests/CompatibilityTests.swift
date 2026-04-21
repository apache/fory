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
private struct CompatibleProfileV1: Equatable {
    var id: Int32 = 0
    var name: String = ""
}

@ForyObject
private struct CompatibleProfileV2: Equatable {
    var id: Int32 = 0
    var name: String = ""
    var nickname: String = "guest"
    var scores: [Int32] = []
}

@ForyObject
private struct CompatibleNestedProfileV1: Equatable {
    var id: Int32 = 0
    var name: String = ""
}

@ForyObject
private struct CompatibleNestedProfileV2: Equatable {
    var id: Int32 = 0
    var name: String = ""
    var alias: String = ""
    var scores: [Int32] = []
}

@ForyObject
private struct CompatibleNestedArrayV1: Equatable {
    var items: [CompatibleNestedProfileV1] = []
}

@ForyObject
private struct CompatibleNestedArrayV2: Equatable {
    var items: [CompatibleNestedProfileV2] = []
}

@ForyObject
private struct CompatibleNestedMapV1: Equatable {
    var items: [Int32: CompatibleNestedProfileV1] = [:]
}

@ForyObject
private struct CompatibleNestedMapV2: Equatable {
    var items: [Int32: CompatibleNestedProfileV2] = [:]
}

@ForyObject
private struct SchemaVersionV1: Equatable {
    var id: Int32 = 0
    var name: String = ""
}

@ForyObject
private struct SchemaVersionV2: Equatable {
    var id: Int32 = 0
    var alias: String = ""
    var count: Int32 = 0
}

@ForyObject
private final class CompatibleGraphNode {
    var value: Int32 = 0
    var next: CompatibleGraphNode?

    required init() {}

    init(value: Int32, next: CompatibleGraphNode? = nil) {
        self.value = value
        self.next = next
    }
}

@ForyObject
private final class CompatibleGraphContainer {
    var first: CompatibleGraphNode?
    var second: CompatibleGraphNode?
    var items: [CompatibleGraphNode] = []
    var byName: [String: CompatibleGraphNode] = [:]

    required init() {}

    init(
        first: CompatibleGraphNode?,
        second: CompatibleGraphNode?,
        items: [CompatibleGraphNode],
        byName: [String: CompatibleGraphNode]
    ) {
        self.first = first
        self.second = second
        self.items = items
        self.byName = byName
    }
}

@Test
func compatibleModeSupportsAddedAndRemovedFields() throws {
    let writerV1 = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    writerV1.register(CompatibleProfileV1.self, id: 9901)

    let readerV2 = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    readerV2.register(CompatibleProfileV2.self, id: 9901)

    let sourceV1 = CompatibleProfileV1(id: 7, name: "swift")
    let bytesFromV1 = try writerV1.serialize(sourceV1)
    let decodedAsV2: CompatibleProfileV2 = try readerV2.deserialize(bytesFromV1)
    #expect(decodedAsV2.id == sourceV1.id)
    #expect(decodedAsV2.name == sourceV1.name)
    #expect(decodedAsV2.nickname == "")
    #expect(decodedAsV2.scores.isEmpty)

    let writerV2 = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    writerV2.register(CompatibleProfileV2.self, id: 9901)

    let readerV1 = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    readerV1.register(CompatibleProfileV1.self, id: 9901)

    let sourceV2 = CompatibleProfileV2(id: 9, name: "fory", nickname: "macro", scores: [1, 2, 3])
    let bytesFromV2 = try writerV2.serialize(sourceV2)
    let decodedAsV1: CompatibleProfileV1 = try readerV1.deserialize(bytesFromV2)
    #expect(decodedAsV1 == CompatibleProfileV1(id: sourceV2.id, name: sourceV2.name))
}

@Test
func schemaConsistentModeRejectsVersionHashMismatch() throws {
    let writer = Fory(config: .init(xlang: true, trackRef: false, compatible: false, checkClassVersion: true))
    writer.register(SchemaVersionV1.self, id: 9902)

    let reader = Fory(config: .init(xlang: true, trackRef: false, compatible: false, checkClassVersion: true))
    reader.register(SchemaVersionV2.self, id: 9902)

    let bytes = try writer.serialize(SchemaVersionV1(id: 1, name: "shape"))
    do {
        let _: SchemaVersionV2 = try reader.deserialize(bytes)
        #expect(Bool(false))
    } catch {
        #expect("\(error)".contains("class version hash mismatch"))
    }
}

@Test
func compatibleModePreservesSharedAndCircularReferencesForMacroObjects() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: true, compatible: true))
    fory.register(CompatibleGraphNode.self, id: 9903)
    fory.register(CompatibleGraphContainer.self, id: 9904)

    let shared = CompatibleGraphNode(value: 11)
    shared.next = shared
    let value = CompatibleGraphContainer(
        first: shared,
        second: shared,
        items: [shared, shared],
        byName: [
            "left": shared,
            "right": shared
        ]
    )

    let decoded: CompatibleGraphContainer = try fory.deserialize(try fory.serialize(value))

    #expect(decoded.first != nil)
    #expect(decoded.first === decoded.second)
    #expect(decoded.first === decoded.items[0])
    #expect(decoded.items[0] === decoded.items[1])
    #expect(decoded.byName["left"] === decoded.byName["right"])
    #expect(decoded.byName["left"] === decoded.first)
    #expect(decoded.first?.next === decoded.first)
}

@Test
func compatibleNestedArrayEvolves() throws {
    let writerV1 = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    writerV1.register(CompatibleNestedProfileV1.self, id: 9910)
    writerV1.register(CompatibleNestedArrayV1.self, id: 9911)

    let readerV2 = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    readerV2.register(CompatibleNestedProfileV2.self, id: 9910)
    readerV2.register(CompatibleNestedArrayV2.self, id: 9911)

    let sourceV1 = CompatibleNestedArrayV1(
        items: [
            CompatibleNestedProfileV1(id: 1, name: "alpha"),
            CompatibleNestedProfileV1(id: 2, name: "beta")
        ]
    )
    let decodedAsV2: CompatibleNestedArrayV2 = try readerV2.deserialize(try writerV1.serialize(sourceV1))
    #expect(decodedAsV2.items.map(\.id) == [1, 2])
    #expect(decodedAsV2.items.map(\.name) == ["alpha", "beta"])
    #expect(decodedAsV2.items.allSatisfy { $0.alias.isEmpty })
    #expect(decodedAsV2.items.allSatisfy { $0.scores.isEmpty })

    let writerV2 = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    writerV2.register(CompatibleNestedProfileV2.self, id: 9910)
    writerV2.register(CompatibleNestedArrayV2.self, id: 9911)

    let readerV1 = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    readerV1.register(CompatibleNestedProfileV1.self, id: 9910)
    readerV1.register(CompatibleNestedArrayV1.self, id: 9911)

    let sourceV2 = CompatibleNestedArrayV2(
        items: [
            CompatibleNestedProfileV2(id: 3, name: "gamma", alias: "g", scores: [3, 4]),
            CompatibleNestedProfileV2(id: 4, name: "delta", alias: "d", scores: [])
        ]
    )
    let decodedAsV1: CompatibleNestedArrayV1 = try readerV1.deserialize(try writerV2.serialize(sourceV2))
    #expect(decodedAsV1.items == [
        CompatibleNestedProfileV1(id: 3, name: "gamma"),
        CompatibleNestedProfileV1(id: 4, name: "delta")
    ])
}

@Test
func compatibleNestedMapEvolves() throws {
    let writerV1 = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    writerV1.register(CompatibleNestedProfileV1.self, id: 9910)
    writerV1.register(CompatibleNestedMapV1.self, id: 9912)

    let readerV2 = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    readerV2.register(CompatibleNestedProfileV2.self, id: 9910)
    readerV2.register(CompatibleNestedMapV2.self, id: 9912)

    let sourceV1 = CompatibleNestedMapV1(
        items: [
            1: CompatibleNestedProfileV1(id: 10, name: "first"),
            2: CompatibleNestedProfileV1(id: 20, name: "second")
        ]
    )
    let decodedAsV2: CompatibleNestedMapV2 = try readerV2.deserialize(try writerV1.serialize(sourceV1))
    #expect(decodedAsV2.items[1]?.id == 10)
    #expect(decodedAsV2.items[1]?.name == "first")
    #expect(decodedAsV2.items[1]?.alias == "")
    #expect(decodedAsV2.items[1]?.scores.isEmpty == true)
    #expect(decodedAsV2.items[2]?.id == 20)
    #expect(decodedAsV2.items[2]?.name == "second")
    #expect(decodedAsV2.items[2]?.alias == "")
    #expect(decodedAsV2.items[2]?.scores.isEmpty == true)
}

@Test
func compatibleNestedReadsReuseTypeMeta() throws {
    let writerV1 = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    writerV1.register(CompatibleNestedProfileV1.self, id: 9910)
    writerV1.register(CompatibleNestedArrayV1.self, id: 9911)

    let readerV2 = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    readerV2.register(CompatibleNestedProfileV2.self, id: 9910)
    readerV2.register(CompatibleNestedArrayV2.self, id: 9911)

    let first = CompatibleNestedArrayV1(
        items: [
            CompatibleNestedProfileV1(id: 1, name: "alpha"),
            CompatibleNestedProfileV1(id: 2, name: "beta")
        ]
    )
    let second = CompatibleNestedArrayV1(
        items: [
            CompatibleNestedProfileV1(id: 3, name: "gamma"),
            CompatibleNestedProfileV1(id: 4, name: "delta"),
            CompatibleNestedProfileV1(id: 5, name: "epsilon")
        ]
    )

    let decodedFirst: CompatibleNestedArrayV2 = try readerV2.deserialize(try writerV1.serialize(first))
    let decodedSecond: CompatibleNestedArrayV2 = try readerV2.deserialize(try writerV1.serialize(second))

    #expect(decodedFirst.items.map(\.id) == [1, 2])
    #expect(decodedSecond.items.map(\.id) == [3, 4, 5])
    #expect(decodedSecond.items.map(\.name) == ["gamma", "delta", "epsilon"])
    #expect(decodedSecond.items.allSatisfy { $0.alias.isEmpty })
    #expect(decodedSecond.items.allSatisfy { $0.scores.isEmpty })
}
