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

public sealed class StringSerializer : Serializer<string>
{
    private const int MaxVarUInt36SmallBytes = 6;


    public override string DefaultValue => null!;

    public override void WriteData(WriteContext context, in string value, bool hasGenerics)
    {
        _ = hasGenerics;
        WriteString(context, value ?? string.Empty);
    }

    public override string ReadData(ReadContext context)
    {
        return ReadString(context);
    }

    public static void WriteString(WriteContext context, string value)
    {
        string safe = value ?? string.Empty;
        ForyStringEncoding encoding = SelectEncoding(safe);
        switch (encoding)
        {
            case ForyStringEncoding.Latin1:
                WriteLatin1(context, safe);
                break;
            case ForyStringEncoding.Utf8:
                WriteUtf8(context, safe);
                break;
            case ForyStringEncoding.Utf16:
                WriteUtf16(context, safe);
                break;
            default:
                throw new EncodingException($"unsupported string encoding {encoding}");
        }
    }

    public static string ReadString(ReadContext context)
    {
        ulong header = context.Reader.ReadVarUInt36Small();
        ulong encoding = header & 0x03;
        int byteLength = checked((int)(header >> 2));
        ReadOnlySpan<byte> bytes = context.Reader.ReadSpan(byteLength);
        return encoding switch
        {
            (ulong)ForyStringEncoding.Utf8 => Encoding.UTF8.GetString(bytes),
            (ulong)ForyStringEncoding.Latin1 => DecodeLatin1(bytes),
            (ulong)ForyStringEncoding.Utf16 => DecodeUtf16(bytes),
            _ => throw new EncodingException($"unsupported string encoding {encoding}"),
        };
    }

    private static string DecodeLatin1(ReadOnlySpan<byte> bytes)
    {
        return Encoding.Latin1.GetString(bytes);
    }

    private static string DecodeUtf16(ReadOnlySpan<byte> bytes)
    {
        if ((bytes.Length & 1) != 0)
        {
            throw new EncodingException("utf16 byte length is not even");
        }

        return Encoding.Unicode.GetString(bytes);
    }

    private static ForyStringEncoding SelectEncoding(string value)
    {
        int asciiCount = 0;
        bool allLatin1 = true;
        for (int i = 0; i < value.Length; i++)
        {
            char c = value[i];
            if (c < 0x80)
            {
                asciiCount++;
            }
            else if (c > 0xFF)
            {
                allLatin1 = false;
            }
        }

        if (allLatin1)
        {
            return ForyStringEncoding.Latin1;
        }

        return asciiCount * 2 >= value.Length ? ForyStringEncoding.Utf8 : ForyStringEncoding.Utf16;
    }

    private static void WriteLatin1(WriteContext context, string value)
    {
        int byteLength = value.Length;
        ulong header = ((ulong)byteLength << 2) | (ulong)ForyStringEncoding.Latin1;
        Span<byte> headerBuf = stackalloc byte[MaxVarUInt36SmallBytes];
        int headerBytes = EncodeVarUInt36Small(headerBuf, header);
        Span<byte> destination = context.Writer.GetSpan(headerBytes + byteLength);
        headerBuf.Slice(0, headerBytes).CopyTo(destination);
        int written = Encoding.Latin1.GetBytes(value.AsSpan(), destination.Slice(headerBytes));
        context.Writer.Advance(headerBytes + written);
    }

    private static void WriteUtf8(WriteContext context, string value)
    {
        int maxByteLength = Encoding.UTF8.GetMaxByteCount(value.Length);
        Span<byte> destination = context.Writer.GetSpan(MaxVarUInt36SmallBytes + maxByteLength);
        Span<byte> payload = destination.Slice(MaxVarUInt36SmallBytes);
        int written = Encoding.UTF8.GetBytes(value.AsSpan(), payload);

        ulong header = ((ulong)written << 2) | (ulong)ForyStringEncoding.Utf8;
        Span<byte> headerBuf = stackalloc byte[MaxVarUInt36SmallBytes];
        int headerBytes = EncodeVarUInt36Small(headerBuf, header);
        if (headerBytes != MaxVarUInt36SmallBytes)
        {
            payload.Slice(0, written).CopyTo(destination.Slice(headerBytes));
        }

        headerBuf.Slice(0, headerBytes).CopyTo(destination);
        context.Writer.Advance(headerBytes + written);
    }

    private static void WriteUtf16(WriteContext context, string value)
    {
        int byteLength = checked(value.Length * 2);
        ulong header = ((ulong)byteLength << 2) | (ulong)ForyStringEncoding.Utf16;
        Span<byte> headerBuf = stackalloc byte[MaxVarUInt36SmallBytes];
        int headerBytes = EncodeVarUInt36Small(headerBuf, header);
        Span<byte> destination = context.Writer.GetSpan(headerBytes + byteLength);
        headerBuf.Slice(0, headerBytes).CopyTo(destination);
        int written = Encoding.Unicode.GetBytes(value.AsSpan(), destination.Slice(headerBytes));
        context.Writer.Advance(headerBytes + written);
    }

    private static int EncodeVarUInt36Small(Span<byte> destination, ulong value)
    {
        int index = 0;
        ulong remaining = value;
        while (remaining >= 0x80)
        {
            destination[index] = unchecked((byte)((remaining & 0x7FuL) | 0x80uL));
            index += 1;
            remaining >>= 7;
        }

        destination[index] = unchecked((byte)remaining);
        return index + 1;
    }
}
