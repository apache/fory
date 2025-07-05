using System;

namespace Fury;

internal static partial class ThrowHelper
{
    public static void ThrowOutOfMemoryException_BufferMaximumSizeExceeded(uint needed)
    {
        throw new OutOfMemoryException($"Cannot allocate a buffer of size {needed}.");
    }
}
