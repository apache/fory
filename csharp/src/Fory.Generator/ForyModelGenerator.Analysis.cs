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

using System.Collections.Immutable;
using System.Globalization;
using System.Text;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;

namespace Apache.Fory.Generator;

public sealed partial class ForyModelGenerator
{
    private static TypeModel? BuildTypeModel(GeneratorSyntaxContext context, CancellationToken cancellationToken)
    {
        if (context.SemanticModel.GetDeclaredSymbol(context.Node, cancellationToken) is not INamedTypeSymbol typeSymbol)
        {
            return null;
        }

        AttributeData? attribute = GetForyAttribute(
            typeSymbol,
            out ForyAttributeKind attributeKind,
            out bool hasConflictingAttributes);
        if (attribute is null)
        {
            return null;
        }

        string declarationName = typeSymbol.ToDisplayString(FullNameFormat);
        string generatedClassName = "__ForySerializer_" + Sanitize(
            typeSymbol.ToDisplayString(SymbolDisplayFormat.FullyQualifiedFormat));
        Location? declarationLocation = typeSymbol.Locations.FirstOrDefault(location => location.IsInSource);
        bool evolving = GetEvolving(attribute);
        bool evolvingExplicit = HasNamedArgument(attribute, "Evolving");
        bool baseOnly = GetBaseOnly(attribute);
        bool baseOnlyExplicit = HasNamedArgument(attribute, "BaseOnly");
        if (hasConflictingAttributes)
        {
            return new TypeModel(
                declarationName,
                declarationName,
                typeSymbol,
                generatedClassName,
                DeclKind.Unknown,
                evolving,
                declarationLocation,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray.Create(Diagnostic.Create(
                    InvalidGeneratedDeclaration,
                    declarationLocation,
                    declarationName,
                    "exactly one of ForyStruct, ForyEnum, or ForyUnion is allowed")));
        }

        Location? targetLocation = GetNamedArgumentLocation(attribute, "Target", cancellationToken) ??
                                   declarationLocation;
        ITypeSymbol? target = null;
        bool invalidTargetValue = false;
        foreach (KeyValuePair<string, TypedConstant> namedArgument in attribute.NamedArguments)
        {
            if (!string.Equals(namedArgument.Key, "Target", StringComparison.Ordinal))
            {
                continue;
            }

            if (namedArgument.Value.Value is ITypeSymbol targetSymbol)
            {
                target = targetSymbol;
            }
            else if (!namedArgument.Value.IsNull)
            {
                invalidTargetValue = true;
            }

            break;
        }

        if (invalidTargetValue)
        {
            return new TypeModel(
                declarationName,
                declarationName,
                typeSymbol,
                generatedClassName,
                DeclKind.Unknown,
                evolving,
                declarationLocation,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray.Create(Diagnostic.Create(
                    InvalidExternalTarget,
                    targetLocation,
                    "<unknown>",
                    "Target must name one closed CLR type")));
        }

        if (target is null)
        {
            if (attributeKind == ForyAttributeKind.Struct &&
                typeSymbol.TypeKind == TypeKind.Class &&
                !typeSymbol.IsSealed)
            {
                generatedClassName = GeneratedHierarchyName(typeSymbol);
            }

            return BuildOrdinaryTypeModel(
                context.SemanticModel.Compilation,
                typeSymbol,
                attributeKind,
                declarationName,
                generatedClassName,
                evolving,
                evolvingExplicit,
                baseOnly,
                baseOnlyExplicit,
                declarationLocation);
        }

        if (attributeKind == ForyAttributeKind.Struct &&
            target is INamedTypeSymbol
            {
                TypeKind: not TypeKind.Error,
                ContainingAssembly: not null,
            } namedTarget &&
            !ContainsOpenType(namedTarget) &&
            namedTarget.TypeKind == TypeKind.Class &&
            !namedTarget.IsSealed)
        {
            generatedClassName = GeneratedHierarchyName(namedTarget);
        }

        return attributeKind switch
        {
            ForyAttributeKind.Struct => BuildExternalStructModel(
                context.SemanticModel.Compilation,
                typeSymbol,
                target,
                declarationName,
                generatedClassName,
                evolving,
                evolvingExplicit,
                baseOnly,
                baseOnlyExplicit,
                declarationLocation,
                targetLocation),
            ForyAttributeKind.Enum => BuildExternalEnumModel(
                context.SemanticModel.Compilation,
                typeSymbol,
                target,
                declarationName,
                generatedClassName,
                declarationLocation,
                targetLocation),
            _ => new TypeModel(
                declarationName,
                declarationName,
                target,
                generatedClassName,
                DeclKind.Unknown,
                evolving,
                declarationLocation,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray.Create(Diagnostic.Create(
                    InvalidExternalDeclaration,
                    declarationLocation,
                    declarationName,
                    "Target is supported only by ForyStruct and ForyEnum"))),
        };
    }

    private static TypeModel BuildOrdinaryTypeModel(
        Compilation compilation,
        INamedTypeSymbol typeSymbol,
        ForyAttributeKind attributeKind,
        string declarationName,
        string generatedClassName,
        bool evolving,
        bool evolvingExplicit,
        bool baseOnly,
        bool baseOnlyExplicit,
        Location? declarationLocation)
    {
        ImmutableArray<Diagnostic> ignoredFieldDiagnostics = typeSymbol.GetMembers()
            .Where(member =>
                member is IFieldSymbol or IPropertySymbol &&
                TryGetIgnoredField(member, out _))
            .Select(member => Diagnostic.Create(
                InvalidIgnoredField,
                member.Locations.FirstOrDefault(location => location.IsInSource),
                member.Name,
                "Ignore is supported only by external ForyStruct serializer declarations"))
            .ToImmutableArray();
        if (!ignoredFieldDiagnostics.IsEmpty)
        {
            return new TypeModel(
                declarationName,
                declarationName,
                typeSymbol,
                generatedClassName,
                DeclKind.Unknown,
                evolving,
                declarationLocation,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray<MemberModel>.Empty,
                ignoredFieldDiagnostics);
        }

        if (HasGenericContext(typeSymbol))
        {
            return new TypeModel(
                declarationName,
                declarationName,
                typeSymbol,
                generatedClassName,
                DeclKind.Unknown,
                evolving,
                declarationLocation,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray.Create(Diagnostic.Create(
                    GenericTypeNotSupported,
                    declarationLocation,
                    declarationName)));
        }

        if (attributeKind == ForyAttributeKind.Enum)
        {
            if (typeSymbol.TypeKind != TypeKind.Enum)
            {
                return new TypeModel(
                    declarationName,
                    declarationName,
                    typeSymbol,
                    generatedClassName,
                    DeclKind.Unknown,
                    evolving,
                    declarationLocation,
                    ImmutableArray<MemberModel>.Empty,
                    ImmutableArray<MemberModel>.Empty,
                    ImmutableArray.Create(Diagnostic.Create(
                        InvalidGeneratedDeclaration,
                        declarationLocation,
                        declarationName,
                        "ForyEnum without Target is valid only on an enum")));
            }

            List<Diagnostic> enumDiagnostics = [];
            ValidateEnumValues(typeSymbol, declarationLocation, enumDiagnostics);
            return new TypeModel(
                declarationName,
                declarationName,
                typeSymbol,
                generatedClassName,
                DeclKind.Enum,
                evolving,
                declarationLocation,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray<MemberModel>.Empty,
                enumDiagnostics.ToImmutableArray());
        }

        if (attributeKind == ForyAttributeKind.Union)
        {
            if (typeSymbol.TypeKind != TypeKind.Class)
            {
                return new TypeModel(
                    declarationName,
                    declarationName,
                    typeSymbol,
                    generatedClassName,
                    DeclKind.Unknown,
                    evolving,
                    declarationLocation,
                    ImmutableArray<MemberModel>.Empty,
                    ImmutableArray<MemberModel>.Empty,
                    ImmutableArray.Create(Diagnostic.Create(
                        InvalidUnionType,
                        declarationLocation,
                        declarationName)));
            }

            List<Diagnostic> unionDiagnostics = [];
            ImmutableArray<UnionCaseModel> unionCases = BuildUnionCases(typeSymbol, unionDiagnostics);
            if (unionCases.IsEmpty)
            {
                unionDiagnostics.Add(Diagnostic.Create(
                    InvalidUnionType,
                    declarationLocation,
                    declarationName));
            }

            return new TypeModel(
                declarationName,
                declarationName,
                typeSymbol,
                generatedClassName,
                DeclKind.Union,
                evolving,
                declarationLocation,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray<MemberModel>.Empty,
                unionDiagnostics.ToImmutableArray(),
                unionCases);
        }

        DeclKind kind = typeSymbol.TypeKind switch
        {
            TypeKind.Struct => DeclKind.Struct,
            TypeKind.Class => DeclKind.Class,
            _ => DeclKind.Unknown,
        };
        if (kind == DeclKind.Unknown)
        {
            return new TypeModel(
                declarationName,
                declarationName,
                typeSymbol,
                generatedClassName,
                kind,
                evolving,
                declarationLocation,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray<MemberModel>.Empty,
                ImmutableArray.Create(Diagnostic.Create(
                    InvalidGeneratedDeclaration,
                    declarationLocation,
                    declarationName,
                    "ForyStruct without Target is valid only on a class or struct")));
        }

        List<Diagnostic> diagnostics = [];
        if (typeSymbol.IsStatic)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidGeneratedDeclaration,
                declarationLocation,
                declarationName,
                "ForyStruct cannot target a static class"));
        }

        if (!IsGeneratedTypeNameable(typeSymbol, compilation))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidGeneratedDeclaration,
                declarationLocation,
                declarationName,
                "the target type cannot be named by generated code"));
        }

        if (baseOnlyExplicit)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidGeneratedDeclaration,
                declarationLocation,
                declarationName,
                "BaseOnly is valid only on an external class declaration"));
        }

        bool abstractClass = kind == DeclKind.Class && typeSymbol.IsAbstract;
        if (abstractClass && evolvingExplicit)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidAbstractStructOption,
                GetNamedArgumentLocation(
                    GetForyAttribute(typeSymbol, out _)!,
                    "Evolving",
                    CancellationToken.None) ?? declarationLocation,
                declarationName,
                "Evolving"));
        }

        if (kind == DeclKind.Class && !abstractClass)
        {
            IMethodSymbol? constructor = FindAccessibleParameterlessCtor(typeSymbol, compilation);
            if (constructor is null)
            {
                diagnostics.Add(Diagnostic.Create(
                    MissingCtor,
                    declarationLocation,
                    declarationName));
            }
            else if (HasRequiredMembers(typeSymbol) && !SetsRequiredMembers(constructor))
            {
                diagnostics.Add(Diagnostic.Create(
                    MissingCtor,
                    declarationLocation,
                    declarationName));
            }
        }

        List<MemberModel> members = [];
        int declarationOrdinal = 0;
        foreach (ISymbol member in typeSymbol.GetMembers())
        {
            if (member.IsImplicitlyDeclared ||
                member.IsStatic ||
                member is not (IFieldSymbol or IPropertySymbol))
            {
                continue;
            }

            if (member is IFieldSymbol field)
            {
                bool explicitField = HasForyFieldAttribute(field);
                if (!ValidateOrdinaryFieldOptions(field, diagnostics))
                {
                    continue;
                }

                bool accessible = compilation.IsSymbolAccessibleWithin(field, compilation.Assembly);
                if (!explicitField && !accessible)
                {
                    continue;
                }

                if (kind == DeclKind.Struct && !accessible)
                {
                    diagnostics.Add(Diagnostic.Create(
                        InvalidOrdinaryMember,
                        field.Locations.FirstOrDefault(location => location.IsInSource),
                        field.Name,
                        declarationName,
                        "inaccessible ordinary members are supported only on class targets"));
                    continue;
                }

                if (field.IsConst ||
                    field.IsReadOnly ||
                    field.IsFixedSizeBuffer ||
                    field.Type.TypeKind is TypeKind.Pointer or TypeKind.FunctionPointer ||
                    field.Type.IsRefLikeType)
                {
                    if (explicitField)
                    {
                        diagnostics.Add(Diagnostic.Create(
                            InvalidOrdinaryMember,
                            field.Locations.FirstOrDefault(location => location.IsInSource),
                            field.Name,
                            declarationName,
                            "an explicitly selected field must be a mutable supported instance field"));
                    }

                    continue;
                }

                if (!accessible &&
                    RequiresGenericUnsafeAccessor(typeSymbol, field.Type))
                {
                    diagnostics.Add(Diagnostic.Create(
                        InvalidOrdinaryMember,
                        field.Locations.FirstOrDefault(location => location.IsInSource),
                        field.Name,
                        declarationName,
                        "private generic UnsafeAccessor signatures are not supported on .NET 8"));
                    continue;
                }

                if (RequiresExternAlias(field.Type, compilation))
                {
                    diagnostics.Add(Diagnostic.Create(
                        UnsupportedExternAlias,
                        field.Locations.FirstOrDefault(location => location.IsInSource),
                        field.Type.ToDisplayString(FullNameFormat)));
                    continue;
                }

                if (!IsGeneratedTypeNameable(field.Type, compilation))
                {
                    diagnostics.Add(Diagnostic.Create(
                        UnsupportedGeneratedMemberType,
                        field.Locations.FirstOrDefault(location => location.IsInSource),
                        field.Name,
                        declarationName,
                        field.Type.ToDisplayString(FullNameFormat)));
                    continue;
                }

                MemberModel? parsedField = BuildMemberModel(field.Name, field.Type, field, diagnostics);
                if (parsedField is not null)
                {
                    members.Add(BindSourceMember(
                        compilation,
                        generatedClassName,
                        field,
                        parsedField,
                        declarationOrdinal));
                    declarationOrdinal++;
                }

                continue;
            }

            if (member is IPropertySymbol property)
            {
                bool explicitField = HasForyFieldAttribute(property);
                if (!ValidateOrdinaryFieldOptions(property, diagnostics))
                {
                    continue;
                }

                if (property.ExplicitInterfaceImplementations.Length > 0)
                {
                    if (explicitField)
                    {
                        diagnostics.Add(Diagnostic.Create(
                            InvalidOrdinaryMember,
                            property.Locations.FirstOrDefault(location => location.IsInSource),
                            property.Name,
                            declarationName,
                            "explicit interface implementations are not structural class members"));
                    }

                    continue;
                }

                if (property.IsIndexer ||
                    property.GetMethod is null ||
                    property.SetMethod is null ||
                    property.SetMethod.IsInitOnly ||
                    property.ReturnsByRef ||
                    property.ReturnsByRefReadonly ||
                    property.Type.TypeKind is TypeKind.Pointer or TypeKind.FunctionPointer ||
                    property.Type.IsRefLikeType)
                {
                    if (explicitField)
                    {
                        diagnostics.Add(Diagnostic.Create(
                            InvalidOrdinaryMember,
                            property.Locations.FirstOrDefault(location => location.IsInSource),
                            property.Name,
                            declarationName,
                            "an explicitly selected property must have a supported getter and non-init setter"));
                    }

                    continue;
                }

                bool getterAccessible = compilation.IsSymbolAccessibleWithin(
                    property.GetMethod,
                    compilation.Assembly);
                bool setterAccessible = compilation.IsSymbolAccessibleWithin(
                    property.SetMethod,
                    compilation.Assembly);
                if (!explicitField && (!getterAccessible || !setterAccessible))
                {
                    continue;
                }

                if (kind == DeclKind.Struct &&
                    (!getterAccessible || !setterAccessible))
                {
                    diagnostics.Add(Diagnostic.Create(
                        InvalidOrdinaryMember,
                        property.Locations.FirstOrDefault(location => location.IsInSource),
                        property.Name,
                        declarationName,
                        "inaccessible ordinary members are supported only on class targets"));
                    continue;
                }

                if ((!getterAccessible || !setterAccessible) &&
                    RequiresGenericUnsafeAccessor(typeSymbol, property.Type))
                {
                    diagnostics.Add(Diagnostic.Create(
                        InvalidOrdinaryMember,
                        property.Locations.FirstOrDefault(location => location.IsInSource),
                        property.Name,
                        declarationName,
                        "private generic UnsafeAccessor signatures are not supported on .NET 8"));
                    continue;
                }

                if (RequiresExternAlias(property.Type, compilation))
                {
                    diagnostics.Add(Diagnostic.Create(
                        UnsupportedExternAlias,
                        property.Locations.FirstOrDefault(location => location.IsInSource),
                        property.Type.ToDisplayString(FullNameFormat)));
                    continue;
                }

                if (!IsGeneratedTypeNameable(property.Type, compilation))
                {
                    diagnostics.Add(Diagnostic.Create(
                        UnsupportedGeneratedMemberType,
                        property.Locations.FirstOrDefault(location => location.IsInSource),
                        property.Name,
                        declarationName,
                        property.Type.ToDisplayString(FullNameFormat)));
                    continue;
                }

                MemberModel? parsedProperty = BuildMemberModel(
                    property.Name,
                    property.Type,
                    property,
                    diagnostics);
                if (parsedProperty is not null)
                {
                    members.Add(BindSourceMember(
                        compilation,
                        generatedClassName,
                        property,
                        parsedProperty,
                        declarationOrdinal));
                    declarationOrdinal++;
                }
            }
        }

        ImmutableArray<MemberModel> ordered = members
            .OrderBy(m => m.DeclarationOrdinal)
            .ToImmutableArray();
        string providerVisibility = ProviderVisibility(typeSymbol, ordered);
        ImmutableArray<MemberModel> sorted = SortMembers(ordered);
        ImmutableArray<string> shallowFields = kind == DeclKind.Class
            ? BuildDeclaredShallowFields(compilation, typeSymbol, diagnostics)
            : ImmutableArray<string>.Empty;

        return new TypeModel(
            declarationName,
            declarationName,
            typeSymbol,
            generatedClassName,
            kind,
            evolving,
            declarationLocation,
            ordered,
            sorted,
            diagnostics.ToImmutableArray(),
            declaredMembers: ordered,
            shallowStorage: kind == DeclKind.Class
                ? new ShallowStorageModel(null, shallowFields)
                : null,
            providerOnly: abstractClass,
            providerVisibility: providerVisibility);
    }

    private static bool HasGenericContext(INamedTypeSymbol type)
    {
        for (INamedTypeSymbol? current = type; current is not null; current = current.ContainingType)
        {
            if (current.TypeParameters.Length != 0)
            {
                return true;
            }
        }

        return false;
    }

    private static bool RequiresGenericUnsafeAccessor(
        INamedTypeSymbol declaringType,
        ITypeSymbol memberType)
    {
        return ContainsGenericSignatureType(declaringType) ||
               ContainsGenericSignatureType(memberType);
    }

    private static bool ContainsGenericSignatureType(ITypeSymbol type)
    {
        switch (type)
        {
            case IArrayTypeSymbol array:
                return ContainsGenericSignatureType(array.ElementType);
            case IPointerTypeSymbol pointer:
                return ContainsGenericSignatureType(pointer.PointedAtType);
            case INamedTypeSymbol named:
                return named.OriginalDefinition.Arity > 0 ||
                       named.ContainingType is not null &&
                       ContainsGenericSignatureType(named.ContainingType) ||
                       named.TypeArguments.Any(ContainsGenericSignatureType);
            default:
                return false;
        }
    }

    private static bool ContainsOpenType(ITypeSymbol type)
    {
        if (type.TypeKind == TypeKind.TypeParameter)
        {
            return true;
        }

        if (type is IArrayTypeSymbol array)
        {
            return ContainsOpenType(array.ElementType);
        }

        if (type is IPointerTypeSymbol pointer)
        {
            return ContainsOpenType(pointer.PointedAtType);
        }

        if (type is not INamedTypeSymbol named)
        {
            return false;
        }

        if (named.IsUnboundGenericType ||
            named.ContainingType is not null && ContainsOpenType(named.ContainingType))
        {
            return true;
        }

        foreach (ITypeSymbol argument in named.TypeArguments)
        {
            if (ContainsOpenType(argument))
            {
                return true;
            }
        }

        return false;
    }

    private static bool IsRuntimeOwnedTarget(INamedTypeSymbol type)
    {
        if (type.SpecialType == SpecialType.System_Object)
        {
            return true;
        }

        string containingNamespace = type.ContainingNamespace.ToDisplayString();
        if (string.Equals(containingNamespace, "System", StringComparison.Ordinal))
        {
            return type.Name is
                "ArraySegment" or
                "Memory" or
                "Nullable" or
                "ReadOnlyMemory" or
                "Tuple" or
                "ValueTuple";
        }

        return string.Equals(
                   containingNamespace,
                   "System.Collections.Generic",
                   StringComparison.Ordinal) &&
               string.Equals(type.Name, "KeyValuePair", StringComparison.Ordinal);
    }

    private static bool RequiresExternAlias(ITypeSymbol type, Compilation compilation)
    {
        if (type is IArrayTypeSymbol array)
        {
            return RequiresExternAlias(array.ElementType, compilation);
        }

        if (type is IPointerTypeSymbol pointer)
        {
            return RequiresExternAlias(pointer.PointedAtType, compilation);
        }

        if (type is not INamedTypeSymbol named)
        {
            return false;
        }

        if (AssemblyRequiresExternAlias(named.ContainingAssembly, compilation))
        {
            return true;
        }

        if (named.ContainingType is not null &&
            RequiresExternAlias(named.ContainingType, compilation))
        {
            return true;
        }

        foreach (ITypeSymbol argument in named.TypeArguments)
        {
            if (RequiresExternAlias(argument, compilation))
            {
                return true;
            }
        }

        return false;
    }

    private static bool IsGeneratedTypeNameable(
        ITypeSymbol type,
        Compilation compilation)
    {
        switch (type)
        {
            case IDynamicTypeSymbol:
                return true;
            case IArrayTypeSymbol array:
                return IsGeneratedTypeNameable(array.ElementType, compilation);
            case IPointerTypeSymbol pointer:
                return IsGeneratedTypeNameable(pointer.PointedAtType, compilation);
            case INamedTypeSymbol named:
                return compilation.IsSymbolAccessibleWithin(
                           named.OriginalDefinition,
                           compilation.Assembly) &&
                       (named.ContainingType is null ||
                        IsGeneratedTypeNameable(named.ContainingType, compilation)) &&
                       named.TypeArguments.All(argument =>
                           IsGeneratedTypeNameable(argument, compilation));
            default:
                return false;
        }
    }

    private static bool AssemblyRequiresExternAlias(IAssemblySymbol assembly, Compilation compilation)
    {
        if (SymbolEqualityComparer.Default.Equals(assembly, compilation.Assembly))
        {
            return false;
        }

        bool foundAssembly = false;
        foreach (MetadataReference reference in compilation.References)
        {
            if (!SymbolEqualityComparer.Default.Equals(
                    compilation.GetAssemblyOrModuleSymbol(reference),
                    assembly))
            {
                continue;
            }

            foundAssembly = true;
            if (reference.Properties.Aliases.IsDefaultOrEmpty)
            {
                return false;
            }

            foreach (string alias in reference.Properties.Aliases)
            {
                if (string.Equals(alias, "global", StringComparison.Ordinal))
                {
                    return false;
                }
            }
        }

        return foundAssembly;
    }

    private static IMethodSymbol? FindAccessibleParameterlessCtor(
        INamedTypeSymbol type,
        Compilation compilation)
    {
        foreach (IMethodSymbol constructor in type.InstanceConstructors)
        {
            if (constructor.Parameters.Length == 0 &&
                compilation.IsSymbolAccessibleWithin(constructor, compilation.Assembly))
            {
                return constructor;
            }
        }

        return null;
    }

    private static bool HasRequiredMembers(INamedTypeSymbol type)
    {
        for (INamedTypeSymbol? current = type; current is not null; current = current.BaseType)
        {
            foreach (ISymbol member in current.GetMembers())
            {
                if (member is IFieldSymbol { IsRequired: true } or
                    IPropertySymbol { IsRequired: true })
                {
                    return true;
                }
            }
        }

        return false;
    }

    private static bool SetsRequiredMembers(IMethodSymbol constructor)
    {
        foreach (AttributeData attribute in constructor.GetAttributes())
        {
            if (string.Equals(
                    attribute.AttributeClass?.ToDisplayString(),
                    "System.Diagnostics.CodeAnalysis.SetsRequiredMembersAttribute",
                    StringComparison.Ordinal))
            {
                return true;
            }
        }

        return false;
    }

    private static void ValidateEnumValues(
        INamedTypeSymbol enumType,
        Location? fallbackLocation,
        List<Diagnostic> diagnostics)
    {
        foreach (IFieldSymbol field in enumType.GetMembers().OfType<IFieldSymbol>())
        {
            if (!field.HasConstantValue ||
                IsSupportedEnumValue(field.ConstantValue))
            {
                continue;
            }

            diagnostics.Add(Diagnostic.Create(
                EnumValueOutOfRange,
                field.Locations.FirstOrDefault(location => location.IsInSource) ?? fallbackLocation,
                $"{enumType.ToDisplayString(FullNameFormat)}.{field.Name}"));
        }
    }

    private static bool IsSupportedEnumValue(object? value)
    {
        return value switch
        {
            byte => true,
            ushort => true,
            uint => true,
            ulong unsignedValue => unsignedValue <= uint.MaxValue,
            sbyte signedValue => signedValue >= 0,
            short signedValue => signedValue >= 0,
            int signedValue => signedValue >= 0,
            long signedValue => signedValue is >= 0 and <= uint.MaxValue,
            _ => false,
        };
    }

    private static bool GetEvolving(AttributeData attribute)
    {
        foreach (KeyValuePair<string, TypedConstant> namedArgument in attribute.NamedArguments)
        {
            if (string.Equals(namedArgument.Key, "Evolving", StringComparison.Ordinal) &&
                namedArgument.Value.Value is bool evolving)
            {
                return evolving;
            }
        }

        return true;
    }

    private static bool GetBaseOnly(AttributeData attribute)
    {
        foreach (KeyValuePair<string, TypedConstant> namedArgument in attribute.NamedArguments)
        {
            if (string.Equals(namedArgument.Key, "BaseOnly", StringComparison.Ordinal) &&
                namedArgument.Value.Value is bool baseOnly)
            {
                return baseOnly;
            }
        }

        return false;
    }

    private static bool HasNamedArgument(AttributeData attribute, string name)
    {
        return attribute.NamedArguments.Any(argument =>
            string.Equals(argument.Key, name, StringComparison.Ordinal));
    }

    private static Location? GetNamedArgumentLocation(
        AttributeData attribute,
        string argumentName,
        CancellationToken cancellationToken)
    {
        if (attribute.ApplicationSyntaxReference?.GetSyntax(cancellationToken) is not AttributeSyntax attributeSyntax)
        {
            return null;
        }

        foreach (AttributeArgumentSyntax argument in attributeSyntax.ArgumentList?.Arguments ??
                                                            default(SeparatedSyntaxList<AttributeArgumentSyntax>))
        {
            if (string.Equals(argument.NameEquals?.Name.Identifier.ValueText, argumentName, StringComparison.Ordinal))
            {
                return argument.Expression.GetLocation();
            }
        }

        return attributeSyntax.GetLocation();
    }

    private static ImmutableArray<UnionCaseModel> BuildUnionCases(
        INamedTypeSymbol unionType,
        List<Diagnostic> diagnostics)
    {
        List<UnionCaseModel> cases = [];
        HashSet<int> caseIds = [];
        foreach (INamedTypeSymbol caseType in unionType.GetTypeMembers())
        {
            bool isUnknown = HasForyUnknownCase(caseType);
            if (!TryGetForyCase(caseType, diagnostics, out int caseId, out SchemaTypeModel? schemaType))
            {
                if (isUnknown)
                {
                    string unknownCaseTypeName = caseType.ToDisplayString(FullNameFormat);
                    if (!SymbolEqualityComparer.Default.Equals(caseType.BaseType, unionType))
                    {
                        diagnostics.Add(Diagnostic.Create(
                            InvalidUnionCase,
                            caseType.Locations.FirstOrDefault(),
                            unknownCaseTypeName,
                            "unknown case type must directly derive from the annotated union root"));
                        continue;
                    }

                    if (!string.Equals(caseType.Name, "Unknown", StringComparison.Ordinal) ||
                        !HasUnknownCaseValueProperty(caseType))
                    {
                        diagnostics.Add(Diagnostic.Create(
                            InvalidUnionCase,
                            caseType.Locations.FirstOrDefault(),
                            unknownCaseTypeName,
                            "unknown case must be named Unknown and expose Value:UnknownCase"));
                        continue;
                    }

                    cases.Add(new UnionCaseModel(null, unknownCaseTypeName, isUnknown: true, valueMember: null));
                }

                continue;
            }

            if (isUnknown)
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidUnionCase,
                    caseType.Locations.FirstOrDefault(),
                    caseType.ToDisplayString(FullNameFormat),
                    "unknown case must use [ForyUnknownCase] without [ForyCase]"));
                continue;
            }

            if (!caseIds.Add(caseId))
            {
                diagnostics.Add(Diagnostic.Create(
                    DuplicateUnionCaseId,
                    caseType.Locations.FirstOrDefault(),
                    caseId,
                    unionType.ToDisplayString(FullNameFormat)));
                continue;
            }

            if (!SymbolEqualityComparer.Default.Equals(caseType.BaseType, unionType))
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidUnionCase,
                    caseType.Locations.FirstOrDefault(),
                    caseType.ToDisplayString(FullNameFormat),
                    "case type must directly derive from the annotated union root"));
                continue;
            }

            string caseTypeName = caseType.ToDisplayString(FullNameFormat);
            IPropertySymbol? valueProperty = FindProperty(caseType, "Value");
            if (valueProperty is null)
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidUnionCase,
                    caseType.Locations.FirstOrDefault(),
                    caseTypeName,
                    "known cases must expose a Value property"));
                continue;
            }

            MemberModel? valueMember = BuildMemberModel(
                valueProperty.Name,
                valueProperty.Type,
                valueProperty,
                diagnostics,
                schemaType,
                parseFieldAttribute: false);
            if (valueMember is null)
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidUnionCase,
                    valueProperty.Locations.FirstOrDefault(),
                    caseTypeName,
                    "case Value type is not supported"));
                continue;
            }

            cases.Add(new UnionCaseModel(caseId, caseTypeName, isUnknown: false, valueMember));
        }

        if (cases.Count(c => c.IsUnknown) > 1)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidUnionCase,
                unionType.Locations.FirstOrDefault(),
                unionType.ToDisplayString(FullNameFormat),
                "union must declare exactly one [ForyUnknownCase] Unknown"));
        }
        else if (!cases.Any(c => c.IsUnknown))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidUnionCase,
                unionType.Locations.FirstOrDefault(),
                unionType.ToDisplayString(FullNameFormat),
                "union must declare [ForyUnknownCase] Unknown"));
        }
        else if (!cases.Any(c => !c.IsUnknown))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidUnionCase,
                unionType.Locations.FirstOrDefault(),
                unionType.ToDisplayString(FullNameFormat),
                "union must declare at least one non-Unknown case; Unknown is a forward-compatibility carrier and cannot be the default"));
        }

        return cases
            .OrderBy(c => c.CaseId ?? -1)
            .ToImmutableArray();
    }

    private static IEnumerable<UnionCaseModel> KnownUnionCases(TypeModel model)
    {
        return model.UnionCases
            .Where(c => !c.IsUnknown)
            .OrderBy(c => c.KnownCaseId);
    }

    private static bool TryGetForyCase(
        INamedTypeSymbol caseType,
        List<Diagnostic> diagnostics,
        out int caseId,
        out SchemaTypeModel? schemaType)
    {
        caseId = default;
        schemaType = null;
        foreach (AttributeData attribute in caseType.GetAttributes())
        {
            string? attrName = attribute.AttributeClass?.ToDisplayString();
            if (!string.Equals(attrName, "Apache.Fory.ForyCaseAttribute", StringComparison.Ordinal))
            {
                continue;
            }

            if (attribute.ConstructorArguments.Length != 1 ||
                !TryGetUnionCaseId(attribute.ConstructorArguments[0], out caseId))
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidUnionCase,
                    caseType.Locations.FirstOrDefault(),
                    caseType.ToDisplayString(FullNameFormat),
                    "case id must be a non-negative int"));
                return true;
            }

            foreach (KeyValuePair<string, TypedConstant> namedArg in attribute.NamedArguments)
            {
                if (!string.Equals(namedArg.Key, "Type", StringComparison.Ordinal))
                {
                    continue;
                }

                if (namedArg.Value.Value is not ITypeSymbol schemaSymbol ||
                    TryParseSchemaType(schemaSymbol) is not SchemaTypeModel parsedSchema)
                {
                    diagnostics.Add(Diagnostic.Create(
                        InvalidUnionCase,
                        caseType.Locations.FirstOrDefault(),
                        caseType.ToDisplayString(FullNameFormat),
                        "ForyCase.Type must be an Apache.Fory.Schema.Types descriptor"));
                    continue;
                }

                schemaType = parsedSchema;
            }

            return true;
        }

        return false;
    }

    private static bool TryGetUnionCaseId(TypedConstant value, out int caseId)
    {
        caseId = default;
        if (value.Value is int id && id >= 0)
        {
            caseId = id;
            return true;
        }

        return false;
    }

    private static bool HasForyUnknownCase(INamedTypeSymbol caseType)
    {
        foreach (AttributeData attribute in caseType.GetAttributes())
        {
            string? attrName = attribute.AttributeClass?.ToDisplayString();
            if (string.Equals(attrName, "Apache.Fory.ForyUnknownCaseAttribute", StringComparison.Ordinal))
            {
                return true;
            }
        }

        return false;
    }

    private static IPropertySymbol? FindProperty(INamedTypeSymbol type, string name)
    {
        foreach (ISymbol member in type.GetMembers(name))
        {
            if (member is IPropertySymbol property && !property.IsStatic)
            {
                return property;
            }
        }

        return null;
    }

    private static bool HasUnknownCaseValueProperty(INamedTypeSymbol type)
    {
        IPropertySymbol? property = FindProperty(type, "Value");
        return property is not null &&
               string.Equals(
                   property.Type.ToDisplayString(FullNameFormat),
                   "global::Apache.Fory.UnknownCase",
                   StringComparison.Ordinal);
    }

    private static AttributeData? GetForyAttribute(
        INamedTypeSymbol typeSymbol,
        out ForyAttributeKind attributeKind)
    {
        return GetForyAttribute(typeSymbol, out attributeKind, out _);
    }

    private static AttributeData? GetForyAttribute(
        INamedTypeSymbol typeSymbol,
        out ForyAttributeKind attributeKind,
        out bool hasConflict)
    {
        AttributeData? result = null;
        attributeKind = ForyAttributeKind.None;
        hasConflict = false;
        foreach (AttributeData attribute in typeSymbol.GetAttributes())
        {
            string? attrName = attribute.AttributeClass?.ToDisplayString();
            ForyAttributeKind currentKind;
            if (string.Equals(attrName, "Apache.Fory.ForyStructAttribute", StringComparison.Ordinal))
            {
                currentKind = ForyAttributeKind.Struct;
            }
            else if (string.Equals(attrName, "Apache.Fory.ForyEnumAttribute", StringComparison.Ordinal))
            {
                currentKind = ForyAttributeKind.Enum;
            }
            else if (string.Equals(attrName, "Apache.Fory.ForyUnionAttribute", StringComparison.Ordinal))
            {
                currentKind = ForyAttributeKind.Union;
            }
            else
            {
                continue;
            }

            if (result is not null)
            {
                hasConflict = true;
                continue;
            }

            result = attribute;
            attributeKind = currentKind;
        }

        return result;
    }

    private static ForyAttributeKind GetForyAttributeKind(INamedTypeSymbol typeSymbol)
    {
        _ = GetForyAttribute(typeSymbol, out ForyAttributeKind attributeKind);
        return attributeKind;
    }

    private static bool TryGetIgnoredField(
        ISymbol member,
        out AttributeData? fieldAttribute)
    {
        fieldAttribute = null;
        foreach (AttributeData attribute in member.GetAttributes())
        {
            if (!string.Equals(
                    attribute.AttributeClass?.ToDisplayString(),
                    "Apache.Fory.ForyFieldAttribute",
                    StringComparison.Ordinal))
            {
                continue;
            }

            fieldAttribute = attribute;
            foreach (KeyValuePair<string, TypedConstant> namedArgument in attribute.NamedArguments)
            {
                if (string.Equals(namedArgument.Key, "Ignore", StringComparison.Ordinal) &&
                    namedArgument.Value.Value is true)
                {
                    return true;
                }
            }

            return false;
        }

        return false;
    }

    private static AttributeData? GetEffectiveForyFieldAttribute(ISymbol member)
    {
        for (ISymbol? current = member;
             current is not null;
             current = current is IPropertySymbol property ? property.OverriddenProperty : null)
        {
            foreach (AttributeData attribute in current.GetAttributes())
            {
                if (string.Equals(
                        attribute.AttributeClass?.ToDisplayString(),
                        "Apache.Fory.ForyFieldAttribute",
                        StringComparison.Ordinal))
                {
                    return attribute;
                }
            }
        }

        return null;
    }

    private static bool HasForyFieldAttribute(ISymbol member)
    {
        return GetEffectiveForyFieldAttribute(member) is not null;
    }

    private static string BuildPropertySlotKey(IPropertySymbol property)
    {
        while (property.OverriddenProperty is IPropertySymbol overridden)
        {
            property = overridden;
        }

        return $"{BuildRuntimeTypeKey(property.ContainingType)}|P|{property.MetadataName}";
    }

    private static bool IgnoredFieldHasWireOptions(AttributeData fieldAttribute)
    {
        if (!fieldAttribute.ConstructorArguments.IsEmpty)
        {
            return true;
        }

        return fieldAttribute.NamedArguments.Any(
            argument => argument.Key is "Id" or "Type");
    }

    private static MemberModel? BuildMemberModel(
        string name,
        ITypeSymbol memberType,
        ISymbol memberSymbol,
        List<Diagnostic> diagnostics)
    {
        return BuildMemberModel(
            name,
            memberType,
            memberSymbol,
            diagnostics,
            schemaTypeOverride: null,
            parseFieldAttribute: true);
    }

    private static MemberModel? BuildMemberModel(
        string name,
        ITypeSymbol memberType,
        ISymbol memberSymbol,
        List<Diagnostic> diagnostics,
        SchemaTypeModel? schemaTypeOverride,
        bool parseFieldAttribute,
        short? fieldIdOverride = null,
        ITypeSymbol? schemaDescriptorTypeOverride = null)
    {
        (bool isOptional, ITypeSymbol unwrappedType) = UnwrapNullable(memberType);
        short? fieldId = fieldIdOverride;
        SchemaTypeModel? schemaType = schemaTypeOverride;
        ITypeSymbol? schemaDescriptorType = schemaDescriptorTypeOverride;
        bool invalidSchemaType = false;
        if (parseFieldAttribute)
        {
            AttributeData? fieldAttribute = GetEffectiveForyFieldAttribute(memberSymbol);
            if (fieldAttribute is not null)
            {
                if (fieldAttribute.ConstructorArguments.Length == 1 &&
                    TryGetFieldId(fieldAttribute.ConstructorArguments[0], memberSymbol, diagnostics, out short ctorFieldId))
                {
                    fieldId = ctorFieldId;
                }

                foreach (KeyValuePair<string, TypedConstant> namedArg in fieldAttribute.NamedArguments)
                {
                    if (string.Equals(namedArg.Key, "Id", StringComparison.Ordinal))
                    {
                        if (TryGetFieldId(namedArg.Value, memberSymbol, diagnostics, out short parsedFieldId))
                        {
                            fieldId = parsedFieldId;
                        }

                        continue;
                    }

                    if (!string.Equals(namedArg.Key, "Type", StringComparison.Ordinal))
                    {
                        continue;
                    }

                    if (namedArg.Value.Value is ITypeSymbol schemaSymbol)
                    {
                        schemaDescriptorType = schemaSymbol;
                        schemaType = TryParseSchemaType(schemaSymbol);
                        if (schemaType is null)
                        {
                            invalidSchemaType = true;
                            diagnostics.Add(Diagnostic.Create(
                                UnsupportedSchemaType,
                                memberSymbol.Locations.FirstOrDefault(),
                                memberSymbol.Name,
                                memberType.ToDisplayString(FullNameFormat)));
                        }
                    }
                    else if (!namedArg.Value.IsNull)
                    {
                        invalidSchemaType = true;
                        diagnostics.Add(Diagnostic.Create(
                            UnsupportedSchemaType,
                            memberSymbol.Locations.FirstOrDefault(),
                            memberSymbol.Name,
                            memberType.ToDisplayString(FullNameFormat)));
                    }
                }
            }
        }

        if (invalidSchemaType)
        {
            return null;
        }

        DynamicAnyKind dynamicAnyKind = ResolveDynamicAnyKind(unwrappedType);
        TypeResolution resolution = ResolveTypeResolution(unwrappedType, schemaType);
        if (!resolution.Supported)
        {
            return null;
        }

        TypeClassification classification = resolution.Classification;
        int group = classification.IsPrimitive
            ? (isOptional ? 2 : 1)
            : 3;

        string typeName = memberType.ToDisplayString(FullNameFormat);
        TypeMetaFieldTypeModel typeMeta = BuildTypeMetaFieldTypeModel(
            memberType,
            isOptional,
            dynamicAnyKind,
            resolution.Classification.TypeId,
            schemaType);
        FieldCodecModel? fieldCodec = BuildFieldCodecModel(memberType, typeMeta, schemaType, classification);

        return new MemberModel(
            name,
            ToSnakeCase(name),
            typeName,
            isOptional,
            memberType is INamedTypeSymbol nts &&
            nts.OriginalDefinition.SpecialType == SpecialType.System_Nullable_T,
            fieldId,
            classification,
            group,
            classification.IsCollection || classification.IsMap,
            classification.IsMap && !IsTypeSealed(unwrappedType),
            !unwrappedType.IsValueType && classification.TypeId != 21,
            FieldNeedsTypeInfo(classification, dynamicAnyKind, unwrappedType),
            dynamicAnyKind == DynamicAnyKind.None ? DynamicAnyKind.None : dynamicAnyKind,
            typeMeta,
            fieldCodec,
            schemaType is not null,
            memberType,
            memberSymbol.ContainingType,
            memberSymbol.Name,
            memberSymbol is IPropertySymbol ? WireMemberKind.Property : WireMemberKind.Field,
            memberSymbol is IPropertySymbol property
                ? BuildPropertySlotKey(property)
                : null,
            schemaDescriptorType: schemaDescriptorType);
    }

    private static int FixedGraphValueBytes(ITypeSymbol type, TypeClassification classification)
    {
        if (classification.IsPrimitive && classification.PrimitiveSize > 0)
        {
            return classification.PrimitiveSize;
        }

        if (type.TypeKind == TypeKind.Enum &&
            type is INamedTypeSymbol enumType &&
            enumType.EnumUnderlyingType is not null)
        {
            return SpecialTypeBytes(enumType.EnumUnderlyingType.SpecialType);
        }

        return type.SpecialType == SpecialType.System_Decimal ? 16 : 0;
    }

    private static int SpecialTypeBytes(SpecialType specialType)
    {
        return specialType switch
        {
            SpecialType.System_Boolean or
            SpecialType.System_SByte or
            SpecialType.System_Byte => 1,
            SpecialType.System_Int16 or
            SpecialType.System_UInt16 => 2,
            SpecialType.System_Int32 or
            SpecialType.System_UInt32 or
            SpecialType.System_Single => 4,
            SpecialType.System_Int64 or
            SpecialType.System_UInt64 or
            SpecialType.System_Double => 8,
            _ => 0,
        };
    }

    private static TypeMetaFieldTypeModel BuildTypeMetaFieldTypeModel(
        ITypeSymbol memberType,
        bool nullable,
        DynamicAnyKind dynamicAnyKind,
        uint explicitTypeId,
        SchemaTypeModel? schemaType = null)
    {
        (bool _, ITypeSymbol unwrapped) = UnwrapNullable(memberType);

        if (schemaType is not null)
        {
            return BuildSchemaTypeMetaFieldTypeModel(memberType, nullable, schemaType);
        }

        if (unwrapped is IArrayTypeSymbol &&
            ClassifyType(unwrapped) is { TypeId: not 22 } arrayClassification &&
            IsPackedArrayTypeId(arrayClassification.TypeId))
        {
            return new TypeMetaFieldTypeModel(
                $"(uint){arrayClassification.TypeId}",
                nullable,
                false,
                ImmutableArray<TypeMetaFieldTypeModel>.Empty);
        }

        if (TryGetListElementType(unwrapped, out ITypeSymbol? listElementType))
        {
            bool elementNullable = GenericNullable(listElementType!);
            TypeMetaFieldTypeModel element = BuildTypeMetaFieldTypeModel(
                listElementType!,
                elementNullable,
                ResolveDynamicAnyKind(UnwrapNullable(listElementType!).Item2),
                0);
            return new TypeMetaFieldTypeModel(
                "(uint)global::Apache.Fory.TypeId.List",
                nullable,
                false,
                ImmutableArray.Create(element));
        }

        if (TryGetSetElementType(unwrapped, out ITypeSymbol? setElementType))
        {
            bool elementNullable = GenericNullable(setElementType!);
            TypeMetaFieldTypeModel element = BuildTypeMetaFieldTypeModel(
                setElementType!,
                elementNullable,
                ResolveDynamicAnyKind(UnwrapNullable(setElementType!).Item2),
                0);
            return new TypeMetaFieldTypeModel(
                "(uint)global::Apache.Fory.TypeId.Set",
                nullable,
                false,
                ImmutableArray.Create(element));
        }

        if (TryGetMapTypeArguments(unwrapped, out ITypeSymbol? keyType, out ITypeSymbol? valueType))
        {
            bool keyNullable = GenericNullable(keyType!);
            bool valueNullable = GenericNullable(valueType!);
            TypeMetaFieldTypeModel key = BuildTypeMetaFieldTypeModel(
                keyType!,
                keyNullable,
                ResolveDynamicAnyKind(UnwrapNullable(keyType!).Item2),
                0);
            TypeMetaFieldTypeModel value = BuildTypeMetaFieldTypeModel(
                valueType!,
                valueNullable,
                ResolveDynamicAnyKind(UnwrapNullable(valueType!).Item2),
                0);
            return new TypeMetaFieldTypeModel(
                "(uint)global::Apache.Fory.TypeId.Map",
                nullable,
                false,
                ImmutableArray.Create(key, value));
        }

        TypeClassification classification = ClassifyType(unwrapped);
        if (explicitTypeId != 0 && classification.IsPrimitive && classification.TypeId != explicitTypeId)
        {
            return new TypeMetaFieldTypeModel(
                explicitTypeId.ToString(),
                nullable,
                false,
                ImmutableArray<TypeMetaFieldTypeModel>.Empty);
        }

        if (IsUnionType(unwrapped))
        {
            // The field owner supplies the union schema, so static union fields
            // must use UNION. TYPED_UNION/NAMED_UNION are root or dynamic Any
            // identities where no field schema is available.
            return new TypeMetaFieldTypeModel(
                "(uint)global::Apache.Fory.TypeId.Union",
                nullable,
                true,
                ImmutableArray<TypeMetaFieldTypeModel>.Empty);
        }

        if (dynamicAnyKind == DynamicAnyKind.AnyValue)
        {
            return new TypeMetaFieldTypeModel(
                "(uint)global::Apache.Fory.TypeId.Unknown",
                nullable,
                true,
                ImmutableArray<TypeMetaFieldTypeModel>.Empty);
        }

        if (unwrapped.TypeKind == TypeKind.Enum)
        {
            return new TypeMetaFieldTypeModel(
                "(uint)global::Apache.Fory.TypeId.Enum",
                nullable,
                false,
                ImmutableArray<TypeMetaFieldTypeModel>.Empty);
        }

        return new TypeMetaFieldTypeModel(
            $"(uint){classification.TypeId}",
            nullable,
            !classification.IsBuiltIn && unwrapped.TypeKind != TypeKind.Enum,
            ImmutableArray<TypeMetaFieldTypeModel>.Empty);
    }

    private static TypeMetaFieldTypeModel BuildSchemaTypeMetaFieldTypeModel(
        ITypeSymbol carrierType,
        bool nullable,
        SchemaTypeModel schemaType)
    {
        (bool _, ITypeSymbol unwrapped) = UnwrapNullable(carrierType);
        switch (schemaType.Kind)
        {
            case SchemaTypeKind.List:
                if (!TryGetListElementType(unwrapped, out ITypeSymbol? listElementType))
                {
                    return new TypeMetaFieldTypeModel(
                        schemaType.TypeId.ToString(),
                        nullable,
                        false,
                        ImmutableArray<TypeMetaFieldTypeModel>.Empty);
                }

                bool elementNullable = GenericNullable(listElementType!);
                return new TypeMetaFieldTypeModel(
                    "(uint)global::Apache.Fory.TypeId.List",
                    nullable,
                    false,
                    ImmutableArray.Create(
                        BuildSchemaTypeMetaFieldTypeModel(
                            listElementType!,
                            elementNullable,
                            schemaType.Generics[0])));
            case SchemaTypeKind.Set:
                if (!TryGetSetElementType(unwrapped, out ITypeSymbol? setElementType))
                {
                    return new TypeMetaFieldTypeModel(
                        schemaType.TypeId.ToString(),
                        nullable,
                        false,
                        ImmutableArray<TypeMetaFieldTypeModel>.Empty);
                }

                bool setElementNullable = GenericNullable(setElementType!);
                return new TypeMetaFieldTypeModel(
                    "(uint)global::Apache.Fory.TypeId.Set",
                    nullable,
                    false,
                    ImmutableArray.Create(
                        BuildSchemaTypeMetaFieldTypeModel(
                            setElementType!,
                            setElementNullable,
                            schemaType.Generics[0])));
            case SchemaTypeKind.Map:
                if (!TryGetMapTypeArguments(unwrapped, out ITypeSymbol? keyType, out ITypeSymbol? valueType))
                {
                    return new TypeMetaFieldTypeModel(
                        schemaType.TypeId.ToString(),
                        nullable,
                        false,
                        ImmutableArray<TypeMetaFieldTypeModel>.Empty);
                }

                bool keyNullable = GenericNullable(keyType!);
                bool valueNullable = GenericNullable(valueType!);
                return new TypeMetaFieldTypeModel(
                    "(uint)global::Apache.Fory.TypeId.Map",
                    nullable,
                    false,
                    ImmutableArray.Create(
                        BuildSchemaTypeMetaFieldTypeModel(keyType!, keyNullable, schemaType.Generics[0]),
                        BuildSchemaTypeMetaFieldTypeModel(valueType!, valueNullable, schemaType.Generics[1])));
            default:
                return new TypeMetaFieldTypeModel(
                    schemaType.TypeId.ToString(),
                    nullable,
                    false,
                    ImmutableArray<TypeMetaFieldTypeModel>.Empty);
        }
    }

    private static FieldCodecModel? BuildFieldCodecModel(
        ITypeSymbol carrierType,
        TypeMetaFieldTypeModel typeMeta,
        SchemaTypeModel? schemaType,
        TypeClassification classification)
    {
        (bool nullable, ITypeSymbol unwrapped) = UnwrapNullable(carrierType);
        bool nullableValueType = carrierType is INamedTypeSymbol nts &&
                                 nts.OriginalDefinition.SpecialType == SpecialType.System_Nullable_T;

        if (schemaType is not null)
        {
            FieldCodecModel codec = BuildFieldCodecFromSchema(carrierType, nullable, nullableValueType, schemaType);
            return codec.Kind == FieldCodecKind.Scalar ? null : codec;
        }

        _ = typeMeta;
        _ = classification;
        return null;
    }

    private static FieldCodecModel BuildFieldCodecFromSchema(
        ITypeSymbol carrierType,
        bool nullable,
        bool nullableValueType,
        SchemaTypeModel schemaType)
    {
        (bool _, ITypeSymbol unwrapped) = UnwrapNullable(carrierType);
        switch (schemaType.Kind)
        {
            case SchemaTypeKind.List:
                {
                    ITypeSymbol elementType = TryGetListElementType(unwrapped, out ITypeSymbol? listElementType)
                        ? listElementType!
                        : carrierType;
                    FieldCodecModel element = BuildFieldCodecFromSchema(
                        elementType,
                        GenericNullable(elementType),
                        elementType is INamedTypeSymbol elementNamed &&
                        elementNamed.OriginalDefinition.SpecialType == SpecialType.System_Nullable_T,
                        schemaType.Generics[0]);
                    return new FieldCodecModel(
                        FieldCodecKind.List,
                        schemaType.TypeId,
                        carrierType.ToDisplayString(FullNameFormat),
                        nullable,
                        nullableValueType,
                        GetCarrierKind(unwrapped),
                        ImmutableArray.Create(element));
                }
            case SchemaTypeKind.Set:
                {
                    ITypeSymbol elementType = TryGetSetElementType(unwrapped, out ITypeSymbol? setElementType)
                        ? setElementType!
                        : carrierType;
                    FieldCodecModel element = BuildFieldCodecFromSchema(
                        elementType,
                        GenericNullable(elementType),
                        elementType is INamedTypeSymbol elementNamed &&
                        elementNamed.OriginalDefinition.SpecialType == SpecialType.System_Nullable_T,
                        schemaType.Generics[0]);
                    return new FieldCodecModel(
                        FieldCodecKind.Set,
                        schemaType.TypeId,
                        carrierType.ToDisplayString(FullNameFormat),
                        nullable,
                        nullableValueType,
                        GetCarrierKind(unwrapped),
                        ImmutableArray.Create(element));
                }
            case SchemaTypeKind.Map:
                {
                    ITypeSymbol keyType = carrierType;
                    ITypeSymbol valueType = carrierType;
                    if (TryGetMapTypeArguments(unwrapped, out ITypeSymbol? parsedKeyType, out ITypeSymbol? parsedValueType))
                    {
                        keyType = parsedKeyType!;
                        valueType = parsedValueType!;
                    }

                    FieldCodecModel key = BuildFieldCodecFromSchema(
                        keyType,
                        GenericNullable(keyType),
                        keyType is INamedTypeSymbol keyNamed &&
                        keyNamed.OriginalDefinition.SpecialType == SpecialType.System_Nullable_T,
                        schemaType.Generics[0]);
                    FieldCodecModel value = BuildFieldCodecFromSchema(
                        valueType,
                        GenericNullable(valueType),
                        valueType is INamedTypeSymbol valueNamed &&
                        valueNamed.OriginalDefinition.SpecialType == SpecialType.System_Nullable_T,
                        schemaType.Generics[1]);
                    return new FieldCodecModel(
                        FieldCodecKind.Map,
                        schemaType.TypeId,
                        carrierType.ToDisplayString(FullNameFormat),
                        nullable,
                        nullableValueType,
                        GetCarrierKind(unwrapped),
                        ImmutableArray.Create(key, value));
                }
            case SchemaTypeKind.PackedArray:
                return new FieldCodecModel(
                    FieldCodecKind.PackedArray,
                    schemaType.TypeId,
                    carrierType.ToDisplayString(FullNameFormat),
                    nullable,
                    nullableValueType,
                    GetCarrierKind(unwrapped),
                    ImmutableArray<FieldCodecModel>.Empty);
            default:
                return new FieldCodecModel(
                    FieldCodecKind.Scalar,
                    schemaType.TypeId,
                    carrierType.ToDisplayString(FullNameFormat),
                    nullable,
                    nullableValueType,
                    GetCarrierKind(unwrapped),
                    ImmutableArray<FieldCodecModel>.Empty);
        }
    }

    private static CarrierKind GetCarrierKind(ITypeSymbol unwrappedType)
    {
        if (unwrappedType is IArrayTypeSymbol)
        {
            return CarrierKind.Array;
        }

        if (unwrappedType is not INamedTypeSymbol named)
        {
            return CarrierKind.Value;
        }

        string genericName = named.ConstructedFrom.ToDisplayString();
        return genericName switch
        {
            "System.Collections.Generic.List<T>" => CarrierKind.List,
            "System.Collections.Generic.HashSet<T>" => CarrierKind.HashSet,
            "System.Collections.Generic.Dictionary<TKey, TValue>" => CarrierKind.Dictionary,
            "Apache.Fory.NullableKeyDictionary<TKey, TValue>" => CarrierKind.NullableKeyDictionary,
            _ => CarrierKind.Value,
        };
    }

    private static bool TryGetFieldId(
        TypedConstant value,
        ISymbol memberSymbol,
        List<Diagnostic> diagnostics,
        out short fieldId)
    {
        fieldId = default;
        object? raw = value.Value;
        if (raw is null)
        {
            return false;
        }

        long numeric;
        switch (raw)
        {
            case byte v:
                numeric = v;
                break;
            case sbyte v:
                numeric = v;
                break;
            case short v:
                numeric = v;
                break;
            case ushort v:
                numeric = v;
                break;
            case int v:
                numeric = v;
                break;
            case uint v:
                numeric = v;
                break;
            case long v:
                numeric = v;
                break;
            case ulong v:
                if (v > (ulong)short.MaxValue)
                {
                    diagnostics.Add(Diagnostic.Create(
                        InvalidFieldId,
                        memberSymbol.Locations.FirstOrDefault(),
                        memberSymbol.Name));
                    return false;
                }

                numeric = (long)v;
                break;
            default:
                return false;
        }

        if (numeric < 0 || numeric > short.MaxValue)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidFieldId,
                memberSymbol.Locations.FirstOrDefault(),
                memberSymbol.Name));
            return false;
        }

        fieldId = (short)numeric;
        return true;
    }

    private static ImmutableArray<MemberModel> SortMembers(ImmutableArray<MemberModel> members)
    {
        return members
            .OrderBy(m => m.Group)
            .ThenBy(m =>
            {
                if (m.Group is 1 or 2)
                {
                    return m.Classification.IsCompressedNumeric ? 1 : 0;
                }

                return 0;
            })
            .ThenByDescending(m => m.Group is 1 or 2 ? m.Classification.PrimitiveSize : 0)
            .ThenBy(m =>
            {
                if (m.Group is 1 or 2)
                {
                    return (int)m.Classification.TypeId;
                }

                return 0;
            })
            .ThenBy(m => m.FieldId.HasValue ? 0 : 1)
            .ThenBy(m => m.FieldId.GetValueOrDefault())
            .ThenBy(m => m.FieldIdentifier, StringComparer.Ordinal)
            .ToImmutableArray();
    }

    private static bool GenericNullable(ITypeSymbol type)
    {
        (bool optional, ITypeSymbol unwrapped) = UnwrapNullable(type);
        if (optional)
        {
            return true;
        }

        if (unwrapped.IsValueType)
        {
            return false;
        }

        TypeClassification c = ClassifyType(unwrapped);
        return !c.IsPrimitive;
    }

    private static bool FieldNeedsTypeInfo(
        TypeClassification classification,
        DynamicAnyKind dynamicAnyKind,
        ITypeSymbol unwrappedType)
    {
        if (dynamicAnyKind == DynamicAnyKind.AnyValue)
        {
            return true;
        }

        if (classification.IsBuiltIn || IsUnionType(unwrappedType) || unwrappedType.TypeKind == TypeKind.Enum)
        {
            return false;
        }

        return true;
    }

    private static bool ValidateOrdinaryFieldOptions(
        ISymbol member,
        List<Diagnostic> diagnostics)
    {
        AttributeData? attribute = GetEffectiveForyFieldAttribute(member);
        if (attribute is null)
        {
            return true;
        }

        string? invalidOption = attribute.NamedArguments
            .Select(argument => argument.Key)
            .FirstOrDefault(key => key is "TargetDeclaringType" or "TargetMemberName");
        if (invalidOption is null)
        {
            return true;
        }

        diagnostics.Add(Diagnostic.Create(
            InvalidOrdinaryMember,
            member.Locations.FirstOrDefault(location => location.IsInSource),
            member.Name,
            member.ContainingType.ToDisplayString(FullNameFormat),
            $"{invalidOption} is valid only on an external ForyStruct declaration"));
        return false;
    }

    private static MemberModel BindSourceMember(
        Compilation compilation,
        string generatedClassName,
        ISymbol symbol,
        MemberModel member,
        int declarationOrdinal)
    {
        string providerName = $"global::Apache.Fory.Generated.{generatedClassName}";
        string? fieldAccessorName = null;
        string? getterAccessorName = null;
        string? setterAccessorName = null;
        WireMemberKind memberKind;
        ITypeSymbol memberType;
        string targetMemberName;
        string? slotKey;
        if (symbol is IFieldSymbol field)
        {
            memberKind = WireMemberKind.Field;
            memberType = field.Type;
            targetMemberName = field.MetadataName;
            slotKey = null;
            if (!compilation.IsSymbolAccessibleWithin(field, compilation.Assembly))
            {
                fieldAccessorName = $"F{declarationOrdinal}";
            }
        }
        else
        {
            IPropertySymbol property = (IPropertySymbol)symbol;
            memberKind = WireMemberKind.Property;
            memberType = property.Type;
            targetMemberName = property.MetadataName;
            slotKey = BuildPropertySlotKey(property);
            if (!compilation.IsSymbolAccessibleWithin(property.GetMethod!, compilation.Assembly))
            {
                getterAccessorName = $"G{declarationOrdinal}";
            }

            if (!compilation.IsSymbolAccessibleWithin(property.SetMethod!, compilation.Assembly))
            {
                setterAccessorName = $"S{declarationOrdinal}";
            }
        }

        return member.WithDeclaration(
            memberType,
            symbol.ContainingType,
            targetMemberName,
            memberKind,
            slotKey,
            providerName,
            fieldAccessorName,
            getterAccessorName,
            setterAccessorName,
            member.SchemaDescriptorType,
            declarationOrdinal);
    }

    private static ImmutableArray<string> BuildDeclaredShallowFields(
        Compilation compilation,
        INamedTypeSymbol type,
        List<Diagnostic> diagnostics)
    {
        Dictionary<string, string> fields = new(StringComparer.Ordinal);
        string typeKey = BuildRuntimeTypeKey(type);
        foreach (IFieldSymbol field in type.GetMembers().OfType<IFieldSymbol>())
        {
            if (field.IsStatic)
            {
                continue;
            }

            string identity = $"{typeKey}|F|{field.MetadataName}";
            if (!TryBuildShallowFieldExpression(
                    compilation,
                    type,
                    field.Name,
                    field.Type,
                    field,
                    field.Locations.FirstOrDefault(location => location.IsInSource),
                    diagnostics,
                    out string? memoryExpression))
            {
                continue;
            }

            if (!fields.ContainsKey(identity))
            {
                fields.Add(identity, memoryExpression!);
            }
        }

        foreach (SyntaxReference syntaxReference in type.DeclaringSyntaxReferences)
        {
            if (syntaxReference.GetSyntax() is not TypeDeclarationSyntax declaration)
            {
                continue;
            }

            SemanticModel semanticModel = compilation.GetSemanticModel(declaration.SyntaxTree);
            foreach (EventFieldDeclarationSyntax eventDeclaration in declaration.Members
                         .OfType<EventFieldDeclarationSyntax>())
            {
                foreach (VariableDeclaratorSyntax variable in eventDeclaration.Declaration.Variables)
                {
                    if (semanticModel.GetDeclaredSymbol(variable) is not IEventSymbol eventSymbol ||
                        eventSymbol.IsStatic ||
                        eventSymbol.IsAbstract ||
                        eventSymbol.IsExtern)
                    {
                        continue;
                    }

                    string identity = $"{typeKey}|F|{eventSymbol.MetadataName}";
                    if (!fields.ContainsKey(identity))
                    {
                        fields.Add(
                            identity,
                            "4");
                    }
                }
            }
        }

        return fields
            .OrderBy(field => field.Key, StringComparer.Ordinal)
            .Select(field => field.Value)
            .ToImmutableArray();
    }

    private static bool TryBuildShallowFieldExpression(
        Compilation compilation,
        INamedTypeSymbol owner,
        string fieldName,
        ITypeSymbol fieldType,
        IFieldSymbol? field,
        Location? location,
        List<Diagnostic> diagnostics,
        out string? memoryExpression)
    {
        string expression;
        if (field is { IsFixedSizeBuffer: true })
        {
            ITypeSymbol elementType = field.Type is IPointerTypeSymbol pointer
                ? pointer.PointedAtType
                : field.Type;
            expression =
                $"checked((long){field.FixedSize} * {ShallowFieldMemoryExpr(elementType)})";
        }
        else
        {
            bool requiresNamedType =
                fieldType.IsValueType &&
                fieldType.TypeKind is not (TypeKind.Pointer or TypeKind.FunctionPointer) &&
                FixedGraphValueBytes(fieldType, ClassifyType(fieldType)) == 0;
            if (requiresNamedType &&
                (!compilation.IsSymbolAccessibleWithin(fieldType, compilation.Assembly) ||
                 RequiresExternAlias(fieldType, compilation)))
            {
                diagnostics.Add(Diagnostic.Create(
                    UnsupportedShallowField,
                    location,
                    owner.ToDisplayString(FullNameFormat),
                    fieldName,
                    fieldType.ToDisplayString(FullNameFormat)));
                memoryExpression = null;
                return false;
            }

            expression = ShallowFieldMemoryExpr(fieldType);
        }

        memoryExpression = expression;
        return true;
    }

    private static SchemaTypeModel? TryParseSchemaType(ITypeSymbol symbol)
    {
        if (symbol is not INamedTypeSymbol named)
        {
            return null;
        }

        string fullName = named.ConstructedFrom.ToDisplayString(SymbolDisplayFormat.FullyQualifiedFormat);
        fullName = fullName.StartsWith("global::", StringComparison.Ordinal)
            ? fullName.Substring("global::".Length)
            : fullName;

        if (fullName == "Apache.Fory.Schema.Types.List<TElement>")
        {
            if (named.TypeArguments.Length != 1 ||
                TryParseSchemaType(named.TypeArguments[0]) is not SchemaTypeModel element)
            {
                return null;
            }

            return new SchemaTypeModel(22, SchemaTypeKind.List, ImmutableArray.Create(element));
        }

        if (fullName == "Apache.Fory.Schema.Types.Array<TElement>")
        {
            if (named.TypeArguments.Length != 1 ||
                TryParseSchemaType(named.TypeArguments[0]) is not SchemaTypeModel element ||
                element.HasExplicitScalarEncoding ||
                TryResolveArrayTypeIdForElement(element.TypeId) is not uint arrayTypeId)
            {
                return null;
            }

            return new SchemaTypeModel(arrayTypeId, SchemaTypeKind.PackedArray, ImmutableArray.Create(element));
        }

        if (fullName == "Apache.Fory.Schema.Types.Fixed<TScalar>")
        {
            if (named.TypeArguments.Length != 1 ||
                TryParseSchemaType(named.TypeArguments[0]) is not SchemaTypeModel scalar ||
                TryResolveFixedTypeId(scalar.TypeId) is not uint fixedTypeId)
            {
                return null;
            }

            return new SchemaTypeModel(
                fixedTypeId,
                SchemaTypeKind.Scalar,
                ImmutableArray<SchemaTypeModel>.Empty,
                hasExplicitScalarEncoding: true);
        }

        if (fullName == "Apache.Fory.Schema.Types.Tagged<TScalar>")
        {
            if (named.TypeArguments.Length != 1 ||
                TryParseSchemaType(named.TypeArguments[0]) is not SchemaTypeModel scalar ||
                TryResolveTaggedTypeId(scalar.TypeId) is not uint taggedTypeId)
            {
                return null;
            }

            return new SchemaTypeModel(
                taggedTypeId,
                SchemaTypeKind.Scalar,
                ImmutableArray<SchemaTypeModel>.Empty,
                hasExplicitScalarEncoding: true);
        }

        if (fullName == "Apache.Fory.Schema.Types.Set<TElement>")
        {
            if (named.TypeArguments.Length != 1 ||
                TryParseSchemaType(named.TypeArguments[0]) is not SchemaTypeModel element)
            {
                return null;
            }

            return new SchemaTypeModel(23, SchemaTypeKind.Set, ImmutableArray.Create(element));
        }

        if (fullName == "Apache.Fory.Schema.Types.Map<TKey, TValue>")
        {
            if (named.TypeArguments.Length != 2 ||
                TryParseSchemaType(named.TypeArguments[0]) is not SchemaTypeModel key ||
                TryParseSchemaType(named.TypeArguments[1]) is not SchemaTypeModel value)
            {
                return null;
            }

            return new SchemaTypeModel(24, SchemaTypeKind.Map, ImmutableArray.Create(key, value));
        }

        return TryResolveSchemaTypeId(fullName, out uint typeId, out SchemaTypeKind kind)
            ? new SchemaTypeModel(typeId, kind, ImmutableArray<SchemaTypeModel>.Empty)
            : null;
    }

    private static bool TryResolveSchemaTypeId(string fullName, out uint typeId, out SchemaTypeKind kind)
    {
        kind = SchemaTypeKind.Scalar;
        switch (fullName)
        {
            case "Apache.Fory.Schema.Types.Bool":
                typeId = 1;
                return true;
            case "Apache.Fory.Schema.Types.Int8":
                typeId = 2;
                return true;
            case "Apache.Fory.Schema.Types.Int16":
                typeId = 3;
                return true;
            case "Apache.Fory.Schema.Types.Int32":
                typeId = 5;
                return true;
            case "Apache.Fory.Schema.Types.Int64":
                typeId = 7;
                return true;
            case "Apache.Fory.Schema.Types.UInt8":
                typeId = 9;
                return true;
            case "Apache.Fory.Schema.Types.UInt16":
                typeId = 10;
                return true;
            case "Apache.Fory.Schema.Types.UInt32":
                typeId = 12;
                return true;
            case "Apache.Fory.Schema.Types.UInt64":
                typeId = 14;
                return true;
            case "Apache.Fory.Schema.Types.Float16":
                typeId = 17;
                return true;
            case "Apache.Fory.Schema.Types.BFloat16":
                typeId = 18;
                return true;
            case "Apache.Fory.Schema.Types.Float32":
                typeId = 19;
                return true;
            case "Apache.Fory.Schema.Types.Float64":
                typeId = 20;
                return true;
            case "Apache.Fory.Schema.Types.String":
                typeId = 21;
                return true;
            case "Apache.Fory.Schema.Types.Binary":
                typeId = 41;
                return true;
            case "Apache.Fory.Schema.Types.Duration":
                typeId = 37;
                return true;
            case "Apache.Fory.Schema.Types.Timestamp":
                typeId = 38;
                return true;
            case "Apache.Fory.Schema.Types.Date":
                typeId = 39;
                return true;
            case "Apache.Fory.Schema.Types.Decimal":
                typeId = 40;
                return true;
            default:
                typeId = 0;
                return false;
        }
    }

    private static uint? TryResolveFixedTypeId(uint scalarTypeId)
    {
        return scalarTypeId switch
        {
            5 => 4,
            7 => 6,
            12 => 11,
            14 => 13,
            4 or 6 or 11 or 13 => scalarTypeId,
            _ => null,
        };
    }

    private static uint? TryResolveTaggedTypeId(uint scalarTypeId)
    {
        return scalarTypeId switch
        {
            7 or 6 => 8,
            14 or 13 => 15,
            _ => null,
        };
    }

    private static uint? TryResolveArrayTypeIdForElement(uint elementTypeId)
    {
        return elementTypeId switch
        {
            1 => 43,
            2 => 44,
            3 => 45,
            4 or 5 => 46,
            6 or 7 or 8 => 47,
            9 => 48,
            10 => 49,
            11 or 12 => 50,
            13 or 14 or 15 => 51,
            17 => 53,
            18 => 54,
            19 => 55,
            20 => 56,
            _ => null,
        };
    }

    private static bool IsPackedArrayTypeId(uint typeId)
    {
        return typeId is 41 or 43 or 44 or 45 or 46 or 47 or 48 or 49 or 50 or 51 or 53 or 54 or 55 or 56;
    }

    private static TypeResolution ResolveTypeResolution(ITypeSymbol type, SchemaTypeModel? schemaType)
    {
        TypeClassification baseType = ClassifyType(type);
        if (schemaType is null)
        {
            return new TypeResolution(true, baseType);
        }

        bool isPrimitive = schemaType.Kind == SchemaTypeKind.Scalar;
        bool isCollection = schemaType.Kind == SchemaTypeKind.List ||
                            schemaType.Kind == SchemaTypeKind.Set;
        bool isMap = schemaType.Kind == SchemaTypeKind.Map;
        bool isCompressedNumeric = schemaType.TypeId is 5 or 7 or 8 or 12 or 14 or 15;
        int primitiveSize = schemaType.TypeId switch
        {
            1 or 2 or 9 => 1,
            3 or 10 or 17 or 18 => 2,
            4 or 5 or 11 or 12 or 19 => 4,
            6 or 7 or 8 or 13 or 14 or 15 or 20 => 8,
            _ => 0,
        };
        return new TypeResolution(
            true,
            new TypeClassification(
                schemaType.TypeId,
                isPrimitive,
                true,
                isCollection,
                isMap,
                isCompressedNumeric,
                primitiveSize));
    }

    private static TypeClassification ClassifyType(ITypeSymbol type)
    {
        if (ResolveDynamicAnyKind(type) == DynamicAnyKind.AnyValue)
        {
            return new TypeClassification(0, false, true, false, false, false, 0);
        }

        if (type.SpecialType == SpecialType.System_Boolean)
        {
            return new TypeClassification(1, true, true, false, false, false, 1);
        }

        if (type.SpecialType == SpecialType.System_SByte)
        {
            return new TypeClassification(2, true, true, false, false, false, 1);
        }

        if (type.SpecialType == SpecialType.System_Int16)
        {
            return new TypeClassification(3, true, true, false, false, false, 2);
        }

        if (type.SpecialType == SpecialType.System_Int32)
        {
            return new TypeClassification(5, true, true, false, false, true, 4);
        }

        if (type.SpecialType == SpecialType.System_Int64)
        {
            return new TypeClassification(7, true, true, false, false, true, 8);
        }

        if (type.SpecialType == SpecialType.System_Byte)
        {
            return new TypeClassification(9, true, true, false, false, false, 1);
        }

        if (type.SpecialType == SpecialType.System_UInt16)
        {
            return new TypeClassification(10, true, true, false, false, false, 2);
        }

        if (type.SpecialType == SpecialType.System_UInt32)
        {
            return new TypeClassification(12, true, true, false, false, true, 4);
        }

        if (type.SpecialType == SpecialType.System_UInt64)
        {
            return new TypeClassification(14, true, true, false, false, true, 8);
        }

        if (type.SpecialType == SpecialType.System_Single)
        {
            return new TypeClassification(19, true, true, false, false, false, 4);
        }

        if (string.Equals(type.ToDisplayString(), "System.Half", StringComparison.Ordinal))
        {
            return new TypeClassification(17, true, true, false, false, false, 2);
        }

        if (string.Equals(type.ToDisplayString(), "Apache.Fory.BFloat16", StringComparison.Ordinal))
        {
            return new TypeClassification(18, true, true, false, false, false, 2);
        }

        if (type.SpecialType == SpecialType.System_Double)
        {
            return new TypeClassification(20, true, true, false, false, false, 8);
        }

        if (type.SpecialType == SpecialType.System_String)
        {
            return new TypeClassification(21, false, true, false, false, false, 0);
        }

        if (IsDateType(type))
        {
            return new TypeClassification(39, false, true, false, false, false, 0);
        }

        if (IsTimestampType(type))
        {
            return new TypeClassification(38, false, true, false, false, false, 0);
        }

        if (IsDurationType(type))
        {
            return new TypeClassification(37, false, true, false, false, false, 0);
        }

        if (type.SpecialType == SpecialType.System_Decimal ||
            string.Equals(type.ToDisplayString(), "Apache.Fory.ForyDecimal", StringComparison.Ordinal))
        {
            return new TypeClassification(40, false, true, false, false, false, 0);
        }

        if (type is IArrayTypeSymbol arrayType)
        {
            if (TryResolvePackedArrayTypeIdForElement(arrayType.ElementType) is uint packedArrayTypeId)
            {
                return new TypeClassification(packedArrayTypeId, false, true, false, false, false, 0);
            }

            return new TypeClassification(22, false, true, true, false, false, 0);
        }

        if (TryGetListElementType(type, out _))
        {
            return new TypeClassification(22, false, true, true, false, false, 0);
        }

        if (TryGetSetElementType(type, out _))
        {
            return new TypeClassification(23, false, true, true, false, false, 0);
        }

        if (TryGetMapTypeArguments(type, out _, out _))
        {
            return new TypeClassification(24, false, true, false, true, false, 0);
        }

        if (IsUnionType(type))
        {
            return new TypeClassification(33, false, false, false, false, false, 0);
        }

        return new TypeClassification(27, false, false, false, false, false, 0);
    }

    private static DynamicAnyKind ResolveDynamicAnyKind(ITypeSymbol type)
    {
        if (type.SpecialType == SpecialType.System_Object)
        {
            return DynamicAnyKind.AnyValue;
        }

        return DynamicAnyKind.None;
    }

    private static bool IsDateType(ITypeSymbol symbol)
    {
        return string.Equals(symbol.ToDisplayString(), "System.DateOnly", StringComparison.Ordinal);
    }

    private static bool IsTimestampType(ITypeSymbol symbol)
    {
        string name = symbol.ToDisplayString();
        return string.Equals(name, "System.DateTime", StringComparison.Ordinal) ||
               string.Equals(name, "System.DateTimeOffset", StringComparison.Ordinal);
    }

    private static bool IsDurationType(ITypeSymbol symbol)
    {
        return string.Equals(symbol.ToDisplayString(), "System.TimeSpan", StringComparison.Ordinal);
    }

    private static bool IsUnionType(ITypeSymbol symbol)
    {
        if (symbol is INamedTypeSymbol namedType &&
            GetForyAttributeKind(namedType) == ForyAttributeKind.Union)
        {
            return true;
        }

        INamedTypeSymbol? current = symbol as INamedTypeSymbol;
        while (current is not null)
        {
            if (string.Equals(current.ToDisplayString(), "Apache.Fory.Union", StringComparison.Ordinal))
            {
                return true;
            }

            current = current.BaseType;
        }

        return false;
    }

    private static bool IsTypeSealed(ITypeSymbol symbol)
    {
        if (symbol.TypeKind == TypeKind.TypeParameter)
        {
            return false;
        }

        return symbol.IsSealed;
    }

    private static bool TryGetListElementType(ITypeSymbol type, out ITypeSymbol? elementType)
    {
        elementType = null;
        if (type is IArrayTypeSymbol arrayType)
        {
            elementType = arrayType.ElementType;
            return true;
        }

        if (type is not INamedTypeSymbol named)
        {
            return false;
        }

        string genericName = named.ConstructedFrom.ToDisplayString();
        if (genericName is
            "System.Collections.Generic.List<T>" or
            "System.Collections.Generic.LinkedList<T>" or
            "System.Collections.Generic.Queue<T>" or
            "System.Collections.Generic.Stack<T>" or
            "System.Collections.Generic.IList<T>" or
            "System.Collections.Generic.IReadOnlyList<T>")
        {
            elementType = named.TypeArguments[0];
            return true;
        }

        return false;
    }

    private static bool TryGetSetElementType(ITypeSymbol type, out ITypeSymbol? elementType)
    {
        elementType = null;
        if (type is not INamedTypeSymbol named)
        {
            return false;
        }

        string genericName = named.ConstructedFrom.ToDisplayString();
        if (genericName is
            "System.Collections.Generic.HashSet<T>" or
            "System.Collections.Generic.SortedSet<T>" or
            "System.Collections.Immutable.ImmutableHashSet<T>" or
            "System.Collections.Generic.ISet<T>" or
            "System.Collections.Generic.IReadOnlySet<T>" or
            "System.Collections.Immutable.IImmutableSet<T>")
        {
            elementType = named.TypeArguments[0];
            return true;
        }

        return false;
    }

    private static bool TryGetMapTypeArguments(ITypeSymbol type, out ITypeSymbol? keyType, out ITypeSymbol? valueType)
    {
        keyType = null;
        valueType = null;
        if (type is not INamedTypeSymbol named)
        {
            return false;
        }

        string genericName = named.ConstructedFrom.ToDisplayString();
        if (genericName is
            "System.Collections.Generic.Dictionary<TKey, TValue>" or
            "System.Collections.Generic.SortedDictionary<TKey, TValue>" or
            "System.Collections.Generic.SortedList<TKey, TValue>" or
            "System.Collections.Concurrent.ConcurrentDictionary<TKey, TValue>" or
            "System.Collections.Generic.IDictionary<TKey, TValue>" or
            "System.Collections.Generic.IReadOnlyDictionary<TKey, TValue>" or
            "Apache.Fory.NullableKeyDictionary<TKey, TValue>")
        {
            keyType = named.TypeArguments[0];
            valueType = named.TypeArguments[1];
            return true;
        }

        return false;
    }

    private static uint? TryResolvePackedArrayTypeIdForElement(ITypeSymbol elementType)
    {
        (bool isNullable, ITypeSymbol unwrapped) = UnwrapNullable(elementType);
        if (isNullable)
        {
            return null;
        }

        uint elementTypeId = ClassifyType(unwrapped).TypeId;
        return elementTypeId switch
        {
            9 => 41,  // byte -> binary
            1 => 43,  // bool -> bool array
            2 => 44,  // sbyte -> int8 array
            3 => 45,  // short -> int16 array
            5 => 46,  // int -> int32 array
            7 => 47,  // long -> int64 array
            10 => 49, // ushort -> uint16 array
            12 => 50, // uint -> uint32 array
            14 => 51, // ulong -> uint64 array
            17 => 53, // Half -> float16 array
            18 => 54, // BFloat16 -> bfloat16 array
            19 => 55, // float -> float32 array
            20 => 56, // double -> float64 array
            _ => null,
        };
    }

    private static (bool, ITypeSymbol) UnwrapNullable(ITypeSymbol type)
    {
        if (type is INamedTypeSymbol named &&
            named.OriginalDefinition.SpecialType == SpecialType.System_Nullable_T)
        {
            return (true, named.TypeArguments[0]);
        }

        if (type.IsReferenceType && type.NullableAnnotation == NullableAnnotation.Annotated)
        {
            return (true, type.WithNullableAnnotation(NullableAnnotation.NotAnnotated));
        }

        return (false, type);
    }

    private static string BoolLiteral(bool value) => value ? "true" : "false";

    private static string EscapeString(string value) =>
        Microsoft.CodeAnalysis.CSharp.SymbolDisplay.FormatLiteral(value, quote: false);

    private static string EscapeIdentifier(string value)
    {
        return SyntaxFacts.GetKeywordKind(value) != SyntaxKind.None
            || SyntaxFacts.GetContextualKeywordKind(value) != SyntaxKind.None
                ? "@" + value
                : value;
    }

    private static string ToSnakeCase(string name)
    {
        if (string.IsNullOrEmpty(name))
        {
            return name;
        }

        StringBuilder sb = new(name.Length + 4);
        for (int i = 0; i < name.Length; i++)
        {
            char c = name[i];
            if (char.IsUpper(c))
            {
                if (i > 0)
                {
                    bool prevUpper = char.IsUpper(name[i - 1]);
                    bool nextUpperOrEnd = i + 1 >= name.Length || char.IsUpper(name[i + 1]);
                    bool leadingPascalBoundary = i == 1 && prevUpper && !nextUpperOrEnd;
                    if ((!prevUpper || !nextUpperOrEnd) && !leadingPascalBoundary)
                    {
                        sb.Append('_');
                    }
                }

                sb.Append(char.ToLowerInvariant(c));
            }
            else
            {
                sb.Append(c);
            }
        }

        return sb.ToString();
    }

    private static string Sanitize(string name)
    {
        StringBuilder sb = new(name.Length + 8);
        foreach (char c in name)
        {
            sb.Append(char.IsLetterOrDigit(c) ? c : '_');
        }

        return sb.ToString();
    }
}
