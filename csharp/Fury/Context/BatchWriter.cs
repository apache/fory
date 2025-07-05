using System;
using System.Buffers;
using System.Diagnostics.CodeAnalysis;
using System.IO.Pipelines;
using JetBrains.Annotations;

namespace Fury.Context;

// This is used to reduce the virtual call overhead of the PipeWriter

internal sealed class BatchWriter : IBufferWriter<byte>, IDisposable
{
    private PipeWriter _innerWriter = null!;
    private Memory<byte> _cachedMemory;

    public int Version { get; private set; }
    internal int Consumed { get; private set; }
    internal Memory<byte> Buffer => _cachedMemory;

    public Memory<byte> UnconsumedBuffer => _cachedMemory.Slice(Consumed);
    public Memory<byte> UnflushedConsumedBuffer => _cachedMemory.Slice(0, Consumed);

    [MemberNotNull(nameof(_innerWriter))]
    internal void Initialize(PipeWriter writer)
    {
        _innerWriter = writer;
        Consumed = 0;
        Version = 0;
    }

    internal void Reset()
    {
        _innerWriter = null!;
        Consumed = 0;
        Version = 0;
    }

    public void Flush()
    {
        if (Consumed > 0)
        {
            _innerWriter.Advance(Consumed);
            Consumed = 0;
        }
        _cachedMemory = Memory<byte>.Empty;
        Version++;
    }

    public void Advance(int bytes)
    {
        if (bytes + Consumed > _cachedMemory.Length)
        {
            ThrowHelper.ThrowArgumentOutOfRangeException_AttemptedToAdvanceFurtherThanBufferLength(
                nameof(bytes),
                _cachedMemory.Length,
                bytes
            );
        }

        Consumed += bytes;
        Version++;
    }

    [MustUseReturnValue]
    public Memory<byte> GetMemory(int sizeHint = 0)
    {
        var result = UnconsumedBuffer;
        if (result.Length < sizeHint)
        {
            if (Consumed > 0)
            {
                _innerWriter.Advance(Consumed);
                Consumed = 0;
            }
            _cachedMemory = _innerWriter.GetMemory(sizeHint);
            Version++;
            result = UnconsumedBuffer;
        }

        return result;
    }

    [MustUseReturnValue]
    public Span<byte> GetSpan(int sizeHint = 0)
    {
        return GetMemory(sizeHint).Span;
    }

    public void Dispose()
    {
        Flush();
    }
}
