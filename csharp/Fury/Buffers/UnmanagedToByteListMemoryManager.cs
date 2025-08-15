using System;
using System.Buffers;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace Fury.Buffers;

#if NET5_0_OR_GREATER
internal sealed class UnmanagedToByteListMemoryManager<TElement> : MemoryManager<byte>
    where TElement : unmanaged
{
    public List<TElement>? List;

    protected override void Dispose(bool disposing) { }

    public override Span<byte> GetSpan()
    {
        return MemoryMarshal.AsBytes(CollectionsMarshal.AsSpan(List));
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
#endif
