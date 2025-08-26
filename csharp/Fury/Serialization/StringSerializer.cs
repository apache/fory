using System;
using System.Buffers;
using System.Diagnostics;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Fury.Context;

namespace Fury.Serialization;

public enum StringEncoding : byte
{
    Latin1 = 0,

    // ReSharper disable once InconsistentNaming
    UTF16 = 1,

    // ReSharper disable once InconsistentNaming
    UTF8 = 2,
}

file static class StringSerializationHelper
{
    private const int EncodingBitCount = 2;
    private const int EncodingMask = (1 << EncodingBitCount) - 1;

    internal static readonly Encoding Latin1 = Encoding.GetEncoding(
        "ISO-8859-1",
        EncoderFallback.ExceptionFallback,
        DecoderFallback.ExceptionFallback
    );

    public static uint GetHeader(int length, StringEncoding encoding)
    {
        return (uint)((length << EncodingBitCount) | (byte)encoding);
    }

    public static (int Length, StringEncoding encoding) GetLengthAndEncoding(uint header)
    {
        var encoding = (StringEncoding)(header & EncodingMask);
        var length = (int)(header >> EncodingBitCount);
        return (length, encoding);
    }
}

internal sealed class StringSerializer : AbstractSerializer<string>
{
    private static readonly Encoding Latin1Encoding = StringSerializationHelper.Latin1;
    private static readonly Encoding Utf16Encoding = Encoding.Unicode;
    private static readonly Encoding Utf8Encoding = Encoding.UTF8;

    private readonly Encoder _latin1Encoder = Latin1Encoding.GetEncoder();
    private readonly Encoder _utf16Encoder = Utf16Encoding.GetEncoder();
    private readonly Encoder _utf8Encoder = Utf8Encoding.GetEncoder();

    private Encoding? _encoding;
    private Encoder? _encoder;
    private StringEncoding _selectedStringEncoding;
    private bool _hasWrittenHeader;
    private bool _hasWrittenUtf16ByteCount;
    private int _charsUsed;
    private int _byteCount;

    public override void Reset()
    {
        _encoding = null;
        _encoder = null;
        _latin1Encoder.Reset();
        _utf16Encoder.Reset();
        _utf8Encoder.Reset();

        _hasWrittenHeader = false;
        _hasWrittenUtf16ByteCount = false;
        _charsUsed = 0;
        _byteCount = 0;
    }

    public override bool Serialize(SerializationWriter writer, in string value)
    {
        var config = writer.Config;
        var writerRef = writer.ByrefWriter;
        if (_encoding is null)
        {
            // If no preferred encoding is set, we default to UTF8
            _encoding = Utf8Encoding;
            _selectedStringEncoding = StringEncoding.UTF8;
            foreach (var preferredEncoding in config.PreferredStringEncodings)
            {
                (_encoding, _encoder, _selectedStringEncoding) = preferredEncoding switch
                {
                    StringEncoding.Latin1 => (Latin1Encoding, _latin1Encoder, StringEncoding.Latin1),
                    StringEncoding.UTF16 => (Utf16Encoding, _utf16Encoder, StringEncoding.UTF16),
                    _ => (Utf8Encoding, _utf8Encoder, StringEncoding.UTF8),
                };
                try
                {
                    _byteCount = _encoding.GetByteCount(value);
                    _encoder = _encoding.GetEncoder();
                }
                catch (EncoderFallbackException) { }
            }
        }

        WriteHeader(ref writerRef, value);
        WriteUtf8ByteCount(ref writerRef);
        WriteStringBytes(ref writerRef, value);

        return _charsUsed == value.Length;
    }

    private void WriteHeader(ref SerializationWriterRef writerRef, string value)
    {
        if (_hasWrittenHeader)
        {
            return;
        }

        var config = writerRef.Config;
        int length;
        if (_selectedStringEncoding is StringEncoding.UTF8 && config.WriteUtf16ByteCountForUtf8Encoding)
        {
            // When WriteUtf16ByteCountForUtf8Encoding is true,
            // length contained in the header represents the byte length of the UTF-16 string.
            // This is redundant with the byte count written after the header,
            // but we can use this to create a string without allocating a temporary buffer.
            length = value.Length * sizeof(char);
        }
        else
        {
            // When WriteUtf16ByteCountForUtf8Encoding is false,
            // length contained in the header represents the byte length of the selected encoding.
            length = _byteCount;
        }
        var header = StringSerializationHelper.GetHeader(length, _selectedStringEncoding);
        _hasWrittenHeader = writerRef.Write7BitEncodedUInt32(header);
    }

    private void WriteUtf8ByteCount(ref SerializationWriterRef writerRef)
    {
        if (_hasWrittenUtf16ByteCount)
        {
            return;
        }

        if (_selectedStringEncoding is StringEncoding.UTF8 && writerRef.Config.WriteUtf16ByteCountForUtf8Encoding)
        {
            // When WriteUtf16ByteCountForUtf8Encoding is true,
            // the true byte length of the UTF-8 string is written as Int32 after the header.
            _hasWrittenUtf16ByteCount = writerRef.WriteInt32(_byteCount);
        }
        else
        {
            _hasWrittenUtf16ByteCount = true;
        }
    }

    private void WriteStringBytes(ref SerializationWriterRef writerRef, string value)
    {
        while (_charsUsed < value.Length)
        {
            var charSpan = value.AsSpan().Slice(_charsUsed);
            var buffer = writerRef.GetSpan();
            if (buffer.Length == 0)
            {
                return;
            }

            _encoder!.Convert(charSpan, buffer, true, out var currentCharsUsed, out var currentBytesUsed, out _);

            _charsUsed += currentCharsUsed;
            writerRef.Advance(currentBytesUsed);
        }
    }
}

internal sealed class StringDeserializer : AbstractDeserializer<string>
{
    private readonly Decoder _latin1Decoder = StringSerializationHelper.Latin1.GetDecoder();
    private readonly Decoder _utf16Decoder = Encoding.Unicode.GetDecoder();
    private readonly Decoder _utf8Decoder = Encoding.UTF8.GetDecoder();

    private bool _hasReadHeader;
    private StringEncoding _encoding;
    private int _byteCount;
    private int? _charCount;

    private int _bytesUsed;
    private readonly ArrayBufferWriter<char> _charBuffer = new();

    public override object ReferenceableObject => ThrowInvalidOperationException_AcyclicType();

    public override void Reset()
    {
        _hasReadHeader = false;
        _bytesUsed = 0;
        _charBuffer.Clear();
    }

    private Decoder SelectDecoder(StringEncoding encoding)
    {
        return encoding switch
        {
            StringEncoding.Latin1 => _latin1Decoder,
            StringEncoding.UTF16 => _utf16Decoder,
            _ => _utf8Decoder,
        };
    }

    public override ReadValueResult<string> Deserialize(DeserializationReader reader)
    {
        var task = CreateAndFillInstance(reader, false, CancellationToken.None);
        Debug.Assert(task.IsCompleted);
        return task.Result;
    }

    public override ValueTask<ReadValueResult<string>> DeserializeAsync(
        DeserializationReader reader,
        CancellationToken cancellationToken = default
    )
    {
        return CreateAndFillInstance(reader, true, cancellationToken);
    }

    private async ValueTask<ReadValueResult<string>> CreateAndFillInstance(
        DeserializationReader reader,
        bool isAsync,
        CancellationToken cancellationToken
    )
    {
        var config = reader.Config;
        if (!_hasReadHeader)
        {
            var headerResult = await reader.Read7BitEncodedUint(isAsync, cancellationToken);
            if (!headerResult.IsSuccess)
            {
                return ReadValueResult<string>.Failed;
            }

            (_byteCount, _encoding) = StringSerializationHelper.GetLengthAndEncoding(headerResult.Value);
            _hasReadHeader = true;
        }

        if (config.ReadUtf16ByteCountForUtf8Encoding && _encoding is StringEncoding.UTF8)
        {
            if (_charCount is null)
            {
                _charCount = _byteCount / sizeof(char);
                _charBuffer.GetSpan(_charCount.Value);
                var utf8ByteCountResult = await reader.ReadInt32(isAsync, cancellationToken);
                if (!utf8ByteCountResult.IsSuccess)
                {
                    return ReadValueResult<string>.Failed;
                }
                _byteCount = utf8ByteCountResult.Value;
            }
        }

        var decoder = SelectDecoder(_encoding);
        while (_bytesUsed < _byteCount)
        {
            var requiredLength = _byteCount - _bytesUsed;
            var readResult = await reader.Read(requiredLength, isAsync, cancellationToken);
            var buffer = readResult.Buffer;
            if (buffer.Length == 0)
            {
                return ReadValueResult<string>.Failed;
            }

            if (buffer.Length > requiredLength)
            {
                buffer = buffer.Slice(0, requiredLength);
            }

            var bufferReader = new SequenceReader<byte>(buffer);
            while (!bufferReader.End)
            {
                var unwrittenChars = _charBuffer.GetSpan();
                var unreadBytes = bufferReader.UnreadSpan;
                var flush = bufferReader.Remaining == unreadBytes.Length;
                decoder.Convert(unreadBytes, unwrittenChars, flush, out var bytesUsed, out var charsUsed, out _);
                _bytesUsed += bytesUsed;
                _charBuffer.Advance(charsUsed);
                bufferReader.Advance(bytesUsed);
            }
        }

        if (_bytesUsed != _byteCount)
        {
            return ReadValueResult<string>.Failed;
        }

        var str = _charBuffer.WrittenSpan.ToString();
        return ReadValueResult<string>.FromValue(str);
    }
}
