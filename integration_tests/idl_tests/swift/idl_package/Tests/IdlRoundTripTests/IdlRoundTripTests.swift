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
@testable import IdlGenerated
import XCTest

final class IdlRoundTripTests: XCTestCase {
    func testAddressBookRoundTripCompatible() throws {
        try runIdlMatrixRoundTrip(compatible: true)
    }

    func testAddressBookRoundTripSchemaConsistent() throws {
        try runIdlMatrixRoundTrip(compatible: false)
    }

    func testEvolvingRoundTrip() throws {
        let foryV1 = Fory(xlang: true, trackRef: false, compatible: true)
        try Evolving1.ForyRegistration.register(foryV1)
        let foryV2 = Fory(xlang: true, trackRef: false, compatible: true)
        try Evolving2.ForyRegistration.register(foryV2)

        let messageV1 = Evolving1.EvolvingMessage(id: 1, name: "Alice", city: "NYC")
        let bytes = try foryV1.serialize(messageV1)
        var messageV2: Evolving2.EvolvingMessage = try foryV2.deserialize(bytes)
        XCTAssertEqual(messageV2.id, messageV1.id)
        XCTAssertEqual(messageV2.name, messageV1.name)
        XCTAssertEqual(messageV2.city, messageV1.city)

        messageV2.email = "alice@example.com"
        let roundBytes = try foryV2.serialize(messageV2)
        let roundMessage: Evolving1.EvolvingMessage = try foryV1.deserialize(roundBytes)
        XCTAssertEqual(roundMessage, messageV1)

        let fixedV1 = Evolving1.FixedMessage(id: 10, name: "Bob", score: 90, note: "note")
        let fixedBytes = try foryV1.serialize(fixedV1)

        do {
            let fixedV2: Evolving2.FixedMessage = try foryV2.deserialize(fixedBytes)
            let fixedRoundBytes = try foryV2.serialize(fixedV2)
            let fixedRound: Evolving1.FixedMessage = try foryV1.deserialize(fixedRoundBytes)
            XCTAssertNotEqual(fixedRound, fixedV1)
        } catch {
            // non-evolving type mismatch is allowed to fail
        }
    }

    func testRootToBytesFromBytes() throws {
        let holder = buildRootHolder()
        let payload = try holder.toBytes()
        let decoded = try Root.MultiHolder.fromBytes(payload)
        assertRootHolder(expected: holder, actual: decoded)
    }

    func testToBytesFromBytesHelpers() throws {
        let book = buildAddressBook()
        let bookBytes = try book.toBytes()
        let decodedBook = try Addressbook.AddressBook.fromBytes(bookBytes)
        XCTAssertEqual(decodedBook, book)

        let animal = Addressbook.Animal.dog(
            Addressbook.Dog(name: "Rex", barkVolume: 5)
        )
        let animalBytes = try animal.toBytes()
        let decodedAnimal = try Addressbook.Animal.fromBytes(animalBytes)
        XCTAssertEqual(decodedAnimal, animal)
    }

    private func runIdlMatrixRoundTrip(compatible: Bool) throws {
        let fory = Fory(xlang: true, trackRef: false, compatible: compatible)
        try Addressbook.ForyRegistration.register(fory)
        try AutoId.ForyRegistration.register(fory)
        try Collection.ForyRegistration.register(fory)
        try OptionalTypes.ForyRegistration.register(fory)
        try AnyExample.ForyRegistration.register(fory)
        try MonsterNamespace.ForyRegistration.register(fory)
        try ComplexFbs.ForyRegistration.register(fory)

        let book = buildAddressBook()
        let bookRoundTrip: Addressbook.AddressBook = try roundTrip(fory, value: book)
        XCTAssertEqual(bookRoundTrip, book)
        try roundTripFile(fory, env: "DATA_FILE", expected: book) { expected, actual in
            XCTAssertEqual(actual, expected)
        }

        let envelope = buildAutoIdEnvelope()
        let envelopeRoundTrip: AutoId.Envelope = try roundTrip(fory, value: envelope)
        assertAutoIdEnvelope(expected: envelope, actual: envelopeRoundTrip)
        try roundTripFile(fory, env: "DATA_FILE_AUTO_ID", expected: envelope) { expected, actual in
            self.assertAutoIdEnvelope(expected: expected, actual: actual)
        }

        let wrapper = AutoId.Wrapper.envelope(envelope)
        let wrapperRoundTrip: AutoId.Wrapper = try roundTrip(fory, value: wrapper)
        assertAutoIdWrapper(expected: wrapper, actual: wrapperRoundTrip)

        let primitiveTypes = buildPrimitiveTypes()
        let primitiveRoundTrip: ComplexPb.PrimitiveTypes = try roundTrip(fory, value: primitiveTypes)
        XCTAssertEqual(primitiveRoundTrip, primitiveTypes)
        try roundTripFile(fory, env: "DATA_FILE_PRIMITIVES", expected: primitiveTypes) { expected, actual in
            XCTAssertEqual(actual, expected)
        }

        let collections = buildNumericCollections()
        let collectionsRoundTrip: Collection.NumericCollections = try roundTrip(fory, value: collections)
        XCTAssertEqual(collectionsRoundTrip, collections)
        try roundTripFile(fory, env: "DATA_FILE_COLLECTION", expected: collections) { expected, actual in
            XCTAssertEqual(actual, expected)
        }

        let collectionUnion = Collection.NumericCollectionUnion.int32Values([7, 8, 9])
        let collectionUnionRoundTrip: Collection.NumericCollectionUnion = try roundTrip(fory, value: collectionUnion)
        XCTAssertEqual(collectionUnionRoundTrip, collectionUnion)
        try roundTripFile(fory, env: "DATA_FILE_COLLECTION_UNION", expected: collectionUnion) { expected, actual in
            XCTAssertEqual(actual, expected)
        }

        let collectionsArray = buildNumericCollectionsArray()
        let collectionsArrayRoundTrip: Collection.NumericCollectionsArray = try roundTrip(fory, value: collectionsArray)
        XCTAssertEqual(collectionsArrayRoundTrip, collectionsArray)
        try roundTripFile(fory, env: "DATA_FILE_COLLECTION_ARRAY", expected: collectionsArray) { expected, actual in
            XCTAssertEqual(actual, expected)
        }

        let collectionArrayUnion = Collection.NumericCollectionArrayUnion.uint16Values([1000, 2000, 3000])
        let collectionArrayUnionRoundTrip: Collection.NumericCollectionArrayUnion = try roundTrip(fory, value: collectionArrayUnion)
        XCTAssertEqual(collectionArrayUnionRoundTrip, collectionArrayUnion)
        try roundTripFile(
            fory,
            env: "DATA_FILE_COLLECTION_ARRAY_UNION",
            expected: collectionArrayUnion
        ) { expected, actual in
            XCTAssertEqual(actual, expected)
        }

        let optionalHolder = buildOptionalHolder()
        let optionalRoundTrip: OptionalTypes.OptionalHolder = try roundTrip(fory, value: optionalHolder)
        XCTAssertEqual(optionalRoundTrip, optionalHolder)
        try roundTripFile(fory, env: "DATA_FILE_OPTIONAL_TYPES", expected: optionalHolder) { expected, actual in
            XCTAssertEqual(actual, expected)
        }

        let anyHolder = buildAnyHolder()
        do {
            let anyRoundTrip: AnyExample.AnyHolder = try roundTrip(fory, value: anyHolder)
            assertAnyHolder(expected: anyHolder, actual: anyRoundTrip)
            try roundTripFile(fory, env: "DATA_FILE_ANY", expected: anyHolder) { expected, actual in
                self.assertAnyHolder(expected: expected, actual: actual)
            }
        } catch {
            throw NSError(
                domain: "IdlRoundTripTests",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "any_example roundtrip failed: \(error)"]
            )
        }

        let anyProtoFory = Fory(xlang: true, trackRef: false, compatible: compatible)
        try AnyExamplePb.ForyRegistration.register(anyProtoFory)
        let anyProtoHolder = buildAnyProtoHolder()
        do {
            let anyProtoRoundTrip: AnyExamplePb.AnyHolder = try roundTrip(anyProtoFory, value: anyProtoHolder)
            assertAnyProtoHolder(expected: anyProtoHolder, actual: anyProtoRoundTrip)
            try roundTripFile(anyProtoFory, env: "DATA_FILE_ANY_PROTO", expected: anyProtoHolder) { expected, actual in
                self.assertAnyProtoHolder(expected: expected, actual: actual)
            }
        } catch {
            throw NSError(
                domain: "IdlRoundTripTests",
                code: 2,
                userInfo: [NSLocalizedDescriptionKey: "any_example_pb roundtrip failed: \(error)"]
            )
        }

        let monster = buildMonster()
        let monsterRoundTrip: MonsterNamespace.Monster = try roundTrip(fory, value: monster)
        XCTAssertEqual(monsterRoundTrip, monster)
        try roundTripFile(fory, env: "DATA_FILE_FLATBUFFERS_MONSTER", expected: monster) { expected, actual in
            XCTAssertEqual(actual, expected)
        }

        let container = buildContainer()
        let containerRoundTrip: ComplexFbs.Container = try roundTrip(fory, value: container)
        XCTAssertEqual(containerRoundTrip, container)
        try roundTripFile(fory, env: "DATA_FILE_FLATBUFFERS_TEST2", expected: container) { expected, actual in
            XCTAssertEqual(actual, expected)
        }

        let refFory = Fory(xlang: true, trackRef: true, compatible: compatible)
        try Tree.ForyRegistration.register(refFory)
        try GraphNamespace.ForyRegistration.register(refFory)
        try Root.ForyRegistration.register(refFory)

        let tree = buildTree()
        let treeRoundTrip: Tree.TreeNode = try roundTrip(refFory, value: tree)
        assertTree(treeRoundTrip)
        try roundTripFile(refFory, env: "DATA_FILE_TREE", expected: tree) { _, actual in
            self.assertTree(actual)
        }

        let graph = buildGraph()
        let graphRoundTrip: GraphNamespace.Graph = try roundTrip(refFory, value: graph)
        assertGraph(graphRoundTrip)
        try roundTripFile(refFory, env: "DATA_FILE_GRAPH", expected: graph) { _, actual in
            self.assertGraph(actual)
        }

        let rootHolder = buildRootHolder()
        let rootRoundTrip: Root.MultiHolder = try roundTrip(refFory, value: rootHolder)
        assertRootHolder(expected: rootHolder, actual: rootRoundTrip)
        try roundTripFile(refFory, env: "DATA_FILE_ROOT", expected: rootHolder) { expected, actual in
            self.assertRootHolder(expected: expected, actual: actual)
        }
    }

    private func roundTrip<T: Serializer>(_ fory: Fory, value: T) throws -> T {
        let payload = try fory.serialize(value)
        return try fory.deserialize(payload)
    }

    private func roundTripFile<T: Serializer>(
        _ fory: Fory,
        env: String,
        expected: T,
        assertRoundTrip: (T, T) throws -> Void
    ) throws {
        guard let path = ProcessInfo.processInfo.environment[env], !path.isEmpty else {
            return
        }
        let url = URL(fileURLWithPath: path)
        let fileManager = FileManager.default
        if !fileManager.fileExists(atPath: path) {
            fileManager.createFile(atPath: path, contents: nil)
        }

        let inputData: Data
        if let existing = try? Data(contentsOf: url), !existing.isEmpty {
            inputData = existing
        } else {
            inputData = try fory.serialize(expected)
            try inputData.write(to: url)
        }

        let decoded: T = try fory.deserialize(inputData)
        try assertRoundTrip(expected, decoded)
        let output = try fory.serialize(decoded)
        try output.write(to: url)
    }

    private func buildAddressBook() -> Addressbook.AddressBook {
        let mobile = Addressbook.Person.PhoneNumber(number: "555-0100", phoneType: .mobile)
        let work = Addressbook.Person.PhoneNumber(number: "555-0111", phoneType: .work)

        var pet = Addressbook.Animal.dog(Addressbook.Dog(name: "Rex", barkVolume: 5))
        pet = .cat(Addressbook.Cat(name: "Mimi", lives: 9))

        let person = Addressbook.Person(
            name: "Alice",
            id: 123,
            email: "alice@example.com",
            tags: ["friend", "colleague"],
            scores: ["math": 100, "science": 98],
            salary: 120000.5,
            phones: [mobile, work],
            pet: pet
        )

        return Addressbook.AddressBook(
            people: [person],
            peopleByName: [person.name: person]
        )
    }

    private func buildAutoIdEnvelope() -> AutoId.Envelope {
        let payload = AutoId.Envelope.Payload(value: 42)
        return AutoId.Envelope(
            id: "env-1",
            payload: payload,
            detail: .payload(payload),
            status: .ok
        )
    }

    private func assertAutoIdEnvelope(expected: AutoId.Envelope, actual: AutoId.Envelope) {
        XCTAssertEqual(actual.id, expected.id)
        XCTAssertEqual(actual.status, expected.status)
        XCTAssertEqual(actual.payload?.value, expected.payload?.value)

        switch (expected.detail, actual.detail) {
        case let (.payload(left), .payload(right)):
            XCTAssertEqual(right, left)
        case let (.note(left), .note(right)):
            XCTAssertEqual(right, left)
        default:
            XCTFail("AutoId.Envelope.Detail case mismatch")
        }
    }

    private func assertAutoIdWrapper(expected: AutoId.Wrapper, actual: AutoId.Wrapper) {
        switch (expected, actual) {
        case let (.envelope(expectedEnvelope), .envelope(actualEnvelope)):
            assertAutoIdEnvelope(expected: expectedEnvelope, actual: actualEnvelope)
        case let (.raw(expectedRaw), .raw(actualRaw)):
            XCTAssertEqual(actualRaw, expectedRaw)
        default:
            XCTFail("AutoId.Wrapper case mismatch")
        }
    }

    private func buildPrimitiveTypes() -> ComplexPb.PrimitiveTypes {
        var contact = ComplexPb.PrimitiveTypes.Contact.email("alice@example.com")
        contact = .phone(12345)

        return ComplexPb.PrimitiveTypes(
            boolValue: true,
            int8Value: 12,
            int16Value: 1234,
            int32Value: -123456,
            varint32Value: -12345,
            int64Value: -123456789,
            varint64Value: -987654321,
            taggedInt64Value: 123456789,
            uint8Value: 200,
            uint16Value: 60000,
            uint32Value: 1234567890,
            varUint32Value: 1234567890,
            uint64Value: 9876543210,
            varUint64Value: 12345678901,
            taggedUint64Value: 2222222222,
            float32Value: 2.5,
            float64Value: 3.5,
            contact: contact
        )
    }

    private func buildNumericCollections() -> Collection.NumericCollections {
        Collection.NumericCollections(
            int8Values: [1, -2, 3],
            int16Values: [100, -200, 300],
            int32Values: [1000, -2000, 3000],
            int64Values: [10000, -20000, 30000],
            uint8Values: [200, 250],
            uint16Values: [50000, 60000],
            uint32Values: [2000000000, 2100000000],
            uint64Values: [9000000000, 12000000000],
            float32Values: [1.5, 2.5],
            float64Values: [3.5, 4.5]
        )
    }

    private func buildNumericCollectionsArray() -> Collection.NumericCollectionsArray {
        Collection.NumericCollectionsArray(
            int8Values: [1, -2, 3],
            int16Values: [100, -200, 300],
            int32Values: [1000, -2000, 3000],
            int64Values: [10000, -20000, 30000],
            uint8Values: [200, 250],
            uint16Values: [50000, 60000],
            uint32Values: [2000000000, 2100000000],
            uint64Values: [9000000000, 12000000000],
            float32Values: [1.5, 2.5],
            float64Values: [3.5, 4.5]
        )
    }

    private func buildOptionalHolder() -> OptionalTypes.OptionalHolder {
        let allTypes = OptionalTypes.AllOptionalTypes(
            boolValue: true,
            int8Value: 12,
            int16Value: 1234,
            int32Value: -123456,
            fixedInt32Value: -123456,
            varint32Value: -12345,
            int64Value: -123456789,
            fixedInt64Value: -123456789,
            varint64Value: -987654321,
            taggedInt64Value: 123456789,
            uint8Value: 200,
            uint16Value: 60000,
            uint32Value: 1234567890,
            fixedUint32Value: 1234567890,
            varUint32Value: 1234567890,
            uint64Value: 9876543210,
            fixedUint64Value: 9876543210,
            varUint64Value: 12345678901,
            taggedUint64Value: 2222222222,
            float32Value: 2.5,
            float64Value: 3.5,
            stringValue: "optional",
            bytesValue: Data([1, 2, 3]),
            dateValue: ForyDate(daysSinceEpoch: 19724),
            timestampValue: ForyTimestamp(seconds: 1704164645, nanos: 0),
            int32List: [1, 2, 3],
            stringList: ["alpha", "beta"],
            int64Map: ["alpha": 10, "beta": 20]
        )
        return OptionalTypes.OptionalHolder(
            allTypes: allTypes,
            choice: .note("optional")
        )
    }

    private func buildAnyHolder() -> AnyExample.AnyHolder {
        AnyExample.AnyHolder(
            boolValue: true,
            stringValue: "hello",
            dateValue: ForyDate(daysSinceEpoch: 19724),
            timestampValue: Date(timeIntervalSince1970: 1704164645),
            messageValue: AnyExample.AnyInner(name: "inner"),
            unionValue: AnyExample.AnyUnion.text("union"),
            listValue: ["alpha", "beta"],
            mapValue: ["k1": "v1", "k2": "v2"]
        )
    }

    private func buildAnyProtoHolder() -> AnyExamplePb.AnyHolder {
        AnyExamplePb.AnyHolder(
            boolValue: true,
            stringValue: "hello",
            dateValue: ForyDate(daysSinceEpoch: 19724),
            timestampValue: Date(timeIntervalSince1970: 1704164645),
            messageValue: AnyExamplePb.AnyInner(name: "inner"),
            unionValue: AnyExamplePb.AnyUnion(kind: .text("proto-union")),
            listValue: ["alpha", "beta"],
            mapValue: ["k1": "v1", "k2": "v2"]
        )
    }

    private func assertAnyHolder(expected: AnyExample.AnyHolder, actual: AnyExample.AnyHolder) {
        XCTAssertEqual(actual.boolValue as? Bool, expected.boolValue as? Bool)
        XCTAssertEqual(actual.stringValue as? String, expected.stringValue as? String)
        XCTAssertEqual(actual.dateValue as? ForyDate, expected.dateValue as? ForyDate)
        XCTAssertEqual((actual.timestampValue as? Date)?.timeIntervalSince1970, (expected.timestampValue as? Date)?.timeIntervalSince1970)
        XCTAssertEqual(actual.messageValue as? AnyExample.AnyInner, expected.messageValue as? AnyExample.AnyInner)
        XCTAssertEqual(actual.unionValue as? AnyExample.AnyUnion, expected.unionValue as? AnyExample.AnyUnion)
        XCTAssertEqual(normalizeStringList(actual.listValue), normalizeStringList(expected.listValue))
        XCTAssertEqual(normalizeStringMap(actual.mapValue), normalizeStringMap(expected.mapValue))
    }

    private func assertAnyProtoHolder(expected: AnyExamplePb.AnyHolder, actual: AnyExamplePb.AnyHolder) {
        XCTAssertEqual(actual.boolValue as? Bool, expected.boolValue as? Bool)
        XCTAssertEqual(actual.stringValue as? String, expected.stringValue as? String)
        XCTAssertEqual(actual.dateValue as? ForyDate, expected.dateValue as? ForyDate)
        XCTAssertEqual((actual.timestampValue as? Date)?.timeIntervalSince1970, (expected.timestampValue as? Date)?.timeIntervalSince1970)
        XCTAssertEqual(actual.messageValue as? AnyExamplePb.AnyInner, expected.messageValue as? AnyExamplePb.AnyInner)
        XCTAssertEqual(actual.unionValue as? AnyExamplePb.AnyUnion, expected.unionValue as? AnyExamplePb.AnyUnion)
        XCTAssertEqual(normalizeStringList(actual.listValue), normalizeStringList(expected.listValue))
        XCTAssertEqual(normalizeStringMap(actual.mapValue), normalizeStringMap(expected.mapValue))
    }

    private func normalizeStringList(_ value: Any) -> [String]? {
        if let list = value as? [String] {
            return list
        }
        guard let list = value as? [Any] else {
            return nil
        }
        return list.compactMap { $0 as? String }.count == list.count ? list.compactMap { $0 as? String } : nil
    }

    private func normalizeStringMap(_ value: Any) -> [String: String]? {
        if let map = value as? [String: String] {
            return map
        }
        if let map = value as? [String: Any] {
            var normalized: [String: String] = [:]
            for (key, item) in map {
                guard let stringValue = item as? String else {
                    return nil
                }
                normalized[key] = stringValue
            }
            return normalized
        }
        if let map = value as? [AnyHashable: Any] {
            var normalized: [String: String] = [:]
            for (key, item) in map {
                guard let stringKey = key.base as? String, let stringValue = item as? String else {
                    return nil
                }
                normalized[stringKey] = stringValue
            }
            return normalized
        }
        return nil
    }

    private func buildMonster() -> MonsterNamespace.Monster {
        MonsterNamespace.Monster(
            pos: MonsterNamespace.Vec3(x: 1.0, y: 2.0, z: 3.0),
            mana: 200,
            hp: 80,
            name: "Orc",
            friendly: true,
            inventory: [1, 2, 3],
            color: .blue
        )
    }

    private func buildContainer() -> ComplexFbs.Container {
        let scalars = ComplexFbs.ScalarPack(
            b: -8,
            ub: 200,
            s: -1234,
            us: 40000,
            i: -123456,
            ui: 123456,
            l: -123456789,
            ul: 987654321,
            f: 1.5,
            d: 2.5,
            ok: true
        )
        return ComplexFbs.Container(
            id: 9876543210,
            status: .started,
            bytes: [1, 2, 3],
            numbers: [10, 20, 30],
            scalars: scalars,
            names: ["alpha", "beta"],
            flags: [true, false],
            payload: .metric(ComplexFbs.Metric(value: 42.0))
        )
    }

    private func buildTree() -> Tree.TreeNode {
        let childA = Tree.TreeNode()
        childA.id = "child-a"
        childA.name = "child-a"

        let childB = Tree.TreeNode()
        childB.id = "child-b"
        childB.name = "child-b"

        childA.parent = childB
        childB.parent = childA

        let root = Tree.TreeNode()
        root.id = "root"
        root.name = "root"
        root.children = [childA, childA, childB]
        return root
    }

    private func assertTree(_ root: Tree.TreeNode) {
        XCTAssertEqual(root.children.count, 3)
        XCTAssertTrue(root.children[0] === root.children[1])
        XCTAssertFalse(root.children[0] === root.children[2])
        XCTAssertTrue(root.children[0].parent === root.children[2])
        XCTAssertTrue(root.children[2].parent === root.children[0])
    }

    private func buildGraph() -> GraphNamespace.Graph {
        let nodeA = GraphNamespace.Node()
        nodeA.id = "node-a"

        let nodeB = GraphNamespace.Node()
        nodeB.id = "node-b"

        let edge = GraphNamespace.Edge()
        edge.id = "edge-1"
        edge.weight = 1.5
        edge.from = nodeA
        edge.to = nodeB

        nodeA.outEdges = [edge]
        nodeA.inEdges = [edge]
        nodeB.inEdges = [edge]
        let graph = GraphNamespace.Graph()
        graph.nodes = [nodeA, nodeB]
        graph.edges = [edge]
        return graph
    }

    private func assertGraph(_ value: GraphNamespace.Graph) {
        XCTAssertEqual(value.nodes.count, 2)
        XCTAssertEqual(value.edges.count, 1)

        let nodeA = value.nodes[0]
        let nodeB = value.nodes[1]
        let edge = value.edges[0]

        XCTAssertTrue(nodeA.outEdges[0] === nodeA.inEdges[0])
        XCTAssertTrue(nodeA.outEdges[0] === edge)
        XCTAssertTrue(edge.from === nodeA)
        XCTAssertTrue(edge.to === nodeB)
    }

    private func buildRootHolder() -> Root.MultiHolder {
        let owner = Addressbook.Person(
            name: "Alice",
            id: 123,
            email: "",
            tags: [],
            scores: [:],
            salary: 0,
            phones: [],
            pet: .dog(Addressbook.Dog(name: "Rex", barkVolume: 5))
        )
        let book = Addressbook.AddressBook(
            people: [owner],
            peopleByName: [owner.name: owner]
        )
        let rootNode = Tree.TreeNode()
        rootNode.id = "root"
        rootNode.name = "root"
        rootNode.children = []
        return Root.MultiHolder(
            book: book,
            root: rootNode,
            owner: owner
        )
    }

    private func assertRootHolder(expected: Root.MultiHolder, actual: Root.MultiHolder) {
        XCTAssertEqual(actual.book, expected.book)
        XCTAssertEqual(actual.owner, expected.owner)

        if let expectedRoot = expected.root, let actualRoot = actual.root {
            XCTAssertEqual(actualRoot.id, expectedRoot.id)
            XCTAssertEqual(actualRoot.name, expectedRoot.name)
            XCTAssertEqual(actualRoot.children.count, expectedRoot.children.count)
        } else {
            XCTAssertNil(actual.root)
            XCTAssertNil(expected.root)
        }
    }
}
