#if !NET5_0_OR_GREATER
using System;
using System.Reflection;

namespace Fury;

internal static class MethodInfoExtensions
{

    /// <summary>Creates a delegate of the given type 'T' from this method.</summary>
    public static T CreateDelegate<T>(this MethodInfo methodInfo) where T : Delegate => (T)methodInfo.CreateDelegate(typeof(T));
}
#endif
