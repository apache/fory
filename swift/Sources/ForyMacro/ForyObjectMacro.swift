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
// swiftlint:disable file_length

import SwiftCompilerPlugin
import SwiftDiagnostics
import SwiftSyntax
import SwiftSyntaxBuilder
import SwiftSyntaxMacros

@main
struct ForySwiftPlugin: CompilerPlugin {
    let providingMacros: [Macro.Type] = [
        ForyStructMacro.self,
        ForyEnumMacro.self,
        ForyUnionMacro.self,
        ForyFieldMacro.self,
        ListFieldMacro.self,
        ArrayFieldMacro.self,
        SetFieldMacro.self,
        MapFieldMacro.self,
        ForyCaseMacro.self
    ]
}

public struct ForyStructMacro: MemberMacro, ExtensionMacro {
    public static func expansion(
        of attribute: AttributeSyntax,
        providingMembersOf declaration: some DeclGroupSyntax,
        conformingTo _: [TypeSyntax],
        in _: some MacroExpansionContext
    ) throws -> [DeclSyntax] {
        let accessPrefix = serializerMemberAccessPrefix(declaration)
        let objectConfig = try parseForyObjectConfiguration(attribute)

        if declaration.is(EnumDeclSyntax.self) {
            throw MacroExpansionErrorMessage("@ForyStruct supports struct and class declarations only")
        }

        let parsed = try parseFields(declaration)
        let sortedFields = sortFields(parsed.fields)

        let staticTypeIDDecl: DeclSyntax = """
        \(raw: accessPrefix)static var staticTypeId: TypeId { .structType }
        """
        let evolvingDecl: DeclSyntax = """
        \(raw: accessPrefix)static var foryEvolving: Bool { \(raw: objectConfig.evolving ? "true" : "false") }
        """

        let referenceTrackDecl: DeclSyntax? = parsed.isClass ? """
        \(raw: accessPrefix)static var isRefType: Bool { true }
        """ : nil

        let schemaHashDecl: DeclSyntax = DeclSyntax(stringLiteral: try buildSchemaHashDecl(fields: parsed.fields))
        let compatibleTypeMetaDecl: DeclSyntax = DeclSyntax(
            stringLiteral: buildCompatibleTypeMetaFieldsDecl(sortedFields: sortedFields, accessPrefix: accessPrefix)
        )
        let defaultDecl: DeclSyntax = DeclSyntax(
            stringLiteral: buildDefaultDecl(isClass: parsed.isClass, fields: parsed.fields, accessPrefix: accessPrefix)
        )
        let writeWrapperDecl: DeclSyntax = DeclSyntax(stringLiteral: buildWriteWrapperDecl(accessPrefix: accessPrefix))
        let readWrapperDecl: DeclSyntax? = parsed.isClass
            ? DeclSyntax(stringLiteral: buildClassReadWrapperDecl(accessPrefix: accessPrefix))
            : nil
        let writeDecl: DeclSyntax = DeclSyntax(
            stringLiteral: buildWriteDataDecl(sortedFields: sortedFields, accessPrefix: accessPrefix)
        )
        let readDecl: DeclSyntax = DeclSyntax(
            stringLiteral: buildReadDataDecl(
                isClass: parsed.isClass,
                fields: parsed.fields,
                sortedFields: sortedFields,
                accessPrefix: accessPrefix
            )
        )
        let readCompatibleDecl: DeclSyntax = DeclSyntax(
            stringLiteral: buildReadCompatibleDataDecl(
                isClass: parsed.isClass,
                fields: parsed.fields,
                sortedFields: sortedFields,
                accessPrefix: accessPrefix
            )
        )
        return [
            staticTypeIDDecl,
            evolvingDecl,
            referenceTrackDecl,
            schemaHashDecl,
            compatibleTypeMetaDecl,
            defaultDecl,
            writeWrapperDecl,
            readWrapperDecl,
            writeDecl,
            readDecl,
            readCompatibleDecl
        ].compactMap { $0 }
    }

    public static func expansion(
        of _: AttributeSyntax,
        attachedTo declaration: some DeclGroupSyntax,
        providingExtensionsOf type: some TypeSyntaxProtocol,
        conformingTo _: [TypeSyntax],
        in _: some MacroExpansionContext
    ) throws -> [ExtensionDeclSyntax] {
        _ = declaration
        let typeName = type.trimmedDescription
        guard !typeName.isEmpty else {
            return []
        }

        let extensionDecl: ExtensionDeclSyntax = try ExtensionDeclSyntax(
            "extension \(raw: typeName): Serializer, StructSerializer {}"
        )
        return [extensionDecl]
    }
}

public struct ForyEnumMacro: MemberMacro, ExtensionMacro {
    public static func expansion(
        of _: AttributeSyntax,
        providingMembersOf declaration: some DeclGroupSyntax,
        conformingTo _: [TypeSyntax],
        in _: some MacroExpansionContext
    ) throws -> [DeclSyntax] {
        let accessPrefix = serializerMemberAccessPrefix(declaration)
        guard let enumDecl = declaration.as(EnumDeclSyntax.self) else {
            throw MacroExpansionErrorMessage("@ForyEnum supports enum declarations only")
        }
        let parsedEnum = try parseEnumDecl(enumDecl)
        guard parsedEnum.kind == .ordinal else {
            throw MacroExpansionErrorMessage("@ForyEnum cases cannot have associated values; use @ForyUnion")
        }
        return buildEnumDecls(parsedEnum, accessPrefix: accessPrefix)
    }

    public static func expansion(
        of _: AttributeSyntax,
        attachedTo _: some DeclGroupSyntax,
        providingExtensionsOf type: some TypeSyntaxProtocol,
        conformingTo _: [TypeSyntax],
        in _: some MacroExpansionContext
    ) throws -> [ExtensionDeclSyntax] {
        let typeName = type.trimmedDescription
        guard !typeName.isEmpty else {
            return []
        }
        return [try ExtensionDeclSyntax("extension \(raw: typeName): Serializer {}")]
    }
}

public struct ForyUnionMacro: MemberMacro, ExtensionMacro {
    public static func expansion(
        of _: AttributeSyntax,
        providingMembersOf declaration: some DeclGroupSyntax,
        conformingTo _: [TypeSyntax],
        in _: some MacroExpansionContext
    ) throws -> [DeclSyntax] {
        let accessPrefix = serializerMemberAccessPrefix(declaration)
        guard let enumDecl = declaration.as(EnumDeclSyntax.self) else {
            throw MacroExpansionErrorMessage("@ForyUnion supports enum declarations only")
        }
        let parsedEnum = try parseEnumDecl(enumDecl)
        guard parsedEnum.kind == .taggedUnion else {
            throw MacroExpansionErrorMessage("@ForyUnion requires at least one associated-value case; use @ForyEnum")
        }
        return buildEnumDecls(parsedEnum, accessPrefix: accessPrefix)
    }

    public static func expansion(
        of _: AttributeSyntax,
        attachedTo _: some DeclGroupSyntax,
        providingExtensionsOf type: some TypeSyntaxProtocol,
        conformingTo _: [TypeSyntax],
        in _: some MacroExpansionContext
    ) throws -> [ExtensionDeclSyntax] {
        let typeName = type.trimmedDescription
        guard !typeName.isEmpty else {
            return []
        }
        return [try ExtensionDeclSyntax("extension \(raw: typeName): Serializer {}")]
    }
}

public struct ForyFieldMacro: PeerMacro {
    public static func expansion(
        of _: AttributeSyntax,
        providingPeersOf _: some DeclSyntaxProtocol,
        in _: some MacroExpansionContext
    ) throws -> [DeclSyntax] {
        []
    }
}

public struct ListFieldMacro: PeerMacro {
    public static func expansion(
        of _: AttributeSyntax,
        providingPeersOf _: some DeclSyntaxProtocol,
        in _: some MacroExpansionContext
    ) throws -> [DeclSyntax] {
        []
    }
}

public struct ArrayFieldMacro: PeerMacro {
    public static func expansion(
        of _: AttributeSyntax,
        providingPeersOf _: some DeclSyntaxProtocol,
        in _: some MacroExpansionContext
    ) throws -> [DeclSyntax] {
        []
    }
}

public struct SetFieldMacro: PeerMacro {
    public static func expansion(
        of _: AttributeSyntax,
        providingPeersOf _: some DeclSyntaxProtocol,
        in _: some MacroExpansionContext
    ) throws -> [DeclSyntax] {
        []
    }
}

public struct MapFieldMacro: PeerMacro {
    public static func expansion(
        of _: AttributeSyntax,
        providingPeersOf _: some DeclSyntaxProtocol,
        in _: some MacroExpansionContext
    ) throws -> [DeclSyntax] {
        []
    }
}

public struct ForyCaseMacro: PeerMacro {
    public static func expansion(
        of _: AttributeSyntax,
        providingPeersOf _: some DeclSyntaxProtocol,
        in _: some MacroExpansionContext
    ) throws -> [DeclSyntax] {
        []
    }
}

private func serializerMemberAccessPrefix(_ declaration: some DeclGroupSyntax) -> String {
    let isPublicType = declaration.modifiers.contains(where: { modifier in
        modifier.name.tokenKind == .keyword(.public) || modifier.name.tokenKind == .keyword(.open)
    })
    guard isPublicType else {
        return ""
    }
    return "public "
}

private enum FieldEncoding: String {
    case varint
    case fixed
    case tagged
}

enum DynamicAnyCodecKind {
    case anyValue
    case anyHashableValue
    case anyList
    case stringAnyMap
    case int32AnyMap
    case anyHashableAnyMap
}

struct ParsedField {
    let name: String
    let typeText: String
    fileprivate let typeHint: FieldTypeHint?
    let originalIndex: Int

    let isOptional: Bool
    let isCollection: Bool
    let fieldID: Int?
    let schemaIdentifier: String
    let fieldIdentifier: String

    let group: Int
    let typeID: UInt32
    let isCompressedNumeric: Bool
    let primitiveSize: Int
    let customCodecType: String?
    let dynamicAnyCodec: DynamicAnyCodecKind?
}

private struct ParsedDecl {
    let isClass: Bool
    let fields: [ParsedField]
}

private enum ParsedEnumKind: Equatable {
    case ordinal
    case taggedUnion
}

private struct ParsedEnumPayloadField {
    let label: String?
    let typeText: String
    let isOptional: Bool
    let hasGenerics: Bool
    let customCodecType: String?
}

private struct ParsedEnumCase {
    let name: String
    let payload: [ParsedEnumPayloadField]
    let caseID: Int?
    let wireValue: UInt32?
}

private struct ParsedEnumDecl {
    let kind: ParsedEnumKind
    let cases: [ParsedEnumCase]
}

private struct FieldTypeResolution {
    let classification: TypeClassification
    let customCodecType: String?
}

private indirect enum FieldTypeHint {
    case inferredEncoding(FieldEncoding)
    case scalar(name: String, nullable: Bool?, encoding: FieldEncoding?)
    case list(element: FieldTypeHint)
    case array(element: FieldTypeHint)
    case set(element: FieldTypeHint)
    case map(key: FieldTypeHint?, value: FieldTypeHint?)
}

private struct ParsedForyFieldConfiguration {
    let encoding: FieldEncoding?
    let id: Int?
    let typeHint: FieldTypeHint?
}

private struct ParsedForyCaseConfiguration {
    let id: Int?
    let payloadHint: FieldTypeHint?
}

private struct ParsedForyObjectConfiguration {
    let evolving: Bool
}

private func parseEnumDecl(_ enumDecl: EnumDeclSyntax) throws -> ParsedEnumDecl {
    var cases: [ParsedEnumCase] = []
    let integerRawEnum = enumDeclUsesExplicitIntegerRawValues(enumDecl)

    for member in enumDecl.memberBlock.members {
        guard let caseDecl = member.decl.as(EnumCaseDeclSyntax.self) else {
            continue
        }

        let caseConfig = try parseForyCaseConfiguration(from: caseDecl.attributes)
        if caseConfig?.id != nil, caseDecl.elements.count != 1 {
            throw MacroExpansionErrorMessage("@ForyCase(id:) enum case declarations must contain exactly one case")
        }

        for element in caseDecl.elements {
            let caseName = element.name.text
            if caseName.isEmpty {
                continue
            }

            var payloadFields: [ParsedEnumPayloadField] = []
            if let parameterClause = element.parameterClause {
                for parameter in parameterClause.parameters {
                    if parameter.defaultValue != nil {
                        throw MacroExpansionErrorMessage(
                            "Fory enum associated values cannot have default values"
                        )
                    }

                    let payloadType = parameter.type.trimmedDescription
                    if caseConfig?.payloadHint != nil, parameterClause.parameters.count != 1 {
                        throw MacroExpansionErrorMessage("@ForyCase(payload:) requires exactly one associated value")
                    }
                    let optional = unwrapOptional(payloadType)
                    let classification = classifyType(optional.type)
                    let hasGenerics = classification.isCollection || classification.isMap
                    let customCodecType = try caseConfig?.payloadHint.map {
                        try codecTypeExpression(typeText: optional.type, hint: $0)
                    }
                    let label: String?
                    if let firstName = parameter.firstName, firstName.text != "_" {
                        label = firstName.text
                    } else {
                        label = nil
                    }

                    payloadFields.append(
                        .init(
                            label: label,
                            typeText: payloadType,
                            isOptional: optional.isOptional,
                            hasGenerics: hasGenerics,
                            customCodecType: customCodecType
                        )
                    )
                }
            }
            cases.append(
                .init(
                    name: caseName,
                    payload: payloadFields,
                    caseID: caseConfig?.id,
                    wireValue: integerRawEnum ? parseEnumCaseWireValue(element) : nil
                )
            )
        }
    }

    guard !cases.isEmpty else {
        throw MacroExpansionErrorMessage("Fory enum must define at least one case")
    }

    var seenCaseIDs: [Int: String] = [:]
    for enumCase in cases {
        guard let caseID = enumCase.caseID else {
            continue
        }
        if let existing = seenCaseIDs[caseID], existing != enumCase.name {
            throw MacroExpansionErrorMessage(
                "duplicate @ForyCase(id:) value \(caseID) used by enum cases '\(existing)' and '\(enumCase.name)'"
            )
        }
        seenCaseIDs[caseID] = enumCase.name
    }

    let hasPayload = cases.contains { !$0.payload.isEmpty }
    if hasPayload {
        return .init(kind: .taggedUnion, cases: cases)
    }

    return .init(kind: .ordinal, cases: cases)
}

private func buildEnumDecls(_ parsedEnum: ParsedEnumDecl, accessPrefix: String) -> [DeclSyntax] {
    switch parsedEnum.kind {
    case .ordinal:
        return buildOrdinalEnumDecls(parsedEnum.cases, accessPrefix: accessPrefix)
    case .taggedUnion:
        return buildTaggedUnionEnumDecls(parsedEnum.cases, accessPrefix: accessPrefix)
    }
}

private func buildOrdinalEnumDecls(_ cases: [ParsedEnumCase], accessPrefix: String) -> [DeclSyntax] {
    let defaultCase = cases[0].name
    let useExplicitWireValues = cases.allSatisfy { $0.wireValue != nil }
    let writeSwitchCases = cases.enumerated().map { index, enumCase in
        let wireValue = enumCase.wireValue ?? UInt32(index)
        return """
        case .\(enumCase.name):
            context.buffer.writeVarUInt32(\(wireValue))
        """
    }.joined(separator: "\n        ")
    let readSwitchCases = cases.enumerated().map { index, enumCase in
        let wireValue = enumCase.wireValue ?? UInt32(index)
        return "case \(wireValue): return .\(enumCase.name)"
    }.joined(separator: "\n        ")
    let errorLabel = useExplicitWireValues ? "enum value" : "enum ordinal"

    let defaultDecl: DeclSyntax = DeclSyntax(
        stringLiteral: """
        \(accessPrefix)static func foryDefault() -> Self {
            .\(defaultCase)
        }
        """
    )

    let staticTypeIDDecl: DeclSyntax = """
    \(raw: accessPrefix)static var staticTypeId: TypeId { .enumType }
    """
    let writeWrapperDecl: DeclSyntax = DeclSyntax(stringLiteral: buildWriteWrapperDecl(accessPrefix: accessPrefix))

    let writeDecl: DeclSyntax = DeclSyntax(
        stringLiteral: """
        @inline(__always)
        \(accessPrefix)func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
            _ = hasGenerics
            switch self {
            \(writeSwitchCases)
            }
        }
        """
    )

    let readDecl: DeclSyntax = DeclSyntax(
        stringLiteral: """
        @inline(__always)
        \(accessPrefix)static func foryReadData(_ context: ReadContext) throws -> Self {
            let ordinal = try context.buffer.readVarUInt32()
            switch ordinal {
            \(readSwitchCases)
            default:
                throw ForyError.invalidData("unknown \(errorLabel) \\(ordinal)")
            }
        }
        """
    )

    return [defaultDecl, staticTypeIDDecl, writeWrapperDecl, writeDecl, readDecl]
}

private func enumDeclUsesExplicitIntegerRawValues(_ enumDecl: EnumDeclSyntax) -> Bool {
    guard let inheritanceClause = enumDecl.inheritanceClause else {
        return false
    }
    let inheritedTypes = inheritanceClause.inheritedTypes.map { $0.type.trimmedDescription }
    return inheritedTypes.contains {
        [
            "Int",
            "Int8",
            "Int16",
            "Int32",
            "Int64",
            "UInt",
            "UInt8",
            "UInt16",
            "UInt32",
            "UInt64"
        ].contains($0)
    }
}

private func parseEnumCaseWireValue(_ element: EnumCaseElementSyntax) -> UInt32? {
    guard let rawValue = element.rawValue?.value.trimmedDescription,
          let parsed = UInt32(rawValue)
    else {
        return nil
    }
    return parsed
}

private func buildTaggedUnionEnumDecls(_ cases: [ParsedEnumCase], accessPrefix: String) -> [DeclSyntax] {
    let defaultExpr = enumCaseDefaultExpr(cases[0])
    let writeSwitchCases = cases.enumerated().map { index, enumCase in
        let caseID = enumCase.caseID ?? index
        var lines: [String] = []
        lines.append("case \(enumCasePattern(enumCase)):")
        lines.append("    context.buffer.writeVarUInt32(\(caseID))")
        for (payloadIndex, payloadField) in enumCase.payload.enumerated() {
            let variableName = "__value\(payloadIndex)"
            let hasGenerics = payloadField.hasGenerics ? "true" : "false"
            if let codecType = payloadField.customCodecType {
                let payloadCodec = payloadField.isOptional ? "OptionalFieldCodec<\(codecType)>" : codecType
                lines.append(
                    "    try \(payloadCodec).write(\(variableName), context, refMode: .tracking, writeTypeInfo: true)"
                )
            } else {
                lines.append(
                    "    try \(variableName).foryWrite(context, refMode: .tracking, writeTypeInfo: true, hasGenerics: \(hasGenerics))"
                )
            }
        }
        return lines.joined(separator: "\n")
    }.joined(separator: "\n        ")

    let readSwitchCases = cases.enumerated().map { index, enumCase in
        let caseID = enumCase.caseID ?? index
        if enumCase.payload.isEmpty {
            return """
            case \(caseID):
                return .\(enumCase.name)
            """
        }

        var lines: [String] = ["case \(caseID):"]
        for (payloadIndex, payloadField) in enumCase.payload.enumerated() {
            if let codecType = payloadField.customCodecType {
                let payloadCodec = payloadField.isOptional ? "OptionalFieldCodec<\(codecType)>" : codecType
                lines.append(
                    "    let __value\(payloadIndex) = try \(payloadCodec).read(context, refMode: .tracking, readTypeInfo: true)"
                )
            } else {
                lines.append(
                    "    let __value\(payloadIndex) = try \(payloadField.typeText).foryRead(context, refMode: .tracking, readTypeInfo: true)"
                )
            }
        }
        let ctorArgs = enumCase.payload.enumerated().map { payloadIndex, payloadField in
            if let label = payloadField.label {
                return "\(label): __value\(payloadIndex)"
            }
            return "__value\(payloadIndex)"
        }.joined(separator: ", ")
        lines.append("    return .\(enumCase.name)(\(ctorArgs))")
        return lines.joined(separator: "\n")
    }.joined(separator: "\n        ")

    let defaultDecl: DeclSyntax = DeclSyntax(
        stringLiteral: """
        \(accessPrefix)static func foryDefault() -> Self {
            \(defaultExpr)
        }
        """
    )

    let staticTypeIDDecl: DeclSyntax = """
    \(raw: accessPrefix)static var staticTypeId: TypeId { .typedUnion }
    """
    let writeWrapperDecl: DeclSyntax = DeclSyntax(stringLiteral: buildWriteWrapperDecl(accessPrefix: accessPrefix))

    let writeDecl: DeclSyntax = DeclSyntax(
        stringLiteral: """
        @inline(__always)
        \(accessPrefix)func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
            _ = hasGenerics
            switch self {
            \(writeSwitchCases)
            }
        }
        """
    )

    let readDecl: DeclSyntax = DeclSyntax(
        stringLiteral: """
        @inline(__always)
        \(accessPrefix)static func foryReadData(_ context: ReadContext) throws -> Self {
            let caseID = try context.buffer.readVarUInt32()
            switch caseID {
            \(readSwitchCases)
            default:
                throw ForyError.invalidData("unknown union tag \\(caseID)")
            }
        }
        """
    )

    return [defaultDecl, staticTypeIDDecl, writeWrapperDecl, writeDecl, readDecl]
}

private func enumCasePattern(_ enumCase: ParsedEnumCase) -> String {
    guard !enumCase.payload.isEmpty else {
        return ".\(enumCase.name)"
    }
    let bindings = enumCase.payload.indices.map { "let __value\($0)" }.joined(separator: ", ")
    return ".\(enumCase.name)(\(bindings))"
}

private func enumCaseDefaultExpr(_ enumCase: ParsedEnumCase) -> String {
    guard !enumCase.payload.isEmpty else {
        return ".\(enumCase.name)"
    }
    let args = enumCase.payload.map { payloadField in
        let defaultValue: String
        if payloadField.isOptional {
            defaultValue = "nil"
        } else {
            defaultValue = payloadField.customCodecType.map { "\($0).defaultValue" }
                ?? "\(payloadField.typeText).foryDefault()"
        }
        if let label = payloadField.label {
            return "\(label): \(defaultValue)"
        }
        return defaultValue
    }.joined(separator: ", ")
    return ".\(enumCase.name)(\(args))"
}

private func parseFields(_ declaration: some DeclGroupSyntax) throws -> ParsedDecl {
    let isClass = declaration.is(ClassDeclSyntax.self)
    guard isClass || declaration.is(StructDeclSyntax.self) else {
        throw MacroExpansionErrorMessage("@ForyStruct supports struct and class only")
    }

    var fields: [ParsedField] = []
    var originalIndex = 0

    for member in declaration.memberBlock.members {
        guard let varDecl = member.decl.as(VariableDeclSyntax.self) else {
            continue
        }

        if varDecl.modifiers.contains(where: { $0.name.tokenKind == .keyword(.static) || $0.name.tokenKind == .keyword(.class) }) {
            continue
        }

        let fieldConfig = try parseForyFieldConfiguration(
            from: varDecl.attributes,
            supportsEncoding: true
        )
        let fieldTypeHint = try parseNestedFieldTypeHint(from: varDecl.attributes, existing: fieldConfig?.typeHint)
        if fieldConfig != nil || fieldTypeHint != nil, varDecl.bindings.count != 1 {
            throw MacroExpansionErrorMessage("Fory field annotations can only be used on a single stored property")
        }

        for binding in varDecl.bindings {
            guard let pattern = binding.pattern.as(IdentifierPatternSyntax.self) else {
                continue
            }
            guard binding.accessorBlock == nil else {
                continue
            }
            guard let typeAnnotation = binding.typeAnnotation else {
                throw MacroExpansionErrorMessage("@ForyStruct requires explicit types for stored properties")
            }

            let name = pattern.identifier.text
            let rawType = typeAnnotation.type.trimmedDescription
            let optionalUnwrapped = unwrapOptional(rawType)
            let isOptional = optionalUnwrapped.isOptional
            let concreteType = optionalUnwrapped.type

            let typeResolution = try resolveFieldType(
                concreteType: concreteType,
                fieldEncoding: fieldConfig?.encoding,
                typeHint: fieldTypeHint
            )
            let dynamicAnyCodec = try resolveDynamicAnyCodec(rawType: rawType)
            let classification = typeResolution.classification
            let fieldID = fieldConfig?.id
            let baseIdentifier = toSnakeCase(name)
            let schemaIdentifier = fieldID.map(String.init) ?? baseIdentifier
            let fieldIdentifier = fieldID.map { "$tag\($0)" } ?? baseIdentifier
            let group: Int
            if classification.isPrimitive {
                group = isOptional ? 2 : 1
            } else if classification.isMap {
                group = 5
            } else if classification.isCollection {
                group = 4
            } else if classification.isBuiltIn {
                group = 3
            } else {
                group = 6
            }

            fields.append(
                ParsedField(
                    name: name,
                    typeText: rawType,
                    typeHint: fieldTypeHint,
                    originalIndex: originalIndex,
                    isOptional: isOptional,
                    isCollection: classification.isCollection || classification.isMap,
                    fieldID: fieldID,
                    schemaIdentifier: schemaIdentifier,
                    fieldIdentifier: fieldIdentifier,
                    group: group,
                    typeID: classification.typeID,
                    isCompressedNumeric: classification.isCompressedNumeric,
                    primitiveSize: classification.primitiveSize,
                    customCodecType: typeResolution.customCodecType,
                    dynamicAnyCodec: dynamicAnyCodec
                )
            )
            originalIndex += 1
        }
    }

    var seenFieldIDs: [Int: String] = [:]
    for field in fields {
        guard let fieldID = field.fieldID else {
            continue
        }
        if let existing = seenFieldIDs[fieldID], existing != field.name {
            throw MacroExpansionErrorMessage(
                "duplicate @ForyField(id:) value \(fieldID) used by fields '\(existing)' and '\(field.name)'"
            )
        }
        seenFieldIDs[fieldID] = field.name
    }

    return ParsedDecl(isClass: isClass, fields: fields)
}

private func parseForyFieldConfiguration(
    from attributes: AttributeListSyntax,
    supportsEncoding: Bool
) throws -> ParsedForyFieldConfiguration? {
    var parsedEncoding: FieldEncoding?
    var parsedID: Int?
    var parsedTypeHint: FieldTypeHint?
    for element in attributes {
        guard let attr = element.as(AttributeSyntax.self) else {
            continue
        }

        let attrName = trimType(attr.attributeName.trimmedDescription)
        if attrName != "ForyField" && !attrName.hasSuffix(".ForyField") {
            continue
        }

        guard let args = attr.arguments else {
            throw MacroExpansionErrorMessage("@ForyField requires at least one argument")
        }
        guard case .argumentList(let argList) = args else {
            throw MacroExpansionErrorMessage("@ForyField arguments are invalid")
        }
        guard !argList.isEmpty else {
            throw MacroExpansionErrorMessage("@ForyField requires at least one argument")
        }

        for arg in argList {
            let label = arg.label?.text
            if label == nil || label == "encoding" {
                guard supportsEncoding else {
                    throw MacroExpansionErrorMessage("@ForyField(encoding:) is not supported here")
                }
                let encoding = try parseFieldEncodingExpression(arg.expression)
                if let existing = parsedEncoding, existing != encoding {
                    throw MacroExpansionErrorMessage("conflicting @ForyField encoding values on the same declaration")
                }
                parsedEncoding = encoding
                continue
            }

            if label == "id" {
                let idValue = try parseFieldIDExpression(arg.expression)
                if let existing = parsedID, existing != idValue {
                    throw MacroExpansionErrorMessage("conflicting @ForyField id values on the same declaration")
                }
                parsedID = idValue
                continue
            }

            if label == "type" {
                if parsedTypeHint != nil {
                    throw MacroExpansionErrorMessage("conflicting @ForyField type hints on the same declaration")
                }
                parsedTypeHint = try parseFieldTypeHintExpression(arg.expression)
                continue
            }

            throw MacroExpansionErrorMessage(
                "@ForyField supports only 'id', 'encoding', and 'type' arguments"
            )
        }
    }

    if parsedEncoding != nil, parsedTypeHint != nil {
        throw MacroExpansionErrorMessage("@ForyField cannot specify both 'encoding' and 'type'")
    }

    if parsedEncoding == nil, parsedID == nil, parsedTypeHint == nil {
        return nil
    }

    return ParsedForyFieldConfiguration(
        encoding: parsedEncoding,
        id: parsedID,
        typeHint: parsedTypeHint
    )
}

private func parseForyCaseConfiguration(
    from attributes: AttributeListSyntax
) throws -> ParsedForyCaseConfiguration? {
    var parsedID: Int?
    var payloadHint: FieldTypeHint?
    for element in attributes {
        guard let attr = element.as(AttributeSyntax.self) else {
            continue
        }
        let attrName = trimType(attr.attributeName.trimmedDescription)
        if attrName != "ForyCase" && !attrName.hasSuffix(".ForyCase") {
            continue
        }
        guard let args = attr.arguments else {
            throw MacroExpansionErrorMessage("@ForyCase requires at least one argument")
        }
        guard case .argumentList(let argList) = args else {
            throw MacroExpansionErrorMessage("@ForyCase arguments are invalid")
        }
        guard !argList.isEmpty else {
            throw MacroExpansionErrorMessage("@ForyCase requires at least one argument")
        }
        for arg in argList {
            let label = arg.label?.text
            if label == nil || label == "id" {
                let idValue = try parseFieldIDExpression(arg.expression)
                if let existing = parsedID, existing != idValue {
                    throw MacroExpansionErrorMessage("conflicting @ForyCase id values on the same declaration")
                }
                parsedID = idValue
                continue
            }
            if label == "payload" {
                if payloadHint != nil {
                    throw MacroExpansionErrorMessage("conflicting @ForyCase payload hints on the same declaration")
                }
                payloadHint = try parseFieldTypeHintExpression(arg.expression)
                continue
            }
            throw MacroExpansionErrorMessage("@ForyCase supports only 'id' and 'payload' arguments")
        }
    }

    if parsedID == nil, payloadHint == nil {
        return nil
    }
    return .init(id: parsedID, payloadHint: payloadHint)
}

private func parseNestedFieldTypeHint(
    from attributes: AttributeListSyntax,
    existing: FieldTypeHint?
) throws -> FieldTypeHint? {
    var hint = existing
    for element in attributes {
        guard let attr = element.as(AttributeSyntax.self) else {
            continue
        }
        let attrName = trimType(attr.attributeName.trimmedDescription)
        let parsed: FieldTypeHint?
        if attrName == "ListField" || attrName.hasSuffix(".ListField") {
            parsed = try parseListFieldHint(attr)
        } else if attrName == "ArrayField" || attrName.hasSuffix(".ArrayField") {
            parsed = try parseArrayFieldHint(attr)
        } else if attrName == "SetField" || attrName.hasSuffix(".SetField") {
            parsed = try parseSetFieldHint(attr)
        } else if attrName == "MapField" || attrName.hasSuffix(".MapField") {
            parsed = try parseMapFieldHint(attr)
        } else {
            parsed = nil
        }
        guard let parsed else {
            continue
        }
        if hint != nil {
            throw MacroExpansionErrorMessage("conflicting nested Fory field type hints on the same declaration")
        }
        hint = parsed
    }
    return hint
}

private func parseListFieldHint(_ attr: AttributeSyntax) throws -> FieldTypeHint {
    let args = try attributeArgumentList(attr, name: "@ListField")
    var elementHint: FieldTypeHint?
    for arg in args {
        let label = arg.label?.text
        guard label == nil || label == "element" else {
            throw MacroExpansionErrorMessage("@ListField supports only the 'element' argument")
        }
        elementHint = try parseFieldTypeHintExpression(arg.expression)
    }
    guard let elementHint else {
        throw MacroExpansionErrorMessage("@ListField requires an element hint")
    }
    return .list(element: elementHint)
}

private func parseArrayFieldHint(_ attr: AttributeSyntax) throws -> FieldTypeHint {
    let args = try attributeArgumentList(attr, name: "@ArrayField")
    var elementHint: FieldTypeHint?
    for arg in args {
        let label = arg.label?.text
        guard label == nil || label == "element" else {
            throw MacroExpansionErrorMessage("@ArrayField supports only the 'element' argument")
        }
        elementHint = try parseFieldTypeHintExpression(arg.expression)
    }
    guard let elementHint else {
        throw MacroExpansionErrorMessage("@ArrayField requires an element hint")
    }
    return .array(element: elementHint)
}

private func parseSetFieldHint(_ attr: AttributeSyntax) throws -> FieldTypeHint {
    let args = try attributeArgumentList(attr, name: "@SetField")
    var elementHint: FieldTypeHint?
    for arg in args {
        let label = arg.label?.text
        guard label == nil || label == "element" else {
            throw MacroExpansionErrorMessage("@SetField supports only the 'element' argument")
        }
        elementHint = try parseFieldTypeHintExpression(arg.expression)
    }
    guard let elementHint else {
        throw MacroExpansionErrorMessage("@SetField requires an element hint")
    }
    return .set(element: elementHint)
}

private func parseMapFieldHint(_ attr: AttributeSyntax) throws -> FieldTypeHint {
    let args = try attributeArgumentList(attr, name: "@MapField")
    var keyHint: FieldTypeHint?
    var valueHint: FieldTypeHint?
    for arg in args {
        switch arg.label?.text {
        case "key":
            keyHint = try parseFieldTypeHintExpression(arg.expression)
        case "value":
            valueHint = try parseFieldTypeHintExpression(arg.expression)
        default:
            throw MacroExpansionErrorMessage("@MapField supports only 'key' and 'value' arguments")
        }
    }
    if keyHint == nil, valueHint == nil {
        throw MacroExpansionErrorMessage("@MapField requires a key or value hint")
    }
    return .map(key: keyHint, value: valueHint)
}

private func attributeArgumentList(
    _ attr: AttributeSyntax,
    name: String
) throws -> LabeledExprListSyntax {
    guard let args = attr.arguments else {
        throw MacroExpansionErrorMessage("\(name) requires arguments")
    }
    guard case .argumentList(let argList) = args else {
        throw MacroExpansionErrorMessage("\(name) arguments are invalid")
    }
    guard !argList.isEmpty else {
        throw MacroExpansionErrorMessage("\(name) requires arguments")
    }
    return argList
}

private func parseForyObjectConfiguration(_ attribute: AttributeSyntax) throws -> ParsedForyObjectConfiguration {
    guard let args = attribute.arguments else {
        return .init(evolving: true)
    }
    guard case .argumentList(let argList) = args else {
        throw MacroExpansionErrorMessage("@ForyStruct arguments are invalid")
    }
    guard !argList.isEmpty else {
        return .init(evolving: true)
    }

    var evolving = true
    for arg in argList {
        let label = arg.label?.text
        if label == nil || label == "evolving" {
            evolving = try parseBoolLiteralExpression(
                arg.expression,
                message: "@ForyStruct evolving must be a boolean literal"
            )
            continue
        }
        throw MacroExpansionErrorMessage("@ForyStruct supports only the 'evolving' argument")
    }
    return .init(evolving: evolving)
}

private func parseBoolLiteralExpression(_ expr: ExprSyntax, message: String) throws -> Bool {
    let raw = trimType(expr.trimmedDescription)
    switch raw {
    case "true":
        return true
    case "false":
        return false
    default:
        throw MacroExpansionErrorMessage(message)
    }
}

private func parseFieldEncodingExpression(_ expr: ExprSyntax) throws -> FieldEncoding {
    let raw = trimType(expr.trimmedDescription)
    let candidate: String

    if raw.hasPrefix("\""), raw.hasSuffix("\""), raw.count >= 2 {
        candidate = String(raw.dropFirst().dropLast())
    } else if let dot = raw.lastIndex(of: ".") {
        candidate = String(raw[raw.index(after: dot)...])
    } else {
        candidate = raw
    }

    guard let encoding = FieldEncoding(rawValue: candidate.lowercased()) else {
        throw MacroExpansionErrorMessage(
            "@ForyField encoding must be one of: .varint, .fixed, .tagged"
        )
    }
    return encoding
}

private func parseFieldTypeHintExpression(_ expr: ExprSyntax) throws -> FieldTypeHint {
    if let member = expr.as(MemberAccessExprSyntax.self) {
        return try parseFieldTypeHintMember(member.declName.baseName.text)
    }

    guard let call = expr.as(FunctionCallExprSyntax.self) else {
        throw MacroExpansionErrorMessage("Fory field type hint must be a DSL expression")
    }

    let functionName: String
    if let member = call.calledExpression.as(MemberAccessExprSyntax.self) {
        functionName = member.declName.baseName.text
    } else if let reference = call.calledExpression.as(DeclReferenceExprSyntax.self) {
        functionName = reference.baseName.text
    } else {
        throw MacroExpansionErrorMessage("Fory field type hint call is invalid")
    }

    switch functionName {
    case "encoding":
        guard let first = call.arguments.first else {
            throw MacroExpansionErrorMessage(".encoding requires an integer encoding")
        }
        return .inferredEncoding(try parseFieldEncodingExpression(first.expression))
    case "int32", "int64", "int", "uint32", "uint64", "uint":
        return try parseScalarFieldTypeHint(name: functionName, args: call.arguments)
    case "list":
        return .list(element: try parseSingleNestedHint(functionName: ".list", args: call.arguments, label: "element"))
    case "array":
        return .array(element: try parseSingleNestedHint(functionName: ".array", args: call.arguments, label: "element"))
    case "set":
        return .set(element: try parseSingleNestedHint(functionName: ".set", args: call.arguments, label: "element"))
    case "map":
        return try parseMapFieldTypeHint(args: call.arguments)
    default:
        throw MacroExpansionErrorMessage("unsupported Fory field type hint '.\(functionName)'")
    }
}

private func parseFieldTypeHintMember(_ name: String) throws -> FieldTypeHint {
    switch name {
    case "bool", "int8", "int16", "uint8", "uint16", "float16", "bfloat16",
         "float32", "float64", "string", "date", "timestamp", "duration",
         "decimal", "binary":
        return .scalar(name: name, nullable: nil, encoding: nil)
    default:
        throw MacroExpansionErrorMessage("unsupported Fory field type hint '.\(name)'")
    }
}

private func parseScalarFieldTypeHint(
    name: String,
    args: LabeledExprListSyntax
) throws -> FieldTypeHint {
    var nullable: Bool?
    var encoding: FieldEncoding?
    for arg in args {
        switch arg.label?.text {
        case "nullable":
            nullable = try parseBoolLiteralExpression(
                arg.expression,
                message: "Fory field type hint nullable must be a boolean literal"
            )
        case "encoding":
            encoding = try parseFieldEncodingExpression(arg.expression)
        default:
            throw MacroExpansionErrorMessage(".\(name) supports only 'nullable' and 'encoding' arguments")
        }
    }
    return .scalar(name: name, nullable: nullable, encoding: encoding)
}

private func parseSingleNestedHint(
    functionName: String,
    args: LabeledExprListSyntax,
    label: String
) throws -> FieldTypeHint {
    var result: FieldTypeHint?
    for arg in args {
        let argLabel = arg.label?.text
        guard argLabel == nil || argLabel == label else {
            throw MacroExpansionErrorMessage("\(functionName) supports only the '\(label)' argument")
        }
        result = try parseFieldTypeHintExpression(arg.expression)
    }
    guard let result else {
        throw MacroExpansionErrorMessage("\(functionName) requires a \(label) hint")
    }
    return result
}

private func parseMapFieldTypeHint(args: LabeledExprListSyntax) throws -> FieldTypeHint {
    var key: FieldTypeHint?
    var value: FieldTypeHint?
    for arg in args {
        switch arg.label?.text {
        case "key":
            key = try parseFieldTypeHintExpression(arg.expression)
        case "value":
            value = try parseFieldTypeHintExpression(arg.expression)
        default:
            throw MacroExpansionErrorMessage(".map supports only 'key' and 'value' arguments")
        }
    }
    guard key != nil || value != nil else {
        throw MacroExpansionErrorMessage(".map requires a key or value hint")
    }
    return .map(key: key, value: value)
}

private func parseFieldIDExpression(_ expr: ExprSyntax) throws -> Int {
    let raw = trimType(expr.trimmedDescription)
    guard let value = Int(raw) else {
        throw MacroExpansionErrorMessage("@ForyField id must be an integer literal")
    }
    if value < 0 {
        throw MacroExpansionErrorMessage("@ForyField id must be non-negative")
    }
    if value > Int(Int16.max) {
        throw MacroExpansionErrorMessage("@ForyField id must be <= \(Int16.max)")
    }
    return value
}

private func resolveFieldType(
    concreteType: String,
    fieldEncoding: FieldEncoding?,
    typeHint: FieldTypeHint?
) throws -> FieldTypeResolution {
    let normalized = trimType(concreteType)
    let base = classifyType(normalized)

    if let typeHint {
        let codecType = try codecTypeExpression(typeText: normalized, hint: typeHint)
        return .init(
            classification: try classification(for: normalized, hint: typeHint),
            customCodecType: codecType
        )
    }

    guard let fieldEncoding else {
        return .init(classification: base, customCodecType: nil)
    }

    switch normalized {
    case "Int32":
        switch fieldEncoding {
        case .varint:
            return .init(classification: classifyType("Int32"), customCodecType: nil)
        case .fixed:
            return .init(
                classification: .init(
                    typeID: 4,
                    isPrimitive: true,
                    isBuiltIn: true,
                    isCollection: false,
                    isMap: false,
                    isCompressedNumeric: false,
                    primitiveSize: 4
                ),
                customCodecType: "Int32FixedCodec"
            )
        case .tagged:
            throw MacroExpansionErrorMessage("@ForyField(encoding: .tagged) is not supported for Int32")
        }
    case "UInt32":
        switch fieldEncoding {
        case .varint:
            return .init(classification: classifyType("UInt32"), customCodecType: nil)
        case .fixed:
            return .init(
                classification: .init(
                    typeID: 11,
                    isPrimitive: true,
                    isBuiltIn: true,
                    isCollection: false,
                    isMap: false,
                    isCompressedNumeric: false,
                    primitiveSize: 4
                ),
                customCodecType: "UInt32FixedCodec"
            )
        case .tagged:
            throw MacroExpansionErrorMessage("@ForyField(encoding: .tagged) is not supported for UInt32")
        }
    case "Int64", "Int":
        switch fieldEncoding {
        case .varint:
            return .init(classification: classifyType(normalized), customCodecType: nil)
        case .fixed:
            return .init(
                classification: .init(
                    typeID: 6,
                    isPrimitive: true,
                    isBuiltIn: true,
                    isCollection: false,
                    isMap: false,
                    isCompressedNumeric: false,
                    primitiveSize: 8
                ),
                customCodecType: "Int64FixedCodec"
            )
        case .tagged:
            return .init(
                classification: .init(
                    typeID: 8,
                    isPrimitive: true,
                    isBuiltIn: true,
                    isCollection: false,
                    isMap: false,
                    isCompressedNumeric: true,
                    primitiveSize: 8
                ),
                customCodecType: "Int64TaggedCodec"
            )
        }
    case "UInt64", "UInt":
        switch fieldEncoding {
        case .varint:
            return .init(classification: classifyType(normalized), customCodecType: nil)
        case .fixed:
            return .init(
                classification: .init(
                    typeID: 13,
                    isPrimitive: true,
                    isBuiltIn: true,
                    isCollection: false,
                    isMap: false,
                    isCompressedNumeric: false,
                    primitiveSize: 8
                ),
                customCodecType: "UInt64FixedCodec"
            )
        case .tagged:
            return .init(
                classification: .init(
                    typeID: 15,
                    isPrimitive: true,
                    isBuiltIn: true,
                    isCollection: false,
                    isMap: false,
                    isCompressedNumeric: true,
                    primitiveSize: 8
                ),
                customCodecType: "UInt64TaggedCodec"
            )
        }
    default:
        throw MacroExpansionErrorMessage(
            "@ForyField(encoding:) is only supported for Int32/UInt32/Int64/UInt64/Int/UInt fields"
        )
    }
}

private func classification(for typeText: String, hint: FieldTypeHint) throws -> TypeClassification {
    switch hint {
    case .list(let elementHint):
        _ = elementHint
        return .init(
            typeID: 22,
            isPrimitive: false,
            isBuiltIn: true,
            isCollection: true,
            isMap: false,
            isCompressedNumeric: false,
            primitiveSize: 0
        )
    case .array(let elementHint):
        let elementType = parseArrayElement(trimType(typeText)) ?? hintedValueTypeName(elementHint)
        guard let elementType,
              let packedTypeID = packedArrayTypeID(typeText: elementType, hint: elementHint) else {
            throw MacroExpansionErrorMessage("array field hint requires a non-null numeric or bool Array element type")
        }
        return .init(
            typeID: packedTypeID,
            isPrimitive: false,
            isBuiltIn: true,
            isCollection: false,
            isMap: false,
            isCompressedNumeric: false,
            primitiveSize: 0
        )
    case .set:
        return .init(
            typeID: 23,
            isPrimitive: false,
            isBuiltIn: true,
            isCollection: true,
            isMap: false,
            isCompressedNumeric: false,
            primitiveSize: 0
        )
    case .map:
        return .init(
            typeID: 24,
            isPrimitive: false,
            isBuiltIn: true,
            isCollection: false,
            isMap: true,
            isCompressedNumeric: false,
            primitiveSize: 0
        )
    case .inferredEncoding(let encoding):
        return try integerEncodingClassification(typeText: typeText, encoding: encoding)
    case .scalar(let name, _, let encoding):
        let swiftType = swiftTypeName(forScalarHint: name)
        if let encoding {
            return try integerEncodingClassification(typeText: swiftType, encoding: encoding)
        }
        return classifyType(swiftType)
    }
}

private func packedArrayTypeID(typeText: String, hint: FieldTypeHint) -> UInt32? {
    let optional = unwrapOptional(typeText)
    if optional.isOptional {
        return nil
    }
    let normalized = trimKnownModulePrefix(trimType(optional.type))
    switch hint {
    case .inferredEncoding(.fixed):
        return fixedIntegerArrayTypeID(typeText: normalized)
    case .scalar(let name, let nullable, let encoding):
        if nullable == true {
            return nil
        }
        switch name {
        case "bool":
            return 43
        case "int8":
            return 44
        case "int16":
            return 45
        case "uint8":
            return 48
        case "uint16":
            return 49
        case "float16":
            return 53
        case "bfloat16":
            return 54
        case "float32":
            return 55
        case "float64":
            return 56
        case "int32", "int64", "int", "uint32", "uint64", "uint":
            guard encoding == nil else {
                return nil
            }
            return fixedIntegerArrayTypeID(typeText: swiftTypeName(forScalarHint: name))
        default:
            return nil
        }
    default:
        return nil
    }
}

private func fixedIntegerArrayTypeID(typeText: String) -> UInt32? {
    switch trimKnownModulePrefix(trimType(typeText)) {
    case "Int8":
        return 44
    case "Int16":
        return 45
    case "Int32":
        return 46
    case "Int64", "Int":
        return 47
    case "UInt8":
        return 48
    case "UInt16":
        return 49
    case "UInt32":
        return 50
    case "UInt64", "UInt":
        return 51
    default:
        return nil
    }
}

private func integerEncodingClassification(typeText: String, encoding: FieldEncoding) throws -> TypeClassification {
    let normalized = trimKnownModulePrefix(trimType(unwrapOptional(typeText).type))
    switch normalized {
    case "Int32":
        switch encoding {
        case .varint:
            return classifyType("Int32")
        case .fixed:
            return .init(
                typeID: 4,
                isPrimitive: true,
                isBuiltIn: true,
                isCollection: false,
                isMap: false,
                isCompressedNumeric: false,
                primitiveSize: 4
            )
        case .tagged:
            throw MacroExpansionErrorMessage("@ForyField(encoding: .tagged) is not supported for Int32")
        }
    case "UInt32":
        switch encoding {
        case .varint:
            return classifyType("UInt32")
        case .fixed:
            return .init(
                typeID: 11,
                isPrimitive: true,
                isBuiltIn: true,
                isCollection: false,
                isMap: false,
                isCompressedNumeric: false,
                primitiveSize: 4
            )
        case .tagged:
            throw MacroExpansionErrorMessage("@ForyField(encoding: .tagged) is not supported for UInt32")
        }
    case "Int64", "Int":
        switch encoding {
        case .varint:
            return classifyType(normalized)
        case .fixed:
            return .init(
                typeID: 6,
                isPrimitive: true,
                isBuiltIn: true,
                isCollection: false,
                isMap: false,
                isCompressedNumeric: false,
                primitiveSize: 8
            )
        case .tagged:
            return .init(
                typeID: 8,
                isPrimitive: true,
                isBuiltIn: true,
                isCollection: false,
                isMap: false,
                isCompressedNumeric: true,
                primitiveSize: 8
            )
        }
    case "UInt64", "UInt":
        switch encoding {
        case .varint:
            return classifyType(normalized)
        case .fixed:
            return .init(
                typeID: 13,
                isPrimitive: true,
                isBuiltIn: true,
                isCollection: false,
                isMap: false,
                isCompressedNumeric: false,
                primitiveSize: 8
            )
        case .tagged:
            return .init(
                typeID: 15,
                isPrimitive: true,
                isBuiltIn: true,
                isCollection: false,
                isMap: false,
                isCompressedNumeric: true,
                primitiveSize: 8
            )
        }
    default:
        throw MacroExpansionErrorMessage(
            "integer encoding hints are only supported for Int32/UInt32/Int64/UInt64/Int/UInt fields"
        )
    }
}

private func codecTypeExpression(typeText: String, hint: FieldTypeHint?) throws -> String {
    let optional = unwrapOptional(typeText)
    let concreteType = optional.type
    let baseCodec: String

    switch hint {
    case .none:
        baseCodec = try defaultCodecTypeExpression(typeText: concreteType)
    case .inferredEncoding(let encoding):
        baseCodec = try integerCodecTypeExpression(typeText: concreteType, encoding: encoding)
    case .scalar(let name, let nullable, let encoding):
        let expectedType = swiftTypeName(forScalarHint: name)
        if !typeMatchesHint(actual: concreteType, expected: expectedType) {
            throw MacroExpansionErrorMessage("Fory field type hint .\(name) does not match Swift type \(concreteType)")
        }
        baseCodec = try scalarCodecTypeExpression(name: name, encoding: encoding)
        if nullable == true {
            return "OptionalFieldCodec<\(baseCodec)>"
        }
        if nullable == false, optional.isOptional {
            throw MacroExpansionErrorMessage("non-nullable Fory field type hint .\(name) cannot target optional Swift type")
        }
    case .list(let elementHint):
        let elementType = parseArrayElement(concreteType) ?? hintedValueTypeName(elementHint)
        guard let elementType else {
            throw MacroExpansionErrorMessage("list field hint requires an Array/List Swift type or a full element type hint")
        }
        let elementCodec = try codecTypeExpression(typeText: elementType, hint: elementHint)
        baseCodec = "ListFieldCodec<\(elementCodec)>"
    case .array(let elementHint):
        let elementType = parseArrayElement(concreteType) ?? hintedValueTypeName(elementHint)
        guard let elementType else {
            throw MacroExpansionErrorMessage("array field hint requires an Array Swift type or a full element type hint")
        }
        let elementCodec = try arrayElementCodecTypeExpression(typeText: elementType, hint: elementHint)
        baseCodec = "ArrayFieldCodec<\(elementCodec)>"
    case .set(let elementHint):
        let elementType = parseSetElement(concreteType) ?? hintedValueTypeName(elementHint)
        guard let elementType else {
            throw MacroExpansionErrorMessage("set field hint requires a Set Swift type or a full element type hint")
        }
        let elementCodec = try codecTypeExpression(typeText: elementType, hint: elementHint)
        baseCodec = "SetFieldCodec<\(elementCodec)>"
    case .map(let keyHint, let valueHint):
        let parsedMap = parseDictionary(concreteType)
        let keyType = parsedMap?.0 ?? keyHint.flatMap(hintedValueTypeName)
        let valueType = parsedMap?.1 ?? valueHint.flatMap(hintedValueTypeName)
        guard let keyType, let valueType else {
            throw MacroExpansionErrorMessage("map field hint requires a Dictionary/Map Swift type or full key/value hints")
        }
        let keyCodec = try codecTypeExpression(typeText: keyType, hint: keyHint)
        let valueCodec = try codecTypeExpression(typeText: valueType, hint: valueHint)
        baseCodec = "MapFieldCodec<\(keyCodec), \(valueCodec)>"
    }

    if optional.isOptional {
        return "OptionalFieldCodec<\(baseCodec)>"
    }
    return baseCodec
}

private func arrayElementCodecTypeExpression(typeText: String, hint: FieldTypeHint) throws -> String {
    let optional = unwrapOptional(typeText)
    if optional.isOptional {
        throw MacroExpansionErrorMessage("array field elements cannot be optional")
    }

    switch hint {
    case .scalar(let name, let nullable, let encoding):
        if nullable == true {
            throw MacroExpansionErrorMessage("array field elements cannot be nullable")
        }
        let expectedType = swiftTypeName(forScalarHint: name)
        if !typeMatchesHint(actual: typeText, expected: expectedType) {
            throw MacroExpansionErrorMessage("Fory field type hint .\(name) does not match Swift type \(optional.type)")
        }
        guard encoding == nil else {
            throw MacroExpansionErrorMessage("array field elements use fixed-width encoding and cannot specify integer encoding")
        }
        return try arrayScalarCodecTypeExpression(name: name)
    case .inferredEncoding(.fixed):
        return try integerCodecTypeExpression(typeText: optional.type, encoding: .fixed)
    default:
        throw MacroExpansionErrorMessage("array field hint requires a numeric or bool scalar element")
    }
}

private func arrayScalarCodecTypeExpression(name: String) throws -> String {
    switch name {
    case "bool":
        return "BoolCodec"
    case "int8":
        return "Int8Codec"
    case "int16":
        return "Int16Codec"
    case "int32":
        return "Int32FixedCodec"
    case "int64":
        return "Int64FixedCodec"
    case "int":
        return "IntFixedCodec"
    case "uint8":
        return "UInt8Codec"
    case "uint16":
        return "UInt16Codec"
    case "uint32":
        return "UInt32FixedCodec"
    case "uint64":
        return "UInt64FixedCodec"
    case "uint":
        return "UIntFixedCodec"
    case "float16":
        return "Float16Codec"
    case "bfloat16":
        return "BFloat16Codec"
    case "float32":
        return "FloatCodec"
    case "float64":
        return "DoubleCodec"
    default:
        throw MacroExpansionErrorMessage("array field hint requires a numeric or bool scalar element")
    }
}

private func defaultCodecTypeExpression(typeText: String) throws -> String {
    let optional = unwrapOptional(typeText)
    let concreteType = trimKnownModulePrefix(optional.type)
    let baseCodec: String
    switch concreteType {
    case "Bool":
        baseCodec = "BoolCodec"
    case "Int8":
        baseCodec = "Int8Codec"
    case "Int16":
        baseCodec = "Int16Codec"
    case "Int32":
        baseCodec = "Int32VarintCodec"
    case "Int64":
        baseCodec = "Int64VarintCodec"
    case "Int":
        baseCodec = "IntVarintCodec"
    case "UInt8":
        baseCodec = "UInt8Codec"
    case "UInt16":
        baseCodec = "UInt16Codec"
    case "UInt32":
        baseCodec = "UInt32VarintCodec"
    case "UInt64":
        baseCodec = "UInt64VarintCodec"
    case "UInt":
        baseCodec = "UIntVarintCodec"
    case "Float16":
        baseCodec = "Float16Codec"
    case "BFloat16":
        baseCodec = "BFloat16Codec"
    case "Float":
        baseCodec = "FloatCodec"
    case "Double":
        baseCodec = "DoubleCodec"
    case "String":
        baseCodec = "StringCodec"
    case "Date":
        baseCodec = "TimestampCodec"
    case "LocalDate":
        baseCodec = "LocalDateCodec"
    case "Duration":
        baseCodec = "DurationCodec"
    case "Decimal":
        baseCodec = "DecimalCodec"
    case "Data":
        baseCodec = "DataCodec"
    default:
        if let elementType = parseArrayElement(concreteType) {
            baseCodec = "ListFieldCodec<\(try codecTypeExpression(typeText: elementType, hint: nil))>"
        } else if let elementType = parseSetElement(concreteType) {
            baseCodec = "SetFieldCodec<\(try codecTypeExpression(typeText: elementType, hint: nil))>"
        } else if let (keyType, valueType) = parseDictionary(concreteType) {
            let keyCodec = try codecTypeExpression(typeText: keyType, hint: nil)
            let valueCodec = try codecTypeExpression(typeText: valueType, hint: nil)
            baseCodec = "MapFieldCodec<\(keyCodec), \(valueCodec)>"
        } else {
            baseCodec = "SerializerCodec<\(optional.type)>"
        }
    }
    if optional.isOptional {
        return "OptionalFieldCodec<\(baseCodec)>"
    }
    return baseCodec
}

private func integerCodecTypeExpression(typeText: String, encoding: FieldEncoding) throws -> String {
    let normalized = trimKnownModulePrefix(trimType(unwrapOptional(typeText).type))
    switch (normalized, encoding) {
    case ("Int32", .varint):
        return "Int32VarintCodec"
    case ("Int32", .fixed):
        return "Int32FixedCodec"
    case ("UInt32", .varint):
        return "UInt32VarintCodec"
    case ("UInt32", .fixed):
        return "UInt32FixedCodec"
    case ("Int64", .varint):
        return "Int64VarintCodec"
    case ("Int64", .fixed):
        return "Int64FixedCodec"
    case ("Int64", .tagged):
        return "Int64TaggedCodec"
    case ("Int", .varint):
        return "IntVarintCodec"
    case ("Int", .fixed):
        return "IntFixedCodec"
    case ("Int", .tagged):
        return "IntTaggedCodec"
    case ("UInt64", .varint):
        return "UInt64VarintCodec"
    case ("UInt64", .fixed):
        return "UInt64FixedCodec"
    case ("UInt64", .tagged):
        return "UInt64TaggedCodec"
    case ("UInt", .varint):
        return "UIntVarintCodec"
    case ("UInt", .fixed):
        return "UIntFixedCodec"
    case ("UInt", .tagged):
        return "UIntTaggedCodec"
    case ("Int32", .tagged):
        throw MacroExpansionErrorMessage("@ForyField(encoding: .tagged) is not supported for Int32")
    case ("UInt32", .tagged):
        throw MacroExpansionErrorMessage("@ForyField(encoding: .tagged) is not supported for UInt32")
    default:
        throw MacroExpansionErrorMessage(
            "integer encoding hints are only supported for Int32/UInt32/Int64/UInt64/Int/UInt fields"
        )
    }
}

private func scalarCodecTypeExpression(name: String, encoding: FieldEncoding?) throws -> String {
    switch name {
    case "bool":
        return "BoolCodec"
    case "int8":
        return "Int8Codec"
    case "int16":
        return "Int16Codec"
    case "uint8":
        return "UInt8Codec"
    case "uint16":
        return "UInt16Codec"
    case "float16":
        return "Float16Codec"
    case "bfloat16":
        return "BFloat16Codec"
    case "float32":
        return "FloatCodec"
    case "float64":
        return "DoubleCodec"
    case "string":
        return "StringCodec"
    case "date":
        return "LocalDateCodec"
    case "timestamp":
        return "TimestampCodec"
    case "duration":
        return "DurationCodec"
    case "decimal":
        return "DecimalCodec"
    case "binary":
        return "DataCodec"
    default:
        return try integerCodecTypeExpression(typeText: swiftTypeName(forScalarHint: name), encoding: encoding ?? .varint)
    }
}

private func swiftTypeName(forScalarHint name: String) -> String {
    switch name {
    case "bool":
        return "Bool"
    case "int8":
        return "Int8"
    case "int16":
        return "Int16"
    case "int32":
        return "Int32"
    case "int64":
        return "Int64"
    case "int":
        return "Int"
    case "uint8":
        return "UInt8"
    case "uint16":
        return "UInt16"
    case "uint32":
        return "UInt32"
    case "uint64":
        return "UInt64"
    case "uint":
        return "UInt"
    case "float16":
        return "Float16"
    case "bfloat16":
        return "BFloat16"
    case "float32":
        return "Float"
    case "float64":
        return "Double"
    case "string":
        return "String"
    case "date":
        return "LocalDate"
    case "timestamp":
        return "Date"
    case "duration":
        return "Duration"
    case "decimal":
        return "Decimal"
    case "binary":
        return "Data"
    default:
        return name
    }
}

private func hintedValueTypeName(_ hint: FieldTypeHint) -> String? {
    switch hint {
    case .scalar(let name, let nullable, _):
        let base = swiftTypeName(forScalarHint: name)
        return nullable == true ? "\(base)?" : base
    case .list(let element):
        guard let elementType = hintedValueTypeName(element) else {
            return nil
        }
        return "[\(elementType)]"
    case .array(let element):
        guard let elementType = hintedValueTypeName(element) else {
            return nil
        }
        return "[\(elementType)]"
    case .set(let element):
        guard let elementType = hintedValueTypeName(element) else {
            return nil
        }
        return "Set<\(elementType)>"
    case .map(let key, let value):
        guard let key, let value,
              let keyType = hintedValueTypeName(key),
              let valueType = hintedValueTypeName(value) else {
            return nil
        }
        return "[\(keyType): \(valueType)]"
    case .inferredEncoding:
        return nil
    }
}

private func typeMatchesHint(actual: String, expected: String) -> Bool {
    trimKnownModulePrefix(trimType(unwrapOptional(actual).type)) == expected
}

private func resolveDynamicAnyCodec(rawType: String) throws -> DynamicAnyCodecKind? {
    let optional = unwrapOptional(rawType)
    let concreteType = trimType(optional.type)

    if concreteType == "AnyHashable" {
        return .anyHashableValue
    }

    if isDynamicAnyConcreteType(concreteType) {
        return .anyValue
    }

    if let elementType = parseArrayElement(concreteType), containsDynamicAny(typeText: elementType) {
        return .anyList
    }

    if let elementType = parseSetElement(concreteType), containsDynamicAny(typeText: elementType) {
        throw MacroExpansionErrorMessage("Set<...> with Any elements is not supported by @ForyStruct yet")
    }

    if let (keyType, valueType) = parseDictionary(concreteType),
       containsDynamicAny(typeText: keyType) || containsDynamicAny(typeText: valueType) {
        let normalizedKeyType = trimType(unwrapOptional(keyType).type)
        if normalizedKeyType == "String" {
            return .stringAnyMap
        }
        if normalizedKeyType == "Int32" {
            return .int32AnyMap
        }
        if normalizedKeyType == "AnyHashable" {
            return .anyHashableAnyMap
        }
        throw MacroExpansionErrorMessage(
            "Dictionary<\(keyType), ...> with Any values is only supported for String, Int32, or AnyHashable keys"
        )
    }

    return nil
}

private func containsDynamicAny(typeText: String) -> Bool {
    let optional = unwrapOptional(typeText)
    let concreteType = trimType(optional.type)

    if isDynamicAnyConcreteType(concreteType) {
        return true
    }

    if let elementType = parseArrayElement(concreteType) {
        return containsDynamicAny(typeText: elementType)
    }

    if let elementType = parseSetElement(concreteType) {
        return containsDynamicAny(typeText: elementType)
    }

    if let (keyType, valueType) = parseDictionary(concreteType) {
        return containsDynamicAny(typeText: keyType) || containsDynamicAny(typeText: valueType)
    }

    return false
}

private func compareFieldIdentifier(_ lhs: ParsedField, _ rhs: ParsedField) -> Bool? {
    if let lhsID = lhs.fieldID, let rhsID = rhs.fieldID, lhsID != rhsID {
        return lhsID < rhsID
    }
    if lhs.fieldIdentifier != rhs.fieldIdentifier {
        return lhs.fieldIdentifier < rhs.fieldIdentifier
    }
    return nil
}

private func compareTaggedFieldIdentifier(_ lhs: ParsedField, _ rhs: ParsedField) -> Bool? {
    switch (lhs.fieldID, rhs.fieldID) {
    case let (lhsID?, rhsID?):
        if lhsID != rhsID {
            return lhsID < rhsID
        }
        if lhs.fieldIdentifier != rhs.fieldIdentifier {
            return lhs.fieldIdentifier < rhs.fieldIdentifier
        }
        return nil
    case (_?, nil):
        return true
    case (nil, _?):
        return false
    case (nil, nil):
        return nil
    }
}

private func sortFields(_ fields: [ParsedField]) -> [ParsedField] {
    fields.sorted { lhs, rhs in
        if lhs.group != rhs.group {
            return lhs.group < rhs.group
        }

        switch lhs.group {
        case 1, 2:
            let lhsCompressed = lhs.isCompressedNumeric ? 1 : 0
            let rhsCompressed = rhs.isCompressedNumeric ? 1 : 0
            if lhsCompressed != rhsCompressed {
                return lhsCompressed < rhsCompressed
            }
            if lhs.primitiveSize != rhs.primitiveSize {
                return lhs.primitiveSize > rhs.primitiveSize
            }
            if lhs.typeID != rhs.typeID {
                return lhs.typeID < rhs.typeID
            }
            if let identifierOrder = compareFieldIdentifier(lhs, rhs) {
                return identifierOrder
            }
        case 3, 4, 5:
            if lhs.typeID != rhs.typeID {
                return lhs.typeID < rhs.typeID
            }
            if let identifierOrder = compareFieldIdentifier(lhs, rhs) {
                return identifierOrder
            }
        default:
            if let identifierOrder = compareFieldIdentifier(lhs, rhs) {
                return identifierOrder
            }
        }

        if lhs.name != rhs.name {
            return lhs.name < rhs.name
        }
        return lhs.originalIndex < rhs.originalIndex
    }
}

private func buildSchemaHashDecl(fields: [ParsedField]) throws -> String {
    let fingerprintTrackRefDisabled = try buildSchemaFingerprint(fields: fields, trackRefExpression: "false")
    let fingerprintTrackRefEnabled = try buildSchemaFingerprint(fields: fields, trackRefExpression: "true")
    return """
    private static func __foryNormalizeSchemaFingerprintTypeID(_ typeID: UInt32) -> UInt32 {
        switch typeID {
        case 0, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35:
            return 0
        default:
            return typeID
        }
    }

    private static let __forySchemaHashTrackRefDisabled: UInt32 = SchemaHash.structHash32(\(fingerprintTrackRefDisabled))
    private static let __forySchemaHashTrackRefEnabled: UInt32 = SchemaHash.structHash32(\(fingerprintTrackRefEnabled))

    private static func __forySchemaHash(_ trackRef: Bool) -> UInt32 {
        trackRef ? __forySchemaHashTrackRefEnabled : __forySchemaHashTrackRefDisabled
    }
    """
}

private func buildCompatibleTypeMetaFieldsDecl(sortedFields: [ParsedField], accessPrefix: String) -> String {
    let disabledExpr = compatibleTypeMetaFieldsExpr(sortedFields: sortedFields, trackRefExpression: "false")
    let enabledExpr = compatibleTypeMetaFieldsExpr(sortedFields: sortedFields, trackRefExpression: "true")
    return """
    private static let __foryFieldsInfoTrackRefDisabled: [TypeMeta.FieldInfo] = \(disabledExpr)
    private static let __foryFieldsInfoTrackRefEnabled: [TypeMeta.FieldInfo] = \(enabledExpr)

    \(accessPrefix)static func foryFieldsInfo(trackRef: Bool) -> [TypeMeta.FieldInfo] {
        trackRef ? __foryFieldsInfoTrackRefEnabled : __foryFieldsInfoTrackRefDisabled
    }
    """
}

private func compatibleTypeMetaFieldsExpr(
    sortedFields: [ParsedField],
    trackRefExpression: String
) -> String {
    let fieldInfos = sortedFields.map { field in
        let fieldTypeExpr = compatibleTypeMetaFieldExpression(field, trackRefExpression: trackRefExpression)
        return "TypeMeta.FieldInfo(fieldID: \(compatibleFieldIDExpr(field)), fieldName: \"\(field.name)\", fieldType: \(fieldTypeExpr))"
    }
    guard !fieldInfos.isEmpty else {
        return "[]"
    }
    return "[\n            \(fieldInfos.joined(separator: ",\n            "))\n        ]"
}

private func compatibleFieldIDExpr(_ field: ParsedField) -> String {
    if let fieldID = field.fieldID {
        return "\(fieldID)"
    }
    return "nil"
}

private func buildSchemaFingerprint(fields: [ParsedField], trackRefExpression: String) throws -> String {
    let sortedFields = fields
        .sorted { lhs, rhs in
            if let taggedOrder = compareTaggedFieldIdentifier(lhs, rhs) {
                return taggedOrder
            }
            if lhs.schemaIdentifier != rhs.schemaIdentifier {
                return lhs.schemaIdentifier < rhs.schemaIdentifier
            }
            return lhs.originalIndex < rhs.originalIndex
        }
    let entries = try sortedFields.map { field -> String in
        let typeFingerprint = try buildSchemaFieldTypeFingerprint(field, trackRefExpression: trackRefExpression)
        return "\"\(field.schemaIdentifier),\" + \(typeFingerprint) + \";\""
    }
    if entries.isEmpty {
        return "\"\""
    }
    return entries.joined(separator: " + ")
}

private func buildSchemaFieldTypeFingerprint(
    _ field: ParsedField,
    trackRefExpression: String
) throws -> String {
    let fieldTrackRefExpression: String
    if let dynamicAnyCodec = field.dynamicAnyCodec {
        fieldTrackRefExpression = dynamicAnyUsesContextTrackRef(dynamicAnyCodec) ? trackRefExpression : "false"
    } else if let customCodecType = field.customCodecType {
        fieldTrackRefExpression = "\(trackRefExpression) && \(customCodecType).isRefType"
    } else {
        fieldTrackRefExpression = "\(trackRefExpression) && \(field.typeText).isRefType"
    }

    return try buildSchemaTypeFingerprint(
        typeText: field.typeText,
        hint: field.typeHint,
        nullableExpression: field.isOptional ? "true" : "false",
        trackRefExpression: fieldTrackRefExpression,
        explicitTypeIDExpression: field.customCodecType != nil ? "\(field.typeID)" : nil
    )
}

private func buildSchemaTypeFingerprint(
    typeText: String,
    hint: FieldTypeHint?,
    nullableExpression: String,
    trackRefExpression: String,
    includeNullable: Bool = true,
    explicitTypeIDExpression: String? = nil
) throws -> String {
    let normalized = trimType(typeText)
    let optional = unwrapOptional(normalized)
    let concreteType = optional.type
    let outerClassification = classifyType(concreteType)
    let nullableLiteral = includeNullable && nullableExpression == "true" ? "1" : "0"
    let trackFlagExpr = trackRefExpression == "false" ? "0" : "((\(trackRefExpression)) ? 1 : 0)"

    if let hint {
        switch hint {
        case .list(let elementHint):
            let listClassification = try classification(for: concreteType, hint: hint)
            if listClassification.typeID != 22 {
                let typeIDExpr = explicitTypeIDExpression ?? "\(listClassification.typeID)"
                return "\"\\(__foryNormalizeSchemaFingerprintTypeID(\(typeIDExpr))),\\(\(trackFlagExpr)),\(nullableLiteral)\""
            }
            let elementType = parseArrayElement(concreteType) ?? hintedValueTypeName(elementHint)
            guard let elementType else {
                throw MacroExpansionErrorMessage(
                    "list field hint requires an Array/List Swift type or a full element type hint"
                )
            }
            let elementNullable = compatibleGenericNullableExpression(elementType)
            let elementExpr = try buildSchemaTypeFingerprint(
                typeText: elementType,
                hint: elementHint,
                nullableExpression: elementNullable,
                trackRefExpression: "false",
                includeNullable: false
            )
            let prefix = "\"\\(__foryNormalizeSchemaFingerprintTypeID(UInt32(TypeId.list.rawValue))),\\(\(trackFlagExpr)),\(nullableLiteral)\""
            return "\(prefix) + \"[\" + \(elementExpr) + \"]\""
        case .array:
            let typeIDExpr: String
            if let explicitTypeIDExpression {
                typeIDExpr = explicitTypeIDExpression
            } else {
                typeIDExpr = "\(try classification(for: concreteType, hint: hint).typeID)"
            }
            return "\"\\(__foryNormalizeSchemaFingerprintTypeID(\(typeIDExpr))),\\(\(trackFlagExpr)),\(nullableLiteral)\""
        case .set(let elementHint):
            let elementType = parseSetElement(concreteType) ?? hintedValueTypeName(elementHint)
            guard let elementType else {
                throw MacroExpansionErrorMessage(
                    "set field hint requires a Set Swift type or a full element type hint"
                )
            }
            let elementNullable = compatibleGenericNullableExpression(elementType)
            let elementExpr = try buildSchemaTypeFingerprint(
                typeText: elementType,
                hint: elementHint,
                nullableExpression: elementNullable,
                trackRefExpression: "false",
                includeNullable: false
            )
            let prefix = "\"\\(__foryNormalizeSchemaFingerprintTypeID(UInt32(TypeId.set.rawValue))),\\(\(trackFlagExpr)),\(nullableLiteral)\""
            return "\(prefix) + \"[\" + \(elementExpr) + \"]\""
        case .map(let keyHint, let valueHint):
            let parsedMap = parseDictionary(concreteType)
            let keyType = parsedMap?.0 ?? keyHint.flatMap(hintedValueTypeName)
            let valueType = parsedMap?.1 ?? valueHint.flatMap(hintedValueTypeName)
            guard let keyType, let valueType else {
                throw MacroExpansionErrorMessage(
                    "map field hint requires a Dictionary/Map Swift type or full key/value hints"
                )
            }
            let keyNullable = compatibleGenericNullableExpression(keyType)
            let valueNullable = compatibleGenericNullableExpression(valueType)
            let keyExpr = try buildSchemaTypeFingerprint(
                typeText: keyType,
                hint: keyHint,
                nullableExpression: keyNullable,
                trackRefExpression: "false",
                includeNullable: false
            )
            let valueExpr = try buildSchemaTypeFingerprint(
                typeText: valueType,
                hint: valueHint,
                nullableExpression: valueNullable,
                trackRefExpression: "false",
                includeNullable: false
            )
            let prefix = "\"\\(__foryNormalizeSchemaFingerprintTypeID(UInt32(TypeId.map.rawValue))),\\(\(trackFlagExpr)),\(nullableLiteral)\""
            return "\(prefix) + \"[\" + \(keyExpr) + \"|\" + \(valueExpr) + \"]\""
        case .inferredEncoding, .scalar:
            break
        }
    }

    if outerClassification.typeID == 22, let elementType = parseArrayElement(concreteType) {
        let elementNullable = compatibleGenericNullableExpression(elementType)
        let elementExpr = try buildSchemaTypeFingerprint(
            typeText: elementType,
            hint: nil,
            nullableExpression: elementNullable,
            trackRefExpression: "false",
            includeNullable: false
        )
        let prefix = "\"\\(__foryNormalizeSchemaFingerprintTypeID(UInt32(TypeId.list.rawValue))),\\(\(trackFlagExpr)),\(nullableLiteral)\""
        return "\(prefix) + \"[\" + \(elementExpr) + \"]\""
    }

    if outerClassification.typeID == 23, let elementType = parseSetElement(concreteType) {
        let elementNullable = compatibleGenericNullableExpression(elementType)
        let elementExpr = try buildSchemaTypeFingerprint(
            typeText: elementType,
            hint: nil,
            nullableExpression: elementNullable,
            trackRefExpression: "false",
            includeNullable: false
        )
        let prefix = "\"\\(__foryNormalizeSchemaFingerprintTypeID(UInt32(TypeId.set.rawValue))),\\(\(trackFlagExpr)),\(nullableLiteral)\""
        return "\(prefix) + \"[\" + \(elementExpr) + \"]\""
    }

    if outerClassification.typeID == 24, let (keyType, valueType) = parseDictionary(concreteType) {
        let keyNullable = compatibleGenericNullableExpression(keyType)
        let valueNullable = compatibleGenericNullableExpression(valueType)
        let keyExpr = try buildSchemaTypeFingerprint(
            typeText: keyType,
            hint: nil,
            nullableExpression: keyNullable,
            trackRefExpression: "false",
            includeNullable: false
        )
        let valueExpr = try buildSchemaTypeFingerprint(
            typeText: valueType,
            hint: nil,
            nullableExpression: valueNullable,
            trackRefExpression: "false",
            includeNullable: false
        )
        let prefix = "\"\\(__foryNormalizeSchemaFingerprintTypeID(UInt32(TypeId.map.rawValue))),\\(\(trackFlagExpr)),\(nullableLiteral)\""
        return "\(prefix) + \"[\" + \(keyExpr) + \"|\" + \(valueExpr) + \"]\""
    }

    let typeIDExpr: String
    if let explicitTypeIDExpression {
        typeIDExpr = explicitTypeIDExpression
    } else if let hint {
        typeIDExpr = "\(try classification(for: concreteType, hint: hint).typeID)"
    } else if isDynamicAnyConcreteType(concreteType) {
        typeIDExpr = "UInt32(TypeId.unknown.rawValue)"
    } else {
        typeIDExpr = compatibleFieldTypeIDExpression(concreteType)
    }

    return "\"\\(__foryNormalizeSchemaFingerprintTypeID(\(typeIDExpr))),\\(\(trackFlagExpr)),\(nullableLiteral)\""
}

private func buildDefaultDecl(isClass: Bool, fields: [ParsedField], accessPrefix: String) -> String {
    if isClass {
        return """
        \(accessPrefix)static func foryDefault() -> Self {
            Self.init()
        }
        """
    }

    if fields.isEmpty {
        return """
        \(accessPrefix)static func foryDefault() -> Self {
            Self()
        }
        """
    }

    let args = fields
        .sorted(by: { $0.originalIndex < $1.originalIndex })
        .map { field in
            "\(field.name): \(fieldDefaultExpr(field))"
        }
        .joined(separator: ",\n            ")

    return """
    \(accessPrefix)static func foryDefault() -> Self {
        Self(
            \(args)
        )
    }
    """
}

private func buildWriteWrapperDecl(accessPrefix: String) -> String {
    """
    \(accessPrefix)func foryWrite(
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool,
        hasGenerics: Bool
    ) throws {
        let __buffer = context.buffer
        if refMode != .none {
            if refMode == .tracking, Self.isRefType, let object = self as AnyObject? {
                if context.refWriter.tryWriteRef(buffer: __buffer, object: object) {
                    return
                }
            } else {
                __buffer.writeInt8(RefFlag.notNullValue.rawValue)
            }
        }

        if writeTypeInfo {
            try Self.foryWriteStaticTypeInfo(context)
        }

        try foryWriteData(context, hasGenerics: hasGenerics)
    }
    """
}

private func buildWriteDataDecl(sortedFields: [ParsedField], accessPrefix: String) -> String {
    let allFieldLines = sortedFields.map { field in
        writeLine(for: field)
    }
    let leadingPrimitiveFields = leadingPrimitiveFastPathFields(sortedFields)
    let remainingFieldLines = sortedFields.dropFirst(leadingPrimitiveFields.count).map { field in
        writeLine(for: field)
    }
    var schemaHeaderLines = [
        "if context.checkClassVersion {",
        "    __buffer.writeInt32(Int32(bitPattern: Self.__forySchemaHash(context.trackRef)))",
        "}"
    ]
    let primitiveReserveBytes = schemaPrimitiveReserveBytes(sortedFields)
    if primitiveReserveBytes > 0 {
        schemaHeaderLines.insert(
            "__buffer.reserve(\(primitiveReserveBytes) + (context.checkClassVersion ? 4 : 0))",
            at: 0
        )
    }
    let schemaHeader = schemaHeaderLines.joined(separator: "\n            ")
    let primitiveFastWriteBlock = buildPrimitiveFastWriteBlock(leadingPrimitiveFields)
    var fastFieldLines: [String] = []
    if let primitiveFastWriteBlock {
        fastFieldLines.append(primitiveFastWriteBlock)
    }
    fastFieldLines.append(contentsOf: remainingFieldLines)

    let fieldBody: String
    if allFieldLines.isEmpty {
        fieldBody = "_ = hasGenerics"
    } else {
        fieldBody = fastFieldLines.joined(separator: "\n        ")
    }

    return """
    @inline(__always)
    \(accessPrefix)func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        let __buffer = context.buffer
        if !context.compatible {
            \(schemaHeader)
        }
        \(fieldBody)
    }
    """
}

private func schemaPrimitiveReserveBytes(_ fields: [ParsedField]) -> Int {
    fields.reduce(0) { partial, field in
        partial + schemaPrimitiveReserveBytes(for: field)
    }
}

private func schemaPrimitiveReserveBytes(for field: ParsedField) -> Int {
    guard !field.isOptional else {
        return 0
    }
    guard field.dynamicAnyCodec == nil, field.typeID != 27 else {
        return 0
    }

    if let customCodecType = field.customCodecType {
        switch customCodecType {
        case "Int32FixedCodec", "UInt32FixedCodec":
            return 4
        case "Int64FixedCodec", "UInt64FixedCodec":
            return 8
        case "Int64TaggedCodec", "UInt64TaggedCodec":
            return 9
        default:
            return 0
        }
    }

    switch trimType(field.typeText) {
    case "Bool", "Int8", "UInt8":
        return 1
    case "Int16", "UInt16":
        return 2
    case "Float":
        return 4
    case "Double":
        return 8
    case "Int32", "UInt32":
        return 5
    case "Int64", "UInt64", "Int", "UInt":
        return 10
    default:
        return 0
    }
}

private func writeLine(for field: ParsedField) -> String {
    if let dynamicAnyCodec = field.dynamicAnyCodec {
        let refMode = fieldRefModeExpression(field)
        return dynamicAnyWriteLine(
            field: field,
            dynamicAnyCodec: dynamicAnyCodec,
            refModeExpr: refMode
        )
    }
    let hasGenerics = field.isCollection ? "true" : "false"
    if let codecType = field.customCodecType {
        let refMode = fieldRefModeExpression(field)
        let fieldCodec = field.isOptional ? "OptionalFieldCodec<\(codecType)>" : codecType
        if field.isOptional {
            return """
            try \(fieldCodec).write(
                self.\(field.name),
                context,
                refMode: \(refMode),
                writeTypeInfo: false
            )
            """
        }
        return """
        try \(fieldCodec).write(
            self.\(field.name),
            context,
            refMode: \(refMode),
            writeTypeInfo: false
        )
        """
    }
    if !field.isOptional, !compatibleFieldNeedsTypeInfo(field) {
        if let primitiveLine = primitiveSchemaWriteLine(field) {
            return primitiveLine
        }
        return "try self.\(field.name).foryWriteData(context, hasGenerics: \(hasGenerics))"
    }
    let refMode = fieldRefModeExpression(field)
    let writeTypeInfoExpr = "context.compatible ? TypeId.needsTypeInfoForField(\(field.typeText).staticTypeId) : false"
    return """
    try self.\(field.name).foryWrite(
        context,
        refMode: \(refMode),
        writeTypeInfo: \(writeTypeInfoExpr),
        hasGenerics: \(hasGenerics)
    )
    """
}

private enum MacroTypeId {
    static let unknown: UInt32 = 0
    static let compatibleStruct: UInt32 = 27
    static let namedStruct: UInt32 = 28
    static let namedCompatibleStruct: UInt32 = 29
    static let enumType: UInt32 = 30
    static let namedEnum: UInt32 = 31
    static let ext: UInt32 = 32
}

func compatibleFieldNeedsTypeInfo(_ field: ParsedField) -> Bool {
    switch field.typeID {
    case MacroTypeId.unknown,
         MacroTypeId.compatibleStruct,
         MacroTypeId.namedStruct,
         MacroTypeId.namedCompatibleStruct,
         MacroTypeId.enumType,
         MacroTypeId.namedEnum,
         MacroTypeId.ext:
        return true
    default:
        return false
    }
}

private func primitiveSchemaWriteLine(_ field: ParsedField) -> String? {
    let type = trimType(field.typeText)
    switch type {
    case "Bool":
        return "__buffer.writeUInt8(self.\(field.name) ? 1 : 0)"
    case "Int8":
        return "__buffer.writeInt8(self.\(field.name))"
    case "Int16":
        return "__buffer.writeInt16(self.\(field.name))"
    case "Int32":
        return "__buffer.writeVarInt32(self.\(field.name))"
    case "Int64":
        return "__buffer.writeVarInt64(self.\(field.name))"
    case "Int":
        return "__buffer.writeVarInt64(Int64(self.\(field.name)))"
    case "UInt8":
        return "__buffer.writeUInt8(self.\(field.name))"
    case "UInt16":
        return "__buffer.writeUInt16(self.\(field.name))"
    case "UInt32":
        return "__buffer.writeVarUInt32(self.\(field.name))"
    case "UInt64":
        return "__buffer.writeVarUInt64(self.\(field.name))"
    case "UInt":
        return "__buffer.writeVarUInt64(UInt64(self.\(field.name)))"
    case "Float":
        return "__buffer.writeFloat32(self.\(field.name))"
    case "Double":
        return "__buffer.writeFloat64(self.\(field.name))"
    default:
        return nil
    }
}

private func dynamicAnyWriteLine(
    field: ParsedField,
    dynamicAnyCodec: DynamicAnyCodecKind,
    refModeExpr: String
) -> String {
    if dynamicAnyCodec == .anyValue || dynamicAnyCodec == .anyHashableValue {
        return "try context.writeAny(self.\(field.name), refMode: \(refModeExpr), writeTypeInfo: true, hasGenerics: false)"
    }
    let method = dynamicAnyWriteMethodName(dynamicAnyCodec)
    let castType = dynamicAnyCastType(dynamicAnyCodec)
    let optionalSuffix = field.isOptional ? "?" : ""
    return "try context.\(method)(self.\(field.name) as \(castType)\(optionalSuffix), refMode: \(refModeExpr), hasGenerics: true)"
}

func fieldRefModeExpression(_ field: ParsedField) -> String {
    let nullable = field.isOptional ? "true" : "false"
    if let dynamicAnyCodec = field.dynamicAnyCodec {
        let trackRefExpr = dynamicAnyUsesContextTrackRef(dynamicAnyCodec) ? "context.trackRef" : "false"
        return "RefMode.from(nullable: \(nullable), trackRef: \(trackRefExpr))"
    }
    if let customCodecType = field.customCodecType {
        return "RefMode.from(nullable: \(nullable), trackRef: context.trackRef && \(customCodecType).isRefType)"
    }
    return "RefMode.from(nullable: \(nullable), trackRef: context.trackRef && \(field.typeText).isRefType)"
}

private func compatibleTypeMetaFieldExpression(
    _ field: ParsedField,
    trackRefExpression: String
) -> String {
    let fieldTrackRefExpression: String
    if let dynamicAnyCodec = field.dynamicAnyCodec {
        fieldTrackRefExpression = dynamicAnyUsesContextTrackRef(dynamicAnyCodec) ? trackRefExpression : "false"
    } else if let customCodecType = field.customCodecType {
        fieldTrackRefExpression = "\(trackRefExpression) && \(customCodecType).isRefType"
    } else {
        fieldTrackRefExpression = "\(trackRefExpression) && \(field.typeText).isRefType"
    }

    if let customCodecType = field.customCodecType {
        return """
\(customCodecType).fieldType(
    nullable: \(field.isOptional ? "true" : "false"),
    trackRef: \(fieldTrackRefExpression)
)
"""
    }

    return buildCompatibleFieldTypeExpression(
        typeText: field.typeText,
        nullableExpression: field.isOptional ? "true" : "false",
        trackRefExpression: fieldTrackRefExpression,
        explicitTypeID: field.customCodecType != nil ? field.typeID : nil
    )
}

func dynamicAnyWriteMethodName(_ codec: DynamicAnyCodecKind) -> String {
    switch codec {
    case .anyValue, .anyHashableValue:
        return "writeAny"
    case .anyList:
        return "writeListOfAny"
    case .stringAnyMap:
        return "writeMapStringToAny"
    case .int32AnyMap:
        return "writeMapInt32ToAny"
    case .anyHashableAnyMap:
        return "writeMapAnyHashableToAny"
    }
}

func dynamicAnyReadMethodName(_ codec: DynamicAnyCodecKind) -> String {
    switch codec {
    case .anyValue, .anyHashableValue:
        return "readAny"
    case .anyList:
        return "readListOfAny"
    case .stringAnyMap:
        return "readMapStringToAny"
    case .int32AnyMap:
        return "readMapInt32ToAny"
    case .anyHashableAnyMap:
        return "readMapAnyHashableToAny"
    }
}

func dynamicAnyCastType(_ codec: DynamicAnyCodecKind) -> String {
    switch codec {
    case .anyList:
        return "[Any]"
    case .stringAnyMap:
        return "[String: Any]"
    case .int32AnyMap:
        return "[Int32: Any]"
    case .anyHashableAnyMap:
        return "[AnyHashable: Any]"
    case .anyValue, .anyHashableValue:
        return "Any"
    }
}

func dynamicAnyUsesContextTrackRef(_ codec: DynamicAnyCodecKind) -> Bool {
    codec == .anyValue
}

func dynamicAnyReadsTypeInfo(_ codec: DynamicAnyCodecKind) -> Bool {
    codec == .anyValue || codec == .anyHashableValue
}

func fieldDefaultExpr(_ field: ParsedField) -> String {
    if field.dynamicAnyCodec != nil {
        return dynamicAnyDefaultExpr(typeText: field.typeText)
    }
    if let customCodecType = field.customCodecType {
        return field.isOptional ? "nil" : "\(customCodecType).defaultValue"
    }
    return "\(field.typeText).foryDefault()"
}

private func buildCompatibleFieldTypeExpression(
    typeText: String,
    nullableExpression: String,
    trackRefExpression: String,
    explicitTypeID: UInt32? = nil
) -> String {
    let normalized = trimType(typeText)
    let optional = unwrapOptional(normalized)
    let concreteType = optional.type
    let outerClassification = classifyType(concreteType)

    if outerClassification.typeID == 22, let elementType = parseArrayElement(concreteType) {
        let elementNullable = compatibleGenericNullableExpression(elementType)
        let elementExpr = buildCompatibleFieldTypeExpression(
            typeText: elementType,
            nullableExpression: elementNullable,
            trackRefExpression: "false"
        )
        return """
TypeMeta.FieldType(
    typeID: TypeId.list.rawValue,
    nullable: \(nullableExpression),
    trackRef: \(trackRefExpression),
    generics: [\(elementExpr)]
)
"""
    }

    if outerClassification.typeID == 23, let elementType = parseSetElement(concreteType) {
        let elementNullable = compatibleGenericNullableExpression(elementType)
        let elementExpr = buildCompatibleFieldTypeExpression(
            typeText: elementType,
            nullableExpression: elementNullable,
            trackRefExpression: "false"
        )
        return """
TypeMeta.FieldType(
    typeID: TypeId.set.rawValue,
    nullable: \(nullableExpression),
    trackRef: \(trackRefExpression),
    generics: [\(elementExpr)]
)
"""
    }

    if outerClassification.typeID == 24, let (keyType, valueType) = parseDictionary(concreteType) {
        let keyNullable = compatibleGenericNullableExpression(keyType)
        let valueNullable = compatibleGenericNullableExpression(valueType)
        let keyExpr = buildCompatibleFieldTypeExpression(
            typeText: keyType,
            nullableExpression: keyNullable,
            trackRefExpression: "false"
        )
        let valueExpr = buildCompatibleFieldTypeExpression(
            typeText: valueType,
            nullableExpression: valueNullable,
            trackRefExpression: "false"
        )
        return """
TypeMeta.FieldType(
    typeID: TypeId.map.rawValue,
    nullable: \(nullableExpression),
    trackRef: \(trackRefExpression),
    generics: [\(keyExpr), \(valueExpr)]
)
"""
    }

    let typeIDExpr: String
    if let explicitTypeID {
        typeIDExpr = "\(explicitTypeID)"
    } else if isDynamicAnyConcreteType(concreteType) {
        typeIDExpr = "UInt32(TypeId.unknown.rawValue)"
    } else {
        typeIDExpr = compatibleFieldTypeIDExpression(concreteType)
    }

    return """
TypeMeta.FieldType(
    typeID: \(typeIDExpr),
    nullable: \(nullableExpression),
    trackRef: \(trackRefExpression)
)
"""
}

private func compatibleFieldTypeIDExpression(_ typeText: String) -> String {
    let staticTypeIDExpr = "\(typeText).staticTypeId"
    return "UInt32((\(staticTypeIDExpr) == .structType ? TypeId.compatibleStruct : \(staticTypeIDExpr)).rawValue)"
}

private func compatibleGenericNullableExpression(_ typeText: String) -> String {
    let optional = unwrapOptional(typeText)
    if optional.isOptional {
        return "true"
    }
    return classifyType(optional.type).isPrimitive ? "false" : "true"
}

private func unwrapOptional(_ typeText: String) -> (isOptional: Bool, type: String) {
    let trimmed = trimType(typeText)
    if trimmed.hasSuffix("?") {
        return (true, String(trimmed.dropLast()))
    }
    if let inner = extractGenericTypeContent(trimmed, baseNames: ["Optional", "Swift.Optional"]) {
        return (true, inner)
    }
    return (false, trimmed)
}

func trimType(_ type: String) -> String {
    type.replacingOccurrences(of: " ", with: "")
}

private struct TypeClassification {
    let typeID: UInt32
    let isPrimitive: Bool
    let isBuiltIn: Bool
    let isCollection: Bool
    let isMap: Bool
    let isCompressedNumeric: Bool
    let primitiveSize: Int
}

private func classifyType(
    _ typeText: String
) -> TypeClassification {
    let normalized = trimKnownModulePrefix(trimType(typeText))
    if isDynamicAnyConcreteType(normalized) {
        return .init(typeID: 0, isPrimitive: false, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 0)
    }

    switch normalized {
    case "Bool":
        return .init(typeID: 1, isPrimitive: true, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 1)
    case "Int8":
        return .init(typeID: 2, isPrimitive: true, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 1)
    case "Int16":
        return .init(typeID: 3, isPrimitive: true, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 2)
    case "Int32":
        return .init(typeID: 5, isPrimitive: true, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: true, primitiveSize: 4)
    case "Int64", "Int":
        return .init(typeID: 7, isPrimitive: true, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: true, primitiveSize: 8)
    case "UInt8":
        return .init(typeID: 9, isPrimitive: true, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 1)
    case "UInt16":
        return .init(typeID: 10, isPrimitive: true, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 2)
    case "UInt32":
        return .init(typeID: 12, isPrimitive: true, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: true, primitiveSize: 4)
    case "UInt64", "UInt":
        return .init(typeID: 14, isPrimitive: true, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: true, primitiveSize: 8)
    case "Float16":
        return .init(typeID: 17, isPrimitive: true, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 2)
    case "BFloat16":
        return .init(typeID: 18, isPrimitive: true, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 2)
    case "Float":
        return .init(typeID: 19, isPrimitive: true, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 4)
    case "Double":
        return .init(typeID: 20, isPrimitive: true, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 8)
    case "String":
        return .init(typeID: 21, isPrimitive: false, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 0)
    case "Data":
        return .init(typeID: 41, isPrimitive: false, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 0)
    case "Date":
        return .init(
            typeID: 38,
            isPrimitive: false,
            isBuiltIn: true,
            isCollection: false,
            isMap: false,
            isCompressedNumeric: false,
            primitiveSize: 0
        )
    case "LocalDate":
        return .init(typeID: 39, isPrimitive: false, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 0)
    case "Duration":
        return .init(typeID: 37, isPrimitive: false, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 0)
    case "Decimal":
        return .init(typeID: 40, isPrimitive: false, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 0)
    default:
        break
    }

    if let arrayElement = parseArrayElement(normalized) {
        _ = arrayElement
        return .init(typeID: 22, isPrimitive: false, isBuiltIn: true, isCollection: true, isMap: false, isCompressedNumeric: false, primitiveSize: 0)
    }

    if parseSetElement(normalized) != nil {
        return .init(typeID: 23, isPrimitive: false, isBuiltIn: true, isCollection: true, isMap: false, isCompressedNumeric: false, primitiveSize: 0)
    }

    if parseDictionary(normalized) != nil {
        return .init(typeID: 24, isPrimitive: false, isBuiltIn: true, isCollection: false, isMap: true, isCompressedNumeric: false, primitiveSize: 0)
    }

    return .init(typeID: 27, isPrimitive: false, isBuiltIn: false, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 0)
}

private func parseArrayElement(_ type: String) -> String? {
    let normalized = trimType(type)
    if normalized.hasPrefix("[") && normalized.hasSuffix("]") {
        let content = String(normalized.dropFirst().dropLast())
        if findTopLevelSeparatorIndex(in: content, separator: ":") != nil {
            return nil
        }
        return content
    }
    return extractGenericTypeContent(normalized, baseNames: ["Array", "Swift.Array"])
}

func dynamicAnyDefaultExpr(typeText: String) -> String {
    let optional = unwrapOptional(typeText)
    if optional.isOptional {
        return "nil"
    }

    let concreteType = normalizeTypeForDynamicAny(optional.type)
    if concreteType == "AnyObject" {
        return "NSNull()"
    }
    if concreteType == "AnyHashable" {
        return "AnyHashable(Int32(0))"
    }
    if concreteType == "Any" || isAnySerializerExistentialType(concreteType) {
        return "ForyAnyNullValue()"
    }
    if parseArrayElement(concreteType) != nil {
        return "[]"
    }
    if parseDictionary(concreteType) != nil {
        return "[:]"
    }
    return "\(typeText)()"
}

private func isDynamicAnyConcreteType(_ typeText: String) -> Bool {
    let normalized = normalizeTypeForDynamicAny(typeText)
    if normalized == "Any" || normalized == "AnyObject" {
        return true
    }
    return isAnySerializerExistentialType(normalized)
}

private func isAnySerializerExistentialType(_ normalizedType: String) -> Bool {
    let normalized = normalizeTypeForDynamicAny(normalizedType)
    guard normalized.hasPrefix("any") else {
        return false
    }

    let protocolType = String(normalized.dropFirst(3))
    if protocolType == "Serializer" {
        return true
    }
    return protocolType.hasSuffix(".Serializer")
}

private func normalizeTypeForDynamicAny(_ typeText: String) -> String {
    var normalized = trimType(typeText)
    while normalized.hasPrefix("("), normalized.hasSuffix(")"), normalized.count > 1 {
        normalized = String(normalized.dropFirst().dropLast())
    }
    return normalized
}

private func parseSetElement(_ type: String) -> String? {
    extractGenericTypeContent(trimType(type), baseNames: ["Set", "Swift.Set"])
}

private func parseDictionary(_ type: String) -> (String, String)? {
    let normalized = trimType(type)
    if normalized.hasPrefix("[") && normalized.hasSuffix("]") {
        let content = String(normalized.dropFirst().dropLast())
        if let colon = findTopLevelSeparatorIndex(in: content, separator: ":") {
            let key = String(content[..<colon])
            let value = String(content[content.index(after: colon)...])
            return (trimType(key), trimType(value))
        }
    }

    if let content = extractGenericTypeContent(normalized, baseNames: ["Dictionary", "Swift.Dictionary"]) {
        if let comma = findTopLevelSeparatorIndex(in: content, separator: ",") {
            let key = String(content[..<comma])
            let value = String(content[content.index(after: comma)...])
            return (trimType(key), trimType(value))
        }
    }

    return nil
}

private func trimKnownModulePrefix(_ type: String) -> String {
    if type.hasPrefix("Swift.") {
        return String(type.dropFirst("Swift.".count))
    }
    if type.hasPrefix("Foundation.") {
        return String(type.dropFirst("Foundation.".count))
    }
    return type
}

private func extractGenericTypeContent(_ type: String, baseNames: [String]) -> String? {
    for baseName in baseNames {
        let prefix = "\(baseName)<"
        if type.hasPrefix(prefix), type.hasSuffix(">") {
            let start = type.index(type.startIndex, offsetBy: prefix.count)
            return String(type[start..<type.index(before: type.endIndex)])
        }
    }
    return nil
}

private func findTopLevelSeparatorIndex(in content: String, separator: Character) -> String.Index? {
    var angleDepth = 0
    var squareDepth = 0
    var roundDepth = 0

    for index in content.indices {
        let character = content[index]
        switch character {
        case "<":
            angleDepth += 1
        case ">":
            angleDepth = max(0, angleDepth - 1)
        case "[":
            squareDepth += 1
        case "]":
            squareDepth = max(0, squareDepth - 1)
        case "(":
            roundDepth += 1
        case ")":
            roundDepth = max(0, roundDepth - 1)
        default:
            break
        }

        if character == separator && angleDepth == 0 && squareDepth == 0 && roundDepth == 0 {
            return index
        }
    }
    return nil
}

private func toSnakeCase(_ name: String) -> String {
    if name.isEmpty {
        return name
    }

    let chars = Array(name)
    var result = String()
    result.reserveCapacity(name.count + 4)

    for (index, char) in chars.enumerated() {
        if char.isUppercase {
            if index > 0 {
                let prevUpper = chars[index - 1].isUppercase
                let nextUpperOrEnd = (index + 1 >= chars.count) || chars[index + 1].isUppercase
                if !prevUpper || !nextUpperOrEnd {
                    result.append("_")
                }
            }
            result.append(char.lowercased())
        } else {
            result.append(char)
        }
    }

    return result
}
