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

namespace Apache.Fory;

internal static class CollectionBits
{
    public const byte TrackingRef = 0b0000_0001;
    public const byte HasNull = 0b0000_0010;
    public const byte DeclaredElementType = 0b0000_0100;
    public const byte SameType = 0b0000_1000;
}


internal static class CollectionCodec
{
    public static void WriteCollectionData<T>(
        IEnumerable<T> values,
        Serializer<T> elementSerializer,
        ref WriteContext context,
        bool hasGenerics)
    {
        List<T> list = values as List<T> ?? [.. values];
        context.Writer.WriteVarUInt32((uint)list.Count);
        if (list.Count == 0)
        {
            return;
        }

        bool hasNull = false;
        if (elementSerializer.IsNullableType)
        {
            for (int i = 0; i < list.Count; i++)
            {
                if (!elementSerializer.IsNoneObject(list[i]))
                {
                    continue;
                }

                hasNull = true;
                break;
            }
        }

        bool trackRef = context.TrackRef && elementSerializer.IsReferenceTrackableType;
        bool declaredElementType = hasGenerics && !elementSerializer.StaticTypeId.NeedsTypeInfoForField();
        bool dynamicElementType = elementSerializer.StaticTypeId == TypeId.Unknown;

        byte header = dynamicElementType ? (byte)0 : CollectionBits.SameType;
        if (trackRef)
        {
            header |= CollectionBits.TrackingRef;
        }

        if (hasNull)
        {
            header |= CollectionBits.HasNull;
        }

        if (declaredElementType)
        {
            header |= CollectionBits.DeclaredElementType;
        }

        context.Writer.WriteUInt8(header);
        if (!dynamicElementType && !declaredElementType)
        {
            elementSerializer.WriteTypeInfo(ref context);
        }

        if (dynamicElementType)
        {
            RefMode refMode = trackRef ? RefMode.Tracking : hasNull ? RefMode.NullOnly : RefMode.None;
            foreach (T element in list)
            {
                elementSerializer.Write(ref context, element, refMode, true, hasGenerics);
            }

            return;
        }

        if (trackRef)
        {
            foreach (T element in list)
            {
                elementSerializer.Write(ref context, element, RefMode.Tracking, false, hasGenerics);
            }

            return;
        }

        if (hasNull)
        {
            foreach (T element in list)
            {
                if (elementSerializer.IsNoneObject(element))
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.Null);
                }
                else
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.NotNullValue);
                    elementSerializer.WriteData(ref context, element, hasGenerics);
                }
            }

            return;
        }

        foreach (T element in list)
        {
            elementSerializer.WriteData(ref context, element, hasGenerics);
        }
    }

    public static List<T> ReadCollectionData<T>(Serializer<T> elementSerializer, ref ReadContext context)
    {
        int length = checked((int)context.Reader.ReadVarUInt32());
        if (length == 0)
        {
            return [];
        }

        byte header = context.Reader.ReadUInt8();
        bool trackRef = (header & CollectionBits.TrackingRef) != 0;
        bool hasNull = (header & CollectionBits.HasNull) != 0;
        bool declared = (header & CollectionBits.DeclaredElementType) != 0;
        bool sameType = (header & CollectionBits.SameType) != 0;
        bool canonicalizeElements = context.TrackRef && !trackRef && elementSerializer.IsReferenceTrackableType;

        List<T> values = new(length);
        if (!sameType)
        {
            if (trackRef)
            {
                for (int i = 0; i < length; i++)
                {
                    values.Add(elementSerializer.Read(ref context, RefMode.Tracking, true));
                }

                return values;
            }

            if (hasNull)
            {
                for (int i = 0; i < length; i++)
                {
                    sbyte refFlag = context.Reader.ReadInt8();
                    if (refFlag == (sbyte)RefFlag.Null)
                    {
                        values.Add((T)elementSerializer.DefaultObject!);
                    }
                    else if (refFlag == (sbyte)RefFlag.NotNullValue)
                    {
                        values.Add(ReadCollectionElementWithCanonicalization(elementSerializer, ref context, true, canonicalizeElements));
                    }
                    else
                    {
                        throw new RefException($"invalid nullability flag {refFlag}");
                    }
                }
            }
            else
            {
                for (int i = 0; i < length; i++)
                {
                    values.Add(ReadCollectionElementWithCanonicalization(elementSerializer, ref context, true, canonicalizeElements));
                }
            }

            return values;
        }

        if (!declared)
        {
            elementSerializer.ReadTypeInfo(ref context);
        }

        if (trackRef)
        {
            for (int i = 0; i < length; i++)
            {
                values.Add(elementSerializer.Read(ref context, RefMode.Tracking, false));
            }

            if (!declared)
            {
                context.ClearDynamicTypeInfo(typeof(T));
            }

            return values;
        }

        if (hasNull)
        {
            for (int i = 0; i < length; i++)
            {
                sbyte refFlag = context.Reader.ReadInt8();
                if (refFlag == (sbyte)RefFlag.Null)
                {
                    values.Add((T)elementSerializer.DefaultObject!);
                }
                else
                {
                    values.Add(ReadCollectionElementDataWithCanonicalization(elementSerializer, ref context, canonicalizeElements));
                }
            }
        }
        else
        {
            for (int i = 0; i < length; i++)
            {
                values.Add(ReadCollectionElementDataWithCanonicalization(elementSerializer, ref context, canonicalizeElements));
            }
        }

        if (!declared)
        {
            context.ClearDynamicTypeInfo(typeof(T));
        }

        return values;
    }

    private static T ReadCollectionElementWithCanonicalization<T>(
        Serializer<T> elementSerializer,
        ref ReadContext context,
        bool readTypeInfo,
        bool canonicalize)
    {
        if (!canonicalize)
        {
            return elementSerializer.Read(ref context, RefMode.None, readTypeInfo);
        }

        int start = context.Reader.Cursor;
        T value = elementSerializer.Read(ref context, RefMode.None, readTypeInfo);
        int end = context.Reader.Cursor;
        return context.CanonicalizeNonTrackingReference(value, start, end);
    }

    private static T ReadCollectionElementDataWithCanonicalization<T>(
        Serializer<T> elementSerializer,
        ref ReadContext context,
        bool canonicalize)
    {
        if (!canonicalize)
        {
            return elementSerializer.ReadData(ref context);
        }

        int start = context.Reader.Cursor;
        T value = elementSerializer.ReadData(ref context);
        int end = context.Reader.Cursor;
        return context.CanonicalizeNonTrackingReference(value, start, end);
    }
}

internal static class DynamicContainerCodec
{
    public static bool TryGetTypeId(object value, out TypeId typeId)
    {
        if (value is IDictionary)
        {
            typeId = TypeId.Map;
            return true;
        }

        Type valueType = value.GetType();
        if (value is IList && !valueType.IsArray)
        {
            typeId = TypeId.List;
            return true;
        }

        if (IsSet(valueType))
        {
            typeId = TypeId.Set;
            return true;
        }

        typeId = default;
        return false;
    }

    public static bool TryWritePayload(object value, ref WriteContext context, bool hasGenerics)
    {
        if (value is IDictionary dictionary)
        {
            NullableKeyDictionary<object, object?> map = new();
            foreach (DictionaryEntry entry in dictionary)
            {
                map.Add(entry.Key, entry.Value);
            }

            SerializerRegistry.Get<NullableKeyDictionary<object, object?>>().WriteData(ref context, map, false);
            return true;
        }

        Type valueType = value.GetType();
        if (value is IList list && !valueType.IsArray)
        {
            List<object?> values = new(list.Count);
            for (int i = 0; i < list.Count; i++)
            {
                values.Add(list[i]);
            }

            SerializerRegistry.Get<List<object?>>().WriteData(ref context, values, hasGenerics);
            return true;
        }

        if (!IsSet(valueType))
        {
            return false;
        }

        HashSet<object?> set = [];
        foreach (object? item in (IEnumerable)value)
        {
            set.Add(item);
        }

        SerializerRegistry.Get<HashSet<object?>>().WriteData(ref context, set, hasGenerics);
        return true;
    }

    public static List<object?> ReadListPayload(ref ReadContext context)
    {
        return SerializerRegistry.Get<List<object?>>().ReadData(ref context);
    }

    public static HashSet<object?> ReadSetPayload(ref ReadContext context)
    {
        return SerializerRegistry.Get<HashSet<object?>>().ReadData(ref context);
    }

    public static object ReadMapPayload(ref ReadContext context)
    {
        NullableKeyDictionary<object, object?> map = SerializerRegistry.Get<NullableKeyDictionary<object, object?>>().ReadData(ref context);
        if (map.HasNullKey)
        {
            return map;
        }

        return new Dictionary<object, object?>(map.NonNullEntries);
    }

    private static bool IsSet(Type valueType)
    {
        if (!valueType.IsGenericType)
        {
            return false;
        }

        if (valueType.GetGenericTypeDefinition() == typeof(ISet<>))
        {
            return true;
        }

        foreach (Type iface in valueType.GetInterfaces())
        {
            if (!iface.IsGenericType)
            {
                continue;
            }

            if (iface.GetGenericTypeDefinition() == typeof(ISet<>))
            {
                return true;
            }
        }

        return false;
    }
}

internal static class PrimitiveArrayCodec
{
    public static TypeId? PrimitiveArrayTypeId(Type elementType)
    {
        if (elementType == typeof(byte))
        {
            return TypeId.Binary;
        }

        if (elementType == typeof(bool))
        {
            return TypeId.BoolArray;
        }

        if (elementType == typeof(sbyte))
        {
            return TypeId.Int8Array;
        }

        if (elementType == typeof(short))
        {
            return TypeId.Int16Array;
        }

        if (elementType == typeof(int))
        {
            return TypeId.Int32Array;
        }

        if (elementType == typeof(long))
        {
            return TypeId.Int64Array;
        }

        if (elementType == typeof(ushort))
        {
            return TypeId.UInt16Array;
        }

        if (elementType == typeof(uint))
        {
            return TypeId.UInt32Array;
        }

        if (elementType == typeof(ulong))
        {
            return TypeId.UInt64Array;
        }

        if (elementType == typeof(float))
        {
            return TypeId.Float32Array;
        }

        if (elementType == typeof(double))
        {
            return TypeId.Float64Array;
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
                throw new InvalidDataException("int16 array payload size mismatch");
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
                throw new InvalidDataException("int32 array payload size mismatch");
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
                throw new InvalidDataException("uint32 array payload size mismatch");
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
                throw new InvalidDataException("int64 array payload size mismatch");
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
                throw new InvalidDataException("uint64 array payload size mismatch");
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
                throw new InvalidDataException("uint16 array payload size mismatch");
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
                throw new InvalidDataException("float32 array payload size mismatch");
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
            throw new InvalidDataException("float64 array payload size mismatch");
        }

        double[] doubles = new double[payloadSize / 8];
        for (int i = 0; i < doubles.Length; i++)
        {
            doubles[i] = context.Reader.ReadFloat64();
        }

        return (T[])(object)doubles;
    }
}

public readonly struct ArraySerializer<T> : IStaticSerializer<ArraySerializer<T>, T[]>
{
    private static Serializer<T> ElementSerializer => SerializerRegistry.Get<T>();
    private static readonly TypeId? PrimitiveArrayTypeId = PrimitiveArrayCodec.PrimitiveArrayTypeId(typeof(T));

    public static TypeId StaticTypeId => PrimitiveArrayTypeId ?? TypeId.List;
    public static bool IsNullableType => true;
    public static bool IsReferenceTrackableType => true;
    public static T[] DefaultValue => null!;
    public static bool IsNone(in T[] value) => value is null;

    public static void WriteData(ref WriteContext context, in T[] value, bool hasGenerics)
    {
        T[] safe = value ?? [];
        if (PrimitiveArrayTypeId is not null)
        {
            PrimitiveArrayCodec.WritePrimitiveArray(safe, ref context);
            return;
        }

        CollectionCodec.WriteCollectionData(
            safe,
            ElementSerializer,
            ref context,
            hasGenerics);
    }

    public static T[] ReadData(ref ReadContext context)
    {
        if (PrimitiveArrayTypeId is not null)
        {
            return PrimitiveArrayCodec.ReadPrimitiveArray<T>(ref context);
        }

        List<T> values = CollectionCodec.ReadCollectionData<T>(ElementSerializer, ref context);
        return values.ToArray();
    }
}

public readonly struct ListSerializer<T> : IStaticSerializer<ListSerializer<T>, List<T>>
{
    private static Serializer<T> ElementSerializer => SerializerRegistry.Get<T>();

    public static TypeId StaticTypeId => TypeId.List;
    public static bool IsNullableType => true;
    public static bool IsReferenceTrackableType => true;
    public static List<T> DefaultValue => null!;
    public static bool IsNone(in List<T> value) => value is null;

    public static void WriteData(ref WriteContext context, in List<T> value, bool hasGenerics)
    {
        List<T> safe = value ?? [];
        CollectionCodec.WriteCollectionData(safe, ElementSerializer, ref context, hasGenerics);
    }

    public static List<T> ReadData(ref ReadContext context)
    {
        return CollectionCodec.ReadCollectionData(ElementSerializer, ref context);
    }
}

public readonly struct SetSerializer<T> : IStaticSerializer<SetSerializer<T>, HashSet<T>> where T : notnull
{
    private static Serializer<List<T>> ListSerializer => SerializerRegistry.Get<List<T>>();

    public static TypeId StaticTypeId => TypeId.Set;
    public static bool IsNullableType => true;
    public static bool IsReferenceTrackableType => true;
    public static HashSet<T> DefaultValue => null!;
    public static bool IsNone(in HashSet<T> value) => value is null;

    public static void WriteData(ref WriteContext context, in HashSet<T> value, bool hasGenerics)
    {
        List<T> list = value is null ? [] : [.. value];
        ListSerializer.WriteData(ref context, list, hasGenerics);
    }

    public static HashSet<T> ReadData(ref ReadContext context)
    {
        return [.. ListSerializer.ReadData(ref context)];
    }
}

