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
private final class BudgetSelfNode {
    var id: Int32 = 0
    var next: BudgetSelfNode?
    var children: [BudgetSelfNode] = []

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

private protocol BudgetDynamicValueProtocol {
    var id: Int32 { get }
}

@ForyStruct
private struct BudgetDynamicValue: BudgetDynamicValueProtocol, Equatable {
    var id: Int32 = 0
}

@ForyStruct
private final class BudgetDynamicHolder {
    var value: any BudgetDynamicValueProtocol = BudgetDynamicValue()

    required init() {}

    init(value: any BudgetDynamicValueProtocol) {
        self.value = value
    }
}

private let defaultGraphMemoryBytes: Int64 = 128 * 1024 * 1024

private func makeBudgetFory(
    maxGraphMemoryBytes: Int64 = defaultGraphMemoryBytes,
    trackRef: Bool = false
) throws -> Fory {
    let fory = Fory(
        config: .init(
            trackRef: trackRef,
            compatible: false,
            maxGraphMemoryBytes: maxGraphMemoryBytes
        ))
    try fory.register(BudgetNode.self, id: 9801)
    try fory.register(BudgetSiblings.self, id: 9802)
    try fory.register(BudgetDenseHolder.self, id: 9803)
    try fory.register(BudgetValue.self, id: 9804)
    try fory.register(BudgetValueHolder.self, id: 9805)
    try fory.register(BudgetSelfNode.self, id: 9810)
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
private let classOwnerBytes = 2 * MemoryLayout<Int>.stride
private let budgetNodeGraphBytes = classOwnerBytes + 4

private func elementBytes<S: Serializer>(_ serializer: S.Type) -> Int {
    if serializer.staticTypeId == .unknown {
        return max(1, MemoryLayout<S.Target>.stride)
    }
    return serializer.isRefType ? testReferenceBytes : max(1, MemoryLayout<S.Target>.stride)
}

private func ownerBytes<T>(_ type: T.Type) -> Int {
    max(1, MemoryLayout<T>.stride)
}

private func arrayBudget<Element: Serializer>(_ type: Element.Type, count: Int) -> Int {
    count * elementBytes(type)
}

private func listBudget<S: Serializer>(
    _ serializer: S.Type,
    count: Int,
    elementOwnerBytes: Int = 0
) -> Int {
    ownerBytes([S.Target].self)
        + arrayBudget(serializer, count: count)
        + count * elementOwnerBytes
}

private func rootArrayBudget<S: Serializer>(
    _ serializer: S.Type,
    count: Int,
    elementOwnerBytes: Int = 0
) -> Int {
    listBudget(serializer, count: count, elementOwnerBytes: elementOwnerBytes)
}

private func mapBudget<Key: Serializer, Value: Serializer>(
    key: Key.Type,
    value: Value.Type,
    count: Int
) -> Int {
    count * (elementBytes(key) + elementBytes(value))
}

private func dictionaryBudget<Key: Serializer, Value: Serializer>(
    key: Key.Type,
    value: Value.Type,
    count: Int
) -> Int where Key.Target: Hashable {
    ownerBytes(Dictionary<Key.Target, Value.Target>.self)
        + mapBudget(key: key, value: value, count: count)
}

private func rootMapBudget<Key: Serializer, Value: Serializer>(
    key: Key.Type,
    value: Value.Type,
    count: Int
) -> Int where Key.Target: Hashable {
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

private func budgetSelfNodeGraphBytes() -> Int {
    classOwnerBytes
        + MemoryLayout<Int32>.stride
        + testReferenceBytes
        + ownerBytes([BudgetSelfNode].self)
}

@Test
func fixedDefaultBudget() throws {
    let fory = try makeBudgetFory()
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
    let required = oneList * 2

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
func generatedSelfReferenceBudget() throws {
    let value = BudgetSelfNode(id: 7)
    value.next = value
    value.children = [value]

    let writer = try makeBudgetFory(trackRef: true)
    let bytes = try writer.serialize(value)
    let required =
        budgetSelfNodeGraphBytes()
        + listBudget(BudgetSelfNode.self, count: 1)

    expectInvalidData {
        let _: BudgetSelfNode = try makeBudgetFory(
            maxGraphMemoryBytes: Int64(required - 1),
            trackRef: true
        ).deserialize(bytes)
    }
    let decoded: BudgetSelfNode = try makeBudgetFory(
        maxGraphMemoryBytes: Int64(required),
        trackRef: true
    ).deserialize(bytes)
    #expect(decoded === decoded.next)
    #expect(decoded.children.count == 1)
    #expect(decoded === decoded.children[0])
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
    let decoded: BudgetValueHolder = try makeBudgetFory(maxGraphMemoryBytes: 1).deserialize(bytes)
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
    let decoded: BudgetDenseHolder = try makeBudgetFory(maxGraphMemoryBytes: 1).deserialize(bytes)
    #expect(decoded == value)
}

@Test
func dynamicAnyEmptyMapOwnerOnce() throws {
    let value = [:] as [AnyHashable: Any]
    let bytes = try makeBudgetFory().serialize(
        value as Any,
        with: DynamicSerializer<Any>.self
    )
    let required =
        dictionaryBudget(
            key: DynamicSerializer<AnyHashable>.self,
            value: DynamicSerializer<Any>.self,
            count: value.count
        )

    expectInvalidData {
        _ = try makeBudgetFory(maxGraphMemoryBytes: Int64(required - 1))
            .deserialize(bytes, with: DynamicSerializer<Any>.self)
    }
    let decoded = try makeBudgetFory(maxGraphMemoryBytes: Int64(required))
        .deserialize(bytes, with: DynamicSerializer<Any>.self)
    #expect((decoded as? [AnyHashable: Any])?.isEmpty == true)
}

@Test
func publicAnyArrayBudget() throws {
    let value: [Any] = [Int32(1), Int32(2), Int32(3)]
    let bytes = try makeBudgetFory().serialize(
        value,
        with: ArraySerializer<DynamicSerializer<Any>>.self
    )
    let required = listBudget(DynamicSerializer<Any>.self, count: value.count)

    expectInvalidData {
        _ = try makeBudgetFory(maxGraphMemoryBytes: Int64(required - 1))
            .deserialize(bytes, with: ArraySerializer<DynamicSerializer<Any>>.self)
    }
    let decoded = try makeBudgetFory(maxGraphMemoryBytes: Int64(required))
        .deserialize(bytes, with: ArraySerializer<DynamicSerializer<Any>>.self)
    #expect(decoded.count == value.count)
}

@Test
func publicAnyMapBudget() throws {
    let stringMap: [String: Any] = ["a": Int32(1), "b": Int32(2), "c": Int32(3)]
    typealias StringMapSerializer = DictionarySerializer<String, DynamicSerializer<Any>>
    let stringBytes = try makeBudgetFory().serialize(stringMap, with: StringMapSerializer.self)
    let stringRequired = dictionaryBudget(
        key: String.self,
        value: DynamicSerializer<Any>.self,
        count: stringMap.count
    )
    expectInvalidData {
        _ = try makeBudgetFory(maxGraphMemoryBytes: Int64(stringRequired - 1))
            .deserialize(stringBytes, with: StringMapSerializer.self)
    }
    let decodedString = try makeBudgetFory(maxGraphMemoryBytes: Int64(stringRequired))
        .deserialize(stringBytes, with: StringMapSerializer.self)
    #expect(decodedString.count == stringMap.count)

    let intMap: [Int32: Any] = [1: Int32(10), 2: Int32(20), 3: Int32(30)]
    typealias IntMapSerializer = DictionarySerializer<Int32, DynamicSerializer<Any>>
    let intBytes = try makeBudgetFory().serialize(intMap, with: IntMapSerializer.self)
    let intRequired = dictionaryBudget(
        key: Int32.self,
        value: DynamicSerializer<Any>.self,
        count: intMap.count
    )
    expectInvalidData {
        _ = try makeBudgetFory(maxGraphMemoryBytes: Int64(intRequired - 1))
            .deserialize(intBytes, with: IntMapSerializer.self)
    }
    let decodedInt = try makeBudgetFory(maxGraphMemoryBytes: Int64(intRequired))
        .deserialize(intBytes, with: IntMapSerializer.self)
    #expect(decodedInt.count == intMap.count)

    let anyHashableMap: [AnyHashable: Any] = [
        AnyHashable("a"): Int32(1),
        AnyHashable(Int32(2)): Int32(2),
        AnyHashable(true): Int32(3)
    ]
    typealias AnyHashableMapSerializer =
        DictionarySerializer<DynamicSerializer<AnyHashable>, DynamicSerializer<Any>>
    let anyHashableBytes = try makeBudgetFory().serialize(
        anyHashableMap,
        with: AnyHashableMapSerializer.self
    )
    let anyHashableRequired = dictionaryBudget(
        key: DynamicSerializer<AnyHashable>.self,
        value: DynamicSerializer<Any>.self,
        count: anyHashableMap.count
    )
    expectInvalidData {
        _ = try makeBudgetFory(maxGraphMemoryBytes: Int64(anyHashableRequired - 1))
            .deserialize(anyHashableBytes, with: AnyHashableMapSerializer.self)
    }
    let decodedAnyHashable = try makeBudgetFory(
        maxGraphMemoryBytes: Int64(anyHashableRequired)
    ).deserialize(anyHashableBytes, with: AnyHashableMapSerializer.self)
    #expect(decodedAnyHashable.count == anyHashableMap.count)
}

@Test
func dynamicAnyArrayBudget() throws {
    let list: [Any] = [Int32(1), "two", Int32(3)]
    let value: Any = list
    let bytes = try makeBudgetFory().serialize(value, with: DynamicSerializer<Any>.self)
    let count = list.count
    let required = listBudget(DynamicSerializer<Any>.self, count: count)

    expectInvalidData {
        _ = try makeBudgetFory(maxGraphMemoryBytes: Int64(required - 1))
            .deserialize(bytes, with: DynamicSerializer<Any>.self)
    }
    let decoded = try makeBudgetFory(maxGraphMemoryBytes: Int64(required))
        .deserialize(bytes, with: DynamicSerializer<Any>.self)
    #expect((decoded as? [Any])?.count == count)
}

@Test
func dynamicFieldUsesExistentialSlot() throws {
    func makeFory(_ maxGraphMemoryBytes: Int64) throws -> Fory {
        let fory = Fory(
            config: .init(
                trackRef: false,
                compatible: false,
                maxGraphMemoryBytes: maxGraphMemoryBytes
            ))
        try fory.register(BudgetDynamicValue.self, id: 9811)
        try fory.register(BudgetDynamicHolder.self, id: 9812)
        return fory
    }

    let value = BudgetDynamicHolder(value: BudgetDynamicValue(id: 7))
    let bytes = try makeFory(defaultGraphMemoryBytes).serialize(value)
    let required =
        classOwnerBytes + max(1, MemoryLayout<any BudgetDynamicValueProtocol>.stride)

    expectInvalidData {
        _ =
            try makeFory(Int64(required - 1))
            .deserialize(bytes) as BudgetDynamicHolder
    }
    let decoded: BudgetDynamicHolder = try makeFory(Int64(required)).deserialize(bytes)
    #expect((decoded.value as? BudgetDynamicValue)?.id == 7)
}

@Test
func compatibleDenseArraySkip() throws {
    let writer = makeCompatibleBudgetFory()
    try writer.register(BudgetListDenseWriter.self, id: 9806)
    let reader = makeCompatibleBudgetFory(maxGraphMemoryBytes: 1)
    try reader.register(BudgetListDenseReader.self, id: 9806)
    let bytes = try writer.serialize(BudgetListDenseWriter(dense: [1, 2, 3]))

    let decoded: BudgetListDenseReader = try reader.deserialize(bytes)
    #expect(decoded.dense == [1, 2, 3])
}

@Test
func compatibleInlineValueFieldBudget() throws {
    let writer = makeCompatibleBudgetFory()
    try writer.register(BudgetValue.self, id: 9804)
    try writer.register(BudgetValueCompatWriter.self, id: 9807)
    let bytes = try writer.serialize(
        BudgetValueCompatWriter(value: BudgetValue(id: 9, enabled: true), extra: 1))

    let reader = makeCompatibleBudgetFory(maxGraphMemoryBytes: 1)
    try reader.register(BudgetValue.self, id: 9804)
    try reader.register(BudgetValueCompatReader.self, id: 9807)
    let decoded: BudgetValueCompatReader = try reader.deserialize(bytes)
    #expect(decoded.value == BudgetValue(id: 9, enabled: true))
}

@Test
func compatibleNestedInlineValueFieldBudget() throws {
    let writer = makeCompatibleBudgetFory()
    try writer.register(BudgetNestedValueWriter.self, id: 9808)
    try writer.register(BudgetNestedHolderWriter.self, id: 9809)
    let bytes = try writer.serialize(
        BudgetNestedHolderWriter(
            value: BudgetNestedValueWriter(id: 9, enabled: true, extra: 1),
            extra: 2
        ))

    let reader = makeCompatibleBudgetFory(maxGraphMemoryBytes: 1)
    try reader.register(BudgetNestedValueReader.self, id: 9808)
    try reader.register(BudgetNestedHolderReader.self, id: 9809)
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
        _ = try [String].readData(context)
    }
}
