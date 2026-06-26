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

private func makeBudgetFory(maxContainerMemoryBytes: Int64 = -1) -> Fory {
    let fory = Fory(config: .init(
        trackRef: false,
        compatible: false,
        maxContainerMemoryBytes: maxContainerMemoryBytes
    ))
    fory.register(BudgetNode.self, id: 9801)
    fory.register(BudgetSiblings.self, id: 9802)
    fory.register(BudgetDenseHolder.self, id: 9803)
    return fory
}

private func elementBytes<Element: Serializer>(_ type: Element.Type) -> Int {
    type.isRefType ? ReadContext.referenceBytes : max(1, MemoryLayout<Element>.stride)
}

private func arrayBudget<Element: Serializer>(_ type: Element.Type, count: Int) -> Int {
    if count == 0 {
        return ReadContext.containerFixedBytes
    }
    return ReadContext.containerFixedBytes + ReadContext.arrayHeaderBytes +
        count * elementBytes(type)
}

private func mapBudget<Key: Serializer, Value: Serializer>(
    key: Key.Type,
    value: Value.Type,
    count: Int
) -> Int {
    if count == 0 {
        return ReadContext.containerFixedBytes
    }
    return ReadContext.containerFixedBytes + ReadContext.arrayHeaderBytes * 2 +
        count * (
            elementBytes(key) + elementBytes(value) +
                ReadContext.mapEntryOverheadBytes + ReadContext.referenceBytes
        )
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
func knownLengthAutoBudgetRejectsNestedEmptyArrays() throws {
    let count = 6_000
    let value = Array(repeating: [String](), count: count)
    let bytes = try makeBudgetFory().serialize(value)
    let autoLimit = bytes.count * 8 + ReadContext.knownContainerBudgetSlackBytes
    let required = arrayBudget([String].self, count: count) +
        count * arrayBudget(String.self, count: 0)
    #expect(required > autoLimit)

    expectInvalidData {
        let _: [[String]] = try makeBudgetFory().deserialize(bytes)
    }

    let decoded: [[String]] = try makeBudgetFory(maxContainerMemoryBytes: Int64(required)).deserialize(bytes)
    #expect(decoded.count == count)
}

@Test
func byteBufferRootUsesKnownLengthAutoBudget() throws {
    let count = 6_000
    let value = Array(repeating: [String](), count: count)
    let bytes = try makeBudgetFory().serialize(value)
    let buffer = ByteBuffer(data: bytes)

    expectInvalidData {
        let _: [[String]] = try makeBudgetFory().deserialize(from: buffer)
    }
}

@Test
func explicitConfigOverridesAutoBudget() throws {
    let values = (0..<16).map(Int32.init)
    let bytes = try makeBudgetFory().serialize(values)
    let required = arrayBudget(Int32.self, count: values.count)

    expectInvalidData {
        let _: [Int32] = try makeBudgetFory(maxContainerMemoryBytes: Int64(required - 1)).deserialize(bytes)
    }
    let decoded: [Int32] = try makeBudgetFory(maxContainerMemoryBytes: Int64(required)).deserialize(bytes)
    #expect(decoded == values)
}

@Test
func siblingContainersShareOneBudget() throws {
    let value = BudgetSiblings(
        left: (0..<16).map { BudgetNode(id: Int32($0)) },
        right: (16..<32).map { BudgetNode(id: Int32($0)) }
    )
    let bytes = try makeBudgetFory().serialize(value)
    let oneList = arrayBudget(BudgetNode.self, count: 16)

    expectInvalidData {
        let _: BudgetSiblings = try makeBudgetFory(maxContainerMemoryBytes: Int64(oneList)).deserialize(bytes)
    }
    let decoded: BudgetSiblings = try makeBudgetFory(maxContainerMemoryBytes: Int64(oneList * 2)).deserialize(bytes)
    #expect(decoded.left.count == 16)
    #expect(decoded.right.count == 16)
}

@Test
func mapBudgetIsCharged() throws {
    let value: [String: Int32] = ["a": 1, "b": 2, "c": 3]
    let bytes = try makeBudgetFory().serialize(value)
    let required = mapBudget(key: String.self, value: Int32.self, count: value.count)

    expectInvalidData {
        let _: [String: Int32] = try makeBudgetFory(maxContainerMemoryBytes: Int64(required - 1)).deserialize(bytes)
    }
    let decoded: [String: Int32] = try makeBudgetFory(maxContainerMemoryBytes: Int64(required)).deserialize(bytes)
    #expect(decoded == value)
}

@Test
func referenceAndInlineValueArraysAreCharged() throws {
    let nodes = (0..<4).map { BudgetNode(id: Int32($0)) }
    let nodeBytes = try makeBudgetFory().serialize(nodes)
    let nodeBudget = arrayBudget(BudgetNode.self, count: nodes.count)
    expectInvalidData {
        let _: [BudgetNode] = try makeBudgetFory(maxContainerMemoryBytes: Int64(nodeBudget - 1)).deserialize(nodeBytes)
    }
    let decodedNodes: [BudgetNode] = try makeBudgetFory(maxContainerMemoryBytes: Int64(nodeBudget)).deserialize(nodeBytes)
    #expect(decodedNodes.count == nodes.count)

    let ints: [Int32] = [1, 2, 3, 4]
    let intBytes = try makeBudgetFory().serialize(ints)
    let intBudget = arrayBudget(Int32.self, count: ints.count)
    expectInvalidData {
        let _: [Int32] = try makeBudgetFory(maxContainerMemoryBytes: Int64(intBudget - 1)).deserialize(intBytes)
    }
    #expect(try makeBudgetFory(maxContainerMemoryBytes: Int64(intBudget)).deserialize(intBytes) as [Int32] == ints)
}

@Test
func stringBinaryAndPrimitiveDenseArrayOwnersAreSkipped() throws {
    let value = BudgetDenseHolder(
        text: "budget",
        data: Data([1, 2, 3]),
        dense: [1, 2, 3]
    )
    let bytes = try makeBudgetFory().serialize(value)

    let decoded: BudgetDenseHolder = try makeBudgetFory(maxContainerMemoryBytes: 1).deserialize(bytes)
    #expect(decoded == value)
}

@Test
func dynamicAnyEmptyMapChargesFixedCost() throws {
    let value = [:] as [AnyHashable: Any]
    let bytes = try makeBudgetFory().serialize(value as Any)
    let required = ReadContext.containerFixedBytes * 3

    expectInvalidData {
        let _: Any = try makeBudgetFory(maxContainerMemoryBytes: Int64(required - 1))
            .deserialize(bytes)
    }
    let decoded: Any = try makeBudgetFory(maxContainerMemoryBytes: Int64(required))
        .deserialize(bytes)
    #expect((decoded as? [String: Any])?.isEmpty == true)
}

@Test
func byteAvailabilityCheckStillRejectsLargeLength() throws {
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
