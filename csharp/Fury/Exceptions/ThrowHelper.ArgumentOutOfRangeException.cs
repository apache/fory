using System;
using System.Diagnostics.CodeAnalysis;
using JetBrains.Annotations;

namespace Fury;

internal static partial class ThrowHelper
{

    [DoesNotReturn]
    public static void ThrowArgumentOutOfRangeException_AttemptedToAdvanceFurtherThanBufferLength(
        string paramName,
        int bufferLength,
        int advanceLength
    )
    {
        throw new ArgumentOutOfRangeException(
            paramName,
            $"Attempted to advance further than the buffer length. Buffer length: {bufferLength}, Advance length: {advanceLength}"
        );
    }
}
