using System;
using System.Buffers;
using System.Runtime.InteropServices;

namespace Fury.Buffers;

internal sealed class UnmanagedToByteArrayMemoryManager<TElement>(TElement[] array) : MemoryManager<byte>
    where TElement : unmanaged
{
    protected override void Dispose(bool disposing) { }

    public override Span<byte> GetSpan()
    {
        return MemoryMarshal.AsBytes(array.AsSpan());
    }

    public override MemoryHandle Pin(int elementIndex = 0)
    {
        throw new InvalidOperationException();
    }

    public override void Unpin()
    {
        throw new InvalidOperationException();
    }
}
