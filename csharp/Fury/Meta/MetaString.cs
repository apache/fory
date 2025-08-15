using System;
using System.Buffers;
using System.Diagnostics.Contracts;
using System.Runtime.InteropServices;
using Fury.Helpers;

namespace Fury.Meta;

internal sealed class MetaString : IEquatable<MetaString>
{
    public const int SmallStringThreshold = sizeof(long) * 2;
    private const int EncodingBitCount = 8;

    public ulong HashCode { get; }
    public ulong HashCodeWithoutEncoding => BitHelper.ClearLowBits(HashCode, EncodingBitCount);
    public string Value { get; }
    public Encoding MetaEncoding { get; }
    public char SpecialChar1 { get; }
    public char SpecialChar2 { get; }
    private readonly byte[] _bytes;
    public ReadOnlySpan<byte> Bytes => new(_bytes);
    public bool IsSmallString => _bytes.Length <= SmallStringThreshold;

    public MetaString(string value, Encoding metaEncoding, char specialChar1, char specialChar2, byte[] bytes)
    {
        Value = value;
        MetaEncoding = metaEncoding;
        SpecialChar1 = specialChar1;
        SpecialChar2 = specialChar2;
        _bytes = bytes;
        HashCode = GetHashCode(_bytes, metaEncoding);
    }

    [Pure]
    public static Encoding GetEncodingFromHashCode(ulong hashCode)
    {
        return (Encoding)BitHelper.KeepLowBits(hashCode, EncodingBitCount);
    }

    [Pure]
    public static ulong GetHashCode(ReadOnlySpan<byte> bytes, Encoding metaEncoding)
    {
        HashHelper.MurmurHash3_x64_128(bytes, 47, out var hash, out _);
        return GetHashCode(hash, metaEncoding);
    }

    [Pure]
    public static ulong GetHashCode(int length, ulong v1, ulong v2, Encoding metaEncoding)
    {
        Span<byte> bytes = stackalloc byte[SmallStringThreshold];
        var ulongSpan = MemoryMarshal.Cast<byte, ulong>(bytes);
        ulongSpan[0] = v1;
        ulongSpan[1] = v2;
        bytes = bytes.Slice(0, length);
        HashHelper.MurmurHash3_x64_128(bytes, 47, out var hash, out _);
        return GetHashCode(hash, metaEncoding);
    }

    [Pure]
    public static ulong GetHashCode(ReadOnlySequence<byte> bytes, Encoding metaEncoding)
    {
        HashHelper.MurmurHash3_x64_128(bytes, 47, out var hash, out _);
        return GetHashCode(hash, metaEncoding);
    }

    [Pure]
    public static ulong GetHashCodeWithoutEncoding(ReadOnlySequence<byte> bytes)
    {
        HashHelper.MurmurHash3_x64_128(bytes, 47, out var hash, out _);
        return GetHashCodeWithoutEncoding(hash);
    }

    [Pure]
    private static ulong GetHashCode(ulong hash, Encoding metaEncoding)
    {
        if (hash == 0)
        {
            // Ensure hash is never 0
            // Last byte is reserved for header
            hash += 0x100;
        }
        hash = BitHelper.ClearLowBits(hash, EncodingBitCount);
        var header = (byte)metaEncoding;
        hash |= header;
        return hash;
    }

    [Pure]
    private static ulong GetHashCodeWithoutEncoding(ulong hash)
    {
        if (hash == 0)
        {
            // Ensure hash is never 0
            // Last byte is reserved for header
            hash += 0x100;
        }
        hash = BitHelper.ClearLowBits(hash, EncodingBitCount);
        return hash;
    }

    [Pure]
    public override int GetHashCode() => HashCode.GetHashCode();

    [Pure]
    public bool Equals(MetaString? other)
    {
        if (other is null)
        {
            return false;
        }

        if (ReferenceEquals(this, other))
        {
            return true;
        }

        return Value == other.Value
            && MetaEncoding == other.MetaEncoding
            && SpecialChar1 == other.SpecialChar1
            && SpecialChar2 == other.SpecialChar2;
    }

    [Pure]
    public override bool Equals(object? obj)
    {
        return ReferenceEquals(this, obj) || obj is MetaString other && Equals(other);
    }

    public enum Encoding : byte
    {
        Utf8 = 0,
        LowerSpecial = 1,
        LowerUpperDigitSpecial = 2,
        FirstToLowerSpecial = 3,
        AllToLowerSpecial = 4,
    }
}
