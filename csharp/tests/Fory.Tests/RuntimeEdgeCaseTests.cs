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

using System.Numerics;
using Apache.Fory;
using ForyRuntime = Apache.Fory.Fory;

namespace Apache.Fory.Tests;

[ForyStruct]
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

[ForyStruct]
public sealed class NullableEnvelope
{
    public int? Int32Value { get; set; }
    public ulong? UInt64Value { get; set; }
    public DateTimeOffset? Timestamp { get; set; }
    public TestColor? Color { get; set; }
}

[ForyStruct]
public sealed class CustomPayload
{
    public int Id { get; set; }
    public string Marker { get; set; } = string.Empty;
}

[ForyStruct]
public sealed class DecimalEnvelope
{
    public ForyDecimal Exact { get; set; }
    public List<ForyDecimal> History { get; set; } = [];
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
        fory.ReadHead(reader);
        Assert.Equal((sbyte)RefFlag.NotNullValue, reader.ReadInt8());
        Assert.Equal((uint)TypeId.Duration, reader.ReadUInt8());
        Assert.Equal(1L, reader.ReadVarInt64());
        Assert.Equal(300, reader.ReadInt32());
        Assert.Equal(0, reader.Remaining);
    }

    [Fact]
    public void DateOnlyUsesVarInt64Days()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        byte[] payload = fory.Serialize(new DateOnly(2021, 11, 23));

        ByteReader reader = new(payload);
        fory.ReadHead(reader);
        Assert.Equal((sbyte)RefFlag.NotNullValue, reader.ReadInt8());
        Assert.Equal((uint)TypeId.Date, reader.ReadUInt8());
        Assert.Equal(18_954L, reader.ReadVarInt64());
        Assert.Equal(0, reader.Remaining);
    }

    [Theory]
    [InlineData(TypeId.Date)]
    [InlineData(TypeId.Timestamp)]
    [InlineData(TypeId.Duration)]
    public void FieldSkipperSkipsTimePayloads(TypeId typeId)
    {
        ByteWriter writer = new();
        switch (typeId)
        {
            case TypeId.Date:
                writer.WriteVarInt64(18_954);
                break;
            case TypeId.Timestamp:
                writer.WriteInt64(1_704_164_645);
                writer.WriteUInt32(123_456_700);
                break;
            case TypeId.Duration:
                writer.WriteVarInt64(42);
                writer.WriteInt32(700);
                break;
        }

        writer.WriteUInt8(0xA5);
        ByteReader reader = new(writer.ToArray());
        Config config = ForyRuntime.Builder().Compatible(false).Build().Config;
        ReadContext context = new(reader, new TypeResolver(), config);

        FieldSkipper.SkipFieldValue(context, new TypeMetaFieldType((uint)typeId, nullable: false));

        Assert.Equal(0xA5, reader.ReadUInt8());
        Assert.Equal(0, reader.Remaining);
    }

    [Fact]
    public void DecimalRoundTripEdgeCases()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        ForyDecimal[] values =
        [
            new(BigInteger.Zero, 0),
            new(BigInteger.Zero, 3),
            new(BigInteger.One, 0),
            new(BigInteger.MinusOne, 0),
            new(new BigInteger(12_345), 2),
            new(new BigInteger(long.MaxValue), 0),
            new(new BigInteger(long.MinValue), 0),
            new(new BigInteger(long.MaxValue) + BigInteger.One, 0),
            new(new BigInteger(long.MinValue) - BigInteger.One, 0),
            new(BigInteger.Parse("123456789012345678901234567890123456789"), 37),
            new(BigInteger.Parse("-123456789012345678901234567890123456789"), -17),
        ];

        foreach (ForyDecimal value in values)
        {
            Assert.Equal(value, fory.Deserialize<ForyDecimal>(fory.Serialize(value)));
        }
    }

    [Fact]
    public void DecimalFieldsAndDynamicAnyRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<DecimalEnvelope>(706);
        fory.Register<DynamicAnyHolder>(707);

        DecimalEnvelope envelope = new()
        {
            Exact = new(BigInteger.Parse("987654321098765432109876543210"), 9),
            History =
            [
                new ForyDecimal(BigInteger.Zero, 2),
                new ForyDecimal(BigInteger.Parse("-12345678901234567890"), 4),
                new ForyDecimal(BigInteger.Parse("9223372036854775808"), 0),
            ],
        };
        DecimalEnvelope decodedEnvelope = fory.Deserialize<DecimalEnvelope>(fory.Serialize(envelope));
        Assert.Equal(envelope.Exact, decodedEnvelope.Exact);
        Assert.Equal(envelope.History, decodedEnvelope.History);

        DynamicAnyHolder anyHolder = new()
        {
            AnyValue = envelope.Exact,
            AnySet = [envelope.History[1], "marker"],
            AnyMap = new Dictionary<object, object?>
            {
                ["decimal"] = envelope.History[2],
                [envelope.History[0]] = "scaled-zero",
            },
        };
        DynamicAnyHolder decodedAny = fory.Deserialize<DynamicAnyHolder>(fory.Serialize(anyHolder));
        Assert.Equal(anyHolder.AnyValue, decodedAny.AnyValue);
        Assert.Contains(envelope.History[1], decodedAny.AnySet);
        Assert.Equal(envelope.History[2], decodedAny.AnyMap["decimal"]);
        Assert.Equal("scaled-zero", decodedAny.AnyMap[envelope.History[0]]);
    }

    [Fact]
    public void DecimalUsesCanonicalWireFormat()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        byte[] payload = fory.Serialize(new ForyDecimal(BigInteger.Zero, 2));

        ByteReader reader = new(payload);
        fory.ReadHead(reader);
        Assert.Equal((sbyte)RefFlag.NotNullValue, reader.ReadInt8());
        Assert.Equal((uint)TypeId.Decimal, reader.ReadUInt8());
        Assert.Equal(2, reader.ReadVarInt32());
        Assert.Equal(0UL, reader.ReadVarUInt64());
        Assert.Equal(0, reader.Remaining);

        payload = fory.Serialize(new ForyDecimal(BigInteger.Parse("9223372036854775808"), 0));
        reader.Reset(payload);
        fory.ReadHead(reader);
        Assert.Equal((sbyte)RefFlag.NotNullValue, reader.ReadInt8());
        Assert.Equal((uint)TypeId.Decimal, reader.ReadUInt8());
        Assert.Equal(0, reader.ReadVarInt32());
        ulong header = reader.ReadVarUInt64();
        Assert.Equal(1UL, header & 1UL);
        Assert.True(reader.Remaining > 0);
    }

    [Fact]
    public void DecimalRejectsNonCanonicalBigPayload()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        ByteWriter writer = new();
        fory.WriteHead(writer);
        writer.WriteInt8((sbyte)RefFlag.NotNullValue);
        writer.WriteUInt8((byte)TypeId.Decimal);
        writer.WriteVarInt32(0);
        writer.WriteVarUInt64(1UL);
        _ = Assert.Throws<InvalidDataException>(() => fory.Deserialize<ForyDecimal>(writer.ToArray()));

        writer.Reset();
        fory.WriteHead(writer);
        writer.WriteInt8((sbyte)RefFlag.NotNullValue);
        writer.WriteUInt8((byte)TypeId.Decimal);
        writer.WriteVarInt32(0);
        writer.WriteVarUInt64(((((ulong)2 << 1) | 0UL) << 1) | 1UL);
        writer.WriteBytes([0x01, 0x00]);

        InvalidDataException trailingZeroException =
            Assert.Throws<InvalidDataException>(() => fory.Deserialize<ForyDecimal>(writer.ToArray()));
        Assert.Contains("trailing zero byte", trailingZeroException.Message);
    }

    [Fact]
    public void TimestampNormalizesNegativeFractionalSecond()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        byte[] payload = fory.Serialize(DateTimeOffset.FromUnixTimeMilliseconds(-1));

        ByteReader reader = new(payload);
        fory.ReadHead(reader);
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
    public void DottedNameRegistrationRoundTrip()
    {
        ForyRuntime writer = ForyRuntime.Builder()
            .Compatible(false)
            .Build();
        writer.Register<TimeEnvelope>("test.time_envelope");

        ForyRuntime reader = ForyRuntime.Builder()
            .Compatible(false)
            .Build();
        reader.Register<TimeEnvelope>("test", "time_envelope");

        TimeEnvelope value = new() { Date = new DateOnly(2024, 6, 4) };
        TimeEnvelope decoded = reader.Deserialize<TimeEnvelope>(writer.Serialize(value));

        Assert.Equal(value.Date, decoded.Date);
    }

    [Fact]
    public void DottedSerializerNameRoundTrip()
    {
        ForyRuntime writer = ForyRuntime.Builder()
            .Compatible(false)
            .Build();
        writer.Register<CustomPayload, CustomPayloadSerializer>("test.custom_payload");

        ForyRuntime reader = ForyRuntime.Builder()
            .Compatible(false)
            .Build();
        reader.Register<CustomPayload, CustomPayloadSerializer>("test", "custom_payload");

        CustomPayload decoded = reader.Deserialize<CustomPayload>(
            writer.Serialize(new CustomPayload { Id = 7, Marker = "ignored" }));

        Assert.Equal(7, decoded.Id);
        Assert.Equal("custom", decoded.Marker);
    }

    [Fact]
    public void SplitTypeNameRejectsDots()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();

        Assert.Throws<ArgumentException>(() => fory.Register<TimeEnvelope>("test", string.Empty));
        Assert.Throws<ArgumentException>(() => fory.Register<TimeEnvelope>("test", "bad.name"));
        Assert.Throws<ArgumentException>(() => fory.Register<TimeEnvelope>(string.Empty));
        Assert.Throws<ArgumentException>(() => fory.Register<TimeEnvelope>("test."));
        Assert.Throws<ArgumentException>(
            () => fory.Register<CustomPayload, CustomPayloadSerializer>("test", "bad.name"));

        using ThreadSafeFory threadSafeFory = ForyRuntime.Builder().BuildThreadSafe();
        Assert.Throws<ArgumentException>(() => threadSafeFory.Register<TimeEnvelope>("test", "bad.name"));
        Assert.Throws<ArgumentException>(
            () => threadSafeFory.Register<CustomPayload, CustomPayloadSerializer>("test", "bad.name"));
    }

    [Fact]
    public void ThreadSafeDottedSerializerNameRoundTrip()
    {
        using ThreadSafeFory fory = ForyRuntime.Builder().BuildThreadSafe();
        _ = fory.Serialize(1);
        fory.Register<CustomPayload, CustomPayloadSerializer>("test.custom_payload");

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
    public void SerializeNullRootUsesRefMeta()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        byte[] payload = fory.Serialize<string?>(null);

        Assert.Equal(ForyHeaderFlag.IsXlang, payload[0]);
        Assert.Equal(unchecked((byte)(sbyte)RefFlag.Null), payload[1]);
        Assert.Null(fory.Deserialize<string?>(payload));
    }

    [Fact]
    public void DeserializeRejectsUnsupportedRootHeaderBits()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        byte[] payload = fory.Serialize(123);

        foreach (byte bitmap in new[] { (byte)0x03, (byte)0x05, (byte)0x81 })
        {
            byte[] invalidPayload = [.. payload];
            invalidPayload[0] = bitmap;

            InvalidDataException exception =
                Assert.Throws<InvalidDataException>(() => fory.Deserialize<int>(invalidPayload));
            Assert.Contains("unsupported root header bitmap", exception.Message, StringComparison.Ordinal);
        }
    }

    [Fact]
    public void TypeMetaSchemaLimitRejectsExtraVersions()
    {
        Config config = ForyRuntime.Builder()
            .Compatible(false)
            .MaxSchemaVersionsPerType(1)
            .Build()
            .Config;
        ReadContext context = new(new ByteReader(Array.Empty<byte>()), new TypeResolver(), config);
        TypeMeta first = RemoteStructTypeMeta(901, "first");
        TypeMeta second = RemoteStructTypeMeta(901, "second");

        ReadAndStoreTypeMeta(context, first);

        Assert.Throws<InvalidDataException>(() => ReadAndStoreTypeMeta(context, second));
    }

    [Fact]
    public void NonStructTypeMetaUsesSchemaLimit()
    {
        Config config = ForyRuntime.Builder()
            .Compatible(false)
            .MaxSchemaVersionsPerType(1)
            .Build()
            .Config;
        ReadContext context = new(new ByteReader(Array.Empty<byte>()), new TypeResolver(), config);
        TypeMeta first = RemoteNamedNonStructTypeMeta(TypeId.NamedEnum, "SharedEnum");
        TypeMeta second = RemoteNamedNonStructTypeMeta(TypeId.NamedExt, "SharedEnum");

        ReadAndStoreTypeMeta(context, first);

        InvalidDataException exception =
            Assert.Throws<InvalidDataException>(() => ReadAndStoreTypeMeta(context, second));
        Assert.Contains("MaxSchemaVersionsPerType", exception.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void IdEnumDoesNotUseTypeMetaLimits()
    {
        ForyRuntime fory = ForyRuntime.Builder()
            .Compatible(true)
            .MaxTypeMetaBytes(1)
            .MaxSchemaVersionsPerType(1)
            .Build();
        fory.Register<TestColor>(901);

        Assert.Equal(TestColor.Red, fory.Deserialize<TestColor>(fory.Serialize(TestColor.Red)));
    }

    [Fact]
    public void IdExtDoesNotUseTypeMetaLimits()
    {
        ForyRuntime fory = ForyRuntime.Builder()
            .Compatible(true)
            .MaxTypeMetaBytes(1)
            .MaxSchemaVersionsPerType(1)
            .Build();
        fory.Register<CustomPayload, CustomPayloadSerializer>(902);

        byte[] payload = fory.Serialize<object?>(new CustomPayload { Id = 9 });
        object? decoded = fory.Deserialize<object?>(payload);

        CustomPayload result = Assert.IsType<CustomPayload>(decoded);
        Assert.Equal(9, result.Id);
        Assert.Equal("custom", result.Marker);
    }

    [Fact]
    public void TypeMetaFieldLimitRejectsLargeStruct()
    {
        Config config = ForyRuntime.Builder()
            .Compatible(false)
            .MaxTypeFields(1)
            .Build()
            .Config;
        ReadContext context = new(new ByteReader(Array.Empty<byte>()), new TypeResolver(), config);
        TypeMeta typeMeta = RemoteStructTypeMeta(901, "first", "second");

        InvalidDataException exception =
            Assert.Throws<InvalidDataException>(() => ReadAndStoreTypeMeta(context, typeMeta));
        Assert.Contains("MaxTypeFields", exception.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void TypeMetaBodyLimitRejectsLargeMetadata()
    {
        Config config = ForyRuntime.Builder()
            .Compatible(false)
            .MaxTypeMetaBytes(1)
            .Build()
            .Config;
        ReadContext context = new(new ByteReader(Array.Empty<byte>()), new TypeResolver(), config);

        InvalidDataException exception =
            Assert.Throws<InvalidDataException>(() => ReadAndStoreTypeMeta(context, RemoteStructTypeMeta(901, "value")));
        Assert.Contains("MaxTypeMetaBytes", exception.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void TypeMetaSchemaLimitKeepsUnknownTypesSeparate()
    {
        Config config = ForyRuntime.Builder()
            .Compatible(false)
            .MaxSchemaVersionsPerType(1)
            .Build()
            .Config;
        ReadContext context = new(new ByteReader(Array.Empty<byte>()), new TypeResolver(), config);
        TypeMeta first = RemoteStructTypeMeta(901, "value");
        TypeMeta second = RemoteStructTypeMeta(902, "value");

        TypeMeta firstRead = ReadAndStoreTypeMeta(context, first);
        TypeMeta secondRead = ReadAndStoreTypeMeta(context, second);

        Assert.True(context.TryGetTypeMetaByHeader(EncodedTypeMetaHeader(firstRead), out _));
        Assert.True(context.TryGetTypeMetaByHeader(EncodedTypeMetaHeader(secondRead), out _));
    }

    [Fact]
    public void FailedAnyTypeMetaDoesNotConsumeLimit()
    {
        TypeResolver resolver = new();
        resolver.Register(typeof(CustomPayload), 901);
        Config config = ForyRuntime.Builder()
            .Compatible(true)
            .MaxSchemaVersionsPerType(1)
            .Build()
            .Config;
        ReadContext context = new(new ByteReader(Array.Empty<byte>()), resolver, config);

        Assert.Throws<InvalidDataException>(() =>
            ReadAnyTypeInfo(context, resolver, RemoteCompatibleStructTypeMeta(901, "Id", MapType())));
        TypeMeta accepted = ReadAndStoreTypeMeta(context, RemoteStructTypeMeta(901, "second"));

        Assert.True(context.TryGetTypeMetaByHeader(EncodedTypeMetaHeader(accepted), out _));
    }

    [Fact]
    public void ExactAnyTypeMetaIsFree()
    {
        TypeResolver resolver = new();
        resolver.Register(typeof(CustomPayload), 901);
        Config config = ForyRuntime.Builder()
            .Compatible(true)
            .MaxSchemaVersionsPerType(1)
            .Build()
            .Config;
        ReadContext context = new(new ByteReader(Array.Empty<byte>()), resolver, config);
        TypeMeta remote = ReadAndStoreTypeMeta(context, RemoteStructTypeMeta(901, "remote"));
        TypeMeta exact = resolver.GetTypeInfo(typeof(CustomPayload)).GetTypeMetaCacheEntry(trackRef: false).TypeMeta;

        TypeInfo typeInfo = ReadAnyTypeInfo(context, resolver, exact);

        Assert.True(context.TryGetTypeMetaByHeader(EncodedTypeMetaHeader(remote), out _));
    }

    [Fact]
    public void ExactNonStructTypeMetaBypassesLimit()
    {
        TypeResolver resolver = new();
        resolver.Register(typeof(TestColor), "example", "SharedEnum");
        Config config = ForyRuntime.Builder()
            .Compatible(true)
            .MaxSchemaVersionsPerType(1)
            .Build()
            .Config;
        ReadContext context = new(new ByteReader(Array.Empty<byte>()), resolver, config);
        TypeInfo typeInfo = resolver.GetTypeInfo(typeof(TestColor));
        TypeMeta exact = typeInfo.GetTypeMetaCacheEntry(trackRef: false).TypeMeta;

        ReadAndStoreTypeMeta(context, exact);

        TypeMeta remote = RemoteNamedNonStructTypeMeta(TypeId.NamedExt, "SharedEnum");
        ReadAndStoreTypeMeta(context, remote);
        Assert.True(context.TryGetTypeMetaByHeader(EncodedTypeMetaHeader(remote), out _));
    }

    [Fact]
    public void TypeMetaHeaderCacheHitSkipsCurrentBodySize()
    {
        const ulong header = 0xffUL;
        TypeMeta typeMeta = new(
            (uint)TypeId.Struct,
            902,
            MetaString.Empty('.', '_'),
            MetaString.Empty('$', '_'),
            registerByName: false,
            []);

        ByteWriter writer = new();
        writer.WriteVarUInt32(0);
        writer.WriteUInt64(header);
        writer.WriteVarUInt32(0);
        writer.WriteBytes(new byte[0xff]);
        writer.WriteUInt8(0x7b);

        Config config = ForyRuntime.Builder().Compatible(false).Build().Config;
        ReadContext context = new(new ByteReader(writer.ToArray()), new TypeResolver(), config);
        context.StoreRemoteTypeMeta(header, typeMeta);

        Assert.Same(typeMeta, context.ReadTypeMeta());
        Assert.Equal(0x7b, context.Reader.ReadUInt8());
    }

    private static TypeMeta RemoteStructTypeMeta(uint userTypeId, string fieldName)
    {
        return RemoteStructTypeMeta(userTypeId, [fieldName]);
    }

    private static TypeMeta RemoteStructTypeMeta(uint userTypeId, params string[] fieldNames)
    {
        TypeMetaFieldInfo[] fields = new TypeMetaFieldInfo[fieldNames.Length];
        for (int i = 0; i < fieldNames.Length; i++)
        {
            fields[i] = new TypeMetaFieldInfo(null, fieldNames[i], new TypeMetaFieldType((uint)TypeId.Int32, nullable: false));
        }

        return new TypeMeta(
            (uint)TypeId.Struct,
            userTypeId,
            MetaString.Empty('.', '_'),
            MetaString.Empty('$', '_'),
            registerByName: false,
            fields);
    }

    private static TypeMeta RemoteNamedNonStructTypeMeta(TypeId typeId, string typeName)
    {
        return new TypeMeta(
            (uint)typeId,
            null,
            MetaStringEncoder.Namespace.Encode("example", TypeMetaEncodings.NamespaceMetaStringEncodings),
            MetaStringEncoder.TypeName.Encode(typeName, TypeMetaEncodings.TypeNameMetaStringEncodings),
            registerByName: true,
            []);
    }

    private static TypeMeta RemoteCompatibleStructTypeMeta(uint userTypeId, string fieldName)
    {
        return RemoteCompatibleStructTypeMeta(
            userTypeId,
            fieldName,
            new TypeMetaFieldType((uint)TypeId.Int32, nullable: false));
    }

    private static TypeMeta RemoteCompatibleStructTypeMeta(
        uint userTypeId,
        string fieldName,
        TypeMetaFieldType fieldType)
    {
        return new TypeMeta(
            (uint)TypeId.CompatibleStruct,
            userTypeId,
            MetaString.Empty('.', '_'),
            MetaString.Empty('$', '_'),
            registerByName: false,
            [new TypeMetaFieldInfo(null, fieldName, fieldType)]);
    }

    private static TypeMetaFieldType MapType()
    {
        return new TypeMetaFieldType(
            (uint)TypeId.Map,
            nullable: false,
            trackRef: false,
            [
                new TypeMetaFieldType((uint)TypeId.String, nullable: false),
                new TypeMetaFieldType((uint)TypeId.Int32, nullable: false),
            ]);
    }

    private static TypeMeta ReadAndStoreTypeMeta(ReadContext context, TypeMeta typeMeta)
    {
        ByteWriter writer = new();
        writer.WriteVarUInt32(0);
        writer.WriteBytes(typeMeta.Encode());
        context.ResetFor(new ByteReader(writer.ToArray()));
        return context.ReadTypeMeta();
    }

    private static TypeInfo ReadAnyTypeInfo(ReadContext context, TypeResolver resolver, TypeMeta typeMeta)
    {
        ByteWriter writer = new();
        writer.WriteUInt8((byte)TypeId.CompatibleStruct);
        writer.WriteVarUInt32(0);
        writer.WriteBytes(typeMeta.Encode());
        context.ResetFor(new ByteReader(writer.ToArray()));
        return resolver.ReadAnyTypeInfo(context);
    }

    private static ulong EncodedTypeMetaHeader(TypeMeta typeMeta)
    {
        return BitConverter.ToUInt64(typeMeta.Encode(), 0);
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
