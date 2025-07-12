using System.Runtime.CompilerServices;
using System.Threading;
using System.Threading.Tasks;
using Fury.Context;

namespace Fury.Serialization;

internal sealed class PrimitiveSerializer<T> : AbstractSerializer<T>
    where T : unmanaged
{
    public static PrimitiveSerializer<T> Instance { get; } = new();

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public override bool Serialize(SerializationWriter writer, in T value)
    {
        return writer.WriteUnmanaged(value);
    }

    public override void Reset() { }
}

internal sealed class PrimitiveDeserializer<T> : AbstractDeserializer<T>
    where T : unmanaged
{
    public static PrimitiveDeserializer<T> Instance { get; } = new();

    private static readonly int Size = Unsafe.SizeOf<T>();

    public override object ReferenceableObject => ThrowInvalidOperationException_AcyclicType();

    public override ReadValueResult<T> Deserialize(DeserializationReader reader)
    {
        return reader.ReadUnmanagedAs<T>(Size);
    }

    public override async ValueTask<ReadValueResult<T>> DeserializeAsync(
        DeserializationReader reader,
        CancellationToken cancellationToken = default
    )
    {
        return await reader.ReadUnmanagedAsAsync<T>(Size, cancellationToken);
    }

    public override void Reset() { }
}
