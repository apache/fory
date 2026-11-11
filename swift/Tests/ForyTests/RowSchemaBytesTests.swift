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

private func field(_ name: String, _ dataType: RowDataType, nullable: Bool = true) -> RowField {
    RowField(name: name, dataType: dataType, nullable: nullable)
}

// Bytes and hashes that every row format runtime produces for these schemas.
private let simpleSchema = RowSchema([
    field("id", .int32), field("name", .string), field("score", .float64), field("active", .bool)
])

private let simpleBytes: [UInt8] = [
    0x01, 0x04, 0x45, 0xa0, 0x60, 0x04, 0x49, 0x34, 0x0c, 0x20, 0x15, 0x4d,
    0xc8, 0x4e, 0x89, 0x00, 0x14, 0x4d, 0x00, 0x53, 0x45, 0x48, 0x01
]

private let nestedSchema = RowSchema([
    field("person", .struct([field("name", .string), field("age", .int32, nullable: false)])),
    field("tags", .listOf(.string)),
    field("attrs", .mapOf(.string, .int64))
])

private let nestedBytes: [UInt8] = [
    0x01, 0x03, 0x4d, 0x3c, 0x91, 0x93, 0x9a, 0x1b, 0x02, 0x49, 0x34, 0x0c,
    0x20, 0x15, 0x05, 0x00, 0xc4, 0x04, 0x49, 0x4c, 0x06, 0x90, 0x16, 0x49,
    0x22, 0x64, 0x60, 0x15, 0x4d, 0x82, 0x73, 0x8c, 0x80, 0x18, 0x05, 0x28,
    0x98, 0x15, 0x4d, 0xd4, 0x0b, 0xa1, 0x00, 0x06
]

private let specialSchema = RowSchema([
    field("a_very_long_field_name_exceeding_limit", .int64, nullable: false),
    field("field2", .string),
    field("$special_name", .date32),
    field("CamelCase", .timestamp, nullable: false),
    field("price", .decimal(precision: 10, scale: 2))
])

private let specialBytes: [UInt8] = [
    0x01, 0x05, 0x3d, 0x09, 0x03, 0x75, 0x24, 0x71, 0xb5, 0xb9, 0xa6, 0xd9,
    0x50, 0x45, 0x8f, 0x6d, 0x03, 0x09, 0xb2, 0x5c, 0x44, 0x20, 0xd0, 0xd3,
    0x6d, 0x68, 0x62, 0x26, 0x06, 0x52, 0x0a, 0x40, 0x85, 0x87, 0xb0, 0x15,
    0x61, 0xf2, 0x4f, 0x20, 0x90, 0x05, 0xed, 0xa0, 0x61, 0x00, 0x27, 0x1a,
    0x38, 0x01, 0x82, 0x16, 0xe0, 0x09, 0x08, 0x26, 0x4d, 0xbe, 0x28, 0x11,
    0x00, 0x28, 0x0a, 0x02
]

// The Foo schema from the cross-language row tests.
private let fooSchema = RowSchema([
    field("f1", .int32),
    field("f2", .string),
    field("f3", .listOf(.string)),
    field("f4", .mapOf(.string, .int32)),
    field("f5", .struct([field("f1", .int32), field("f2", .string)]))
])

private func nestedLists(_ levels: Int) -> RowDataType {
    var dataType = RowDataType.int32
    for _ in 0..<levels {
        dataType = .listOf(dataType)
    }
    return dataType
}

@Test
func rowSchemaBytesGolden() throws {
    let goldens = [(simpleSchema, simpleBytes), (nestedSchema, nestedBytes), (specialSchema, specialBytes)]
    for (schema, bytes) in goldens {
        #expect(try schema.toBytes() == bytes, "\(schema)")
        #expect(try RowSchema(bytes: bytes) == schema, "\(schema)")
    }
}

@Test
func rowSchemaBytesFoo() throws {
    #expect(try fooSchema.toBytes() == goldenFooSchema)
    #expect(try RowSchema(bytes: goldenFooSchema) == fooSchema)
}

@Test
func rowSchemaBytesNames() throws {
    // A hyphen forces UTF-8, so the encoded size equals the character count. Fifteen bytes still fit
    // in the header; sixteen need the varint.
    let fifteen = "a-bcdefghijklmn"
    var expected: [UInt8] = [0x01, 0x01, 0x78]
    expected += Array(fifteen.utf8)
    expected.append(0x04)
    #expect(try RowSchema([field(fifteen, .int32)]).toBytes() == expected)

    let sixteen = "a-bcdefghijklmno"
    expected = [0x01, 0x01, 0x7c, 0x01]
    expected += Array(sixteen.utf8)
    expected.append(0x04)
    #expect(try RowSchema([field(sixteen, .int32)]).toBytes() == expected)
    #expect(try RowSchema(bytes: expected) == RowSchema([field(sixteen, .int32)]))

    let nonLatin = RowSchema([field("名前", .string)])
    #expect(try RowSchema(bytes: nonLatin.toBytes()) == nonLatin)
}

@Test
func rowSchemaBytesDepth() throws {
    #expect(throws: ForyError.encodingError("row schema nests 64 or more levels deep")) {
        try RowSchema([field("deep", nestedLists(64))]).toBytes()
    }
    let deepest = RowSchema([field("deep", nestedLists(63))])
    #expect(try RowSchema(bytes: deepest.toBytes()) == deepest)

    // Each level is a field named "a" whose type is a list, ending in an int32 field.
    func listChain(_ levels: Int) -> [UInt8] {
        var bytes: [UInt8] = [0x01, 0x01]
        for _ in 0..<levels {
            bytes += [0x00, 0x61, 0x16]
        }
        bytes += [0x00, 0x61, 0x04]
        return bytes
    }
    #expect(throws: ForyError.invalidData("row schema nests 64 or more levels deep")) {
        try RowSchema(bytes: listChain(64))
    }
    _ = try RowSchema(bytes: listChain(63))
}

@Test
func rowSchemaBytesErrors() throws {
    #expect(throws: ForyError.invalidData("unsupported row schema version 2, expected 1")) {
        try RowSchema(bytes: [0x02, 0x00])
    }
    #expect(throws: ForyError.invalidData("unknown row schema field name encoding 3")) {
        try RowSchema(bytes: [0x01, 0x01, 0x07])
    }
    var unknownType = simpleBytes
    unknownType[unknownType.count - 1] = 0x63
    #expect(throws: ForyError.invalidData("unknown type id 99 in row schema")) {
        try RowSchema(bytes: unknownType)
    }
    #expect(throws: ForyError.invalidData("row schema declares 127 fields but only 0 bytes remain")) {
        try RowSchema(bytes: [0x01, 0x7f])
    }
    for length in 0..<nestedBytes.count {
        #expect(throws: ForyError.self, "prefix of \(length) bytes") {
            try RowSchema(bytes: Array(nestedBytes.prefix(length)))
        }
    }

    #expect(throws: ForyError.encodingError("row schema field has an empty name")) {
        try RowSchema([field("", .int32)]).toBytes()
    }
    #expect(throws: ForyError.encodingError("decimal(300, 2) precision and scale must each fit in one byte")) {
        try RowSchema([field("d", .decimal(precision: 300, scale: 2))]).toBytes()
    }
}

@Test
func rowSchemaHash() {
    #expect(simpleSchema.hash == 15_839_823)
    #expect(nestedSchema.hash == 15_260_761_278_193)
    #expect(specialSchema.hash == 492_901_001)
    #expect(fooSchema.hash == 14_057_974_700_829_126)

    let aSchema = RowSchema([field("f1", .int32), field("f2", .mapOf(.string, .string))])
    #expect(aSchema.hash == 15_842_757)
    let blobSchema = RowSchema([
        field("f1", .list(field("item", .int8, nullable: false))),
        field("f2", .string)
    ])
    #expect(blobSchema.hash == 527_672)

    // Twenty int64 fields overflow Int64, so the shift and retry path runs.
    let wide = RowSchema((0..<20).map { field("f\($0)", .int64) })
    #expect(wide.hash == 2_627_256_684_647_412_568)

    #expect(RowSchema([field("x", .int32, nullable: false)]).hash == RowSchema([field("y", .int32)]).hash)
}
