#if !NET8_0_OR_GREATER
using System;
using System.Runtime.CompilerServices;
using System.Text;

namespace Fury;

internal static class EncodingExtensions
{
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static unsafe void Convert(
        this Encoder encoder,
        ReadOnlySpan<char> chars,
        Span<byte> bytes,
        bool flush,
        out int charsUsed,
        out int bytesUsed,
        out bool completed
    )
    {
        fixed (char* pChars = chars)
        fixed (byte* pBytes = bytes)
        {
            encoder.Convert(
                pChars,
                chars.Length,
                pBytes,
                bytes.Length,
                flush,
                out charsUsed,
                out bytesUsed,
                out completed
            );
        }
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static unsafe void Convert(
        this Decoder decoder,
        ReadOnlySpan<byte> bytes,
        Span<char> chars,
        bool flush,
        out int bytesUsed,
        out int charsUsed,
        out bool completed
    )
    {
        fixed (byte* pBytes = bytes)
        fixed (char* pChars = chars)
        {
            decoder.Convert(
                pBytes,
                bytes.Length,
                pChars,
                chars.Length,
                flush,
                out bytesUsed,
                out charsUsed,
                out completed
            );
        }
    }
}
#endif
