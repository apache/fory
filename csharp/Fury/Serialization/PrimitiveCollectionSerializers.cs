using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using Fury.Buffers;
using Fury.Context;
using Fury.Helpers;

namespace Fury.Serialization;

// For primitive arrays, the length is the byte size of the array rather than the number of elements.

internal sealed class PrimitiveArraySerializer<TElement> : AbstractSerializer<TElement[]>
    where TElement : unmanaged
{
    private bool _hasWrittenLength;
    private int _writtenByteCount;

    public override void Reset()
    {
        _hasWrittenLength = false;
        _writtenByteCount = 0;
    }

    public override bool Serialize(SerializationWriter writer, in TElement[] value)
    {
        var writerRef = writer.ByrefWriter;
        var bytes = MemoryMarshal.AsBytes(value.AsSpan()).Slice(_writtenByteCount);
        var byteCount = bytes.Length;
        if (_hasWrittenLength)
        {
            _hasWrittenLength = writerRef.Write7BitEncodedUInt32((uint)byteCount);
            if (_hasWrittenLength)
            {
                return false;
            }
        }

        var buffer = writerRef.GetSpan(byteCount);
        var consumed = bytes.CopyUpTo(buffer);
        _writtenByteCount += consumed;
        writerRef.Advance(consumed);
        Debug.Assert(_writtenByteCount <= byteCount);
        return _writtenByteCount == byteCount;
    }
}

internal sealed class PrimitiveArrayDeserializer<TElement> : AbstractDeserializer<TElement[]>
    where TElement : unmanaged
{
    private static readonly int ElementSize = Unsafe.SizeOf<TElement>();

    private TElement[]? _array;
    private int _readByteCount;

    public override void Reset()
    {
        _array = null;
        _readByteCount = 0;
    }

    public override object ReferenceableObject => ThrowInvalidOperationException_AcyclicType();

    public override ReadValueResult<TElement[]> Deserialize(DeserializationReader reader)
    {
        var task = Deserialize(reader, false, CancellationToken.None);
        Debug.Assert(task.IsCompleted);
        return task.Result;
    }

    public override ValueTask<ReadValueResult<TElement[]>> DeserializeAsync(DeserializationReader reader, CancellationToken cancellationToken = default)
    {
        return Deserialize(reader, true, cancellationToken);
    }

    private async ValueTask<ReadValueResult<TElement[]>> Deserialize(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        if (_array is null)
        {
            var byteCountResult = await reader.Read7BitEncodedUint(isAsync, cancellationToken);
            if (!byteCountResult.IsSuccess)
            {
                return ReadValueResult<TElement[]>.Failed;
            }

            var byteCount = (int)byteCountResult.Value;
            if (byteCount % ElementSize != 0)
            {
                ThrowBadDeserializationInputException_InvalidByteCount(byteCount);
                return ReadValueResult<TElement[]>.Failed;
            }

            _array = new TElement[byteCount / ElementSize];
        }

        var totalByteCount = _array.Length * ElementSize;

        if (isAsync)
        {
            var memoryManager = new UnmanagedToByteArrayMemoryManager<TElement>(_array);
            var destination = memoryManager.Memory.Slice(_readByteCount);
            _readByteCount += await reader.ReadBytesAsync(destination, cancellationToken);
        }
        else
        {
            var destination = MemoryMarshal.AsBytes(_array.AsSpan()).Slice(_readByteCount);
            _readByteCount += reader.ReadBytes(destination);
        }
        Debug.Assert(_readByteCount <= totalByteCount);

        if (_readByteCount != totalByteCount)
        {
            return ReadValueResult<TElement[]>.Failed;
        }

        return ReadValueResult<TElement[]>.FromValue(_array);
    }

    [DoesNotReturn]
    private static void ThrowBadDeserializationInputException_InvalidByteCount(int byteCount)
    {
        throw new BadDeserializationInputException($"Invalid byte count: {byteCount}. Expected a multiple of {ElementSize}.");
    }
}

#if NET5_0_OR_GREATER
internal sealed class PrimitiveListSerializer<TElement>(TypeRegistration elementRegistration)
    : CollectionSerializer<TElement, List<TElement>>(elementRegistration)
    where TElement : unmanaged
{
    private int _writtenByteCount;

    public override void Reset()
    {
        base.Reset();
        _writtenByteCount = 0;
    }

    protected override int GetCount(in List<TElement> collection)
    {
        return collection.Count;
    }

    protected override bool WriteElements(ref SerializationWriterRef writerRef, in List<TElement> collection)
    {
        var bytes = MemoryMarshal.AsBytes(CollectionsMarshal.AsSpan(collection));
        _writtenByteCount += writerRef.WriteBytes(bytes.Slice(_writtenByteCount));
        return _writtenByteCount == bytes.Length;
    }

    protected override CollectionCheckResult CheckElementsState(in List<TElement> collection, CollectionCheckOptions options)
    {
        return new CollectionCheckResult(false, typeof(TElement));
    }
}
#endif

#if NET8_0_OR_GREATER
internal sealed class PrimitiveListDeserializer<TElement>(TypeRegistration elementRegistration)
    : CollectionDeserializer<TElement, List<TElement>>(elementRegistration)
    where TElement : unmanaged
{
    private static readonly int ElementSize = Unsafe.SizeOf<TElement>();

    private int _readByteCount;
    private int _totalByteCount;

    private readonly UnmanagedToByteListMemoryManager<TElement> _listMemoryManager = new();
    private Memory<byte> ByteMemory => _listMemoryManager.Memory;

    public override void Reset()
    {
        base.Reset();
        _readByteCount = 0;
        _totalByteCount = 0;
    }

    protected override void CreateCollection(int count)
    {
        _totalByteCount = count * ElementSize;
        Collection = new List<TElement>(count);
        CollectionsMarshal.SetCount(Collection, count);
        _listMemoryManager.List = Collection;
    }

    protected override bool ReadElements(DeserializationReader reader)
    {
        var destination = MemoryMarshal.AsBytes(CollectionsMarshal.AsSpan(Collection)).Slice(_readByteCount);
        _readByteCount += reader.ReadBytes(destination);
        return _readByteCount == _totalByteCount;
    }

    protected override async ValueTask<bool> ReadElementsAsync(DeserializationReader reader, CancellationToken cancellationToken)
    {
        var destination = ByteMemory.Slice(_readByteCount);
        _readByteCount += await reader.ReadBytesAsync(destination, cancellationToken);
        return _readByteCount == _totalByteCount;
    }
}
#endif
