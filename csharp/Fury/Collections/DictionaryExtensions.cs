using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace Fury.Collections;

internal static class DictionaryExtensions
{
    public static TValue GetOrAdd<TKey, TValue>(
        this Dictionary<TKey, TValue> dictionary,
        TKey key,
        TValue value,
        out bool exists
    )
        where TKey : notnull
        where TValue : notnull
    {
#if NET8_0_OR_GREATER
        ref var existingValue = ref CollectionsMarshal.GetValueRefOrAddDefault(dictionary, key, out exists);
#else
        exists = dictionary.TryGetValue(key, out var existingValue);
#endif
        if (exists)
        {
            return existingValue!;
        }
#if NET8_0_OR_GREATER
        existingValue = value;
#else
        dictionary[key] = value;
#endif
        return value;
    }

    public static TValue GetOrAdd<TKey, TValue, TArg>(
        this Dictionary<TKey, TValue> dictionary,
        TKey key,
        Func<TKey, TArg, TValue> factory,
        in TArg arg,
        out bool exists
    )
        where TKey : notnull
        where TValue : notnull
    {
#if NET8_0_OR_GREATER
        ref var existingValue = ref CollectionsMarshal.GetValueRefOrAddDefault(dictionary, key, out exists);
#else
        exists = dictionary.TryGetValue(key, out var existingValue);
#endif
        if (!exists)
        {
            existingValue = factory(key, arg);
#if !NET8_0_OR_GREATER
            dictionary[key] = existingValue;
#endif
        }
        return existingValue!;
    }

    public static TValue GetOrAdd<TKey, TValue>(
        this Dictionary<TKey, TValue> dictionary,
        TKey key,
        Func<TKey, TValue> factory,
        out bool exists
    )
        where TKey : notnull
        where TValue : notnull
    {
#if NET8_0_OR_GREATER
        ref var existingValue = ref CollectionsMarshal.GetValueRefOrAddDefault(dictionary, key, out exists);
#else
        exists = dictionary.TryGetValue(key, out var existingValue);
#endif
        if (!exists)
        {
            existingValue = factory(key);
#if !NET8_0_OR_GREATER
            dictionary[key] = existingValue;
#endif
        }
        return existingValue!;
    }

    public static TValue AddAndModify<TKey, TValue, TState>(
        this Dictionary<TKey, TValue> dictionary,
        TKey key,
        Func<TKey, TValue?, TState, TValue> modifier,
        in TState state,
        out bool exists
    )
        where TKey : notnull
        where TValue : notnull
    {
#if NET8_0_OR_GREATER
        ref var existingValue = ref CollectionsMarshal.GetValueRefOrAddDefault(dictionary, key, out exists);
        existingValue = modifier(key, existingValue, state);
#else
        exists = dictionary.TryGetValue(key, out var existingValue);
        existingValue = modifier(key, existingValue, state);
        dictionary[key] = existingValue;
#endif
        return existingValue;
    }
}
