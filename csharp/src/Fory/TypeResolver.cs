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

namespace Apache.Fory;

internal sealed record RegisteredTypeInfo(
    uint? UserTypeId,
    TypeId Kind,
    bool RegisterByName,
    MetaString? NamespaceName,
    MetaString TypeName,
    SerializerBinding Serializer);

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
    private static readonly ConcurrentDictionary<Type, Func<SerializerBinding>> GeneratedFactories = new();
    private static readonly ConcurrentDictionary<Type, SerializerBinding> SharedBindings = new();
    private static readonly ConcurrentDictionary<Type, TypeInfo> SharedTypeInfos = new();
    private static int _sharedCacheVersion;

    private readonly Dictionary<Type, RegisteredTypeInfo> _byType = [];
    private readonly Dictionary<uint, TypeReader> _byUserTypeId = [];
    private readonly Dictionary<TypeNameKey, TypeReader> _byTypeName = [];
    private readonly Dictionary<TypeId, DynamicRegistrationMode> _registrationModeByKind = [];

    private readonly ConcurrentDictionary<Type, SerializerBinding> _serializerBindings = new();
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
        public static ITypedSerializer<T>? Cached;
    }

    public static void RegisterGenerated<T, TSerializer>()
        where TSerializer : TypedSerializer<T>, new()
    {
        Type type = typeof(T);
        GeneratedFactories[type] = SerializerFactory.Create<TSerializer>;
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
            return new Serializer<T>(SerializerCache<T>.Cached);
        }

        ITypedSerializer<T> typedSerializer = GetTypeInfo<T>().Serializer.RequireTypedSerializer<T>();
        SerializerCache<T>.Resolver = this;
        SerializerCache<T>.Version = _cacheVersion;
        SerializerCache<T>.Cached = typedSerializer;
        return new Serializer<T>(typedSerializer);
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

    internal SerializerBinding GetBinding(Type type)
    {
        return _serializerBindings.GetOrAdd(type, CreateBindingCore);
    }

    internal SerializerBinding RegisterCustom<T, TSerializer>()
        where TSerializer : TypedSerializer<T>, new()
    {
        SerializerBinding serializerBinding = SerializerFactory.Create<TSerializer>();
        RegisterCustom(typeof(T), serializerBinding);
        return serializerBinding;
    }

    internal void RegisterCustom(Type type, SerializerBinding serializerBinding)
    {
        _serializerBindings[type] = serializerBinding;
        _typeInfos.TryRemove(type, out _);
        unchecked
        {
            _cacheVersion += 1;
        }
    }

    internal void Register(Type type, uint id, SerializerBinding? explicitSerializer = null)
    {
        SerializerBinding serializer = explicitSerializer ?? GetBinding(type);
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

    internal void Register(Type type, string namespaceName, string typeName, SerializerBinding? explicitSerializer = null)
    {
        SerializerBinding serializer = explicitSerializer ?? GetBinding(type);
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

    private static SerializerBinding GetSharedBinding(Type type)
    {
        return SharedBindings.GetOrAdd(type, CreateBindingCore);
    }

    private static TypeInfo GetSharedTypeInfo(Type type)
    {
        return SharedTypeInfos.GetOrAdd(type, t => new TypeInfo(t, GetSharedBinding(t)));
    }

    private static SerializerBinding CreateBindingCore(Type type)
    {
        if (GeneratedFactories.TryGetValue(type, out Func<SerializerBinding>? generatedFactory))
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

        if (type == typeof(List<int>))
        {
            return new ListIntSerializer();
        }

        if (type == typeof(List<long>))
        {
            return new ListLongSerializer();
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

        if (type == typeof(Dictionary<int, int>))
        {
            return new DictionaryIntIntSerializer();
        }

        if (type == typeof(Dictionary<long, long>))
        {
            return new DictionaryLongLongSerializer();
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
            return SerializerFactory.Create(serializerType);
        }

        if (type.IsEnum)
        {
            Type serializerType = typeof(EnumSerializer<>).MakeGenericType(type);
            return SerializerFactory.Create(serializerType);
        }

        if (type.IsArray)
        {
            Type elementType = type.GetElementType()!;
            Type serializerType = typeof(ArraySerializer<>).MakeGenericType(elementType);
            return SerializerFactory.Create(serializerType);
        }

        if (type.IsGenericType)
        {
            Type genericType = type.GetGenericTypeDefinition();
            Type[] genericArgs = type.GetGenericArguments();
            if (genericType == typeof(Nullable<>))
            {
                Type serializerType = typeof(NullableSerializer<>).MakeGenericType(genericArgs[0]);
                return SerializerFactory.Create(serializerType);
            }

            if (genericType == typeof(List<>))
            {
                Type serializerType = typeof(ListSerializer<>).MakeGenericType(genericArgs[0]);
                return SerializerFactory.Create(serializerType);
            }

            if (genericType == typeof(HashSet<>))
            {
                Type serializerType = typeof(SetSerializer<>).MakeGenericType(genericArgs[0]);
                return SerializerFactory.Create(serializerType);
            }

            if (genericType == typeof(Dictionary<,>))
            {
                Type serializerType = typeof(DictionarySerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return SerializerFactory.Create(serializerType);
            }

            if (genericType == typeof(NullableKeyDictionary<,>))
            {
                Type serializerType = typeof(NullableKeyDictionarySerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return SerializerFactory.Create(serializerType);
            }
        }

        throw new TypeNotRegisteredException($"No serializer available for {type}");
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
