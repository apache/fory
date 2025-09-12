using System;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;

namespace Fury;

public sealed class BadSerializationInputException(string? message = null) : Exception(message);

internal static partial class ThrowHelper
{
    [DoesNotReturn]
    public static void ThrowBadSerializationInputException(string? message = null)
    {
        throw new BadSerializationInputException(message);
    }

    [DoesNotReturn]
    public static TReturn ThrowBadSerializationInputException<TReturn>(string? message = null)
    {
        throw new BadSerializationInputException(message);
    }

    [DoesNotReturn]
    public static void ThrowBadSerializationInputException_UnsupportedMetaStringChar(char c)
    {
        throw new BadSerializationInputException($"Unsupported MetaString character: '{c}'");
    }

    [DoesNotReturn]
    public static void ThrowBadSerializationInputException_UnregisteredType(Type type)
    {
        throw new BadSerializationInputException($"Type '{type.FullName}' is not registered.");
    }

    [DoesNotReturn]
    public static void ThrowBadSerializationInputException_CircularDependencyDetected()
    {
        throw new BadSerializationInputException("Circular dependency detected.");
    }

    [DoesNotReturn]
    public static void ThrowBadSerializationInputException_NoSerializerFactoryProvider(Type targetType)
    {
        throw new BadSerializationInputException(
            $"Can not find an appropriate serializer factory provider for type '{targetType.FullName}'."
        );
    }

    [DoesNotReturn]
    public static void ThrowBadSerializationInputException_AttemptedToSerializeNullValueWhenResuming()
    {
        throw new BadSerializationInputException("Attempted to serialize a null value when resuming.");
    }

    [DoesNotReturn]
    public static void ThrowBadSerializationInputException_ObjectWithBuiltInSerializerAndCustomDeserializer(
        Type targetType
    )
    {
        throw new BadSerializationInputException(
            "Attempted to serialize an object of type '{targetType.FullName}' with a built-in serializer and a custom deserializer."
        );
    }
}
