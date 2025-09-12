using Fury.Context;

namespace Fury;

public readonly struct SerializationResult
{
    public bool IsCompleted { get; private init; }

    internal SerializationWriter? Writer { get; private init; }
    internal TypeRegistration? RootTypeRegistrationHint { get; private init; }

    internal static SerializationResult Completed { get; } = new() { IsCompleted = true };

    internal static SerializationResult FromUncompleted(
        SerializationWriter writer,
        TypeRegistration? rootTypeRegistrationHint
    )
    {
        return new SerializationResult
        {
            IsCompleted = false,
            Writer = writer,
            RootTypeRegistrationHint = rootTypeRegistrationHint,
        };
    }
}

public readonly struct DeserializationResult<T>
{
    public bool IsCompleted { get; private init; }
    public T? Value { get; init; }
    internal DeserializationReader? Reader { get; private init; }
    internal TypeRegistration? RootTypeRegistrationHint { get; private init; }

    internal static DeserializationResult<T> FromValue(in T? value)
    {
        return new DeserializationResult<T>
        {
            IsCompleted = true,
            Reader = null,
            Value = value,
            RootTypeRegistrationHint = null,
        };
    }

    internal static DeserializationResult<T> FromUncompleted(
        DeserializationReader reader,
        TypeRegistration? rootTypeRegistrationHint
    )
    {
        return new DeserializationResult<T>
        {
            IsCompleted = false,
            Reader = reader,
            RootTypeRegistrationHint = rootTypeRegistrationHint,
            Value = default,
        };
    }
}
