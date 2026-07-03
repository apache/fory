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
private final class BudgetNode {
    var id: Int32 = 0

    required init() {}

    init(id: Int32) {
        self.id = id
    }
}

@ForyStruct
private struct BudgetSiblings {
    var left: [BudgetNode] = []
    var right: [BudgetNode] = []
}

@ForyStruct
private struct BudgetDenseHolder: Equatable {
    var text: String = ""
    var data: Data = Data()
    @ArrayField(element: .int32())
    var dense: [Int32] = []
}

@ForyStruct
private struct BudgetValue: Equatable {
    var id: Int32 = 0
    var enabled: Bool = false
}

@ForyStruct
private struct BudgetValueHolder: Equatable {
    var value: BudgetValue = BudgetValue()
}

@ForyStruct
private struct BudgetValueCompatWriter {
    var value: BudgetValue = BudgetValue()
    var extra: Int32 = 0
}

@ForyStruct
private struct BudgetValueCompatReader: Equatable {
    var value: BudgetValue = BudgetValue()
}

@ForyStruct
private struct BudgetNestedValueWriter {
    var id: Int32 = 0
    var enabled: Bool = false
    var extra: Int32 = 0
}

@ForyStruct
private struct BudgetNestedValueReader: Equatable {
    var id: Int32 = 0
    var enabled: Bool = false
}

@ForyStruct
private struct BudgetNestedHolderWriter {
    var value: BudgetNestedValueWriter = BudgetNestedValueWriter()
    var extra: Int32 = 0
}

@ForyStruct
private struct BudgetNestedHolderReader: Equatable {
    var value: BudgetNestedValueReader = BudgetNestedValueReader()
}

@ForyStruct
private struct BudgetListDenseWriter {
    var dense: [Int32] = []
}

@ForyStruct
private struct BudgetListDenseReader: Equatable {
    @ArrayField(element: .int32())
    var dense: [Int32] = []
}

private let defaultGraphMemoryBytes: Int64 = 128 * 1024 * 1024

private func makeBudgetFory(maxGraphMemoryBytes: Int64 = defaultGraphMemoryBytes) -> Fory {
    let fory = Fory(
        config: .init(
            trackRef: false,
            compatible: false,
            maxGraphMemoryBytes: maxGraphMemoryBytes
        ))
    fory.register(BudgetNode.self, id: 9801)
    fory.register(BudgetSiblings.self, id: 9802)
    fory.register(BudgetDenseHolder.self, id: 9803)
    fory.register(BudgetValue.self, id: 9804)
    fory.register(BudgetValueHolder.self, id: 9805)
    return fory
}

private func makeCompatibleBudgetFory(maxGraphMemoryBytes: Int64 = defaultGraphMemoryBytes) -> Fory {
    Fory(
        config: .init(
            trackRef: false,
            compatible: true,
            maxGraphMemoryBytes: maxGraphMemoryBytes
        ))
}

private let testReferenceBytes = 4
private let budgetNodeGraphBytes = (2 * testReferenceBytes) + 4

private func elementBytes<Element: Serializer>(_ type: Element.Type) -> Int {
    type.isRefType ? testReferenceBytes : max(1, MemoryLayout<Element>.stride)
}

private func ownerBytes<T>(_ type: T.Type) -> Int {
    max(1, MemoryLayout<T>.stride)
}

private func arrayBudget<Element: Serializer>(_ type: Element.Type, count: Int) -> Int {
    count * elementBytes(type)
}

private func listBudget<Element: Serializer>(
    _ type: Element.Type,
    count: Int,
    elementOwnerBytes: Int = 0
) -> Int {
    ownerBytes([Element].self) + arrayBudget(type, count: count) + count * elementOwnerBytes
}

private func rootArrayBudget<Element: Serializer>(
    _ type: Element.Type,
    count: Int,
    elementOwnerBytes: Int = 0
) -> Int {
    listBudget(type, count: count, elementOwnerBytes: elementOwnerBytes)
}

private func mapBudget<Key: Serializer, Value: Serializer>(
    key: Key.Type,
    value: Value.Type,
    count: Int
) -> Int {
    count * (elementBytes(key) + elementBytes(value))
}

private func dictionaryBudget<Key: Serializer & Hashable, Value: Serializer>(
    key: Key.Type,
    value: Value.Type,
    count: Int
) -> Int {
    ownerBytes(Dictionary<Key, Value>.self) + mapBudget(key: key, value: value, count: count)
}

private func rootMapBudget<Key: Serializer & Hashable, Value: Serializer>(
    key: Key.Type,
    value: Value.Type,
    count: Int
) -> Int {
    dictionaryBudget(key: key, value: value, count: count)
}

private func expectInvalidData(_ body: () throws -> Void) {
    do {
        try body()
        Issue.record("expected invalid data")
    } catch ForyError.invalidData {
    } catch {
        Issue.record("expected invalid data, got \(error)")
    }
}

@Test
func fixedDefaultBudget() throws {
    let fory = makeBudgetFory()
    #expect(fory.config.maxGraphMemoryBytes == defaultGraphMemoryBytes)
    let value = Array(repeating: [String](), count: 3)
    #expect(try fory.deserialize(try fory.serialize(value)) == value)
}

@Test
func byteBufferRootDefaultBudget() throws {
    let count = 6
    let value = Array(repeating: [String](), count: count)
    let bytes = try makeBudgetFory().serialize(value)
    let buffer = ByteBuffer(data: bytes)

    let decoded: [[String]] = try makeBudgetFory().deserialize(from: buffer)
    #expect(decoded.count == count)
}

@Test
func explicitConfigOverridesDefault() throws {
    let values = (0..<16).map { "value-\($0)" }
    let bytes = try makeBudgetFory().serialize(values)
    let required = rootArrayBudget(String.self, count: values.count)

    expectInvalidData {
        let _: [String] = try makeBudgetFory(maxGraphMemoryBytes: Int64(required - 1)).deserialize(
            bytes)
    }
    let decoded: [String] = try makeBudgetFory(maxGraphMemoryBytes: Int64(required)).deserialize(
        bytes)
    #expect(decoded == values)
}

@Test
func siblingContainersShareOneBudget() throws {
    let value = BudgetSiblings(
        left: (0..<16).map { BudgetNode(id: Int32($0)) },
        right: (16..<32).map { BudgetNode(id: Int32($0)) }
    )
    let bytes = try makeBudgetFory().serialize(value)
    let oneList = listBudget(BudgetNode.self, count: 16, elementOwnerBytes: budgetNodeGraphBytes)
    let required = ownerBytes(BudgetSiblings.self) + oneList * 2

    expectInvalidData {
        let _: BudgetSiblings = try makeBudgetFory(maxGraphMemoryBytes: Int64(required - 1))
            .deserialize(bytes)
    }
    let decoded: BudgetSiblings = try makeBudgetFory(maxGraphMemoryBytes: Int64(required))
        .deserialize(bytes)
    #expect(decoded.left.count == 16)
    #expect(decoded.right.count == 16)
}

@Test
func nestedEmptyArraysChargeOwner() throws {
    let count = 3
    let value = Array(repeating: [String](), count: count)
    let bytes = try makeBudgetFory().serialize(value)
    let required = listBudget([String].self, count: count) + count * ownerBytes([String].self)

    expectInvalidData {
        let _: [[String]] = try makeBudgetFory(maxGraphMemoryBytes: Int64(required - 1))
            .deserialize(bytes)
    }
    let decoded: [[String]] = try makeBudgetFory(maxGraphMemoryBytes: Int64(required))
        .deserialize(bytes)
    #expect(decoded == value)
}

@Test
func mapBudgetIsCharged() throws {
    let value: [String: Int32] = ["a": 1, "b": 2, "c": 3]
    let bytes = try makeBudgetFory().serialize(value)
    let required = rootMapBudget(key: String.self, value: Int32.self, count: value.count)

    expectInvalidData {
        let _: [String: Int32] = try makeBudgetFory(maxGraphMemoryBytes: Int64(required - 1))
            .deserialize(bytes)
    }
    let decoded: [String: Int32] = try makeBudgetFory(maxGraphMemoryBytes: Int64(required))
        .deserialize(bytes)
    #expect(decoded == value)
}

@Test
func emptyTypedMapOwnerIsCharged() throws {
    let value: [String: Int32] = [:]
    let bytes = try makeBudgetFory().serialize(value)
    let required = rootMapBudget(key: String.self, value: Int32.self, count: value.count)

    expectInvalidData {
        let _: [String: Int32] = try makeBudgetFory(maxGraphMemoryBytes: Int64(required - 1))
            .deserialize(bytes)
    }
    let decoded: [String: Int32] = try makeBudgetFory(maxGraphMemoryBytes: Int64(required))
        .deserialize(bytes)
    #expect(decoded == value)
}

@Test
func arrayInlineValueBudget() throws {
    let nodes = (0..<4).map { BudgetNode(id: Int32($0)) }
    let nodeBytes = try makeBudgetFory().serialize(nodes)
    let nodeBudget = rootArrayBudget(
        BudgetNode.self,
        count: nodes.count,
        elementOwnerBytes: budgetNodeGraphBytes
    )
    expectInvalidData {
        let _: [BudgetNode] = try makeBudgetFory(maxGraphMemoryBytes: Int64(nodeBudget - 1))
            .deserialize(nodeBytes)
    }
    let decodedNodes: [BudgetNode] = try makeBudgetFory(maxGraphMemoryBytes: Int64(nodeBudget))
        .deserialize(nodeBytes)
    #expect(decodedNodes.count == nodes.count)

    let ints: [Int32] = [1, 2, 3, 4]
    let intBytes = try makeBudgetFory().serialize(ints)
    let intBudget = rootArrayBudget(Int32.self, count: ints.count)
    expectInvalidData {
        let _: [Int32] = try makeBudgetFory(maxGraphMemoryBytes: Int64(intBudget - 1))
            .deserialize(intBytes)
    }
    #expect(try makeBudgetFory(maxGraphMemoryBytes: Int64(intBudget)).deserialize(intBytes) == ints)
}

@Test
func inlineValueFieldBudget() throws {
    let value = BudgetValueHolder(value: BudgetValue(id: 7, enabled: true))
    let bytes = try makeBudgetFory().serialize(value)
    let required = ownerBytes(BudgetValueHolder.self)

    expectInvalidData {
        let _: BudgetValueHolder = try makeBudgetFory(maxGraphMemoryBytes: Int64(required - 1))
            .deserialize(bytes)
    }
    let decoded: BudgetValueHolder = try makeBudgetFory(maxGraphMemoryBytes: Int64(required))
        .deserialize(bytes)
    #expect(decoded == value)
}

@Test
func setConversionOwnerChargedOnce() throws {
    let values: Set<Int32> = [1, 2, 3]
    let bytes = try makeBudgetFory().serialize(values)
    let required = ownerBytes(Set<Int32>.self) + arrayBudget(Int32.self, count: values.count)

    expectInvalidData {
        let _: Set<Int32> = try makeBudgetFory(maxGraphMemoryBytes: Int64(required - 1))
            .deserialize(bytes)
    }
    let decoded: Set<Int32> = try makeBudgetFory(maxGraphMemoryBytes: Int64(required))
        .deserialize(bytes)
    #expect(decoded == values)
}

@Test
func denseLeafOwnersSkipped() throws {
    let value = BudgetDenseHolder(
        text: "budget",
        data: Data([1, 2, 3]),
        dense: [1, 2, 3]
    )
    let bytes = try makeBudgetFory().serialize(value)
    let required = ownerBytes(BudgetDenseHolder.self)

    expectInvalidData {
        let _: BudgetDenseHolder = try makeBudgetFory(maxGraphMemoryBytes: Int64(required - 1))
            .deserialize(bytes)
    }
    let decoded: BudgetDenseHolder = try makeBudgetFory(maxGraphMemoryBytes: Int64(required))
        .deserialize(bytes)
    #expect(decoded == value)
}

@Test
func dynamicAnyEmptyMapOwnerSelf() throws {
    let value = [:] as [AnyHashable: Any]
    let bytes = try makeBudgetFory().serialize(value as Any)
    let required =
        dictionaryBudget(key: AnyHashable.self, value: SerializableAny.self, count: value.count)
        + ownerBytes(Dictionary<AnyHashable, Any>.self)
        + ownerBytes(Dictionary<String, Any>.self)

    expectInvalidData {
        let _: Any = try makeBudgetFory(maxGraphMemoryBytes: Int64(required - 1))
            .deserialize(bytes)
    }
    let decoded: Any = try makeBudgetFory(maxGraphMemoryBytes: Int64(required))
        .deserialize(bytes)
    #expect((decoded as? [String: Any])?.isEmpty == true)
}

@Test
func publicAnyArrayBudget() throws {
    let value: [Any] = [Int32(1), Int32(2), Int32(3)]
    let bytes = try makeBudgetFory().serialize(value)
    let wrappedBudget = listBudget(SerializableAny.self, count: value.count)
    let finalBudget = ownerBytes([Any].self) + value.count * testReferenceBytes

    expectInvalidData {
        let _: [Any] = try makeBudgetFory(maxGraphMemoryBytes: Int64(wrappedBudget))
            .deserialize(bytes, as: [Any].self)
    }
    let decoded = try makeBudgetFory(maxGraphMemoryBytes: Int64(wrappedBudget + finalBudget))
        .deserialize(bytes, as: [Any].self)
    #expect(decoded.count == value.count)
}

@Test
func publicAnyMapBudget() throws {
    let stringMap: [String: Any] = ["a": Int32(1), "b": Int32(2), "c": Int32(3)]
    let stringBytes = try makeBudgetFory().serialize(stringMap)
    let stringWrapped = dictionaryBudget(
        key: String.self,
        value: SerializableAny.self,
        count: stringMap.count
    )
    let stringFinal =
        ownerBytes(Dictionary<String, Any>.self) + stringMap.count * 2 * testReferenceBytes
    expectInvalidData {
        let _: [String: Any] = try makeBudgetFory(maxGraphMemoryBytes: Int64(stringWrapped))
            .deserialize(stringBytes, as: [String: Any].self)
    }
    let decodedString = try makeBudgetFory(maxGraphMemoryBytes: Int64(stringWrapped + stringFinal))
        .deserialize(stringBytes, as: [String: Any].self)
    #expect(decodedString.count == stringMap.count)

    let intMap: [Int32: Any] = [1: Int32(10), 2: Int32(20), 3: Int32(30)]
    let intBytes = try makeBudgetFory().serialize(intMap)
    let intWrapped = dictionaryBudget(
        key: Int32.self,
        value: SerializableAny.self,
        count: intMap.count
    )
    let intFinal = ownerBytes(Dictionary<Int32, Any>.self) + intMap.count * 2 * testReferenceBytes
    expectInvalidData {
        let _: [Int32: Any] = try makeBudgetFory(maxGraphMemoryBytes: Int64(intWrapped))
            .deserialize(intBytes, as: [Int32: Any].self)
    }
    let decodedInt = try makeBudgetFory(maxGraphMemoryBytes: Int64(intWrapped + intFinal))
        .deserialize(intBytes, as: [Int32: Any].self)
    #expect(decodedInt.count == intMap.count)

    let anyHashableMap: [AnyHashable: Any] = [
        AnyHashable("a"): Int32(1),
        AnyHashable(Int32(2)): Int32(2),
        AnyHashable(true): Int32(3)
    ]
    let anyHashableBytes = try makeBudgetFory().serialize(anyHashableMap)
    let anyHashableWrapped = dictionaryBudget(
        key: AnyHashable.self,
        value: SerializableAny.self,
        count: anyHashableMap.count
    )
    let anyHashableFinal =
        ownerBytes(Dictionary<AnyHashable, Any>.self) + anyHashableMap.count * 2 * testReferenceBytes
    expectInvalidData {
        let _: [AnyHashable: Any] = try makeBudgetFory(
            maxGraphMemoryBytes: Int64(anyHashableWrapped)
        ).deserialize(anyHashableBytes, as: [AnyHashable: Any].self)
    }
    let decodedAnyHashable = try makeBudgetFory(
        maxGraphMemoryBytes: Int64(anyHashableWrapped + anyHashableFinal)
    ).deserialize(anyHashableBytes, as: [AnyHashable: Any].self)
    #expect(decodedAnyHashable.count == anyHashableMap.count)
}

@Test
func dynamicAnyArrayBudget() throws {
    let list: [Any] = [Int32(1), "two", Int32(3)]
    let value: Any = list
    let bytes = try makeBudgetFory().serialize(value)
    let count = list.count
    let wrappedBudget = listBudget(SerializableAny.self, count: count)
    let finalBudget = ownerBytes([Any].self) + count * testReferenceBytes

    expectInvalidData {
        let _: Any = try makeBudgetFory(maxGraphMemoryBytes: Int64(wrappedBudget))
            .deserialize(bytes, as: Any.self)
    }
    let decoded = try makeBudgetFory(maxGraphMemoryBytes: Int64(wrappedBudget + finalBudget))
        .deserialize(bytes, as: Any.self)
    #expect((decoded as? [Any])?.count == count)
}

@Test
func compatibleDenseArraySkip() throws {
    let writer = makeCompatibleBudgetFory()
    writer.register(BudgetListDenseWriter.self, id: 9806)
    let reader = makeCompatibleBudgetFory(
        maxGraphMemoryBytes: Int64(ownerBytes(BudgetListDenseReader.self))
    )
    reader.register(BudgetListDenseReader.self, id: 9806)
    let bytes = try writer.serialize(BudgetListDenseWriter(dense: [1, 2, 3]))

    expectInvalidData {
        let failingReader = makeCompatibleBudgetFory(
            maxGraphMemoryBytes: Int64(ownerBytes(BudgetListDenseReader.self) - 1)
        )
        failingReader.register(BudgetListDenseReader.self, id: 9806)
        let _: BudgetListDenseReader = try failingReader.deserialize(bytes)
    }
    let decoded: BudgetListDenseReader = try reader.deserialize(bytes)
    #expect(decoded.dense == [1, 2, 3])
}

@Test
func compatibleInlineValueFieldBudget() throws {
    let writer = makeCompatibleBudgetFory()
    writer.register(BudgetValue.self, id: 9804)
    writer.register(BudgetValueCompatWriter.self, id: 9807)
    let bytes = try writer.serialize(
        BudgetValueCompatWriter(value: BudgetValue(id: 9, enabled: true), extra: 1))

    expectInvalidData {
        let failingReader = makeCompatibleBudgetFory(
            maxGraphMemoryBytes: Int64(ownerBytes(BudgetValueCompatReader.self) - 1)
        )
        failingReader.register(BudgetValue.self, id: 9804)
        failingReader.register(BudgetValueCompatReader.self, id: 9807)
        let _: BudgetValueCompatReader = try failingReader.deserialize(bytes)
    }

    let reader = makeCompatibleBudgetFory(
        maxGraphMemoryBytes: Int64(ownerBytes(BudgetValueCompatReader.self))
    )
    reader.register(BudgetValue.self, id: 9804)
    reader.register(BudgetValueCompatReader.self, id: 9807)
    let decoded: BudgetValueCompatReader = try reader.deserialize(bytes)
    #expect(decoded.value == BudgetValue(id: 9, enabled: true))
}

@Test
func compatibleNestedInlineValueFieldBudget() throws {
    let writer = makeCompatibleBudgetFory()
    writer.register(BudgetNestedValueWriter.self, id: 9808)
    writer.register(BudgetNestedHolderWriter.self, id: 9809)
    let bytes = try writer.serialize(
        BudgetNestedHolderWriter(
            value: BudgetNestedValueWriter(id: 9, enabled: true, extra: 1),
            extra: 2
        ))

    expectInvalidData {
        let failingReader = makeCompatibleBudgetFory(
            maxGraphMemoryBytes: Int64(ownerBytes(BudgetNestedHolderReader.self) - 1)
        )
        failingReader.register(BudgetNestedValueReader.self, id: 9808)
        failingReader.register(BudgetNestedHolderReader.self, id: 9809)
        let _: BudgetNestedHolderReader = try failingReader.deserialize(bytes)
    }

    let reader = makeCompatibleBudgetFory(
        maxGraphMemoryBytes: Int64(ownerBytes(BudgetNestedHolderReader.self))
    )
    reader.register(BudgetNestedValueReader.self, id: 9808)
    reader.register(BudgetNestedHolderReader.self, id: 9809)
    let decoded: BudgetNestedHolderReader = try reader.deserialize(bytes)
    #expect(decoded.value == BudgetNestedValueReader(id: 9, enabled: true))
}

@Test
func byteCheckRejectsLargeLength() throws {
    let buffer = ByteBuffer()
    buffer.writeVarUInt32(64)
    buffer.writeUInt8(CollectionHeader.sameType | CollectionHeader.declaredElementType)
    let config = Config(trackRef: false, compatible: false)
    let context = ReadContext(
        buffer: buffer,
        typeResolver: TypeResolver(config: config),
        config: config
    )

    expectInvalidData {
        let _: [String] = try [String].foryReadData(context)
    }
}
