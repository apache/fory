using System;
using System.Diagnostics.CodeAnalysis;

namespace Fury;

internal static partial class ThrowHelper
{
    [DoesNotReturn]
    public static void ThrowInvalidCastException_CannotCastFromTo(Type fromType, Type toType)
    {
        throw new InvalidCastException($"Cannot cast from {fromType} to {toType}.");
    }
}
