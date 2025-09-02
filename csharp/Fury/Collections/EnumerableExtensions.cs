using System;
using System.Collections;
using System.Collections.Generic;
using JetBrains.Annotations;
#if NET8_0_OR_GREATER
using System.Collections.Immutable;
using System.Runtime.InteropServices;
#endif

namespace Fury.Collections;

internal static class EnumerableExtensions
{
#if !NET8_0_OR_GREATER
    public static bool TryGetNonEnumeratedCount<T>([NoEnumeration] this IEnumerable<T> enumerable, out int count)
    {
        switch (enumerable)
        {
            case ICollection<T> typedCollection:
                count = typedCollection.Count;
                return true;
            case ICollection collection:
                count = collection.Count;
                return true;
            case IReadOnlyCollection<T> readOnlyCollection:
                count = readOnlyCollection.Count;
                return true;
            default:
                count = 0;
                return false;
        }
    }
#endif
    public static bool TryGetSpan<T>([NoEnumeration] this IEnumerable<T> enumerable, out Span<T> span)
    {
        switch (enumerable)
        {
            case T[] elements:
                span = elements;
                return true;
#if NET8_0_OR_GREATER
            case List<T> elements:
                span = CollectionsMarshal.AsSpan(elements);
                return true;
            case ImmutableArray<T> elements:
                span = ImmutableCollectionsMarshal.AsArray(elements);
                return true;
#endif
            case PooledList<T> elements:
                span = elements.AsSpan();
                return true;
            default:
                span = Span<T>.Empty;
                return false;
        }
    }
}
