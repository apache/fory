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

using System.Reflection;
using System.Threading;

namespace Apache.Fory;

internal delegate bool TypedIsNoneDelegate<T>(in T value);

internal delegate void TypedWriteDataDelegate<T>(
    ref WriteContext context,
    in T value,
    bool hasGenerics);

internal delegate T TypedReadDataDelegate<T>(ref ReadContext context);

internal delegate void TypedWriteDelegate<T>(
    ref WriteContext context,
    in T value,
    RefMode refMode,
    bool writeTypeInfo,
    bool hasGenerics);

internal delegate T TypedReadDelegate<T>(
    ref ReadContext context,
    RefMode refMode,
    bool readTypeInfo);

internal delegate void TypeInfoWriteDelegate(ref WriteContext context);

internal delegate void TypeInfoReadDelegate(ref ReadContext context);

internal delegate IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFieldsDelegate(bool trackRef);

internal delegate bool UntypedIsNoneObjectDelegate(object? value);

internal delegate void UntypedWriteDataDelegate(ref WriteContext context, object? value, bool hasGenerics);

internal delegate object? UntypedReadDataDelegate(ref ReadContext context);

internal delegate void UntypedWriteDelegate(
    ref WriteContext context,
    object? value,
    RefMode refMode,
    bool writeTypeInfo,
    bool hasGenerics);

internal delegate object? UntypedReadDelegate(
    ref ReadContext context,
    RefMode refMode,
    bool readTypeInfo);

internal sealed class TypedSerializerBinding<T>
{
    public TypedSerializerBinding(
        TypeId staticTypeId,
        bool isNullableType,
        bool isReferenceTrackableType,
        T defaultValue,
        TypedIsNoneDelegate<T> isNone,
        TypedWriteDataDelegate<T> writeData,
        TypedReadDataDelegate<T> readData,
        TypedWriteDelegate<T> write,
        TypedReadDelegate<T> read,
        TypeInfoWriteDelegate writeTypeInfo,
        TypeInfoReadDelegate readTypeInfo,
        CompatibleTypeMetaFieldsDelegate compatibleTypeMetaFields)
    {
        StaticTypeId = staticTypeId;
        IsNullableType = isNullableType;
        IsReferenceTrackableType = isReferenceTrackableType;
        DefaultValue = defaultValue;
        _isNone = isNone;
        _writeData = writeData;
        _readData = readData;
        _write = write;
        _read = read;
        _writeTypeInfo = writeTypeInfo;
        _readTypeInfo = readTypeInfo;
        _compatibleTypeMetaFields = compatibleTypeMetaFields;
    }

    public TypeId StaticTypeId { get; }

    public bool IsNullableType { get; }

    public bool IsReferenceTrackableType { get; }

    public T DefaultValue { get; }

    private readonly TypedIsNoneDelegate<T> _isNone;
    private readonly TypedWriteDataDelegate<T> _writeData;
    private readonly TypedReadDataDelegate<T> _readData;
    private readonly TypedWriteDelegate<T> _write;
    private readonly TypedReadDelegate<T> _read;
    private readonly TypeInfoWriteDelegate _writeTypeInfo;
    private readonly TypeInfoReadDelegate _readTypeInfo;
    private readonly CompatibleTypeMetaFieldsDelegate _compatibleTypeMetaFields;

    public bool IsNone(in T value)
    {
        return _isNone(value);
    }

    public void WriteData(ref WriteContext context, in T value, bool hasGenerics)
    {
        _writeData(ref context, value, hasGenerics);
    }

    public T ReadData(ref ReadContext context)
    {
        return _readData(ref context);
    }

    public void Write(ref WriteContext context, in T value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        _write(ref context, value, refMode, writeTypeInfo, hasGenerics);
    }

    public T Read(ref ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        return _read(ref context, refMode, readTypeInfo);
    }

    public void WriteTypeInfo(ref WriteContext context)
    {
        _writeTypeInfo(ref context);
    }

    public void ReadTypeInfo(ref ReadContext context)
    {
        _readTypeInfo(ref context);
    }

    public IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields(bool trackRef)
    {
        return _compatibleTypeMetaFields(trackRef);
    }
}

internal sealed class SerializerBinding
{
    public SerializerBinding(
        Type type,
        TypeId staticTypeId,
        bool isNullableType,
        bool isReferenceTrackableType,
        object? defaultObject,
        UntypedIsNoneObjectDelegate isNoneObject,
        UntypedWriteDataDelegate writeData,
        UntypedReadDataDelegate readData,
        UntypedWriteDelegate write,
        UntypedReadDelegate read,
        TypeInfoWriteDelegate writeTypeInfo,
        TypeInfoReadDelegate readTypeInfo,
        CompatibleTypeMetaFieldsDelegate compatibleTypeMetaFields,
        object typedBinding)
    {
        Type = type;
        StaticTypeId = staticTypeId;
        IsNullableType = isNullableType;
        IsReferenceTrackableType = isReferenceTrackableType;
        DefaultObject = defaultObject;
        _isNoneObject = isNoneObject;
        _writeData = writeData;
        _readData = readData;
        _write = write;
        _read = read;
        _writeTypeInfo = writeTypeInfo;
        _readTypeInfo = readTypeInfo;
        _compatibleTypeMetaFields = compatibleTypeMetaFields;
        _typedBinding = typedBinding;
    }

    public Type Type { get; }

    public TypeId StaticTypeId { get; }

    public bool IsNullableType { get; }

    public bool IsReferenceTrackableType { get; }

    public object? DefaultObject { get; }

    private readonly UntypedIsNoneObjectDelegate _isNoneObject;
    private readonly UntypedWriteDataDelegate _writeData;
    private readonly UntypedReadDataDelegate _readData;
    private readonly UntypedWriteDelegate _write;
    private readonly UntypedReadDelegate _read;
    private readonly TypeInfoWriteDelegate _writeTypeInfo;
    private readonly TypeInfoReadDelegate _readTypeInfo;
    private readonly CompatibleTypeMetaFieldsDelegate _compatibleTypeMetaFields;
    private readonly object _typedBinding;

    public bool IsNoneObject(object? value)
    {
        return _isNoneObject(value);
    }

    public void WriteData(ref WriteContext context, object? value, bool hasGenerics)
    {
        _writeData(ref context, value, hasGenerics);
    }

    public object? ReadData(ref ReadContext context)
    {
        return _readData(ref context);
    }

    public void Write(ref WriteContext context, object? value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        _write(ref context, value, refMode, writeTypeInfo, hasGenerics);
    }

    public object? Read(ref ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        return _read(ref context, refMode, readTypeInfo);
    }

    public void WriteTypeInfo(ref WriteContext context)
    {
        _writeTypeInfo(ref context);
    }

    public void ReadTypeInfo(ref ReadContext context)
    {
        _readTypeInfo(ref context);
    }

    public IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields(bool trackRef)
    {
        return _compatibleTypeMetaFields(trackRef);
    }

    public TypedSerializerBinding<T> RequireTypedBinding<T>()
    {
        if (_typedBinding is TypedSerializerBinding<T> typed)
        {
            return typed;
        }

        throw new InvalidDataException($"serializer type mismatch for {typeof(T)}");
    }
}

internal static class StaticSerializerDispatch<T, TSerializer>
    where TSerializer : IStaticSerializer<TSerializer, T>
{
    private static readonly TypedSerializerBinding<T> TypedBindingValue = new(
        TSerializer.StaticTypeId,
        TSerializer.IsNullableType,
        TSerializer.IsReferenceTrackableType,
        TSerializer.DefaultValue,
        IsNone,
        WriteData,
        ReadData,
        Write,
        Read,
        WriteTypeInfo,
        ReadTypeInfo,
        CompatibleTypeMetaFields);

    public static TypedSerializerBinding<T> TypedBinding => TypedBindingValue;

    public static bool IsNone(in T value)
    {
        return TSerializer.IsNone(value);
    }

    public static bool IsNoneObject(object? value)
    {
        if (value is null)
        {
            return TSerializer.IsNullableType;
        }

        return value is T typed && TSerializer.IsNone(typed);
    }

    public static void WriteData(ref WriteContext context, in T value, bool hasGenerics)
    {
        TSerializer.WriteData(ref context, value, hasGenerics);
    }

    public static T ReadData(ref ReadContext context)
    {
        return TSerializer.ReadData(ref context);
    }

    public static void Write(ref WriteContext context, in T value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        TSerializer.Write(ref context, value, refMode, writeTypeInfo, hasGenerics);
    }

    public static T Read(ref ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        return TSerializer.Read(ref context, refMode, readTypeInfo);
    }

    public static void WriteTypeInfo(ref WriteContext context)
    {
        TSerializer.WriteTypeInfo(ref context);
    }

    public static void ReadTypeInfo(ref ReadContext context)
    {
        TSerializer.ReadTypeInfo(ref context);
    }

    public static IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields(bool trackRef)
    {
        return TSerializer.CompatibleTypeMetaFields(trackRef);
    }

    public static void WriteDataObject(ref WriteContext context, object? value, bool hasGenerics)
    {
        TSerializer.WriteData(ref context, CoerceValue(value), hasGenerics);
    }

    public static object? ReadDataObject(ref ReadContext context)
    {
        return TSerializer.ReadData(ref context);
    }

    public static void WriteObject(ref WriteContext context, object? value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        TSerializer.Write(ref context, CoerceValue(value), refMode, writeTypeInfo, hasGenerics);
    }

    public static object? ReadObject(ref ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        return TSerializer.Read(ref context, refMode, readTypeInfo);
    }

    private static T CoerceValue(object? value)
    {
        if (value is T typed)
        {
            return typed;
        }

        if (value is null && TSerializer.IsNullableType)
        {
            return TSerializer.DefaultValue;
        }

        throw new InvalidDataException(
            $"serializer {typeof(TSerializer).Name} expected value of type {typeof(T)}, got {value?.GetType()}");
    }
}

internal static class StaticSerializerBindingFactory
{
    private static readonly MethodInfo CreateGenericMethod =
        typeof(StaticSerializerBindingFactory).GetMethod(
            nameof(CreateGeneric),
            BindingFlags.NonPublic | BindingFlags.Static)
        ?? throw new InvalidOperationException("missing static serializer binding factory method");

    public static SerializerBinding Create<T, TSerializer>()
        where TSerializer : IStaticSerializer<TSerializer, T>
    {
        TypedSerializerBinding<T> typed = StaticSerializerDispatch<T, TSerializer>.TypedBinding;
        return new SerializerBinding(
            typeof(T),
            typed.StaticTypeId,
            typed.IsNullableType,
            typed.IsReferenceTrackableType,
            typed.DefaultValue,
            StaticSerializerDispatch<T, TSerializer>.IsNoneObject,
            StaticSerializerDispatch<T, TSerializer>.WriteDataObject,
            StaticSerializerDispatch<T, TSerializer>.ReadDataObject,
            StaticSerializerDispatch<T, TSerializer>.WriteObject,
            StaticSerializerDispatch<T, TSerializer>.ReadObject,
            StaticSerializerDispatch<T, TSerializer>.WriteTypeInfo,
            StaticSerializerDispatch<T, TSerializer>.ReadTypeInfo,
            StaticSerializerDispatch<T, TSerializer>.CompatibleTypeMetaFields,
            typed);
    }

    public static SerializerBinding Create(Type valueType, Type serializerType)
    {
        MethodInfo method = CreateGenericMethod.MakeGenericMethod(valueType, serializerType);
        try
        {
            return (SerializerBinding)method.Invoke(null, null)!;
        }
        catch (TargetInvocationException ex) when (ex.InnerException is not null)
        {
            throw ex.InnerException;
        }
    }

    private static SerializerBinding CreateGeneric<T, TSerializer>()
        where TSerializer : IStaticSerializer<TSerializer, T>
    {
        return Create<T, TSerializer>();
    }
}

internal static class TypedSerializerBindingCache<T>
{
    private static readonly object Gate = new();
    private static TypedSerializerBinding<T>? _binding;
    private static int _bindingVersion = -1;

    public static Serializer<T> Get()
    {
        int currentVersion = SerializerRegistry.Version;
        TypedSerializerBinding<T>? binding = Volatile.Read(ref _binding);
        if (binding is not null && Volatile.Read(ref _bindingVersion) == currentVersion)
        {
            return new Serializer<T>(binding);
        }

        lock (Gate)
        {
            binding = _binding;
            if (binding is not null && _bindingVersion == currentVersion)
            {
                return new Serializer<T>(binding);
            }

            SerializerBinding untyped = SerializerRegistry.GetBinding(typeof(T));
            binding = untyped.RequireTypedBinding<T>();
            _binding = binding;
            Volatile.Write(ref _bindingVersion, currentVersion);
            return new Serializer<T>(binding);
        }
    }
}
