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
using System.Runtime.CompilerServices;

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
            Config.CheckStructVersion);
        _readContext = new ReadContext(
            new ByteReader(Array.Empty<byte>()),
            _typeResolver,
            Config);
    }

    /// <summary>
    /// Gets the immutable runtime configuration.
    /// </summary>
    public Config Config { get; }

    /// <summary>
    /// Creates a new <see cref="ForyBuilder"/> for configuring and building runtimes.
    /// </summary>
    /// <returns>A new builder instance.</returns>
    public static ForyBuilder Builder()
    {
        return new ForyBuilder();
    }

    /// <summary>
    /// Registers a user type by numeric type identifier.
    /// </summary>
    /// <typeparam name="T">Type to register.</typeparam>
    /// <param name="typeId">Numeric type identifier used on the wire.</param>
    /// <returns>The same runtime instance.</returns>
    public Fory Register<T>(uint typeId)
    {
        _typeResolver.Register(typeof(T), typeId);
        return this;
    }

    /// <summary>
    /// Registers a user type by name.
    /// </summary>
    /// <typeparam name="T">Type to register.</typeparam>
    /// <param name="name">Name used on the wire. A dotted name is split at the last dot.</param>
    /// <returns>The same runtime instance.</returns>
    public Fory Register<T>(string name)
    {
        (string namespaceName, string typeName) = TypeResolver.SplitTypeName(name);
        _typeResolver.Register(typeof(T), namespaceName, typeName);
        return this;
    }

    /// <summary>
    /// Registers a user type by namespace and name.
    /// </summary>
    /// <typeparam name="T">Type to register.</typeparam>
    /// <param name="typeNamespace">Namespace used on the wire.</param>
    /// <param name="typeName">Type name used on the wire.</param>
    /// <returns>The same runtime instance.</returns>
    public Fory Register<T>(string typeNamespace, string typeName)
    {
        _typeResolver.Register(typeof(T), typeNamespace, typeName);
        return this;
    }

    /// <summary>
    /// Registers a user type by numeric type identifier with a custom serializer.
    /// </summary>
    /// <typeparam name="T">Type to register.</typeparam>
    /// <typeparam name="TSerializer">Serializer implementation used for <typeparamref name="T"/>.</typeparam>
    /// <param name="typeId">Numeric type identifier used on the wire.</param>
    /// <returns>The same runtime instance.</returns>
    public Fory Register<T, TSerializer>(uint typeId)
        where TSerializer : Serializer<T>, new()
    {
        TypeInfo typeInfo = _typeResolver.RegisterSerializer<T, TSerializer>();
        _typeResolver.Register(typeof(T), typeId, typeInfo);
        return this;
    }

    /// <summary>
    /// Registers a user type by name with a custom serializer.
    /// </summary>
    /// <typeparam name="T">Type to register.</typeparam>
    /// <typeparam name="TSerializer">Serializer implementation used for <typeparamref name="T"/>.</typeparam>
    /// <param name="name">Name used on the wire. A dotted name is split at the last dot.</param>
    /// <returns>The same runtime instance.</returns>
    public Fory Register<T, TSerializer>(string name)
        where TSerializer : Serializer<T>, new()
    {
        (string namespaceName, string typeName) = TypeResolver.SplitTypeName(name);
        TypeInfo typeInfo = _typeResolver.RegisterSerializer<T, TSerializer>();
        _typeResolver.Register(typeof(T), namespaceName, typeName, typeInfo);
        return this;
    }

    /// <summary>
    /// Registers a user type by namespace and name with a custom serializer.
    /// </summary>
    /// <typeparam name="T">Type to register.</typeparam>
    /// <typeparam name="TSerializer">Serializer implementation used for <typeparamref name="T"/>.</typeparam>
    /// <param name="typeNamespace">Namespace used on the wire.</param>
    /// <param name="typeName">Type name used on the wire.</param>
    /// <returns>The same runtime instance.</returns>
    public Fory Register<T, TSerializer>(string typeNamespace, string typeName)
        where TSerializer : Serializer<T>, new()
    {
        TypeResolver.ValidateSplitTypeName(typeNamespace, typeName);
        TypeInfo typeInfo = _typeResolver.RegisterSerializer<T, TSerializer>();
        _typeResolver.Register(typeof(T), typeNamespace, typeName, typeInfo);
        return this;
    }

    /// <summary>
    /// Serializes a value into a new byte array containing one Fory frame.
    /// </summary>
    /// <typeparam name="T">Value type.</typeparam>
    /// <param name="value">Value to serialize.</param>
    /// <returns>Serialized bytes.</returns>
    public byte[] Serialize<T>(in T value)
    {
        ByteWriter writer = _writeContext.Writer;
        writer.Reset();
        Serializer<T> serializer = _typeResolver.GetSerializer<T>();
        WriteHead(writer);
        _writeContext.ResetFor(writer);
        RefMode refMode = Config.TrackRef ? RefMode.Tracking : RefMode.NullOnly;
        serializer.Write(_writeContext, value, refMode, true, false);
        _writeContext.RefWriter.Reset();

        return writer.ToArray();
    }

    /// <summary>
    /// Serializes a value and writes one Fory frame into the provided buffer writer.
    /// </summary>
    /// <typeparam name="T">Value type.</typeparam>
    /// <param name="output">Destination writer.</param>
    /// <param name="value">Value to serialize.</param>
    public void Serialize<T>(IBufferWriter<byte> output, in T value)
    {
        byte[] payload = Serialize(value);
        output.Write(payload);
    }

    /// <summary>
    /// Deserializes a value from one Fory frame in the provided span.
    /// </summary>
    /// <typeparam name="T">Target type.</typeparam>
    /// <param name="payload">Serialized bytes containing exactly one frame.</param>
    /// <returns>Deserialized value.</returns>
    /// <exception cref="InvalidDataException">Thrown when trailing bytes remain after decoding.</exception>
    public T Deserialize<T>(ReadOnlySpan<byte> payload)
    {
        ByteReader reader = _readContext.Reader;
        reader.Reset(payload);
        T value = DeserializeFromReader<T>(reader);
        if (reader.Remaining != 0)
        {
            ThrowUnexpectedTrailingBytes<T>();
        }

        return value;
    }

    /// <summary>
    /// Deserializes a value from one Fory frame in the provided byte array.
    /// </summary>
    /// <typeparam name="T">Target type.</typeparam>
    /// <param name="payload">Serialized bytes containing exactly one frame.</param>
    /// <returns>Deserialized value.</returns>
    /// <exception cref="InvalidDataException">Thrown when trailing bytes remain after decoding.</exception>
    public T Deserialize<T>(byte[] payload)
    {
        ByteReader reader = _readContext.Reader;
        reader.Reset(payload);
        T value = DeserializeFromReader<T>(reader);
        if (reader.Remaining != 0)
        {
            ThrowUnexpectedTrailingBytes<T>();
        }

        return value;
    }

    /// <summary>
    /// Deserializes a value from the head of a framed sequence and advances the sequence.
    /// </summary>
    /// <typeparam name="T">Target type.</typeparam>
    /// <param name="payload">Input sequence. On success, sliced past the consumed frame.</param>
    /// <returns>Deserialized value.</returns>
    public T Deserialize<T>(ref ReadOnlySequence<byte> payload)
    {
        byte[] bytes = payload.ToArray();
        ByteReader reader = _readContext.Reader;
        reader.Reset(bytes);
        T value = DeserializeFromReader<T>(reader);
        payload = payload.Slice(reader.Cursor);
        return value;
    }


    /// <summary>
    /// Writes the frame header for a payload.
    /// </summary>
    /// <param name="writer">Destination writer.</param>
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal void WriteHead(ByteWriter writer)
    {
        writer.WriteUInt8(ForyHeaderFlag.IsXlang);
    }

    /// <summary>
    /// Reads and validates the frame header.
    /// </summary>
    /// <param name="reader">Source reader.</param>
    /// <exception cref="InvalidDataException">Thrown when the peer xlang bitmap does not match this runtime mode.</exception>
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal void ReadHead(ByteReader reader)
    {
        byte bitmap = reader.ReadUInt8();
        if (bitmap == ForyHeaderFlag.IsXlang)
        {
            return;
        }
        ThrowInvalidRootHeader(bitmap);
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void ThrowUnexpectedTrailingBytes<T>() =>
        throw new InvalidDataException($"unexpected trailing bytes after deserializing {typeof(T)}");

    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void ThrowInvalidRootHeader(byte bitmap) =>
        throw new InvalidDataException((bitmap & ForyHeaderFlag.IsXlang) == 0
            ? "xlang bitmap mismatch"
            : $"unsupported root header bitmap 0x{bitmap:X2}");

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private T DeserializeFromReader<T>(ByteReader reader)
    {
        ReadHead(reader);
        Serializer<T> serializer = _typeResolver.GetSerializer<T>();
        ReadContext readContext = _readContext;
        readContext.ResetFor(reader);
        RefMode refMode = Config.TrackRef ? RefMode.Tracking : RefMode.NullOnly;
        T value = serializer.Read(readContext, refMode, true);
        readContext.RefReader.Reset();
        readContext._typeMetaType = null;
        readContext._typeMeta = null;
        readContext._typeMetaByType?.ClearKeys();
        readContext._readTypeInfoByType.ClearKeys();
        readContext._reservedRefIds.Clear();
        readContext._cachedTypeMetaType = null;
        readContext._cachedTypeMeta = null;
        readContext._currentDynamicReadDepth = 0;
        return value;
    }

}
