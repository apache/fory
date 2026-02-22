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
