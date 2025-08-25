using System;
using System.Buffers;

namespace Fury.Helpers;

internal static class StringHelper
{
    public static string Create<TState>(int length, in TState state, SpanAction<char, TState> action)
    {
        if (length == 0)
        {
            return string.Empty;
        }
#if NET5_0_OR_GREATER || NETSTANDARD2_1
        return string.Create(length, state, action);
#else
        var result = new string(' ', length);
        unsafe
        {
            fixed (char* pChar = result)
            {
                var chars = new Span<char>(pChar, result.Length);
                action(chars, state);
            }
        }

        return result;
#endif
    }

    public static string ToFullName(string? ns, string? name)
    {
        name = ToStringOrNull(name);
        if (string.IsNullOrWhiteSpace(ns))
        {
            return name;
        }
        return ns + "." + name;
    }

    public static bool AreStringsEqualOrEmpty(string? str1, string? str2)
    {
        return string.IsNullOrEmpty(str1) && string.IsNullOrEmpty(str2) || str1 == str2;
    }

    public static string ToStringOrNull<T>(in T obj)
    {
        return obj?.ToString() ?? "null";
    }
}
