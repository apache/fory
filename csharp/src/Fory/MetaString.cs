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

public enum MetaStringEncoding : byte
{
    Utf8 = 0,
    LowerSpecial = 1,
    LowerUpperDigitSpecial = 2,
    FirstToLowerSpecial = 3,
    AllToLowerSpecial = 4,
}

public readonly struct MetaString : IEquatable<MetaString>
{
    private const int MaxMetaStringLength = 32_767;

    public MetaString(
        string value,
        MetaStringEncoding encoding,
        char specialChar1,
        char specialChar2,
        byte[] bytes)
    {
        if (value.Length >= MaxMetaStringLength)
        {
            throw new EncodingException("meta string too long");
        }

        if (encoding != MetaStringEncoding.Utf8 && bytes.Length == 0)
        {
            throw new EncodingException("encoded meta string cannot be empty");
        }

        Value = value;
        Encoding = encoding;
        SpecialChar1 = specialChar1;
        SpecialChar2 = specialChar2;
        Bytes = bytes;
        StripLastChar = encoding != MetaStringEncoding.Utf8 && (bytes[0] & 0x80) != 0;
    }

    public string Value { get; }

    public MetaStringEncoding Encoding { get; }

    public char SpecialChar1 { get; }

    public char SpecialChar2 { get; }

    public byte[] Bytes { get; }

    public bool StripLastChar { get; }

    public static MetaString Empty(char specialChar1, char specialChar2)
    {
        return new MetaString(string.Empty, MetaStringEncoding.Utf8, specialChar1, specialChar2, []);
    }

    public bool Equals(MetaString other)
    {
        return Value == other.Value &&
               Encoding == other.Encoding &&
               SpecialChar1 == other.SpecialChar1 &&
               SpecialChar2 == other.SpecialChar2 &&
               Bytes.AsSpan().SequenceEqual(other.Bytes);
    }

    public override bool Equals(object? obj)
    {
        return obj is MetaString other && Equals(other);
    }

    public override int GetHashCode()
    {
        HashCode hc = new();
        hc.Add(Value);
        hc.Add(Encoding);
        hc.Add(SpecialChar1);
        hc.Add(SpecialChar2);
        foreach (byte b in Bytes)
        {
            hc.Add(b);
        }

        return hc.ToHashCode();
    }
}

public sealed class MetaStringEncoder
{
    private const int MaxMetaStringLength = 32_767;

    public MetaStringEncoder(char specialChar1, char specialChar2)
    {
        SpecialChar1 = specialChar1;
        SpecialChar2 = specialChar2;
    }

    public char SpecialChar1 { get; }

    public char SpecialChar2 { get; }

    public static MetaStringEncoder Namespace { get; } = new('.', '_');

    public static MetaStringEncoder TypeName { get; } = new('$', '_');

    public static MetaStringEncoder FieldName { get; } = new('$', '_');

    public MetaString Encode(string input)
    {
        return EncodeAuto(input, null);
    }

    public MetaString Encode(string input, IReadOnlyList<MetaStringEncoding> allowedEncodings)
    {
        return EncodeAuto(input, allowedEncodings);
    }

    public MetaString Encode(string input, MetaStringEncoding encoding)
    {
        if (input.Length >= MaxMetaStringLength)
        {
            throw new EncodingException("meta string too long");
        }

        if (input.Length == 0)
        {
            return MetaString.Empty(SpecialChar1, SpecialChar2);
        }

        if (encoding != MetaStringEncoding.Utf8 && !IsLatin(input))
        {
            throw new EncodingException("non-ASCII characters are not allowed for packed meta string");
        }

        return encoding switch
        {
            MetaStringEncoding.Utf8 => new MetaString(
                input,
                MetaStringEncoding.Utf8,
                SpecialChar1,
                SpecialChar2,
                Encoding.UTF8.GetBytes(input)),
            MetaStringEncoding.LowerSpecial => new MetaString(
                input,
                MetaStringEncoding.LowerSpecial,
                SpecialChar1,
                SpecialChar2,
                EncodeGeneric(input, 5, MapLowerSpecial)),
            MetaStringEncoding.LowerUpperDigitSpecial => new MetaString(
                input,
                MetaStringEncoding.LowerUpperDigitSpecial,
                SpecialChar1,
                SpecialChar2,
                EncodeGeneric(input, 6, MapLowerUpperDigitSpecial)),
            MetaStringEncoding.FirstToLowerSpecial => new MetaString(
                input,
                MetaStringEncoding.FirstToLowerSpecial,
                SpecialChar1,
                SpecialChar2,
                EncodeGeneric(LowerFirstAscii(input), 5, MapLowerSpecial)),
            MetaStringEncoding.AllToLowerSpecial => new MetaString(
                input,
                MetaStringEncoding.AllToLowerSpecial,
                SpecialChar1,
                SpecialChar2,
                EncodeGeneric(EscapeAllUpper(input), 5, MapLowerSpecial)),
            _ => throw new EncodingException($"unsupported meta string encoding: {encoding}"),
        };
    }

    private MetaString EncodeAuto(string input, IReadOnlyList<MetaStringEncoding>? allowedEncodings)
    {
        if (input.Length >= MaxMetaStringLength)
        {
            throw new EncodingException("meta string too long");
        }

        if (input.Length == 0)
        {
            return MetaString.Empty(SpecialChar1, SpecialChar2);
        }

        if (!IsLatin(input))
        {
            return new MetaString(input, MetaStringEncoding.Utf8, SpecialChar1, SpecialChar2, Encoding.UTF8.GetBytes(input));
        }

        MetaStringEncoding encoding = ChooseEncoding(input, allowedEncodings);
        return Encode(input, encoding);
    }

    private MetaStringEncoding ChooseEncoding(string input, IReadOnlyList<MetaStringEncoding>? allowedEncodings)
    {
        bool Allow(MetaStringEncoding encoding)
        {
            return allowedEncodings is null || allowedEncodings.Contains(encoding);
        }

        int digitCount = 0;
        int upperCount = 0;
        bool canLowerSpecial = true;
        bool canLowerUpperDigitSpecial = true;

        foreach (char c in input)
        {
            if (canLowerSpecial)
            {
                bool isValid = c is >= 'a' and <= 'z' || c is '.' or '_' or '$' or '|';
                if (!isValid)
                {
                    canLowerSpecial = false;
                }
            }

            if (canLowerUpperDigitSpecial)
            {
                bool isLower = c is >= 'a' and <= 'z';
                bool isUpper = c is >= 'A' and <= 'Z';
                bool isDigit = c is >= '0' and <= '9';
                bool isSpecial = c == SpecialChar1 || c == SpecialChar2;
                if (!(isLower || isUpper || isDigit || isSpecial))
                {
                    canLowerUpperDigitSpecial = false;
                }
            }

            if (c is >= '0' and <= '9')
            {
                digitCount++;
            }

            if (c is >= 'A' and <= 'Z')
            {
                upperCount++;
            }
        }

        if (canLowerSpecial && Allow(MetaStringEncoding.LowerSpecial))
        {
            return MetaStringEncoding.LowerSpecial;
        }

        if (canLowerUpperDigitSpecial)
        {
            if (digitCount != 0 && Allow(MetaStringEncoding.LowerUpperDigitSpecial))
            {
                return MetaStringEncoding.LowerUpperDigitSpecial;
            }

            if (upperCount == 1 &&
                char.IsUpper(input[0]) &&
                Allow(MetaStringEncoding.FirstToLowerSpecial))
            {
                return MetaStringEncoding.FirstToLowerSpecial;
            }

            if ((input.Length + upperCount) * 5 < input.Length * 6 && Allow(MetaStringEncoding.AllToLowerSpecial))
            {
                return MetaStringEncoding.AllToLowerSpecial;
            }

            if (Allow(MetaStringEncoding.LowerUpperDigitSpecial))
            {
                return MetaStringEncoding.LowerUpperDigitSpecial;
            }
        }

        return MetaStringEncoding.Utf8;
    }

    private byte[] EncodeGeneric(string input, int bitsPerChar, Func<char, byte> mapper)
    {
        int totalBits = input.Length * bitsPerChar + 1;
        int byteLength = (totalBits + 7) / 8;
        byte[] bytes = new byte[byteLength];
        int currentBit = 1;

        foreach (char c in input)
        {
            byte value = mapper(c);
            for (int i = bitsPerChar - 1; i >= 0; i--)
            {
                if (((value >> i) & 0x01) != 0)
                {
                    int bytePos = currentBit / 8;
                    int bitPos = currentBit % 8;
                    bytes[bytePos] |= (byte)(1 << (7 - bitPos));
                }

                currentBit++;
            }
        }

        if (byteLength * 8 >= totalBits + bitsPerChar)
        {
            bytes[0] |= 0x80;
        }

        return bytes;
    }

    private static byte MapLowerSpecial(char c)
    {
        if (c is >= 'a' and <= 'z')
        {
            return (byte)(c - 'a');
        }

        return c switch
        {
            '.' => 26,
            '_' => 27,
            '$' => 28,
            '|' => 29,
            _ => throw new EncodingException("unsupported character in LOWER_SPECIAL"),
        };
    }

    private byte MapLowerUpperDigitSpecial(char c)
    {
        if (c is >= 'a' and <= 'z')
        {
            return (byte)(c - 'a');
        }

        if (c is >= 'A' and <= 'Z')
        {
            return (byte)(26 + c - 'A');
        }

        if (c is >= '0' and <= '9')
        {
            return (byte)(52 + c - '0');
        }

        if (c == SpecialChar1)
        {
            return 62;
        }

        if (c == SpecialChar2)
        {
            return 63;
        }

        throw new EncodingException("unsupported character in LOWER_UPPER_DIGIT_SPECIAL");
    }

    private static string LowerFirstAscii(string input)
    {
        if (input.Length == 0)
        {
            return input;
        }

        return char.ToLowerInvariant(input[0]) + input[1..];
    }

    private static string EscapeAllUpper(string input)
    {
        StringBuilder sb = new(input.Length * 2);
        foreach (char c in input)
        {
            if (char.IsUpper(c))
            {
                sb.Append('|');
                sb.Append(char.ToLowerInvariant(c));
            }
            else
            {
                sb.Append(c);
            }
        }

        return sb.ToString();
    }

    private static bool IsLatin(string input)
    {
        foreach (char c in input)
        {
            if (c > 255)
            {
                return false;
            }
        }

        return true;
    }
}

public sealed class MetaStringDecoder
{
    public MetaStringDecoder(char specialChar1, char specialChar2)
    {
        SpecialChar1 = specialChar1;
        SpecialChar2 = specialChar2;
    }

    public char SpecialChar1 { get; }

    public char SpecialChar2 { get; }

    public static MetaStringDecoder Namespace { get; } = new('.', '_');

    public static MetaStringDecoder TypeName { get; } = new('$', '_');

    public static MetaStringDecoder FieldName { get; } = new('$', '_');

    public MetaString Decode(byte[] bytes, MetaStringEncoding encoding)
    {
        string value = encoding switch
        {
            MetaStringEncoding.Utf8 => Encoding.UTF8.GetString(bytes),
            MetaStringEncoding.LowerSpecial => DecodeGeneric(bytes, 5, UnmapLowerSpecial),
            MetaStringEncoding.LowerUpperDigitSpecial => DecodeGeneric(bytes, 6, UnmapLowerUpperDigitSpecial),
            MetaStringEncoding.FirstToLowerSpecial =>
                DecodeFirstToLowerSpecial(bytes),
            MetaStringEncoding.AllToLowerSpecial =>
                UnescapeAllUpper(DecodeGeneric(bytes, 5, UnmapLowerSpecial)),
            _ => throw new EncodingException($"unsupported meta string encoding: {encoding}"),
        };

        return new MetaString(value, encoding, SpecialChar1, SpecialChar2, bytes);
    }

    private string DecodeFirstToLowerSpecial(byte[] bytes)
    {
        string decoded = DecodeGeneric(bytes, 5, UnmapLowerSpecial);
        if (decoded.Length == 0)
        {
            return decoded;
        }

        return char.ToUpperInvariant(decoded[0]) + decoded[1..];
    }

    private string DecodeGeneric(byte[] bytes, int bitsPerChar, Func<byte, char> mapper)
    {
        if (bytes.Length == 0)
        {
            return string.Empty;
        }

        bool stripLast = (bytes[0] & 0x80) != 0;
        int totalBits = bytes.Length * 8;
        int bitIndex = 1;
        StringBuilder sb = new(bytes.Length);
        while (bitIndex + bitsPerChar <= totalBits &&
               !(stripLast && (bitIndex + 2 * bitsPerChar > totalBits)))
        {
            byte value = 0;
            for (var i = 0; i < bitsPerChar; i++)
            {
                int byteIndex = bitIndex / 8;
                int intra = bitIndex % 8;
                byte bit = (byte)((bytes[byteIndex] >> (7 - intra)) & 0x01);
                value = (byte)((value << 1) | bit);
                bitIndex++;
            }

            sb.Append(mapper(value));
        }

        return sb.ToString();
    }

    private static char UnmapLowerSpecial(byte value)
    {
        return value switch
        {
            <= 25 => (char)('a' + value),
            26 => '.',
            27 => '_',
            28 => '$',
            29 => '|',
            _ => throw new EncodingException("invalid LOWER_SPECIAL value"),
        };
    }

    private char UnmapLowerUpperDigitSpecial(byte value)
    {
        return value switch
        {
            <= 25 => (char)('a' + value),
            <= 51 => (char)('A' + value - 26),
            <= 61 => (char)('0' + value - 52),
            62 => SpecialChar1,
            63 => SpecialChar2,
            _ => throw new EncodingException("invalid LOWER_UPPER_DIGIT_SPECIAL value"),
        };
    }

    private static string UnescapeAllUpper(string input)
    {
        StringBuilder sb = new(input.Length);
        for (int i = 0; i < input.Length; i++)
        {
            char c = input[i];
            if (c == '|' && i + 1 < input.Length)
            {
                i++;
                sb.Append(char.ToUpperInvariant(input[i]));
            }
            else
            {
                sb.Append(c);
            }
        }

        return sb.ToString();
    }
}

