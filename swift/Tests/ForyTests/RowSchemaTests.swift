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

private let rowSchemaPrimitives: [RowDataType] = [
    .bool, .int8, .int16, .int32, .int64, .float16, .float32, .float64,
    .string, .binary, .date32, .timestamp, .duration
]

@Test
func rowSchemaTypeIds() {
    let cases: [(type: RowDataType, id: UInt32)] = [
        (.bool, 1), (.int8, 2), (.int16, 3), (.int32, 4), (.int64, 6),
        (.float16, 17), (.float32, 19), (.float64, 20), (.string, 21), (.binary, 41),
        (.date32, 39), (.timestamp, 38), (.duration, 37), (.decimal(precision: 10, scale: 2), 40),
        (.listOf(.int32), 22), (.mapOf(.string, .int32), 24), (.struct([]), 27)
    ]
    for (type, id) in cases {
        #expect(type.typeId.rawValue == id, "\(type)")
    }
}

@Test
func rowSchemaByteWidths() {
    let widths: [Int?] = [1, 1, 2, 4, 8, 2, 4, 8, nil, nil, 4, 8, 8]
    for (type, width) in zip(rowSchemaPrimitives, widths) {
        #expect(type.byteWidth == width, "\(type)")
    }
    #expect(RowDataType.decimal(precision: 10, scale: 2).byteWidth == nil)
    #expect(RowDataType.listOf(.int32).byteWidth == nil)
    #expect(RowDataType.mapOf(.string, .int32).byteWidth == nil)
    #expect(RowDataType.struct([]).byteWidth == nil)
}

@Test
func rowSchemaFactories() {
    #expect(RowDataType.listOf(.string) == .list(RowField(name: "item", dataType: .string, nullable: true)))
    #expect(
        RowDataType.mapOf(.string, .int32)
            == .map(
                key: RowField(name: "key", dataType: .string, nullable: false),
                value: RowField(name: "value", dataType: .int32, nullable: true)
            )
    )
}

@Test
func rowSchemaFieldIndex() {
    let schema = RowSchema([
        RowField(name: "a", dataType: .int32, nullable: false),
        RowField(name: "b", dataType: .string, nullable: true),
        RowField(name: "a", dataType: .int64, nullable: false)
    ])
    #expect(schema.fieldCount == 3)
    #expect(schema.fieldIndex(of: "a") == 2)
    #expect(schema.fieldIndex(of: "b") == 1)
    #expect(schema.fieldIndex(of: "missing") == nil)
}

@Test
func rowSchemaEquality() {
    let field = RowField(name: "a", dataType: .int32, nullable: true)
    #expect(RowSchema([field]) == RowSchema([field]))
    #expect(RowSchema([field]) != RowSchema([RowField(name: "b", dataType: .int32, nullable: true)]))
    #expect(RowSchema([field]) != RowSchema([RowField(name: "a", dataType: .int32, nullable: false)]))
    #expect(RowSchema([field]) != RowSchema([RowField(name: "a", dataType: .int64, nullable: true)]))
    #expect(RowDataType.decimal(precision: 10, scale: 2) != .decimal(precision: 10, scale: 3))
    #expect(RowDataType.decimal(precision: 10, scale: 2) != .decimal(precision: 9, scale: 2))
    #expect(RowDataType.listOf(.int32) != .listOf(.int64))
}

@Test
func rowSchemaDescription() {
    let names = [
        "bool", "int8", "int16", "int32", "int64", "float16", "float32", "float64",
        "string", "binary", "date32", "timestamp", "duration"
    ]
    for (type, name) in zip(rowSchemaPrimitives, names) {
        #expect(type.description == name)
    }
    #expect(RowDataType.decimal(precision: 10, scale: 2).description == "decimal(10, 2)")
    #expect(RowDataType.listOf(.string).description == "list<string>")
    #expect(RowDataType.mapOf(.string, .int32).description == "map<string, int32>")
    let structType = RowDataType.struct([
        RowField(name: "a", dataType: .int32, nullable: false),
        RowField(name: "b", dataType: .string, nullable: true)
    ])
    #expect(structType.description == "struct<a: int32, b: string>")
    #expect(RowSchema([RowField(name: "a", dataType: .int32, nullable: false)]).description == "schema<a: int32>")
}
