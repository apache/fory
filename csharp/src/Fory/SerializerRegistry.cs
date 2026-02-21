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
using System.Threading;

namespace Apache.Fory;

public static class SerializerRegistry
{
    private static readonly ConcurrentDictionary<Type, SerializerBinding> Cache = new();
    private static readonly ConcurrentDictionary<Type, Func<SerializerBinding>> GeneratedFactories = new();
    private static int _version;

    static SerializerRegistry()
    {
        RegisterBuiltins();
        GeneratedSerializerRegistry.Register(GeneratedFactories);
    }

    public static void RegisterGenerated<T, TSerializer>()
        where TSerializer : IStaticSerializer<TSerializer, T>
    {
        GeneratedFactories[typeof(T)] = StaticSerializerBindingFactory.Create<T, TSerializer>;
        Cache.TryRemove(typeof(T), out _);
        Interlocked.Increment(ref _version);
    }

    public static void RegisterCustom<T, TSerializer>()
        where TSerializer : IStaticSerializer<TSerializer, T>
    {
        Cache[typeof(T)] = StaticSerializerBindingFactory.Create<T, TSerializer>();
        Interlocked.Increment(ref _version);
    }

    internal static void RegisterCustom(Type type, SerializerBinding serializerBinding)
    {
        Cache[type] = serializerBinding;
        Interlocked.Increment(ref _version);
    }

    internal static int Version => Volatile.Read(ref _version);

    public static Serializer<T> Get<T>()
    {
        return TypedSerializerBindingCache<T>.Get();
    }

    internal static SerializerBinding GetBinding(Type type)
    {
        return Cache.GetOrAdd(type, Create);
    }

    private static SerializerBinding Create(Type type)
    {
        if (GeneratedFactories.TryGetValue(type, out Func<SerializerBinding>? generatedFactory))
        {
            return generatedFactory();
        }

        if (type == typeof(bool))
        {
            return StaticSerializerBindingFactory.Create<bool, BoolSerializer>();
        }

        if (type == typeof(sbyte))
        {
            return StaticSerializerBindingFactory.Create<sbyte, Int8Serializer>();
        }

        if (type == typeof(short))
        {
            return StaticSerializerBindingFactory.Create<short, Int16Serializer>();
        }

        if (type == typeof(int))
        {
            return StaticSerializerBindingFactory.Create<int, Int32Serializer>();
        }

        if (type == typeof(long))
        {
            return StaticSerializerBindingFactory.Create<long, Int64Serializer>();
        }

        if (type == typeof(byte))
        {
            return StaticSerializerBindingFactory.Create<byte, UInt8Serializer>();
        }

        if (type == typeof(ushort))
        {
            return StaticSerializerBindingFactory.Create<ushort, UInt16Serializer>();
        }

        if (type == typeof(uint))
        {
            return StaticSerializerBindingFactory.Create<uint, UInt32Serializer>();
        }

        if (type == typeof(ulong))
        {
            return StaticSerializerBindingFactory.Create<ulong, UInt64Serializer>();
        }

        if (type == typeof(float))
        {
            return StaticSerializerBindingFactory.Create<float, Float32Serializer>();
        }

        if (type == typeof(double))
        {
            return StaticSerializerBindingFactory.Create<double, Float64Serializer>();
        }

        if (type == typeof(string))
        {
            return StaticSerializerBindingFactory.Create<string, StringSerializer>();
        }

        if (type == typeof(byte[]))
        {
            return StaticSerializerBindingFactory.Create<byte[], BinarySerializer>();
        }

        if (type == typeof(DateOnly))
        {
            return StaticSerializerBindingFactory.Create<DateOnly, DateOnlySerializer>();
        }

        if (type == typeof(DateTimeOffset))
        {
            return StaticSerializerBindingFactory.Create<DateTimeOffset, DateTimeOffsetSerializer>();
        }

        if (type == typeof(DateTime))
        {
            return StaticSerializerBindingFactory.Create<DateTime, DateTimeSerializer>();
        }

        if (type == typeof(TimeSpan))
        {
            return StaticSerializerBindingFactory.Create<TimeSpan, TimeSpanSerializer>();
        }

        if (type == typeof(ForyInt32Fixed))
        {
            return StaticSerializerBindingFactory.Create<ForyInt32Fixed, ForyInt32FixedSerializer>();
        }

        if (type == typeof(ForyInt64Fixed))
        {
            return StaticSerializerBindingFactory.Create<ForyInt64Fixed, ForyInt64FixedSerializer>();
        }

        if (type == typeof(ForyInt64Tagged))
        {
            return StaticSerializerBindingFactory.Create<ForyInt64Tagged, ForyInt64TaggedSerializer>();
        }

        if (type == typeof(ForyUInt32Fixed))
        {
            return StaticSerializerBindingFactory.Create<ForyUInt32Fixed, ForyUInt32FixedSerializer>();
        }

        if (type == typeof(ForyUInt64Fixed))
        {
            return StaticSerializerBindingFactory.Create<ForyUInt64Fixed, ForyUInt64FixedSerializer>();
        }

        if (type == typeof(ForyUInt64Tagged))
        {
            return StaticSerializerBindingFactory.Create<ForyUInt64Tagged, ForyUInt64TaggedSerializer>();
        }

        if (type == typeof(object))
        {
            return StaticSerializerBindingFactory.Create<object?, DynamicAnyObjectSerializer>();
        }

        if (typeof(Union).IsAssignableFrom(type))
        {
            Type serializerType = typeof(UnionSerializer<>).MakeGenericType(type);
            return StaticSerializerBindingFactory.Create(type, serializerType);
        }

        if (type.IsEnum)
        {
            Type serializerType = typeof(EnumSerializer<>).MakeGenericType(type);
            return StaticSerializerBindingFactory.Create(type, serializerType);
        }

        if (type.IsArray)
        {
            Type elementType = type.GetElementType()!;
            Type serializerType = typeof(ArraySerializer<>).MakeGenericType(elementType);
            return StaticSerializerBindingFactory.Create(type, serializerType);
        }

        if (type.IsGenericType)
        {
            Type genericType = type.GetGenericTypeDefinition();
            Type[] genericArgs = type.GetGenericArguments();
            if (genericType == typeof(Nullable<>))
            {
                Type serializerType = typeof(NullableSerializer<>).MakeGenericType(genericArgs[0]);
                return StaticSerializerBindingFactory.Create(type, serializerType);
            }

            if (genericType == typeof(List<>))
            {
                Type serializerType = typeof(ListSerializer<>).MakeGenericType(genericArgs[0]);
                return StaticSerializerBindingFactory.Create(type, serializerType);
            }

            if (genericType == typeof(HashSet<>))
            {
                Type serializerType = typeof(SetSerializer<>).MakeGenericType(genericArgs[0]);
                return StaticSerializerBindingFactory.Create(type, serializerType);
            }

            if (genericType == typeof(Dictionary<,>))
            {
                Type serializerType = typeof(DictionarySerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return StaticSerializerBindingFactory.Create(type, serializerType);
            }

            if (genericType == typeof(NullableKeyDictionary<,>))
            {
                Type serializerType = typeof(NullableKeyDictionarySerializer<,>).MakeGenericType(genericArgs[0], genericArgs[1]);
                return StaticSerializerBindingFactory.Create(type, serializerType);
            }
        }

        if (GeneratedSerializerRegistry.TryCreate(type, out SerializerBinding? generatedSerializer))
        {
            return generatedSerializer!;
        }

        throw new ForyTypeNotRegisteredException($"No serializer available for {type}");
    }

    private static void RegisterBuiltins()
    {
        Cache[typeof(bool)] = StaticSerializerBindingFactory.Create<bool, BoolSerializer>();
        Cache[typeof(sbyte)] = StaticSerializerBindingFactory.Create<sbyte, Int8Serializer>();
        Cache[typeof(short)] = StaticSerializerBindingFactory.Create<short, Int16Serializer>();
        Cache[typeof(int)] = StaticSerializerBindingFactory.Create<int, Int32Serializer>();
        Cache[typeof(long)] = StaticSerializerBindingFactory.Create<long, Int64Serializer>();
        Cache[typeof(byte)] = StaticSerializerBindingFactory.Create<byte, UInt8Serializer>();
        Cache[typeof(ushort)] = StaticSerializerBindingFactory.Create<ushort, UInt16Serializer>();
        Cache[typeof(uint)] = StaticSerializerBindingFactory.Create<uint, UInt32Serializer>();
        Cache[typeof(ulong)] = StaticSerializerBindingFactory.Create<ulong, UInt64Serializer>();
        Cache[typeof(float)] = StaticSerializerBindingFactory.Create<float, Float32Serializer>();
        Cache[typeof(double)] = StaticSerializerBindingFactory.Create<double, Float64Serializer>();
        Cache[typeof(string)] = StaticSerializerBindingFactory.Create<string, StringSerializer>();
        Cache[typeof(byte[])] = StaticSerializerBindingFactory.Create<byte[], BinarySerializer>();
        Cache[typeof(object)] = StaticSerializerBindingFactory.Create<object?, DynamicAnyObjectSerializer>();
        Cache[typeof(Union)] = StaticSerializerBindingFactory.Create<Union, UnionSerializer<Union>>();
    }
}

internal static partial class GeneratedSerializerRegistry
{
    public static void Register(ConcurrentDictionary<Type, Func<SerializerBinding>> factories)
    {
        _ = factories;
    }

    public static bool TryCreate(Type type, out SerializerBinding? serializer)
    {
        _ = type;
        serializer = null;
        return false;
    }
}
