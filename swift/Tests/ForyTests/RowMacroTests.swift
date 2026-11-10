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

import SwiftSyntaxMacroExpansion
import SwiftSyntaxMacros
import SwiftSyntaxMacrosTestSupport
import Testing
@testable import ForyMacro

private let rowMacroSpecs = ["ForyRow": MacroSpec(type: ForyRowMacro.self, conformances: ["RowStruct"])]

private func assertRowExpansion(_ source: String, _ expandedSource: String) {
    assertMacroExpansion(source, expandedSource: expandedSource, macroSpecs: rowMacroSpecs)
}

// A rejected declaration expands to itself, without the attribute and with nothing added.
private func assertRowRejects(_ source: String, _ expandedSource: String, _ message: String, line: Int, column: Int) {
    assertMacroExpansion(
        source,
        expandedSource: expandedSource,
        diagnostics: [DiagnosticSpec(message: message, line: line, column: column)],
        macroSpecs: rowMacroSpecs
    )
}

@Test
func rowMacroExpansion() {
    assertRowExpansion(
        """
        @ForyRow
        struct Profile {
            var userID: Int64
            var age: Int32?
        }
        """,
        """
        struct Profile {
            var userID: Int64
            var age: Int32?

            static let rowSchema = RowSchema([
                RowField.inferred("age", (Int32?).self),
                RowField.inferred("user_i_d", (Int64).self)
            ])

            static func writeRowFields(_ value: Self, to writer: RowWriter) throws {
                try writer.write(value.age, at: 0)
                try writer.write(value.userID, at: 1)
            }

            static func readRowFields(from row: Row) throws -> Self {
                try Self(
                    userID: row.read(at: 1),
                    age: row.read(at: 0)
                )
            }
        }

        extension Profile: RowStruct {
        }
        """
    )
}

@Test
func rowMacroPublic() {
    assertRowExpansion(
        """
        @ForyRow
        public struct Empty {
        }
        """,
        """
        public struct Empty {

            public static let rowSchema = RowSchema([])

            public static func writeRowFields(_ value: Self, to writer: RowWriter) throws {

            }

            public static func readRowFields(from row: Row) throws -> Self {
                Self()
            }
        }

        extension Empty: RowStruct {
        }
        """
    )
}

@Test
func rowMacroSkips() {
    assertRowExpansion(
        """
        @ForyRow
        struct Counter {
            static var shared: Int32 = 0
            lazy var cache: Int32 = 0
            var doubled: Int32 { count * 2 }
            var label: String {
                get { "" }
                set {}
            }
            var count: Int32 {
                didSet {}
            }
        }
        """,
        """
        struct Counter {
            static var shared: Int32 = 0
            lazy var cache: Int32 = 0
            var doubled: Int32 { count * 2 }
            var label: String {
                get { "" }
                set {}
            }
            var count: Int32 {
                didSet {}
            }

            static let rowSchema = RowSchema([
                RowField.inferred("count", (Int32).self)
            ])

            static func writeRowFields(_ value: Self, to writer: RowWriter) throws {
                try writer.write(value.count, at: 0)
            }

            static func readRowFields(from row: Row) throws -> Self {
                try Self(
                    count: row.read(at: 0)
                )
            }
        }

        extension Counter: RowStruct {
        }
        """
    )
}

@Test
func rowMacroFieldName() {
    let cases = [
        ("userID", "user_i_d"), ("httpURL", "http_u_r_l"), ("f1", "f1"), ("field2Name", "field2_name"),
        ("URLValue", "_u_r_l_value"), ("x", "x"), ("already", "already")
    ]
    for (name, expected) in cases {
        #expect(rowFieldName(name) == expected, "\(name)")
    }
}

@Test
func rowMacroOrder() {
    assertRowExpansion(
        """
        @ForyRow
        struct Names {
            var x: Int8
            var userID: Int8
            var URLValue: Int8
            var already: Int8
        }
        """,
        """
        struct Names {
            var x: Int8
            var userID: Int8
            var URLValue: Int8
            var already: Int8

            static let rowSchema = RowSchema([
                RowField.inferred("_u_r_l_value", (Int8).self),
                RowField.inferred("already", (Int8).self),
                RowField.inferred("user_i_d", (Int8).self),
                RowField.inferred("x", (Int8).self)
            ])

            static func writeRowFields(_ value: Self, to writer: RowWriter) throws {
                try writer.write(value.URLValue, at: 0)
                try writer.write(value.already, at: 1)
                try writer.write(value.userID, at: 2)
                try writer.write(value.x, at: 3)
            }

            static func readRowFields(from row: Row) throws -> Self {
                try Self(
                    x: row.read(at: 3),
                    userID: row.read(at: 2),
                    URLValue: row.read(at: 0),
                    already: row.read(at: 1)
                )
            }
        }

        extension Names: RowStruct {
        }
        """
    )
}

@Test
func rowMacroBacktick() {
    assertRowExpansion(
        """
        @ForyRow
        struct Option {
            var `default`: Int32
        }
        """,
        """
        struct Option {
            var `default`: Int32

            static let rowSchema = RowSchema([
                RowField.inferred("default", (Int32).self)
            ])

            static func writeRowFields(_ value: Self, to writer: RowWriter) throws {
                try writer.write(value.`default`, at: 0)
            }

            static func readRowFields(from row: Row) throws -> Self {
                try Self(
                    `default`: row.read(at: 0)
                )
            }
        }

        extension Option: RowStruct {
        }
        """
    )
}

@Test
func rowMacroRejectsFieldTypes() {
    let cases: [(declaration: String, message: String)] = [
        ("var count: UInt32", "@ForyRow field 'count' uses unsigned type 'UInt32'; use Data for bytes or a signed integer type"),
        ("var ids: [UInt8]", "@ForyRow field 'ids' uses unsigned type 'UInt8'; use Data for bytes or a signed integer type"),
        ("var value: Int??", "@ForyRow field 'value' uses the nested optional 'Int??'"),
        ("var pair: (Int32, Int32)", "@ForyRow field 'pair' uses the tuple type '(Int32, Int32)', which has no row type"),
        ("var times: [Date: Int32]", "@ForyRow field 'times' uses 'Date' as a map key, which is not supported"),
        ("var names: [String?: Int32]", "@ForyRow field 'names' uses the optional map key 'String?'; map keys are never null"),
        ("let version: Int32 = 1", "@ForyRow field 'version' is a let with a default value, so decoding cannot set it; use var"),
        ("var count = 0", "@ForyRow field 'count' needs its own type annotation"),
        ("var next: [Bad]", "@ForyRow field 'next' refers to 'Bad' itself, which a row cannot hold")
    ]
    for (declaration, message) in cases {
        assertRowRejects(
            "@ForyRow\nstruct Bad {\n    \(declaration)\n}",
            "struct Bad {\n    \(declaration)\n}",
            message,
            line: 3,
            column: 9
        )
    }
}

@Test
func rowMacroRejectsDeclarations() {
    assertRowRejects(
        "@ForyRow\nstruct Bad {\n    var (a, b): (Int32, Int32)\n}",
        "struct Bad {\n    var (a, b): (Int32, Int32)\n}",
        "@ForyRow does not support the tuple pattern '(a, b)'; declare each property on its own",
        line: 3,
        column: 9
    )
    assertRowRejects(
        "@ForyRow\nstruct Bad {\n    @Wrapped var name: String\n}",
        "struct Bad {\n    @Wrapped var name: String\n}",
        "@ForyRow field 'name' uses an attribute or property wrapper, which row encoding does not support",
        line: 3,
        column: 5
    )
    // In `var a, b: Int32` only `b` carries the annotation.
    assertRowRejects(
        "@ForyRow\nstruct Bad {\n    var a, b: Int32\n}",
        "struct Bad {\n    var a, b: Int32\n}",
        "@ForyRow field 'a' needs its own type annotation",
        line: 3,
        column: 9
    )
    assertRowRejects(
        "@ForyRow\nenum Bad {\n    case one\n}",
        "enum Bad {\n    case one\n}",
        "@ForyRow can only be applied to a struct",
        line: 2,
        column: 1
    )
    assertRowRejects(
        "@ForyRow\nclass Bad {\n}",
        "class Bad {\n}",
        "@ForyRow can only be applied to a struct",
        line: 2,
        column: 1
    )
    assertRowRejects(
        "@ForyRow\nstruct Bad<T> {\n}",
        "struct Bad<T> {\n}",
        "@ForyRow does not support generic structs",
        line: 2,
        column: 11
    )
}

@Test
func rowMacroReportsEveryField() {
    assertMacroExpansion(
        "@ForyRow\nstruct Bad {\n    var a: UInt8\n    var b: Int??\n}",
        expandedSource: "struct Bad {\n    var a: UInt8\n    var b: Int??\n}",
        diagnostics: [
            DiagnosticSpec(
                message: "@ForyRow field 'a' uses unsigned type 'UInt8'; use Data for bytes or a signed integer type",
                line: 3,
                column: 9
            ),
            DiagnosticSpec(message: "@ForyRow field 'b' uses the nested optional 'Int??'", line: 4, column: 9)
        ],
        macroSpecs: rowMacroSpecs
    )
}
