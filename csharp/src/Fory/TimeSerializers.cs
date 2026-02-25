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

internal static class TimeCodec
{
    private static readonly DateOnly Epoch = new(1970, 1, 1);

    public static void WriteDate(WriteContext context, in DateOnly value)
    {
        context.Writer.WriteInt32(value.DayNumber - Epoch.DayNumber);
    }

    public static DateOnly ReadDate(ReadContext context)
    {
        int days = context.Reader.ReadInt32();
        return DateOnly.FromDayNumber(Epoch.DayNumber + days);
    }

    public static DateTimeOffset ToDateTimeOffset(in DateTime value)
    {
        return value.Kind switch
        {
            DateTimeKind.Utc => new DateTimeOffset(value, TimeSpan.Zero),
            DateTimeKind.Local => value,
            _ => new DateTimeOffset(DateTime.SpecifyKind(value, DateTimeKind.Utc)),
        };
    }

    public static void WriteTimestamp(WriteContext context, in DateTimeOffset value)
    {
        (long seconds, uint nanos) = ToTimestampParts(value);
        context.Writer.WriteInt64(seconds);
        context.Writer.WriteUInt32(nanos);
    }

    public static DateTimeOffset ReadTimestamp(ReadContext context)
    {
        long seconds = context.Reader.ReadInt64();
        uint nanos = context.Reader.ReadUInt32();
        return DateTimeOffset.FromUnixTimeSeconds(seconds).AddTicks(nanos / 100);
    }

    public static void WriteDuration(WriteContext context, in TimeSpan value)
    {
        long seconds = value.Ticks / TimeSpan.TicksPerSecond;
        int nanos = checked((int)((value.Ticks % TimeSpan.TicksPerSecond) * 100));
        context.Writer.WriteInt64(seconds);
        context.Writer.WriteInt32(nanos);
    }

    public static TimeSpan ReadDuration(ReadContext context)
    {
        long seconds = context.Reader.ReadInt64();
        int nanos = context.Reader.ReadInt32();
        return TimeSpan.FromSeconds(seconds) + TimeSpan.FromTicks(nanos / 100);
    }

    private static (long Seconds, uint Nanos) ToTimestampParts(DateTimeOffset value)
    {
        long seconds = value.ToUnixTimeSeconds();
        long nanos = (value.Ticks % TimeSpan.TicksPerSecond) * 100;
        long normalizedSeconds = seconds + nanos / 1_000_000_000L;
        long normalizedNanos = nanos % 1_000_000_000L;
        if (normalizedNanos < 0)
        {
            normalizedNanos += 1_000_000_000L;
            normalizedSeconds -= 1;
        }

        return (normalizedSeconds, unchecked((uint)normalizedNanos));
    }
}

public sealed class DateOnlySerializer : Serializer<DateOnly>
{

    public override DateOnly DefaultValue => new(1970, 1, 1);

    public override void WriteData(WriteContext context, in DateOnly value, bool hasGenerics)
    {
        _ = hasGenerics;
        TimeCodec.WriteDate(context, value);
    }

    public override DateOnly ReadData(ReadContext context)
    {
        return TimeCodec.ReadDate(context);
    }
}

public sealed class DateTimeOffsetSerializer : Serializer<DateTimeOffset>
{

    public override DateTimeOffset DefaultValue => DateTimeOffset.UnixEpoch;

    public override void WriteData(WriteContext context, in DateTimeOffset value, bool hasGenerics)
    {
        _ = hasGenerics;
        TimeCodec.WriteTimestamp(context, value);
    }

    public override DateTimeOffset ReadData(ReadContext context)
    {
        return TimeCodec.ReadTimestamp(context);
    }
}

public sealed class DateTimeSerializer : Serializer<DateTime>
{

    public override DateTime DefaultValue => DateTime.UnixEpoch;

    public override void WriteData(WriteContext context, in DateTime value, bool hasGenerics)
    {
        _ = hasGenerics;
        DateTimeOffset dto = TimeCodec.ToDateTimeOffset(value);
        TimeCodec.WriteTimestamp(context, dto);
    }

    public override DateTime ReadData(ReadContext context)
    {
        return TimeCodec.ReadTimestamp(context).UtcDateTime;
    }
}

public sealed class TimeSpanSerializer : Serializer<TimeSpan>
{

    public override TimeSpan DefaultValue => TimeSpan.Zero;

    public override void WriteData(WriteContext context, in TimeSpan value, bool hasGenerics)
    {
        _ = hasGenerics;
        TimeCodec.WriteDuration(context, value);
    }

    public override TimeSpan ReadData(ReadContext context)
    {
        return TimeCodec.ReadDuration(context);
    }
}

internal sealed class ListDateOnlySerializer : Serializer<List<DateOnly>>
{
    private static readonly ListSerializer<DateOnly> Fallback = new();




    public override List<DateOnly> DefaultValue => null!;

    public override void WriteData(WriteContext context, in List<DateOnly> value, bool hasGenerics)
    {
        List<DateOnly> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(context, list.Count, hasGenerics, TypeId.Date, false);
        for (int i = 0; i < list.Count; i++)
        {
            TimeCodec.WriteDate(context, list[i]);
        }
    }

    public override List<DateOnly> ReadData(ReadContext context)
    {
        return Fallback.ReadData(context);
    }
}

internal sealed class ListDateTimeOffsetSerializer : Serializer<List<DateTimeOffset>>
{
    private static readonly ListSerializer<DateTimeOffset> Fallback = new();




    public override List<DateTimeOffset> DefaultValue => null!;

    public override void WriteData(WriteContext context, in List<DateTimeOffset> value, bool hasGenerics)
    {
        List<DateTimeOffset> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(context, list.Count, hasGenerics, TypeId.Timestamp, false);
        for (int i = 0; i < list.Count; i++)
        {
            TimeCodec.WriteTimestamp(context, list[i]);
        }
    }

    public override List<DateTimeOffset> ReadData(ReadContext context)
    {
        return Fallback.ReadData(context);
    }
}

internal sealed class ListDateTimeSerializer : Serializer<List<DateTime>>
{
    private static readonly ListSerializer<DateTime> Fallback = new();




    public override List<DateTime> DefaultValue => null!;

    public override void WriteData(WriteContext context, in List<DateTime> value, bool hasGenerics)
    {
        List<DateTime> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(context, list.Count, hasGenerics, TypeId.Timestamp, false);
        for (int i = 0; i < list.Count; i++)
        {
            DateTimeOffset dto = TimeCodec.ToDateTimeOffset(list[i]);
            TimeCodec.WriteTimestamp(context, dto);
        }
    }

    public override List<DateTime> ReadData(ReadContext context)
    {
        return Fallback.ReadData(context);
    }
}

internal sealed class ListTimeSpanSerializer : Serializer<List<TimeSpan>>
{
    private static readonly ListSerializer<TimeSpan> Fallback = new();




    public override List<TimeSpan> DefaultValue => null!;

    public override void WriteData(WriteContext context, in List<TimeSpan> value, bool hasGenerics)
    {
        List<TimeSpan> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(context, list.Count, hasGenerics, TypeId.Duration, false);
        for (int i = 0; i < list.Count; i++)
        {
            TimeCodec.WriteDuration(context, list[i]);
        }
    }

    public override List<TimeSpan> ReadData(ReadContext context)
    {
        return Fallback.ReadData(context);
    }
}
