using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.Threading;
using Fury.Collections;
using Fury.Helpers;
using Fury.Meta;
using Fury.Serialization;
using JetBrains.Annotations;

namespace Fury.Context;

internal readonly struct TypeRegistrationCreateInfo(Type targetType)
{
    public Type TargetType { get; } = targetType;
    public string? Namespace { get; init; } = null;
    public string? Name { get; init; } = null;
    public InternalTypeKind? TypeKind { get; init; }
    public int? Id { get; init; }
    public Func<ISerializer>? SerializerFactory { get; init; }
    public Func<IDeserializer>? DeserializerFactory { get; init; }

    internal bool CustomSerialization { get; init; } = false;
}

[MustDisposeResource]
public sealed class TypeRegistry : IDisposable
{
    private readonly TimeSpan _timeout;

    private readonly MetaStringStorage _metaStringStorage;

    private readonly Dictionary<Type, TypeRegistration> _typeToRegistrations = new();
    private readonly Dictionary<(TypeKind TypeKind, Type DeclaredType), TypeRegistration> _declaredTypeToRegistrations = new();
    private readonly Dictionary<(string? Namespace, string Name), TypeRegistration> _nameToRegistrations = new();
    private readonly Dictionary<int, TypeRegistration> _idToRegistrations = new();
    private int _idGenerator;

    private readonly ReaderWriterLockSlim _registrationLock = new(LockRecursionPolicy.SupportsRecursion);
    private readonly ReaderWriterLockSlim _declaredTypeLock = new(LockRecursionPolicy.SupportsRecursion);

    private readonly ITypeRegistrationProvider _registrationProvider;

    internal TypeRegistry(MetaStringStorage metaStringStorage, ITypeRegistrationProvider provider, TimeSpan timeout)
    {
        _metaStringStorage = metaStringStorage;
        _registrationProvider = provider;
        _timeout = timeout;

        Initialize();
    }

    public void Dispose()
    {
        _registrationLock.Dispose();
        _declaredTypeLock.Dispose();
    }

    private void Initialize()
    {
        RegisterPrimitive<bool>(InternalTypeKind.Bool, TypeKind.BoolArray);
        RegisterPrimitive<byte>(InternalTypeKind.Int8, TypeKind.Int8Array);
        RegisterPrimitive<sbyte>(InternalTypeKind.Int8, TypeKind.Int8Array);
        RegisterPrimitive<ushort>(InternalTypeKind.Int16, TypeKind.Int16Array);
        RegisterPrimitive<short>(InternalTypeKind.Int16, TypeKind.Int16Array);
        RegisterPrimitive<uint>(InternalTypeKind.Int32, TypeKind.Int32Array);
        RegisterPrimitive<int>(InternalTypeKind.Int32, TypeKind.Int32Array);
        RegisterPrimitive<ulong>(InternalTypeKind.Int64, TypeKind.Int64Array);
        RegisterPrimitive<long>(InternalTypeKind.Int64, TypeKind.Int64Array);
#if NET5_0_OR_GREATER
        // Technically, this is not a primitive type, but we register it here for convenience.
        RegisterPrimitive<Half>(InternalTypeKind.Float16, TypeKind.Float16Array);
#endif
        RegisterPrimitive<float>(InternalTypeKind.Float32, TypeKind.Float32Array);
        RegisterPrimitive<double>(InternalTypeKind.Float64, TypeKind.Float64Array);

        RegisterGeneral<string>(InternalTypeKind.String, () => new StringSerializer(), () => new StringDeserializer());
        RegisterGeneral<TimeSpan>(InternalTypeKind.Duration, () => new StandardTimeSpanSerializer(), () => new StandardTimeSpanDeserializer());
#if NET6_0_OR_GREATER
        RegisterGeneral<DateOnly>(InternalTypeKind.LocalDate, () => new StandardDateOnlySerializer(), () => new StandardDateOnlyDeserializer());
#endif
        RegisterGeneral<DateTime>(InternalTypeKind.Timestamp, () => StandardDateTimeSerializer.Instance, () => StandardDateTimeDeserializer.Instance);
        return;

        void RegisterPrimitive<T>(InternalTypeKind typeKind, TypeKind arrayTypeKind)
            where T : unmanaged
        {
            var createInfo = new TypeRegistrationCreateInfo(typeof(T))
            {
                TypeKind = typeKind,
                SerializerFactory = () => PrimitiveSerializer<T>.Instance,
                DeserializerFactory = () => PrimitiveDeserializer<T>.Instance,
            };
            var registration = Register(createInfo);

            Register(typeof(T[]), arrayTypeKind, () => new PrimitiveArraySerializer<T>(), () => new PrimitiveArrayDeserializer<T>());
#if NET8_0_OR_GREATER
            Register(typeof(List<T>), TypeKind.List, () => new PrimitiveListSerializer<T>(registration), () => new PrimitiveListDeserializer<T>(registration));
#else
            Register(typeof(List<T>), TypeKind.List, () => new ListSerializer<T>(registration), () => new ListDeserializer<T>(registration));
#endif
            RegisterCollections<T>(registration);
        }

        void RegisterGeneral<T>(InternalTypeKind typeKind, Func<ISerializer> serializerFactory, Func<IDeserializer> deserializerFactory)
        {
            var createInfo = new TypeRegistrationCreateInfo(typeof(T))
            {
                TypeKind = typeKind,
                SerializerFactory = serializerFactory,
                DeserializerFactory = deserializerFactory,
            };
            var registration = Register(createInfo);

            Register(typeof(T[]), TypeKind.List, () => new ArraySerializer<T>(registration), () => new ArrayDeserializer<T>(registration));
            Register(typeof(List<T>), TypeKind.List, () => new ListSerializer<T>(registration), () => new ListDeserializer<T>(registration));
            RegisterCollections<T>(registration);
        }

        void RegisterCollections<T>(TypeRegistration elementRegistration)
        {
            Register(
                typeof(HashSet<T>),
                TypeKind.Set,
                () => new HashSetSerializer<T>(elementRegistration),
                () => new HashSetDeserializer<T>(elementRegistration)
            );
        }
    }

    #region Public Register Methods

    public TypeRegistration Register(Type targetType, Func<ISerializer>? serializerFactory, Func<IDeserializer>? deserializerFactory)
    {
        // We need lock here to ensure that the auto-generated id is unique.
        if (!_registrationLock.TryEnterReadLock(_timeout))
        {
            ThrowTimeoutException_RegisterTypeTimeout();
        }

        try
        {
            while (_idToRegistrations.ContainsKey(_idGenerator))
            {
                _idGenerator++;
            }
            var createInfo = new TypeRegistrationCreateInfo(targetType)
            {
                Id = _idGenerator++,
                SerializerFactory = serializerFactory,
                DeserializerFactory = deserializerFactory,
            };
            return Register(createInfo);
        }
        finally
        {
            _registrationLock.ExitReadLock();
        }
    }

    public TypeRegistration Register(
        Type targetType,
        string? @namespace,
        string name,
        Func<ISerializer> serializerFactory,
        Func<IDeserializer> deserializerFactory
    )
    {
        var createInfo = new TypeRegistrationCreateInfo(targetType)
        {
            Namespace = @namespace,
            Name = name,
            SerializerFactory = serializerFactory,
            DeserializerFactory = deserializerFactory,
        };
        return Register(createInfo);
    }

    public TypeRegistration Register(Type targetType, TypeKind targetTypeKind, Func<ISerializer> serializerFactory, Func<IDeserializer> deserializerFactory)
    {
        var createInfo = new TypeRegistrationCreateInfo(targetType)
        {
            TypeKind = targetTypeKind.ToInternal(),
            SerializerFactory = serializerFactory,
            DeserializerFactory = deserializerFactory,
        };
        return Register(createInfo);
    }

    public TypeRegistration Register(Type targetType, int id, Func<ISerializer> serializerFactory, Func<IDeserializer> deserializerFactory)
    {
        var createInfo = new TypeRegistrationCreateInfo(targetType)
        {
            Id = id,
            SerializerFactory = serializerFactory,
            DeserializerFactory = deserializerFactory,
        };
        return Register(createInfo);
    }

    public void Register(Type declaredType, TypeRegistration registration)
    {
        if (!_declaredTypeLock.TryEnterWriteLock(_timeout))
        {
            ThrowTimeoutException_RegisterTypeTimeout();
        }

        try
        {
            if (registration.TypeKind is not { } typeKind)
            {
                ThrowArgumentException_NoTypeKindRegistered(nameof(registration), registration);
                return;
            }
            if (_declaredTypeToRegistrations.TryGetValue((typeKind, declaredType), out var existingRegistration))
            {
                ThrowArgumentException_DuplicateTypeKindDeclaredType($"{nameof(declaredType)}, {nameof(registration)}", declaredType, existingRegistration);
            }

            _declaredTypeToRegistrations.Add((typeKind, declaredType), registration);
        }
        finally
        {
            _declaredTypeLock.ExitWriteLock();
        }
    }

    #endregion

    private TypeRegistration Register(TypeRegistrationCreateInfo createInfo)
    {
        if (!_registrationLock.TryEnterWriteLock(_timeout))
        {
            ThrowTimeoutException_RegisterTypeTimeout();
        }

        try
        {
            var registration = _typeToRegistrations.GetOrAdd(
                createInfo.TargetType,
                static (_, tuple) => tuple.Register.CreateTypeRegistration(tuple.CreateInfo),
                (Register: this, CreateInfo: createInfo),
                out var exists
            );

            if (exists)
            {
                ThrowInvalidOperationException_DuplicateRegistration(registration);
            }

            if (createInfo.TypeKind is not null)
            {
                Register(registration.TargetType, registration);
            }

            if (createInfo.Id is { } id)
            {
                var registered = _idToRegistrations.GetOrAdd(id, registration, out exists);
                if (exists)
                {
                    Debug.Assert(registered.Id == createInfo.Id);
                    _typeToRegistrations.Remove(createInfo.TargetType);
                    ThrowInvalidOperationException_DuplicateTypeId(registered);
                }
            }
            if (createInfo.Name is not null)
            {
                var registered = _nameToRegistrations.GetOrAdd((createInfo.Namespace, createInfo.Name), registration, out exists);
                if (exists)
                {
                    Debug.Assert(registered.Name == createInfo.Name);
                    Debug.Assert(registered.Namespace == createInfo.Namespace);
                    _typeToRegistrations.Remove(createInfo.TargetType);
                    ThrowInvalidOperationException_DuplicateTypeName(registered);
                }
            }

            return registration;
        }
        finally
        {
            _registrationLock.ExitWriteLock();
        }
    }

    private TypeRegistration CreateTypeRegistration(in TypeRegistrationCreateInfo createInfo)
    {
        var targetType = createInfo.TargetType;
        var serializerFactory = createInfo.SerializerFactory;
        var deserializerFactory = createInfo.DeserializerFactory;

        if (serializerFactory is null && deserializerFactory is null)
        {
            ThrowInvalidOperationException_NoSerializationProviderSupport(targetType);
        }

        MetaString? namespaceMetaString = null;
        MetaString? nameMetaString = null;
        if (createInfo.Namespace is { } ns)
        {
            namespaceMetaString = _metaStringStorage.GetMetaString(ns, MetaStringStorage.EncodingPolicy.Namespace);
        }

        var isNamed = false;
        if (createInfo.Name is { } name)
        {
            nameMetaString = _metaStringStorage.GetMetaString(name, MetaStringStorage.EncodingPolicy.Name);
            isNamed = true;
        }
        if (createInfo.TypeKind is not { } typeKind)
        {
            // Other prefixes, such as "Polymorphic" or "Compatible", depend on configuration and object being serialized.
            // We can't determine them here, so we'll just use the "Struct" and "Ext" and handle them in the serialization code.
            if (targetType.IsEnum)
            {
                typeKind = isNamed ? InternalTypeKind.NamedEnum : InternalTypeKind.Enum;
            }
            else if (createInfo.CustomSerialization)
            {
                typeKind = isNamed ? InternalTypeKind.NamedExt : InternalTypeKind.Ext;
            }
            else
            {
                typeKind = isNamed ? InternalTypeKind.NamedStruct : InternalTypeKind.Struct;
            }
        }
        var newRegistration = new TypeRegistration(
            targetType,
            typeKind,
            namespaceMetaString,
            nameMetaString,
            createInfo.Id,
            serializerFactory,
            deserializerFactory
        );

        return newRegistration;
    }

    private static void ThrowInvalidOperationException_NoSerializationProviderSupport(Type targetType)
    {
        throw new InvalidOperationException($"Type `{targetType}` is not supported by either built-in or custom serialization provider.");
    }

    public TypeRegistration GetTypeRegistration(Type type)
    {
        if (!_registrationLock.TryEnterUpgradeableReadLock(_timeout))
        {
            ThrowTimeoutException_RegisterTypeTimeout();
        }

        try
        {
            if (!_typeToRegistrations.TryGetValue(type, out var registration))
            {
                registration = _registrationProvider.RegisterType(this, type);
            }
            return registration;
        }
        finally
        {
            _registrationLock.ExitUpgradeableReadLock();
        }
    }

    public TypeRegistration GetTypeRegistration(string ns, string name)
    {
        if (!_registrationLock.TryEnterUpgradeableReadLock(_timeout))
        {
            ThrowTimeoutException_RegisterTypeTimeout();
        }

        try
        {
            if (!_nameToRegistrations.TryGetValue((ns, name), out var registration))
            {
                registration = _registrationProvider.GetTypeRegistration(this, ns, name);
            }
            return registration;
        }
        finally
        {
            _registrationLock.ExitUpgradeableReadLock();
        }
    }

    public TypeRegistration GetTypeRegistration(TypeKind typeKind, Type declaredType)
    {
        if (!_registrationLock.TryEnterUpgradeableReadLock(_timeout))
        {
            ThrowTimeoutException_RegisterTypeTimeout();
        }

        try
        {
            if (!_declaredTypeToRegistrations.TryGetValue((typeKind, declaredType), out var registration))
            {
                registration = _registrationProvider.GetTypeRegistration(this, typeKind, declaredType);
            }
            return registration;
        }
        finally
        {
            _registrationLock.ExitUpgradeableReadLock();
        }
    }

    public TypeRegistration GetTypeRegistration(int id)
    {
        if (!_registrationLock.TryEnterUpgradeableReadLock(_timeout))
        {
            ThrowTimeoutException_RegisterTypeTimeout();
        }

        try
        {
            if (!_idToRegistrations.TryGetValue(id, out var registration))
            {
                registration = _registrationProvider.GetTypeRegistration(this, id);
            }

            return registration;
        }
        finally
        {
            _registrationLock.ExitUpgradeableReadLock();
        }
    }

    [DoesNotReturn]
    private static void ThrowTimeoutException_RegisterTypeTimeout()
    {
        throw new TimeoutException("It took too long to register the type.");
    }

    [DoesNotReturn]
    private static void ThrowInvalidOperationException_DuplicateRegistration(TypeRegistration registration)
    {
        throw new InvalidOperationException($"Type `{registration.TargetType}` is already registered.");
    }

    [DoesNotReturn]
    private static void ThrowInvalidOperationException_DuplicateTypeName(TypeRegistration registration)
    {
        var fullName = StringHelper.ToFullName(registration.Namespace, registration.Name);
        throw new InvalidOperationException($"Type name `{fullName}` is already registered for type `{registration.TargetType}`.");
    }

    [DoesNotReturn]
    private static void ThrowInvalidOperationException_DuplicateTypeId(TypeRegistration existent)
    {
        throw new InvalidOperationException($"Type id `{existent.Id}` is already registered for type `{existent.TargetType}`.");
    }

    [DoesNotReturn]
    private static void ThrowArgumentException_DuplicateTypeKindDeclaredType(
        [InvokerParameterName] string parameterName,
        Type declaredType,
        TypeRegistration registration
    )
    {
        var typeKind = registration.TypeKind;
        throw new ArgumentException($"Declared type `{declaredType}` and type kind `{typeKind}` are already registered.", parameterName);
    }

    [DoesNotReturn]
    private static void ThrowArgumentException_NoTypeKindRegistered([InvokerParameterName] string parameterName, TypeRegistration registration)
    {
        throw new ArgumentException($"Type `{registration.TargetType}` was not registered with a {nameof(TypeKind)}", parameterName);
    }
}
