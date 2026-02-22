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

internal static class PrimitiveCollectionHeader
{
    public static void WriteListHeader(ref WriteContext context, int count, bool hasGenerics, TypeId elementTypeId, bool hasNull)
    {
        context.Writer.WriteVarUInt32((uint)count);
        if (count == 0)
        {
            return;
        }

        bool declared = hasGenerics && !elementTypeId.NeedsTypeInfoForField();
        byte header = CollectionBits.SameType;
        if (hasNull)
        {
            header |= CollectionBits.HasNull;
        }

        if (declared)
        {
            header |= CollectionBits.DeclaredElementType;
        }

        context.Writer.WriteUInt8(header);
        if (!declared)
        {
            context.Writer.WriteUInt8((byte)elementTypeId);
        }
    }

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

internal sealed class ListBoolSerializer : Serializer<List<bool>>
{
    private static readonly ListSerializer<bool> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<bool> DefaultValue => null!;

    public override bool IsNone(in List<bool> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<bool> value, bool hasGenerics)
    {
        List<bool> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.Bool, false);
        for (int i = 0; i < list.Count; i++)
        {
            context.Writer.WriteUInt8(list[i] ? (byte)1 : (byte)0);
        }
    }

    public override List<bool> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListIntSerializer : Serializer<List<int>>
{
    private static readonly ListSerializer<int> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<int> DefaultValue => null!;

    public override bool IsNone(in List<int> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<int> value, bool hasGenerics)
    {
        List<int> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.VarInt32, false);
        for (int i = 0; i < list.Count; i++)
        {
            context.Writer.WriteVarInt32(list[i]);
        }
    }

    public override List<int> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListLongSerializer : Serializer<List<long>>
{
    private static readonly ListSerializer<long> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<long> DefaultValue => null!;

    public override bool IsNone(in List<long> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<long> value, bool hasGenerics)
    {
        List<long> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.VarInt64, false);
        for (int i = 0; i < list.Count; i++)
        {
            context.Writer.WriteVarInt64(list[i]);
        }
    }

    public override List<long> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListStringSerializer : Serializer<List<string>>
{
    private static readonly ListSerializer<string> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<string> DefaultValue => null!;

    public override bool IsNone(in List<string> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<string> value, bool hasGenerics)
    {
        List<string> list = value ?? [];
        bool hasNull = false;
        for (int i = 0; i < list.Count; i++)
        {
            if (list[i] is null)
            {
                hasNull = true;
                break;
            }
        }

        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.String, hasNull);
        if (hasNull)
        {
            for (int i = 0; i < list.Count; i++)
            {
                string? item = list[i];
                if (item is null)
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.Null);
                    continue;
                }

                context.Writer.WriteInt8((sbyte)RefFlag.NotNullValue);
                StringSerializer.WriteString(ref context, item);
            }

            return;
        }

        for (int i = 0; i < list.Count; i++)
        {
            StringSerializer.WriteString(ref context, list[i]);
        }
    }

    public override List<string> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListDateOnlySerializer : Serializer<List<DateOnly>>
{
    private static readonly ListSerializer<DateOnly> Fallback = new();
    private static readonly DateOnly Epoch = new(1970, 1, 1);

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<DateOnly> DefaultValue => null!;

    public override bool IsNone(in List<DateOnly> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<DateOnly> value, bool hasGenerics)
    {
        List<DateOnly> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.Date, false);
        for (int i = 0; i < list.Count; i++)
        {
            context.Writer.WriteInt32(list[i].DayNumber - Epoch.DayNumber);
        }
    }

    public override List<DateOnly> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListDateTimeOffsetSerializer : Serializer<List<DateTimeOffset>>
{
    private static readonly ListSerializer<DateTimeOffset> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<DateTimeOffset> DefaultValue => null!;

    public override bool IsNone(in List<DateTimeOffset> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<DateTimeOffset> value, bool hasGenerics)
    {
        List<DateTimeOffset> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.Timestamp, false);
        for (int i = 0; i < list.Count; i++)
        {
            ForyTimestamp ts = ForyTimestamp.FromDateTimeOffset(list[i]);
            context.Writer.WriteInt64(ts.Seconds);
            context.Writer.WriteUInt32(ts.Nanos);
        }
    }

    public override List<DateTimeOffset> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListDateTimeSerializer : Serializer<List<DateTime>>
{
    private static readonly ListSerializer<DateTime> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<DateTime> DefaultValue => null!;

    public override bool IsNone(in List<DateTime> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<DateTime> value, bool hasGenerics)
    {
        List<DateTime> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.Timestamp, false);
        for (int i = 0; i < list.Count; i++)
        {
            DateTimeOffset dto = list[i].Kind switch
            {
                DateTimeKind.Utc => new DateTimeOffset(list[i], TimeSpan.Zero),
                DateTimeKind.Local => list[i],
                _ => new DateTimeOffset(DateTime.SpecifyKind(list[i], DateTimeKind.Utc)),
            };
            ForyTimestamp ts = ForyTimestamp.FromDateTimeOffset(dto);
            context.Writer.WriteInt64(ts.Seconds);
            context.Writer.WriteUInt32(ts.Nanos);
        }
    }

    public override List<DateTime> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListTimeSpanSerializer : Serializer<List<TimeSpan>>
{
    private static readonly ListSerializer<TimeSpan> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<TimeSpan> DefaultValue => null!;

    public override bool IsNone(in List<TimeSpan> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<TimeSpan> value, bool hasGenerics)
    {
        List<TimeSpan> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.Duration, false);
        for (int i = 0; i < list.Count; i++)
        {
            long seconds = list[i].Ticks / TimeSpan.TicksPerSecond;
            int nanos = checked((int)((list[i].Ticks % TimeSpan.TicksPerSecond) * 100));
            context.Writer.WriteInt64(seconds);
            context.Writer.WriteInt32(nanos);
        }
    }

    public override List<TimeSpan> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class DictionaryStringStringSerializer : Serializer<Dictionary<string, string>>
{
    private static readonly DictionarySerializer<string, string> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Map;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override Dictionary<string, string> DefaultValue => null!;

    public override bool IsNone(in Dictionary<string, string> value) => value is null;

    public override void WriteData(ref WriteContext context, in Dictionary<string, string> value, bool hasGenerics)
    {
        Dictionary<string, string> map = value ?? [];
        if (ContainsNull(map))
        {
            Fallback.WriteData(ref context, map, hasGenerics);
            return;
        }

        WriteMapStringString(ref context, map, hasGenerics);
    }

    public override Dictionary<string, string> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }

    private static bool ContainsNull(Dictionary<string, string> map)
    {
        foreach (KeyValuePair<string, string> pair in map)
        {
            if (pair.Key is null || pair.Value is null)
            {
                return true;
            }
        }

        return false;
    }

    private static void WriteMapStringString(ref WriteContext context, Dictionary<string, string> map, bool hasGenerics)
    {
        KeyValuePair<string, string>[] pairs = [.. map];
        context.Writer.WriteVarUInt32((uint)pairs.Length);
        if (pairs.Length == 0)
        {
            return;
        }

        bool keyDeclared = hasGenerics && !TypeId.String.NeedsTypeInfoForField();
        bool valueDeclared = hasGenerics && !TypeId.String.NeedsTypeInfoForField();
        int index = 0;
        while (index < pairs.Length)
        {
            int chunkSize = Math.Min(byte.MaxValue, pairs.Length - index);
            byte header = 0;
            if (keyDeclared)
            {
                header |= DictionaryBits.DeclaredKeyType;
            }

            if (valueDeclared)
            {
                header |= DictionaryBits.DeclaredValueType;
            }

            context.Writer.WriteUInt8(header);
            context.Writer.WriteUInt8((byte)chunkSize);
            PrimitiveCollectionHeader.WriteMapChunkTypeInfo(ref context, keyDeclared, valueDeclared, TypeId.String, TypeId.String);
            for (int i = 0; i < chunkSize; i++)
            {
                KeyValuePair<string, string> pair = pairs[index + i];
                StringSerializer.WriteString(ref context, pair.Key);
                StringSerializer.WriteString(ref context, pair.Value);
            }

            index += chunkSize;
        }
    }
}

internal sealed class DictionaryStringIntSerializer : Serializer<Dictionary<string, int>>
{
    private static readonly DictionarySerializer<string, int> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Map;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override Dictionary<string, int> DefaultValue => null!;

    public override bool IsNone(in Dictionary<string, int> value) => value is null;

    public override void WriteData(ref WriteContext context, in Dictionary<string, int> value, bool hasGenerics)
    {
        Dictionary<string, int> map = value ?? [];
        PrimitiveDictionaryWriter.WriteMapStringValue(
            ref context,
            map,
            hasGenerics,
            TypeId.VarInt32,
            static (writer, valueItem) => writer.WriteVarInt32(valueItem));
    }

    public override Dictionary<string, int> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class DictionaryStringLongSerializer : Serializer<Dictionary<string, long>>
{
    private static readonly DictionarySerializer<string, long> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Map;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override Dictionary<string, long> DefaultValue => null!;

    public override bool IsNone(in Dictionary<string, long> value) => value is null;

    public override void WriteData(ref WriteContext context, in Dictionary<string, long> value, bool hasGenerics)
    {
        Dictionary<string, long> map = value ?? [];
        PrimitiveDictionaryWriter.WriteMapStringValue(
            ref context,
            map,
            hasGenerics,
            TypeId.VarInt64,
            static (writer, valueItem) => writer.WriteVarInt64(valueItem));
    }

    public override Dictionary<string, long> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class DictionaryStringBoolSerializer : Serializer<Dictionary<string, bool>>
{
    private static readonly DictionarySerializer<string, bool> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Map;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override Dictionary<string, bool> DefaultValue => null!;

    public override bool IsNone(in Dictionary<string, bool> value) => value is null;

    public override void WriteData(ref WriteContext context, in Dictionary<string, bool> value, bool hasGenerics)
    {
        Dictionary<string, bool> map = value ?? [];
        PrimitiveDictionaryWriter.WriteMapStringValue(
            ref context,
            map,
            hasGenerics,
            TypeId.Bool,
            static (writer, valueItem) => writer.WriteUInt8(valueItem ? (byte)1 : (byte)0));
    }

    public override Dictionary<string, bool> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class DictionaryStringDoubleSerializer : Serializer<Dictionary<string, double>>
{
    private static readonly DictionarySerializer<string, double> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Map;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override Dictionary<string, double> DefaultValue => null!;

    public override bool IsNone(in Dictionary<string, double> value) => value is null;

    public override void WriteData(ref WriteContext context, in Dictionary<string, double> value, bool hasGenerics)
    {
        Dictionary<string, double> map = value ?? [];
        PrimitiveDictionaryWriter.WriteMapStringValue(
            ref context,
            map,
            hasGenerics,
            TypeId.Float64,
            static (writer, valueItem) => writer.WriteFloat64(valueItem));
    }

    public override Dictionary<string, double> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class DictionaryIntIntSerializer : Serializer<Dictionary<int, int>>
{
    private static readonly DictionarySerializer<int, int> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Map;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override Dictionary<int, int> DefaultValue => null!;

    public override bool IsNone(in Dictionary<int, int> value) => value is null;

    public override void WriteData(ref WriteContext context, in Dictionary<int, int> value, bool hasGenerics)
    {
        Dictionary<int, int> map = value ?? [];
        PrimitiveDictionaryWriter.WriteMap(
            ref context,
            map,
            hasGenerics,
            TypeId.VarInt32,
            static (writer, key) => writer.WriteVarInt32(key),
            TypeId.VarInt32,
            static (writer, valueItem) => writer.WriteVarInt32(valueItem));
    }

    public override Dictionary<int, int> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class DictionaryLongLongSerializer : Serializer<Dictionary<long, long>>
{
    private static readonly DictionarySerializer<long, long> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Map;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override Dictionary<long, long> DefaultValue => null!;

    public override bool IsNone(in Dictionary<long, long> value) => value is null;

    public override void WriteData(ref WriteContext context, in Dictionary<long, long> value, bool hasGenerics)
    {
        Dictionary<long, long> map = value ?? [];
        PrimitiveDictionaryWriter.WriteMap(
            ref context,
            map,
            hasGenerics,
            TypeId.VarInt64,
            static (writer, key) => writer.WriteVarInt64(key),
            TypeId.VarInt64,
            static (writer, valueItem) => writer.WriteVarInt64(valueItem));
    }

    public override Dictionary<long, long> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal static class PrimitiveDictionaryWriter
{
    public static void WriteMapStringValue<TValue>(
        ref WriteContext context,
        Dictionary<string, TValue> map,
        bool hasGenerics,
        TypeId valueTypeId,
        Action<ByteWriter, TValue> writeValue)
    {
        KeyValuePair<string, TValue>[] pairs = [.. map];
        context.Writer.WriteVarUInt32((uint)pairs.Length);
        if (pairs.Length == 0)
        {
            return;
        }

        bool keyDeclared = hasGenerics && !TypeId.String.NeedsTypeInfoForField();
        bool valueDeclared = hasGenerics && !valueTypeId.NeedsTypeInfoForField();
        int index = 0;
        while (index < pairs.Length)
        {
            int chunkSize = Math.Min(byte.MaxValue, pairs.Length - index);
            byte header = 0;
            if (keyDeclared)
            {
                header |= DictionaryBits.DeclaredKeyType;
            }

            if (valueDeclared)
            {
                header |= DictionaryBits.DeclaredValueType;
            }

            context.Writer.WriteUInt8(header);
            context.Writer.WriteUInt8((byte)chunkSize);
            PrimitiveCollectionHeader.WriteMapChunkTypeInfo(ref context, keyDeclared, valueDeclared, TypeId.String, valueTypeId);
            for (int i = 0; i < chunkSize; i++)
            {
                KeyValuePair<string, TValue> pair = pairs[index + i];
                StringSerializer.WriteString(ref context, pair.Key);
                writeValue(context.Writer, pair.Value);
            }

            index += chunkSize;
        }
    }

    public static void WriteMap<TKey, TValue>(
        ref WriteContext context,
        Dictionary<TKey, TValue> map,
        bool hasGenerics,
        TypeId keyTypeId,
        Action<ByteWriter, TKey> writeKey,
        TypeId valueTypeId,
        Action<ByteWriter, TValue> writeValue)
        where TKey : notnull
    {
        KeyValuePair<TKey, TValue>[] pairs = [.. map];
        context.Writer.WriteVarUInt32((uint)pairs.Length);
        if (pairs.Length == 0)
        {
            return;
        }

        bool keyDeclared = hasGenerics && !keyTypeId.NeedsTypeInfoForField();
        bool valueDeclared = hasGenerics && !valueTypeId.NeedsTypeInfoForField();
        int index = 0;
        while (index < pairs.Length)
        {
            int chunkSize = Math.Min(byte.MaxValue, pairs.Length - index);
            byte header = 0;
            if (keyDeclared)
            {
                header |= DictionaryBits.DeclaredKeyType;
            }

            if (valueDeclared)
            {
                header |= DictionaryBits.DeclaredValueType;
            }

            context.Writer.WriteUInt8(header);
            context.Writer.WriteUInt8((byte)chunkSize);
            PrimitiveCollectionHeader.WriteMapChunkTypeInfo(ref context, keyDeclared, valueDeclared, keyTypeId, valueTypeId);
            for (int i = 0; i < chunkSize; i++)
            {
                KeyValuePair<TKey, TValue> pair = pairs[index + i];
                writeKey(context.Writer, pair.Key);
                writeValue(context.Writer, pair.Value);
            }

            index += chunkSize;
        }
    }
}
