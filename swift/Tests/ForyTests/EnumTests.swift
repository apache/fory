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
    var unionField: StringOrLong = .foryDefault()
}

@ForyUnion
private indirect enum Token: Equatable {
    @ForyUnknownCase
    case unknown(UnknownCase)
    case plus
    case number(Int64)
    case ident(String)
    case assign(target: String, value: Int32)
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
func unionDefaultUsesKnownCase() {
    #expect(ForwardStringOrLong.foryDefault() == .text(""))
}

@Test
func unionCaseIdZeroIsKnownCase() throws {
    let buffer = ByteBuffer()
    let typeResolver = TypeResolver(trackRef: false)
    let writeContext = WriteContext(buffer: buffer, typeResolver: typeResolver, trackRef: false)
    try ForwardStringOrLong.text("zero").foryWriteData(writeContext, hasGenerics: false)
    buffer.flip()
    #expect(try buffer.readVarUInt32() == 0)
    buffer.flip()
    let context = ReadContext(
        buffer: buffer,
        typeResolver: typeResolver,
        trackRef: false
    )

    #expect(try ForwardStringOrLong.foryReadData(context) == .text("zero"))
}

@Test
func structWithEnumFieldRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))
    fory.register(Color.self, id: 100)
    fory.register(StructWithEnum.self, id: 101)

    let value = StructWithEnum(name: "test", color: .green, value: 42)
    let data = try fory.serialize(value)
    let decoded: StructWithEnum = try fory.deserialize(data)
    #expect(decoded == value)
}

@Test
func taggedUnionXlangRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: false, compatible: false))
    fory.register(StringOrLong.self, id: 300)
    fory.register(StructWithUnion.self, id: 301)

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
    fory.register(FixedPayloadEvent.self, id: 302)

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
    fory.register(Token.self, id: 1000)

    let nestedMap: [String: Token] = [
        "one": .number(1),
        "plus": .plus,
        "nested": .child(.ident("deep"))
    ]

    let tokens: [Token] = [
        .plus,
        .number(1),
        .ident("foo"),
        .assign(target: "bar", value: 42),
        .other(42),
        .other(nil),
        .child(.child(.other(nil))),
        .map(nestedMap)
    ]

    let data = try fory.serialize(tokens)
    let decoded: [Token] = try fory.deserialize(data)
    #expect(decoded == tokens)
}
