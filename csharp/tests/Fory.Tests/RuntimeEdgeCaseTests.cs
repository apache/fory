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

using Apache.Fory;
using ForyRuntime = Apache.Fory.Fory;

namespace Apache.Fory.Tests;

[ForyObject]
public sealed class TimeEnvelope
{
    public DateOnly Date { get; set; }
    public DateTime Timestamp { get; set; }
    public DateTimeOffset OffsetTimestamp { get; set; }
    public TimeSpan Duration { get; set; }
    public List<DateOnly> Dates { get; set; } = [];
    public List<DateTime> Timestamps { get; set; } = [];
    public List<DateTimeOffset> OffsetTimestamps { get; set; } = [];
    public List<TimeSpan> Durations { get; set; } = [];
}

[ForyObject]
public sealed class NullableEnvelope
{
    public int? Int32Value { get; set; }
    public ulong? UInt64Value { get; set; }
    public DateTimeOffset? Timestamp { get; set; }
    public TestColor? Color { get; set; }
}

[ForyObject]
public sealed class CustomPayload
{
    public int Id { get; set; }
    public string Marker { get; set; } = string.Empty;
}

public sealed class CustomPayloadSerializer : Serializer<CustomPayload>
{
    public override CustomPayload DefaultValue => null!;

    public override void WriteData(WriteContext context, in CustomPayload value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteVarInt32((value ?? new CustomPayload()).Id + 7);
    }

    public override CustomPayload ReadData(ReadContext context)
    {
        return new CustomPayload
        {
            Id = context.Reader.ReadVarInt32() - 7,
            Marker = "custom",
        };
    }
}

public sealed class RuntimeEdgeCaseTests
{
    [Fact]
    public void TimeRoundTripEdgeCases()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();

        DateOnly date = new(1960, 2, 29);
        Assert.Equal(date, fory.Deserialize<DateOnly>(fory.Serialize(date)));

        DateTimeOffset offset = DateTimeOffset.FromUnixTimeMilliseconds(-1).AddTicks(45);
        Assert.Equal(offset, fory.Deserialize<DateTimeOffset>(fory.Serialize(offset)));

        TimeSpan duration = TimeSpan.FromDays(-3) - TimeSpan.FromMilliseconds(45) - TimeSpan.FromTicks(67);
        Assert.Equal(duration, fory.Deserialize<TimeSpan>(fory.Serialize(duration)));

        DateTime utc = new DateTime(2024, 1, 2, 3, 4, 5, 678, DateTimeKind.Utc).AddTicks(9);
        AssertDateTimeEqual(utc, fory.Deserialize<DateTime>(fory.Serialize(utc)));

        DateTime local = new DateTime(2024, 1, 2, 3, 4, 5, 678, DateTimeKind.Local).AddTicks(9);
        AssertDateTimeEqual(local.ToUniversalTime(), fory.Deserialize<DateTime>(fory.Serialize(local)));

        DateTime unspecified = DateTime.SpecifyKind(new DateTime(2024, 1, 2, 3, 4, 5, 678).AddTicks(9), DateTimeKind.Unspecified);
        AssertDateTimeEqual(
            DateTime.SpecifyKind(unspecified, DateTimeKind.Utc),
            fory.Deserialize<DateTime>(fory.Serialize(unspecified)));
    }

    [Fact]
    public void TimeFieldsAndTypedListsRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<TimeEnvelope>(700);

        TimeEnvelope source = new()
        {
            Date = new DateOnly(1969, 12, 31),
            Timestamp = new DateTime(2024, 1, 2, 3, 4, 5, 678, DateTimeKind.Local).AddTicks(9),
            OffsetTimestamp = new DateTimeOffset(2024, 1, 2, 3, 4, 5, 678, TimeSpan.FromHours(5)).AddTicks(9),
            Duration = TimeSpan.FromTicks(-12_345_678_901),
            Dates = [new DateOnly(1969, 12, 31), new DateOnly(1970, 1, 1), new DateOnly(2024, 4, 21)],
            Timestamps =
            [
                new DateTime(2024, 1, 2, 3, 4, 5, 678, DateTimeKind.Utc).AddTicks(9),
                new DateTime(2024, 1, 2, 3, 4, 5, 678, DateTimeKind.Local).AddTicks(10),
                DateTime.SpecifyKind(new DateTime(2024, 1, 2, 3, 4, 5, 678).AddTicks(11), DateTimeKind.Unspecified),
            ],
            OffsetTimestamps =
            [
                DateTimeOffset.FromUnixTimeMilliseconds(-1),
                new DateTimeOffset(2024, 1, 2, 3, 4, 5, 678, TimeSpan.FromHours(-7)).AddTicks(12),
            ],
            Durations =
            [
                TimeSpan.Zero,
                TimeSpan.FromTicks(123_456_789),
                TimeSpan.FromTicks(-123_456_789),
            ],
        };

        TimeEnvelope decoded = fory.Deserialize<TimeEnvelope>(fory.Serialize(source));
        Assert.Equal(source.Date, decoded.Date);
        AssertDateTimeEqual(source.Timestamp.ToUniversalTime(), decoded.Timestamp);
        Assert.Equal(source.OffsetTimestamp, decoded.OffsetTimestamp);
        Assert.Equal(source.Duration, decoded.Duration);
        Assert.Equal(source.Dates, decoded.Dates);
        Assert.Equal(source.OffsetTimestamps, decoded.OffsetTimestamps);
        Assert.Equal(source.Durations, decoded.Durations);

        Assert.Equal(source.Timestamps.Count, decoded.Timestamps.Count);
        for (int i = 0; i < source.Timestamps.Count; i++)
        {
            AssertDateTimeEqual(NormalizeDateTime(source.Timestamps[i]), decoded.Timestamps[i]);
        }
    }

    [Fact]
    public void TimeSpanUsesVarIntSeconds()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        byte[] payload = fory.Serialize(TimeSpan.FromSeconds(1) + TimeSpan.FromTicks(3));

        ByteReader reader = new(payload);
        Assert.False(fory.ReadHead(reader));
        Assert.Equal((sbyte)RefFlag.NotNullValue, reader.ReadInt8());
        Assert.Equal((uint)TypeId.Duration, reader.ReadUInt8());
        Assert.Equal(1L, reader.ReadVarInt64());
        Assert.Equal(300, reader.ReadInt32());
        Assert.Equal(0, reader.Remaining);
    }

    [Fact]
    public void TimestampNormalizesNegativeFractionalSecond()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        byte[] payload = fory.Serialize(DateTimeOffset.FromUnixTimeMilliseconds(-1));

        ByteReader reader = new(payload);
        Assert.False(fory.ReadHead(reader));
        Assert.Equal((sbyte)RefFlag.NotNullValue, reader.ReadInt8());
        Assert.Equal((uint)TypeId.Timestamp, reader.ReadUInt8());
        Assert.Equal(-1L, reader.ReadInt64());
        Assert.Equal(999_000_000u, reader.ReadUInt32());
        Assert.Equal(0, reader.Remaining);
    }

    [Fact]
    public void NullableValuesRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<TestColor>(704);

        Assert.Null(fory.Deserialize<int?>(fory.Serialize<int?>(null)));
        Assert.Equal(123, fory.Deserialize<int?>(fory.Serialize<int?>(123)));
        Assert.Equal(ulong.MaxValue, fory.Deserialize<ulong?>(fory.Serialize<ulong?>(ulong.MaxValue)));

        DateTimeOffset timestamp = DateTimeOffset.FromUnixTimeMilliseconds(-1).AddTicks(23);
        Assert.Equal(timestamp, fory.Deserialize<DateTimeOffset?>(fory.Serialize<DateTimeOffset?>(timestamp)));

        Assert.Null(fory.Deserialize<TestColor?>(fory.Serialize<TestColor?>(null)));
        Assert.Equal(TestColor.Red, fory.Deserialize<TestColor?>(fory.Serialize<TestColor?>(TestColor.Red)));

        List<int?> list = [null, 0, int.MaxValue];
        Assert.Equal(list, fory.Deserialize<List<int?>>(fory.Serialize(list)));
    }

    [Fact]
    public void NullableFieldsRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<TestColor>(705);
        fory.Register<NullableEnvelope>(701);

        NullableEnvelope populated = new()
        {
            Int32Value = int.MinValue,
            UInt64Value = ulong.MaxValue,
            Timestamp = DateTimeOffset.FromUnixTimeMilliseconds(-1).AddTicks(23),
            Color = (TestColor)12345,
        };
        NullableEnvelope decodedPopulated = fory.Deserialize<NullableEnvelope>(fory.Serialize(populated));
        Assert.Equal(populated.Int32Value, decodedPopulated.Int32Value);
        Assert.Equal(populated.UInt64Value, decodedPopulated.UInt64Value);
        Assert.Equal(populated.Timestamp, decodedPopulated.Timestamp);
        Assert.Equal(populated.Color, decodedPopulated.Color);

        NullableEnvelope missing = new();
        NullableEnvelope decodedMissing = fory.Deserialize<NullableEnvelope>(fory.Serialize(missing));
        Assert.Null(decodedMissing.Int32Value);
        Assert.Null(decodedMissing.UInt64Value);
        Assert.Null(decodedMissing.Timestamp);
        Assert.Null(decodedMissing.Color);
    }

    [Fact]
    public void CustomSerializerRegistrationByIdRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<CustomPayload, CustomPayloadSerializer>(702);

        CustomPayload decoded = fory.Deserialize<CustomPayload>(
            fory.Serialize(new CustomPayload { Id = 42, Marker = "ignored" }));

        Assert.Equal(42, decoded.Id);
        Assert.Equal("custom", decoded.Marker);
    }

    [Fact]
    public void ThreadSafeCustomSerializerNamedRegistrationAppliesToInitializedLocal()
    {
        using ThreadSafeFory fory = ForyRuntime.Builder().BuildThreadSafe();
        _ = fory.Serialize(1);
        fory.Register<CustomPayload, CustomPayloadSerializer>(string.Empty, "custom_payload");

        CustomPayload decoded = fory.Deserialize<CustomPayload>(
            fory.Serialize(new CustomPayload { Id = 7, Marker = "ignored" }));

        Assert.Equal(7, decoded.Id);
        Assert.Equal("custom", decoded.Marker);
    }

    [Fact]
    public void DeserializeRejectsTrailingBytes()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        byte[] payload = fory.Serialize(123);
        byte[] invalidPayload = [.. payload, 0x7F];

        InvalidDataException exception = Assert.Throws<InvalidDataException>(() => fory.Deserialize<int>(invalidPayload));
        Assert.Contains("unexpected trailing bytes", exception.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void DeserializeRejectsNonXlangBitmap()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        byte[] payload = fory.Serialize(123);
        payload[0] = 0;

        InvalidDataException exception = Assert.Throws<InvalidDataException>(() => fory.Deserialize<int>(payload));
        Assert.Contains("xlang bitmap mismatch", exception.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void DynamicAnyRejectsUnknownUserTypeId()
    {
        ForyRuntime writer = ForyRuntime.Builder().Build();
        writer.Register<CustomPayload, CustomPayloadSerializer>(703);
        byte[] payload = writer.Serialize<object?>(new CustomPayload { Id = 9, Marker = "ignored" });
        byte[] invalidPayload = RewriteRootUserTypeId(payload, TypeId.Ext, 704);

        ForyRuntime reader = ForyRuntime.Builder().Build();
        TypeNotRegisteredException exception =
            Assert.Throws<TypeNotRegisteredException>(() => reader.Deserialize<object?>(invalidPayload));
        Assert.Contains("user_type_id=704", exception.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void ThreadSafeForyThrowsAfterDispose()
    {
        ThreadSafeFory fory = ForyRuntime.Builder().BuildThreadSafe();
        byte[] payload = fory.Serialize(123);
        fory.Dispose();

        Assert.Throws<ObjectDisposedException>(() => fory.Serialize(1));
        Assert.Throws<ObjectDisposedException>(() => fory.Deserialize<int>(payload));
        Assert.Throws<ObjectDisposedException>(() => fory.Register<Node>(999));
    }

    private static DateTime NormalizeDateTime(DateTime value)
    {
        return value.Kind switch
        {
            DateTimeKind.Utc => value,
            DateTimeKind.Local => value.ToUniversalTime(),
            _ => DateTime.SpecifyKind(value, DateTimeKind.Utc),
        };
    }

    private static void AssertDateTimeEqual(DateTime expected, DateTime actual)
    {
        Assert.Equal(expected, actual);
        Assert.Equal(DateTimeKind.Utc, actual.Kind);
    }

    private static byte[] RewriteRootUserTypeId(byte[] payload, TypeId expectedWireTypeId, uint replacementUserTypeId)
    {
        ByteReader reader = new(payload);
        _ = reader.ReadUInt8(); // frame header bitmap
        _ = reader.ReadInt8(); // root ref flag
        uint wireTypeId = reader.ReadUInt8();
        Assert.Equal((uint)expectedWireTypeId, wireTypeId);

        int userTypeIdStart = reader.Cursor;
        _ = reader.ReadVarUInt32();
        int userTypeIdEnd = reader.Cursor;

        ByteWriter writer = new(payload.Length + 5);
        writer.WriteBytes(payload.AsSpan(0, userTypeIdStart));
        writer.WriteVarUInt32(replacementUserTypeId);
        writer.WriteBytes(payload.AsSpan(userTypeIdEnd));
        return writer.ToArray();
    }
}
