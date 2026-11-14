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

private struct Scalars: Equatable {
    var bool: Bool
    var int8: Int8
    var int16: Int16
    var int32: Int32
    var int64: Int64
    var int: Int
    var float: Float
    var double: Double
    var string: String
    var date: Date
    var localDate: LocalDate
    var duration: Duration
}

private let scalarSchema = RowSchema([
    .inferred("bool", Bool.self), .inferred("int8", Int8.self), .inferred("int16", Int16.self),
    .inferred("int32", Int32.self), .inferred("int64", Int64.self), .inferred("int", Int.self),
    .inferred("float", Float.self), .inferred("double", Double.self), .inferred("string", String.self),
    .inferred("date", Date.self), .inferred("localDate", LocalDate.self), .inferred("duration", Duration.self)
])

private func roundTrip(_ value: Scalars) throws -> Scalars {
    var writer = RowWriter(schema: scalarSchema)
    writer.reset()
    try writer.write(value.bool, at: 0)
    try writer.write(value.int8, at: 1)
    try writer.write(value.int16, at: 2)
    try writer.write(value.int32, at: 3)
    try writer.write(value.int64, at: 4)
    try writer.write(value.int, at: 5)
    try writer.write(value.float, at: 6)
    try writer.write(value.double, at: 7)
    try writer.write(value.string, at: 8)
    try writer.write(value.date, at: 9)
    try writer.write(value.localDate, at: 10)
    try writer.write(value.duration, at: 11)
    let row = try Row(schema: scalarSchema, bytes: writer.toBytes())
    return try Scalars(
        bool: row.read(at: 0), int8: row.read(at: 1), int16: row.read(at: 2), int32: row.read(at: 3),
        int64: row.read(at: 4), int: row.read(at: 5), float: row.read(at: 6), double: row.read(at: 7),
        string: row.read(at: 8), date: row.read(at: 9), localDate: row.read(at: 10), duration: row.read(at: 11)
    )
}

private let optionalSchema = RowSchema([
    .inferred("a", Int32?.self), .inferred("b", String?.self), .inferred("c", Date?.self)
])

private func optionalRow(_ number: Int32?, _ text: String?, _ date: Date?) throws -> Row {
    var writer = RowWriter(schema: optionalSchema)
    writer.reset()
    try writer.write(number, at: 0)
    try writer.write(text, at: 1)
    try writer.write(date, at: 2)
    return try Row(schema: optionalSchema, bytes: writer.toBytes())
}

@Test
func rowCodableScalars() throws {
    let low = Scalars(
        bool: false, int8: .min, int16: .min, int32: .min, int64: .min, int: .min,
        float: -.greatestFiniteMagnitude, double: -.greatestFiniteMagnitude, string: "",
        date: Date(timeIntervalSince1970: -86_400.5), localDate: LocalDate(epochDay: Int64(Int32.min)),
        duration: .microseconds(Int64.min)
    )
    let high = Scalars(
        bool: true, int8: .max, int16: .max, int32: .max, int64: .max, int: .max,
        float: .greatestFiniteMagnitude, double: .greatestFiniteMagnitude, string: "héllo, 世界",
        date: Date(timeIntervalSinceReferenceDate: 721_692_800.25), localDate: LocalDate(epochDay: Int64(Int32.max)),
        duration: .microseconds(Int64.max)
    )
    #expect(try roundTrip(low) == low)
    #expect(try roundTrip(high) == high)
}

@Test
func rowCodableOptionalNull() throws {
    let nulls = try optionalRow(nil, nil, nil)
    for index in 0..<3 {
        #expect(nulls.isNull(at: index))
    }
    #expect(try nulls.read(Int32?.self, at: 0) == nil)
    #expect(try nulls.read(String?.self, at: 1) == nil)
    #expect(try nulls.read(Date?.self, at: 2) == nil)

    let date = Date(timeIntervalSinceReferenceDate: 0.5)
    let values = try optionalRow(7, "x", date)
    #expect(try values.read(Int32?.self, at: 0) == 7)
    #expect(try values.read(String?.self, at: 1) == "x")
    #expect(try values.read(Date?.self, at: 2) == date)
}

@Test
func rowCodableNullIntoValue() throws {
    let nulls = try optionalRow(nil, nil, nil)
    #expect(throws: ForyError.invalidData("null row value cannot be read into non-optional Int32")) {
        try nulls.read(Int32.self, at: 0)
    }
    #expect(throws: ForyError.invalidData("null row value cannot be read into non-optional String")) {
        try nulls.read(String.self, at: 1)
    }
}

@Test
func rowCodableInferredFields() {
    #expect(RowField.inferred("a", Int32.self) == RowField(name: "a", dataType: .int32, nullable: false))
    #expect(RowField.inferred("a", Int32?.self) == RowField(name: "a", dataType: .int32, nullable: true))
    #expect(RowField.inferred("a", String.self) == RowField(name: "a", dataType: .string, nullable: true))
    #expect(RowField.inferred("a", Int.self) == RowField(name: "a", dataType: .int64, nullable: false))
    #expect(RowField.inferred("a", Date.self) == RowField(name: "a", dataType: .timestamp, nullable: true))
    #expect(RowField.inferred("a", LocalDate.self) == RowField(name: "a", dataType: .date32, nullable: true))
    #expect(RowField.inferred("a", Duration.self) == RowField(name: "a", dataType: .duration, nullable: true))

    // An array of Int32? still packs 4-byte elements, so the width must follow the wrapped type.
    let types: [any RowCodable.Type] = [
        Bool.self, Int8.self, Int16.self, Int32.self, Int64.self, Int.self, Float.self, Double.self,
        String.self, Date.self, LocalDate.self, Duration.self, Int16?.self, String?.self
    ]
    for type in types {
        #expect(type.rowFixedWidth == type.rowDataType.byteWidth, "\(type)")
    }
}
