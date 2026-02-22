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
    public override TypeId StaticTypeId => TypeId.String;

    public override bool IsNullableType => true;

    public override string DefaultValue => null!;

    public override bool IsNone(in string value) => value is null;

    public override void WriteData(ref WriteContext context, in string value, bool hasGenerics)
    {
        _ = hasGenerics;
        WriteString(ref context, value ?? string.Empty);
    }

    public override string ReadData(ref ReadContext context)
    {
        return ReadString(ref context);
    }

    public static void WriteString(ref WriteContext context, string value)
    {
        string safe = value ?? string.Empty;
        ForyStringEncoding encoding = SelectEncoding(safe);
        switch (encoding)
        {
            case ForyStringEncoding.Latin1:
                WriteLatin1(ref context, safe);
                break;
            case ForyStringEncoding.Utf8:
                WriteUtf8(ref context, safe);
                break;
            case ForyStringEncoding.Utf16:
                WriteUtf16(ref context, safe);
                break;
            default:
                throw new EncodingException($"unsupported string encoding {encoding}");
        }
    }

    public static string ReadString(ref ReadContext context)
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

    private static void WriteLatin1(ref WriteContext context, string value)
    {
        byte[] latin1 = new byte[value.Length];
        for (int i = 0; i < value.Length; i++)
        {
            latin1[i] = unchecked((byte)value[i]);
        }

        WriteEncodedBytes(ref context, latin1, ForyStringEncoding.Latin1);
    }

    private static void WriteUtf8(ref WriteContext context, string value)
    {
        byte[] utf8 = Encoding.UTF8.GetBytes(value);
        WriteEncodedBytes(ref context, utf8, ForyStringEncoding.Utf8);
    }

    private static void WriteUtf16(ref WriteContext context, string value)
    {
        byte[] utf16 = Encoding.Unicode.GetBytes(value);
        WriteEncodedBytes(ref context, utf16, ForyStringEncoding.Utf16);
    }

    private static void WriteEncodedBytes(ref WriteContext context, byte[] bytes, ForyStringEncoding encoding)
    {
        ulong header = ((ulong)bytes.Length << 2) | (ulong)encoding;
        context.Writer.WriteVarUInt36Small(header);
        context.Writer.WriteBytes(bytes);
    }
}
