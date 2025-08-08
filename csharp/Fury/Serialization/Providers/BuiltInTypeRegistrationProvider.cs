using System;
using System.Diagnostics.CodeAnalysis;
using Fury.Context;
using Fury.Meta;

namespace Fury.Serialization;

public sealed class BuiltInTypeRegistrationProvider : ITypeRegistrationProvider
{
    public TypeRegistration RegisterType(TypeRegistry registry, Type targetType)
    {
        if (!TryRegisterType(registry, targetType, out var registration))
        {
            ThrowNotSupportedException_TypeNotSupported(targetType);
        }

        return registration;
    }

    public TypeRegistration GetTypeRegistration(TypeRegistry registry, TypeKind targetTypeKind, Type declaredType)
    {
        if (!TryGetTypeRegistration(registry, targetTypeKind, declaredType, out var registration))
        {
            ThrowNotSupportedException_DeclaredTypeNotSupported(declaredType, targetTypeKind);
        }

        return registration;
    }

    public TypeRegistration GetTypeRegistration(TypeRegistry registry, int id)
    {
        ThrowNotSupportedException_IdNotSupported(id);
        return null;
    }

    public TypeRegistration GetTypeRegistration(TypeRegistry registry, string? @namespace, string name)
    {
        if (!TryGetTypeRegistration(registry, @namespace, name, out var registration))
        {
            ThrowNotSupportedException_NameNotSupported(@namespace, name);
        }

        return registration;
    }

    public static bool TryRegisterType(TypeRegistry registry, Type targetType, [NotNullWhen(true)] out TypeRegistration? registration)
    {
        if (EnumTypeRegistrationProvider.TryRegisterType(registry, targetType, out registration))
        {
            return true;
        }

        if (ArrayTypeRegistrationProvider.TryRegisterType(registry, targetType, out registration))
        {
            return true;
        }

        if (CollectionTypeRegistrationProvider.TryRegisterType(registry, targetType, out registration))
        {
            return true;
        }

        return false;
    }

    public static bool TryGetTypeRegistration(
        TypeRegistry registry,
        TypeKind targetTypeKind,
        Type declaredType,
        [NotNullWhen(true)] out TypeRegistration? registration
    )
    {
        if (CollectionTypeRegistrationProvider.TryGetTypeRegistration(registry, targetTypeKind, declaredType, out registration))
        {
            return true;
        }

        return false;
    }

    public static bool TryGetTypeRegistration(TypeRegistry registry, string? @namespace, string name, [NotNullWhen(true)] out TypeRegistration? registration)
    {
        if (EnumTypeRegistrationProvider.TryGetTypeRegistration(registry, @namespace, name, out registration))
        {
            return true;
        }

        return false;
    }

    [DoesNotReturn]
    private static void ThrowNotSupportedException_TypeNotSupported(Type targetType)
    {
        throw new NotSupportedException($"Type `{targetType}` is not supported by built-in type registration provider.");
    }

    [DoesNotReturn]
    private static void ThrowNotSupportedException_DeclaredTypeNotSupported(Type declaredType, TypeKind typeKind)
    {
        throw new NotSupportedException(
            $"The exact type can not be determined with declared type `{declaredType}` and type kind `{typeKind}` by built-in type registration provider."
        );
    }

    [DoesNotReturn]
    private static void ThrowNotSupportedException_IdNotSupported(int id)
    {
        throw new NotSupportedException($"The type whose id is '{id}' is not supported by built-in type registration provider.");
    }

    [DoesNotReturn]
    private static void ThrowNotSupportedException_NameNotSupported(string? @namespace, string name)
    {
        throw new NotSupportedException(
            $"The type whose namespace is `{@namespace}` and name is `{name}` is not supported by built-in type registration provider."
        );
    }
}
