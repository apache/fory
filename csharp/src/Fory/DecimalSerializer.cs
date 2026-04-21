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

namespace Apache.Fory;

public readonly record struct ForyDecimal(BigInteger UnscaledValue, int Scale);

internal sealed class ForyDecimalSerializer : Serializer<ForyDecimal>
{
    public override ForyDecimal DefaultValue => default;

    public override void WriteData(WriteContext context, in ForyDecimal value, bool hasGenerics)
    {
        _ = hasGenerics;
        DecimalCodec.Write(context.Writer, value.Scale, value.UnscaledValue);
    }

    public override ForyDecimal ReadData(ReadContext context)
    {
        (int scale, BigInteger unscaled) = DecimalCodec.Read(context.Reader);
        return new ForyDecimal(unscaled, scale);
    }
}

internal static class DecimalCodec
{
    private static readonly BigInteger LongMin = long.MinValue;
    private static readonly BigInteger LongMax = long.MaxValue;

    public static void Write(ByteWriter buffer, int scale, BigInteger unscaled)
    {
        buffer.WriteVarInt32(scale);
        if (CanUseSmallEncoding(unscaled))
        {
            long smallValue = (long)unscaled;
            ulong zigzag = EncodeZigZag64(smallValue);
            buffer.WriteVarUInt64(zigzag << 1);
            return;
        }

        BigInteger magnitude = BigInteger.Abs(unscaled);
        if (magnitude.IsZero)
        {
            throw new InvalidDataException("zero must use the small decimal encoding");
        }

        byte[] payload = magnitude.ToByteArray(isUnsigned: true, isBigEndian: false);
        ulong meta = ((ulong)payload.Length << 1) | (unscaled.Sign < 0 ? 1UL : 0UL);
        ulong header = (meta << 1) | 1UL;
        buffer.WriteVarUInt64(header);
        buffer.WriteBytes(payload);
    }

    public static (int Scale, BigInteger Unscaled) Read(ByteReader buffer)
    {
        int scale = buffer.ReadVarInt32();
        ulong header = buffer.ReadVarUInt64();
        if ((header & 1UL) == 0UL)
        {
            return (scale, new BigInteger(DecodeZigZag64(header >> 1)));
        }

        ulong meta = header >> 1;
        ulong lenLong = meta >> 1;
        if (lenLong == 0 || lenLong > int.MaxValue)
        {
            throw new InvalidDataException($"invalid decimal magnitude length {lenLong}");
        }

        int length = checked((int)lenLong);
        byte[] payload = buffer.ReadBytes(length);
        if (payload[^1] == 0)
        {
            throw new InvalidDataException("non-canonical decimal payload: trailing zero byte");
        }

        BigInteger magnitude = new(payload, isUnsigned: true, isBigEndian: false);
        if (magnitude.IsZero)
        {
            throw new InvalidDataException("big decimal encoding must not represent zero");
        }

        return (scale, (meta & 1UL) == 0UL ? magnitude : BigInteger.Negate(magnitude));
    }

    private static bool CanUseSmallEncoding(BigInteger value)
    {
        if (value < LongMin || value > LongMax)
        {
            return false;
        }

        ulong zigzag = EncodeZigZag64((long)value);
        return (zigzag & (1UL << 63)) == 0;
    }

    private static ulong EncodeZigZag64(long value)
    {
        return unchecked((ulong)((value << 1) ^ (value >> 63)));
    }

    private static long DecodeZigZag64(ulong value)
    {
        return unchecked((long)((value >> 1) ^ (ulong)-(long)(value & 1UL)));
    }
}
