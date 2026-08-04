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

import SwiftSyntaxMacros
import SwiftSyntaxMacrosTestSupport
import Testing
@testable import ForyMacro

private func foryMacros() -> [String: Macro.Type] {
    [
        "ForyStruct": ForyStructMacro.self,
        "ForyEnum": ForyEnumMacro.self,
        "ForyUnion": ForyUnionMacro.self,
        "ForyField": ForyFieldMacro.self,
        "ForyCase": ForyCaseMacro.self,
        "ForyUnknownCase": ForyUnknownCaseMacro.self,
        "ListField": ListFieldMacro.self,
        "ArrayField": ArrayFieldMacro.self,
        "SetField": SetFieldMacro.self,
        "MapField": MapFieldMacro.self
    ]
}

private func assertForyDiagnostic(
    _ source: String,
    expandedSource: String,
    message: String
) {
    assertMacroExpansion(
        source,
        expandedSource: expandedSource,
        diagnostics: [
            .init(
                message: message,
                line: 1,
                column: 1
            )
        ],
        macros: foryMacros()
    )
}

@Test
func listFieldRejectsWrongArgumentLabel() {
    assertForyDiagnostic(
        """
        @ForyStruct
        struct BadList {
            @ListField(value: .encoding(.fixed))
            var values: [Int32] = []
        }
        """,
        expandedSource:
            """
            struct BadList {
                var values: [Int32] = []
            }
            """,
        message: "@ListField supports only the 'element' argument"
    )
}

@Test
func mapFieldRequiresKeyOrValueHint() {
    assertForyDiagnostic(
        """
        @ForyStruct
        struct BadMap {
            @MapField()
            var data: [Int32: Int32] = [:]
        }
        """,
        expandedSource:
            """
            struct BadMap {
                var data: [Int32: Int32] = [:]
            }
            """,
        message: "@MapField requires a key or value hint"
    )
}

@Test
func nestedIntegerHintsRejectUnsupportedEncoding() {
    assertForyDiagnostic(
        """
        @ForyStruct
        struct BadEncoding {
            @ListField(element: .encoding(.tagged))
            var values: [Int32] = []
        }
        """,
        expandedSource:
            """
            struct BadEncoding {
                var values: [Int32] = []
            }
            """,
        message: "@ForyField(encoding: .tagged) is not supported for Int32"
    )
}

@Test
func fullTypeHintsRejectAliasShapeMismatch() {
    assertForyDiagnostic(
        """
        @ForyStruct
        struct BadAlias {
            @ForyField(type: .map(key: .string, value: .list(.int32(nullable: true, encoding: .fixed))))
            var data: [Int32: [Int32?]] = [:]
        }
        """,
        expandedSource:
            """
            struct BadAlias {
                var data: [Int32: [Int32?]] = [:]
            }
            """,
        message: "Fory field type hint .string does not match Swift type Int32"
    )
}

@Test
func duplicateFieldIDsAreRejected() {
    assertForyDiagnostic(
        """
        @ForyStruct
        struct BadIDs {
            @ForyField(id: 1)
            var first: Int32 = 0
            @ForyField(id: 1)
            var second: Int32 = 0
        }
        """,
        expandedSource:
            """
            struct BadIDs {
                var first: Int32 = 0
                var second: Int32 = 0
            }
            """,
        message: "duplicate @ForyField(id:) value 1 used by fields 'first' and 'second'"
    )
}

@Test
func unionPayloadHintsMustMatchPayloadType() {
    assertForyDiagnostic(
        """
        @ForyUnion
        enum BadUnion {
            @ForyUnknownCase
            case unknown(UnknownCase)
            @ForyCase(id: 1, payload: .uint64(encoding: .fixed))
            case deleted(UInt32)
        }
        """,
        expandedSource:
            """
            enum BadUnion {
                case unknown(UnknownCase)
                case deleted(UInt32)
            }
            """,
        message: "Fory field type hint .uint64 does not match Swift type UInt32"
    )
}

@Test
func unionRequiresUnknownCarrier() {
    assertForyDiagnostic(
        """
        @ForyUnion
        enum BadUnion {
            @ForyCase(id: 1)
            case dog(Dog)
        }
        """,
        expandedSource:
            """
            enum BadUnion {
                case dog(Dog)
            }
            """,
        message: "@ForyUnion requires @ForyUnknownCase case unknown(UnknownCase)"
    )
}

@Test
func unionRequiresRealCaseBeyondUnknown() {
    assertForyDiagnostic(
        """
        @ForyUnion
        enum OnlyUnknown {
            @ForyUnknownCase
            case unknown(UnknownCase)
        }
        """,
        expandedSource:
            """
            enum OnlyUnknown {
                case unknown(UnknownCase)
            }
            """,
        message: "@ForyUnion requires at least one non-unknown case; unknown is a forward-compatibility carrier and cannot be the default"
    )
}

@Test
func unionRejectsUnknownCaseLookalike() {
    assertForyDiagnostic(
        """
        enum Local {
            struct UnknownCase {}
        }
        @ForyUnion
        enum BadUnion {
            @ForyUnknownCase
            case unknown(Local.UnknownCase)
            @ForyCase(id: 1)
            case dog(Dog)
        }
        """,
        expandedSource:
            """
            enum Local {
                struct UnknownCase {}
            }
            enum BadUnion {
                case unknown(Local.UnknownCase)
                case dog(Dog)
            }
            """,
        message: "@ForyUnion unknown case must be @ForyUnknownCase case unknown(UnknownCase)"
    )
}

@Test
func unionRejectsUnknownMarkerWithWrongPayload() {
    assertForyDiagnostic(
        """
        @ForyUnion
        enum BadUnion {
            @ForyUnknownCase
            case unknown(String)
            @ForyCase(id: 0)
            case dog(Dog)
        }
        """,
        expandedSource:
            """
            enum BadUnion {
                case unknown(String)
                case dog(Dog)
            }
            """,
        message: "@ForyUnion unknown case must be @ForyUnknownCase case unknown(UnknownCase)"
    )
}

@Test
func unionUnknownRequiresMarker() {
    assertForyDiagnostic(
        """
        @ForyUnion
        enum BadUnion {
            case unknown(UnknownCase)
            @ForyCase(id: 1)
            case dog(Dog)
        }
        """,
        expandedSource:
            """
            enum BadUnion {
                case unknown(UnknownCase)
                case dog(Dog)
            }
            """,
        message: "@ForyUnion requires @ForyUnknownCase case unknown(UnknownCase)"
    )
}

@Test
func unionRejectsMultiplePayloadValues() {
    assertForyDiagnostic(
        """
        @ForyUnion
        enum BadUnion {
            @ForyUnknownCase
            case unknown(UnknownCase)
            case assign(target: String, value: Int32)
        }
        """,
        expandedSource:
            """
            enum BadUnion {
                case unknown(UnknownCase)
                case assign(target: String, value: Int32)
            }
            """,
        message: "@ForyUnion cases support zero or exactly one associated value"
    )
}

@Test
func fieldRejectsSerializerConflict() {
    assertForyDiagnostic(
        """
        @ForyStruct
        struct BadField {
            @ForyField(type: .string, with: String.self)
            var value: String = ""
        }
        """,
        expandedSource:
            """
            struct BadField {
                var value: String = ""
            }
            """,
        message: "@ForyField 'with' cannot be combined with 'encoding' or 'type'"
    )
}

@Test
func fieldRejectsEncodingAndTypeConflict() {
    assertForyDiagnostic(
        """
        @ForyStruct
        struct BadField {
            @ForyField(encoding: .fixed, type: .int32())
            var value: Int32 = 0
        }
        """,
        expandedSource:
            """
            struct BadField {
                var value: Int32 = 0
            }
            """,
        message: "@ForyField cannot specify both 'encoding' and 'type'"
    )
}

@Test
func packedArrayRejectsSerializer() {
    assertForyDiagnostic(
        """
        @ForyStruct
        struct BadArray {
            @ArrayField(element: .with(Int32.self))
            var values: [Int32] = []
        }
        """,
        expandedSource:
            """
            struct BadArray {
                var values: [Int32] = []
            }
            """,
        message: "array field hint requires a numeric or bool scalar element"
    )
}

@Test
func serializerExistentialRejected() {
    assertForyDiagnostic(
        """
        @ForyStruct
        struct BadValue {
            var value: any Serializer
        }
        """,
        expandedSource:
            """
            struct BadValue {
                var value: any Serializer
            }
            """,
        message:
            "fields cannot use 'any Serializer' as an application value; select a concrete application protocol with DynamicSerializer"
    )
}

@Test
func ignoredFieldRequiresExternalTarget() {
    assertForyDiagnostic(
        """
        @ForyStruct
        struct BadIgnoredField {
            @ForyField(ignore: true)
            var value: Int32 = 0
        }
        """,
        expandedSource:
            """
                struct BadIgnoredField {
                    var value: Int32 = 0
                }
            """,
        message: "@ForyField(ignore:) is only supported by external @ForyStruct declarations"
    )

    assertForyDiagnostic(
        """
        @ForyStruct
        struct BadExplicitFalse {
            @ForyField(ignore: false)
            var value: Int32 = 0
        }
        """,
        expandedSource:
            """
            struct BadExplicitFalse {
                var value: Int32 = 0
            }
            """,
        message: "@ForyField(ignore:) is only supported by external @ForyStruct declarations"
    )
}

@Test
func ignoredFieldRequiresLiteral() {
    assertForyDiagnostic(
        """
        @ForyStruct(target: External.self)
        struct BadIgnoredField {
            @ForyField(ignore: enabled)
            var value: Int32 = 0
        }
        """,
        expandedSource:
            """
            struct BadIgnoredField {
                var value: Int32 = 0
            }
            """,
        message: "@ForyField ignore must be a boolean literal"
    )
}

@Test
func ignoredFieldRejectsWireOptions() {
    assertForyDiagnostic(
        """
        @ForyStruct(target: External.self)
        struct BadIgnoredField {
            @ForyField(id: 1, ignore: true)
            var value: Int32 = 0
        }
        """,
        expandedSource:
            """
            struct BadIgnoredField {
                var value: Int32 = 0
            }
            """,
        message: "@ForyField(ignore: true) cannot be combined with wire or nested field options"
    )
}

@Test
func ignoredFieldRejectsNestedOptions() {
    assertForyDiagnostic(
        """
        @ForyStruct(target: External.self)
        struct BadIgnoredField {
            @ForyField(ignore: true)
            @ListField(element: .int32())
            var value: [Int32] = []
        }
        """,
        expandedSource:
            """
            struct BadIgnoredField {
                var value: [Int32] = []
            }
            """,
        message: "@ForyField(ignore: true) cannot be combined with wire or nested field options"
    )
}

@Test
func ignoredFieldShapeDiagnostics() {
    assertForyDiagnostic(
        """
        @ForyStruct(target: External.self)
        final class BadStaticField {
            @ForyField(ignore: true)
            static var value: Int32 = 0
        }
        """,
        expandedSource:
            """
            final class BadStaticField {
                static var value: Int32 = 0
            }
            """,
        message: "@ForyField(ignore:) requires a named instance stored property"
    )

    assertForyDiagnostic(
        """
        @ForyStruct(target: External.self)
        struct BadPatternField {
            @ForyField(ignore: true)
            var (first, second): (Int32, Int32) = (0, 0)
        }
        """,
        expandedSource:
            """
            struct BadPatternField {
                var (first, second): (Int32, Int32) = (0, 0)
            }
            """,
        message: "@ForyField(ignore:) requires a named instance stored property"
    )

    assertForyDiagnostic(
        """
        @ForyStruct(target: External.self)
        final class BadComputedField {
            @ForyField(ignore: true)
            var value: Int32 {
                0
            }
        }
        """,
        expandedSource:
            """
            final class BadComputedField {
                var value: Int32 {
                    0
                }
            }
            """,
        message: "@ForyField(ignore:) requires a named instance stored property"
    )

    assertForyDiagnostic(
        """
        @ForyStruct(target: External.self)
        struct BadMultiBinding {
            @ForyField(ignore: true)
            var first: Int32 = 0, second: Int32 = 0
        }
        """,
        expandedSource:
            """
            struct BadMultiBinding {
                var first: Int32 = 0, second: Int32 = 0
            }
            """,
        message: "Fory field annotations can only be used on a single stored property"
    )
}
