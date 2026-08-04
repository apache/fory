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

using System.Collections.Generic;
using System.Collections.Immutable;
using System.Linq;
using Microsoft.CodeAnalysis;

namespace Apache.Fory.Generator;

public sealed partial class ForyModelGenerator
{
    private static TypeModel BuildExternalStructModel(
        Compilation compilation,
        INamedTypeSymbol declaration,
        ITypeSymbol targetSymbol,
        string declarationName,
        string generatedClassName,
        bool evolving,
        bool evolvingExplicit,
        bool baseOnly,
        bool baseOnlyExplicit,
        Location? declarationLocation,
        Location? targetLocation)
    {
        List<Diagnostic> diagnostics = [];
        bool validDeclaration = ValidateExternalStructDeclaration(
            declaration,
            declarationName,
            declarationLocation,
            diagnostics);
        bool validTarget = ValidateExternalStructTarget(
            compilation,
            declaration,
            targetSymbol,
            targetLocation,
            baseOnly,
            baseOnlyExplicit,
            diagnostics,
            out INamedTypeSymbol? target,
            out DeclKind targetKind);
        if (baseOnly && evolvingExplicit)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalDeclaration,
                declarationLocation,
                declarationName,
                "BaseOnly declarations cannot explicitly set Evolving"));
        }

        string targetTypeName = targetSymbol.ToDisplayString(FullNameFormat);
        List<MemberModel> members = [];
        Dictionary<string, string> shallowFields = new(StringComparer.Ordinal);
        if (validDeclaration && validTarget)
        {
            IPropertySymbol[] schemaProperties = declaration.GetMembers()
                .OfType<IPropertySymbol>()
                .Where(property => !property.IsImplicitlyDeclared)
                .ToArray();
            int declarationOrdinal = 0;
            foreach (IPropertySymbol schemaProperty in schemaProperties)
            {
                if (!TryParseExternalMapping(
                        target!,
                        schemaProperty,
                        targetTypeName,
                        diagnostics,
                        out ExternalMemberMapping? mapping))
                {
                    continue;
                }

                if (mapping!.Ignore)
                {
                    if (!TryResolveAccessibleExactTargetMember(
                            compilation,
                            mapping.DeclaringType!,
                            mapping.TargetMemberName,
                            out ISymbol? visibleMember,
                            out string? reason))
                    {
                        diagnostics.Add(Diagnostic.Create(
                            InvalidExternalMember,
                            schemaProperty.Locations.FirstOrDefault(
                                location => location.IsInSource),
                            schemaProperty.Name,
                            targetTypeName,
                            reason));
                        continue;
                    }

                    IFieldSymbol? visibleField = visibleMember as IFieldSymbol;
                    if (visibleField is not null &&
                        (visibleField.IsStatic ||
                         visibleField.IsConst ||
                         !ExternalMemberTypesMatch(
                             schemaProperty.Type,
                             visibleField.Type)))
                    {
                        if (!ExternalMemberTypesMatch(
                                schemaProperty.Type,
                                visibleField.Type))
                        {
                            ReportExternalTypeMismatch(
                                schemaProperty,
                                visibleField.Type,
                                diagnostics);
                        }
                        else
                        {
                            diagnostics.Add(Diagnostic.Create(
                                InvalidExternalMember,
                                schemaProperty.Locations.FirstOrDefault(
                                    location => location.IsInSource),
                                schemaProperty.Name,
                                targetTypeName,
                                "a storage-only mapping must name an instance field"));
                        }

                        continue;
                    }

                    TryAddExternalShallowField(
                        compilation,
                        target!,
                        schemaProperty,
                        mapping,
                        shallowFields,
                        diagnostics,
                        visibleField);
                    continue;
                }

                if (!TryBindExternalMapping(
                        compilation,
                        target!,
                        schemaProperty,
                        mapping,
                        targetTypeName,
                        diagnostics,
                        out ISymbol? targetMember))
                {
                    continue;
                }

                int diagnosticCount = diagnostics.Count;
                MemberModel? member = BuildMemberModel(
                    schemaProperty.Name,
                    schemaProperty.Type,
                    schemaProperty,
                    diagnostics);
                if (member is not null)
                {
                    MemberModel boundMember = BindExternalMember(
                        generatedClassName,
                        target!,
                        schemaProperty,
                        mapping,
                        targetMember,
                        member,
                        declarationOrdinal);
                    members.Add(boundMember);
                    declarationOrdinal++;
                    if (mapping.DeclaringType is not null ||
                        targetMember is IFieldSymbol)
                    {
                        TryAddExternalShallowField(
                            compilation,
                            target!,
                            schemaProperty,
                            mapping,
                            shallowFields,
                            diagnostics,
                            targetMember as IFieldSymbol);
                    }
                }
                else if (diagnostics.Count == diagnosticCount)
                {
                    diagnostics.Add(Diagnostic.Create(
                        InvalidExternalMember,
                        schemaProperty.Locations.FirstOrDefault(location => location.IsInSource),
                        schemaProperty.Name,
                        targetTypeName,
                        "the schema property type or descriptor is not supported"));
                }
            }

            foreach (IFieldSymbol targetField in PublicInstanceFields(target!))
            {
                string identity = ExternalFieldIdentity(targetField.ContainingType, targetField.MetadataName);
                if (shallowFields.ContainsKey(identity))
                {
                    continue;
                }

                if (TryBuildShallowFieldExpression(
                        compilation,
                        target!,
                        targetField.Name,
                        targetField.Type,
                        targetField,
                        targetField.Locations.FirstOrDefault(location => location.IsInSource),
                        diagnostics,
                        out string? memoryExpression))
                {
                    shallowFields.Add(identity, memoryExpression!);
                }
            }
        }

        ImmutableArray<MemberModel> ordered = members
            .OrderBy(member => member.DeclarationOrdinal)
            .ToImmutableArray();
        string providerVisibility =
            target is not null &&
            IsPublicType(declaration)
                ? ProviderVisibility(target, ordered)
                : "internal";
        return new TypeModel(
            declarationName,
            targetTypeName,
            targetSymbol,
            generatedClassName,
            targetKind,
            evolving,
            declarationLocation,
            ordered,
            SortMembers(ordered),
            diagnostics.ToImmutableArray(),
            declaredMembers: ordered,
            shallowStorage: targetKind == DeclKind.Class
                ? new ShallowStorageModel(
                    null,
                    shallowFields
                        .OrderBy(field => field.Key, StringComparer.Ordinal)
                        .Select(field => field.Value)
                        .ToImmutableArray())
                : null,
            isExternal: true,
            providerOnly: baseOnly,
            providerVisibility: providerVisibility);
    }

    private static TypeModel BuildExternalEnumModel(
        Compilation compilation,
        INamedTypeSymbol declaration,
        ITypeSymbol targetSymbol,
        string declarationName,
        string generatedClassName,
        Location? declarationLocation,
        Location? targetLocation)
    {
        List<Diagnostic> diagnostics = [];
        ValidateExternalEnumDeclaration(
            declaration,
            declarationName,
            declarationLocation,
            diagnostics);
        INamedTypeSymbol? target = ValidateExternalEnumTarget(
            compilation,
            declaration,
            targetSymbol,
            targetLocation,
            diagnostics);
        if (target is not null)
        {
            ValidateEnumValues(target, targetLocation, diagnostics);
        }

        return new TypeModel(
            declarationName,
            targetSymbol.ToDisplayString(FullNameFormat),
            targetSymbol,
            generatedClassName,
            DeclKind.Enum,
            true,
            declarationLocation,
            ImmutableArray<MemberModel>.Empty,
            ImmutableArray<MemberModel>.Empty,
            diagnostics.ToImmutableArray());
    }

    private static bool ValidateExternalStructDeclaration(
        INamedTypeSymbol declaration,
        string declarationName,
        Location? declarationLocation,
        List<Diagnostic> diagnostics)
    {
        int initialDiagnosticCount = diagnostics.Count;
        if (declaration.TypeKind != TypeKind.Class ||
            !declaration.IsAbstract ||
            declaration.IsStatic ||
            declaration.IsRecord)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalDeclaration,
                declarationLocation,
                declarationName,
                "ForyStruct with Target requires a non-record abstract class"));
        }

        if (HasGenericContext(declaration))
        {
            diagnostics.Add(Diagnostic.Create(
                GenericTypeNotSupported,
                declarationLocation,
                declarationName));
        }

        if (declaration.BaseType?.SpecialType != SpecialType.System_Object)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalDeclaration,
                declarationLocation,
                declarationName,
                "the declaration cannot have a base class other than object"));
        }

        foreach (ISymbol member in declaration.GetMembers())
        {
            if (member.IsImplicitlyDeclared ||
                member is IMethodSymbol { AssociatedSymbol: not null })
            {
                continue;
            }

            if (member is IPropertySymbol property &&
                property.IsAbstract &&
                !property.IsStatic &&
                !property.IsIndexer &&
                property.GetMethod is { IsAbstract: true } &&
                property.SetMethod is null)
            {
                continue;
            }

            diagnostics.Add(Diagnostic.Create(
                InvalidExternalDeclaration,
                member.Locations.FirstOrDefault(location => location.IsInSource) ?? declarationLocation,
                declarationName,
                $"member '{member.Name}' must be an abstract instance get-only schema property"));
        }

        return diagnostics.Count == initialDiagnosticCount;
    }

    private static bool ValidateExternalEnumDeclaration(
        INamedTypeSymbol declaration,
        string declarationName,
        Location? declarationLocation,
        List<Diagnostic> diagnostics)
    {
        int initialDiagnosticCount = diagnostics.Count;
        if (declaration.TypeKind != TypeKind.Class || !declaration.IsStatic)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalDeclaration,
                declarationLocation,
                declarationName,
                "ForyEnum with Target requires an empty static class"));
        }

        if (HasGenericContext(declaration))
        {
            diagnostics.Add(Diagnostic.Create(
                GenericTypeNotSupported,
                declarationLocation,
                declarationName));
        }

        foreach (ISymbol member in declaration.GetMembers())
        {
            if (member.IsImplicitlyDeclared ||
                member is IMethodSymbol { AssociatedSymbol: not null })
            {
                continue;
            }

            diagnostics.Add(Diagnostic.Create(
                InvalidExternalDeclaration,
                member.Locations.FirstOrDefault(location => location.IsInSource) ?? declarationLocation,
                declarationName,
                $"external enum declarations must be empty; found member '{member.Name}'"));
        }

        return diagnostics.Count == initialDiagnosticCount;
    }

    private static bool ValidateExternalStructTarget(
        Compilation compilation,
        INamedTypeSymbol declaration,
        ITypeSymbol targetSymbol,
        Location? targetLocation,
        bool baseOnly,
        bool baseOnlyExplicit,
        List<Diagnostic> diagnostics,
        out INamedTypeSymbol? target,
        out DeclKind targetKind)
    {
        int initialDiagnosticCount = diagnostics.Count;
        target = targetSymbol as INamedTypeSymbol;
        targetKind = DeclKind.Unknown;
        string targetName = targetSymbol.ToDisplayString(FullNameFormat);
        if (target is null || target.TypeKind == TypeKind.Error)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalTarget,
                targetLocation,
                targetName,
                "Target must resolve to one closed class or struct"));
            return false;
        }

        if (SymbolEqualityComparer.Default.Equals(declaration, target))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalTarget,
                targetLocation,
                targetName,
                "the serializer declaration cannot target itself"));
        }

        if (ContainsOpenType(target))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalTarget,
                targetLocation,
                targetName,
                "open generic targets are not supported"));
        }

        targetKind = target.TypeKind switch
        {
            TypeKind.Class => DeclKind.Class,
            TypeKind.Struct => DeclKind.Struct,
            _ => DeclKind.Unknown,
        };
        TypeClassification classification = ClassifyType(target);
        if (targetKind == DeclKind.Unknown ||
            target.IsStatic ||
            target.IsRefLikeType ||
            target.IsReadOnly ||
            classification.IsBuiltIn ||
            classification.IsCollection ||
            classification.IsMap ||
            IsRuntimeOwnedTarget(target) ||
            IsUnionType(target))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalTarget,
                targetLocation,
                targetName,
                "Target must be a supported user class or struct"));
        }

        if (baseOnlyExplicit && targetKind != DeclKind.Class)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalDeclaration,
                targetLocation,
                declaration.ToDisplayString(FullNameFormat),
                "BaseOnly is valid only for external class targets"));
        }
        else if (baseOnly && target.IsSealed)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalDeclaration,
                targetLocation,
                declaration.ToDisplayString(FullNameFormat),
                "BaseOnly Target must be a non-sealed class"));
        }
        else if (!baseOnly && target.IsAbstract)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalTarget,
                targetLocation,
                targetName,
                "a standalone external Target must be concrete"));
        }

        if (GetForyAttributeKind(target) == ForyAttributeKind.Struct)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalTarget,
                targetLocation,
                targetName,
                "the target already owns a direct ForyStruct serializer"));
        }

        if (!compilation.IsSymbolAccessibleWithin(target, compilation.Assembly))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalTarget,
                targetLocation,
                targetName,
                "the target is not accessible from generated code"));
        }

        if (RequiresExternAlias(target, compilation))
        {
            diagnostics.Add(Diagnostic.Create(
                UnsupportedExternAlias,
                targetLocation,
                targetName));
        }

        IMethodSymbol? constructor = baseOnly
            ? null
            : FindAccessibleParameterlessCtor(target, compilation);
        if (!baseOnly && targetKind == DeclKind.Class && constructor is null)
        {
            diagnostics.Add(Diagnostic.Create(
                MissingCtor,
                targetLocation,
                targetName));
        }

        if (!baseOnly &&
            HasRequiredMembers(target) &&
            (constructor is null || !SetsRequiredMembers(constructor)))
        {
            diagnostics.Add(Diagnostic.Create(
                MissingCtor,
                targetLocation,
                targetName));
        }

        return diagnostics.Count == initialDiagnosticCount;
    }

    private static INamedTypeSymbol? ValidateExternalEnumTarget(
        Compilation compilation,
        INamedTypeSymbol declaration,
        ITypeSymbol targetSymbol,
        Location? targetLocation,
        List<Diagnostic> diagnostics)
    {
        string targetName = targetSymbol.ToDisplayString(FullNameFormat);
        if (targetSymbol is not INamedTypeSymbol target ||
            target.TypeKind == TypeKind.Error ||
            target.TypeKind != TypeKind.Enum)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalTarget,
                targetLocation,
                targetName,
                "ForyEnum Target must resolve to one closed enum"));
            return null;
        }

        if (SymbolEqualityComparer.Default.Equals(declaration, target))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalTarget,
                targetLocation,
                targetName,
                "the serializer declaration cannot target itself"));
        }

        if (ContainsOpenType(target))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalTarget,
                targetLocation,
                targetName,
                "open generic targets are not supported"));
        }

        if (GetForyAttributeKind(target) == ForyAttributeKind.Enum)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalTarget,
                targetLocation,
                targetName,
                "the target already owns a direct ForyEnum serializer"));
        }

        if (!compilation.IsSymbolAccessibleWithin(target, compilation.Assembly))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalTarget,
                targetLocation,
                targetName,
                "the target is not accessible from generated code"));
        }

        if (RequiresExternAlias(target, compilation))
        {
            diagnostics.Add(Diagnostic.Create(
                UnsupportedExternAlias,
                targetLocation,
                targetName));
        }

        return target;
    }

    private static bool TryParseExternalMapping(
        INamedTypeSymbol target,
        IPropertySymbol schemaProperty,
        string targetTypeName,
        List<Diagnostic> diagnostics,
        out ExternalMemberMapping? mapping)
    {
        AttributeData? attribute = GetEffectiveForyFieldAttribute(schemaProperty);
        bool ignore = attribute is not null &&
                      TryGetIgnoredField(schemaProperty, out _);
        INamedTypeSymbol? declaringType = null;
        string targetMemberName = schemaProperty.Name;
        bool hasDeclaringType = false;
        bool hasTargetMemberName = false;
        if (attribute is not null)
        {
            foreach (KeyValuePair<string, TypedConstant> argument in attribute.NamedArguments)
            {
                switch (argument.Key)
                {
                    case "TargetDeclaringType":
                        hasDeclaringType = true;
                        declaringType = argument.Value.Value as INamedTypeSymbol;
                        break;
                    case "TargetMemberName":
                        hasTargetMemberName = true;
                        if (argument.Value.Value is string configuredName)
                        {
                            targetMemberName = configuredName;
                        }
                        else
                        {
                            targetMemberName = string.Empty;
                        }

                        break;
                }
            }
        }

        Location? location = schemaProperty.Locations.FirstOrDefault(sourceLocation => sourceLocation.IsInSource);
        if (hasDeclaringType != hasTargetMemberName)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalMember,
                location,
                schemaProperty.Name,
                targetTypeName,
                "TargetDeclaringType and TargetMemberName must be specified together"));
            mapping = null;
            return false;
        }

        if (hasDeclaringType &&
            (declaringType is null || declaringType.TypeKind == TypeKind.Error))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalMember,
                location,
                schemaProperty.Name,
                targetTypeName,
                "TargetDeclaringType must name the Target or one of its base classes"));
            mapping = null;
            return false;
        }

        if (target.TypeKind == TypeKind.Struct &&
            (hasDeclaringType || ignore))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalMember,
                location,
                schemaProperty.Name,
                targetTypeName,
                "exact field mappings and Ignore are supported only for external class targets"));
            mapping = null;
            return false;
        }

        if (string.IsNullOrEmpty(targetMemberName))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalMember,
                location,
                schemaProperty.Name,
                targetTypeName,
                "TargetMemberName cannot be empty"));
            mapping = null;
            return false;
        }

        if (declaringType is not null && !IsTypeInHierarchy(target, declaringType))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalMember,
                location,
                schemaProperty.Name,
                targetTypeName,
                "TargetDeclaringType must be the Target or one of its base classes"));
            mapping = null;
            return false;
        }

        if (ignore)
        {
            if (IgnoredFieldHasWireOptions(attribute!))
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidIgnoredField,
                    location,
                    schemaProperty.Name,
                    "Id and Type cannot be combined with Ignore"));
                mapping = null;
                return false;
            }

            if (declaringType is null)
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidIgnoredField,
                    location,
                    schemaProperty.Name,
                    "Ignore requires an exact TargetDeclaringType field mapping"));
                mapping = null;
                return false;
            }
        }

        mapping = new ExternalMemberMapping(
            ignore,
            declaringType,
            targetMemberName);
        return true;
    }

    private static bool TryBindExternalMapping(
        Compilation compilation,
        INamedTypeSymbol target,
        IPropertySymbol schemaProperty,
        ExternalMemberMapping mapping,
        string targetTypeName,
        List<Diagnostic> diagnostics,
        out ISymbol? targetMember)
    {
        targetMember = null;
        Location? location = schemaProperty.Locations.FirstOrDefault(sourceLocation => sourceLocation.IsInSource);
        if (schemaProperty.Type.TypeKind is TypeKind.Pointer or TypeKind.FunctionPointer ||
            schemaProperty.Type.IsRefLikeType)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalMember,
                location,
                schemaProperty.Name,
                targetTypeName,
                "the member type cannot be used by Serializer<T>"));
            return false;
        }

        if (RequiresExternAlias(schemaProperty.Type, compilation))
        {
            diagnostics.Add(Diagnostic.Create(
                UnsupportedExternAlias,
                location,
                schemaProperty.Type.ToDisplayString(FullNameFormat)));
            return false;
        }

        if (!IsGeneratedTypeNameable(schemaProperty.Type, compilation))
        {
            diagnostics.Add(Diagnostic.Create(
                UnsupportedGeneratedMemberType,
                location,
                schemaProperty.Name,
                targetTypeName,
                schemaProperty.Type.ToDisplayString(FullNameFormat)));
            return false;
        }

        if (mapping.DeclaringType is null)
        {
            if (!TryFindTargetMember(
                    compilation,
                    target,
                    mapping.TargetMemberName,
                    out targetMember,
                    out string reason))
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidExternalMember,
                    location,
                    schemaProperty.Name,
                    targetTypeName,
                    reason));
                return false;
            }

        }
        else
        {
            if (!TryResolveAccessibleExactTargetMember(
                compilation,
                mapping.DeclaringType!,
                mapping.TargetMemberName,
                out targetMember,
                out string? reason))
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidExternalMember,
                    location,
                    schemaProperty.Name,
                    targetTypeName,
                    reason));
                return false;
            }
        }

        bool needsFieldAccessor =
            targetMember is null ||
            targetMember is IFieldSymbol { CanBeReferencedByName: false };
        INamedTypeSymbol memberDeclaringType =
            targetMember?.ContainingType ?? mapping.DeclaringType!;
        if (RequiresExternAlias(memberDeclaringType, compilation))
        {
            diagnostics.Add(Diagnostic.Create(
                UnsupportedExternAlias,
                location,
                memberDeclaringType.ToDisplayString(FullNameFormat)));
            return false;
        }

        if (!IsGeneratedTypeNameable(memberDeclaringType, compilation))
        {
            diagnostics.Add(Diagnostic.Create(
                UnsupportedGeneratedMemberType,
                location,
                schemaProperty.Name,
                targetTypeName,
                memberDeclaringType.ToDisplayString(FullNameFormat)));
            return false;
        }

        if (needsFieldAccessor &&
            RequiresGenericUnsafeAccessor(memberDeclaringType, schemaProperty.Type))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalMember,
                location,
                schemaProperty.Name,
                targetTypeName,
                "private generic UnsafeAccessor signatures are not supported on .NET 8"));
            return false;
        }

        if (targetMember is IFieldSymbol field)
        {
            if (field.IsStatic ||
                field.IsConst ||
                field.IsReadOnly ||
                field.IsFixedSizeBuffer ||
                !compilation.IsSymbolAccessibleWithin(field, compilation.Assembly))
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidExternalMember,
                    location,
                    schemaProperty.Name,
                    targetTypeName,
                    "the target field must be an accessible mutable instance field"));
                return false;
            }

            if (!ExternalMemberTypesMatch(schemaProperty.Type, field.Type))
            {
                ReportExternalTypeMismatch(schemaProperty, field.Type, diagnostics);
                return false;
            }
        }
        else if (targetMember is IPropertySymbol property)
        {
            if (property.IsStatic ||
                property.IsIndexer ||
                property.GetMethod is null ||
                property.SetMethod is null ||
                property.SetMethod.IsInitOnly ||
                !compilation.IsSymbolAccessibleWithin(property.GetMethod, compilation.Assembly) ||
                !compilation.IsSymbolAccessibleWithin(property.SetMethod, compilation.Assembly))
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidExternalMember,
                    location,
                    schemaProperty.Name,
                    targetTypeName,
                    "the target property must have accessible get and non-init set accessors"));
                return false;
            }

            if (!ExternalMemberTypesMatch(schemaProperty.Type, property.Type))
            {
                ReportExternalTypeMismatch(schemaProperty, property.Type, diagnostics);
                return false;
            }
        }
        else if (mapping.DeclaringType is null)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalMember,
                location,
                schemaProperty.Name,
                targetTypeName,
                "the matching target symbol is not a field or property"));
            return false;
        }
        return true;
    }

    private static bool TryResolveAccessibleExactTargetMember(
        Compilation compilation,
        INamedTypeSymbol declaringType,
        string memberName,
        out ISymbol? targetMember,
        out string? reason)
    {
        ISymbol[] accessibleMembers = declaringType.GetMembers(memberName)
            .Where(member => compilation.IsSymbolAccessibleWithin(member, compilation.Assembly))
            .ToArray();
        if (accessibleMembers.Length == 0)
        {
            // Exact private mappings are application-owned ABI declarations.
            // Do not inspect referenced private metadata to validate them here.
            targetMember = null;
            reason = null;
            return true;
        }

        if (accessibleMembers.Length != 1 ||
            accessibleMembers[0] is not IFieldSymbol)
        {
            targetMember = null;
            reason = "the accessible target member is not one exact field";
            return false;
        }

        targetMember = accessibleMembers[0];
        reason = null;
        return true;
    }

    private static void ReportExternalTypeMismatch(
        IPropertySymbol schemaProperty,
        ITypeSymbol targetMemberType,
        List<Diagnostic> diagnostics)
    {
        diagnostics.Add(Diagnostic.Create(
            ExternalMemberTypeMismatch,
            schemaProperty.Locations.FirstOrDefault(location => location.IsInSource),
            schemaProperty.Name,
            schemaProperty.Type.ToDisplayString(FullNameFormat),
            targetMemberType.ToDisplayString(FullNameFormat)));
    }

    private static MemberModel BindExternalMember(
        string generatedClassName,
        INamedTypeSymbol target,
        IPropertySymbol schemaProperty,
        ExternalMemberMapping mapping,
        ISymbol? targetMember,
        MemberModel member,
        int declarationOrdinal)
    {
        INamedTypeSymbol declaringType = targetMember?.ContainingType ?? mapping.DeclaringType!;
        string providerName = $"global::Apache.Fory.Generated.{generatedClassName}";
        string? fieldAccessorName = null;
        string? getterAccessorName = null;
        string? setterAccessorName = null;
        WireMemberKind memberKind;
        string? slotKey = null;
        if (mapping.DeclaringType is not null ||
            targetMember is IFieldSymbol)
        {
            memberKind = WireMemberKind.Field;
            if (targetMember is null ||
                targetMember is IFieldSymbol { CanBeReferencedByName: false })
            {
                fieldAccessorName = $"F{declarationOrdinal}";
            }
        }
        else
        {
            memberKind = WireMemberKind.Property;
            if (targetMember is IPropertySymbol targetProperty)
            {
                slotKey = BuildPropertySlotKey(targetProperty);
            }
            else
            {
                slotKey =
                    $"{BuildRuntimeTypeKey(declaringType)}|P|{mapping.TargetMemberName}";
                getterAccessorName = $"G{declarationOrdinal}";
                setterAccessorName = $"S{declarationOrdinal}";
            }

        }

        return member.WithDeclaration(
            schemaProperty.Type,
            declaringType,
            mapping.TargetMemberName,
            memberKind,
            slotKey,
            providerName,
            fieldAccessorName,
            getterAccessorName,
            setterAccessorName,
            member.SchemaDescriptorType,
            declarationOrdinal,
            useDeclaringCast:
                !RuntimeTypeComparer.Instance.Equals(declaringType, target));
    }

    private static bool TryAddExternalShallowField(
        Compilation compilation,
        INamedTypeSymbol target,
        IPropertySymbol schemaProperty,
        ExternalMemberMapping mapping,
        Dictionary<string, string> shallowFields,
        List<Diagnostic> diagnostics,
        IFieldSymbol? targetField = null)
    {
        INamedTypeSymbol declaringType = targetField?.ContainingType ?? mapping.DeclaringType!;
        string fieldName = targetField?.MetadataName ?? mapping.TargetMemberName;
        string identity = ExternalFieldIdentity(declaringType, fieldName);
        if (shallowFields.ContainsKey(identity))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalMember,
                schemaProperty.Locations.FirstOrDefault(location => location.IsInSource),
                schemaProperty.Name,
                target.ToDisplayString(FullNameFormat),
                $"physical field '{declaringType.ToDisplayString(FullNameFormat)}.{fieldName}' is mapped more than once"));
            return false;
        }

        ITypeSymbol fieldType = targetField?.Type ?? schemaProperty.Type;
        if (targetField is null &&
            fieldType.TypeKind == TypeKind.Pointer)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidExternalMember,
                schemaProperty.Locations.FirstOrDefault(location => location.IsInSource),
                schemaProperty.Name,
                target.ToDisplayString(FullNameFormat),
                "an inaccessible pointer field cannot be distinguished from fixed-buffer storage"));
            return false;
        }

        if (!TryBuildShallowFieldExpression(
                compilation,
                target,
                fieldName,
                fieldType,
                targetField,
                schemaProperty.Locations.FirstOrDefault(location => location.IsInSource),
                diagnostics,
                out string? memoryExpression))
        {
            return false;
        }

        shallowFields.Add(identity, memoryExpression!);
        return true;
    }

    private static string ExternalFieldIdentity(INamedTypeSymbol declaringType, string fieldName)
    {
        return $"{BuildRuntimeTypeKey(declaringType)}|F|{fieldName}";
    }

    private static IEnumerable<IFieldSymbol> PublicInstanceFields(INamedTypeSymbol target)
    {
        for (INamedTypeSymbol? current = target; current is not null; current = current.BaseType)
        {
            foreach (IFieldSymbol field in current.GetMembers()
                         .OfType<IFieldSymbol>()
                         .Where(field =>
                             !field.IsImplicitlyDeclared &&
                             !field.IsStatic &&
                             !field.IsConst &&
                             field.DeclaredAccessibility == Accessibility.Public)
                         .OrderBy(field => field.MetadataName, StringComparer.Ordinal))
            {
                yield return field;
            }
        }
    }

    private static bool TryFindTargetMember(
        Compilation compilation,
        INamedTypeSymbol target,
        string name,
        out ISymbol? targetMember,
        out string reason)
    {
        for (INamedTypeSymbol? current = target; current is not null; current = current.BaseType)
        {
            ISymbol[] namedMembers = current.GetMembers(name)
                .Where(member =>
                    member is IFieldSymbol or IPropertySymbol &&
                    compilation.IsSymbolAccessibleWithin(member, compilation.Assembly))
                .ToArray();
            if (namedMembers.Length == 0)
            {
                continue;
            }

            if (namedMembers.Length != 1)
            {
                targetMember = null;
                reason = "the target member name is ambiguous";
                return false;
            }

            targetMember = namedMembers[0];
            reason = string.Empty;
            return true;
        }

        targetMember = null;
        reason = "no target member has the same case-sensitive name";
        return false;
    }

    private static bool ExternalMemberTypesMatch(ITypeSymbol declarationType, ITypeSymbol targetType)
    {
        if ((declarationType.TypeKind == TypeKind.Dynamic) != (targetType.TypeKind == TypeKind.Dynamic))
        {
            return false;
        }

        if (targetType.NullableAnnotation != NullableAnnotation.None &&
            declarationType.NullableAnnotation != targetType.NullableAnnotation)
        {
            return false;
        }

        if (declarationType is IArrayTypeSymbol declarationArray &&
            targetType is IArrayTypeSymbol targetArray)
        {
            return declarationArray.Rank == targetArray.Rank &&
                   declarationArray.IsSZArray == targetArray.IsSZArray &&
                   ExternalMemberTypesMatch(declarationArray.ElementType, targetArray.ElementType);
        }

        if (declarationType is IPointerTypeSymbol declarationPointer &&
            targetType is IPointerTypeSymbol targetPointer)
        {
            return ExternalMemberTypesMatch(declarationPointer.PointedAtType, targetPointer.PointedAtType);
        }

        if (declarationType is INamedTypeSymbol declarationNamed &&
            targetType is INamedTypeSymbol targetNamed)
        {
            if (!SymbolEqualityComparer.Default.Equals(
                    declarationNamed.OriginalDefinition,
                    targetNamed.OriginalDefinition) ||
                declarationNamed.TypeArguments.Length != targetNamed.TypeArguments.Length)
            {
                return false;
            }

            if ((declarationNamed.ContainingType is null) != (targetNamed.ContainingType is null) ||
                declarationNamed.ContainingType is not null &&
                !ExternalMemberTypesMatch(declarationNamed.ContainingType, targetNamed.ContainingType!))
            {
                return false;
            }

            for (int i = 0; i < declarationNamed.TypeArguments.Length; i++)
            {
                if (!ExternalMemberTypesMatch(
                        declarationNamed.TypeArguments[i],
                        targetNamed.TypeArguments[i]))
                {
                    return false;
                }
            }

            return true;
        }

        return SymbolEqualityComparer.Default.Equals(declarationType, targetType);
    }
}
