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
        byte[] utf8 = Encoding.UTF8.GetBytes(safe);
        ulong header = ((ulong)utf8.Length << 2) | (ulong)ForyStringEncoding.Utf8;
        context.Writer.WriteVarUInt36Small(header);
        context.Writer.WriteBytes(utf8);
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
}
