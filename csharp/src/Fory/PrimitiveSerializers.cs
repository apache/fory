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

using System.Text;

namespace Apache.Fory;

internal enum ForyStringEncoding : ulong
{
    Latin1 = 0,
    Utf16 = 1,
    Utf8 = 2,
}

public readonly record struct ForyInt32Fixed(int RawValue);

public readonly record struct ForyInt64Fixed(long RawValue);

public readonly record struct ForyInt64Tagged(long RawValue);

public readonly record struct ForyUInt32Fixed(uint RawValue);

public readonly record struct ForyUInt64Fixed(ulong RawValue);

public readonly record struct ForyUInt64Tagged(ulong RawValue);

public readonly record struct ForyDate(int DaysSinceEpoch);

public readonly record struct ForyTimestamp(long Seconds, uint Nanos)
{
    public static ForyTimestamp FromDateTimeOffset(DateTimeOffset value)
    {
        long seconds = value.ToUnixTimeSeconds();
        long nanos = (value.Ticks % TimeSpan.TicksPerSecond) * 100;
        return Normalize(seconds, nanos);
    }

    public DateTimeOffset ToDateTimeOffset()
    {
        return DateTimeOffset.FromUnixTimeSeconds(Seconds).AddTicks(Nanos / 100);
    }

    private static ForyTimestamp Normalize(long seconds, long nanos)
    {
        long normalizedSeconds = seconds + nanos / 1_000_000_000L;
        long normalizedNanos = nanos % 1_000_000_000L;
        if (normalizedNanos < 0)
        {
            normalizedNanos += 1_000_000_000L;
            normalizedSeconds -= 1;
        }

        return new ForyTimestamp(normalizedSeconds, unchecked((uint)normalizedNanos));
    }
}

public sealed class BoolSerializer : Serializer<bool>
{
    public static BoolSerializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.Bool;
    public override bool DefaultValue => false;
    public override void WriteData(ref WriteContext context, in bool value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteUInt8(value ? (byte)1 : (byte)0);
    }

    public override bool ReadData(ref ReadContext context)
    {
        return context.Reader.ReadUInt8() != 0;
    }
}

public sealed class Int8Serializer : Serializer<sbyte>
{
    public static Int8Serializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.Int8;
    public override sbyte DefaultValue => 0;
    public override void WriteData(ref WriteContext context, in sbyte value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteInt8(value);
    }

    public override sbyte ReadData(ref ReadContext context)
    {
        return context.Reader.ReadInt8();
    }
}

public sealed class Int16Serializer : Serializer<short>
{
    public static Int16Serializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.Int16;
    public override short DefaultValue => 0;
    public override void WriteData(ref WriteContext context, in short value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteInt16(value);
    }

    public override short ReadData(ref ReadContext context)
    {
        return context.Reader.ReadInt16();
    }
}

public sealed class Int32Serializer : Serializer<int>
{
    public static Int32Serializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.VarInt32;
    public override int DefaultValue => 0;
    public override void WriteData(ref WriteContext context, in int value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteVarInt32(value);
    }

    public override int ReadData(ref ReadContext context)
    {
        return context.Reader.ReadVarInt32();
    }
}

public sealed class Int64Serializer : Serializer<long>
{
    public static Int64Serializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.VarInt64;
    public override long DefaultValue => 0;
    public override void WriteData(ref WriteContext context, in long value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteVarInt64(value);
    }

    public override long ReadData(ref ReadContext context)
    {
        return context.Reader.ReadVarInt64();
    }
}

public sealed class UInt8Serializer : Serializer<byte>
{
    public static UInt8Serializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.UInt8;
    public override byte DefaultValue => 0;
    public override void WriteData(ref WriteContext context, in byte value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteUInt8(value);
    }

    public override byte ReadData(ref ReadContext context)
    {
        return context.Reader.ReadUInt8();
    }
}

public sealed class UInt16Serializer : Serializer<ushort>
{
    public static UInt16Serializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.UInt16;
    public override ushort DefaultValue => 0;
    public override void WriteData(ref WriteContext context, in ushort value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteUInt16(value);
    }

    public override ushort ReadData(ref ReadContext context)
    {
        return context.Reader.ReadUInt16();
    }
}

public sealed class UInt32Serializer : Serializer<uint>
{
    public static UInt32Serializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.VarUInt32;
    public override uint DefaultValue => 0;
    public override void WriteData(ref WriteContext context, in uint value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteVarUInt32(value);
    }

    public override uint ReadData(ref ReadContext context)
    {
        return context.Reader.ReadVarUInt32();
    }
}

public sealed class UInt64Serializer : Serializer<ulong>
{
    public static UInt64Serializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.VarUInt64;
    public override ulong DefaultValue => 0;
    public override void WriteData(ref WriteContext context, in ulong value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteVarUInt64(value);
    }

    public override ulong ReadData(ref ReadContext context)
    {
        return context.Reader.ReadVarUInt64();
    }
}

public sealed class Float32Serializer : Serializer<float>
{
    public static Float32Serializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.Float32;
    public override float DefaultValue => 0;
    public override void WriteData(ref WriteContext context, in float value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteFloat32(value);
    }

    public override float ReadData(ref ReadContext context)
    {
        return context.Reader.ReadFloat32();
    }
}

public sealed class Float64Serializer : Serializer<double>
{
    public static Float64Serializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.Float64;
    public override double DefaultValue => 0;
    public override void WriteData(ref WriteContext context, in double value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteFloat64(value);
    }

    public override double ReadData(ref ReadContext context)
    {
        return context.Reader.ReadFloat64();
    }
}

public sealed class StringSerializer : Serializer<string>
{
    public static StringSerializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.String;
    public override bool IsNullableType => true;
    public override string DefaultValue => null!;
    public override bool IsNone(string value) => value is null;

    public override void WriteData(ref WriteContext context, in string value, bool hasGenerics)
    {
        _ = hasGenerics;
        string safe = value ?? string.Empty;
        byte[] utf8 = Encoding.UTF8.GetBytes(safe);
        ulong header = ((ulong)utf8.Length << 2) | (ulong)ForyStringEncoding.Utf8;
        context.Writer.WriteVarUInt36Small(header);
        context.Writer.WriteBytes(utf8);
    }

    public override string ReadData(ref ReadContext context)
    {
        ulong header = context.Reader.ReadVarUInt36Small();
        ulong encoding = header & 0x03;
        int byteLength = checked((int)(header >> 2));
        byte[] bytes = context.Reader.ReadBytes(byteLength);
        return encoding switch
        {
            (ulong)ForyStringEncoding.Utf8 => Encoding.UTF8.GetString(bytes),
            (ulong)ForyStringEncoding.Latin1 => DecodeLatin1(bytes),
            (ulong)ForyStringEncoding.Utf16 => DecodeUtf16(bytes),
            _ => throw new ForyEncodingException($"unsupported string encoding {encoding}"),
        };
    }

    private static string DecodeLatin1(byte[] bytes)
    {
        return string.Create(bytes.Length, bytes, static (span, b) =>
        {
            for (int i = 0; i < b.Length; i++)
            {
                span[i] = (char)b[i];
            }
        });
    }

    private static string DecodeUtf16(byte[] bytes)
    {
        if ((bytes.Length & 1) != 0)
        {
            throw new ForyEncodingException("utf16 byte length is not even");
        }

        return Encoding.Unicode.GetString(bytes);
    }
}

public sealed class BinarySerializer : Serializer<byte[]>
{
    public static BinarySerializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.Binary;
    public override bool IsNullableType => true;
    public override byte[] DefaultValue => null!;
    public override bool IsNone(byte[] value) => value is null;

    public override void WriteData(ref WriteContext context, in byte[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        byte[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)safe.Length);
        context.Writer.WriteBytes(safe);
    }

    public override byte[] ReadData(ref ReadContext context)
    {
        uint length = context.Reader.ReadVarUInt32();
        return context.Reader.ReadBytes(checked((int)length));
    }
}

public sealed class ForyInt32FixedSerializer : Serializer<ForyInt32Fixed>
{
    public static ForyInt32FixedSerializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.Int32;
    public override ForyInt32Fixed DefaultValue => new(0);
    public override void WriteData(ref WriteContext context, in ForyInt32Fixed value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteInt32(value.RawValue);
    }

    public override ForyInt32Fixed ReadData(ref ReadContext context)
    {
        return new ForyInt32Fixed(context.Reader.ReadInt32());
    }
}

public sealed class ForyInt64FixedSerializer : Serializer<ForyInt64Fixed>
{
    public static ForyInt64FixedSerializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.Int64;
    public override ForyInt64Fixed DefaultValue => new(0);
    public override void WriteData(ref WriteContext context, in ForyInt64Fixed value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteInt64(value.RawValue);
    }

    public override ForyInt64Fixed ReadData(ref ReadContext context)
    {
        return new ForyInt64Fixed(context.Reader.ReadInt64());
    }
}

public sealed class ForyInt64TaggedSerializer : Serializer<ForyInt64Tagged>
{
    public static ForyInt64TaggedSerializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.TaggedInt64;
    public override ForyInt64Tagged DefaultValue => new(0);
    public override void WriteData(ref WriteContext context, in ForyInt64Tagged value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteTaggedInt64(value.RawValue);
    }

    public override ForyInt64Tagged ReadData(ref ReadContext context)
    {
        return new ForyInt64Tagged(context.Reader.ReadTaggedInt64());
    }
}

public sealed class ForyUInt32FixedSerializer : Serializer<ForyUInt32Fixed>
{
    public static ForyUInt32FixedSerializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.UInt32;
    public override ForyUInt32Fixed DefaultValue => new(0);
    public override void WriteData(ref WriteContext context, in ForyUInt32Fixed value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteUInt32(value.RawValue);
    }

    public override ForyUInt32Fixed ReadData(ref ReadContext context)
    {
        return new ForyUInt32Fixed(context.Reader.ReadUInt32());
    }
}

public sealed class ForyUInt64FixedSerializer : Serializer<ForyUInt64Fixed>
{
    public static ForyUInt64FixedSerializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.UInt64;
    public override ForyUInt64Fixed DefaultValue => new(0);
    public override void WriteData(ref WriteContext context, in ForyUInt64Fixed value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteUInt64(value.RawValue);
    }

    public override ForyUInt64Fixed ReadData(ref ReadContext context)
    {
        return new ForyUInt64Fixed(context.Reader.ReadUInt64());
    }
}

public sealed class ForyUInt64TaggedSerializer : Serializer<ForyUInt64Tagged>
{
    public static ForyUInt64TaggedSerializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.TaggedUInt64;
    public override ForyUInt64Tagged DefaultValue => new(0);
    public override void WriteData(ref WriteContext context, in ForyUInt64Tagged value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteTaggedUInt64(value.RawValue);
    }

    public override ForyUInt64Tagged ReadData(ref ReadContext context)
    {
        return new ForyUInt64Tagged(context.Reader.ReadTaggedUInt64());
    }
}

public sealed class DateOnlySerializer : Serializer<DateOnly>
{
    private static readonly DateOnly Epoch = new(1970, 1, 1);
    public static DateOnlySerializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.Date;
    public override DateOnly DefaultValue => Epoch;

    public override void WriteData(ref WriteContext context, in DateOnly value, bool hasGenerics)
    {
        _ = hasGenerics;
        int days = value.DayNumber - Epoch.DayNumber;
        context.Writer.WriteInt32(days);
    }

    public override DateOnly ReadData(ref ReadContext context)
    {
        int days = context.Reader.ReadInt32();
        return DateOnly.FromDayNumber(Epoch.DayNumber + days);
    }
}

public sealed class DateTimeOffsetSerializer : Serializer<DateTimeOffset>
{
    public static DateTimeOffsetSerializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.Timestamp;
    public override DateTimeOffset DefaultValue => DateTimeOffset.UnixEpoch;

    public override void WriteData(ref WriteContext context, in DateTimeOffset value, bool hasGenerics)
    {
        _ = hasGenerics;
        ForyTimestamp ts = ForyTimestamp.FromDateTimeOffset(value);
        context.Writer.WriteInt64(ts.Seconds);
        context.Writer.WriteUInt32(ts.Nanos);
    }

    public override DateTimeOffset ReadData(ref ReadContext context)
    {
        long seconds = context.Reader.ReadInt64();
        uint nanos = context.Reader.ReadUInt32();
        return new ForyTimestamp(seconds, nanos).ToDateTimeOffset();
    }
}

public sealed class DateTimeSerializer : Serializer<DateTime>
{
    public static DateTimeSerializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.Timestamp;
    public override DateTime DefaultValue => DateTime.UnixEpoch;

    public override void WriteData(ref WriteContext context, in DateTime value, bool hasGenerics)
    {
        _ = hasGenerics;
        DateTimeOffset dto = value.Kind switch
        {
            DateTimeKind.Utc => new DateTimeOffset(value, TimeSpan.Zero),
            DateTimeKind.Local => value,
            _ => new DateTimeOffset(DateTime.SpecifyKind(value, DateTimeKind.Utc)),
        };
        ForyTimestamp ts = ForyTimestamp.FromDateTimeOffset(dto);
        context.Writer.WriteInt64(ts.Seconds);
        context.Writer.WriteUInt32(ts.Nanos);
    }

    public override DateTime ReadData(ref ReadContext context)
    {
        long seconds = context.Reader.ReadInt64();
        uint nanos = context.Reader.ReadUInt32();
        return new ForyTimestamp(seconds, nanos).ToDateTimeOffset().UtcDateTime;
    }
}

public sealed class TimeSpanSerializer : Serializer<TimeSpan>
{
    public static TimeSpanSerializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.Duration;
    public override TimeSpan DefaultValue => TimeSpan.Zero;

    public override void WriteData(ref WriteContext context, in TimeSpan value, bool hasGenerics)
    {
        _ = hasGenerics;
        long seconds = value.Ticks / TimeSpan.TicksPerSecond;
        int nanos = checked((int)((value.Ticks % TimeSpan.TicksPerSecond) * 100));
        context.Writer.WriteInt64(seconds);
        context.Writer.WriteInt32(nanos);
    }

    public override TimeSpan ReadData(ref ReadContext context)
    {
        long seconds = context.Reader.ReadInt64();
        int nanos = context.Reader.ReadInt32();
        return TimeSpan.FromSeconds(seconds) + TimeSpan.FromTicks(nanos / 100);
    }
}
