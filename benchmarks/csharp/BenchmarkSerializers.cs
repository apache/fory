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

using Apache.Fory;
using MessagePack;
using MessagePack.Resolvers;
using ProtoBuf;
using ForyRuntime = Apache.Fory.Fory;
using ProtobufNetSerializer = ProtoBuf.Serializer;

namespace Apache.Fory.Benchmarks.CSharp;

internal interface IBenchmarkSerializer<T>
{
    string Name { get; }

    byte[] Serialize(T value);

    T Deserialize(byte[] payload);
}

internal sealed class ForySerializer<T> : IBenchmarkSerializer<T>
{
    private readonly ForyRuntime _fory = ForyRuntime.Builder().Compatible(true).Build();

    public ForySerializer()
    {
        BenchmarkTypeRegistry.RegisterAll(_fory);
    }

    public string Name => "fory";

    public byte[] Serialize(T value)
    {
        return _fory.Serialize(value);
    }

    public T Deserialize(byte[] payload)
    {
        return _fory.Deserialize<T>(payload);
    }
}

internal static class BenchmarkTypeRegistry
{
    public static void RegisterAll(ForyRuntime fory)
    {
        // Keep user type IDs aligned with C++ benchmark registration.
        fory.Register<NumericStruct>(1);
        fory.Register<Sample>(2);
        fory.Register<Media>(3);
        fory.Register<Image>(4);
        fory.Register<MediaContent>(5);
        fory.Register<StructList>(6);
        fory.Register<SampleList>(7);
        fory.Register<MediaContentList>(8);
        fory.Register<Player>(9);
        fory.Register<MediaSize>(10);
    }
}

internal sealed class ProtobufSerializer<T> : IBenchmarkSerializer<T>
{
    private readonly MemoryStream _writeStream = new(256);

    public string Name => "protobuf";

    public byte[] Serialize(T value)
    {
        _writeStream.Position = 0;
        _writeStream.SetLength(0);
        ProtobufNetSerializer.Serialize(_writeStream, value);
        return _writeStream.ToArray();
    }

    public T Deserialize(byte[] payload)
    {
        using MemoryStream stream = new(payload, writable: false);
        return ProtobufNetSerializer.Deserialize<T>(stream);
    }
}

internal sealed class MessagePackRuntimeSerializer<T> : IBenchmarkSerializer<T>
{
    private readonly MessagePackSerializerOptions _options = StandardResolver.Options;

    public string Name => "msgpack";

    public byte[] Serialize(T value)
    {
        return MessagePackSerializer.Serialize(value, _options);
    }

    public T Deserialize(byte[] payload)
    {
        return MessagePackSerializer.Deserialize<T>(payload, _options);
    }
}
