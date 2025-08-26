using System;
using System.Buffers;
using System.Text;

namespace Fury.Meta;

// The StripLastChar flag need to be set in the first byte of the encoded data,
// so that wo can not implement a dotnet-style encoder.
// However, implementing a decoder is possible.

internal abstract class MetaStringDecoder : Decoder
{
    internal bool StripLastChar { get; set; }
    internal bool HasState { get; set; }
    protected byte LeftoverBits { get; set; }
    protected int LeftoverBitCount { get; set; }
    internal bool MustFlush { get; private protected set; }

    internal bool HasLeftoverData => LeftoverBitCount > 0;

    internal void SetLeftoverData(byte bits, int bitCount)
    {
        LeftoverBits = (byte)(bits & ((1 << bitCount) - 1));
        LeftoverBitCount = bitCount;
    }

    internal (byte bits, int bitCount) GetLeftoverData()
    {
        return (LeftoverBits, LeftoverBitCount);
    }

    public override void Reset()
    {
        LeftoverBits = 0;
        LeftoverBitCount = 0;
        HasState = false;
        StripLastChar = false;
        MustFlush = false;
    }

#if !NET8_0_OR_GREATER
    public abstract void Convert(
        ReadOnlySpan<byte> bytes,
        Span<char> chars,
        bool flush,
        out int bytesUsed,
        out int charsUsed,
        out bool completed
    );

    public abstract int GetCharCount(ReadOnlySpan<byte> bytes, bool flush);

    public abstract int GetChars(ReadOnlySpan<byte> bytes, Span<char> chars, bool flush);
#endif

    public sealed override unsafe void Convert(
        byte* bytes,
        int byteCount,
        char* chars,
        int charCount,
        bool flush,
        out int bytesUsed,
        out int charsUsed,
        out bool completed
    )
    {
        var byteSpan = new ReadOnlySpan<byte>(bytes, byteCount);
        var charSpan = new Span<char>(chars, charCount);
        Convert(byteSpan, charSpan, flush, out bytesUsed, out charsUsed, out completed);
    }

    public sealed override void Convert(
        byte[] bytes,
        int byteIndex,
        int byteCount,
        char[] chars,
        int charIndex,
        int charCount,
        bool flush,
        out int bytesUsed,
        out int charsUsed,
        out bool completed
    )
    {
        var byteSpan = new ReadOnlySpan<byte>(bytes, byteIndex, byteCount);
        var charSpan = new Span<char>(chars, charIndex, charCount);
        Convert(byteSpan, charSpan, flush, out bytesUsed, out charsUsed, out completed);
    }

    public void Convert(
        ReadOnlySequence<byte> bytes,
        Span<char> chars,
        bool flush,
        out int bytesUsed,
        out int charsUsed,
        out bool completed
    )
    {
        var reader = new SequenceReader<byte>(bytes);
        bytesUsed = 0;
        charsUsed = 0;
        var unwrittenChars = chars;
        completed = reader.End;
        while (!reader.End && unwrittenChars.Length > 0)
        {
            var currentSpan = reader.UnreadSpan;
            var currentFlush = flush && reader.Remaining == currentSpan.Length;
            Convert(
                currentSpan,
                chars,
                currentFlush,
                out var currentBytesUsed,
                out var currentCharsUsed,
                out completed
            );
            bytesUsed += currentBytesUsed;
            charsUsed += currentCharsUsed;
            unwrittenChars = chars.Slice(charsUsed);
            reader.Advance(currentBytesUsed);
        }
    }

    public sealed override unsafe int GetCharCount(byte* bytes, int count, bool flush)
    {
        return GetCharCount(new ReadOnlySpan<byte>(bytes, count), flush);
    }

    public sealed override int GetCharCount(byte[] bytes, int index, int count)
    {
        return GetCharCount(bytes, index, count, true);
    }

    public sealed override int GetCharCount(byte[] bytes, int index, int count, bool flush)
    {
        return GetCharCount(bytes.AsSpan(index, count), flush);
    }

    public int GetCharCount(ReadOnlySequence<byte> bytes, bool flush)
    {
        var reader = new SequenceReader<byte>(bytes);
        var charCount = 0;
        while (!reader.End)
        {
            var currentSpan = reader.UnreadSpan;
            var currentFlush = flush && reader.Remaining == currentSpan.Length;
            charCount += GetCharCount(currentSpan, currentFlush);
            reader.Advance(currentSpan.Length);
        }
        return charCount;
    }

    public sealed override unsafe int GetChars(byte* bytes, int byteCount, char* chars, int charCount, bool flush)
    {
        var byteSpan = new ReadOnlySpan<byte>(bytes, byteCount);
        var charSpan = new Span<char>(chars, charCount);
        return GetChars(byteSpan, charSpan, flush);
    }

    public sealed override int GetChars(byte[] bytes, int byteIndex, int byteCount, char[] chars, int charIndex)
    {
        return GetChars(bytes, byteIndex, byteCount, chars, charIndex, true);
    }

    public sealed override int GetChars(
        byte[] bytes,
        int byteIndex,
        int byteCount,
        char[] chars,
        int charIndex,
        bool flush
    )
    {
        return GetChars(bytes.AsSpan(byteIndex, byteCount), chars.AsSpan(charIndex), flush);
    }
}
