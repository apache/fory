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

namespace Apache.Fory.Tests;

public sealed class ByteBufferTests
{
    public static TheoryData<uint, int> VarUInt32Cases => new()
    {
        { 0u, 1 },
        { 0x7Fu, 1 },
        { 0x80u, 2 },
        { 0x3FFFu, 2 },
        { 0x4000u, 3 },
        { 0x1F_FFFFu, 3 },
        { 0x20_0000u, 4 },
        { 0x0FFF_FFFFu, 4 },
        { 0x1000_0000u, 5 },
        { uint.MaxValue, 5 },
    };

    public static TheoryData<ulong, int> VarUInt64Cases => new()
    {
        { 0UL, 1 },
        { 0x7FUL, 1 },
        { 0x80UL, 2 },
        { (1UL << 14) - 1, 2 },
        { 1UL << 14, 3 },
        { (1UL << 21) - 1, 3 },
        { 1UL << 21, 4 },
        { (1UL << 28) - 1, 4 },
        { 1UL << 28, 5 },
        { (1UL << 35) - 1, 5 },
        { 1UL << 35, 6 },
        { (1UL << 42) - 1, 6 },
        { 1UL << 42, 7 },
        { (1UL << 49) - 1, 7 },
        { 1UL << 49, 8 },
        { (1UL << 56) - 1, 8 },
        { 1UL << 56, 9 },
        { ulong.MaxValue, 9 },
    };

    public static TheoryData<int> VarInt32Cases => new()
    {
        0,
        -1,
        1,
        -63,
        63,
        int.MinValue,
        int.MaxValue,
    };

    public static TheoryData<long> VarInt64Cases => new()
    {
        0L,
        -1L,
        1L,
        -63L,
        63L,
        int.MinValue,
        int.MaxValue,
        long.MinValue,
        long.MaxValue,
    };

    [Fact]
    public void PrimitiveReadWriteRoundTrip()
    {
        ByteWriter writer = new(1);
        writer.WriteUInt8(0xAB);
        writer.WriteInt8(-7);
        writer.WriteUInt16(0xCAFE);
        writer.WriteInt16(-12_345);
        writer.WriteUInt32(0x89ABCDEF);
        writer.WriteInt32(-123_456_789);
        writer.WriteUInt64(0xFEDCBA9876543210UL);
        writer.WriteInt64(-1_234_567_890_123_456_789L);
        writer.WriteFloat32(123.5f);
        writer.WriteFloat64(-9876.25);
        writer.WriteBytes([0x01, 0x02, 0x03, 0xFF]);

        ByteReader reader = new(writer.ToArray());
        Assert.Equal(0xAB, reader.ReadUInt8());
        Assert.Equal(-7, reader.ReadInt8());
        Assert.Equal(0xCAFE, reader.ReadUInt16());
        Assert.Equal(-12_345, reader.ReadInt16());
        Assert.Equal(0x89ABCDEFu, reader.ReadUInt32());
        Assert.Equal(-123_456_789, reader.ReadInt32());
        Assert.Equal(0xFEDCBA9876543210UL, reader.ReadUInt64());
        Assert.Equal(-1_234_567_890_123_456_789L, reader.ReadInt64());
        Assert.Equal(
            BitConverter.SingleToInt32Bits(123.5f),
            BitConverter.SingleToInt32Bits(reader.ReadFloat32()));
        Assert.Equal(
            BitConverter.DoubleToInt64Bits(-9876.25),
            BitConverter.DoubleToInt64Bits(reader.ReadFloat64()));
        Assert.Equal(new byte[] { 0x01, 0x02, 0x03, 0xFF }, reader.ReadBytes(4));
        Assert.Equal(0, reader.Remaining);
    }

    [Theory]
    [MemberData(nameof(VarUInt32Cases))]
    public void VarUInt32RoundTripAndSize(uint value, int expectedBytes)
    {
        ByteWriter writer = new();
        writer.WriteVarUInt32(value);
        Assert.Equal(expectedBytes, writer.Count);

        ByteReader reader = new(writer.ToArray());
        Assert.Equal(value, reader.ReadVarUInt32());
        Assert.Equal(0, reader.Remaining);
    }

    [Theory]
    [MemberData(nameof(VarUInt64Cases))]
    public void VarUInt64RoundTripAndSize(ulong value, int expectedBytes)
    {
        ByteWriter writer = new();
        writer.WriteVarUInt64(value);
        Assert.Equal(expectedBytes, writer.Count);

        ByteReader reader = new(writer.ToArray());
        Assert.Equal(value, reader.ReadVarUInt64());
        Assert.Equal(0, reader.Remaining);
    }

    [Theory]
    [MemberData(nameof(VarInt32Cases))]
    public void VarInt32RoundTrip(int value)
    {
        ByteWriter writer = new();
        writer.WriteVarInt32(value);

        ByteReader reader = new(writer.ToArray());
        Assert.Equal(value, reader.ReadVarInt32());
        Assert.Equal(0, reader.Remaining);
    }

    [Theory]
    [MemberData(nameof(VarInt64Cases))]
    public void VarInt64RoundTrip(long value)
    {
        ByteWriter writer = new();
        writer.WriteVarInt64(value);

        ByteReader reader = new(writer.ToArray());
        Assert.Equal(value, reader.ReadVarInt64());
        Assert.Equal(0, reader.Remaining);
    }

    [Fact]
    public void VarUInt36SmallBoundariesAndOverflow()
    {
        ByteWriter writer = new();
        foreach (ulong value in new[] { 0UL, 31UL, 32UL, 1UL << 35, (1UL << 36) - 1 })
        {
            writer.Reset();
            writer.WriteVarUInt36Small(value);

            ByteReader reader = new(writer.ToArray());
            Assert.Equal(value, reader.ReadVarUInt36Small());
            Assert.Equal(0, reader.Remaining);
        }

        Assert.Throws<EncodingException>(() => writer.WriteVarUInt36Small(1UL << 36));

        writer.Reset();
        writer.WriteVarUInt64(1UL << 36);
        ByteReader overflowReader = new(writer.ToArray());
        Assert.Throws<EncodingException>(() => overflowReader.ReadVarUInt36Small());
    }

    [Fact]
    public void TaggedIntegersUseCompactAndWideEncodings()
    {
        AssertTaggedInt64(1_073_741_823L, expectedBytes: 4);
        AssertTaggedInt64(1_073_741_824L, expectedBytes: 9);
        AssertTaggedInt64(-1_073_741_824L, expectedBytes: 4);
        AssertTaggedInt64(-1_073_741_825L, expectedBytes: 9);

        AssertTaggedUInt64((ulong)int.MaxValue, expectedBytes: 4);
        AssertTaggedUInt64((ulong)int.MaxValue + 1UL, expectedBytes: 9);
        AssertTaggedUInt64(ulong.MaxValue, expectedBytes: 9);
    }

    [Fact]
    public void SpanAndPatchOperationsMutateWrittenBytes()
    {
        ByteWriter writer = new();
        Span<byte> span = writer.GetSpan(4);
        span[0] = 10;
        span[1] = 20;
        span[2] = 30;
        span[3] = 40;
        writer.Advance(4);

        writer.SetByte(1, 99);
        writer.SetBytes(2, [7, 8]);

        Assert.Equal(new byte[] { 10, 99, 7, 8 }, writer.ToArray());

        writer.Reset();
        Assert.Equal(0, writer.Count);
    }

    [Fact]
    public void ReaderRejectsTruncatedVarInts()
    {
        Assert.Throws<OutOfBoundsException>(() => new ByteReader([0x80]).ReadVarUInt32());
        Assert.Throws<OutOfBoundsException>(() => new ByteReader([0x80]).ReadVarUInt64());
    }

    private static void AssertTaggedInt64(long value, int expectedBytes)
    {
        ByteWriter writer = new();
        writer.WriteTaggedInt64(value);
        Assert.Equal(expectedBytes, writer.Count);

        ByteReader reader = new(writer.ToArray());
        Assert.Equal(value, reader.ReadTaggedInt64());
        Assert.Equal(0, reader.Remaining);
    }

    private static void AssertTaggedUInt64(ulong value, int expectedBytes)
    {
        ByteWriter writer = new();
        writer.WriteTaggedUInt64(value);
        Assert.Equal(expectedBytes, writer.Count);

        ByteReader reader = new(writer.ToArray());
        Assert.Equal(value, reader.ReadTaggedUInt64());
        Assert.Equal(0, reader.Remaining);
    }
}
