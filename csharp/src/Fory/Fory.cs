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

using System.Buffers;

namespace Apache.Fory;

public sealed class Fory
{
    private readonly TypeResolver _typeResolver;

    internal Fory(Config config)
    {
        Config = config;
        _typeResolver = new TypeResolver();
    }

    public Config Config { get; }

    public static ForyBuilder Builder()
    {
        return new ForyBuilder();
    }

    public Fory Register<T>(uint typeId)
    {
        _typeResolver.Register(typeof(T), typeId);
        return this;
    }

    public Fory Register<T>(string typeName)
    {
        _typeResolver.Register(typeof(T), string.Empty, typeName);
        return this;
    }

    public Fory Register<T>(string typeNamespace, string typeName)
    {
        _typeResolver.Register(typeof(T), typeNamespace, typeName);
        return this;
    }

    public Fory Register<T, TSerializer>(uint typeId)
        where TSerializer : IStaticSerializer<TSerializer, T>
    {
        SerializerRegistry.RegisterCustom<T, TSerializer>();
        _typeResolver.Register(typeof(T), typeId);
        return this;
    }

    public Fory Register<T, TSerializer>(string typeNamespace, string typeName)
        where TSerializer : IStaticSerializer<TSerializer, T>
    {
        SerializerRegistry.RegisterCustom<T, TSerializer>();
        _typeResolver.Register(typeof(T), typeNamespace, typeName);
        return this;
    }

    public byte[] Serialize<T>(in T value)
    {
        ByteWriter writer = new();
        Serializer<T> binding = TypedSerializerBindingCache<T>.Get();
        bool isNone = binding.IsNone(value);
        WriteHead(writer, isNone);
        if (!isNone)
        {
            WriteContext context = new(
                writer,
                _typeResolver,
                Config.TrackRef,
                Config.Compatible,
                new CompatibleTypeDefWriteState(),
                new MetaStringWriteState());
            RefMode refMode = Config.TrackRef ? RefMode.Tracking : RefMode.NullOnly;
            binding.Write(ref context, value, refMode, true, false);
            context.ResetObjectState();
        }

        return writer.ToArray();
    }

    public void Serialize<T>(IBufferWriter<byte> output, in T value)
    {
        byte[] payload = Serialize(value);
        output.Write(payload);
    }

    public T Deserialize<T>(ReadOnlySpan<byte> payload)
    {
        ByteReader reader = new(payload);
        T value = DeserializeFromReader<T>(reader);
        if (reader.Remaining != 0)
        {
            throw new ForyInvalidDataException($"unexpected trailing bytes after deserializing {typeof(T)}");
        }

        return value;
    }

    public T Deserialize<T>(ref ReadOnlySequence<byte> payload)
    {
        byte[] bytes = payload.ToArray();
        ByteReader reader = new(bytes);
        T value = DeserializeFromReader<T>(reader);
        payload = payload.Slice(reader.Cursor);
        return value;
    }

    public byte[] SerializeObject(object? value)
    {
        ByteWriter writer = new();
        bool isNone = value is null;
        WriteHead(writer, isNone);
        if (!isNone)
        {
            WriteContext context = new(
                writer,
                _typeResolver,
                Config.TrackRef,
                Config.Compatible,
                new CompatibleTypeDefWriteState(),
                new MetaStringWriteState());
            RefMode refMode = Config.TrackRef ? RefMode.Tracking : RefMode.NullOnly;
            DynamicAnyCodec.WriteAny(ref context, value, refMode, true, false);
            context.ResetObjectState();
        }

        return writer.ToArray();
    }

    public void SerializeObject(IBufferWriter<byte> output, object? value)
    {
        byte[] payload = SerializeObject(value);
        output.Write(payload);
    }

    public object? DeserializeObject(ReadOnlySpan<byte> payload)
    {
        ByteReader reader = new(payload);
        object? value = DeserializeObjectFromReader(reader);
        if (reader.Remaining != 0)
        {
            throw new ForyInvalidDataException("unexpected trailing bytes after deserializing dynamic object");
        }

        return value;
    }

    public object? DeserializeObject(ref ReadOnlySequence<byte> payload)
    {
        byte[] bytes = payload.ToArray();
        ByteReader reader = new(bytes);
        object? value = DeserializeObjectFromReader(reader);
        payload = payload.Slice(reader.Cursor);
        return value;
    }

    public void WriteHead(ByteWriter writer, bool isNone)
    {
        byte bitmap = 0;
        if (Config.Xlang)
        {
            bitmap |= ForyHeaderFlag.IsXlang;
        }

        if (isNone)
        {
            bitmap |= ForyHeaderFlag.IsNull;
        }

        writer.WriteUInt8(bitmap);
    }

    public bool ReadHead(ByteReader reader)
    {
        byte bitmap = reader.ReadUInt8();
        bool peerIsXlang = (bitmap & ForyHeaderFlag.IsXlang) != 0;
        if (peerIsXlang != Config.Xlang)
        {
            throw new ForyInvalidDataException("xlang bitmap mismatch");
        }

        return (bitmap & ForyHeaderFlag.IsNull) != 0;
    }

    private T DeserializeFromReader<T>(ByteReader reader)
    {
        bool isNone = ReadHead(reader);
        Serializer<T> binding = TypedSerializerBindingCache<T>.Get();
        if (isNone)
        {
            return binding.DefaultValue;
        }

        ReadContext context = new(
            reader,
            _typeResolver,
            Config.TrackRef,
            Config.Compatible,
            new CompatibleTypeDefReadState(),
            new MetaStringReadState());
        RefMode refMode = Config.TrackRef ? RefMode.Tracking : RefMode.NullOnly;
        T value = binding.Read(ref context, refMode, true);
        context.ResetObjectState();
        return value;
    }

    private object? DeserializeObjectFromReader(ByteReader reader)
    {
        bool isNone = ReadHead(reader);
        if (isNone)
        {
            return null;
        }

        ReadContext context = new(
            reader,
            _typeResolver,
            Config.TrackRef,
            Config.Compatible,
            new CompatibleTypeDefReadState(),
            new MetaStringReadState());
        RefMode refMode = Config.TrackRef ? RefMode.Tracking : RefMode.NullOnly;
        object? value = DynamicAnyCodec.ReadAny(ref context, refMode, true);
        context.ResetObjectState();
        return value;
    }
}
