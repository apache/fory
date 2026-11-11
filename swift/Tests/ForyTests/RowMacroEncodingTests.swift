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
import Fory
import Testing

// The models every row format runtime shares in its cross-language tests.

@ForyRow
private struct Bar: Equatable {
    var f1: Int32?
    var f2: String?
}

@ForyRow
private struct Foo: Equatable {
    var f1: Int32?
    var f2: String?
    var f3: [String?]?
    var f4: [String: Int32?]?
    var f5: Bar?
}

@ForyRow
private struct StringMap: Equatable {
    var f1: Int32?
    var f2: [String: String]?
}

@ForyRow
private struct Blob: Equatable {
    var f1: Data?
    var f2: String?
}

private let fooValue = Foo(
    f1: 1, f2: "str", f3: ["str1", nil, "str2"], f4: ["k1": 1, "k2": 2, "k3": 3, "k4": 4, "k5": 5, "k6": 6],
    f5: Bar(f1: 1, f2: "str")
)
private let stringMapValue = StringMap(f1: 1, f2: ["pid": "12345", "ip": "0.0.0.0", "k1": "v1"])
private let blobValue = Blob(f1: Data([0, 1, 0xff, 0x7f, 0x80]), f2: "bytes")

// Field counts around the bitmap's 64-bit word boundary.

@ForyRow
private struct Empty: Equatable {}

@ForyRow
private struct Wide63: Equatable {
    var f00: Int64 = 0, f01: Int64 = 0, f02: Int64 = 0, f03: Int64 = 0, f04: Int64 = 0, f05: Int64 = 0, f06: Int64 = 0, f07: Int64 = 0
    var f08: Int64 = 0, f09: Int64 = 0, f10: Int64 = 0, f11: Int64 = 0, f12: Int64 = 0, f13: Int64 = 0, f14: Int64 = 0, f15: Int64 = 0
    var f16: Int64 = 0, f17: Int64 = 0, f18: Int64 = 0, f19: Int64 = 0, f20: Int64 = 0, f21: Int64 = 0, f22: Int64 = 0, f23: Int64 = 0
    var f24: Int64 = 0, f25: Int64 = 0, f26: Int64 = 0, f27: Int64 = 0, f28: Int64 = 0, f29: Int64 = 0, f30: Int64 = 0, f31: Int64 = 0
    var f32: Int64 = 0, f33: Int64 = 0, f34: Int64 = 0, f35: Int64 = 0, f36: Int64 = 0, f37: Int64 = 0, f38: Int64 = 0, f39: Int64 = 0
    var f40: Int64 = 0, f41: Int64 = 0, f42: Int64 = 0, f43: Int64 = 0, f44: Int64 = 0, f45: Int64 = 0, f46: Int64 = 0, f47: Int64 = 0
    var f48: Int64 = 0, f49: Int64 = 0, f50: Int64 = 0, f51: Int64 = 0, f52: Int64 = 0, f53: Int64 = 0, f54: Int64 = 0, f55: Int64 = 0
    var f56: Int64 = 0, f57: Int64 = 0, f58: Int64 = 0, f59: Int64 = 0, f60: Int64 = 0, f61: Int64 = 0, f62: Int64 = 0
}

@ForyRow
private struct Wide64: Equatable {
    var f00: Int64 = 0, f01: Int64 = 0, f02: Int64 = 0, f03: Int64 = 0, f04: Int64 = 0, f05: Int64 = 0, f06: Int64 = 0, f07: Int64 = 0
    var f08: Int64 = 0, f09: Int64 = 0, f10: Int64 = 0, f11: Int64 = 0, f12: Int64 = 0, f13: Int64 = 0, f14: Int64 = 0, f15: Int64 = 0
    var f16: Int64 = 0, f17: Int64 = 0, f18: Int64 = 0, f19: Int64 = 0, f20: Int64 = 0, f21: Int64 = 0, f22: Int64 = 0, f23: Int64 = 0
    var f24: Int64 = 0, f25: Int64 = 0, f26: Int64 = 0, f27: Int64 = 0, f28: Int64 = 0, f29: Int64 = 0, f30: Int64 = 0, f31: Int64 = 0
    var f32: Int64 = 0, f33: Int64 = 0, f34: Int64 = 0, f35: Int64 = 0, f36: Int64 = 0, f37: Int64 = 0, f38: Int64 = 0, f39: Int64 = 0
    var f40: Int64 = 0, f41: Int64 = 0, f42: Int64 = 0, f43: Int64 = 0, f44: Int64 = 0, f45: Int64 = 0, f46: Int64 = 0, f47: Int64 = 0
    var f48: Int64 = 0, f49: Int64 = 0, f50: Int64 = 0, f51: Int64 = 0, f52: Int64 = 0, f53: Int64 = 0, f54: Int64 = 0, f55: Int64 = 0
    var f56: Int64 = 0, f57: Int64 = 0, f58: Int64 = 0, f59: Int64 = 0, f60: Int64 = 0, f61: Int64 = 0, f62: Int64 = 0, f63: Int64 = 0
}

@ForyRow
private struct Wide65: Equatable {
    var f00: Int64 = 0, f01: Int64 = 0, f02: Int64 = 0, f03: Int64 = 0, f04: Int64 = 0, f05: Int64 = 0, f06: Int64 = 0, f07: Int64 = 0
    var f08: Int64 = 0, f09: Int64 = 0, f10: Int64 = 0, f11: Int64 = 0, f12: Int64 = 0, f13: Int64 = 0, f14: Int64 = 0, f15: Int64 = 0
    var f16: Int64 = 0, f17: Int64 = 0, f18: Int64 = 0, f19: Int64 = 0, f20: Int64 = 0, f21: Int64 = 0, f22: Int64 = 0, f23: Int64 = 0
    var f24: Int64 = 0, f25: Int64 = 0, f26: Int64 = 0, f27: Int64 = 0, f28: Int64 = 0, f29: Int64 = 0, f30: Int64 = 0, f31: Int64 = 0
    var f32: Int64 = 0, f33: Int64 = 0, f34: Int64 = 0, f35: Int64 = 0, f36: Int64 = 0, f37: Int64 = 0, f38: Int64 = 0, f39: Int64 = 0
    var f40: Int64 = 0, f41: Int64 = 0, f42: Int64 = 0, f43: Int64 = 0, f44: Int64 = 0, f45: Int64 = 0, f46: Int64 = 0, f47: Int64 = 0
    var f48: Int64 = 0, f49: Int64 = 0, f50: Int64 = 0, f51: Int64 = 0, f52: Int64 = 0, f53: Int64 = 0, f54: Int64 = 0, f55: Int64 = 0
    var f56: Int64 = 0, f57: Int64 = 0, f58: Int64 = 0, f59: Int64 = 0, f60: Int64 = 0, f61: Int64 = 0, f62: Int64 = 0, f63: Int64 = 0
    var f64: Int64 = 0
}

private enum Color: String, RowCodable {
    case red
    case green
}

@ForyRow
private struct AllNullable: Equatable {
    var flag: Bool?
    var tiny: Int8?
    var short: Int16?
    var int: Int32?
    var long: Int64?
    var word: Int?
    var real: Float?
    var double: Double?
    var text: String?
    var time: Date?
    var day: LocalDate?
    var span: Duration?
    var blob: Data?
    var list: [Int32]?
    var tags: Set<String>?
    var counts: [String: Int32]?
    var bar: Bar?
    var color: Color?
}

@ForyRow
private struct Nested: Equatable {
    var groups: [String: [Bar?]]?
}

@ForyRow
private struct Names: Equatable {
    var userID: Int8
    var httpURL: Int8
    var f1: Int8
    var field2Name: Int8
    var URLValue: Int8
    // swiftlint:disable:next identifier_name
    var x: Int8
    var already: Int8
}

@ForyRow
private struct Secret {
    private var token: String
    var id: Int32
}

// An initializer in the struct body would remove the memberwise initializer that decoding calls.
extension Secret {
    init(id: Int32, secret: String) {
        self.init(token: secret, id: id)
    }

    var revealed: String { token }
}

@ForyRow
private struct OptionalName {
    var name: String?
}

@ForyRow
private struct RequiredName {
    var name: String
}

private func checkRoundTrip<T: RowStruct & Equatable>(_ value: T, size: Int) throws {
    let encoder = RowEncoder<T>()
    let row = try encoder.toRow(value)
    #expect(row.count == size, "\(T.self)")
    #expect(try encoder.fromRow(row) == value, "\(T.self)")
}

@Test
func rowMacroSchemaGolden() throws {
    #expect(try Foo.rowSchema.toBytes() == goldenFooSchema)
    #expect(Foo.rowSchema.hash == 14_057_974_700_829_126)
    #expect(StringMap.rowSchema.hash == 15_842_757)
    #expect(Blob.rowSchema.hash == 527_672)
}

@Test
func rowMacroDecodeGolden() throws {
    let fooEncoder = RowEncoder<Foo>()
    #expect(try fooEncoder.fromRow(Data(goldenFooRow)) == fooValue)
    #expect(try fooEncoder.decode(fooEncoder.encode(fooValue)) == fooValue)
    let stringMapEncoder = RowEncoder<StringMap>()
    #expect(try stringMapEncoder.decode(Data(goldenStringMapEncoded)) == stringMapValue)
    #expect(try stringMapEncoder.decode(stringMapEncoder.encode(stringMapValue)) == stringMapValue)
    #expect(try RowEncoder<Blob>().decode(Data(goldenBlobEncoded)) == blobValue)
}

// Blob holds no map, so its bytes do not depend on dictionary order and must match exactly.
@Test
func rowMacroBlobBytes() throws {
    #expect(try RowEncoder<Blob>().encode(blobValue) == Data(goldenBlobEncoded))
}

@Test
func rowMacroFieldCounts() throws {
    try checkRoundTrip(Empty(), size: 0)
    var wide63 = Wide63()
    wide63.f00 = 1
    wide63.f62 = .min
    try checkRoundTrip(wide63, size: 8 + 63 * 8)
    var wide64 = Wide64()
    wide64.f00 = 1
    wide64.f63 = .min
    try checkRoundTrip(wide64, size: 8 + 64 * 8)
    var wide65 = Wide65()
    wide65.f00 = 1
    wide65.f64 = .min
    try checkRoundTrip(wide65, size: 16 + 65 * 8)
}

@Test
func rowMacroAllNull() throws {
    let encoder = RowEncoder<AllNullable>()
    let row = try encoder.toRow(AllNullable())
    // Eighteen fields set the low 18 bitmap bits, and every slot stays zero.
    #expect(Array(row) == [0xff, 0xff, 0x03] + [UInt8](repeating: 0, count: 5 + 18 * 8))
    #expect(try encoder.fromRow(row) == AllNullable())
}

@Test
func rowMacroAllValues() throws {
    let full = AllNullable(
        flag: true, tiny: -8, short: -16, int: 32, long: .min, word: .max, real: -0.0, double: .nan, text: "text",
        time: Date(timeIntervalSince1970: 1_700_000_000), day: LocalDate(epochDay: 19_000), span: .seconds(-90),
        blob: Data([1, 2]), list: [1, 2, 3], tags: ["a", "b"], counts: ["k": 1], bar: Bar(f1: nil, f2: "bar"), color: .green
    )
    let encoder = RowEncoder<AllNullable>()
    var decoded = try encoder.fromRow(encoder.toRow(full))
    #expect(decoded.real?.bitPattern == full.real?.bitPattern)
    #expect(decoded.double?.bitPattern == full.double?.bitPattern)
    // NaN never equals itself, so the floats are compared by bit pattern above and cleared here.
    var expected = full
    (expected.real, expected.double, decoded.real, decoded.double) = (nil, nil, nil, nil)
    #expect(decoded == expected)
}

@Test
func rowMacroNestedInListInMap() throws {
    let encoder = RowEncoder<Nested>()
    let values = [
        Nested(groups: ["a": [Bar(f1: 1, f2: "x"), nil, Bar(f1: nil, f2: nil)], "b": []]),
        Nested(groups: [:]),
        Nested(groups: nil)
    ]
    for value in values {
        #expect(try encoder.decode(encoder.encode(value)) == value)
    }
}

@Test
func rowMacroPrivateProperty() throws {
    let encoder = RowEncoder<Secret>()
    #expect(encoder.schema.fields.map(\.name) == ["id", "token"])
    let decoded = try encoder.decode(encoder.encode(Secret(id: 7, secret: "hidden")))
    #expect(decoded.id == 7)
    #expect(decoded.revealed == "hidden")
}

@Test
func rowMacroNames() throws {
    let encoder = RowEncoder<Names>()
    let expected = ["_u_r_l_value", "already", "f1", "field2_name", "http_u_r_l", "user_i_d", "x"]
    #expect(encoder.schema.fields.map(\.name) == expected)
    let names = Names(userID: 1, httpURL: 2, f1: 3, field2Name: 4, URLValue: 5, x: 6, already: 7)
    let row = try encoder.toRow(names)
    #expect(try encoder.fromRow(row) == names)
    // Slots follow the sorted names, so URLValue comes first and x last.
    let view = try Row(schema: encoder.schema, bytes: Array(row))
    #expect(try view.read(Int8.self, at: 0) == 5)
    #expect(try view.read(Int8.self, at: 6) == 6)
}

@Test
func rowMacroDeterministic() throws {
    let encoder = RowEncoder<Foo>()
    #expect(try encoder.encode(fooValue) == encoder.encode(fooValue))
}

// A row schema marks String nullable whether or not the property is optional, so both structs share
// one schema, and a null written by one cannot be read into the other.
@Test
func rowMacroNullIntoValue() throws {
    #expect(OptionalName.rowSchema == RequiredName.rowSchema)
    let row = try RowEncoder<OptionalName>().toRow(OptionalName(name: nil))
    #expect(throws: ForyError.invalidData("null row value cannot be read into non-optional String")) {
        try RowEncoder<RequiredName>().fromRow(row)
    }
}
