using System;
using System.Buffers;
using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.IO.Pipelines;
using System.Numerics;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;

namespace Fury.Context;

/// <summary>
/// The result of read operation.
/// </summary>
/// <param name="isSuccess">
/// Indicates if the deserialization operation was successful.
/// If true, the <see cref="Value"/> property will contain the deserialized value and
/// the bytes will be consumed.
/// Otherwise, the <see cref="Value"/> property will be the default value of <typeparamref name="TValue"/> and
/// the bytes will be examined.
/// </param>
/// <param name="value">
/// The deserialized value. If <see cref="IsSuccess"/> is false, this will be the default value of <typeparamref name="TValue"/>.
/// </param>
/// <typeparam name="TValue">
/// The type of the deserialized value.
/// </typeparam>
public readonly struct ReadValueResult<TValue>
{
    /// <summary>
    /// Indicates if the deserialization operation was successful.
    /// If true, the <see cref="Value"/> property will contain the deserialized value and
    /// the bytes will be consumed.
    /// Otherwise, the <see cref="Value"/> property will be the default value of <typeparamref name="TValue"/> and
    /// the bytes will be examined.
    /// </summary>
    public bool IsSuccess { get; }

    /// <summary>
    /// The deserialized value. If <see cref="IsSuccess"/> is false, this will be the default value of <typeparamref name="TValue"/>.
    /// </summary>
    public TValue Value { get; }

    public ReadValueResult()
    {
        IsSuccess = false;
        Value = default!;
    }

    public ReadValueResult(in TValue value)
    {
        IsSuccess = true;
        Value = value;
    }

    /// <summary>
    /// Creates a successful <see cref="ReadValueResult{T}"/> with the provided value.
    /// </summary>
    /// <param name="value"></param>
    /// <returns></returns>
    public static ReadValueResult<TValue> FromValue(in TValue value)
    {
        return new ReadValueResult<TValue>(in value);
    }

    /// <summary>
    /// A failed <see cref="ReadValueResult{T}"/> instance with the default value of <typeparamref name="TValue"/>.
    /// </summary>
    public static ReadValueResult<TValue> Failed { get; } = new();
}

// /// <inheritdoc cref="ReadValueResult{TValue}"/>
// /// <param name="ReadingState">
// /// The state of read operation.
// /// Some read operations may read part of the data even if they fail, this can be used to resume the read operation.
// /// </param>
// /// <typeparam name="TValue">
// /// The type of the deserialized value.
// /// </typeparam>
// /// <typeparam name="TState">
// /// The type of the reading state.
// /// </typeparam>
// public readonly record struct ReadValueResult<TValue, TState>(
//     bool IsSuccess,
//     in TValue? Value,
//     in TState? ReadingState
// )
// {
//     /// <summary>
//     /// Creates a successful <see cref="ReadValueResult{T}"/> with the provided value.
//     /// </summary>
//     /// <param name="value">
//     /// The deserialized value.
//     /// </param>
//     /// <returns>
//     /// A successful <see cref="ReadValueResult{T}"/> instance with the provided value.
//     /// </returns>
//     public static ReadValueResult<TValue, TState> Success(in TValue? value)
//     {
//         return new ReadValueResult<TValue, TState>(true, in value, default);
//     }
//
//     /// <summary>
//     /// A failed <see cref="ReadValueResult{T}"/> instance with the default value of <typeparamref name="TValue"/>.
//     /// </summary>
//     /// <param name="minRequiredBytes">
//     /// The minimum required bytes to complete the current read operation.
//     /// This will be 0 if the write operation is successful.
//     /// </param>
//     /// <param name="readingState">
//     /// The state of read operation.
//     /// </param>
//     /// <returns>
//     /// A failed <see cref="ReadValueResult{T}"/> instance with the default value of <typeparamref name="TValue"/>
//     /// and the provided reading state.
//     /// </returns>
//     public static ReadValueResult<TValue, TState> Failure(int minRequiredBytes, in TState? readingState) =>
//         new(false, default, in readingState);
// }
//
// /// <summary>
// /// Result of read byte sequence operation.
// /// </summary>
// public readonly struct ReadBytesResult
// {
//     private readonly ResultFlags _flags;
//     public ReadOnlySequence<byte> Buffer { get; }
//
//     /// <summary>
//     /// Indicates if the <see cref="ReadOnlySequence{T}.Length"/> of <see cref="Buffer"/> is greater than or equal to
//     /// the provided "sizeHint".
//     /// </summary>
//     public bool IsSuccess => (_flags & ResultFlags.IsSuccess) != 0;
//
//     /// <summary>
//     /// Indicates if all remaining data has been returned and there is no more data to read.
//     /// </summary>
//     public bool IsCompleted => (_flags & ResultFlags.IsCompleted) != 0;
//
//     internal ReadBytesResult(bool isSuccess, bool isCompleted, in ReadOnlySequence<byte> buffer)
//     {
//         _flags = ResultFlags.None;
//         if (isSuccess)
//         {
//             _flags |= ResultFlags.IsSuccess;
//         }
//         if (isCompleted)
//         {
//             _flags |= ResultFlags.IsCompleted;
//         }
//         Buffer = buffer;
//     }
//
//     [Flags]
//     private enum ResultFlags
//     {
//         None = 0,
//         IsSuccess = 1,
//         IsCompleted = 2,
//     }
// }

internal sealed class BatchReader
{
    private PipeReader _innerReader = null!;
    private ReadOnlySequence<byte> _currentBuffer;
    private ReadOnlySequence<byte> _uncomsumedBuffer;
    private ReadOnlySequence<byte> _unexaminedBuffer;

    internal int Version { get; private set; }

    private bool _isInnerReaderCompleted;
    private bool _isLastReadCanceled;

    internal void Reset()
    {
        _innerReader = null!;
    }

    [MemberNotNull(nameof(_innerReader))]
    internal void Initialize(PipeReader reader)
    {
        _innerReader = reader;
        _currentBuffer = ReadOnlySequence<byte>.Empty;
        _uncomsumedBuffer = ReadOnlySequence<byte>.Empty;
        _unexaminedBuffer = ReadOnlySequence<byte>.Empty;
        _isInnerReaderCompleted = false;
        Version = 0;
    }

    public void AdvanceTo(SequencePosition consumed)
    {
        Version++;
        _uncomsumedBuffer = _uncomsumedBuffer.Slice(consumed);
        if (_uncomsumedBuffer.Length < _unexaminedBuffer.Length)
        {
            _unexaminedBuffer = _uncomsumedBuffer;
        }
    }

    public void AdvanceTo(SequencePosition consumed, SequencePosition examined)
    {
        Version++;
        _uncomsumedBuffer = _uncomsumedBuffer.Slice(consumed);
        _unexaminedBuffer = _unexaminedBuffer.Slice(examined);
    }

    private void Flush()
    {
        // Check if the AdvanceTo call is necessary to reduce virtual calls.
        var consumed = _uncomsumedBuffer.Start;
        var examined = _unexaminedBuffer.Start;
        var start = _currentBuffer.Start;
        if (!consumed.Equals(start) || !examined.Equals(start))
        {
            _innerReader.AdvanceTo(consumed, examined);
            _currentBuffer = _uncomsumedBuffer;
        }
    }

    public ReadResult Read(int sizeHint = 0)
    {
        if (sizeHint < 0)
        {
            ThrowHelper.ThrowArgumentOutOfRangeException(nameof(sizeHint), sizeHint);
        }

        if (sizeHint > _uncomsumedBuffer.Length)
        {
            Flush();
            if (_innerReader.TryRead(out var innerResult))
            {
                PopulateNewData(in innerResult);
            }
        }

        return new ReadResult(_uncomsumedBuffer, _isInnerReaderCompleted, _isLastReadCanceled);
    }

    public async ValueTask<ReadResult> ReadAsync(int sizeHint, CancellationToken cancellationToken = default)
    {
        if (sizeHint < 0)
        {
            ThrowHelper.ThrowArgumentOutOfRangeException(nameof(sizeHint), sizeHint);
        }

        if (sizeHint is 0)
        {
            if (_unexaminedBuffer.IsEmpty)
            {
                Flush();
                var innerResult = await _innerReader.ReadAsync(cancellationToken);
                PopulateNewData(in innerResult);
            }
        }
        else if (sizeHint > _uncomsumedBuffer.Length || _unexaminedBuffer.IsEmpty)
        {
            Flush();
            var innerResult = await _innerReader.ReadAtLeastAsync(sizeHint, cancellationToken);
            PopulateNewData(in innerResult);
        }

        return new ReadResult(_uncomsumedBuffer, _isInnerReaderCompleted, _isLastReadCanceled);
    }

    private void PopulateNewData(in ReadResult result)
    {
        Version++;
        var examined = _uncomsumedBuffer.Length - _unexaminedBuffer.Length;
        _currentBuffer = result.Buffer;
        _uncomsumedBuffer = result.Buffer;
        _unexaminedBuffer = result.Buffer.Slice(examined);
        _isInnerReaderCompleted = result.IsCompleted;
        _isLastReadCanceled = result.IsCanceled;
    }
}
