using System;
using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using Fury.Buffers;
using Fury.Meta;
using Fury.Serialization;

namespace Fury.Context;

public sealed class TypeRegistration
{
    private readonly ObjectPool<ISerializer>? _serializerPool;
    private readonly ObjectPool<IDeserializer>? _deserializerPool;

    public Func<ISerializer>? SerializerFactory { get; }

    public Func<IDeserializer>? DeserializerFactory { get; }

    internal MetaString? NamespaceMetaString { get; }
    internal MetaString? NameMetaString { get; }

    public string? Namespace => NamespaceMetaString?.Value;
    public string? Name => NameMetaString?.Value;

    public Type TargetType { get; }
    public TypeKind? TypeKind { get; }
    public int? Id { get; }
    internal InternalTypeKind InternalTypeKind { get; }

    internal TypeRegistration(
        Type targetType,
        InternalTypeKind internalTypeKind,
        MetaString? ns,
        MetaString? name,
        int? id,
        Func<ISerializer>? serializerFactory,
        Func<IDeserializer>? deserializerFactory
    )
    {
        TargetType = targetType;

        SerializerFactory = serializerFactory;
        DeserializerFactory = deserializerFactory;
        if (serializerFactory is not null)
        {
            _serializerPool = new ObjectPool<ISerializer>(serializerFactory);
        }

        if (deserializerFactory is not null)
        {
            _deserializerPool = new ObjectPool<IDeserializer>(deserializerFactory);
        }

        NamespaceMetaString = ns;
        NameMetaString = name;

        Id = id;

        InternalTypeKind = internalTypeKind;
        if (internalTypeKind.TryToBePublic(out var typeKind))
        {
            TypeKind = typeKind;
        }
        else
        {
            TypeKind = null;
        }
    }

    internal ISerializer RentSerializer()
    {
        if (_serializerPool is null)
        {
            ThrowInvalidOperationException_NoSerializerPool();
        }
        return _serializerPool!.Rent();
    }

    internal void ReturnSerializer(ISerializer serializer)
    {
        if (_serializerPool is null)
        {
            ThrowInvalidOperationException_NoSerializerPool();
        }
        _serializerPool!.Return(serializer);
    }

    internal IDeserializer RentDeserializer()
    {
        if (_deserializerPool is null)
        {
            ThrowInvalidOperationException_NoDeserializerPool();
        }
        return _deserializerPool!.Rent();
    }

    internal void ReturnDeserializer(IDeserializer deserializer)
    {
        if (_deserializerPool is null)
        {
            ThrowInvalidOperationException_NoDeserializerPool();
        }
        _deserializerPool!.Return(deserializer);
    }

    [DoesNotReturn]
    private void ThrowInvalidOperationException_NoSerializerPool()
    {
        throw new InvalidOperationException($"Can not get serializer for type '{TargetType}'.");
    }

    [DoesNotReturn]
    private void ThrowInvalidOperationException_NoDeserializerPool()
    {
        throw new InvalidOperationException($"Can not get deserializer for type '{TargetType}'.");
    }
}
