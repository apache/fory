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

private enum ManualStringEncoding: UInt64 {
    case latin1 = 0
    case utf16 = 1
    case utf8 = 2
}

private func utf16LittleEndianBytes(_ value: String) -> [UInt8] {
    value.utf16.flatMap { unit in
        [
            UInt8(truncatingIfNeeded: unit),
            UInt8(truncatingIfNeeded: unit >> 8)
        ]
    }
}

private func makeStringReadContext(payload: [UInt8], encoding: ManualStringEncoding) -> ReadContext {
    let buffer = ByteBuffer()
    buffer.writeVarUInt36Small((UInt64(payload.count) << 2) | encoding.rawValue)
    buffer.writeBytes(payload)
    return ReadContext(
        buffer: buffer,
        typeResolver: TypeResolver(trackRef: false),
        trackRef: false
    )
}

private func stringPayloadBytes(for value: String) throws -> [UInt8] {
    let context = WriteContext(
        buffer: ByteBuffer(),
        typeResolver: TypeResolver(trackRef: false),
        trackRef: false
    )
    try value.foryWriteData(context, hasGenerics: false)
    return Array(context.buffer.storage.prefix(context.buffer.count))
}

@Test
func stringSerializerRoundTripsUnicodeAndLengthBoundaries() throws {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    let values = [
        "",
        "ascii",
        String(repeating: "a", count: 200),
        "café",
        "你好，Swift",
        "emoji 👩🏽‍💻🚀",
        "e\u{301}",
        "null\u{0000}byte",
        "𐍈 Gothic"
    ]

    for value in values {
        let data = try fory.serialize(value)
        let decoded: String = try fory.deserialize(data)
        #expect(decoded == value)

        let payload = try stringPayloadBytes(for: value)
        let headerReader = ByteBuffer(bytes: payload)
        let header = try headerReader.readVarUInt36Small()
        #expect((header & 0x03) == ManualStringEncoding.utf8.rawValue)
        #expect(Int(header >> 2) == value.utf8.count)

        let context = ReadContext(
            buffer: ByteBuffer(bytes: payload),
            typeResolver: TypeResolver(trackRef: false),
            trackRef: false
        )
        #expect(try String.foryReadData(context) == value)
    }
}

@Test
func stringSerializerReadsUtf8Latin1AndUtf16Payloads() throws {
    let latin1Context = makeStringReadContext(
        payload: [0x63, 0x61, 0x66, 0xE9],
        encoding: .latin1
    )
    #expect(try String.foryReadData(latin1Context) == "café")

    let utf16Value = "你好😀"
    let utf16Context = makeStringReadContext(
        payload: utf16LittleEndianBytes(utf16Value),
        encoding: .utf16
    )
    #expect(try String.foryReadData(utf16Context) == utf16Value)

    let utf8Value = "emoji 👩🏽‍💻"
    let utf8Context = makeStringReadContext(
        payload: Array(utf8Value.utf8),
        encoding: .utf8
    )
    #expect(try String.foryReadData(utf8Context) == utf8Value)
}

@Test
func stringSerializerRejectsInvalidPayloads() throws {
    let oddUTF16 = makeStringReadContext(payload: [0x41], encoding: .utf16)
    do {
        _ = try String.foryReadData(oddUTF16)
        #expect(Bool(false))
    } catch {
        #expect("\(error)".contains("utf16 byte length is not even"))
    }

    let invalidUTF8 = makeStringReadContext(payload: [0xC3, 0x28], encoding: .utf8)
    do {
        _ = try String.foryReadData(invalidUTF8)
        #expect(Bool(false))
    } catch {
        #expect("\(error)".contains("invalid UTF-8"))
    }

    let unsupportedEncodingBuffer = ByteBuffer()
    unsupportedEncodingBuffer.writeVarUInt36Small(3)
    let unsupportedEncoding = ReadContext(
        buffer: unsupportedEncodingBuffer,
        typeResolver: TypeResolver(trackRef: false),
        trackRef: false
    )
    do {
        _ = try String.foryReadData(unsupportedEncoding)
        #expect(Bool(false))
    } catch {
        #expect("\(error)".contains("unsupported string encoding"))
    }
}
