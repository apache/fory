using System;
using System.Diagnostics.CodeAnalysis;
using System.Threading;
using System.Threading.Tasks;
using Fury.Context;

namespace Fury.Serialization;

public sealed class NotSupportedSerializer(Type targetType) : ISerializer
{
    public void Dispose()
    {
        ThrowNotSupportedException();
    }

    public bool Serialize(SerializationWriter writer, object value)
    {
        ThrowNotSupportedException();
        return false;
    }

    public void Reset()
    {
        ThrowNotSupportedException();
    }

    [DoesNotReturn]
    private void ThrowNotSupportedException()
    {
        throw new NotSupportedException($"Serialization of type {targetType} is not supported.");
    }
}

public sealed class NotSupportedDeserializer(Type targetType) : IDeserializer
{
    public void Dispose()
    {
        ThrowNotSupportedException();
    }

    public object ReferenceableObject
    {
        get
        {
            ThrowNotSupportedException();
            return null!;
        }
    }

    public ReadValueResult<object> Deserialize(DeserializationReader reader)
    {
        ThrowNotSupportedException();
        return default;
    }

    public ValueTask<ReadValueResult<object>> DeserializeAsync(
        DeserializationReader reader,
        CancellationToken cancellationToken = default
    )
    {
        ThrowNotSupportedException();
        return default;
    }

    public void Reset()
    {
        ThrowNotSupportedException();
    }

    [DoesNotReturn]
    private void ThrowNotSupportedException()
    {
        throw new NotSupportedException($"Deserialization of type {targetType} is not supported.");
    }
}
