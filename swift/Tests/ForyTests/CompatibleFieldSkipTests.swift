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
private final class SkippedReferenceBody {
    @ForyField(id: 1)
    var marker: Int32 = 0

    @ForyField(id: 2)
    var text: String = ""

    @ForyField(id: 3)
    var dynamic: Any = Int32(0)

    required init() {}

    init(marker: Int32, text: String, dynamic: Any) {
        self.marker = marker
        self.text = text
        self.dynamic = dynamic
    }
}

@ForyStruct
private struct SkippedValueBody {
    var first: Int64 = 0
    var second: Int64 = 0
    var third: Int64 = 0
    var fourth: Int64 = 0
}

@ForyStruct
private struct SkippedValueOwnerV1 {
    @ForyField(id: 1)
    var removed: SkippedValueBody = SkippedValueBody()

    @ForyField(id: 2)
    var keep: Int32 = 0
}

@ForyStruct
private struct SkippedValueOwnerV2: Equatable {
    @ForyField(id: 2)
    var keep: Int32 = 0
}

@ForyStruct
private struct SkippedReferenceOwnerV1 {
    @ForyField(id: 1)
    var removed: [SkippedReferenceBody] = []

    @ForyField(id: 2)
    var keep: Int32 = 0
}

@ForyStruct
private struct SkippedReferenceOwnerV2: Equatable {
    @ForyField(id: 2)
    var keep: Int32 = 0
}

@ForyStruct
private final class SkippedRecursiveNode {
    var marker: Int32 = 0
    var next: SkippedRecursiveNode?

    required init() {}

    init(marker: Int32, next: SkippedRecursiveNode? = nil) {
        self.marker = marker
        self.next = next
    }
}

@ForyStruct
private struct SkippedRecursiveOwnerV1 {
    @ForyField(id: 1)
    var removed: SkippedRecursiveNode = SkippedRecursiveNode()

    @ForyField(id: 2)
    var keep: Int32 = 0
}

@ForyStruct
private struct SkippedRecursiveOwnerV2: Equatable {
    @ForyField(id: 2)
    var keep: Int32 = 0
}

@ForyStruct(evolving: false)
private struct NamedCollectionItemV1 {
    @ForyField(id: 1)
    var removed: Int32 = 0

    @ForyField(id: 2)
    var keep: Int32 = 0
}

@ForyStruct(evolving: false)
private struct NamedCollectionItemV2: Equatable {
    @ForyField(id: 2)
    var keep: Int32 = 0
}

@Test
func skipsStaticReferenceBodies() throws {
    for trackRef in [false, true] {
        // Each skipped class item consumes one materialization level and its
        // dynamic field consumes the second level.
        let config = Config(trackRef: trackRef, compatible: true, maxDepth: 2)
        let writer = Fory(config: config)
        try writer.register(SkippedReferenceBody.self, id: 9980)
        try writer.register(SkippedReferenceOwnerV1.self, id: 9981)

        let reader = Fory(config: config)
        try reader.register(SkippedReferenceBody.self, id: 9980)
        try reader.register(SkippedReferenceOwnerV2.self, id: 9981)

        let source = SkippedReferenceOwnerV1(
            removed: [
                SkippedReferenceBody(marker: 17, text: "first", dynamic: Int32(41)),
                SkippedReferenceBody(marker: 29, text: "second", dynamic: "value")
            ],
            keep: 73
        )
        let decoded: SkippedReferenceOwnerV2 = try reader.deserialize(
            writer.serialize(source)
        )
        #expect(decoded.keep == source.keep)
    }
}

@Test
func skipsStaticValueBody() throws {
    let config = Config(trackRef: false, compatible: true, maxDepth: 1)
    let writer = Fory(config: config)
    try writer.register(SkippedValueBody.self, id: 9982)
    try writer.register(SkippedValueOwnerV1.self, id: 9983)

    let reader = Fory(config: config)
    try reader.register(SkippedValueBody.self, id: 9982)
    try reader.register(SkippedValueOwnerV2.self, id: 9983)

    let source = SkippedValueOwnerV1(
        removed: SkippedValueBody(first: 1, second: 2, third: 3, fourth: 4),
        keep: 81
    )
    let decoded: SkippedValueOwnerV2 = try reader.deserialize(writer.serialize(source))
    #expect(decoded.keep == source.keep)
}

@Test
func skippedRecursiveBodyUsesDepth() throws {
    let source = SkippedRecursiveOwnerV1(
        removed: SkippedRecursiveNode(
            marker: 1,
            next: SkippedRecursiveNode(
                marker: 2,
                next: SkippedRecursiveNode(marker: 3)
            )
        ),
        keep: 89
    )
    let writer = Fory(config: .init(trackRef: false, compatible: true, maxDepth: 8))
    try writer.register(SkippedRecursiveNode.self, id: 9984)
    try writer.register(SkippedRecursiveOwnerV1.self, id: 9985)
    let bytes = try writer.serialize(source)

    let limited = Fory(config: .init(trackRef: false, compatible: true, maxDepth: 2))
    try limited.register(SkippedRecursiveNode.self, id: 9984)
    try limited.register(SkippedRecursiveOwnerV2.self, id: 9985)
    do {
        let _: SkippedRecursiveOwnerV2 = try limited.deserialize(bytes)
        Issue.record("expected maxDepth failure")
    } catch {
        #expect(String(describing: error).contains("maxDepth"))
    }

    let boundary = Fory(config: .init(trackRef: false, compatible: true, maxDepth: 3))
    try boundary.register(SkippedRecursiveNode.self, id: 9984)
    try boundary.register(SkippedRecursiveOwnerV2.self, id: 9985)
    let decoded: SkippedRecursiveOwnerV2 = try boundary.deserialize(bytes)
    #expect(decoded.keep == source.keep)
}

@Test
func retainsNamedCollectionSchema() throws {
    let config = Config(trackRef: false, compatible: true)
    let writer = Fory(config: config)
    try writer.register(NamedCollectionItemV1.self, name: "compatible.NamedCollectionItem")

    let reader = Fory(config: config)
    try reader.register(NamedCollectionItemV2.self, name: "compatible.NamedCollectionItem")

    let source = [
        NamedCollectionItemV1(removed: 101, keep: 7),
        NamedCollectionItemV1(removed: 202, keep: 9)
    ]
    let decoded: [NamedCollectionItemV2] = try reader.deserialize(
        writer.serialize(source)
    )
    #expect(decoded == source.map { NamedCollectionItemV2(keep: $0.keep) })
}
