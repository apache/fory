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

import SwiftDiagnostics
import SwiftSyntax
import SwiftSyntaxMacros

// Adds `rowSchema`, `writeRowFields`, and `readRowFields` to a struct and makes it a `RowStruct`.
// Fields go into the schema sorted by name and renamed to lower underscore form, so every runtime
// agrees on the slot order and the field names. Decoding calls the memberwise initializer.
public struct ForyRowMacro: MemberMacro, ExtensionMacro {
    public static func expansion(
        of _: AttributeSyntax,
        providingMembersOf declaration: some DeclGroupSyntax,
        conformingTo _: [TypeSyntax],
        in _: some MacroExpansionContext
    ) throws -> [DeclSyntax] {
        let fields = try parseRowFields(declaration)
        let access = serializerMemberAccessPrefix(declaration)
        let sorted = fields.indices.sorted { fields[$0].name.utf16.lexicographicallyPrecedes(fields[$1].name.utf16) }
        var ordinals = [Int](repeating: 0, count: fields.count)
        for (ordinal, fieldIndex) in sorted.enumerated() {
            ordinals[fieldIndex] = ordinal
        }

        let schemaFields = sorted.map { index in
            "    RowField.inferred(\"\(rowFieldName(fields[index].name))\", (\(fields[index].typeText)).self)"
        }
        let schema = schemaFields.isEmpty ? "RowSchema([])" : "RowSchema([\n\(schemaFields.joined(separator: ",\n"))\n])"
        let writes = sorted.enumerated().map { ordinal, index in
            "    try writer.write(value.\(fields[index].codeName), at: \(ordinal))"
        }
        let arguments = fields.indices.map { index in
            "        \(fields[index].codeName): row.read(at: \(ordinals[index]))"
        }
        // With no fields there is nothing that throws, and a bare `try` would warn.
        let construction = arguments.isEmpty ? "Self()" : "try Self(\n\(arguments.joined(separator: ",\n"))\n    )"

        return [
            "\(raw: access)static let rowSchema = \(raw: schema)",
            """
            \(raw: access)static func writeRowFields(_ value: Self, to writer: RowWriter) throws {
            \(raw: writes.joined(separator: "\n"))
            }
            """,
            """
            \(raw: access)static func readRowFields(from row: Row) throws -> Self {
                \(raw: construction)
            }
            """
        ]
    }

    public static func expansion(
        of _: AttributeSyntax,
        attachedTo declaration: some DeclGroupSyntax,
        providingExtensionsOf type: some TypeSyntaxProtocol,
        conformingTo protocols: [TypeSyntax],
        in _: some MacroExpansionContext
    ) throws -> [ExtensionDeclSyntax] {
        // The member expansion reports what is wrong. Adding the conformance anyway would only add
        // errors about missing members on top.
        guard !protocols.isEmpty, (try? parseRowFields(declaration)) != nil else {
            return []
        }
        return [try ExtensionDeclSyntax("extension \(type.trimmed): RowStruct {}")]
    }
}

// Each ASCII capital letter becomes an underscore followed by its lowercase letter, and every other
// character stays, so `userID` becomes `user_i_d` and `URLValue` becomes `_u_r_l_value`.
func rowFieldName(_ name: String) -> String {
    var result = String.UnicodeScalarView()
    for scalar in name.unicodeScalars {
        if scalar.isASCII, let ascii = UInt8(exactly: scalar.value), ascii >= 0x41, ascii <= 0x5A {
            result.append("_")
            result.append(Unicode.Scalar(ascii + 0x20))
        } else {
            result.append(scalar)
        }
    }
    return String(result)
}

private struct RowMacroField {
    // Without backticks, for sorting and the schema name.
    let name: String
    // As written, so a keyword such as `default` keeps its backticks in generated code.
    let codeName: String
    let typeText: String
}

private let rowUnsignedTypes: Set<String> = ["UInt", "UInt8", "UInt16", "UInt32", "UInt64"]

private func parseRowFields(_ declaration: some DeclGroupSyntax) throws -> [RowMacroField] {
    guard let structDecl = declaration.as(StructDeclSyntax.self) else {
        throw DiagnosticsError(diagnostics: [rowDiagnostic(declaration.introducer, "@ForyRow can only be applied to a struct")])
    }
    if let generics = structDecl.genericParameterClause {
        throw DiagnosticsError(diagnostics: [rowDiagnostic(generics, "@ForyRow does not support generic structs")])
    }
    let structName = structDecl.name.text
    var fields: [RowMacroField] = []
    var diagnostics: [Diagnostic] = []
    for member in structDecl.memberBlock.members {
        guard let variable = member.decl.as(VariableDeclSyntax.self) else {
            continue
        }
        let skipped: [TokenKind] = [.keyword(.static), .keyword(.class), .keyword(.lazy)]
        if variable.modifiers.contains(where: { skipped.contains($0.name.tokenKind) }) {
            continue
        }
        let isLet = variable.bindingSpecifier.tokenKind == .keyword(.let)
        for binding in variable.bindings {
            guard let pattern = binding.pattern.as(IdentifierPatternSyntax.self) else {
                let text = binding.pattern.trimmedDescription
                diagnostics.append(
                    rowDiagnostic(binding.pattern, "@ForyRow does not support the tuple pattern '\(text)'; declare each property on its own")
                )
                continue
            }
            guard rowIsStored(binding.accessorBlock) else {
                continue
            }
            let name = pattern.identifier.identifier?.name ?? pattern.identifier.text
            if let attribute = variable.attributes.first {
                diagnostics.append(
                    rowDiagnostic(attribute, "@ForyRow field '\(name)' uses an attribute or property wrapper, which row encoding does not support")
                )
                continue
            }
            // The memberwise initializer has no parameter for such a property, so decoding could not set it.
            if isLet, binding.initializer != nil {
                diagnostics.append(rowDiagnostic(binding, "@ForyRow field '\(name)' is a let with a default value, so decoding cannot set it; use var"))
                continue
            }
            guard let type = binding.typeAnnotation?.type else {
                diagnostics.append(rowDiagnostic(binding, "@ForyRow field '\(name)' needs its own type annotation"))
                continue
            }
            if let problem = rowTypeProblem(type, structName: structName) {
                diagnostics.append(rowDiagnostic(binding, "@ForyRow field '\(name)' \(problem)"))
                continue
            }
            fields.append(RowMacroField(name: name, codeName: pattern.identifier.text, typeText: type.trimmedDescription))
        }
    }
    if !diagnostics.isEmpty {
        throw DiagnosticsError(diagnostics: diagnostics)
    }
    return fields
}

// A property is stored when it has no accessors, or only willSet and didSet observers.
private func rowIsStored(_ accessorBlock: AccessorBlockSyntax?) -> Bool {
    guard let accessorBlock else {
        return true
    }
    guard case .accessors(let accessors) = accessorBlock.accessors else {
        return false
    }
    let observers: [TokenKind] = [.keyword(.willSet), .keyword(.didSet)]
    return accessors.allSatisfy { observers.contains($0.accessorSpecifier.tokenKind) }
}

// Returns why `type` cannot be a row field, or nil. Anything not rejected here must still conform to
// RowCodable, which the compiler checks.
private func rowTypeProblem(_ type: TypeSyntax, structName: String) -> String? {
    if let optional = type.as(OptionalTypeSyntax.self) {
        if rowIsOptional(optional.wrappedType) {
            return "uses the nested optional '\(type.trimmedDescription)'"
        }
        return rowTypeProblem(optional.wrappedType, structName: structName)
    }
    if type.is(ImplicitlyUnwrappedOptionalTypeSyntax.self) {
        return "uses the implicitly unwrapped optional '\(type.trimmedDescription)'; use '?' instead"
    }
    if let array = type.as(ArrayTypeSyntax.self) {
        return rowTypeProblem(array.element, structName: structName)
    }
    if let dictionary = type.as(DictionaryTypeSyntax.self) {
        return rowMapProblem(key: dictionary.key, value: dictionary.value, structName: structName)
    }
    if let tuple = type.as(TupleTypeSyntax.self) {
        // `(Int32)` is only parentheses around one type.
        if tuple.elements.count == 1, let only = tuple.elements.first, only.firstName == nil {
            return rowTypeProblem(only.type, structName: structName)
        }
        return "uses the tuple type '\(type.trimmedDescription)', which has no row type"
    }
    if let identifier = type.as(IdentifierTypeSyntax.self) {
        let name = identifier.name.text
        if name == structName || name == "Self" {
            return "refers to '\(structName)' itself, which a row cannot hold"
        }
        return rowNamedTypeProblem(name, identifier.genericArgumentClause, whole: type, structName: structName)
    }
    if let member = type.as(MemberTypeSyntax.self) {
        return rowNamedTypeProblem(member.name.text, member.genericArgumentClause, whole: type, structName: structName)
    }
    return "uses the type '\(type.trimmedDescription)', which has no row type"
}

private func rowNamedTypeProblem(
    _ name: String,
    _ generics: GenericArgumentClauseSyntax?,
    whole: TypeSyntax,
    structName: String
) -> String? {
    if rowUnsignedTypes.contains(name) {
        return "uses unsigned type '\(name)'; use Data for bytes or a signed integer type"
    }
    let arguments = generics?.arguments.map(\.argument) ?? []
    if name == "Optional", let wrapped = arguments.first, rowIsOptional(wrapped) {
        return "uses the nested optional '\(whole.trimmedDescription)'"
    }
    if name == "Dictionary", arguments.count == 2 {
        return rowMapProblem(key: arguments[0], value: arguments[1], structName: structName)
    }
    for argument in arguments {
        if let problem = rowTypeProblem(argument, structName: structName) {
            return problem
        }
    }
    return nil
}

// Map keys are never null, and a Date or Duration key is rounded to whole microseconds when written,
// so two different keys could come back equal.
private func rowMapProblem(key: TypeSyntax, value: TypeSyntax, structName: String) -> String? {
    if rowIsOptional(key) {
        return "uses the optional map key '\(key.trimmedDescription)'; map keys are never null"
    }
    let keyName = key.as(IdentifierTypeSyntax.self)?.name.text ?? key.as(MemberTypeSyntax.self)?.name.text
    if let keyName, keyName == "Date" || keyName == "Duration" {
        return "uses '\(keyName)' as a map key, which is not supported"
    }
    return rowTypeProblem(key, structName: structName) ?? rowTypeProblem(value, structName: structName)
}

private func rowIsOptional(_ type: TypeSyntax) -> Bool {
    type.is(OptionalTypeSyntax.self) || type.is(ImplicitlyUnwrappedOptionalTypeSyntax.self)
        || type.as(IdentifierTypeSyntax.self)?.name.text == "Optional"
}

private func rowDiagnostic(_ node: some SyntaxProtocol, _ message: String) -> Diagnostic {
    Diagnostic(node: node, message: MacroExpansionErrorMessage(message))
}
