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

internal static class PrimitiveDictionaryHeader
{
    public static void WriteMapChunkTypeInfo(
        ref WriteContext context,
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

    static abstract void Write(ref WriteContext context, T value);

    static abstract T Read(ref ReadContext context);
}

internal readonly struct StringPrimitiveDictionaryCodec : IPrimitiveDictionaryCodec<string>
{
    public static TypeId WireTypeId => TypeId.String;

    public static bool IsNullable => true;

    public static string DefaultValue => null!;

    public static bool IsNone(string value) => value is null;

    public static void Write(ref WriteContext context, string value)
    {
        StringSerializer.WriteString(ref context, value ?? string.Empty);
    }

    public static string Read(ref ReadContext context)
    {
        return StringSerializer.ReadString(ref context);
    }
}

internal readonly struct BoolPrimitiveDictionaryCodec : IPrimitiveDictionaryCodec<bool>
{
    public static TypeId WireTypeId => TypeId.Bool;

    public static bool IsNullable => false;

    public static bool DefaultValue => false;

    public static bool IsNone(bool value) => false;

    public static void Write(ref WriteContext context, bool value)
    {
        context.Writer.WriteUInt8(value ? (byte)1 : (byte)0);
    }

    public static bool Read(ref ReadContext context)
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

    public static void Write(ref WriteContext context, sbyte value)
    {
        context.Writer.WriteInt8(value);
    }

    public static sbyte Read(ref ReadContext context)
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

    public static void Write(ref WriteContext context, short value)
    {
        context.Writer.WriteInt16(value);
    }

    public static short Read(ref ReadContext context)
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

    public static void Write(ref WriteContext context, int value)
    {
        context.Writer.WriteVarInt32(value);
    }

    public static int Read(ref ReadContext context)
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

    public static void Write(ref WriteContext context, long value)
    {
        context.Writer.WriteVarInt64(value);
    }

    public static long Read(ref ReadContext context)
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

    public static void Write(ref WriteContext context, ushort value)
    {
        context.Writer.WriteUInt16(value);
    }

    public static ushort Read(ref ReadContext context)
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

    public static void Write(ref WriteContext context, uint value)
    {
        context.Writer.WriteVarUInt32(value);
    }

    public static uint Read(ref ReadContext context)
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

    public static void Write(ref WriteContext context, ulong value)
    {
        context.Writer.WriteVarUInt64(value);
    }

    public static ulong Read(ref ReadContext context)
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

    public static void Write(ref WriteContext context, float value)
    {
        context.Writer.WriteFloat32(value);
    }

    public static float Read(ref ReadContext context)
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

    public static void Write(ref WriteContext context, double value)
    {
        context.Writer.WriteFloat64(value);
    }

    public static double Read(ref ReadContext context)
    {
        return context.Reader.ReadFloat64();
    }
}

internal static class PrimitiveDictionaryCodecWriter
{
    public static void WriteMap<TKey, TValue, TKeyCodec, TValueCodec>(
        ref WriteContext context,
        Dictionary<TKey, TValue> map,
        bool hasGenerics)
        where TKey : notnull
        where TKeyCodec : struct, IPrimitiveDictionaryCodec<TKey>
        where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
    {
        KeyValuePair<TKey, TValue>[] pairs = [.. map];
        context.Writer.WriteVarUInt32((uint)pairs.Length);
        if (pairs.Length == 0)
        {
            return;
        }

        TypeId keyTypeId = TKeyCodec.WireTypeId;
        TypeId valueTypeId = TValueCodec.WireTypeId;
        bool keyDeclared = hasGenerics && !keyTypeId.NeedsTypeInfoForField();
        bool valueDeclared = hasGenerics && !valueTypeId.NeedsTypeInfoForField();
        bool keyNullable = TKeyCodec.IsNullable;
        bool valueNullable = TValueCodec.IsNullable;

        int index = 0;
        while (index < pairs.Length)
        {
            KeyValuePair<TKey, TValue> pair = pairs[index];
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

                    TKeyCodec.Write(ref context, pair.Key);
                }

                if (!valueNull)
                {
                    if (!valueDeclared)
                    {
                        context.Writer.WriteUInt8((byte)valueTypeId);
                    }

                    TValueCodec.Write(ref context, pair.Value);
                }

                index += 1;
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
            PrimitiveDictionaryHeader.WriteMapChunkTypeInfo(ref context, keyDeclared, valueDeclared, keyTypeId, valueTypeId);

            byte chunkSize = 0;
            while (index < pairs.Length && chunkSize < byte.MaxValue)
            {
                pair = pairs[index];
                keyNull = keyNullable && TKeyCodec.IsNone(pair.Key);
                valueNull = valueNullable && TValueCodec.IsNone(pair.Value);
                if (keyNull || valueNull)
                {
                    break;
                }

                TKeyCodec.Write(ref context, pair.Key);
                TValueCodec.Write(ref context, pair.Value);
                index += 1;
                chunkSize += 1;
            }

            context.Writer.SetByte(chunkSizeOffset, chunkSize);
        }
    }
}

internal static class PrimitiveDictionaryCodecReader
{
    public static Dictionary<TKey, TValue> ReadMap<TKey, TValue, TKeyCodec, TValueCodec>(ref ReadContext context)
        where TKey : notnull
        where TKeyCodec : struct, IPrimitiveDictionaryCodec<TKey>
        where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
    {
        int totalLength = checked((int)context.Reader.ReadVarUInt32());
        if (totalLength == 0)
        {
            return [];
        }

        TypeId keyTypeId = TKeyCodec.WireTypeId;
        TypeId valueTypeId = TValueCodec.WireTypeId;
        bool keyNullable = TKeyCodec.IsNullable;
        Dictionary<TKey, TValue> map = new(totalLength);

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
                    ReadAndValidateTypeInfo(ref context, valueTypeId);
                }

                _ = TValueCodec.Read(ref context);
                readCount += 1;
                continue;
            }

            if (valueNull)
            {
                if (!keyDeclared)
                {
                    ReadAndValidateTypeInfo(ref context, keyTypeId);
                }

                TKey key = TKeyCodec.Read(ref context);
                map[key] = TValueCodec.DefaultValue;
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
                ReadAndValidateTypeInfo(ref context, keyTypeId);
            }

            if (!valueDeclared)
            {
                ReadAndValidateTypeInfo(ref context, valueTypeId);
            }

            for (int i = 0; i < chunkSize; i++)
            {
                TKey key = TKeyCodec.Read(ref context);
                TValue value = TValueCodec.Read(ref context);
                map[key] = value;
            }

            readCount += chunkSize;
        }

        return map;
    }

    private static void ReadAndValidateTypeInfo(ref ReadContext context, TypeId expectedTypeId)
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
    public override TypeId StaticTypeId => TypeId.Map;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override Dictionary<TKey, TValue> DefaultValue => null!;

    public override bool IsNone(in Dictionary<TKey, TValue> value) => value is null;

    public override void WriteData(ref WriteContext context, in Dictionary<TKey, TValue> value, bool hasGenerics)
    {
        Dictionary<TKey, TValue> map = value ?? [];
        PrimitiveDictionaryCodecWriter.WriteMap<TKey, TValue, TKeyCodec, TValueCodec>(ref context, map, hasGenerics);
    }

    public override Dictionary<TKey, TValue> ReadData(ref ReadContext context)
    {
        return PrimitiveDictionaryCodecReader.ReadMap<TKey, TValue, TKeyCodec, TValueCodec>(ref context);
    }
}

internal class PrimitiveStringKeyDictionarySerializer<TValue, TValueCodec>
    : PrimitiveDictionarySerializer<string, TValue, StringPrimitiveDictionaryCodec, TValueCodec>
    where TValueCodec : struct, IPrimitiveDictionaryCodec<TValue>
{
}

internal sealed class DictionaryStringStringSerializer
    : PrimitiveDictionarySerializer<string, string, StringPrimitiveDictionaryCodec, StringPrimitiveDictionaryCodec>
{
}

internal sealed class DictionaryStringIntSerializer
    : PrimitiveStringKeyDictionarySerializer<int, Int32PrimitiveDictionaryCodec>
{
}

internal sealed class DictionaryStringLongSerializer
    : PrimitiveStringKeyDictionarySerializer<long, Int64PrimitiveDictionaryCodec>
{
}

internal sealed class DictionaryStringBoolSerializer
    : PrimitiveStringKeyDictionarySerializer<bool, BoolPrimitiveDictionaryCodec>
{
}

internal sealed class DictionaryStringFloatSerializer
    : PrimitiveStringKeyDictionarySerializer<float, Float32PrimitiveDictionaryCodec>
{
}

internal sealed class DictionaryStringDoubleSerializer
    : PrimitiveStringKeyDictionarySerializer<double, Float64PrimitiveDictionaryCodec>
{
}

internal sealed class DictionaryStringUIntSerializer
    : PrimitiveStringKeyDictionarySerializer<uint, UInt32PrimitiveDictionaryCodec>
{
}

internal sealed class DictionaryStringULongSerializer
    : PrimitiveStringKeyDictionarySerializer<ulong, UInt64PrimitiveDictionaryCodec>
{
}

internal sealed class DictionaryStringInt8Serializer
    : PrimitiveStringKeyDictionarySerializer<sbyte, Int8PrimitiveDictionaryCodec>
{
}

internal sealed class DictionaryStringInt16Serializer
    : PrimitiveStringKeyDictionarySerializer<short, Int16PrimitiveDictionaryCodec>
{
}

internal sealed class DictionaryStringUInt16Serializer
    : PrimitiveStringKeyDictionarySerializer<ushort, UInt16PrimitiveDictionaryCodec>
{
}

internal sealed class DictionaryIntIntSerializer
    : PrimitiveDictionarySerializer<int, int, Int32PrimitiveDictionaryCodec, Int32PrimitiveDictionaryCodec>
{
}

internal sealed class DictionaryLongLongSerializer
    : PrimitiveDictionarySerializer<long, long, Int64PrimitiveDictionaryCodec, Int64PrimitiveDictionaryCodec>
{
}
