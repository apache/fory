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

public sealed class StringSerializerTests
{
    private const ulong Latin1 = 0;
    private const ulong Utf16 = 1;
    private const ulong Utf8 = 2;

    [Fact]
    public void StringRoundTripEdgeCases()
    {
        string[] values =
        [
            string.Empty,
            "\0",
            "\n\r\t",
            "\u00FF",
            "\u0100",
            "café",
            "你好",
            "abc你好",
            "hello café 你好",
            "😀😁",
            new string('\u00E9', 64),
            new string('你', 64),
        ];

        foreach (string value in values)
        {
            (_, string decoded, _, _) = WriteAndReadString(value);
            Assert.Equal(value, decoded);
        }
    }

    [Fact]
    public void StringSerializerHandlesHeaderSizeBoundaries()
    {
        AssertHeader(new string('a', 31), Latin1, expectedHeaderBytes: 1);
        AssertHeader(new string('a', 32), Latin1, expectedHeaderBytes: 2);

        AssertHeader(new string('你', 15), Utf16, expectedHeaderBytes: 1);
        AssertHeader(new string('你', 16), Utf16, expectedHeaderBytes: 2);

        AssertHeader(new string('a', 28) + "你", Utf8, expectedHeaderBytes: 1);
        AssertHeader(new string('a', 29) + "你", Utf8, expectedHeaderBytes: 2);

        AssertHeader(new string('a', 4092) + "你", Utf8, expectedHeaderBytes: 2);
        AssertHeader(new string('a', 4093) + "你", Utf8, expectedHeaderBytes: 3);
    }

    [Fact]
    public void StringSerializerRejectsOddUtf16Payload()
    {
        ByteWriter writer = new();
        writer.WriteVarUInt36Small((3UL << 2) | Utf16);
        writer.WriteBytes([0x61, 0x00, 0x62]);

        ReadContext context = new(new ByteReader(writer.ToArray()), new TypeResolver(), trackRef: false, compatible: false);
        Assert.Throws<EncodingException>(() => StringSerializer.ReadString(context));
    }

    [Fact]
    public void StringSerializerRejectsUnknownEncoding()
    {
        ByteWriter writer = new();
        writer.WriteVarUInt36Small(3UL);

        ReadContext context = new(new ByteReader(writer.ToArray()), new TypeResolver(), trackRef: false, compatible: false);
        Assert.Throws<EncodingException>(() => StringSerializer.ReadString(context));
    }

    private static void AssertHeader(string value, ulong expectedEncoding, int expectedHeaderBytes)
    {
        (ulong encoding, string decoded, int headerBytes, int byteLength) = WriteAndReadString(value);
        Assert.Equal(expectedEncoding, encoding);
        Assert.Equal(expectedHeaderBytes, headerBytes);
        Assert.Equal(value, decoded);
        Assert.Equal(byteLength + headerBytes, GetPayloadLength(value));
    }

    private static int GetPayloadLength(string value)
    {
        ByteWriter writer = new();
        WriteContext context = new(writer, new TypeResolver(), trackRef: false, compatible: false);
        StringSerializer.WriteString(context, value);
        return writer.Count;
    }

    private static (ulong Encoding, string Decoded, int HeaderBytes, int ByteLength) WriteAndReadString(string value)
    {
        ByteWriter writer = new();
        TypeResolver resolver = new();
        WriteContext writeContext = new(writer, resolver, trackRef: false, compatible: false);
        StringSerializer.WriteString(writeContext, value);

        byte[] payload = writer.ToArray();
        ByteReader headerReader = new(payload);
        ulong header = headerReader.ReadVarUInt36Small();
        ulong encoding = header & 0x03;
        int byteLength = checked((int)(header >> 2));
        Assert.Equal(payload.Length - headerReader.Cursor, byteLength);

        ReadContext readContext = new(new ByteReader(payload), resolver, trackRef: false, compatible: false);
        string decoded = StringSerializer.ReadString(readContext);
        Assert.Equal(0, readContext.Reader.Remaining);
        return (encoding, decoded, headerReader.Cursor, byteLength);
    }
}
