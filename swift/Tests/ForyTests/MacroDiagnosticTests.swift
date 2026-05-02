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
        "ForyUnion": ForyUnionMacro.self,
        "ForyField": ForyFieldMacro.self,
        "ForyCase": ForyCaseMacro.self,
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
            @ForyCase(id: 1, payload: .uint64(encoding: .fixed))
            case deleted(UInt32)
        }
        """,
        expandedSource:
        """
        enum BadUnion {
            case deleted(UInt32)
        }
        """,
        message: "Fory field type hint .uint64 does not match Swift type UInt32"
    )
}
