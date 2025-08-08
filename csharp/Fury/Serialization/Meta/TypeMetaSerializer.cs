using System;
using System.Threading;
using System.Threading.Tasks;
using Fury.Collections;
using Fury.Context;
using Fury.Helpers;
using Fury.Meta;

namespace Fury.Serialization.Meta;

internal sealed class TypeMetaSerializer
{
    private readonly MetaStringSerializer _nameMetaStringSerializer = new();
    private readonly MetaStringSerializer _namespaceMetaStringSerializer = new();

    private bool _hasWrittenTypeKind;

    public void Reset()
    {
        _hasWrittenTypeKind = false;
        _nameMetaStringSerializer.Reset();
        _namespaceMetaStringSerializer.Reset();
    }

    public void Initialize(AutoIncrementIdDictionary<MetaString> metaStringContext)
    {
        _nameMetaStringSerializer.Initialize(metaStringContext);
        _namespaceMetaStringSerializer.Initialize(metaStringContext);
    }

    public bool Write(ref SerializationWriterRef writerRef, TypeRegistration registration)
    {
        var typeKind = registration.InternalTypeKind;
        WriteTypeKind(ref writerRef, typeKind);
        if (!_hasWrittenTypeKind)
        {
            return false;
        }
        if (typeKind.IsNamed())
        {
            if (!_namespaceMetaStringSerializer.Write(ref writerRef, registration.NamespaceMetaString!))
            {
                return false;
            }

            if (!_nameMetaStringSerializer.Write(ref writerRef, registration.NameMetaString!))
            {
                return false;
            }
        }
        return true;
    }

    private void WriteTypeKind(ref SerializationWriterRef writerRef, InternalTypeKind typeKind)
    {
        if (_hasWrittenTypeKind)
        {
            return;
        }

        _hasWrittenTypeKind = writerRef.Write7BitEncodedUInt32((uint)typeKind);
    }
}

internal sealed class TypeMetaDeserializer
{
    private MetaStringDeserializer _nameMetaStringDeserializer = new(MetaStringStorage.EncodingPolicy.Name);
    private MetaStringDeserializer _namespaceMetaStringDeserializer = new(MetaStringStorage.EncodingPolicy.Namespace);

    private TypeRegistry _registry = null!;

    private TypeMetadata? _typeMetadata;
    private MetaString? _namespaceMetaString;
    private MetaString? _nameMetaString;
    private TypeRegistration? _registration;

    public void Reset()
    {
        ResetCurrent();
    }

    public void Initialize(TypeRegistry registry, MetaStringStorage metaStringStorage, AutoIncrementIdDictionary<MetaString> metaStringContext)
    {
        _registry = registry;
        _nameMetaStringDeserializer.Initialize(metaStringStorage, metaStringContext);
        _namespaceMetaStringDeserializer.Initialize(metaStringStorage, metaStringContext);
        Reset();
    }

    public void ResetCurrent()
    {
        _typeMetadata = null;
        _namespaceMetaString = null;
        _nameMetaString = null;
        _registration = null;
        _nameMetaStringDeserializer.Reset();
        _namespaceMetaStringDeserializer.Reset();
    }

    public async ValueTask<ReadValueResult<TypeRegistration>> Read(
        DeserializationReader reader,
        Type declaredType,
        TypeRegistration? registrationHint,
        bool isAsync,
        CancellationToken cancellationToken
    )
    {
        await ReadTypeMeta(reader, isAsync, cancellationToken);
        if (_typeMetadata is not var (internalTypeKind, typeId))
        {
            return ReadValueResult<TypeRegistration>.Failed;
        }

        if (internalTypeKind.TryToBePublic(out var typeKind))
        {
            if (registrationHint is not null && registrationHint.TypeKind == typeKind && declaredType.IsAssignableFrom(registrationHint.TargetType))
            {
                _registration = registrationHint;
            }
            else
            {
                _registration = _registry.GetTypeRegistration(typeKind, declaredType);
            }
        }
        else
        {
            if (internalTypeKind.IsNamed())
            {
                await ReadNamespaceMetaString(reader, isAsync, cancellationToken);
                if (_namespaceMetaString is not { } namespaceMetaString)
                {
                    return ReadValueResult<TypeRegistration>.Failed;
                }
                await ReadNameMetaString(reader, isAsync, cancellationToken);
                if (_nameMetaString is not { } nameMetaString)
                {
                    return ReadValueResult<TypeRegistration>.Failed;
                }

                if (
                    registrationHint is not null
                    && registrationHint.InternalTypeKind == internalTypeKind
                    && StringHelper.AreStringsEqualOrEmpty(registrationHint.Name, nameMetaString.Value)
                    && StringHelper.AreStringsEqualOrEmpty(registrationHint.Namespace, namespaceMetaString.Value)
                    && declaredType.IsAssignableFrom(registrationHint.TargetType)
                )
                {
                    _registration = registrationHint;
                }
                else
                {
                    _registration = _registry.GetTypeRegistration(namespaceMetaString.Value, nameMetaString.Value);
                }
            }
            else
            {
                if (
                    registrationHint is not null
                    && registrationHint.InternalTypeKind == internalTypeKind
                    && registrationHint.Id == typeId
                    && declaredType.IsAssignableFrom(registrationHint.TargetType)
                )
                {
                    _registration = registrationHint;
                }
                else
                {
                    _registration = _registry.GetTypeRegistration(typeId);
                }
            }
        }

        return ReadValueResult<TypeRegistration>.FromValue(_registration);
    }

    private async ValueTask ReadTypeMeta(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        if (_typeMetadata is not null)
        {
            return;
        }

        var varIntResult = await reader.Read7BitEncodedUint(isAsync, cancellationToken);
        if (!varIntResult.IsSuccess)
        {
            return;
        }

        _typeMetadata = TypeMetadata.FromUint(varIntResult.Value);
    }

    private async ValueTask ReadNamespaceMetaString(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        if (_namespaceMetaString is not null)
        {
            return;
        }

        var metaStringResult = await _namespaceMetaStringDeserializer.Read(reader, isAsync, cancellationToken);
        if (metaStringResult.IsSuccess)
        {
            _namespaceMetaString = metaStringResult.Value;
        }
    }

    private async ValueTask ReadNameMetaString(DeserializationReader reader, bool isAsync, CancellationToken cancellationToken)
    {
        if (_nameMetaString is not null)
        {
            return;
        }

        var metaStringResult = await _nameMetaStringDeserializer.Read(reader, isAsync, cancellationToken);
        if (metaStringResult.IsSuccess)
        {
            _nameMetaString = metaStringResult.Value;
        }
    }
}
