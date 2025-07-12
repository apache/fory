using System;
using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.IO.Pipelines;
using System.Threading;
using System.Threading.Tasks;
using Fury.Buffers;
using Fury.Context;
using Fury.Meta;
using Fury.Serialization;
using Fury.Serialization.Meta;
using JetBrains.Annotations;

namespace Fury;

[MustDisposeResource]
public sealed class Fury : IDisposable
{
    private const int MaxRetainedPoolSize = 16;

    private readonly MetaStringStorage _metaStringStorage = new();
    private readonly TypeRegistry _typeRegistry;
    private readonly ObjectPool<SerializationWriter> _writerPool;
    private readonly ObjectPool<DeserializationReader> _readerPool;
    private readonly ObjectPool<HeaderSerializer> _headerSerializerPool = new(
        () => new HeaderSerializer(),
        MaxRetainedPoolSize
    );
    private readonly ObjectPool<HeaderDeserializer> _headerDeserializerPool = new(
        () => new HeaderDeserializer(),
        MaxRetainedPoolSize
    );

    public Fury(FuryConfig config)
    {
        _typeRegistry = new TypeRegistry(_metaStringStorage, config.RegistrationProvider, config.LockTimeOut);
        _writerPool = new ObjectPool<SerializationWriter>(
            () => new SerializationWriter(_typeRegistry),
            MaxRetainedPoolSize
        );
        _readerPool = new ObjectPool<DeserializationReader>(
            () => new DeserializationReader(_typeRegistry, _metaStringStorage),
            MaxRetainedPoolSize
        );
    }

    public void Dispose()
    {
        _typeRegistry.Dispose();
        _writerPool.Dispose();
        _readerPool.Dispose();
        _headerSerializerPool.Dispose();
        _headerDeserializerPool.Dispose();
    }

    public SerializationResult Serialize<T>(
        PipeWriter writer,
        in T? value,
        SerializationConfig config,
        TypeRegistration? registrationHint = null
    )
        where T : notnull
    {
        var serializationWriter = _writerPool.Rent();
        serializationWriter.Initialize(writer, config);
        var uncompletedResult = SerializationResult.FromUncompleted(serializationWriter, registrationHint);
        return ContinueSerialize(uncompletedResult, in value);
    }

    public SerializationResult Serialize<T>(
        PipeWriter writer,
        in T? value,
        SerializationConfig config,
        TypeRegistration? registrationHint = null
    )
        where T : struct
    {
        var serializationWriter = _writerPool.Rent();
        serializationWriter.Initialize(writer, config);
        var uncompletedResult = SerializationResult.FromUncompleted(serializationWriter, registrationHint);
        return ContinueSerialize(uncompletedResult, in value);
    }

    // To avoid unnecessary copying, we let the caller provide the value again rather than
    // storing it in the SerializationResult.

    public SerializationResult ContinueSerialize<T>(SerializationResult uncompletedResult, in T? value)
        where T : notnull
    {
        if (uncompletedResult.IsCompleted)
        {
            ThrowInvalidOperationException_SerializationCompleted();
        }

        var completedOrFailed = false;
        var writer = uncompletedResult.Writer;
        Debug.Assert(writer is not null);
        try
        {
            if (!writer.WriteHeader(value is null))
            {
                return uncompletedResult;
            }

            if (value is not null && !writer.Write(in value, uncompletedResult.RootTypeRegistrationHint))
            {
                return uncompletedResult;
            }

            completedOrFailed = true;
            return SerializationResult.Completed;
        }
        catch (Exception)
        {
            completedOrFailed = true;
            throw;
        }
        finally
        {
            if (completedOrFailed)
            {
                writer.Reset();
                _writerPool.Return(writer);
            }
        }
    }

    public SerializationResult ContinueSerialize<T>(SerializationResult uncompletedResult, in T? value)
        where T : struct
    {
        if (uncompletedResult.IsCompleted)
        {
            ThrowInvalidOperationException_SerializationCompleted();
        }

        var completedOrFailed = false;
        var writer = uncompletedResult.Writer;
        Debug.Assert(writer is not null);
        try
        {
            if (!writer.WriteHeader(value is null))
            {
                return uncompletedResult;
            }

            if (value is not null && !writer.Write(value.Value, uncompletedResult.RootTypeRegistrationHint))
            {
                return uncompletedResult;
            }

            completedOrFailed = true;
            return SerializationResult.Completed;
        }
        catch (Exception)
        {
            completedOrFailed = true;
            throw;
        }
        finally
        {
            if (completedOrFailed)
            {
                writer.Reset();
                _writerPool.Return(writer);
            }
        }
    }

    public DeserializationResult<T> Deserialize<T>(
        PipeReader reader,
        DeserializationConfig config,
        TypeRegistration? registrationHint = null
    )
        where T : notnull
    {
        var serializationReader = _readerPool.Rent();
        serializationReader.Initialize(reader, config);
        var uncompletedResult = DeserializationResult<T>.FromUncompleted(serializationReader, registrationHint);
        var task = Deserialize(uncompletedResult, false, CancellationToken.None);
        Debug.Assert(task.IsCompleted);
        return task.Result;
    }

    public DeserializationResult<T?> DeserializeNullable<T>(
        PipeReader reader,
        DeserializationConfig config,
        TypeRegistration? registrationHint = null
    )
        where T : struct
    {
        var serializationReader = _readerPool.Rent();
        serializationReader.Initialize(reader, config);
        var uncompletedResult = DeserializationResult<T?>.FromUncompleted(serializationReader, registrationHint);
        var task = DeserializeNullable(uncompletedResult, false, CancellationToken.None);
        Debug.Assert(task.IsCompleted);
        return task.Result;
    }

    public ValueTask<DeserializationResult<T>> DeserializeAsync<T>(
        PipeReader reader,
        DeserializationConfig config,
        TypeRegistration? registrationHint = null,
        CancellationToken cancellationToken = default
    )
        where T : notnull
    {
        var serializationReader = _readerPool.Rent();
        serializationReader.Initialize(reader, config);
        var uncompletedResult = DeserializationResult<T>.FromUncompleted(serializationReader, registrationHint);
        return Deserialize(uncompletedResult, true, cancellationToken);
    }

    public ValueTask<DeserializationResult<T?>> DeserializeNullableAsync<T>(
        PipeReader reader,
        DeserializationConfig config,
        TypeRegistration? registrationHint = null,
        CancellationToken cancellationToken = default
    )
        where T : struct
    {
        var serializationReader = _readerPool.Rent();
        serializationReader.Initialize(reader, config);
        var uncompletedResult = DeserializationResult<T?>.FromUncompleted(serializationReader, registrationHint);
        return DeserializeNullable(uncompletedResult, true, cancellationToken);
    }

    public DeserializationResult<T> ContinueDeserialize<T>(DeserializationResult<T> uncompletedResult)
        where T : notnull
    {
        var task = Deserialize(uncompletedResult, false, CancellationToken.None);
        Debug.Assert(task.IsCompleted);
        return task.Result;
    }

    public DeserializationResult<T?> ContinueDeserializeNullable<T>(DeserializationResult<T?> uncompletedResult)
        where T : struct
    {
        var task = DeserializeNullable(uncompletedResult, false, CancellationToken.None);
        Debug.Assert(task.IsCompleted);
        return task.Result;
    }

    public ValueTask<DeserializationResult<T>> ContinueDeserializeAsync<T>(
        DeserializationResult<T> uncompletedResult,
        CancellationToken cancellationToken
    )
        where T : notnull
    {
        return Deserialize(uncompletedResult, true, cancellationToken);
    }

    public ValueTask<DeserializationResult<T?>> ContinueDeserializeNullableAsync<T>(
        DeserializationResult<T?> uncompletedResult,
        CancellationToken cancellationToken
    )
        where T : struct
    {
        return DeserializeNullable(uncompletedResult, true, cancellationToken);
    }

    private async ValueTask<DeserializationResult<T>> Deserialize<T>(
        DeserializationResult<T> uncompletedResult,
        bool isAsync,
        CancellationToken cancellationToken
    )
        where T : notnull
    {
        if (uncompletedResult.IsCompleted)
        {
            ThrowInvalidOperationException_DeserializationCompleted();
        }

        var completedOrFailed = false;
        var reader = uncompletedResult.Reader;
        Debug.Assert(reader is not null);
        try
        {
            var headerResult = await reader.ReadHeader(isAsync, cancellationToken);
            if (!headerResult.IsSuccess)
            {
                return uncompletedResult;
            }

            var rootObjectIsNull = headerResult.Value;
            if (rootObjectIsNull)
            {
                return DeserializationResult<T>.FromValue(default);
            }
            var deserializationResult = await reader.Read<T>(
                uncompletedResult.RootTypeRegistrationHint,
                ObjectMetaOption.ReferenceMeta | ObjectMetaOption.TypeMeta,
                isAsync,
                cancellationToken
            );
            if (!deserializationResult.IsSuccess)
            {
                return uncompletedResult;
            }

            completedOrFailed = true;
            return DeserializationResult<T>.FromValue(deserializationResult.Value);
        }
        catch (Exception)
        {
            completedOrFailed = true;
            throw;
        }
        finally
        {
            if (completedOrFailed)
            {
                reader.Reset();
                _readerPool.Return(reader);
            }
        }
    }

    private async ValueTask<DeserializationResult<T?>> DeserializeNullable<T>(
        DeserializationResult<T?> uncompletedResult,
        bool isAsync,
        CancellationToken cancellationToken
    )
        where T : struct
    {
        if (uncompletedResult.IsCompleted)
        {
            ThrowInvalidOperationException_DeserializationCompleted();
        }

        var completedOrFailed = false;
        var reader = uncompletedResult.Reader;
        Debug.Assert(reader is not null);
        try
        {
            var headerResult = await reader.ReadHeader(isAsync, cancellationToken);
            if (!headerResult.IsSuccess)
            {
                return uncompletedResult;
            }

            var rootObjectIsNull = headerResult.Value;
            if (rootObjectIsNull)
            {
                return DeserializationResult<T?>.FromValue(null);
            }
            var deserializationResult = await reader.ReadNullable<T>(
                uncompletedResult.RootTypeRegistrationHint,
                ObjectMetaOption.ReferenceMeta | ObjectMetaOption.TypeMeta,
                isAsync,
                cancellationToken
            );
            if (!deserializationResult.IsSuccess)
            {
                return uncompletedResult;
            }

            completedOrFailed = true;
            return DeserializationResult<T?>.FromValue(deserializationResult.Value);
        }
        catch (Exception)
        {
            completedOrFailed = true;
            throw;
        }
        finally
        {
            if (completedOrFailed)
            {
                reader.Reset();
                _readerPool.Return(reader);
            }
        }
    }

    [DoesNotReturn]
    private void ThrowInvalidOperationException_SerializationCompleted()
    {
        throw new InvalidOperationException("Serialization is already completed.");
    }

    [DoesNotReturn]
    private void ThrowInvalidOperationException_DeserializationCompleted()
    {
        throw new InvalidOperationException("Deserialization is already completed.");
    }

    #region Register methods

    /// <inheritdoc cref="TypeRegistry.Register(Type, Func{ISerializer}, Func{IDeserializer})"/>
    public TypeRegistration Register(
        Type targetType,
        Func<ISerializer>? serializerFactory,
        Func<IDeserializer>? deserializerFactory
    ) => _typeRegistry.Register(targetType, serializerFactory, deserializerFactory);

    /// <inheritdoc cref="TypeRegistry.Register(Type, string, string, Func{ISerializer}, Func{IDeserializer})"/>
    public TypeRegistration Register(
        Type targetType,
        string? @namespace,
        string name,
        Func<ISerializer> serializerFactory,
        Func<IDeserializer> deserializerFactory
    ) => _typeRegistry.Register(targetType, @namespace, name, serializerFactory, deserializerFactory);

    /// <inheritdoc cref="TypeRegistry.Register(Type, TypeKind, Func{ISerializer}, Func{IDeserializer})"/>
    public TypeRegistration Register(
        Type targetType,
        TypeKind targetTypeKind,
        Func<ISerializer> serializerFactory,
        Func<IDeserializer> deserializerFactory
    ) => _typeRegistry.Register(targetType, targetTypeKind, serializerFactory, deserializerFactory);

    /// <inheritdoc cref="TypeRegistry.Register(Type, int, Func{ISerializer}, Func{IDeserializer})"/>
    public TypeRegistration Register(
        Type targetType,
        int id,
        Func<ISerializer> serializerFactory,
        Func<IDeserializer> deserializerFactory
    ) => _typeRegistry.Register(targetType, id, serializerFactory, deserializerFactory);

    /// <inheritdoc cref="TypeRegistry.Register(Type, TypeRegistration)"/>
    public void Register(Type declaredType, TypeRegistration registration) =>
        _typeRegistry.Register(declaredType, registration);

    #endregion
}
