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

using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Collections.Immutable;
using System.Reflection;

namespace Apache.Fory;

internal enum UserTypeKind
{
    Enum,
    Struct,
    Ext,
    TypedUnion,
}

public sealed class TypeInfo
{
    internal readonly record struct TypeMetaCacheEntry(TypeMeta TypeMeta, byte[] EncodedBytes, ulong HeaderHash);

    private readonly object _serializer;
    private readonly TypeMeta? _typeMeta;
    private readonly Action<WriteContext, object?, bool> _writeDataObject;
    private readonly Func<ReadContext, object?> _readDataObject;
    private readonly Action<WriteContext, object?, RefMode, bool, bool> _writeObject;
    private readonly Func<ReadContext, RefMode, bool, object?> _readObject;
    private readonly Func<bool, IReadOnlyList<TypeMetaFieldInfo>> _typeMetaFields;
    private readonly object _typeMetaCacheLock = new();
    private static readonly IReadOnlyList<TypeMetaFieldInfo> EmptyTypeMetaFields = Array.Empty<TypeMetaFieldInfo>();
    private TypeMetaCacheEntry? _typeMetaNoTrackRef;
    private TypeMetaCacheEntry? _typeMetaTrackRef;

    private TypeInfo(
        Type type,
        object serializer,
        TypeId? builtInTypeId,
        UserTypeKind? userTypeKind,
        bool isDynamicType,
        bool isNullableType,
        bool isRefType,
        object? defaultObject,
        bool evolving,
        bool isRegistered,
        uint? userTypeId,
        bool registerByName,
        MetaString? namespaceName,
        MetaString? typeName,
        Action<WriteContext, object?, bool> writeDataObject,
        Func<ReadContext, object?> readDataObject,
        Action<WriteContext, object?, RefMode, bool, bool> writeObject,
        Func<ReadContext, RefMode, bool, object?> readObject,
        Func<bool, IReadOnlyList<TypeMetaFieldInfo>> typeMetaFields,
        TypeId? wireTypeId,
        TypeMeta? typeMeta)
    {
        Type = type;
        _serializer = serializer;
        _typeMeta = typeMeta;
        BuiltInTypeId = builtInTypeId;
        UserTypeKind = userTypeKind;
        IsDynamicType = isDynamicType;
        IsNullableType = isNullableType;
        IsRefType = isRefType;
        DefaultObject = defaultObject;
        Evolving = evolving;
        IsRegistered = isRegistered;
        UserTypeId = userTypeId;
        RegisterByName = registerByName;
        NamespaceName = namespaceName;
        TypeName = typeName;
        _writeDataObject = writeDataObject;
        _readDataObject = readDataObject;
        _writeObject = writeObject;
        _readObject = readObject;
        _typeMetaFields = typeMetaFields;
        WireTypeId = wireTypeId;
    }

    internal static TypeInfo Create<T>(Type type, Serializer<T> serializer)
    {
        Func<bool, IReadOnlyList<TypeMetaFieldInfo>> typeMetaFields =
            CreateTypeMetaFieldsProvider(serializer, out bool hasTypeMetaFieldsProvider);
        (TypeId? builtInTypeId, UserTypeKind? userTypeKind, bool isDynamicType) = ResolveTypeShape(
            type,
            hasTypeMetaFieldsProvider);
        bool evolving = ResolveStructEvolving(type, userTypeKind);
        bool isNullableType = !type.IsValueType || Nullable.GetUnderlyingType(type) is not null;
        bool isRefType = type != typeof(string) && !type.IsValueType;
        return new TypeInfo(
            type,
            serializer,
            builtInTypeId,
            userTypeKind,
            isDynamicType,
            isNullableType,
            isRefType,
            serializer.DefaultObject,
            evolving,
            isRegistered: false,
            userTypeId: null,
            registerByName: false,
            namespaceName: null,
            typeName: null,
            (context, value, hasGenerics) => WriteDataObject(serializer, context, value, hasGenerics),
            context => serializer.ReadData(context),
            (context, value, refMode, writeTypeInfo, hasGenerics) =>
                WriteObject(serializer, context, value, refMode, writeTypeInfo, hasGenerics),
            (context, refMode, readTypeInfo) => serializer.Read(context, refMode, readTypeInfo),
            typeMetaFields,
            builtInTypeId,
            null);
    }

    private static Func<bool, IReadOnlyList<TypeMetaFieldInfo>> CreateTypeMetaFieldsProvider(
        object serializer,
        out bool hasProvider)
    {
        MethodInfo? method = serializer.GetType().GetMethod(
            "TypeMetaFields",
            BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
            null,
            [typeof(bool)],
            null);
        if (method is null || method.ReturnType != typeof(IReadOnlyList<TypeMetaFieldInfo>))
        {
            hasProvider = false;
            return EmptyTypeMetaFieldsProvider;
        }

        try
        {
            Delegate del = method.CreateDelegate(typeof(Func<bool, IReadOnlyList<TypeMetaFieldInfo>>), serializer);
            hasProvider = true;
            return (Func<bool, IReadOnlyList<TypeMetaFieldInfo>>)del;
        }
        catch
        {
            hasProvider = false;
            return EmptyTypeMetaFieldsProvider;
        }
    }

    private static IReadOnlyList<TypeMetaFieldInfo> EmptyTypeMetaFieldsProvider(bool _)
    {
        return EmptyTypeMetaFields;
    }

    private static bool ResolveStructEvolving(Type type, UserTypeKind? userTypeKind)
    {
        if (userTypeKind != Apache.Fory.UserTypeKind.Struct)
        {
            return true;
        }

        Type structType = Nullable.GetUnderlyingType(type) ?? type;
        ForyObjectAttribute? attribute = structType.GetCustomAttribute<ForyObjectAttribute>();
        return attribute?.Evolving ?? true;
    }

    private static void WriteDataObject<T>(Serializer<T> serializer, WriteContext context, object? value, bool hasGenerics)
    {
        serializer.WriteData(context, CoerceRuntimeValue(serializer, value), hasGenerics);
    }

    private static void WriteObject<T>(
        Serializer<T> serializer,
        WriteContext context,
        object? value,
        RefMode refMode,
        bool writeTypeInfo,
        bool hasGenerics)
    {
        serializer.Write(context, CoerceRuntimeValue(serializer, value), refMode, writeTypeInfo, hasGenerics);
    }

    private static T CoerceRuntimeValue<T>(Serializer<T> serializer, object? value)
    {
        if (value is T typed)
        {
            return typed;
        }

        if (value is null && default(T) is null)
        {
            return serializer.DefaultValue;
        }

        throw new InvalidDataException(
            $"serializer {serializer.GetType().Name} expected value of type {typeof(T)}, got {value?.GetType()}");
    }

    private static (TypeId? BuiltInTypeId, UserTypeKind? UserTypeKind, bool IsDynamicType) ResolveTypeShape(
        Type type,
        bool hasTypeMetaFieldsProvider)
    {
        Type? nullableType = Nullable.GetUnderlyingType(type);
        if (nullableType is not null)
        {
            return ResolveTypeShape(nullableType, hasTypeMetaFieldsProvider);
        }

        if (TryResolveBuiltInTypeId(type, out TypeId builtInTypeId))
        {
            return (builtInTypeId, null, false);
        }

        if (type == typeof(object))
        {
            return (null, null, true);
        }

        if (type.IsEnum)
        {
            return (null, Apache.Fory.UserTypeKind.Enum, false);
        }

        if (typeof(Union).IsAssignableFrom(type))
        {
            return (null, Apache.Fory.UserTypeKind.TypedUnion, false);
        }

        if (hasTypeMetaFieldsProvider)
        {
            return (null, Apache.Fory.UserTypeKind.Struct, false);
        }

        return (null, Apache.Fory.UserTypeKind.Ext, false);
    }

    private static bool TryResolveBuiltInTypeId(Type type, out TypeId typeId)
    {
        if (type == typeof(bool))
        {
            typeId = TypeId.Bool;
            return true;
        }

        if (type == typeof(sbyte))
        {
            typeId = TypeId.Int8;
            return true;
        }

        if (type == typeof(short))
        {
            typeId = TypeId.Int16;
            return true;
        }

        if (type == typeof(int))
        {
            typeId = TypeId.VarInt32;
            return true;
        }

        if (type == typeof(long))
        {
            typeId = TypeId.VarInt64;
            return true;
        }

        if (type == typeof(byte))
        {
            typeId = TypeId.UInt8;
            return true;
        }

        if (type == typeof(ushort))
        {
            typeId = TypeId.UInt16;
            return true;
        }

        if (type == typeof(uint))
        {
            typeId = TypeId.VarUInt32;
            return true;
        }

        if (type == typeof(ulong))
        {
            typeId = TypeId.VarUInt64;
            return true;
        }

        if (type == typeof(float))
        {
            typeId = TypeId.Float32;
            return true;
        }

        if (type == typeof(double))
        {
            typeId = TypeId.Float64;
            return true;
        }

        if (type == typeof(string))
        {
            typeId = TypeId.String;
            return true;
        }

        if (type == typeof(byte[]))
        {
            typeId = TypeId.Binary;
            return true;
        }

        if (type == typeof(bool[]))
        {
            typeId = TypeId.BoolArray;
            return true;
        }

        if (type == typeof(sbyte[]))
        {
            typeId = TypeId.Int8Array;
            return true;
        }

        if (type == typeof(short[]))
        {
            typeId = TypeId.Int16Array;
            return true;
        }

        if (type == typeof(int[]))
        {
            typeId = TypeId.Int32Array;
            return true;
        }

        if (type == typeof(long[]))
        {
            typeId = TypeId.Int64Array;
            return true;
        }

        if (type == typeof(ushort[]))
        {
            typeId = TypeId.UInt16Array;
            return true;
        }

        if (type == typeof(uint[]))
        {
            typeId = TypeId.UInt32Array;
            return true;
        }

        if (type == typeof(ulong[]))
        {
            typeId = TypeId.UInt64Array;
            return true;
        }

        if (type == typeof(float[]))
        {
            typeId = TypeId.Float32Array;
            return true;
        }

        if (type == typeof(double[]))
        {
            typeId = TypeId.Float64Array;
            return true;
        }

        if (type == typeof(DateOnly))
        {
            typeId = TypeId.Date;
            return true;
        }

        if (type == typeof(DateTimeOffset) || type == typeof(DateTime))
        {
            typeId = TypeId.Timestamp;
            return true;
        }

        if (type == typeof(TimeSpan))
        {
            typeId = TypeId.Duration;
            return true;
        }

        if (type.IsArray)
        {
            typeId = TypeId.List;
            return true;
        }

        if (type.IsGenericType)
        {
            Type genericType = type.GetGenericTypeDefinition();
            if (genericType == typeof(List<>) ||
                genericType == typeof(LinkedList<>) ||
                genericType == typeof(Queue<>) ||
                genericType == typeof(Stack<>))
            {
                typeId = TypeId.List;
                return true;
            }

            if (genericType == typeof(HashSet<>) ||
                genericType == typeof(SortedSet<>) ||
                genericType == typeof(ImmutableHashSet<>))
            {
                typeId = TypeId.Set;
                return true;
            }

            if (genericType == typeof(Dictionary<,>) ||
                genericType == typeof(SortedDictionary<,>) ||
                genericType == typeof(SortedList<,>) ||
                genericType == typeof(ConcurrentDictionary<,>) ||
                genericType == typeof(NullableKeyDictionary<,>))
            {
                typeId = TypeId.Map;
                return true;
            }

            if (genericType == typeof(Nullable<>))
            {
                Type? underlying = Nullable.GetUnderlyingType(type);
                if (underlying is not null)
                {
                    return TryResolveBuiltInTypeId(underlying, out typeId);
                }
            }
        }

        typeId = default;
        return false;
    }

    internal Type Type { get; }

    internal bool IsBuiltinType => BuiltInTypeId.HasValue;

    internal TypeId? BuiltInTypeId { get; }

    internal UserTypeKind? UserTypeKind { get; }

    internal bool IsDynamicType { get; }

    internal bool IsNullableType { get; }

    internal bool IsRefType { get; }

    internal object? DefaultObject { get; }

    internal bool Evolving { get; }

    internal TypeId? WireTypeId { get; }

    internal Type SerializerType => _serializer.GetType();

    internal Serializer<T> RequireSerializer<T>()
    {
        if (_serializer is Serializer<T> serializer)
        {
            return serializer;
        }

        throw new InvalidDataException($"serializer type mismatch for {typeof(T)}");
    }

    internal void WriteDataObject(WriteContext context, object? value, bool hasGenerics)
    {
        _writeDataObject(context, value, hasGenerics);
    }

    internal object? ReadDataObject(ReadContext context)
    {
        return _readDataObject(context);
    }

    internal void WriteObject(WriteContext context, object? value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        _writeObject(context, value, refMode, writeTypeInfo, hasGenerics);
    }

    internal object? ReadObject(ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        return _readObject(context, refMode, readTypeInfo);
    }

    internal IReadOnlyList<TypeMetaFieldInfo> TypeMetaFields(bool trackRef)
    {
        return _typeMetaFields(trackRef);
    }

    internal TypeMeta? GetTypeMeta()
    {
        return _typeMeta;
    }

    public ulong GetTypeMetaHeaderHash(bool trackRef)
    {
        return GetTypeMetaCacheEntry(trackRef).HeaderHash;
    }

    internal bool IsRegistered { get; }

    internal uint? UserTypeId { get; }

    internal bool RegisterByName { get; }

    internal MetaString? NamespaceName { get; }

    internal MetaString? TypeName { get; }

    internal TypeInfo WithTypeIdRegistration(uint userTypeId)
    {
        return new TypeInfo(
            Type,
            _serializer,
            BuiltInTypeId,
            UserTypeKind,
            IsDynamicType,
            IsNullableType,
            IsRefType,
            DefaultObject,
            Evolving,
            isRegistered: true,
            userTypeId: userTypeId,
            registerByName: false,
            namespaceName: null,
            typeName: null,
            _writeDataObject,
            _readDataObject,
            _writeObject,
            _readObject,
            _typeMetaFields,
            WireTypeId,
            _typeMeta);
    }

    internal TypeInfo WithTypeNameRegistration(MetaString namespaceName, MetaString typeName)
    {
        return new TypeInfo(
            Type,
            _serializer,
            BuiltInTypeId,
            UserTypeKind,
            IsDynamicType,
            IsNullableType,
            IsRefType,
            DefaultObject,
            Evolving,
            isRegistered: true,
            userTypeId: null,
            registerByName: true,
            namespaceName: namespaceName,
            typeName: typeName,
            _writeDataObject,
            _readDataObject,
            _writeObject,
            _readObject,
            _typeMetaFields,
            WireTypeId,
            _typeMeta);
    }

    internal TypeInfo WithRegistrationFrom(TypeInfo source)
    {
        if (!source.IsRegistered)
        {
            return this;
        }

        if (source.RegisterByName)
        {
            if (!source.NamespaceName.HasValue || !source.TypeName.HasValue)
            {
                throw new InvalidDataException("missing type name metadata for name-registered type");
            }

            return WithTypeNameRegistration(source.NamespaceName.Value, source.TypeName.Value);
        }

        if (!source.UserTypeId.HasValue)
        {
            throw new InvalidDataException("missing user type id metadata for id-registered type");
        }

        return WithTypeIdRegistration(source.UserTypeId.Value);
    }

    internal TypeInfo WithWireTypeInfo(TypeId wireTypeId, TypeMeta? typeMeta = null)
    {
        return new TypeInfo(
            Type,
            _serializer,
            BuiltInTypeId,
            UserTypeKind,
            IsDynamicType,
            IsNullableType,
            IsRefType,
            DefaultObject,
            Evolving,
            IsRegistered,
            UserTypeId,
            RegisterByName,
            NamespaceName,
            TypeName,
            _writeDataObject,
            _readDataObject,
            _writeObject,
            _readObject,
            _typeMetaFields,
            wireTypeId,
            typeMeta);
    }

    internal TypeMetaCacheEntry GetTypeMetaCacheEntry(bool trackRef)
    {
        if (trackRef)
        {
            if (_typeMetaTrackRef.HasValue)
            {
                return _typeMetaTrackRef.Value;
            }
        }
        else if (_typeMetaNoTrackRef.HasValue)
        {
            return _typeMetaNoTrackRef.Value;
        }

        lock (_typeMetaCacheLock)
        {
            if (trackRef)
            {
                _typeMetaTrackRef ??= BuildTypeMetaCacheEntry(trackRef: true);
                return _typeMetaTrackRef.Value;
            }

            _typeMetaNoTrackRef ??= BuildTypeMetaCacheEntry(trackRef: false);
            return _typeMetaNoTrackRef.Value;
        }
    }

    private TypeMetaCacheEntry BuildTypeMetaCacheEntry(bool trackRef)
    {
        if (!UserTypeKind.HasValue)
        {
            throw new InvalidDataException($"type meta is only available for user types, got {Type}");
        }

        if (!IsRegistered)
        {
            throw new TypeNotRegisteredException($"{Type} is not registered");
        }

        TypeId wireTypeId = TypeResolver.ResolveWireTypeId(
            UserTypeKind.Value,
            RegisterByName,
            compatible: true,
            Evolving);
        IReadOnlyList<TypeMetaFieldInfo> fields = TypeMetaFields(trackRef);
        bool hasFieldsMeta = fields.Count > 0;
        TypeMeta typeMeta;
        if (RegisterByName)
        {
            if (!NamespaceName.HasValue || !TypeName.HasValue)
            {
                throw new InvalidDataException("missing type name metadata for name-registered type");
            }

            typeMeta = new TypeMeta(
                (uint)wireTypeId,
                null,
                NamespaceName.Value,
                TypeName.Value,
                true,
                fields,
                hasFieldsMeta);
        }
        else
        {
            if (!UserTypeId.HasValue)
            {
                throw new InvalidDataException("missing user type id metadata for id-registered type");
            }

            typeMeta = new TypeMeta(
                (uint)wireTypeId,
                UserTypeId.Value,
                MetaString.Empty('.', '_'),
                MetaString.Empty('$', '_'),
                false,
                fields,
                hasFieldsMeta);
        }

        byte[] encoded = typeMeta.Encode();
        ulong header = BinaryPrimitives.ReadUInt64LittleEndian(encoded);
        ulong headerHash = header >> (int)(64 - TypeMetaConstants.TypeMetaNumHashBits);
        return new TypeMetaCacheEntry(typeMeta, encoded, headerHash);
    }
}
