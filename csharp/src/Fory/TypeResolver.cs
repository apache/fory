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
using System.Linq.Expressions;

namespace Apache.Fory;

internal sealed record RegisteredTypeInfo(
    uint? UserTypeId,
    TypeId Kind,
    bool RegisterByName,
    MetaString? NamespaceName,
    MetaString TypeName,
    Serializer Serializer);

internal enum DynamicRegistrationMode
{
    IdOnly,
    NameOnly,
    Mixed,
}

internal readonly record struct TypeNameKey(string NamespaceName, string TypeName);

internal sealed class TypeReader
{
    public required TypeId Kind { get; init; }
    public required Func<ReadContext, object?> Reader { get; init; }
    public required Func<ReadContext, TypeMeta, object?> CompatibleReader { get; init; }
}

public sealed class TypeResolver
{
    private static readonly ConcurrentDictionary<Type, Func<Serializer>> GeneratedFactories = new();
    private static readonly ConcurrentDictionary<Type, Func<Serializer>> SerializerConstructors = new();
    private static readonly ConcurrentDictionary<Type, Serializer> SharedBindings = new();
    private static readonly ConcurrentDictionary<Type, TypeInfo> SharedTypeInfos = new();
    private static int _sharedCacheVersion;

    private readonly Dictionary<Type, RegisteredTypeInfo> _byType = [];
    private readonly Dictionary<uint, TypeReader> _byUserTypeId = [];
    private readonly Dictionary<TypeNameKey, TypeReader> _byTypeName = [];
    private readonly Dictionary<TypeId, DynamicRegistrationMode> _registrationModeByKind = [];

    private readonly ConcurrentDictionary<Type, Serializer> _serializerBindings = new();
    private readonly ConcurrentDictionary<Type, TypeInfo> _typeInfos = new();
    private int _cacheVersion;

    private static class SharedTypeInfoCache<T>
    {
        public static int Version = -1;
        public static TypeInfo? Cached;
    }

    private static class TypeInfoCache<T>
    {
        public static int Version = -1;
        public static TypeResolver? Resolver;
        public static TypeInfo? Cached;
    }

    private static class SerializerCache<T>
    {
        public static int Version = -1;
        public static TypeResolver? Resolver;
        public static Serializer<T>? Cached;
    }

    public static void RegisterGenerated<T, TSerializer>()
        where TSerializer : Serializer<T>, new()
    {
        Type type = typeof(T);
        GeneratedFactories[type] = CreateSerializer<TSerializer>;
        SharedBindings.TryRemove(type, out _);
        SharedTypeInfos.TryRemove(type, out _);
        unchecked
        {
            _sharedCacheVersion += 1;
        }
    }

    public static TypeId StaticTypeIdOf<T>()
    {
        return StaticTypeInfoOf<T>().StaticTypeId;
    }

    public static bool IsReferenceTrackableTypeOf<T>()
    {
        return StaticTypeInfoOf<T>().IsReferenceTrackableType;
    }

    public static IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFieldsOf<T>(bool trackRef)
    {
        return StaticTypeInfoOf<T>().Serializer.CompatibleTypeMetaFields(trackRef);
    }

    public static TypeInfo StaticTypeInfoOf<T>()
    {
        if (SharedTypeInfoCache<T>.Version == _sharedCacheVersion &&
            SharedTypeInfoCache<T>.Cached is not null)
        {
            return SharedTypeInfoCache<T>.Cached;
        }

        TypeInfo typeInfo = GetSharedTypeInfo(typeof(T));
        SharedTypeInfoCache<T>.Cached = typeInfo;
        SharedTypeInfoCache<T>.Version = _sharedCacheVersion;
        return typeInfo;
    }

    public Serializer<T> GetSerializer<T>()
    {
        if (SerializerCache<T>.Version == _cacheVersion &&
            ReferenceEquals(SerializerCache<T>.Resolver, this) &&
            SerializerCache<T>.Cached is not null)
        {
            return SerializerCache<T>.Cached;
        }

        Serializer<T> typedSerializer = GetTypeInfo<T>().Serializer.RequireSerializer<T>();
        SerializerCache<T>.Resolver = this;
        SerializerCache<T>.Version = _cacheVersion;
        SerializerCache<T>.Cached = typedSerializer;
        return typedSerializer;
    }

    public TypeInfo GetTypeInfo(Type type)
    {
        return _typeInfos.GetOrAdd(type, t => new TypeInfo(t, GetBinding(t)));
    }

    public TypeInfo GetTypeInfo<T>()
    {
        if (TypeInfoCache<T>.Version == _cacheVersion &&
            ReferenceEquals(TypeInfoCache<T>.Resolver, this) &&
            TypeInfoCache<T>.Cached is not null)
        {
            return TypeInfoCache<T>.Cached;
        }

        TypeInfo typeInfo = GetTypeInfo(typeof(T));
        TypeInfoCache<T>.Resolver = this;
        TypeInfoCache<T>.Version = _cacheVersion;
        TypeInfoCache<T>.Cached = typeInfo;
        return typeInfo;
    }

    internal Serializer GetBinding(Type type)
    {
        return _serializerBindings.GetOrAdd(type, CreateBindingCore);
    }

    internal Serializer RegisterCustom<T, TSerializer>()
        where TSerializer : Serializer<T>, new()
    {
        Serializer serializerBinding = CreateSerializer<TSerializer>();
        RegisterCustom(typeof(T), serializerBinding);
        return serializerBinding;
    }

    internal void RegisterCustom(Type type, Serializer serializerBinding)
    {
        _serializerBindings[type] = serializerBinding;
        _typeInfos.TryRemove(type, out _);
        unchecked
        {
            _cacheVersion += 1;
        }
    }

    internal void Register(Type type, uint id, Serializer? explicitSerializer = null)
    {
        Serializer serializer = explicitSerializer ?? GetBinding(type);
        RegisteredTypeInfo info = new(
            id,
            serializer.StaticTypeId,
            false,
            null,
            MetaString.Empty('$', '_'),
            serializer);
        _byType[type] = info;
        MarkRegistrationMode(info.Kind, false);
        _byUserTypeId[id] = new TypeReader
        {
            Kind = serializer.StaticTypeId,
            Reader = context => serializer.ReadObject(ref context, RefMode.None, false),
            CompatibleReader = (context, typeMeta) =>
            {
                context.PushCompatibleTypeMeta(type, typeMeta);
                return serializer.ReadObject(ref context, RefMode.None, false);
            },
        };
    }

    internal void Register(Type type, string namespaceName, string typeName, Serializer? explicitSerializer = null)
    {
        Serializer serializer = explicitSerializer ?? GetBinding(type);
        MetaString namespaceMeta = MetaStringEncoder.Namespace.Encode(namespaceName, TypeMetaEncodings.NamespaceMetaStringEncodings);
        MetaString typeNameMeta = MetaStringEncoder.TypeName.Encode(typeName, TypeMetaEncodings.TypeNameMetaStringEncodings);
        RegisteredTypeInfo info = new(
            null,
            serializer.StaticTypeId,
            true,
            namespaceMeta,
            typeNameMeta,
            serializer);
        _byType[type] = info;
        MarkRegistrationMode(info.Kind, true);
        _byTypeName[new TypeNameKey(namespaceName, typeName)] = new TypeReader
        {
            Kind = serializer.StaticTypeId,
            Reader = context => serializer.ReadObject(ref context, RefMode.None, false),
            CompatibleReader = (context, typeMeta) =>
            {
                context.PushCompatibleTypeMeta(type, typeMeta);
                return serializer.ReadObject(ref context, RefMode.None, false);
            },
        };
    }

    internal RegisteredTypeInfo? RegisteredTypeInfo(Type type)
    {
        return _byType.TryGetValue(type, out RegisteredTypeInfo? info) ? info : null;
    }

    internal RegisteredTypeInfo RequireRegisteredTypeInfo(Type type)
    {
        if (_byType.TryGetValue(type, out RegisteredTypeInfo? info))
        {
            return info;
        }

        throw new TypeNotRegisteredException($"{type} is not registered");
    }

    internal void WriteTypeInfo(Type type, Serializer serializer, ref WriteContext context)
    {
        TypeId staticTypeId = serializer.StaticTypeId;
        if (!staticTypeId.IsUserTypeKind())
        {
            context.Writer.WriteUInt8((byte)staticTypeId);
            return;
        }

        RegisteredTypeInfo info = RequireRegisteredTypeInfo(type);
        TypeId wireTypeId = ResolveWireTypeId(info.Kind, info.RegisterByName, context.Compatible);
        context.Writer.WriteUInt8((byte)wireTypeId);
        switch (wireTypeId)
        {
            case TypeId.CompatibleStruct:
            case TypeId.NamedCompatibleStruct:
            {
                TypeMeta typeMeta = BuildCompatibleTypeMeta(info, wireTypeId, context.TrackRef);
                context.WriteCompatibleTypeMeta(type, typeMeta);
                return;
            }
            case TypeId.NamedEnum:
            case TypeId.NamedStruct:
            case TypeId.NamedExt:
            case TypeId.NamedUnion:
            {
                if (context.Compatible)
                {
                    TypeMeta typeMeta = BuildCompatibleTypeMeta(info, wireTypeId, context.TrackRef);
                    context.WriteCompatibleTypeMeta(type, typeMeta);
                }
                else
                {
                    if (info.NamespaceName is null)
                    {
                        throw new InvalidDataException("missing namespace metadata for name-registered type");
                    }

                    WriteMetaString(
                        ref context,
                        info.NamespaceName.Value,
                        TypeMetaEncodings.NamespaceMetaStringEncodings,
                        MetaStringEncoder.Namespace);
                    WriteMetaString(
                        ref context,
                        info.TypeName,
                        TypeMetaEncodings.TypeNameMetaStringEncodings,
                        MetaStringEncoder.TypeName);
                }

                return;
            }
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

    internal void ReadTypeInfo(Type type, Serializer serializer, ref ReadContext context)
    {
        uint rawTypeId = context.Reader.ReadVarUInt32();
        if (!Enum.IsDefined(typeof(TypeId), rawTypeId))
        {
            throw new InvalidDataException($"unknown type id {rawTypeId}");
        }

        TypeId typeId = (TypeId)rawTypeId;
        TypeId staticTypeId = serializer.StaticTypeId;
        if (!staticTypeId.IsUserTypeKind())
        {
            if (typeId != staticTypeId)
            {
                throw new TypeMismatchException((uint)staticTypeId, rawTypeId);
            }

            return;
        }

        RegisteredTypeInfo info = RequireRegisteredTypeInfo(type);
        HashSet<TypeId> allowed = AllowedWireTypeIds(info.Kind, info.RegisterByName, context.Compatible);
        if (!allowed.Contains(typeId))
        {
            uint expected = 0;
            foreach (TypeId allowedType in allowed)
            {
                expected = (uint)allowedType;
                break;
            }

            throw new TypeMismatchException(expected, rawTypeId);
        }

        switch (typeId)
        {
            case TypeId.CompatibleStruct:
            case TypeId.NamedCompatibleStruct:
            {
                TypeMeta remoteTypeMeta = context.ReadCompatibleTypeMeta();
                ValidateCompatibleTypeMeta(remoteTypeMeta, info, allowed, typeId);
                context.PushCompatibleTypeMeta(type, remoteTypeMeta);
                return;
            }
            case TypeId.NamedEnum:
            case TypeId.NamedStruct:
            case TypeId.NamedExt:
            case TypeId.NamedUnion:
            {
                if (context.Compatible)
                {
                    TypeMeta remoteTypeMeta = context.ReadCompatibleTypeMeta();
                    ValidateCompatibleTypeMeta(remoteTypeMeta, info, allowed, typeId);
                    if (typeId == TypeId.NamedStruct)
                    {
                        context.PushCompatibleTypeMeta(type, remoteTypeMeta);
                    }
                }
                else
                {
                    MetaString namespaceName = ReadMetaString(
                        ref context,
                        MetaStringDecoder.Namespace,
                        TypeMetaEncodings.NamespaceMetaStringEncodings);
                    MetaString typeName = ReadMetaString(
                        ref context,
                        MetaStringDecoder.TypeName,
                        TypeMetaEncodings.TypeNameMetaStringEncodings);
                    if (!info.RegisterByName || info.NamespaceName is null)
                    {
                        throw new InvalidDataException("received name-registered type info for id-registered local type");
                    }

                    if (namespaceName.Value != info.NamespaceName.Value.Value || typeName.Value != info.TypeName.Value)
                    {
                        throw new InvalidDataException(
                            $"type name mismatch: expected {info.NamespaceName.Value.Value}::{info.TypeName.Value}, got {namespaceName.Value}::{typeName.Value}");
                    }
                }

                return;
            }
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

    internal static TypeId ResolveWireTypeId(TypeId declaredKind, bool registerByName, bool compatible)
    {
        TypeId baseKind = NormalizeBaseKind(declaredKind);
        if (registerByName)
        {
            return NamedKind(baseKind, compatible);
        }

        return IdKind(baseKind, compatible);
    }

    internal static HashSet<TypeId> AllowedWireTypeIds(TypeId declaredKind, bool registerByName, bool compatible)
    {
        TypeId baseKind = NormalizeBaseKind(declaredKind);
        TypeId expected = ResolveWireTypeId(declaredKind, registerByName, compatible);
        HashSet<TypeId> allowed = [expected];
        if (baseKind == TypeId.Struct && compatible)
        {
            allowed.Add(TypeId.CompatibleStruct);
            allowed.Add(TypeId.NamedCompatibleStruct);
            allowed.Add(TypeId.Struct);
            allowed.Add(TypeId.NamedStruct);
        }

        return allowed;
    }

    public object? ReadByUserTypeId(uint userTypeId, ref ReadContext context, TypeMeta? compatibleTypeMeta = null)
    {
        if (!_byUserTypeId.TryGetValue(userTypeId, out TypeReader? entry))
        {
            throw new TypeNotRegisteredException($"user_type_id={userTypeId}");
        }

        return compatibleTypeMeta is null
            ? entry.Reader(context)
            : entry.CompatibleReader(context, compatibleTypeMeta);
    }

    public object? ReadByTypeName(string namespaceName, string typeName, ref ReadContext context, TypeMeta? compatibleTypeMeta = null)
    {
        if (!_byTypeName.TryGetValue(new TypeNameKey(namespaceName, typeName), out TypeReader? entry))
        {
            throw new TypeNotRegisteredException($"namespace={namespaceName}, type={typeName}");
        }

        return compatibleTypeMeta is null
            ? entry.Reader(context)
            : entry.CompatibleReader(context, compatibleTypeMeta);
    }

    public DynamicTypeInfo ReadDynamicTypeInfo(ref ReadContext context)
    {
        uint rawTypeId = context.Reader.ReadVarUInt32();
        if (!Enum.IsDefined(typeof(TypeId), rawTypeId))
        {
            throw new InvalidDataException($"unknown dynamic type id {rawTypeId}");
        }

        TypeId wireTypeId = (TypeId)rawTypeId;
        switch (wireTypeId)
        {
            case TypeId.CompatibleStruct:
            case TypeId.NamedCompatibleStruct:
            {
                TypeMeta typeMeta = context.ReadCompatibleTypeMeta();
                if (typeMeta.RegisterByName)
                {
                    return new DynamicTypeInfo(wireTypeId, null, typeMeta.NamespaceName, typeMeta.TypeName, typeMeta);
                }

                return new DynamicTypeInfo(wireTypeId, typeMeta.UserTypeId, null, null, typeMeta);
            }
            case TypeId.NamedStruct:
            case TypeId.NamedEnum:
            case TypeId.NamedExt:
            case TypeId.NamedUnion:
            {
                MetaString namespaceName = ReadMetaString(context.Reader, MetaStringDecoder.Namespace, TypeMetaEncodings.NamespaceMetaStringEncodings);
                MetaString typeName = ReadMetaString(context.Reader, MetaStringDecoder.TypeName, TypeMetaEncodings.TypeNameMetaStringEncodings);
                return new DynamicTypeInfo(wireTypeId, null, namespaceName, typeName, null);
            }
            case TypeId.Struct:
            case TypeId.Enum:
            case TypeId.Ext:
            case TypeId.TypedUnion:
            {
                DynamicRegistrationMode mode = DynamicRegistrationModeFor(wireTypeId);
                if (mode == DynamicRegistrationMode.IdOnly)
                {
                    return new DynamicTypeInfo(wireTypeId, context.Reader.ReadVarUInt32(), null, null, null);
                }

                if (mode == DynamicRegistrationMode.NameOnly)
                {
                    MetaString namespaceName = ReadMetaString(context.Reader, MetaStringDecoder.Namespace, TypeMetaEncodings.NamespaceMetaStringEncodings);
                    MetaString typeName = ReadMetaString(context.Reader, MetaStringDecoder.TypeName, TypeMetaEncodings.TypeNameMetaStringEncodings);
                    return new DynamicTypeInfo(wireTypeId, null, namespaceName, typeName, null);
                }

                throw new InvalidDataException($"ambiguous dynamic type registration mode for {wireTypeId}");
            }
            default:
                return new DynamicTypeInfo(wireTypeId, null, null, null, null);
        }
    }

    public object? ReadDynamicValue(DynamicTypeInfo typeInfo, ref ReadContext context)
    {
        switch (typeInfo.WireTypeId)
        {
            case TypeId.Bool:
                return GetSerializer<bool>().Read(ref context, RefMode.None, false);
            case TypeId.Int8:
                return GetSerializer<sbyte>().Read(ref context, RefMode.None, false);
            case TypeId.Int16:
                return GetSerializer<short>().Read(ref context, RefMode.None, false);
            case TypeId.Int32:
                return GetSerializer<ForyInt32Fixed>().Read(ref context, RefMode.None, false);
            case TypeId.VarInt32:
                return GetSerializer<int>().Read(ref context, RefMode.None, false);
            case TypeId.Int64:
                return GetSerializer<ForyInt64Fixed>().Read(ref context, RefMode.None, false);
            case TypeId.VarInt64:
                return GetSerializer<long>().Read(ref context, RefMode.None, false);
            case TypeId.TaggedInt64:
                return GetSerializer<ForyInt64Tagged>().Read(ref context, RefMode.None, false);
            case TypeId.UInt8:
                return GetSerializer<byte>().Read(ref context, RefMode.None, false);
            case TypeId.UInt16:
                return GetSerializer<ushort>().Read(ref context, RefMode.None, false);
            case TypeId.UInt32:
                return GetSerializer<ForyUInt32Fixed>().Read(ref context, RefMode.None, false);
            case TypeId.VarUInt32:
                return GetSerializer<uint>().Read(ref context, RefMode.None, false);
            case TypeId.UInt64:
                return GetSerializer<ForyUInt64Fixed>().Read(ref context, RefMode.None, false);
            case TypeId.VarUInt64:
                return GetSerializer<ulong>().Read(ref context, RefMode.None, false);
            case TypeId.TaggedUInt64:
                return GetSerializer<ForyUInt64Tagged>().Read(ref context, RefMode.None, false);
            case TypeId.Float32:
                return GetSerializer<float>().Read(ref context, RefMode.None, false);
            case TypeId.Float64:
                return GetSerializer<double>().Read(ref context, RefMode.None, false);
            case TypeId.String:
                return GetSerializer<string>().Read(ref context, RefMode.None, false);
            case TypeId.Binary:
            case TypeId.UInt8Array:
                return GetSerializer<byte[]>().Read(ref context, RefMode.None, false);
            case TypeId.BoolArray:
                return GetSerializer<bool[]>().Read(ref context, RefMode.None, false);
            case TypeId.Int8Array:
                return GetSerializer<sbyte[]>().Read(ref context, RefMode.None, false);
            case TypeId.Int16Array:
                return GetSerializer<short[]>().Read(ref context, RefMode.None, false);
            case TypeId.Int32Array:
                return GetSerializer<int[]>().Read(ref context, RefMode.None, false);
            case TypeId.Int64Array:
                return GetSerializer<long[]>().Read(ref context, RefMode.None, false);
            case TypeId.UInt16Array:
                return GetSerializer<ushort[]>().Read(ref context, RefMode.None, false);
            case TypeId.UInt32Array:
                return GetSerializer<uint[]>().Read(ref context, RefMode.None, false);
            case TypeId.UInt64Array:
                return GetSerializer<ulong[]>().Read(ref context, RefMode.None, false);
            case TypeId.Float32Array:
                return GetSerializer<float[]>().Read(ref context, RefMode.None, false);
            case TypeId.Float64Array:
                return GetSerializer<double[]>().Read(ref context, RefMode.None, false);
            case TypeId.List:
                return DynamicContainerCodec.ReadListPayload(ref context);
            case TypeId.Set:
                return DynamicContainerCodec.ReadSetPayload(ref context);
            case TypeId.Map:
                return DynamicContainerCodec.ReadMapPayload(ref context);
            case TypeId.Union:
                return GetSerializer<Union>().Read(ref context, RefMode.None, false);
            case TypeId.Struct:
            case TypeId.Enum:
            case TypeId.Ext:
            case TypeId.TypedUnion:
            {
                if (typeInfo.UserTypeId.HasValue)
                {
                    return ReadByUserTypeId(typeInfo.UserTypeId.Value, ref context);
                }

                if (typeInfo.NamespaceName.HasValue && typeInfo.TypeName.HasValue)
                {
                    return ReadByTypeName(typeInfo.NamespaceName.Value.Value, typeInfo.TypeName.Value.Value, ref context);
                }

                throw new InvalidDataException($"missing dynamic registration info for {typeInfo.WireTypeId}");
            }
            case TypeId.NamedStruct:
            case TypeId.NamedEnum:
            case TypeId.NamedExt:
            case TypeId.NamedUnion:
            {
                if (!typeInfo.NamespaceName.HasValue || !typeInfo.TypeName.HasValue)
                {
                    throw new InvalidDataException($"missing dynamic type name for {typeInfo.WireTypeId}");
                }

                return ReadByTypeName(typeInfo.NamespaceName.Value.Value, typeInfo.TypeName.Value.Value, ref context);
            }
            case TypeId.CompatibleStruct:
            case TypeId.NamedCompatibleStruct:
            {
                if (typeInfo.CompatibleTypeMeta is null)
                {
                    throw new InvalidDataException($"missing compatible type meta for {typeInfo.WireTypeId}");
                }

                TypeMeta compatibleTypeMeta = typeInfo.CompatibleTypeMeta;
                if (compatibleTypeMeta.RegisterByName)
                {
                    return ReadByTypeName(
                        compatibleTypeMeta.NamespaceName.Value,
                        compatibleTypeMeta.TypeName.Value,
                        ref context,
                        compatibleTypeMeta);
                }

                if (!compatibleTypeMeta.UserTypeId.HasValue)
                {
                    throw new InvalidDataException("missing user type id in compatible dynamic type meta");
                }

                return ReadByUserTypeId(compatibleTypeMeta.UserTypeId.Value, ref context, compatibleTypeMeta);
            }
            case TypeId.None:
                return null;
            default:
                throw new InvalidDataException($"unsupported dynamic type id {typeInfo.WireTypeId}");
        }
    }

    private void MarkRegistrationMode(TypeId kind, bool registerByName)
    {
        DynamicRegistrationMode mode = registerByName ? DynamicRegistrationMode.NameOnly : DynamicRegistrationMode.IdOnly;
        if (!_registrationModeByKind.TryGetValue(kind, out DynamicRegistrationMode existing))
        {
            _registrationModeByKind[kind] = mode;
            return;
        }

        if (existing != mode)
        {
            _registrationModeByKind[kind] = DynamicRegistrationMode.Mixed;
        }
    }

    private DynamicRegistrationMode DynamicRegistrationModeFor(TypeId kind)
    {
        if (_registrationModeByKind.TryGetValue(kind, out DynamicRegistrationMode mode))
        {
            return mode;
        }

        throw new TypeNotRegisteredException($"no dynamic registration mode for kind {kind}");
    }

    private static TypeId NormalizeBaseKind(TypeId kind)
    {
        return kind switch
        {
            TypeId.NamedEnum => TypeId.Enum,
            TypeId.CompatibleStruct or TypeId.NamedCompatibleStruct or TypeId.NamedStruct => TypeId.Struct,
            TypeId.NamedExt => TypeId.Ext,
            TypeId.NamedUnion => TypeId.TypedUnion,
            _ => kind,
        };
    }

    private static TypeId NamedKind(TypeId baseKind, bool compatible)
    {
        return baseKind switch
        {
            TypeId.Struct => compatible ? TypeId.NamedCompatibleStruct : TypeId.NamedStruct,
            TypeId.Enum => TypeId.NamedEnum,
            TypeId.Ext => TypeId.NamedExt,
            TypeId.TypedUnion => TypeId.NamedUnion,
            _ => baseKind,
        };
    }

    private static TypeId IdKind(TypeId baseKind, bool compatible)
    {
        return baseKind switch
        {
            TypeId.Struct => compatible ? TypeId.CompatibleStruct : TypeId.Struct,
            _ => baseKind,
        };
    }

    private static bool WireTypeNeedsUserTypeId(TypeId typeId)
    {
        return typeId is TypeId.Enum or TypeId.Struct or TypeId.Ext or TypeId.TypedUnion;
    }

    private static TypeMeta BuildCompatibleTypeMeta(
        RegisteredTypeInfo info,
        TypeId wireTypeId,
        bool trackRef)
    {
        IReadOnlyList<TypeMetaFieldInfo> fields = info.Serializer.CompatibleTypeMetaFields(trackRef);
        bool hasFieldsMeta = fields.Count > 0;
        if (info.RegisterByName)
        {
            if (info.NamespaceName is null)
            {
                throw new InvalidDataException("missing namespace metadata for name-registered type");
            }

            return new TypeMeta(
                (uint)wireTypeId,
                null,
                info.NamespaceName.Value,
                info.TypeName,
                true,
                fields,
                hasFieldsMeta);
        }

        if (!info.UserTypeId.HasValue)
        {
            throw new InvalidDataException("missing user type id metadata for id-registered type");
        }

        return new TypeMeta(
            (uint)wireTypeId,
            info.UserTypeId.Value,
            MetaString.Empty('.', '_'),
            MetaString.Empty('$', '_'),
            false,
            fields,
            hasFieldsMeta);
    }

    private static void ValidateCompatibleTypeMeta(
        TypeMeta remoteTypeMeta,
        RegisteredTypeInfo localInfo,
        HashSet<TypeId> expectedWireTypes,
        TypeId actualWireTypeId)
    {
        if (remoteTypeMeta.RegisterByName)
        {
            if (!localInfo.RegisterByName || localInfo.NamespaceName is null)
            {
                throw new InvalidDataException(
                    "received name-registered compatible metadata for id-registered local type");
            }

            if (remoteTypeMeta.NamespaceName.Value != localInfo.NamespaceName.Value.Value)
            {
                throw new InvalidDataException(
                    $"namespace mismatch: expected {localInfo.NamespaceName.Value.Value}, got {remoteTypeMeta.NamespaceName.Value}");
            }

            if (remoteTypeMeta.TypeName.Value != localInfo.TypeName.Value)
            {
                throw new InvalidDataException(
                    $"type name mismatch: expected {localInfo.TypeName.Value}, got {remoteTypeMeta.TypeName.Value}");
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
            Enum.IsDefined(typeof(TypeId), remoteTypeMeta.TypeId.Value))
        {
            TypeId remoteWireTypeId = (TypeId)remoteTypeMeta.TypeId.Value;
            if (!expectedWireTypes.Contains(remoteWireTypeId))
            {
                throw new TypeMismatchException((uint)actualWireTypeId, remoteTypeMeta.TypeId.Value);
            }
        }
    }

    private static void WriteMetaString(
        ref WriteContext context,
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
        (uint index, bool isNew) = context.MetaStringWriteState.AssignIndexIfAbsent(normalized);
        if (isNew)
        {
            context.Writer.WriteVarUInt32((uint)(bytes.Length << 1));
            if (bytes.Length > 16)
            {
                context.Writer.WriteInt64(unchecked((long)JavaMetaStringHash(normalized)));
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
        ref ReadContext context,
        MetaStringDecoder decoder,
        IReadOnlyList<MetaStringEncoding> encodings)
    {
        uint header = context.Reader.ReadVarUInt32();
        int length = checked((int)(header >> 1));
        bool isRef = (header & 1) == 1;
        if (isRef)
        {
            int index = length - 1;
            MetaString? cached = context.MetaStringReadState.ValueAt(index);
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

        context.MetaStringReadState.Append(value);
        return value;
    }

    private static ulong JavaMetaStringHash(MetaString metaString)
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

    private static Serializer GetSharedBinding(Type type)
    {
        return SharedBindings.GetOrAdd(type, CreateBindingCore);
    }

    private static TypeInfo GetSharedTypeInfo(Type type)
    {
        return SharedTypeInfos.GetOrAdd(type, t => new TypeInfo(t, GetSharedBinding(t)));
    }

    private static Serializer CreateBindingCore(Type type)
    {
        if (GeneratedFactories.TryGetValue(type, out Func<Serializer>? generatedFactory))
        {
            return generatedFactory();
        }

        if (type == typeof(bool))
        {
            return new BoolSerializer();
        }

        if (type == typeof(sbyte))
        {
            return new Int8Serializer();
        }

        if (type == typeof(short))
        {
            return new Int16Serializer();
        }

        if (type == typeof(int))
        {
            return new Int32Serializer();
        }

        if (type == typeof(long))
        {
            return new Int64Serializer();
        }

        if (type == typeof(byte))
        {
            return new UInt8Serializer();
        }

        if (type == typeof(ushort))
        {
            return new UInt16Serializer();
        }

        if (type == typeof(uint))
        {
            return new UInt32Serializer();
        }

        if (type == typeof(ulong))
        {
            return new UInt64Serializer();
        }

        if (type == typeof(float))
        {
            return new Float32Serializer();
        }

        if (type == typeof(double))
        {
            return new Float64Serializer();
        }

        if (type == typeof(string))
        {
            return new StringSerializer();
        }

        if (type == typeof(byte[]))
        {
            return new BinarySerializer();
        }

        if (type == typeof(bool[]))
        {
            return new BoolArraySerializer();
        }

        if (type == typeof(sbyte[]))
        {
            return new Int8ArraySerializer();
        }

        if (type == typeof(short[]))
        {
            return new Int16ArraySerializer();
        }

        if (type == typeof(int[]))
        {
            return new Int32ArraySerializer();
        }

        if (type == typeof(long[]))
        {
            return new Int64ArraySerializer();
        }

        if (type == typeof(ushort[]))
        {
            return new UInt16ArraySerializer();
        }

        if (type == typeof(uint[]))
        {
            return new UInt32ArraySerializer();
        }

        if (type == typeof(ulong[]))
        {
            return new UInt64ArraySerializer();
        }

        if (type == typeof(float[]))
        {
            return new Float32ArraySerializer();
        }

        if (type == typeof(double[]))
        {
            return new Float64ArraySerializer();
        }

        if (type == typeof(DateOnly))
        {
            return new DateOnlySerializer();
        }

        if (type == typeof(DateTimeOffset))
        {
            return new DateTimeOffsetSerializer();
        }

        if (type == typeof(DateTime))
        {
            return new DateTimeSerializer();
        }

        if (type == typeof(TimeSpan))
        {
            return new TimeSpanSerializer();
        }

        if (type == typeof(List<bool>))
        {
            return new ListBoolSerializer();
        }

        if (type == typeof(List<sbyte>))
        {
            return new ListInt8Serializer();
        }

        if (type == typeof(List<short>))
        {
            return new ListInt16Serializer();
        }

        if (type == typeof(List<int>))
        {
            return new ListIntSerializer();
        }

        if (type == typeof(List<long>))
        {
            return new ListLongSerializer();
        }

        if (type == typeof(List<byte>))
        {
            return new ListUInt8Serializer();
        }

        if (type == typeof(List<ushort>))
        {
            return new ListUInt16Serializer();
        }

        if (type == typeof(List<uint>))
        {
            return new ListUIntSerializer();
        }

        if (type == typeof(List<ulong>))
        {
            return new ListULongSerializer();
        }

        if (type == typeof(List<float>))
        {
            return new ListFloatSerializer();
        }

        if (type == typeof(List<double>))
        {
            return new ListDoubleSerializer();
        }

        if (type == typeof(List<string>))
        {
            return new ListStringSerializer();
        }

        if (type == typeof(List<DateOnly>))
        {
            return new ListDateOnlySerializer();
        }

        if (type == typeof(List<DateTimeOffset>))
        {
            return new ListDateTimeOffsetSerializer();
        }

        if (type == typeof(List<DateTime>))
        {
            return new ListDateTimeSerializer();
        }

        if (type == typeof(List<TimeSpan>))
        {
            return new ListTimeSpanSerializer();
        }

        if (type == typeof(HashSet<sbyte>))
        {
            return new SetInt8Serializer();
        }

        if (type == typeof(HashSet<short>))
        {
            return new SetInt16Serializer();
        }

        if (type == typeof(HashSet<int>))
        {
            return new SetIntSerializer();
        }

        if (type == typeof(HashSet<long>))
        {
            return new SetLongSerializer();
        }

        if (type == typeof(HashSet<byte>))
        {
            return new SetUInt8Serializer();
        }

        if (type == typeof(HashSet<ushort>))
        {
            return new SetUInt16Serializer();
        }

        if (type == typeof(HashSet<uint>))
        {
            return new SetUIntSerializer();
        }

        if (type == typeof(HashSet<ulong>))
        {
            return new SetULongSerializer();
        }

        if (type == typeof(HashSet<float>))
        {
            return new SetFloatSerializer();
        }

        if (type == typeof(HashSet<double>))
        {
            return new SetDoubleSerializer();
        }

        if (type == typeof(Dictionary<string, string>))
        {
            return new DictionaryStringStringSerializer();
        }

        if (type == typeof(Dictionary<string, int>))
        {
            return new DictionaryStringIntSerializer();
        }

        if (type == typeof(Dictionary<string, long>))
        {
            return new DictionaryStringLongSerializer();
        }

        if (type == typeof(Dictionary<string, bool>))
        {
            return new DictionaryStringBoolSerializer();
        }

        if (type == typeof(Dictionary<string, double>))
        {
            return new DictionaryStringDoubleSerializer();
        }

        if (type == typeof(Dictionary<string, float>))
        {
            return new DictionaryStringFloatSerializer();
        }

        if (type == typeof(Dictionary<string, uint>))
        {
            return new DictionaryStringUIntSerializer();
        }

        if (type == typeof(Dictionary<string, ulong>))
        {
            return new DictionaryStringULongSerializer();
        }

        if (type == typeof(Dictionary<string, sbyte>))
        {
            return new DictionaryStringInt8Serializer();
        }

        if (type == typeof(Dictionary<string, short>))
        {
            return new DictionaryStringInt16Serializer();
        }

        if (type == typeof(Dictionary<string, ushort>))
        {
            return new DictionaryStringUInt16Serializer();
        }

        if (type == typeof(Dictionary<int, int>))
        {
            return new DictionaryIntIntSerializer();
        }

        if (type == typeof(Dictionary<long, long>))
        {
            return new DictionaryLongLongSerializer();
        }

        if (type == typeof(Dictionary<uint, uint>))
        {
            return new DictionaryUIntUIntSerializer();
        }

        if (type == typeof(Dictionary<ulong, ulong>))
        {
            return new DictionaryULongULongSerializer();
        }

        if (type == typeof(ForyInt32Fixed))
        {
            return new ForyInt32FixedSerializer();
        }

        if (type == typeof(ForyInt64Fixed))
        {
            return new ForyInt64FixedSerializer();
        }

        if (type == typeof(ForyInt64Tagged))
        {
            return new ForyInt64TaggedSerializer();
        }

        if (type == typeof(ForyUInt32Fixed))
        {
            return new ForyUInt32FixedSerializer();
        }

        if (type == typeof(ForyUInt64Fixed))
        {
            return new ForyUInt64FixedSerializer();
        }

        if (type == typeof(ForyUInt64Tagged))
        {
            return new ForyUInt64TaggedSerializer();
        }

        if (type == typeof(object))
        {
            return new DynamicAnyObjectSerializer();
        }

        if (typeof(Union).IsAssignableFrom(type))
        {
            Type serializerType = typeof(UnionSerializer<>).MakeGenericType(type);
            return CreateSerializer(serializerType);
        }

        if (type.IsEnum)
        {
            Type serializerType = typeof(EnumSerializer<>).MakeGenericType(type);
            return CreateSerializer(serializerType);
        }

        if (type.IsArray)
        {
            Type elementType = type.GetElementType()!;
            Type serializerType = typeof(ArraySerializer<>).MakeGenericType(elementType);
            return CreateSerializer(serializerType);
        }

        if (type.IsGenericType)
        {
            Type genericType = type.GetGenericTypeDefinition();
            Type[] genericArgs = type.GetGenericArguments();
            if (genericType == typeof(Nullable<>))
            {
                Type serializerType = typeof(NullableSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateSerializer(serializerType);
            }

            if (genericType == typeof(List<>))
            {
                Type serializerType = typeof(ListSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateSerializer(serializerType);
            }

            if (genericType == typeof(HashSet<>))
            {
                Type serializerType = typeof(SetSerializer<>).MakeGenericType(genericArgs[0]);
                return CreateSerializer(serializerType);
            }

            if (genericType == typeof(Dictionary<,>))
            {
                Type serializerType = typeof(DictionarySerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return CreateSerializer(serializerType);
            }

            if (genericType == typeof(NullableKeyDictionary<,>))
            {
                Type serializerType = typeof(NullableKeyDictionarySerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return CreateSerializer(serializerType);
            }
        }

        throw new TypeNotRegisteredException($"No serializer available for {type}");
    }

    private static Serializer CreateSerializer<TSerializer>()
        where TSerializer : Serializer, new()
    {
        return new TSerializer();
    }

    private static Serializer CreateSerializer(Type serializerType)
    {
        if (!typeof(Serializer).IsAssignableFrom(serializerType))
        {
            throw new InvalidDataException($"{serializerType} is not a serializer");
        }

        return SerializerConstructors.GetOrAdd(serializerType, BuildSerializerConstructor)();
    }

    private static Func<Serializer> BuildSerializerConstructor(Type serializerType)
    {
        try
        {
            NewExpression body = Expression.New(serializerType);
            return Expression.Lambda<Func<Serializer>>(body).Compile();
        }
        catch (Exception ex)
        {
            throw new InvalidDataException($"failed to build serializer constructor for {serializerType}: {ex.Message}");
        }
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
