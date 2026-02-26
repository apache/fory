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

using System.Collections;
using System.Collections.Concurrent;

namespace Apache.Fory;

internal static class PrimitiveDictionaryHeader
{
    public static void WriteMapChunkTypeInfo(
        WriteContext context,
        bool keyDeclared,
        bool valueDeclared,
        TypeId keyTypeId,
        TypeId valueTypeId)
    {
        if (!keyDeclared)
        {
            context.Writer.WriteUInt8((byte)keyTypeId);
        }

        if (!valueDeclared)
        {
            context.Writer.WriteUInt8((byte)valueTypeId);
        }
    }
}

internal interface IPrimitiveDictionaryCodec<T>
{
    static abstract TypeId WireTypeId { get; }

    static abstract bool IsNullable { get; }

    static abstract T DefaultValue { get; }

    static abstract bool IsNone(T value);

    static abstract void Write(WriteContext context, T value);

    static abstract T Read(ReadContext context);
}

internal readonly struct StringPrimitiveDictionaryCodec : IPrimitiveDictionaryCodec<string>
{
    public static TypeId WireTypeId => TypeId.String;

    public static bool IsNullable => true;

    public static string DefaultValue => null!;

    public static bool IsNone(string value) => value is null;

    public static void Write(WriteContext context, string value)
    {
        StringSerializer.WriteString(context, value ?? string.Empty);
    }

    public static string Read(ReadContext context)
    {
        return StringSerializer.ReadString(context);
    }
}

internal readonly struct BoolPrimitiveDictionaryCodec : IPrimitiveDictionaryCodec<bool>
{
    public static TypeId WireTypeId => TypeId.Bool;

    public static bool IsNullable => false;

    public static bool DefaultValue => false;

    public static bool IsNone(bool value) => false;

    public static void Write(WriteContext context, bool value)
    {
        context.Writer.WriteUInt8(value ? (byte)1 : (byte)0);
    }

    public static bool Read(ReadContext context)
    {
        return context.Reader.ReadUInt8() != 0;
    }
}

internal readonly struct Int8PrimitiveDictionaryCodec : IPrimitiveDictionaryCodec<sbyte>
{
    public static TypeId WireTypeId => TypeId.Int8;

    public static bool IsNullable => false;

    public static sbyte DefaultValue => 0;

    public static bool IsNone(sbyte value) => false;

    public static void Write(WriteContext context, sbyte value)
    {
        context.Writer.WriteInt8(value);
    }

    public static sbyte Read(ReadContext context)
    {
        return context.Reader.ReadInt8();
    }
}

internal readonly struct Int16PrimitiveDictionaryCodec : IPrimitiveDictionaryCodec<short>
{
    public static TypeId WireTypeId => TypeId.Int16;

    public static bool IsNullable => false;

    public static short DefaultValue => 0;

    public static bool IsNone(short value) => false;

    public static void Write(WriteContext context, short value)
    {
        context.Writer.WriteInt16(value);
    }

    public static short Read(ReadContext context)
    {
        return context.Reader.ReadInt16();
    }
}

internal readonly struct Int32PrimitiveDictionaryCodec : IPrimitiveDictionaryCodec<int>
{
    public static TypeId WireTypeId => TypeId.VarInt32;

    public static bool IsNullable => false;

    public static int DefaultValue => 0;

    public static bool IsNone(int value) => false;

    public static void Write(WriteContext context, int value)
    {
        context.Writer.WriteVarInt32(value);
    }

    public static int Read(ReadContext context)
    {
        return context.Reader.ReadVarInt32();
    }
}

internal readonly struct Int64PrimitiveDictionaryCodec : IPrimitiveDictionaryCodec<long>
{
    public static TypeId WireTypeId => TypeId.VarInt64;

    public static bool IsNullable => false;

    public static long DefaultValue => 0;

    public static bool IsNone(long value) => false;

    public static void Write(WriteContext context, long value)
    {
        context.Writer.WriteVarInt64(value);
    }

    public static long Read(ReadContext context)
    {
        return context.Reader.ReadVarInt64();
    }
}

internal readonly struct UInt16PrimitiveDictionaryCodec : IPrimitiveDictionaryCodec<ushort>
{
    public static TypeId WireTypeId => TypeId.UInt16;

    public static bool IsNullable => false;

    public static ushort DefaultValue => 0;

    public static bool IsNone(ushort value) => false;

    public static void Write(WriteContext context, ushort value)
    {
        context.Writer.WriteUInt16(value);
    }

    public static ushort Read(ReadContext context)
    {
        return context.Reader.ReadUInt16();
    }
}

internal readonly struct UInt32PrimitiveDictionaryCodec : IPrimitiveDictionaryCodec<uint>
{
    public static TypeId WireTypeId => TypeId.VarUInt32;

    public static bool IsNullable => false;

    public static uint DefaultValue => 0;

    public static bool IsNone(uint value) => false;

    public static void Write(WriteContext context, uint value)
    {
        context.Writer.WriteVarUInt32(value);
    }

    public static uint Read(ReadContext context)
    {
        return context.Reader.ReadVarUInt32();
    }
}

internal readonly struct UInt64PrimitiveDictionaryCodec : IPrimitiveDictionaryCodec<ulong>
{
    public static TypeId WireTypeId => TypeId.VarUInt64;

    public static bool IsNullable => false;

    public static ulong DefaultValue => 0;

    public static bool IsNone(ulong value) => false;

    public static void Write(WriteContext context, ulong value)
    {
        context.Writer.WriteVarUInt64(value);
    }

    public static ulong Read(ReadContext context)
    {
        return context.Reader.ReadVarUInt64();
    }
}

internal readonly struct Float32PrimitiveDictionaryCodec : IPrimitiveDictionaryCodec<float>
{
    public static TypeId WireTypeId => TypeId.Float32;

    public static bool IsNullable => false;

    public static float DefaultValue => 0;

    public static bool IsNone(float value) => false;

    public static void Write(WriteContext context, float value)
    {
        context.Writer.WriteFloat32(value);
    }

    public static float Read(ReadContext context)
    {
        return context.Reader.ReadFloat32();
    }
}

internal readonly struct Float64PrimitiveDictionaryCodec : IPrimitiveDictionaryCodec<double>
{
    public static TypeId WireTypeId => TypeId.Float64;

    public static bool IsNullable => false;

    public static double DefaultValue => 0;

    public static bool IsNone(double value) => false;

    public static void Write(WriteContext context, double value)
    {
        context.Writer.WriteFloat64(value);
    }

    public static double Read(ReadContext context)
    {
        return context.Reader.ReadFloat64();
    }
}

internal interface IPrimitiveMapWriteOps<TMap, TKey, TValue, TEnumerator>
    where TKey : notnull
    where TEnumerator : struct, IEnumerator<KeyValuePair<TKey, TValue>>
{
    static abstract int Count(TMap map);

    static abstract TEnumerator GetEnumerator(TMap map);
}

internal interface IPrimitiveMapReadOps<TMap, TKey, TValue>
    where TKey : notnull
{
    static abstract TMap Create(int capacity);

    static abstract void Put(TMap map, TKey key, TValue value);
}

internal readonly struct DictionaryPrimitiveMapOps<TKey, TValue>
    : IPrimitiveMapWriteOps<Dictionary<TKey, TValue>, TKey, TValue, Dictionary<TKey, TValue>.Enumerator>,
      IPrimitiveMapReadOps<Dictionary<TKey, TValue>, TKey, TValue>
    where TKey : notnull
{
    public static int Count(Dictionary<TKey, TValue> map) => map.Count;

    public static Dictionary<TKey, TValue>.Enumerator GetEnumerator(Dictionary<TKey, TValue> map) => map.GetEnumerator();

    public static Dictionary<TKey, TValue> Create(int capacity) => new(capacity);

    public static void Put(Dictionary<TKey, TValue> map, TKey key, TValue value)
    {
        map[key] = value;
    }
}

internal readonly struct SortedDictionaryPrimitiveMapOps<TKey, TValue>
    : IPrimitiveMapWriteOps<SortedDictionary<TKey, TValue>, TKey, TValue, SortedDictionary<TKey, TValue>.Enumerator>,
      IPrimitiveMapReadOps<SortedDictionary<TKey, TValue>, TKey, TValue>
    where TKey : notnull
{
    public static int Count(SortedDictionary<TKey, TValue> map) => map.Count;

    public static SortedDictionary<TKey, TValue>.Enumerator GetEnumerator(SortedDictionary<TKey, TValue> map) => map.GetEnumerator();

    public static SortedDictionary<TKey, TValue> Create(int capacity)
    {
        _ = capacity;
        return new SortedDictionary<TKey, TValue>();
    }

    public static void Put(SortedDictionary<TKey, TValue> map, TKey key, TValue value)
    {
        map[key] = value;
    }
}

internal readonly struct SortedListPrimitiveMapOps<TKey, TValue>
    : IPrimitiveMapWriteOps<SortedList<TKey, TValue>, TKey, TValue, SortedListPrimitiveEnumerator<TKey, TValue>>,
      IPrimitiveMapReadOps<SortedList<TKey, TValue>, TKey, TValue>
    where TKey : notnull
{
    public static int Count(SortedList<TKey, TValue> map) => map.Count;

    public static SortedListPrimitiveEnumerator<TKey, TValue> GetEnumerator(SortedList<TKey, TValue> map) => new(map);

    public static SortedList<TKey, TValue> Create(int capacity) => new(capacity);

    public static void Put(SortedList<TKey, TValue> map, TKey key, TValue value)
    {
        map[key] = value;
    }
}

internal struct SortedListPrimitiveEnumerator<TKey, TValue> : IEnumerator<KeyValuePair<TKey, TValue>>
    where TKey : notnull
{
    private readonly SortedList<TKey, TValue> _map;
    private int _index;

    public SortedListPrimitiveEnumerator(SortedList<TKey, TValue> map)
    {
        _map = map;
        _index = -1;
    }

    public KeyValuePair<TKey, TValue> Current => new(_map.Keys[_index], _map.Values[_index]);

    object IEnumerator.Current => Current;

    public bool MoveNext()
    {
        int next = _index + 1;
        if (next >= _map.Count)
        {
            return false;
        }

        _index = next;
        return true;
    }

    public void Reset()
    {
        _index = -1;
    }

    public void Dispose()
    {
    }
}

internal readonly struct ConcurrentDictionaryPrimitiveMapOps<TKey, TValue>
    : IPrimitiveMapReadOps<ConcurrentDictionary<TKey, TValue>, TKey, TValue>
    where TKey : notnull
{
    public static ConcurrentDictionary<TKey, TValue> Create(int capacity)
    {
        return new ConcurrentDictionary<TKey, TValue>(Environment.ProcessorCount, capacity);
    }

    public static void Put(ConcurrentDictionary<TKey, TValue> map, TKey key, TValue value)
    {
        map[key] = value;
    }
}

internal struct KeyValuePairArrayEnumerator<TKey, TValue> : IEnumerator<KeyValuePair<TKey, TValue>>
{
    private readonly KeyValuePair<TKey, TValue>[] _array;
    private int _index;

    public KeyValuePairArrayEnumerator(KeyValuePair<TKey, TValue>[] array)
    {
        _array = array;
        _index = -1;
    }

    public KeyValuePair<TKey, TValue> Current => _array[_index];

    object IEnumerator.Current => Current;

    public bool MoveNext()
    {
        int next = _index + 1;
        if (next >= _array.Length)
        {
            return false;
        }

        _index = next;
        return true;
    }

    public void Reset()
    {
        _index = -1;
    }

    public void Dispose()
    {
    }
}

internal readonly struct ArrayPrimitiveMapWriteOps<TKey, TValue>
    : IPrimitiveMapWriteOps<KeyValuePair<TKey, TValue>[], TKey, TValue, KeyValuePairArrayEnumerator<TKey, TValue>>
    where TKey : notnull
{
    public static int Count(KeyValuePair<TKey, TValue>[] map) => map.Length;

    public static KeyValuePairArrayEnumerator<TKey, TValue> GetEnumerator(KeyValuePair<TKey, TValue>[] map) => new(map);
}

internal static class PrimitiveDictionaryCodecWriter
{
    public static void WriteMap<TMap, TKey, TValue, TKeyCodec, TValueCodec, TMapOps, TEnumerator>(
        WriteContext context,
        TMap map,
        bool hasGenerics)
        where TKey : notnull
        where TKeyCodec : struct, IPrimitiveDictionaryCodec<TKey>
        where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
        where TMapOps : struct, IPrimitiveMapWriteOps<TMap, TKey, TValue, TEnumerator>
        where TEnumerator : struct, IEnumerator<KeyValuePair<TKey, TValue>>
    {
        int totalLength = map is null ? 0 : TMapOps.Count(map);
        context.Writer.WriteVarUInt32((uint)totalLength);
        if (totalLength == 0)
        {
            return;
        }

        TypeId keyTypeId = TKeyCodec.WireTypeId;
        TypeId valueTypeId = TValueCodec.WireTypeId;
        bool keyDeclared = hasGenerics && !keyTypeId.NeedsTypeInfoForField();
        bool valueDeclared = hasGenerics && !valueTypeId.NeedsTypeInfoForField();
        bool keyNullable = TKeyCodec.IsNullable;
        bool valueNullable = TValueCodec.IsNullable;

        int writtenCount = 0;
        using TEnumerator enumerator = TMapOps.GetEnumerator(map);
        if (!enumerator.MoveNext())
        {
            throw new InvalidDataException($"primitive map enumerator yielded zero entries while count={totalLength}");
        }

        KeyValuePair<TKey, TValue> pair = enumerator.Current;
        bool hasCurrent = true;
        while (hasCurrent)
        {
            bool keyNull = keyNullable && TKeyCodec.IsNone(pair.Key);
            bool valueNull = valueNullable && TValueCodec.IsNone(pair.Value);
            if (keyNull || valueNull)
            {
                byte header = 0;
                if (keyNull)
                {
                    header |= DictionaryBits.KeyNull;
                }
                else if (keyDeclared)
                {
                    header |= DictionaryBits.DeclaredKeyType;
                }

                if (valueNull)
                {
                    header |= DictionaryBits.ValueNull;
                }
                else if (valueDeclared)
                {
                    header |= DictionaryBits.DeclaredValueType;
                }

                context.Writer.WriteUInt8(header);
                if (!keyNull)
                {
                    if (!keyDeclared)
                    {
                        context.Writer.WriteUInt8((byte)keyTypeId);
                    }

                    TKeyCodec.Write(context, pair.Key);
                }

                if (!valueNull)
                {
                    if (!valueDeclared)
                    {
                        context.Writer.WriteUInt8((byte)valueTypeId);
                    }

                    TValueCodec.Write(context, pair.Value);
                }

                writtenCount += 1;
                if (writtenCount > totalLength)
                {
                    throw new InvalidDataException($"primitive map count mismatch: expected {totalLength}, wrote at least {writtenCount}");
                }

                hasCurrent = enumerator.MoveNext();
                if (hasCurrent)
                {
                    pair = enumerator.Current;
                }

                continue;
            }

            byte blockHeader = 0;
            if (keyDeclared)
            {
                blockHeader |= DictionaryBits.DeclaredKeyType;
            }

            if (valueDeclared)
            {
                blockHeader |= DictionaryBits.DeclaredValueType;
            }

            context.Writer.WriteUInt8(blockHeader);
            int chunkSizeOffset = context.Writer.Count;
            context.Writer.WriteUInt8(0);
            PrimitiveDictionaryHeader.WriteMapChunkTypeInfo(context, keyDeclared, valueDeclared, keyTypeId, valueTypeId);

            byte chunkSize = 0;
            while (true)
            {
                TKeyCodec.Write(context, pair.Key);
                TValueCodec.Write(context, pair.Value);
                chunkSize += 1;
                writtenCount += 1;
                if (writtenCount > totalLength)
                {
                    throw new InvalidDataException($"primitive map count mismatch: expected {totalLength}, wrote at least {writtenCount}");
                }

                if (chunkSize == byte.MaxValue)
                {
                    hasCurrent = enumerator.MoveNext();
                    if (hasCurrent)
                    {
                        pair = enumerator.Current;
                    }

                    break;
                }

                if (!enumerator.MoveNext())
                {
                    hasCurrent = false;
                    break;
                }

                pair = enumerator.Current;
                keyNull = keyNullable && TKeyCodec.IsNone(pair.Key);
                valueNull = valueNullable && TValueCodec.IsNone(pair.Value);
                if (keyNull || valueNull)
                {
                    hasCurrent = true;
                    break;
                }
            }

            context.Writer.SetByte(chunkSizeOffset, chunkSize);
        }

        if (writtenCount != totalLength)
        {
            throw new InvalidDataException($"primitive map count mismatch: expected {totalLength}, wrote {writtenCount}");
        }
    }
}

internal static class PrimitiveDictionaryCodecReader
{
    public static TMap ReadMap<TMap, TKey, TValue, TKeyCodec, TValueCodec, TMapOps>(ReadContext context)
        where TKey : notnull
        where TKeyCodec : struct, IPrimitiveDictionaryCodec<TKey>
        where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
        where TMapOps : struct, IPrimitiveMapReadOps<TMap, TKey, TValue>
    {
        int totalLength = checked((int)context.Reader.ReadVarUInt32());
        TMap map = TMapOps.Create(totalLength);
        if (totalLength == 0)
        {
            return map;
        }

        TypeId keyTypeId = TKeyCodec.WireTypeId;
        TypeId valueTypeId = TValueCodec.WireTypeId;
        bool keyNullable = TKeyCodec.IsNullable;
        int readCount = 0;
        while (readCount < totalLength)
        {
            byte header = context.Reader.ReadUInt8();
            bool trackKeyRef = (header & DictionaryBits.TrackingKeyRef) != 0;
            bool keyNull = (header & DictionaryBits.KeyNull) != 0;
            bool keyDeclared = (header & DictionaryBits.DeclaredKeyType) != 0;
            bool trackValueRef = (header & DictionaryBits.TrackingValueRef) != 0;
            bool valueNull = (header & DictionaryBits.ValueNull) != 0;
            bool valueDeclared = (header & DictionaryBits.DeclaredValueType) != 0;
            if (trackKeyRef || trackValueRef)
            {
                throw new InvalidDataException("primitive dictionary codecs do not support reference-tracking flags");
            }

            if (keyNull && !keyNullable)
            {
                throw new InvalidDataException("non-nullable primitive dictionary key cannot be null");
            }

            if (keyNull && valueNull)
            {
                readCount += 1;
                continue;
            }

            if (keyNull)
            {
                if (!valueDeclared)
                {
                    ReadAndValidateTypeInfo(context, valueTypeId);
                }

                _ = TValueCodec.Read(context);
                readCount += 1;
                continue;
            }

            if (valueNull)
            {
                if (!keyDeclared)
                {
                    ReadAndValidateTypeInfo(context, keyTypeId);
                }

                TKey key = TKeyCodec.Read(context);
                TMapOps.Put(map, key, TValueCodec.DefaultValue);
                readCount += 1;
                continue;
            }

            int chunkSize = context.Reader.ReadUInt8();
            if (chunkSize == 0)
            {
                throw new InvalidDataException("invalid primitive map chunk size 0");
            }

            if (!keyDeclared)
            {
                ReadAndValidateTypeInfo(context, keyTypeId);
            }

            if (!valueDeclared)
            {
                ReadAndValidateTypeInfo(context, valueTypeId);
            }

            for (int i = 0; i < chunkSize; i++)
            {
                TKey key = TKeyCodec.Read(context);
                TValue value = TValueCodec.Read(context);
                TMapOps.Put(map, key, value);
            }

            readCount += chunkSize;
        }

        return map;
    }

    private static void ReadAndValidateTypeInfo(ReadContext context, TypeId expectedTypeId)
    {
        uint actualTypeId = context.Reader.ReadVarUInt32();
        if (actualTypeId != (uint)expectedTypeId)
        {
            throw new TypeMismatchException((uint)expectedTypeId, actualTypeId);
        }
    }
}

internal class PrimitiveDictionarySerializer<TKey, TValue, TKeyCodec, TValueCodec> : Serializer<Dictionary<TKey, TValue>>
    where TKey : notnull
    where TKeyCodec : struct, IPrimitiveDictionaryCodec<TKey>
    where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
{



    public override Dictionary<TKey, TValue> DefaultValue => null!;

public override void WriteData(WriteContext context, in Dictionary<TKey, TValue> value, bool hasGenerics)
    {
        Dictionary<TKey, TValue> map = value ?? [];
        PrimitiveDictionaryCodecWriter.WriteMap<
            Dictionary<TKey, TValue>,
            TKey,
            TValue,
            TKeyCodec,
            TValueCodec,
            DictionaryPrimitiveMapOps<TKey, TValue>,
            Dictionary<TKey, TValue>.Enumerator>(context, map, hasGenerics);
    }

    public override Dictionary<TKey, TValue> ReadData(ReadContext context)
    {
        return PrimitiveDictionaryCodecReader.ReadMap<
            Dictionary<TKey, TValue>,
            TKey,
            TValue,
            TKeyCodec,
            TValueCodec,
            DictionaryPrimitiveMapOps<TKey, TValue>>(context);
    }
}

internal class PrimitiveStringKeyDictionarySerializer<TValue, TValueCodec>
    : PrimitiveDictionarySerializer<string, TValue, StringPrimitiveDictionaryCodec, TValueCodec>
    where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
{
}

internal class PrimitiveSameTypeDictionarySerializer<TValue, TValueCodec>
    : PrimitiveDictionarySerializer<TValue, TValue, TValueCodec, TValueCodec>
    where TValue : notnull
    where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
{
}

internal class PrimitiveSortedDictionarySerializer<TKey, TValue, TKeyCodec, TValueCodec> : Serializer<SortedDictionary<TKey, TValue>>
    where TKey : notnull
    where TKeyCodec : struct, IPrimitiveDictionaryCodec<TKey>
    where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
{



    public override SortedDictionary<TKey, TValue> DefaultValue => null!;

    public override void WriteData(WriteContext context, in SortedDictionary<TKey, TValue> value, bool hasGenerics)
    {
        SortedDictionary<TKey, TValue> map = value ?? new SortedDictionary<TKey, TValue>();
        PrimitiveDictionaryCodecWriter.WriteMap<
            SortedDictionary<TKey, TValue>,
            TKey,
            TValue,
            TKeyCodec,
            TValueCodec,
            SortedDictionaryPrimitiveMapOps<TKey, TValue>,
            SortedDictionary<TKey, TValue>.Enumerator>(context, map, hasGenerics);
    }

    public override SortedDictionary<TKey, TValue> ReadData(ReadContext context)
    {
        return PrimitiveDictionaryCodecReader.ReadMap<
            SortedDictionary<TKey, TValue>,
            TKey,
            TValue,
            TKeyCodec,
            TValueCodec,
            SortedDictionaryPrimitiveMapOps<TKey, TValue>>(context);
    }
}

internal class PrimitiveStringKeySortedDictionarySerializer<TValue, TValueCodec>
    : PrimitiveSortedDictionarySerializer<string, TValue, StringPrimitiveDictionaryCodec, TValueCodec>
    where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
{
}

internal class PrimitiveSameTypeSortedDictionarySerializer<TValue, TValueCodec>
    : PrimitiveSortedDictionarySerializer<TValue, TValue, TValueCodec, TValueCodec>
    where TValue : notnull
    where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
{
}

internal class PrimitiveSortedListSerializer<TKey, TValue, TKeyCodec, TValueCodec> : Serializer<SortedList<TKey, TValue>>
    where TKey : notnull
    where TKeyCodec : struct, IPrimitiveDictionaryCodec<TKey>
    where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
{



    public override SortedList<TKey, TValue> DefaultValue => null!;

    public override void WriteData(WriteContext context, in SortedList<TKey, TValue> value, bool hasGenerics)
    {
        SortedList<TKey, TValue> map = value ?? new SortedList<TKey, TValue>();
        PrimitiveDictionaryCodecWriter.WriteMap<
            SortedList<TKey, TValue>,
            TKey,
            TValue,
            TKeyCodec,
            TValueCodec,
            SortedListPrimitiveMapOps<TKey, TValue>,
            SortedListPrimitiveEnumerator<TKey, TValue>>(context, map, hasGenerics);
    }

    public override SortedList<TKey, TValue> ReadData(ReadContext context)
    {
        return PrimitiveDictionaryCodecReader.ReadMap<
            SortedList<TKey, TValue>,
            TKey,
            TValue,
            TKeyCodec,
            TValueCodec,
            SortedListPrimitiveMapOps<TKey, TValue>>(context);
    }
}

internal class PrimitiveStringKeySortedListSerializer<TValue, TValueCodec>
    : PrimitiveSortedListSerializer<string, TValue, StringPrimitiveDictionaryCodec, TValueCodec>
    where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
{
}

internal class PrimitiveSameTypeSortedListSerializer<TValue, TValueCodec>
    : PrimitiveSortedListSerializer<TValue, TValue, TValueCodec, TValueCodec>
    where TValue : notnull
    where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
{
}

internal class PrimitiveConcurrentDictionarySerializer<TKey, TValue, TKeyCodec, TValueCodec> : Serializer<ConcurrentDictionary<TKey, TValue>>
    where TKey : notnull
    where TKeyCodec : struct, IPrimitiveDictionaryCodec<TKey>
    where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
{



    public override ConcurrentDictionary<TKey, TValue> DefaultValue => null!;

    public override void WriteData(WriteContext context, in ConcurrentDictionary<TKey, TValue> value, bool hasGenerics)
    {
        ConcurrentDictionary<TKey, TValue> map = value ?? new ConcurrentDictionary<TKey, TValue>();
        // Use snapshot to keep count and entry enumeration stable under concurrent mutation.
        PrimitiveDictionaryCodecWriter.WriteMap<
            KeyValuePair<TKey, TValue>[],
            TKey,
            TValue,
            TKeyCodec,
            TValueCodec,
            ArrayPrimitiveMapWriteOps<TKey, TValue>,
            KeyValuePairArrayEnumerator<TKey, TValue>>(context, map.ToArray(), hasGenerics);
    }

    public override ConcurrentDictionary<TKey, TValue> ReadData(ReadContext context)
    {
        return PrimitiveDictionaryCodecReader.ReadMap<
            ConcurrentDictionary<TKey, TValue>,
            TKey,
            TValue,
            TKeyCodec,
            TValueCodec,
            ConcurrentDictionaryPrimitiveMapOps<TKey, TValue>>(context);
    }
}

internal class PrimitiveStringKeyConcurrentDictionarySerializer<TValue, TValueCodec>
    : PrimitiveConcurrentDictionarySerializer<string, TValue, StringPrimitiveDictionaryCodec, TValueCodec>
    where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
{
}

internal class PrimitiveSameTypeConcurrentDictionarySerializer<TValue, TValueCodec>
    : PrimitiveConcurrentDictionarySerializer<TValue, TValue, TValueCodec, TValueCodec>
    where TValue : notnull
    where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
{
}

// String-key primitive dictionary serializers.
internal sealed class DictionaryStringBoolSerializer : PrimitiveStringKeyDictionarySerializer<bool, BoolPrimitiveDictionaryCodec> { }

internal sealed class DictionaryStringDoubleSerializer : PrimitiveStringKeyDictionarySerializer<double, Float64PrimitiveDictionaryCodec> { }

internal sealed class DictionaryStringFloatSerializer : PrimitiveStringKeyDictionarySerializer<float, Float32PrimitiveDictionaryCodec> { }

internal sealed class DictionaryStringInt8Serializer : PrimitiveStringKeyDictionarySerializer<sbyte, Int8PrimitiveDictionaryCodec> { }

internal sealed class DictionaryStringInt16Serializer : PrimitiveStringKeyDictionarySerializer<short, Int16PrimitiveDictionaryCodec> { }

internal sealed class DictionaryStringIntSerializer : PrimitiveStringKeyDictionarySerializer<int, Int32PrimitiveDictionaryCodec> { }

internal sealed class DictionaryStringLongSerializer : PrimitiveStringKeyDictionarySerializer<long, Int64PrimitiveDictionaryCodec> { }

internal sealed class DictionaryStringStringSerializer : PrimitiveStringKeyDictionarySerializer<string, StringPrimitiveDictionaryCodec> { }

internal sealed class DictionaryStringUInt16Serializer : PrimitiveStringKeyDictionarySerializer<ushort, UInt16PrimitiveDictionaryCodec> { }

internal sealed class DictionaryStringUIntSerializer : PrimitiveStringKeyDictionarySerializer<uint, UInt32PrimitiveDictionaryCodec> { }

internal sealed class DictionaryStringULongSerializer : PrimitiveStringKeyDictionarySerializer<ulong, UInt64PrimitiveDictionaryCodec> { }

// Same-type primitive dictionary serializers.
internal sealed class DictionaryIntIntSerializer : PrimitiveSameTypeDictionarySerializer<int, Int32PrimitiveDictionaryCodec> { }

internal sealed class DictionaryLongLongSerializer : PrimitiveSameTypeDictionarySerializer<long, Int64PrimitiveDictionaryCodec> { }

internal sealed class DictionaryUIntUIntSerializer : PrimitiveSameTypeDictionarySerializer<uint, UInt32PrimitiveDictionaryCodec> { }

internal sealed class DictionaryULongULongSerializer : PrimitiveSameTypeDictionarySerializer<ulong, UInt64PrimitiveDictionaryCodec> { }
