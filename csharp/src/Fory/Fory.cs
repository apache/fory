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

/// <summary>
/// Core serializer runtime.
/// This type is optimized for single-threaded reuse and must not be shared concurrently across threads.
/// Use <see cref="ThreadSafeFory"/> for concurrent access.
/// </summary>
public sealed class Fory
{
    private readonly TypeResolver _typeResolver;
    private WriteContext _writeContext;
    private ReadContext _readContext;

    internal Fory(Config config)
    {
        Config = config;
        _typeResolver = new TypeResolver();
        _writeContext = new WriteContext(
            new ByteWriter(),
            _typeResolver,
            Config.TrackRef,
            Config.Compatible,
            Config.CheckStructVersion,
            new CompatibleTypeDefWriteState(),
            new MetaStringWriteState());
        _readContext = new ReadContext(
            new ByteReader(Array.Empty<byte>()),
            _typeResolver,
            Config.TrackRef,
            Config.Compatible,
            Config.CheckStructVersion,
            new CompatibleTypeDefReadState(),
            new MetaStringReadState());
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
        where TSerializer : Serializer<T>, new()
    {
        TypeInfo typeInfo = _typeResolver.RegisterSerializer<T, TSerializer>();
        _typeResolver.Register(typeof(T), typeId, typeInfo);
        return this;
    }

    public Fory Register<T, TSerializer>(string typeNamespace, string typeName)
        where TSerializer : Serializer<T>, new()
    {
        TypeInfo typeInfo = _typeResolver.RegisterSerializer<T, TSerializer>();
        _typeResolver.Register(typeof(T), typeNamespace, typeName, typeInfo);
        return this;
    }

    public byte[] Serialize<T>(in T value)
    {
        ByteWriter writer = _writeContext.Writer;
        writer.Reset();
        Serializer<T> serializer = _typeResolver.GetSerializer<T>();
        TypeInfo typeInfo = _typeResolver.GetTypeInfo<T>();
        bool isNone = typeInfo.IsNullableType && value is null;
        WriteHead(writer, isNone);
        if (!isNone)
        {
            _writeContext.ResetFor(writer);
            RefMode refMode = Config.TrackRef ? RefMode.Tracking : RefMode.NullOnly;
            serializer.Write(_writeContext, value, refMode, true, false);
            _writeContext.ResetObjectState();
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
        ByteReader reader = _readContext.Reader;
        reader.Reset(payload);
        T value = DeserializeFromReader<T>(reader);
        if (reader.Remaining != 0)
        {
            throw new InvalidDataException($"unexpected trailing bytes after deserializing {typeof(T)}");
        }

        return value;
    }

    public T Deserialize<T>(byte[] payload)
    {
        ByteReader reader = _readContext.Reader;
        reader.Reset(payload);
        T value = DeserializeFromReader<T>(reader);
        if (reader.Remaining != 0)
        {
            throw new InvalidDataException($"unexpected trailing bytes after deserializing {typeof(T)}");
        }

        return value;
    }

    public T Deserialize<T>(ref ReadOnlySequence<byte> payload)
    {
        byte[] bytes = payload.ToArray();
        ByteReader reader = _readContext.Reader;
        reader.Reset(bytes);
        T value = DeserializeFromReader<T>(reader);
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
            throw new InvalidDataException("xlang bitmap mismatch");
        }

        return (bitmap & ForyHeaderFlag.IsNull) != 0;
    }

    private T DeserializeFromReader<T>(ByteReader reader)
    {
        bool isNone = ReadHead(reader);
        Serializer<T> serializer = _typeResolver.GetSerializer<T>();
        if (isNone)
        {
            return serializer.DefaultValue;
        }

        _readContext.ResetFor(reader);
        RefMode refMode = Config.TrackRef ? RefMode.Tracking : RefMode.NullOnly;
        T value = serializer.Read(_readContext, refMode, true);
        _readContext.ResetObjectState();
        return value;
    }

}
