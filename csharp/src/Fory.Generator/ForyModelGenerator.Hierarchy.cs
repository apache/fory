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
using System.Globalization;
using System.Linq;
using System.Text;
using Microsoft.CodeAnalysis;

namespace Apache.Fory.Generator;

public sealed partial class ForyModelGenerator
{
    private static ImmutableArray<TypeModel> ComposeHierarchyModels(
        SourceProductionContext context,
        Compilation compilation,
        ImmutableArray<TypeModel> rawModels)
    {
        IEqualityComparer<ITypeSymbol> comparer = RuntimeTypeComparer.Instance;
        Dictionary<ITypeSymbol, TypeModel> classProviders = new(comparer);
        Dictionary<ITypeSymbol, ResolvedProvider> resolvedProviders = new(comparer);
        foreach (TypeModel model in rawModels)
        {
            if (model.Kind == DeclKind.Class)
            {
                classProviders.Add(model.TargetType, model);
            }
        }

        List<TypeModel> result = [];
        foreach (TypeModel rawModel in rawModels)
        {
            if (rawModel.Kind != DeclKind.Class)
            {
                ImmutableArray<MemberModel> members = AssignCodeKeys(rawModel.Members);
                result.Add(rawModel.WithHierarchy(members, SortMembers(members), null));
                continue;
            }

            if (rawModel.IsExternal)
            {
                List<Diagnostic> diagnostics = [];
                ValidateStructuralIdentities(rawModel, rawModel.Members, diagnostics);
                if (diagnostics.Count > 0)
                {
                    foreach (Diagnostic diagnostic in diagnostics)
                    {
                        context.ReportDiagnostic(diagnostic);
                    }

                    continue;
                }

                ImmutableArray<MemberModel> members = AssignCodeKeys(rawModel.Members);
                result.Add(rawModel.WithHierarchy(members, SortMembers(members), null));
                continue;
            }

            if (rawModel.TargetType is not INamedTypeSymbol target)
            {
                result.Add(rawModel);
                continue;
            }

            List<Diagnostic> hierarchyDiagnostics = [];
            List<MemberModel> flattened = [];
            string? parentProviderTypeName = null;
            bool parentIsPublic = true;
            if (target.BaseType is INamedTypeSymbol baseType &&
                baseType.SpecialType != SpecialType.System_Object)
            {
                if (!TryResolveProvider(
                        compilation,
                        rawModel,
                        baseType,
                        classProviders,
                        resolvedProviders,
                        hierarchyDiagnostics,
                        out ResolvedProvider? provider))
                {
                    foreach (Diagnostic diagnostic in hierarchyDiagnostics)
                    {
                        context.ReportDiagnostic(diagnostic);
                    }

                    continue;
                }

                parentProviderTypeName = provider!.ProviderTypeName;
                parentIsPublic = provider.IsPublic;
                flattened.AddRange(provider.WireMembers);
            }

            flattened.AddRange(rawModel.DeclaredMembers);
            ImmutableArray<MemberModel> collapsed = CollapsePropertyOverrides(flattened);
            ValidateStructuralIdentities(rawModel, collapsed, hierarchyDiagnostics);
            if (hierarchyDiagnostics.Count > 0)
            {
                foreach (Diagnostic diagnostic in hierarchyDiagnostics)
                {
                    context.ReportDiagnostic(diagnostic);
                }

                continue;
            }

            ImmutableArray<MemberModel> membersWithKeys = AssignCodeKeys(collapsed);
            result.Add(rawModel.WithHierarchy(
                membersWithKeys,
                SortMembers(membersWithKeys),
                parentProviderTypeName,
                parentIsPublic));
        }

        return result.ToImmutableArray();
    }

    private static bool TryResolveProvider(
        Compilation compilation,
        TypeModel consumer,
        INamedTypeSymbol target,
        Dictionary<ITypeSymbol, TypeModel> classProviders,
        Dictionary<ITypeSymbol, ResolvedProvider> resolvedProviders,
        List<Diagnostic> diagnostics,
        out ResolvedProvider? provider)
    {
        if (resolvedProviders.TryGetValue(target, out ResolvedProvider? resolved))
        {
            provider = resolved;
            return true;
        }

        if (classProviders.TryGetValue(target, out TypeModel? localProvider))
        {
            if (RejectReferencedProviderConflict(
                    compilation,
                    consumer,
                    target,
                    diagnostics))
            {
                provider = null;
                return false;
            }

            string providerTypeName =
                $"global::Apache.Fory.Generated.{localProvider.GeneratedClassName}";
            List<MemberModel> members = [];
            bool isPublic = localProvider.ProviderVisibility == "public";
            if (!localProvider.IsExternal &&
                target.BaseType is INamedTypeSymbol baseType &&
                baseType.SpecialType != SpecialType.System_Object)
            {
                if (!TryResolveProvider(
                        compilation,
                        consumer,
                        baseType,
                        classProviders,
                        resolvedProviders,
                        diagnostics,
                        out ResolvedProvider? parent))
                {
                    provider = null;
                    return false;
                }

                members.AddRange(parent!.WireMembers);
                isPublic &= parent.IsPublic;
            }

            members.AddRange(
                localProvider.DeclaredMembers.Select(ForInheritedUse));
            provider = new ResolvedProvider(
                providerTypeName,
                CollapsePropertyOverrides(members),
                isPublic);
            resolvedProviders.Add(target, provider);
            return true;
        }

        if (!TryResolveReferencedProvider(
                compilation,
                consumer,
                target,
                classProviders,
                resolvedProviders,
                diagnostics,
                out provider))
        {
            return false;
        }

        resolvedProviders.Add(target, provider!);
        return true;
    }

    private static bool RejectReferencedProviderConflict(
        Compilation compilation,
        TypeModel consumer,
        INamedTypeSymbol target,
        List<Diagnostic> diagnostics)
    {
        ImmutableArray<INamedTypeSymbol> referencedProviders =
            FindReferencedProviders(compilation, target)
                .Where(provider => CanReferenceProvider(compilation, provider))
                .ToImmutableArray();
        if (referencedProviders.IsEmpty)
        {
            return false;
        }

        ReportProviderConflict(
            consumer,
            target,
            referencedProviders,
            diagnostics);
        return true;
    }

    private static ImmutableArray<INamedTypeSymbol> FindReferencedProviders(
        Compilation compilation,
        INamedTypeSymbol target)
    {
        string metadataName =
            $"Apache.Fory.Generated.{GeneratedHierarchyName(target)}";
        ImmutableArray<INamedTypeSymbol>.Builder matches =
            ImmutableArray.CreateBuilder<INamedTypeSymbol>();
        HashSet<INamedTypeSymbol> seen =
            new(SymbolEqualityComparer.Default);
        foreach (MetadataReference reference in compilation.References)
        {
            if (compilation.GetAssemblyOrModuleSymbol(reference) is IAssemblySymbol assembly &&
                assembly.GetTypeByMetadataName(metadataName) is INamedTypeSymbol match &&
                seen.Add(match))
            {
                matches.Add(match);
            }
        }

        return matches.ToImmutable();
    }

    private static void ReportProviderConflict(
        TypeModel consumer,
        INamedTypeSymbol target,
        ImmutableArray<INamedTypeSymbol> referencedProviders,
        List<Diagnostic> diagnostics)
    {
        string assemblies = string.Join(
            ", ",
            referencedProviders
                .Select(provider => provider.ContainingAssembly.Identity.Name)
                .Distinct(StringComparer.Ordinal)
                .OrderBy(name => name, StringComparer.Ordinal));
        diagnostics.Add(Diagnostic.Create(
            MissingHierarchyProvider,
            consumer.DeclarationLocation,
            consumer.TargetTypeName,
            target.ToDisplayString(FullNameFormat),
            $"a local provider conflicts with referenced provider assemblies {assemblies}"));
    }

    private static bool TryResolveReferencedProvider(
        Compilation compilation,
        TypeModel consumer,
        INamedTypeSymbol target,
        Dictionary<ITypeSymbol, TypeModel> classProviders,
        Dictionary<ITypeSymbol, ResolvedProvider> resolvedProviders,
        List<Diagnostic> diagnostics,
        out ResolvedProvider? provider)
    {
        string metadataName =
            $"Apache.Fory.Generated.{GeneratedHierarchyName(target)}";
        ImmutableArray<INamedTypeSymbol> matches =
            FindReferencedProviders(compilation, target);
        ImmutableArray<INamedTypeSymbol> candidates = matches
            .Where(provider => CanReferenceProvider(compilation, provider))
            .ToImmutableArray();

        if (candidates.Length != 1)
        {
            string reason = candidates.Length == 0
                ? matches.IsEmpty
                    ? $"no generated provider named '{metadataName}' is referenced"
                    : $"no accessible generated provider named '{metadataName}' is referenced"
                : $"multiple generated providers are referenced from {string.Join(", ", candidates.Select(match => match.ContainingAssembly.Identity.Name).Distinct(StringComparer.Ordinal).OrderBy(name => name, StringComparer.Ordinal))}";
            diagnostics.Add(Diagnostic.Create(
                MissingHierarchyProvider,
                consumer.DeclarationLocation,
                consumer.TargetTypeName,
                target.ToDisplayString(FullNameFormat),
                reason));
            provider = null;
            return false;
        }

        return TryReadReferencedProvider(
            compilation,
            consumer,
            candidates[0],
            target,
            classProviders,
            resolvedProviders,
            diagnostics,
            out provider);
    }

    private static bool TryReadReferencedProvider(
        Compilation compilation,
        TypeModel consumer,
        INamedTypeSymbol providerType,
        INamedTypeSymbol expectedTarget,
        Dictionary<ITypeSymbol, TypeModel> classProviders,
        Dictionary<ITypeSymbol, ResolvedProvider> resolvedProviders,
        List<Diagnostic> diagnostics,
        out ResolvedProvider? provider)
    {
        string providerTypeName = providerType.ToDisplayString(FullNameFormat);
        AttributeData? marker = providerType.GetAttributes().FirstOrDefault(attribute =>
            string.Equals(
                attribute.AttributeClass?.ToDisplayString(),
                "Apache.Fory.ForyGeneratedHierarchyProviderAttribute",
                StringComparison.Ordinal));
        if (marker is null ||
            marker.ConstructorArguments.Length != 2 ||
            marker.ConstructorArguments[0].Value is not INamedTypeSymbol markerTarget ||
            marker.ConstructorArguments[1].Value is not int declaredWireMemberCount ||
            declaredWireMemberCount < 0 ||
            !RuntimeTypeComparer.Instance.Equals(markerTarget, expectedTarget))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidInheritedDescriptor,
                consumer.DeclarationLocation,
                consumer.TargetTypeName,
                providerTypeName,
                "the provider marker is malformed or does not bind the expected target"));
            provider = null;
            return false;
        }

        // Referenced providers are ordinary assembly metadata. Validate the complete generated ABI
        // before copying any member name into child source.
        ImmutableArray<ISymbol> shallowMembers =
            providerType.GetMembers("HierarchyShallowBytes");
        if (shallowMembers.Length != 1 ||
            shallowMembers[0] is not IFieldSymbol shallowField ||
            !shallowField.IsStatic ||
            !shallowField.IsReadOnly ||
            shallowField.Type.SpecialType != SpecialType.System_Int64 ||
            !compilation.IsSymbolAccessibleWithin(
                shallowField,
                compilation.Assembly))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidInheritedDescriptor,
                consumer.DeclarationLocation,
                consumer.TargetTypeName,
                providerTypeName,
                "HierarchyShallowBytes is not an accessible static readonly long field"));
            provider = null;
            return false;
        }

        // Ordinary providers are owned by the target's direct annotation;
        // external providers target an unannotated third-party class.
        bool ordinaryProvider =
            GetForyAttributeKind(expectedTarget) == ForyAttributeKind.Struct;
        List<MemberModel> members = [];
        bool isPublic = providerType.DeclaredAccessibility == Accessibility.Public;
        if (ordinaryProvider &&
            expectedTarget.BaseType is INamedTypeSymbol expectedBase &&
            expectedBase.SpecialType != SpecialType.System_Object)
        {
            if (!TryResolveProvider(
                    compilation,
                    consumer,
                    expectedBase,
                    classProviders,
                    resolvedProviders,
                    diagnostics,
                    out ResolvedProvider? parent))
            {
                provider = null;
                return false;
            }

            members.AddRange(parent!.WireMembers);
            isPublic &= parent.IsPublic;
        }

        if (!TryReadWireDescriptors(
                compilation,
                consumer,
                providerType,
                expectedTarget,
                ordinaryProvider,
                declaredWireMemberCount,
                diagnostics,
                out ImmutableArray<MemberModel> declaredMembers))
        {
            provider = null;
            return false;
        }

        members.AddRange(declaredMembers);
        provider = new ResolvedProvider(
            providerTypeName,
            CollapsePropertyOverrides(members),
            isPublic);
        return true;
    }

    private static bool TryReadWireDescriptors(
        Compilation compilation,
        TypeModel consumer,
        INamedTypeSymbol providerType,
        INamedTypeSymbol providerTarget,
        bool ordinaryProvider,
        int declaredWireMemberCount,
        List<Diagnostic> diagnostics,
        out ImmutableArray<MemberModel> members)
    {
        List<(int Ordinal, MemberModel Member)> parsedMembers = [];
        HashSet<int> ordinals = [];
        foreach (IMethodSymbol readAccessor in providerType.GetMembers()
                     .OfType<IMethodSymbol>())
        {
            AttributeData? descriptor = readAccessor.GetAttributes().FirstOrDefault(attribute =>
                string.Equals(
                    attribute.AttributeClass?.ToDisplayString(),
                    "Apache.Fory.ForyGeneratedWireMemberAttribute",
                    StringComparison.Ordinal));
            if (descriptor is null)
            {
                continue;
            }

            if (!TryReadWireDescriptor(
                    compilation,
                    consumer,
                    providerType,
                    providerTarget,
                    ordinaryProvider,
                    readAccessor,
                    descriptor,
                    diagnostics,
                    out int ordinal,
                    out MemberModel? member))
            {
                members = ImmutableArray<MemberModel>.Empty;
                return false;
            }

            if (!ordinals.Add(ordinal))
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidInheritedDescriptor,
                    consumer.DeclarationLocation,
                    consumer.TargetTypeName,
                    providerType.ToDisplayString(FullNameFormat),
                    $"wire descriptor ordinal {ordinal} is duplicated"));
                members = ImmutableArray<MemberModel>.Empty;
                return false;
            }

            parsedMembers.Add((ordinal, member!));
        }

        parsedMembers.Sort((left, right) => left.Ordinal.CompareTo(right.Ordinal));
        if (parsedMembers.Count != declaredWireMemberCount)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidInheritedDescriptor,
                consumer.DeclarationLocation,
                consumer.TargetTypeName,
                providerType.ToDisplayString(FullNameFormat),
                $"provider declares {declaredWireMemberCount} wire members but publishes {parsedMembers.Count} descriptors"));
            members = ImmutableArray<MemberModel>.Empty;
            return false;
        }

        for (int ordinal = 0; ordinal < parsedMembers.Count; ordinal++)
        {
            if (parsedMembers[ordinal].Ordinal != ordinal)
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidInheritedDescriptor,
                    consumer.DeclarationLocation,
                    consumer.TargetTypeName,
                    providerType.ToDisplayString(FullNameFormat),
                    $"wire descriptor ordinal {parsedMembers[ordinal].Ordinal} is not contiguous"));
                members = ImmutableArray<MemberModel>.Empty;
                return false;
            }
        }

        members = parsedMembers
            .Select(entry => entry.Member)
            .ToImmutableArray();
        return true;
    }

    private static bool TryReadWireDescriptor(
        Compilation compilation,
        TypeModel consumer,
        INamedTypeSymbol providerType,
        INamedTypeSymbol providerTarget,
        bool ordinaryProvider,
        IMethodSymbol readAccessor,
        AttributeData descriptor,
        List<Diagnostic> diagnostics,
        out int ordinal,
        out MemberModel? member)
    {
        ordinal = -1;
        member = null;
        if (descriptor.ConstructorArguments.Length != 4 ||
            descriptor.ConstructorArguments[0].Value is not int parsedOrdinal ||
            parsedOrdinal < 0 ||
            descriptor.ConstructorArguments[1].Value is not INamedTypeSymbol declaringType ||
            descriptor.ConstructorArguments[2].Value is not string logicalName ||
            descriptor.ConstructorArguments[3].Value is not string targetMemberName)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidInheritedDescriptor,
                consumer.DeclarationLocation,
                consumer.TargetTypeName,
                providerType.ToDisplayString(FullNameFormat),
                $"method '{readAccessor.Name}' has malformed wire metadata"));
            return false;
        }

        if (string.IsNullOrEmpty(logicalName) ||
            string.IsNullOrEmpty(targetMemberName) ||
            ordinaryProvider &&
            !RuntimeTypeComparer.Instance.Equals(declaringType, providerTarget) ||
            !ordinaryProvider &&
            !IsTypeInHierarchy(providerTarget, declaringType))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidInheritedDescriptor,
                consumer.DeclarationLocation,
                consumer.TargetTypeName,
                providerType.ToDisplayString(FullNameFormat),
                $"method '{readAccessor.Name}' has invalid declaration ownership"));
            return false;
        }

        int fieldIdValue = -1;
        ITypeSymbol? schemaDescriptorType = null;
        string? slot = null;
        foreach (KeyValuePair<string, TypedConstant> argument in descriptor.NamedArguments)
        {
            switch (argument.Key)
            {
                case "FieldId":
                    if (argument.Value.Value is int configuredFieldId)
                    {
                        fieldIdValue = configuredFieldId;
                    }

                    break;
                case "SchemaType":
                    schemaDescriptorType = argument.Value.Value as ITypeSymbol;
                    break;
                case "Slot":
                    slot = argument.Value.Value as string;
                    break;
            }
        }

        WireMemberKind memberKind = readAccessor.ReturnsByRef
            ? WireMemberKind.Field
            : WireMemberKind.Property;
        string expectedAccessorName =
            $"{(memberKind == WireMemberKind.Field ? "F" : "G")}{parsedOrdinal}";
        ImmutableArray<IMethodSymbol> readCandidates = providerType
            .GetMembers(expectedAccessorName)
            .OfType<IMethodSymbol>()
            .ToImmutableArray();
        if (fieldIdValue is < -1 or > MaxFieldId ||
            readCandidates.Length != 1 ||
            !SymbolEqualityComparer.Default.Equals(
                readCandidates[0],
                readAccessor) ||
            readAccessor.MethodKind != MethodKind.Ordinary ||
            readAccessor.Arity != 0 ||
            !readAccessor.IsStatic ||
            readAccessor.IsExtensionMethod ||
            readAccessor.IsVararg ||
            readAccessor.ReturnsByRefReadonly ||
            readAccessor.Parameters.Length != 1 ||
            readAccessor.Parameters[0].RefKind != RefKind.None ||
            !RuntimeTypeComparer.Instance.Equals(
                readAccessor.Parameters[0].Type,
                declaringType) ||
            !compilation.IsSymbolAccessibleWithin(
                readAccessor,
                compilation.Assembly) ||
            !string.Equals(
                readAccessor.Name,
                expectedAccessorName,
                StringComparison.Ordinal))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidInheritedDescriptor,
                consumer.DeclarationLocation,
                consumer.TargetTypeName,
                providerType.ToDisplayString(FullNameFormat),
                $"method '{readAccessor.Name}' has invalid wire-accessor metadata"));
            return false;
        }

        if (RequiresExternAlias(readAccessor.ReturnType, compilation))
        {
            diagnostics.Add(Diagnostic.Create(
                UnsupportedExternAlias,
                consumer.DeclarationLocation,
                readAccessor.ReturnType.ToDisplayString(FullNameFormat)));
            return false;
        }

        if (!IsGeneratedTypeNameable(readAccessor.ReturnType, compilation))
        {
            diagnostics.Add(Diagnostic.Create(
                UnsupportedGeneratedMemberType,
                consumer.DeclarationLocation,
                logicalName,
                consumer.TargetTypeName,
                readAccessor.ReturnType.ToDisplayString(FullNameFormat)));
            return false;
        }

        SchemaTypeModel? schemaType = schemaDescriptorType is null
            ? null
            : TryParseSchemaType(schemaDescriptorType);
        if (schemaDescriptorType is not null && schemaType is null)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidInheritedDescriptor,
                consumer.DeclarationLocation,
                consumer.TargetTypeName,
                providerType.ToDisplayString(FullNameFormat),
                $"method '{readAccessor.Name}' has an unsupported schema descriptor"));
            return false;
        }

        int diagnosticCount = diagnostics.Count;
        MemberModel? parsed = BuildMemberModel(
            logicalName,
            readAccessor.ReturnType,
            readAccessor,
            diagnostics,
            schemaType,
            parseFieldAttribute: false,
            fieldIdValue,
            schemaDescriptorType);
        if (parsed is null || diagnostics.Count != diagnosticCount)
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidInheritedDescriptor,
                consumer.DeclarationLocation,
                consumer.TargetTypeName,
                providerType.ToDisplayString(FullNameFormat),
                $"method '{readAccessor.Name}' describes an unsupported wire type"));
            return false;
        }

        if (memberKind == WireMemberKind.Field &&
            slot is not null ||
            memberKind == WireMemberKind.Property &&
            string.IsNullOrEmpty(slot))
        {
            diagnostics.Add(Diagnostic.Create(
                InvalidInheritedDescriptor,
                consumer.DeclarationLocation,
                consumer.TargetTypeName,
                providerType.ToDisplayString(FullNameFormat),
                $"method '{readAccessor.Name}' has inconsistent member-access metadata"));
            return false;
        }

        string? setterName = null;
        if (memberKind == WireMemberKind.Property)
        {
            ImmutableArray<IMethodSymbol> setterCandidates = providerType
                .GetMembers($"S{parsedOrdinal}")
                .OfType<IMethodSymbol>()
                .ToImmutableArray();
            IMethodSymbol? setter = setterCandidates.Length == 1
                ? setterCandidates[0]
                : null;
            if (setter is null ||
                setter.MethodKind != MethodKind.Ordinary ||
                setter.Arity != 0 ||
                !setter.IsStatic ||
                setter.IsExtensionMethod ||
                setter.IsVararg ||
                !setter.ReturnsVoid ||
                setter.Parameters.Length != 2 ||
                setter.Parameters[0].RefKind != RefKind.None ||
                setter.Parameters[1].RefKind != RefKind.None ||
                !RuntimeTypeComparer.Instance.Equals(
                    setter.Parameters[0].Type,
                    declaringType) ||
                !SymbolEqualityComparer.IncludeNullability.Equals(
                    setter.Parameters[1].Type,
                    readAccessor.ReturnType) ||
                !compilation.IsSymbolAccessibleWithin(
                    setter,
                    compilation.Assembly))
            {
                diagnostics.Add(Diagnostic.Create(
                    InvalidInheritedDescriptor,
                    consumer.DeclarationLocation,
                    consumer.TargetTypeName,
                    providerType.ToDisplayString(FullNameFormat),
                    $"property accessor '{readAccessor.Name}' has no matching writable accessor"));
                return false;
            }

            setterName = setter.Name;
        }

        string providerTypeName = providerType.ToDisplayString(FullNameFormat);
        parsed = parsed.WithDeclaration(
            readAccessor.ReturnType,
            declaringType,
            targetMemberName,
            memberKind,
            slot,
            providerTypeName,
            memberKind == WireMemberKind.Field ? readAccessor.Name : null,
            memberKind == WireMemberKind.Property ? readAccessor.Name : null,
            setterName,
            schemaDescriptorType,
            parsedOrdinal,
            useDeclaringCast: true);

        ordinal = parsedOrdinal;
        member = parsed;
        return true;
    }

    private static MemberModel ForInheritedUse(MemberModel member)
    {
        return member.WithAccess(
            member.DeclaringType,
            member.AccessorProviderTypeName,
            member.FieldAccessorName,
            member.GetterAccessorName,
            member.SetterAccessorName,
            useDeclaringCast: true);
    }

    private static ImmutableArray<MemberModel> CollapsePropertyOverrides(
        IEnumerable<MemberModel> members)
    {
        List<MemberModel> collapsed = [];
        Dictionary<string, int> propertySlots = new(StringComparer.Ordinal);
        foreach (MemberModel member in members)
        {
            if (member.MemberKind == WireMemberKind.Property &&
                member.SlotKey is not null)
            {
                if (propertySlots.TryGetValue(member.SlotKey, out int index))
                {
                    collapsed[index] = member;
                }
                else
                {
                    propertySlots.Add(member.SlotKey, collapsed.Count);
                    collapsed.Add(member);
                }
            }
            else
            {
                collapsed.Add(member);
            }
        }

        return collapsed.ToImmutableArray();
    }

    private static ImmutableArray<MemberModel> AssignCodeKeys(
        IEnumerable<MemberModel> members)
    {
        return members
            .Select((member, index) => member.WithCodeKey($"M{index}"))
            .ToImmutableArray();
    }

    private static void ValidateStructuralIdentities(
        TypeModel target,
        IEnumerable<MemberModel> members,
        List<Diagnostic> diagnostics)
    {
        Dictionary<int, MemberModel> fieldIds = [];
        Dictionary<string, MemberModel> fieldNames = new(StringComparer.Ordinal);
        foreach (MemberModel member in members)
        {
            if (member.FieldId >= 0)
            {
                if (fieldIds.TryGetValue(member.FieldId, out MemberModel? previous))
                {
                    diagnostics.Add(Diagnostic.Create(
                        DuplicateStructuralField,
                        target.DeclarationLocation,
                        target.TargetTypeName,
                        member.FieldId.ToString(CultureInfo.InvariantCulture),
                        MemberDisplay(previous),
                        MemberDisplay(member)));
                }
                else
                {
                    fieldIds.Add(member.FieldId, member);
                }
            }
            else if (fieldNames.TryGetValue(member.FieldIdentifier, out MemberModel? previous))
            {
                diagnostics.Add(Diagnostic.Create(
                    DuplicateStructuralField,
                    target.DeclarationLocation,
                    target.TargetTypeName,
                    member.FieldIdentifier,
                    MemberDisplay(previous),
                    MemberDisplay(member)));
            }
            else
            {
                fieldNames.Add(member.FieldIdentifier, member);
            }
        }
    }

    private static string MemberDisplay(MemberModel member)
    {
        string owner = member.DeclaringType?.ToDisplayString(FullNameFormat) ?? "<unknown>";
        return $"{owner}.{member.TargetMemberName}";
    }

    private static bool IsTypeInHierarchy(
        INamedTypeSymbol target,
        INamedTypeSymbol candidate)
    {
        for (INamedTypeSymbol? current = target;
             current is not null &&
             current.SpecialType != SpecialType.System_Object;
             current = current.BaseType)
        {
            if (RuntimeTypeComparer.Instance.Equals(current, candidate))
            {
                return true;
            }
        }

        return false;
    }

    private static void EmitHierarchyProviderApi(StringBuilder sb, TypeModel model)
    {
        string shallowExpression = BuildShallowMemoryExpression(model);
        sb.AppendLine(
            $"    public static readonly long HierarchyShallowBytes = checked({shallowExpression});");
    }

    private static void EmitWireDescriptor(
        StringBuilder sb,
        MemberModel member)
    {
        if (member.MemberType is null || member.DeclaringType is null)
        {
            return;
        }

        string declaringTypeName =
            member.DeclaringType.ToDisplayString(FullNameFormat);
        sb.Append(
            $"    [global::Apache.Fory.ForyGeneratedWireMember({member.DeclarationOrdinal}, typeof({declaringTypeName}), \"{EscapeString(member.Name)}\", \"{EscapeString(member.TargetMemberName)}\"");
        if (member.FieldId >= 0)
        {
            sb.Append($", FieldId = {member.FieldId}");
        }

        if (member.SchemaDescriptorType is not null)
        {
            sb.Append(
                $", SchemaType = typeof({member.SchemaDescriptorType.ToDisplayString(FullNameFormat)})");
        }

        if (member.SlotKey is not null)
        {
            sb.Append($", Slot = \"{EscapeString(member.SlotKey)}\"");
        }

        sb.AppendLine(")]");
    }

    private static string BuildShallowMemoryExpression(TypeModel model)
    {
        List<string> shallowParts = ["0L"];
        if (model.ShallowStorage.ParentProviderTypeName is not null)
        {
            shallowParts.Add(
                $"{model.ShallowStorage.ParentProviderTypeName}.HierarchyShallowBytes");
        }

        shallowParts.AddRange(
            model.ShallowStorage.DeclaredFieldExpressions);
        return string.Join(" + ", shallowParts);
    }

    private static void EmitMemberAccessors(
        StringBuilder sb,
        TypeModel model,
        MemberModel member)
    {
        if (member.MemberType is null ||
            member.DeclaringType is null ||
            member.AccessorProviderTypeName is null)
        {
            return;
        }

        string visibility = AccessorVisibility(model);
        string memberTypeName = member.MemberType.ToDisplayString(FullNameFormat);
        string declaringTypeName = member.DeclaringType.ToDisplayString(FullNameFormat);
        bool publish = model.PublishesHierarchy;
        string targetName = EscapeIdentifier(member.TargetMemberName);
        if (member.MemberKind == WireMemberKind.Field &&
            (publish || member.FieldAccessorName is not null))
        {
            if (publish)
            {
                EmitWireDescriptor(sb, member);
            }

            if (member.FieldAccessorName is not null)
            {
                sb.AppendLine(
                    $"    [global::System.Runtime.CompilerServices.UnsafeAccessor(global::System.Runtime.CompilerServices.UnsafeAccessorKind.Field, Name = \"{EscapeString(member.TargetMemberName)}\")]");
                sb.AppendLine(
                    $"    {visibility} static extern ref {memberTypeName} F{member.DeclarationOrdinal}({declaringTypeName} value);");
            }
            else
            {
                sb.AppendLine(
                    $"    {visibility} static ref {memberTypeName} F{member.DeclarationOrdinal}({declaringTypeName} value) => ref value.{targetName};");
            }
        }

        if (member.MemberKind == WireMemberKind.Property &&
            (publish || member.GetterAccessorName is not null))
        {
            if (publish)
            {
                EmitWireDescriptor(sb, member);
            }

            if (member.GetterAccessorName is not null)
            {
                sb.AppendLine(
                    $"    [global::System.Runtime.CompilerServices.UnsafeAccessor(global::System.Runtime.CompilerServices.UnsafeAccessorKind.Method, Name = \"get_{EscapeString(member.TargetMemberName)}\")]");
                sb.AppendLine(
                    $"    {visibility} static extern {memberTypeName} G{member.DeclarationOrdinal}({declaringTypeName} value);");
            }
            else
            {
                sb.AppendLine(
                    $"    {visibility} static {memberTypeName} G{member.DeclarationOrdinal}({declaringTypeName} value) => value.{targetName};");
            }
        }

        if (member.MemberKind == WireMemberKind.Property &&
            (publish || member.SetterAccessorName is not null))
        {
            if (member.SetterAccessorName is not null)
            {
                sb.AppendLine(
                    $"    [global::System.Runtime.CompilerServices.UnsafeAccessor(global::System.Runtime.CompilerServices.UnsafeAccessorKind.Method, Name = \"set_{EscapeString(member.TargetMemberName)}\")]");
                sb.AppendLine(
                    $"    {visibility} static extern void S{member.DeclarationOrdinal}({declaringTypeName} value, {memberTypeName} fieldValue);");
            }
            else
            {
                sb.AppendLine(
                    $"    {visibility} static void S{member.DeclarationOrdinal}({declaringTypeName} value, {memberTypeName} fieldValue) => value.{targetName} = fieldValue;");
            }
        }
    }

    private static string AccessorVisibility(TypeModel model)
    {
        if (!model.PublishesHierarchy)
        {
            return "private";
        }

        return model.ProviderVisibility;
    }

    private static string ProviderVisibility(
        INamedTypeSymbol target,
        ImmutableArray<MemberModel> members)
    {
        if (target.TypeKind != TypeKind.Class ||
            target.IsSealed ||
            !IsPublicSignatureType(target) ||
            members.Any(member =>
                member.DeclaringType is null ||
                !IsPublicSignatureType(member.DeclaringType) ||
                member.MemberType is null ||
                !IsPublicSignatureType(member.MemberType) ||
                member.SchemaDescriptorType is not null &&
                !IsPublicSignatureType(member.SchemaDescriptorType)))
        {
            return "internal";
        }

        return "public";
    }

    private static bool CanReferenceProvider(
        Compilation compilation,
        INamedTypeSymbol provider)
    {
        // Generated files do not inherit extern-alias directives from user source.
        return compilation.IsSymbolAccessibleWithin(provider, compilation.Assembly) &&
               !RequiresExternAlias(provider, compilation);
    }

    private static bool IsPublicType(INamedTypeSymbol type)
    {
        for (INamedTypeSymbol? current = type; current is not null; current = current.ContainingType)
        {
            if (current.DeclaredAccessibility != Accessibility.Public)
            {
                return false;
            }
        }

        return true;
    }

    private static bool IsPublicSignatureType(ITypeSymbol type)
    {
        switch (type)
        {
            case IArrayTypeSymbol array:
                return IsPublicSignatureType(array.ElementType);
            case IPointerTypeSymbol pointer:
                return IsPublicSignatureType(pointer.PointedAtType);
            case INamedTypeSymbol named:
                return IsPublicType(named.OriginalDefinition) &&
                       (named.ContainingType is null ||
                        IsPublicSignatureType(named.ContainingType)) &&
                       named.TypeArguments.All(IsPublicSignatureType);
            default:
                return type.TypeKind == TypeKind.Dynamic;
        }
    }

    private static string GeneratedHierarchyName(ITypeSymbol target)
    {
        return "__ForyHierarchy_" + BuildRuntimeTypeKey(target);
    }

    private static string BuildRuntimeTypeKey(ITypeSymbol type)
    {
        List<byte> bytes = [];
        AppendRuntimeTypeKey(bytes, type);
        StringBuilder result = new(bytes.Count * 2);
        const string hex = "0123456789ABCDEF";
        foreach (byte value in bytes)
        {
            result.Append(hex[value >> 4]);
            result.Append(hex[value & 0x0F]);
        }

        return result.ToString();
    }

    private static void AppendRuntimeTypeKey(List<byte> bytes, ITypeSymbol type)
    {
        if (type.TypeKind == TypeKind.Dynamic ||
            type.SpecialType == SpecialType.System_Object)
        {
            AppendKeyComponent(bytes, "dynamic-object");
            return;
        }

        switch (type)
        {
            case IArrayTypeSymbol array:
                AppendKeyComponent(bytes, "array");
                AppendKeyComponent(bytes, array.Rank.ToString(CultureInfo.InvariantCulture));
                AppendKeyComponent(bytes, array.IsSZArray ? "1" : "0");
                AppendRuntimeTypeKey(bytes, array.ElementType);
                return;
            case IPointerTypeSymbol pointer:
                AppendKeyComponent(bytes, "pointer");
                AppendRuntimeTypeKey(bytes, pointer.PointedAtType);
                return;
            case INamedTypeSymbol named:
                named = named.TupleUnderlyingType ?? named;
                if (named.IsNativeIntegerType &&
                    named.NativeIntegerUnderlyingType is INamedTypeSymbol nativeUnderlying)
                {
                    named = nativeUnderlying;
                }

                AppendKeyComponent(bytes, "named");
                AppendAssemblyKey(bytes, named.OriginalDefinition.ContainingAssembly.Identity);
                AppendKeyComponent(bytes, FullMetadataName(named.OriginalDefinition));
                if (named.ContainingType is null)
                {
                    AppendKeyComponent(bytes, "no-containing-type");
                }
                else
                {
                    AppendKeyComponent(bytes, "containing-type");
                    AppendRuntimeTypeKey(bytes, named.ContainingType);
                }

                AppendKeyComponent(
                    bytes,
                    named.TypeArguments.Length.ToString(CultureInfo.InvariantCulture));
                foreach (ITypeSymbol typeArgument in named.TypeArguments)
                {
                    AppendRuntimeTypeKey(bytes, typeArgument);
                }

                return;
            default:
                AppendKeyComponent(bytes, type.TypeKind.ToString());
                AppendKeyComponent(bytes, type.ToDisplayString(SymbolDisplayFormat.FullyQualifiedFormat));
                return;
        }
    }

    private static void AppendAssemblyKey(List<byte> bytes, AssemblyIdentity identity)
    {
        AppendKeyComponent(bytes, identity.Name);
        AppendKeyComponent(bytes, identity.Version.ToString());
        AppendKeyComponent(bytes, identity.CultureName ?? string.Empty);
        StringBuilder token = new(identity.PublicKeyToken.Length * 2);
        const string hex = "0123456789ABCDEF";
        foreach (byte value in identity.PublicKeyToken)
        {
            token.Append(hex[value >> 4]);
            token.Append(hex[value & 0x0F]);
        }

        AppendKeyComponent(bytes, token.ToString());
        AppendKeyComponent(
            bytes,
            ((int)identity.ContentType).ToString(CultureInfo.InvariantCulture));
        AppendKeyComponent(bytes, identity.IsRetargetable ? "1" : "0");
    }

    private static void AppendKeyComponent(List<byte> bytes, string value)
    {
        byte[] valueBytes = Encoding.UTF8.GetBytes(value);
        uint length = checked((uint)valueBytes.Length);
        bytes.Add((byte)(length >> 24));
        bytes.Add((byte)(length >> 16));
        bytes.Add((byte)(length >> 8));
        bytes.Add((byte)length);
        bytes.AddRange(valueBytes);
    }

    private static string FullMetadataName(INamedTypeSymbol type)
    {
        if (type.ContainingType is not null)
        {
            return $"{FullMetadataName(type.ContainingType)}+{type.MetadataName}";
        }

        string namespaceName = type.ContainingNamespace.IsGlobalNamespace
            ? string.Empty
            : type.ContainingNamespace.ToDisplayString();
        return string.IsNullOrEmpty(namespaceName)
            ? type.MetadataName
            : $"{namespaceName}.{type.MetadataName}";
    }
}
