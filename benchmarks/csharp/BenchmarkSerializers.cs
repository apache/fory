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

    object? Deserialize(byte[] payload);
}

internal sealed class ForySerializer<TWrite, TRead> : IBenchmarkSerializer<TWrite>
{
    private readonly ForyRuntime _writer = ForyRuntime.Builder().Compatible(true).Build();
    private readonly ForyRuntime _reader = ForyRuntime.Builder().Compatible(true).Build();

    public ForySerializer(bool schemaMismatch)
    {
        BenchmarkTypeRegistry.RegisterAll(_writer);
        if (schemaMismatch)
        {
            BenchmarkTypeRegistry.RegisterAllV2(_reader);
        }
        else
        {
            BenchmarkTypeRegistry.RegisterAll(_reader);
        }
    }

    public string Name => "fory";

    public byte[] Serialize(TWrite value)
    {
        return _writer.Serialize(value);
    }

    public object? Deserialize(byte[] payload)
    {
        return _reader.Deserialize<TRead>(payload);
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
        fory.Register<NumericStructList>(6);
        fory.Register<SampleList>(7);
        fory.Register<MediaContentList>(8);
        fory.Register<Player>(9);
        fory.Register<MediaSize>(10);
    }

    public static void RegisterAllV2(ForyRuntime fory)
    {
        // Keep user type IDs identical to v1 so compatible reads exercise schema evolution.
        fory.Register<NumericStructV2>(1);
        fory.Register<SampleV2>(2);
        fory.Register<MediaV2>(3);
        fory.Register<ImageV2>(4);
        fory.Register<MediaContentV2>(5);
        fory.Register<NumericStructListV2>(6);
        fory.Register<SampleListV2>(7);
        fory.Register<MediaContentListV2>(8);
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

    public object? Deserialize(byte[] payload)
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

    public object? Deserialize(byte[] payload)
    {
        return MessagePackSerializer.Deserialize<T>(payload, _options);
    }
}
