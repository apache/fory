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

@ForyEnum
private enum Color: Equatable {
    case red
    case green
    case blue
}

@ForyUnion
private enum StringOrLong: Equatable {
    @ForyUnknownCase
    case unknown(UnknownCase)
    @ForyCase(id: 0)
    case text(String)
    @ForyCase(id: 1)
    case number(Int64)
}

@ForyUnion
private enum ForwardStringOrLong: Equatable {
    @ForyUnknownCase
    case unknown(UnknownCase)
    @ForyCase(id: 0)
    case text(String)
    @ForyCase(id: 1)
    case number(Int64)
}

@ForyUnion
private enum FixedPayloadEvent: Equatable {
    @ForyUnknownCase
    case unknown(UnknownCase)

    @ForyCase(id: 0)
    case created(String)

    @ForyCase(id: 1, payload: .uint64(encoding: .fixed))
    case deleted(UInt64)
}

@ForyStruct
private struct StructWithEnum: Equatable {
    var name: String = ""
    var color: Color = .red
    var value: Int32 = 0
}

@ForyStruct
private struct StructWithUnion: Equatable {
    var unionField: StringOrLong = .text("")
}

@ForyUnion
private indirect enum Token: Equatable {
    @ForyUnknownCase
    case unknown(UnknownCase)
    case plus
    case number(Int64)
    case ident(String)
    case other(Int64?)
    case child(Token)
    case map([String: Token])
}

@Test
func enumTypeIdClassification() {
    #expect(Color.staticTypeId == .enumType)
    #expect(StringOrLong.staticTypeId == .typedUnion)
}

@Test
func unionDefaultUsesKnownCase() throws {
    let context = ReadContext(
        buffer: ByteBuffer(),
        typeResolver: TypeResolver(config: Config(trackRef: false)),
        config: Config(trackRef: false)
    )
    #expect(try ForwardStringOrLong.defaultValue(context) == .text(""))
}

@Test
func unionCaseIdZeroIsKnownCase() throws {
    let buffer = ByteBuffer()
    let typeResolver = TypeResolver(config: Config(trackRef: false))
    let writeContext = WriteContext(buffer: buffer, typeResolver: typeResolver, trackRef: false)
    try ForwardStringOrLong.writeData(.text("zero"), writeContext)
    buffer.flip()
    #expect(try buffer.readVarUInt32() == 0)
    buffer.flip()
    let context = ReadContext(
        buffer: buffer,
        typeResolver: typeResolver,
        config: Config(trackRef: false)
    )

    #expect(try ForwardStringOrLong.readData(context) == .text("zero"))
}

@Test
func structWithEnumFieldRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(Color.self, id: 100)
    try fory.register(StructWithEnum.self, id: 101)

    let value = StructWithEnum(name: "test", color: .green, value: 42)
    let data = try fory.serialize(value)
    let decoded: StructWithEnum = try fory.deserialize(data)
    #expect(decoded == value)
}

@Test
func idEnumDoesNotUseTypeMetaLimits() throws {
    let fory = Fory(
        config: .init(
            trackRef: false,
            compatible: true,
            maxTypeMetaBytes: 1,
            maxSchemaVersionsPerType: 1))
    try fory.register(Color.self, id: 100)

    let data = try fory.serialize(Color.green)
    let decoded: Color = try fory.deserialize(data)
    #expect(decoded == .green)
}

@Test
func idUnionDoesNotUseTypeMetaLimits() throws {
    let fory = Fory(
        config: .init(
            trackRef: false,
            compatible: true,
            maxTypeMetaBytes: 1,
            maxSchemaVersionsPerType: 1))
    try fory.register(StringOrLong.self, id: 101)

    let data = try fory.serialize(StringOrLong.text("hello"))
    let decoded: StringOrLong = try fory.deserialize(data)
    #expect(decoded == .text("hello"))
}

@Test
func taggedUnionXlangRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(StringOrLong.self, id: 300)
    try fory.register(StructWithUnion.self, id: 301)

    let first = StructWithUnion(unionField: .text("hello"))
    let second = StructWithUnion(unionField: .number(42))

    let firstData = try fory.serialize(first)
    let secondData = try fory.serialize(second)

    let firstDecoded: StructWithUnion = try fory.deserialize(firstData)
    let secondDecoded: StructWithUnion = try fory.deserialize(secondData)

    #expect(firstDecoded == first)
    #expect(secondDecoded == second)
}

@Test
func taggedUnionPayloadFieldCodecsRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))
    try fory.register(FixedPayloadEvent.self, id: 302)

    let values: [FixedPayloadEvent] = [
        .created("item"),
        .deleted(UInt64.max)
    ]
    for value in values {
        let decoded: FixedPayloadEvent = try fory.deserialize(try fory.serialize(value))
        #expect(decoded == value)
    }
}

@Test
func mixedEnumShapesRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: true, compatible: false))
    try fory.register(Token.self, id: 1000)

    let nestedMap: [String: Token] = [
        "one": .number(1),
        "plus": .plus,
        "nested": .child(.ident("deep"))
    ]

    let tokens: [Token] = [
        .plus,
        .number(1),
        .ident("foo"),
        .other(42),
        .other(nil),
        .child(.child(.other(nil))),
        .map(nestedMap)
    ]

    let data = try fory.serialize(tokens)
    let decoded: [Token] = try fory.deserialize(data)
    #expect(decoded == tokens)
}

@Test
func unionDepthOnlyCountsDynamicUnknownPayload() throws {
    let writer = Fory(config: .init(trackRef: false, maxDepth: 8))
    try writer.register(Token.self, id: 1001)
    let value = Token.child(.child(.ident("leaf")))
    let bytes = try writer.serialize(value)

    let staticReader = Fory(config: .init(trackRef: false, maxDepth: 0))
    try staticReader.register(Token.self, id: 1001)
    let decoded: Token = try staticReader.deserialize(bytes)
    #expect(decoded == value)

    func unknownContext(maxDepth: Int) -> ReadContext {
        let buffer = ByteBuffer()
        buffer.writeVarUInt32(77)
        buffer.writeInt8(RefFlag.notNullValue.rawValue)
        buffer.writeUInt8(UInt8(TypeId.varint32.rawValue))
        buffer.writeVarInt32(9)
        let config = Config(compatible: false, maxDepth: maxDepth)
        let context = ReadContext(
            buffer: buffer,
            typeResolver: TypeResolver(config: config),
            config: config
        )
        context.remainingGraphMemoryBytes = Int(config.maxGraphMemoryBytes)
        return context
    }

    do {
        let _: ForwardStringOrLong = try ForwardStringOrLong.readData(
            unknownContext(maxDepth: 0)
        )
        Issue.record("expected maxDepth failure")
    } catch ForyError.invalidData(let message) {
        #expect(message.contains("maxDepth"))
    }

    let unknown = try ForwardStringOrLong.readData(unknownContext(maxDepth: 1))
    guard case .unknown(let payload) = unknown else {
        Issue.record("expected unknown union case")
        return
    }
    #expect(payload.caseId == 77)
    #expect(payload.value as? Int32 == 9)
}

@Test
func unknownScalarReplayDepthBoundary() throws {
    let value = ForwardStringOrLong.unknown(
        UnknownCase(
            caseId: 77,
            typeId: TypeId.varint32.rawValue,
            value: Int32(9)
        ))

    let blocked = Fory(config: .init(trackRef: false, compatible: false, maxDepth: 0))
    try blocked.register(ForwardStringOrLong.self, id: 1002)
    do {
        _ = try blocked.serialize(value)
        Issue.record("expected maxDepth failure")
    } catch ForyError.invalidData(let message) {
        #expect(message.contains("maxDepth"))
    }

    let boundary = Fory(config: .init(trackRef: false, compatible: false, maxDepth: 1))
    try boundary.register(ForwardStringOrLong.self, id: 1002)
    let decoded: ForwardStringOrLong = try boundary.deserialize(
        boundary.serialize(value)
    )
    guard case .unknown(let payload) = decoded else {
        Issue.record("expected unknown union case")
        return
    }
    #expect(payload.caseId == 77)
    #expect(payload.typeId == TypeId.varint32.rawValue)
    #expect(payload.value as? Int32 == 9)
}
