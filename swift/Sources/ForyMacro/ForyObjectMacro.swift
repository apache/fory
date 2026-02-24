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

import SwiftCompilerPlugin
import SwiftDiagnostics
import SwiftSyntax
import SwiftSyntaxBuilder
import SwiftSyntaxMacros

@main
struct ForySwiftPlugin: CompilerPlugin {
    let providingMacros: [Macro.Type] = [ForyObjectMacro.self, ForyFieldMacro.self]
}

public struct ForyObjectMacro: MemberMacro, ExtensionMacro {
    public static func expansion(
        of _: AttributeSyntax,
        providingMembersOf declaration: some DeclGroupSyntax,
        conformingTo _: [TypeSyntax],
        in _: some MacroExpansionContext
    ) throws -> [DeclSyntax] {
        if let enumDecl = declaration.as(EnumDeclSyntax.self) {
            let parsedEnum = try parseEnumDecl(enumDecl)
            return buildEnumDecls(parsedEnum)
        }

        let parsed = try parseFields(declaration)
        let sortedFields = sortFields(parsed.fields)

        let staticTypeIDDecl: DeclSyntax = """
        static var staticTypeId: ForyTypeId { .structType }
        """

        let referenceTrackDecl: DeclSyntax? = parsed.isClass ? """
        static var isReferenceTrackableType: Bool { true }
        """ : nil

        let schemaHashDecl: DeclSyntax = DeclSyntax(stringLiteral: buildSchemaHashDecl(fields: parsed.fields))
        let compatibleTypeMetaDecl: DeclSyntax = DeclSyntax(
            stringLiteral: buildCompatibleTypeMetaFieldsDecl(sortedFields: sortedFields)
        )
        let defaultDecl: DeclSyntax = DeclSyntax(stringLiteral: buildDefaultDecl(isClass: parsed.isClass, fields: parsed.fields))
        let writeWrapperDecl: DeclSyntax = DeclSyntax(stringLiteral: buildWriteWrapperDecl())
        let readWrapperDecl: DeclSyntax = DeclSyntax(stringLiteral: buildReadWrapperDecl())
        let writeDecl: DeclSyntax = DeclSyntax(stringLiteral: buildWriteDataDecl(sortedFields: sortedFields))
        let readDecl: DeclSyntax = DeclSyntax(stringLiteral: buildReadDataDecl(isClass: parsed.isClass, fields: parsed.fields, sortedFields: sortedFields))

        return [
            staticTypeIDDecl,
            referenceTrackDecl,
            schemaHashDecl,
            compatibleTypeMetaDecl,
            defaultDecl,
            writeWrapperDecl,
            readWrapperDecl,
            writeDecl,
            readDecl,
        ].compactMap { $0 }
    }

    public static func expansion(
        of _: AttributeSyntax,
        attachedTo declaration: some DeclGroupSyntax,
        providingExtensionsOf _: some TypeSyntaxProtocol,
        conformingTo _: [TypeSyntax],
        in _: some MacroExpansionContext
    ) throws -> [ExtensionDeclSyntax] {
        let typeName: TokenSyntax
        if let structDecl = declaration.as(StructDeclSyntax.self) {
            typeName = structDecl.name
        } else if let classDecl = declaration.as(ClassDeclSyntax.self) {
            typeName = classDecl.name
        } else if let enumDecl = declaration.as(EnumDeclSyntax.self) {
            typeName = enumDecl.name
        } else {
            return []
        }

        let extensionDecl: ExtensionDeclSyntax = try ExtensionDeclSyntax("extension \(typeName): Serializer {}")
        return [extensionDecl]
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

private enum FieldEncoding: String {
    case varint
    case fixed
    case tagged
}

private enum DynamicAnyCodecKind {
    case anyValue
    case anyHashableValue
    case anyList
    case stringAnyMap
    case int32AnyMap
    case anyHashableAnyMap
}

private struct ParsedField {
    let name: String
    let typeText: String
    let originalIndex: Int

    let isOptional: Bool
    let isCollection: Bool
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

private enum ParsedEnumKind {
    case ordinal
    case taggedUnion
}

private struct ParsedEnumPayloadField {
    let label: String?
    let typeText: String
    let hasGenerics: Bool
}

private struct ParsedEnumCase {
    let name: String
    let payload: [ParsedEnumPayloadField]
}

private struct ParsedEnumDecl {
    let kind: ParsedEnumKind
    let cases: [ParsedEnumCase]
}

private struct FieldTypeResolution {
    let classification: TypeClassification
    let customCodecType: String?
}

private func parseEnumDecl(_ enumDecl: EnumDeclSyntax) throws -> ParsedEnumDecl {
    var cases: [ParsedEnumCase] = []

    for member in enumDecl.memberBlock.members {
        guard let caseDecl = member.decl.as(EnumCaseDeclSyntax.self) else {
            continue
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
                            "@ForyObject enum associated values cannot have default values"
                        )
                    }

                    let payloadType = parameter.type.trimmedDescription
                    let optional = unwrapOptional(payloadType)
                    let classification = classifyType(optional.type)
                    let hasGenerics = classification.isCollection || classification.isMap
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
                            hasGenerics: hasGenerics
                        )
                    )
                }
            }
            cases.append(.init(name: caseName, payload: payloadFields))
        }
    }

    guard !cases.isEmpty else {
        throw MacroExpansionErrorMessage("@ForyObject enum must define at least one case")
    }

    let hasPayload = cases.contains { !$0.payload.isEmpty }
    if hasPayload {
        return .init(kind: .taggedUnion, cases: cases)
    }

    return .init(kind: .ordinal, cases: cases)
}

private func buildEnumDecls(_ parsedEnum: ParsedEnumDecl) -> [DeclSyntax] {
    switch parsedEnum.kind {
    case .ordinal:
        return buildOrdinalEnumDecls(parsedEnum.cases)
    case .taggedUnion:
        return buildTaggedUnionEnumDecls(parsedEnum.cases)
    }
}

private func buildOrdinalEnumDecls(_ cases: [ParsedEnumCase]) -> [DeclSyntax] {
    let defaultCase = cases[0].name
    let writeSwitchCases = cases.enumerated().map { index, enumCase in
        """
        case .\(enumCase.name):
            context.buffer.writeVarUInt32(\(index))
        """
    }.joined(separator: "\n        ")
    let readSwitchCases = cases.enumerated().map { index, enumCase in
        "case \(index): return .\(enumCase.name)"
    }.joined(separator: "\n        ")

    let defaultDecl: DeclSyntax = DeclSyntax(
        stringLiteral: """
        static func foryDefault() -> Self {
            .\(defaultCase)
        }
        """
    )

    let staticTypeIDDecl: DeclSyntax = """
    static var staticTypeId: ForyTypeId { .enumType }
    """
    let writeWrapperDecl: DeclSyntax = DeclSyntax(stringLiteral: buildWriteWrapperDecl())
    let readWrapperDecl: DeclSyntax = DeclSyntax(stringLiteral: buildReadWrapperDecl())

    let writeDecl: DeclSyntax = DeclSyntax(
        stringLiteral: """
        @inlinable
        @inline(__always)
        func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
            _ = hasGenerics
            switch self {
            \(writeSwitchCases)
            }
        }
        """
    )

    let readDecl: DeclSyntax = DeclSyntax(
        stringLiteral: """
        @inlinable
        @inline(__always)
        static func foryReadData(_ context: ReadContext) throws -> Self {
            let ordinal = try context.buffer.readVarUInt32()
            switch ordinal {
            \(readSwitchCases)
            default:
                throw ForyError.invalidData("unknown enum ordinal \\(ordinal)")
            }
        }
        """
    )

    return [defaultDecl, staticTypeIDDecl, writeWrapperDecl, readWrapperDecl, writeDecl, readDecl]
}

private func buildTaggedUnionEnumDecls(_ cases: [ParsedEnumCase]) -> [DeclSyntax] {
    let defaultExpr = enumCaseDefaultExpr(cases[0])
    let writeSwitchCases = cases.enumerated().map { index, enumCase in
        var lines: [String] = []
        lines.append("case \(enumCasePattern(enumCase)):")
        lines.append("    context.buffer.writeVarUInt32(\(index))")
        for payloadIndex in enumCase.payload.indices {
            let variableName = "__value\(payloadIndex)"
            let hasGenerics = enumCase.payload[payloadIndex].hasGenerics ? "true" : "false"
            lines.append(
                "    try \(variableName).foryWrite(context, refMode: .tracking, writeTypeInfo: true, hasGenerics: \(hasGenerics))"
            )
        }
        return lines.joined(separator: "\n")
    }.joined(separator: "\n        ")

    let readSwitchCases = cases.enumerated().map { index, enumCase in
        if enumCase.payload.isEmpty {
            return """
            case \(index):
                return .\(enumCase.name)
            """
        }

        var lines: [String] = ["case \(index):"]
        for (payloadIndex, payloadField) in enumCase.payload.enumerated() {
            lines.append(
                "    let __value\(payloadIndex) = try \(payloadField.typeText).foryRead(context, refMode: .tracking, readTypeInfo: true)"
            )
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
        static func foryDefault() -> Self {
            \(defaultExpr)
        }
        """
    )

    let staticTypeIDDecl: DeclSyntax = """
    static var staticTypeId: ForyTypeId { .typedUnion }
    """
    let writeWrapperDecl: DeclSyntax = DeclSyntax(stringLiteral: buildWriteWrapperDecl())
    let readWrapperDecl: DeclSyntax = DeclSyntax(stringLiteral: buildReadWrapperDecl())

    let writeDecl: DeclSyntax = DeclSyntax(
        stringLiteral: """
        @inlinable
        @inline(__always)
        func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
            _ = hasGenerics
            switch self {
            \(writeSwitchCases)
            }
        }
        """
    )

    let readDecl: DeclSyntax = DeclSyntax(
        stringLiteral: """
        @inlinable
        @inline(__always)
        static func foryReadData(_ context: ReadContext) throws -> Self {
            let caseID = try context.buffer.readVarUInt32()
            switch caseID {
            \(readSwitchCases)
            default:
                throw ForyError.invalidData("unknown union tag \\(caseID)")
            }
        }
        """
    )

    return [defaultDecl, staticTypeIDDecl, writeWrapperDecl, readWrapperDecl, writeDecl, readDecl]
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
        let defaultValue = "\(payloadField.typeText).foryDefault()"
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
        throw MacroExpansionErrorMessage("@ForyObject supports struct and class only")
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

        let fieldEncoding = try parseForyFieldEncoding(from: varDecl)
        if fieldEncoding != nil, varDecl.bindings.count != 1 {
            throw MacroExpansionErrorMessage("@ForyField can only be used on a single stored property")
        }

        for binding in varDecl.bindings {
            guard let pattern = binding.pattern.as(IdentifierPatternSyntax.self) else {
                continue
            }
            guard binding.accessorBlock == nil else {
                continue
            }
            guard let typeAnnotation = binding.typeAnnotation else {
                throw MacroExpansionErrorMessage("@ForyObject requires explicit types for stored properties")
            }

            let name = pattern.identifier.text
            let rawType = typeAnnotation.type.trimmedDescription
            let optionalUnwrapped = unwrapOptional(rawType)
            let isOptional = optionalUnwrapped.isOptional
            let concreteType = optionalUnwrapped.type

            let typeResolution = try resolveFieldType(
                concreteType: concreteType,
                fieldEncoding: fieldEncoding
            )
            let dynamicAnyCodec = try resolveDynamicAnyCodec(rawType: rawType)
            let classification = typeResolution.classification
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
                    originalIndex: originalIndex,
                    isOptional: isOptional,
                    isCollection: classification.isCollection || classification.isMap,
                    fieldIdentifier: toSnakeCase(name),
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

    return ParsedDecl(isClass: isClass, fields: fields)
}

private func parseForyFieldEncoding(from varDecl: VariableDeclSyntax) throws -> FieldEncoding? {
    let attributes = varDecl.attributes
    var parsedEncoding: FieldEncoding?
    for element in attributes {
        guard let attr = element.as(AttributeSyntax.self) else {
            continue
        }

        let attrName = trimType(attr.attributeName.trimmedDescription)
        if attrName != "ForyField" && !attrName.hasSuffix(".ForyField") {
            continue
        }

        guard let args = attr.arguments else {
            throw MacroExpansionErrorMessage("@ForyField requires an encoding argument")
        }
        guard case .argumentList(let argList) = args else {
            throw MacroExpansionErrorMessage("@ForyField arguments are invalid")
        }
        guard let arg = argList.first else {
            throw MacroExpansionErrorMessage("@ForyField requires an encoding argument")
        }

        if let label = arg.label, label.text != "encoding" {
            throw MacroExpansionErrorMessage("@ForyField supports only the 'encoding' argument")
        }
        let encoding = try parseFieldEncodingExpression(arg.expression)
        if let existing = parsedEncoding, existing != encoding {
            throw MacroExpansionErrorMessage("conflicting @ForyField encoding values on the same field")
        }
        parsedEncoding = encoding
    }

    return parsedEncoding
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

private func resolveFieldType(
    concreteType: String,
    fieldEncoding: FieldEncoding?
) throws -> FieldTypeResolution {
    let normalized = trimType(concreteType)
    let base = classifyType(normalized)

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
                customCodecType: "ForyInt32Fixed"
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
                customCodecType: "ForyUInt32Fixed"
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
                customCodecType: "ForyInt64Fixed"
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
                customCodecType: "ForyInt64Tagged"
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
                customCodecType: "ForyUInt64Fixed"
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
                customCodecType: "ForyUInt64Tagged"
            )
        }
    default:
        throw MacroExpansionErrorMessage(
            "@ForyField(encoding:) is only supported for Int32/UInt32/Int64/UInt64/Int/UInt fields"
        )
    }
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
        throw MacroExpansionErrorMessage("Set<...> with Any elements is not supported by @ForyObject yet")
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
                return lhs.typeID > rhs.typeID
            }
            if lhs.fieldIdentifier != rhs.fieldIdentifier {
                return lhs.fieldIdentifier < rhs.fieldIdentifier
            }
        case 3, 4, 5:
            if lhs.typeID != rhs.typeID {
                return lhs.typeID < rhs.typeID
            }
            if lhs.fieldIdentifier != rhs.fieldIdentifier {
                return lhs.fieldIdentifier < rhs.fieldIdentifier
            }
        default:
            if lhs.fieldIdentifier != rhs.fieldIdentifier {
                return lhs.fieldIdentifier < rhs.fieldIdentifier
            }
        }

        if lhs.name != rhs.name {
            return lhs.name < rhs.name
        }
        return lhs.originalIndex < rhs.originalIndex
    }
}

private func buildSchemaHashDecl(fields: [ParsedField]) -> String {
    let fingerprintTrackRefDisabled = buildSchemaFingerprint(fields: fields, trackRefExpression: "false")
    let fingerprintTrackRefEnabled = buildSchemaFingerprint(fields: fields, trackRefExpression: "true")
    return """
    private static let __forySchemaHashTrackRefDisabled: UInt32 = SchemaHash.structHash32(\(fingerprintTrackRefDisabled))
    private static let __forySchemaHashTrackRefEnabled: UInt32 = SchemaHash.structHash32(\(fingerprintTrackRefEnabled))

    private static func __forySchemaHash(_ trackRef: Bool) -> UInt32 {
        trackRef ? Self.__forySchemaHashTrackRefEnabled : Self.__forySchemaHashTrackRefDisabled
    }
    """
}

private func buildCompatibleTypeMetaFieldsDecl(sortedFields: [ParsedField]) -> String {
    let fieldInfosTrackRefDisabled = sortedFields.map { field in
        "TypeMetaFieldInfo(fieldID: nil, fieldName: \"\(field.name)\", fieldType: \(compatibleTypeMetaFieldExpression(field, trackRefExpression: "false")))"
    }
    let fieldInfosTrackRefEnabled = sortedFields.map { field in
        "TypeMetaFieldInfo(fieldID: nil, fieldName: \"\(field.name)\", fieldType: \(compatibleTypeMetaFieldExpression(field, trackRefExpression: "true")))"
    }
    let disabledExpr = fieldInfosTrackRefDisabled.isEmpty
        ? "[]"
        : "[\n            \(fieldInfosTrackRefDisabled.joined(separator: ",\n            "))\n        ]"
    let enabledExpr = fieldInfosTrackRefEnabled.isEmpty
        ? "[]"
        : "[\n            \(fieldInfosTrackRefEnabled.joined(separator: ",\n            "))\n        ]"
    return """
    private static let __foryCompatibleTypeMetaFieldsTrackRefDisabled: [TypeMetaFieldInfo] = \(disabledExpr)
    private static let __foryCompatibleTypeMetaFieldsTrackRefEnabled: [TypeMetaFieldInfo] = \(enabledExpr)

    static func foryCompatibleTypeMetaFields(trackRef: Bool) -> [TypeMetaFieldInfo] {
        trackRef ? Self.__foryCompatibleTypeMetaFieldsTrackRefEnabled : Self.__foryCompatibleTypeMetaFieldsTrackRefDisabled
    }
    """
}

private func buildSchemaFingerprint(fields: [ParsedField], trackRefExpression: String) -> String {
    let entries = fields
        .sorted { lhs, rhs in
            if lhs.fieldIdentifier != rhs.fieldIdentifier {
                return lhs.fieldIdentifier < rhs.fieldIdentifier
            }
            return lhs.originalIndex < rhs.originalIndex
        }
        .map { field -> String in
            let typeID = fingerprintTypeID(for: field)
            let nullable = field.isOptional ? "1" : "0"
            let trackRefExpr: String
            if let dynamicAnyCodec = field.dynamicAnyCodec {
                switch dynamicAnyCodec {
                case .anyValue:
                    trackRefExpr = "(\(trackRefExpression)) ? 1 : 0"
                case .anyHashableValue, .anyList, .stringAnyMap, .int32AnyMap, .anyHashableAnyMap:
                    trackRefExpr = "0"
                }
            } else {
                trackRefExpr = "((\(trackRefExpression)) && \(field.typeText).isReferenceTrackableType) ? 1 : 0"
            }
            return "\"\(field.fieldIdentifier),\(typeID),\\(\(trackRefExpr)),\(nullable);\""
        }
    if entries.isEmpty {
        return "\"\""
    }
    return entries.joined(separator: " + ")
}

private func fingerprintTypeID(for field: ParsedField) -> UInt32 {
    let optional = unwrapOptional(field.typeText)
    let classification = classifyType(optional.type)
    if classification.isPrimitive || classification.isBuiltIn {
        return field.typeID
    }
    return 0
}

private func buildDefaultDecl(isClass: Bool, fields: [ParsedField]) -> String {
    if isClass {
        return """
        static func foryDefault() -> Self {
            Self.init()
        }
        """
    }

    if fields.isEmpty {
        return """
        static func foryDefault() -> Self {
            Self()
        }
        """
    }

    let args = fields
        .sorted(by: { $0.originalIndex < $1.originalIndex })
        .map { field in
            if field.dynamicAnyCodec != nil {
                return "\(field.name): \(dynamicAnyDefaultExpr(typeText: field.typeText))"
            }
            return "\(field.name): \(field.typeText).foryDefault()"
        }
        .joined(separator: ",\n            ")

    return """
    static func foryDefault() -> Self {
        Self(
            \(args)
        )
    }
    """
}

private func buildWriteWrapperDecl() -> String {
    """
    @inlinable
    func foryWrite(
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool,
        hasGenerics: Bool
    ) throws {
        if refMode != .none {
            if refMode == .tracking, Self.isReferenceTrackableType, let object = self as AnyObject? {
                if context.refWriter.tryWriteReference(buffer: context.buffer, object: object) {
                    return
                }
            } else {
                context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
            }
        }

        if writeTypeInfo {
            try Self.foryWriteTypeInfo(context)
        }

        try foryWriteData(context, hasGenerics: hasGenerics)
    }
    """
}

private func buildReadWrapperDecl() -> String {
    """
    @inlinable
    static func foryRead(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Self {
        if refMode != .none {
            let rawFlag = try context.buffer.readInt8()
            guard let flag = RefFlag(rawValue: rawFlag) else {
                throw ForyError.refError("invalid ref flag \\(rawFlag)")
            }

            switch flag {
            case .null:
                return Self.foryDefault()
            case .ref:
                let refID = try context.buffer.readVarUInt32()
                return try context.refReader.readRef(refID, as: Self.self)
            case .refValue:
                let reservedRefID = context.refReader.reserveRefID()
                context.pushPendingReference(reservedRefID)
                if readTypeInfo {
                    try Self.foryReadTypeInfo(context)
                }
                let value = try Self.foryReadData(context)
                context.finishPendingReferenceIfNeeded(value)
                context.popPendingReference()
                return value
            case .notNullValue:
                break
            }
        }

        if readTypeInfo {
            try Self.foryReadTypeInfo(context)
        }
        return try Self.foryReadData(context)
    }
    """
}

private func buildWriteDataDecl(sortedFields: [ParsedField]) -> String {
    let schemaFieldLines = sortedFields.map { field in
        schemaWriteLine(for: field)
    }
    let compatibleLines = sortedFields.map { field in
        compatibleWriteLine(for: field)
    }
    var schemaBodyLines = [
        "if context.checkClassVersion {",
        "    context.buffer.writeInt32(Int32(bitPattern: Self.__forySchemaHash(context.trackRef)))",
        "}"
    ]
    let primitiveReserveBytes = schemaPrimitiveReserveBytes(sortedFields)
    if primitiveReserveBytes > 0 {
        schemaBodyLines.insert(
            "context.buffer.reserve(\(primitiveReserveBytes) + (context.checkClassVersion ? 4 : 0))",
            at: 0
        )
    }
    if schemaFieldLines.isEmpty {
        schemaBodyLines.append("_ = hasGenerics")
    } else {
        schemaBodyLines.append(contentsOf: schemaFieldLines)
    }
    let schemaBody = schemaBodyLines.joined(separator: "\n        ")

    let compatibleWriteLines = compatibleLines.isEmpty ? "\n            _ = hasGenerics" : "\n            \(compatibleLines.joined(separator: "\n            "))"

    return """
    @inlinable
    @inline(__always)
    func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        if context.compatible {
            \(compatibleWriteLines)
            return
        }
        \(schemaBody)
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
        case "ForyInt32Fixed", "ForyUInt32Fixed":
            return 4
        case "ForyInt64Fixed", "ForyUInt64Fixed":
            return 8
        case "ForyInt64Tagged", "ForyUInt64Tagged":
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

private func schemaWriteLine(for field: ParsedField) -> String {
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
        if field.isOptional {
            return "try (self.\(field.name).map { \(codecType)(rawValue: $0) }).foryWrite(context, refMode: \(refMode), writeTypeInfo: false, hasGenerics: false)"
        }
        return "try \(codecType)(rawValue: self.\(field.name)).foryWrite(context, refMode: \(refMode), writeTypeInfo: false, hasGenerics: false)"
    }
    if !field.isOptional, field.typeID != 27 {
        if let primitiveLine = primitiveSchemaWriteLine(field) {
            return primitiveLine
        }
        return "try self.\(field.name).foryWriteData(context, hasGenerics: \(hasGenerics))"
    }
    let refMode = fieldRefModeExpression(field)
    return "try self.\(field.name).foryWrite(context, refMode: \(refMode), writeTypeInfo: false, hasGenerics: \(hasGenerics))"
}

private func compatibleWriteLine(for field: ParsedField) -> String {
    let refMode = fieldRefModeExpression(field)
    if let dynamicAnyCodec = field.dynamicAnyCodec {
        return dynamicAnyWriteLine(
            field: field,
            dynamicAnyCodec: dynamicAnyCodec,
            refModeExpr: refMode
        )
    }
    let hasGenerics = field.isCollection ? "true" : "false"
    if let codecType = field.customCodecType {
        if field.isOptional {
            return "try (self.\(field.name).map { \(codecType)(rawValue: $0) }).foryWrite(context, refMode: \(refMode), writeTypeInfo: false, hasGenerics: false)"
        }
        return "try \(codecType)(rawValue: self.\(field.name)).foryWrite(context, refMode: \(refMode), writeTypeInfo: false, hasGenerics: false)"
    }
    return "try self.\(field.name).foryWrite(context, refMode: \(refMode), writeTypeInfo: ForyTypeId.needsTypeInfoForField(\(field.typeText).staticTypeId), hasGenerics: \(hasGenerics))"
}

private func buildReadDataDecl(isClass: Bool, fields: [ParsedField], sortedFields: [ParsedField]) -> String {
    let compatiblePrelude = """
            let typeMeta = try context.consumeCompatibleTypeMeta(for: Self.self)
    """

    if isClass {
        let assignLines = sortedFields.map { field -> String in
            let refMode = fieldRefModeExpression(field)
            let valueExpr = readFieldExpr(
                field,
                refModeExpr: refMode,
                readTypeInfoExpr: "false"
            )
            return "value.\(field.name) = \(valueExpr)"
        }.joined(separator: "\n        ")
        let compatibleCases = sortedFields.map { field -> String in
            let valueExpr = readFieldExpr(
                field,
                refModeExpr: "RefMode.from(nullable: remoteField.fieldType.nullable, trackRef: remoteField.fieldType.trackRef)",
                readTypeInfoExpr: "ForyTypeId.needsTypeInfoForField(ForyTypeId(rawValue: remoteField.fieldType.typeID) ?? .unknown)"
            )
            return "case \"\(field.fieldIdentifier)\": value.\(field.name) = \(valueExpr)"
        }.joined(separator: "\n                ")

        return """
        @inlinable
        @inline(__always)
        static func foryReadData(_ context: ReadContext) throws -> Self {
            if context.compatible {
                \(compatiblePrelude)
                let value = Self.init()
                context.bindPendingReference(value)
                for remoteField in typeMeta.fields {
                    switch remoteField.fieldName {
                \(compatibleCases)
                    default:
                        try FieldSkipper.skipFieldValue(context: context, fieldType: remoteField.fieldType)
                    }
                }
                return value
            }
            if context.checkClassVersion {
                let __schemaHash = UInt32(bitPattern: try context.buffer.readInt32())
                let __expectedHash = Self.__forySchemaHash(context.trackRef)
                if __schemaHash != __expectedHash {
                    throw ForyError.invalidData("class version hash mismatch: expected \\(__expectedHash), got \\(__schemaHash)")
                }
            }
            let value = Self.init()
            context.bindPendingReference(value)
            \(assignLines)
            return value
        }
        """
    }

    if fields.isEmpty {
        return """
        @inlinable
        @inline(__always)
        static func foryReadData(_ context: ReadContext) throws -> Self {
            if context.compatible {
                \(compatiblePrelude)
                for remoteField in typeMeta.fields {
                    try FieldSkipper.skipFieldValue(context: context, fieldType: remoteField.fieldType)
                }
                return Self()
            }
            if context.checkClassVersion {
                let __schemaHash = UInt32(bitPattern: try context.buffer.readInt32())
                let __expectedHash = Self.__forySchemaHash(context.trackRef)
                if __schemaHash != __expectedHash {
                    throw ForyError.invalidData("class version hash mismatch: expected \\(__expectedHash), got \\(__schemaHash)")
                }
            }
            return Self()
        }
        """
    }

    let readLines = sortedFields.map { field -> String in
        let valueExpr = schemaReadFieldExpr(field)
        return "let __\(field.name) = \(valueExpr)"
    }.joined(separator: "\n        ")

    let ctorArgs = fields
        .sorted(by: { $0.originalIndex < $1.originalIndex })
        .map { "\($0.name): __\($0.name)" }
        .joined(separator: ",\n            ")
    let compatibleDefaults = fields
        .sorted(by: { $0.originalIndex < $1.originalIndex })
        .map { field in
            if field.dynamicAnyCodec != nil {
                return "var __\(field.name): \(field.typeText) = \(dynamicAnyDefaultExpr(typeText: field.typeText))"
            }
            return "var __\(field.name) = \(field.typeText).foryDefault()"
        }
        .joined(separator: "\n                ")
    let compatibleCases = sortedFields.map { field -> String in
        let valueExpr = readFieldExpr(
            field,
            refModeExpr: "RefMode.from(nullable: remoteField.fieldType.nullable, trackRef: remoteField.fieldType.trackRef)",
            readTypeInfoExpr: "ForyTypeId.needsTypeInfoForField(ForyTypeId(rawValue: remoteField.fieldType.typeID) ?? .unknown)"
        )
        return "case \"\(field.fieldIdentifier)\": __\(field.name) = \(valueExpr)"
    }.joined(separator: "\n                    ")

    return """
    @inlinable
    @inline(__always)
    static func foryReadData(_ context: ReadContext) throws -> Self {
        if context.compatible {
            \(compatiblePrelude)
                \(compatibleDefaults)
                for remoteField in typeMeta.fields {
                    switch remoteField.fieldName {
                    \(compatibleCases)
                    default:
                        try FieldSkipper.skipFieldValue(context: context, fieldType: remoteField.fieldType)
                    }
                }
                return Self(
                    \(ctorArgs)
                )
            }
        if context.checkClassVersion {
            let __schemaHash = UInt32(bitPattern: try context.buffer.readInt32())
            let __expectedHash = Self.__forySchemaHash(context.trackRef)
            if __schemaHash != __expectedHash {
                throw ForyError.invalidData("class version hash mismatch: expected \\(__expectedHash), got \\(__schemaHash)")
            }
        }
        \(readLines)
        return Self(
            \(ctorArgs)
        )
    }
    """
}

private func readFieldExpr(
    _ field: ParsedField,
    refModeExpr: String,
    readTypeInfoExpr: String
) -> String {
    if let dynamicAnyCodec = field.dynamicAnyCodec {
        return dynamicAnyReadExpr(
            field: field,
            dynamicAnyCodec: dynamicAnyCodec,
            refModeExpr: refModeExpr,
            readTypeInfoExpr: readTypeInfoExpr
        )
    }
    if let codecType = field.customCodecType {
        if field.isOptional {
            return "try \(codecType)?.foryRead(context, refMode: \(refModeExpr), readTypeInfo: false)?.rawValue"
        }
        return "try \(codecType).foryRead(context, refMode: \(refModeExpr), readTypeInfo: false).rawValue"
    }
    return "try \(field.typeText).foryRead(context, refMode: \(refModeExpr), readTypeInfo: \(readTypeInfoExpr))"
}

private func schemaReadFieldExpr(_ field: ParsedField) -> String {
    if field.dynamicAnyCodec != nil || field.customCodecType != nil || field.isOptional || field.typeID == 27 {
        let refMode = fieldRefModeExpression(field)
        return readFieldExpr(
            field,
            refModeExpr: refMode,
            readTypeInfoExpr: "false"
        )
    }
    if let primitiveExpr = primitiveSchemaReadExpr(field) {
        return primitiveExpr
    }
    return "try \(field.typeText).foryReadData(context)"
}

private func primitiveSchemaWriteLine(_ field: ParsedField) -> String? {
    let type = trimType(field.typeText)
    switch type {
    case "Bool":
        return "context.buffer.writeUInt8(self.\(field.name) ? 1 : 0)"
    case "Int8":
        return "context.buffer.writeInt8(self.\(field.name))"
    case "Int16":
        return "context.buffer.writeInt16(self.\(field.name))"
    case "Int32":
        return "context.buffer.writeVarInt32(self.\(field.name))"
    case "Int64":
        return "context.buffer.writeVarInt64(self.\(field.name))"
    case "Int":
        return "context.buffer.writeVarInt64(Int64(self.\(field.name)))"
    case "UInt8":
        return "context.buffer.writeUInt8(self.\(field.name))"
    case "UInt16":
        return "context.buffer.writeUInt16(self.\(field.name))"
    case "UInt32":
        return "context.buffer.writeVarUInt32(self.\(field.name))"
    case "UInt64":
        return "context.buffer.writeVarUInt64(self.\(field.name))"
    case "UInt":
        return "context.buffer.writeVarUInt64(UInt64(self.\(field.name)))"
    case "Float":
        return "context.buffer.writeFloat32(self.\(field.name))"
    case "Double":
        return "context.buffer.writeFloat64(self.\(field.name))"
    default:
        return nil
    }
}

private func primitiveSchemaReadExpr(_ field: ParsedField) -> String? {
    let type = trimType(field.typeText)
    switch type {
    case "Bool":
        return "try context.buffer.readUInt8() != 0"
    case "Int8":
        return "try context.buffer.readInt8()"
    case "Int16":
        return "try context.buffer.readInt16()"
    case "Int32":
        return "try context.buffer.readVarInt32()"
    case "Int64":
        return "try context.buffer.readVarInt64()"
    case "Int":
        return "Int(try context.buffer.readVarInt64())"
    case "UInt8":
        return "try context.buffer.readUInt8()"
    case "UInt16":
        return "try context.buffer.readUInt16()"
    case "UInt32":
        return "try context.buffer.readVarUInt32()"
    case "UInt64":
        return "try context.buffer.readVarUInt64()"
    case "UInt":
        return "UInt(try context.buffer.readVarUInt64())"
    case "Float":
        return "try context.buffer.readFloat32()"
    case "Double":
        return "try context.buffer.readFloat64()"
    default:
        return nil
    }
}

private func dynamicAnyWriteLine(
    field: ParsedField,
    dynamicAnyCodec: DynamicAnyCodecKind,
    refModeExpr: String
) -> String {
    switch dynamicAnyCodec {
    case .anyValue:
        return "try context.writeAny(self.\(field.name), refMode: \(refModeExpr), writeTypeInfo: true, hasGenerics: false)"
    case .anyHashableValue:
        return "try context.writeAny(self.\(field.name), refMode: \(refModeExpr), writeTypeInfo: true, hasGenerics: false)"
    case .anyList:
        if field.isOptional {
            return "try context.writeAnyList(self.\(field.name) as [Any]?, refMode: \(refModeExpr), hasGenerics: true)"
        }
        return "try context.writeAnyList(self.\(field.name) as [Any], refMode: \(refModeExpr), hasGenerics: true)"
    case .stringAnyMap:
        if field.isOptional {
            return "try context.writeStringAnyMap(self.\(field.name) as [String: Any]?, refMode: \(refModeExpr), hasGenerics: true)"
        }
        return "try context.writeStringAnyMap(self.\(field.name) as [String: Any], refMode: \(refModeExpr), hasGenerics: true)"
    case .int32AnyMap:
        if field.isOptional {
            return "try context.writeInt32AnyMap(self.\(field.name) as [Int32: Any]?, refMode: \(refModeExpr), hasGenerics: true)"
        }
        return "try context.writeInt32AnyMap(self.\(field.name) as [Int32: Any], refMode: \(refModeExpr), hasGenerics: true)"
    case .anyHashableAnyMap:
        if field.isOptional {
            return "try context.writeAnyHashableAnyMap(self.\(field.name) as [AnyHashable: Any]?, refMode: \(refModeExpr), hasGenerics: true)"
        }
        return "try context.writeAnyHashableAnyMap(self.\(field.name) as [AnyHashable: Any], refMode: \(refModeExpr), hasGenerics: true)"
    }
}

private func dynamicAnyReadExpr(
    field: ParsedField,
    dynamicAnyCodec: DynamicAnyCodecKind,
    refModeExpr: String,
    readTypeInfoExpr _: String
) -> String {
    let metatypeExpr = "(\(field.typeText)).self"
    switch dynamicAnyCodec {
    case .anyValue:
        return "try castAnyDynamicValue(context.readAny(refMode: \(refModeExpr), readTypeInfo: true), to: \(metatypeExpr))"
    case .anyHashableValue:
        return "try castAnyDynamicValue(context.readAny(refMode: \(refModeExpr), readTypeInfo: true), to: \(metatypeExpr))"
    case .anyList:
        return "try castAnyDynamicValue(context.readAnyList(refMode: \(refModeExpr)), to: \(metatypeExpr))"
    case .stringAnyMap:
        return "try castAnyDynamicValue(context.readStringAnyMap(refMode: \(refModeExpr)), to: \(metatypeExpr))"
    case .int32AnyMap:
        return "try castAnyDynamicValue(context.readInt32AnyMap(refMode: \(refModeExpr)), to: \(metatypeExpr))"
    case .anyHashableAnyMap:
        return "try castAnyDynamicValue(context.readAnyHashableAnyMap(refMode: \(refModeExpr)), to: \(metatypeExpr))"
    }
}

private func fieldRefModeExpression(_ field: ParsedField) -> String {
    let nullable = field.isOptional ? "true" : "false"
    if let dynamicAnyCodec = field.dynamicAnyCodec {
        switch dynamicAnyCodec {
        case .anyValue:
            return "RefMode.from(nullable: \(nullable), trackRef: context.trackRef)"
        case .anyHashableValue, .anyList, .stringAnyMap, .int32AnyMap, .anyHashableAnyMap:
            return "RefMode.from(nullable: \(nullable), trackRef: false)"
        }
    }
    return "RefMode.from(nullable: \(nullable), trackRef: context.trackRef && \(field.typeText).isReferenceTrackableType)"
}

private func compatibleTypeMetaFieldExpression(
    _ field: ParsedField,
    trackRefExpression: String
) -> String {
    let fieldTrackRefExpression: String
    if let dynamicAnyCodec = field.dynamicAnyCodec {
        switch dynamicAnyCodec {
        case .anyValue:
            fieldTrackRefExpression = trackRefExpression
        case .anyHashableValue, .anyList, .stringAnyMap, .int32AnyMap, .anyHashableAnyMap:
            fieldTrackRefExpression = "false"
        }
    } else {
        fieldTrackRefExpression = "\(trackRefExpression) && \(field.typeText).isReferenceTrackableType"
    }

    return buildCompatibleTypeMetaFieldTypeExpression(
        typeText: field.typeText,
        nullableExpression: field.isOptional ? "true" : "false",
        trackRefExpression: fieldTrackRefExpression,
        explicitTypeID: field.customCodecType == nil ? nil : field.typeID
    )
}

private func buildCompatibleTypeMetaFieldTypeExpression(
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
        let elementExpr = buildCompatibleTypeMetaFieldTypeExpression(
            typeText: elementType,
            nullableExpression: elementNullable,
            trackRefExpression: "false"
        )
        return """
TypeMetaFieldType(
    typeID: ForyTypeId.list.rawValue,
    nullable: \(nullableExpression),
    trackRef: \(trackRefExpression),
    generics: [\(elementExpr)]
)
"""
    }

    if outerClassification.typeID == 23, let elementType = parseSetElement(concreteType) {
        let elementNullable = compatibleGenericNullableExpression(elementType)
        let elementExpr = buildCompatibleTypeMetaFieldTypeExpression(
            typeText: elementType,
            nullableExpression: elementNullable,
            trackRefExpression: "false"
        )
        return """
TypeMetaFieldType(
    typeID: ForyTypeId.set.rawValue,
    nullable: \(nullableExpression),
    trackRef: \(trackRefExpression),
    generics: [\(elementExpr)]
)
"""
    }

    if outerClassification.typeID == 24, let (keyType, valueType) = parseDictionary(concreteType) {
        let keyNullable = compatibleGenericNullableExpression(keyType)
        let valueNullable = compatibleGenericNullableExpression(valueType)
        let keyExpr = buildCompatibleTypeMetaFieldTypeExpression(
            typeText: keyType,
            nullableExpression: keyNullable,
            trackRefExpression: "false"
        )
        let valueExpr = buildCompatibleTypeMetaFieldTypeExpression(
            typeText: valueType,
            nullableExpression: valueNullable,
            trackRefExpression: "false"
        )
        return """
TypeMetaFieldType(
    typeID: ForyTypeId.map.rawValue,
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
        typeIDExpr = "UInt32(ForyTypeId.unknown.rawValue)"
    } else {
        typeIDExpr = "UInt32(\(concreteType).staticTypeId.rawValue)"
    }

    return """
TypeMetaFieldType(
    typeID: \(typeIDExpr),
    nullable: \(nullableExpression),
    trackRef: \(trackRefExpression)
)
"""
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
    if trimmed.hasPrefix("Optional<") && trimmed.hasSuffix(">") {
        let start = trimmed.index(trimmed.startIndex, offsetBy: "Optional<".count)
        let inner = String(trimmed[start..<trimmed.index(before: trimmed.endIndex)])
        return (true, inner)
    }
    return (false, trimmed)
}

private func trimType(_ type: String) -> String {
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

private func classifyType(_ typeText: String) -> TypeClassification {
    let normalized = trimType(typeText)
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
    case "Float":
        return .init(typeID: 19, isPrimitive: true, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 4)
    case "Double":
        return .init(typeID: 20, isPrimitive: true, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 8)
    case "String":
        return .init(typeID: 21, isPrimitive: false, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 0)
    case "Data", "Foundation.Data":
        return .init(typeID: 41, isPrimitive: false, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 0)
    case "Date", "Foundation.Date", "ForyTimestamp":
        return .init(typeID: 38, isPrimitive: false, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 0)
    case "ForyDate":
        return .init(typeID: 39, isPrimitive: false, isBuiltIn: true, isCollection: false, isMap: false, isCompressedNumeric: false, primitiveSize: 0)
    default:
        break
    }

    if let arrayElement = parseArrayElement(normalized) {
        let elem = classifyType(arrayElement)
        if elem.typeID == 9 { // UInt8
            return .init(typeID: 41, isPrimitive: false, isBuiltIn: true, isCollection: true, isMap: false, isCompressedNumeric: false, primitiveSize: 0)
        }
        if elem.typeID == 1 { return .init(typeID: 43, isPrimitive: false, isBuiltIn: true, isCollection: true, isMap: false, isCompressedNumeric: false, primitiveSize: 0) }
        if elem.typeID == 2 { return .init(typeID: 44, isPrimitive: false, isBuiltIn: true, isCollection: true, isMap: false, isCompressedNumeric: false, primitiveSize: 0) }
        if elem.typeID == 3 { return .init(typeID: 45, isPrimitive: false, isBuiltIn: true, isCollection: true, isMap: false, isCompressedNumeric: false, primitiveSize: 0) }
        if elem.typeID == 5 { return .init(typeID: 46, isPrimitive: false, isBuiltIn: true, isCollection: true, isMap: false, isCompressedNumeric: false, primitiveSize: 0) }
        if elem.typeID == 7 { return .init(typeID: 47, isPrimitive: false, isBuiltIn: true, isCollection: true, isMap: false, isCompressedNumeric: false, primitiveSize: 0) }
        if elem.typeID == 10 { return .init(typeID: 49, isPrimitive: false, isBuiltIn: true, isCollection: true, isMap: false, isCompressedNumeric: false, primitiveSize: 0) }
        if elem.typeID == 12 { return .init(typeID: 50, isPrimitive: false, isBuiltIn: true, isCollection: true, isMap: false, isCompressedNumeric: false, primitiveSize: 0) }
        if elem.typeID == 14 { return .init(typeID: 51, isPrimitive: false, isBuiltIn: true, isCollection: true, isMap: false, isCompressedNumeric: false, primitiveSize: 0) }
        if elem.typeID == 19 { return .init(typeID: 55, isPrimitive: false, isBuiltIn: true, isCollection: true, isMap: false, isCompressedNumeric: false, primitiveSize: 0) }
        if elem.typeID == 20 { return .init(typeID: 56, isPrimitive: false, isBuiltIn: true, isCollection: true, isMap: false, isCompressedNumeric: false, primitiveSize: 0) }
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
    if type.hasPrefix("[") && type.hasSuffix("]") {
        let content = String(type.dropFirst().dropLast())
        if content.contains(":") {
            return nil
        }
        return content
    }
    if type.hasPrefix("Array<") && type.hasSuffix(">") {
        let start = type.index(type.startIndex, offsetBy: "Array<".count)
        return String(type[start..<type.index(before: type.endIndex)])
    }
    return nil
}

private func dynamicAnyDefaultExpr(typeText: String) -> String {
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
    if type.hasPrefix("Set<") && type.hasSuffix(">") {
        let start = type.index(type.startIndex, offsetBy: "Set<".count)
        return String(type[start..<type.index(before: type.endIndex)])
    }
    return nil
}

private func parseDictionary(_ type: String) -> (String, String)? {
    if type.hasPrefix("[") && type.hasSuffix("]") {
        let content = String(type.dropFirst().dropLast())
        if let colon = content.firstIndex(of: ":") {
            let key = String(content[..<colon])
            let value = String(content[content.index(after: colon)...])
            return (key, value)
        }
    }

    if type.hasPrefix("Dictionary<") && type.hasSuffix(">") {
        let start = type.index(type.startIndex, offsetBy: "Dictionary<".count)
        let content = String(type[start..<type.index(before: type.endIndex)])
        if let comma = content.firstIndex(of: ",") {
            let key = String(content[..<comma])
            let value = String(content[content.index(after: comma)...])
            return (key, value)
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
