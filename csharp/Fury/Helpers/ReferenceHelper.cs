using System.Reflection;
using System.Runtime.CompilerServices;

namespace Fury.Helpers;

internal static class ReferenceHelper
{
    public static readonly MethodInfo UnboxMethod = typeof(Unsafe).GetMethod(nameof(Unsafe.Unbox))!;

    public static ref T UnboxOrGetNullRef<T>(object value)
    {
        if (value is not T)
        {
            ThrowHelper.ThrowArgumentNullExceptionIfNull(in value, nameof(value));
        }

        if (ReferenceHelper<T>.Unbox is null)
        {
            return ref Unsafe.NullRef<T>();
        }

        return ref ReferenceHelper<T>.Unbox(value);
    }

    public static ref T UnboxOrGetInputRef<T>(ref object value)
    {
        if (value is not T)
        {
            ThrowHelper.ThrowArgumentNullExceptionIfNull(in value, nameof(value));
        }

        if (ReferenceHelper<T>.Unbox is null)
        {
            return ref Unsafe.As<object, T>(ref value);
        }

        return ref ReferenceHelper<T>.Unbox(value);
    }
}

file static class ReferenceHelper<T>
{
    internal delegate ref T UnboxDelegate(object box);

    internal static readonly UnboxDelegate? Unbox;

    static ReferenceHelper()
    {
        if (typeof(T).IsValueType)
        {
            Unbox = ReferenceHelper.UnboxMethod.MakeGenericMethod(typeof(T)).CreateDelegate<UnboxDelegate>();
        }
    }
}
