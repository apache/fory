using System;
using System.Diagnostics.CodeAnalysis;
using JetBrains.Annotations;

namespace Fury;

internal static partial class ThrowHelper
{
    [DoesNotReturn]
    public static void ThrowInvalidOperationException(string? message = null)
    {
        throw new InvalidOperationException(message);
    }

    [DoesNotReturn]
    public static void ThrowArgumentException(string? message = null, string? paramName = null)
    {
        throw new ArgumentException(message, paramName);
    }

    [DoesNotReturn]
    public static void ThrowArgumentNullException([InvokerParameterName] string? paramName = null)
    {
        throw new ArgumentNullException(paramName);
    }

    public static void ThrowArgumentNullExceptionIfNull<T>(in T value, [InvokerParameterName] string? paramName = null)
    {
        if (value is null)
        {
            throw new ArgumentNullException(paramName);
        }
    }

    [DoesNotReturn]
    public static void ThrowArgumentOutOfRangeException(
        string paramName,
        object? actualValue = null,
        string? message = null
    )
    {
        throw new ArgumentOutOfRangeException(paramName, actualValue, message);
    }

    public static void ThrowArgumentOutOfRangeExceptionIfNegative(
        int value,
        [InvokerParameterName]string paramName,
        string? message = null
    )
    {
        if (value < 0)
        {
            throw new ArgumentOutOfRangeException(paramName, value, message);
        }
    }

    [DoesNotReturn]
    public static void ThrowIndexOutOfRangeException()
    {
        throw new IndexOutOfRangeException();
    }
}
