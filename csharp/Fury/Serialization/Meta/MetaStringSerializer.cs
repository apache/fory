using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using Fury.Collections;
using Fury.Context;
using Fury.Meta;
using JetBrains.Annotations;

namespace Fury.Serialization.Meta;

internal sealed class MetaStringSerializer
{
    private int? _cachedMetaStringId;
    private bool _shouldWriteId;
    private bool _hasWrittenHeader;
    private bool _hasWrittenHashCodeOrEncoding;
    private int _writtenBytesCount;
    private AutoIncrementIdDictionary<MetaString> _metaStringContext = null!;

    public void Reset()
    {
        _cachedMetaStringId = null;
        _shouldWriteId = false;
        _hasWrittenHeader = false;
        _hasWrittenHashCodeOrEncoding = false;
        _writtenBytesCount = 0;
    }

    public void Initialize(AutoIncrementIdDictionary<MetaString> metaStringContext)
    {
        _metaStringContext = metaStringContext;
    }

    [MustUseReturnValue]
    public bool Write(ref SerializationWriterRef writerRef, MetaString metaString)
    {
        _cachedMetaStringId ??= _metaStringContext.GetOrAdd(metaString, out _shouldWriteId);
        if (_shouldWriteId)
        {
            var header = MetaStringHeader.FromId(_cachedMetaStringId.Value);
            WriteHeader(ref writerRef, header.Value);
            return _hasWrittenHeader;
        }
        else
        {
            var length = metaString.Bytes.Length;
            var header = MetaStringHeader.FromLength(length);
            WriteHeader(ref writerRef, header.Value);
            if (!_hasWrittenHeader)
            {
                return false;
            }
            if (metaString.IsSmallString)
            {
                WriteEncoding(ref writerRef, metaString.MetaEncoding);
            }
            else
            {
                WriteHashCode(ref writerRef, metaString.HashCode);
            }

            if (!_hasWrittenHashCodeOrEncoding)
            {
                return false;
            }

            return WriteMetaStringBytes(ref writerRef, metaString);
        }
    }

    private void WriteHeader(ref SerializationWriterRef writerRef, uint header)
    {
        if (_hasWrittenHeader)
        {
            return;
        }

        _hasWrittenHeader = writerRef.Write7BitEncodedUInt32(header);
    }

    private void WriteEncoding(ref SerializationWriterRef writerRef, MetaString.Encoding encoding)
    {
        if (_hasWrittenHashCodeOrEncoding)
        {
            return;
        }

        _hasWrittenHashCodeOrEncoding = writerRef.WriteUInt8((byte)encoding);
    }

    private void WriteHashCode(ref SerializationWriterRef writerRef, ulong hashCode)
    {
        if (_hasWrittenHashCodeOrEncoding)
        {
            return;
        }

        _hasWrittenHashCodeOrEncoding = writerRef.WriteInt64(hashCode);
    }

    private bool WriteMetaStringBytes(ref SerializationWriterRef writerRef, MetaString metaString)
    {
        var bytes = metaString.Bytes;
        if (_writtenBytesCount == bytes.Length)
        {
            return true;
        }

        var unwrittenBytes = bytes.Slice(_writtenBytesCount);
        var writtenBytes = writerRef.WriteBytes(unwrittenBytes);
        _writtenBytesCount += writtenBytes;
        Debug.Assert(_writtenBytesCount <= bytes.Length);
        return _writtenBytesCount == bytes.Length;
    }
}

internal struct MetaStringDeserializer(MetaStringStorage.EncodingPolicy encodingPolicy)
{
    private MetaStringStorage _sharedMetaStringStorage;
    private MetaStringHeader? _header;
    private ulong? _hashCode;
    private MetaString.Encoding? _metaEncoding;
    private MetaString? _metaString;

    private MetaStringStorage.MetaStringFactory? _cache;
    private AutoIncrementIdDictionary<MetaString> _metaStringContext;

    public void Reset()
    {
        _header = null;
        _hashCode = null;
        _metaString = null;
    }

    public void Initialize(MetaStringStorage metaStringStorage, AutoIncrementIdDictionary<MetaString> metaStringContext)
    {
        _sharedMetaStringStorage = metaStringStorage;
        _metaStringContext = metaStringContext;
        Reset();
    }

    public async ValueTask<ReadValueResult<MetaString>> Read(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        if (_metaString is not null)
        {
            return ReadValueResult<MetaString>.FromValue(_metaString);
        }

        await ReadHeader(reader, isAsync, cancellationToken);
        if (_header is null)
        {
            return ReadValueResult<MetaString>.Failed;
        }

        await ReadMetaString(reader, isAsync, cancellationToken);
        if (_metaString is null)
        {
            return ReadValueResult<MetaString>.Failed;
        }

        return ReadValueResult<MetaString>.FromValue(_metaString);
    }

    private async ValueTask ReadHeader(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        if (_header is not null)
        {
            return;
        }

        ReadValueResult<uint> uintResult;
        if (isAsync)
        {
            uintResult = await reader.Read7BitEncodedUintAsync(cancellationToken);
        }
        else
        {
            // ReSharper disable once MethodHasAsyncOverloadWithCancellation
            uintResult = reader.Read7BitEncodedUint();
        }

        if (!uintResult.IsSuccess)
        {
            return;
        }

        var header = new MetaStringHeader(uintResult.Value);
        _header = header;
    }

    private async ValueTask ReadMetaString(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        if (_metaString is not null)
        {
            return;
        }

        Debug.Assert(_header is not null);
        var header = _header.Value;
        if (header.IsId)
        {
            _metaString = GetMetaStringById();
        }
        else
        {
            await ReadMetaStringBytes(reader, isAsync, cancellationToken);
        }
    }

    private MetaString GetMetaStringById()
    {
        var id = _header!.Value.Id;
        if (!_metaStringContext.TryGetValue(id, out var metaString))
        {
            ThrowHelper.ThrowBadDeserializationInputException_UnknownMetaStringId(id);
        }

        return metaString;
    }

    private async ValueTask ReadMetaStringBytes(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        var length = _header!.Value.Length;
        ulong hashCode = 0;
        MetaString.Encoding metaEncoding = default;
        if (length > MetaString.SmallStringThreshold)
        {
            // big meta string

            await ReadHashCode(reader, isAsync, cancellationToken);
            if (!_hashCode.HasValue)
            {
                return;
            }
            hashCode = _hashCode.Value;
        }
        else
        {
            // small meta string

            await ReadMetaEncoding(reader, isAsync, cancellationToken);
            if (!_metaEncoding.HasValue)
            {
                return;
            }
            metaEncoding = _metaEncoding.Value;
        }

        // Maybe we can use the hash code to get the meta string and skip reading the bytes
        // if a meta string can be found by the hash code.

        var bytesResult = await reader.Read(length, isAsync, cancellationToken);
        var buffer = bytesResult.Buffer;
        var bufferLength = buffer.Length;
        if (bufferLength < length)
        {
            reader.AdvanceTo(buffer.Start, buffer.End);
            return;
        }

        if (bufferLength > length)
        {
            buffer = buffer.Slice(0, length);
        }

        if (length <= MetaString.SmallStringThreshold)
        {
            hashCode = MetaString.GetHashCode(buffer, metaEncoding);
        }

        _metaString = _sharedMetaStringStorage.GetMetaString(hashCode, in buffer, encodingPolicy, ref _cache);

        reader.AdvanceTo(buffer.End);
    }

    private async ValueTask ReadHashCode(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        if (_hashCode is not null)
        {
            return;
        }

        ReadValueResult<ulong> ulongResult;
        if (isAsync)
        {
            ulongResult = await reader.ReadUInt64Async(cancellationToken);
        }
        else
        {
            // ReSharper disable once MethodHasAsyncOverloadWithCancellation
            ulongResult = reader.ReadUInt64();
        }

        if (ulongResult.IsSuccess)
        {
            _hashCode = ulongResult.Value;
        }
    }

    private async ValueTask ReadMetaEncoding(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        if (_metaEncoding is not null)
        {
            return;
        }

        ReadValueResult<byte> byteResult;
        if (isAsync)
        {
            byteResult = await reader.ReadUInt8Async(cancellationToken);
        }
        else
        {
            // ReSharper disable once MethodHasAsyncOverloadWithCancellation
            byteResult = reader.ReadUInt8();
        }

        if (byteResult.IsSuccess)
        {
            _metaEncoding = (MetaString.Encoding)byteResult.Value;
        }
    }
}

internal readonly struct MetaStringHeader(uint value)
{
    public uint Value { get; } = value;
    public bool IsId => (Value & 1) == 1;
    public int Length
    {
        get
        {
            Debug.Assert(!IsId);
            return (int)(Value >> 1);
        }
    }

    public int Id
    {
        get
        {
            Debug.Assert(IsId);
            return (int)(Value >> 1) - 1;
        }
    }

    public static MetaStringHeader FromLength(int length) => new((uint)length << 1);

    public static MetaStringHeader FromId(int id) => new((uint)(id + 1) << 1 | 1);
}
