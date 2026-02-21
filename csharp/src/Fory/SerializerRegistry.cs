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

public static class SerializerRegistry
{
    private static readonly ConcurrentDictionary<Type, ISerializer> Cache = new();
    private static readonly ConcurrentDictionary<Type, Func<ISerializer>> GeneratedFactories = new();

    static SerializerRegistry()
    {
        RegisterBuiltins();
        GeneratedSerializerRegistry.Register(GeneratedFactories);
    }

    public static void RegisterGenerated<T>(Func<Serializer<T>> factory)
    {
        GeneratedFactories[typeof(T)] = () => factory();
        Cache.TryRemove(typeof(T), out _);
    }

    public static void RegisterCustom(Type type, ISerializer serializer)
    {
        Cache[type] = serializer;
    }

    public static Serializer<T> Get<T>()
    {
        ISerializer serializer = Get(typeof(T));
        if (serializer is Serializer<T> typed)
        {
            return typed;
        }

        throw new ForyInvalidDataException($"serializer type mismatch for {typeof(T)}");
    }

    public static ISerializer Get(Type type)
    {
        return Cache.GetOrAdd(type, Create);
    }

    private static ISerializer Create(Type type)
    {
        if (GeneratedFactories.TryGetValue(type, out Func<ISerializer>? generatedFactory))
        {
            return generatedFactory();
        }

        if (type == typeof(bool))
        {
            return BoolSerializer.Instance;
        }

        if (type == typeof(sbyte))
        {
            return Int8Serializer.Instance;
        }

        if (type == typeof(short))
        {
            return Int16Serializer.Instance;
        }

        if (type == typeof(int))
        {
            return Int32Serializer.Instance;
        }

        if (type == typeof(long))
        {
            return Int64Serializer.Instance;
        }

        if (type == typeof(byte))
        {
            return UInt8Serializer.Instance;
        }

        if (type == typeof(ushort))
        {
            return UInt16Serializer.Instance;
        }

        if (type == typeof(uint))
        {
            return UInt32Serializer.Instance;
        }

        if (type == typeof(ulong))
        {
            return UInt64Serializer.Instance;
        }

        if (type == typeof(float))
        {
            return Float32Serializer.Instance;
        }

        if (type == typeof(double))
        {
            return Float64Serializer.Instance;
        }

        if (type == typeof(string))
        {
            return StringSerializer.Instance;
        }

        if (type == typeof(byte[]))
        {
            return BinarySerializer.Instance;
        }

        if (type == typeof(DateOnly))
        {
            return DateOnlySerializer.Instance;
        }

        if (type == typeof(DateTimeOffset))
        {
            return DateTimeOffsetSerializer.Instance;
        }

        if (type == typeof(DateTime))
        {
            return DateTimeSerializer.Instance;
        }

        if (type == typeof(TimeSpan))
        {
            return TimeSpanSerializer.Instance;
        }

        if (type == typeof(ForyInt32Fixed))
        {
            return ForyInt32FixedSerializer.Instance;
        }

        if (type == typeof(ForyInt64Fixed))
        {
            return ForyInt64FixedSerializer.Instance;
        }

        if (type == typeof(ForyInt64Tagged))
        {
            return ForyInt64TaggedSerializer.Instance;
        }

        if (type == typeof(ForyUInt32Fixed))
        {
            return ForyUInt32FixedSerializer.Instance;
        }

        if (type == typeof(ForyUInt64Fixed))
        {
            return ForyUInt64FixedSerializer.Instance;
        }

        if (type == typeof(ForyUInt64Tagged))
        {
            return ForyUInt64TaggedSerializer.Instance;
        }

        if (type == typeof(ForyAnyNullValue))
        {
            return ForyAnyNullValueSerializer.Instance;
        }

        if (type == typeof(object))
        {
            return DynamicAnyObjectSerializer.Instance;
        }

        if (typeof(Union).IsAssignableFrom(type))
        {
            Type serializerType = typeof(UnionSerializer<>).MakeGenericType(type);
            return (ISerializer)Activator.CreateInstance(serializerType)!;
        }

        if (type.IsEnum)
        {
            Type serializerType = typeof(EnumSerializer<>).MakeGenericType(type);
            return (ISerializer)Activator.CreateInstance(serializerType)!;
        }

        if (type.IsArray)
        {
            Type elementType = type.GetElementType()!;
            Type serializerType = typeof(ArraySerializer<>).MakeGenericType(elementType);
            return (ISerializer)Activator.CreateInstance(serializerType)!;
        }

        if (type.IsGenericType)
        {
            Type genericType = type.GetGenericTypeDefinition();
            Type[] genericArgs = type.GetGenericArguments();
            if (genericType == typeof(Nullable<>))
            {
                Type serializerType = typeof(NullableSerializer<>).MakeGenericType(genericArgs[0]);
                return (ISerializer)Activator.CreateInstance(serializerType)!;
            }

            if (genericType == typeof(List<>))
            {
                Type serializerType = typeof(ListSerializer<>).MakeGenericType(genericArgs[0]);
                return (ISerializer)Activator.CreateInstance(serializerType)!;
            }

            if (genericType == typeof(HashSet<>))
            {
                Type serializerType = typeof(SetSerializer<>).MakeGenericType(genericArgs[0]);
                return (ISerializer)Activator.CreateInstance(serializerType)!;
            }

            if (genericType == typeof(Dictionary<,>))
            {
                Type serializerType = typeof(MapSerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return (ISerializer)Activator.CreateInstance(serializerType)!;
            }

            if (genericType == typeof(ForyMap<,>))
            {
                Type serializerType = typeof(ForyMapSerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return (ISerializer)Activator.CreateInstance(serializerType)!;
            }
        }

        if (GeneratedSerializerRegistry.TryCreate(type, out ISerializer? generatedSerializer))
        {
            return generatedSerializer!;
        }

        throw new ForyTypeNotRegisteredException($"No serializer available for {type}");
    }

    private static void RegisterBuiltins()
    {
        Cache[typeof(bool)] = BoolSerializer.Instance;
        Cache[typeof(sbyte)] = Int8Serializer.Instance;
        Cache[typeof(short)] = Int16Serializer.Instance;
        Cache[typeof(int)] = Int32Serializer.Instance;
        Cache[typeof(long)] = Int64Serializer.Instance;
        Cache[typeof(byte)] = UInt8Serializer.Instance;
        Cache[typeof(ushort)] = UInt16Serializer.Instance;
        Cache[typeof(uint)] = UInt32Serializer.Instance;
        Cache[typeof(ulong)] = UInt64Serializer.Instance;
        Cache[typeof(float)] = Float32Serializer.Instance;
        Cache[typeof(double)] = Float64Serializer.Instance;
        Cache[typeof(string)] = StringSerializer.Instance;
        Cache[typeof(byte[])] = BinarySerializer.Instance;
        Cache[typeof(object)] = DynamicAnyObjectSerializer.Instance;
        Cache[typeof(ForyAnyNullValue)] = ForyAnyNullValueSerializer.Instance;
        Cache[typeof(Union)] = new UnionSerializer<Union>();
    }
}

internal static partial class GeneratedSerializerRegistry
{
    public static void Register(ConcurrentDictionary<Type, Func<ISerializer>> factories)
    {
        _ = factories;
    }

    public static bool TryCreate(Type type, out ISerializer? serializer)
    {
        _ = type;
        serializer = null;
        return false;
    }
}
