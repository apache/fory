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
    public TypeId StaticTypeId => TypeId.String;

    public bool IsNullableType => true;

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
        int numChars = value.Length;
        int sampleNum = Math.Min(64, numChars);
        int asciiCount = 0;
        int latin1Count = 0;
        for (int i = 0; i < sampleNum; i++)
        {
            char c = value[i];
            if (c < 0x80)
            {
                asciiCount++;
                latin1Count++;
            }
            else if (c <= 0xFF)
            {
                latin1Count++;
            }
        }

        if (latin1Count == numChars || (latin1Count == sampleNum && IsLatin(value, sampleNum)))
        {
            return ForyStringEncoding.Latin1;
        }

        return asciiCount * 2 >= sampleNum ? ForyStringEncoding.Utf8 : ForyStringEncoding.Utf16;
    }

    private static bool IsLatin(string value, int start)
    {
        for (int i = start; i < value.Length; i++)
        {
            if (value[i] > 0xFF)
            {
                return false;
            }
        }

        return true;
    }

    private static void WriteLatin1(WriteContext context, string value)
    {
        int byteLength = value.Length;
        ulong header = ((ulong)byteLength << 2) | (ulong)ForyStringEncoding.Latin1;
        context.Writer.WriteVarUInt36Small(header);
        Span<byte> latin1 = context.Writer.GetSpan(byteLength);
        for (int i = 0; i < value.Length; i++)
        {
            latin1[i] = unchecked((byte)value[i]);
        }
        context.Writer.Advance(byteLength);
    }

    private static void WriteUtf8(WriteContext context, string value)
    {
        int byteLength = Encoding.UTF8.GetByteCount(value);
        ulong header = ((ulong)byteLength << 2) | (ulong)ForyStringEncoding.Utf8;
        context.Writer.WriteVarUInt36Small(header);
        Span<byte> utf8 = context.Writer.GetSpan(byteLength);
        int written = Encoding.UTF8.GetBytes(value, utf8);
        context.Writer.Advance(written);
    }

    private static void WriteUtf16(WriteContext context, string value)
    {
        int byteLength = Encoding.Unicode.GetByteCount(value);
        ulong header = ((ulong)byteLength << 2) | (ulong)ForyStringEncoding.Utf16;
        context.Writer.WriteVarUInt36Small(header);
        Span<byte> utf16 = context.Writer.GetSpan(byteLength);
        int written = Encoding.Unicode.GetBytes(value, utf16);
        context.Writer.Advance(written);
    }
}
