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
    private sealed class TypeResolution
    {
        public TypeResolution(bool supported, TypeClassification classification)
        {
            Supported = supported;
            Classification = classification;
        }

        public bool Supported { get; }
        public TypeClassification Classification { get; }
    }

    private sealed class TypeClassification
    {
        public TypeClassification(
            uint typeId,
            bool isPrimitive,
            bool isBuiltIn,
            bool isCollection,
            bool isMap,
            bool isCompressedNumeric,
            int primitiveSize)
        {
            TypeId = typeId;
            IsPrimitive = isPrimitive;
            IsBuiltIn = isBuiltIn;
            IsCollection = isCollection;
            IsMap = isMap;
            IsCompressedNumeric = isCompressedNumeric;
            PrimitiveSize = primitiveSize;
        }

        public uint TypeId { get; }
        public bool IsPrimitive { get; }
        public bool IsBuiltIn { get; }
        public bool IsCollection { get; }
        public bool IsMap { get; }
        public bool IsCompressedNumeric { get; }
        public int PrimitiveSize { get; }
    }

    private sealed class TypeMetaFieldTypeModel
    {
        public TypeMetaFieldTypeModel(
            string typeIdExpr,
            bool nullable,
            bool trackRefByContext,
            ImmutableArray<TypeMetaFieldTypeModel> generics)
        {
            TypeIdExpr = typeIdExpr;
            Nullable = nullable;
            TrackRefByContext = trackRefByContext;
            Generics = generics;
        }

        public string TypeIdExpr { get; }
        public bool Nullable { get; }
        public bool TrackRefByContext { get; }
        public ImmutableArray<TypeMetaFieldTypeModel> Generics { get; }
    }

    private sealed class SchemaTypeModel
    {
        public SchemaTypeModel(
            uint typeId,
            SchemaTypeKind kind,
            ImmutableArray<SchemaTypeModel> generics,
            bool hasExplicitScalarEncoding = false)
        {
            TypeId = typeId;
            Kind = kind;
            Generics = generics;
            HasExplicitScalarEncoding = hasExplicitScalarEncoding;
        }

        public uint TypeId { get; }
        public SchemaTypeKind Kind { get; }
        public ImmutableArray<SchemaTypeModel> Generics { get; }
        public bool HasExplicitScalarEncoding { get; }
    }

    private sealed class FieldCodecModel
    {
        public FieldCodecModel(
            FieldCodecKind kind,
            uint typeId,
            string typeName,
            bool nullable,
            bool nullableValueType,
            CarrierKind carrierKind,
            ImmutableArray<FieldCodecModel> generics)
        {
            Kind = kind;
            TypeId = typeId;
            TypeName = typeName;
            Nullable = nullable;
            NullableValueType = nullableValueType;
            CarrierKind = carrierKind;
            Generics = generics;
        }

        public FieldCodecKind Kind { get; }
        public uint TypeId { get; }
        public string TypeName { get; }
        public bool Nullable { get; }
        public bool NullableValueType { get; }
        public CarrierKind CarrierKind { get; }
        public ImmutableArray<FieldCodecModel> Generics { get; }
    }

    private sealed class RuntimeTypeComparer : IEqualityComparer<ITypeSymbol>
    {
        // CLR registration erases source-only distinctions such as tuple names,
        // dynamic, and native-integer aliases; equality and hashing must erase
        // the same distinctions before selecting one generated owner.
        public static readonly RuntimeTypeComparer Instance = new();

        public bool Equals(ITypeSymbol? left, ITypeSymbol? right)
        {
            if (ReferenceEquals(left, right))
            {
                return true;
            }

            if (left is null || right is null)
            {
                return false;
            }

            if (IsDynamicOrObject(left) || IsDynamicOrObject(right))
            {
                return IsDynamicOrObject(left) && IsDynamicOrObject(right);
            }

            if (left is IArrayTypeSymbol || right is IArrayTypeSymbol)
            {
                if (left is not IArrayTypeSymbol leftArray ||
                    right is not IArrayTypeSymbol rightArray)
                {
                    return false;
                }

                return leftArray.Rank == rightArray.Rank &&
                       leftArray.IsSZArray == rightArray.IsSZArray &&
                       Equals(leftArray.ElementType, rightArray.ElementType);
            }

            if (left is INamedTypeSymbol leftNamed &&
                right is INamedTypeSymbol rightNamed)
            {
                return NamedEquals(
                    NormalizeNamed(leftNamed),
                    NormalizeNamed(rightNamed));
            }

            return SymbolEqualityComparer.Default.Equals(left, right);
        }

        public int GetHashCode(ITypeSymbol type)
        {
            if (IsDynamicOrObject(type))
            {
                return (int)SpecialType.System_Object;
            }

            if (type is IArrayTypeSymbol array)
            {
                int hash = CombineHash(17, (int)TypeKind.Array);
                hash = CombineHash(hash, array.Rank);
                hash = CombineHash(hash, array.IsSZArray ? 1 : 0);
                return CombineHash(hash, GetHashCode(array.ElementType));
            }

            if (type is INamedTypeSymbol named)
            {
                return NamedHash(NormalizeNamed(named));
            }

            return SymbolEqualityComparer.Default.GetHashCode(type);
        }

        private bool NamedEquals(
            INamedTypeSymbol left,
            INamedTypeSymbol right)
        {
            if (!SymbolEqualityComparer.Default.Equals(
                    left.OriginalDefinition,
                    right.OriginalDefinition) ||
                left.TypeArguments.Length != right.TypeArguments.Length)
            {
                return false;
            }

            INamedTypeSymbol? leftContaining = left.ContainingType;
            INamedTypeSymbol? rightContaining = right.ContainingType;
            if ((leftContaining is null) != (rightContaining is null) ||
                leftContaining is not null &&
                !Equals(leftContaining, rightContaining))
            {
                return false;
            }

            for (int i = 0; i < left.TypeArguments.Length; i++)
            {
                if (!Equals(left.TypeArguments[i], right.TypeArguments[i]))
                {
                    return false;
                }
            }

            return true;
        }

        private int NamedHash(INamedTypeSymbol type)
        {
            int hash = CombineHash(
                23,
                SymbolEqualityComparer.Default.GetHashCode(type.OriginalDefinition));
            if (type.ContainingType is not null)
            {
                hash = CombineHash(hash, GetHashCode(type.ContainingType));
            }

            foreach (ITypeSymbol typeArgument in type.TypeArguments)
            {
                hash = CombineHash(hash, GetHashCode(typeArgument));
            }

            return hash;
        }

        private static INamedTypeSymbol NormalizeNamed(INamedTypeSymbol type)
        {
            type = NormalizeTuple(type);

            if (type.IsNativeIntegerType &&
                type.NativeIntegerUnderlyingType is INamedTypeSymbol nativeUnderlying)
            {
                return nativeUnderlying;
            }

            return type;
        }

        private static INamedTypeSymbol NormalizeTuple(INamedTypeSymbol type)
        {
            return type.TupleUnderlyingType ?? type;
        }

        private static bool IsDynamicOrObject(ITypeSymbol type)
        {
            return type.TypeKind == TypeKind.Dynamic ||
                   type.SpecialType == SpecialType.System_Object;
        }

        private static int CombineHash(int current, int value)
        {
            return unchecked(current * 31 + value);
        }
    }

    private sealed class TypeModel
    {
        public TypeModel(
            string declarationName,
            string targetTypeName,
            ITypeSymbol targetType,
            string generatedClassName,
            DeclKind kind,
            bool evolving,
            Location? declarationLocation,
            ImmutableArray<MemberModel> members,
            ImmutableArray<MemberModel> sortedMembers,
            ImmutableArray<Diagnostic> diagnostics,
            ImmutableArray<UnionCaseModel> unionCases = default,
            ImmutableArray<MemberModel> declaredMembers = default,
            ShallowStorageModel? shallowStorage = null,
            bool isExternal = false,
            bool providerOnly = false,
            string providerVisibility = "internal")
        {
            DeclarationName = declarationName;
            TargetTypeName = targetTypeName;
            TargetType = targetType;
            GeneratedClassName = generatedClassName;
            Kind = kind;
            Evolving = evolving;
            DeclarationLocation = declarationLocation;
            Members = members;
            SortedMembers = sortedMembers;
            Diagnostics = diagnostics;
            UnionCases = unionCases.IsDefault
                ? ImmutableArray<UnionCaseModel>.Empty
                : unionCases;
            DeclaredMembers = declaredMembers.IsDefault ? members : declaredMembers;
            if (kind == DeclKind.Class && shallowStorage is null)
            {
                throw new ArgumentException(
                    "Class type models require an explicit shallow-storage model.",
                    nameof(shallowStorage));
            }

            ShallowStorage = shallowStorage ?? ShallowStorageModel.Empty;
            IsExternal = isExternal;
            ProviderOnly = providerOnly;
            ProviderVisibility = providerVisibility;
        }

        public string DeclarationName { get; }
        public string TargetTypeName { get; }
        public ITypeSymbol TargetType { get; }
        public string GeneratedClassName { get; }
        public DeclKind Kind { get; }
        public bool Evolving { get; }
        public Location? DeclarationLocation { get; }
        public ImmutableArray<MemberModel> Members { get; }
        public ImmutableArray<MemberModel> SortedMembers { get; }
        public ImmutableArray<Diagnostic> Diagnostics { get; }
        public ImmutableArray<UnionCaseModel> UnionCases { get; }
        public ImmutableArray<MemberModel> DeclaredMembers { get; }
        public ShallowStorageModel ShallowStorage { get; }
        public bool IsExternal { get; }
        public bool ProviderOnly { get; }
        public string ProviderVisibility { get; }
        public bool PublishesHierarchy =>
            Kind == DeclKind.Class &&
            TargetType is INamedTypeSymbol { IsSealed: false };

        public TypeModel WithHierarchy(
            ImmutableArray<MemberModel> members,
            ImmutableArray<MemberModel> sortedMembers,
            string? parentProviderTypeName,
            bool parentIsPublic = true)
        {
            return new TypeModel(
                DeclarationName,
                TargetTypeName,
                TargetType,
                GeneratedClassName,
                Kind,
                Evolving,
                DeclarationLocation,
                members,
                sortedMembers,
                Diagnostics,
                UnionCases,
                DeclaredMembers,
                Kind == DeclKind.Class
                    ? ShallowStorage.WithParent(parentProviderTypeName)
                    : ShallowStorage,
                IsExternal,
                ProviderOnly,
                ProviderVisibility == "public" && parentIsPublic
                    ? "public"
                    : "internal");
        }
    }

    private sealed class ShallowStorageModel
    {
        public static readonly ShallowStorageModel Empty = new(
            null,
            ImmutableArray<string>.Empty);

        public ShallowStorageModel(
            string? parentProviderTypeName,
            ImmutableArray<string> declaredFieldExpressions)
        {
            ParentProviderTypeName = parentProviderTypeName;
            DeclaredFieldExpressions = declaredFieldExpressions.IsDefault
                ? ImmutableArray<string>.Empty
                : declaredFieldExpressions;
        }

        public string? ParentProviderTypeName { get; }
        public ImmutableArray<string> DeclaredFieldExpressions { get; }

        public ShallowStorageModel WithParent(string? parentProviderTypeName)
        {
            return new ShallowStorageModel(
                parentProviderTypeName,
                DeclaredFieldExpressions);
        }
    }

    private sealed class ExternalMemberMapping
    {
        public ExternalMemberMapping(
            bool ignore,
            INamedTypeSymbol? declaringType,
            string targetMemberName)
        {
            Ignore = ignore;
            DeclaringType = declaringType;
            TargetMemberName = targetMemberName;
        }

        public bool Ignore { get; }
        public INamedTypeSymbol? DeclaringType { get; }
        public string TargetMemberName { get; }
    }

    private sealed class ResolvedProvider
    {
        public ResolvedProvider(
            string providerTypeName,
            ImmutableArray<MemberModel> wireMembers,
            bool isPublic)
        {
            ProviderTypeName = providerTypeName;
            WireMembers = wireMembers;
            IsPublic = isPublic;
        }

        public string ProviderTypeName { get; }
        public ImmutableArray<MemberModel> WireMembers { get; }
        public bool IsPublic { get; }
    }

    private sealed class MemberModel
    {
        public MemberModel(
            string name,
            string fieldIdentifier,
            string typeName,
            bool isNullable,
            bool isNullableValueType,
            int fieldId,
            TypeClassification classification,
            int group,
            bool isCollection,
            bool useDictionaryTypeInfoCache,
            bool isRefType,
            bool needsFieldTypeInfo,
            DynamicAnyKind dynamicAnyKind,
            TypeMetaFieldTypeModel typeMeta,
            FieldCodecModel? fieldCodec,
            bool hasSchemaType = false,
            ITypeSymbol? memberType = null,
            INamedTypeSymbol? declaringType = null,
            string? targetMemberName = null,
            WireMemberKind memberKind = WireMemberKind.Field,
            string? slotKey = null,
            string? accessorProviderTypeName = null,
            string? fieldAccessorName = null,
            string? getterAccessorName = null,
            string? setterAccessorName = null,
            string? codeKey = null,
            ITypeSymbol? schemaDescriptorType = null,
            int declarationOrdinal = 0,
            bool useDeclaringCast = false)
        {
            Name = name;
            FieldIdentifier = fieldIdentifier;
            TypeName = typeName;
            IsNullable = isNullable;
            IsNullableValueType = isNullableValueType;
            FieldId = fieldId;
            Classification = classification;
            Group = group;
            IsCollection = isCollection;
            UseDictionaryTypeInfoCache = useDictionaryTypeInfoCache;
            IsRefType = isRefType;
            NeedsFieldTypeInfo = needsFieldTypeInfo;
            DynamicAnyKind = dynamicAnyKind;
            TypeMeta = typeMeta;
            FieldCodec = fieldCodec;
            HasSchemaType = hasSchemaType;
            MemberType = memberType;
            DeclaringType = declaringType;
            TargetMemberName = targetMemberName ?? name;
            MemberKind = memberKind;
            SlotKey = slotKey;
            AccessorProviderTypeName = accessorProviderTypeName;
            FieldAccessorName = fieldAccessorName;
            GetterAccessorName = getterAccessorName;
            SetterAccessorName = setterAccessorName;
            CodeKey = codeKey ?? "M0";
            SchemaDescriptorType = schemaDescriptorType;
            DeclarationOrdinal = declarationOrdinal;
            UseDeclaringCast = useDeclaringCast;
        }

        public string Name { get; }
        public string FieldIdentifier { get; }
        public string TypeName { get; }
        public bool IsNullable { get; }
        public bool IsNullableValueType { get; }
        public int FieldId { get; }
        public TypeClassification Classification { get; }
        public int Group { get; }
        public bool IsCollection { get; }
        public bool UseDictionaryTypeInfoCache { get; }
        public bool IsRefType { get; }
        public bool NeedsFieldTypeInfo { get; }
        public DynamicAnyKind DynamicAnyKind { get; }
        public TypeMetaFieldTypeModel TypeMeta { get; }
        public FieldCodecModel? FieldCodec { get; }
        public bool HasSchemaType { get; }
        public ITypeSymbol? MemberType { get; }
        public INamedTypeSymbol? DeclaringType { get; }
        public string TargetMemberName { get; }
        public WireMemberKind MemberKind { get; }
        public string? SlotKey { get; }
        public string? AccessorProviderTypeName { get; }
        public string? FieldAccessorName { get; }
        public string? GetterAccessorName { get; }
        public string? SetterAccessorName { get; }
        public string CodeKey { get; }
        public ITypeSymbol? SchemaDescriptorType { get; }
        public int DeclarationOrdinal { get; }
        public bool UseDeclaringCast { get; }

        public string ReadExpression(string valueExpression)
        {
            string receiver = AccessReceiver(valueExpression);
            if (FieldAccessorName is not null)
            {
                return $"{AccessorProviderTypeName}.{FieldAccessorName}({receiver})";
            }

            if (GetterAccessorName is not null)
            {
                return $"{AccessorProviderTypeName}.{GetterAccessorName}({receiver})";
            }

            return $"{receiver}.{EscapeIdentifier(TargetMemberName)}";
        }

        public string AssignmentTarget(string valueExpression)
        {
            string receiver = AccessReceiver(valueExpression);
            if (FieldAccessorName is not null)
            {
                return $"{AccessorProviderTypeName}.{FieldAccessorName}({receiver})";
            }

            return $"{receiver}.{EscapeIdentifier(TargetMemberName)}";
        }

        public string AccessReceiver(string valueExpression)
        {
            return !UseDeclaringCast || DeclaringType is null
                ? valueExpression
                : $"(({DeclaringType.ToDisplayString(FullNameFormat)}){valueExpression})";
        }

        public MemberModel WithCodeKey(string codeKey)
        {
            return Copy(
                declaringType: DeclaringType,
                accessorProviderTypeName: AccessorProviderTypeName,
                fieldAccessorName: FieldAccessorName,
                getterAccessorName: GetterAccessorName,
                setterAccessorName: SetterAccessorName,
                codeKey: codeKey,
                useDeclaringCast: UseDeclaringCast);
        }

        public MemberModel WithAccess(
            INamedTypeSymbol? declaringType,
            string? accessorProviderTypeName,
            string? fieldAccessorName,
            string? getterAccessorName,
            string? setterAccessorName,
            bool useDeclaringCast = false)
        {
            return Copy(
                declaringType,
                accessorProviderTypeName,
                fieldAccessorName,
                getterAccessorName,
                setterAccessorName,
                CodeKey,
                useDeclaringCast);
        }

        public MemberModel WithDeclaration(
            ITypeSymbol memberType,
            INamedTypeSymbol declaringType,
            string targetMemberName,
            WireMemberKind memberKind,
            string? slotKey,
            string? accessorProviderTypeName,
            string? fieldAccessorName,
            string? getterAccessorName,
            string? setterAccessorName,
            ITypeSymbol? schemaDescriptorType,
            int declarationOrdinal,
            bool useDeclaringCast = false)
        {
            return new MemberModel(
                Name,
                FieldIdentifier,
                TypeName,
                IsNullable,
                IsNullableValueType,
                FieldId,
                Classification,
                Group,
                IsCollection,
                UseDictionaryTypeInfoCache,
                IsRefType,
                NeedsFieldTypeInfo,
                DynamicAnyKind,
                TypeMeta,
                FieldCodec,
                HasSchemaType,
                memberType,
                declaringType,
                targetMemberName,
                memberKind,
                slotKey,
                accessorProviderTypeName,
                fieldAccessorName,
                getterAccessorName,
                setterAccessorName,
                CodeKey,
                schemaDescriptorType,
                declarationOrdinal,
                useDeclaringCast);
        }

        private MemberModel Copy(
            INamedTypeSymbol? declaringType,
            string? accessorProviderTypeName,
            string? fieldAccessorName,
            string? getterAccessorName,
            string? setterAccessorName,
            string codeKey,
            bool useDeclaringCast)
        {
            return new MemberModel(
                Name,
                FieldIdentifier,
                TypeName,
                IsNullable,
                IsNullableValueType,
                FieldId,
                Classification,
                Group,
                IsCollection,
                UseDictionaryTypeInfoCache,
                IsRefType,
                NeedsFieldTypeInfo,
                DynamicAnyKind,
                TypeMeta,
                FieldCodec,
                HasSchemaType,
                MemberType,
                declaringType,
                TargetMemberName,
                MemberKind,
                SlotKey,
                accessorProviderTypeName,
                fieldAccessorName,
                getterAccessorName,
                setterAccessorName,
                codeKey,
                SchemaDescriptorType,
                DeclarationOrdinal,
                useDeclaringCast);
        }
    }

    private sealed class UnionCaseModel
    {
        public UnionCaseModel(int? caseId, string typeName, bool isUnknown, MemberModel? valueMember)
        {
            CaseId = caseId;
            TypeName = typeName;
            IsUnknown = isUnknown;
            ValueMember = valueMember;
        }

        public int? CaseId { get; }
        public int KnownCaseId => CaseId ?? throw new InvalidOperationException("unknown union carrier has no schema case id");
        public string TypeName { get; }
        public bool IsUnknown { get; }
        public MemberModel? ValueMember { get; }
    }

    private enum DeclKind
    {
        Unknown,
        Class,
        Struct,
        Enum,
        Union,
    }

    private enum ForyAttributeKind
    {
        None,
        Struct,
        Enum,
        Union,
    }

    private enum DynamicAnyKind
    {
        None,
        AnyValue,
    }

    private enum WireMemberKind
    {
        Field,
        Property,
    }

    private enum SchemaTypeKind
    {
        Scalar,
        PackedArray,
        List,
        Set,
        Map,
    }

    private enum FieldCodecKind
    {
        Scalar,
        PackedArray,
        List,
        Set,
        Map,
    }

    private enum CarrierKind
    {
        Value,
        Array,
        List,
        HashSet,
        Dictionary,
        NullableKeyDictionary,
    }
}
