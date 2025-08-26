using System.Diagnostics.Contracts;
using System.Runtime.CompilerServices;

#if NET6_0_OR_GREATER
using System.Runtime.Intrinsics.X86;
#endif

namespace Fury.Helpers;

internal static class BitHelper
{
    [Pure]
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static int GetBitMask32(int bitsCount) => (1 << bitsCount) - 1;

    [Pure]
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static uint GetBitMaskU32(int bitsCount) => (1u << bitsCount) - 1;

    [Pure]
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static long GetBitMask64(int bitsCount) => (1L << bitsCount) - 1;

    [Pure]
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static ulong GetBitMaskU64(int bitsCount) => (1uL << bitsCount) - 1;

    [Pure]
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static byte ClearLowBits(byte value, int lowBitsCount) => (byte)(value & ~GetBitMask32(lowBitsCount));

    [Pure]
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static long ClearLowBits(long value, int lowBitsCount) => value & ~GetBitMask64(lowBitsCount);

    [Pure]
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static ulong ClearLowBits(ulong value, int lowBitsCount) => value & ~(ulong)GetBitMask64(lowBitsCount);

    [Pure]
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static byte ClearHighBits(byte value, int highBitsCount) => (byte)(value & GetBitMask32(8 - highBitsCount));

    [Pure]
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static byte KeepLowBits(byte value, int lowBitsCount) => (byte)(value & GetBitMask32(lowBitsCount));

    [Pure]
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static ulong KeepLowBits(ulong value, int lowBitsCount) => value & (ulong)GetBitMask64(lowBitsCount);

    [Pure]
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static byte KeepHighBits(byte value, int highBitsCount) => (byte)(value & ~GetBitMask32(8 - highBitsCount));

    [Pure]
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static byte ReadBits(byte b1, int bitOffset, int bitCount)
    {

        return (byte)((b1 >>> (8 - bitCount - bitOffset)) & GetBitMask32(bitCount));
    }

    [Pure]
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static byte ReadBits(byte b1, byte b2, int bitOffset, int bitCount)
    {
        var byteFromB1 = b1 << (bitOffset + bitCount - 8);
        var byteFromB2 = b2 >>> (8 * 2 - bitCount - bitOffset);
        return (byte)((byteFromB1 | byteFromB2) & GetBitMask32(bitCount));
    }

    [Pure]
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static ulong BitFieldExtract(ulong value, byte bitOffset, byte bitCount)
    {
#if NET6_0_OR_GREATER
        if (Bmi1.X64.IsSupported)
        {
            return Bmi1.X64.BitFieldExtract(value, bitOffset, bitCount);
        }
#endif
        return (value >>> bitOffset) & GetBitMaskU64(bitCount);
    }
}
