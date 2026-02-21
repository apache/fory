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

internal static class CollectionBits
{
    public const byte TrackingRef = 0b0000_0001;
    public const byte HasNull = 0b0000_0010;
    public const byte DeclaredElementType = 0b0000_0100;
    public const byte SameType = 0b0000_1000;
}

internal static class MapBits
{
    public const byte TrackingKeyRef = 0b0000_0001;
    public const byte KeyNull = 0b0000_0010;
    public const byte DeclaredKeyType = 0b0000_0100;
    public const byte TrackingValueRef = 0b0000_1000;
    public const byte ValueNull = 0b0001_0000;
    public const byte DeclaredValueType = 0b0010_0000;
}

internal static class PrimitiveArrayCodec
{
    public static ForyTypeId? PrimitiveArrayTypeId(Type elementType)
    {
        if (elementType == typeof(byte))
        {
            return ForyTypeId.Binary;
        }

        if (elementType == typeof(bool))
        {
            return ForyTypeId.BoolArray;
        }

        if (elementType == typeof(sbyte))
        {
            return ForyTypeId.Int8Array;
        }

        if (elementType == typeof(short))
        {
            return ForyTypeId.Int16Array;
        }

        if (elementType == typeof(int))
        {
            return ForyTypeId.Int32Array;
        }

        if (elementType == typeof(long))
        {
            return ForyTypeId.Int64Array;
        }

        if (elementType == typeof(ushort))
        {
            return ForyTypeId.UInt16Array;
        }

        if (elementType == typeof(uint))
        {
            return ForyTypeId.UInt32Array;
        }

        if (elementType == typeof(ulong))
        {
            return ForyTypeId.UInt64Array;
        }

        if (elementType == typeof(float))
        {
            return ForyTypeId.Float32Array;
        }

        if (elementType == typeof(double))
        {
            return ForyTypeId.Float64Array;
        }

        return null;
    }

    public static void WritePrimitiveArray<T>(T[] value, ref WriteContext context)
    {
        if (typeof(T) == typeof(byte))
        {
            byte[] bytes = (byte[])(object)value;
            context.Writer.WriteVarUInt32((uint)bytes.Length);
            context.Writer.WriteBytes(bytes);
            return;
        }

        if (typeof(T) == typeof(bool))
        {
            bool[] values = (bool[])(object)value;
            context.Writer.WriteVarUInt32((uint)values.Length);
            foreach (bool item in values)
            {
                context.Writer.WriteUInt8(item ? (byte)1 : (byte)0);
            }

            return;
        }

        if (typeof(T) == typeof(sbyte))
        {
            sbyte[] values = (sbyte[])(object)value;
            context.Writer.WriteVarUInt32((uint)values.Length);
            foreach (sbyte item in values)
            {
                context.Writer.WriteInt8(item);
            }

            return;
        }

        if (typeof(T) == typeof(short))
        {
            short[] values = (short[])(object)value;
            context.Writer.WriteVarUInt32((uint)(values.Length * 2));
            foreach (short item in values)
            {
                context.Writer.WriteInt16(item);
            }

            return;
        }

        if (typeof(T) == typeof(int))
        {
            int[] values = (int[])(object)value;
            context.Writer.WriteVarUInt32((uint)(values.Length * 4));
            foreach (int item in values)
            {
                context.Writer.WriteInt32(item);
            }

            return;
        }

        if (typeof(T) == typeof(uint))
        {
            uint[] values = (uint[])(object)value;
            context.Writer.WriteVarUInt32((uint)(values.Length * 4));
            foreach (uint item in values)
            {
                context.Writer.WriteUInt32(item);
            }

            return;
        }

        if (typeof(T) == typeof(long))
        {
            long[] values = (long[])(object)value;
            context.Writer.WriteVarUInt32((uint)(values.Length * 8));
            foreach (long item in values)
            {
                context.Writer.WriteInt64(item);
            }

            return;
        }

        if (typeof(T) == typeof(ulong))
        {
            ulong[] values = (ulong[])(object)value;
            context.Writer.WriteVarUInt32((uint)(values.Length * 8));
            foreach (ulong item in values)
            {
                context.Writer.WriteUInt64(item);
            }

            return;
        }

        if (typeof(T) == typeof(ushort))
        {
            ushort[] values = (ushort[])(object)value;
            context.Writer.WriteVarUInt32((uint)(values.Length * 2));
            foreach (ushort item in values)
            {
                context.Writer.WriteUInt16(item);
            }

            return;
        }

        if (typeof(T) == typeof(float))
        {
            float[] values = (float[])(object)value;
            context.Writer.WriteVarUInt32((uint)(values.Length * 4));
            foreach (float item in values)
            {
                context.Writer.WriteFloat32(item);
            }

            return;
        }

        double[] doubles = (double[])(object)value;
        context.Writer.WriteVarUInt32((uint)(doubles.Length * 8));
        foreach (double item in doubles)
        {
            context.Writer.WriteFloat64(item);
        }
    }

    public static T[] ReadPrimitiveArray<T>(ref ReadContext context)
    {
        int payloadSize = checked((int)context.Reader.ReadVarUInt32());
        if (typeof(T) == typeof(byte))
        {
            return (T[])(object)context.Reader.ReadBytes(payloadSize);
        }

        if (typeof(T) == typeof(bool))
        {
            bool[] outValues = new bool[payloadSize];
            for (int i = 0; i < payloadSize; i++)
            {
                outValues[i] = context.Reader.ReadUInt8() != 0;
            }

            return (T[])(object)outValues;
        }

        if (typeof(T) == typeof(sbyte))
        {
            sbyte[] outValues = new sbyte[payloadSize];
            for (int i = 0; i < payloadSize; i++)
            {
                outValues[i] = context.Reader.ReadInt8();
            }

            return (T[])(object)outValues;
        }

        if (typeof(T) == typeof(short))
        {
            if ((payloadSize & 1) != 0)
            {
                throw new ForyInvalidDataException("int16 array payload size mismatch");
            }

            short[] outValues = new short[payloadSize / 2];
            for (int i = 0; i < outValues.Length; i++)
            {
                outValues[i] = context.Reader.ReadInt16();
            }

            return (T[])(object)outValues;
        }

        if (typeof(T) == typeof(int))
        {
            if ((payloadSize & 3) != 0)
            {
                throw new ForyInvalidDataException("int32 array payload size mismatch");
            }

            int[] outValues = new int[payloadSize / 4];
            for (int i = 0; i < outValues.Length; i++)
            {
                outValues[i] = context.Reader.ReadInt32();
            }

            return (T[])(object)outValues;
        }

        if (typeof(T) == typeof(uint))
        {
            if ((payloadSize & 3) != 0)
            {
                throw new ForyInvalidDataException("uint32 array payload size mismatch");
            }

            uint[] outValues = new uint[payloadSize / 4];
            for (int i = 0; i < outValues.Length; i++)
            {
                outValues[i] = context.Reader.ReadUInt32();
            }

            return (T[])(object)outValues;
        }

        if (typeof(T) == typeof(long))
        {
            if ((payloadSize & 7) != 0)
            {
                throw new ForyInvalidDataException("int64 array payload size mismatch");
            }

            long[] outValues = new long[payloadSize / 8];
            for (int i = 0; i < outValues.Length; i++)
            {
                outValues[i] = context.Reader.ReadInt64();
            }

            return (T[])(object)outValues;
        }

        if (typeof(T) == typeof(ulong))
        {
            if ((payloadSize & 7) != 0)
            {
                throw new ForyInvalidDataException("uint64 array payload size mismatch");
            }

            ulong[] outValues = new ulong[payloadSize / 8];
            for (int i = 0; i < outValues.Length; i++)
            {
                outValues[i] = context.Reader.ReadUInt64();
            }

            return (T[])(object)outValues;
        }

        if (typeof(T) == typeof(ushort))
        {
            if ((payloadSize & 1) != 0)
            {
                throw new ForyInvalidDataException("uint16 array payload size mismatch");
            }

            ushort[] outValues = new ushort[payloadSize / 2];
            for (int i = 0; i < outValues.Length; i++)
            {
                outValues[i] = context.Reader.ReadUInt16();
            }

            return (T[])(object)outValues;
        }

        if (typeof(T) == typeof(float))
        {
            if ((payloadSize & 3) != 0)
            {
                throw new ForyInvalidDataException("float32 array payload size mismatch");
            }

            float[] outValues = new float[payloadSize / 4];
            for (int i = 0; i < outValues.Length; i++)
            {
                outValues[i] = context.Reader.ReadFloat32();
            }

            return (T[])(object)outValues;
        }

        if ((payloadSize & 7) != 0)
        {
            throw new ForyInvalidDataException("float64 array payload size mismatch");
        }

        double[] doubles = new double[payloadSize / 8];
        for (int i = 0; i < doubles.Length; i++)
        {
            doubles[i] = context.Reader.ReadFloat64();
        }

        return (T[])(object)doubles;
    }
}

public sealed class ArraySerializer<T> : Serializer<T[]>
{
    private readonly Serializer<T> _elementSerializer = SerializerRegistry.Get<T>();
    private readonly ForyTypeId? _primitiveArrayTypeId = PrimitiveArrayCodec.PrimitiveArrayTypeId(typeof(T));

    public override ForyTypeId StaticTypeId => _primitiveArrayTypeId ?? ForyTypeId.List;
    public override bool IsNullableType => true;
    public override bool IsReferenceTrackableType => true;
    public override T[] DefaultValue => null!;
    public override bool IsNone(T[] value) => value is null;

    public override void WriteData(ref WriteContext context, in T[] value, bool hasGenerics)
    {
        T[] safe = value ?? [];
        if (_primitiveArrayTypeId is not null)
        {
            PrimitiveArrayCodec.WritePrimitiveArray(safe, ref context);
            return;
        }

        DynamicAnyCodec.WriteCollectionData(
            safe,
            _elementSerializer,
            ref context,
            hasGenerics);
    }

    public override T[] ReadData(ref ReadContext context)
    {
        if (_primitiveArrayTypeId is not null)
        {
            return PrimitiveArrayCodec.ReadPrimitiveArray<T>(ref context);
        }

        List<T> values = DynamicAnyCodec.ReadCollectionData<T>(_elementSerializer, ref context);
        return values.ToArray();
    }
}

public sealed class ListSerializer<T> : Serializer<List<T>>
{
    private readonly Serializer<T> _elementSerializer = SerializerRegistry.Get<T>();

    public override ForyTypeId StaticTypeId => ForyTypeId.List;
    public override bool IsNullableType => true;
    public override bool IsReferenceTrackableType => true;
    public override List<T> DefaultValue => null!;
    public override bool IsNone(List<T> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<T> value, bool hasGenerics)
    {
        List<T> safe = value ?? [];
        DynamicAnyCodec.WriteCollectionData(safe, _elementSerializer, ref context, hasGenerics);
    }

    public override List<T> ReadData(ref ReadContext context)
    {
        return DynamicAnyCodec.ReadCollectionData(_elementSerializer, ref context);
    }
}

public sealed class SetSerializer<T> : Serializer<HashSet<T>> where T : notnull
{
    private readonly ListSerializer<T> _listSerializer = new();

    public override ForyTypeId StaticTypeId => ForyTypeId.Set;
    public override bool IsNullableType => true;
    public override bool IsReferenceTrackableType => true;
    public override HashSet<T> DefaultValue => null!;
    public override bool IsNone(HashSet<T> value) => value is null;

    public override void WriteData(ref WriteContext context, in HashSet<T> value, bool hasGenerics)
    {
        List<T> list = value is null ? [] : [.. value];
        _listSerializer.WriteData(ref context, list, hasGenerics);
    }

    public override HashSet<T> ReadData(ref ReadContext context)
    {
        return [.. _listSerializer.ReadData(ref context)];
    }
}

public sealed class MapSerializer<TKey, TValue> : Serializer<Dictionary<TKey, TValue>>
    where TKey : notnull
{
    private readonly Serializer<TKey> _keySerializer = SerializerRegistry.Get<TKey>();
    private readonly Serializer<TValue> _valueSerializer = SerializerRegistry.Get<TValue>();

    public override ForyTypeId StaticTypeId => ForyTypeId.Map;
    public override bool IsNullableType => true;
    public override bool IsReferenceTrackableType => true;
    public override Dictionary<TKey, TValue> DefaultValue => null!;
    public override bool IsNone(Dictionary<TKey, TValue> value) => value is null;

    public override void WriteData(ref WriteContext context, in Dictionary<TKey, TValue> value, bool hasGenerics)
    {
        Dictionary<TKey, TValue> map = value ?? [];
        context.Writer.WriteVarUInt32((uint)map.Count);
        if (map.Count == 0)
        {
            return;
        }

        bool trackKeyRef = context.TrackRef && _keySerializer.IsReferenceTrackableType;
        bool trackValueRef = context.TrackRef && _valueSerializer.IsReferenceTrackableType;
        bool keyDeclared = hasGenerics && !_keySerializer.StaticTypeId.NeedsTypeInfoForField();
        bool valueDeclared = hasGenerics && !_valueSerializer.StaticTypeId.NeedsTypeInfoForField();
        bool keyDynamicType = _keySerializer.StaticTypeId == ForyTypeId.Unknown;
        bool valueDynamicType = _valueSerializer.StaticTypeId == ForyTypeId.Unknown;

        KeyValuePair<TKey, TValue>[] pairs = [.. map];
        if (keyDynamicType || valueDynamicType)
        {
            WriteDynamicMapPairs(pairs, ref context, hasGenerics, trackKeyRef, trackValueRef, keyDeclared, valueDeclared, keyDynamicType, valueDynamicType);
            return;
        }

        int index = 0;
        while (index < pairs.Length)
        {
            KeyValuePair<TKey, TValue> pair = pairs[index];
            bool keyIsNull = _keySerializer.IsNoneObject(pair.Key);
            bool valueIsNull = _valueSerializer.IsNoneObject(pair.Value);
            if (keyIsNull || valueIsNull)
            {
                byte header = 0;
                if (trackKeyRef)
                {
                    header |= MapBits.TrackingKeyRef;
                }

                if (trackValueRef)
                {
                    header |= MapBits.TrackingValueRef;
                }

                if (keyIsNull)
                {
                    header |= MapBits.KeyNull;
                }

                if (valueIsNull)
                {
                    header |= MapBits.ValueNull;
                }

                if (!keyIsNull && keyDeclared)
                {
                    header |= MapBits.DeclaredKeyType;
                }

                if (!valueIsNull && valueDeclared)
                {
                    header |= MapBits.DeclaredValueType;
                }

                context.Writer.WriteUInt8(header);
                if (!keyIsNull)
                {
                    if (!keyDeclared)
                    {
                        _keySerializer.WriteTypeInfo(ref context);
                    }

                    _keySerializer.Write(ref context, pair.Key, trackKeyRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
                }

                if (!valueIsNull)
                {
                    if (!valueDeclared)
                    {
                        _valueSerializer.WriteTypeInfo(ref context);
                    }

                    _valueSerializer.Write(ref context, pair.Value, trackValueRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
                }

                index += 1;
                continue;
            }

            byte blockHeader = 0;
            if (trackKeyRef)
            {
                blockHeader |= MapBits.TrackingKeyRef;
            }

            if (trackValueRef)
            {
                blockHeader |= MapBits.TrackingValueRef;
            }

            if (keyDeclared)
            {
                blockHeader |= MapBits.DeclaredKeyType;
            }

            if (valueDeclared)
            {
                blockHeader |= MapBits.DeclaredValueType;
            }

            context.Writer.WriteUInt8(blockHeader);
            int chunkSizeOffset = context.Writer.Count;
            context.Writer.WriteUInt8(0);
            if (!keyDeclared)
            {
                _keySerializer.WriteTypeInfo(ref context);
            }

            if (!valueDeclared)
            {
                _valueSerializer.WriteTypeInfo(ref context);
            }

            byte chunkSize = 0;
            while (index < pairs.Length && chunkSize < byte.MaxValue)
            {
                KeyValuePair<TKey, TValue> current = pairs[index];
                if (_keySerializer.IsNoneObject(current.Key) || _valueSerializer.IsNoneObject(current.Value))
                {
                    break;
                }

                _keySerializer.Write(ref context, current.Key, trackKeyRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
                _valueSerializer.Write(ref context, current.Value, trackValueRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
                chunkSize += 1;
                index += 1;
            }

            context.Writer.SetByte(chunkSizeOffset, chunkSize);
        }
    }

    public override Dictionary<TKey, TValue> ReadData(ref ReadContext context)
    {
        int totalLength = checked((int)context.Reader.ReadVarUInt32());
        if (totalLength == 0)
        {
            return [];
        }

        Dictionary<TKey, TValue> map = new(totalLength);
        bool keyDynamicType = _keySerializer.StaticTypeId == ForyTypeId.Unknown;
        bool valueDynamicType = _valueSerializer.StaticTypeId == ForyTypeId.Unknown;
        bool canonicalizeValues = context.TrackRef && _valueSerializer.IsReferenceTrackableType;

        int readCount = 0;
        while (readCount < totalLength)
        {
            byte header = context.Reader.ReadUInt8();
            bool trackKeyRef = (header & MapBits.TrackingKeyRef) != 0;
            bool keyNull = (header & MapBits.KeyNull) != 0;
            bool keyDeclared = (header & MapBits.DeclaredKeyType) != 0;
            bool trackValueRef = (header & MapBits.TrackingValueRef) != 0;
            bool valueNull = (header & MapBits.ValueNull) != 0;
            bool valueDeclared = (header & MapBits.DeclaredValueType) != 0;

            if (keyNull && valueNull)
            {
                map[(TKey)_keySerializer.DefaultObject!] = (TValue)_valueSerializer.DefaultObject!;
                readCount += 1;
                continue;
            }

            if (keyNull)
            {
                TValue value = ReadValueElement(
                    ref context,
                    trackValueRef,
                    valueDynamicType || !valueDeclared,
                    canonicalizeValues,
                    _valueSerializer);
                map[(TKey)_keySerializer.DefaultObject!] = value;
                readCount += 1;
                continue;
            }

            if (valueNull)
            {
                TKey key = (TKey)_keySerializer.Read(ref context, trackKeyRef ? RefMode.Tracking : RefMode.None, keyDynamicType || !keyDeclared)!;
                map[key] = (TValue)_valueSerializer.DefaultObject!;
                readCount += 1;
                continue;
            }

            int chunkSize = context.Reader.ReadUInt8();
            if (!keyDeclared)
            {
                _keySerializer.ReadTypeInfo(ref context);
            }

            if (!valueDeclared)
            {
                _valueSerializer.ReadTypeInfo(ref context);
            }

            for (int i = 0; i < chunkSize; i++)
            {
                TKey key = (TKey)_keySerializer.Read(ref context, trackKeyRef ? RefMode.Tracking : RefMode.None, false)!;
                TValue value = ReadValueElement(ref context, trackValueRef, false, canonicalizeValues, _valueSerializer);
                map[key] = value;
            }

            if (!keyDeclared)
            {
                context.ClearDynamicTypeInfo(typeof(TKey));
            }

            if (!valueDeclared)
            {
                context.ClearDynamicTypeInfo(typeof(TValue));
            }

            readCount += chunkSize;
        }

        return map;
    }

    private void WriteDynamicMapPairs(
        KeyValuePair<TKey, TValue>[] pairs,
        ref WriteContext context,
        bool hasGenerics,
        bool trackKeyRef,
        bool trackValueRef,
        bool keyDeclared,
        bool valueDeclared,
        bool keyDynamicType,
        bool valueDynamicType)
    {
        foreach (KeyValuePair<TKey, TValue> pair in pairs)
        {
            bool keyIsNull = _keySerializer.IsNoneObject(pair.Key);
            bool valueIsNull = _valueSerializer.IsNoneObject(pair.Value);
            byte header = 0;
            if (trackKeyRef)
            {
                header |= MapBits.TrackingKeyRef;
            }

            if (trackValueRef)
            {
                header |= MapBits.TrackingValueRef;
            }

            if (keyIsNull)
            {
                header |= MapBits.KeyNull;
            }
            else if (!keyDynamicType && keyDeclared)
            {
                header |= MapBits.DeclaredKeyType;
            }

            if (valueIsNull)
            {
                header |= MapBits.ValueNull;
            }
            else if (!valueDynamicType && valueDeclared)
            {
                header |= MapBits.DeclaredValueType;
            }

            context.Writer.WriteUInt8(header);
            if (keyIsNull && valueIsNull)
            {
                continue;
            }

            if (keyIsNull)
            {
                if (!valueDeclared)
                {
                    if (valueDynamicType)
                    {
                        DynamicAnyCodec.WriteAnyTypeInfo(pair.Value!, ref context);
                    }
                    else
                    {
                        _valueSerializer.WriteTypeInfo(ref context);
                    }
                }

                _valueSerializer.Write(ref context, pair.Value, trackValueRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
                continue;
            }

            if (valueIsNull)
            {
                if (!keyDeclared)
                {
                    if (keyDynamicType)
                    {
                        DynamicAnyCodec.WriteAnyTypeInfo(pair.Key!, ref context);
                    }
                    else
                    {
                        _keySerializer.WriteTypeInfo(ref context);
                    }
                }

                _keySerializer.Write(ref context, pair.Key, trackKeyRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
                continue;
            }

            context.Writer.WriteUInt8(1);
            if (!keyDeclared)
            {
                if (keyDynamicType)
                {
                    DynamicAnyCodec.WriteAnyTypeInfo(pair.Key!, ref context);
                }
                else
                {
                    _keySerializer.WriteTypeInfo(ref context);
                }
            }

            if (!valueDeclared)
            {
                if (valueDynamicType)
                {
                    DynamicAnyCodec.WriteAnyTypeInfo(pair.Value!, ref context);
                }
                else
                {
                    _valueSerializer.WriteTypeInfo(ref context);
                }
            }

            _keySerializer.Write(ref context, pair.Key, trackKeyRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
            _valueSerializer.Write(ref context, pair.Value, trackValueRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
        }
    }

    private static TValue ReadValueElement(
        ref ReadContext context,
        bool trackValueRef,
        bool readTypeInfo,
        bool canonicalizeValues,
        ISerializer valueSerializer)
    {
        if (trackValueRef || !canonicalizeValues)
        {
            return (TValue)valueSerializer.Read(ref context, trackValueRef ? RefMode.Tracking : RefMode.None, readTypeInfo)!;
        }

        int start = context.Reader.Cursor;
        TValue value = (TValue)valueSerializer.Read(ref context, RefMode.None, readTypeInfo)!;
        int end = context.Reader.Cursor;
        return context.CanonicalizeNonTrackingReference(value, start, end);
    }
}
