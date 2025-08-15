using System;
using System.Diagnostics.CodeAnalysis;
using System.Runtime.CompilerServices;
using System.Threading;
using System.Threading.Tasks;
using Fury.Context;
using Fury.Helpers;

namespace Fury.Serialization;

public abstract class AbstractSerializer<TTarget> : ISerializer<TTarget>
    where TTarget : notnull
{
    public abstract bool Serialize(SerializationWriter writer, in TTarget value);

    public virtual bool Serialize(SerializationWriter writer, object value)
    {
        ref var valueRef = ref ReferenceHelper.UnboxOrGetNullRef<TTarget>(value);

        bool completed;
        if (Unsafe.IsNullRef(ref valueRef))
        {
            completed = Serialize(writer, (TTarget)value);
        }
        else
        {
            completed = Serialize(writer, in valueRef);
        }

        return completed;
    }

    public abstract void Reset();

    public virtual void Dispose() { }
}

public abstract class AbstractDeserializer<TTarget> : IDeserializer<TTarget>
    where TTarget : notnull
{
    public abstract object ReferenceableObject { get; }

    public abstract ReadValueResult<TTarget> Deserialize(DeserializationReader reader);
    public abstract ValueTask<ReadValueResult<TTarget>> DeserializeAsync(
        DeserializationReader reader,
        CancellationToken cancellationToken = default
    );

    ReadValueResult<object> IDeserializer.Deserialize(DeserializationReader reader)
    {
        var typedResult = Deserialize(reader);
        if (!typedResult.IsSuccess)
        {
            return ReadValueResult<object>.Failed;
        }
        return ReadValueResult<object>.FromValue(typedResult.Value);
    }

    async ValueTask<ReadValueResult<object>> IDeserializer.DeserializeAsync(
        DeserializationReader reader,
        CancellationToken cancellationToken
    )
    {
        var typedResult = await DeserializeAsync(reader, cancellationToken);
        if (!typedResult.IsSuccess)
        {
            return ReadValueResult<object>.Failed;
        }
        return ReadValueResult<object>.FromValue(typedResult.Value);
    }

    public abstract void Reset();

    public virtual void Dispose() { }

    [DoesNotReturn]
    private protected static void ThrowInvalidOperationException_ObjectNotCreated()
    {
        throw new InvalidOperationException("Attempted to get the deserialized object before it was created.");
    }

    private protected static object ThrowInvalidOperationException_AcyclicType()
    {
        throw new InvalidOperationException(
            $"Attempted to get a referenceable object of type {typeof(TTarget).Name}. "
                + $"This type should not contain circular references."
        );
    }
}
