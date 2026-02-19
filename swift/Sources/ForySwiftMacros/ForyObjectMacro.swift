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
    let providingMacros: [Macro.Type] = [ForyObjectMacro.self]
}

public struct ForyObjectMacro: MemberMacro, ExtensionMacro {
    public static func expansion(
        of _: AttributeSyntax,
        providingMembersOf declaration: some DeclGroupSyntax,
        conformingTo _: [TypeSyntax],
        in _: some MacroExpansionContext
    ) throws -> [DeclSyntax] {
        let parsed = try parseFields(declaration)
        let sortedFields = sortFields(parsed.fields)

        let staticTypeIDDecl: DeclSyntax = """
        static var staticTypeId: ForyTypeId { .structType }
        """

        let referenceTrackDecl: DeclSyntax? = parsed.isClass ? """
        static var isReferenceTrackableType: Bool { true }
        """ : nil

        let schemaHashDecl: DeclSyntax = DeclSyntax(stringLiteral: buildSchemaHashDecl(fields: parsed.fields))
        let defaultDecl: DeclSyntax = DeclSyntax(stringLiteral: buildDefaultDecl(isClass: parsed.isClass, fields: parsed.fields))
        let writeDecl: DeclSyntax = DeclSyntax(stringLiteral: buildWriteDataDecl(sortedFields: sortedFields))
        let readDecl: DeclSyntax = DeclSyntax(stringLiteral: buildReadDataDecl(isClass: parsed.isClass, fields: parsed.fields, sortedFields: sortedFields))

        return [staticTypeIDDecl, referenceTrackDecl, schemaHashDecl, defaultDecl, writeDecl, readDecl].compactMap { $0 }
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
        } else {
            return []
        }

        let extensionDecl: ExtensionDeclSyntax = try ExtensionDeclSyntax("extension \(typeName): Serializer {}")
        return [extensionDecl]
    }
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
}

private struct ParsedDecl {
    let isClass: Bool
    let fields: [ParsedField]
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

            let classification = classifyType(concreteType)
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
                    primitiveSize: classification.primitiveSize
                )
            )
            originalIndex += 1
        }
    }

    return ParsedDecl(isClass: isClass, fields: fields)
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
    let fingerprint = buildSchemaFingerprint(fields: fields)
    return """
    private static let __forySchemaHash: UInt32 = SchemaHash.structHash32("\(fingerprint)")
    """
}

private func buildSchemaFingerprint(fields: [ParsedField]) -> String {
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
            return "\(field.fieldIdentifier),\(typeID),0,\(nullable);"
        }
    return entries.joined()
}

private func fingerprintTypeID(for field: ParsedField) -> UInt32 {
    let optional = unwrapOptional(field.typeText)
    let classification = classifyType(optional.type)
    if classification.isPrimitive || classification.isBuiltIn {
        return classification.typeID
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
        .map { "\($0.name): \($0.typeText).foryDefault()" }
        .joined(separator: ",\n            ")

    return """
    static func foryDefault() -> Self {
        Self(
            \(args)
        )
    }
    """
}

private func buildWriteDataDecl(sortedFields: [ParsedField]) -> String {
    let lines = sortedFields.map { field in
        let refMode = fieldRefModeExpression(field)
        let hasGenerics = field.isCollection ? "true" : "false"
        return "try self.\(field.name).foryWrite(context, refMode: \(refMode), writeTypeInfo: false, hasGenerics: \(hasGenerics))"
    }
    var schemaLines = ["context.writer.writeInt32(Int32(bitPattern: Self.__forySchemaHash))"]
    if lines.isEmpty {
        schemaLines.append("_ = hasGenerics")
    } else {
        schemaLines.append(contentsOf: lines)
    }
    let schemaBody = schemaLines.joined(separator: "\n        ")

    let fieldInfos = sortedFields.map { field in
        "TypeMetaFieldInfo(fieldID: nil, fieldName: \"\(field.name)\", fieldType: \(compatibleTypeMetaFieldExpression(field)))"
    }
    let compatibleFieldsExpr = fieldInfos.isEmpty
        ? "[]"
        : "[\n                \(fieldInfos.joined(separator: ",\n                "))\n            ]"
    let compatibleWriteLines = lines.isEmpty ? "" : "\n            \(lines.joined(separator: "\n            "))"

    return """
    func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        if context.compatible {
            let info = try context.typeResolver.requireRegisteredTypeInfo(for: Self.self)
            let typeMeta = try TypeMeta(
                typeID: ForyTypeId.compatibleStruct.rawValue,
                userTypeID: info.userTypeID,
                namespace: MetaString.empty(specialChar1: ".", specialChar2: "_"),
                typeName: MetaString.empty(specialChar1: "$", specialChar2: "_"),
                registerByName: false,
                fields: \(compatibleFieldsExpr)
            )
            context.writer.writeVarUInt32(0)
            context.writer.writeBytes(try typeMeta.encode())\(compatibleWriteLines)
            return
        }
        \(schemaBody)
    }
    """
}

private func buildReadDataDecl(isClass: Bool, fields: [ParsedField], sortedFields: [ParsedField]) -> String {
    let compatiblePrelude = """
            let marker = try context.reader.readVarUInt32()
            if (marker & 1) == 1 {
                throw ForyError.invalidData("type metadata references are not supported")
            }
            let typeMeta = try TypeMeta.decode(context.reader)
            if typeMeta.registerByName {
                throw ForyError.invalidData("register-by-name compatible metadata is not supported")
            }
            let info = try context.typeResolver.requireRegisteredTypeInfo(for: Self.self)
            guard let remoteUserTypeID = typeMeta.userTypeID else {
                throw ForyError.invalidData("missing user type id in compatible type metadata")
            }
            if remoteUserTypeID != info.userTypeID {
                throw ForyError.typeMismatch(expected: info.userTypeID, actual: remoteUserTypeID)
            }
            if let remoteTypeID = typeMeta.typeID,
               remoteTypeID != ForyTypeId.compatibleStruct.rawValue,
               remoteTypeID != ForyTypeId.structType.rawValue {
                throw ForyError.typeMismatch(expected: ForyTypeId.compatibleStruct.rawValue, actual: remoteTypeID)
            }
    """

    if isClass {
        let assignLines = sortedFields.map { field -> String in
            let refMode = fieldRefModeExpression(field)
            return "value.\(field.name) = try \(field.typeText).foryRead(context, refMode: \(refMode), readTypeInfo: false)"
        }.joined(separator: "\n        ")
        let compatibleCases = sortedFields.map { field -> String in
            "case \"\(field.fieldIdentifier)\": value.\(field.name) = try \(field.typeText).foryRead(context, refMode: RefMode.from(nullable: remoteField.fieldType.nullable, trackRef: remoteField.fieldType.trackRef), readTypeInfo: false)"
        }.joined(separator: "\n                ")

        return """
        static func foryReadData(_ context: ReadContext) throws -> Self {
            if context.compatible {
                \(compatiblePrelude)
                let value = Self.init()
                context.bindPendingReference(value)
                for remoteField in typeMeta.fields {
                    switch remoteField.fieldName {
                \(compatibleCases)
                    default:
                        try CompatibleFieldIO.skipField(context: context, fieldType: remoteField.fieldType)
                    }
                }
                return value
            }
            let __schemaHash = UInt32(bitPattern: try context.reader.readInt32())
            if __schemaHash != Self.__forySchemaHash {
                throw ForyError.invalidData("class version hash mismatch: expected \\(Self.__forySchemaHash), got \\(__schemaHash)")
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
        static func foryReadData(_ context: ReadContext) throws -> Self {
            if context.compatible {
                \(compatiblePrelude)
                for remoteField in typeMeta.fields {
                    try CompatibleFieldIO.skipField(context: context, fieldType: remoteField.fieldType)
                }
                return Self()
            }
            let __schemaHash = UInt32(bitPattern: try context.reader.readInt32())
            if __schemaHash != Self.__forySchemaHash {
                throw ForyError.invalidData("class version hash mismatch: expected \\(Self.__forySchemaHash), got \\(__schemaHash)")
            }
            Self()
        }
        """
    }

    let readLines = sortedFields.map { field -> String in
        let refMode = fieldRefModeExpression(field)
        return "let __\(field.name) = try \(field.typeText).foryRead(context, refMode: \(refMode), readTypeInfo: false)"
    }.joined(separator: "\n        ")

    let ctorArgs = fields
        .sorted(by: { $0.originalIndex < $1.originalIndex })
        .map { "\($0.name): __\($0.name)" }
        .joined(separator: ",\n            ")
    let compatibleDefaults = fields
        .sorted(by: { $0.originalIndex < $1.originalIndex })
        .map { "var __\($0.name) = \($0.typeText).foryDefault()" }
        .joined(separator: "\n                ")
    let compatibleCases = sortedFields.map { field -> String in
        "case \"\(field.fieldIdentifier)\": __\(field.name) = try \(field.typeText).foryRead(context, refMode: RefMode.from(nullable: remoteField.fieldType.nullable, trackRef: remoteField.fieldType.trackRef), readTypeInfo: false)"
    }.joined(separator: "\n                    ")

    return """
    static func foryReadData(_ context: ReadContext) throws -> Self {
        if context.compatible {
            \(compatiblePrelude)
                \(compatibleDefaults)
                for remoteField in typeMeta.fields {
                    switch remoteField.fieldName {
                    \(compatibleCases)
                    default:
                        try CompatibleFieldIO.skipField(context: context, fieldType: remoteField.fieldType)
                    }
                }
                return Self(
                    \(ctorArgs)
                )
            }
        let __schemaHash = UInt32(bitPattern: try context.reader.readInt32())
        if __schemaHash != Self.__forySchemaHash {
            throw ForyError.invalidData("class version hash mismatch: expected \\(Self.__forySchemaHash), got \\(__schemaHash)")
        }
        \(readLines)
        return Self(
            \(ctorArgs)
        )
    }
    """
}

private func fieldRefModeExpression(_ field: ParsedField) -> String {
    let nullable = field.isOptional ? "true" : "false"
    return "RefMode.from(nullable: \(nullable), trackRef: context.trackRef && \(field.typeText).isReferenceTrackableType)"
}

private func compatibleTypeMetaFieldExpression(_ field: ParsedField) -> String {
    buildCompatibleTypeMetaFieldTypeExpression(
        typeText: field.typeText,
        nullableExpression: field.isOptional ? "true" : "false",
        trackRefExpression: "context.trackRef && \(field.typeText).isReferenceTrackableType"
    )
}

private func buildCompatibleTypeMetaFieldTypeExpression(
    typeText: String,
    nullableExpression: String,
    trackRefExpression: String
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

    return """
TypeMetaFieldType(
    typeID: UInt32(\(concreteType).staticTypeId.rawValue),
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
