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

public readonly struct BoolSerializer : IStaticSerializer<BoolSerializer, bool>
{
    public static TypeId StaticTypeId => TypeId.Bool;
    public static bool DefaultValue => false;
    public static void WriteData(ref WriteContext context, in bool value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteUInt8(value ? (byte)1 : (byte)0);
    }

    public static bool ReadData(ref ReadContext context)
    {
        return context.Reader.ReadUInt8() != 0;
    }
}

public readonly struct Int8Serializer : IStaticSerializer<Int8Serializer, sbyte>
{
    public static TypeId StaticTypeId => TypeId.Int8;
    public static sbyte DefaultValue => 0;
    public static void WriteData(ref WriteContext context, in sbyte value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteInt8(value);
    }

    public static sbyte ReadData(ref ReadContext context)
    {
        return context.Reader.ReadInt8();
    }
}

public readonly struct Int16Serializer : IStaticSerializer<Int16Serializer, short>
{
    public static TypeId StaticTypeId => TypeId.Int16;
    public static short DefaultValue => 0;
    public static void WriteData(ref WriteContext context, in short value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteInt16(value);
    }

    public static short ReadData(ref ReadContext context)
    {
        return context.Reader.ReadInt16();
    }
}

public readonly struct Int32Serializer : IStaticSerializer<Int32Serializer, int>
{
    public static TypeId StaticTypeId => TypeId.VarInt32;
    public static int DefaultValue => 0;
    public static void WriteData(ref WriteContext context, in int value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteVarInt32(value);
    }

    public static int ReadData(ref ReadContext context)
    {
        return context.Reader.ReadVarInt32();
    }
}

public readonly struct Int64Serializer : IStaticSerializer<Int64Serializer, long>
{
    public static TypeId StaticTypeId => TypeId.VarInt64;
    public static long DefaultValue => 0;
    public static void WriteData(ref WriteContext context, in long value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteVarInt64(value);
    }

    public static long ReadData(ref ReadContext context)
    {
        return context.Reader.ReadVarInt64();
    }
}

public readonly struct UInt8Serializer : IStaticSerializer<UInt8Serializer, byte>
{
    public static TypeId StaticTypeId => TypeId.UInt8;
    public static byte DefaultValue => 0;
    public static void WriteData(ref WriteContext context, in byte value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteUInt8(value);
    }

    public static byte ReadData(ref ReadContext context)
    {
        return context.Reader.ReadUInt8();
    }
}

public readonly struct UInt16Serializer : IStaticSerializer<UInt16Serializer, ushort>
{
    public static TypeId StaticTypeId => TypeId.UInt16;
    public static ushort DefaultValue => 0;
    public static void WriteData(ref WriteContext context, in ushort value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteUInt16(value);
    }

    public static ushort ReadData(ref ReadContext context)
    {
        return context.Reader.ReadUInt16();
    }
}

public readonly struct UInt32Serializer : IStaticSerializer<UInt32Serializer, uint>
{
    public static TypeId StaticTypeId => TypeId.VarUInt32;
    public static uint DefaultValue => 0;
    public static void WriteData(ref WriteContext context, in uint value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteVarUInt32(value);
    }

    public static uint ReadData(ref ReadContext context)
    {
        return context.Reader.ReadVarUInt32();
    }
}

public readonly struct UInt64Serializer : IStaticSerializer<UInt64Serializer, ulong>
{
    public static TypeId StaticTypeId => TypeId.VarUInt64;
    public static ulong DefaultValue => 0;
    public static void WriteData(ref WriteContext context, in ulong value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteVarUInt64(value);
    }

    public static ulong ReadData(ref ReadContext context)
    {
        return context.Reader.ReadVarUInt64();
    }
}

public readonly struct Float32Serializer : IStaticSerializer<Float32Serializer, float>
{
    public static TypeId StaticTypeId => TypeId.Float32;
    public static float DefaultValue => 0;
    public static void WriteData(ref WriteContext context, in float value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteFloat32(value);
    }

    public static float ReadData(ref ReadContext context)
    {
        return context.Reader.ReadFloat32();
    }
}

public readonly struct Float64Serializer : IStaticSerializer<Float64Serializer, double>
{
    public static TypeId StaticTypeId => TypeId.Float64;
    public static double DefaultValue => 0;
    public static void WriteData(ref WriteContext context, in double value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteFloat64(value);
    }

    public static double ReadData(ref ReadContext context)
    {
        return context.Reader.ReadFloat64();
    }
}

public readonly struct StringSerializer : IStaticSerializer<StringSerializer, string>
{
    public static TypeId StaticTypeId => TypeId.String;
    public static bool IsNullableType => true;
    public static string DefaultValue => null!;
    public static bool IsNone(in string value) => value is null;

    public static void WriteData(ref WriteContext context, in string value, bool hasGenerics)
    {
        _ = hasGenerics;
        string safe = value ?? string.Empty;
        byte[] utf8 = Encoding.UTF8.GetBytes(safe);
        ulong header = ((ulong)utf8.Length << 2) | (ulong)ForyStringEncoding.Utf8;
        context.Writer.WriteVarUInt36Small(header);
        context.Writer.WriteBytes(utf8);
    }

    public static string ReadData(ref ReadContext context)
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
            _ => throw new EncodingException($"unsupported string encoding {encoding}"),
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
            throw new EncodingException("utf16 byte length is not even");
        }

        return Encoding.Unicode.GetString(bytes);
    }
}

public readonly struct BinarySerializer : IStaticSerializer<BinarySerializer, byte[]>
{
    public static TypeId StaticTypeId => TypeId.Binary;
    public static bool IsNullableType => true;
    public static byte[] DefaultValue => null!;
    public static bool IsNone(in byte[] value) => value is null;

    public static void WriteData(ref WriteContext context, in byte[] value, bool hasGenerics)
    {
        _ = hasGenerics;
        byte[] safe = value ?? [];
        context.Writer.WriteVarUInt32((uint)safe.Length);
        context.Writer.WriteBytes(safe);
    }

    public static byte[] ReadData(ref ReadContext context)
    {
        uint length = context.Reader.ReadVarUInt32();
        return context.Reader.ReadBytes(checked((int)length));
    }
}

public readonly struct ForyInt32FixedSerializer : IStaticSerializer<ForyInt32FixedSerializer, ForyInt32Fixed>
{
    public static TypeId StaticTypeId => TypeId.Int32;
    public static ForyInt32Fixed DefaultValue => new(0);
    public static void WriteData(ref WriteContext context, in ForyInt32Fixed value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteInt32(value.RawValue);
    }

    public static ForyInt32Fixed ReadData(ref ReadContext context)
    {
        return new ForyInt32Fixed(context.Reader.ReadInt32());
    }
}

public readonly struct ForyInt64FixedSerializer : IStaticSerializer<ForyInt64FixedSerializer, ForyInt64Fixed>
{
    public static TypeId StaticTypeId => TypeId.Int64;
    public static ForyInt64Fixed DefaultValue => new(0);
    public static void WriteData(ref WriteContext context, in ForyInt64Fixed value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteInt64(value.RawValue);
    }

    public static ForyInt64Fixed ReadData(ref ReadContext context)
    {
        return new ForyInt64Fixed(context.Reader.ReadInt64());
    }
}

public readonly struct ForyInt64TaggedSerializer : IStaticSerializer<ForyInt64TaggedSerializer, ForyInt64Tagged>
{
    public static TypeId StaticTypeId => TypeId.TaggedInt64;
    public static ForyInt64Tagged DefaultValue => new(0);
    public static void WriteData(ref WriteContext context, in ForyInt64Tagged value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteTaggedInt64(value.RawValue);
    }

    public static ForyInt64Tagged ReadData(ref ReadContext context)
    {
        return new ForyInt64Tagged(context.Reader.ReadTaggedInt64());
    }
}

public readonly struct ForyUInt32FixedSerializer : IStaticSerializer<ForyUInt32FixedSerializer, ForyUInt32Fixed>
{
    public static TypeId StaticTypeId => TypeId.UInt32;
    public static ForyUInt32Fixed DefaultValue => new(0);
    public static void WriteData(ref WriteContext context, in ForyUInt32Fixed value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteUInt32(value.RawValue);
    }

    public static ForyUInt32Fixed ReadData(ref ReadContext context)
    {
        return new ForyUInt32Fixed(context.Reader.ReadUInt32());
    }
}

public readonly struct ForyUInt64FixedSerializer : IStaticSerializer<ForyUInt64FixedSerializer, ForyUInt64Fixed>
{
    public static TypeId StaticTypeId => TypeId.UInt64;
    public static ForyUInt64Fixed DefaultValue => new(0);
    public static void WriteData(ref WriteContext context, in ForyUInt64Fixed value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteUInt64(value.RawValue);
    }

    public static ForyUInt64Fixed ReadData(ref ReadContext context)
    {
        return new ForyUInt64Fixed(context.Reader.ReadUInt64());
    }
}

public readonly struct ForyUInt64TaggedSerializer : IStaticSerializer<ForyUInt64TaggedSerializer, ForyUInt64Tagged>
{
    public static TypeId StaticTypeId => TypeId.TaggedUInt64;
    public static ForyUInt64Tagged DefaultValue => new(0);
    public static void WriteData(ref WriteContext context, in ForyUInt64Tagged value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteTaggedUInt64(value.RawValue);
    }

    public static ForyUInt64Tagged ReadData(ref ReadContext context)
    {
        return new ForyUInt64Tagged(context.Reader.ReadTaggedUInt64());
    }
}

public readonly struct DateOnlySerializer : IStaticSerializer<DateOnlySerializer, DateOnly>
{
    private static readonly DateOnly Epoch = new(1970, 1, 1);
    public static TypeId StaticTypeId => TypeId.Date;
    public static DateOnly DefaultValue => Epoch;

    public static void WriteData(ref WriteContext context, in DateOnly value, bool hasGenerics)
    {
        _ = hasGenerics;
        int days = value.DayNumber - Epoch.DayNumber;
        context.Writer.WriteInt32(days);
    }

    public static DateOnly ReadData(ref ReadContext context)
    {
        int days = context.Reader.ReadInt32();
        return DateOnly.FromDayNumber(Epoch.DayNumber + days);
    }
}

public readonly struct DateTimeOffsetSerializer : IStaticSerializer<DateTimeOffsetSerializer, DateTimeOffset>
{
    public static TypeId StaticTypeId => TypeId.Timestamp;
    public static DateTimeOffset DefaultValue => DateTimeOffset.UnixEpoch;

    public static void WriteData(ref WriteContext context, in DateTimeOffset value, bool hasGenerics)
    {
        _ = hasGenerics;
        ForyTimestamp ts = ForyTimestamp.FromDateTimeOffset(value);
        context.Writer.WriteInt64(ts.Seconds);
        context.Writer.WriteUInt32(ts.Nanos);
    }

    public static DateTimeOffset ReadData(ref ReadContext context)
    {
        long seconds = context.Reader.ReadInt64();
        uint nanos = context.Reader.ReadUInt32();
        return new ForyTimestamp(seconds, nanos).ToDateTimeOffset();
    }
}

public readonly struct DateTimeSerializer : IStaticSerializer<DateTimeSerializer, DateTime>
{
    public static TypeId StaticTypeId => TypeId.Timestamp;
    public static DateTime DefaultValue => DateTime.UnixEpoch;

    public static void WriteData(ref WriteContext context, in DateTime value, bool hasGenerics)
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

    public static DateTime ReadData(ref ReadContext context)
    {
        long seconds = context.Reader.ReadInt64();
        uint nanos = context.Reader.ReadUInt32();
        return new ForyTimestamp(seconds, nanos).ToDateTimeOffset().UtcDateTime;
    }
}

public readonly struct TimeSpanSerializer : IStaticSerializer<TimeSpanSerializer, TimeSpan>
{
    public static TypeId StaticTypeId => TypeId.Duration;
    public static TimeSpan DefaultValue => TimeSpan.Zero;

    public static void WriteData(ref WriteContext context, in TimeSpan value, bool hasGenerics)
    {
        _ = hasGenerics;
        long seconds = value.Ticks / TimeSpan.TicksPerSecond;
        int nanos = checked((int)((value.Ticks % TimeSpan.TicksPerSecond) * 100));
        context.Writer.WriteInt64(seconds);
        context.Writer.WriteInt32(nanos);
    }

    public static TimeSpan ReadData(ref ReadContext context)
    {
        long seconds = context.Reader.ReadInt64();
        int nanos = context.Reader.ReadInt32();
        return TimeSpan.FromSeconds(seconds) + TimeSpan.FromTicks(nanos / 100);
    }
}
