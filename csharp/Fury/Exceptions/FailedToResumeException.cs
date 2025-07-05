using System;
using System.Diagnostics.CodeAnalysis;

namespace Fury;

public sealed class FailedToResumeException(string? message = null, Exception? innerException = null)
    : Exception(message, innerException);

internal static partial class ThrowHelper
{
    [DoesNotReturn]
    public static void ThrowFailedToResumeException_ExceptionWasThrownDuringResuming(Exception innerException)
    {
        throw new FailedToResumeException("An exception was thrown during resuming.", innerException);
    }
}
