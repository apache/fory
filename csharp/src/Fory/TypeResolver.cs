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

using System.Collections.Concurrent;
using System.Collections.Immutable;
using System.Reflection;
using System.Threading;

namespace Apache.Fory;

public sealed class TypeResolver
{
    private static readonly ConcurrentDictionary<Type, Func<TypeResolver, TypeInfo>> GeneratedFactories = new();
    private static readonly MethodInfo CreateTypeInfoFromSerializerTypedMethod =
        typeof(TypeResolver).GetMethod(
            nameof(CreateTypeInfoFromSerializerTyped),
            BindingFlags.NonPublic | BindingFlags.Static)!;
    private static readonly MethodInfo CreateNullableSerializerTypeInfoMethod =
        typeof(TypeResolver).GetMethod(
            nameof(CreateNullableSerializerTypeInfo),
            BindingFlags.NonPublic | BindingFlags.Instance)!;

    // Cache lookup intentionally keys only by ResolverVersion.
    // Keep this entry resolver-independent: do not add TypeResolver references here.
    // ResolverVersion is a deterministic semantic fingerprint only (no resolver identity).
    // This keeps generic cache reuse efficient across equivalent resolver instances,
    // including per-thread resolvers in ThreadSafeFory.
    private sealed class GenericTypeCacheEntry<T>
    {
        public GenericTypeCacheEntry(ulong resolverVersion, TypeInfo typeInfo)
        {
            ResolverVersion = resolverVersion;
            TypeInfo = typeInfo;
        }

        public ulong ResolverVersion { get; }

        public TypeInfo TypeInfo { get; }
    }

    private static class GenericTypeCache<T>
    {
        public static GenericTypeCacheEntry<T>? Entry;
    }
    private static readonly UInt64Map<Type> PrimitiveStringKeyDictionaryCodecs = CreateTypeMap(
        (typeof(string), typeof(StringPrimitiveDictionaryCodec)),
        (typeof(int), typeof(Int32PrimitiveDictionaryCodec)),
        (typeof(long), typeof(Int64PrimitiveDictionaryCodec)),
        (typeof(bool), typeof(BoolPrimitiveDictionaryCodec)),
        (typeof(double), typeof(Float64PrimitiveDictionaryCodec)),
        (typeof(float), typeof(Float32PrimitiveDictionaryCodec)),
        (typeof(uint), typeof(UInt32PrimitiveDictionaryCodec)),
        (typeof(ulong), typeof(UInt64PrimitiveDictionaryCodec)),
        (typeof(sbyte), typeof(Int8PrimitiveDictionaryCodec)),
        (typeof(short), typeof(Int16PrimitiveDictionaryCodec)),
        (typeof(ushort), typeof(UInt16PrimitiveDictionaryCodec)));

    private static readonly UInt64Map<Type> PrimitiveSameTypeDictionaryCodecs = CreateTypeMap(
        (typeof(int), typeof(Int32PrimitiveDictionaryCodec)),
        (typeof(long), typeof(Int64PrimitiveDictionaryCodec)),
        (typeof(uint), typeof(UInt32PrimitiveDictionaryCodec)),
        (typeof(ulong), typeof(UInt64PrimitiveDictionaryCodec)));

    private static readonly UInt64Map<Type> PrimitiveListLikeCollectionCodecs = CreateTypeMap(
        (typeof(bool), typeof(BoolPrimitiveDictionaryCodec)),
        (typeof(sbyte), typeof(Int8PrimitiveDictionaryCodec)),
        (typeof(short), typeof(Int16PrimitiveDictionaryCodec)),
        (typeof(int), typeof(Int32PrimitiveDictionaryCodec)),
        (typeof(long), typeof(Int64PrimitiveDictionaryCodec)),
        (typeof(ushort), typeof(UInt16PrimitiveDictionaryCodec)),
        (typeof(uint), typeof(UInt32PrimitiveDictionaryCodec)),
        (typeof(ulong), typeof(UInt64PrimitiveDictionaryCodec)),
        (typeof(float), typeof(Float32PrimitiveDictionaryCodec)),
        (typeof(double), typeof(Float64PrimitiveDictionaryCodec)));

    private static readonly UInt64Map<Type> PrimitiveSetCollectionCodecs = CreateTypeMap(
        (typeof(sbyte), typeof(Int8PrimitiveDictionaryCodec)),
        (typeof(short), typeof(Int16PrimitiveDictionaryCodec)),
        (typeof(int), typeof(Int32PrimitiveDictionaryCodec)),
        (typeof(long), typeof(Int64PrimitiveDictionaryCodec)),
        (typeof(ushort), typeof(UInt16PrimitiveDictionaryCodec)),
        (typeof(uint), typeof(UInt32PrimitiveDictionaryCodec)),
        (typeof(ulong), typeof(UInt64PrimitiveDictionaryCodec)),
        (typeof(float), typeof(Float32PrimitiveDictionaryCodec)),
        (typeof(double), typeof(Float64PrimitiveDictionaryCodec)));

    private readonly Dictionary<uint, TypeInfo> _byUserTypeId = [];
    private readonly Dictionary<(string NamespaceName, string TypeName), TypeInfo> _byTypeName = [];
    private readonly UInt64Map<TypeMeta> _validatedTypeMetaByType = new();

    private readonly UInt64Map<TypeInfo> _typeInfos = new();
    private ulong _versionHash;
    private bool _finalized;

    public static void RegisterGenerated<T, TSerializer>()
        where TSerializer : Serializer<T>, new()
    {
        Type type = typeof(T);
        GeneratedFactories[type] = static _ => TypeInfo.Create(typeof(T), new TSerializer());
    }

    private static UInt64Map<Type> CreateTypeMap(params (Type Key, Type Value)[] entries)
    {
        UInt64Map<Type> map = new(entries.Length * 2);
        foreach ((Type key, Type value) in entries)
        {
            map.Set(TypeMapKey.Get(key), value);
        }

        return map;
    }

    public Serializer<T> GetSerializer<T>()
    {
        if (_finalized)
        {
            ulong version = _versionHash;
            GenericTypeCacheEntry<T>? cacheEntry = Volatile.Read(ref GenericTypeCache<T>.Entry);
            if (cacheEntry is not null && cacheEntry.ResolverVersion == version)
            {
                return cacheEntry.TypeInfo.RequireSerializer<T>();
            }
        }

        TypeInfo typeInfo = GetTypeInfo<T>();
        return typeInfo.RequireSerializer<T>();
    }

    public TypeInfo GetTypeInfo(Type type)
    {
        return GetOrCreateTypeInfo(type, null);
    }

    public TypeInfo GetTypeInfo<T>()
    {
        if (_finalized)
        {
            ulong version = _versionHash;
            GenericTypeCacheEntry<T>? cacheEntry = Volatile.Read(ref GenericTypeCache<T>.Entry);
            if (cacheEntry is not null && cacheEntry.ResolverVersion == version)
            {
                return cacheEntry.TypeInfo;
            }
        }

        TypeInfo typeInfo = GetTypeInfo(typeof(T));
        EnsureFinalizedVersion();
        Volatile.Write(
            ref GenericTypeCache<T>.Entry,
            new GenericTypeCacheEntry<T>(_versionHash, typeInfo));
        return typeInfo;
    }

    internal bool IsNoneObject(TypeInfo typeInfo, object? value)
    {
        return typeInfo.IsNullableType && value is null;
    }

    internal static bool NeedToWriteTypeInfoForField(TypeInfo typeInfo)
    {
        return typeInfo.IsDynamicType ||
               typeInfo.UserTypeKind is UserTypeKind.Struct or UserTypeKind.Ext;
    }

    internal static bool NeedToWriteTypeInfoForField(TypeId typeId)
    {
        return typeId is
            TypeId.Struct or
            TypeId.CompatibleStruct or
            TypeId.NamedStruct or
            TypeId.NamedCompatibleStruct or
            TypeId.Ext or
            TypeId.NamedExt;
    }

    internal void WriteDataObject(TypeInfo typeInfo, WriteContext context, object? value, bool hasGenerics)
    {
        typeInfo.WriteDataObject(context, value, hasGenerics);
    }

    internal object? ReadDataObject(TypeInfo typeInfo, ReadContext context)
    {
        return typeInfo.ReadDataObject(context);
    }

    public void WriteObject(
        TypeInfo typeInfo,
        WriteContext context,
        object? value,
        RefMode refMode,
        bool writeTypeInfo,
        bool hasGenerics)
    {
        typeInfo.WriteObject(context, value, refMode, writeTypeInfo, hasGenerics);
    }

    internal object? ReadObject(TypeInfo typeInfo, ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        return typeInfo.ReadObject(context, refMode, readTypeInfo);
    }

    internal void WriteTypeInfo(TypeInfo typeInfo, WriteContext context)
    {
        WriteTypeInfoCore(typeInfo.Type, typeInfo, context);
    }

    internal void ReadTypeInfo(TypeInfo typeInfo, ReadContext context)
    {
        ReadTypeInfoCore(typeInfo.Type, typeInfo, context);
    }

    internal IReadOnlyList<TypeMetaFieldInfo> TypeMetaFields(TypeInfo typeInfo, bool trackRef)
    {
        Type? nullableType = Nullable.GetUnderlyingType(typeInfo.Type);
        if (nullableType is not null)
        {
            return TypeMetaFields(GetTypeInfo(nullableType), trackRef);
        }

        return typeInfo.TypeMetaFields(trackRef);
    }

    private TypeInfo GetOrCreateTypeInfo(Type type, TypeInfo? explicitTypeInfo)
    {
        ulong typeKey = TypeMapKey.Get(type);
        if (_typeInfos.TryGetValue(typeKey, out TypeInfo? existing))
        {
            if (explicitTypeInfo is null || ReferenceEquals(existing, explicitTypeInfo))
            {
                return existing;
            }

            if (existing.IsRegistered)
            {
                throw new InvalidDataException($"cannot override serializer for registered type {type}");
            }
        }

        TypeInfo typeInfo = explicitTypeInfo ?? CreateBindingCore(type);
        if (typeInfo.Type != type)
        {
            throw new InvalidDataException($"serializer type mismatch for {type}, got {typeInfo.Type}");
        }

        if (_typeInfos.TryGetValue(typeKey, out TypeInfo? previous))
        {
            typeInfo = typeInfo.WithRegistrationFrom(previous);
        }

        _typeInfos.Set(typeKey, typeInfo);
        InvalidateFinalizedVersion();
        return typeInfo;
    }

    internal TypeInfo RegisterSerializer<T, TSerializer>()
        where TSerializer : Serializer<T>, new()
    {
        TypeInfo typeInfo = TypeInfo.Create(typeof(T), new TSerializer());
        RegisterSerializer(typeof(T), typeInfo);
        return typeInfo;
    }

    internal void RegisterSerializer(Type type, TypeInfo typeInfo)
    {
        GetOrCreateTypeInfo(type, typeInfo);
    }

    internal void Register(Type type, uint id, TypeInfo? explicitTypeInfo = null)
    {
        TypeInfo typeInfo = GetOrCreateTypeInfo(type, explicitTypeInfo).WithTypeIdRegistration(id);
        _typeInfos.Set(TypeMapKey.Get(type), typeInfo);
        _byUserTypeId[id] = typeInfo;
        InvalidateFinalizedVersion();
    }

    internal void Register(Type type, string namespaceName, string typeName, TypeInfo? explicitTypeInfo = null)
    {
        TypeInfo typeInfo = GetOrCreateTypeInfo(type, explicitTypeInfo);
        MetaString namespaceMeta = MetaStringEncoder.Namespace.Encode(namespaceName, TypeMetaEncodings.NamespaceMetaStringEncodings);
        MetaString typeNameMeta = MetaStringEncoder.TypeName.Encode(typeName, TypeMetaEncodings.TypeNameMetaStringEncodings);
        typeInfo = typeInfo.WithTypeNameRegistration(namespaceMeta, typeNameMeta);
        _typeInfos.Set(TypeMapKey.Get(type), typeInfo);
        _byTypeName[(namespaceName, typeName)] = typeInfo;
        InvalidateFinalizedVersion();
    }

    /// <summary>
    /// Returns a finalized semantic resolver version used by generated/static caches.
    /// The version is computed lazily and changes whenever bindings/registrations change.
    /// </summary>
    /// <returns>Resolver version token.</returns>
    public ulong VersionHash()
    {
        EnsureFinalizedVersion();
        return _versionHash;
    }

    private void InvalidateFinalizedVersion()
    {
        _finalized = false;
        _versionHash = 0;
        _validatedTypeMetaByType.ClearKeys();
    }

    private void EnsureFinalizedVersion()
    {
        if (_finalized)
        {
            return;
        }

        _versionHash = ComputeVersionHash();
        _finalized = true;
    }

    private ulong ComputeVersionHash()
    {
        const ulong offsetBasis = 14695981039346656037UL;
        const ulong prime = 1099511628211UL;

        static ulong MixByte(ulong hash, byte value)
        {
            const ulong p = 1099511628211UL;
            return (hash ^ value) * p;
        }

        static ulong MixBool(ulong hash, bool value)
        {
            return MixByte(hash, value ? (byte)1 : (byte)0);
        }

        static ulong MixUInt32(ulong hash, uint value)
        {
            hash = MixByte(hash, unchecked((byte)(value & 0xFF)));
            hash = MixByte(hash, unchecked((byte)((value >> 8) & 0xFF)));
            hash = MixByte(hash, unchecked((byte)((value >> 16) & 0xFF)));
            hash = MixByte(hash, unchecked((byte)((value >> 24) & 0xFF)));
            return hash;
        }

        static ulong MixUInt64(ulong hash, ulong value)
        {
            hash = MixByte(hash, unchecked((byte)(value & 0xFF)));
            hash = MixByte(hash, unchecked((byte)((value >> 8) & 0xFF)));
            hash = MixByte(hash, unchecked((byte)((value >> 16) & 0xFF)));
            hash = MixByte(hash, unchecked((byte)((value >> 24) & 0xFF)));
            hash = MixByte(hash, unchecked((byte)((value >> 32) & 0xFF)));
            hash = MixByte(hash, unchecked((byte)((value >> 40) & 0xFF)));
            hash = MixByte(hash, unchecked((byte)((value >> 48) & 0xFF)));
            hash = MixByte(hash, unchecked((byte)((value >> 56) & 0xFF)));
            return hash;
        }

        static ulong MixString(ulong hash, string? value)
        {
            if (value is null)
            {
                return MixUInt32(hash, uint.MaxValue);
            }

            hash = MixUInt32(hash, unchecked((uint)value.Length));
            for (int i = 0; i < value.Length; i++)
            {
                char c = value[i];
                hash = MixByte(hash, unchecked((byte)(c & 0xFF)));
                hash = MixByte(hash, unchecked((byte)(c >> 8)));
            }

            return hash;
        }

        static ulong MixFieldType(ulong hash, TypeMetaFieldType fieldType)
        {
            hash = MixUInt32(hash, fieldType.TypeId);
            hash = MixBool(hash, fieldType.Nullable);
            hash = MixBool(hash, fieldType.TrackRef);
            hash = MixUInt32(hash, unchecked((uint)fieldType.Generics.Count));
            for (int i = 0; i < fieldType.Generics.Count; i++)
            {
                hash = MixFieldType(hash, fieldType.Generics[i]);
            }

            return hash;
        }

        ulong hash = offsetBasis;
        hash = MixBool(hash, true);

        List<TypeInfo> typeInfos = new(_typeInfos.Count);
        _typeInfos.AddValuesTo(typeInfos);
        typeInfos.Sort(static (left, right) =>
            StringComparer.Ordinal.Compare(
                left.Type.AssemblyQualifiedName ?? left.Type.FullName ?? left.Type.Name,
                right.Type.AssemblyQualifiedName ?? right.Type.FullName ?? right.Type.Name));
        hash = MixUInt32(hash, unchecked((uint)typeInfos.Count));
        foreach (TypeInfo info in typeInfos)
        {
            hash = MixString(hash, info.Type.AssemblyQualifiedName ?? info.Type.FullName ?? info.Type.Name);
            hash = MixString(hash, info.SerializerType.AssemblyQualifiedName ?? info.SerializerType.FullName ?? info.SerializerType.Name);
            hash = MixUInt32(hash, info.BuiltInTypeId.HasValue ? (uint)info.BuiltInTypeId.Value + 1u : 0u);
            hash = MixUInt32(hash, info.UserTypeKind.HasValue ? (uint)info.UserTypeKind.Value + 1u : 0u);
            hash = MixBool(hash, info.IsDynamicType);
            hash = MixBool(hash, info.IsNullableType);
            hash = MixBool(hash, info.IsRefType);
            hash = MixBool(hash, info.Evolving);
            hash = MixBool(hash, info.IsRegistered);
            hash = MixBool(hash, info.RegisterByName);

            if (info.IsRegistered && info.RegisterByName)
            {
                hash = MixString(hash, info.NamespaceName?.Value);
                hash = MixString(hash, info.TypeName?.Value);
            }
            else if (info.IsRegistered)
            {
                hash = MixUInt32(hash, info.UserTypeId ?? 0u);
            }

            if (info.UserTypeKind.HasValue && info.UserTypeKind.Value == UserTypeKind.Struct)
            {
                for (int i = 0; i < 2; i++)
                {
                    bool trackRef = i == 1;
                    hash = MixBool(hash, trackRef);
                    IReadOnlyList<TypeMetaFieldInfo> fields = TypeMetaFields(info, trackRef);
                    hash = MixUInt32(hash, unchecked((uint)fields.Count));
                    for (int fieldIdx = 0; fieldIdx < fields.Count; fieldIdx++)
                    {
                        TypeMetaFieldInfo field = fields[fieldIdx];
                        hash = MixUInt32(hash, field.FieldId.HasValue ? (uint)field.FieldId.Value + 1u : 0u);
                        hash = MixString(hash, field.FieldName);
                        hash = MixFieldType(hash, field.FieldType);
                    }
                }

                if (info.IsRegistered)
                {
                    TypeId wireTypeId = ResolveWireTypeId(
                        info.UserTypeKind.Value,
                        info.RegisterByName,
                        compatible: true,
                        info.Evolving);
                    hash = MixUInt32(hash, (uint)wireTypeId);
                    hash = MixUInt64(hash, info.GetTypeMetaHeaderHash(trackRef: false));
                    hash = MixUInt64(hash, info.GetTypeMetaHeaderHash(trackRef: true));
                }
            }
        }

        if (hash == 0)
        {
            hash = prime;
        }

        return hash;
    }

    internal TypeInfo? GetRegisteredTypeInfo(Type type)
    {
        if (_typeInfos.TryGetValue(TypeMapKey.Get(type), out TypeInfo? typeInfo) && typeInfo.IsRegistered)
        {
            return typeInfo;
        }

        return null;
    }

    internal TypeInfo RequireRegisteredTypeInfo(Type type)
    {
        TypeInfo? info = GetRegisteredTypeInfo(type);
        if (info is not null)
        {
            return info;
        }

        throw new TypeNotRegisteredException($"{type} is not registered");
    }

    internal void WriteTypeInfo<T>(Serializer<T> serializer, WriteContext context)
    {
        Type type = typeof(T);
        if (type == typeof(object))
        {
            throw new InvalidDataException("dynamic Any value type info is runtime-only");
        }

        Type? nullableType = Nullable.GetUnderlyingType(type);
        if (nullableType is not null)
        {
            WriteTypeInfoCore(nullableType, GetTypeInfo(nullableType), context);
            return;
        }

        TypeInfo typeInfo = GetTypeInfo<T>();
        WriteTypeInfoCore(type, typeInfo, context);
    }

    private void WriteTypeInfoCore(Type type, TypeInfo typeInfo, WriteContext context)
    {
        if (typeInfo.BuiltInTypeId.HasValue)
        {
            context.Writer.WriteUInt8((byte)typeInfo.BuiltInTypeId.Value);
            return;
        }

        if (!typeInfo.UserTypeKind.HasValue)
        {
            throw new InvalidDataException($"type {type} has runtime-only type info");
        }

        TypeInfo info = typeInfo;
        if (!info.IsRegistered)
        {
            throw new TypeNotRegisteredException($"{type} is not registered");
        }

        if (!info.UserTypeKind.HasValue)
        {
            throw new InvalidDataException($"registered type {type} is not a user type");
        }

        TypeId wireTypeId = ResolveWireTypeId(
            info.UserTypeKind.Value,
            info.RegisterByName,
            context.Compatible,
            info.Evolving);
        context.Writer.WriteUInt8((byte)wireTypeId);
        switch (wireTypeId)
        {
            case TypeId.CompatibleStruct:
            case TypeId.NamedCompatibleStruct:
                {
                    TypeInfo.TypeMetaCacheEntry typeMeta = info.GetTypeMetaCacheEntry(context.TrackRef);
                    context.WriteTypeMeta(type, typeMeta.EncodedBytes);
                    return;
                }
            case TypeId.NamedEnum:
            case TypeId.NamedStruct:
            case TypeId.NamedExt:
            case TypeId.NamedUnion:
                WriteNamedTypeInfo(type, info, context);
                return;
            default:
                if (!info.RegisterByName && WireTypeNeedsUserTypeId(wireTypeId))
                {
                    if (!info.UserTypeId.HasValue)
                    {
                        throw new InvalidDataException("missing user type id for id-registered type");
                    }

                    context.Writer.WriteVarUInt32(info.UserTypeId.Value);
                }

                return;
        }
    }

    private void WriteNamedTypeInfo(Type type, TypeInfo typeInfo, WriteContext context)
    {
        if (context.Compatible)
        {
            TypeInfo.TypeMetaCacheEntry typeMeta = typeInfo.GetTypeMetaCacheEntry(context.TrackRef);
            context.WriteTypeMeta(type, typeMeta.EncodedBytes);
            return;
        }

        if (!typeInfo.NamespaceName.HasValue || !typeInfo.TypeName.HasValue)
        {
            throw new InvalidDataException("missing type name metadata for name-registered type");
        }

        WriteMetaString(
            context,
            typeInfo.NamespaceName.Value,
            TypeMetaEncodings.NamespaceMetaStringEncodings,
            MetaStringEncoder.Namespace);
        WriteMetaString(
            context,
            typeInfo.TypeName.Value,
            TypeMetaEncodings.TypeNameMetaStringEncodings,
            MetaStringEncoder.TypeName);
    }

    internal void ReadTypeInfo<T>(Serializer<T> serializer, ReadContext context)
    {
        Type type = typeof(T);
        if (type == typeof(object))
        {
            TypeInfo anyTypeInfo = ReadAnyTypeInfo(context);
            context.SetReadTypeInfo(type, anyTypeInfo);
            return;
        }

        Type? nullableType = Nullable.GetUnderlyingType(type);
        if (nullableType is not null)
        {
            ReadTypeInfoCore(nullableType, GetTypeInfo(nullableType), context);
            return;
        }

        TypeInfo typeInfo = GetTypeInfo<T>();
        ReadTypeInfoCore(type, typeInfo, context);
    }

    private void ReadTypeInfoCore(Type type, TypeInfo typeInfo, ReadContext context)
    {
        uint rawTypeId = context.Reader.ReadUInt8();
        if (!IsKnownTypeId(rawTypeId))
        {
            throw new InvalidDataException($"unknown type id {rawTypeId}");
        }

        TypeId typeId = (TypeId)rawTypeId;
        if (typeInfo.BuiltInTypeId.HasValue)
        {
            if (typeId != typeInfo.BuiltInTypeId.Value)
            {
                throw new TypeMismatchException((uint)typeInfo.BuiltInTypeId.Value, rawTypeId);
            }

            return;
        }

        if (!typeInfo.UserTypeKind.HasValue)
        {
            throw new InvalidDataException($"type {type} has runtime-only type info");
        }

        TypeInfo info = typeInfo;
        if (!info.IsRegistered)
        {
            throw new TypeNotRegisteredException($"{type} is not registered");
        }

        if (!info.UserTypeKind.HasValue)
        {
            throw new InvalidDataException($"registered type {type} is not a user type");
        }

        if (context.Compatible &&
            info.UserTypeKind.Value == UserTypeKind.Struct &&
            !info.RegisterByName &&
            typeId == TypeId.CompatibleStruct)
        {
            TypeMeta remoteTypeMeta = context.ReadTypeMeta();
            if (!HasValidatedTypeMeta(info, remoteTypeMeta))
            {
                ValidateTypeMeta(
                    remoteTypeMeta,
                    info,
                    info.UserTypeKind.Value,
                    info.RegisterByName,
                    context.Compatible,
                    typeId);
                remoteTypeMeta.EnsureAssignedFieldIds(TypeMetaFields(info, context.TrackRef));
                SetValidatedTypeMeta(info, remoteTypeMeta);
            }

            context.StoreTypeMeta(type, remoteTypeMeta);
            return;
        }

        UserTypeKind declaredKind = info.UserTypeKind.Value;
        bool registerByName = info.RegisterByName;
        bool compatible = context.Compatible;
        if (!IsAllowedWireType(typeId, declaredKind, registerByName, compatible, info.Evolving))
        {
            TypeId expected = ResolveWireTypeId(declaredKind, registerByName, compatible, info.Evolving);
            throw new TypeMismatchException((uint)expected, rawTypeId);
        }

        switch (typeId)
        {
            case TypeId.CompatibleStruct:
            case TypeId.NamedCompatibleStruct:
                {
                    TypeMeta remoteTypeMeta = context.ReadTypeMeta();
                    if (!HasValidatedTypeMeta(info, remoteTypeMeta))
                    {
                        ValidateTypeMeta(
                            remoteTypeMeta,
                            info,
                            declaredKind,
                            registerByName,
                            compatible,
                            typeId);
                        remoteTypeMeta.EnsureAssignedFieldIds(TypeMetaFields(info, context.TrackRef));
                        SetValidatedTypeMeta(info, remoteTypeMeta);
                    }

                    context.StoreTypeMeta(type, remoteTypeMeta);
                    return;
                }
            case TypeId.NamedEnum:
            case TypeId.NamedStruct:
            case TypeId.NamedExt:
            case TypeId.NamedUnion:
                ReadNamedTypeInfo(info, typeId, declaredKind, registerByName, compatible, context);
                return;
            default:
                if (!info.RegisterByName && WireTypeNeedsUserTypeId(typeId))
                {
                    if (!info.UserTypeId.HasValue)
                    {
                        throw new InvalidDataException("missing user type id for id-registered local type");
                    }

                    uint remoteUserTypeId = context.Reader.ReadVarUInt32();
                    if (remoteUserTypeId != info.UserTypeId.Value)
                    {
                        throw new TypeMismatchException(info.UserTypeId.Value, remoteUserTypeId);
                    }
                }

                return;
        }
    }

    private void ReadNamedTypeInfo(
        TypeInfo typeInfo,
        TypeId wireTypeId,
        UserTypeKind declaredKind,
        bool registerByName,
        bool compatible,
        ReadContext context)
    {
        if (compatible)
        {
            TypeMeta remoteTypeMeta = context.ReadTypeMeta();
            if (!HasValidatedTypeMeta(typeInfo, remoteTypeMeta))
            {
                ValidateTypeMeta(
                    remoteTypeMeta,
                    typeInfo,
                    declaredKind,
                    registerByName,
                    compatible,
                    wireTypeId);
                SetValidatedTypeMeta(typeInfo, remoteTypeMeta);
            }

            return;
        }

        MetaString namespaceName = ReadMetaString(
            context,
            MetaStringDecoder.Namespace,
            TypeMetaEncodings.NamespaceMetaStringEncodings);
        MetaString typeName = ReadMetaString(
            context,
            MetaStringDecoder.TypeName,
            TypeMetaEncodings.TypeNameMetaStringEncodings);
        if (!typeInfo.RegisterByName || !typeInfo.NamespaceName.HasValue || !typeInfo.TypeName.HasValue)
        {
            throw new InvalidDataException("received name-registered type info for id-registered local type");
        }

        if (namespaceName.Value != typeInfo.NamespaceName.Value.Value ||
            typeName.Value != typeInfo.TypeName.Value.Value)
        {
            throw new InvalidDataException(
                $"type name mismatch: expected {typeInfo.NamespaceName.Value.Value}::{typeInfo.TypeName.Value.Value}, got {namespaceName.Value}::{typeName.Value}");
        }
    }

    internal static TypeId ResolveWireTypeId(
        UserTypeKind declaredKind,
        bool registerByName,
        bool compatible,
        bool evolving = true)
    {
        if (registerByName)
        {
            return declaredKind switch
            {
                UserTypeKind.Struct => compatible && evolving ? TypeId.NamedCompatibleStruct : TypeId.NamedStruct,
                UserTypeKind.Enum => TypeId.NamedEnum,
                UserTypeKind.Ext => TypeId.NamedExt,
                UserTypeKind.TypedUnion => TypeId.NamedUnion,
                _ => throw new InvalidDataException($"unknown user type kind {declaredKind}"),
            };
        }

        return declaredKind switch
        {
            UserTypeKind.Struct => compatible && evolving ? TypeId.CompatibleStruct : TypeId.Struct,
            UserTypeKind.Enum => TypeId.Enum,
            UserTypeKind.Ext => TypeId.Ext,
            UserTypeKind.TypedUnion => TypeId.TypedUnion,
            _ => throw new InvalidDataException($"unknown user type kind {declaredKind}"),
        };
    }

    private static bool IsKnownTypeId(uint rawTypeId)
    {
        return rawTypeId <= (uint)TypeId.Float64Array;
    }

    private static bool IsAllowedWireType(
        TypeId actualWireType,
        UserTypeKind declaredKind,
        bool registerByName,
        bool compatible,
        bool evolving = true)
    {
        if (declaredKind == UserTypeKind.Struct && compatible)
        {
            return actualWireType is
                TypeId.Struct or
                TypeId.NamedStruct or
                TypeId.CompatibleStruct or
                TypeId.NamedCompatibleStruct;
        }

        TypeId expected = ResolveWireTypeId(declaredKind, registerByName, compatible, evolving);
        return actualWireType == expected;
    }

    private object? ReadRegisteredValue(
        TypeInfo typeInfo,
        ReadContext context,
        TypeMeta? typeMeta)
    {
        if (typeMeta is not null)
        {
            typeMeta.EnsureAssignedFieldIds(TypeMetaFields(typeInfo, context.TrackRef));
            context.StoreTypeMeta(typeInfo.Type, typeMeta);
        }

        return ReadObject(typeInfo, context, RefMode.None, false);
    }

    internal TypeInfo ReadAnyTypeInfo(ReadContext context)
    {
        uint rawTypeId = context.Reader.ReadUInt8();
        if (!IsKnownTypeId(rawTypeId))
        {
            throw new InvalidDataException($"unknown type id {rawTypeId}");
        }

        TypeId wireTypeId = (TypeId)rawTypeId;
        switch (wireTypeId)
        {
            case TypeId.CompatibleStruct:
            case TypeId.NamedCompatibleStruct:
                return ResolveAnyTypeInfoFromMeta(wireTypeId, context.ReadTypeMeta(), context.Compatible);
            case TypeId.NamedEnum:
            case TypeId.NamedStruct:
            case TypeId.NamedExt:
            case TypeId.NamedUnion:
                return ReadNamedAnyTypeInfo(wireTypeId, context);
            case TypeId.Struct:
            case TypeId.Enum:
            case TypeId.Ext:
            case TypeId.TypedUnion:
                return ResolveAnyUserTypeInfo(wireTypeId, context.Reader.ReadVarUInt32(), context.Compatible);
            default:
                return ResolveAnyBuiltInTypeInfo(wireTypeId);
        }
    }

    private TypeInfo ReadNamedAnyTypeInfo(TypeId wireTypeId, ReadContext context)
    {
        if (context.Compatible)
        {
            return ResolveAnyTypeInfoFromMeta(wireTypeId, context.ReadTypeMeta(), compatible: true);
        }

        MetaString namespaceName = ReadMetaString(
            context,
            MetaStringDecoder.Namespace,
            TypeMetaEncodings.NamespaceMetaStringEncodings);
        MetaString typeName = ReadMetaString(
            context,
            MetaStringDecoder.TypeName,
            TypeMetaEncodings.TypeNameMetaStringEncodings);
        return ResolveAnyUserTypeInfo(wireTypeId, namespaceName.Value, typeName.Value, compatible: false);
    }

    internal object? ReadAnyValue(TypeInfo typeInfo, ReadContext context)
    {
        TypeId wireTypeId = typeInfo.WireTypeId
                            ?? throw new InvalidDataException($"missing read wire type for {typeInfo.Type}");
        switch (wireTypeId)
        {
            case TypeId.Int32:
                return context.Reader.ReadInt32();
            case TypeId.Int64:
                return context.Reader.ReadInt64();
            case TypeId.TaggedInt64:
                return context.Reader.ReadTaggedInt64();
            case TypeId.UInt32:
                return context.Reader.ReadUInt32();
            case TypeId.UInt64:
                return context.Reader.ReadUInt64();
            case TypeId.TaggedUInt64:
                return context.Reader.ReadTaggedUInt64();
            case TypeId.Map:
                return DynamicContainerCodec.ReadMapPayload(context);
            case TypeId.Struct:
            case TypeId.Enum:
            case TypeId.Ext:
            case TypeId.TypedUnion:
            case TypeId.NamedStruct:
            case TypeId.NamedEnum:
            case TypeId.NamedExt:
            case TypeId.NamedUnion:
            case TypeId.CompatibleStruct:
            case TypeId.NamedCompatibleStruct:
                return ReadRegisteredValue(typeInfo, context, typeInfo.GetTypeMeta());
            case TypeId.None:
                return null;
            default:
                return ReadDataObject(typeInfo, context);
        }
    }

    private TypeInfo ResolveAnyTypeInfoFromMeta(TypeId wireTypeId, TypeMeta typeMeta, bool compatible)
    {
        TypeInfo typeInfo = typeMeta.RegisterByName
            ? RequireRegisteredTypeInfoByName(typeMeta.NamespaceName.Value, typeMeta.TypeName.Value)
            : typeMeta.UserTypeId.HasValue
                ? RequireRegisteredTypeInfoByUserTypeId(typeMeta.UserTypeId.Value)
                : throw new InvalidDataException("missing user type id in compatible type meta");
        ValidateAnyReadWireType(typeInfo, wireTypeId, compatible);
        return typeInfo.WithWireTypeInfo(wireTypeId, typeMeta);
    }

    private TypeInfo ResolveAnyUserTypeInfo(TypeId wireTypeId, uint userTypeId, bool compatible)
    {
        TypeInfo typeInfo = RequireRegisteredTypeInfoByUserTypeId(userTypeId);
        ValidateAnyReadWireType(typeInfo, wireTypeId, compatible);
        return typeInfo.WithWireTypeInfo(wireTypeId);
    }

    private TypeInfo ResolveAnyUserTypeInfo(TypeId wireTypeId, string namespaceName, string typeName, bool compatible)
    {
        TypeInfo typeInfo = RequireRegisteredTypeInfoByName(namespaceName, typeName);
        ValidateAnyReadWireType(typeInfo, wireTypeId, compatible);
        return typeInfo.WithWireTypeInfo(wireTypeId);
    }

    private void ValidateAnyReadWireType(TypeInfo typeInfo, TypeId wireTypeId, bool compatible)
    {
        if (!typeInfo.UserTypeKind.HasValue)
        {
            return;
        }

        if (!IsAllowedWireType(
                wireTypeId,
                typeInfo.UserTypeKind.Value,
                typeInfo.RegisterByName,
                compatible,
                typeInfo.Evolving))
        {
            throw new InvalidDataException(
                $"wire type {wireTypeId} is not valid for registered type {typeInfo.Type}");
        }
    }

    private TypeInfo ResolveAnyBuiltInTypeInfo(TypeId wireTypeId)
    {
        return wireTypeId switch
        {
            TypeId.Bool => GetTypeInfo<bool>().WithWireTypeInfo(wireTypeId),
            TypeId.Int8 => GetTypeInfo<sbyte>().WithWireTypeInfo(wireTypeId),
            TypeId.Int16 => GetTypeInfo<short>().WithWireTypeInfo(wireTypeId),
            TypeId.Int32 or TypeId.VarInt32 => GetTypeInfo<int>().WithWireTypeInfo(wireTypeId),
            TypeId.Int64 or TypeId.VarInt64 or TypeId.TaggedInt64 => GetTypeInfo<long>().WithWireTypeInfo(wireTypeId),
            TypeId.UInt8 => GetTypeInfo<byte>().WithWireTypeInfo(wireTypeId),
            TypeId.UInt16 => GetTypeInfo<ushort>().WithWireTypeInfo(wireTypeId),
            TypeId.UInt32 or TypeId.VarUInt32 => GetTypeInfo<uint>().WithWireTypeInfo(wireTypeId),
            TypeId.UInt64 or TypeId.VarUInt64 or TypeId.TaggedUInt64 => GetTypeInfo<ulong>().WithWireTypeInfo(wireTypeId),
            TypeId.Float32 => GetTypeInfo<float>().WithWireTypeInfo(wireTypeId),
            TypeId.Float64 => GetTypeInfo<double>().WithWireTypeInfo(wireTypeId),
            TypeId.String => GetTypeInfo<string>().WithWireTypeInfo(wireTypeId),
            TypeId.Date => GetTypeInfo<DateOnly>().WithWireTypeInfo(wireTypeId),
            TypeId.Timestamp => GetTypeInfo<DateTimeOffset>().WithWireTypeInfo(wireTypeId),
            TypeId.Duration => GetTypeInfo<TimeSpan>().WithWireTypeInfo(wireTypeId),
            TypeId.Binary or TypeId.UInt8Array => GetTypeInfo<byte[]>().WithWireTypeInfo(wireTypeId),
            TypeId.BoolArray => GetTypeInfo<bool[]>().WithWireTypeInfo(wireTypeId),
            TypeId.Int8Array => GetTypeInfo<sbyte[]>().WithWireTypeInfo(wireTypeId),
            TypeId.Int16Array => GetTypeInfo<short[]>().WithWireTypeInfo(wireTypeId),
            TypeId.Int32Array => GetTypeInfo<int[]>().WithWireTypeInfo(wireTypeId),
            TypeId.Int64Array => GetTypeInfo<long[]>().WithWireTypeInfo(wireTypeId),
            TypeId.UInt16Array => GetTypeInfo<ushort[]>().WithWireTypeInfo(wireTypeId),
            TypeId.UInt32Array => GetTypeInfo<uint[]>().WithWireTypeInfo(wireTypeId),
            TypeId.UInt64Array => GetTypeInfo<ulong[]>().WithWireTypeInfo(wireTypeId),
            TypeId.Float32Array => GetTypeInfo<float[]>().WithWireTypeInfo(wireTypeId),
            TypeId.Float64Array => GetTypeInfo<double[]>().WithWireTypeInfo(wireTypeId),
            TypeId.List => GetTypeInfo<List<object?>>().WithWireTypeInfo(wireTypeId),
            TypeId.Set => GetTypeInfo<HashSet<object?>>().WithWireTypeInfo(wireTypeId),
            TypeId.Map => GetTypeInfo<NullableKeyDictionary<object, object?>>().WithWireTypeInfo(wireTypeId),
            TypeId.Union => GetTypeInfo<Union>().WithWireTypeInfo(wireTypeId),
            TypeId.None => GetTypeInfo<object?>().WithWireTypeInfo(wireTypeId),
            _ => throw new InvalidDataException($"unsupported dynamic type id {wireTypeId}"),
        };
    }

    private TypeInfo RequireRegisteredTypeInfoByUserTypeId(uint userTypeId)
    {
        if (_byUserTypeId.TryGetValue(userTypeId, out TypeInfo? typeInfo))
        {
            return typeInfo;
        }

        throw new TypeNotRegisteredException($"user_type_id={userTypeId}");
    }

    private TypeInfo RequireRegisteredTypeInfoByName(string namespaceName, string typeName)
    {
        if (_byTypeName.TryGetValue((namespaceName, typeName), out TypeInfo? typeInfo))
        {
            return typeInfo;
        }

        throw new TypeNotRegisteredException($"namespace={namespaceName}, type={typeName}");
    }

    private static byte[] ReadBinary(ReadContext context)
    {
        uint length = context.Reader.ReadVarUInt32();
        return context.Reader.ReadBytes(checked((int)length));
    }

    private static bool[] ReadBoolArray(ReadContext context)
    {
        int count = checked((int)context.Reader.ReadVarUInt32());
        bool[] values = new bool[count];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadUInt8() != 0;
        }

        return values;
    }

    private static sbyte[] ReadInt8Array(ReadContext context)
    {
        int count = checked((int)context.Reader.ReadVarUInt32());
        sbyte[] values = new sbyte[count];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadInt8();
        }

        return values;
    }

    private static short[] ReadInt16Array(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 1) != 0)
        {
            throw new InvalidDataException("int16 array payload size mismatch");
        }

        short[] values = new short[payloadSize / 2];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadInt16();
        }

        return values;
    }

    private static int[] ReadInt32Array(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 3) != 0)
        {
            throw new InvalidDataException("int32 array payload size mismatch");
        }

        int[] values = new int[payloadSize / 4];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadInt32();
        }

        return values;
    }

    private static long[] ReadInt64Array(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 7) != 0)
        {
            throw new InvalidDataException("int64 array payload size mismatch");
        }

        long[] values = new long[payloadSize / 8];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadInt64();
        }

        return values;
    }

    private static ushort[] ReadUInt16Array(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 1) != 0)
        {
            throw new InvalidDataException("uint16 array payload size mismatch");
        }

        ushort[] values = new ushort[payloadSize / 2];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadUInt16();
        }

        return values;
    }

    private static uint[] ReadUInt32Array(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 3) != 0)
        {
            throw new InvalidDataException("uint32 array payload size mismatch");
        }

        uint[] values = new uint[payloadSize / 4];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadUInt32();
        }

        return values;
    }

    private static ulong[] ReadUInt64Array(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 7) != 0)
        {
            throw new InvalidDataException("uint64 array payload size mismatch");
        }

        ulong[] values = new ulong[payloadSize / 8];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadUInt64();
        }

        return values;
    }

    private static float[] ReadFloat32Array(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 3) != 0)
        {
            throw new InvalidDataException("float32 array payload size mismatch");
        }

        float[] values = new float[payloadSize / 4];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadFloat32();
        }

        return values;
    }

    private static double[] ReadFloat64Array(ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if ((payloadSize & 7) != 0)
        {
            throw new InvalidDataException("float64 array payload size mismatch");
        }

        double[] values = new double[payloadSize / 8];
        for (int i = 0; i < values.Length; i++)
        {
            values[i] = context.Reader.ReadFloat64();
        }

        return values;
    }

    private static bool WireTypeNeedsUserTypeId(TypeId typeId)
    {
        return typeId is TypeId.Enum or TypeId.Struct or TypeId.Ext or TypeId.TypedUnion;
    }

    private bool HasValidatedTypeMeta(TypeInfo info, TypeMeta remoteTypeMeta)
    {
        return _validatedTypeMetaByType.TryGetValue(TypeMapKey.Get(info.Type), out TypeMeta? validated) &&
               ReferenceEquals(validated, remoteTypeMeta);
    }

    private void SetValidatedTypeMeta(TypeInfo info, TypeMeta remoteTypeMeta)
    {
        _validatedTypeMetaByType.Set(TypeMapKey.Get(info.Type), remoteTypeMeta);
    }

    private static void ValidateTypeMeta(
        TypeMeta remoteTypeMeta,
        TypeInfo localInfo,
        UserTypeKind declaredKind,
        bool registerByName,
        bool compatible,
        TypeId actualWireTypeId)
    {
        if (remoteTypeMeta.RegisterByName)
        {
            if (!localInfo.RegisterByName || !localInfo.NamespaceName.HasValue || !localInfo.TypeName.HasValue)
            {
                throw new InvalidDataException(
                    "received name-registered compatible metadata for id-registered local type");
            }

            if (remoteTypeMeta.NamespaceName.Value != localInfo.NamespaceName.Value.Value)
            {
                throw new InvalidDataException(
                    $"namespace mismatch: expected {localInfo.NamespaceName.Value.Value}, got {remoteTypeMeta.NamespaceName.Value}");
            }

            if (remoteTypeMeta.TypeName.Value != localInfo.TypeName.Value.Value)
            {
                throw new InvalidDataException(
                    $"type name mismatch: expected {localInfo.TypeName.Value.Value}, got {remoteTypeMeta.TypeName.Value}");
            }
        }
        else
        {
            if (localInfo.RegisterByName)
            {
                throw new InvalidDataException(
                    "received id-registered compatible metadata for name-registered local type");
            }

            if (!remoteTypeMeta.UserTypeId.HasValue)
            {
                throw new InvalidDataException("missing user type id in compatible type metadata");
            }

            if (!localInfo.UserTypeId.HasValue)
            {
                throw new InvalidDataException("missing local user type id metadata for id-registered type");
            }

            if (remoteTypeMeta.UserTypeId.Value != localInfo.UserTypeId.Value)
            {
                throw new TypeMismatchException(localInfo.UserTypeId.Value, remoteTypeMeta.UserTypeId.Value);
            }
        }

        if (remoteTypeMeta.TypeId.HasValue &&
            IsKnownTypeId(remoteTypeMeta.TypeId.Value))
        {
            TypeId remoteWireTypeId = (TypeId)remoteTypeMeta.TypeId.Value;
            if (!IsAllowedWireType(remoteWireTypeId, declaredKind, registerByName, compatible, localInfo.Evolving))
            {
                throw new TypeMismatchException((uint)actualWireTypeId, remoteTypeMeta.TypeId.Value);
            }
        }
    }

    private static void WriteMetaString(
        WriteContext context,
        MetaString value,
        IReadOnlyList<MetaStringEncoding> encodings,
        MetaStringEncoder encoder)
    {
        MetaString normalized = encodings.Contains(value.Encoding)
            ? value
            : encoder.Encode(value.Value, encodings);
        if (!encodings.Contains(normalized.Encoding))
        {
            throw new EncodingException("failed to normalize meta string encoding");
        }

        byte[] bytes = normalized.Bytes;
        (uint index, bool isNew) = context.AssignMetaStringIndexIfAbsent(normalized);
        if (isNew)
        {
            context.Writer.WriteVarUInt32((uint)(bytes.Length << 1));
            if (bytes.Length > 16)
            {
                context.Writer.WriteInt64(unchecked((long)MetaStringHash(normalized)));
            }
            else if (bytes.Length > 0)
            {
                context.Writer.WriteUInt8((byte)normalized.Encoding);
            }

            context.Writer.WriteBytes(bytes);
        }
        else
        {
            context.Writer.WriteVarUInt32(((index + 1) << 1) | 1);
        }
    }

    private static MetaString ReadMetaString(
        ReadContext context,
        MetaStringDecoder decoder,
        IReadOnlyList<MetaStringEncoding> encodings)
    {
        uint header = context.Reader.ReadVarUInt32();
        int length = checked((int)(header >> 1));
        bool isRef = (header & 1) == 1;
        if (isRef)
        {
            int index = length - 1;
            MetaString? cached = context.GetReadMetaString(index);
            if (cached is null)
            {
                throw new InvalidDataException($"unknown meta string ref index {index}");
            }

            return cached.Value;
        }

        MetaString value;
        if (length == 0)
        {
            value = MetaString.Empty(decoder.SpecialChar1, decoder.SpecialChar2);
        }
        else
        {
            MetaStringEncoding encoding;
            if (length > 16)
            {
                long hash = context.Reader.ReadInt64();
                byte rawEncoding = unchecked((byte)(hash & 0xFF));
                encoding = (MetaStringEncoding)rawEncoding;
            }
            else
            {
                encoding = (MetaStringEncoding)context.Reader.ReadUInt8();
            }

            if (!encodings.Contains(encoding))
            {
                throw new InvalidDataException($"meta string encoding {encoding} not allowed in this context");
            }

            byte[] bytes = context.Reader.ReadBytes(length);
            value = decoder.Decode(bytes, encoding);
        }

        context.AppendReadMetaString(value);
        return value;
    }

    private static ulong MetaStringHash(MetaString metaString)
    {
        (ulong h1, _) = MurmurHash3.X64_128(metaString.Bytes, 47);
        long hash = unchecked((long)h1);
        if (hash != long.MinValue)
        {
            hash = Math.Abs(hash);
        }

        ulong result = unchecked((ulong)hash);
        if (result == 0)
        {
            result += 256;
        }

        result &= 0xffffffffffffff00;
        result |= (byte)metaString.Encoding;
        return result;
    }

    private TypeInfo CreateBindingCore(Type type)
    {
        if (GeneratedFactories.TryGetValue(type, out Func<TypeResolver, TypeInfo>? generatedFactory))
        {
            return generatedFactory(this);
        }

        if (type == typeof(bool))
        {
            return TypeInfo.Create(type, new BoolSerializer());
        }

        if (type == typeof(sbyte))
        {
            return TypeInfo.Create(type, new Int8Serializer());
        }

        if (type == typeof(short))
        {
            return TypeInfo.Create(type, new Int16Serializer());
        }

        if (type == typeof(int))
        {
            return TypeInfo.Create(type, new Int32Serializer());
        }

        if (type == typeof(long))
        {
            return TypeInfo.Create(type, new Int64Serializer());
        }

        if (type == typeof(byte))
        {
            return TypeInfo.Create(type, new UInt8Serializer());
        }

        if (type == typeof(ushort))
        {
            return TypeInfo.Create(type, new UInt16Serializer());
        }

        if (type == typeof(uint))
        {
            return TypeInfo.Create(type, new UInt32Serializer());
        }

        if (type == typeof(ulong))
        {
            return TypeInfo.Create(type, new UInt64Serializer());
        }

        if (type == typeof(float))
        {
            return TypeInfo.Create(type, new Float32Serializer());
        }

        if (type == typeof(double))
        {
            return TypeInfo.Create(type, new Float64Serializer());
        }

        if (type == typeof(string))
        {
            return TypeInfo.Create(type, new StringSerializer());
        }

        if (type == typeof(byte[]))
        {
            return TypeInfo.Create(type, new BinarySerializer());
        }

        if (type == typeof(bool[]))
        {
            return TypeInfo.Create(type, new BoolArraySerializer());
        }

        if (type == typeof(sbyte[]))
        {
            return TypeInfo.Create(type, new Int8ArraySerializer());
        }

        if (type == typeof(short[]))
        {
            return TypeInfo.Create(type, new Int16ArraySerializer());
        }

        if (type == typeof(int[]))
        {
            return TypeInfo.Create(type, new Int32ArraySerializer());
        }

        if (type == typeof(long[]))
        {
            return TypeInfo.Create(type, new Int64ArraySerializer());
        }

        if (type == typeof(ushort[]))
        {
            return TypeInfo.Create(type, new UInt16ArraySerializer());
        }

        if (type == typeof(uint[]))
        {
            return TypeInfo.Create(type, new UInt32ArraySerializer());
        }

        if (type == typeof(ulong[]))
        {
            return TypeInfo.Create(type, new UInt64ArraySerializer());
        }

        if (type == typeof(float[]))
        {
            return TypeInfo.Create(type, new Float32ArraySerializer());
        }

        if (type == typeof(double[]))
        {
            return TypeInfo.Create(type, new Float64ArraySerializer());
        }

        if (type == typeof(DateOnly))
        {
            return TypeInfo.Create(type, new DateOnlySerializer());
        }

        if (type == typeof(DateTimeOffset))
        {
            return TypeInfo.Create(type, new DateTimeOffsetSerializer());
        }

        if (type == typeof(DateTime))
        {
            return TypeInfo.Create(type, new DateTimeSerializer());
        }

        if (type == typeof(TimeSpan))
        {
            return TypeInfo.Create(type, new TimeSpanSerializer());
        }

        if (type == typeof(List<bool>))
        {
            return TypeInfo.Create(type, new ListBoolSerializer());
        }

        if (type == typeof(List<sbyte>))
        {
            return TypeInfo.Create(type, new ListInt8Serializer());
        }

        if (type == typeof(List<short>))
        {
            return TypeInfo.Create(type, new ListInt16Serializer());
        }

        if (type == typeof(List<int>))
        {
            return TypeInfo.Create(type, new ListIntSerializer());
        }

        if (type == typeof(List<long>))
        {
            return TypeInfo.Create(type, new ListLongSerializer());
        }

        if (type == typeof(List<byte>))
        {
            return TypeInfo.Create(type, new ListUInt8Serializer());
        }

        if (type == typeof(List<ushort>))
        {
            return TypeInfo.Create(type, new ListUInt16Serializer());
        }

        if (type == typeof(List<uint>))
        {
            return TypeInfo.Create(type, new ListUIntSerializer());
        }

        if (type == typeof(List<ulong>))
        {
            return TypeInfo.Create(type, new ListULongSerializer());
        }

        if (type == typeof(List<float>))
        {
            return TypeInfo.Create(type, new ListFloatSerializer());
        }

        if (type == typeof(List<double>))
        {
            return TypeInfo.Create(type, new ListDoubleSerializer());
        }

        if (type == typeof(List<string>))
        {
            return TypeInfo.Create(type, new ListStringSerializer());
        }

        if (type == typeof(List<DateOnly>))
        {
            return TypeInfo.Create(type, new ListDateOnlySerializer());
        }

        if (type == typeof(List<DateTimeOffset>))
        {
            return TypeInfo.Create(type, new ListDateTimeOffsetSerializer());
        }

        if (type == typeof(List<DateTime>))
        {
            return TypeInfo.Create(type, new ListDateTimeSerializer());
        }

        if (type == typeof(List<TimeSpan>))
        {
            return TypeInfo.Create(type, new ListTimeSpanSerializer());
        }

        if (type == typeof(HashSet<sbyte>))
        {
            return TypeInfo.Create(type, new SetInt8Serializer());
        }

        if (type == typeof(HashSet<short>))
        {
            return TypeInfo.Create(type, new SetInt16Serializer());
        }

        if (type == typeof(HashSet<int>))
        {
            return TypeInfo.Create(type, new SetIntSerializer());
        }

        if (type == typeof(HashSet<long>))
        {
            return TypeInfo.Create(type, new SetLongSerializer());
        }

        if (type == typeof(HashSet<byte>))
        {
            return TypeInfo.Create(type, new SetUInt8Serializer());
        }

        if (type == typeof(HashSet<ushort>))
        {
            return TypeInfo.Create(type, new SetUInt16Serializer());
        }

        if (type == typeof(HashSet<uint>))
        {
            return TypeInfo.Create(type, new SetUIntSerializer());
        }

        if (type == typeof(HashSet<ulong>))
        {
            return TypeInfo.Create(type, new SetULongSerializer());
        }

        if (type == typeof(HashSet<float>))
        {
            return TypeInfo.Create(type, new SetFloatSerializer());
        }

        if (type == typeof(HashSet<double>))
        {
            return TypeInfo.Create(type, new SetDoubleSerializer());
        }

        TypeInfo? primitiveCollectionTypeInfo = TryCreatePrimitiveCollectionTypeInfo(type);
        if (primitiveCollectionTypeInfo is not null)
        {
            return primitiveCollectionTypeInfo;
        }

        TypeInfo? primitiveDictionaryTypeInfo = TryCreatePrimitiveDictionaryTypeInfo(type);
        if (primitiveDictionaryTypeInfo is not null)
        {
            return primitiveDictionaryTypeInfo;
        }

        if (type == typeof(object))
        {
            return TypeInfo.Create(type, new DynamicAnyObjectSerializer());
        }

        if (typeof(Union).IsAssignableFrom(type))
        {
            Type serializerType = typeof(UnionSerializer<>).MakeGenericType(type);
            return CreateTypeInfo(type, serializerType);
        }

        if (type.IsEnum)
        {
            Type serializerType = typeof(EnumSerializer<>).MakeGenericType(type);
            return CreateTypeInfo(type, serializerType);
        }

        if (type.IsArray)
        {
            Type elementType = type.GetElementType()!;
            Type serializerType = typeof(ArraySerializer<>).MakeGenericType(elementType);
            return CreateTypeInfo(type, serializerType);
        }

        if (type.IsGenericType)
        {
            Type genericType = type.GetGenericTypeDefinition();
            Type[] genericArgs = type.GetGenericArguments();
            if (genericType == typeof(Nullable<>))
            {
                return CreateNullableTypeInfo(genericArgs[0]);
            }

            if (genericType == typeof(List<>))
            {
                Type serializerType = typeof(ListSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateTypeInfo(type, serializerType);
            }

            if (genericType == typeof(HashSet<>))
            {
                Type serializerType = typeof(SetSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateTypeInfo(type, serializerType);
            }

            if (genericType == typeof(SortedSet<>))
            {
                Type serializerType = typeof(SortedSetSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateTypeInfo(type, serializerType);
            }

            if (genericType == typeof(ImmutableHashSet<>))
            {
                Type serializerType = typeof(ImmutableHashSetSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateTypeInfo(type, serializerType);
            }

            if (genericType == typeof(LinkedList<>))
            {
                Type serializerType = typeof(LinkedListSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateTypeInfo(type, serializerType);
            }

            if (genericType == typeof(Queue<>))
            {
                Type serializerType = typeof(QueueSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateTypeInfo(type, serializerType);
            }

            if (genericType == typeof(Stack<>))
            {
                Type serializerType = typeof(StackSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateTypeInfo(type, serializerType);
            }

            if (genericType == typeof(Dictionary<,>))
            {
                Type serializerType = typeof(DictionarySerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return CreateTypeInfo(type, serializerType);
            }

            if (genericType == typeof(SortedDictionary<,>))
            {
                Type serializerType = typeof(SortedDictionarySerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return CreateTypeInfo(type, serializerType);
            }

            if (genericType == typeof(SortedList<,>))
            {
                Type serializerType = typeof(SortedListSerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return CreateTypeInfo(type, serializerType);
            }

            if (genericType == typeof(ConcurrentDictionary<,>))
            {
                Type serializerType = typeof(ConcurrentDictionarySerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return CreateTypeInfo(type, serializerType);
            }

            if (genericType == typeof(NullableKeyDictionary<,>))
            {
                Type serializerType = typeof(NullableKeyDictionarySerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return CreateTypeInfo(type, serializerType);
            }
        }

        throw new TypeNotRegisteredException($"No serializer available for {type}");
    }

    private TypeInfo? TryCreatePrimitiveCollectionTypeInfo(Type type)
    {
        if (!type.IsGenericType)
        {
            return null;
        }

        Type genericType = type.GetGenericTypeDefinition();
        Type elementType = type.GetGenericArguments()[0];
        if ((genericType == typeof(LinkedList<>) ||
             genericType == typeof(Queue<>) ||
             genericType == typeof(Stack<>)) &&
            PrimitiveListLikeCollectionCodecs.TryGetValue(TypeMapKey.Get(elementType), out Type? listLikeCodec))
        {
            Type serializerType = genericType == typeof(LinkedList<>)
                ? typeof(PrimitiveLinkedListSerializer<,>).MakeGenericType(elementType, listLikeCodec)
                : genericType == typeof(Queue<>)
                    ? typeof(PrimitiveQueueSerializer<,>).MakeGenericType(elementType, listLikeCodec)
                    : typeof(PrimitiveStackSerializer<,>).MakeGenericType(elementType, listLikeCodec);
            return CreateTypeInfo(type, serializerType);
        }

        if ((genericType == typeof(SortedSet<>) ||
             genericType == typeof(ImmutableHashSet<>)) &&
            PrimitiveSetCollectionCodecs.TryGetValue(TypeMapKey.Get(elementType), out Type? setCodec))
        {
            Type serializerType = genericType == typeof(SortedSet<>)
                ? typeof(PrimitiveSortedSetSerializer<,>).MakeGenericType(elementType, setCodec)
                : typeof(PrimitiveImmutableHashSetSerializer<,>).MakeGenericType(elementType, setCodec);
            return CreateTypeInfo(type, serializerType);
        }

        return null;
    }

    private TypeInfo? TryCreatePrimitiveDictionaryTypeInfo(Type type)
    {
        if (!type.IsGenericType)
        {
            return null;
        }

        Type genericType = type.GetGenericTypeDefinition();
        if (genericType != typeof(Dictionary<,>) &&
            genericType != typeof(SortedDictionary<,>) &&
            genericType != typeof(SortedList<,>) &&
            genericType != typeof(ConcurrentDictionary<,>))
        {
            return null;
        }

        Type[] genericArgs = type.GetGenericArguments();
        Type keyType = genericArgs[0];
        Type valueType = genericArgs[1];

        if (keyType == typeof(string) &&
            PrimitiveStringKeyDictionaryCodecs.TryGetValue(TypeMapKey.Get(valueType), out Type? valueCodecType))
        {
            Type serializerType = genericType == typeof(Dictionary<,>)
                ? typeof(PrimitiveStringKeyDictionarySerializer<,>).MakeGenericType(valueType, valueCodecType)
                : genericType == typeof(SortedDictionary<,>)
                    ? typeof(PrimitiveStringKeySortedDictionarySerializer<,>).MakeGenericType(valueType, valueCodecType)
                    : genericType == typeof(SortedList<,>)
                        ? typeof(PrimitiveStringKeySortedListSerializer<,>).MakeGenericType(valueType, valueCodecType)
                        : typeof(PrimitiveStringKeyConcurrentDictionarySerializer<,>).MakeGenericType(valueType, valueCodecType);
            return CreateTypeInfo(type, serializerType);
        }

        if (keyType == valueType &&
            PrimitiveSameTypeDictionaryCodecs.TryGetValue(TypeMapKey.Get(valueType), out Type? sameTypeCodec))
        {
            Type serializerType = genericType == typeof(Dictionary<,>)
                ? typeof(PrimitiveSameTypeDictionarySerializer<,>).MakeGenericType(valueType, sameTypeCodec)
                : genericType == typeof(SortedDictionary<,>)
                    ? typeof(PrimitiveSameTypeSortedDictionarySerializer<,>).MakeGenericType(valueType, sameTypeCodec)
                    : genericType == typeof(SortedList<,>)
                        ? typeof(PrimitiveSameTypeSortedListSerializer<,>).MakeGenericType(valueType, sameTypeCodec)
                        : typeof(PrimitiveSameTypeConcurrentDictionarySerializer<,>).MakeGenericType(valueType, sameTypeCodec);
            return CreateTypeInfo(type, serializerType);
        }

        return null;
    }

    private TypeInfo CreateTypeInfo(Type expectedType, Type serializerType)
    {
        object serializer;
        try
        {
            serializer = Activator.CreateInstance(serializerType)
                ?? throw new InvalidDataException($"failed to create serializer for {serializerType}");
        }
        catch (Exception ex)
        {
            throw new InvalidDataException($"failed to create serializer for {serializerType}: {ex.Message}");
        }

        return CreateTypeInfo(expectedType, serializer);
    }

    private static TypeInfo CreateTypeInfo(Type expectedType, object serializer)
    {
        Type? valueType = ResolveSerializerValueType(serializer.GetType());
        if (valueType is null)
        {
            throw new InvalidDataException($"{serializer.GetType()} is not a serializer");
        }

        if (valueType != expectedType)
        {
            throw new InvalidDataException($"serializer type mismatch for {expectedType}, got {valueType}");
        }

        MethodInfo createMethod = CreateTypeInfoFromSerializerTypedMethod.MakeGenericMethod(valueType);
        return (TypeInfo)createMethod.Invoke(null, [serializer])!;
    }

    private static TypeInfo CreateTypeInfoFromSerializerTyped<T>(object serializerObject)
    {
        if (serializerObject is not Serializer<T> serializer)
        {
            throw new InvalidDataException($"serializer type mismatch for {typeof(T)}");
        }

        return TypeInfo.Create(typeof(T), serializer);
    }

    private TypeInfo CreateNullableTypeInfo(Type valueType)
    {
        MethodInfo createMethod = CreateNullableSerializerTypeInfoMethod.MakeGenericMethod(valueType);
        return (TypeInfo)createMethod.Invoke(this, null)!;
    }

    private TypeInfo CreateNullableSerializerTypeInfo<T>() where T : struct
    {
        return TypeInfo.Create(typeof(T?), new NullableSerializer<T>());
    }

    private static Type? ResolveSerializerValueType(Type serializerType)
    {
        Type? current = serializerType;
        while (current is not null)
        {
            if (current.IsGenericType && current.GetGenericTypeDefinition() == typeof(Serializer<>))
            {
                return current.GetGenericArguments()[0];
            }

            current = current.BaseType;
        }

        return null;
    }

    private static MetaString ReadMetaString(ByteReader reader, MetaStringDecoder decoder, IReadOnlyList<MetaStringEncoding> encodings)
    {
        byte header = reader.ReadUInt8();
        int encodingIndex = header & 0b11;
        if (encodingIndex >= encodings.Count)
        {
            throw new InvalidDataException("invalid meta string encoding index");
        }

        int length = header >> 2;
        if (length >= 0b11_1111)
        {
            length = 0b11_1111 + (int)reader.ReadVarUInt32();
        }

        byte[] bytes = reader.ReadBytes(length);
        return decoder.Decode(bytes, encodings[encodingIndex]);
    }
}
