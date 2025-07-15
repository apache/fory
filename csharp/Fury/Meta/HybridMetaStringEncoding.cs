using System;
using System.Diagnostics.CodeAnalysis;
using System.Linq;

namespace Fury.Meta;

internal sealed class HybridMetaStringEncoding(char specialChar1, char specialChar2, MetaString.Encoding[] candidateEncodings)
{
    public LowerUpperDigitSpecialEncoding LowerUpperDigit { get; } = new(specialChar1, specialChar2);
    public char SpecialChar1 { get; } = specialChar1;
    public char SpecialChar2 { get; } = specialChar2;

    private MetaString.Encoding[] _candidateEncodings = candidateEncodings;

    public MetaStringEncoding GetEncoding(MetaString.Encoding encoding)
    {
        var result = encoding switch
        {
            MetaString.Encoding.LowerSpecial => LowerSpecialEncoding.Instance,
            MetaString.Encoding.FirstToLowerSpecial => FirstToLowerSpecialEncoding.Instance,
            MetaString.Encoding.AllToLowerSpecial => AllToLowerSpecialEncoding.Instance,
            MetaString.Encoding.LowerUpperDigitSpecial => LowerUpperDigit,
            MetaString.Encoding.Utf8 => Utf8Encoding.Instance,
            _ => ThrowHelper.ThrowUnreachableException<MetaStringEncoding>(),
        };

        return result;
    }

    private MetaString GetMetaString(string chars, MetaString.Encoding encoding)
    {
        var e = GetEncoding(encoding);
        var byteCount = e.GetByteCount(chars);
        var bytes = new byte[byteCount];
        e.GetBytes(chars.AsSpan(), bytes);
        return new MetaString(chars, encoding, SpecialChar1, SpecialChar2, bytes);
    }

    public MetaStringEncoding SelectEncoding(string chars)
    {
        var statistics = GetStatistics(chars);
        if (statistics.LowerSpecialCompatible && _candidateEncodings.Contains(MetaString.Encoding.LowerSpecial))
        {
            return LowerSpecialEncoding.Instance;
        }

        if (statistics.LowerUpperDigitCompatible)
        {
            if (statistics.DigitCount == 0)
            {
                if (
                    statistics.UpperCount == 1
                    && char.IsUpper(chars[0])
                    && _candidateEncodings.Contains(MetaString.Encoding.FirstToLowerSpecial)
                )
                {
                    return FirstToLowerSpecialEncoding.Instance;
                }

                var bitCountWithAllToLower = AllToLowerSpecialEncoding.GetBitCount(chars.Length, statistics.UpperCount);
                var bitCountWithLowerUpperDigit = LowerUpperDigitSpecialEncoding.GetBitCount(chars.Length);
                if (
                    bitCountWithAllToLower < bitCountWithLowerUpperDigit
                    && _candidateEncodings.Contains(MetaString.Encoding.AllToLowerSpecial)
                )
                {
                    return AllToLowerSpecialEncoding.Instance;
                }
            }

            if (_candidateEncodings.Contains(MetaString.Encoding.LowerUpperDigitSpecial))
            {
                return LowerUpperDigit;
            }
        }
        return Utf8Encoding.Instance;
    }

    private CharStatistics GetStatistics(string chars)
    {
        var digitCount = 0;
        var upperCount = 0;
        var lowerSpecialCompatible = true;
        var lowerUpperDigitCompatible = true;
        foreach (var c in chars)
        {
            if (lowerSpecialCompatible)
            {
                lowerSpecialCompatible = AbstractLowerSpecialEncoding.TryEncodeChar(c, out _);
            }

            if (lowerUpperDigitCompatible)
            {
                lowerUpperDigitCompatible = LowerUpperDigit.TryEncodeChar(c, out _);
            }

            if (char.IsDigit(c))
            {
                digitCount++;
            }
            else if (char.IsUpper(c))
            {
                upperCount++;
            }
        }

        return new CharStatistics(digitCount, upperCount, lowerSpecialCompatible, lowerUpperDigitCompatible);
    }

    private record struct CharStatistics(
        int DigitCount,
        int UpperCount,
        bool LowerSpecialCompatible,
        bool LowerUpperDigitCompatible
    );
}
