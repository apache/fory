using System.Collections.Generic;
using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.Threading;
using System.Threading.Tasks;
using Fury.Collections;
using Fury.Context;
using Fury.Meta;

namespace Fury.Serialization.Meta;

internal sealed class ReferenceMetaSerializer
{
    private bool _referenceTracking;

    private readonly HashSet<object> _objectsBeingSerialized = [];
    private readonly AutoIncrementIdDictionary<object> _writtenRefIds = new();
    private bool _hasWrittenRefId;
    private bool _hasWrittenRefFlag;
    private RefMetadata? _cachedRefMetadata;

    public void Reset()
    {
        ResetCurrent();

        _objectsBeingSerialized.Clear();
        _writtenRefIds.Clear();
    }

    public void Initialize(bool referenceTracking)
    {
        _referenceTracking = referenceTracking;
    }

    public void ResetCurrent()
    {
        _hasWrittenRefId = false;
        _hasWrittenRefFlag = false;
        _cachedRefMetadata = null;
    }

    public bool Write<TTarget>(ref SerializationWriterRef writerRef, in TTarget? value, out RefFlag writtenFlag)
    {
        if (value is null)
        {
            writtenFlag = RefFlag.Null;
            WriteRefFlag(ref writerRef, writtenFlag);
            return _hasWrittenRefFlag;
        }

        if (typeof(TTarget).IsValueType)
        {
            // Objects declared as ValueType are not possible to be referenced
            writtenFlag = RefFlag.NotNullValue;

            WriteRefFlag(ref writerRef, writtenFlag);
            return _hasWrittenRefFlag;
        }

        if (_referenceTracking)
        {
            if (_cachedRefMetadata is null)
            {
                var id = _writtenRefIds.GetOrAdd(value, out var exists);
                var flag = exists ? RefFlag.Ref : RefFlag.RefValue;
                _cachedRefMetadata = new RefMetadata(flag, id);
            }
            writtenFlag = _cachedRefMetadata.Value.RefFlag;
            var refId = _cachedRefMetadata.Value.RefId;
            WriteRefFlag(ref writerRef, writtenFlag);
            if (!_hasWrittenRefFlag)
            {
                return false;
            }
            if (writtenFlag is RefFlag.Ref)
            {
                // A referenceable object has been recorded
                if (_hasWrittenRefId)
                {
                    // This should not happen, but if it does, nothing will be written.
                    Debug.Fail($"Redundant call to {nameof(Write)}.");
                    return true;
                }
                _hasWrittenRefId = writerRef.Write7BitEncodedUInt32((uint)refId);
                return _hasWrittenRefId;
            }

            // A new referenceable object
            Debug.Assert(writtenFlag is RefFlag.RefValue);
            return true;
        }

        // Add the object to the set to mark it as being serialized.
        // When reference tracking is disabled, the same object can be serialized multiple times,
        // so we need a mechanism to detect circular dependencies.

        if (!_objectsBeingSerialized.Add(value))
        {
            ThrowBadSerializationInputException_CircularDependencyDetected();
        }

        writtenFlag = RefFlag.NotNullValue;
        WriteRefFlag(ref writerRef, writtenFlag);
        return _hasWrittenRefFlag;
    }

    private void WriteRefFlag(ref SerializationWriterRef writerRef, RefFlag flag)
    {
        if (_hasWrittenRefFlag)
        {
            return;
        }

        _hasWrittenRefFlag = writerRef.WriteUInt64((sbyte)flag);
    }

    public void HandleWriteValueCompleted<TValue>(in TValue value)
    {
        if (!_referenceTracking && !typeof(TValue).IsValueType)
        {
            // Remove the object from the set to mark it as completed.
            _objectsBeingSerialized.Remove(value!);
        }
    }

    [DoesNotReturn]
    private static void ThrowBadSerializationInputException_CircularDependencyDetected()
    {
        throw new BadSerializationInputException("Circular dependency detected.");
    }
}

internal sealed class ReferenceMetaDeserializer
{
    private readonly AutoIncrementIdDictionary<object> _readValues = new();
    private readonly AutoIncrementIdDictionary<IDeserializer> _inProgressDeserializers = new();
    private RefFlag? _refFlag;
    private int? _refId;

    public void Reset()
    {
        ResetCurrent();
        _readValues.Clear();
        _inProgressDeserializers.Clear();
    }

    public void ResetCurrent()
    {
        _refFlag = null;
        _refId = null;
    }

    private async ValueTask ReadRefFlag(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        if (_refFlag is not null)
        {
            return;
        }

        var sbyteResult = await reader.ReadInt8(isAsync, cancellationToken);
        if (!sbyteResult.IsSuccess)
        {
            return;
        }

        _refFlag = (RefFlag)sbyteResult.Value;
    }

    private async ValueTask ReadRefId(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        if (_refId is not null)
        {
            return;
        }
        var uintResult = await reader.Read7BitEncodedUint(isAsync, cancellationToken);

        if (!uintResult.IsSuccess)
        {
            return;
        }
        _refId = (int)uintResult.Value;
    }

    public async ValueTask<ReadValueResult<RefMetadata>> Read(
        DeserializationReader reader,
        bool isAsync,
        CancellationToken cancellationToken
    )
    {
        await ReadRefFlag(reader, isAsync, cancellationToken);
        switch (_refFlag)
        {
            case null:
                return ReadValueResult<RefMetadata>.Failed;
            case RefFlag.Null:
            case RefFlag.NotNullValue:
            case RefFlag.RefValue:
                return ReadValueResult<RefMetadata>.FromValue(new RefMetadata(_refFlag.Value));
            case RefFlag.Ref:
                await ReadRefId(reader, isAsync, cancellationToken);
                if (_refId is not { } refId)
                {
                    return ReadValueResult<RefMetadata>.Failed;
                }
                return ReadValueResult<RefMetadata>.FromValue(new RefMetadata(_refFlag.Value, refId));
            default:
                return ThrowHelper.ThrowUnreachableException<ReadValueResult<RefMetadata>>();
        }
    }

    public void GetReadValue(int refId, out object value)
    {
        if (_readValues.TryGetValue(refId, out value))
        {
            return;
        }

        if (!_inProgressDeserializers.TryGetValue(refId, out var deserializer))
        {
            ThrowBadDeserializationInputException_ReferencedObjectNotFound(refId);
        }

        value = deserializer.ReferenceableObject;
        _readValues[refId] = value;
    }

    public void AddReadValue(int refId, object value)
    {
        _readValues[refId] = value;
    }

    public void AddInProgressDeserializer(int refId, IDeserializer deserializer)
    {
        _inProgressDeserializers[refId] = deserializer;
    }

    [DoesNotReturn]
    private static void ThrowBadDeserializationInputException_ReferencedObjectNotFound(int refId)
    {
        throw new BadDeserializationInputException($"Referenced object not found for ref kind '{refId}'.");
    }
}
