#if DEBUG
using System.Collections.Generic;
using Fury.Context;
using JetBrains.Annotations;

namespace Fury;

/// <summary>
/// Macros for rider templates.
/// </summary>
internal static class Macros
{
    // These code are used in development time to generate similar code.
    // They should not be depended on at runtime.

    [UsedImplicitly]
    [SourceTemplate, Macro(Target = nameof(TTarget), Expression = "guessExpectedType()")]
    internal static void GetRegistrationIfPossible<TTarget>(this TypeRegistration? registration)
    {
        /*$
        if ($registration$ is null && TypeHelper<$TTarget$>.IsSealed)
        {
            $registration$ = context.TypeRegistry.GetOrRegisterType(typeof($TTarget$));
        }
        */
    }

    [UsedImplicitly]
    [SourceTemplate]
    internal static void GetValueRefOrAddDefault<TKey, TValue>(
        this IDictionary<TKey, TValue> dictionary,
        TKey key,
        TValue value,
        TValue newValue
    )
    {
        /*$
#if NET8_0_OR_GREATER
        ref var $value$ = ref CollectionsMarshal.GetValueRefOrAddDefault(dictionary, $key$, out var exists);
#else
        var exists = dictionary.TryGetValue($key$, out var $value$);
#endif
        if (!exists)
        {
            $value$ = $newValue$;
#if !NET8_0_OR_GREATER
            dictionary.Add($key$, $value$);
#endif
        }
        */
    }
}
#endif
