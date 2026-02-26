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
    private readonly object _serializer;
    private readonly Action<WriteContext, object?, bool> _writeDataObject;
    private readonly Func<ReadContext, object?> _readDataObject;
    private readonly Action<WriteContext, object?, RefMode, bool, bool> _writeObject;
    private readonly Func<ReadContext, RefMode, bool, object?> _readObject;
    private readonly Func<bool, IReadOnlyList<TypeMetaFieldInfo>> _compatibleTypeMetaFields;
    private static readonly IReadOnlyList<TypeMetaFieldInfo> EmptyTypeMetaFields = Array.Empty<TypeMetaFieldInfo>();

    private TypeInfo(
        Type type,
        object serializer,
        TypeId? builtInTypeId,
        UserTypeKind? userTypeKind,
        bool isDynamicType,
        bool isNullableType,
        bool isReferenceTrackableType,
        object? defaultObject,
        Action<WriteContext, object?, bool> writeDataObject,
        Func<ReadContext, object?> readDataObject,
        Action<WriteContext, object?, RefMode, bool, bool> writeObject,
        Func<ReadContext, RefMode, bool, object?> readObject,
        Func<bool, IReadOnlyList<TypeMetaFieldInfo>> compatibleTypeMetaFields)
    {
        Type = type;
        _serializer = serializer;
        BuiltInTypeId = builtInTypeId;
        UserTypeKind = userTypeKind;
        IsDynamicType = isDynamicType;
        IsNullableType = isNullableType;
        IsReferenceTrackableType = isReferenceTrackableType;
        DefaultObject = defaultObject;
        _writeDataObject = writeDataObject;
        _readDataObject = readDataObject;
        _writeObject = writeObject;
        _readObject = readObject;
        _compatibleTypeMetaFields = compatibleTypeMetaFields;
    }

    internal static TypeInfo Create<T>(Type type, Serializer<T> serializer)
    {
        Func<bool, IReadOnlyList<TypeMetaFieldInfo>> compatibleTypeMetaFields =
            CreateCompatibleTypeMetaFieldsProvider(serializer, out bool hasCompatibleTypeMetaFieldsProvider);
        (TypeId? builtInTypeId, UserTypeKind? userTypeKind, bool isDynamicType) = ResolveTypeShape(
            type,
            hasCompatibleTypeMetaFieldsProvider);
        bool isNullableType = !type.IsValueType || Nullable.GetUnderlyingType(type) is not null;
        bool isReferenceTrackableType = type != typeof(string) && !type.IsValueType;
        return new TypeInfo(
            type,
            serializer,
            builtInTypeId,
            userTypeKind,
            isDynamicType,
            isNullableType,
            isReferenceTrackableType,
            serializer.DefaultObject,
            (context, value, hasGenerics) => WriteDataObject(serializer, context, value, hasGenerics),
            context => serializer.ReadData(context),
            (context, value, refMode, writeTypeInfo, hasGenerics) =>
                WriteObject(serializer, context, value, refMode, writeTypeInfo, hasGenerics),
            (context, refMode, readTypeInfo) => serializer.Read(context, refMode, readTypeInfo),
            compatibleTypeMetaFields);
    }

    private static Func<bool, IReadOnlyList<TypeMetaFieldInfo>> CreateCompatibleTypeMetaFieldsProvider(
        object serializer,
        out bool hasProvider)
    {
        MethodInfo? method = serializer.GetType().GetMethod(
            "CompatibleTypeMetaFields",
            BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
            null,
            [typeof(bool)],
            null);
        if (method is null || method.ReturnType != typeof(IReadOnlyList<TypeMetaFieldInfo>))
        {
            hasProvider = false;
            return EmptyCompatibleTypeMetaFields;
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
            return EmptyCompatibleTypeMetaFields;
        }
    }

    private static IReadOnlyList<TypeMetaFieldInfo> EmptyCompatibleTypeMetaFields(bool _)
    {
        return EmptyTypeMetaFields;
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
        bool hasCompatibleTypeMetaFieldsProvider)
    {
        Type? nullableType = Nullable.GetUnderlyingType(type);
        if (nullableType is not null)
        {
            return ResolveTypeShape(nullableType, hasCompatibleTypeMetaFieldsProvider);
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

        if (hasCompatibleTypeMetaFieldsProvider)
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

    public Type Type { get; }

    public bool IsBuiltinType => BuiltInTypeId.HasValue;

    public TypeId? BuiltInTypeId { get; }

    public bool IsUserType => UserTypeKind.HasValue;

    internal UserTypeKind? UserTypeKind { get; }

    public bool IsDynamicType { get; }

    public bool IsNullableType { get; }

    public bool IsReferenceTrackableType { get; }

    public object? DefaultObject { get; }

    public bool NeedsTypeInfoForField()
    {
        if (IsDynamicType)
        {
            return true;
        }

        if (!UserTypeKind.HasValue)
        {
            return false;
        }

        return UserTypeKind.Value is Apache.Fory.UserTypeKind.Struct or Apache.Fory.UserTypeKind.Ext;
    }

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

    internal IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields(bool trackRef)
    {
        return _compatibleTypeMetaFields(trackRef);
    }

    internal bool IsRegistered { get; private set; }

    internal uint? UserTypeId { get; private set; }

    internal bool RegisterByName { get; private set; }

    internal MetaString? NamespaceName { get; private set; }

    internal MetaString? TypeName { get; private set; }

    internal void RegisterByTypeId(uint userTypeId)
    {
        IsRegistered = true;
        UserTypeId = userTypeId;
        RegisterByName = false;
        NamespaceName = null;
        TypeName = null;
    }

    internal void RegisterByTypeName(MetaString namespaceName, MetaString typeName)
    {
        IsRegistered = true;
        UserTypeId = null;
        RegisterByName = true;
        NamespaceName = namespaceName;
        TypeName = typeName;
    }

    internal void CopyRegistrationFrom(TypeInfo source)
    {
        IsRegistered = source.IsRegistered;
        UserTypeId = source.UserTypeId;
        RegisterByName = source.RegisterByName;
        NamespaceName = source.NamespaceName;
        TypeName = source.TypeName;
    }
}
