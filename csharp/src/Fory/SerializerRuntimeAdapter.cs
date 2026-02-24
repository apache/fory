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

namespace Apache.Fory;

public interface ITypeInfoSerializer
{
    void WriteTypeInfo(WriteContext context);

    void ReadTypeInfo(ReadContext context);
}

public interface ICompatibleTypeMetaFieldProvider
{
    IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields(bool trackRef);
}

internal static class SerializerRuntimeAdapter
{
    internal static void WriteTypeInfo<T>(this Serializer<T> serializer, WriteContext context)
    {
        if (serializer is ITypeInfoSerializer typeInfoSerializer)
        {
            typeInfoSerializer.WriteTypeInfo(context);
            return;
        }

        context.TypeResolver.WriteTypeInfo(serializer, context);
    }

    internal static void ReadTypeInfo<T>(this Serializer<T> serializer, ReadContext context)
    {
        if (serializer is ITypeInfoSerializer typeInfoSerializer)
        {
            typeInfoSerializer.ReadTypeInfo(context);
            return;
        }

        context.TypeResolver.ReadTypeInfo(serializer, context);
    }

    internal static IReadOnlyList<TypeMetaFieldInfo> CompatibleTypeMetaFields<T>(
        this Serializer<T> serializer,
        bool trackRef)
    {
        if (serializer is ICompatibleTypeMetaFieldProvider provider)
        {
            return provider.CompatibleTypeMetaFields(trackRef);
        }

        return [];
    }

    internal static bool IsNoneObject<T>(this Serializer<T> serializer, object? value)
    {
        if (value is null)
        {
            return serializer.IsNullableType;
        }

        return value is T typed && serializer.IsNone(typed);
    }

    internal static void WriteDataObject<T>(this Serializer<T> serializer, WriteContext context, object? value, bool hasGenerics)
    {
        serializer.WriteData(context, CoerceValue(serializer, value), hasGenerics);
    }

    internal static object? ReadDataObject<T>(this Serializer<T> serializer, ReadContext context)
    {
        return serializer.ReadData(context);
    }

    internal static void WriteObject<T>(
        this Serializer<T> serializer,
        WriteContext context,
        object? value,
        RefMode refMode,
        bool writeTypeInfo,
        bool hasGenerics)
    {
        serializer.Write(context, CoerceValue(serializer, value), refMode, writeTypeInfo, hasGenerics);
    }

    internal static object? ReadObject<T>(this Serializer<T> serializer, ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        return serializer.Read(context, refMode, readTypeInfo);
    }

    private static T CoerceValue<T>(Serializer<T> serializer, object? value)
    {
        if (value is T typed)
        {
            return typed;
        }

        if (value is null && serializer.IsNullableType)
        {
            return serializer.DefaultValue;
        }

        throw new InvalidDataException(
            $"serializer {serializer.GetType().Name} expected value of type {typeof(T)}, got {value?.GetType()}");
    }
}
